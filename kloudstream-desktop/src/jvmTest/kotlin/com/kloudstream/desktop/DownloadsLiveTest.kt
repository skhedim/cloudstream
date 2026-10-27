package com.kloudstream.desktop

import com.kloudstream.desktop.app.AppModel
import com.kloudstream.desktop.app.DetailModel
import com.kloudstream.desktop.app.MediaCard
import com.kloudstream.desktop.downloads.DownloadState
import com.kloudstream.desktop.downloads.Downloads
import com.kloudstream.desktop.extensions.ExtensionManager
import com.kloudstream.desktop.platform.DesktopPlatform
import com.lagradost.cloudstream4.aggregation.AggregationPlatform
import com.lagradost.cloudstream4.aggregation.CanonicalSearchEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files

/**
 * A real episode starts downloading from a real source (then the download is cancelled): source
 * choice, link headers and the stream format work outside fixtures. Opt-in: -Pkloudstream.live=true
 */
class DownloadsLiveTest {
    @Test fun anEpisodeStartsDownloading() = runBlocking {
        assumeTrue("live tests disabled", System.getProperty("kloudstream.live") == "true")
        val home = Files.createTempDirectory("kloudstream-downloads").toFile()
        System.setProperty("kloudstream.home", home.path)
        DesktopPlatform.install()
        ExtensionManager.syncRecommended()
        ExtensionManager.loadInstalled()
        AppModel.extensionsReady.complete(Unit)
        Downloads.folder = java.io.File(home, "videos")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val engine = CanonicalSearchEngine(scope)
        engine.search("Breaking Bad", AggregationPlatform.registry.providers())
        val searched = withTimeout(90_000) { engine.state.first { it.published && !it.loading && it.cards.isNotEmpty() } }
        val card = searched.cards.map { MediaCard(it.media, it.responses) }.first { it.title.contains("Breaking Bad", true) }
        val detail = DetailModel(scope, card.request()).also { it.load() }
        withTimeout(120_000) { detail.state.first { it.model != null } }
        val request = detail.playRequest(detail.resumeRow())!!
        Downloads.enqueue(request, null)
        val item = withTimeoutOrNull(240_000) {
            Downloads.items.first { list -> list.any { it.state == DownloadState.Failed || it.state == DownloadState.Done || (it.state == DownloadState.Downloading && it.bytes > 2_000_000) } }
        }?.firstOrNull()
        println("Download: ${item?.state} ${item?.bytes} bytes ${item?.progress} ${item?.error ?: ""}")
        item?.let(Downloads::cancel)
        assertTrue("download progressed: $item", item != null && item.state != DownloadState.Failed && item.bytes > 2_000_000)
    }
}
