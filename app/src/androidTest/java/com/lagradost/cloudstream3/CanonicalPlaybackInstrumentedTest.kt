package com.lagradost.cloudstream3

import android.app.Dialog
import android.graphics.Bitmap
import android.view.View
import android.widget.ListView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.ui.player.*
import com.lagradost.cloudstream3.ui.player.aggregation.*
import com.lagradost.cloudstream3.ui.search.aggregation.AggregatedSearchResponse
import com.lagradost.cloudstream4.aggregation.SearchMediaMapper
import com.lagradost.cloudstream3.ui.search.aggregation.aggregate
import com.lagradost.cloudstream3.ui.result.aggregation.CanonicalDetailRequest
import com.lagradost.cloudstream3.ui.result.aggregation.CanonicalDetailStore
import com.lagradost.cloudstream3.utils.AppContextUtils.loadSearchResult
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream4.domain.media.MediaAggregator
import com.lagradost.cloudstream4.domain.media.ProviderKey
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import java.security.MessageDigest

/** Requires tools/canonical-playback-fixture.sh: only generated local videos, no third-party media.
 * Exercises the real detail, source loader, Media3 player and Tracks dialog on a device. */
@RunWith(AndroidJUnit4::class)
class CanonicalPlaybackInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun <T> main(block: () -> T): T {
        var value: Result<T>? = null
        instrumentation.runOnMainSync { value = runCatching(block) }
        return value!!.getOrThrow()
    }
    private fun waitFor(label: String, timeout: Long = 30_000, predicate: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            if (main(predicate)) return
            Thread.sleep(100)
        }
        fail("Timed out: $label")
    }
    private fun fragments(activity: FragmentActivity): List<Fragment> {
        fun children(fragment: Fragment): List<Fragment> = listOf(fragment) +
            fragment.childFragmentManager.fragments.flatMap(::children)
        return activity.supportFragmentManager.fragments.flatMap(::children)
    }
    @Suppress("UNCHECKED_CAST")
    private fun selected(fragment: GeneratorPlayer): ExtractorLink? =
        (GeneratorPlayer::class.java.getDeclaredField("currentSelectedLink").apply { isAccessible = true }
            .get(fragment) as? VideoLink)?.first
    private fun dialog(fragment: GeneratorPlayer): Dialog =
        FullScreenPlayer::class.java.getDeclaredField("selectTrackDialog").apply { isAccessible = true }
            .get(fragment) as Dialog

    @Test fun commonEpisodeSelects1080pAndSwitchesVfToVostInsideTracksWithoutLosingPosition() {
        val context = instrumentation.targetContext
        val fixtureDir = context.getExternalFilesDir("canonical-fixtures")!!
        listOf("vf720.mp4", "vf1080.mp4", "vost1080.mp4").forEach {
            assertTrue("Generate and push fixture: $it", File(fixtureDir, it).isFile)
        }
        val title = "Test VF VOST — milestone 4"
        val fixtureApis = (0..2).map { index -> object : MainAPI() {
            override var name = "Canonical fixture $index"
            override var mainUrl = "https://fixture$index.invalid"
            override var lang = "fr"
            override suspend fun load(url: String): LoadResponse {
                val ep = newEpisode("fixture:$index", { season = 1; episode = 1 }, fix = false)
                return if (index == 0) newAnimeLoadResponse(title, url, TvType.TvSeries) {
                    episodes[DubStatus.Dubbed] = listOf(ep.copy(data = "vf"))
                    episodes[DubStatus.Subbed] = listOf(ep.copy(data = "vost"))
                } else newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(ep))
            }
            override suspend fun loadLinks(data: String, isCasting: Boolean,
                subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
                if (index == 2) error("Expected fixture failure: other providers must survive")
                delay(if (index == 1) 400 else 30)
                val file = if (data == "vost") "vost1080.mp4" else if (index == 1) "vf1080.mp4" else "vf720.mp4"
                @Suppress("DEPRECATION")
                callback(ExtractorLink(name, if (index == 1) "Host VF" else "Host", "http://127.0.0.1:18765/$file",
                    "", if (file == "vf720.mp4") 720 else 1080, type = ExtractorLinkType.VIDEO))
                return true
            }
        } }
        val preferenceKey = "${DataStoreHelper.currentAccount}/canonical_source_version"
        val oldPreference = CloudStreamApp.getKey<String>(preferenceKey)
        CloudStreamApp.setKey(preferenceKey, "Auto")
        var activity: MainActivity? = null
        var fixtureParentId: Int? = null
        val output = File(context.cacheDir, "milestone4-fixture").apply { mkdirs() }
        fun capture(name: String) {
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(output, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity = it }
            instrumentation.waitForIdleSync()
            main {
                // Plugin initialization rebuilds apis from allProviders in the background.
                // Register fixtures in both, just as installed plugins do.
                APIHolder.allProviders.withLock { fixtureApis.forEach { APIHolder.allProviders.add(it) } }
                fixtureApis.forEach(APIHolder::addPluginMapping)
                val results = fixtureApis.map { api -> ProviderKey(api.name, api.mainUrl) to
                    api.newTvSeriesSearchResponse(title, "/series") { year = 2026 } }
                val card = SearchMediaMapper.aggregate(MediaAggregator(), results).single() as AggregatedSearchResponse
                val identity = CanonicalDetailStore.pin(CanonicalDetailRequest.from(card)).identity
                fixtureParentId = ("canonical:v1:" + MessageDigest.getInstance("SHA-256")
                    .digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }).hashCode()
                activity!!.loadSearchResult(card)
            }
            waitFor("common episode ready") {
                activity!!.findViewById<View>(R.id.result_play_series_button)?.isShown == true ||
                    activity!!.findViewById<View>(R.id.result_resume_series_button)?.isShown == true
            }
            capture("01-detail.png")
            main {
                val selector = activity!!.findViewById<View>(R.id.result_dub_selection)
                assertTrue("Version selection must be absent from detail", selector == null || !selector.isShown)
                val play = activity!!.findViewById<View>(R.id.result_play_series_button)
                (play.takeIf { it.isShown } ?: activity!!.findViewById<View>(R.id.result_resume_series_button)).performClick()
            }
            var fragment: GeneratorPlayer? = null
            waitFor("player fragment") {
                fragment = fragments(activity!!).filterIsInstance<GeneratorPlayer>().firstOrNull()
                fragment != null
            }
            val player = fragment!!
            waitFor("decoded VF frame") {
                selected(player) != null && (player.player.getPosition() ?: 0L) > 1000 &&
                    player.player.getVideoTracks().currentVideoTrack?.height == 1080 &&
                    player.currentPlayerStatus != CSPlayerLoading.IsBuffering
            }
            main {
                assertEquals(SourceVersion.VF, PlaybackSources.version(selected(player)))
                assertEquals(1080, selected(player)!!.quality)
                assertEquals(1080, player.player.getVideoTracks().currentVideoTrack?.height)
                val vm = ViewModelProvider(player)[PlayerGeneratorViewModel::class.java]
                assertEquals(3, vm.state.links.size)
                assertTrue(vm.state.canonical)
                assertEquals(1, (vm.state.generatorState!!.meta as com.lagradost.cloudstream3.ui.result.ResultEpisode).season)
                player.player.handleEvent(CSPlayerEvent.Pause)
                player.player.seekTo(9_000)
            }
            waitFor("seek VF") { abs((player.player.getPosition() ?: 0L) - 9_000L) < 500 }
            capture("02-vf1080.png")
            main {
                val tracksButton = activity!!.findViewById<View>(R.id.player_tracks_btt)
                assertEquals("Tracks button must remain available with a single embedded audio track", View.VISIBLE, tracksButton.visibility)
                tracksButton.performClick()
            }
            instrumentation.waitForIdleSync()
            capture("03-tracks.png")
            main {
                val dialog = dialog(player)
                val list = dialog.findViewById<ListView>(R.id.source_version_list)
                val labels = (0 until list.adapter.count).map { list.adapter.getItem(it).toString() }
                assertTrue(labels.containsAll(listOf("VF", "VOST")))
                val index = labels.indexOf("VOST")
                list.performItemClick(list.getChildAt(index - list.firstVisiblePosition), index, list.adapter.getItemId(index))
                dialog.findViewById<View>(R.id.apply_btt).performClick()
            }
            waitFor("VOST source and preserved position") {
                PlaybackSources.version(selected(player)) == SourceVersion.VOST &&
                    player.currentPlayerStatus != CSPlayerLoading.IsBuffering &&
                    abs((player.player.getPosition() ?: 0L) - 9_000L) < 1000
            }
            main { assertFalse("Paused state must survive", player.player.getIsPlaying()) }
            capture("04-vost1080.png")
            val evidence = main {
                mapOf("defaultVersion" to "VF", "defaultQuality" to 1080, "selectedVersion" to PlaybackSources.version(selected(player)).name,
                    "selectedQuality" to selected(player)!!.quality, "positionBeforeMs" to 9_000,
                    "positionAfterMs" to player.player.getPosition(), "pausedStatePreserved" to !player.player.getIsPlaying(),
                    "linkCount" to ViewModelProvider(player)[PlayerGeneratorViewModel::class.java].state.links.size)
            }
            File(output, "report.json").writeText(JSONObject(evidence).toString(2))
        } catch (failure: Throwable) {
            capture("failure.png")
            File(output, "failure.json").writeText(JSONObject(main {
                mapOf("message" to failure.message, "fragments" to (activity?.let(::fragments)?.map { it.javaClass.simpleName }),
                    "registeredFixtures" to APIHolder.apis.filter { it.name.startsWith("Canonical fixture") }.map { it.name })
            }).toString(2))
            throw failure
        } finally {
            main {
                activity?.let { a -> fragments(a).filterIsInstance<GeneratorPlayer>().firstOrNull()?.let { player ->
                    val meta = ViewModelProvider(player)[PlayerGeneratorViewModel::class.java].state.generatorState?.meta
                        as? com.lagradost.cloudstream3.ui.result.ResultEpisode
                    DataStoreHelper.removeLastWatched(meta?.parentId)
                } }
                APIHolder.allProviders.withLock { fixtureApis.forEach { APIHolder.allProviders.remove(it) } }
                fixtureApis.forEach(APIHolder::removePluginMapping)
            }
            scenario.close()
            DataStoreHelper.removeLastWatched(fixtureParentId)
            if (oldPreference == null) CloudStreamApp.removeKey(preferenceKey) else CloudStreamApp.setKey(preferenceKey, oldPreference)
        }
    }
}
