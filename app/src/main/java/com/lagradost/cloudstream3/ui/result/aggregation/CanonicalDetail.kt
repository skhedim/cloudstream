package com.lagradost.cloudstream3.ui.result.aggregation

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.VideoWatchState
import com.lagradost.cloudstream3.utils.DataStoreHelper.getVideoWatchState
import com.lagradost.cloudstream3.utils.DataStoreHelper.getViewPos
import com.lagradost.cloudstream3.utils.DataStoreHelper.setViewPos
import com.lagradost.cloudstream3.utils.DataStoreHelper.setVideoWatchState
import com.lagradost.cloudstream3.utils.DataStoreHelper.currentAccount
import com.lagradost.cloudstream4.aggregation.CanonicalDetailBuilder
import com.lagradost.cloudstream4.aggregation.CanonicalDetailModel
import com.lagradost.cloudstream4.aggregation.CanonicalDetailRequest
import com.lagradost.cloudstream4.aggregation.Highlight
import com.lagradost.cloudstream4.aggregation.LoadedDetail
import com.lagradost.cloudstream4.domain.media.*
import java.security.MessageDigest

/** An app-only projection of [CanonicalDetailModel] onto upstream's detail UI. Extensions only see their original payloads. */
data class CanonicalDetail(
    val model: CanonicalDetailModel,
    val response: LoadResponse,
    val episodes: Map<String, CanonicalEpisode>,
) {
    val request: CanonicalDetailRequest get() = model.request
    val representative: LoadedDetail get() = model.base
    val loadedBindings: Int get() = model.compatible.size
    val rejectedBindings: Int get() = model.rejected.size
    val requestedBindings: Int get() = model.requestedBindings
    val incomplete get() = loadedBindings < requestedBindings

    fun resolve(episode: ResultEpisode): ResultEpisode {
        val canonical = episodes.getValue(episode.data)
        val sources = model.sources(canonical)
        val first = sources.first()
        val id = if (response is MovieLoadResponse) episode.parentId else episodeId(request.identity, canonical.key)
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
        /** Season slots for rows that cannot be placed in a numbered season. */
        const val UNLOCATED_SEASON = -1
        const val ABSOLUTE_SEASON = -2

        /** The page's [LoadResponse.uniqueUrl]: history and bookmarks follow the work, not a provider page. */
        fun pageUrl(identity: String) = "canonical:v1:" + MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }

        /** The page's ID in the history (getId() of [pageUrl], which has no provider URL or slash in it). */
        fun pageId(identity: String) = pageUrl(identity).hashCode()

        /** A series episode's ID in the history; a film's is [pageId]. */
        fun episodeId(identity: String, episodeKey: String) = "$identity:$episodeKey".hashCode()

        /** [highlight]: the work's TMDB metadata, for its cast. */
        suspend fun create(request: CanonicalDetailRequest, loaded: List<LoadedDetail>, highlight: Highlight? = null): CanonicalDetail? =
            CanonicalDetailBuilder.build(request, loaded)?.let { project(it, highlight) }

        suspend fun project(model: CanonicalDetailModel, highlight: Highlight? = null): CanonicalDetail? {
            val request = model.request
            val base = model.base
            val routes = linkedMapOf<String, CanonicalEpisode>()
            val api = base.api
            val projected = when (model.family) {
                "movie" -> {
                    routes["canonical:movie"] = model.movie ?: return null
                    (base.response as MovieLoadResponse).copy(dataUrl = "canonical:movie")
                }
                "series" -> {
                    var unresolved = 0
                    val absoluteAsSeason = model.absoluteOnly
                    val rows = model.episodes.mapIndexed { index, (episode, original) ->
                        val token = "canonical:episode:$index"
                        routes[token] = episode
                        // Every uncertain row remains visible, with a unique display slot.
                        val located = episode.season != null && episode.episode != null
                        val displayName = if (EpisodeNumbering.fromLabel(original.name) != null)
                            original.name?.replace(Regex("\\s+(?:VF|VOSTFR|VOST)(?:\\s*(?:📜|🇫🇷|🇬🇧))*\\s*$", RegexOption.IGNORE_CASE), "")
                            else original.name
                        when {
                            located -> original.copy(data = token, name = displayName,
                                season = episode.season, episode = episode.episode)
                            episode.absolute -> original.copy(data = token, name = displayName,
                                season = if (absoluteAsSeason) 1 else ABSOLUTE_SEASON, episode = episode.episode)
                            else -> original.copy(data = token, name = displayName,
                                season = UNLOCATED_SEASON, episode = ++unresolved)
                        }
                    }
                    val context = CloudStreamApp.context
                    val seasons = rows.mapNotNull { it.season }.distinct().sorted().map {
                        val name = when (it) {
                            UNLOCATED_SEASON -> context?.getString(R.string.canonical_unlocated_episodes) ?: "Episodes to identify"
                            ABSOLUTE_SEASON -> context?.getString(R.string.canonical_absolute_episodes) ?: "Absolute numbering"
                            else -> model.seasonName(it)
                        }
                        SeasonData(it, name, displaySeason = it.takeIf { season -> season >= 0 })
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
                apiName = api.name
                year = request.year ?: metadata.year
                // Stable identity for new history/bookmarks, independent of the representative URL.
                uniqueUrl = pageUrl(request.identity)
                posterUrl = metadata.posterUrl
                posterHeaders = metadata.posterHeaders?.toMap()
                backgroundPosterUrl = metadata.backgroundPosterUrl
                logoUrl = metadata.logoUrl
                plot = model.compatible.firstNotNullOfOrNull { it.response.plot?.takeIf(String::isNotBlank) }
                score = metadata.score
                tags = metadata.tags?.toList()
                // TMDB's cast (photos and roles for every work), else the first provider naming actors.
                actors = highlight?.cast?.takeIf { it.isNotEmpty() }
                    ?.map { ActorData(Actor(it.name, it.photoUrl), roleString = it.character) }
                    ?: model.compatible.firstNotNullOfOrNull { page -> page.response.actors?.takeIf { it.isNotEmpty() } }?.toList()
                duration = metadata.duration
                trailers = metadata.trailers.toMutableList()
                recommendations = metadata.recommendations?.toList()
                contentRating = metadata.contentRating
                comingSoon = routes.isEmpty()
                syncData = metadata.syncData.toMutableMap()
                model.knownIds.forEach { id ->
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
            return CanonicalDetail(model, projected, routes)
        }
    }
}
