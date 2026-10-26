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
) {
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
        )
    }

    companion object { const val UNKNOWN_MS = 8_000.0 }
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

    fun persist() {
        val snapshot = synchronized(this) { state().toMap() }
        runCatching { AggregationPlatform.store.set(KEY, Saved(snapshot)) }
    }

    /** Higher is better: expected successes per second of waiting. */
    fun priority(provider: String): Double = stats(provider).let { it.successRate / (it.expectedMs + 500.0) }

    /** Forget everything, e.g. for tests. */
    fun reset() = synchronized(this) { loaded = mutableMapOf() }
}
