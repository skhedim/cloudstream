package com.kloudstream.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.kloudstream.desktop.app.AppModel
import com.kloudstream.desktop.app.Screen
import com.kloudstream.desktop.core.AppLog
import com.kloudstream.desktop.core.JsonFile
import com.kloudstream.desktop.ui.KloudApp
import com.kloudstream.desktop.ui.appIcon

fun main() {
    // Before any TLS connection: fetch intermediate certificates that some hosts omit, like Android.
    System.setProperty("com.sun.security.enableAIAcaIssuers", "true")
    Thread.setDefaultUncaughtExceptionHandler { thread, error -> AppLog.error("Uncaught on ${thread.name}", error) }
    AppLog.info("KloudStream ${System.getProperty("kloudstream.version") ?: "dev"} starting (${System.getProperty("os.name")}, Java ${System.getProperty("java.version")})")
    AppModel.start()
    application {
        // Created at the screen's size: maximizing a smaller window shows an unpainted area first.
        val screen = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        val state = rememberWindowState(placement = WindowPlacement.Maximized, width = screen.width.dp, height = screen.height.dp)
        Window(
            onCloseRequest = {
                AppModel.player.stop()
                JsonFile.flushAll()
                exitApplication()
            },
            title = "KloudStream",
            icon = appIcon(),
            state = state,
            onPreviewKeyEvent = { event ->
                if (event.type != KeyEventType.KeyDown) return@Window false
                when {
                    event.key == Key.Escape || event.key == Key.Backspace && event.isCtrlPressed -> AppModel.navigator.back()
                    event.key == Key.F && event.isCtrlPressed -> { AppModel.navigator.open(Screen.Search); true }
                    else -> false
                }
            },
        ) {
            window.minimumSize = java.awt.Dimension(1024, 640)
            KloudApp()
        }
    }
}
