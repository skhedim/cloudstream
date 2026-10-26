package com.lagradost.cloudstream3.ui.catalog

import com.lagradost.cloudstream3.plugins.PluginWrapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Sequential registry mutations, resumable from installed state, with isolated per-package failures. */
object CatalogProvisioner {
    suspend fun run(plan: List<PluginWrapper>, current: (PluginWrapper) -> Boolean,
        download: suspend (PluginWrapper) -> Boolean,
        progress: suspend (Int, Int, String) -> Unit): CatalogInstallation {
        var installed = 0; var unchanged = 0
        val failed = mutableListOf<String>()
        plan.forEachIndexed { index, wrapper ->
            currentCoroutineContext().ensureActive()
            progress(index, plan.size, wrapper.plugin.name)
            if (current(wrapper)) unchanged++ else {
                val loaded = try { download(wrapper) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
                currentCoroutineContext().ensureActive()
                if (loaded) installed++ else failed += wrapper.plugin.name
            }
        }
        progress(plan.size, plan.size, "")
        return CatalogInstallation(installed, unchanged, failed)
    }
}
