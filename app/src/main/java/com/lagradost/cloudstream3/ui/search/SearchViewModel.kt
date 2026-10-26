package com.lagradost.cloudstream3.ui.search

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKeys
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.launchSafe
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.ui.search.aggregation.AggregatedSearchResponse
import com.lagradost.cloudstream3.ui.search.aggregation.SearchAggregationDiagnostics
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.DataStoreHelper.currentAccount
import com.lagradost.cloudstream4.aggregation.CanonicalSearchEngine
import com.lagradost.cloudstream4.aggregation.CanonicalSearchState
import com.lagradost.cloudstream4.aggregation.ProviderIds
import com.lagradost.cloudstream4.aggregation.RegisteredProvider
import com.lagradost.cloudstream4.domain.media.ProviderKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    /** Shared multi-provider search, canonical grouping and metadata enrichment. */
    private val engine = CanonicalSearchEngine(viewModelScope)
    private var diagnosticJob: Job? = null
    private var aggregateResults = true
    private var excludedQualities: Set<SearchQuality> = emptySet()
    private val _loadingMore = MutableLiveData(false)
    val loadingMore: LiveData<Boolean> get() = _loadingMore

    var lastQuery: String? = null
        private set
    private var requestedTypes: Set<TvType> = emptySet()

    init {
        viewModelScope.launch {
            engine.state.collect { state -> withContext(Dispatchers.Main.immediate) { show(state) } }
        }
    }

    /** Called on the UI thread; excludes individual bindings before grouping. */
    fun configure(aggregate: Boolean, excluded: Set<SearchQuality>) {
        aggregateResults = aggregate
        excludedQualities = excluded.toSet()
        engine.configure(aggregate, itemFilter())
    }

    /** Quality exclusions and, for aggregated search, the catalogue policy. */
    private fun itemFilter(): (ProviderKey, SearchResponse) -> Boolean {
        val excluded = excludedQualities
        val policy = searchPolicy()
        val byKey = repos.associate { ProviderKey(it.name, it.mainUrl) to it.api }
        return { provider, item ->
            item.quality !in excluded && (policy == null || byKey[provider]?.let { policy.allowsItem(it, item) } ?: true)
        }
    }

    fun clearSearch() {
        engine.clear()
        diagnosticJob?.cancel()
        requestedTypes = emptySet()
        lastQuery = null
        _loadingMore.value = false
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
        if (query.trim().length <= 1) {
            clearSearch()
            return
        }
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
        _searchResponse.value = Resource.Loading()
        _loadingMore.value = true
        engine.configure(aggregateResults, itemFilter())
        engine.search(query.trim(), selected.map {
            RegisteredProvider(ProviderIds.of(it.api), ProviderKey(it.name, it.mainUrl), it.api)
        }, isQuickSearch)
        if (!isQuickSearch) viewModelScope.launchSafe {
            val key = query.hashCode().toString()
            setKey("$currentAccount/$SEARCH_HISTORY_KEY", key, SearchHistoryItem(
                System.currentTimeMillis(), query, emptyList(), key,
            ))
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

    private fun show(state: CanonicalSearchState) {
        _loadingMore.value = state.loading
        if (state.query == null) {
            _currentSearch.value = emptyMap()
            _searchResponse.value = Resource.Success(ExpandableSearchList(emptyList(), 0, false, false))
            return
        }
        // A snapshot of the previous query can still be in flight when a new one starts.
        if (!state.published || state.query != lastQuery) return
        _currentSearch.value = state.pages.map { (provider, page) ->
            provider.name to ExpandableSearchList(state.visible[provider].orEmpty(), page.page, page.hasNext)
        }.toMap()
        val list = if (state.aggregate) state.cards.map { AggregatedSearchResponse(it.media, it.responses) }
            else state.interleaved.map { it.second }
        if (BuildConfig.DEBUG && state.aggregate) {
            diagnosticJob?.cancel()
            diagnosticJob = viewModelScope.launch(Dispatchers.IO) {
                delay(150)
                runCatching { SearchAggregationDiagnostics.write(state.candidates, state.cards.map { it.media }) }
            }
        }
        _searchResponse.value = Resource.Success(ExpandableSearchList(list, state.page, state.hasNext, state.hasFailures))
    }

    fun loadMore() {
        if (lastQuery == null || _loadingMore.value == true) return
        engine.loadMore()
    }

    private fun searchPolicy() = com.lagradost.cloudstream3.CloudStreamApp.context?.let {
        SearchScope(it, requestedTypes, aggregateResults)
    }

    /** Retained for the optional upstream provider-row view and its bottom sheet. */
    suspend fun expandAndReturn(name: String): HomeViewModel.ExpandableHomepageList? {
        val provider = engine.state.value.pages.keys.firstOrNull { it.name == name } ?: return null
        val page = engine.expand(provider) ?: return null
        val filter = itemFilter()
        val visible = page.items.filter { filter(provider, it) }
        return HomeViewModel.ExpandableHomepageList(HomePageList(name, visible), page.page, page.hasNext)
    }
}
