package com.lagradost.cloudstream3.ui.catalog

import android.app.Activity
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.plugins.*
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

data class CatalogInventory(val plugins: List<PluginWrapper>, val failedRepositories: List<String>)
data class CatalogInstallation(val installed: Int, val unchanged: Int, val failed: List<String>)

/** Remote discovery is bounded. PluginManager remains responsible for hashes, storage and loading. */
class CatalogInstaller {
    suspend fun inventory(): CatalogInventory = withContext(Dispatchers.IO) {
        var indexFailed = false
        val index = try { withTimeout(12_000) { CatalogIndex.parse(app.get(CatalogIndex.INDEX).text) } }
        catch (cancelled: CancellationException) {
            if (cancelled !is TimeoutCancellationException) throw cancelled
            indexFailed = true; emptyList()
        } catch (_: Exception) { indexFailed = true; emptyList() }
        val urls = (index + CatalogIndex.MEGA_REPOSITORY + RepositoryManager.getRepositories().map { it.url }).distinct().take(100)
        val deadline = System.nanoTime() + 90_000_000_000L
        val limit = Semaphore(4)
        val results = urls.map { url -> async {
            limit.withPermit {
                val plugins = try {
                    val remaining = (deadline - System.nanoTime()) / 1_000_000
                    withTimeoutOrNull(minOf(15_000, remaining.coerceAtLeast(1))) {
                        val repo = RepositoryManager.parseRepository(url) ?: return@withTimeoutOrNull null
                        val data = RepositoryData(repo.iconUrl, repo.name, url)
                        repo.pluginLists.take(10).flatMap { list ->
                            app.get(RepositoryManager.convertRawGitUrl(list)).parsed<Array<SitePlugin>>()
                                .map { PluginWrapper(repo, data, it) }
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                url to plugins
            }
        } }.awaitAll()
        ensureActive()
        CatalogInventory(results.flatMap { it.second.orEmpty() }, results.filter { it.second == null }.map { it.first } +
            if (indexFailed) listOf(CatalogIndex.INDEX) else emptyList())
    }

    suspend fun install(activity: Activity, plan: List<PluginWrapper>,
        progress: suspend (Int, Int, String) -> Unit): CatalogInstallation = withContext(Dispatchers.IO) {
        fun current(wrapper: PluginWrapper) = PluginManager.getPluginsOnline().find { it.url == wrapper.plugin.url }
        fun healthy(wrapper: PluginWrapper): Boolean = current(wrapper)?.let {
            it.version >= wrapper.plugin.version && File(it.filePath).isFile && PluginManager.plugins.containsKey(it.filePath)
        } == true
        CatalogProvisioner.run(plan, current = { wrapper ->
            healthy(wrapper)
        }, download = { wrapper ->
            val p = wrapper.plugin
            RepositoryManager.addRepository(wrapper.repositoryData)
            val existing = current(wrapper)
            val target = existing?.let { File(it.filePath) }
                ?: PluginManager.getPluginPath(activity, p.internalName, wrapper.repositoryData.url)
            val previousInstance = PluginManager.plugins[target.absolutePath]
            val loaded = withTimeoutOrNull(45_000) {
                PluginManager.downloadPlugin(activity, p.url, p.fileHash, p.internalName, target, true)
            } ?: false
            ensureActive()
            // Core registers a plugin before invoking its load(). Remove partial registrations on failure.
            if (!loaded && PluginManager.plugins[target.absolutePath] !== previousInstance) {
                PluginManager.unloadPlugin(target.absolutePath)
            }
            loaded
        }, progress = progress)
    }
}
