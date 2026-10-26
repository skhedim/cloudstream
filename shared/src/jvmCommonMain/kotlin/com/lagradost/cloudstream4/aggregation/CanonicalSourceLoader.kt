package com.lagradost.cloudstream4.aggregation

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkPlayList
import com.lagradost.cloudstream4.domain.media.ProviderKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class SourceLoadOutcome(val ordinal: Int, val provider: String, val track: String,
    val status: String, val links: Int, val elapsedMs: Long)

data class CanonicalSources(
    val sources: List<PlaybackSource>,
    val subtitles: List<SubtitleFile>,
    val outcomes: List<SourceLoadOutcome>,
    /** Milliseconds from the start until [CanonicalSourceLoader.load]'s `onReady`; null if never signalled. */
    val readyAfterMs: Long? = null,
) {
    val links: List<ExtractorLink> get() = sources.map { it.link }
}

typealias SourceFetch = suspend (EpisodeSourceBinding, Boolean, (SubtitleFile) -> Unit, (ExtractorLink) -> Unit) -> Boolean?

/**
 * Bounded multi-provider acquisition. Sources are published as soon as they are acquired;
 * [PlaybackPolicy] decides when enough is known to start playback, while acquisition carries on
 * so later, better sources remain available for an automatic upgrade or a fallback.
 * An extension failure never discards other sources.
 */
class CanonicalSourceLoader(
    private val concurrency: Int = 6,
    private val bindingTimeoutMs: Long = 15_000,
    private val totalTimeoutMs: Long = 30_000,
    private val perProvider: Int = 2,
    private val policy: PlaybackPolicy = PlaybackPolicy(),
    private val probeStreams: Boolean = true,
) {
    private data class Cached(val links: List<ExtractorLink>, val subtitles: List<SubtitleFile>, val at: Long)
    private class Entry { val mutex = Mutex(); var cached: Cached? = null }
    private data class Key(val binding: EpisodeSourceBinding, val casting: Boolean)

    companion object {
        private val cache = LinkedHashMap<Key, Entry>(16, .75f, true)
        private const val TTL_MS = 20 * 60 * 1000L
        private val probes = Semaphore(4)

        @Synchronized private fun entry(key: Key): Entry {
            val result = cache.getOrPut(key) { Entry() }
            while (cache.size > 200) {
                val evict = cache.entries.firstOrNull { !it.value.mutex.isLocked && it.key != key } ?: break
                cache.remove(evict.key)
            }
            return result
        }

        /** Positive results already acquired for this binding (prefetch or an earlier play). */
        @Synchronized fun isCached(binding: EpisodeSourceBinding, casting: Boolean = false): Boolean =
            cache[Key(binding, casting)]?.cached?.let { System.currentTimeMillis() - it.at < TTL_MS } == true

        suspend fun fetch(binding: EpisodeSourceBinding, casting: Boolean,
            subtitle: (SubtitleFile) -> Unit, link: (ExtractorLink) -> Unit): Boolean? {
            val provider = AggregationPlatform.registry.resolve(ProviderKey(binding.apiName, binding.mainUrl), binding.providerId)
                ?: return null
            return AggregationPlatform.gateway.loadLinks(provider.api, binding.data, casting, subtitle, link)
        }

        private fun providerKey(binding: EpisodeSourceBinding) = binding.providerId ?: binding.apiName
    }

    /**
     * @param preference the remembered version choice; it weighs readiness like the player's ranking.
     * @param onSource each newly acquired unique source (a better-quality duplicate is published again).
     * @param onReady called once, when [PlaybackPolicy.shouldStart] holds.
     */
    suspend fun load(
        bindings: List<EpisodeSourceBinding>, clearCache: Boolean, casting: Boolean,
        preference: SourceVersion = SourceVersion.Auto,
        onSource: ((PlaybackSource) -> Unit)? = null,
        onSubtitle: ((SubtitleFile) -> Unit)? = null,
        onReady: (() -> Unit)? = null,
        fetch: SourceFetch = Companion::fetch,
    ): CanonicalSources = withContext(Dispatchers.IO) {
        val startedAt = System.nanoTime()
        val indexed = bindings.distinct().withIndex().toList()
        // Most reliable/fastest providers first; and one binding of each declared version starts
        // before the queue is spent on unknown audio.
        val now = System.currentTimeMillis()
        // Repeatedly failing providers (often an interactive verification) are a fallback only:
        // they start once the reliable ones are done, and only if nothing is playing yet.
        val (fallbacks, reliable) = indexed.partition { ProviderHealth.stats(providerKey(it.value)).unreliable(now) }
        val byHealth = reliable.sortedByDescending { ProviderHealth.priority(providerKey(it.value)) }
        val firsts = byHealth.groupBy { PlaybackSources.version("", it.value) }.values.map { it.first() }
            .sortedByDescending { PlaybackSources.versionRank(PlaybackSources.version("", it.value)) }
        val rest = byHealth.filterNot { it in firsts }
        val ordered = firsts + rest + fallbacks
        val fallbackOrdinals = fallbacks.map { it.index }.toSet()
        val reliableDone = kotlinx.coroutines.CompletableDeferred<Unit>()
        val reliableRemaining = AtomicInteger(reliable.size)
        if (reliable.isEmpty()) reliableDone.complete(Unit)
        val limiter = ProviderLimiter(concurrency, perProvider)
        val deadline = startedAt + totalTimeoutMs * 1_000_000

        val acquired = linkedMapOf<List<Any?>, PlaybackSource>()
        val subtitles = linkedMapOf<List<Any?>, SubtitleFile>()
        val pending = AtomicInteger(ordered.size)
        val ready = AtomicBoolean(onReady == null)
        var firstSourceAt = 0L
        var readyAfterMs: Long? = null
        val scope = this

        fun bestScore(): Double? = synchronized(acquired) {
            acquired.values.maxOfOrNull { SourceRanking.score(it.version, it.quality, preference) }
        }
        fun checkReady() {
            if (ready.get()) return
            val since = synchronized(acquired) { if (firstSourceAt == 0L) 0L else (System.nanoTime() - firstSourceAt) / 1_000_000 }
            if (policy.shouldStart(bestScore(), since, pending.get()) && ready.compareAndSet(false, true)) {
                readyAfterMs = (System.nanoTime() - startedAt) / 1_000_000
                onReady?.invoke()
            }
        }
        fun publish(raw: ExtractorLink, binding: EpisodeSourceBinding, ordinal: Int, probe: StreamProbe?) {
            val copy = PlaybackSources.copy(raw, binding, ordinal, probe)
            val source = PlaybackSource(copy, PlaybackSources.metadata(copy)!!)
            val key = PlaybackSources.key(copy)
            val isNew = synchronized(acquired) {
                val previous = acquired[key]
                (previous == null || source.quality > previous.quality).also {
                    if (it) acquired[key] = source
                    if (firstSourceAt == 0L) firstSourceAt = System.nanoTime()
                }
            }
            if (isNew) onSource?.invoke(source)
            checkReady()
        }

        val ticker: Job? = if (onReady == null) null else launch {
            while (isActive && !ready.get()) { delay(100); checkReady() }
        }

        val results = try {
            ordered.map { (ordinal, binding) -> async(start = CoroutineStart.UNDISPATCHED) {
                val started = System.nanoTime()
                val raw = mutableListOf<ExtractorLink>()
                val rawSubtitles = mutableListOf<SubtitleFile>()
                val probesOfBinding = mutableListOf<Job>()
                val gate = AtomicBoolean(false)
                var status = "Timeout"
                fun accept(link: ExtractorLink) {
                    synchronized(raw) { raw += link }
                    if (probeStreams && StreamProbes.worthProbing(link)) {
                        synchronized(probesOfBinding) {
                            probesOfBinding += scope.launch { publish(link, binding, ordinal, probes.withPermit { StreamProbes.probe(link) }) }
                        }
                    } else publish(link, binding, ordinal, null)
                }
                fun acceptSubtitle(file: SubtitleFile) {
                    val isNew = synchronized(subtitles) {
                        val key = listOf(file.url, file.headers)
                        (key !in subtitles).also { if (it) subtitles[key] = file }
                    }
                    synchronized(rawSubtitles) { rawSubtitles += file }
                    if (isNew) onSubtitle?.invoke(file)
                }
                val fallback = ordinal in fallbackOrdinals
                if (fallback) {
                    withTimeoutOrNull(((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1)) { reliableDone.await() }
                    if ((onReady != null && ready.get()) || synchronized(acquired) { acquired.isNotEmpty() }) status = "Skipped"
                }
                if (status != "Skipped") try {
                    withTimeoutOrNull(totalTimeoutMs) {
                        limiter.withPermit(providerKey(binding)) {
                            // Acquire in planned order before dispatching extension work.
                            yield()
                            entry(Key(binding, casting)).let { entry -> entry.mutex.withLock {
                                if (clearCache) entry.cached = null
                                val saved = entry.cached?.takeIf { System.currentTimeMillis() - it.at < TTL_MS }
                                if (saved != null) {
                                    status = "Cached"
                                    saved.subtitles.forEach(::acceptSubtitle)
                                    saved.links.forEach(::accept)
                                } else {
                                    val remaining = (deadline - System.nanoTime()) / 1_000_000
                                    if (remaining > 0) {
                                        val finished = withTimeoutOrNull(minOf(bindingTimeoutMs, remaining)) {
                                            gate.set(true)
                                            val job = currentCoroutineContext().job
                                            try {
                                                val success = fetch(binding, casting, { file ->
                                                    if (gate.get() && job.isActive && file.url.isNotBlank()) acceptSubtitle(file)
                                                }, { link ->
                                                    if (gate.get() && job.isActive &&
                                                        (link.url.isNotBlank() || link is ExtractorLinkPlayList)) accept(link)
                                                })
                                                gate.set(false)
                                                ensureActive()
                                                val count = synchronized(raw) { raw.size }
                                                status = when {
                                                    success == null -> "MissingProvider"
                                                    count > 0 && success -> "Success"
                                                    count > 0 -> "Partial"
                                                    success -> "Empty"
                                                    else -> "Failure"
                                                }
                                                success == true
                                            } finally { gate.set(false) }
                                        }
                                        val links = synchronized(raw) { raw.toList() }
                                        if (finished == true && links.isNotEmpty()) {
                                            entry.cached = Cached(links, synchronized(rawSubtitles) { rawSubtitles.toList() },
                                                System.currentTimeMillis())
                                        }
                                    }
                                }
                            } }
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { status = "Failure" }
                finally { gate.set(false) }
                // Manifest probes of this binding are bounded by their own timeout and the deadline.
                withTimeoutOrNull(((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1)) {
                    synchronized(probesOfBinding) { probesOfBinding.toList() }.joinAll()
                }
                ensureActive()
                val elapsed = (System.nanoTime() - started) / 1_000_000
                val count = synchronized(raw) { raw.size }
                if (!fallback && reliableRemaining.decrementAndGet() == 0) reliableDone.complete(Unit)
                if (status != "Cached" && status != "MissingProvider" && status != "Skipped") {
                    ProviderHealth.record(providerKey(binding), success = count > 0, timeout = status == "Timeout", elapsedMs = elapsed)
                }
                pending.decrementAndGet()
                checkReady()
                SourceLoadOutcome(ordinal, binding.apiName, binding.track, status, count, elapsed)
            } }.awaitAll().sortedBy { it.ordinal }
        } finally {
            ticker?.cancel()
        }
        ensureActive()
        ProviderHealth.persist()
        val sources = synchronized(acquired) { acquired.values.sortedBy { it.metadata.ordinal } }
        CanonicalSources(sources, synchronized(subtitles) { subtitles.values.toList() }, results, readyAfterMs)
    }

    /** Fills the cache for [bindings] without publishing anything; used before the user presses play. */
    suspend fun prefetch(bindings: List<EpisodeSourceBinding>, fetch: SourceFetch = Companion::fetch) {
        val missing = bindings.distinct().filterNot { isCached(it) }
        if (missing.isNotEmpty()) load(missing, clearCache = false, casting = false, fetch = fetch)
    }
}
