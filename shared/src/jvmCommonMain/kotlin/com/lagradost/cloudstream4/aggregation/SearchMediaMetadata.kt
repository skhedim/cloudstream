package com.lagradost.cloudstream4.aggregation

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.getAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.getImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.getKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.getMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.getTMDbId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream4.domain.media.ExternalIdNamespace
import com.lagradost.cloudstream4.domain.media.ExternalMediaId
import com.lagradost.cloudstream4.domain.media.ProviderBinding
import com.lagradost.cloudstream4.domain.media.ProviderKey
import com.lagradost.cloudstream4.domain.media.MediaKind

data class SearchMediaMetadata(
    val year: Int?, val alternateTitles: Set<String>, val ids: Set<ExternalMediaId>,
    val artworkIds: Set<String> = emptySet(),
    val kind: MediaKind = MediaKind.Unknown,
)

/** Bounded, in-memory metadata from already opened pages; search does not fetch every detail page. */
object SearchMediaMetadataCache {
    private data class Entry(val savedAt: Long, val metadata: SearchMediaMetadata)
    private val entries = linkedMapOf<ProviderBinding, Entry>()
    private const val MAX_ENTRIES = 200
    private const val TTL_MS = 10 * 60 * 1000L

    @Synchronized
    fun remember(
        api: MainAPI, requestedUrl: String, response: LoadResponse,
        provider: ProviderKey = ProviderKey(api.name, api.mainUrl),
    ) {
        val ids = buildSet {
            fun addId(namespace: ExternalIdNamespace, value: String?) {
                value?.let { runCatching { ExternalMediaId(namespace, it.trim()) }.getOrNull() }?.let(::add)
            }
            addId(ExternalIdNamespace.Imdb, response.getImdbId())
            val tmdbNamespace = when (response.type) {
                TvType.Movie, TvType.AnimeMovie -> ExternalIdNamespace.TmdbMovie
                TvType.TvSeries, TvType.Anime, TvType.Cartoon, TvType.AsianDrama -> ExternalIdNamespace.TmdbTv
                else -> null
            }
            tmdbNamespace?.let { addId(it, response.getTMDbId()) }
            addId(ExternalIdNamespace.AniList, response.getAniListId())
            addId(ExternalIdNamespace.MyAnimeList, response.getMalId())
            addId(ExternalIdNamespace.Kitsu, response.getKitsuId())
        }
        val titles = buildSet {
            add(response.name)
            if (response is AnimeLoadResponse) {
                response.engName?.let(::add)
                response.japName?.let(::add)
                addAll(response.synonyms.orEmpty())
            }
        }
        val entry = Entry(System.currentTimeMillis(), SearchMediaMetadata(
            response.year, titles, ids, SearchIdentityEvidence.artworkIds(response.posterUrl),
            SearchMediaMapper.kind(response.type),
        ))
        entries[ProviderBinding(provider, requestedUrl)] = entry
        entries[ProviderBinding(provider, response.url)] = entry
        while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
    }

    @Synchronized
    fun get(binding: ProviderBinding): SearchMediaMetadata? {
        val entry = entries[binding] ?: return null
        if (System.currentTimeMillis() - entry.savedAt > TTL_MS) {
            entries.remove(binding)
            return null
        }
        return entry.metadata
    }
}
