package com.lagradost.cloudstream4.aggregation

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream4.domain.media.ExternalIdNamespace
import com.lagradost.cloudstream4.domain.media.ExternalMediaId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** One actor of a work's main cast. */
data class CastMember(val name: String, val character: String?, val photoUrl: String?)

/** Presentation metadata of a featured work (home hero, detail page), in the viewer's language. */
data class Highlight(
    val id: ExternalMediaId,
    val title: String,
    val overview: String?,
    val backdropUrl: String?,
    val posterUrl: String?,
    val logoUrl: String?,
    val genres: List<String>,
    /** TMDB vote average out of 10, only when enough people voted. */
    val rating: Double?,
    val year: Int?,
    val runtimeMinutes: Int?,
    /** Main cast in billing order (a series' cast across its seasons). */
    val cast: List<CastMember> = emptyList(),
    /** Official YouTube trailer, see [Trailers.pick]. */
    val trailerUrl: String? = null,
)

/**
 * What people watch this week (TMDB trending, viewer's language), used to rank and feature works
 * that providers actually offer. Never creates cards on its own. Cached; failures mean "no signal".
 */
object DiscoveryCatalog {
    private const val IMAGE = "https://image.tmdb.org/t/p/"
    private data class Saved<T>(val value: T, val at: Long)
    private var trending: Saved<Map<ExternalMediaId, Int>>? = null
    private val highlights = linkedMapOf<ExternalMediaId, Saved<Highlight?>>()

    private fun id(node: JsonNode, fallbackType: String? = null): ExternalMediaId? {
        val type = node.path("media_type").asText(fallbackType ?: "")
        val number = node.path("id").asLong(0).takeIf { it > 0 } ?: return null
        return when (type) {
            "movie" -> ExternalMediaId(ExternalIdNamespace.TmdbMovie, number.toString())
            "tv" -> ExternalMediaId(ExternalIdNamespace.TmdbTv, number.toString())
            else -> null
        }
    }

    /** Rank (0 = most watched) of this week's trending movies and shows, three pages of each. */
    suspend fun trendingRanks(language: String = AggregationPlatform.metadataLanguage()): Map<ExternalMediaId, Int> {
        synchronized(this) { trending?.takeIf { System.currentTimeMillis() - it.at < 6 * 60 * 60_000L }?.let { return it.value } }
        val ranks = try {
            withTimeoutOrNull(6_000) {
                coroutineScope {
                    listOf("movie", "tv").flatMap { type -> (1..3).map { page -> async {
                        runCatching { TmdbAccess.json("/trending/$type/week", mapOf("language" to language, "page" to page.toString())) }
                            .getOrNull()?.path("results")?.mapNotNull { id(it, type) }.orEmpty()
                    } } }.awaitAll()
                }.let { pages ->
                    // Interleave movies and shows page by page so both families share the top.
                    val movies = pages.take(3).flatten(); val shows = pages.drop(3).flatten()
                    buildList { for (i in 0 until maxOf(movies.size, shows.size)) {
                        movies.getOrNull(i)?.let(::add); shows.getOrNull(i)?.let(::add) } }
                        .distinct().withIndex().associate { it.value to it.index }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        if (!ranks.isNullOrEmpty()) synchronized(this) { trending = Saved(ranks, System.currentTimeMillis()) }
        return ranks.orEmpty()
    }

    /** A series' aggregate credits name each role in `roles`; TMDB writes roles in English. */
    internal fun cast(credits: JsonNode, language: String): List<CastMember> = credits.take(20).mapNotNull { person ->
        val name = person.path("name").asText("").takeIf(String::isNotBlank) ?: return@mapNotNull null
        val character = person.path("character").asText("").ifBlank { person.path("roles").path(0).path("character").asText("") }
            .let { if (language == "fr") it.replace("(voice)", "(voix)") else it }
        CastMember(name, character.takeIf(String::isNotBlank),
            person.path("profile_path").asText("").takeIf { it.startsWith("/") }?.let { IMAGE + "w185" + it })
    }

    suspend fun highlight(id: ExternalMediaId, language: String = AggregationPlatform.metadataLanguage()): Highlight? {
        synchronized(highlights) { highlights[id]?.takeIf { System.currentTimeMillis() - it.at < 24 * 60 * 60_000L }?.let { return it.value } }
        val type = when (id.namespace) {
            ExternalIdNamespace.TmdbMovie -> "movie"
            ExternalIdNamespace.TmdbTv -> "tv"
            else -> return null
        }
        val result = try {
            withTimeoutOrNull(4_000) {
                val short = language.substringBefore('-')
                val credits = if (type == "movie") "credits" else "aggregate_credits"
                val node = TmdbAccess.json("/$type/${id.value}", mapOf("language" to language,
                    "append_to_response" to "images,$credits,videos", "include_image_language" to "$short,en,null",
                    "include_video_language" to "$short,en"))
                val logo = node.path("images").path("logos").let { logos ->
                    logos.firstOrNull { it.path("iso_639_1").asText() == short } ?: logos.firstOrNull { it.path("iso_639_1").asText() == "en" }
                }?.path("file_path")?.asText()?.takeIf { it.startsWith("/") }
                val votes = node.path("vote_count").asInt(0)
                val date = node.path(if (type == "movie") "release_date" else "first_air_date").asText("")
                Highlight(
                    id, node.path(if (type == "movie") "title" else "name").asText(""),
                    node.path("overview").asText("").takeIf { it.isNotBlank() },
                    node.path("backdrop_path").asText("").takeIf { it.startsWith("/") }?.let { IMAGE + "w1280" + it },
                    node.path("poster_path").asText("").takeIf { it.startsWith("/") }?.let { IMAGE + "w500" + it },
                    logo?.let { IMAGE + "w500" + it },
                    node.path("genres").mapNotNull { it.path("name").asText("").takeIf(String::isNotBlank) },
                    node.path("vote_average").asDouble(0.0).takeIf { votes >= 50 && it > 0 },
                    date.take(4).toIntOrNull(),
                    if (type == "movie") node.path("runtime").asInt(0).takeIf { it > 0 }
                    else node.path("episode_run_time").firstOrNull()?.asInt(0)?.takeIf { it > 0 },
                    cast(node.path(credits).path("cast"), short),
                    Trailers.pick(node.path("videos").path("results"), short),
                ).takeIf { it.title.isNotBlank() }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        synchronized(highlights) {
            highlights[id] = Saved(result, System.currentTimeMillis())
            while (highlights.size > 100) highlights.remove(highlights.keys.first())
        }
        return result
    }
}
