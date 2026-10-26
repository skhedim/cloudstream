package com.lagradost.cloudstream3.ui.player.aggregation

import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import org.json.JSONObject
import java.io.File

/** Local Debug evidence only. Never expose video URLs, payloads, credentials or request headers. */
internal object CanonicalSourceDiagnostics {
    fun write(episode: ResultEpisode, sources: CanonicalSources) {
        val directory = CloudStreamApp.context?.cacheDir ?: return
        val data = mapOf("title" to episode.headerName, "season" to episode.season,
            "episode" to episode.episode, "episodeId" to episode.id,
            "bindings" to episode.sourceBindings.size, "links" to sources.links.size,
            "subtitles" to sources.subtitles.size,
            "versions" to sources.links.groupingBy { PlaybackSources.version(it).name }.eachCount(),
            "qualities" to sources.links.groupingBy { PlaybackSources.quality(it).toString() }.eachCount(),
            "outcomes" to sources.outcomes.map { mapOf("ordinal" to it.ordinal, "provider" to it.provider,
                "track" to it.track, "status" to it.status, "links" to it.links, "elapsedMs" to it.elapsedMs) })
        val pending = File(directory, "canonical-sources.pending.json")
        pending.writeText(JSONObject(data).toString(2))
        pending.renameTo(File(directory, "canonical-sources.json"))
    }
}
