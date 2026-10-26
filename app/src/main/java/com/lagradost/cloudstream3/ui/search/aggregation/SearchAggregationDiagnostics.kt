package com.lagradost.cloudstream3.ui.search.aggregation

import com.lagradost.cloudstream4.aggregation.SearchMediaMetadataCache
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream4.domain.media.CanonicalMedia
import com.lagradost.cloudstream4.domain.media.MediaCandidate
import org.json.JSONObject
import java.io.File

/** Debug-only device evidence. Never export navigation payloads, request headers or API keys. */
internal object SearchAggregationDiagnostics {
    fun write(candidates: List<MediaCandidate>, cards: List<CanonicalMedia>) {
        val directory = CloudStreamApp.context?.cacheDir ?: return
        val indexes = candidates.mapIndexed { index, candidate -> candidate.binding to index }.toMap()
        val snapshot = mapOf(
            "candidateCount" to candidates.size,
            "cardCount" to cards.size,
            "candidates" to candidates.mapIndexed { index, candidate -> mapOf(
                "index" to index, "provider" to candidate.binding.provider.name,
                "title" to candidate.title, "year" to candidate.year, "kind" to candidate.kind.name,
                "editions" to candidate.edition.toList(), "artwork" to candidate.artworkIds.toList(),
                "externalIds" to candidate.externalIds.map { "${it.namespace}:${it.value}" },
                "visualSignatureAvailable" to (candidate.artworkSignature != null),
                "metadataCached" to (SearchMediaMetadataCache.get(candidate.binding) != null),
            ) },
            "cards" to cards.map { card -> mapOf(
                "id" to card.id, "title" to card.title, "year" to card.year,
                "bindings" to card.bindings.map { indexes[it] }, "reasons" to card.matchReasons.map { it.name },
            ) },
        )
        val pending = File(directory, "search-aggregation.pending.json")
        pending.writeText(JSONObject(snapshot).toString(2))
        pending.renameTo(File(directory, "search-aggregation.json"))
    }
}
