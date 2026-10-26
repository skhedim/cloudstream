package com.lagradost.cloudstream3

import com.lagradost.cloudstream4.aggregation.SearchMediaMapper
import com.lagradost.cloudstream4.domain.media.ExternalIdNamespace
import com.lagradost.cloudstream4.domain.media.MediaAggregator
import com.lagradost.cloudstream4.domain.media.ProviderKey
import org.junit.Assert.*
import org.junit.Test

class SearchProviderIdentityAdaptersTest {
    private val api = object : MainAPI() {
        override var name = "Arbitrary display name"
        override var mainUrl = "https://catalog.example"
    }
    private val key = ProviderKey(api.name,api.mainUrl)
    private fun response(path: String, title: String = "Breaking Bad", type: TvType = TvType.TvSeries) =
        api.newMovieSearchResponse(title,api.mainUrl+path,type,fix = false) {
            posterUrl = "https://image.tmdb.org/t/p/w500/abcd123.jpg"
        }

    @Test fun auditedClassesSupplyTypedIdsAndMergeTranslatedTitles() {
        val inputs = listOf(
            response("/tv/1396") to "com.byayzen.Movix",
            response("/watch/tv-1396", "Le Chimiste") to "com.afterdark.cloudstream.AfterDarkProvider",
            response("/tv-show/breaking-bad/1396") to "com.lagradost.Frembed",
        ).map { (response, implementation) -> SearchMediaMapper.candidate(key,response,implementation) }
        val card = MediaAggregator().aggregate(inputs).single()
        assertEquals(3,card.bindings.size)
        assertEquals(ExternalIdNamespace.TmdbTv,card.externalIds.single().namespace)
        assertEquals("1396",card.externalIds.single().value)
        val film = SearchMediaMapper.candidate(key,response("/movie/1396",type = TvType.Movie),"com.byayzen.Movix")
        assertEquals(ExternalIdNamespace.TmdbMovie,film.externalIds.single().namespace)
        assertEquals(2,MediaAggregator().aggregate(inputs+film).size)
    }

    @Test fun genericProvidersAndChangedProtocolsNeverSupplyGuessedIds() {
        val cases = listOf(
            response("/tv/1396") to null,
            response("/tv/1396") to "some.other.Movix",
            response("/watch/1396") to "com.afterdark.cloudstream.AfterDarkProvider",
            response("/tv/1396-1-1") to "com.byayzen.Movix",
            response("/movie/1396",type = TvType.TvSeries) to "com.byayzen.Movix",
        )
        cases.forEach { (response,implementation) ->
            assertTrue(SearchMediaMapper.candidate(key,response,implementation).externalIds.isEmpty())
        }
        val external = response("/tv/1396").copy(url = "https://other.example/tv/1396")
        assertTrue(SearchMediaMapper.candidate(key,external,"com.byayzen.Movix").externalIds.isEmpty())
        val noArtwork = response("/tv/1396").copy(posterUrl = "https://catalog.example/placeholder.jpg")
        assertTrue(SearchMediaMapper.candidate(key,noArtwork,"com.byayzen.Movix").externalIds.isEmpty())
    }
}
