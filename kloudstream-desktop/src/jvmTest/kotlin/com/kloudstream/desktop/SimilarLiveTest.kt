package com.kloudstream.desktop

import com.kloudstream.desktop.app.DetailModel
import com.kloudstream.desktop.app.MediaCard
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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files

/** A film and a series page list similar works from their real sources. Opt-in: -Pkloudstream.live=true. */
class SimilarLiveTest {
    @Test fun detailPagesListSimilarWorks() = runBlocking {
        assumeTrue("live tests disabled", System.getProperty("kloudstream.live") == "true")
        System.setProperty("kloudstream.home", Files.createTempDirectory("kloudstream-similar").toString())
        DesktopPlatform.install()
        ExtensionManager.syncRecommended()
        ExtensionManager.loadInstalled()
        com.kloudstream.desktop.app.AppModel.extensionsReady.complete(Unit)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        for ((query, year) in listOf("Dune" to 2021, "Breaking Bad" to 2008)) {
            val engine = CanonicalSearchEngine(scope)
            engine.search(query, AggregationPlatform.registry.providers())
            val searched = withTimeout(60_000) { engine.state.first { it.published && !it.loading && it.cards.isNotEmpty() } }
            val card = searched.cards.map { MediaCard(it.media, it.responses) }.first { it.title == query && it.year == year }
            val detail = DetailModel(scope, card.request()).also { it.load() }
            val page = withTimeout(120_000) { detail.state.first { !it.loading && it.model != null && it.similar.isNotEmpty() } }
            println("Similar to $query (${page.model!!.compatible.size} pages): " +
                page.similar.take(12).joinToString { "${it.title} (${it.year}) x${it.media.bindings.size}" })
            assertTrue(page.similar.none { it.title == query && it.year == year })
        }
    }
}
