package com.kloudstream.desktop.downloads

import com.kloudstream.desktop.core.AppDirs
import com.kloudstream.desktop.core.AppLog
import com.kloudstream.desktop.core.JsonFile
import com.kloudstream.desktop.library.WatchEntry
import com.kloudstream.desktop.platform.DesktopPreferences
import com.kloudstream.desktop.player.PlayRequest
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream4.aggregation.CanonicalSourceLoader
import com.lagradost.cloudstream4.aggregation.EpisodeSourceBinding
import com.lagradost.cloudstream4.aggregation.ExternalSubtitles
import com.lagradost.cloudstream4.aggregation.PlaybackSources
import com.lagradost.cloudstream4.aggregation.PlaybackWork
import com.lagradost.cloudstream4.aggregation.SourceRanking
import com.lagradost.cloudstream4.aggregation.SourceVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

enum class DownloadState { Queued, Downloading, Done, Failed }

/** What to fetch again after a restart: the routes to the work and what checks its streams. */
data class DownloadRequest(
    val bindings: List<EpisodeSourceBinding> = emptyList(),
    val expectedSeconds: Double? = null,
    val isEpisode: Boolean = false,
    val work: PlaybackWork? = null,
)

data class DownloadItem(
    val id: String = "",
    val title: String = "",
    val episodeLabel: String? = null,
    val poster: String? = null,
    val history: WatchEntry? = null,
    val request: DownloadRequest = DownloadRequest(),
    val video: String? = null,
    val audio: String? = null,
    val subtitles: List<String> = emptyList(),
    val version: SourceVersion? = null,
    val quality: Int = 0,
    val state: DownloadState = DownloadState.Queued,
    val progress: Double = 0.0,
    val bytes: Long = 0,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val identity: String? get() = history?.identity
    val episodeKey: String? get() = history?.episodeKey
}

/**
 * Downloads for offline viewing: two at a time, each from the best source the engine finds (as for
 * playback, with the remembered version), trying the next ones when a source fails. Original audio
 * gets its French subtitles saved next to it. The list survives restarts; unfinished downloads
 * start again.
 */
object Downloads {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json by lazy { JsonFile.open(File(AppDirs.data, "downloads.json")) }
    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> get() = _items
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val slots = Semaphore(2)
    private val prefs get() = DesktopPreferences.open("kloudstream")

    /** `Videos/KloudStream` unless chosen in the settings. */
    var folder: File
        get() = prefs.getString("download_folder", null)?.let(::File)
            ?: File(System.getProperty("user.home"), "Videos" + File.separator + "KloudStream")
        set(value) { prefs.edit().putString("download_folder", value.path).apply() }

    fun id(identity: String, episodeKey: String?): String = identity + "/" + (episodeKey ?: "movie")

    fun load() {
        _items.value = json.keys().mapNotNull { key -> json.get(key)?.let { runCatching { mapper.treeToValue(it, DownloadItem::class.java) }.getOrNull() } }
            .sortedByDescending { it.createdAt }
        _items.value.filter { it.state == DownloadState.Queued || it.state == DownloadState.Downloading }.forEach { start(it) }
    }

    fun find(identity: String, episodeKey: String?): DownloadItem? = _items.value.firstOrNull { it.id == id(identity, episodeKey) }

    /** The finished download of this movie or episode, if its file is still there. */
    fun local(identity: String, episodeKey: String?): DownloadItem? =
        find(identity, episodeKey)?.takeIf { it.state == DownloadState.Done && it.video?.let { path -> File(path).isFile } == true }

    fun enqueue(request: PlayRequest, poster: String?) {
        val history = request.history
        val id = id(history.identity, history.episodeKey)
        if (jobs.containsKey(id) || local(history.identity, history.episodeKey) != null) return
        val item = DownloadItem(id, request.title, request.episodeLabel, poster, history,
            DownloadRequest(request.bindings, request.expectedSeconds, request.isEpisode, request.work))
        update(item)
        start(item)
    }

    fun retry(item: DownloadItem) = start(item.copy(state = DownloadState.Queued, error = null, progress = 0.0, bytes = 0).also(::update))

    fun cancel(item: DownloadItem) {
        jobs.remove(item.id)?.cancel()
        delete(item)
    }

    /** Removes the entry and its files. */
    fun delete(item: DownloadItem) {
        jobs.remove(item.id)?.cancel()
        listOfNotNull(item.video, item.audio).plus(item.subtitles).map(::File).forEach { file ->
            file.delete(); File(file.parentFile, file.name + ".part").delete()
        }
        json.set(item.id, null)
        _items.value = _items.value.filter { it.id != item.id }
    }

    private fun update(item: DownloadItem) {
        json.set(item.id, mapper.valueToTree(item))
        _items.value = (listOf(item) + _items.value.filter { it.id != item.id }).sortedByDescending { it.createdAt }
    }

    private fun preference(): SourceVersion =
        runCatching { SourceVersion.valueOf(prefs.getString("source_version", null) ?: "Auto") }.getOrDefault(SourceVersion.Auto)

    private fun start(item: DownloadItem) {
        if (jobs.containsKey(item.id)) return
        jobs[item.id] = scope.launch {
            try {
                slots.withPermit { run(item) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                AppLog.warn("Download failed: ${item.title} ${item.episodeLabel.orEmpty()}", e)
                update(item.copy(state = DownloadState.Failed, error = "Aucune source n'a pu être téléchargée."))
            } finally {
                jobs.remove(item.id)
            }
        }
    }

    private suspend fun run(queued: DownloadItem) {
        var item = queued.copy(state = DownloadState.Downloading, progress = 0.0, bytes = 0, error = null)
        update(item)
        val preference = preference()
        val request = item.request
        val found = CanonicalSourceLoader().load(request.bindings, clearCache = false, casting = false, preference = preference,
            expectedSeconds = request.expectedSeconds, episode = request.isEpisode, work = request.work)
        val ranked = found.sources.sortedWith(SourceRanking.comparator({ SourceRanking.score(it.metadata, it.quality, preference) },
            { 0 }, { it.quality }, { it.metadata.ordinal }, { it.link.name }, { it.link.url }))
        val base = File(folder, safe(listOfNotNull(item.title, item.history?.year?.let { "($it)" }).joinToString(" ")) +
            File.separator + safe(listOfNotNull(item.title, item.episodeLabel).joinToString(" - ")))
        for (source in ranked.take(6)) {
            try {
                AppLog.info("Downloading ${item.title} ${item.episodeLabel.orEmpty()} from ${source.link.name}")
                val result = MediaDownloader().download(source.link, base) { progress, bytes ->
                    item = item.copy(progress = progress.coerceAtLeast(0.0), bytes = bytes)
                    update(item)
                }
                // French subtitles go with original audio (VO, or VOST through fetched subtitles).
                val subtitles = if (source.metadata.addedSubtitles || source.version == SourceVersion.VO)
                    found.subtitles.filter { ExternalSubtitles.isExternal(it) || it.lang.lowercase().let { l -> l.startsWith("fr") || "fran" in l } }
                        .take(1).mapNotNull { subtitle ->
                            runCatching {
                                File(base.parentFile, base.name + ".fr.srt").also { file ->
                                    file.writeBytes(app.get(subtitle.url, headers = subtitle.headers.orEmpty() + ("User-Agent" to USER_AGENT)).body.bytes())
                                }.path
                            }.getOrNull()
                        }
                    else emptyList()
                update(item.copy(state = DownloadState.Done, progress = 1.0, bytes = result.bytes, video = result.video.path,
                    audio = result.audio?.path, subtitles = subtitles, version = source.version, quality = source.quality))
                AppLog.info("Downloaded ${result.video.path}")
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                AppLog.warn("Download source failed: ${PlaybackSources.metadata(source.link)?.provider} ${e.message}")
            }
        }
        throw IllegalStateException("No source could be downloaded")
    }

    private fun safe(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|]"), " ").replace(Regex("\\s+"), " ").trim().take(120)
}
