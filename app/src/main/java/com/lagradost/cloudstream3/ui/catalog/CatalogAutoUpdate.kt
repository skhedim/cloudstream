package com.lagradost.cloudstream3.ui.catalog

import android.app.Activity
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.plugins.PluginWrapper
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream4.aggregation.ReviewedExtensions
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reviewed extensions added to the list after the catalogue was set up are installed once, at
 * start, as the desktop app does at every start. One the viewer removes afterwards is not brought
 * back, and nothing happens before the setup wizard has run or without the recommended selection.
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
}
