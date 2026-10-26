package com.lagradost.cloudstream3.ui.search.aggregation

import com.lagradost.cloudstream4.domain.media.ProviderBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

/** Search never waits for this work. Limit fan-out and propagate cancellation to extension calls. */
class SearchMetadataEnricher(
    concurrency: Int = 2,
    private val timeoutMs: Long = 5_000,
) {
    private val semaphore = Semaphore(concurrency)

    suspend fun enrich(
        bindings: List<ProviderBinding>,
        load: suspend (ProviderBinding) -> Boolean,
        onUpdated: suspend () -> Unit,
    ) = coroutineScope {
        bindings.map { binding ->
            async {
                val changed = semaphore.withPermit {
                    try {
                        withTimeoutOrNull(timeoutMs) { load(binding) } == true
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                }
                coroutineContext.ensureActive()
                if (changed) onUpdated()
            }
        }.awaitAll()
        Unit
    }
}
