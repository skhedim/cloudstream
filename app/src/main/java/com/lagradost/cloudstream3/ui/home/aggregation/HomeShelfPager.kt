package com.lagradost.cloudstream3.ui.home.aggregation

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.search.aggregation.AggregatedSearchResponse
import com.lagradost.cloudstream4.domain.media.ProviderKey
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** On-demand paging of the feeds that contributed to a shelf. No extension API changes. */
class HomeShelfPager(
    private val repos: List<APIRepository>,
    results: List<AggregatedHomeLoader.Result>,
    private val timeoutMs: Long = 8_000,
) {
    private data class Cursor(val provider: ProviderKey, val request: Int, var page: Int,
        var hasNext: Boolean, val seen: MutableSet<String>)
    private val lock = Mutex()
    private val feeds = results.map { it.feed }.toMutableList()
    private val cursors = results.flatMap { result -> result.pages.map { page ->
        Cursor(result.feed.provider, page.requestIndex, page.number, page.hasNext,
            page.sections.flatMap { it.list }.map { it.url }.toMutableSet())
    } }
    var failed: Boolean = false
        private set

    fun feeds(): List<HomeFeed> = feeds.toList()
    private fun keys(cards: List<SearchResponse>) = cards.flatMap { card ->
        if (card is AggregatedSearchResponse) card.media.bindings.map { it.provider.name to it.url }
        else listOf(card.apiName to card.url)
    }.toSet()
    private fun eligible(cards: List<SearchResponse>): List<Cursor> {
        val keys = keys(cards)
        return cursors.filter { cursor -> cursor.hasNext && cursor.page < 20 &&
            cursor.seen.any { (cursor.provider.name to it) in keys } }
    }
    fun hasMore(cards: List<SearchResponse>) = eligible(cards).isNotEmpty()

    suspend fun loadMore(cards: List<SearchResponse>,
        fetch: suspend (APIRepository, Int, Int) -> Resource<List<HomePageResponse?>> = { repo, page, index -> repo.getMainPage(page, index) },
    ): List<HomeFeed> = lock.withLock {
        // One request per provider at a time, up to four providers. Lowest pages go first.
        val targets = eligible(cards).sortedBy { it.page }.distinctBy { it.provider }.take(4)
        val responses = coroutineScope { targets.map { cursor -> async(Dispatchers.IO) {
            val repo = repos.firstOrNull { it.name == cursor.provider.name && it.mainUrl == cursor.provider.mainUrl }
            cursor to try {
                if (repo == null) null else withTimeoutOrNull(timeoutMs) {
                    repo.waitForHomeDelay()
                    fetch(repo, cursor.page + 1, cursor.request)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        } }.awaitAll() }
        currentCoroutineContext().ensureActive()
        failed = false
        responses.forEach { (cursor, response) ->
            if (response !is Resource.Success) {
                failed = true // Preserve the cursor so the user can retry a transient failure.
                return@forEach
            }
            val repo = repos.first { it.name == cursor.provider.name && it.mainUrl == cursor.provider.mainUrl }
            val pages = response.value.filterNotNull()
            val sections = pages.flatMap { it.items }.map { list -> list.copy(
                name = repo.mainPage[cursor.request].name + " " + list.name,
                list = list.list.take(120).distinctBy { it.url }.filter { it.url !in cursor.seen }) }
            val added = sections.flatMap { it.list }.map { it.url }
            cursor.page++
            cursor.hasNext = pages.any { it.hasNext } && added.isNotEmpty()
            cursor.seen += added
            val index = feeds.indexOfFirst { it.provider == cursor.provider }
            if (index >= 0) feeds[index] = feeds[index].copy(sections = feeds[index].sections + sections)
        }
        feeds()
    }
}
