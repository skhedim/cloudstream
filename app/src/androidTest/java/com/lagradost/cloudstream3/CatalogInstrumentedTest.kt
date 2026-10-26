package com.lagradost.cloudstream3

import android.graphics.Bitmap
import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.plugins.*
import com.lagradost.cloudstream3.ui.catalog.*
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.net.InetAddress
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking

/** Real catalogue wizard and installer integrity, with neutral in-process films. */
@RunWith(AndroidJUnit4::class)
class CatalogInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
    private fun waitFor(label: String, timeout: Long = 30_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            if (main(predicate)) return
            Thread.sleep(100)
        }
        fail("Timed out: $label")
    }
    @Test fun catalogueWizardAndInstallerPreserveExistingConfiguration() {
        val ctx = instrumentation.targetContext
        val output = File(ctx.cacheDir, "catalog-fixture").apply { mkdirs() }
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val keys = setOf(ctx.getString(R.string.provider_lang_key), ctx.getString(R.string.prefer_media_type_key),
            CatalogPreferences.RECOMMENDED_ONLY, CatalogPreferences.MANAGED_ARCHIVES)
        val backup = prefs.all.filterKeys { it in keys }.mapValues { (_, value) -> if (value is Set<*>) value.toSet() else value }
        val nativePreferences = prefs.all.filterKeys { it !in keys }
        val repositoryPrefs = ctx.getSharedPreferences(com.lagradost.cloudstream3.utils.PREFERENCES_NAME, android.content.Context.MODE_PRIVATE)
        val repositoryKey = com.lagradost.cloudstream3.ui.settings.extensions.REPOSITORIES_KEY
        val oldRepositories = repositoryPrefs.getString(repositoryKey, null)
        val oldAccount = com.lagradost.cloudstream3.utils.DataStoreHelper.selectedKeyIndex
        val account = -902721
        val fixture = object : MainAPI() {
            override var name = "Catalog fixture"
            override var lang = "zz"
            override var mainUrl = "https://catalog-fixture.invalid"
            override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
            override val hasMainPage = true
            override val mainPage = listOf(mainPage("recent", "Derniers films"))
            override suspend fun search(query: String, page: Int) = newSearchResponseList(
                listOf(newMovieSearchResponse("Film catalogue fixture", "/film") { year = 2026 }), false)
            override suspend fun getMainPage(page: Int, request: MainPageRequest) = newHomePageResponse(
                HomePageList("Derniers films", listOf(newMovieSearchResponse("Film catalogue fixture", "/film") { year = 2026 })), false)
        }
        fun capture(name: String) {
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(output, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        CatalogPreferences.save(ctx, CatalogSelection(setOf("zz"), setOf(TvType.Movie, TvType.TvSeries)))
        com.lagradost.cloudstream3.utils.DataStoreHelper.selectedKeyIndex = account
        val plugins = FixturePluginIsolation(ctx)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var activity: MainActivity? = null
        fun nav() = (activity!!.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as androidx.navigation.fragment.NavHostFragment).navController
        fun catalog() = activity!!.supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
            ?.childFragmentManager?.primaryNavigationFragment as? CatalogFragment
        try {
            scenario.onActivity { activity = it; it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            val vm = main {
                APIHolder.allProviders.withLock { APIHolder.allProviders.add(fixture) }
                APIHolder.addPluginMapping(fixture)
                ViewModelProvider(activity!!)[HomeViewModel::class.java].also { it.loadAndCancel(null, true) }
            }
            waitFor("fixture home") { (vm.page.value as? Resource.Success)?.value?.values
                ?.flatMap { it.list.list }?.any { it.name == "Film catalogue fixture" } == true }
            assertFalse(runBlocking { PluginManager.loadPlugin(ctx, File(ctx.cacheDir, "must-not-load.cs3"),
                PluginData("Rate", "https://raw.githubusercontent.com/RVRBEAST76/allforu-repo/builds/Rate-v8.cs3", true,
                    File(ctx.cacheDir, "must-not-load.cs3").absolutePath, 8)) })
            main { activity!!.findViewById<View>(R.id.home_change_api).performClick() }
            instrumentation.waitForIdleSync()
            capture("01-catalogue-menu.png")
            var node = instrumentation.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText(ctx.getString(R.string.catalog_title)).first()
            while (!node.isClickable) node = requireNotNull(node.parent)
            assertTrue(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            waitFor("catalogue screen") { catalog()?.view != null }
            val model = main { ViewModelProvider(catalog()!!)[CatalogViewModel::class.java] }
            waitFor("repository metadata", 120_000) { model.state.value?.loading == false }
            val inventory = main { model.state.value!!.inventory!! }
            assertTrue(inventory.plugins.isNotEmpty())
            val healthy = inventory.plugins.sortedBy { if (it.plugin.internalName.equals("CineStream", true)) 0 else 1 }.first { candidate ->
                CatalogCuration.recommended(candidate) && plugins.online.any { it.url == candidate.plugin.url && File(it.filePath).isFile }
            }.let { wrapper -> wrapper.copy(plugin = wrapper.plugin.copy(version = plugins.online.first { it.url == wrapper.plugin.url }.version)) }
            val original = plugins.online.first { it.url == healthy.plugin.url }
            val originalFile = File(original.filePath)
            val originalHash = RepositoryManager.sha256(originalFile)
            PluginManager.unloadPlugin(original.filePath)
            val location = runBlocking { com.lagradost.cloudstream3.ui.settings.extensions.AndroidRepositoryManager.downloadPlugin(
                com.lagradost.cloudstream4.viewmodels.PluginDownloadSource(originalFile.length(), original.internalName,
                    healthy.plugin.url, healthy.repositoryData.url, com.lagradost.cloudstream4.viewmodels.DigestPair.parse(originalHash))) { _, _ ->
                    fail("A verified cached archive must not be downloaded")
                } }
            assertEquals(original.filePath, location)
            assertTrue(PluginManager.plugins.containsKey(original.filePath))
            val instance = PluginManager.plugins[original.filePath]
            val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            val served = java.util.concurrent.atomic.AtomicBoolean()
            val worker = thread(isDaemon = true, name = "catalog-integrity") {
                runCatching { server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val body = "invalid fixture archive".toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(body); flush()
                        served.set(true)
                    }
                } }
            }
            try {
                val bad = healthy.copy(plugin = healthy.plugin.copy(name = "Integrity fixture",
                    url = "http://127.0.0.1:${server.localPort}/fixture.cs3", fileHash = "sha256-" + "0".repeat(64)))
                val result = runBlocking { CatalogInstaller().install(activity!!, listOf(bad, healthy)) { _, _, _ -> } }
                assertEquals(CatalogInstallation(0, 1, listOf("Integrity fixture")), result)
                assertTrue(served.get()); assertEquals(originalHash, RepositoryManager.sha256(originalFile))
                assertSame(instance, PluginManager.plugins[original.filePath])
            } finally { server.close(); worker.join(2_000) }
            capture("02-configure-catalogue.png")
            main {
                val repo = Repository(null, "Fixture", null, 1, emptyList())
                val disabled = SitePlugin("https://fixture.invalid/disabled.cs3", 0, 1, 1, "Disabled fixture", "DisabledFixture",
                    emptyList(), null, null, listOf("Movie"), "zz", null, null, null)
                model.state.value = CatalogViewModel.State(inventory = CatalogInventory(listOf(PluginWrapper(repo,
                    RepositoryData("Fixture", "https://fixture.invalid/repo.json"), disabled)), emptyList()), loading = false)
                assertTrue(catalog()!!.requireView().findViewById<View>(R.id.catalog_apply).performClick())
            }
            waitFor("wizard apply") { model.state.value?.result != null }
            main { assertEquals(0, model.state.value!!.result!!.installed); catalog()!!.requireView().findViewById<View>(R.id.catalog_close).performClick() }
            waitFor("return to discover") { activity!!.findViewById<View>(R.id.home_change_api) != null }
            main { nav().navigate(R.id.navigation_search) }
            fun search() = activity!!.supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
                ?.childFragmentManager?.primaryNavigationFragment as? com.lagradost.cloudstream3.ui.search.SearchFragment
            waitFor("search screen") { search()?.view != null }
            val searchModel = main { ViewModelProvider(activity!!)[com.lagradost.cloudstream3.ui.search.SearchViewModel::class.java] }
            main { activity!!.findViewById<androidx.appcompat.widget.SearchView>(R.id.main_search).setQuery("Catalogue fixture", true) }
            waitFor("aggregated film search") { (searchModel.searchResponse.value as? Resource.Success)?.value?.list?.any {
                it is com.lagradost.cloudstream3.ui.search.aggregation.AggregatedSearchResponse && it.name == "Film catalogue fixture"
            } == true }
            waitFor("rendered film search") {
                val recycler = activity!!.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.search_autofit_results)
                (recycler?.adapter as? com.lagradost.cloudstream3.ui.search.SearchAdapter)?.immutableCurrentList?.any { it.name == "Film catalogue fixture" } == true
            }
            instrumentation.waitForIdleSync()
            capture("03-film-search.png")
            main { searchModel.clearSearch(); nav().popBackStack() }
            main { nav().navigate(R.id.navigation_setup_language) }
            waitFor("setup languages") { activity!!.findViewById<View>(R.id.next_btt) != null }
            main { activity!!.findViewById<View>(R.id.next_btt).performClick() }
            waitFor("wizard route") { catalog()?.arguments?.getBoolean("isSetup") == true }
            main { catalog()!!.requireView().findViewById<View>(R.id.catalog_close).performClick() }
            waitFor("layout setup") { nav().currentDestination?.id == R.id.navigation_setup_layout }
            assertEquals(nativePreferences.filterKeys { it != ctx.getString(R.string.auto_update_plugins_key) },
                prefs.all.filterKeys { it !in keys && it != ctx.getString(R.string.auto_update_plugins_key) })
            File(output, "report.json").writeText(JSONObject(mapOf("passed" to true, "wizardApply" to true,
                "initialWizardRoute" to true, "cachedArchiveRegistered" to true, "hashFailureIsolated" to true,
                "intrusivePluginBlockedBeforeLoading" to true, "nativePreferencesPreserved" to true,
                "aggregatedFilmSearch" to true)).toString(2))
        } finally {
            main {
                APIHolder.allProviders.withLock { APIHolder.allProviders.remove(fixture) }
                APIHolder.removePluginMapping(fixture)
                CloudStreamApp.removeKeys(account.toString())
                com.lagradost.cloudstream3.utils.DataStoreHelper.selectedKeyIndex = oldAccount
                prefs.edit().also { edit ->
                    keys.forEach(edit::remove)
                    backup.forEach { (key, value) -> when (value) {
                        is Boolean -> edit.putBoolean(key, value)
                        is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                    } }
                }.commit()
                check(repositoryPrefs.edit().apply {
                    if (oldRepositories == null) remove(repositoryKey) else putString(repositoryKey, oldRepositories)
                }.commit())
            }
            scenario.close()
            plugins.close()
        }
    }
}
