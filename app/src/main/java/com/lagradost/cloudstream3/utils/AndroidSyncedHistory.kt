package com.lagradost.cloudstream3.utils

import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.player.NEXT_WATCH_EPISODE_PERCENTAGE
import com.lagradost.cloudstream3.ui.result.aggregation.CanonicalDetail
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream4.aggregation.CanonicalDetailRequest
import com.lagradost.cloudstream4.aggregation.CanonicalDetailStore
import com.lagradost.cloudstream4.domain.media.MediaAggregator
import com.lagradost.cloudstream4.domain.media.MediaKind
import com.lagradost.cloudstream4.sync.SyncedHistoryStore
import com.lagradost.cloudstream4.sync.SyncedWork

/**
 * The current account's history as the shared one sees it (HistorySyncService): Continue watching and
 * finished works of canonical pages, whose history IDs follow the work ([CanonicalDetail.pageId]).
 */
object AndroidSyncedHistory : SyncedHistoryStore {
    private fun header(id: Int) = getKey<DownloadObjects.DownloadHeaderCached>(DOWNLOAD_HEADER_CACHE, id.toString())
        ?: getKey<DownloadObjects.DownloadHeaderCached>(DOWNLOAD_HEADER_CACHE_BACKUP, id.toString())

    /** The canonical page saved as [parentId], if it is one. */
    private fun request(parentId: Int): Pair<CanonicalDetailRequest, DownloadObjects.DownloadHeaderCached>? {
        val header = header(parentId) ?: return null
        val request = CanonicalDetailStore.find(header.apiName, header.url) ?: return null
        return (request to header).takeIf { CanonicalDetail.pageId(request.identity) == parentId }
    }

    private fun movie(request: CanonicalDetailRequest) = MediaAggregator.family(request.kind) != "series"

    override fun states(): List<SyncedWork> {
        val resumes = DataStoreHelper.getAllResumeStateIds().orEmpty().mapNotNull { DataStoreHelper.getLastWatched(it) }
        val playing = resumes.mapNotNull { resume ->
            val (request, header) = request(resume.parentId) ?: return@mapNotNull null
            // The canonical episode key back from the numbers saved, checked against the episode ID.
            val episodeKey = if (movie(request)) null else listOf("s${resume.season}:e${resume.episode}", "abs:e${resume.episode}")
                .firstOrNull { CanonicalDetail.episodeId(request.identity, it) == resume.episodeId }
            val position = DataStoreHelper.getViewPos(if (movie(request)) resume.parentId else resume.episodeId)
                ?.takeIf { movie(request) || episodeKey != null }
            SyncedWork(SyncedWork.keyOf(request), SyncedWork.shared(request), header.poster, episodeKey,
                resume.season?.takeIf { episodeKey != null && it >= 0 }, resume.episode?.takeIf { episodeKey != null },
                null, position?.position ?: 0, position?.duration ?: 0,
                position != null && position.position * 100 >= position.duration * NEXT_WATCH_EPISODE_PERCENTAGE,
                updatedAt = resume.updateTime)
        }
        val playingIds = resumes.map { it.parentId }.toSet()
        val finished = DataStoreHelper.getFinishedWorks().filter { it.first !in playingIds }.mapNotNull { (id, at) ->
            val (request, header) = request(id) ?: return@mapNotNull null
            val position = DataStoreHelper.getViewPos(id)?.takeIf { movie(request) }
            SyncedWork(SyncedWork.keyOf(request), SyncedWork.shared(request), header.poster,
                positionMs = position?.position ?: 0, durationMs = position?.duration ?: 0, finished = true, updatedAt = at)
        }
        return (playing + finished).distinctBy { it.key }
    }

    override fun apply(state: SyncedWork): Boolean {
        // Pages open through a binding of an installed provider (HomeViewModel, CanonicalDetailStore.find).
        val binding = state.request.bindings.firstOrNull { getApiFromNameNull(it.provider.name) != null } ?: return false
        val request = CanonicalDetailStore.remember(state.request)
        val parentId = CanonicalDetail.pageId(request.identity)
        if (state.removed) {
            DataStoreHelper.removeLastWatched(parentId)
            return true
        }
        setKey(DOWNLOAD_HEADER_CACHE, parentId.toString(), DownloadObjects.DownloadHeaderCached(binding.provider.name,
            binding.url, type(request.kind), request.title, state.poster ?: header(parentId)?.poster, state.updatedAt, parentId))
        val episodeId = if (movie(request)) parentId else state.episodeKey?.let { CanonicalDetail.episodeId(request.identity, it) }
        if (episodeId != null) DataStoreHelper.setViewPos(episodeId, state.positionMs, state.durationMs)
        if (state.finished && (movie(request) || state.episodeKey == null)) {
            DataStoreHelper.removeLastWatched(parentId)
            DataStoreHelper.setFinished(parentId, state.updatedAt)
        } else {
            DataStoreHelper.setLastWatched(parentId, episodeId, state.episode, state.season, updateTime = state.updatedAt)
        }
        return true
    }

    private fun type(kind: MediaKind) = when (kind) {
        MediaKind.Movie -> TvType.Movie
        MediaKind.AnimeMovie -> TvType.AnimeMovie
        MediaKind.Series -> TvType.TvSeries
        MediaKind.Anime -> TvType.Anime
        MediaKind.Cartoon -> TvType.Cartoon
        MediaKind.AsianDrama -> TvType.AsianDrama
        MediaKind.Unknown -> TvType.Others
    }
}
