package com.kloudstream.desktop.extensions

import android.content.Context
import com.fasterxml.jackson.module.kotlin.readValue
import com.kloudstream.desktop.core.AppDirs
import com.kloudstream.desktop.core.AppLog
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.extractorApis
import com.lagradost.cloudstream4.aggregation.ReviewedExtensions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLClassLoader

data class InstalledExtension(
    val key: String,
    val internalName: String,
    val name: String,
    val version: Int,
    val url: String,
    val repository: String,
    val file: String,
)

enum class ExtensionState { Installed, Loaded, Failed }

data class ExtensionStatus(
    val extension: InstalledExtension,
    val state: ExtensionState,
    val providers: List<String> = emptyList(),
    val error: String? = null,
)

/** The Context extensions receive; preferences and the application context come from the desktop app. */
object DesktopContext : Context()

/**
 * Installs, updates and loads extensions. Each `.cs3` is translated once (see [DexTranslator]) and
 * loaded in its own class loader whose parent provides the CloudStream library and Android stand-ins.
 */
object ExtensionManager {
    private data class SitePlugin(
        val internalName: String = "",
        val name: String? = null,
        val url: String = "",
        val version: Int = 0,
        val status: Int = 1,
        val apiVersion: Int = 1,
    )
    private data class Repository(val name: String? = null, val pluginLists: List<String> = emptyList())
    private class Loaded(val plugin: BasePlugin, val loader: URLClassLoader, val file: File)

    private val mutex = Mutex()
    private val loaded = linkedMapOf<String, Loaded>()
    private val _status = MutableStateFlow<List<ExtensionStatus>>(emptyList())
    val status: StateFlow<List<ExtensionStatus>> get() = _status

    private val indexFile get() = File(AppDirs.extensions, "installed.json")

    fun installed(): List<InstalledExtension> = runCatching {
        if (indexFile.exists()) mapper.readValue<List<InstalledExtension>>(indexFile.readText()) else emptyList()
    }.getOrElse { AppLog.warn("Unreadable extension index", it); emptyList() }

    private fun saveInstalled(list: List<InstalledExtension>) {
        val tmp = File(indexFile.parentFile, indexFile.name + ".tmp")
        tmp.writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(list))
        java.nio.file.Files.move(tmp.toPath(), indexFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    /**
     * Installs missing recommended extensions and updates outdated ones. Returns the keys that
     * changed; already loaded ones are reloaded. Repository failures keep the installed version.
     */
    suspend fun syncRecommended(onProgress: (String) -> Unit = {}): List<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = installed().associateBy { it.key }.toMutableMap()
            val changed = mutableListOf<String>()
            val plugins = mutableMapOf<String, List<SitePlugin>>()
            for (recommended in ReviewedExtensions.all) {
                try {
                    val list = plugins.getOrPut(recommended.repository) { fetchPlugins(recommended.repository) }
                    val site = list.firstOrNull { it.internalName.equals(recommended.internalName, true) } ?: run {
                        AppLog.warn("${recommended.internalName} not found in ${recommended.repository}")
                        continue
                    }
                    val existing = current[recommended.key]
                    if (existing != null && existing.version >= site.version && File(AppDirs.extensions, existing.file).exists()) continue
                    onProgress(site.name ?: site.internalName)
                    val file = File(AppDirs.extensions, "${recommended.key}.cs3")
                    val tmp = File(AppDirs.extensions, "${recommended.key}.cs3.part")
                    tmp.writeBytes(app.get(site.url, cacheTime = 0).body.bytes())
                    java.util.zip.ZipFile(tmp).use { require(it.getEntry("manifest.json") != null) { "Not an extension" } }
                    java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    current[recommended.key] = InstalledExtension(recommended.key, site.internalName, site.name ?: site.internalName,
                        site.version, site.url, recommended.repository, file.name)
                    changed += recommended.key
                    AppLog.info("Installed ${site.internalName} v${site.version} (${recommended.key})")
                } catch (e: Exception) {
                    AppLog.warn("Could not install or update ${recommended.key}", e)
                }
            }
            // Extensions removed from the recommended selection are uninstalled.
            val keys = ReviewedExtensions.all.map { it.key }.toSet()
            current.keys.filter { it !in keys }.forEach { key ->
                unloadLocked(key)
                current.remove(key)?.let { File(AppDirs.extensions, it.file).delete() }
                changed += key
            }
            saveInstalled(current.values.toList())
            changed.filter { it in loaded }.forEach { unloadLocked(it) }
            changed
        }
    }

    private suspend fun fetchPlugins(repositoryUrl: String): List<SitePlugin> {
        val repository = mapper.readValue<Repository>(app.get(repositoryUrl, cacheTime = 0).text)
        return repository.pluginLists.flatMap { list -> mapper.readValue<List<SitePlugin>>(app.get(list, cacheTime = 0).text) }
    }

    /** Loads every installed extension that is not loaded yet. */
    suspend fun loadInstalled() = withContext(Dispatchers.IO) {
        mutex.withLock {
            CloudStreamApp.context = DesktopContext
            // In parallel: some extensions query their site while loading.
            coroutineScope {
                installed().filter { it.key !in loaded }.map { extension -> async { loadLocked(extension) } }.awaitAll()
            }
            APIHolder.initAll()
            publish()
        }
    }

    private val failures = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun loadLocked(extension: InstalledExtension) {
        val file = File(AppDirs.extensions, extension.file)
        try {
            // Reviewed settings (e.g. catalogue-only providers off), unless already set.
            ReviewedExtensions.all.firstOrNull { it.key == extension.key }?.preferences?.forEach { (key, value) ->
                if (CloudStreamApp.getKeyClass(key, Any::class.java) == null) CloudStreamApp.setKey(key, value)
            }
            val dir = DexTranslator.translate(file, AppDirs.classes)
            val manifest = mapper.readValue<BasePlugin.Manifest>(File(dir, "manifest.json"))
            val loader = URLClassLoader(arrayOf(dir.toURI().toURL()), ExtensionManager::class.java.classLoader)
            val plugin = loader.loadClass(manifest.pluginClassName).getDeclaredConstructor().newInstance() as BasePlugin
            plugin.filename = file.absolutePath
            if (plugin is Plugin) plugin.load(DesktopContext) else plugin.load()
            synchronized(loaded) { loaded[extension.key] = Loaded(plugin, loader, file) }
            failures.remove(extension.key)
            AppLog.info("Loaded ${extension.name} v${extension.version}: ${providersOf(file).joinToString { it.name }}")
        } catch (t: Throwable) {
            failures[extension.key] = t.toString()
            AppLog.error("Could not load ${extension.name} (${extension.key})", t)
        }
    }

    private fun unloadLocked(key: String) {
        val entry = loaded.remove(key) ?: return
        runCatching { entry.plugin.beforeUnload() }
        val path = entry.file.absolutePath
        val providers = APIHolder.allProviders.filter { it.sourcePlugin == path }
        providers.forEach { APIHolder.removePluginMapping(it) }
        APIHolder.allProviders.removeAll(providers.toSet())
        extractorApis.removeAll(extractorApis.filter { it.sourcePlugin == path }.toSet())
        runCatching { entry.loader.close() }
    }

    private fun providersOf(file: File): List<MainAPI> = APIHolder.allProviders.filter { it.sourcePlugin == file.absolutePath }

    private fun publish() {
        _status.value = installed().map { extension ->
            val entry = loaded[extension.key]
            when {
                entry != null -> ExtensionStatus(extension, ExtensionState.Loaded, providersOf(entry.file).map { it.name })
                failures.containsKey(extension.key) -> ExtensionStatus(extension, ExtensionState.Failed, error = failures[extension.key])
                else -> ExtensionStatus(extension, ExtensionState.Installed)
            }
        }
    }
}
