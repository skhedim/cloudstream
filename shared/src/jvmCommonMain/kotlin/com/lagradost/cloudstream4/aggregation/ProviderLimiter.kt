package com.lagradost.cloudstream4.aggregation

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Bounded fan-out: a global limit plus a per-provider limit, so one extension listed several
 * times (season entries, dub/sub tracks) cannot occupy every slot or hammer its own site.
 * The provider permit is taken first, so a waiting request never holds a global slot.
 */
class ProviderLimiter(global: Int, private val perProvider: Int) {
    private val globalPermits = Semaphore(global)
    private val providers = mutableMapOf<String, Semaphore>()

    private fun provider(key: String): Semaphore = synchronized(providers) {
        providers.getOrPut(key) { Semaphore(perProvider) }
    }

    suspend fun <T> withPermit(providerKey: String, block: suspend () -> T): T =
        provider(providerKey).withPermit { globalPermits.withPermit { block() } }
}
