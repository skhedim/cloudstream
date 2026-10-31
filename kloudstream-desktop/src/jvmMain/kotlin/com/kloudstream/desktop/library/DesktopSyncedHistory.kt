package com.kloudstream.desktop.library

import com.lagradost.cloudstream4.aggregation.CanonicalDetailStore
import com.lagradost.cloudstream4.domain.media.MediaAggregator
import com.lagradost.cloudstream4.sync.SyncedHistoryStore
import com.lagradost.cloudstream4.sync.SyncedWork

/** The desktop history as the shared one sees it (HistorySyncService): one state per work. */
object DesktopSyncedHistory : SyncedHistoryStore {
    override fun states(): List<SyncedWork> = WatchHistory.entries.value.mapNotNull { entry ->
        val request = entry.request ?: return@mapNotNull null
        SyncedWork(SyncedWork.keyOf(request), SyncedWork.shared(request), entry.poster, entry.episodeKey, entry.season,
            entry.episode, entry.episodeTitle, entry.positionMs, entry.durationMs, entry.finished, updatedAt = entry.updatedAt)
    }

    override fun apply(state: SyncedWork): Boolean {
        // The work's identity here (it may be known under an older one), its page reopening with all bindings.
        val request = CanonicalDetailStore.remember(state.request)
        if (state.removed) {
            WatchHistory.remove(request.identity)
            return true
        }
        val existing = WatchHistory.entries.value.firstOrNull { it.identity == request.identity }
        WatchHistory.import(WatchEntry(request.identity, state.request.title, state.request.year, state.poster ?: existing?.poster,
            existing?.backdrop, MediaAggregator.family(state.request.kind) ?: "movie", request, state.episodeKey, state.season,
            state.episode, state.episodeTitle, state.positionMs, state.durationMs, state.updatedAt))
        return true
    }
}
