package com.kloudstream.desktop.app

import com.kloudstream.desktop.core.AppLog
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.home.aggregation.AggregatedHomeProjector
import com.lagradost.cloudstream3.ui.home.aggregation.HomeFeed
import com.lagradost.cloudstream3.ui.home.aggregation.HomeFeedPolicy
import com.lagradost.cloudstream3.ui.home.aggregation.HomeShelf
import com.lagradost.cloudstream4.aggregation.AggregationPlatform
import com.lagradost.cloudstream4.aggregation.DiscoveryCatalog
import com.lagradost.cloudstream4.aggregation.Highlight
import com.lagradost.cloudstream4.aggregation.PlaybackSources
import com.lagradost.cloudstream4.aggregation.RegisteredProvider
import com.lagradost.cloudstream4.domain.media.ExternalIdNamespace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

data class HomeRow(val shelf: HomeShelf, val title: String, val cards: List<MediaCard>)
data class HeroItem(val card: MediaCard, val highlight: Highlight)

/** What the home showed last time, restored instantly at the next start. */
data class HomeSnapshot(val rows: List<Row> = emptyList(), val hero: List<Hero> = emptyList()) {
    data class Card(val title: String = "", val year: Int? = null, val poster: String? = null, val score: Double? = null,
        val request: com.lagradost.cloudstream4.aggregation.CanonicalDetailRequest? = null)
    data class Row(val shelf: HomeShelf = HomeShelf.Discover, val cards: List<Card> = emptyList())
    data class Hero(val card: Card = Card(), val highlight: Highlight? = null)
}

data class HomeState(
    val loading: Boolean = true,
    val rows: List<HomeRow> = emptyList(),
    val hero: List<HeroItem> = emptyList(),
    val status: String? = null,
)

/**
 * The discovery home: first pages of the providers in the viewer's language, merged into one card
 * per work by the shared projector, ranked with this week's TMDB trends, with a featured hero.
 */
class HomeModel(private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> get() = _state
    private var job: Job? = null
    private val snapshotFile get() = java.io.File(com.kloudstream.desktop.core.AppDirs.data, "home.json")

    /** Shows the last session's home while the providers load. */
    fun restore() {
        val saved = runCatching { com.lagradost.cloudstream3.mapper.readValue(snapshotFile, HomeSnapshot::class.java) }.getOrNull() ?: return
        fun HomeSnapshot.Card.toCard() = request?.let { MediaCard.of(title, year, poster, score, it) }
        val rows = saved.rows.map { row -> HomeRow(row.shelf, shelfTitle(row.shelf), row.cards.mapNotNull { it.toCard() }) }
            .filter { it.cards.isNotEmpty() }
        val hero = saved.hero.mapNotNull { item -> val card = item.card.toCard(); if (card != null && item.highlight != null) HeroItem(card, item.highlight) else null }
        if (rows.isNotEmpty()) _state.value = HomeState(loading = true, rows = rows, hero = hero, status = "Mise à jour des nouveautés…")
    }

    private fun save(state: HomeState) {
        fun MediaCard.toSnapshot() = HomeSnapshot.Card(title, year, poster, score, request())
        val snapshot = HomeSnapshot(state.rows.map { row -> HomeSnapshot.Row(row.shelf, row.cards.take(30).map { it.toSnapshot() }) },
            state.hero.map { HomeSnapshot.Hero(it.card.toSnapshot(), it.highlight) })
        runCatching {
            val tmp = java.io.File(snapshotFile.parentFile, "home.json.tmp")
            com.lagradost.cloudstream3.mapper.writeValue(tmp, snapshot)
            java.nio.file.Files.move(tmp.toPath(), snapshotFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }.onFailure { AppLog.warn("Could not save the home", it) }
    }

    fun load() {
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(loading = true, status = if (_state.value.rows.isEmpty()) "Chargement des nouveautés…" else "Mise à jour des nouveautés…")
            val restored = _state.value.rows.isNotEmpty()
            val viewer = PlaybackSources.viewerLanguage()
            val providers = AggregationPlatform.registry.providers().filter { it.api.hasMainPage }
                .filter { it.api.lang.substringBefore('-').lowercase() in setOf(viewer, "multi", "mul", "all") }
            val feeds = arrayOfNulls<HomeFeed>(providers.size)
            val projector = AggregatedHomeProjector()
            val lock = Mutex()
            var trending = emptyMap<com.lagradost.cloudstream4.domain.media.ExternalMediaId, Int>()
            var heroBuilt = false
            suspend fun publish(final: Boolean) = lock.withLock {
                val shelves = projector.project(feeds.filterNotNull(), trending = trending)
                val rows = shelves.map { row -> HomeRow(row.shelf, shelfTitle(row.shelf), row.cards.map { MediaCard(it.media, it.responses) }) }
                // The restored home stays until the new one is complete enough to replace it.
                if (restored && !final && rows.sumOf { it.cards.size } < 60) return@withLock
                _state.value = _state.value.copy(rows = rows, loading = !final,
                    status = if (final) null else "Chargement des nouveautés…")
                if (final) save(_state.value)
                val featured = rows.firstOrNull { it.shelf == HomeShelf.Top10 } ?: rows.firstOrNull { it.shelf == HomeShelf.Recent }
                if (featured != null && (!heroBuilt || final && (_state.value.hero.isEmpty() || restored)) && (featured.cards.size >= 5 || final)) {
                    heroBuilt = true
                    scope.launch(Dispatchers.IO) { buildHero(featured.cards) }
                }
            }
            coroutineScope {
                launch {
                    trending = DiscoveryCatalog.trendingRanks()
                    if (feeds.any { it != null }) publish(false)
                }
                val semaphore = Semaphore(4)
                providers.mapIndexed { index, provider ->
                    async {
                        val feed = semaphore.withPermit { loadFeed(provider) }
                        feeds[index] = feed
                        publish(false)
                    }
                }.awaitAll()
            }
            publish(true)
            if (projector.refine()) publish(true)
        }
    }

    private suspend fun loadFeed(provider: RegisteredProvider): HomeFeed {
        val api = provider.api
        val pages = api.mainPage
        val sections = mutableListOf<HomePageList>()
        withTimeoutOrNull(20_000) {
            for (index in HomeFeedPolicy.requests(pages)) {
                val page = pages[index]
                val response = try {
                    withTimeoutOrNull(8_000) { api.getMainPage(1, MainPageRequest(page.name, page.data, page.horizontalImages)) }
                } catch (cancelled: CancellationException) { throw cancelled } catch (t: Throwable) {
                    AppLog.warn("${api.name} home section ${page.name} failed: $t"); null
                }
                response?.items?.forEach { list -> sections += list.copy(name = page.name + " " + list.name, list = list.list.take(120)) }
            }
        }
        val animationOnly = api.supportedTypes.isNotEmpty() && api.supportedTypes.all {
            it in setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.Cartoon)
        }
        return HomeFeed(provider.key, api.javaClass.name, sections, animationOnly)
    }

    private suspend fun buildHero(cards: List<MediaCard>) {
        val picks = cards.mapNotNull { card ->
            card.media.externalIds.firstOrNull { it.namespace == ExternalIdNamespace.TmdbMovie || it.namespace == ExternalIdNamespace.TmdbTv }
                ?.let { card to it }
        }.take(8)
        val hero = coroutineScope {
            picks.map { (card, id) -> async { DiscoveryCatalog.highlight(id)?.let { HeroItem(card, it) } } }.awaitAll()
        }.filterNotNull().filter { it.highlight.backdropUrl != null }.take(6)
        if (hero.isNotEmpty()) {
            _state.value = _state.value.copy(hero = hero)
            if (!_state.value.loading) save(_state.value)
        }
    }

    companion object {
        fun shelfTitle(shelf: HomeShelf): String = when (shelf) {
            HomeShelf.Top10 -> "Top 10 de la semaine"
            HomeShelf.Recent -> "Derniers ajouts"
            HomeShelf.Trending -> "Tendances du moment"
            HomeShelf.ForYou -> "Films pour vous"
            HomeShelf.ForYouSeries -> "Séries pour vous"
            HomeShelf.Movies -> "Films"
            HomeShelf.Series -> "Séries"
            HomeShelf.Anime -> "Animés et animation"
            HomeShelf.Documentaries -> "Documentaires"
            HomeShelf.Action -> "Action"
            HomeShelf.Comedy -> "Comédie"
            HomeShelf.Thriller -> "Thriller"
            HomeShelf.ScienceFiction -> "Science-fiction"
            HomeShelf.Discover -> "À découvrir"
        }
    }
}
