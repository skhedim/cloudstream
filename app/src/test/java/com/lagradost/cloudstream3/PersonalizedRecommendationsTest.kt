package com.lagradost.cloudstream3

import android.app.Application
import com.lagradost.cloudstream3.ui.home.aggregation.*
import com.lagradost.cloudstream3.ui.result.ResultFragment
import com.lagradost.cloudstream3.ui.search.aggregation.SearchMediaMapper
import com.lagradost.cloudstream4.domain.media.*
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
class PersonalizedRecommendationsTest {
    private fun api(label: String) = object : MainAPI() {
        override var name = "Recommendation fixture $label"
        override var mainUrl = "https://recommendations-$label.invalid"
    }
    private fun movie(api: MainAPI, title: String, year: Int = 2026) = api.newMovieSearchResponse(title, "/$title/$year") { this.year = year }
    private fun source(api: MainAPI, path: String = "/seen") = RecommendationSource(ProviderBinding(ProviderKey(api.name, api.mainUrl), api.mainUrl + path), api.javaClass.name)
    private fun history(api: MainAPI, title: String, time: Long, position: Long = 60_000, year: Int = 2026, completed: Boolean = false, dropped: Boolean = false): RecommendationHistory {
        val response = movie(api, title, year)
        val candidate = SearchMediaMapper.candidate(ProviderKey(api.name, api.mainUrl), response)
        return RecommendationHistory(listOf(candidate), listOf(source(api).copy(binding = candidate.binding)), time, position, completed, dropped)
    }
    private fun feed(api: MainAPI, items: List<SearchResponse>) = HomeFeed(ProviderKey(api.name, api.mainUrl), api.javaClass.name, listOf(HomePageList("Derniers films", items)))

    @Test fun historyUsesRecentDistinctWorksAndCompletedTitlesButSkipsAccidentalLaunchesAndDroppedTitles() {
        val a = api("a"); val b = api("b")
        val plan = RecommendationPlan.from(listOf(
            history(a,"Just opened", 100, 0), history(a,"Accidental", 99, 1_000),
            history(a,"Dropped", 98, dropped = true), history(a,"Seen", 97), history(b,"Seen", 96),
            history(a,"Completed", 95, 0, completed = true), history(a,"Third", 94),
            history(a,"Fourth", 93), history(a,"Fifth", 92)))
        assertEquals(3, plan.seeds.size)
        assertEquals(2, plan.seeds.first().sources.size)
        assertTrue(plan.seeds[1].key.contains("Completed"))
        assertTrue(plan.seeds.none { it.key.contains("Accidental") || it.key.contains("Dropped") || it.key.contains("Fifth") })
        assertEquals(8, plan.excluded.size)
        assertTrue(RecommendationPlan.from(emptyList()).seeds.isEmpty())
    }

    @Test fun sharedRecommendationsWinAndWatchedRemakeIsExcludedWithoutHidingTheOtherYear() {
        val a = api("a"); val b = api("b"); val c = api("c")
        val watched = history(a,"Remake",100,year = 1990)
        val batches = listOf(
            RecommendationBatch("seed1", source(a), listOf(movie(a,"Solo"), movie(a,"Shared"), movie(a,"Remake",1990), movie(a,"Remake",2026))),
            RecommendationBatch("seed2", source(b), listOf(movie(b,"Shared"), movie(b,"Remake",1990))))
        val rows = AggregatedHomeProjector().project(listOf(feed(c,listOf(movie(c,"Shared"),movie(c,"Fresh")))), batches, watched.candidates)
        assertEquals(HomeShelf.Recent, rows.first().shelf)
        val suggestions = rows.single { it.shelf == HomeShelf.ForYou }.cards
        assertEquals(listOf("Shared","Solo","Remake"), suggestions.map { it.name })
        assertEquals(2026, suggestions.last().media.year)
        assertEquals(3, suggestions.first().media.bindings.size)
        assertTrue(ResultFragment.newInstance(suggestions.first()).getString("canonicalDetailV1")!!.contains(b.name))
        assertSame(suggestions.first(), rows.single { it.shelf == HomeShelf.Movies }.cards.first())
        assertTrue(rows.single { it.shelf == HomeShelf.Recent }.cards.none { it.name == "Solo" })
        assertTrue(suggestions.none { it.media.bindings.any { binding -> binding == watched.candidates.single().binding } })
    }

    @Test fun unknownYearsStaySeparateAndRecommendationsDoNotCreateFakeLatestAdditions() {
        val a=api("a");val b=api("b")
        val unknown=a.newMovieSearchResponse("Remake", "/unknown")
        val batch=RecommendationBatch("seed",source(a),listOf(unknown, a.newMovieSearchResponse("Hidden","/hidden",TvType.Music)))
        val rows=AggregatedHomeProjector().project(emptyList(),listOf(batch),history(b,"Remake",0,year=1990).candidates)
        assertEquals(listOf(HomeShelf.ForYou),rows.map { it.shelf })
        assertEquals("Remake",rows.single().cards.single().name)
        assertNull(rows.single().cards.single().media.year)
        assertTrue(AggregatedHomeProjector().project(emptyList()).isEmpty())
    }

    @Test fun aRecommendationAlreadyInTheFeedKeepsItsOriginalPositionInLatestAdditions() {
        val a=api("a")
        val first=movie(a,"First");val second=movie(a,"Second")
        val rows=AggregatedHomeProjector().project(listOf(feed(a,listOf(first,second))),
            listOf(RecommendationBatch("seed",source(a),listOf(first))))
        assertEquals(listOf("First","Second"),rows.single { it.shelf==HomeShelf.Recent }.cards.map { it.name })
    }

    @Test fun recentFilmsCannotDisplaceSeriesAndTheVisibleHistoryTakesPriorityOverCompletedLibraryEntries() {
        val a=api("a")
        fun series(title: String, time: Long, position: Long): RecommendationHistory {
            val response=a.newTvSeriesSearchResponse(title,"/$title") { year=2026 }
            val candidate=SearchMediaMapper.candidate(ProviderKey(a.name,a.mainUrl),response)
            return RecommendationHistory(listOf(candidate),listOf(source(a).copy(binding=candidate.binding)),time,position)
        }
        val movies=(1..5).map { history(a,"Recent film $it",100-it.toLong()) }
        val completed=history(a,"Library only",9999,0,completed=true).copy(fromContinueWatching=false)
        val plan=RecommendationPlan.from(movies+series("South Park",50,46_758)+series("Breaking Bad",40,110_817)+completed)
        assertEquals(listOf("Recent film 1","South Park","Recent film 2","Breaking Bad","Recent film 3"),plan.seeds.map { it.title })
        assertEquals(2,plan.seeds.count { MediaAggregator.family(it.kind)=="series" })
        assertTrue(plan.seeds.none { it.title=="Library only" })
    }

    @Test fun aSingleHorrorFilmCannotFillTheShelfAheadOfOtherWatchedWorks() {
        val a=api("a");val b=api("b");val c=api("c")
        val batches=listOf(
            RecommendationBatch("horror",source(a),(1..24).map { movie(a,"Horror $it") }),
            RecommendationBatch("adventure",source(b),(1..24).map { movie(b,"Adventure $it") }),
            RecommendationBatch("comedy",source(c),(1..2).map { movie(c,"Comedy $it") }))
        val cards=AggregatedHomeProjector().project(emptyList(),batches).single().cards
        assertEquals(listOf("Horror 1","Adventure 1","Comedy 1","Horror 2","Adventure 2","Comedy 2"),cards.take(6).map { it.name })
        assertEquals(42,cards.size)
        assertEquals(20,cards.count { it.name.startsWith("Horror") })
        assertEquals(20,cards.count { it.name.startsWith("Adventure") })
    }

    @Test fun moviesAndSeriesGetIndependentShelvesAndWatchedSeriesRemainExcluded() {
        val a=api("a")
        val seen=a.newTvSeriesSearchResponse("Seen series","/seen-series") { year=2026 }
        val series=a.newTvSeriesSearchResponse("Suggested series","/suggested-series") { year=2026 }
        val anime=a.newAnimeSearchResponse("Suggested anime","/anime",TvType.Anime) { year=2026 }
        val batch=RecommendationBatch("legacy",source(a),listOf(movie(a,"A film"),seen,series,anime))
        val excluded=listOf(SearchMediaMapper.candidate(ProviderKey(a.name,a.mainUrl),seen))
        val rows=AggregatedHomeProjector().project(emptyList(),listOf(batch),excluded)
        assertEquals(listOf("A film"),rows.single { it.shelf==HomeShelf.ForYou }.cards.map { it.name })
        assertEquals(listOf("Suggested series","Suggested anime"),rows.single { it.shelf==HomeShelf.ForYouSeries }.cards.map { it.name })
    }

    @Test fun aSeriesPageReturningOnlyFilmsDoesNotBlockTheNextProvidersActualSeriesRecommendations() = runBlocking {
        val a=api("a");val b=api("b")
        val seed=RecommendationSeed("series",listOf(source(a),source(b)),MediaKind.Series,"Seen series")
        val result=PersonalizedRecommendationsLoader().load("account",listOf(seed),false) { s ->
            if(s.binding.provider.name==a.name) listOf(movie(a,"Unrelated horror"))
            else listOf(b.newTvSeriesSearchResponse("A similar series","/similar") { year=2026 })
        }.single()
        assertEquals(b.name,result.source.binding.provider.name)
        val row=AggregatedHomeProjector().project(emptyList(),listOf(result)).single()
        assertEquals(HomeShelf.ForYouSeries,row.shelf)
        assertEquals("A similar series",row.cards.single().name)
    }

    @Test fun recommendationsMustComeFromTheWatchedWorkRatherThanARedirectedOrDifferentPage() = runBlocking {
        val a=api("a");val s=source(a)
        val seed=RecommendationSeed("expected",listOf(s),MediaKind.Movie,"Expected film",2026)
        assertFalse(seed.accepts(s,a.newMovieLoadResponse("Unrelated horror",s.binding.url,TvType.Movie,"opaque") { year=2026 }))
        assertFalse(seed.accepts(s,a.newMovieLoadResponse("Expected film",s.binding.url,TvType.Movie,"opaque") { year=1990 }))
        assertTrue(seed.accepts(s,a.newMovieLoadResponse("Expected film",s.binding.url,TvType.Movie,"opaque") { year=2026 }))
        val seriesSeed=seed.copy(kind=MediaKind.Series,title="Expected series")
        assertFalse(seriesSeed.accepts(s,a.newMovieLoadResponse("Expected series",s.binding.url,TvType.Movie,"opaque")))
    }

    @Test fun genericProviderListsAreSkippedWhenMetadataCanCorroborateTheActualRelatedWorks() = runBlocking {
        val a=api("a");val b=api("b")
        val reference=MediaReference(ExternalMediaId(ExternalIdNamespace.TmdbMovie,"42"),"Related science fiction",
            setOf("Related science fiction"),2026,MediaKind.Movie,emptySet())
        val seed=RecommendationSeed("science fiction",listOf(source(a),source(b)),MediaKind.Movie,"Watched SF",2026)
        val loader=PersonalizedRecommendationsLoader(related={ listOf(reference) })
        val batch=loader.load("account",listOf(seed),false) { s ->
            if (s.binding.provider.name==a.name) listOf(movie(a,"Unrelated new film"))
            else listOf(movie(b,"Related science fiction"),movie(b,"Unrelated horror"))
        }.single()
        assertEquals(b.name,batch.source.binding.provider.name)
        assertEquals(listOf("Related science fiction"),batch.items.map { it.name })
    }

    @Test fun aChangedMetadataClassificationCannotLendASeriesRecommendationToTheMovieShelf() = runBlocking {
        val a=api("a")
        val response=a.newTvSeriesSearchResponse("Misclassified","/misclassified") { year=2026 }
        val batch=RecommendationBatch("series",source(a),listOf(response),MediaKind.Series,"Seen series")
        com.lagradost.cloudstream3.ui.search.aggregation.SearchMediaMetadataCache.remember(a,response.url,
            a.newMovieLoadResponse("Misclassified",response.url,TvType.Movie,"opaque") { year=2026 })
        assertTrue(AggregatedHomeProjector().project(emptyList(),listOf(batch)).isEmpty())
    }

    @Test fun loaderRetriesAlternativeBindingsAndCacheNeverCrossesAccountsOrProviderImplementations() = runBlocking {
        val a=api("a");val b=api("b")
        val first=source(a);val second=source(b)
        val seed=RecommendationSeed("seen",listOf(first,second))
        val loader=PersonalizedRecommendationsLoader()
        val calls=AtomicInteger()
        val fetch:suspend(RecommendationSource)->List<SearchResponse>? = {
            calls.incrementAndGet()
            if(it==first) error("Provider unavailable") else listOf(movie(b,"Suggested"))
        }
        assertEquals(second,loader.load("account1",listOf(seed),false,fetch).single().source)
        loader.load("account1",listOf(seed),false,fetch)
        assertEquals(2,calls.get())
        loader.load("account2",listOf(seed),false,fetch)
        assertEquals(4,calls.get())
        loader.load("account1",listOf(seed),true,fetch)
        assertEquals(6,calls.get())
        val replaced=seed.copy(sources=listOf(second.copy(implementation="New implementation")))
        loader.load("account1",listOf(replaced),false) { calls.incrementAndGet();listOf(movie(b,"Replacement")) }
        assertEquals(7,calls.get())
    }

    @Test fun totalDeadlineKeepsCompletedSeedsAndFailuresCanBeRetriedWithoutBlockingNews() = runBlocking {
        val a=api("a")
        val seeds=(0..3).map { RecommendationSeed("seed$it",listOf(source(a,"/seed$it"))) }
        val active=AtomicInteger();val peak=AtomicInteger()
        val loader=PersonalizedRecommendationsLoader(2,300,400)
        val result=loader.load("account",seeds,false) {
            val n=active.incrementAndGet();peak.accumulateAndGet(n,::maxOf)
            try { if(it==seeds[0].sources.single()) listOf(movie(a,"Available"))
                else { delay(Long.MAX_VALUE);null } } finally { active.decrementAndGet() }
        }
        assertEquals(2,peak.get());assertEquals(0,active.get())
        assertEquals("seed0",result.single().seed)
        val retried=loader.load("account",seeds,false) { listOf(movie(a,"Recovered")) }
        assertEquals(4,retried.size)
    }

    @Test fun cancellationStopsLoadsAndNeverReturnsAnOldAccountsSuggestions() = runBlocking {
        val a=api("a");val started=CompletableDeferred<Unit>();val active=AtomicInteger()
        var published=false
        val job=launch {
            PersonalizedRecommendationsLoader().load("old",listOf(RecommendationSeed("seen",listOf(source(a)))),false) {
                active.incrementAndGet();started.complete(Unit)
                try { delay(Long.MAX_VALUE);null } finally { active.decrementAndGet() }
            }
            published=true
        }
        started.await();job.cancelAndJoin()
        assertEquals(0,active.get());assertFalse(published)
    }
}
