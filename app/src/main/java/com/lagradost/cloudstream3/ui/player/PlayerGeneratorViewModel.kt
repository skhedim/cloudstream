package com.lagradost.cloudstream3.ui.player

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.launchSafe
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.ui.player.source_priority.ProfileSettings
import com.lagradost.cloudstream3.ui.player.source_priority.QualityDataHelper
import com.lagradost.cloudstream3.ui.player.source_priority.QualityDataHelper.getLinkPriority
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.videoskip.SkipAPI
import com.lagradost.cloudstream3.utils.videoskip.VideoSkipStamp
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.annotations.Contract
import java.util.concurrent.ConcurrentHashMap

import com.lagradost.cloudstream4.aggregation.PlaybackSources
import com.lagradost.cloudstream4.aggregation.SourceRanking
import com.lagradost.cloudstream4.aggregation.SourceVersion
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.utils.DataStoreHelper.currentAccount

typealias VideoLink = Pair<ExtractorLink?, ExtractorUri?>

data class GeneratorState(
    val meta: Any?,
    val nextMeta: Any?,
    val allMeta: List<*>?,
    val response: LoadResponse?,
    val index: Int,
    val id: Int?,
)

data class DisplayLink(
    val link: VideoLink,
    // If the link should be displayed and used by the player
    val shouldUseLink: Boolean,
    val priority: Int
)

/** Auto ranking score of a canonical link: language first unless the quality gap is very large. */
fun canonicalScore(link: VideoLink?): Double =
    SourceRanking.score(PlaybackSources.version(link?.first), PlaybackSources.quality(link?.first))

/** Immutable state of all current links relevant to displaying the video */
// @MustUseReturnValues
// @Immutable
data class VideoState(
    val subtitles: PersistentSet<SubtitleData> = persistentSetOf(),
    val links: PersistentSet<VideoLink> = persistentSetOf(),
    val erroredLinks: PersistentSet<VideoLink> = persistentSetOf(),
    val stamps: PersistentList<VideoSkipStamp> = persistentListOf(),
    val loading: Resource<Unit> = Resource.Loading(),
    val generatorState: GeneratorState? = null,
    val instance: Int,
    val canonical: Boolean = false,
    val sourceVersion: SourceVersion = SourceVersion.Auto,
    /** Canonical acquisition knows enough to start; it may still be loading better sources. */
    val canonicalReady: Boolean = false,
) {
    /**
     * This acts as a local cache for sorted links that are not copied over by the copy constructor.
     *
     * sortedBy is not exactly expensive, but each hasNextMirror does it again, so this alleviates unnecessary recomputation
     * */
    private val sortedLinks: ConcurrentHashMap<Int, List<DisplayLink>> = ConcurrentHashMap()

    /**
     * The cache is guaranteed to be up to date link-wise due to the immutable links.
     * However, hideNegativeSources and hideErrorSources could be updated, which requires clearing the cache.
     */
    fun clearSortedLinksCache() = sortedLinks.clear()

    private fun hasLinkErrored(link: VideoLink): Boolean {
        return erroredLinks.any { it == link }
    }

    private fun VideoLink.toDisplayLink(
        qualityProfile: Int,
        hideNegativeSources: Boolean,
        hideErrorSources: Boolean
    ): DisplayLink {
        val priority = getLinkPriority(qualityProfile, this.first)
        val shouldHideLink =
            (hideNegativeSources && priority < 0) || (hideErrorSources && hasLinkErrored(this))
        val displayLink = DisplayLink(this, !shouldHideLink, priority)

        return displayLink
    }

    // Modifying sortedLinks is not considered a "visible" side effect, and rerunning it does not change the result
    // It is by all standards, idempotent and by extension also pure as it has no "visible" side effect
    /** Returns .links in the sorted order according to the qualityProfile.
     * Use .links if order is not needed */
    @Contract(pure = true)
    fun sortLinks(qualityProfile: Int): List<DisplayLink> {
        sortedLinks[qualityProfile]?.let {
            return it
        }

        val hideNegativeSources =
            QualityDataHelper.getProfileSetting(qualityProfile, ProfileSettings.HideNegativeSources)
        val hideErrorSources =
            QualityDataHelper.getProfileSetting(qualityProfile, ProfileSettings.HideErrorSources)

        val versionExists = links.any { PlaybackSources.version(it.first) == sourceVersion }
        val requested = sourceVersion.takeIf { versionExists } ?: SourceVersion.Auto
        val display = links.map { link ->
            val base = link.toDisplayLink(qualityProfile, hideNegativeSources, hideErrorSources)
            if (canonical && requested != SourceVersion.Auto && PlaybackSources.version(link.first) != requested)
                base.copy(shouldUseLink = false) else base
        }
        return (if (canonical) display.sortedWith(SourceRanking.comparator(
            score = { canonicalScore(it.link) },
            profilePriority = { it.priority },
            quality = { PlaybackSources.quality(it.link.first) },
            ordinal = { PlaybackSources.metadata(it.link.first)?.ordinal ?: Int.MAX_VALUE },
            name = { it.link.first?.name.orEmpty() },
            url = { it.link.first?.url.orEmpty() },
        )) else display.sortedByDescending { it.priority })
            .also { sortedLinks[qualityProfile] = it }
    }

    @Contract(pure = true)
    fun add(item: SubtitleData): VideoState = copy(subtitles = subtitles.add(item))

    @Contract(pure = true)
    fun add(item: VideoLink): VideoState = copy(links = links.add(item))

    @Contract(pure = true)
    fun add(item: VideoSkipStamp): VideoState = copy(stamps = stamps.add(item))

    @JvmName("addSubtitleData")
    @Contract(pure = true)
    fun add(items: Collection<SubtitleData>): VideoState = copy(subtitles = subtitles.addAll(items))

    @JvmName("addVideoLink")
    @Contract(pure = true)
    fun add(items: Collection<VideoLink>): VideoState = copy(links = links.addAll(items))

    @JvmName("addVideoSkipStamp")
    @Contract(pure = true)
    fun add(items: Collection<VideoSkipStamp>): VideoState = copy(stamps = stamps.addAll(items))

    @Contract(pure = true)
    fun set(item: SubtitleData): VideoState = copy(subtitles = persistentSetOf(item))

    @Contract(pure = true)
    fun set(item: VideoLink): VideoState = copy(links = persistentSetOf(item))

    @Contract(pure = true)
    fun set(item: VideoSkipStamp): VideoState = copy(stamps = persistentListOf(item))

    @JvmName("setSubtitleData")
    @Contract(pure = true)
    fun set(items: Collection<SubtitleData>): VideoState = copy(subtitles = items.toPersistentSet())

    @JvmName("setVideoLink")
    @Contract(pure = true)
    fun set(items: Collection<VideoLink>): VideoState = copy(links = items.toPersistentSet())

    @JvmName("setVideoSkipStamp")
    @Contract(pure = true)
    fun set(items: Collection<VideoSkipStamp>): VideoState = copy(stamps = items.toPersistentList())

    @Contract(pure = true)
    fun addError(item: VideoLink): VideoState = copy(erroredLinks = erroredLinks.add(item))

    @Contract(pure = true)
    fun setError(items: Collection<VideoLink>): VideoState = copy(erroredLinks = items.toPersistentSet())
}

data class VideoLive<T>(
    val value: T,
    val instance: Int,
)

class PlayerGeneratorViewModel : ViewModel() {
    companion object {
        const val TAG = "PlayViewGen"
    }

    @Volatile
    var generator: VideoGenerator<*>? = null

    @Volatile
    var episodeIndex: Int = 0

    /**
     * The state of the video player, only modify it by modifyState to make sure observe is called,
     * and avoid concurrency issues.
     *
     * This value can be used without Synchronized or locking when reading, as all fields are immutable.
     * */
    @Volatile
    var state = VideoState(instance = 0)
        private set

    private val _currentLinks = MutableLiveData<VideoLive<Set<VideoLink>>>(null)
    val currentLinks: LiveData<VideoLive<Set<VideoLink>>> = _currentLinks

    private val _currentSubtitles = MutableLiveData<VideoLive<Set<SubtitleData>>>(null)
    val currentSubtitles: LiveData<VideoLive<Set<SubtitleData>>> = _currentSubtitles

    private val _loadingLinks = MutableLiveData<VideoLive<Resource<Unit>>>()
    val loadingLinks: LiveData<VideoLive<Resource<Unit>>> = _loadingLinks

    private val _currentStamps = MutableLiveData<VideoLive<List<VideoSkipStamp>>>(null)
    val currentStamps: LiveData<VideoLive<List<VideoSkipStamp>>> = _currentStamps

    /**
     * Modifies the `state` variable safely, and with the correct observe behavior.
     *
     * Synchronized to avoid concurrency issues, and make this operation atomic.
     * Otherwise, one update may be lost if they are done in parallel.
     * */
    @Synchronized
    fun modifyState(op: VideoState.() -> VideoState) {
        val oldState = state
        state = op.invoke(oldState)

        /** New instance, always push state */
        if (state.instance != oldState.instance) {
            _currentSubtitles.postValue(VideoLive(state.subtitles, state.instance))
            _currentStamps.postValue(VideoLive(state.stamps, state.instance))
            _currentLinks.postValue(VideoLive(state.links, state.instance))
            _loadingLinks.postValue(VideoLive(state.loading, state.instance))
            return
        }

        /**
         * Only post the changed values, this makes sure we do not invoke the "observe"
         *
         * We do this by "Referential equality" https://kotlinlang.org/docs/equality.html#referential-equality
         * to avoid comparing the entire set or list as "Persistent" classes will hold the same reference if they are unchanged.
         * */
        if (state.links !== oldState.links)
            _currentLinks.postValue(VideoLive(state.links, state.instance))
        if (state.stamps !== oldState.stamps)
            _currentStamps.postValue(VideoLive(state.stamps, state.instance))
        if (state.subtitles !== oldState.subtitles)
            _currentSubtitles.postValue(VideoLive(state.subtitles, state.instance))

        /** Normal equality here as it is not a collection */
        if (state.loading != oldState.loading)
            _loadingLinks.postValue(VideoLive(state.loading, state.instance))
    }

    private val _currentSubtitleYear = MutableLiveData<Int?>(null)
    val currentSubtitleYear: LiveData<Int?> = _currentSubtitleYear

    /**
     * Save the Episode ID to prevent starting multiple link loading Jobs when preloading links.
     */
    private var currentLoadingEpisodeId: Int? = null

    var forceClearCache = false
    private var preferredVersion = runCatching {
        SourceVersion.valueOf(getKey<String>("$currentAccount/canonical_source_version") ?: "Auto")
    }.getOrDefault(SourceVersion.Auto)

    fun selectSourceVersion(version: SourceVersion) {
        preferredVersion = version
        setKey("$currentAccount/canonical_source_version", version.name)
        modifyState { copy(sourceVersion = version) }
    }

    fun setSubtitleYear(year: Int?) {
        _currentSubtitleYear.postValue(year)
    }

    fun loadLinksPrev() {
        Log.i(TAG, "loadLinksPrev")
        if (generator?.hasPrev(episodeIndex) == true) {
            episodeIndex += 1
            loadLinks()
        }
    }

    fun loadLinksNext() {
        Log.i(TAG, "loadLinksNext")
        if (generator?.hasNext(episodeIndex) == true) {
            episodeIndex += 1
            loadLinks()
        }
    }

    fun hasNextEpisode(): Boolean? {
        return generator?.hasNext(episodeIndex)
    }

    fun hasPrevEpisode(): Boolean? {
        return generator?.hasPrev(episodeIndex)
    }

    fun preLoadNextLinks() {
        val id = generator?.getId(episodeIndex)
        // Do not preload if already loading
        if (id == currentLoadingEpisodeId) return

        Log.i(TAG, "preLoadNextLinks")
        currentJob?.cancel()
        currentLoadingEpisodeId = id

        currentJob = viewModelScope.launch {
            try {
                if (generator?.hasCache == true && generator?.hasNext(episodeIndex) == true) {
                    safeApiCall {
                        generator?.generateLinks(
                            sourceTypes = LOADTYPE_INAPP,
                            clearCache = false,
                            isCasting = false,
                            callback = {},
                            subtitleCallback = {},
                            offset = episodeIndex + 1
                        )
                    }
                }
            } catch (t: Throwable) {
                logError(t)
            } finally {
                if (currentLoadingEpisodeId == id) {
                    currentLoadingEpisodeId = null
                }
            }
        }
    }

    fun loadThisEpisode(index: Int) {
        episodeIndex = index
        loadLinks()
    }

    fun attachGenerator(newGenerator: VideoGenerator<*>, index: Int) {
        Log.i(TAG, "attachGenerator with generator=$newGenerator and index=$index")
        generator = newGenerator
        episodeIndex = index
    }

    /**
     * If duplicate nothing will happen
     * */
    fun addSubtitles(file: Set<SubtitleData>) {
        val validFile = file.filter(::isValidSubtitle)
        if (validFile.isNotEmpty())
            modifyState {
                add(validFile)
            }
    }

    private var currentJob: Job? = null
    private var currentStampJob: Job? = null

    fun loadStamps(duration: Long) {
        currentStampJob = ioSafe {
            val genState = state.generatorState ?: return@ioSafe
            val meta = genState.meta
            val page = genState.response
            val id = genState.id
            if (page == null || meta !is ResultEpisode) {
                return@ioSafe
            }
            val stamps = SkipAPI.videoStamps(
                page,
                meta,
                duration,
                hasNextEpisode() ?: false
            )

            /** Avoid adding stamps to the wrong video */
            modifyState {
                if (id != this.generatorState?.id) {
                    this
                } else {
                    set(stamps)
                }
            }
        }
    }

    var langFilterList = listOf<String>()
    var filterSubByLang = false

    fun isValidSubtitle(subtitle: SubtitleData): Boolean {
        if (langFilterList.isEmpty() || !filterSubByLang) {
            return true
        }

        /** Only filter out subtitles fetched online */
        if (subtitle.origin != SubtitleOrigin.URL) {
            return true
        }

        return langFilterList.any { lang ->
            subtitle.originalName.contains(lang, ignoreCase = true)
        }
    }

    fun loadLinks(sourceTypes: Set<ExtractorLinkType> = LOADTYPE_INAPP) {
        Log.i(TAG, "loadLinks with generator=$generator and index=$episodeIndex")
        currentJob?.cancel()
        val index = episodeIndex

        // Clear old data and reset the state
        modifyState {
            VideoState(
                loading = Resource.Loading(),
                canonical = (generator?.videos?.getOrNull(index) as? ResultEpisode)?.sourceBindings?.isNotEmpty() == true,
                sourceVersion = preferredVersion,
                generatorState = generator?.let { gen ->
                    GeneratorState(
                        meta = gen.videos.getOrNull(index),
                        nextMeta = gen.videos.getOrNull(index + 1),
                        id = gen.getId(index),
                        response = (gen as? RepoLinkGenerator)?.page,
                        index = index,
                        allMeta = gen.videos
                    )
                },
                instance = instance + 1
            )
        }

        val instanceOfLoad = state.instance
        (generator as? RepoLinkGenerator)?.apply {
            canonicalPreference = preferredVersion
            onCanonicalReady = { offset ->
                if (offset == index && state.instance == instanceOfLoad) {
                    modifyState { if (instance == instanceOfLoad) copy(canonicalReady = true) else this }
                    _currentLinks.postValue(VideoLive(state.links, state.instance))
                }
            }
        }

        currentJob = viewModelScope.launchSafe {
            // Load more data
            val loadingState = safeApiCall {
                generator?.generateLinks(
                    sourceTypes = sourceTypes,
                    clearCache = forceClearCache,
                    callback = { link ->
                        if (isActive)
                            modifyState {
                                add(link)
                            }
                    },
                    isCasting = false,
                    offset = index,
                    subtitleCallback = { link ->
                        if (isActive && isValidSubtitle(link))
                            modifyState {
                                add(link)
                            }
                    })
                Unit
            }

            if (!isActive) {
                return@launchSafe
            }

            /** Only mark as success if we have not skipped loading */
            modifyState {
                if (!isActive) {
                    this
                } else {
                    when (loading) {
                        is Resource.Loading -> copy(loading = loadingState)
                        else -> this
                    }
                }
            }
        }
    }
}