package com.lagradost.cloudstream3.ui.catalog

import android.app.Activity
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.plugins.PluginData
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.plugins.PluginWrapper
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream4.aggregation.ReviewedExtensions
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the recommended catalogue in line with the reviewed list at start, as the desktop app does:
 * reviewed extensions added to the list are installed once (one the viewer removes afterwards is
 * not brought back), packages the catalogue installed that are no longer reviewed are uninstalled,
 * and repositories left with none of them are removed. Extensions installed by hand are left
 * alone, and nothing happens before the setup wizard has run or without the recommended selection.
 */
object CatalogAutoUpdate {
    private const val OFFERED = "catalog_offered_reviewed"
    private val started = AtomicBoolean(false)

    /** The packages to install: reviewed, not offered yet, matching the selection, not installed. */
    fun plan(selection: CatalogSelection, available: List<PluginWrapper>, installed: Set<String>, offered: Set<String>): List<PluginWrapper> =
        selection.plan(available, installed).filter { wrapper ->
            val reviewed = CatalogCuration.reviewed(wrapper) ?: return@filter false
            reviewed.key !in offered && wrapper.plugin.url !in installed
        }

    /** Packages the catalogue installed ([managed]) that are no longer reviewed. */
    fun stale(installed: List<PluginData>, managed: Set<String>): List<PluginData> = installed.filter {
        it.filePath in managed && !CatalogCuration.recommended(it.internalName, it.url.orEmpty() + it.filePath)
    }

    /** Repositories that no reviewed extension and no remaining package comes from. */
    fun unused(repositories: List<RepositoryData>, remaining: List<PluginData>): List<RepositoryData> {
        val reviewed = ReviewedExtensions.all.map { it.origin }.toSet()
        return repositories.filter { repository ->
            val folder = ReviewedExtensions.key(repository.url)
            ReviewedExtensions.origin(repository.url) !in reviewed && remaining.none { folder in ReviewedExtensions.key(it.filePath) }
        }
    }

    fun install() {
        MainActivity.afterPluginsLoadedEvent += ::onPluginsLoaded
    }

    private fun onPluginsLoaded(@Suppress("UNUSED_PARAMETER") reloaded: Boolean) {
        val activity = CommonActivity.activity ?: return
        if (!started.compareAndSet(false, true)) return
        ioSafe { update(activity) }
    }

    private suspend fun update(activity: Activity) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        if (!prefs.contains(activity.getString(R.string.provider_lang_key))) return
        val selection = CatalogPreferences.read(activity)
        if (!selection.recommendedOnly) return
        prune(activity)
        val offered = prefs.getStringSet(OFFERED, emptySet()).orEmpty()
        val pending = ReviewedExtensions.all.filter { it.key !in offered }
        if (pending.isEmpty()) return
        // Repositories that do not answer now are asked again at the next start.
        val fetched = pending.map { it.repository }.distinct().associateWith { url ->
            val repository = RepositoryManager.parseRepository(url) ?: return@associateWith null
            RepositoryManager.getRepoPlugins(RepositoryData(repository.iconUrl, repository.name, url))
        }.filterValues { it != null }
        val available = fetched.values.flatMap { it.orEmpty() }
        val installed = PluginManager.getPluginsOnline().mapNotNull { it.url }.toSet()
        val plan = plan(selection, available, installed, offered)
        if (plan.isNotEmpty()) {
            CatalogPreferences.manageArchives(activity, plan.map { wrapper ->
                PluginManager.getPluginPath(activity, wrapper.plugin.internalName, wrapper.repositoryData.url).absolutePath
            })
            // Failures are retried at the next start.
            if (CatalogInstaller().install(activity, plan) { _, _, _ -> }.failed.isNotEmpty()) return
        }
        prefs.edit().putStringSet(OFFERED, offered + pending.filter { it.repository in fetched }.map { it.key }).apply()
    }

    private suspend fun prune(activity: Activity) {
        val managed = PreferenceManager.getDefaultSharedPreferences(activity)
            .getStringSet(CatalogPreferences.MANAGED_ARCHIVES, emptySet()).orEmpty()
        stale(PluginManager.getPluginsOnline().toList(), managed).forEach { data ->
            if (PluginManager.deletePlugin(File(data.filePath))) CatalogPreferences.releaseManagedArchive(activity, data.filePath)
        }
        unused(RepositoryManager.getRepositories().toList(), PluginManager.getPluginsOnline().toList())
            .forEach { RepositoryManager.removeRepository(activity, it) }
    }
}
