package com.lagradost.cloudstream3.ui.search.aggregation

import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.ui.search.SearchSuggestionApi
import com.lagradost.cloudstream4.domain.media.ExternalIdNamespace
import com.lagradost.cloudstream4.domain.media.ExternalMediaId
import com.lagradost.cloudstream4.domain.media.MediaKind
import com.lagradost.cloudstream4.domain.media.MediaReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.Locale

/** Bounded optional TMDB lookups. Failures leave the provider-only search fully usable. */
internal object SearchMetadataCatalog {
    private data class Saved<T>(val time: Long, val value: T)
    private const val TTL = 24 * 60 * 60 * 1000L
    private val searches = linkedMapOf<String, Saved<List<MediaReference>>>()
    private val images = linkedMapOf<ExternalMediaId, Saved<Set<String>>>()
    private val seasonImages = linkedMapOf<String, Saved<Set<String>>>()

    @Synchronized private fun <K,T> cached(cache: MutableMap<K,Saved<T>>, key: K): T? =
        cache[key]?.takeIf { System.currentTimeMillis()-it.time < TTL }?.value

    @Synchronized private fun <K,T> save(cache: MutableMap<K,Saved<T>>, key: K, value: T, limit: Int) {
        cache[key] = Saved(System.currentTimeMillis(),value)
        while (cache.size > limit) cache.remove(cache.keys.first())
    }

    suspend fun search(query: String): List<MediaReference> {
        val language = Locale.getDefault().toLanguageTag()
        val key = "$language:${query.trim().lowercase()}"
        cached(searches,key)?.let { return it }
        return try {
            withTimeoutOrNull(5_000) {
                val references = mutableListOf<MediaReference>()
                for (page in 1..2) {
                    val result = SearchSuggestionApi.metadata("/search/multi",mapOf(
                        "query" to query, "language" to language, "page" to page.toString(),
                    )).parsed<TmdbProvider.TmdbPageResult>()
                    val items = result.results ?: return@withTimeoutOrNull emptyList()
                    references += items.mapNotNull { item ->
                        val movie = when (item.mediaType) { "movie" -> true; "tv" -> false; else -> return@mapNotNull null }
                        val number = item.id?.takeIf { it > 0 } ?: return@mapNotNull null
                        val title = item.displayTitle.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        MediaReference(
                            ExternalMediaId(if (movie) ExternalIdNamespace.TmdbMovie else ExternalIdNamespace.TmdbTv,number.toString()),
                            title, setOfNotNull(item.title,item.name,item.originalTitle,item.originalName), item.year,
                            if (movie) MediaKind.Movie else MediaKind.Series,
                            SearchIdentityEvidence.artworkIds(item.posterPath?.let { "https://image.tmdb.org/t/p/original$it" }),
                        )
                    }
                    if (page >= (result.totalPages ?: 1)) break
                }
                references.distinctBy { it.id }.also { save(searches,key,it,30) }
            }.orEmpty()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { emptyList() }
    }

    suspend fun withImages(reference: MediaReference): MediaReference {
        cached(images,reference.id)?.let { return reference.copy(artworkIds = reference.artworkIds+it) }
        val type = when (reference.id.namespace) {
            ExternalIdNamespace.TmdbMovie -> "movie"
            ExternalIdNamespace.TmdbTv -> "tv"
            else -> return reference
        }
        return try {
            withTimeoutOrNull(3_000) {
                // No language filter: alternate localized posters are identity evidence too.
                val response = JSONObject(SearchSuggestionApi.metadata("/$type/${reference.id.value}/images").text)
                val posters = response.optJSONArray("posters") ?: return@withTimeoutOrNull reference
                val artwork = buildSet {
                    for (index in 0 until (posters.length())) {
                        val path = posters.optJSONObject(index)?.optString("file_path") ?: continue
                        addAll(SearchIdentityEvidence.artworkIds("https://image.tmdb.org/t/p/original$path"))
                    }
                }
                save(images,reference.id,artwork,100)
                reference.copy(artworkIds = reference.artworkIds+artwork)
            } ?: reference
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { reference }
    }

    /** A season poster identifies its parent show; scope remains on the original binding. */
    suspend fun withSeasonImages(reference: MediaReference, season: Int): MediaReference {
        if (reference.id.namespace != ExternalIdNamespace.TmdbTv || season !in 0..100) return reference
        val key = "${reference.id.value}:$season"
        cached(seasonImages, key)?.let { return reference.copy(artworkIds = reference.artworkIds + it) }
        return try {
            withTimeoutOrNull(3_000) {
                val response = JSONObject(SearchSuggestionApi.metadata("/tv/${reference.id.value}/season/$season/images").text)
                val posters = response.optJSONArray("posters") ?: return@withTimeoutOrNull reference
                val artwork = buildSet {
                    for (index in 0 until posters.length()) {
                        val path = posters.optJSONObject(index)?.optString("file_path") ?: continue
                        addAll(SearchIdentityEvidence.artworkIds("https://image.tmdb.org/t/p/original$path"))
                    }
                }
                save(seasonImages, key, artwork, 100)
                reference.copy(artworkIds = reference.artworkIds + artwork)
            } ?: reference
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { reference }
    }
}
