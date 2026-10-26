package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream4.aggregation.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class CanonicalSourceLoaderTest {
    companion object { private val counter = AtomicInteger() }
    private fun binding(track: String = "None", language: String = "fr") =
        EpisodeSourceBinding("Fixture${counter.incrementAndGet()}", "https://fixture.invalid", "/show", "opaque", track, language)
    @Suppress("DEPRECATION")
    private fun link(label: String, quality: Int = 1080, url: String = "https://video.invalid/$label", headers: Map<String,String> = emptyMap()) =
        ExtractorLink("Fixture", label, url, "https://ref.invalid", quality, headers, "verification-data", ExtractorLinkType.VIDEO)

    @Test fun unknownAudioOrDubbedNonFrenchIsNeverAdvertisedAsFrenchAndUnknownQualityIsNot480p() {
        assertEquals(SourceVersion.Unknown, PlaybackSources.version("host", binding("Dubbed", "en")))
        assertEquals(SourceVersion.Unknown, PlaybackSources.version("VO"))
        assertEquals(SourceVersion.VF, PlaybackSources.version("Host [VF]"))
        assertEquals(SourceVersion.VOST, PlaybackSources.version("Host VOSTFR"))
        assertEquals(SourceVersion.Unknown, PlaybackSources.version("Multi VF/VOSTFR"))
        val unknown = PlaybackSources.copy(link("Host", Qualities.Unknown.value), binding(), 0)
        assertEquals(0, PlaybackSources.quality(unknown))
        assertEquals(SourceVersion.Unknown, PlaybackSources.version(unknown))
    }


    @Test fun allBindingsAreQueriedInParallelWithinLimitAndTheFastestOneDoesNotWinTheSelection() = runBlocking {
        val active = AtomicInteger(); val peak = AtomicInteger()
        val bindings = listOf(binding("Dubbed"), binding("Subbed"), binding(), binding())
        val result = CanonicalSourceLoader(2, 2000, 4000).load(bindings, true, false) { b, _, _, callback ->
            val count = active.incrementAndGet(); peak.accumulateAndGet(count, ::maxOf)
            try { delay(if (b == bindings[0]) 50 else 5); callback(link("VF", if (b == bindings[0]) 2160 else 720)); true }
            finally { active.decrementAndGet() }
        }
        assertEquals(2, peak.get()); assertEquals(0, active.get())
        assertEquals(4, result.outcomes.size)
        // Identical contexts collapse; the best reported resolution survives.
        assertEquals(1, result.links.size)
        assertEquals(2160, result.links.single().quality)
    }


    @Test fun deduplicationKeepsDifferentVersionsAndAuthenticationContextsAndDoesNotMutateTheExtension() = runBlocking {
        val bindings = listOf(binding("Dubbed"), binding("Subbed"), binding("Dubbed"))
        val originals = mutableListOf<ExtractorLink>()
        val result = CanonicalSourceLoader().load(bindings, true, false) { b, _, _, callback ->
            val original = link("Host", 1080, "https://same.invalid/stream?signature=keep", if (b == bindings[2]) mapOf("Cookie" to "other") else mapOf("Cookie" to "first"))
            synchronized(originals) { originals += original }
            callback(original); true
        }
        assertEquals(3, result.links.size)
        assertEquals(setOf(SourceVersion.VF, SourceVersion.VOST), result.links.map(PlaybackSources::version).toSet())
        assertTrue(originals.all { it.name == "Host" })
        assertTrue(result.links.all { it.referer == "https://ref.invalid" && it.extractorData == "verification-data" && it.url.endsWith("signature=keep") })
        assertTrue(result.links.any { it.headers["Cookie"] == "other" })
    }


    @Test fun failuresAndTimeoutsKeepOtherSourcesAndIgnoreLateCallbacks() = runBlocking {
        val bindings = listOf(binding(), binding(), binding())
        var late: ((ExtractorLink) -> Unit)? = null
        val result = CanonicalSourceLoader(3, 50, 150).load(bindings, true, false) { b, _, _, callback ->
            when (b) {
                bindings[0] -> { callback(link("VF", 720)); error("Fixture failure") }
                bindings[1] -> { late = callback; delay(Long.MAX_VALUE); false }
                else -> { callback(link("VOSTFR", 1080)); true }
            }
        }
        assertEquals(setOf("Failure", "Timeout", "Success"), result.outcomes.map { it.status }.toSet())
        assertEquals(2, result.links.size)
        late!!.invoke(link("VF", 2160))
        assertEquals(2, result.links.size)
    }


    @Test fun cacheIsScopedToPayloadAndCastingAndCanBeClearedWithoutCachingFailures() = runBlocking {
        val b = binding("Dubbed")
        var calls = 0
        val fetch: suspend (EpisodeSourceBinding, Boolean, (SubtitleFile) -> Unit, (ExtractorLink) -> Unit) -> Boolean? = { _, _, _, emit ->
            calls++; emit(link("VF")); true
        }
        val loader = CanonicalSourceLoader()
        loader.load(listOf(b), false, false, fetch = fetch)
        val cached = loader.load(listOf(b), false, false, fetch = fetch)
        assertEquals("Cached", cached.outcomes.single().status)
        assertEquals(1, calls)
        loader.load(listOf(b.copy(data = "other-payload")), false, false, fetch = fetch)
        loader.load(listOf(b), false, true, fetch = fetch)
        loader.load(listOf(b), true, false, fetch = fetch)
        assertEquals(4, calls)
        val failed = binding()
        repeat(2) { loader.load(listOf(failed), false, false) { _, _, _, _ -> calls++; false } }
        assertEquals(6, calls)
        val partial = binding()
        repeat(2) {
            val result = loader.load(listOf(partial), false, false) { _, _, _, emit -> calls++; emit(link("VF")); false }
            assertEquals("Partial", result.outcomes.single().status)
            assertEquals(1, result.links.size)
        }
        assertEquals(8, calls)
    }


    @Test fun cancellationStopsQueuedRequestsAndCannotPublishACompletedSnapshot() = runBlocking {
        val started = CompletableDeferred<Unit>(); val active = AtomicInteger()
        var published = false
        val job = launch {
            CanonicalSourceLoader().load((1..8).map { binding() }, true, false) { _, _, _, _ ->
                active.incrementAndGet(); started.complete(Unit)
                try { delay(Long.MAX_VALUE); true } finally { active.decrementAndGet() }
            }
            published = true
        }
        started.await(); job.cancelAndJoin()
        assertEquals(0, active.get()); assertFalse(published)
    }


    @Test fun firstDeclaredVfAndVostBindingsAreNotStarvedBehindUnknownProviders() = runBlocking {
        val unknown = (1..5).map { binding() }
        val vf = binding("Dubbed"); val vost = binding("Subbed")
        val started = java.util.Collections.synchronizedList(mutableListOf<String>())
        CanonicalSourceLoader(3, 2000, 400).load(unknown + vf + vost, true, false) { b, _, _, _ ->
            started += b.apiName; delay(Long.MAX_VALUE); true
        }
        assertTrue(vf.apiName in started && vost.apiName in started)
        assertTrue(started.size <= 3)
    }


    @Test fun playlistSegmentsExternalAudioAndHeadersSurviveAppCopies() = runBlocking {
        val audio = newAudioFile("https://audio.invalid/file") { headers = mapOf("Cookie" to "audio-cookie") }
        val original = ExtractorLinkPlayList("Fixture", "VF", listOf(PlayListItem("https://video.invalid/part", 1_000_000)),
            "https://ref.invalid", 1080, mapOf("Cookie" to "video-cookie"), type = ExtractorLinkType.VIDEO,
            audioTracks = listOf(audio))
        val copy = PlaybackSources.copy(original, binding("Dubbed"), 0) as ExtractorLinkPlayList
        assertNotSame(original, copy)
        assertEquals(original.playlist, copy.playlist)
        assertEquals(original.audioTracks, copy.audioTracks)
        assertEquals("audio-cookie", copy.audioTracks.single().headers!!["Cookie"])
        copy.headers = emptyMap(); copy.referer = "changed"
        assertEquals("video-cookie", original.headers["Cookie"])
        assertEquals("https://ref.invalid", original.referer)
    }


    @Test fun subtitlesAreAggregatedWithoutCollapsingDifferentCredentials() = runBlocking {
        val bindings = listOf(binding("Dubbed"), binding("Subbed"))
        val result = CanonicalSourceLoader().load(bindings, true, false) { b, _, subtitle, _ ->
            subtitle(newSubtitleFile("French", "https://subs.invalid/fr.srt") { headers = mapOf("Cookie" to b.apiName) })
            true
        }
        assertEquals(2, result.subtitles.size)
        assertEquals(2, result.subtitles.map { it.headers }.distinct().size)
        assertTrue(result.subtitles.all { it.lang == "French" })
    }


    @Test fun sourcesArePublishedProgressivelyAndReadinessDoesNotWaitForSlowProviders() = runBlocking {
        val fast = binding("Dubbed"); val slow = binding()
        val published = java.util.Collections.synchronizedList(mutableListOf<String>())
        var readyAt = -1L
        val started = System.nanoTime()
        val result = CanonicalSourceLoader(policy = PlaybackPolicy(graceMs = 100)).load(listOf(fast, slow), true, false,
            onSource = { published += it.link.name },
            onReady = { readyAt = (System.nanoTime() - started) / 1_000_000 }) { b, _, _, callback ->
            if (b == fast) { callback(link("VF", 720)); true } else { delay(1_500); callback(link("Other", 2160)); true }
        }
        assertTrue("ready after ${readyAt}ms", readyAt in 0..1_200)
        assertEquals(2, published.size)
        assertEquals(2, result.links.size)
        assertNotNull(result.readyAfterMs)
    }

    @Test fun anExcellentSourceIsReadyImmediatelyAndNothingIsReadyWithoutSources() = runBlocking {
        var ready = false
        CanonicalSourceLoader(policy = PlaybackPolicy(graceMs = 60_000)).load(listOf(binding("Dubbed"), binding()), true, false,
            onReady = { ready = true }) { b, _, _, callback ->
            if (b.track == "Dubbed") { callback(link("VF", 1080)); true } else { delay(300); true }
        }
        assertTrue(ready)
        var none = false
        CanonicalSourceLoader().load(listOf(binding()), true, false, onReady = { none = true }) { _, _, _, _ -> false }
        assertFalse(none)
    }

    @Test fun reliableFastProvidersStartBeforeSlowUnreliableOnes() = runBlocking {
        ProviderHealth.reset()
        val slow = binding(); val fast = binding()
        repeat(5) { ProviderHealth.record(slow.apiName, success = false, timeout = true, elapsedMs = 15_000) }
        repeat(5) { ProviderHealth.record(fast.apiName, success = true, timeout = false, elapsedMs = 800) }
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        CanonicalSourceLoader(concurrency = 1).load(listOf(slow, fast), true, false) { b, _, _, _ -> order += b.apiName; true }
        assertEquals(listOf(fast.apiName, slow.apiName), order)
        ProviderHealth.reset()
    }

    @Test fun rankingPrefersLanguageUnlessTheQualityGapIsVeryLarge() {
        fun s(version: SourceVersion, quality: Int) = SourceRanking.score(version, quality)
        assertTrue(s(SourceVersion.Unknown, 1080) > s(SourceVersion.VF, 480))
        assertTrue(s(SourceVersion.Unknown, 1080) > s(SourceVersion.VOST, 480))
        assertTrue(s(SourceVersion.VF, 480) > s(SourceVersion.Unknown, 720))
        assertTrue(s(SourceVersion.VF, 720) > s(SourceVersion.Unknown, 1080))
        assertTrue(s(SourceVersion.VF, 1080) > s(SourceVersion.VOST, 2160))
        assertTrue(s(SourceVersion.VF, 480) > s(SourceVersion.VOST, 720))
        assertTrue(s(SourceVersion.Unknown, 2160) > s(SourceVersion.VF, 720))
        // Unreported quality is unverified, not presumed to be 480p.
        assertTrue(s(SourceVersion.VF, 0) > s(SourceVersion.VF, 480))
        assertTrue(s(SourceVersion.VF, 720) > s(SourceVersion.VF, 0))
        val policy = PlaybackPolicy()
        assertTrue(policy.shouldUpgrade(s(SourceVersion.Unknown, 720), s(SourceVersion.VF, 1080)))
        assertFalse(policy.shouldUpgrade(s(SourceVersion.VF, 1080), s(SourceVersion.VF, 1440)))
    }

    @Test fun masterPlaylistsRevealResolutionAndFrenchRenditionsOnly() {
        val master = """#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",LANGUAGE="fr",NAME="Français",DEFAULT=YES
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",LANGUAGE="en",NAME="English"
#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=1280x720,AUDIO="a"
low.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,AUDIO="a"
high.m3u8"""
        val probe = StreamProbe.parse(master)!!
        assertEquals(1080, probe.height)
        assertEquals(SourceVersion.VF, probe.version)
        val vost = StreamProbe.parse("#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,LANGUAGE=\"en\",NAME=\"English\"\n" +
            "#EXT-X-MEDIA:TYPE=SUBTITLES,LANGUAGE=\"fr\",NAME=\"Français\"\n#EXT-X-STREAM-INF:RESOLUTION=854x480\nv.m3u8")!!
        assertEquals(SourceVersion.VOST, vost.version)
        assertNull(StreamProbe.parse("#EXTM3U\n#EXTINF:10,\nseg1.ts"))
        assertNull(StreamProbe.parse("<html>not a playlist</html>"))
        assertNull(StreamProbe.parse("#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=640x360\nv.m3u8")!!.version)
    }

    @Test fun probedFactsOnlyFillWhatTheExtensionLeftUnknown() {
        val probe = StreamProbe(1080, setOf("fr"), emptySet())
        val unknown = PlaybackSources.copy(link("Host", Qualities.Unknown.value), binding(), 0, probe)
        assertEquals(1080, unknown.quality)
        assertEquals(SourceVersion.VF, PlaybackSources.version(unknown))
        assertTrue(PlaybackSources.metadata(unknown)!!.probed)
        val labelled = PlaybackSources.copy(link("Host VOSTFR", 720), binding(), 0, probe)
        assertEquals(720, labelled.quality)
        assertEquals(SourceVersion.VOST, PlaybackSources.version(labelled))
    }
}
