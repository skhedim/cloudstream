package com.lagradost.cloudstream3.ui.result.aggregation

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.APIRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class LoadedDetail(val binding: DetailBinding, val response: LoadResponse, val repo: APIRepository)

/** Failures remain local to a binding. Queued requests share a total deadline. */
class CanonicalDetailLoader(
    private val concurrency: Int = 3,
    private val bindingTimeoutMs: Long = 12_000,
    private val totalTimeoutMs: Long = 30_000,
) {
    suspend fun load(
        request: CanonicalDetailRequest,
        fetch: suspend (DetailBinding) -> LoadedDetail? = ::fetchBinding,
    ): List<LoadedDetail> = coroutineScope {
        val semaphore = Semaphore(concurrency)
        val deadline = System.nanoTime() + totalTimeoutMs * 1_000_000
        request.bindings.distinct().map { binding ->
            async {
                try {
                    withTimeoutOrNull(totalTimeoutMs) {
                        semaphore.withPermit {
                            val remaining = (deadline - System.nanoTime()) / 1_000_000
                            if (remaining <= 0) null else
                                withTimeoutOrNull(minOf(bindingTimeoutMs, remaining)) { fetch(binding) }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }.also { ensureActive() }
            }
        }.awaitAll().filterNotNull()
    }

    companion object {
        private suspend fun fetchBinding(binding: DetailBinding): LoadedDetail? {
            // Name is the extension's routing contract; URL is an additional disambiguator.
            val api = APIHolder.apis.firstOrNull {
                it.name == binding.provider.name && it.mainUrl == binding.provider.mainUrl
            } ?: APIHolder.getApiFromNameNull(binding.provider.name) ?: return null
            val repo = APIRepository(api)
            val response = (repo.load(binding.url) as? Resource.Success)?.value ?: return null
            currentCoroutineContext().ensureActive()
            return LoadedDetail(binding, response, repo)
        }
    }
}
