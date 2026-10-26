package com.lagradost.cloudstream4.aggregation

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.EpisodeResponse
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream4.domain.media.CanonicalEpisode
import com.lagradost.cloudstream4.domain.media.EpisodeAggregator
import com.lagradost.cloudstream4.domain.media.EpisodeCandidate
import com.lagradost.cloudstream4.domain.media.EpisodeNumbering
import com.lagradost.cloudstream4.domain.media.EpisodeProviderBinding
import com.lagradost.cloudstream4.domain.media.ExternalIdNamespace
import com.lagradost.cloudstream4.domain.media.ExternalMediaId
import com.lagradost.cloudstream4.domain.media.MediaAggregator
import com.lagradost.cloudstream4.domain.media.ProviderKey
import kotlinx.serialization.Serializable
import kotlin.math.abs

/** One provider route to a canonical episode. [data] is the extension's opaque payload. */
@Serializable
data class EpisodeSourceBinding(
    val apiName: String, val mainUrl: String, val mediaUrl: String, val data: String,
    val track: String = "None",
    val language: String? = null,
    val providerId: String? = null,
) {
    val providerKey: ProviderKey get() = ProviderKey(apiName, mainUrl)
}

/** [title]/[year] are what the rejected page declared, for diagnostics. */
data class DetailRejection(val binding: DetailBinding, val reason: String, val title: String? = null, val year: Int? = null)

/** A canonical episode and the provider episode whose metadata (title, plot, artwork) represents it. */
data class CanonicalEpisodeRow(val episode: CanonicalEpisode, val original: Episode)

/** Platform-neutral result of combining every loaded detail page of a canonical work. */
data class CanonicalDetailModel(
    /** Rejected bindings removed, IDs learned from accepted pages added. */
    val request: CanonicalDetailRequest,
    /** The page whose metadata (poster, plot, tags) represents the work: a whole-show binding first. */
    val base: LoadedDetail,
    val compatible: List<LoadedDetail>,
    val rejected: List<DetailRejection>,
    /** `movie` or `series`. */
    val family: String,
    /** Series rows in canonical order; empty for movies. */
    val episodes: List<CanonicalEpisodeRow>,
    /** Every playable movie binding; null for series. */
    val movie: CanonicalEpisode?,
    val requestedBindings: Int,
    val languages: Map<ProviderKey, String>,
) {
    val knownIds: Set<ExternalMediaId> get() = request.externalIds
    val incomplete: Boolean get() = compatible.size < requestedBindings

    /** Only flat absolute lists (and possibly unlocated rows) exist: show them as one season. */
    val absoluteOnly: Boolean get() = episodes.none { !it.episode.absolute && it.episode.season != null && it.episode.episode != null }

    fun sources(episode: CanonicalEpisode): List<EpisodeSourceBinding> {
        val ids = request.bindings.associate { it.mediaBinding() to it.providerId }
        return episode.bindings.map {
            EpisodeSourceBinding(it.media.provider.name, it.media.provider.mainUrl, it.media.url, it.data,
                it.track, languages[it.media.provider], ids[it.media])
        }
    }

    /** A provider-supplied season name, when one of the accepted pages has one. */
    fun seasonName(season: Int): String? = compatible.asSequence().mapNotNull { detail ->
        (detail.response as? EpisodeResponse)?.seasonNames?.firstOrNull { s -> (s.displaySeason ?: s.season) == season }?.name
    }.firstOrNull()
}

object CanonicalDetailBuilder {
    private fun familyOf(detail: LoadedDetail): String? = when (detail.response) {
        is MovieLoadResponse -> "movie"
        is TvSeriesLoadResponse, is AnimeLoadResponse -> "series"
        else -> null
    }

    private fun conflicts(ids: Set<ExternalMediaId>, known: Set<ExternalMediaId>) = ids.any { id ->
        known.any { it.namespace == id.namespace && it != id }
    }

    /** Titles a detail page may legitimately carry for the requested work. */
    private fun titleKeys(titles: Collection<String>): Set<String> = titles.flatMap { title ->
        val clean = SearchMediaMapper.cleanTitle(title)
        listOf(title, clean, SearchMediaMapper.withoutSeasonSuffix(clean))
    }.map(MediaAggregator::matchKey).filter { it.isNotBlank() }.toSet()

    /**
     * Accepts loaded pages independently of completion order. A page is rejected when it is of
     * another media family, contradicts a known ID, carries a minority ID among the other pages,
     * or (without a shared ID) contradicts the year or the observed titles of the work.
     */
    fun build(request: CanonicalDetailRequest, loaded: List<LoadedDetail>): CanonicalDetailModel? {
        val wantedFamily = MediaAggregator.family(request.kind) ?: return null
        val rejected = mutableListOf<DetailRejection>()
        fun reject(detail: LoadedDetail, reason: String) {
            rejected += DetailRejection(detail.binding, reason, detail.response.name, detail.response.year)
        }
        val metadata = loaded.associateWith { SearchMediaMetadata.of(it.response) }

        var remaining = loaded.filter { detail ->
            when {
                familyOf(detail) != wantedFamily -> false.also { reject(detail, "family") }
                conflicts(metadata.getValue(detail).ids, request.externalIds) -> false.also { reject(detail, "conflicting-id") }
                else -> true
            }
        }
        val provisionalBase = remaining.firstOrNull { it.binding.editions.isEmpty() } ?: remaining.firstOrNull()

        // IDs the request does not know yet: the value most pages agree on wins, ties go to the base page.
        val known = request.externalIds.toMutableSet()
        val namespaces = remaining.flatMap { metadata.getValue(it).ids }.map { it.namespace }.toSet() -
            request.externalIds.map { it.namespace }.toSet()
        for (namespace in namespaces) {
            val counts = remaining.flatMap { detail -> metadata.getValue(detail).ids.filter { it.namespace == namespace } }
                .groupingBy { it }.eachCount()
            val best = counts.values.max()
            val winner = counts.filterValues { it == best }.keys.singleOrNull()
                ?: provisionalBase?.let { base -> metadata.getValue(base).ids.firstOrNull { it.namespace == namespace } }
                ?: continue
            known += winner
        }
        // A movie and a series ID for one work cannot both be right; the family decides.
        if (wantedFamily == "movie") known.removeAll { it.namespace == ExternalIdNamespace.TmdbTv }
        if (wantedFamily == "series") known.removeAll { it.namespace == ExternalIdNamespace.TmdbMovie }
        remaining = remaining.filter { detail ->
            !conflicts(metadata.getValue(detail).ids, known).also { if (it) reject(detail, "minority-id") }
        }

        // Without a shared ID, the page must agree with the work's year and observed titles.
        val requestTitles = titleKeys(request.aliases + request.title)
        remaining = remaining.filter { detail ->
            val meta = metadata.getValue(detail)
            if (meta.ids.any { it in known }) return@filter true
            val scoped = detail.binding.editions.isNotEmpty()
            val delta = if (request.year != null && meta.year != null) abs(request.year - meta.year) else null
            when {
                !scoped && delta != null && delta > MediaAggregator.YEAR_TOLERANCE -> false.also { reject(detail, "year") }
                // Contexts saved before titles were recorded cannot verify titles.
                request.aliases.isNotEmpty() && delta != 0 && meta.alternateTitles.isNotEmpty() &&
                    titleKeys(meta.alternateTitles).intersect(requestTitles).isEmpty() -> false.also { reject(detail, "title") }
                else -> true
            }
        }

        val compatible = remaining
        val base = compatible.firstOrNull { it.binding.editions.isEmpty() } ?: compatible.firstOrNull() ?: return null
        val verified = request.copy(
            externalIds = known,
            bindings = request.bindings.filter { binding -> rejected.none { it.binding == binding } },
        )
        val languages = compatible.associate { it.binding.provider to it.api.lang }

        if (wantedFamily == "movie") {
            val bindings = compatible.map { item ->
                EpisodeProviderBinding(item.binding.mediaBinding(), (item.response as MovieLoadResponse).dataUrl)
            }.filter { it.data.isNotBlank() }
            if (bindings.isEmpty()) return null
            return CanonicalDetailModel(verified, base, compatible, rejected, wantedFamily, emptyList(),
                CanonicalEpisode("movie", null, null, bindings), request.bindings.size, languages)
        }

        val originals = linkedMapOf<String, Episode>()
        val candidates = mutableListOf<EpisodeCandidate>()
        compatible.forEach { item ->
            val seasonNames = (item.response as? EpisodeResponse)?.seasonNames
            val scope = item.binding.scopedSeason
            val episodes: List<Triple<Episode, DubStatus, Int>> = when (val response = item.response) {
                is TvSeriesLoadResponse -> response.episodes.mapIndexed { i, e -> Triple(e, DubStatus.None, i) }
                is AnimeLoadResponse -> response.episodes.flatMap { (track, list) ->
                    list.mapIndexed { i, e -> Triple(e, track, i) }
                }
                else -> emptyList()
            }.filter { it.first.data.isNotBlank() }
            fun seasonOf(episode: Episode): Int? = seasonNames?.firstOrNull { it.season == episode.season }
                ?.displaySeason ?: episode.season ?: EpisodeNumbering.fromLabel(episode.name)?.season
            fun numberOf(episode: Episode): Int? = episode.episode?.takeIf { it > 0 }
                ?: EpisodeNumbering.fromLabel(episode.name)?.episode
            // A flat, season-less, unscoped list is absolute numbering for the whole work.
            val absolute = scope == null && episodes.isNotEmpty() && seasonNames.isNullOrEmpty() &&
                episodes.all { seasonOf(it.first) == null } && episodes.any { numberOf(it.first) != null }
            episodes.forEach { (episode, track, index) ->
                val source = EpisodeProviderBinding(item.binding.mediaBinding(), episode.data)
                val season = seasonOf(episode)
                // Contradictory numbering is kept in an unresolved section, never guessed.
                val conflict = scope != null && season != null && scope != season
                val candidate = EpisodeCandidate(source, if (conflict) null else season, numberOf(episode),
                    track.name, index, if (conflict) null else scope, absolute)
                candidates += candidate
                val key = EpisodeAggregator.aggregate(listOf(candidate)).single().key
                val previous = originals[key]
                if (previous == null || ((previous.name.isNullOrBlank() ||
                            EpisodeNumbering.fromLabel(previous.name) != null) &&
                            !episode.name.isNullOrBlank() && EpisodeNumbering.fromLabel(episode.name) == null))
                    originals[key] = episode
            }
        }
        val rows = EpisodeAggregator.aggregate(candidates).map { CanonicalEpisodeRow(it, originals.getValue(it.key)) }
        return CanonicalDetailModel(verified, base, compatible, rejected, wantedFamily, rows, null,
            request.bindings.size, languages)
    }
}
