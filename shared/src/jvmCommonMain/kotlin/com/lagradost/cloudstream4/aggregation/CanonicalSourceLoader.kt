package com.lagradost.cloudstream4.aggregation

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkPlayList
import com.lagradost.cloudstream4.domain.media.ProviderKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class SourceLoadOutcome(val ordinal: Int, val provider: String, val track: String,
    val status: String, val links: Int, val elapsedMs: Long, val rejected: Int = 0)

data class CanonicalSources(
    val sources: List<PlaybackSource>,
    val subtitles: List<SubtitleFile>,
    val outcomes: List<SourceLoadOutcome>,
    /** Milliseconds from the start until [CanonicalSourceLoader.load]'s `onReady`; null if never signalled. */
    val readyAfterMs: Long? = null,
    /** Links dropped by stream checks, by reason. */
    val rejections: Map<String, Int> = emptyMap(),
) {
    val links: List<ExtractorLink> get() = sources.map { it.link }
}

typealias SourceFetch = suspend (EpisodeSourceBinding, Boolean, (SubtitleFile) -> Unit, (ExtractorLink) -> Unit) -> Boolean?

/**
 * Bounded multi-provider acquisition with stream checks.
 *
 * Every acquired link goes through a priority queue: the most promising links (viewer's language,
 * reliable provider, high declared quality) are inspected first by a small worker pool, which
 * measures reachability, manifest resolution/languages and the real duration (HLS) or size. Links
 * whose content cannot be the requested work are dropped before the player sees them. Inspected
 * links are published as they pass; once the inspection budget is spent, remaining links are
 * published unchecked and rank below checked ones. [PlaybackPolicy] decides when to start.
 */
class CanonicalSourceLoader(
    private val concurrency: Int = 6,
    private val bindingTimeoutMs: Long = 15_000,
    private val totalTimeoutMs: Long = 30_000,
    private val perProvider: Int = 2,
    private val policy: PlaybackPolicy = PlaybackPolicy(),
    private val inspectStreams: Boolean = true,
    private val inspectionWorkers: Int = 4,
    private val inspectionBudget: Int = 30,
    private val inspect: suspend (ExtractorLink) -> StreamInspection = { StreamInspector.inspect(it) },
) {
    private data class Cached(val links: List<ExtractorLink>, val subtitles: List<SubtitleFile>, val at: Long)
    private class Entry { val mutex = Mutex(); var cached: Cached? = null }
    private data class Key(val binding: EpisodeSourceBinding, val casting: Boolean)

    private class Candidate(val raw: ExtractorLink, val binding: EpisodeSourceBinding, val ordinal: Int, val priority: Double)

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

        /** Order of inspection: language, then reliability, then believable declared quality. */
        private fun priority(raw: ExtractorLink, binding: EpisodeSourceBinding): Double =
            SourceRanking.score(PlaybackSources.version(raw.name, binding),
                SourceRanking.trustedHeight(PlaybackSources.quality(raw), null)) -
                ProviderHealth.playbackPenalty(providerKey(binding)) +
                ProviderHealth.stats(providerKey(binding)).successRate
    }

    /**
     * @param preference the remembered version choice; it weighs readiness like the player's ranking.
     * @param expectedSeconds catalogue runtime of the movie/episode, for duration checks.
     * @param episode whether [expectedSeconds] is an episode runtime (looser tolerance).
     * @param onSource each newly published unique source (a better-quality duplicate is published again).
     * @param onReady called once, when [PlaybackPolicy.shouldStart] holds.
     */
    suspend fun load(
        bindings: List<EpisodeSourceBinding>, clearCache: Boolean, casting: Boolean,
        preference: SourceVersion = SourceVersion.Auto,
        expectedSeconds: Double? = null,
        episode: Boolean = false,
        onSource: ((PlaybackSource) -> Unit)? = null,
        onSubtitle: ((SubtitleFile) -> Unit)? = null,
        onReady: (() -> Unit)? = null,
        fetch: SourceFetch = Companion::fetch,
    ): CanonicalSources = withContext(Dispatchers.IO) {
        val startedAt = System.nanoTime()
        val indexed = bindings.distinct().withIndex().toList()
        val now = System.currentTimeMillis()
        val viewer = PlaybackSources.viewerLanguage()
        fun native(binding: EpisodeSourceBinding) = binding.language?.substringBefore('-')?.lowercase()
            .let { it == null || it == viewer || it == "multi" || it == "mul" || it == "all" }
        // Repeatedly failing providers (often an interactive verification) are a fallback only:
        // they start once the reliable ones are done, and only if nothing is playing yet.
        val (fallbacks, reliable) = indexed.partition { ProviderHealth.stats(providerKey(it.value)).unreliable(now) }
        // Providers in the viewer's language first, then the most reliable/fastest; one binding of
        // each declared version starts before the queue is spent on unknown audio.
        val byHealth = reliable.sortedWith(compareByDescending<IndexedValue<EpisodeSourceBinding>> { native(it.value) }
            .thenByDescending { ProviderHealth.priority(providerKey(it.value)) })
        val firsts = byHealth.groupBy { PlaybackSources.version("", it.value) }.values.map { it.first() }
            .sortedByDescending { PlaybackSources.versionRank(PlaybackSources.version("", it.value)) }
        val rest = byHealth.filterNot { it in firsts }
        val ordered = firsts + rest + fallbacks
        val fallbackOrdinals = fallbacks.map { it.index }.toSet()
        val reliableDone = CompletableDeferred<Unit>()
        val reliableRemaining = AtomicInteger(reliable.size)
        if (reliable.isEmpty()) reliableDone.complete(Unit)
        val limiter = ProviderLimiter(concurrency, perProvider)
        val deadline = startedAt + totalTimeoutMs * 1_000_000

        val published = linkedMapOf<List<Any?>, PlaybackSource>()
        val subtitles = linkedMapOf<List<Any?>, SubtitleFile>()
        val rejections = linkedMapOf<String, Int>()
        val rejectedPerOrdinal = mutableMapOf<Int, Int>()
        val pendingBindings = AtomicInteger(ordered.size)
        val nativePending = AtomicInteger(ordered.count { native(it.value) && it.index !in fallbackOrdinals })
        val ready = AtomicBoolean(onReady == null)
        var readyAfterMs: Long? = null
        val scope = this

        // Inspection queue: most promising first; workers stop when acquisition is over and it is empty.
        val queue = java.util.PriorityQueue<Candidate>(compareByDescending { it.priority })
        val queued = AtomicInteger(0)
        val nativeQueued = AtomicInteger(0)
        val inspections = AtomicInteger(0)
        val wake = Channel<Unit>(Channel.CONFLATED)
        val acquisitionOver = AtomicBoolean(false)

        fun best(): PlaybackSource? = synchronized(published) {
            published.values.maxByOrNull { SourceRanking.score(it.metadata, PlaybackSources.quality(it.link), preference) }
        }
        fun checkReady() {
            if (ready.get()) return
            val top = best()
            val elapsed = (System.nanoTime() - startedAt) / 1_000_000
            val waiting = pendingBindings.get() + queued.get()
            val nativeWaiting = nativePending.get() + nativeQueued.get()
            val score = top?.let { SourceRanking.score(it.metadata, PlaybackSources.quality(it.link), preference) }
            if (policy.shouldStart(top?.metadata, score, elapsed, nativeWaiting, waiting) && ready.compareAndSet(false, true)) {
                readyAfterMs = elapsed
                onReady?.invoke()
            }
        }
        fun publish(candidate: Candidate, probe: StreamProbe?, check: SourceCheck, duration: Double?) {
            val copy = PlaybackSources.copy(candidate.raw, candidate.binding, candidate.ordinal, probe, check, duration)
            val source = PlaybackSource(copy, PlaybackSources.metadata(copy)!!)
            val key = PlaybackSources.key(copy)
            val isNew = synchronized(published) {
                val previous = published[key]
                val better = previous == null || source.score > previous.score
                if (better) published[key] = source
                better
            }
            if (isNew) onSource?.invoke(source)
            checkReady()
        }
        fun reject(candidate: Candidate, reason: String) {
            synchronized(rejections) {
                val key = reason.substringBefore(' ')
                rejections[key] = (rejections[key] ?: 0) + 1
                rejectedPerOrdinal[candidate.ordinal] = (rejectedPerOrdinal[candidate.ordinal] ?: 0) + 1
            }
        }
        fun enqueue(raw: ExtractorLink, binding: EpisodeSourceBinding, ordinal: Int) {
            val candidate = Candidate(raw, binding, ordinal, priority(raw, binding))
            synchronized(queue) { queue += candidate }
            queued.incrementAndGet()
            if (native(binding)) nativeQueued.incrementAndGet()
            wake.trySend(Unit)
        }

        val workers: List<Job> = (1..inspectionWorkers).map {
            launch {
                while (isActive) {
                    val next = synchronized(queue) { queue.poll() }
                    if (next == null) {
                        if (acquisitionOver.get()) break
                        withTimeoutOrNull(200) { wake.receive() }
                        continue
                    }
                    try {
                        val inspectable = inspectStreams && next.raw !is ExtractorLinkPlayList
                        if (inspectable && inspections.incrementAndGet() <= inspectionBudget) {
                            val inspection = inspect(next.raw)
                            val verdict = StreamChecks.verdict(inspection, expectedSeconds, episode)
                            if (verdict.rejected) reject(next, verdict.reason ?: "rejected")
                            else publish(next, inspection.probe.takeIf { inspection.reachable }, verdict.check!!, inspection.durationSeconds)
                        } else publish(next, null, SourceCheck.Unchecked, null)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { publish(next, null, SourceCheck.Unchecked, null) }
                    finally {
                        queued.decrementAndGet()
                        if (native(next.binding)) nativeQueued.decrementAndGet()
                        checkReady()
                    }
                }
            }
        }
        val ticker: Job? = if (onReady == null) null else launch {
            while (isActive && !ready.get()) { delay(100); checkReady() }
        }

        val results = try {
            ordered.map { (ordinal, binding) -> async(start = CoroutineStart.UNDISPATCHED) {
                val started = System.nanoTime()
                val raw = mutableListOf<ExtractorLink>()
                val rawSubtitles = mutableListOf<SubtitleFile>()
                val gate = AtomicBoolean(false)
                var status = "Timeout"
                fun accept(link: ExtractorLink) {
                    synchronized(raw) { raw += link }
                    enqueue(link, binding, ordinal)
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
                    // Wait for the reliable providers' links to be checked too.
                    while (queued.get() > 0 && System.nanoTime() < deadline) delay(100)
                    if ((onReady != null && ready.get()) || synchronized(published) { published.isNotEmpty() }) status = "Skipped"
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
                ensureActive()
                val elapsed = (System.nanoTime() - started) / 1_000_000
                val count = synchronized(raw) { raw.size }
                if (status != "Cached" && status != "MissingProvider" && status != "Skipped") {
                    ProviderHealth.record(providerKey(binding), success = count > 0, timeout = status == "Timeout", elapsedMs = elapsed)
                }
                if (!fallback && reliableRemaining.decrementAndGet() == 0) reliableDone.complete(Unit)
                pendingBindings.decrementAndGet()
                if (native(binding) && !fallback) nativePending.decrementAndGet()
                checkReady()
                Triple(ordinal, binding, SourceLoadOutcome(ordinal, binding.apiName, binding.track, status, count, elapsed))
            } }.awaitAll()
        } finally {
            acquisitionOver.set(true)
            wake.trySend(Unit)
        }
        // Let the queue drain within the deadline; what is left is published unchecked.
        withTimeoutOrNull(((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1)) { workers.forEach { it.join() } }
        workers.forEach { it.cancel() }
        synchronized(queue) { generateSequence { queue.poll() }.toList() }.forEach { publish(it, null, SourceCheck.Unchecked, null) }
        ticker?.cancel()
        ensureActive()
        checkReady()
        ProviderHealth.persist()
        val outcomes = results.map { (ordinal, _, outcome) -> outcome.copy(rejected = synchronized(rejections) { rejectedPerOrdinal[ordinal] ?: 0 }) }
            .sortedBy { it.ordinal }
        val sources = synchronized(published) { published.values.sortedBy { it.metadata.ordinal } }
        CanonicalSources(sources, synchronized(subtitles) { subtitles.values.toList() }, outcomes, readyAfterMs,
            synchronized(rejections) { rejections.toMap() })
    }

    /** Fills the cache for [bindings] without publishing anything. */
    suspend fun prefetch(bindings: List<EpisodeSourceBinding>, fetch: SourceFetch = Companion::fetch) {
        val missing = bindings.distinct().filterNot { isCached(it) }
        if (missing.isNotEmpty()) load(missing, clearCache = false, casting = false, fetch = fetch)
    }
}
