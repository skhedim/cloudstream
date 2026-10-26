package com.lagradost.cloudstream3.ui.home.aggregation

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.ui.search.aggregation.AggregatedSearchResponse
import com.lagradost.cloudstream4.aggregation.SearchMediaMapper
import com.lagradost.cloudstream4.domain.media.*
import java.text.Normalizer
import com.lagradost.cloudstream4.aggregation.SearchArtworkSignatures
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Rows describe content, never extension labels. No release dates are invented. */
/** Shelves in display order, like streaming platforms: what everyone watches, what is new, what is for you. */
enum class HomeShelf(val title: Int) {
    Top10(R.string.home_top10), Recent(R.string.home_recent_additions), Trending(R.string.home_trending),
    ForYou(R.string.home_for_you_movies), ForYouSeries(R.string.home_for_you_series),
    Movies(R.string.home_movies), Series(R.string.home_series),
    Anime(R.string.home_anime), Documentaries(R.string.home_documentaries), Action(R.string.home_action),
    Comedy(R.string.home_comedy), Thriller(R.string.home_thriller), ScienceFiction(R.string.home_scifi),
    Discover(R.string.home_discover)
}
data class HomeFeed(val provider: ProviderKey, val implementation: String, val sections: List<HomePageList>, val animationOnly: Boolean = false)
data class HomeShelfItems(val shelf: HomeShelf, val cards: List<AggregatedSearchResponse>)

object HomeFeedPolicy {
    private fun normalize(text: String) = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
    fun recent(text: String): Boolean {
        val label = normalize(text)
        if (Regex("\\b(populaires?|popular|trending|tendances?|top|prochaines?|upcoming)\\b").containsMatchIn(label)) return false
        return Regex("\\b(nouveautes?|nouveaux?|nouve(lle)?s?|derniers?|dernieres?|recents?|recentes?|recemment|recently|latest|newest|new|updated|ajouts?|sorties?)\\b")
            .containsMatchIn(label)
    }
    fun genres(text: String): Set<HomeShelf> {
        val name = normalize(text)
        return buildSet {
            if (Regex("\\b(documentaires?|documentary|documentaries|documental)\\b").containsMatchIn(name)) add(HomeShelf.Documentaries)
            if (Regex("\\b(animes?|animation|mangas?)\\b").containsMatchIn(name)) add(HomeShelf.Anime)
            if (Regex("\\b(action)\\b").containsMatchIn(name)) add(HomeShelf.Action)
            if (Regex("\\b(comedie|comedies|comedy|comedies)\\b").containsMatchIn(name)) add(HomeShelf.Comedy)
            if (Regex("\\b(thriller|thrillers|suspense)\\b").containsMatchIn(name)) add(HomeShelf.Thriller)
            if (Regex("\\b(science[ -]fiction|sci[ -]fi)\\b").containsMatchIn(name)) add(HomeShelf.ScienceFiction)
        }
    }
    /** First pages only: prioritize explicit additions, then diversify film/series/anime feeds. */
    fun requests(pages: List<MainPageData>, limit: Int = 4): List<Int> {
        val ranked = pages.indices.sortedWith(compareByDescending<Int> { recent(pages[it].name) }
            .thenBy { it })
        val result = mutableListOf<Int>()
        val kinds = listOf(Regex("film|movie"), Regex("serie|series|tv"), Regex("anime|animation"))
        kinds.forEach { kind -> ranked.firstOrNull { kind.containsMatchIn(normalize(pages[it].name)) }?.let {
            if (it !in result) result += it
        } }
        val recentPages = ranked.filter { recent(pages[it].name) }
        val firstRecentKinds = kinds.mapNotNull { kind -> recentPages.firstOrNull { kind.containsMatchIn(normalize(pages[it].name)) } }
        return (firstRecentKinds + recentPages + result + ranked).distinct().take(limit)
    }
}

/** One global identity pass keeps every binding even when a work appears in several shelves. */
class AggregatedHomeProjector {
    private val aggregator = MediaAggregator()
    private val signatures = mutableMapOf<String, ArtworkSignature>()
    private var artworkRequests = emptyList<String>()
    var detailRequests: List<ProviderBinding> = emptyList()
        private set

    /** Reuse search's public-TMDB thumbnail evidence for a few unresolved duplicate titles. */
    suspend fun refine(load: suspend (String) -> ArtworkSignature? = SearchArtworkSignatures::load): Boolean = coroutineScope {
        val semaphore = Semaphore(2)
        val loaded = artworkRequests.take(12).map { id -> async(Dispatchers.IO) {
            semaphore.withPermit { id to withTimeoutOrNull(2_500) { load(id) } }
        } }.awaitAll().filter { it.second != null }
        loaded.forEach { (id, signature) -> signatures[id] = signature!! }
        loaded.isNotEmpty()
    }
    /**
     * @param trending this week's TMDB ranks (0 = most watched); ranks and features available works only
     * @param native providers in the viewer's language; cards available only elsewhere are left out
     */
    fun project(feeds: List<HomeFeed>, recommendations: List<RecommendationBatch> = emptyList(),
        excluded: List<MediaCandidate> = emptyList(), trending: Map<ExternalMediaId, Int> = emptyMap(),
        native: Set<ProviderKey>? = null): List<HomeShelfItems> {
        data class Item(val feed: HomeFeed, val response: SearchResponse, val section: String)
        val lists = feeds.map { feed -> feed.sections.flatMap { section ->
            section.list.take(120).map { Item(feed, it, section.name) }
        }.filter { it.response.type == null || it.response.type in com.lagradost.cloudstream3.ui.catalog.CatalogCuration.videoTypes } }
        // Stable round robin preserves each feed's order and never rewards faster providers.
        val items = buildList {
            for (rank in 0 until (lists.maxOfOrNull { it.size } ?: 0)) lists.forEach { it.getOrNull(rank)?.let(::add) }
        }.sortedByDescending { HomeFeedPolicy.recent(it.section) }
        val suggestions = recommendations.flatMap { batch -> batch.items.map { batch to it } }
            .filter { (_, response) -> response.type == null || response.type in com.lagradost.cloudstream3.ui.catalog.CatalogCuration.videoTypes }
        val originals = items.map { it.feed.provider to it.response } +
            suggestions.map { (batch, response) -> batch.source.binding.provider to response }
        val candidates = items.map {
            val raw = SearchMediaMapper.candidate(it.feed.provider, it.response, it.feed.implementation)
            raw.copy(artworkSignature = raw.artworkIds.firstNotNullOfOrNull { id -> signatures[id] })
        } + suggestions.map { (batch, response) -> SearchMediaMapper.candidate(batch.source.binding.provider, response, batch.source.implementation) }
        if (originals.isEmpty()) return emptyList()
        val responses = originals.associate { (provider, response) -> ProviderBinding(provider, response.url) to response }
        val watched = excluded.map { it.binding }.toSet()
        // History contributes evidence, but its synthetic bindings must never become playable sources.
        val historyByBinding = excluded.associateBy { it.binding }
        val evidence = candidates.map { candidate -> historyByBinding[candidate.binding]?.let { history ->
            candidate.copy(year = candidate.year ?: history.year,
                externalIds = candidate.externalIds + history.externalIds,
                alternateTitles = candidate.alternateTitles + history.alternateTitles)
        } ?: candidate } + excluded
        val seenIds = mutableSetOf<Long>()
        val order = originals.map { (provider, response) -> ProviderBinding(provider, response.url) }
            .distinct().withIndex().associate { it.value to it.index }
        val cards = aggregator.aggregate(evidence).mapNotNull { media ->
            if (media.bindings.any { it in watched }) seenIds += media.id
            val bindings = media.bindings.filter { it in responses }
            if (bindings.isEmpty()) null else AggregatedSearchResponse(media.copy(bindings = bindings,
                bindingEditions = media.bindingEditions.filterKeys { it in responses }), bindings.map { responses.getValue(it) })
        }.filter { card -> native == null || card.media.bindings.any { it.provider in native } }
            .sortedBy { card -> card.media.bindings.minOf { order.getValue(it) } }
        fun rank(card: AggregatedSearchResponse) = card.media.externalIds.mapNotNull { trending[it] }.minOrNull()
        fun availability(card: AggregatedSearchResponse) = card.media.bindings.map { it.provider }.distinct().size
        val ranked = cards.filter { rank(it) != null }.sortedBy { rank(it) }
        val top10 = ranked.take(10).map { it.media.id }.toSet()
        // Popular first, then works offered by more providers; ties keep the feeds' order.
        val byPopularity = compareBy<AggregatedSearchResponse> { rank(it) ?: Int.MAX_VALUE }
            .thenByDescending { minOf(availability(it), 3) }
        val unresolvedTitles = cards.groupBy { MediaAggregator.matchKey(it.name) }
            .filterValues { group -> group.size > 1 && group.mapNotNull { it.media.year }.distinct().size <= 1 }.keys
        detailRequests = candidates.filter { MediaAggregator.matchKey(it.title) in unresolvedTitles &&
                (it.year == null || it.externalIds.isEmpty()) }
            .sortedBy { it.year != null }.map { it.binding }.distinct().take(6)
        artworkRequests = candidates.filter { MediaAggregator.matchKey(it.title) in unresolvedTitles }
            .flatMap { it.artworkIds }.distinct().filterNot { it in signatures }.take(12)
        val latest = mutableSetOf<ProviderBinding>()
        val genres = mutableMapOf<ProviderBinding, MutableSet<HomeShelf>>()
        items.forEach { item ->
            val binding = ProviderBinding(item.feed.provider, item.response.url)
            if (HomeFeedPolicy.recent(item.section)) latest += binding
            genres.getOrPut(binding) { mutableSetOf() }.addAll(HomeFeedPolicy.genres(item.section))
            if (item.feed.animationOnly) genres.getValue(binding) += HomeShelf.Anime
            if (item.response.type == TvType.Documentary) genres.getValue(binding) += HomeShelf.Documentaries
        }
        val feedBindings = items.map { ProviderBinding(it.feed.provider, it.response.url) }.toSet()
        val votes = suggestions.groupBy { (batch, response) -> ProviderBinding(batch.source.binding.provider, response.url) }
        fun votesFor(card: AggregatedSearchResponse) = card.media.bindings.flatMap { votes[it].orEmpty() }
            .filter { (batch, _) -> batch.kind == MediaKind.Unknown || MediaAggregator.family(batch.kind) == MediaAggregator.family(card.media.kind) }
        fun personalized(shelf: HomeShelf) = shelf == HomeShelf.ForYou || shelf == HomeShelf.ForYouSeries
        fun matches(card: AggregatedSearchResponse, shelf: HomeShelf) = when (shelf) {
            HomeShelf.ForYou -> card.media.id !in seenIds && votesFor(card).isNotEmpty() && MediaAggregator.family(card.media.kind) == "movie"
            HomeShelf.ForYouSeries -> card.media.id !in seenIds && votesFor(card).isNotEmpty() && MediaAggregator.family(card.media.kind) == "series"
            HomeShelf.Top10 -> card.media.id in top10
            HomeShelf.Trending -> rank(card) != null && card.media.id !in top10
            HomeShelf.Recent -> card.media.bindings.any { it in latest }
            HomeShelf.Movies -> card.media.kind == MediaKind.Movie
            HomeShelf.Series -> card.media.kind in setOf(MediaKind.Series, MediaKind.AsianDrama)
            HomeShelf.Anime -> card.media.kind in setOf(MediaKind.Anime, MediaKind.AnimeMovie, MediaKind.Cartoon) || card.media.bindings.any { HomeShelf.Anime in genres[it].orEmpty() }
            HomeShelf.Discover -> card.media.kind == MediaKind.Unknown && card.media.bindings.none { it in latest }
            else -> card.media.bindings.any { shelf in genres[it].orEmpty() }
        }
        return HomeShelf.entries.mapNotNull { shelf ->
            val matching = cards.filter { matches(it, shelf) &&
                (personalized(shelf) || it.media.bindings.any { binding -> binding in feedBindings }) }
            val list = if (personalized(shelf)) {
                val ranked = matching.sortedWith(compareByDescending<AggregatedSearchResponse> {
                    votesFor(it).map { vote -> vote.first.seed }.distinct().size
                }.thenBy { card -> votesFor(card).minOf { vote -> vote.first.items.indexOf(vote.second) } })
                val selected = linkedMapOf<Long, AggregatedSearchResponse>()
                // Twenty choices per watched work, still interleaved to preserve diversity.
                val queues = recommendations.map { batch -> ranked.filter { card ->
                    votesFor(card).any { it.first.seed == batch.seed }
                }.toMutableList() }
                repeat(20) {
                    queues.forEach { queue ->
                        while (queue.firstOrNull()?.media?.id in selected.keys) queue.removeAt(0)
                        if (queue.isNotEmpty()) queue.removeAt(0).let { selected[it.media.id] = it }
                    }
                }
                selected.values.take(500)
            } else when (shelf) {
                HomeShelf.Top10, HomeShelf.Trending -> matching.sortedBy { rank(it) }
                // New additions keep their recency order, works on several providers slightly first.
                HomeShelf.Recent -> matching.sortedByDescending { minOf(availability(it), 3) }
                else -> matching.sortedWith(byPopularity)
            }.take(500)
            val minimum = if (shelf in setOf(HomeShelf.Action, HomeShelf.Comedy, HomeShelf.Thriller, HomeShelf.ScienceFiction)) 3 else 1
            list.takeIf { it.size >= minimum }?.let { HomeShelfItems(shelf, it) }
        }
    }
}
