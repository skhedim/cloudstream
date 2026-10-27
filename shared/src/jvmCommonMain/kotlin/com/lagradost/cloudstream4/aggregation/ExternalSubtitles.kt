package com.lagradost.cloudstream4.aggregation

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.newSubtitleFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The work being played, for French subtitles when only original-version sources exist. The IMDb ID
 * is looked up from [tmdbId] when the providers did not give it.
 */
data class PlaybackWork(
    val imdbId: String?, val season: Int? = null, val episode: Int? = null,
    val tmdbId: String? = null, val series: Boolean = season != null,
)

/**
 * French subtitles from the public OpenSubtitles catalogue (its Stremio add-on: no account, keyed by
 * IMDb ID). They turn an original-version source into a VOST one. Web releases come first: most
 * streams are web rips, so their timing usually matches.
 */
object ExternalSubtitles {
    private const val BASE = "https://opensubtitles-v3.strem.io/subtitles"
    private const val TTL = 6 * 60 * 60 * 1000L
    /** The subtitle-name cleanup of the players drops "(…sub…)", so this still reads as French. */
    const val LABEL = "Français (OpenSubtitles)"

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Item(
        val url: String? = null,
        val lang: String? = null,
        @JsonProperty("SubEncoding") val encoding: String? = null,
        val movieReleaseName: String? = null,
        val subtitleFileName: String? = null,
    )
    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Response(val subtitles: List<Item> = emptyList())

    private val french = setOf("fre", "fra", "fr")
    private val web = Regex("(?i)web[ ._-]?(?:dl|rip)?|amzn|nf\\.|dsnp|hmax|atvp")
    private data class Saved(val at: Long, val files: List<SubtitleFile>)
    private val cache = linkedMapOf<String, Saved>()

    fun isExternal(file: SubtitleFile): Boolean = file.lang.startsWith(LABEL)

    fun url(work: PlaybackWork, imdbId: String? = work.imdbId): String? {
        val imdb = imdbId?.takeIf { it.matches(Regex("tt\\d+")) } ?: return null
        return when {
            !work.series -> "$BASE/movie/$imdb.json"
            work.season != null && work.episode != null -> "$BASE/series/$imdb:${work.season}:${work.episode}.json"
            else -> null // An absolute episode number cannot be placed in IMDb's seasons.
        }
    }

    private val imdbIds = linkedMapOf<String, String?>()

    private suspend fun imdbOf(work: PlaybackWork): String? {
        work.imdbId?.let { return it }
        val tmdb = work.tmdbId ?: return null
        val key = (if (work.series) "tv/" else "movie/") + tmdb
        synchronized(imdbIds) { if (key in imdbIds) return imdbIds[key] }
        val imdb = runCatching { TmdbAccess.json("/$key/external_ids").path("imdb_id").asText("").takeIf { it.startsWith("tt") } }.getOrNull()
        synchronized(imdbIds) { imdbIds[key] = imdb; while (imdbIds.size > 200) imdbIds.remove(imdbIds.keys.first()) }
        return imdb
    }

    /** French subtitle URLs of a catalogue answer, web releases first, at most [limit]. */
    fun parse(json: String, limit: Int = 3): List<String> =
        mapper.readValue(json, Response::class.java).subtitles
            .filter { it.lang?.lowercase() in french && !it.url.isNullOrBlank() }
            .sortedBy { item -> if (web.containsMatchIn(item.movieReleaseName.orEmpty() + " " + item.subtitleFileName.orEmpty())) 0 else 1 }
            .mapNotNull { it.url }.distinct().take(limit)

    /** Cached for a few hours; empty when nothing is known or the catalogue does not answer in time. */
    suspend fun french(work: PlaybackWork, timeoutMs: Long = 4_000): List<SubtitleFile> {
        val url = url(work, imdbOf(work)) ?: return emptyList()
        synchronized(cache) { cache[url]?.takeIf { System.currentTimeMillis() - it.at < TTL }?.let { return it.files } }
        val files = try {
            withTimeoutOrNull(timeoutMs) {
                parse(app.get(url, timeout = timeoutMs / 1000 + 1).text).mapIndexed { index, file ->
                    newSubtitleFile(if (index == 0) LABEL else "$LABEL ${index + 1}", file)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
        files ?: return emptyList()
        synchronized(cache) {
            cache[url] = Saved(System.currentTimeMillis(), files)
            while (cache.size > 100) cache.remove(cache.keys.first())
        }
        return files
    }
}
