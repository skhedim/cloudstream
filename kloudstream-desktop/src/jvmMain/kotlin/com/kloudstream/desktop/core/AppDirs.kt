package com.kloudstream.desktop.core

import java.io.File

/**
 * Per-user data: `%LOCALAPPDATA%\KloudStream` on Windows, `$XDG_DATA_HOME/kloudstream` (or
 * `~/.local/share/kloudstream`) elsewhere. `-Dkloudstream.home=…` overrides it (tests, portable runs).
 */
object AppDirs {
    val root: File by lazy {
        val override = System.getProperty("kloudstream.home")?.takeIf { it.isNotBlank() }
        val base = when {
            override != null -> File(override)
            isWindows -> File(System.getenv("LOCALAPPDATA") ?: (System.getProperty("user.home") + "\\AppData\\Local"), "KloudStream")
            else -> File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
                ?: (System.getProperty("user.home") + "/.local/share"), "kloudstream")
        }
        base.apply { mkdirs() }
    }
    val extensions: File get() = File(root, "extensions").apply { mkdirs() }
    val classes: File get() = File(extensions, "classes").apply { mkdirs() }
    val data: File get() = File(root, "data").apply { mkdirs() }
    val logs: File get() = File(root, "logs").apply { mkdirs() }
    val cache: File get() = File(root, "cache").apply { mkdirs() }

    val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
}
