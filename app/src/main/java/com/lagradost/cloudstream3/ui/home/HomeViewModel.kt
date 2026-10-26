package com.lagradost.cloudstream3.ui.home

import android.os.Build
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.catalog.CatalogPreferences
import com.lagradost.cloudstream3.ui.catalog.CatalogSelection
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.CloudStreamApp.Companion.context
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.debugAssert
import com.lagradost.cloudstream3.mvvm.debugWarning
import com.lagradost.cloudstream3.mvvm.launchSafe
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.APIRepository.Companion.noneApi
import com.lagradost.cloudstream3.ui.APIRepository.Companion.randomApi
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.ui.quicksearch.QuickSearchFragment
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_FOCUSED
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.ui.search.SearchHelper
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.addProgramsToContinueWatching
import com.lagradost.cloudstream3.utils.AppContextUtils.filterHomePageListByFilmQuality
import com.lagradost.cloudstream3.utils.AppContextUtils.filterProviderByPreferredMedia
import com.lagradost.cloudstream3.utils.AppContextUtils.filterSearchResultByFilmQuality
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE_BACKUP
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.DataStoreHelper.deleteAllResumeStateIds
import com.lagradost.cloudstream3.utils.DataStoreHelper.getAllResumeStateIds
import com.lagradost.cloudstream3.utils.DataStoreHelper.getAllWatchStateIds
import com.lagradost.cloudstream3.utils.DataStoreHelper.getBookmarkedData
import com.lagradost.cloudstream3.utils.DataStoreHelper.getCurrentAccount
import com.lagradost.cloudstream3.utils.DataStoreHelper.getLastWatched
import com.lagradost.cloudstream3.utils.DataStoreHelper.getResultWatchState
import com.lagradost.cloudstream3.utils.DataStoreHelper.getViewPos
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.ui.home.aggregation.*
import com.lagradost.cloudstream4.AppSettings
import com.lagradost.cloudstream3.R
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.lagradost.cloudstream4.aggregation.SearchMediaMapper
import com.lagradost.cloudstream3.ui.result.aggregation.CanonicalDetailStore
import com.lagradost.cloudstream4.domain.media.ProviderKey
import com.lagradost.cloudstream4.domain.media.ProviderBinding
import java.util.EnumSet
import java.util.concurrent.CopyOnWriteArrayList

class HomeViewModel : ViewModel() {
    companion object {
        suspend fun getResumeWatching(): List<DataStoreHelper.ResumeWatchingResult>? {
            val resumeWatching = withContext(Dispatchers.IO) {
                getAllResumeStateIds()?.mapNotNull { id ->
                    getLastWatched(id)
                }?.sortedBy { -it.updateTime }
            }
            val resumeWatchingResult = withContext(Dispatchers.IO) {
                resumeWatching?.mapNotNull { resume ->
                    val headerCache = getKey<DownloadObjects.DownloadHeaderCached>(
                        DOWNLOAD_HEADER_CACHE,
                        resume.parentId.toString()
                    )

                    val data = if (headerCache == null) {
                        // We store resume watching data in download header cache
                        // Because downloads automatically pruned outdated download headers we
                        // removed resume watching data. We should restore the data for affected users.
                        val oldData = getKey<DownloadObjects.DownloadHeaderCached>(
                            DOWNLOAD_HEADER_CACHE_BACKUP,
                            resume.parentId.toString()
                        ) ?: return@mapNotNull null

                        // Restore data
                        setKey(DOWNLOAD_HEADER_CACHE, resume.parentId.toString(), oldData)
                        oldData
                    } else {
                        headerCache
                    }

                    val watchPos = getViewPos(resume.episodeId)

                    DataStoreHelper.ResumeWatchingResult(
                        data.name,
                        data.url,
                        data.apiName,
                        data.type,
                        data.poster,
                        watchPos,
                        resume.episodeId,
                        resume.parentId,
                        resume.episode,
                        resume.season,
                        resume.isFromDownload
                    )
                }
            }
            return resumeWatchingResult
        }
    }

    fun deleteResumeWatching() {
        deleteAllResumeStateIds()
        loadResumeWatching()
        if (aggregated) loadAndCancel(DataStoreHelper.currentHomePage, false)
    }

    fun deleteBookmarks(list: List<SearchResponse>) {
        list.forEach { DataStoreHelper.deleteBookmarkedData(it.id) }
        loadStoredData()
        if (aggregated) loadAndCancel(DataStoreHelper.currentHomePage, false)
    }

    val aggregated: Boolean get() = context?.let { AppSettings(it).ui.aggregateHome.get() } ?: true
    private fun visibleHistory(item: SearchResponse): Boolean {
        if (!aggregated) return true
        val selection = context?.let(CatalogPreferences::read) ?: return true
        return selection.normalItem(item)
    }
    private val _discoveryStatus = MutableLiveData<String>()
    val discoveryStatus: LiveData<String> = _discoveryStatus
    private var discoveryProviders: List<Triple<String, String, String>>? = null
    private var discoverySelection: CatalogSelection? = null
    private var discoveryGeneration = 0L
    private var expandAggregatedShelf: (suspend (String) -> ExpandableHomepageList?)? = null
    private var discoveryRequest = 0L
    private var discoveryAccount: String? = null
    private data class HistorySignal(val api: String, val url: String, val type: String?,
        val played: Boolean, val eligible: Boolean, val completed: Boolean, val dropped: Boolean, val updated: Long)
    private data class RecommendationSnapshot(val plan: RecommendationPlan, val history: Set<HistorySignal>)
    private var discoveryHistory: Set<HistorySignal>? = null
    private val recommendationsLoader = PersonalizedRecommendationsLoader(related = PersonalizedRecommendationCatalog::related)

    private suspend fun recommendationPlan(repos: List<APIRepository>): RecommendationSnapshot = withContext(Dispatchers.IO) {
        val signals = mutableSetOf<HistorySignal>()
        fun entry(response: SearchResponse, updated: Long, position: Long, completed: Boolean, dropped: Boolean,
            year: Int? = null, signalTime: Long = updated, fromContinueWatching: Boolean = true): RecommendationHistory {
            signals += HistorySignal(response.apiName, response.url, response.type?.name,
                position > 0, position >= RecommendationPlan.MIN_PLAYBACK_MS, completed, dropped, signalTime)
            val canonical = CanonicalDetailStore.find(response.apiName, response.url)
            val primary = repos.firstOrNull { it.name == response.apiName }
            val binding = ProviderBinding(ProviderKey(response.apiName, primary?.mainUrl.orEmpty()), response.url)
            val base = SearchMediaMapper.candidate(binding.provider, response, primary?.api?.javaClass?.name)
            val candidates = canonical?.bindings?.map { detail -> base.copy(binding = detail.mediaBinding(),
                title = canonical.title, year = canonical.year ?: year ?: base.year, kind = canonical.kind,
                alternateTitles = setOf(response.name),
                externalIds = canonical.externalIds + base.externalIds.filterNot { cached ->
                    canonical.externalIds.any { it.namespace == cached.namespace }
                }, edition = detail.editions) }
                ?: listOf(base.copy(year = year ?: base.year))
            val bindings = (listOf(binding) + candidates.map { it.binding }).distinct()
            val sources = bindings.mapNotNull { source -> repos.firstOrNull {
                it.name == source.provider.name && it.mainUrl == source.provider.mainUrl
            }?.let { RecommendationSource(source, it.api.javaClass.name) } }
            return RecommendationHistory(candidates, sources, updated, position, completed, dropped, fromContinueWatching)
        }
        val resumes = getResumeWatching().orEmpty().filter(::visibleHistory).map { resume ->
            val status = resume.parentId?.let(::getResultWatchState)
            entry(resume, getLastWatched(resume.parentId)?.updateTime ?: 0, resume.watchPos?.position ?: 0,
                status == WatchType.COMPLETED, status == WatchType.DROPPED)
        }
        val library = getAllWatchStateIds().orEmpty().mapNotNull { id ->
            val status = getResultWatchState(id)
            if (status !in setOf(WatchType.COMPLETED, WatchType.DROPPED)) return@mapNotNull null
            getBookmarkedData(id)?.takeIf(::visibleHistory)?.let { entry(it, it.latestUpdatedTime, 0,
                status == WatchType.COMPLETED, status == WatchType.DROPPED, it.year, it.bookmarkedTime, false) }
        }
        RecommendationSnapshot(RecommendationPlan.from(resumes + library), signals.toSet())
    }

    private suspend fun loadDiscovery(forceReload: Boolean) {
        val ctx = context ?: return
        val request = ++discoveryRequest
        val account = DataStoreHelper.currentAccount
        if (discoveryAccount != account) {
            onGoingLoad?.cancel()
            discoveryGeneration++
            _page.value = Resource.Loading()
        }
        val selection = CatalogPreferences.read(ctx)
        val allRepos = apis.withLock { apis.toList() }.filter {
            selection.normalProvider(it)
        }
            .distinctBy { it.name to it.mainUrl }.sortedWith(compareBy<MainAPI> { it.name }.thenBy { it.mainUrl })
            .map(::APIRepository)
        val repos = allRepos.filter { it.hasMainPage }
        val identities = allRepos.map { Triple(it.name, it.mainUrl, it.api.javaClass.name) }
        val snapshot = recommendationPlan(allRepos)
        val plan = snapshot.plan
        if (request != discoveryRequest || DataStoreHelper.currentAccount != account || !aggregated) return
        // Compare viewing signals, not metadata caches or unordered preference key iteration.
        // Opening a detail can enrich aliases/years without changing anyone's viewing history.
        val sameHistory = snapshot.history == discoveryHistory
        if (!forceReload && account == discoveryAccount && sameHistory && identities == discoveryProviders &&
            (onGoingLoad?.isActive == true || page.value is Resource.Success)) return
        val enteringDiscovery = discoveryProviders == null
        discoveryAccount = account
        discoveryHistory = snapshot.history
        discoveryProviders = identities
        val generation = ++discoveryGeneration
        expandAggregatedShelf = null
        popup(null)
        onGoingLoad?.cancel()
        addJob?.cancel()
        repo = null
        isCurrentlyLoadingName = "__aggregated_home__"
        _apiName.value = "__aggregated_home__"
        _randomItems.value = emptyList()
        _preview.value = Resource.Failure(false, "Content shelves")
        _discoveryStatus.value = ctx.getString(R.string.home_loading_news)
        if (enteringDiscovery || page.value !is Resource.Success) _page.value = Resource.Loading()
        onGoingLoad = viewModelScope.launch {
            val projector = AggregatedHomeProjector()
            var latestResults = emptyList<AggregatedHomeLoader.Result>()
            var finishedCount = 0
            var suggestions = emptyList<RecommendationBatch>()
            var personalization = plan
            var pager: HomeShelfPager? = null
            val publication = Mutex()
            val publish: suspend (List<AggregatedHomeLoader.Result>, Int) -> Unit = { results, finished ->
                publication.withLock {
                    latestResults = results
                    finishedCount = finished
                    publishDiscovery(projector, results, finished, repos.size, generation, account, suggestions, personalization, pager)
                }
            }
            coroutineScope {
                val personalizedJob = launch {
                    val attempts = mutableListOf<Map<String, Any?>>()
                    val loaded = recommendationsLoader.load(account, plan.seeds, forceReload) { source ->
                        val target = allRepos.firstOrNull { it.name == source.binding.provider.name && it.mainUrl == source.binding.provider.mainUrl }
                        val response = (target?.load(source.binding.url) as? Resource.Success)?.value
                        val seed = plan.seeds.firstOrNull { source in it.sources }
                        val accepted = response != null && seed?.accepts(source, response) == true
                        if (com.lagradost.cloudstream3.BuildConfig.DEBUG) synchronized(attempts) {
                            attempts += mapOf("seed" to seed?.title, "provider" to source.binding.provider.name,
                                "pageTitle" to response?.name, "pageType" to response?.type?.name, "accepted" to accepted,
                                "recommendationTypes" to response?.recommendations.orEmpty().groupingBy { it.type?.name ?: "Unknown" }.eachCount())
                            runCatching { java.io.File(ctx.cacheDir,"recommendation-attempts.json")
                                .writeText(org.json.JSONArray(attempts).toString(2)) }
                        }
                        response?.recommendations?.takeIf { accepted }
                    }
                    // Opening a legacy history URL can supply its previously missing year/IDs.
                    val updated = recommendationPlan(allRepos)
                    publication.withLock {
                        if (generation != discoveryGeneration || account != DataStoreHelper.currentAccount) return@withLock
                        personalization = updated.plan
                        discoveryHistory = updated.history
                        suggestions = loaded
                        publishDiscovery(projector, latestResults, finishedCount, repos.size, generation, account, suggestions, personalization, pager)
                    }
                }
                if (repos.isEmpty()) publish(emptyList(), 0)
                AggregatedHomeLoader().load(repos, forceReload, publish)
                personalizedJob.join()
                if (projector.detailRequests.isNotEmpty()) {
                    _discoveryStatus.value = ctx.getString(R.string.home_updating_news)
                    com.lagradost.cloudstream4.aggregation.SearchMetadataEnricher().enrich(
                        projector.detailRequests,
                        load = { binding ->
                            val repo = repos.firstOrNull { it.name == binding.provider.name && it.mainUrl == binding.provider.mainUrl }
                            repo?.load(binding.url) is Resource.Success
                        }, onUpdated = { }
                    )
                    publish(latestResults, repos.size)
                }
                if (projector.refine()) publish(latestResults, repos.size)
                val shelfPager = HomeShelfPager(repos, latestResults)
                pager = shelfPager
                expandAggregatedShelf = expand@{ name ->
                    val initial = expandable[name] ?: return@expand null
                    var current = initial
                    // Providers can return alternative URLs for known works or a mixed page.
                    // Seek visible additions, with a bounded number of requests per user action.
                    repeat(3) {
                        if (generation != discoveryGeneration || account != DataStoreHelper.currentAccount) return@expand null
                        if (!current.hasNext) return@expand current
                        shelfPager.loadMore(current.list.list)
                        if (generation != discoveryGeneration || account != DataStoreHelper.currentAccount) return@expand null
                        publish(latestResults, repos.size)
                        current = expandable[name] ?: return@expand null
                        if (current.list.list.size > initial.list.list.size || current.loadFailed || !current.hasNext) return@expand current
                    }
                    current
                }
                publish(latestResults, repos.size)
            }
            if (generation == discoveryGeneration) isCurrentlyLoadingName = null
        }
    }

    private suspend fun publishDiscovery(projector: AggregatedHomeProjector,
        results: List<AggregatedHomeLoader.Result>, finished: Int, requested: Int, generation: Long,
        account: String, suggestions: List<RecommendationBatch>, plan: RecommendationPlan, pager: HomeShelfPager? = null) {
        val ctx = context ?: return
        val rows = withContext(Dispatchers.Default) {
            projector.project((pager?.feeds() ?: results.map { it.feed }).map { feed ->
                feed.copy(sections = feed.sections.map { section ->
                    ctx.filterHomePageListByFilmQuality(section.copy(list = section.list.filter(CatalogPreferences.read(ctx)::normalItem)))
                })
            }, suggestions.map { batch -> batch.copy(items = ctx.filterSearchResultByFilmQuality(batch.items.filter(CatalogPreferences.read(ctx)::normalItem))) }, plan.excluded)
        }
        withContext(Dispatchers.Main.immediate) {
            ensureActive()
            if (generation != discoveryGeneration || account != DataStoreHelper.currentAccount || !aggregated) return@withContext
            val mapped = rows.associate { row ->
                val title = ctx.getString(row.shelf.title)
                val personalized = row.shelf == HomeShelf.ForYou || row.shelf == HomeShelf.ForYouSeries
                title to ExpandableHomepageList(HomePageList(title, row.cards), 1,
                    !personalized && row.cards.size < 500 && (pager?.hasMore(row.cards) ?: (requested > 0)),
                    previewLimit = 24, loadFailed = pager?.failed == true)
            }
            expandable.clear(); expandable.putAll(mapped)
            _page.value = Resource.Success(mapped)
            val count = rows.flatMap { it.cards }.distinctBy { it.media.id }.size
            _discoveryStatus.value = when {
                finished < requested -> ctx.getString(R.string.home_updating_news)
                count == 0 -> ctx.getString(R.string.home_no_content)
                results.any { !it.completed } -> ctx.getString(R.string.home_partial_news, count)
                else -> ctx.getString(R.string.home_ready_news, count)
            }
            if (com.lagradost.cloudstream3.BuildConfig.DEBUG) {
                val report = mapOf("completed" to finished, "requested" to requested,
                    "works" to count, "partial" to results.any { !it.completed },
                    "personalization" to mapOf("seeds" to plan.seeds.size, "loaded" to suggestions.size,
                        "excluded" to plan.excluded.size,
                        "seedTitles" to plan.seeds.map { mapOf("title" to it.title, "kind" to it.kind.name, "accesses" to it.sources.size) },
                        "contributors" to suggestions.map { mapOf("title" to it.title, "kind" to it.kind.name,
                            "provider" to it.source.binding.provider.name, "items" to it.items.size) }),
                    "feeds" to results.map { mapOf("provider" to it.feed.provider.name, "completed" to it.completed,
                        "cached" to it.cached, "items" to it.feed.sections.sumOf { section -> section.list.size },
                        "sections" to it.feed.sections.map { section -> section.name }) },
                    "shelves" to rows.map { row -> mapOf("name" to row.shelf.name,
                        "cards" to row.cards.map { mapOf("title" to it.name, "id" to it.media.id,
                            "bindings" to it.media.bindings.size, "year" to it.media.year) }) })
                withContext(Dispatchers.IO) {
                    runCatching { java.io.File(ctx.cacheDir, "aggregated-home.json").writeText(org.json.JSONObject(report).toString(2)) }
                }
            }
        }
    }

    var repo: APIRepository? = null

    private val _apiName = MutableLiveData<String>()
    val apiName: LiveData<String> = _apiName

    private val _currentAccount = MutableLiveData<DataStoreHelper.Account?>()
    val currentAccount: MutableLiveData<DataStoreHelper.Account?> = _currentAccount

    private val _randomItems = MutableLiveData<List<SearchResponse>?>(null)
    val randomItems: LiveData<List<SearchResponse>?> = _randomItems

    private var currentShuffledList: List<SearchResponse> = listOf()

    private fun autoloadRepo(): APIRepository {
        return APIRepository(apis.withLock { apis.first { it.hasMainPage } })
    }

    private val _availableWatchStatusTypes =
        MutableLiveData<Pair<Set<WatchType>, Set<WatchType>>>()
    val availableWatchStatusTypes: LiveData<Pair<Set<WatchType>, Set<WatchType>>> =
        _availableWatchStatusTypes
    private val _bookmarks = MutableLiveData<Pair<Boolean, List<SearchResponse>>>()
    val bookmarks: LiveData<Pair<Boolean, List<SearchResponse>>> = _bookmarks

    private val _resumeWatching = MutableLiveData<List<SearchResponse>>()
    private val _preview = MutableLiveData<Resource<Pair<Boolean, List<LoadResponse>>>>()
    private val previewResponses = CopyOnWriteArrayList<LoadResponse>()
    private val previewResponsesAdded = mutableSetOf<String>()

    val resumeWatching: LiveData<List<SearchResponse>> = _resumeWatching
    val preview: LiveData<Resource<Pair<Boolean, List<LoadResponse>>>> = _preview

    private fun loadResumeWatching() = viewModelScope.launchSafe {
        val resumeWatchingResult = getResumeWatching()?.filter(::visibleHistory)
        if (isLayout(TV) && resumeWatchingResult != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ioSafe {
                // this WILL crash on non tvs, so keep this inside a try catch
                activity?.addProgramsToContinueWatching(resumeWatchingResult)
            }
        }
        resumeWatchingResult?.let {
            _resumeWatching.postValue(it)
        }
    }

    fun loadStoredData(preferredWatchStatus: Set<WatchType>?) = viewModelScope.launchSafe {
        val watchStatusIds = withContext(Dispatchers.IO) {
            getAllWatchStateIds()?.map { id ->
                Pair(id, getResultWatchState(id))
            }
        }?.distinctBy { it.first } ?: return@launchSafe

        val length = WatchType.entries.size
        val currentWatchTypes = mutableSetOf<WatchType>()

        for (watch in watchStatusIds) {
            currentWatchTypes.add(watch.second)
            if (currentWatchTypes.size >= length) {
                break
            }
        }

        currentWatchTypes.remove(WatchType.NONE)

        if (currentWatchTypes.size <= 0) {
            DataStoreHelper.homeBookmarkedList = intArrayOf()
            _availableWatchStatusTypes.postValue(setOf<WatchType>() to setOf())
            _bookmarks.postValue(Pair(false, ArrayList()))
            return@launchSafe
        }

        val watchPrefNotNull = preferredWatchStatus ?: EnumSet.of(currentWatchTypes.first())
        //if (currentWatchTypes.any { watchPrefNotNull.contains(it) }) watchPrefNotNull else listOf(currentWatchTypes.first())

        DataStoreHelper.homeBookmarkedList = watchPrefNotNull.map { it.internalId }.toIntArray()
        _availableWatchStatusTypes.postValue(

            watchPrefNotNull to
                    currentWatchTypes,

            )

        val list = withContext(Dispatchers.IO) {
            watchStatusIds.filter { watchPrefNotNull.contains(it.second) }
                .mapNotNull { getBookmarkedData(it.first) }
                .sortedBy { -it.latestUpdatedTime }
        }
        _bookmarks.postValue(Pair(true, list.filter(::visibleHistory)))
    }

    private var onGoingLoad: Job? = null
    private var isCurrentlyLoadingName: String? = null
    private fun loadAndCancel(api: MainAPI) {
        //println("loaded ${api.name}")
        onGoingLoad?.cancel()
        isCurrentlyLoadingName = api.name
        onGoingLoad = load(api)
    }

    data class ExpandableHomepageList(
        var list: HomePageList,
        var currentPage: Int,
        var hasNext: Boolean,
        val previewLimit: Int = Int.MAX_VALUE,
        val loadFailed: Boolean = false,
    )

    private val expandable: MutableMap<String, ExpandableHomepageList> = mutableMapOf()
    private val _page =
        MutableLiveData<Resource<Map<String, ExpandableHomepageList>>>(Resource.Loading())
    val page: LiveData<Resource<Map<String, ExpandableHomepageList>>> = _page

    val lock: MutableSet<String> = mutableSetOf()

    suspend fun expandAndReturn(name: String): ExpandableHomepageList? {
        if (aggregated) return withContext(Dispatchers.Main.immediate) {
            val generation = discoveryGeneration
            onGoingLoad?.join()
            if (generation != discoveryGeneration) null else {
                val result = expandAggregatedShelf?.invoke(name) ?: expandable[name]
                if (generation != discoveryGeneration) null else result
            }
        }
        if (lock.contains(name)) return null
        lock += name

        repo?.apply {
            waitForHomeDelay()

            expandable[name]?.let { current ->
                debugAssert({ !current.hasNext }) {
                    "Expand called when not needed"
                }

                val nextPage = current.currentPage + 1
                val next = getMainPage(nextPage, mainPage.indexOfFirst { it.name == name })
                if (next is Resource.Success) {
                    next.value.filterNotNull().forEach { main ->
                        main.items.forEach { newList ->
                            val key = newList.name
                            expandable[key]?.apply {
                                hasNext = main.hasNext
                                currentPage = nextPage

                                debugWarning({ newList.list.any { outer -> this.list.list.any { it.url == outer.url } } }) {
                                    "Expanded contained an item that was previously already in the list\n${list.name} = ${this.list.list}\n${newList.name} = ${newList.list}"
                                }

                                this.list.list += newList.list
                                this.list.list.distinctBy { it.url } // just to be sure we are not adding the same shit for some reason
                            } ?: debugWarning {
                                "Expanded an item not in main load named $key, current list is ${expandable.keys}"
                            }
                        }
                    }
                } else {
                    current.hasNext = false
                }
            }
            _page.postValue(Resource.Success(expandable))
        }

        lock -= name

        return expandable[name]
    }

    // this is soo over engineered, but idk how I can make it clean without making the main api harder to use :pensive:
    fun expand(name: String) = viewModelScope.launchSafe {
        expandAndReturn(name)
    }

    // returns the amount of items added and modifies current
    private suspend fun updatePreviewResponses(
        current: MutableList<LoadResponse>,
        alreadyAdded: MutableSet<String>,
        shuffledList: List<SearchResponse>,
        size: Int
    ): Int {
        var count = 0

        val addItems = arrayListOf<SearchResponse>()
        for (searchResponse in shuffledList) {
            if (!alreadyAdded.contains(searchResponse.url)) {
                addItems.add(searchResponse)
                previewResponsesAdded.add(searchResponse.url)
                if (++count >= size) {
                    break
                }
            }
        }

        val add = addItems.amap { searchResponse ->
            repo?.load(searchResponse.url)
        }.mapNotNull { if (it != null && it is Resource.Success) it.value else null }
        current.addAll(add)
        return add.size
    }

    private var addJob: Job? = null
    fun loadMoreHomeScrollResponses() {
        addJob = ioSafe {
            updatePreviewResponses(previewResponses, previewResponsesAdded, currentShuffledList, 1)
            _preview.postValue(Resource.Success((previewResponsesAdded.size < currentShuffledList.size) to previewResponses))
        }
    }

    private fun load(api: MainAPI): Job = ioSafe {
        repo = //if (api != null) {
            APIRepository(api)
        //} else {
        //    autoloadRepo()
        //}

        _apiName.postValue(repo?.name)
        _randomItems.postValue(listOf())

        if (repo?.hasMainPage != true) {
            _page.postValue(Resource.Success(emptyMap()))
            _preview.postValue(Resource.Failure(false, "No homepage"))
            return@ioSafe
        }


        _page.postValue(Resource.Loading())
        _preview.postValue(Resource.Loading())
        // cancel the current preview expand as that is no longer relevant
        addJob?.cancel()

        when (val data = repo?.getMainPage(1, null)) {
            is Resource.Success -> {
                try {
                    expandable.clear()
                    data.value.forEach { home ->
                        home?.items?.forEach { list ->
                            val filteredList =
                                context?.filterHomePageListByFilmQuality(list) ?: list
                            expandable[list.name] =
                                ExpandableHomepageList(
                                    filteredList.copy(
                                        list = CopyOnWriteArrayList(
                                            filteredList.list
                                        )
                                    ), 1, home.hasNext
                                )
                        }
                    }

                    val items = data.value.mapNotNull { it?.items }.flatten()


                    previewResponses.clear()
                    previewResponsesAdded.clear()

                    //val home = data.value
                    if (items.isNotEmpty()) {
                        val currentList =
                            items.shuffled().filter { it.list.isNotEmpty() }
                                .flatMap { it.list }
                                .distinctBy { it.url }.toList()

                        if (currentList.isNotEmpty()) {
                            val randomItems =
                                context?.filterSearchResultByFilmQuality(currentList.shuffled())
                                    ?: currentList.shuffled()

                            updatePreviewResponses(
                                previewResponses,
                                previewResponsesAdded,
                                randomItems,
                                3
                            )

                            _randomItems.postValue(randomItems)
                            currentShuffledList = randomItems
                        }
                    }
                    if (previewResponses.isEmpty()) {
                        _preview.postValue(
                            Resource.Failure(
                                false,
                                "No homepage responses"
                            )
                        )
                    } else {
                        _preview.postValue(Resource.Success((previewResponsesAdded.size < currentShuffledList.size) to previewResponses))
                    }
                    _page.postValue(Resource.Success(expandable))
                } catch (e: Exception) {
                    _randomItems.postValue(emptyList())
                    logError(e)
                }
            }

            is Resource.Failure -> {
                @Suppress("UNNECESSARY_NOT_NULL_ASSERTION")
                _page.postValue(data!!)
                @Suppress("UNNECESSARY_NOT_NULL_ASSERTION")
                _preview.postValue(data!!)
            }

            else -> Unit
        }
        isCurrentlyLoadingName = null
    }

    fun click(callback: SearchClickCallback) {
        if (callback.action != SEARCH_ACTION_FOCUSED) {
            SearchHelper.handleSearchClickCallback(callback)
        }
    }

    private val _popup = MutableLiveData<Pair<ExpandableHomepageList, (() -> Unit)?>?>(null)
    val popup: LiveData<Pair<ExpandableHomepageList, (() -> Unit)?>?> = _popup

    fun popup(list: ExpandableHomepageList?, deleteCallback: (() -> Unit)? = null) {
        if (list == null)
            _popup.postValue(null)
        else
            _popup.postValue(list to deleteCallback)
    }

    private fun bookmarksUpdated(unused: Boolean) {
        reloadStored()
    }

    private fun afterPluginsLoaded(forceReload: Boolean) {
        loadAndCancel(DataStoreHelper.currentHomePage, forceReload)
    }

    private fun afterMainPluginsLoaded(unused: Boolean = false) {
        loadAndCancel(DataStoreHelper.currentHomePage, false)
    }

    private fun reloadHome(unused: Boolean = false) {
        loadAndCancel(DataStoreHelper.currentHomePage, true)
    }

    private fun reloadAccount(unused: Boolean = false) {
        _currentAccount.postValue(
            getCurrentAccount()
        )
    }

    init {
        MainActivity.bookmarksUpdatedEvent += ::bookmarksUpdated
        MainActivity.afterPluginsLoadedEvent += ::afterPluginsLoaded
        MainActivity.mainPluginsLoadedEvent += ::afterMainPluginsLoaded
        MainActivity.reloadHomeEvent += ::reloadHome
        MainActivity.reloadAccountEvent += ::reloadAccount
    }

    override fun onCleared() {
        MainActivity.bookmarksUpdatedEvent -= ::bookmarksUpdated
        MainActivity.afterPluginsLoadedEvent -= ::afterPluginsLoaded
        MainActivity.mainPluginsLoadedEvent -= ::afterMainPluginsLoaded
        MainActivity.reloadHomeEvent -= ::reloadHome
        MainActivity.reloadAccountEvent -= ::reloadAccount
        super.onCleared()
    }

    fun queryTextSubmit(query: String) {
        QuickSearchFragment.pushSearch(
            query,
            repo?.name?.let { arrayOf(it) })
    }

    fun queryTextChange(newText: String) {
        // do nothing
    }

    fun loadStoredData() {
        val list = EnumSet.noneOf(WatchType::class.java)
        DataStoreHelper.homeBookmarkedList.map { WatchType.fromInternalId(it) }.let {
            list.addAll(it)
        }
        loadStoredData(list)
    }

    fun reloadStored() {
        loadResumeWatching()
        loadStoredData()
        if (aggregated) loadAndCancel(DataStoreHelper.currentHomePage, false)
    }

    fun click(load: LoadClickCallback) {
        loadResult(load.response.url, load.response.apiName, load.response.name, load.action)
    }

    // only save the key if it is from UI, as we don't want internal functions changing the setting
    fun loadAndCancel(
        preferredApiName: String?,
        forceReload: Boolean = true,
        fromUI: Boolean = false
    ) =
        viewModelScope.launch(Dispatchers.Main.immediate) {
            if (aggregated) {
                val selection = context?.let(CatalogPreferences::read)
                val signature = selection
                if (signature != discoverySelection) {
                    discoverySelection = signature
                    discoveryProviders = null
                    discoveryGeneration++
                    onGoingLoad?.cancel()
                    _page.value = Resource.Loading()
                }
                loadDiscovery(forceReload)
                reloadAccount()
                return@launch
            }
            if (discoveryProviders != null) {
                onGoingLoad?.cancel()
                isCurrentlyLoadingName = null
                _page.value = Resource.Loading()
            }
            discoveryProviders = null
            discoveryRequest++
            discoveryGeneration++
            //println("trying to load $preferredApiName")
            // Since plugins are loaded in stages this function can get called multiple times.
            // The issue with this is that the homepage may be fetched multiple times while the first request is loading
            // api?.let { expandable[it.name]?.list?.list?.isNotEmpty() } == true
            val currentPage = page.value

            // if we don't need to reload and we have a valid homepage or currently loading the same thing then return
            val currentLoading = isCurrentlyLoadingName
            if (!forceReload && (currentPage is Resource.Success && currentPage.value.isNotEmpty() || (currentLoading != null && currentLoading == preferredApiName))) {
                return@launch
            }

            val api = getApiFromNameNull(preferredApiName)
            if (preferredApiName == noneApi.name) {
                // just set to random
                if (fromUI) DataStoreHelper.currentHomePage = noneApi.name
                loadAndCancel(noneApi)
            } else if (preferredApiName == randomApi.name) {
                // randomize the api, if none exist like if not loaded or not installed
                // then use nothing
                val validAPIs = context?.filterProviderByPreferredMedia()
                if (validAPIs.isNullOrEmpty()) {
                    loadAndCancel(noneApi)
                } else {
                    val apiRandom = validAPIs.random()
                    loadAndCancel(apiRandom)
                    if (fromUI) DataStoreHelper.currentHomePage = apiRandom.name
                }
            } else if (api == null) {
                // API is not found aka not loaded or removed, post the loading
                // progress if waiting for plugins, otherwise nothing
                if (PluginManager.loadedOnlinePlugins || PluginManager.isSafeMode()) {
                    loadAndCancel(noneApi)
                } else {
                    _page.postValue(Resource.Loading())
                    if (preferredApiName != null)
                        _apiName.postValue(preferredApiName)
                }
            } else {
                // if the api is found, then set it to it and save key
                if (fromUI) DataStoreHelper.currentHomePage = api.name
                loadAndCancel(api)
            }
            reloadAccount()
        }
}
