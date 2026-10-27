package com.kloudstream.desktop

import com.kloudstream.desktop.app.DetailModel
import com.kloudstream.desktop.app.MediaCard
import com.kloudstream.desktop.extensions.ExtensionManager
import com.kloudstream.desktop.platform.DesktopPlatform
import com.kloudstream.desktop.player.PlaybackController
import com.kloudstream.desktop.player.PlaybackState
import com.lagradost.cloudstream4.aggregation.AggregationPlatform
import com.lagradost.cloudstream4.aggregation.CanonicalSearchEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Search → canonical page → sources → mpv, end to end with real extensions and a real mpv
 * (no video or audio output). Opt-in: -Pkloudstream.live=true, mpv in the PATH.
 */
class PlaybackLiveTest {
    @Test fun aMovieStartsInMpvAndKeepsPlaying() = runBlocking {
        assumeTrue("live tests disabled", System.getProperty("kloudstream.live") == "true")
        System.setProperty("kloudstream.home", Files.createTempDirectory("kloudstream-play").toString())
        System.setProperty("kloudstream.mpvArgs", "--vo=null --ao=null --fullscreen=no")
        DesktopPlatform.install()
        ExtensionManager.syncRecommended()
        ExtensionManager.loadInstalled()
        // What AppModel.start() signals in the app; search and detail pages wait for it.
        com.kloudstream.desktop.app.AppModel.extensionsReady.complete(Unit)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val engine = CanonicalSearchEngine(scope)
        engine.search("Dune", AggregationPlatform.registry.providers())
        val searched = withTimeout(60_000) { engine.state.first { it.published && !it.loading && it.cards.isNotEmpty() } }
        val card = searched.cards.map { MediaCard(it.media, it.responses) }.first { it.title == "Dune" && it.year == 2021 }
        println("Card: ${card.title} ${card.year} bindings=${card.media.bindings.size}")

        val detail = DetailModel(scope, card.request()).also { it.load() }
        val page = withTimeout(90_000) { detail.state.first { !it.loading && it.model != null } }
        println("Page: ${page.title} family=${page.model!!.family} sources=${page.model!!.compatible.size}")

        val player = PlaybackController(scope)
        player.setFullscreen(false)
        player.play(detail.playRequest()!!)
        val result = withTimeout(180_000) {
            player.state.first { (it is PlaybackState.Playing && it.positionMs >= 20_000) || it is PlaybackState.Failed }
        }
        println("Result: $result")
        player.stop()
        assertTrue(result is PlaybackState.Playing)
    }
}
