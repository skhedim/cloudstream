package com.lagradost.cloudstream4.domain.media

import kotlin.math.abs

/** Small RGB raster for nearly identical encodings of the same poster, not semantic image search. */
data class ArtworkSignature(val samples: List<Int>, val aspectRatio: Double) {
    init {
        require(samples.size == WIDTH * HEIGHT * 3 && samples.all { it in 0..255 })
        require(aspectRatio.isFinite() && aspectRatio > 0)
    }
    private val distinctive: Boolean = samples.chunked(3).map { (r,g,b) -> (r*299+g*587+b*114)/1000 }
        .let { it.max()-it.min() >= 48 }

    fun equivalent(other: ArtworkSignature): Boolean = distinctive && other.distinctive &&
        abs(aspectRatio-other.aspectRatio) <= 0.03 &&
        samples.indices.sumOf { abs(samples[it]-other.samples[it]) } <= samples.size*5

    companion object { const val WIDTH = 16; const val HEIGHT = 24 }
}

/** Query-local budget; only compare different posters on unresolved same-title/type/edition cards. */
class VisualArtworkPlanner(private val maxImages: Int = 6) {
    private val attempted = mutableSetOf<String>()
    fun next(candidates: List<MediaCandidate>, cards: List<CanonicalMedia>): List<String> {
        val cardIds = cards.flatMap { card -> card.bindings.map { it to card.id } }.toMap()
        val groups = candidates.flatMap { candidate ->
            val family = MediaAggregator.family(candidate.kind) ?: return@flatMap emptyList()
            MediaAggregator.titles(candidate).map { Triple(it,family,candidate.edition) to candidate }
        }.groupBy({ it.first },{ it.second }).values
        return groups.filter { group ->
            group.any { it.year == null && it.externalIds.isEmpty() } &&
                group.flatMap { it.artworkIds }.distinct().size > 1 &&
                group.mapNotNull { cardIds[it.binding] }.distinct().size > 1
        }
            .flatten().flatMap { it.artworkIds }.distinct()
            .filter { it !in attempted }.take((maxImages-attempted.size).coerceAtLeast(0))
            .also { attempted.addAll(it) }
    }
}
