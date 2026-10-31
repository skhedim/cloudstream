package com.lagradost.cloudstream4.sync

import com.lagradost.cloudstream4.aggregation.AggregationPlatform
import com.lagradost.cloudstream4.aggregation.CanonicalDetailRequest
import com.lagradost.cloudstream4.aggregation.KeyValueStore
import com.lagradost.cloudstream4.aggregation.get
import com.lagradost.cloudstream4.domain.media.ExternalIdNamespace
import com.lagradost.cloudstream4.domain.media.ExternalMediaId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * One work's viewing state as the devices share it: the film, or the series' episode played last.
 * [request] reopens the work's page on any device (the same canonical page as Android's and desktop's).
 */
data class SyncedWork(
    val key: String,
    val request: CanonicalDetailRequest,
    val poster: String? = null,
    val episodeKey: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** The film, or this episode, was watched to the end. */
    val finished: Boolean = false,
    /** Removed from Continue watching on a device: the others remove it too. */
    val removed: Boolean = false,
    val updatedAt: Long = 0,
    val device: String? = null,
) {
    companion object {
        /**
         * The same work gets the same key on every device: its TMDB ID, else its IMDb ID (as
         * canonical identities), else the identity the device knows it by.
         */
        fun keyOf(request: CanonicalDetailRequest): String {
            val preferred = request.externalIds.sortedWith(compareBy<ExternalMediaId> {
                when (it.namespace) {
                    ExternalIdNamespace.TmdbMovie, ExternalIdNamespace.TmdbTv -> 0
                    ExternalIdNamespace.Imdb -> 1
                    else -> 2
                }
            }.thenBy { it.namespace.name }.thenBy { it.value }).firstOrNull() ?: return request.identity
            val edition = request.edition.sorted().joinToString(",").let { if (it.isEmpty()) "" else ":edition:$it" }
            return "canonical:v1:${preferred.namespace}:${preferred.value}$edition"
        }

        /** [request] as shared: a few bindings and titles are enough to reopen and verify the page. */
        fun shared(request: CanonicalDetailRequest) = request.copy(bindings = request.bindings.take(8),
            aliases = request.aliases.take(8).toSet(), completedAt = null)
    }
}

/** The shared file: every work's latest state, most recent first. */
data class SyncedHistory(val version: Int = 1, val works: Map<String, SyncedWork> = emptyMap()) {
    /** Keeps the most recent state of each work (on a tie, the one already here), at most [MAX_WORKS]. */
    fun merge(states: Collection<SyncedWork>): SyncedHistory {
        val merged = LinkedHashMap(works)
        states.forEach { state ->
            val known = merged[state.key]
            if (known == null || state.updatedAt > known.updatedAt) merged[state.key] = state
        }
        return copy(works = merged.values.sortedByDescending { it.updatedAt }.take(MAX_WORKS).associateBy { it.key })
    }

    companion object {
        /** Keeps the file well under the 1 MB GitHub returns inline. */
        const val MAX_WORKS = 400
    }
}

/** A device's own history, as the sync reads and updates it. */
interface SyncedHistoryStore {
    /** Every work with a viewing state here, finished ones included, keyed by [SyncedWork.keyOf]. */
    fun states(): List<SyncedWork>

    /** Applies [state], more recent than this device's; false when it cannot be applied here (no provider for it). */
    fun apply(state: SyncedWork): Boolean
}

/** Where the shared file lives. */
interface SyncRemote {
    /** The shared history and its version, or an empty history and null when there is none yet. */
    suspend fun read(): Pair<SyncedHistory, String?>

    /** False when [version] is no longer the latest: another device wrote meanwhile. */
    suspend fun write(history: SyncedHistory, version: String?): Boolean
}

class SyncConflictException : IOException("The shared history kept changing")

/**
 * Brings this device and the shared history up to date with each other: newer shared states are
 * applied here, newer local ones are written back. A work this device had and no longer has, not
 * played elsewhere since, stays away: in progress, it was removed from Continue watching here and is
 * removed everywhere; finished, the device kept only its latest finished works.
 */
class HistorySync(
    private val local: SyncedHistoryStore,
    private val remote: SyncRemote,
    private val device: String,
    private val store: () -> KeyValueStore = { AggregationPlatform.store },
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Result(val applied: Int, val written: Boolean)

    /**
     * The works this device had (applied or its own) and their time then, to tell a work that left
     * this device from one it never had, or one played elsewhere since.
     */
    internal data class Known(val works: Map<String, Long> = emptyMap())

    private val mutex = Mutex()

    suspend fun sync(): Result = mutex.withLock {
        var applied = 0
        val known = store().get<Known>(KNOWN_KEY)?.works.orEmpty()
        repeat(3) {
            val (shared, version) = remote.read()
            val before = local.states().associateBy { it.key }
            val left = known.filter { (key, at) -> key !in before && (shared.works[key]?.updatedAt ?: Long.MAX_VALUE) <= at }
            var appliedNow = 0
            for (work in shared.works.values.sortedBy { it.updatedAt }) {
                val mine = before[work.key]
                if (work.key in left || mine != null && mine.updatedAt >= work.updatedAt) continue
                if (work.removed && mine == null) continue
                if (local.apply(work)) appliedNow++
            }
            applied += appliedNow
            val after = if (appliedNow > 0) local.states().associateBy { it.key } else before
            val removals = left.keys.mapNotNull { key ->
                shared.works[key]?.takeIf { !it.removed && !it.finished }?.copy(removed = true, updatedAt = now(), device = device)
            }
            val merged = shared.merge(after.values.map { if (it.device == null) it.copy(device = device) else it } + removals)
            if (merged == shared || remote.write(merged, version)) {
                store().set(KNOWN_KEY, Known(left + removals.associate { it.key to it.updatedAt } +
                    after.mapValues { it.value.updatedAt }))
                return Result(applied, merged != shared)
            }
        }
        throw SyncConflictException()
    }

    companion object {
        const val KNOWN_KEY = "history_sync/known"

        /** After connecting to another history: nothing this device had there was removed here. */
        fun forget(store: KeyValueStore) = store.set(KNOWN_KEY, Known())
    }
}
