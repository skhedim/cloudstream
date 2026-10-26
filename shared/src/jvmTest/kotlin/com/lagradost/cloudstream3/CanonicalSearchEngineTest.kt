package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream4.aggregation.*
import com.lagradost.cloudstream4.domain.media.ProviderKey
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class CanonicalSearchEngineTest {
    private fun api(label: String) = object : MainAPI() {
        override var name = label
        override var mainUrl = "https://${label.lowercase()}.example"
    }
    private val apis = listOf(api("A"), api("B"), api("C"))
    private val providers = apis.map { RegisteredProvider(ProviderIds.of(it), ProviderKey(it.name, it.mainUrl), it) }

    /** Answers from a script; `null` means the provider failed. */
    private class FakeGateway(val answer: suspend (MainAPI, String, Int) -> SearchResponseList?) : ProviderGateway {
        override suspend fun search(api: MainAPI, query: String, page: Int) = answer(api, query, page)
        override suspend fun load(api: MainAPI, url: String): LoadResponse? = null
        override suspend fun loadLinks(api: MainAPI, data: String, isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) = false
    }

    private fun engine(scope: CoroutineScope, gateway: ProviderGateway) = CanonicalSearchEngine(scope, { gateway },
        catalog = { emptyList() }, withImages = { it }, withSeasonImages = { r, _ -> r }, artwork = { null }, coalesceMs = 0)

    private suspend fun CanonicalSearchEngine.settled(query: String) =
        withTimeout(5_000) { state.first { it.query == query && it.published && !it.loading } }

    @Test fun providersAreSearchedTogetherAndTheSameWorkBecomesOneCard() = runBlocking {
        val gateway = FakeGateway { api, query, _ ->
            delay(if (api.name == "A") 50 else 5)
            newSearchResponseList(listOf(api.newTvSeriesSearchResponse(query, "${api.mainUrl}/bb") { year = 2008 },
                api.newMovieSearchResponse("${api.name} only", "${api.mainUrl}/other") { year = 2001 }), false)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = engine(scope, gateway)
        engine.search("Breaking Bad", providers)
        val state = engine.settled("Breaking Bad")
        val card = state.cards.single { it.media.title == "Breaking Bad" }
        assertEquals(3, card.responses.size)
        assertEquals(4, state.cards.size)
        // Provider order, not completion order: A first even though it answered last.
        assertEquals("A", state.interleaved.first().first.name)
        scope.cancel()
    }

    @Test fun aReplacedQueryCannotPublishLateResultsAndFailedPagesAreRetried() = runBlocking {
        val calls = mutableMapOf<String, Int>()
        val gateway = FakeGateway { api, query, page ->
            if (query == "slow") delay(300)
            val n = synchronized(calls) { (calls["${api.name}:$page"] ?: 0).also { calls["${api.name}:$page"] = it + 1 } }
            if (api.name == "B" && page == 2 && n == 0) return@FakeGateway null
            newSearchResponseList(listOf(api.newMovieSearchResponse("$query $page", "${api.mainUrl}/$query/$page")), page < 2)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = engine(scope, gateway)
        engine.search("slow", providers)
        engine.search("fast", providers)
        val first = engine.settled("fast")
        assertTrue(first.interleaved.all { it.second.name.startsWith("fast") })
        delay(400)
        assertEquals("fast", engine.state.value.query)
        assertTrue(engine.state.value.interleaved.none { it.second.name.startsWith("slow") })
        engine.loadMore()
        delay(50)
        val second = engine.settled("fast")
        assertTrue(second.hasFailures)
        assertEquals(5, second.interleaved.size)
        engine.loadMore()
        delay(50)
        val third = engine.settled("fast")
        assertFalse(third.hasFailures)
        assertEquals(6, third.interleaved.size)
        scope.cancel()
    }

    @Test fun theItemFilterAppliesBeforeGroupingAndAggregationCanBeTurnedOff() = runBlocking {
        val gateway = FakeGateway { api, query, _ ->
            newSearchResponseList(listOf(api.newMovieSearchResponse(query, "${api.mainUrl}/x") { year = 2020 }), false)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = engine(scope, gateway)
        engine.configure(true) { provider, _ -> provider.name != "C" }
        engine.search("Film", providers)
        assertEquals(2, engine.settled("Film").cards.single().responses.size)
        engine.configure(false) { _, _ -> true }
        val raw = withTimeout(5_000) { engine.state.first { !it.aggregate && it.published } }
        assertTrue(raw.cards.isEmpty())
        assertEquals(3, raw.interleaved.size)
        scope.cancel()
    }
}
