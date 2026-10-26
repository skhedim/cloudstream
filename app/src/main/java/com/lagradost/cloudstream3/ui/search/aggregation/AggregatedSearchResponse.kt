package com.lagradost.cloudstream3.ui.search.aggregation

import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream4.aggregation.SearchMediaMapper
import com.lagradost.cloudstream4.domain.media.CanonicalMedia
import com.lagradost.cloudstream4.domain.media.MediaAggregator
import com.lagradost.cloudstream4.domain.media.MediaCandidate
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

/** Input is already filtered and interleaved in a stable provider/rank order. */
fun SearchMediaMapper.aggregate(
    aggregator: MediaAggregator,
    results: List<Pair<ProviderKey, SearchResponse>>,
    candidates: List<MediaCandidate> = results.map { (provider, response) -> candidate(provider, response) },
): List<SearchResponse> {
    val originals = results.associate { (provider, response) -> ProviderBinding(provider, response.url) to response }
    return aggregator.aggregate(candidates).map { media ->
        AggregatedSearchResponse(media, media.bindings.map { originals.getValue(it) })
    }
}
