package com.kloudstream.desktop.app

import com.kloudstream.desktop.library.WatchEntry
import com.kloudstream.desktop.library.WatchHistory
import com.kloudstream.desktop.player.PlayRequest
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream4.aggregation.AggregationPlatform
import com.lagradost.cloudstream4.aggregation.CanonicalBindingCompleter
import com.lagradost.cloudstream4.aggregation.CanonicalDetailBuilder
import com.lagradost.cloudstream4.aggregation.CanonicalDetailLoader
import com.lagradost.cloudstream4.aggregation.CanonicalDetailModel
import com.lagradost.cloudstream4.aggregation.CanonicalDetailRequest
import com.lagradost.cloudstream4.aggregation.CanonicalDetailStore
import com.lagradost.cloudstream4.aggregation.CanonicalEpisodeRow
import com.lagradost.cloudstream4.aggregation.DiscoveryCatalog
import com.lagradost.cloudstream4.aggregation.Highlight
import com.lagradost.cloudstream4.aggregation.LoadedDetail
import com.lagradost.cloudstream4.aggregation.RuntimeCatalog
import com.lagradost.cloudstream4.domain.media.ExternalIdNamespace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A season as shown on the page; unplaced rows are grouped last. */
data class SeasonGroup(val number: Int?, val name: String, val episodes: List<CanonicalEpisodeRow>)

data class DetailState(
    val loading: Boolean = true,
    val model: CanonicalDetailModel? = null,
    val highlight: Highlight? = null,
    val seasons: List<SeasonGroup> = emptyList(),
    val error: String? = null,
) {
    val title: String? get() = highlight?.title?.takeIf { it.isNotBlank() } ?: model?.request?.title
    val overview: String? get() = highlight?.overview ?: model?.compatible?.firstNotNullOfOrNull { it.response.plot?.takeIf(String::isNotBlank) }
    val backdrop: String? get() = highlight?.backdropUrl ?: model?.base?.response?.backgroundPosterUrl
    val poster: String? get() = highlight?.posterUrl ?: model?.base?.response?.posterUrl
    val logo: String? get() = highlight?.logoUrl ?: model?.base?.response?.logoUrl
    val year: Int? get() = model?.request?.year ?: highlight?.year
    val genres: List<String> get() = highlight?.genres?.takeIf { it.isNotEmpty() } ?: model?.base?.response?.tags.orEmpty().take(4)
    val runtimeMinutes: Int? get() = highlight?.runtimeMinutes ?: model?.base?.response?.duration
    val rating: Double? get() = highlight?.rating ?: model?.base?.response?.score?.toDouble(10)
}

/**
 * Loads one canonical page: every provider page of the work, verified and combined by the shared
 * builder. The page shows as soon as most providers answered, then once more with all of them.
 */
class DetailModel(private val scope: CoroutineScope, private val initial: CanonicalDetailRequest) {
    private val _state = MutableStateFlow(DetailState())
    val state: StateFlow<DetailState> get() = _state
    private var job: Job? = null

    fun load() {
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            AppModel.extensionsReady.await()
            val providers = AggregationPlatform.registry.providers()
            var pinned = CanonicalDetailStore.pin(initial).restrictedTo(providers)
            val highlight = async { tmdbId(pinned)?.let { DiscoveryCatalog.highlight(it) } }
            val lock = Mutex()
            var published = false
            suspend fun publish(snapshot: List<LoadedDetail>, final: Boolean) = lock.withLock {
                if (published && !final) return@withLock
                val model = CanonicalDetailBuilder.build(pinned, snapshot) ?: return@withLock
                published = true
                _state.value = _state.value.copy(loading = !final, model = model, seasons = seasons(model))
            }
            // Works found in one catalogue are completed with the other providers once a day.
            val completion = if (pinned.completedAt?.let { System.currentTimeMillis() - it < 24 * 60 * 60_000L } == true) null
                else async { CanonicalBindingCompleter().complete(pinned, providers) }
            val early = launch {
                delay(4_000)
                val snapshot = latest
                if (snapshot.any { it.binding.editions.isEmpty() }) publish(snapshot, false)
            }
            val details = CanonicalDetailLoader().load(pinned, onProgress = { snapshot ->
                latest = snapshot
                if (snapshot.any { it.binding.editions.isEmpty() } && snapshot.size * 10 >= pinned.bindings.size * 6) publish(snapshot, false)
            })
            early.cancel()
            val extra = completion?.await().orEmpty()
            val all = if (extra.isEmpty()) details else {
                pinned = pinned.copy(bindings = (pinned.bindings + extra).distinctBy { it.mediaBinding() })
                details + CanonicalDetailLoader().load(pinned.copy(bindings = extra))
            }
            if (completion != null) pinned = pinned.copy(completedAt = System.currentTimeMillis())
            published = false
            publish(all, true)
            val model = _state.value.model
            if (model == null) {
                _state.value = _state.value.copy(loading = false, error = "Impossible de charger ce titre pour le moment.")
            } else {
                CanonicalDetailStore.remember(model.request.copy(completedAt = pinned.completedAt))
            }
            _state.value = _state.value.copy(highlight = highlight.await() ?: _state.value.highlight)
        }
        scope.launch(Dispatchers.IO) {
            tmdbId(initial)?.let { DiscoveryCatalog.highlight(it) }?.let { found ->
                if (_state.value.highlight == null) _state.value = _state.value.copy(highlight = found)
            }
        }
    }

    @Volatile private var latest: List<LoadedDetail> = emptyList()

    private fun tmdbId(request: CanonicalDetailRequest) = request.externalIds.firstOrNull {
        it.namespace == ExternalIdNamespace.TmdbMovie || it.namespace == ExternalIdNamespace.TmdbTv
    }

    private fun seasons(model: CanonicalDetailModel): List<SeasonGroup> {
        if (model.family != "series") return emptyList()
        if (model.absoluteOnly) return listOf(SeasonGroup(1, "Épisodes", model.episodes))
        val located = model.episodes.filter { !it.episode.absolute && it.episode.season != null && it.episode.episode != null }
        val groups = located.groupBy { it.episode.season!! }.toSortedMap().map { (season, rows) ->
            SeasonGroup(season, model.seasonName(season) ?: if (season == 0) "Épisodes spéciaux" else "Saison $season", rows)
        }
        val others = model.episodes - located.toSet()
        return if (others.isEmpty()) groups else groups + SeasonGroup(null, "Autres épisodes", others)
    }

    /** The movie, or [row] of the series, ready to play; [next] chains the following episode. */
    suspend fun playRequest(row: CanonicalEpisodeRow? = null): PlayRequest? {
        val model = _state.value.model ?: return null
        val request = model.request
        val ids = request.externalIds
        val history = WatchEntry(request.identity, _state.value.title ?: request.title, request.year,
            _state.value.poster, _state.value.backdrop, model.family, request)
        if (model.family == "movie") {
            val movie = model.movie ?: return null
            val tmdb = ids.firstOrNull { it.namespace == ExternalIdNamespace.TmdbMovie }?.value
            val minutes = tmdb?.let { runCatching { RuntimeCatalog.movieMinutes(it) }.getOrNull() }
                ?: model.base.response.duration?.takeIf { it in 20..400 }
            return PlayRequest(history.title, null, model.sources(movie), minutes?.let { it * 60.0 }, false, history)
        }
        val episodes = model.episodes
        val chosen = row ?: return null
        val tmdb = ids.firstOrNull { it.namespace == ExternalIdNamespace.TmdbTv }?.value
        val season = chosen.episode.season
        val number = chosen.episode.episode
        val minutes = if (tmdb != null && season != null && number != null && !chosen.episode.absolute)
            runCatching { RuntimeCatalog.episodeMinutes(tmdb, season, number) }.getOrNull() else null
        val index = episodes.indexOf(chosen)
        val next = episodes.getOrNull(index + 1)
        return PlayRequest(
            title = history.title,
            episodeLabel = episodeLabel(chosen),
            bindings = model.sources(chosen.episode),
            expectedSeconds = minutes?.let { it * 60.0 },
            isEpisode = true,
            history = history.copy(episodeKey = chosen.episode.key, season = season, episode = number,
                episodeTitle = chosen.original.name),
            next = next?.let { { playRequest(it) } },
        )
    }

    /** The episode to resume: the last one watched (or the next if finished), else the first. */
    fun resumeRow(): CanonicalEpisodeRow? {
        val model = _state.value.model ?: return null
        val entry = WatchHistory.entries.value.firstOrNull { it.identity == model.request.identity }
        val rows = model.episodes
        val last = entry?.episodeKey?.let { key -> rows.firstOrNull { it.episode.key == key } } ?: return rows.firstOrNull()
        return if (entry.finished) rows.getOrNull(rows.indexOf(last) + 1) ?: last else last
    }

    companion object {
        fun episodeLabel(row: CanonicalEpisodeRow): String {
            val episode = row.episode
            val number = when {
                episode.season != null && episode.episode != null && !episode.absolute -> "S${episode.season} É${episode.episode}"
                episode.episode != null -> "Épisode ${episode.episode}"
                else -> null
            }
            val name = cleanName(row.original)
            return listOfNotNull(number, name?.takeIf { number == null || !it.matches(Regex("(?i)(épisode|episode|ep\\.?)\\s*\\d+")) })
                .joinToString(" · ").ifBlank { "Épisode" }
        }

        fun cleanName(episode: Episode): String? = episode.name
            ?.replace(Regex("\\s+(?:VF|VOSTFR|VOST)(?:\\s*(?:📜|🇫🇷|🇬🇧))*\\s*$", RegexOption.IGNORE_CASE), "")
            ?.takeIf { it.isNotBlank() }
    }
}
