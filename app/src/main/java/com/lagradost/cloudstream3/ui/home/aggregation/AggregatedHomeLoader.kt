package com.lagradost.cloudstream3.ui.home.aggregation

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream4.domain.media.ProviderKey
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Bounded first-page discovery. A failed section never discards earlier sections. */
class AggregatedHomeLoader(
    private val concurrency: Int = 4,
    private val sectionTimeoutMs: Long = 8_000,
    private val providerTimeoutMs: Long = 20_000,
    private val totalTimeoutMs: Long = 45_000,
    private val requestPlan: (List<MainPageData>) -> List<Int> = { HomeFeedPolicy.requests(it) },
) {
    data class Page(val requestIndex: Int, val number: Int, val sections: List<HomePageList>, val hasNext: Boolean)
    data class Result(val feed: HomeFeed, val completed: Boolean, val cached: Boolean = false,
        val requested: Int = 0, val attempted: Int = 0, val succeeded: Int = 0, val timedOut: Int = 0,
        val pages: List<Page> = emptyList())
    private data class Key(val provider: ProviderKey, val implementation: String, val pages: List<MainPageData>, val requests: List<Int>)
    private data class Cached(val result: Result, val at: Long)
    companion object {
        private val cache = linkedMapOf<Key, Cached>()
        @Synchronized private fun saved(key: Key) = cache[key]?.takeIf { System.currentTimeMillis() - it.at < 10 * 60_000 }
        @Synchronized private fun save(key: Key, result: Result) {
            cache[key] = Cached(result, System.currentTimeMillis())
            while (cache.size > 60) cache.remove(cache.keys.first())
        }
        @Synchronized private fun clear(key: Key) { cache.remove(key) }
    }

    suspend fun load(
        repos: List<APIRepository>, refresh: Boolean,
        publish: suspend (List<Result>, Int) -> Unit,
        fetch: suspend (APIRepository, Int) -> Resource<List<HomePageResponse?>> = { repo, index -> repo.getMainPage(1, index) },
    ) = coroutineScope {
        val semaphore = Semaphore(concurrency)
        val results = arrayOfNulls<Result>(repos.size)
        val publishLock = kotlinx.coroutines.sync.Mutex()
        val deadline = System.nanoTime() + totalTimeoutMs * 1_000_000
        repos.mapIndexed { ordinal, repo -> async(Dispatchers.IO) {
            val key = Key(ProviderKey(repo.name, repo.mainUrl), repo.api.javaClass.name, repo.mainPage.toList(), requestPlan(repo.mainPage))
            val sections = mutableListOf<HomePageList>()
            val pages = mutableListOf<Page>()
            var complete = true
            var cached = false
            var attempted = 0
            var succeeded = 0
            var timedOut = 0
            var activeNanos = 0L
            try {
                if (refresh) clear(key)
                val saved = saved(key)
                if (saved != null) {
                    sections += saved.result.feed.sections
                    pages += saved.result.pages
                    cached = true
                    attempted = saved.result.attempted; succeeded = saved.result.succeeded
                } else withTimeoutOrNull(totalTimeoutMs) {
                    for ((requestNumber, index) in key.requests.withIndex()) {
                        if (requestNumber > 0 && repo.api.sequentialMainPage) {
                            val start = System.nanoTime()
                            delay(repo.api.sequentialMainPageDelay)
                            activeNanos += System.nanoTime() - start
                        }
                        // A permit covers one section, not an entire provider. FIFO semaphore
                        // waiters get a first request before a busy provider acquires its next one.
                        val proceed = semaphore.withPermit {
                            val remaining = (deadline - System.nanoTime()) / 1_000_000
                            val budget = providerTimeoutMs - activeNanos / 1_000_000
                            if (remaining <= 0 || budget <= 0) return@withPermit false
                            val start = System.nanoTime()
                            attempted++
                            val response = try {
                                withTimeoutOrNull(minOf(sectionTimeoutMs, budget, remaining)) { fetch(repo, index) }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { Resource.Failure(false, "Extension request failed") }
                            activeNanos += System.nanoTime() - start
                            ensureActive()
                            if (response is Resource.Success) {
                                succeeded++
                                val responses = response.value.filterNotNull()
                                val loaded = responses.flatMap { it.items }.map { list ->
                                    val label = repo.mainPage[index].name + " " + list.name
                                    list.copy(name = label, list = list.list.take(120).toList())
                                }
                                sections += loaded
                                pages += Page(index, 1, loaded, responses.any { it.hasNext } && loaded.any { it.list.isNotEmpty() })
                            } else {
                                complete = false
                                if (response == null) timedOut++
                            }
                            true
                        }
                        if (!proceed) { complete = false; break }
                    }
                    true
                }.also { if (it != true) complete = false }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { complete = false }
            ensureActive()
            val result = Result(HomeFeed(key.provider, key.implementation, sections.toList(),
                repo.api.supportedTypes.isNotEmpty() && repo.api.supportedTypes.all {
                    it in setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.Cartoon)
                }), complete, cached, key.requests.size, attempted, succeeded, timedOut, pages.toList())
            if (complete && !cached && sections.any { it.list.isNotEmpty() }) save(key, result)
            publishLock.lock()
            try {
                ensureActive()
                results[ordinal] = result
                publish(results.filterNotNull(), results.count { it != null })
            } finally { publishLock.unlock() }
        } }.awaitAll()
    }
}
