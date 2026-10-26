package com.lagradost.cloudstream3.ui.player.aggregation

import com.lagradost.cloudstream4.aggregation.PlaybackSources
import com.lagradost.cloudstream4.aggregation.CanonicalSources
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
            "subtitles" to sources.subtitles.size, "readyAfterMs" to sources.readyAfterMs,
            "probed" to sources.sources.count { it.metadata.probed },
            "versions" to sources.links.groupingBy { PlaybackSources.version(it).name }.eachCount(),
            "qualities" to sources.links.groupingBy { PlaybackSources.quality(it).toString() }.eachCount(),
            "outcomes" to sources.outcomes.map { mapOf("ordinal" to it.ordinal, "provider" to it.provider,
                "track" to it.track, "status" to it.status, "links" to it.links, "elapsedMs" to it.elapsedMs) })
        val pending = File(directory, "canonical-sources.pending.json")
        pending.writeText(JSONObject(data).toString(2))
        pending.renameTo(File(directory, "canonical-sources.json"))
    }

    private val selections = mutableListOf<Map<String, Any?>>()
    private var loadStartedAt = 0L

    fun loadStarted() = synchronized(selections) { selections.clear(); loadStartedAt = System.currentTimeMillis() }

    /** Which source Auto played and when; no URL, header or payload. */
    fun selection(event: String, link: com.lagradost.cloudstream3.utils.ExtractorLink?, linkCount: Int,
        ranking: List<com.lagradost.cloudstream3.ui.player.DisplayLink> = emptyList(), note: String? = null) {
        val directory = CloudStreamApp.context?.cacheDir ?: return
        val metadata = PlaybackSources.metadata(link)
        val snapshot = synchronized(selections) {
            selections += mapOf("event" to event, "afterMs" to System.currentTimeMillis() - loadStartedAt,
                "provider" to metadata?.provider, "version" to PlaybackSources.version(link).name,
                "quality" to PlaybackSources.quality(link), "probed" to metadata?.probed,
                "score" to com.lagradost.cloudstream4.aggregation.SourceRanking.score(
                    PlaybackSources.version(link), PlaybackSources.quality(link)),
                "availableLinks" to linkCount, "note" to note,
                "ranking" to ranking.take(8).map { display -> val l = display.link.first; mapOf(
                    "provider" to PlaybackSources.metadata(l)?.provider, "version" to PlaybackSources.version(l).name,
                    "quality" to PlaybackSources.quality(l), "priority" to display.priority, "use" to display.shouldUseLink,
                    "type" to l?.type?.name) })
            selections.toList()
        }
        runCatching { File(directory, "canonical-playback.json").writeText(org.json.JSONArray(snapshot.map { JSONObject(it) }).toString(2)) }
    }
}
