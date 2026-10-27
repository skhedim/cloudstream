package com.kloudstream.desktop

import com.kloudstream.desktop.extensions.ExtensionManager
import com.kloudstream.desktop.extensions.ExtensionState
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Downloads the recommended extensions, translates and loads them, then queries their real
 * sites. Opt-in (network): ./gradlew :kloudstream-desktop:jvmTest -Pkloudstream.live=true
 */
class ExtensionRuntimeLiveTest {
    @Test fun recommendedExtensionsLoadAndAnswerOnTheJvm() = runBlocking {
        assumeTrue("live tests disabled", System.getProperty("kloudstream.live") == "true")
        System.setProperty("kloudstream.home", Files.createTempDirectory("kloudstream-live").toString())

        val changed = ExtensionManager.syncRecommended { println("Installing $it") }
        assertEquals(com.lagradost.cloudstream4.aggregation.ReviewedExtensions.all.size, changed.size)
        ExtensionManager.loadInstalled()
        val status = ExtensionManager.status.value
        status.forEach { println("${it.extension.key}: ${it.state} ${it.providers} ${it.error ?: ""}") }
        assertTrue(status.all { it.state == ExtensionState.Loaded })

        val apis = APIHolder.apis.toList()
        println("Providers: ${apis.map { it.name }}")
        for (api in apis) {
            val results = withTimeoutOrNull(30_000) { runCatching { api.search("Dune", 1) }.onFailure { it.printStackTrace() }.getOrNull() }
            println("${api.name}: ${results?.items?.size} results, first: ${results?.items?.take(3)?.map { it.name }}")
        }
        val movix = apis.first { it.name == "Movix" }
        val first = movix.search("Dune", 1)!!.items.first()
        val page = movix.load(first.url)!!
        println("Movix page: ${page.name} ${page.year} ${page.type}")
        val data = (page as? com.lagradost.cloudstream3.MovieLoadResponse)?.dataUrl
            ?: (page as com.lagradost.cloudstream3.TvSeriesLoadResponse).episodes.first().data
        val links = mutableListOf<ExtractorLink>()
        withTimeoutOrNull(60_000) { movix.loadLinks(data, false, {}) { links += it } }
        println("Movix links: ${links.size} ${links.take(5).map { "${it.name} ${it.quality} ${it.url.take(60)}" }}")
        assertTrue(links.isNotEmpty())
    }
}
