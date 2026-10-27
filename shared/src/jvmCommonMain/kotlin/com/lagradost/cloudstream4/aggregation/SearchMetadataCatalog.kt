package com.lagradost.cloudstream4.aggregation

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream4.domain.media.ExternalIdNamespace
import com.lagradost.cloudstream4.domain.media.ExternalMediaId
import com.lagradost.cloudstream4.domain.media.MediaKind
import com.lagradost.cloudstream4.domain.media.MediaReference
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** CloudStream's existing public TMDB access (search suggestions, TmdbProvider); no extension credentials. */
object TmdbAccess {
    private const val API_URL = "https://api.themoviedb.org/3"
    private const val API_KEY = "e6333b32409e02a4a6eba6fb7ff866bb"

    suspend fun get(path: String, params: Map<String, String> = emptyMap()): NiceResponse =
        app.get(API_URL + path, params = params + ("api_key" to API_KEY), cacheTime = 60 * 24)

    suspend fun json(path: String, params: Map<String, String> = emptyMap()): JsonNode =
        mapper.readTree(get(path, params).text)

    fun posterArtwork(node: JsonNode?): Set<String> = buildSet {
        node ?: return@buildSet
        for (poster in node) {
            val path = poster.path("file_path").asText("").takeIf { it.startsWith("/") } ?: continue
            addAll(SearchIdentityEvidence.artworkIds("https://image.tmdb.org/t/p/original$path"))
        }
    }
}

/** Bounded optional TMDB lookups. Failures leave the provider-only search fully usable. */
object SearchMetadataCatalog {
    private data class Saved<T>(val time: Long, val value: T)
    private const val TTL = 24 * 60 * 60 * 1000L
    private val searches = linkedMapOf<String, Saved<List<MediaReference>>>()
    private val images = linkedMapOf<ExternalMediaId, Saved<Set<String>>>()
    private val seasonImages = linkedMapOf<String, Saved<Set<String>>>()
    private val linkedIds = linkedMapOf<ExternalMediaId, Saved<Set<ExternalMediaId>>>()

    @Synchronized private fun <K,T> cached(cache: MutableMap<K,Saved<T>>, key: K): T? =
        cache[key]?.takeIf { System.currentTimeMillis()-it.time < TTL }?.value

    @Synchronized private fun <K,T> save(cache: MutableMap<K,Saved<T>>, key: K, value: T, limit: Int) {
        cache[key] = Saved(System.currentTimeMillis(),value)
        while (cache.size > limit) cache.remove(cache.keys.first())
    }

    suspend fun search(query: String, language: String = AggregationPlatform.metadataLanguage()): List<MediaReference> {
        val key = "$language:${query.trim().lowercase()}"
        cached(searches,key)?.let { return it }
        return try {
            withTimeoutOrNull(5_000) {
                val references = mutableListOf<MediaReference>()
                for (page in 1..2) {
                    val result = mapper.readValue(TmdbAccess.get("/search/multi",mapOf(
                        "query" to query, "language" to language, "page" to page.toString(),
                    )).text, TmdbProvider.TmdbPageResult::class.java)
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
                // Original-version and anime catalogues use English titles ("Frieren: Beyond Journey's End").
                val english = if (language.startsWith("en")) emptyMap() else englishTitles(query)
                references.distinctBy { it.id }
                    .map { ref -> english[ref.id]?.let { ref.copy(alternateTitles = ref.alternateTitles + it) } ?: ref }
                    .also { save(searches,key,it,30) }
            }.orEmpty()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { emptyList() }
    }

    private suspend fun englishTitles(query: String): Map<ExternalMediaId, String> = try {
        mapper.readValue(TmdbAccess.get("/search/multi", mapOf("query" to query, "language" to "en-US", "page" to "1")).text,
            TmdbProvider.TmdbPageResult::class.java).results.orEmpty().mapNotNull { item ->
            val namespace = when (item.mediaType) { "movie" -> ExternalIdNamespace.TmdbMovie; "tv" -> ExternalIdNamespace.TmdbTv; else -> return@mapNotNull null }
            val number = item.id?.takeIf { it > 0 } ?: return@mapNotNull null
            (item.title ?: item.name)?.takeIf { it.isNotBlank() }?.let { ExternalMediaId(namespace, number.toString()) to it }
        }.toMap()
    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { emptyMap() }

    private val englishTitles = linkedMapOf<ExternalMediaId, Saved<String?>>()

    /** The work's English title on TMDB, for providers that only know it. */
    suspend fun englishTitle(id: ExternalMediaId): String? {
        cached(englishTitles, id)?.let { return it }
        val type = when (id.namespace) {
            ExternalIdNamespace.TmdbMovie -> "movie"
            ExternalIdNamespace.TmdbTv -> "tv"
            else -> return null
        }
        return try {
            withTimeoutOrNull(3_000) {
                val node = TmdbAccess.json("/$type/${id.value}", mapOf("language" to "en-US"))
                node.path(if (type == "movie") "title" else "name").asText("").takeIf { it.isNotBlank() }
                    .also { save(englishTitles, id, it, 200) }
            }
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
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
                val posters = TmdbAccess.json("/$type/${reference.id.value}/images").get("posters")
                    ?: return@withTimeoutOrNull reference
                // Catalogues often show a season's main poster on their whole-show entry.
                val seasonPosters = if (type != "tv") emptyList() else try {
                    TmdbAccess.json("/tv/${reference.id.value}").get("seasons")?.mapNotNull { season ->
                        season.path("poster_path").asText("").takeIf { it.startsWith("/") }
                    }.orEmpty().flatMap { SearchIdentityEvidence.artworkIds("https://image.tmdb.org/t/p/original$it") }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { emptyList() }
                val artwork = TmdbAccess.posterArtwork(posters) + seasonPosters
                save(images,reference.id,artwork,100)
                reference.copy(artworkIds = reference.artworkIds+artwork)
            } ?: reference
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { reference }
    }

    /** IDs TMDB links to the reference (IMDb), so providers using another namespace can join it. */
    suspend fun withExternalIds(reference: MediaReference): MediaReference {
        cached(linkedIds, reference.id)?.let { return reference.copy(equivalentIds = reference.equivalentIds + it) }
        val type = when (reference.id.namespace) {
            ExternalIdNamespace.TmdbMovie -> "movie"
            ExternalIdNamespace.TmdbTv -> "tv"
            else -> return reference
        }
        return try {
            withTimeoutOrNull(3_000) {
                val imdb = TmdbAccess.json("/$type/${reference.id.value}/external_ids").path("imdb_id").asText("")
                val ids = setOfNotNull(runCatching { ExternalMediaId(ExternalIdNamespace.Imdb, imdb) }.getOrNull())
                save(linkedIds, reference.id, ids, 100)
                reference.copy(equivalentIds = reference.equivalentIds + ids)
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
                val posters = TmdbAccess.json("/tv/${reference.id.value}/season/$season/images").get("posters")
                    ?: return@withTimeoutOrNull reference
                val artwork = TmdbAccess.posterArtwork(posters)
                save(seasonImages, key, artwork, 100)
                reference.copy(artworkIds = reference.artworkIds + artwork)
            } ?: reference
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { reference }
    }
}
