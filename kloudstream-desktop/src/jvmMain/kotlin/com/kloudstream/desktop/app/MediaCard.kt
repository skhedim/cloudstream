package com.kloudstream.desktop.app

import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream4.aggregation.CanonicalDetailRequest
import com.lagradost.cloudstream4.domain.media.CanonicalMedia
import com.lagradost.cloudstream4.domain.media.MediaAggregator

/** A work as shown on a poster: one card per canonical work, whichever providers offer it. */
data class MediaCard(
    val media: CanonicalMedia,
    val responses: List<SearchResponse>,
    /** Cards restored from the last session keep the request they opened with. */
    val saved: CanonicalDetailRequest? = null,
) {
    val title: String get() = media.title
    val year: Int? get() = media.year
    val isSeries: Boolean get() = MediaAggregator.family(media.kind) == "series"
    /** A whole-show poster first, like Android's representative card. */
    private val representative: SearchResponse
        get() = responses.getOrNull(media.bindings.indexOfFirst { media.bindingEditions[it].isNullOrEmpty() }) ?: responses.first()
    val poster: String? get() = representative.posterUrl ?: responses.firstNotNullOfOrNull { it.posterUrl }
    val posterHeaders: Map<String, String> get() = representative.posterHeaders.orEmpty()
    val score: Double? get() = responses.firstNotNullOfOrNull { it.score?.toDouble(10) }

    fun request(): CanonicalDetailRequest = saved ?: CanonicalDetailRequest.from(media, media.title)

    companion object {
        /** A display-only card (history, last session's home): opening it uses [request]. */
        fun of(title: String, year: Int?, poster: String?, score: Double?, request: CanonicalDetailRequest): MediaCard {
            val media = CanonicalMedia(request.identity.hashCode().toLong(), title, year, request.kind, request.externalIds,
                emptyList(), emptySet())
            val response = object : SearchResponse {
                override val name = title
                override val url = request.identity
                override val apiName = ""
                override var type: com.lagradost.cloudstream3.TvType? = null
                override var posterUrl: String? = poster
                override var posterHeaders: Map<String, String>? = null
                override var id: Int? = null
                override var quality: com.lagradost.cloudstream3.SearchQuality? = null
                override var score: com.lagradost.cloudstream3.Score? = score?.let { com.lagradost.cloudstream3.Score.from(it, 10) }
            }
            return MediaCard(media, listOf(response), request)
        }
    }
}
