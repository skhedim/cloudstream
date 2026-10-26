package com.lagradost.cloudstream4.aggregation

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

data class LoadedDetail(val binding: DetailBinding, val response: LoadResponse, val api: MainAPI)

/**
 * Failures remain local to a binding. Queued requests share a total deadline. Bindings of the
 * same extension (season entries, mirrors) share a smaller per-provider limit.
 */
class CanonicalDetailLoader(
    private val concurrency: Int = 6,
    private val bindingTimeoutMs: Long = 12_000,
    private val totalTimeoutMs: Long = 30_000,
    private val perProvider: Int = 2,
) {
    /**
     * [onProgress] receives every successful snapshot so far, in request order, as soon as each
     * binding completes. The returned list is the final snapshot.
     */
    suspend fun load(
        request: CanonicalDetailRequest,
        onProgress: (suspend (List<LoadedDetail>) -> Unit)? = null,
        fetch: suspend (DetailBinding) -> LoadedDetail? = ::fetchBinding,
    ): List<LoadedDetail> = coroutineScope {
        val limiter = ProviderLimiter(concurrency, perProvider)
        val deadline = System.nanoTime() + totalTimeoutMs * 1_000_000
        val bindings = request.bindings.distinct()
        val done = arrayOfNulls<LoadedDetail>(bindings.size)
        val progress = Mutex()
        bindings.mapIndexed { index, binding ->
            async {
                val loaded = try {
                    withTimeoutOrNull(totalTimeoutMs) {
                        limiter.withPermit(binding.providerId ?: binding.provider.name) {
                            val remaining = (deadline - System.nanoTime()) / 1_000_000
                            if (remaining <= 0) null else
                                withTimeoutOrNull(minOf(bindingTimeoutMs, remaining)) { fetch(binding) }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                ensureActive()
                if (loaded != null && onProgress != null) progress.withLock {
                    done[index] = loaded
                    onProgress(done.filterNotNull())
                }
                loaded
            }
        }.awaitAll().filterNotNull()
    }

    companion object {
        suspend fun fetchBinding(binding: DetailBinding): LoadedDetail? {
            val provider = AggregationPlatform.registry.resolve(binding.provider, binding.providerId) ?: return null
            val response = AggregationPlatform.gateway.load(provider.api, binding.url) ?: return null
            currentCoroutineContext().ensureActive()
            return LoadedDetail(binding, response, provider.api)
        }
    }
}
