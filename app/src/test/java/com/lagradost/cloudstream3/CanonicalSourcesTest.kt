package com.lagradost.cloudstream3

import android.app.Application
import com.lagradost.cloudstream3.ui.player.VideoState
import com.lagradost.cloudstream3.ui.player.VideoLink
import com.lagradost.cloudstream3.ui.player.aggregation.*
import com.lagradost.cloudstream4.aggregation.EpisodeSourceBinding
import com.lagradost.cloudstream3.utils.*
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CanonicalSourcesTest {
    companion object { private val counter = AtomicInteger() }
    private fun binding(track: String = "None", language: String = "fr") =
        EpisodeSourceBinding("Fixture${counter.incrementAndGet()}", "https://fixture.invalid", "/show", "opaque", track, language)
    @Suppress("DEPRECATION")
    private fun link(label: String, quality: Int = 1080, url: String = "https://video.invalid/$label", headers: Map<String,String> = emptyMap()) =
        ExtractorLink("Fixture", label, url, "https://ref.invalid", quality, headers, "verification-data", ExtractorLinkType.VIDEO)
    private fun state(links: List<ExtractorLink>, version: SourceVersion = SourceVersion.Auto) =
        VideoState(instance = 0, canonical = true, sourceVersion = version,
            links = links.map { it to null }.toPersistentSet())

    @Test fun defaultChoosesBestKnownQualityInFrenchAndTheVersionChoiceChangesTheEligibleSources() {
        val vf = binding("Dubbed")
        val vost = binding("Subbed")
        val links = listOf(PlaybackSources.copy(link("fast", 720), vf, 0),
            PlaybackSources.copy(link("slow", 1080), vf, 1),
            PlaybackSources.copy(link("original", 2160), vost, 2))
        val best = state(links).sortLinks(999).first { it.shouldUseLink }.link.first!!
        assertEquals(1080, best.quality)
        assertEquals(SourceVersion.VF, PlaybackSources.version(best))
        val original = state(links, SourceVersion.VOST).sortLinks(999).filter { it.shouldUseLink }
        assertEquals(1, original.size)
        assertEquals(2160, original.single().link.first!!.quality)
        assertEquals(SourceVersion.VOST, PlaybackSources.version(original.single().link.first))
        assertEquals(1080, state(links.reversed()).sortLinks(999).first().link.first!!.quality)
        assertTrue(state(links.take(2), SourceVersion.VOST).sortLinks(999).any { it.shouldUseLink })
    }

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
        loader.load(listOf(b), false, false, fetch)
        val cached = loader.load(listOf(b), false, false, fetch)
        assertEquals("Cached", cached.outcomes.single().status)
        assertEquals(1, calls)
        loader.load(listOf(b.copy(data = "other-payload")), false, false, fetch)
        loader.load(listOf(b), false, true, fetch)
        loader.load(listOf(b), true, false, fetch)
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
        assertEquals(2, result.subtitles.map { it.name }.distinct().size)
        assertTrue(result.subtitles.all { it.matchesLanguageCode("fr") })
    }
}
