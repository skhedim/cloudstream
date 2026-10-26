package com.lagradost.cloudstream3

import android.graphics.Bitmap
import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.catalog.CatalogPreferences
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.ui.search.aggregation.AggregatedSearchResponse
import com.lagradost.cloudstream4.aggregation.SearchMediaMetadataCache
import com.lagradost.cloudstream3.utils.AppContextUtils.loadSearchResult
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking

/** Real persisted history -> aggregated home -> common detail, using temporary local profiles
 * and in-process providers. No remote media or changes to the user's history. */
@RunWith(AndroidJUnit4::class)
class PersonalizedHomeInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
    private fun waitFor(label: String, predicate: () -> Boolean) {
        val end = System.currentTimeMillis() + 25_000
        while (System.currentTimeMillis() < end) {
            if (main(predicate)) return
            Thread.sleep(100)
        }
        fail("Timed out: $label")
    }
    @Test fun historyCreatesOneSuggestionWithAllBindingsAndSwitchingProfilesRemovesIt() {
        val context = instrumentation.targetContext
        val output = File(context.cacheDir, "personalized-home-fixture").apply { mkdirs() }
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val languageKey = context.getString(R.string.provider_lang_key)
        val oldLanguages = preferences.getStringSet(languageKey, null)?.toSet()
        val mediaKey = context.getString(R.string.prefer_media_type_key)
        val oldMedia = preferences.getStringSet(mediaKey, null)?.toSet()
        val oldAccount = DataStoreHelper.selectedKeyIndex
        val fixtureAccount = -902501
        val headerId = -902503
        val seriesHeaderId = -902504
        val detailLoads = AtomicInteger()
        val fixtureApis = (0..1).map { index -> object : MainAPI() {
            override var name = "Personalization fixture $index"
            override var mainUrl = "https://personalization-$index.invalid"
            override var lang = "zz"
            override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
            override val hasMainPage = true
            override val mainPage = listOf(mainPage("recent", "Derniers films"))
            fun movie(title: String) = newMovieSearchResponse(title, "/$title") { year = 2026 }
            fun series(title: String) = newTvSeriesSearchResponse(title, "/series/$title") { year = 2026 }
            override suspend fun getMainPage(page: Int, request: MainPageRequest) = newHomePageResponse(
                HomePageList("Derniers ajouts", listOf(movie("Nouveauté fixture"), movie("Suggestion commune fixture"), series("Série suggérée fixture"))))
            override suspend fun load(url: String): LoadResponse {
                if (url.contains("/series/")) return newTvSeriesLoadResponse("Série vue fixture",url,TvType.TvSeries,emptyList()) {
                    year=2026
                    recommendations=listOf(series("Série vue fixture"),series("Série suggérée fixture"))
                }
                if (!url.contains("seen")) detailLoads.incrementAndGet()
                return newMovieLoadResponse(
                if (url.contains("seen")) "Film vu fixture" else "Suggestion commune fixture", url, TvType.Movie, "opaque") {
                year = 2026
                recommendations = listOf(movie("Film vu fixture"), movie("Suggestion commune fixture"))
                }
            }
        } }
        fun capture(name: String) {
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(output, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        // Restrict the fixture to its own language before launching the activity.
        preferences.edit().putStringSet(languageKey, setOf("zz")).commit()
        val plugins = FixturePluginIsolation(context)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var activity: MainActivity? = null
        try {
            scenario.onActivity {
                activity = it
                it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            instrumentation.waitForIdleSync()
            val vm = main {
                preferences.edit().putStringSet(languageKey, setOf("zz")).commit()
                DataStoreHelper.selectedKeyIndex = fixtureAccount
                APIHolder.allProviders.withLock { fixtureApis.forEach { APIHolder.allProviders.add(it) } }
                fixtureApis.forEach(APIHolder::addPluginMapping)
                val api = fixtureApis.first()
                CloudStreamApp.setKey(DOWNLOAD_HEADER_CACHE, headerId.toString(), DownloadObjects.DownloadHeaderCached(
                    api.name, api.mainUrl + "/seen", TvType.Movie, "Film vu fixture", null, System.currentTimeMillis(), headerId))
                DataStoreHelper.setLastWatched(headerId, headerId, null, null)
                DataStoreHelper.setViewPos(headerId, 120_000, 5_400_000)
                CloudStreamApp.setKey(DOWNLOAD_HEADER_CACHE, seriesHeaderId.toString(), DownloadObjects.DownloadHeaderCached(
                    api.name, api.mainUrl + "/series/seen", TvType.TvSeries, "Série vue fixture", null, System.currentTimeMillis(), seriesHeaderId))
                DataStoreHelper.setLastWatched(seriesHeaderId, seriesHeaderId, 1, 1)
                DataStoreHelper.setViewPos(seriesHeaderId, 46_758, 1_343_959)
                ViewModelProvider(activity!!)[HomeViewModel::class.java].also { it.loadAndCancel(null, true) }
            }
            val title = context.getString(R.string.home_for_you_movies)
            val seriesTitle = context.getString(R.string.home_for_you_series)
            waitFor("personalized shelf from persisted history") {
                val cards = (vm.page.value as? Resource.Success)?.value?.get(title)?.list?.list
                val seriesCards=(vm.page.value as? Resource.Success)?.value?.get(seriesTitle)?.list?.list
                cards?.size == 1 && (cards.single() as? AggregatedSearchResponse)?.media?.bindings?.size == 2 &&
                    seriesCards?.size==1 && (seriesCards.single() as? AggregatedSearchResponse)?.media?.bindings?.size==2
            }
            val suggestion = main {
                val rows = (vm.page.value as Resource.Success).value
                val cards = rows.getValue(title).list.list
                assertEquals(1, cards.size)
                val card = cards.single() as AggregatedSearchResponse
                assertEquals("Suggestion commune fixture", card.name)
                assertEquals(2, card.media.bindings.size)
                activity!!.findViewById<RecyclerView>(R.id.home_master_recycler).scrollToPosition(1)
                card
            }
            instrumentation.waitForIdleSync()
            capture("01-for-you.png")
            main {
                val seriesCards=(vm.page.value as Resource.Success).value.getValue(seriesTitle).list.list
                assertEquals("Série suggérée fixture",seriesCards.single().name)
                activity!!.findViewById<RecyclerView>(R.id.home_master_recycler).scrollToPosition(2)
            }
            instrumentation.waitForIdleSync()
            capture("04-for-you-series.png")
            main { activity!!.loadSearchResult(suggestion) }
            waitFor("common movie detail") {
                activity!!.findViewById<View>(R.id.result_youtube_Button)?.isShown == true && detailLoads.get() >= 2
            }
            capture("02-common-detail.png")
            main {
                activity!!.onBackPressedDispatcher.onBackPressed()
            }
            val generationField = HomeViewModel::class.java.getDeclaredField("discoveryGeneration").apply { isAccessible = true }
            val generation = main { generationField.getLong(vm) }
            val pageBeforeEnrichment = main { vm.page.value }
            // A detail can learn an original title without adding a viewing event.
            val seedApi = fixtureApis.first()
            runBlocking {
                SearchMediaMetadataCache.remember(seedApi, seedApi.mainUrl + "/seen",
                    seedApi.newMovieLoadResponse("Film vu fixture — nom original", seedApi.mainUrl + "/seen", TvType.Movie, "opaque") { year = 2026 })
                main { vm.loadAndCancel(null, false) }.join()
            }
            main {
                assertEquals("Metadata enrichment must not reload the home", generation, generationField.getLong(vm))
                assertSame(pageBeforeEnrichment, vm.page.value)
                DataStoreHelper.selectedKeyIndex = fixtureAccount - 1
                vm.reloadStored()
            }
            waitFor("empty profile cannot retain personalized shelf") {
                val rows = (vm.page.value as? Resource.Success)?.value
                rows != null && rows.isNotEmpty() && title !in rows && seriesTitle !in rows
            }
            instrumentation.waitForIdleSync()
            capture("03-empty-profile.png")
            File(output, "report.json").writeText(JSONObject(mapOf(
                "status" to "passed", "persistedHistoryPositionMs" to 120_000,
                "suggestions" to 1, "bindings" to suggestion.media.bindings.size,
                "seriesSuggestions" to 1, "seriesBindings" to 2, "seriesHistoryPositionMs" to 46_758,
                "watchedExcluded" to true, "profileIsolation" to true,
                "metadataEnrichmentKeepsHome" to true,
                "commonDetailOpened" to true)).toString(2))
        } catch (failure: Throwable) {
            capture("failure.png")
            throw failure
        } finally {
            main {
                DataStoreHelper.selectedKeyIndex = oldAccount
                CloudStreamApp.removeKeys(fixtureAccount.toString())
                CloudStreamApp.removeKeys((fixtureAccount - 1).toString())
                CloudStreamApp.removeKey(DOWNLOAD_HEADER_CACHE, headerId.toString())
                CloudStreamApp.removeKey(DOWNLOAD_HEADER_CACHE, seriesHeaderId.toString())
                APIHolder.allProviders.withLock { fixtureApis.forEach { APIHolder.allProviders.remove(it) } }
                fixtureApis.forEach(APIHolder::removePluginMapping)
            }
            scenario.close()
            plugins.close()
            main {
                preferences.edit().also { edit ->
                    if (oldLanguages == null) edit.remove(languageKey) else edit.putStringSet(languageKey, oldLanguages)
                    if (oldMedia == null) edit.remove(mediaKey) else edit.putStringSet(mediaKey, oldMedia)
                }.commit()
            }
        }
    }
}
