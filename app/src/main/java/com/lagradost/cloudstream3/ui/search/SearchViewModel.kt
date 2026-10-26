package com.lagradost.cloudstream3.ui.search

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKeys
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream4.domain.media.ProviderBinding
import com.lagradost.cloudstream4.aggregation.SearchMediaMapper
import com.lagradost.cloudstream3.ui.search.aggregation.aggregate
import com.lagradost.cloudstream3.ui.search.aggregation.AggregatedSearchResponse
import com.lagradost.cloudstream4.aggregation.SearchMetadataEnricher
import com.lagradost.cloudstream3.ui.search.aggregation.SearchAggregationDiagnostics
import com.lagradost.cloudstream3.ui.search.aggregation.SearchMetadataCatalog
import com.lagradost.cloudstream3.ui.search.aggregation.SearchArtworkSignatures
import com.lagradost.cloudstream4.domain.media.ArtworkSignature
import com.lagradost.cloudstream4.domain.media.VisualArtworkPlanner
import com.lagradost.cloudstream4.domain.media.MediaReference
import com.lagradost.cloudstream4.domain.media.MediaReferenceResolver
import com.lagradost.cloudstream4.domain.media.ExternalMediaId
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream4.domain.media.MetadataEnrichmentPlanner
import com.lagradost.cloudstream4.domain.media.MediaCandidate
import com.lagradost.cloudstream4.domain.media.CanonicalMedia
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream4.domain.media.MediaAggregator
import com.lagradost.cloudstream4.domain.media.PageRequest
import com.lagradost.cloudstream4.domain.media.ProviderKey
import com.lagradost.cloudstream4.domain.media.SearchSession
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.launchSafe
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.DataStoreHelper.currentAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext


data class ExpandableSearchList(
    val list: List<SearchResponse>, val currentPage: Int, val hasNext: Boolean,
    val hasFailures: Boolean = false,
)

const val SEARCH_HISTORY_KEY = "search_history"

class SearchViewModel : ViewModel() {
    private val _searchResponse: MutableLiveData<Resource<ExpandableSearchList>> =
        MutableLiveData()
    val searchResponse: LiveData<Resource<ExpandableSearchList>> get() = _searchResponse

    private val _currentSearch: MutableLiveData<Map<String, ExpandableSearchList>> =
        MutableLiveData()
    val currentSearch: LiveData<Map<String, ExpandableSearchList>> get() = _currentSearch

    private val _currentHistory: MutableLiveData<List<SearchHistoryItem>> = MutableLiveData()
    val currentHistory: LiveData<List<SearchHistoryItem>> get() = _currentHistory

    private val _searchSuggestions: MutableLiveData<List<String>> = MutableLiveData()
    val searchSuggestions: LiveData<List<String>> get() = _searchSuggestions

    private var suggestionJob: Job? = null

    private var repos = apis.withLock { apis.map { APIRepository(it) } }

    private val session = SearchSession<SearchResponse> { it.url }
    private var aggregator = MediaAggregator()
    private var enrichmentPlanner = MetadataEnrichmentPlanner()
    private val metadataEnricher = SearchMetadataEnricher()
    private var enrichmentJob: Job? = null
    private var diagnosticJob: Job? = null
    private var catalogJob: Job? = null
    private var catalogLoading = false
    private var references: List<MediaReference> = emptyList()
    private val imageAttempts = mutableSetOf<ExternalMediaId>()
    private val seasonImageAttempts = mutableSetOf<Pair<ExternalMediaId, Int>>()
    private var visualPlanner = VisualArtworkPlanner()
    private var visualSignatures = emptyMap<String, ArtworkSignature>()
    private var candidates: List<MediaCandidate> = emptyList()
    private var canonicalCards: List<CanonicalMedia> = emptyList()
    private var aggregateResults = true
    private var excludedQualities: Set<SearchQuality> = emptySet()
    private val _loadingMore = MutableLiveData(false)
    val loadingMore: LiveData<Boolean> get() = _loadingMore

    var lastQuery: String? = null
        private set
    private var onGoingSearch: Job? = null
    private var pagingJob: Job? = null
    private var activeRepos: List<APIRepository> = emptyList()
    private var requestedTypes: Set<TvType> = emptySet()

    /** Called on the UI thread; excludes individual bindings before grouping. */
    fun configure(aggregate: Boolean, excluded: Set<SearchQuality>) {
        aggregateResults = aggregate
        if (!aggregate) {
            enrichmentJob?.cancel()
            catalogJob?.cancel()
            catalogJob = null
            catalogLoading = false
        } else if (catalogJob == null) {
            lastQuery?.let(::loadCatalog)
        }
        excludedQualities = excluded.toSet()
        publishResults()
    }

    fun clearSearch() {
        onGoingSearch?.cancel()
        pagingJob?.cancel()
        enrichmentJob?.cancel()
        diagnosticJob?.cancel()
        catalogJob?.cancel()
        catalogJob = null
        catalogLoading = false
        references = emptyList()
        imageAttempts.clear()
        seasonImageAttempts.clear()
        visualPlanner = VisualArtworkPlanner()
        visualSignatures = emptyMap()
        enrichmentJob = null
        enrichmentPlanner = MetadataEnrichmentPlanner()
        onGoingSearch = null
        pagingJob = null
        activeRepos = emptyList()
        requestedTypes = emptySet()
        session.begin(emptyList())
        aggregator = MediaAggregator()
        lastQuery = null
        _loadingMore.value = false
        publishResults()
    }

    fun reloadRepos() {
        repos = apis.withLock { apis.map { APIRepository(it) } }
    }

    fun searchAndCancel(
        query: String,
        providersActive: Set<String>? = null,
        ignoreSettings: Boolean = false,
        isQuickSearch: Boolean = false,
        selectedTypes: Set<TvType> = emptySet(),
    ) {
        clearSearch()
        if (query.trim().length <= 1) return
        requestedTypes = selectedTypes.toSet()
        lastQuery = query.trim()
        val pinned = DataStoreHelper.pinnedProviders.reversedArray()
        val policy = searchPolicy()
        val selected = repos.filter {
            (ignoreSettings || providersActive == null || it.name in providersActive) &&
                (!isQuickSearch || it.hasQuickSearch) && (policy?.allowsProvider(it.api)
                    ?: true)
        }.sortedWith(compareBy<APIRepository> {
            pinned.indexOf(it.name).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE
        }.thenBy { it.name }.thenBy { it.mainUrl })
        session.begin(selected.map { ProviderKey(it.name, it.mainUrl) })
        activeRepos = selected
        if (aggregateResults && selected.isNotEmpty()) loadCatalog(query.trim())
        aggregator = MediaAggregator()
        val requests = selected.mapNotNull { session.request(ProviderKey(it.name, it.mainUrl)) }
        _searchResponse.value = Resource.Loading()
        _loadingMore.value = true
        onGoingSearch = viewModelScope.launchSafe {
            if (!isQuickSearch) {
                val key = query.hashCode().toString()
                setKey("$currentAccount/$SEARCH_HISTORY_KEY", key, SearchHistoryItem(
                    System.currentTimeMillis(), query, emptyList(), key,
                ))
            }
            loadPages(query.trim(), selected, requests, isQuickSearch)
        }
    }

    fun updateHistory() = ioSafe {
        val items = getKeys("$currentAccount/$SEARCH_HISTORY_KEY")?.mapNotNull {
            getKey<SearchHistoryItem>(it)
        }?.sortedByDescending { it.searchedAt } ?: emptyList()
        _currentHistory.postValue(items)
    }

    /**
     * Fetches search suggestions with debouncing.
     * Waits 300ms before making the API call to avoid too many requests.
     * 
     * @param query The search query to get suggestions for
     */
    fun fetchSuggestions(query: String) {
        suggestionJob?.cancel()
        
        if (query.isBlank() || query.length < 2) {
            _searchSuggestions.postValue(emptyList())
            return
        }
        
        suggestionJob = ioSafe {
            delay(300) // Debounce
            val suggestions = SearchSuggestionApi.getSuggestions(query)
            _searchSuggestions.postValue(suggestions)
        }
    }

    /**
     * Clears the current search suggestions.
     */
    fun clearSuggestions() {
        suggestionJob?.cancel()
        _searchSuggestions.postValue(emptyList())
    }

    private fun publishResults() {
        val snapshot = session.snapshot()
        val policy = searchPolicy()
        val providers = activeRepos.associate { ProviderKey(it.name, it.mainUrl) to it.api }
        val visible = snapshot.mapValues { (provider, page) -> page.items.filter { item ->
            item.quality !in excludedQualities && providers[provider]?.let { api -> policy?.allowsItem(api, item) }
                .let { it ?: true }
        } }
        _currentSearch.value = snapshot.map { (provider, page) ->
            provider.name to ExpandableSearchList(visible[provider].orEmpty(), page.page, page.hasNext)
        }.toMap()
        val lists = snapshot.map { (provider, _) ->
            visible[provider].orEmpty().map { provider to it }
        }
        // Round robin preserves each provider's relevance order without using completion order.
        val interleaved = buildList {
            for (index in 0 until (lists.maxOfOrNull { it.size } ?: 0)) {
                lists.forEach { it.getOrNull(index)?.let(::add) }
            }
        }
        val providerClasses = activeRepos.associate { ProviderKey(it.name, it.mainUrl) to it.api.javaClass.name }
        candidates = if (aggregateResults) interleaved.map { (key, response) ->
            val raw = SearchMediaMapper.candidate(key, response, providerClasses[key])
            MediaReferenceResolver.enrich(raw.copy(artworkSignature = raw.artworkIds.firstNotNullOfOrNull { visualSignatures[it] }), references)
        }
            else emptyList()
        val list = if (aggregateResults) SearchMediaMapper.aggregate(aggregator, interleaved, candidates)
            else interleaved.map { it.second }
        canonicalCards = list.filterIsInstance<AggregatedSearchResponse>().map { it.media }
        if (BuildConfig.DEBUG) {
            val candidateSnapshot = candidates
            val cardSnapshot = canonicalCards
            diagnosticJob?.cancel()
            diagnosticJob = viewModelScope.launch(Dispatchers.IO) {
                delay(150)
                runCatching { SearchAggregationDiagnostics.write(candidateSnapshot, cardSnapshot) }
            }
        }
        _searchResponse.value = Resource.Success(ExpandableSearchList(
            list, snapshot.values.maxOfOrNull { it.page } ?: 0, session.hasNext, session.hasFailures,
        ))
        scheduleEnrichment()
    }

    private fun loadCatalog(query: String) {
        if (activeRepos.isEmpty()) return
        catalogLoading = true
        catalogJob = viewModelScope.launchSafe {
            val found = withContext(Dispatchers.IO) { SearchMetadataCatalog.search(query) }
            kotlin.coroutines.coroutineContext.ensureActive()
            references = found
            catalogLoading = false
            publishResults()
        }
    }

    private fun scheduleEnrichment() {
        if (!aggregateResults || candidates.isEmpty() || catalogLoading || lastQuery == null || enrichmentJob?.isActive == true) return
        val planner = enrichmentPlanner
        enrichmentJob = viewModelScope.launchSafe {
            // Coalesce progressive provider responses, then enrich only unresolved duplicate titles.
            delay(400)
            val seasonRequests = references.flatMap { ref ->
                candidates.filter { candidate ->
                    candidate.externalIds.isEmpty() && candidate.artworkIds.isNotEmpty() &&
                        candidate.edition.size == 1 && candidate.edition.single().startsWith("season:") &&
                        (candidate.year == null || ref.year == null || candidate.year == ref.year) &&
                        MediaReferenceResolver.sharesTitle(candidate, ref)
                }.mapNotNull { candidate ->
                    candidate.edition.single().substringAfter(':').toIntOrNull()
                        ?.takeIf { it in 0..100 && ref.id.namespace == com.lagradost.cloudstream4.domain.media.ExternalIdNamespace.TmdbTv }
                        ?.let { ref.id to it }
                }
            }.distinct().filter { it !in seasonImageAttempts }
                .take((6 - seasonImageAttempts.size).coerceAtLeast(0))
            seasonImageAttempts.addAll(seasonRequests)
            if (seasonRequests.isNotEmpty()) {
                val updated = withContext(Dispatchers.IO) {
                    val permits = Semaphore(2)
                    seasonRequests.map { (id, season) -> async { permits.withPermit {
                        SearchMetadataCatalog.withSeasonImages(references.first { it.id == id }, season)
                    } } }.awaitAll()
                }
                kotlin.coroutines.coroutineContext.ensureActive()
                references = references.map { ref -> ref.copy(artworkIds = ref.artworkIds +
                    updated.filter { it.id == ref.id }.flatMap { it.artworkIds }) }
                publishResults()
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
                    imageRequests.map { ref -> async { permits.withPermit { SearchMetadataCatalog.withImages(ref) } } }.awaitAll()
                }.associateBy { it.id }
                kotlin.coroutines.coroutineContext.ensureActive()
                references = references.map { updated[it.id] ?: it }
                publishResults()
            }
            val visualRequests = visualPlanner.next(candidates,canonicalCards)
            if (visualRequests.isNotEmpty()) {
                val signatures = withContext(Dispatchers.IO) {
                    val permits = Semaphore(2)
                    visualRequests.map { id -> async { permits.withPermit { id to SearchArtworkSignatures.load(id) } } }.awaitAll()
                }
                kotlin.coroutines.coroutineContext.ensureActive()
                visualSignatures = visualSignatures + signatures.mapNotNull { (id,signature) -> signature?.let { id to it } }
                publishResults()
            }
            val planned = planner.next(candidates, canonicalCards)
            val repositories = activeRepos.associateBy { ProviderKey(it.name, it.mainUrl) }
            withContext(Dispatchers.IO) {
                metadataEnricher.enrich(planned, { binding ->
                    repositories[binding.provider]?.load(binding.url) is Resource.Success
                }) {
                    withContext(Dispatchers.Main.immediate) {
                        kotlin.coroutines.coroutineContext.ensureActive()
                        if (planner === enrichmentPlanner) publishResults()
                    }
                }
            }
            kotlin.coroutines.coroutineContext.ensureActive()
            enrichmentJob = null
            // Pages/providers may have arrived while the bounded batch was running.
            if (planned.isNotEmpty() || imageRequests.isNotEmpty() || visualRequests.isNotEmpty() || seasonRequests.isNotEmpty()) scheduleEnrichment()
        }
    }

    private suspend fun loadPages(
        query: String,
        sources: List<APIRepository>,
        requests: List<PageRequest>,
        quick: Boolean = false,
    ) {
        withContext(Dispatchers.IO) {
            requests.amap { request ->
                val repo = sources.first { it.name == request.provider.name && it.mainUrl == request.provider.mainUrl }
                val result = if (quick) repo.quickSearch(query) else repo.search(query, request.page)
                withContext(Dispatchers.Main.immediate) {
                    // Legacy wrappers may turn cancellation into a Resource.Failure.
                    kotlin.coroutines.coroutineContext.ensureActive()
                    val changed = when (result) {
                        is Resource.Success -> session.accept(request, result.value.items, result.value.hasNext)
                        else -> session.fail(request)
                    }
                    if (changed) publishResults()
                }
            }
        }
        kotlin.coroutines.coroutineContext.ensureActive()
        if (requests.isEmpty() || session.isCurrent(requests.first())) {
            _loadingMore.value = false
            publishResults()
        }
    }

    fun loadMore() {
        val query = lastQuery ?: return
        if (_loadingMore.value == true) return
        val requests = session.snapshot().keys.mapNotNull { session.request(it) }
        if (requests.isEmpty()) return
        _loadingMore.value = true
        val sources = activeRepos
        pagingJob = viewModelScope.launchSafe { loadPages(query, sources, requests) }
    }

    private fun searchPolicy() = com.lagradost.cloudstream3.CloudStreamApp.context?.let {
        SearchScope(it, requestedTypes, aggregateResults)
    }

    /** Retained for the optional upstream provider-row view and its bottom sheet. */
    suspend fun expandAndReturn(name: String): HomeViewModel.ExpandableHomepageList? =
        withContext(Dispatchers.Main.immediate) {
            val query = lastQuery ?: return@withContext null
            val provider = session.snapshot().keys.firstOrNull { it.name == name } ?: return@withContext null
            val request = session.request(provider) ?: return@withContext null
            loadPages(query, activeRepos, listOf(request))
            if (!session.isCurrent(request)) return@withContext null
            session.snapshot()[provider]?.let { page ->
                val api = activeRepos.firstOrNull { it.name == provider.name && it.mainUrl == provider.mainUrl }?.api
                val visible = page.items.filter { item -> item.quality !in excludedQualities &&
                    (api?.let { searchPolicy()?.allowsItem(it, item) } ?: true) }
                HomeViewModel.ExpandableHomepageList(HomePageList(name, visible), page.page, page.hasNext)
            }
        }
}
