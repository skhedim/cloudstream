package com.lagradost.cloudstream3

import com.lagradost.cloudstream4.aggregation.SourceVersion
import com.lagradost.cloudstream4.aggregation.PlaybackSources
import com.lagradost.cloudstream4.aggregation.CanonicalSourceLoader
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

    @Test fun autoPrefersAVeryMuchBetterUnspecifiedSourceOverMediocreFrench() {
        val vf = binding("Dubbed")
        val links = listOf(PlaybackSources.copy(link("vf-480", 480), vf, 0),
            PlaybackSources.copy(link("unspecified", 1080), binding(), 1),
            PlaybackSources.copy(link("vf-720", 720), vf, 2))
        assertEquals("vf-720", state(links).sortLinks(999).first { it.shouldUseLink }.link.first!!.name.split(" · ")[1])
        val withoutVf720 = links.take(2)
        assertEquals(1080, state(withoutVf720).sortLinks(999).first { it.shouldUseLink }.link.first!!.quality)
        // An explicit version choice still restricts the eligible sources.
        assertEquals(480, state(withoutVf720, SourceVersion.VF).sortLinks(999).first { it.shouldUseLink }.link.first!!.quality)
    }
}
