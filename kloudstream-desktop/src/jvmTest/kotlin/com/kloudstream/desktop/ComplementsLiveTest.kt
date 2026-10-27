package com.kloudstream.desktop

import com.kloudstream.desktop.app.DetailModel
import com.kloudstream.desktop.app.MediaCard
import com.kloudstream.desktop.extensions.ExtensionManager
import com.kloudstream.desktop.platform.DesktopPlatform
import com.lagradost.cloudstream4.aggregation.AggregationPlatform
import com.lagradost.cloudstream4.aggregation.CanonicalSearchEngine
import com.lagradost.cloudstream4.aggregation.CanonicalSourceLoader
import com.lagradost.cloudstream4.aggregation.ExtensionRole
import com.lagradost.cloudstream4.aggregation.ReviewedExtensions
import com.lagradost.cloudstream4.aggregation.SourceVersion
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
 * The reviewed original-version and anime extensions as the viewer meets them: their titles merge
 * with the French catalogues' cards, and they add VOST where the French catalogues only have VF.
 * Opt-in (network): -Pkloudstream.live=true
 */
class ComplementsLiveTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private suspend fun open(query: String, title: (MediaCard) -> Boolean): DetailModel {
        val engine = CanonicalSearchEngine(scope)
        engine.search(query, AggregationPlatform.registry.providers())
        val searched = withTimeout(90_000) { engine.state.first { it.published && !it.loading && it.cards.isNotEmpty() } }
        val cards = searched.cards.map { MediaCard(it.media, it.responses) }
        cards.take(6).forEach { card -> println("  [$query] ${card.title} ${card.year}: ${card.media.bindings.map { it.provider.name }}") }
        val card = cards.first(title)
        return DetailModel(scope, card.request()).also { it.load() }
    }

    @Test fun complementsMergeIntoTheFrenchCardsAndAddVost() = runBlocking {
        assumeTrue("live tests disabled", System.getProperty("kloudstream.live") == "true")
        System.setProperty("kloudstream.home", Files.createTempDirectory("kloudstream-complements").toString())
        DesktopPlatform.install()
        ExtensionManager.syncRecommended()
        ExtensionManager.loadInstalled()
        // What AppModel.start() signals in the app; search and detail pages wait for it.
        com.kloudstream.desktop.app.AppModel.extensionsReady.complete(Unit)
        ExtensionManager.status.value.forEach { println("${it.extension.key}: ${it.state} ${it.providers} ${it.error ?: ""}") }
        val complements = ReviewedExtensions.all.filter { it.role != ExtensionRole.Catalogue }.map { it.key }.toSet()

        for ((query, match) in listOf<Pair<String, (MediaCard) -> Boolean>>(
            "Past Lives" to { it.year == 2023 },
            "Frieren" to { it.title.contains("Frieren", true) },
        )) {
            val detail = open(query, match)
            // The page shows as soon as a model exists; completion at the other providers may go on.
            withTimeout(120_000) { detail.state.first { it.model != null } }
            val page = (withTimeoutOrNull(60_000) { detail.state.first { !it.loading } } ?: detail.state.value).model!!
            val providers = page.compatible.map { it.binding.provider.name }
            println("Page ${page.base.response.name}: $providers")
            val row = if (page.family == "movie") null else detail.resumeRow()
            val request = detail.playRequest(row)!!
            val sources = CanonicalSourceLoader().load(request.bindings, clearCache = true, casting = false,
                preference = SourceVersion.VOST, expectedSeconds = request.expectedSeconds, episode = request.isEpisode, work = request.work)
            sources.outcomes.forEach { println("  ${it.provider} ${it.status} ${it.links} links in ${it.elapsedMs} ms") }
            val versions = sources.sources.groupingBy { it.version.name + if (it.metadata.addedSubtitles) "+subs" else "" }.eachCount()
            println("  versions: $versions, subtitles: ${sources.subtitles.map { it.lang }.distinct().take(8)}")
            assertTrue("a VOST for $query", sources.sources.any { it.version == SourceVersion.VOST })
            assertTrue("$query found by a complement", request.bindings.any { binding ->
                ExtensionManager.status.value.any { it.extension.key in complements && binding.apiName in it.providers } })
        }
    }
}
