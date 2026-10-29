package com.lagradost.cloudstream3.ui.home.aggregation

import com.lagradost.cloudstream4.aggregation.AggregationPlatform
import com.lagradost.cloudstream4.aggregation.SearchIdentityEvidence
import com.lagradost.cloudstream4.aggregation.TmdbAccess
import com.lagradost.cloudstream4.domain.media.*
import kotlinx.coroutines.CancellationException

/**
 * Optional corroboration using TMDB's recommendations and similar works of a watched work, so
 * that generic provider lists are not taken for related works. IDs only, no history uploaded.
 * Shared by Android and the desktop app.
 */
object PersonalizedRecommendationCatalog {
    private data class Key(val id: ExternalMediaId, val language: String)
    private data class Saved(val time: Long, val items: List<MediaReference>)
    private val cache = linkedMapOf<Key, Saved>()
    suspend fun related(seed: RecommendationSeed): List<MediaReference> {
        val namespace = when (MediaAggregator.family(seed.kind)) {
            "movie" -> ExternalIdNamespace.TmdbMovie
            "series" -> ExternalIdNamespace.TmdbTv
            else -> return emptyList()
        }
        val id = seed.externalIds.singleOrNull { it.namespace == namespace } ?: return emptyList()
        val key = Key(id, AggregationPlatform.metadataLanguage())
        synchronized(cache) { cache[key]?.takeIf { System.currentTimeMillis() - it.time < 24 * 60 * 60_000L } }?.let { return it.items }
        return try {
            val movie = namespace == ExternalIdNamespace.TmdbMovie
            val json = TmdbAccess.json("/${if (movie) "movie" else "tv"}/${id.value}", mapOf(
                "language" to key.language, "append_to_response" to "recommendations,similar"))
            val items = listOf("recommendations", "similar").flatMap { section ->
                json.path(section).path("results").mapNotNull { item ->
                    val number = item.path("id").asLong(0).takeIf { it > 0 } ?: return@mapNotNull null
                    val title = item.path(if (movie) "title" else "name").asText("").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val original = item.path(if (movie) "original_title" else "original_name").asText("").takeIf { it.isNotBlank() }
                    val year = item.path(if (movie) "release_date" else "first_air_date").asText("").take(4).toIntOrNull()
                    MediaReference(ExternalMediaId(namespace, number.toString()), title, setOfNotNull(title, original), year,
                        if (movie) MediaKind.Movie else MediaKind.Series,
                        SearchIdentityEvidence.artworkIds(item.path("poster_path").asText("").takeIf { it.startsWith("/") }
                            ?.let { "https://image.tmdb.org/t/p/original$it" }))
                }
            }.distinctBy { it.id }
            if (items.isNotEmpty()) synchronized(cache) {
                cache[key] = Saved(System.currentTimeMillis(), items)
                while (cache.size > 50) cache.remove(cache.keys.first())
            }
            items
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { emptyList() }
    }
}
