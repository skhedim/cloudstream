package com.lagradost.cloudstream3.ui.home.aggregation

import com.lagradost.cloudstream3.ui.search.SearchSuggestionApi
import com.lagradost.cloudstream4.aggregation.SearchIdentityEvidence
import com.lagradost.cloudstream4.domain.media.*
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.util.Locale

/** Optional corroboration using the app's existing TMDB access. IDs only, no full history. */
internal object PersonalizedRecommendationCatalog {
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
        val key = Key(id, Locale.getDefault().toLanguageTag())
        synchronized(cache) { cache[key]?.takeIf { System.currentTimeMillis()-it.time < 24*60*60_000L } }?.let { return it.items }
        return try {
            val type = if (namespace == ExternalIdNamespace.TmdbMovie) "movie" else "tv"
            val json = JSONObject(SearchSuggestionApi.metadata("/$type/${id.value}", mapOf(
                "language" to key.language, "append_to_response" to "recommendations,similar")).text)
            val items = listOf("recommendations", "similar").flatMap { section ->
                val list = json.optJSONObject(section)?.optJSONArray("results") ?: return@flatMap emptyList()
                (0 until list.length()).mapNotNull { index ->
                    val item = list.optJSONObject(index) ?: return@mapNotNull null
                    val number = item.optLong("id",0).takeIf { it>0 } ?: return@mapNotNull null
                    val title = item.optString(if (type=="movie") "title" else "name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val original = item.optString(if (type=="movie") "original_title" else "original_name").takeIf { it.isNotBlank() }
                    val year = item.optString(if (type=="movie") "release_date" else "first_air_date").take(4).toIntOrNull()
                    MediaReference(ExternalMediaId(namespace,number.toString()),title,setOfNotNull(title,original),year,
                        if (type=="movie") MediaKind.Movie else MediaKind.Series,
                        SearchIdentityEvidence.artworkIds(item.optString("poster_path").takeIf { it.startsWith("/") }
                            ?.let { "https://image.tmdb.org/t/p/original$it" }))
                }
            }.distinctBy { it.id }
            if (items.isNotEmpty()) synchronized(cache) {
                cache[key] = Saved(System.currentTimeMillis(), items)
                while (cache.size>50) cache.remove(cache.keys.first())
            }
            items
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { emptyList() }
    }
}
