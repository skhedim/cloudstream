package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.ui.search.aggregation.AggregatedSearchResponse
import com.lagradost.cloudstream3.ui.search.aggregation.SearchMediaMapper
import com.lagradost.cloudstream3.ui.search.aggregation.SearchMediaMetadataCache
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream4.domain.media.ExternalIdNamespace
import com.lagradost.cloudstream4.domain.media.MediaAggregator
import com.lagradost.cloudstream4.domain.media.ProviderKey
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

class SearchMediaMapperTest {
    private fun provider(name: String) = object : MainAPI() {
        override var name = name
        override var mainUrl = "https://$name.example"
    }

    @Test fun genericProviderIdIsNeverTreatedAsAnExternalId() {
        val api = provider("Generic")
        val result = api.newTvSeriesSearchResponse("Show", "/show") { id = 123 }
        assertTrue(SearchMediaMapper.candidate(ProviderKey(api.name, api.mainUrl), result).externalIds.isEmpty())
    }

    @Test fun canonicalTmdbUrlsSupplyTypedIdsWithoutDependingOnTheProviderName() {
        val api = provider("Metadata")
        for ((path, type, namespace) in listOf(
            Triple("movie", TvType.Movie, ExternalIdNamespace.TmdbMovie),
            Triple("tv", TvType.TvSeries, ExternalIdNamespace.TmdbTv),
        )) {
            val result = api.newMovieSearchResponse("Show", "https://www.themoviedb.org/$path/123", type, fix = false)
            val id = SearchMediaMapper.candidate(ProviderKey(api.name, api.mainUrl), result).externalIds.single()
            assertEquals(namespace, id.namespace)
            assertEquals("123", id.value)
        }
    }

    @Test fun arbitraryUrlsAndLookalikeDomainsCannotSupplyTrustedIds() {
        val api = provider("Generic")
        for (url in listOf("https://evil.example/tv/123", "https://www.themoviedb.org.evil.example/tv/123")) {
            val result = api.newTvSeriesSearchResponse("Show", url, fix = false)
            assertTrue(SearchMediaMapper.candidate(ProviderKey(api.name, api.mainUrl), result).externalIds.isEmpty())
        }
    }

    @Test fun placeholderYearsAreNotEvidenceForMerging() {
        val a = provider("PlaceholderA")
        val b = provider("PlaceholderB")
        val first = a.newMovieSearchResponse("Same title", "/film") { year = 0 }
        val second = b.newMovieSearchResponse("Same title", "/film") { year = -1 }
        val key = ProviderKey(a.name, a.mainUrl)
        assertNull(SearchMediaMapper.candidate(key, first).year)
        assertEquals(2, SearchMediaMapper.aggregate(MediaAggregator(), listOf(
            key to first, ProviderKey(b.name, b.mainUrl) to second,
        )).size)
    }

    @Test fun aggregatedCardRetainsExactResponsesAndOpaqueNavigationPayloads() {
        val a = provider("A")
        val b = provider("B")
        val first = a.newTvSeriesSearchResponse("Breaking Bad", "opaque:a", fix = false) {
            year = 2008
            posterHeaders = mapOf("Referer" to "https://a.example")
        }
        val second = b.newTvSeriesSearchResponse("Breaking Bad", "opaque:b", fix = false) { year = 2008 }
        val card = SearchMediaMapper.aggregate(MediaAggregator(), listOf(
            ProviderKey(a.name, a.mainUrl) to first, ProviderKey(b.name, b.mainUrl) to second,
        )).single() as AggregatedSearchResponse
        assertSame(first, card.responses[0])
        assertSame(second, card.responses[1])
        assertEquals("opaque:b", card.responses[1].url)
        assertEquals(first.posterHeaders, card.posterHeaders)
        assertEquals(2, card.media.bindings.size)
    }

    @Test fun seasonMarkersArePreservedAndDoNotCollapseIntoTheWholeSeries() {
        val api = provider("A")
        val whole = api.newTvSeriesSearchResponse("Show", "/show") { year = 2020 }
        val season = api.newTvSeriesSearchResponse("Show Saison 2", "/season-2") { year = 2020 }
        val key = ProviderKey(api.name, api.mainUrl)
        assertTrue(SearchMediaMapper.candidate(key, whole).edition.isEmpty())
        assertEquals(setOf("season:2"), SearchMediaMapper.candidate(key, season).edition)
        assertEquals(2, SearchMediaMapper.aggregate(MediaAggregator(), listOf(key to whole, key to season)).size)
    }

    @Test fun previouslyLoadedMetadataSuppliesIdsWithoutFetchingDetailPagesDuringSearch() = runBlocking {
        val a = provider("PreviouslyLoadedA")
        val b = provider("PreviouslyLoadedB")
        for (api in listOf(a, b)) {
            val page = api.newMovieLoadResponse("Known title", "/film", TvType.Movie, "opaque-episode") {
                addImdbId("tt0903747")
            }
            SearchMediaMetadataCache.remember(api, "opaque-page", page)
        }
        val first = a.newMovieSearchResponse("Original title", "opaque-page", fix = false)
        val second = b.newMovieSearchResponse("Translated title", "opaque-page", fix = false)
        val result = SearchMediaMapper.aggregate(MediaAggregator(), listOf(
            ProviderKey(a.name, a.mainUrl) to first, ProviderKey(b.name, b.mainUrl) to second,
        )).single() as AggregatedSearchResponse
        assertEquals(2, result.responses.size)
        assertEquals("tt0903747", result.media.externalIds.single().value)
        val unrelated = provider("UnvisitedProvider")
        assertTrue(SearchMediaMapper.candidate(ProviderKey(unrelated.name, unrelated.mainUrl), first).externalIds.isEmpty())
    }

    @Test fun sameTmdbArtworkAcrossSizesMergesTheRealMissingYearPattern() {
        val a = provider("FrembedFixture")
        val b = provider("AfterDarkFixture")
        val c = provider("MovixFixture")
        val results = listOf(a, b, c).mapIndexed { index, api ->
            val response = api.newMovieSearchResponse("Breaking in Badly", "/movie") {
                posterUrl = "https://image.tmdb.org/t/p/${if (index == 1) "w780" else "w500"}/sameArtwork123.jpg"
                if (index == 1) year = 2025
            }
            ProviderKey(api.name,api.mainUrl) to response
        }
        val card = SearchMediaMapper.aggregate(MediaAggregator(),results).single() as AggregatedSearchResponse
        assertEquals(3,card.responses.size)
        assertEquals(2025,card.media.year)
        assertEquals("Breaking in Badly",card.name)
    }

    @Test fun genericArtworkAndCanonicalHostLookalikesAreNotIdentityEvidence() {
        val api = provider("Artwork")
        for (url in listOf(
            "https://provider.example/poster.jpg", "https://image.tmdb.org.evil.example/t/p/w500/a.jpg",
            "https://image.tmdb.org@evil.example/t/p/w500/a.jpg", "https://image.tmdb.org/t/p/w500/no-poster.jpg",
        )) {
            val response = api.newMovieSearchResponse("Film", "/film") { posterUrl = url }
            assertTrue(SearchMediaMapper.candidate(ProviderKey(api.name,api.mainUrl),response).artworkIds.isEmpty())
        }
    }

    @Test fun canonicalUrlSlugsAndExplicitTitleYearsAreRecognizedWithoutReadingProviderIds() {
        val api = provider("Titles")
        val response = api.newTvSeriesSearchResponse("Breaking Bad (2008) (VF)",
            "https://www.themoviedb.org/tv/1396-breaking-bad?language=fr", fix = false)
        val candidate = SearchMediaMapper.candidate(ProviderKey(api.name,api.mainUrl),response)
        assertEquals("Breaking Bad",candidate.title)
        assertEquals(2008,candidate.year)
        assertEquals("1396",candidate.externalIds.single().value)
        assertEquals(ExternalIdNamespace.TmdbTv,candidate.externalIds.single().namespace)
        val numbered = api.newMovieSearchResponse("1917", "/film")
        val other = SearchMediaMapper.candidate(ProviderKey(api.name,api.mainUrl),numbered)
        assertEquals("1917",other.title)
        assertNull(other.year)
    }

    @Test fun verifiedDetailTypeCorrectsProvidersThatLabelEverySearchHitAsAMovie() = runBlocking {
        val api = provider("MisclassifiedFixture")
        val result = api.newMovieSearchResponse("Breaking Bad", "/show")
        val key = ProviderKey(api.name,api.mainUrl)
        assertEquals(com.lagradost.cloudstream4.domain.media.MediaKind.Movie,SearchMediaMapper.candidate(key,result).kind)
        val page = api.newTvSeriesLoadResponse("Breaking Bad", "/show", TvType.TvSeries, emptyList()) { year = 2008 }
        SearchMediaMetadataCache.remember(api,result.url,page)
        val enriched = SearchMediaMapper.candidate(key,result)
        assertEquals(com.lagradost.cloudstream4.domain.media.MediaKind.Series,enriched.kind)
        assertEquals(2008,enriched.year)
        assertEquals(TvType.Movie,result.type) // The original extension payload is never mutated.
    }
}
