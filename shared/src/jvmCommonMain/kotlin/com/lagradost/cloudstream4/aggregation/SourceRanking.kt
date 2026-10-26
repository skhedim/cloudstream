package com.lagradost.cloudstream4.aggregation

/**
 * Auto source choice: language first, unless the quality gap is very large.
 *
 * Score = quality tier + version bonus. One tier is one resolution step (480p → 720p → 1080p).
 * VF is worth just under two tiers and VOST just under one, so:
 * - VF 480p beats unspecified 720p, but unspecified 1080p beats VF 480p (a two-step gap);
 * - VOST 480p loses to unspecified 1080p; VF 480p still beats VOST 720p.
 * Unreported quality sits between 480p and 720p: unverified, not presumed bad.
 */
object SourceRanking {
    const val VF_BONUS = 1.95
    const val VOST_BONUS = 0.9
    const val UNKNOWN_QUALITY_TIER = 1.5

    /** A source worth starting immediately: VF with a verified 1080p or better. */
    const val EXCELLENT_SCORE = VF_BONUS + 3.0

    /** Smallest gain that justifies switching an already playing stream (e.g. not 1080p → 1440p). */
    const val UPGRADE_MARGIN = 0.75

    fun qualityTier(height: Int): Double = when {
        height <= 0 -> UNKNOWN_QUALITY_TIER
        height >= 2160 -> 4.0
        height >= 1440 -> 3.5
        height >= 1080 -> 3.0
        height >= 720 -> 2.0
        height >= 480 -> 1.0
        height >= 360 -> 0.5
        else -> 0.0
    }

    fun versionBonus(version: SourceVersion): Double = when (version) {
        SourceVersion.VF -> VF_BONUS
        SourceVersion.VOST -> VOST_BONUS
        else -> 0.0
    }

    fun score(version: SourceVersion, height: Int): Double = qualityTier(height) + versionBonus(version)

    /** Stable order: score, then the user's quality-profile priority, resolution, binding order, name, URL. */
    fun <T> comparator(
        score: (T) -> Double,
        profilePriority: (T) -> Int,
        quality: (T) -> Int,
        ordinal: (T) -> Int,
        name: (T) -> String,
        url: (T) -> String,
    ): Comparator<T> = compareByDescending<T>(score).thenByDescending(profilePriority)
        .thenByDescending(quality).thenBy(ordinal).thenBy(name).thenBy(url)

    fun rank(sources: List<PlaybackSource>, profilePriority: (PlaybackSource) -> Int = { 0 }): List<PlaybackSource> =
        sources.sortedWith(comparator({ it.score }, profilePriority, { it.quality }, { it.metadata.ordinal },
            { it.link.name }, { it.link.url }))
}

/** When to start playback before every provider has answered, and when to switch afterwards. */
class PlaybackPolicy(
    /** Time given to better sources after the first playable one arrives. */
    val graceMs: Long = 2_500,
) {
    fun shouldStart(bestScore: Double?, msSinceFirstSource: Long, pending: Int): Boolean = when {
        bestScore == null -> false
        pending == 0 -> true
        bestScore >= SourceRanking.EXCELLENT_SCORE -> true
        else -> msSinceFirstSource >= graceMs
    }

    fun shouldUpgrade(currentScore: Double, bestScore: Double): Boolean =
        bestScore - currentScore >= SourceRanking.UPGRADE_MARGIN
}
