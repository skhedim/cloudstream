package com.lagradost.cloudstream4.aggregation

import com.fasterxml.jackson.databind.JsonNode
import java.net.URLEncoder

/** Trailer links: TMDB's YouTube trailer of a work, else a YouTube search (like Android's button). */
object Trailers {
    private val types = setOf("Trailer", "Teaser")

    /** The best YouTube video of TMDB `videos.results`: [language] first, then English; trailers before teasers, official first. */
    fun pick(videos: JsonNode?, language: String): String? {
        val best = videos.orEmpty().filter {
            it.path("site").asText() == "YouTube" && it.path("key").asText().isNotBlank() && it.path("type").asText() in types
        }.minByOrNull { video ->
            val lang = video.path("iso_639_1").asText()
            (if (lang == language) 0 else if (lang == "en") 4 else 8) +
                (if (video.path("type").asText() == "Trailer") 0 else 2) + (if (video.path("official").asBoolean(false)) 0 else 1)
        } ?: return null
        return "https://www.youtube.com/watch?v=" + best.path("key").asText()
    }

    private fun JsonNode?.orEmpty(): List<JsonNode> = this?.toList().orEmpty()

    fun searchUrl(title: String, year: Int?, french: Boolean): String {
        val query = listOfNotNull(title.trim(), year?.takeIf { it in 1800..2999 }?.toString(),
            if (french) "bande annonce officielle" else "official trailer").joinToString(" ")
        return "https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8")
    }
}
