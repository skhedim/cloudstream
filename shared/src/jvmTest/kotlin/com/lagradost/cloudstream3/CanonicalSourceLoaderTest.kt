package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream4.aggregation.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class CanonicalSourceLoaderTest {
    // Fixtures are French providers seen by a French-speaking viewer, whatever the build machine's locale.
    init { com.lagradost.cloudstream4.aggregation.AggregationPlatform.metadataLanguage = { "fr-FR" } }
    companion object { private val counter = AtomicInteger() }
    private fun binding(track: String = "None", language: String = "fr") =
        EpisodeSourceBinding("Fixture${counter.incrementAndGet()}", "https://fixture.invalid", "/show", "opaque", track, language)
    /** Network-free inspections: `dur=N` measures an HLS duration, `dead` answers 404, else reachable. */
    private val fakeInspect: suspend (ExtractorLink) -> StreamInspection = { link ->
        when {
            "dead" in link.url -> StreamInspection(false, 404)
            "dur=" in link.url -> StreamInspection(true, 200, durationSeconds = link.url.substringAfter("dur=").substringBefore('&').toDouble())
            else -> StreamInspection(true, 200)
        }
    }
    private fun loader(concurrency: Int = 6, binding: Long = 15_000, total: Long = 30_000, policy: PlaybackPolicy = PlaybackPolicy()) =
        CanonicalSourceLoader(concurrency, binding, total, policy = policy, inspect = fakeInspect)

    @Suppress("DEPRECATION")
    private fun link(label: String, quality: Int = 1080, url: String = "https://video.invalid/$label", headers: Map<String,String> = emptyMap()) =
        ExtractorLink("Fixture", label, url, "https://ref.invalid", quality, headers, "verification-data", ExtractorLinkType.VIDEO)

    @Test fun unknownAudioOrDubbedNonFrenchIsNeverAdvertisedAsFrenchAndUnknownQualityIsNot480p() {
        // A dub on a provider in another language is that language's dub: original version for us.
        assertEquals(SourceVersion.VO, PlaybackSources.version("host", binding("Dubbed", "en")))
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
        val result = loader(2, 2000, 4000).load(bindings, true, false) { b, _, _, callback ->
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
        val result = loader().load(bindings, true, false) { b, _, _, callback ->
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
        val result = loader(3, 50, 150).load(bindings, true, false) { b, _, _, callback ->
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
        val loader = loader()
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
            loader().load((1..8).map { binding() }, true, false) { _, _, _, _ ->
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
        loader(3, 2000, 400).load(unknown + vf + vost, true, false) { b, _, _, _ ->
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
        val result = loader().load(bindings, true, false) { b, _, subtitle, _ ->
            subtitle(newSubtitleFile("French", "https://subs.invalid/fr.srt") { headers = mapOf("Cookie" to b.apiName) })
            true
        }
        assertEquals(2, result.subtitles.size)
        assertEquals(2, result.subtitles.map { it.headers }.distinct().size)
        assertTrue(result.subtitles.all { it.lang == "French" })
    }


    @Test fun aVerifiedVfSourceStartsAtOnceWithoutWaitingForSlowProviders() = runBlocking {
        val fast = binding("Dubbed"); val slow = binding()
        val published = java.util.Collections.synchronizedList(mutableListOf<String>())
        var readyAt = -1L
        val started = System.nanoTime()
        val result = loader().load(listOf(fast, slow), true, false, expectedSeconds = 6_000.0,
            onSource = { published += it.link.name },
            onReady = { readyAt = (System.nanoTime() - started) / 1_000_000 }) { b, _, _, callback ->
            if (b == fast) { callback(link("VF", 720, "https://video.invalid/vf?dur=6100")); true }
            else { delay(1_500); callback(link("Other", 1080, "https://video.invalid/other?dur=5900")); true }
        }
        assertTrue("ready after ${readyAt}ms", readyAt in 0..1_200)
        assertEquals(2, published.size)
        assertEquals(2, result.links.size)
        assertTrue(result.sources.all { it.metadata.check == SourceCheck.Verified })
    }

    @Test fun withoutAnExcellentSourceTheViewersLanguageProvidersAreAwaitedButNotForever() = runBlocking {
        var readyAt = -1L
        val started = System.nanoTime()
        val quick = binding(); val slow = binding()
        loader(policy = PlaybackPolicy(maxWaitMs = 600)).load(listOf(quick, slow), true, false, onReady = {
            readyAt = (System.nanoTime() - started) / 1_000_000 }) { b, _, _, callback ->
            if (b == quick) { callback(link("Host", 480)); true } else { delay(3_000); true }
        }
        assertTrue("ready after ${readyAt}ms", readyAt in 500..2_000)
        var none = false
        loader().load(listOf(binding()), true, false, onReady = { none = true }) { _, _, _, _ -> false }
        assertFalse(none)
    }

    @Test fun streamsThatCannotBeTheWorkNeverReachThePlayer() = runBlocking {
        val result = loader().load(listOf(binding(), binding()), true, false, expectedSeconds = 114 * 60.0) { b, _, _, callback ->
            callback(link("Real", 1080, "https://video.invalid/${b.apiName}/real?dur=6900"))
            callback(link("Advert loop", 2160, "https://video.invalid/${b.apiName}/scam?dur=180"))
            callback(link("Other film", 1080, "https://video.invalid/${b.apiName}/other?dur=3000"))
            callback(link("Gone", 1080, "https://video.invalid/${b.apiName}/dead"))
            true
        }
        assertEquals(2, result.links.size)
        assertTrue(result.links.all { "real" in it.url })
        assertEquals(mapOf("short" to 2, "duration" to 2, "http" to 2), result.rejections)
    }

    @Test fun foreignProvidersAreAFallbackAndUnverifiedFourKIsNotBelieved() = runBlocking {
        val french = binding(language = "fr"); val english = binding(language = "en")
        val result = loader().load(listOf(english, french), true, false) { b, _, _, callback ->
            if (b == english) callback(link("4K", 2160)) else callback(link("Host", 480)); true
        }
        val ranked = SourceRanking.rank(result.sources)
        assertEquals(SourceVersion.Unknown, ranked.first().version)
        assertEquals(SourceVersion.VO, ranked.last().version)
        // A declared 2160p that no manifest confirms counts as 1080p.
        assertEquals(3.0, SourceRanking.qualityTier(SourceRanking.trustedHeight(2160, null)), 0.0)
        assertEquals(4.0, SourceRanking.qualityTier(SourceRanking.trustedHeight(2160, 2160)), 0.0)
    }

    @Test fun reliableFastProvidersStartBeforeSlowUnreliableOnes() = runBlocking {
        ProviderHealth.reset()
        val slow = binding(); val fast = binding()
        repeat(5) { ProviderHealth.record(slow.apiName, success = false, timeout = true, elapsedMs = 15_000) }
        repeat(5) { ProviderHealth.record(fast.apiName, success = true, timeout = false, elapsedMs = 800) }
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        loader(concurrency = 1).load(listOf(slow, fast), true, false) { b, _, _, _ -> order += b.apiName; true }
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
        // The manifest's resolution is measured; the extension's label keeps the version.
        val labelled = PlaybackSources.copy(link("Host VOSTFR", 720), binding(), 0, probe)
        assertEquals(1080, labelled.quality)
        assertEquals(SourceVersion.VOST, PlaybackSources.version(labelled))
    }

    @Test fun repeatedlyFailingProvidersAreOnlyAFallback() = runBlocking {
        ProviderHealth.reset()
        val flaky = binding(); val good = binding()
        repeat(3) { ProviderHealth.record(flaky.apiName, success = false, timeout = true, elapsedMs = 15_000) }
        val called = java.util.Collections.synchronizedList(mutableListOf<String>())
        val result = loader().load(listOf(flaky, good), true, false) { b, _, _, callback ->
            called += b.apiName; if (b == good) callback(link("Good", 1080)); true
        }
        assertEquals(listOf(good.apiName), called)
        assertEquals("Skipped", result.outcomes.single { it.provider == flaky.apiName }.status)
        // Nothing playable from the reliable ones: the fallback is tried.
        val empty = binding()
        loader().load(listOf(flaky, empty), true, false) { b, _, _, _ -> called += b.apiName; true }
        assertTrue(flaky.apiName in called)
        ProviderHealth.reset()
    }

    @Test fun aFallbackIsSkippedOncePlaybackCouldStart() = runBlocking {
        ProviderHealth.reset()
        val flaky = binding(); val good = binding(); val slow = binding()
        repeat(5) { ProviderHealth.record(flaky.apiName, success = false, timeout = true, elapsedMs = 15_000) }
        val called = java.util.Collections.synchronizedList(mutableListOf<String>())
        var ready = false
        val result = loader(policy = PlaybackPolicy(maxWaitMs = 50)).load(listOf(flaky, good, slow), true, false,
            onReady = { ready = true }) { b, _, _, callback ->
            called += b.apiName
            if (b == good) callback(link("Good", 720)) else delay(300)
            true
        }
        assertTrue(ready)
        assertFalse(flaky.apiName in called)
        assertEquals("Skipped", result.outcomes.single { it.provider == flaky.apiName }.status)
        ProviderHealth.reset()
    }

    @Test fun streamsOfAnotherLengthAreNotTheSameWork() {
        val movie = 114 * 60_000L
        assertTrue(PlaybackVerification.sameWork(movie + 120_000, movie))
        assertFalse(PlaybackVerification.sameWork(12 * 60_000L, movie))
        assertFalse(PlaybackVerification.sameWork(3 * 60 * 60_000L, movie))
        assertTrue(PlaybackVerification.matchesRuntime(movie, 114))
        assertTrue(PlaybackVerification.matchesRuntime(movie + 20 * 60_000L, 114))
        assertFalse(PlaybackVerification.matchesRuntime(10 * 60_000L, 114))
        // No usable runtime: only the minimum length of a movie or an episode applies.
        assertTrue(PlaybackVerification.matchesRuntime(10 * 60_000L, null))
        assertTrue(PlaybackVerification.matchesRuntime(10 * 60_000L, 5_000))
        assertFalse(PlaybackVerification.matchesRuntime(30_000L, null))
        assertTrue(PlaybackVerification.matchesRuntime(0L, null))
    }

    @Test fun providersWhoseLinksFailInThePlayerRankLower() {
        ProviderHealth.reset()
        val good = PlaybackSources.copy(link("good", 1080), binding(), 0, StreamProbe(1080, emptySet(), emptySet()), SourceCheck.Verified)
        val fake = PlaybackSources.copy(link("fake", 2160), binding(), 1, StreamProbe(2160, emptySet(), emptySet()), SourceCheck.Verified)
        assertTrue(PlaybackVerification.score(fake) > PlaybackVerification.score(good))
        repeat(2) { ProviderHealth.recordPlayback(PlaybackSources.metadata(fake)!!.provider, false) }
        assertTrue(PlaybackVerification.score(fake) < PlaybackVerification.score(good))
        ProviderHealth.reset()
    }
}
