package com.kloudstream.desktop.app

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mapper

/** New versions are GitHub releases of the fork, with the Windows installer attached. */
object Updates {
    private const val LATEST = "https://api.github.com/repos/skhedim/cloudstream/releases/latest"

    data class Release(val version: String, val installer: String?, val page: String)

    val current: String get() = System.getProperty("kloudstream.version") ?: "dev"

    suspend fun latest(): Release? = runCatching {
        val node = mapper.readTree(app.get(LATEST, cacheTime = 0).text)
        val installer = node.path("assets").firstOrNull { it.path("name").asText().endsWith(".exe") }
            ?.path("browser_download_url")?.asText()
        Release(node.path("tag_name").asText().removePrefix("v"), installer, node.path("html_url").asText())
    }.getOrNull()

    /** Numeric comparison of dotted versions; a development build is never "newer". */
    fun isNewer(candidate: String, installed: String = current): Boolean {
        val a = candidate.split('.').map { it.toIntOrNull() ?: return false }
        val b = installed.split('.').map { it.toIntOrNull() ?: return true }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
