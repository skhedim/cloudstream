package com.lagradost.cloudstream3

import android.app.Application
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.home.aggregation.*
import com.lagradost.cloudstream3.ui.result.ResultFragment
import com.lagradost.cloudstream3.ui.result.TrailerSearch
import com.lagradost.cloudstream4.domain.media.ProviderKey
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class AggregatedHomeTest {
    companion object { private val counter = AtomicInteger() }
    private fun api(pages: List<MainPageData> = listOf(mainPage("opaque", "Derniers films"))) = object : MainAPI() {
        override var name = "Home fixture ${counter.incrementAndGet()}"
        override var mainUrl = "https://home${counter.get()}.invalid"
        override val mainPage = pages
        override val hasMainPage = true
    }
    private fun feed(api: MainAPI, vararg sections: HomePageList) = HomeFeed(ProviderKey(api.name, api.mainUrl), api.javaClass.name, sections.toList())
    private fun movie(api: MainAPI, title: String = "A title", year: Int = 2026) = api.newMovieSearchResponse(title, "/$year/$title") { this.year = year }

    @Test fun requestPlanPrioritizesRecentContentAndDoesNotFetchEveryGenre() {
        val pages = listOf(mainPage("popular", "Films populaires"), mainPage("action", "Action"),
            mainPage("new-series", "Dernières séries"), mainPage("new-film", "Nouveautés films"),
            mainPage("anime", "Recently added anime"), mainPage("other", "Comédies"))
        val indexes = HomeFeedPolicy.requests(pages)
        assertEquals(4, indexes.size)
        assertEquals(setOf(2,3,4), indexes.take(3).toSet())
        assertTrue(HomeFeedPolicy.recent("Ajouts récents"))
        assertFalse(HomeFeedPolicy.recent("Les mieux notés"))
    }

    @Test fun oneIdentityPassKeepsAllSourcesAcrossRowsAndNavigationUsesTheCommonDetail() {
        val a=api(); val b=api(); val film=movie(a); val other=movie(b)
        val rows=AggregatedHomeProjector().project(listOf(feed(a,HomePageList("Derniers films",listOf(film))),
            feed(b,HomePageList("Films",listOf(other)))))
        val recent=rows.single { it.shelf==HomeShelf.Recent }.cards.single()
        val movies=rows.single { it.shelf==HomeShelf.Movies }.cards.single()
        assertSame(recent,movies)
        assertEquals(2,recent.media.bindings.size)
        assertTrue(ResultFragment.newInstance(recent).getString("canonicalDetailV1")!!.contains(b.name))
        assertTrue(rows.none { it.shelf==HomeShelf.Action })
    }

    @Test fun newerFeedsLeadWithoutSortingByReleaseYearAndRemakesStaySeparate() {
        val a=api();val b=api()
        val rows=AggregatedHomeProjector().project(listOf(
            feed(a,HomePageList("Films populaires", listOf(movie(a,"Remake",2026)))),
            feed(b,HomePageList("Derniers ajouts",listOf(movie(b,"Remake",1990))))))
        assertEquals(1,rows.single { it.shelf==HomeShelf.Recent }.cards.size)
        val films=rows.single { it.shelf==HomeShelf.Movies }.cards
        assertEquals(listOf(1990,2026),films.map { it.media.year })
    }

    @Test fun genreRowsUseOnlyExplicitSectionsAndUnsafeMediaAreExcluded() {
        val a=api();val titles=(1..3).map { movie(a,"Film $it") }
        val rows=AggregatedHomeProjector().project(listOf(feed(a,HomePageList("Nouveautés · Comédie",titles),
            HomePageList("Derniers films",listOf(a.newMovieSearchResponse("Hidden", "/hidden", TvType.Music))))))
        assertEquals(3,rows.single { it.shelf==HomeShelf.Comedy }.cards.size)
        assertTrue(rows.flatMap { it.cards }.none { it.name=="Hidden" })
        assertTrue(rows.none { it.shelf==HomeShelf.Action })
    }

    @Test fun boundedParallelLoadingKeepsProviderOrderDespiteReversedCompletionAndCachesCompletedFeeds() = runBlocking {
        val repos=(1..4).map { APIRepository(api()) };val active=AtomicInteger();val peak=AtomicInteger()
        val snapshots=mutableListOf<List<AggregatedHomeLoader.Result>>()
        var calls=0
        val fetch:suspend(APIRepository,Int)->Resource<List<HomePageResponse?>> = { repo, _ ->
            synchronized(this) { calls++ };val n=active.incrementAndGet();peak.accumulateAndGet(n,::maxOf)
            try { delay(if(repo==repos.first())80 else 5)
                Resource.Success(listOf(newHomePageResponse(HomePageList("Films",listOf(movie(repo.api,repo.name))))))
            } finally { active.decrementAndGet() }
        }
        val loader=AggregatedHomeLoader(2,1000,2000,3000)
        loader.load(repos,true,{ result,_->snapshots+=result },fetch)
        assertEquals(2,peak.get());assertEquals(4,calls)
        assertEquals(repos.map { it.name },snapshots.last().map { it.feed.provider.name })
        loader.load(repos,false,{ result,_->assertTrue(result.all { it.cached }) },fetch)
        assertEquals(4,calls)
        loader.load(listOf(repos.first()),true,{_,_->},fetch)
        assertEquals(5,calls)
    }

    @Test fun timeoutsAndFailedSectionsPreservePartialItemsAndAreRetried() = runBlocking {
        val good=APIRepository(api(listOf(mainPage("one","Derniers films"),mainPage("two","Dernières séries"))))
        val slow=APIRepository(api());val calls=AtomicInteger();var latest=listOf<AggregatedHomeLoader.Result>()
        val loader=AggregatedHomeLoader(2,50,200,300)
        repeat(2) {
            loader.load(listOf(good,slow),false,{r,_->latest=r}) { repo,index->
                calls.incrementAndGet()
                if(repo==slow) { delay(Long.MAX_VALUE); error("unreachable") }
                else if(index==0) Resource.Success(listOf(newHomePageResponse(HomePageList("Films",listOf(movie(repo.api))))))
                else Resource.Failure(false,"Fixture failure")
            }
            assertEquals(1,latest.first().feed.sections.size)
            assertFalse(latest.first().completed);assertFalse(latest.last().completed)
            assertTrue(latest.none { it.cached })
        }
        assertEquals(6,calls.get())
    }

    @Test fun cachedFeedsCannotCrossDifferentCategoryRequestPlans() = runBlocking {
        val repo = APIRepository(api(listOf(mainPage("films", "Films"), mainPage("clips", "Clips"))))
        var calls = 0
        val fetch: suspend (APIRepository, Int) -> Resource<List<HomePageResponse?>> = { r, index ->
            calls++
            Resource.Success(listOf(newHomePageResponse(HomePageList("Page $index", listOf(movie(r.api, "Item $index"))))))
        }
        val films = AggregatedHomeLoader(requestPlan = { listOf(0) })
        val clips = AggregatedHomeLoader(requestPlan = { listOf(1) })
        films.load(listOf(repo), true, { result, _ -> assertFalse(result.single().cached) }, fetch)
        clips.load(listOf(repo), false, { result, _ ->
            assertFalse(result.single().cached)
            assertEquals("Item 1", result.single().feed.sections.single().list.single().name)
        }, fetch)
        films.load(listOf(repo), false, { result, _ ->
            assertTrue(result.single().cached)
            assertEquals("Item 0", result.single().feed.sections.single().list.single().name)
        }, fetch)
        assertEquals(2, calls)
    }

    @Test fun cancellationStopsQueuedProvidersAndCannotPublishIntoAnotherHomeLoad() = runBlocking {
        val started=CompletableDeferred<Unit>();val active=AtomicInteger();var published=false
        val job=launch {
            AggregatedHomeLoader().load((1..10).map { APIRepository(api()) },true,{_,_->published=true}) { _,_->
                active.incrementAndGet();started.complete(Unit)
                try { delay(Long.MAX_VALUE);Resource.Success(emptyList()) } finally { active.decrementAndGet() }
            }
        }
        started.await();job.cancelAndJoin()
        assertEquals(0,active.get());assertFalse(published)
    }
    @Test fun manySectionProvidersReleaseTheirWorkerSoLaterSourcesGetAnInitialRequest() = runBlocking {
        val pages = (1..4).map { mainPage("page$it", "Page $it") }
        val repos = (1..10).map { APIRepository(api(pages)) }
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val calls = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Int>>())
        var results = emptyList<AggregatedHomeLoader.Result>()
        val job = launch {
            AggregatedHomeLoader(1, 2000, 3000, 5000, requestPlan = { it.indices.toList() })
                .load(repos, true, { snapshot, _ -> results = snapshot }) { repo, index ->
                    calls += repo.name to index
                    if (calls.size == 1) { firstStarted.complete(Unit); releaseFirst.await() }
                    delay(5)
                    Resource.Success(listOf(newHomePageResponse(HomePageList("Latest", listOf(movie(repo.api))))))
                }
        }
        firstStarted.await()
        delay(100) // All other providers queue behind the first held permit.
        releaseFirst.complete(Unit)
        job.join()
        assertEquals(10, calls.take(10).map { it.first }.distinct().size)
        assertTrue(calls.take(10).all { it.second == 0 })
        assertEquals(10, results.size)
        assertTrue(results.all { it.completed && it.attempted == 4 && it.succeeded == 4 })
    }

    @Test fun animationSectionsCanClassifyAShelfWithoutRewritingTheMediaIdentity() {
        val a=api()
        val response = a.newTvSeriesSearchResponse("Animation title", "/animation") { year = 2026 }
        val rows = AggregatedHomeProjector().project(listOf(
            feed(a, HomePageList("Derniers animés", listOf(response)))
        ))
        val anime=rows.single { it.shelf==HomeShelf.Anime }.cards.single()
        assertSame(anime,rows.single { it.shelf==HomeShelf.Series }.cards.single())
        assertEquals(com.lagradost.cloudstream4.domain.media.MediaKind.Series,anime.media.kind)
    }

    @Test fun boundedPosterRefinementMergesCompatibleUnknownYearsWithoutATitleOnlyGuess() = runBlocking {
        val a=api();val b=api()
        val first=a.newMovieSearchResponse("Same title","/first") { posterUrl="https://image.tmdb.org/t/p/w500/First.jpg" }
        val second=b.newMovieSearchResponse("Same title","/second") { posterUrl="https://image.tmdb.org/t/p/w500/Second.jpg" }
        val feeds=listOf(feed(a,HomePageList("Derniers films",listOf(first))),feed(b,HomePageList("Derniers films",listOf(second))))
        val projector=AggregatedHomeProjector()
        assertEquals(2,projector.project(feeds).first().cards.size)
        val requests=AtomicInteger()
        assertTrue(projector.refine {
            requests.incrementAndGet()
            com.lagradost.cloudstream4.domain.media.ArtworkSignature(List(16*24*3) { index -> index%255 },2.0/3)
        })
        assertEquals(2,requests.get())
        val card=projector.project(feeds).first().cards.single()
        assertEquals(2,card.media.bindings.size)
    }

    @Test fun unresolvedDuplicateMetadataTargetsTheMissingYearAndKeepsOriginalProviderObjectsUntouched() = runBlocking {
        val a=api();val b=api()
        val known=movie(a,"An ambiguous film",2026)
        val incomplete=b.newMovieSearchResponse("An ambiguous film","/ambiguous")
        val feeds=listOf(feed(a,HomePageList("Derniers films",listOf(known))),
            feed(b,HomePageList("Derniers films",listOf(incomplete))))
        val projector=AggregatedHomeProjector()
        assertEquals(2,projector.project(feeds).first().cards.size)
        assertEquals(b.name,projector.detailRequests.first().provider.name)
        val detail=b.newMovieLoadResponse("An ambiguous film",incomplete.url,TvType.Movie,"opaque") { year=2026 }
        com.lagradost.cloudstream4.aggregation.SearchMediaMetadataCache.remember(b,incomplete.url,detail)
        assertEquals(2,projector.project(feeds).first().cards.single().media.bindings.size)
        assertNull(incomplete.year)
    }

    @Test fun trailerSearchEncodesTheActualTitleYearAndDoesNotTurnTitleTextIntoUrlParameters() {
        val intent=TrailerSearch.intent("L’été & moi ? #1",2026,true)
        assertEquals("android.intent.action.VIEW",intent.action)
        assertEquals("www.youtube.com",intent.data!!.host)
        assertEquals("/results",intent.data!!.path)
        assertEquals(setOf("search_query"),intent.data!!.queryParameterNames)
        assertEquals("L’été & moi ? #1 2026 bande annonce officielle",intent.data!!.getQueryParameter("search_query"))
        assertEquals("Show official trailer",TrailerSearch.intent("Show",null,false).data!!.getQueryParameter("search_query"))
    }

    @Test fun weeklyTrendsMakeATopTenAndATrendingShelfOfAvailableWorksOnly() {
        val a = api()
        val titles = (1..14).map { movie(a, "Film $it", 2026).apply { posterUrl = "https://image.tmdb.org/t/p/w500/p$it.jpg" } }
        // Ranks come from TMDB IDs on the cards (here: canonical TMDB URLs are not used, so attach IDs via the trend map keys).
        val withIds = titles.mapIndexed { i, film -> a.newMovieSearchResponse(film.name, "https://www.themoviedb.org/movie/${1000 + i}", fix = false) { year = 2026 } }
        val trending = (0 until 14).associate {
            com.lagradost.cloudstream4.domain.media.ExternalMediaId(com.lagradost.cloudstream4.domain.media.ExternalIdNamespace.TmdbMovie,
                "${1000 + 13 - it}") to it
        } + (com.lagradost.cloudstream4.domain.media.ExternalMediaId(com.lagradost.cloudstream4.domain.media.ExternalIdNamespace.TmdbMovie, "999999") to 0)
        val rows = AggregatedHomeProjector().project(listOf(feed(a, HomePageList("Films", withIds))), trending = trending)
        val top = rows.single { it.shelf == HomeShelf.Top10 }.cards
        assertEquals(10, top.size)
        assertEquals("Film 14", top.first().name)
        val trend = rows.single { it.shelf == HomeShelf.Trending }.cards
        assertEquals(4, trend.size)
        assertTrue(trend.none { it in top })
        assertEquals(HomeShelf.Top10, rows.first().shelf)
    }

    @Test fun cardsOnlyAvailableFromForeignCataloguesAreLeftOut() {
        val french = api(); val foreign = api()
        val rows = AggregatedHomeProjector().project(listOf(
            feed(french, HomePageList("Films", listOf(movie(french, "Shared", 2025)))),
            feed(foreign, HomePageList("Films", listOf(movie(foreign, "Shared", 2025), movie(foreign, "Foreign only", 2025))))),
            native = setOf(ProviderKey(french.name, french.mainUrl)))
        val films = rows.single { it.shelf == HomeShelf.Movies }.cards
        assertEquals(listOf("Shared"), films.map { it.name })
        assertEquals(2, films.single().media.bindings.size)
    }
}
