package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.ui.result.aggregation.*
import com.lagradost.cloudstream4.domain.media.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CanonicalDetailLoaderTest {
    private fun request(count: Int) = CanonicalDetailRequest("fixture", "Show", 2020, MediaKind.Series,
        emptySet(), (1..count).map { DetailBinding(ProviderKey("P$it", "https://p$it.example"), "/show") })

    @Test fun boundsParallelismAndIsolatesTimeoutsAndFailures() = runBlocking {
        var active = 0
        var peak = 0
        var completed = 0
        val result = CanonicalDetailLoader(2, 50, 200).load(request(5)) {
            active++; peak = maxOf(peak, active)
            try {
                when (it.provider.name) {
                    "P1" -> delay(Long.MAX_VALUE)
                    "P2" -> error("Failure")
                    else -> { delay(5); completed++ }
                }
                null
            } finally { active-- }
        }
        assertTrue(result.isEmpty())
        assertEquals(2, peak)
        assertEquals(0, active)
        assertEquals(3, completed)
    }

    @Test fun cancellationStopsQueuedAndRunningWorkWithoutPublishing() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var published = false
        var active = 0
        val job = launch {
            CanonicalDetailLoader().load(request(8)) {
                active++; started.complete(Unit)
                try { delay(Long.MAX_VALUE); null } finally { active-- }
            }
            published = true
        }
        started.await()
        job.cancelAndJoin()
        assertFalse(published)
        assertEquals(0, active)
    }

    @Test fun totalDeadlineIncludesTimeWaitingForPermit() = runBlocking {
        var started = 0
        withTimeout(1000) {
            CanonicalDetailLoader(1, 1000, 50).load(request(8)) {
                started++
                delay(Long.MAX_VALUE)
                null
            }
        }
        assertEquals(1, started)
    }
}
