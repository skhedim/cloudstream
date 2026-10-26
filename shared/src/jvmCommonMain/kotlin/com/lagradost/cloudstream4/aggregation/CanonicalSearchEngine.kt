package com.lagradost.cloudstream4.aggregation

import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream4.domain.media.ArtworkSignature
import com.lagradost.cloudstream4.domain.media.ExternalIdNamespace
import com.lagradost.cloudstream4.domain.media.ExternalMediaId
import com.lagradost.cloudstream4.domain.media.MediaAggregator
import com.lagradost.cloudstream4.domain.media.MediaCandidate
import com.lagradost.cloudstream4.domain.media.MediaReference
import com.lagradost.cloudstream4.domain.media.MediaReferenceResolver
import com.lagradost.cloudstream4.domain.media.MetadataEnrichmentPlanner
import com.lagradost.cloudstream4.domain.media.PageRequest
import com.lagradost.cloudstream4.domain.media.ProviderKey
import com.lagradost.cloudstream4.domain.media.ProviderPage
import com.lagradost.cloudstream4.domain.media.SearchSession
import com.lagradost.cloudstream4.domain.media.VisualArtworkPlanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Immutable snapshot of a canonical search, published after every change. */
data class CanonicalSearchState(
    val query: String? = null,
    /** False between starting a query and its first publication. */
    val published: Boolean = false,
    /** Provider pages in their stable provider order, unfiltered. */
    val pages: Map<ProviderKey, ProviderPage<SearchResponse>> = emptyMap(),
    /** The same pages after the item filter. */
    val visible: Map<ProviderKey, List<SearchResponse>> = emptyMap(),
    /** Visible results interleaved round robin, preserving each provider's relevance order. */
    val interleaved: List<Pair<ProviderKey, SearchResponse>> = emptyList(),
    val candidates: List<MediaCandidate> = emptyList(),
    /** Canonical cards when aggregation is enabled; empty otherwise. */
    val cards: List<CanonicalSearchCard> = emptyList(),
    val aggregate: Boolean = true,
    val page: Int = 0,
    val hasNext: Boolean = false,
    val hasFailures: Boolean = false,
    /** Provider requests are in flight. Metadata enrichment may continue afterwards. */
    val loading: Boolean = false,
)

/**
 * Multi-provider search and canonical grouping, shared by Android and the desktop app.
 *
 * All mutable state is confined to one sequential dispatcher; provider and metadata calls run
 * on IO and come back with their request tokens, so late results of an old query, page or
 * attempt can never publish into the current one. Results regroup progressively.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CanonicalSearchEngine(
    private val scope: CoroutineScope,
    private val gateway: () -> ProviderGateway = { AggregationPlatform.gateway },
    private val catalog: suspend (String) -> List<MediaReference> = { SearchMetadataCatalog.search(it) },
    private val withImages: suspend (MediaReference) -> MediaReference = SearchMetadataCatalog::withImages,
    private val withExternalIds: suspend (MediaReference) -> MediaReference = SearchMetadataCatalog::withExternalIds,
    private val withSeasonImages: suspend (MediaReference, Int) -> MediaReference = SearchMetadataCatalog::withSeasonImages,
    private val artwork: suspend (String) -> ArtworkSignature? = SearchArtworkSignatures::load,
    /** Progressive provider responses are coalesced before metadata enrichment. */
    private val coalesceMs: Long = 400,
) {
    private companion object { const val MAX_SEASON_IMAGE_REQUESTS = 8 }

    private val confined = Dispatchers.Default.limitedParallelism(1)
    private val _state = MutableStateFlow(CanonicalSearchState())
    val state: StateFlow<CanonicalSearchState> get() = _state

    private val session = SearchSession<SearchResponse> { it.url }
    private var aggregator = MediaAggregator()
    private var enrichmentPlanner = MetadataEnrichmentPlanner()
    private val metadataEnricher = SearchMetadataEnricher()
    private var enrichmentJob: Job? = null
    private var catalogJob: Job? = null
    private var catalogLoading = false
    private var references: List<MediaReference> = emptyList()
    private val imageAttempts = mutableSetOf<ExternalMediaId>()
    private val idAttempts = mutableSetOf<ExternalMediaId>()
    private val seasonImageAttempts = mutableSetOf<Pair<ExternalMediaId, Int>>()
    private var visualPlanner = VisualArtworkPlanner()
    private var visualSignatures = emptyMap<String, ArtworkSignature>()
    private var candidates: List<MediaCandidate> = emptyList()
    private var cards: List<CanonicalSearchCard> = emptyList()
    private var aggregate = true
    private var itemFilter: (ProviderKey, SearchResponse) -> Boolean = { _, _ -> true }
    private var query: String? = null
    private var quick = false
    private var loading = false
    private var searchJob: Job? = null
    private var pagingJob: Job? = null
    private var providers: List<RegisteredProvider> = emptyList()

    private fun confine(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(confined, block = block)

    /** Aggregation on/off and the per-item filter (quality exclusions, catalogue policy). */
    fun configure(aggregate: Boolean, filter: (ProviderKey, SearchResponse) -> Boolean) = confine {
        this@CanonicalSearchEngine.aggregate = aggregate
        itemFilter = filter
        if (!aggregate) {
            enrichmentJob?.cancel()
            catalogJob?.cancel()
            catalogJob = null
            catalogLoading = false
        } else if (catalogJob == null) {
            query?.let(::loadCatalog)
        }
        publish()
    }

    fun clear() = confine { reset(); publish() }

    private fun reset() {
        searchJob?.cancel()
        pagingJob?.cancel()
        enrichmentJob?.cancel()
        catalogJob?.cancel()
        catalogJob = null
        catalogLoading = false
        references = emptyList()
        imageAttempts.clear()
        idAttempts.clear()
        seasonImageAttempts.clear()
        visualPlanner = VisualArtworkPlanner()
        visualSignatures = emptyMap()
        enrichmentJob = null
        enrichmentPlanner = MetadataEnrichmentPlanner()
        searchJob = null
        pagingJob = null
        providers = emptyList()
        session.begin(emptyList())
        aggregator = MediaAggregator()
        query = null
        loading = false
        candidates = emptyList()
        cards = emptyList()
    }

    /**
     * Starts a new query over [providers], in the given (stable) order. A blank or one-letter
     * query only clears the previous one.
     */
    fun search(query: String, providers: List<RegisteredProvider>, quick: Boolean = false) = confine {
        reset()
        val trimmed = query.trim()
        if (trimmed.length <= 1) { publish(); return@confine }
        this@CanonicalSearchEngine.query = trimmed
        this@CanonicalSearchEngine.quick = quick
        // Providers whose recent searches all came back empty while others found results are rested.
        val usable = providers.filter { ProviderHealth.searchUsable(it.id) }.ifEmpty { providers }
        this@CanonicalSearchEngine.providers = usable
        session.begin(usable.map { it.key })
        if (aggregate && usable.isNotEmpty()) loadCatalog(trimmed)
        val requests = usable.mapNotNull { session.request(it.key) }
        loading = true
        _state.value = CanonicalSearchState(query = trimmed, published = false, aggregate = aggregate, loading = true)
        searchJob = scope.launch(confined) { loadPages(trimmed, usable, requests, quick) }
    }

    /** Requests the next page of every unfinished provider; failed pages are retried. */
    fun loadMore() = confine {
        val current = query ?: return@confine
        if (loading) return@confine
        val requests = session.snapshot().keys.mapNotNull { session.request(it) }
        if (requests.isEmpty()) return@confine
        loading = true
        publish()
        val sources = providers
        pagingJob = scope.launch(confined) { loadPages(current, sources, requests, false) }
    }

    /** Loads the next page of one provider and returns its updated page (provider-row view). */
    suspend fun expand(provider: ProviderKey): ProviderPage<SearchResponse>? = withContext(confined) {
        val current = query ?: return@withContext null
        val request = session.request(provider) ?: return@withContext null
        loadPages(current, providers, listOf(request), false)
        if (!session.isCurrent(request)) null else session.snapshot()[provider]
    }

    private suspend fun loadPages(query: String, sources: List<RegisteredProvider>, requests: List<PageRequest>, quick: Boolean) {
        val answers = java.util.concurrent.ConcurrentHashMap<RegisteredProvider, Int>()
        coroutineScope {
            requests.map { request ->
                async(Dispatchers.IO) {
                    val provider = sources.first { it.key == request.provider }
                    val result: SearchResponseList? = if (quick) gateway().quickSearch(provider.api, query)
                        else gateway().search(provider.api, query, request.page)
                    if (request.page == 1) answers[provider] = result?.items?.size ?: -1
                    withContext(confined) {
                        currentCoroutineContext().ensureActive()
                        val changed = if (result != null) session.accept(request, result.items, result.hasNext)
                            else session.fail(request)
                        if (changed) publish()
                    }
                }
            }.awaitAll()
        }
        currentCoroutineContext().ensureActive()
        // Search health: an empty first page only counts when at least two other providers found something.
        val answered = answers.values.count { it > 0 }
        answers.forEach { (provider, count) ->
            when {
                count > 0 -> ProviderHealth.recordSearch(provider.id, true)
                count < 0 || answered >= 2 -> ProviderHealth.recordSearch(provider.id, false)
            }
        }
        if (answers.isNotEmpty()) ProviderHealth.persist()
        if (requests.isEmpty() || session.isCurrent(requests.first())) {
            loading = false
            publish()
        }
    }

    private fun publish() {
        val snapshot = session.snapshot()
        val filter = itemFilter
        val visible = snapshot.mapValues { (provider, page) -> page.items.filter { filter(provider, it) } }
        val lists = snapshot.keys.map { provider -> visible[provider].orEmpty().map { provider to it } }
        val interleaved = buildList {
            for (index in 0 until (lists.maxOfOrNull { it.size } ?: 0)) {
                lists.forEach { it.getOrNull(index)?.let(::add) }
            }
        }
        val classes = providers.associate { it.key to it.api.javaClass.name }
        candidates = if (aggregate) interleaved.map { (key, response) ->
            val raw = SearchMediaMapper.candidate(key, response, classes[key])
            MediaReferenceResolver.enrich(raw.copy(artworkSignature = raw.artworkIds.firstNotNullOfOrNull { visualSignatures[it] }), references)
        } else emptyList()
        // Works offered in the viewer's language come first; foreign-only works keep their order after them.
        val viewer = PlaybackSources.viewerLanguage()
        val native = providers.filter { provider ->
            provider.language.substringBefore('-').lowercase().let { it == viewer || it == "multi" || it == "mul" || it == "all" }
        }.map { it.key }.toSet()
        cards = if (aggregate) SearchMediaMapper.group(aggregator, interleaved, candidates)
            .sortedByDescending { card -> native.isEmpty() || card.media.bindings.any { it.provider in native } } else emptyList()
        _state.value = CanonicalSearchState(query, true, snapshot, visible, interleaved, candidates, cards, aggregate,
            snapshot.values.maxOfOrNull { it.page } ?: 0, session.hasNext, session.hasFailures, loading)
        scheduleEnrichment()
    }

    private fun loadCatalog(query: String) {
        if (providers.isEmpty()) return
        catalogLoading = true
        catalogJob = scope.launch(confined) {
            val found = withContext(Dispatchers.IO) { catalog(query) }
            currentCoroutineContext().ensureActive()
            references = found
            catalogLoading = false
            publish()
        }
    }

    private fun scheduleEnrichment() {
        if (!aggregate || candidates.isEmpty() || catalogLoading || query == null || enrichmentJob?.isActive == true) return
        val planner = enrichmentPlanner
        enrichmentJob = scope.launch(confined) {
            // Coalesce progressive provider responses, then enrich only unresolved duplicate titles.
            delay(coalesceMs)
            // References that providers already point to by ID (or by title) learn their linked IDs,
            // so a provider that only knows the IMDb ID joins the same work.
            val idRequests = references.filter { ref ->
                ref.id !in idAttempts && ref.equivalentIds.isEmpty() && candidates.any { candidate ->
                    ref.id in candidate.externalIds || MediaReferenceResolver.sharesTitle(candidate, ref)
                }
            }.sortedByDescending { ref -> candidates.count { ref.id in it.externalIds } }.take((4 - idAttempts.size).coerceAtLeast(0))
            idAttempts.addAll(idRequests.map { it.id })
            if (idRequests.isNotEmpty()) {
                val updated = withContext(Dispatchers.IO) {
                    val permits = Semaphore(2)
                    idRequests.map { ref -> async { permits.withPermit { withExternalIds(ref) } } }.awaitAll()
                }.associateBy { it.id }
                currentCoroutineContext().ensureActive()
                references = references.map { ref -> updated[ref.id]?.let { ref.copy(equivalentIds = it.equivalentIds) } ?: ref }
                publish()
            }
            val seasonRequests = (references.flatMap { ref ->
                candidates.filter { candidate ->
                    candidate.externalIds.isEmpty() && candidate.artworkIds.isNotEmpty() &&
                        candidate.edition.size == 1 && candidate.edition.single().startsWith("season:") &&
                        (candidate.year == null || ref.year == null || candidate.year == ref.year) &&
                        MediaReferenceResolver.sharesTitle(candidate, ref)
                }.mapNotNull { candidate ->
                    candidate.edition.single().substringAfter(':').toIntOrNull()
                        ?.takeIf { it in 0..100 && ref.id.namespace == ExternalIdNamespace.TmdbTv }
                        ?.let { ref.id to it }
                }
            } + references.filter { it.id.namespace == ExternalIdNamespace.TmdbTv && it.id in imageAttempts }.flatMap { ref ->
                // Whole-show entries sometimes carry an alternate poster of an early season. Only
                // after the show's own posters did not match, and only for its first seasons.
                val unresolved = candidates.any { candidate ->
                    candidate.externalIds.isEmpty() && candidate.edition.isEmpty() && candidate.artworkIds.isNotEmpty() &&
                        candidate.artworkIds.intersect(ref.artworkIds).isEmpty() &&
                        (candidate.year == null || ref.year == null || candidate.year == ref.year) &&
                        MediaReferenceResolver.sharesTitle(candidate, ref)
                }
                if (unresolved) listOf(ref.id to 1, ref.id to 2) else emptyList()
            }).distinct().filter { it !in seasonImageAttempts }
                .take((MAX_SEASON_IMAGE_REQUESTS - seasonImageAttempts.size).coerceAtLeast(0))
            seasonImageAttempts.addAll(seasonRequests)
            if (seasonRequests.isNotEmpty()) {
                val current = references
                val updated = withContext(Dispatchers.IO) {
                    val permits = Semaphore(2)
                    seasonRequests.map { (id, season) -> async { permits.withPermit {
                        withSeasonImages(current.first { it.id == id }, season)
                    } } }.awaitAll()
                }
                currentCoroutineContext().ensureActive()
                references = references.map { ref -> ref.copy(artworkIds = ref.artworkIds +
                    updated.filter { it.id == ref.id }.flatMap { it.artworkIds }) }
                publish()
            }
            val imageRequests = references.filter { ref ->
                ref.id !in imageAttempts && candidates.any { candidate ->
                    candidate.externalIds.isEmpty() && candidate.artworkIds.isNotEmpty() &&
                        (candidate.year == null || ref.year == null || candidate.year == ref.year) &&
                        MediaReferenceResolver.sharesTitle(candidate,ref)
                }
            }.take((6-imageAttempts.size).coerceAtLeast(0))
            imageAttempts.addAll(imageRequests.map { it.id })
            if (imageRequests.isNotEmpty()) {
                val updated = withContext(Dispatchers.IO) {
                    val permits = Semaphore(2)
                    imageRequests.map { ref -> async { permits.withPermit { withImages(ref) } } }.awaitAll()
                }.associateBy { it.id }
                currentCoroutineContext().ensureActive()
                references = references.map { updated[it.id] ?: it }
                publish()
            }
            val visualRequests = visualPlanner.next(candidates, cards.map { it.media })
            if (visualRequests.isNotEmpty()) {
                val signatures = withContext(Dispatchers.IO) {
                    val permits = Semaphore(2)
                    visualRequests.map { id -> async { permits.withPermit { id to artwork(id) } } }.awaitAll()
                }
                currentCoroutineContext().ensureActive()
                visualSignatures = visualSignatures + signatures.mapNotNull { (id,signature) -> signature?.let { id to it } }
                publish()
            }
            val planned = planner.next(candidates, cards.map { it.media })
            val byKey = providers.associateBy { it.key }
            withContext(Dispatchers.IO) {
                metadataEnricher.enrich(planned, { binding ->
                    byKey[binding.provider]?.let { gateway().load(it.api, binding.url) } != null
                }) {
                    withContext(confined) {
                        currentCoroutineContext().ensureActive()
                        if (planner === enrichmentPlanner) publish()
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            enrichmentJob = null
            // Pages/providers may have arrived while the bounded batch was running.
            if (planned.isNotEmpty() || imageRequests.isNotEmpty() || visualRequests.isNotEmpty() ||
                seasonRequests.isNotEmpty() || idRequests.isNotEmpty()) scheduleEnrichment()
        }
    }
}
