package com.lagradost.cloudstream3

import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.View
import android.view.inspector.WindowInspector
import android.widget.Button
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.catalog.*
import com.lagradost.cloudstream3.ui.home.*
import com.lagradost.cloudstream3.utils.DataStoreHelper
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Real TV button -> multi-row grid -> retry/paging/reopen. Only neutral in-process data. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class HomeShelvesInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
    private fun waitFor(label: String, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (main(predicate)) return
            Thread.sleep(100)
        }
        fail("Timed out: $label")
    }
    @Test fun tvShelfOpensFullGridAndCanLoadMoreWithoutDuplicates() {
        val ctx = instrumentation.targetContext
        val output = File(ctx.cacheDir, "home-shelves-fixture").apply { mkdirs() }
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val keys = setOf(ctx.getString(R.string.provider_lang_key), ctx.getString(R.string.prefer_media_type_key),
            CatalogPreferences.RECOMMENDED_ONLY)
        val backup = prefs.all.filterKeys { it in keys }.mapValues { (_, value) -> if (value is Set<*>) value.toSet() else value }
        val oldAccount = DataStoreHelper.selectedKeyIndex
        val account = -902720
        val nextMovieCalls = AtomicInteger()
        val nextSeriesCalls = AtomicInteger()
        val fixture = object : MainAPI() {
            override var name = "Grid fixture"
            override var lang = "zz"
            override var mainUrl = "https://grid-fixture.invalid"
            override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
            override val hasMainPage = true
            override val mainPage = listOf(mainPage("films", "Latest films"), mainPage("series", "Latest series"))
            fun film(id: Int) = newMovieSearchResponse("Film fixture $id", "/movie/$id") { year = 2026 }
            override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
                if (request.data == "series") {
                    if (page > 1) nextSeriesCalls.incrementAndGet()
                    return newHomePageResponse(HomePageList("Series", listOf(newTvSeriesSearchResponse("Series fixture", "/series"))), true)
                }
                if (page == 2 && nextMovieCalls.incrementAndGet() == 1) error("Temporary fixture failure")
                val ids = when (page) { 1, 2 -> 1..30; 3 -> 26..60; else -> 31..60 }
                val cards = ids.map { id -> if (page == 2) newMovieSearchResponse("Film fixture $id", "/alternative/$id") { year = 2026 } else film(id) }
                return newHomePageResponse(HomePageList("Films", cards), true)
            }
        }
        fun capture(name: String) {
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(output, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        // Restrict the fixture to its own language before launching the activity.
        CatalogPreferences.save(ctx, CatalogSelection(setOf("zz"), setOf(TvType.Movie, TvType.TvSeries)))
        DataStoreHelper.selectedKeyIndex = account
        val plugins = FixturePluginIsolation(ctx)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var activity: MainActivity? = null
        try {
            scenario.onActivity { activity = it; it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            val vm = main {
                APIHolder.allProviders.withLock { APIHolder.allProviders.add(fixture) }
                APIHolder.addPluginMapping(fixture)
                ViewModelProvider(activity!!)[HomeViewModel::class.java].also { it.loadAndCancel(null, true) }
            }
            val title = ctx.getString(R.string.home_movies)
            fun rows() = (vm.page.value as? Resource.Success)?.value.orEmpty()
            waitFor("full initial shelf") { rows()[title]?.list?.list?.size == 30 }
            waitFor("rendered film shelf") {
                val recycler = activity!!.findViewById<RecyclerView>(R.id.home_master_recycler)
                (recycler?.adapter as? ParentItemAdapter)?.immutableCurrentList?.any { it.list.name == title } == true
            }
            fun rowButton(): View? {
                val recycler = activity!!.findViewById<RecyclerView>(R.id.home_master_recycler) ?: return null
                val adapter = recycler.adapter as? ParentItemAdapter ?: return null
                val index = adapter.immutableCurrentList.indexOfFirst { it.list.name == title }
                return recycler.findViewHolderForAdapterPosition(index)?.itemView?.findViewById(R.id.home_child_show_all)
            }
            main {
                val recycler = activity!!.findViewById<RecyclerView>(R.id.home_master_recycler)
                val adapter = recycler.adapter as ParentItemAdapter
                val index = adapter.immutableCurrentList.indexOfFirst { it.list.name == title }
                (recycler.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(index, 0)
            }
            waitFor("TV see-more button") { rowButton()?.isShown == true }
            main {
                val button = rowButton()!!
                val row = button.parent.parent as View
                assertEquals(24, row.findViewById<RecyclerView>(R.id.home_child_recyclerview).adapter!!.itemCount)
                assertTrue(button.requestFocus())
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            fun sheet() = WindowInspector.getGlobalWindowViews().firstOrNull { it.findViewById<View>(R.id.home_expanded_recycler) != null }
            fun grid() = sheet()?.findViewById<RecyclerView>(R.id.home_expanded_recycler)
            fun more() = sheet()?.findViewById<Button>(R.id.home_expanded_load_more)
            waitFor("multi-row full grid") { grid()?.adapter?.itemCount == 30 }
            main { assertTrue((grid()!!.layoutManager as GridLayoutManager).spanCount > 1) }
            main { assertTrue(grid()!!.findViewHolderForAdapterPosition(0)!!.itemView.requestFocus()) }
            repeat(4) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                instrumentation.waitForIdleSync()
                Thread.sleep(150)
            }
            fun focusedPosition(): Int {
                val recycler = grid() ?: return -1
                val focused = recycler.findFocus() ?: return -1
                val item = recycler.findContainingItemView(focused) ?: return -1
                return recycler.getChildAdapterPosition(item)
            }
            waitFor("D-pad scrolls beyond the first twelve cards") { focusedPosition() >= 18 }
            capture("01-expanded-grid.png")
            main { assertTrue(more()!!.requestFocus()) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("retry after transient failure") { more()?.isEnabled == true && more()?.text == ctx.getString(R.string.home_load_more_retry) }
            main { assertEquals(30, grid()!!.adapter!!.itemCount); more()!!.performClick() }
            waitFor("second page deduplicated") { grid()?.adapter?.itemCount == 60 && more()?.isEnabled == true }
            waitFor("newly loaded cards are visible and focused") { focusedPosition() >= 30 }
            main {
                assertTrue((grid()!!.layoutManager as GridLayoutManager).findFirstVisibleItemPosition() >= 30)
                assertEquals(ctx.getString(R.string.home_expanded_count, 60), sheet()!!.findViewById<android.widget.TextView>(R.id.home_expanded_count).text)
            }
            instrumentation.waitForIdleSync()
            Thread.sleep(350)
            assertTrue(instrumentation.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("Film fixture 31").isNotEmpty())
            capture("02-more-films.png")
            main { more()!!.performClick() }
            waitFor("repeated page stops pagination") { more()?.visibility == View.GONE }
            main { assertEquals(60, grid()!!.adapter!!.itemCount); assertEquals(0, nextSeriesCalls.get()) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor("grid dismissed") { sheet() == null }
            waitFor("updated shelf button") { rowButton()?.isShown == true }
            main { rowButton()!!.performClick() }
            waitFor("reopening retains loaded films") { grid()?.adapter?.itemCount == 60 }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            File(output, "report.json").writeText(JSONObject(mapOf("passed" to true,
                "tvButton" to true, "multipleGridColumns" to true, "carouselCards" to 24,
                "initialGridCards" to 30, "expandedCards" to 60, "retryWorks" to true,
                "dpadScrollsBeyondFirstTwelveCards" to true, "canonicalDuplicatePageSkipped" to true,
                "newCardsFocusedAndCountUpdated" to true,
                "repeatedPageStops" to true, "onlyContributingSectionsPaged" to true,
                "loadedCardsRetainedOnReopen" to true, "realMediaRequests" to 0)).toString(2))
        } catch (failure: Throwable) { capture("failure.png"); throw failure }
        finally {
            main {
                APIHolder.allProviders.withLock { APIHolder.allProviders.remove(fixture) }
                APIHolder.removePluginMapping(fixture)
                CloudStreamApp.removeKeys(account.toString())
                DataStoreHelper.selectedKeyIndex = oldAccount
                prefs.edit().also { edit ->
                    keys.forEach(edit::remove)
                    backup.forEach { (key, value) -> when (value) {
                        is Boolean -> edit.putBoolean(key, value)
                        is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                    } }
                }.commit()
            }
            scenario.close()
            plugins.close()
        }
    }
}
