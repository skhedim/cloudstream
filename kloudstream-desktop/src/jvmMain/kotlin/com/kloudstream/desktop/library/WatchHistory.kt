package com.kloudstream.desktop.library

import com.kloudstream.desktop.core.AppDirs
import com.kloudstream.desktop.core.JsonFile
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream4.aggregation.CanonicalDetailRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** What was watched last for a work, enough to show it and reopen its page. */
data class WatchEntry(
    val identity: String,
    val title: String,
    val year: Int? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val family: String = "movie",
    val request: CanonicalDetailRequest? = null,
    val episodeKey: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val updatedAt: Long = 0,
) {
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val finished: Boolean get() = durationMs > 0 && positionMs >= durationMs * FINISHED_RATIO
}

data class Progress(val positionMs: Long, val durationMs: Long, val updatedAt: Long) {
    val finished: Boolean get() = durationMs > 0 && positionMs >= durationMs * FINISHED_RATIO
}

const val FINISHED_RATIO = 0.92

/** Local viewing history: per-episode progress and the latest entry of each work. */
object WatchHistory {
    private val json by lazy { JsonFile.open(File(AppDirs.data, "history.json")) }
    private val _entries = MutableStateFlow<List<WatchEntry>>(emptyList())
    /** Most recent first. */
    val entries: StateFlow<List<WatchEntry>> get() = _entries

    fun load() { publish() }

    private fun progressKey(identity: String, episodeKey: String?) = "progress/$identity/${episodeKey ?: "movie"}"

    fun progress(identity: String, episodeKey: String?): Progress? =
        json.get(progressKey(identity, episodeKey))?.let { runCatching { mapper.treeToValue(it, Progress::class.java) }.getOrNull() }

    /** A position worth resuming at: started, not finished. */
    fun resumePositionMs(identity: String, episodeKey: String?): Long =
        progress(identity, episodeKey)?.takeIf { !it.finished && it.positionMs > 30_000 }?.positionMs ?: 0

    private var lastStamp = 0L

    /** Strictly increasing, so two saves within one millisecond keep their order (most recent first). */
    @Synchronized private fun stamp(): Long = maxOf(System.currentTimeMillis(), lastStamp + 1).also { lastStamp = it }

    fun save(entry: WatchEntry) {
        val now = stamp()
        json.set(progressKey(entry.identity, entry.episodeKey), mapper.valueToTree(Progress(entry.positionMs, entry.durationMs, now)))
        json.set("item/${entry.identity}", mapper.valueToTree(entry.copy(updatedAt = now)))
        publish()
    }

    /** A state played on another device ([DesktopSyncedHistory]), kept with its own time. */
    @Synchronized fun import(entry: WatchEntry) {
        lastStamp = maxOf(lastStamp, entry.updatedAt)
        json.set(progressKey(entry.identity, entry.episodeKey),
            mapper.valueToTree(Progress(entry.positionMs, entry.durationMs, entry.updatedAt)))
        json.set("item/${entry.identity}", mapper.valueToTree(entry))
        publish()
    }

    fun remove(identity: String) {
        json.keys().filter { it == "item/$identity" || it.startsWith("progress/$identity/") }.forEach { json.set(it, null) }
        publish()
    }

    /** Forgets every position and the Continue watching row. */
    fun clear() {
        json.keys().forEach { json.set(it, null) }
        publish()
    }

    private fun publish() {
        _entries.value = json.keys().filter { it.startsWith("item/") }
            .mapNotNull { key -> json.get(key)?.let { runCatching { mapper.treeToValue(it, WatchEntry::class.java) }.getOrNull() } }
            .sortedByDescending { it.updatedAt }
    }
}
