package com.lagradost.cloudstream3.ui.result.aggregation

import com.lagradost.cloudstream3.CloudStreamApp
import org.json.JSONObject
import java.io.File

/** Debug evidence excludes URLs, episode payloads, headers and video links. */
internal object CanonicalDetailDiagnostics {
    fun write(detail: CanonicalDetail) {
        val directory = CloudStreamApp.context?.cacheDir ?: return
        val snapshot = mapOf(
            "title" to detail.request.title,
            "identity" to detail.response.uniqueUrl,
            "requestedBindings" to detail.requestedBindings,
            "loadedBindings" to detail.loadedBindings,
            "rejectedBindings" to detail.rejectedBindings,
            "partial" to detail.incomplete,
            "episodeCount" to detail.episodes.size,
            "numberedCoordinates" to detail.episodes.values.filter { it.season != null && it.episode != null }
                .map { it.season to it.episode }.distinct().size,
            "unlocatedCount" to if (detail.response is com.lagradost.cloudstream3.MovieLoadResponse) 0
                else detail.episodes.values.count { it.season == null || it.episode == null },
            "seasons" to detail.episodes.values.mapNotNull { it.season }.distinct().sorted(),
            "episodes" to detail.episodes.values.map { episode -> mapOf(
                "season" to episode.season, "episode" to episode.episode,
                "tracks" to episode.tracks.toList(), "bindings" to episode.bindings.size,
                "providers" to episode.bindings.map { it.media.provider.name },
            ) },
        )
        val pending = File(directory, "canonical-detail.pending.json")
        pending.writeText(JSONObject(snapshot).toString(2))
        pending.renameTo(File(directory, "canonical-detail.json"))
    }
}
