package com.lagradost.cloudstream3.ui.catalog

import android.app.Activity
import android.content.Context
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*

class CatalogViewModel : ViewModel() {
    data class State(val inventory: CatalogInventory? = null, val busy: Boolean = false,
        val loading: Boolean = true, val error: Boolean = false, val current: String = "",
        val completed: Int = 0, val total: Int = 0, val result: CatalogInstallation? = null)
    val state = MutableLiveData(State())
    var selection: CatalogSelection? = null
    private val installer = CatalogInstaller()
    private var job: Job? = null
    fun initialize(context: Context) {
        if (selection == null) selection = CatalogPreferences.initial(context)
        if (state.value?.inventory == null && job?.isActive != true) refresh()
    }
    fun refresh() {
        job?.cancel()
        state.value = State()
        job = viewModelScope.launch {
            try {
                val inventory = withTimeout(150_000) { installer.inventory() }
                state.value = State(inventory = inventory, loading = false, error = inventory.plugins.isEmpty())
            } catch (cancelled: CancellationException) {
                if (cancelled !is TimeoutCancellationException) throw cancelled
                state.value = State(loading = false, error = true)
            } catch (_: Exception) { state.value = State(loading = false, error = true) }
        }
    }
    fun install(activity: Activity) {
        val old = state.value ?: return
        if (old.busy || old.loading) return
        val chosen = selection ?: return
        if (chosen.languages.isEmpty() || chosen.types.isEmpty()) return
        val inventory = old.inventory ?: return
        val plan = chosen.plan(inventory.plugins, com.lagradost.cloudstream3.plugins.PluginManager.getPluginsOnline()
            .mapNotNull { it.url }.toSet())
        CatalogPreferences.save(activity, chosen)
        CatalogPreferences.manageArchives(activity, plan.flatMap { wrapper ->
            listOf(com.lagradost.cloudstream3.plugins.PluginManager.getPluginPath(activity,
                wrapper.plugin.internalName, wrapper.repositoryData.url).absolutePath) +
                com.lagradost.cloudstream3.plugins.PluginManager.getPluginsOnline()
                    .filter { it.url == wrapper.plugin.url }.map { it.filePath }
        })
        state.value = old.copy(busy = true, error = false, result = null)
        job = viewModelScope.launch {
            try {
                val result = installer.install(activity, plan) { completed, total, name ->
                    withContext(Dispatchers.Main.immediate) {
                        state.value = old.copy(busy = true, completed = completed, total = total, current = name)
                    }
                }
                state.value = old.copy(result = result, loading = false, busy = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                com.lagradost.cloudstream3.mvvm.logError(error)
                state.value = old.copy(loading = false, error = true, busy = false)
            }
        }
    }
}
