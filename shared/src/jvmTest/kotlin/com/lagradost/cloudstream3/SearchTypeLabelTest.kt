package com.lagradost.cloudstream3

import com.lagradost.cloudstream4.aggregation.SearchMediaMapper
import com.lagradost.cloudstream4.domain.media.MediaKind
import com.lagradost.cloudstream4.domain.media.ProviderKey
import org.junit.Assert.*
import org.junit.Test

class SearchTypeLabelTest {
    private val api = object : MainAPI() {
        override var name = "Labels"
        override var mainUrl = "https://labels.example"
    }
    private val key = ProviderKey(api.name, api.mainUrl)

    @Test fun explicitSeriesLabelsCorrectTheSearchTypeAndLeaveTheTitle() {
        for (name in listOf("Breaking Bad - série", "Breaking Bad (Série)", "Breaking Bad – la serie", "Breaking Bad [TV Series]")) {
            val candidate = SearchMediaMapper.candidate(key, api.newMovieSearchResponse(name, "/x/$name", TvType.Movie))
            assertEquals(name, "Breaking Bad", candidate.title)
            assertEquals(name, MediaKind.Series, candidate.kind)
        }
        val film = SearchMediaMapper.candidate(key, api.newTvSeriesSearchResponse("Barbie (film)", "/barbie", TvType.TvSeries))
        assertEquals("Barbie", film.title)
        assertEquals(MediaKind.Movie, film.kind)
        // Words inside a title are not labels.
        val animated = SearchMediaMapper.candidate(key, api.newTvSeriesSearchResponse("Batman: The Animated Series", "/b", TvType.Cartoon))
        assertEquals("Batman: The Animated Series", animated.title)
        assertEquals(MediaKind.Cartoon, animated.kind)
    }
}
