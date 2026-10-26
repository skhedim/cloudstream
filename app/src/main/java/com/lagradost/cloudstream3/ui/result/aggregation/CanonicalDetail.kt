package com.lagradost.cloudstream3.ui.result.aggregation

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.VideoWatchState
import com.lagradost.cloudstream3.ui.search.aggregation.SearchMediaMetadataCache
import com.lagradost.cloudstream3.utils.DataStoreHelper.getVideoWatchState
import com.lagradost.cloudstream3.utils.DataStoreHelper.getViewPos
import com.lagradost.cloudstream3.utils.DataStoreHelper.setViewPos
import com.lagradost.cloudstream3.utils.DataStoreHelper.setVideoWatchState
import com.lagradost.cloudstream3.utils.DataStoreHelper.currentAccount
import com.lagradost.cloudstream4.domain.media.*
import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
data class EpisodeSourceBinding(
    val apiName: String, val mainUrl: String, val mediaUrl: String, val data: String,
    val track: String = "None",
    val language: String? = null,
)

/** An app-only projection onto upstream's detail UI. Extensions only see their original payloads. */
data class CanonicalDetail(
    val request: CanonicalDetailRequest,
    val response: LoadResponse,
    val representative: LoadedDetail,
    val episodes: Map<String, CanonicalEpisode>,
    val loadedBindings: Int,
    val rejectedBindings: Int,
    val requestedBindings: Int,
    val languages: Map<ProviderKey, String> = emptyMap(),
) {
    val incomplete get() = loadedBindings < requestedBindings

    fun resolve(episode: ResultEpisode): ResultEpisode {
        val canonical = episodes.getValue(episode.data)
        val sources = canonical.bindings.map {
            EpisodeSourceBinding(it.media.provider.name, it.media.provider.mainUrl, it.media.url, it.data,
                it.track, languages[it.media.provider])
        }
        val first = sources.first()
        val id = if (response is MovieLoadResponse) episode.parentId
            else "${request.identity}:${canonical.key}".hashCode()
        val legacyIds = if (canonical.season != null && canonical.episode != null)
            listOf("None", "Dubbed", "Subbed").map { "${request.identity}:$it:${canonical.key}".hashCode() }
            else emptyList()
        // Milestone 3 saved one ID per version. Consolidate progress without deleting those records.
        val migrationKey = "$currentAccount/canonical_episode_migrated/$id"
        if (legacyIds.isNotEmpty() && CloudStreamApp.getKey<Boolean>(migrationKey) != true) {
            if (getViewPos(id) == null) {
                legacyIds.mapNotNull(::getViewPos).maxByOrNull { it.position }?.let {
                    setViewPos(id, it.position, it.duration)
                }
            }
            if (getVideoWatchState(id) == null && legacyIds.any { getVideoWatchState(it) == VideoWatchState.Watched })
                setVideoWatchState(id, VideoWatchState.Watched)
            CloudStreamApp.setKey(migrationKey, true)
        }
        val progress = getViewPos(id)
        return episode.copy(id = id, data = first.data, apiName = first.apiName,
            position = progress?.position ?: 0, duration = progress?.duration ?: 0,
            videoWatchState = getVideoWatchState(id) ?: VideoWatchState.None,
            sourceBindings = sources, legacyIds = legacyIds)
    }

    companion object {
        suspend fun create(request: CanonicalDetailRequest, loaded: List<LoadedDetail>): CanonicalDetail? {
            val wantedFamily = MediaAggregator.family(request.kind)
            val knownIds = request.externalIds.toMutableSet()
            val compatible = loaded.filter { item ->
                val response = item.response
                val family = when (response) {
                    is MovieLoadResponse -> "movie"
                    is TvSeriesLoadResponse, is AnimeLoadResponse -> "series"
                    else -> null
                }
                val ids = SearchMediaMetadataCache.get(item.binding.mediaBinding())?.ids.orEmpty()
                val accepted = family == wantedFamily && knownIds.none { expected ->
                    ids.any { actual -> actual.namespace == expected.namespace && actual != expected }
                }
                if (accepted) knownIds.addAll(ids)
                accepted
            }
            val base = compatible.firstOrNull { it.binding.editions.isEmpty() }
                ?: compatible.firstOrNull() ?: return null
            val originals = linkedMapOf<String, Episode>()
            val candidates = mutableListOf<EpisodeCandidate>()
            compatible.forEach { item ->
                fun add(episode: Episode, track: DubStatus, index: Int) {
                    if (episode.data.isBlank()) return
                    val source = EpisodeProviderBinding(item.binding.mediaBinding(), episode.data)
                    val label = EpisodeNumbering.fromLabel(episode.name)
                    val seasonNames = (item.response as? EpisodeResponse)?.seasonNames
                    val season = seasonNames?.firstOrNull { it.season == episode.season }
                        ?.displaySeason ?: episode.season ?: label?.season
                    val scope = item.binding.scopedSeason
                    // Contradictory numbering is kept in an unresolved section, never guessed.
                    val conflict = scope != null && season != null && scope != season
                    val candidate = EpisodeCandidate(source, if (conflict) null else season,
                        episode.episode?.takeIf { it > 0 } ?: label?.episode,
                        track.name, index, if (conflict) null else scope)
                    candidates += candidate
                    val key = EpisodeAggregator.aggregate(listOf(candidate)).single().key
                    val previous = originals[key]
                    if (previous == null || ((previous.name.isNullOrBlank() ||
                                EpisodeNumbering.fromLabel(previous.name) != null) &&
                                !episode.name.isNullOrBlank() && EpisodeNumbering.fromLabel(episode.name) == null))
                        originals[key] = episode
                }
                when (val response = item.response) {
                    is TvSeriesLoadResponse -> response.episodes.forEachIndexed { i, e -> add(e, DubStatus.None, i) }
                    is AnimeLoadResponse -> response.episodes.forEach { (track, list) ->
                        list.forEachIndexed { i, e -> add(e, track, i) }
                    }
                }
            }
            val routes = linkedMapOf<String, CanonicalEpisode>()
            val api = base.repo.api
            val projected = when (wantedFamily) {
                "movie" -> {
                    val bindings = compatible.map { item ->
                        EpisodeProviderBinding(item.binding.mediaBinding(), (item.response as MovieLoadResponse).dataUrl)
                    }.filter { it.data.isNotBlank() }
                    if (bindings.isEmpty()) return null
                    routes["canonical:movie"] = CanonicalEpisode("movie", null, null, bindings)
                    (base.response as MovieLoadResponse).copy(dataUrl = "canonical:movie")
                }
                "series" -> {
                    val grouped = EpisodeAggregator.aggregate(candidates)
                    var unresolved = 0
                    val rows = grouped.mapIndexed { index, episode ->
                        val token = "canonical:episode:$index"
                        routes[token] = episode
                        val original = originals.getValue(episode.key)
                        // Every uncertain row remains visible, with a unique display slot.
                        val located = episode.season != null && episode.episode != null
                        val displayName = if (EpisodeNumbering.fromLabel(original.name) != null)
                            original.name?.replace(Regex("\\s+(?:VF|VOSTFR|VOST)(?:\\s*(?:📜|🇫🇷|🇬🇧))*\\s*$", RegexOption.IGNORE_CASE), "")
                            else original.name
                        val row = original.copy(data = token, name = displayName,
                            season = if (located) episode.season else -1,
                            episode = if (located) episode.episode else ++unresolved)
                        row
                    }
                    val seasons = rows.mapNotNull { it.season }.distinct().sorted().map {
                        SeasonData(it, if (it == -1) CloudStreamApp.context?.getString(R.string.canonical_unlocated_episodes)
                            ?: "Episodes to identify" else
                            compatible.asSequence().mapNotNull { detail ->
                                (detail.response as? EpisodeResponse)?.seasonNames?.firstOrNull { s ->
                                    (s.displaySeason ?: s.season) == it
                                }?.name
                            }.firstOrNull(), displaySeason = it.takeIf { season -> season >= 0 })
                    }
                    if (base.response is AnimeLoadResponse) {
                        api.newAnimeLoadResponse(request.title, base.response.url, base.response.type) {
                            episodes = mutableMapOf(DubStatus.None to rows)
                            seasonNames = seasons
                        }
                    } else {
                        api.newTvSeriesLoadResponse(request.title, base.response.url, base.response.type,
                            rows) { seasonNames = seasons }
                    }
                }
                else -> return null
            }
            val metadata = base.response
            projected.apply {
                name = request.title
                // Saved cards must reopen through a persisted search binding, even if load()
                // returns a different canonical/redirected page URL.
                url = base.binding.url
                apiName = base.repo.name
                year = request.year ?: metadata.year
                // Stable identity for new history/bookmarks, independent of the representative URL.
                uniqueUrl = "canonical:v1:" + MessageDigest.getInstance("SHA-256")
                    .digest(request.identity.toByteArray()).joinToString("") { "%02x".format(it) }
                posterUrl = metadata.posterUrl
                posterHeaders = metadata.posterHeaders?.toMap()
                backgroundPosterUrl = metadata.backgroundPosterUrl
                logoUrl = metadata.logoUrl
                plot = compatible.firstNotNullOfOrNull { it.response.plot?.takeIf(String::isNotBlank) }
                score = metadata.score
                tags = metadata.tags?.toList()
                actors = metadata.actors?.toList()
                duration = metadata.duration
                trailers = metadata.trailers.toMutableList()
                recommendations = metadata.recommendations?.toList()
                contentRating = metadata.contentRating
                comingSoon = routes.isEmpty()
                syncData = metadata.syncData.toMutableMap()
                knownIds.forEach { id ->
                    when (id.namespace) {
                        ExternalIdNamespace.TmdbMovie, ExternalIdNamespace.TmdbTv -> addTMDbId(id.value)
                        ExternalIdNamespace.Imdb -> addImdbId(id.value)
                        else -> Unit
                    }
                }
                if (this is EpisodeResponse) {
                    showStatus = (metadata as? EpisodeResponse)?.showStatus
                    nextAiring = (metadata as? EpisodeResponse)?.nextAiring
                }
            }
            val rejected = (loaded - compatible.toSet()).map { it.binding.mediaBinding() }.toSet()
            val verifiedRequest = request.copy(externalIds = knownIds,
                bindings = request.bindings.filter { it.mediaBinding() !in rejected })
            return CanonicalDetail(verifiedRequest, projected, base, routes,
                compatible.size, loaded.size - compatible.size, request.bindings.size,
                compatible.associate { it.binding.provider to it.repo.api.lang })
        }
    }
}
