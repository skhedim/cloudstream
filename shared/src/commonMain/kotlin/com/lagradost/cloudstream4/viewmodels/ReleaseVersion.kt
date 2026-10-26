package com.lagradost.cloudstream4.viewmodels

/** Numeric comparison for stable APK versions, including multi-digit build numbers. */
object ReleaseVersion {
    private val pattern = Regex("""^(\d+)\.(\d+)\.(\d+)$""")

    fun isNewer(remote: String, installed: String): Boolean {
        fun parts(value: String): List<Long>? = pattern.matchEntire(value)
            ?.groupValues?.drop(1)?.map { it.toLongOrNull() ?: return null }

        val candidate = parts(remote) ?: return false
        val current = parts(installed) ?: return false
        return candidate.zip(current).firstOrNull { (a, b) -> a != b }
            ?.let { (a, b) -> a > b } ?: false
    }
}
