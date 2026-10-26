package com.lagradost.cloudstream3.ui.search.aggregation

import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream4.domain.media.CanonicalMedia
import com.lagradost.cloudstream4.domain.media.MediaAggregator
import com.lagradost.cloudstream4.domain.media.MediaCandidate
import com.lagradost.cloudstream4.domain.media.MediaKind
import com.lagradost.cloudstream4.domain.media.ProviderBinding
import com.lagradost.cloudstream4.domain.media.ProviderKey

/** A presentation adapter only. Never sent to an extension or persisted as a provider response. */
data class AggregatedSearchResponse(
    val media: CanonicalMedia,
    val responses: List<SearchResponse>,
) : SearchResponse by representativeFor(media, responses) {
    override val name: String get() = media.title
    val representative: SearchResponse get() = representativeFor(media, responses)
}

/** A whole-show poster should not be replaced with a season cover after grouping. */
private fun representativeFor(media: CanonicalMedia, responses: List<SearchResponse>): SearchResponse =
    responses.getOrNull(media.bindings.indexOfFirst { media.bindingEditions[it].isNullOrEmpty() })
        ?: responses.first()

object SearchMediaMapper {
    // SearchResponse.id remains provider-defined; canonical hosts/audited adapters supply IDs.
    private val edition = Regex("\\b(season|saison|part|partie|cour)\\s*(\\d+)\\b|\\bs(\\d+)\\b", RegexOption.IGNORE_CASE)
    private val languageSuffix = Regex("\\s*[\\[(](?:VF|VOSTFR|VOST|FRENCH|DUB|SUB)[\\])]\\s*$", RegexOption.IGNORE_CASE)
    private val yearSuffix = Regex("\\s*\\((18[0-9]{2}|19[0-9]{2}|20[0-9]{2})\\)\\s*$")

    fun candidate(provider: ProviderKey, response: SearchResponse, providerClass: String? = null): MediaCandidate {
        val binding = ProviderBinding(provider, response.url)
        val cached = SearchMediaMetadataCache.get(binding)
        val kind = cached?.kind?.takeIf { it != MediaKind.Unknown } ?: kind(response.type)
        val searchYear = when (response) {
            is MovieSearchResponse -> response.year
            is TvSeriesSearchResponse -> response.year
            is AnimeSearchResponse -> response.year
            else -> null
        }
        val title = response.name.trim().replace(languageSuffix, "").trim()
        val suffix = yearSuffix.find(title)
        val cleanTitle = suffix?.let { title.removeRange(it.range).trim() }?.takeIf { it.isNotBlank() } ?: title
        val year = listOfNotNull(searchYear, cached?.year, suffix?.groupValues?.get(1)?.toIntOrNull())
            .firstOrNull { it in 1800..2999 }
        val ids = cached?.ids.orEmpty().toMutableSet()
        ids.addAll(SearchIdentityEvidence.externalIds(response.url))
        ids.addAll(SearchProviderIdentityAdapters.externalIds(providerClass, provider, response, kind))
        var aliases = cached?.alternateTitles.orEmpty() + listOfNotNull((response as? AnimeSearchResponse)?.otherName)
        val editions = edition.findAll(response.name).map { match ->
            val token = when (match.groupValues[1].lowercase()) {
                "season", "saison", "" -> "season"
                "part", "partie" -> "part"
                else -> "cour"
            }
            val number = match.groupValues[2].ifEmpty { match.groupValues[3] }.trimStart('0').ifEmpty { "0" }
            "$token:$number"
        }.toSet()
        if (kind in setOf(MediaKind.Series, MediaKind.Anime, MediaKind.Cartoon, MediaKind.AsianDrama) &&
            editions.isNotEmpty() && editions.all { it.startsWith("season:") }) {
            val baseTitle = cleanTitle.replace(Regex("\\s*(?:[-–—:]\\s*)?(?:(?:season|saison)\\s*\\d+|s\\d+)\\s*$", RegexOption.IGNORE_CASE), "").trim()
            if (baseTitle.isNotBlank()) aliases = aliases + baseTitle
        }
        val artwork = SearchIdentityEvidence.artworkIds(response.posterUrl) + cached?.artworkIds.orEmpty()
        return MediaCandidate(binding, cleanTitle, year, kind, aliases, ids.toSet(), editions, artwork)
    }

    internal fun kind(type: TvType?): MediaKind = when (type) {
        TvType.Movie -> MediaKind.Movie
        TvType.TvSeries -> MediaKind.Series
        TvType.AnimeMovie -> MediaKind.AnimeMovie
        TvType.Anime -> MediaKind.Anime
        TvType.Cartoon -> MediaKind.Cartoon
        TvType.AsianDrama -> MediaKind.AsianDrama
        else -> MediaKind.Unknown
    }

    /** Input is already filtered and interleaved in a stable provider/rank order. */
    fun aggregate(
        aggregator: MediaAggregator,
        results: List<Pair<ProviderKey, SearchResponse>>,
        candidates: List<MediaCandidate> = results.map { (provider, response) -> candidate(provider, response) },
    ): List<SearchResponse> {
        val originals = results.associate { (provider, response) -> ProviderBinding(provider, response.url) to response }
        return aggregator.aggregate(candidates).map { media ->
            AggregatedSearchResponse(media, media.bindings.map { originals.getValue(it) })
        }
    }

}
