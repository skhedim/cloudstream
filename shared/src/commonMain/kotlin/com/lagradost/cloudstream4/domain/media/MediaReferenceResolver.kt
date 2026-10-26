package com.lagradost.cloudstream4.domain.media

/** Metadata only: a reference never creates a catalogue card without an actual provider binding. */
data class MediaReference(
    val id: ExternalMediaId,
    val title: String,
    val alternateTitles: Set<String>,
    val year: Int?,
    val kind: MediaKind,
    val artworkIds: Set<String>,
)

object MediaReferenceResolver {
    fun enrich(candidate: MediaCandidate, references: List<MediaReference>): MediaCandidate {
        val compatible = references.filter { ref ->
            candidate.externalIds.none {
                (it.namespace == ref.id.namespace && it != ref.id) ||
                    (it.namespace == ExternalIdNamespace.TmdbMovie && ref.id.namespace == ExternalIdNamespace.TmdbTv) ||
                    (it.namespace == ExternalIdNamespace.TmdbTv && ref.id.namespace == ExternalIdNamespace.TmdbMovie)
            }
        }
        val direct = compatible.filter { it.id in candidate.externalIds }
        // An exact year is preferred, so a neighbouring-year homonym cannot make the reference ambiguous.
        val supported = compatible.filter { supports(candidate,it) }
        val matches = direct.ifEmpty { supported.filter { it.year == candidate.year }.ifEmpty { supported } }
        val ref = matches.distinctBy { it.id }.singleOrNull() ?: return candidate
        return candidate.copy(
            title = if (candidate.edition.all { it.startsWith("season:") }) ref.title else candidate.title,
            year = ref.year ?: candidate.year,
            kind = ref.kind,
            alternateTitles = candidate.alternateTitles + candidate.title + ref.alternateTitles + ref.title,
            externalIds = candidate.externalIds + ref.id,
        )
    }

    fun sharesTitle(candidate: MediaCandidate, ref: MediaReference): Boolean =
        MediaAggregator.titles(candidate).intersect(
            (ref.alternateTitles + ref.title).map(MediaAggregator::matchKey).toSet()
        ).isNotEmpty()

    private fun supports(candidate: MediaCandidate, ref: MediaReference): Boolean {
        if (!sharesTitle(candidate,ref)) return false
        val nearYear = candidate.year != null && ref.year != null &&
            kotlin.math.abs(candidate.year - ref.year) <= MediaAggregator.YEAR_TOLERANCE
        if (candidate.year != null && ref.year != null && !nearYear) return false
        val artwork = candidate.artworkIds.intersect(ref.artworkIds).isNotEmpty()
        // A verified poster can correct an extension's erroneous Movie/TvSeries classification.
        if (artwork) return true
        return MediaAggregator.family(candidate.kind) != null &&
            MediaAggregator.family(candidate.kind) == MediaAggregator.family(ref.kind) &&
            nearYear
    }
}
