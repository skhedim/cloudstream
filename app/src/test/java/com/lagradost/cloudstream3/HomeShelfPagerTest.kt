package com.lagradost.cloudstream3

import android.app.Application
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.home.aggregation.*
import com.lagradost.cloudstream4.domain.media.ProviderKey
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class HomeShelfPagerTest {
    private fun repo(name: String) = APIRepository(object : MainAPI() {
        override var name = name
        override var mainUrl = "https://$name.invalid"
        override val mainPage = listOf(mainPage("films", "Films"), mainPage("series", "Series"))
    })
    private fun movie(repo: APIRepository, id: Int) = repo.api.newMovieSearchResponse("Fixture $id", "/$id") { year = 2026 }
    private fun result(repo: APIRepository, cards: List<SearchResponse>, index: Int = 0) : AggregatedHomeLoader.Result {
        val sections = listOf(HomePageList(repo.mainPage[index].name, cards))
        return AggregatedHomeLoader.Result(HomeFeed(ProviderKey(repo.name, repo.mainUrl), "Fixture", sections), true,
            pages = listOf(AggregatedHomeLoader.Page(index, 1, sections, true)))
    }
    @Test fun initialLoadRetainsMoreThanTheCarouselPreviewAndKeepsPaginationWhenCached() = runBlocking {
        val repo = repo("first")
        val cards = (1..70).map { movie(repo, it) }
        val loader = AggregatedHomeLoader(requestPlan = { listOf(0) })
        var result: AggregatedHomeLoader.Result? = null
        loader.load(listOf(repo), true, { rows, _ -> result = rows.single() }) { _, _ ->
            Resource.Success(listOf(newHomePageResponse(HomePageList("Films", cards), true)))
        }
        assertEquals(70, result!!.feed.sections.single().list.size)
        assertTrue(result!!.pages.single().hasNext)
        loader.load(listOf(repo), false, { rows, _ -> result = rows.single() }) { _, _ -> error("Should use cache") }
        assertTrue(result!!.cached)
        assertTrue(HomeShelfPager(listOf(repo), listOf(result!!)).hasMore(cards))
    }
    @Test fun expandedShelfCanContainMoreThanThePreviousEightyCardLimit() {
        val a = repo("a"); val b = repo("b")
        val feeds = listOf(result(a, (1..70).map { movie(a, it) }).feed,
            result(b, (71..140).map { movie(b, it) }).feed)
        val cards = AggregatedHomeProjector().project(feeds).single { it.shelf == HomeShelf.Movies }.cards
        assertEquals(140, cards.size)
        assertEquals(140, cards.map { it.media.id }.distinct().size)
    }
    @Test fun pagingUsesOnlyContributingSectionsDeduplicatesAndStopsRepeatedPages() = runBlocking {
        val films = repo("films"); val series = repo("series")
        val first = movie(films, 1)
        val pager = HomeShelfPager(listOf(films, series), listOf(result(films, listOf(first)), result(series, listOf(movie(series, 5)), 1)))
        val canonical = AggregatedHomeProjector().project(listOf(result(films, listOf(first)).feed))
            .single { it.shelf == HomeShelf.Movies }.cards
        var calls = 0
        val fetch: suspend (APIRepository, Int, Int) -> Resource<List<HomePageResponse?>> = { repo, page, index ->
            assertSame(films, repo); assertEquals(0, index); assertEquals(2 + calls, page); calls++
            Resource.Success(listOf(newHomePageResponse(HomePageList("Films", listOf(first, movie(films, 2), movie(films, 2))), true)))
        }
        pager.loadMore(canonical, fetch)
        assertEquals(listOf(first.url, movie(films, 2).url), pager.feeds().first().sections.flatMap { it.list }.map { it.url })
        assertTrue(pager.hasMore(canonical))
        pager.loadMore(canonical, fetch)
        assertFalse(pager.hasMore(canonical))
        pager.loadMore(canonical, fetch)
        assertEquals(2, calls)
        assertEquals(1, pager.feeds().last().sections.single().list.size)
    }
    @Test fun aTransientFailurePreservesCardsAndCanRetryTheSamePage() = runBlocking {
        val repo = repo("retry"); val cards = listOf(movie(repo, 1))
        val pager = HomeShelfPager(listOf(repo), listOf(result(repo, cards)))
        pager.loadMore(cards) { _, _, _ -> Resource.Failure(false, "Offline fixture") }
        assertTrue(pager.failed); assertTrue(pager.hasMore(cards))
        assertEquals(cards, pager.feeds().single().sections.single().list)
        pager.loadMore(cards) { _, page, _ ->
            assertEquals(2, page)
            Resource.Success(listOf(newHomePageResponse(HomePageList("Films", listOf(movie(repo, 2))), false)))
        }
        assertFalse(pager.failed); assertFalse(pager.hasMore(cards))
        assertEquals(2, pager.feeds().single().sections.sumOf { it.list.size })
    }
    @Test fun timeoutIsRetryableAndCancellationCannotAppendPartialPages() = runBlocking {
        val repo = repo("timeout"); val cards = listOf(movie(repo, 1))
        val pager = HomeShelfPager(listOf(repo), listOf(result(repo, cards)), timeoutMs = 30)
        pager.loadMore(cards) { _, _, _ -> delay(Long.MAX_VALUE); error("Unreachable") }
        assertTrue(pager.failed); assertTrue(pager.hasMore(cards))
        val started = CompletableDeferred<Unit>()
        val request = launch { pager.loadMore(cards) { _, _, _ -> started.complete(Unit); delay(Long.MAX_VALUE); error("Unreachable") } }
        started.await(); request.cancelAndJoin()
        assertEquals(cards, pager.feeds().single().sections.single().list)
        assertTrue(pager.hasMore(cards))
    }
}
