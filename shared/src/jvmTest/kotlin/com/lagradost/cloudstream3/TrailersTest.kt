package com.lagradost.cloudstream3

import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream4.aggregation.CastMember
import com.lagradost.cloudstream4.aggregation.DiscoveryCatalog
import com.lagradost.cloudstream4.aggregation.Trailers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrailersTest {
    private fun json(text: String) = ObjectMapper().readTree(text)
    private fun video(key: String, language: String, type: String = "Trailer", official: Boolean = true, site: String = "YouTube") =
        """{"key":"$key","iso_639_1":"$language","type":"$type","official":$official,"site":"$site"}"""

    @Test fun theViewersLanguageWinsOverEnglishEvenForATeaserAndOfficialTrailersComeFirst() {
        val videos = json("[" + listOf(video("en1", "en"), video("frTeaser", "fr", "Teaser"), video("featurette", "fr", "Featurette"),
            video("vimeo", "fr", site = "Vimeo"), video("frFan", "fr", official = false), video("frOfficial", "fr")).joinToString() + "]")
        assertEquals("https://www.youtube.com/watch?v=frOfficial", Trailers.pick(videos, "fr"))
        assertEquals("https://www.youtube.com/watch?v=en1", Trailers.pick(json("[" + video("en1", "en") + "," + video("frTeaser", "de", "Teaser") + "]"), "fr"))
        assertNull(Trailers.pick(json("[" + video("clip", "fr", "Clip") + "]"), "fr"))
        assertNull(Trailers.pick(null, "fr"))
    }

    @Test fun theSearchFallbackAsksForTheOfficialTrailerInTheViewersLanguage() {
        assertEquals("https://www.youtube.com/results?search_query=Super+Mario+Bros.+le+film+2023+bande+annonce+officielle",
            Trailers.searchUrl(" Super Mario Bros. le film ", 2023, french = true))
        assertEquals("https://www.youtube.com/results?search_query=Dune+official+trailer", Trailers.searchUrl("Dune", 12, french = false))
    }

    @Test fun castKeepsBillingOrderAndReadsASeriesRoleFromItsAggregateCredits() {
        val cast = DiscoveryCatalog.cast(json("""[
            {"name":"Chris Pratt","character":"Mario (voice)","profile_path":"/p.jpg"},
            {"name":"","character":"Nobody"},
            {"name":"Bryan Cranston","roles":[{"character":"Walter White","episode_count":62}]}
        ]"""), "fr")
        assertEquals(listOf(CastMember("Chris Pratt", "Mario (voix)", "https://image.tmdb.org/t/p/w185/p.jpg"),
            CastMember("Bryan Cranston", "Walter White", null)), cast)
    }
}
