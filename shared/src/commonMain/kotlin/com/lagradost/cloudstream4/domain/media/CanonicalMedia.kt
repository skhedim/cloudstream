package com.lagradost.cloudstream4.domain.media

/** Application identities, independent of the binary extension API. */
data class ProviderKey(val name: String, val mainUrl: String)

data class ProviderBinding(val provider: ProviderKey, val url: String)

enum class MediaKind { Movie, Series, AnimeMovie, Anime, Cartoon, AsianDrama, Unknown }

enum class ExternalIdNamespace { Imdb, TmdbMovie, TmdbTv, AniList, MyAnimeList, Kitsu }

data class ExternalMediaId(val namespace: ExternalIdNamespace, val value: String) {
    init {
        require(
            if (namespace == ExternalIdNamespace.Imdb) value.matches(Regex("tt[0-9]+"))
            else value.toLongOrNull()?.let { it > 0 } == true
        ) { "Invalid external media ID" }
    }
}

data class MediaCandidate(
    val binding: ProviderBinding,
    val title: String,
    val year: Int?,
    val kind: MediaKind,
    val alternateTitles: Set<String> = emptySet(),
    val externalIds: Set<ExternalMediaId> = emptySet(),
    /** A season/cour entry must not be treated as a complete series. */
    val edition: Set<String> = emptySet(),
    /** Namespaced artwork identities from a metadata service, never arbitrary provider posters. */
    val artworkIds: Set<String> = emptySet(),
    val artworkSignature: ArtworkSignature? = null,
)

enum class MatchReason { ExternalId, TitleYearType, TitleNearYearType, TitleArtworkType, TitleVisualArtworkType }

data class CanonicalMedia(
    /** Stable within a search session; never replaces saved playback/favourite IDs. */
    val id: Long,
    val title: String,
    val year: Int?,
    val kind: MediaKind,
    val externalIds: Set<ExternalMediaId>,
    val bindings: List<ProviderBinding>,
    val matchReasons: Set<MatchReason>,
    /** Retain season/part scope even when a trusted show ID groups several catalogue entries. */
    val bindingEditions: Map<ProviderBinding, Set<String>> = emptyMap(),
    /** Every provider/metadata title of the grouped candidates, as supplied (not normalized). */
    val titles: Set<String> = emptySet(),
)
