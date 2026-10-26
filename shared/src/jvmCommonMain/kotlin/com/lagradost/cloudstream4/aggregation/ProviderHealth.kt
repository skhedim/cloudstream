package com.lagradost.cloudstream4.aggregation

import com.fasterxml.jackson.annotation.JsonIgnore

/** Measured link resolution outcomes of one provider. Persisted; no URLs or payloads. */
data class ProviderStats(
    val samples: Int = 0,
    val successes: Int = 0,
    val timeouts: Int = 0,
    /** Exponentially weighted resolve time of successful loads, in milliseconds. */
    val averageMs: Double = 0.0,
    val lastSuccess: Long = 0,
    val lastAttempt: Long = 0,
    /** Links of this provider that played, or failed/turned out to be another video, in the player. */
    val playbackSuccesses: Int = 0,
    val playbackFailures: Int = 0,
    /** Consecutive searches that returned nothing or failed, and when the last one ran. */
    val emptySearches: Int = 0,
    val lastSearch: Long = 0,
) {
    /** Up to two quality steps for a provider whose links keep failing in the player. */
    @get:JsonIgnore val playbackPenalty: Double get() =
        2.0 * playbackFailures / (playbackFailures + playbackSuccesses + 1.0)

    /** Repeatedly failing: worth trying only as a fallback, and at least every [RETRY_MS]. */
    fun unreliable(now: Long): Boolean = samples >= 3 && successRate < 0.25 && now - lastAttempt < RETRY_MS

    /** Laplace-smoothed: a provider with no history starts at 50 %. */
    @get:JsonIgnore val successRate: Double get() = (successes + 1.0) / (samples + 2.0)
    @get:JsonIgnore val timeoutRate: Double get() = if (samples == 0) 0.0 else timeouts.toDouble() / samples
    @get:JsonIgnore val expectedMs: Double get() = if (successes == 0) UNKNOWN_MS else averageMs

    fun record(success: Boolean, timeout: Boolean, elapsedMs: Long, now: Long): ProviderStats {
        val weight = 0.3
        return copy(
            // Old evidence fades: the counters behave like a sliding window of about 50 samples.
            samples = minOf(samples, 49) + 1,
            successes = (if (samples >= 50) successes * 49 / 50 else successes) + if (success) 1 else 0,
            timeouts = (if (samples >= 50) timeouts * 49 / 50 else timeouts) + if (timeout) 1 else 0,
            averageMs = if (!success) averageMs else if (successes == 0) elapsedMs.toDouble()
                else averageMs * (1 - weight) + elapsedMs * weight,
            lastSuccess = if (success) now else lastSuccess,
            lastAttempt = now,
        )
    }

    companion object {
        const val UNKNOWN_MS = 8_000.0
        const val RETRY_MS = 6 * 60 * 60 * 1000L
    }
}

/**
 * Provider reliability for ordering work: reliable, fast providers start first. It never
 * excludes a provider or changes which sources are eligible.
 */
object ProviderHealth {
    private const val KEY = "aggregation/provider_health_v1"
    data class Saved(val providers: Map<String, ProviderStats> = emptyMap())
    @Volatile private var loaded: MutableMap<String, ProviderStats>? = null

    private fun state(): MutableMap<String, ProviderStats> = loaded ?: synchronized(this) {
        loaded ?: (runCatching { AggregationPlatform.store.get(KEY, Saved::class.java) }.getOrNull()
            ?.providers.orEmpty().toMutableMap()).also { loaded = it }
    }

    fun stats(provider: String): ProviderStats = synchronized(this) { state()[provider] } ?: ProviderStats()

    fun record(provider: String, success: Boolean, timeout: Boolean, elapsedMs: Long) = synchronized(this) {
        val all = state()
        all[provider] = (all[provider] ?: ProviderStats()).record(success, timeout, elapsedMs, System.currentTimeMillis())
    }

    fun recordPlayback(provider: String, success: Boolean) {
        synchronized(this) {
            val all = state()
            val stats = all[provider] ?: ProviderStats()
            // Recent playback matters most: halve old evidence beyond 20 samples.
            val scale = if (stats.playbackSuccesses + stats.playbackFailures >= 20) 2 else 1
            all[provider] = stats.copy(
                playbackSuccesses = stats.playbackSuccesses / scale + if (success) 1 else 0,
                playbackFailures = stats.playbackFailures / scale + if (success) 0 else 1,
            )
        }
        persist()
    }

    fun playbackPenalty(provider: String): Double = stats(provider).playbackPenalty

    /** A search that found something resets the streak; persisted with the next [persist]. */
    fun recordSearch(provider: String, found: Boolean) = synchronized(this) {
        val all = state()
        val stats = all[provider] ?: ProviderStats()
        all[provider] = stats.copy(emptySearches = if (found) 0 else stats.emptySearches + 1, lastSearch = System.currentTimeMillis())
    }

    /**
     * A provider whose last [DEAD_SEARCH_STREAK] searches all came back empty (broken site, changed
     * domain) is skipped for [SEARCH_RETRY_MS], then tried again: an extension update can fix it.
     */
    fun searchUsable(provider: String, now: Long = System.currentTimeMillis()): Boolean {
        val stats = stats(provider)
        return stats.emptySearches < DEAD_SEARCH_STREAK || now - stats.lastSearch > SEARCH_RETRY_MS
    }

    const val DEAD_SEARCH_STREAK = 12
    const val SEARCH_RETRY_MS = 12 * 60 * 60 * 1000L

    fun persist() {
        val snapshot = synchronized(this) { state().toMap() }
        runCatching { AggregationPlatform.store.set(KEY, Saved(snapshot)) }
    }

    /** Higher is better: expected successes per second of waiting. */
    fun priority(provider: String): Double = stats(provider).let { it.successRate / (it.expectedMs + 500.0) }

    /** Forget everything, e.g. for tests. */
    fun reset() = synchronized(this) { loaded = mutableMapOf() }
}
