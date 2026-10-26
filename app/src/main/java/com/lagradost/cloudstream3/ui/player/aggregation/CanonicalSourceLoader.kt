package com.lagradost.cloudstream3.ui.player.aggregation

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.player.PlayerSubtitleHelper
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream4.aggregation.EpisodeSourceBinding
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicBoolean

data class SourceLoadOutcome(val ordinal: Int, val provider: String, val track: String,
    val status: String, val links: Int, val elapsedMs: Long)
data class CanonicalSources(val links: List<ExtractorLink>, val subtitles: List<SubtitleData>,
    val outcomes: List<SourceLoadOutcome>)

/** One bounded acquisition phase; playback starts with the best completed snapshot, not the
 * fastest extension's first callback. An extension failure never discards other sources. */
class CanonicalSourceLoader(
    private val concurrency: Int = 3,
    private val bindingTimeoutMs: Long = 15_000,
    private val totalTimeoutMs: Long = 30_000,
) {
    private data class Cached(val links: List<ExtractorLink>, val subtitles: List<SubtitleData>, val at: Long)
    private class Entry { val mutex = Mutex(); var cached: Cached? = null }
    private data class Key(val binding: EpisodeSourceBinding, val casting: Boolean)
    companion object {
        private val cache = LinkedHashMap<Key, Entry>(16, .75f, true)
        private const val TTL_MS = 20 * 60 * 1000L
        @Synchronized private fun entry(key: Key): Entry {
            val result = cache.getOrPut(key) { Entry() }
            while (cache.size > 200) {
                val evict = cache.entries.firstOrNull { !it.value.mutex.isLocked && it.key != key } ?: break
                cache.remove(evict.key)
            }
            return result
        }
        private suspend fun fetch(binding: EpisodeSourceBinding, casting: Boolean,
            subtitle: (SubtitleFile) -> Unit, link: (ExtractorLink) -> Unit): Boolean? {
            val named = APIHolder.apis.filter { it.name == binding.apiName }
            val api = named.firstOrNull { it.mainUrl == binding.mainUrl } ?: named.singleOrNull() ?: return null
            return APIRepository(api).loadLinks(binding.data, casting, subtitle, link)
        }
    }

    suspend fun load(bindings: List<EpisodeSourceBinding>, clearCache: Boolean, casting: Boolean,
        fetch: suspend (EpisodeSourceBinding, Boolean, (SubtitleFile) -> Unit, (ExtractorLink) -> Unit) -> Boolean? = Companion::fetch,
    ): CanonicalSources = withContext(Dispatchers.IO) {
        val indexed = bindings.distinct().withIndex().toList()
        // Start one binding of each declared version before spending the queue on unknown audio.
        val firsts = indexed.groupBy { PlaybackSources.version("", it.value) }.values.map { it.first() }
            .sortedByDescending { PlaybackSources.versionRank(PlaybackSources.version("", it.value)) }
        val ordered = firsts + indexed.filterNot { it in firsts }
        val semaphore = Semaphore(concurrency)
        val deadline = System.nanoTime() + totalTimeoutMs * 1_000_000
        val results = ordered.map { (ordinal, binding) -> async(start = CoroutineStart.UNDISPATCHED) {
            val started = System.nanoTime()
            val links = mutableListOf<ExtractorLink>()
            val subtitles = mutableListOf<SubtitleData>()
            val gate = AtomicBoolean(false)
            var status = "Timeout"
            try {
                withTimeoutOrNull(totalTimeoutMs) {
                    semaphore.withPermit {
                        // Acquire in planned order before dispatching extension work to IO.
                        yield()
                        entry(Key(binding, casting)).let { entry -> entry.mutex.withLock {
                            if (clearCache) entry.cached = null
                            val saved = entry.cached?.takeIf { System.currentTimeMillis() - it.at < TTL_MS }
                            if (saved != null) {
                                links.addAll(saved.links); subtitles.addAll(saved.subtitles); status = "Cached"
                            } else {
                                val remaining = (deadline - System.nanoTime()) / 1_000_000
                                if (remaining > 0) {
                                    val finished = withTimeoutOrNull(minOf(bindingTimeoutMs, remaining)) {
                                        gate.set(true)
                                        val job = currentCoroutineContext().job
                                        try {
                                            val success = fetch(binding, casting, { file ->
                                                if (gate.get() && job.isActive && file.url.isNotBlank()) synchronized(subtitles) {
                                                    subtitles += PlayerSubtitleHelper.getSubtitleData(file)
                                                }
                                            }, { link ->
                                                if (gate.get() && job.isActive &&
                                                    (link.url.isNotBlank() || link is com.lagradost.cloudstream3.utils.ExtractorLinkPlayList))
                                                    synchronized(links) { links += link }
                                            })
                                            gate.set(false)
                                            ensureActive()
                                            status = when {
                                                success == null -> "MissingProvider"
                                                links.isNotEmpty() && success -> "Success"
                                                links.isNotEmpty() -> "Partial"
                                                success -> "Empty"
                                                else -> "Failure"
                                            }
                                            success == true
                                        } finally { gate.set(false) }
                                    }
                                    if (finished == true && links.isNotEmpty()) {
                                        entry.cached = Cached(links.toList(), subtitles.toList(), System.currentTimeMillis())
                                    }
                                }
                            }
                        } }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { status = "Failure" }
            finally { gate.set(false) }
            ensureActive()
            val copied = synchronized(links) { links.map { PlaybackSources.copy(it, binding, ordinal) } }
            Triple(copied, synchronized(subtitles) { subtitles.toList() },
                SourceLoadOutcome(ordinal, binding.apiName, binding.track, status, copied.size,
                    (System.nanoTime() - started) / 1_000_000))
        } }.awaitAll().sortedBy { it.third.ordinal }
        ensureActive()
        val unique = linkedMapOf<List<Any?>, ExtractorLink>()
        results.flatMap { it.first }.forEach { link ->
            val key = PlaybackSources.key(link)
            val previous = unique[key]
            if (previous == null || PlaybackSources.quality(link) > PlaybackSources.quality(previous)) unique[key] = link
        }
        val suffixes = mutableMapOf<String, Int>()
        val subs = results.flatMap { it.second }.distinctBy { listOf(it.url, it.headers, it.mimeType) }
            .sortedWith(compareBy<SubtitleData> { it.originalName }.thenBy { it.url }).map { sub ->
                val count = (suffixes[sub.originalName] ?: 0) + 1
                suffixes[sub.originalName] = count
                sub.copy(nameSuffix = if (count == 1) "" else count.toString())
            }
        CanonicalSources(unique.values.toList(), subs, results.map { it.third })
    }
}
