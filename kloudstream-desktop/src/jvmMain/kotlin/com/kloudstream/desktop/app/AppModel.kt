package com.kloudstream.desktop.app

import com.kloudstream.desktop.core.AppLog
import com.kloudstream.desktop.extensions.ExtensionManager
import com.kloudstream.desktop.library.DesktopSyncedHistory
import com.kloudstream.desktop.library.WatchHistory
import com.kloudstream.desktop.platform.DesktopPlatform
import com.kloudstream.desktop.player.PlaybackController
import com.kloudstream.desktop.player.PlaybackState
import com.lagradost.cloudstream4.sync.HistorySyncService
import com.lagradost.cloudstream3.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface Startup {
    data class Loading(val message: String) : Startup
    data object Ready : Startup
    data class Failed(val message: String) : Startup
}

/** The app's long-lived state: extensions, navigation, home, search and playback. */
object AppModel {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _startup = MutableStateFlow<Startup>(Startup.Loading("Démarrage…"))
    val startup: StateFlow<Startup> get() = _startup
    val navigator = Navigator()
    val home = HomeModel(scope)
    val search = SearchModel(scope)
    val player = PlaybackController(scope)

    /** Completed once the installed extensions are loaded: pages and searches wait for it. */
    val extensionsReady = kotlinx.coroutines.CompletableDeferred<Unit>()

    fun start() {
        scope.launch {
            try {
                DesktopPlatform.install()
                WatchHistory.load()
                HistorySyncService.install(DesktopSyncedHistory, "PC") {}
                player.setFullscreen(com.kloudstream.desktop.platform.DesktopPreferences.open("kloudstream").getBoolean("player_fullscreen", true))
                if (ExtensionManager.installed().isEmpty()) {
                    _startup.value = Startup.Loading("Installation des sources…")
                    ExtensionManager.syncRecommended { _startup.value = Startup.Loading("Installation de $it…") }
                } else {
                    // Like streaming apps: the last home shows at once while sources load.
                    home.restore()
                    _startup.value = Startup.Ready
                }
                ExtensionManager.loadInstalled()
                extensionsReady.complete(Unit)
                // Unfinished downloads resume once the providers are there.
                com.kloudstream.desktop.downloads.Downloads.load()
                _startup.value = Startup.Ready
                home.load()
                MainActivity.reloadHomeEvent += { home.load() }
                // For you follows the history: a few seconds after it changes (playback saves often).
                scope.launch {
                    WatchHistory.entries.drop(1).collectLatest {
                        kotlinx.coroutines.delay(5_000)
                        home.refreshForYou()
                    }
                }
                // The history shared with the other devices: now, every 2 minutes and after each viewing.
                scope.launch(Dispatchers.IO) {
                    while (true) {
                        HistorySyncService.sync()
                        kotlinx.coroutines.delay(2 * 60_000L)
                    }
                }
                scope.launch(Dispatchers.IO) {
                    player.state.drop(1).filter { it is PlaybackState.Idle }.collect { HistorySyncService.sync() }
                }
                // Updates are installed in the background and applied right away.
                val changed = ExtensionManager.syncRecommended()
                if (changed.isNotEmpty()) {
                    ExtensionManager.loadInstalled()
                    home.load()
                }
            } catch (t: Throwable) {
                AppLog.error("Startup failed", t)
                _startup.value = Startup.Failed(t.message ?: t.toString())
            }
        }
    }

    suspend fun updateExtensions(): Int {
        val changed = ExtensionManager.syncRecommended()
        ExtensionManager.loadInstalled()
        if (changed.isNotEmpty()) home.load()
        return changed.size
    }
}
