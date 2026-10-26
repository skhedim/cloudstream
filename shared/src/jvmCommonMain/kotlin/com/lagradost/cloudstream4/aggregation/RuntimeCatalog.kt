package com.lagradost.cloudstream4.aggregation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Catalogue runtimes from TMDB, used to recognise streams of another length (advert loops,
 * trailers, another work). Cached for a day; any failure simply means "unknown".
 */
object RuntimeCatalog {
    private data class Saved(val minutes: Int?, val at: Long)
    private val cache = linkedMapOf<String, Saved>()
    private const val TTL_MS = 24 * 60 * 60 * 1000L

    private suspend fun cached(key: String, fetch: suspend () -> Int?): Int? {
        synchronized(cache) { cache[key]?.takeIf { System.currentTimeMillis() - it.at < TTL_MS }?.let { return it.minutes } }
        val minutes = try {
            withTimeoutOrNull(2_500) { fetch() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        synchronized(cache) {
            cache[key] = Saved(minutes, System.currentTimeMillis())
            while (cache.size > 300) cache.remove(cache.keys.first())
        }
        return minutes
    }

    suspend fun movieMinutes(tmdbMovieId: String): Int? = cached("movie:$tmdbMovieId") {
        TmdbAccess.json("/movie/$tmdbMovieId").path("runtime").asInt(0).takeIf { it in 20..400 }
    }

    suspend fun episodeMinutes(tmdbTvId: String, season: Int, episode: Int): Int? = cached("tv:$tmdbTvId:$season:$episode") {
        TmdbAccess.json("/tv/$tmdbTvId/season/$season/episode/$episode").path("runtime").asInt(0).takeIf { it in 3..240 }
    }
}
