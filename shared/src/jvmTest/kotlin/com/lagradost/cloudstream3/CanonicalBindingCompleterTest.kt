package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream4.aggregation.*
import com.lagradost.cloudstream4.domain.media.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CanonicalBindingCompleterTest {
    private fun api(label: String, types: Set<TvType> = setOf(TvType.Movie, TvType.TvSeries)) = object : MainAPI() {
        override var name = label
        override var mainUrl = "https://${label.lowercase()}.example"
        override val supportedTypes = types
    }
    private fun registered(api: MainAPI) = RegisteredProvider(ProviderIds.of(api), ProviderKey(api.name, api.mainUrl), api)

    private class Catalogue(val results: Map<String, (MainAPI) -> List<SearchResponse>>) : ProviderGateway {
        override suspend fun search(api: MainAPI, query: String, page: Int) =
            newSearchResponseList(results[api.name]?.invoke(api).orEmpty(), false)
        override suspend fun load(api: MainAPI, url: String): LoadResponse? = null
        override suspend fun loadLinks(api: MainAPI, data: String, isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) = false
    }

    private val home = api("Home")
    private val request = CanonicalDetailRequest("id", "Le Roi Lion", 2019, MediaKind.Movie, emptySet(),
        listOf(DetailBinding(ProviderKey(home.name, home.mainUrl), "/lion")), aliases = setOf("The Lion King"))

    @Test fun findsTheSameWorkStrictlyAndIgnoresRemakesAndUnknownYears() = runBlocking {
        val exact = api("Exact"); val remake = api("Remake"); val noYear = api("NoYear"); val english = api("English")
        val series = api("Series"); val tmdb = api("Tmdb")
        val gateway = Catalogue(mapOf(
            "Exact" to { a -> listOf(a.newMovieSearchResponse("Le Roi Lion", "${a.mainUrl}/2019") { year = 2019 }) },
            "Remake" to { a -> listOf(a.newMovieSearchResponse("Le Roi Lion", "${a.mainUrl}/1994") { year = 1994 }) },
            "NoYear" to { a -> listOf(a.newMovieSearchResponse("Le Roi Lion", "${a.mainUrl}/x")) },
            "English" to { a -> listOf(a.newMovieSearchResponse("The Lion King", "${a.mainUrl}/en") { year = 2019 }) },
            "Series" to { a -> listOf(a.newTvSeriesSearchResponse("Le Roi Lion", "${a.mainUrl}/s") { year = 2019 }) },
            "Tmdb" to { a -> listOf(a.newMovieSearchResponse("Lion", "https://www.themoviedb.org/movie/420818", fix = false)) },
        ))
        val withId = request.copy(externalIds = setOf(ExternalMediaId(ExternalIdNamespace.TmdbMovie, "420818")))
        val found = CanonicalBindingCompleter(gateway = { gateway })
            .complete(withId, listOf(home, exact, remake, noYear, english, series, tmdb).map(::registered))
        assertEquals(setOf("Exact", "English", "Tmdb"), found.map { it.provider.name }.toSet())
        assertTrue(found.all { it.providerId != null })
    }

    @Test fun originalVersionCataloguesAreAskedAndMatchedWithTheEnglishTitle() = runBlocking {
        AggregationPlatform.metadataLanguage = { "fr-FR" }
        val anime = object : MainAPI() {
            override var name = "AnimeEn"
            override var mainUrl = "https://anime.example"
            override var lang = "en"
            override val supportedTypes = setOf(TvType.Anime)
        }
        val queries = java.util.Collections.synchronizedList(mutableListOf<String>())
        val gateway = object : ProviderGateway {
            override suspend fun search(api: MainAPI, query: String, page: Int): SearchResponseList {
                queries += query
                val found = if (query.startsWith("Frieren")) listOf(api.newAnimeSearchResponse("Frieren: Beyond Journey's End", "${api.mainUrl}/frieren") { year = 2023 })
                    else emptyList()
                return newSearchResponseList(found, false)
            }
            override suspend fun load(api: MainAPI, url: String): LoadResponse? = null
            override suspend fun loadLinks(api: MainAPI, data: String, isCasting: Boolean,
                subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) = false
        }
        val frieren = CanonicalDetailRequest("frieren", "Frieren", 2023, MediaKind.Series,
            setOf(ExternalMediaId(ExternalIdNamespace.TmdbTv, "209867")), listOf(DetailBinding(ProviderKey(home.name, home.mainUrl), "/frieren")))
        val found = CanonicalBindingCompleter(gateway = { gateway })
            .complete(frieren, listOf(registered(home), registered(anime))) { "Frieren: Beyond Journey's End" }
        assertEquals(listOf("AnimeEn"), found.map { it.provider.name })
        // Without the English title, the French one alone does not prove it is the same work.
        assertTrue(CanonicalBindingCompleter(gateway = { gateway }).complete(frieren, listOf(registered(home), registered(anime))) { null }.isEmpty())
    }

    @Test fun anEmptyAnswerOnlyCountsAgainstProvidersThatCoverTheKind() = runBlocking {
        ProviderHealth.reset()
        val anime = api("AnimeOnly", setOf(TvType.Anime)); val general = api("General")
        val gateway = Catalogue(emptyMap())
        repeat(ProviderHealth.DEAD_SEARCH_STREAK) {
            CanonicalBindingCompleter(gateway = { gateway }).complete(request, listOf(anime, general).map(::registered))
        }
        assertTrue(ProviderHealth.searchUsable(registered(anime).id))
        assertFalse(ProviderHealth.searchUsable(registered(general).id))
        ProviderHealth.reset()
    }

    @Test fun savedBindingsOfDisabledExtensionsAreNotLoaded() {
        val other = api("Other"); val removed = api("Removed")
        val saved = request.copy(bindings = request.bindings + listOf(
            DetailBinding(ProviderKey(other.name, "https://moved.example"), "/lion", providerId = ProviderIds.of(other)),
            DetailBinding(ProviderKey(removed.name, removed.mainUrl), "/lion")))
        // Matched by stable ID even after a domain change, or by name for contexts saved without IDs.
        assertEquals(listOf("Home", "Other"), saved.restrictedTo(listOf(registered(home), registered(other)))
            .bindings.map { it.provider.name })
        // Nothing enabled: the context stays as it was, so the page can report what is missing.
        assertEquals(saved, saved.restrictedTo(emptyList()))
    }
}
