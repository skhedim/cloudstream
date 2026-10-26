package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.ui.search.aggregation.SearchMetadataEnricher
import com.lagradost.cloudstream4.domain.media.ProviderBinding
import com.lagradost.cloudstream4.domain.media.ProviderKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class SearchMetadataEnricherTest {
    private fun bindings(count: Int) = (1..count).map { ProviderBinding(ProviderKey("P$it","https://p$it.example"), "/film") }

    @Test fun concurrentLoadsAreBoundedAndOnlySuccessesPublish() = runBlocking {
        var active = 0
        var peak = 0
        var updates = 0
        SearchMetadataEnricher(concurrency = 2).enrich(bindings(5), {
            active++
            peak = maxOf(peak,active)
            try { delay(5); it.provider.name != "P3" } finally { active-- }
        }, { updates++ })
        assertEquals(2,peak)
        assertEquals(0,active)
        assertEquals(4,updates)
    }

    @Test fun slowAndFailedExtensionsDoNotPreventOtherUpdates() = runBlocking {
        var updates = 0
        withTimeout(2_000) {
            SearchMetadataEnricher(timeoutMs = 30).enrich(bindings(3), {
                when (it.provider.name) {
                    "P1" -> { delay(Long.MAX_VALUE); true }
                    "P2" -> error("Provider failed")
                    else -> true
                }
            }, { updates++ })
        }
        assertEquals(1,updates)
    }

    @Test fun cancellingAQueryCancelsLoadsAndCannotPublishStaleResultsOrLeakPermits() = runBlocking {
        val enricher = SearchMetadataEnricher()
        val started = CompletableDeferred<Unit>()
        var cancelledLoads = 0
        var updates = 0
        val job = launch {
            enricher.enrich(bindings(5), {
                started.complete(Unit)
                try { delay(Long.MAX_VALUE); true } finally { cancelledLoads++ }
            }, { updates++ })
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(cancelledLoads > 0)
        assertEquals(0,updates)
        withTimeout(2_000) { enricher.enrich(bindings(2), { true }, { updates++ }) }
        assertEquals(2,updates)
    }
}
