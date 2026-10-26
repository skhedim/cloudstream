package com.lagradost.cloudstream4.domain.media

/** Opaque payloads belong to their extension. Never interpret or rewrite [data]. */
data class EpisodeProviderBinding(val media: ProviderBinding, val data: String, val track: String = "None")

data class EpisodeCandidate(
    val binding: EpisodeProviderBinding,
    val season: Int?,
    val episode: Int?,
    /** Version belongs to the source, not the episode's identity. */
    val track: String,
    val sourceIndex: Int,
    val scopedSeason: Int? = null,
    /**
     * The provider lists the whole work as one flat, season-less sequence. Absolute numbers only
     * match other absolute lists: they are never converted to season/episode coordinates.
     */
    val absolute: Boolean = false,
)

data class CanonicalEpisode(
    val key: String,
    val season: Int?,
    val episode: Int?,
    val bindings: List<EpisodeProviderBinding>,
    val absolute: Boolean = false,
) {
    val tracks: Set<String> get() = bindings.map { it.track }.toSet()
}

object EpisodeAggregator {
    fun aggregate(candidates: List<EpisodeCandidate>): List<CanonicalEpisode> {
        val groups = linkedMapOf<String, MutableList<EpisodeCandidate>>()
        candidates.forEach { candidate ->
            val season = candidate.season?.takeIf { it >= 0 } ?: candidate.scopedSeason
            val episode = candidate.episode?.takeIf { it > 0 }
            val key = if (season != null && episode != null) {
                "s$season:e$episode"
            } else if (candidate.absolute && season == null && episode != null) {
                "abs:e$episode"
            } else {
                // Neither list position nor an episode title proves identity across providers.
                val media = candidate.binding.media
                "${candidate.track}:unlocated:${media.provider.name.length}:${media.provider.name}:" +
                    "${media.provider.mainUrl.length}:${media.provider.mainUrl}:${media.url}:${candidate.sourceIndex}"
            }
            groups.getOrPut(key) { mutableListOf() }.add(candidate)
        }
        return groups.map { (key, members) ->
            val first = members.first()
            val absolute = key.startsWith("abs:")
            CanonicalEpisode(key, if (absolute) null else first.season?.takeIf { it >= 0 } ?: first.scopedSeason,
                first.episode?.takeIf { it > 0 }, members.map { it.binding.copy(track = it.track) }.distinct(), absolute)
        }.sortedWith(compareBy<CanonicalEpisode> { it.absolute }.thenBy { it.season ?: Int.MAX_VALUE }
            .thenBy { it.episode ?: Int.MAX_VALUE }.thenBy { it.key })
    }
}

/** Independent of provider completion order and the ephemeral search card number. */
object CanonicalIdentity {
    fun key(media: CanonicalMedia): String {
        val edition = media.bindingEditions.values.flatten().filterNot { it.startsWith("season:") }
            .distinct().sorted().joinToString(",").let { if (it.isEmpty()) "" else ":edition:$it" }
        val preferred = media.externalIds.sortedWith(compareBy<ExternalMediaId> {
            when (it.namespace) {
                ExternalIdNamespace.TmdbMovie, ExternalIdNamespace.TmdbTv -> 0
                ExternalIdNamespace.Imdb -> 1
                else -> 2
            }
        }.thenBy { it.namespace.name }.thenBy { it.value }).firstOrNull()
        if (preferred != null) return "canonical:v1:${preferred.namespace}:${preferred.value}$edition"
        val title = MediaAggregator.normalizeTitle(media.title)
        val uncertain = if (media.year == null) media.bindings.map {
            "${it.provider.name}|${it.provider.mainUrl}|${it.url}"
        }.sorted().firstOrNull().orEmpty() else ""
        return "canonical:v1:${MediaAggregator.family(media.kind)}:$title:${media.year}:$uncertain$edition"
    }
}
