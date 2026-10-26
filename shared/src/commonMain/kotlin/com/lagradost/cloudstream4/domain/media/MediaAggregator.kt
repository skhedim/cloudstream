package com.lagradost.cloudstream4.domain.media

/** Pure matching over snapshots. Keep one instance per query to retain card identities on paging. */
class MediaAggregator {
    private val identities = mutableMapOf<ProviderBinding, Long>()
    private var nextIdentity = 0L

    fun aggregate(candidates: List<MediaCandidate>): List<CanonicalMedia> {
        val indexed = candidates.distinctBy { it.binding }.withIndex().toList()
        val groups = mutableListOf<Group>()
        val byId = mutableMapOf<ExternalMediaId, MutableSet<Group>>()
        val byTitle = mutableMapOf<TitleKey, MutableSet<Group>>()

        // Process anchors first. An incomplete result must see *all* remakes/conflicting IDs,
        // irrespective of provider completion/rank order, before it can join a group.
        val evidenceOrder = indexed.sortedWith(
            compareByDescending<IndexedValue<MediaCandidate>> { it.value.externalIds.isNotEmpty() }
                .thenByDescending { it.value.year != null }
                .thenBy { it.index }
        )
        for ((index, candidate) in evidenceOrder) {
            val titles = titles(candidate)
            val family = family(candidate.kind)
            val keys = if (family != null) titles.map { TitleKey(it, family, candidate.edition) } else emptyList()
            val possible = buildSet {
                candidate.externalIds.forEach { addAll(byId[it].orEmpty()) }
                keys.forEach { addAll(byTitle[it].orEmpty()) }
            }
            val matches = possible.mapNotNull { group ->
                reason(group, candidate, titles)?.let { group to it }
            }
            // A trusted ID wins over title evidence, and an exact year over a neighbouring one.
            // Never choose arbitrarily between works.
            val match = matches.filter { it.second == MatchReason.ExternalId }
                .ifEmpty { matches.filter { it.second == MatchReason.TitleYearType } }
                .ifEmpty { matches }.singleOrNull()
            val group = match?.first ?: Group().also(groups::add)
            group.members.add(index to candidate)
            group.titles.addAll(titles)
            match?.second?.let(group.reasons::add)
            candidate.externalIds.forEach { byId.getOrPut(it) { mutableSetOf() }.add(group) }
            keys.forEach { byTitle.getOrPut(it) { mutableSetOf() }.add(group) }
        }

        val usedIdentities = mutableSetOf<Long>()
        // Evidence order never changes the user's provider/rank interleaving.
        return groups.sortedBy { it.members.minOf { member -> member.first } }.map { group ->
            val members = group.members.sortedBy { it.first }.map { it.second }
            val first = members.firstOrNull { it.edition.isEmpty() } ?: members.first()
            val id = members.mapNotNull { identities[it.binding] }.filter { it !in usedIdentities }.minOrNull()
                ?: ++nextIdentity
            usedIdentities.add(id)
            members.forEach { identities[it.binding] = id }
            CanonicalMedia(
                id, first.title, members.firstNotNullOfOrNull { it.year }, first.kind,
                members.flatMap { it.externalIds }.toSet(),
                members.map { it.binding }, group.reasons.toSet(),
                members.associate { it.binding to it.edition },
                members.flatMap { it.alternateTitles + it.title }.filter { it.isNotBlank() }.toSet(),
            )
        }
    }

    private fun reason(group: Group, candidate: MediaCandidate, titles: Set<String>): MatchReason? {
        val members = group.members.map { it.second }
        val ids = (members.flatMap { it.externalIds } + candidate.externalIds).groupBy { it.namespace }
        if (ExternalIdNamespace.TmdbMovie in ids && ExternalIdNamespace.TmdbTv in ids) return null
        if (ids.values.any { sameNamespace -> sameNamespace.map { it.value }.distinct().size > 1 }) return null
        val sharedId = members.any { member -> member.externalIds.any { it in candidate.externalIds } }
        val candidateFamily = family(candidate.kind)
        if (members.any { family(it.kind) != candidateFamily }) return null
        val editionMismatch = members.any { it.edition != candidate.edition }
        val sameEdition = members.filter { it.edition == candidate.edition }
        // Season-scoped entries can join a whole series only with the same trusted show ID.
        // Parts/cours often denote different anime works and remain independent.
        if (editionMismatch && sameEdition.isEmpty() && !(sharedId && candidateFamily == "series" &&
                    (members.map { it.edition } + listOf(candidate.edition)).all { edition ->
                        edition.all { it.startsWith("season:") }
                    })) return null
        if (sharedId) return MatchReason.ExternalId
        // A group containing proven season bindings must still admit a missing-ID whole-show
        // result using evidence from its whole-show members, without lending season evidence.
        if (candidateFamily == null || titles.intersect(sameEdition.flatMap { MediaAggregator.titles(it) }.toSet()).isEmpty()) return null

        val years = (sameEdition.mapNotNull { it.year } + listOfNotNull(candidate.year)).toSet()
        // Catalogues disagree by one year (local release, first air date); a wider spread is another work.
        if (years.isNotEmpty() && years.max() - years.min() > YEAR_TOLERANCE) return null
        // A known year cannot lend its identity to an otherwise unsupported missing-year result.
        if (candidate.year != null && sameEdition.any { it.year == candidate.year }) return MatchReason.TitleYearType
        if (candidate.year != null && sameEdition.any { it.year != null }) return MatchReason.TitleNearYearType
        // A metadata-service poster plus the same title/type is independent corroboration when
        // the provider omitted years. No title-only or image-only merge, including for homonyms.
        if (sameEdition.any { it.artworkIds.intersect(candidate.artworkIds).isNotEmpty() }) return MatchReason.TitleArtworkType
        return if (candidate.artworkSignature?.let { signature ->
            sameEdition.any { it.artworkSignature?.equivalent(signature) == true }
        } == true) MatchReason.TitleVisualArtworkType else null
    }

    private class Group {
        val members = mutableListOf<Pair<Int, MediaCandidate>>()
        val titles = mutableSetOf<String>()
        val reasons = mutableSetOf<MatchReason>()
    }

    private data class TitleKey(val title: String, val family: String, val edition: Set<String>)

    companion object {
        const val YEAR_TOLERANCE = 1

        /** Display/identity normalization: preserve accents, numbers and subtitles; punctuation/case are presentation differences. */
        private val punctuation = Regex("[’‘'\".:,!?…/\\\\()\\[\\]–—_-]+")
        private val whitespace = Regex("\\s+")
        fun normalizeTitle(title: String): String = title.trim().lowercase()
            .replace(punctuation, " ").replace(whitespace, " ").trim()

        /**
         * Matching key: [normalizeTitle] plus differences that never distinguish two works on their own
         * once family, year and edition also agree: accents, `&`/and/et, Roman sequel numbers (II–IX).
         */
        fun matchKey(title: String): String = normalizeTitle(TitleFolding.foldAccents(title).replace("&", " & "))
            .split(' ').joinToString(" ") { word -> TitleFolding.roman[word] ?: if (word == "and" || word == "et") "&" else word }

        fun titles(candidate: MediaCandidate): Set<String> =
            (candidate.alternateTitles + candidate.title).map(::matchKey).filter { it.isNotBlank() }.toSet()

        fun family(kind: MediaKind): String? = when (kind) {
            MediaKind.Movie, MediaKind.AnimeMovie -> "movie"
            MediaKind.Series, MediaKind.Anime, MediaKind.Cartoon, MediaKind.AsianDrama -> "series"
            MediaKind.Unknown -> null
        }
    }
}
