package com.lagradost.cloudstream4.aggregation

/**
 * Auto source choice for a French-speaking viewer.
 *
 * Score = quality tier + version bonus + check bonus − provider playback penalty.
 * - One tier is one resolution step (480p 1, 720p 2, 1080p 3, 2160p 4). VF is worth just under
 *   two steps and VOST just under one, so language wins unless the quality gap is very large
 *   (unspecified 1080p beats VF 480p; VF 720p beats unspecified 1080p).
 * - [SourceVersion.VO] (a foreign provider that does not say) is a fallback only: it never beats
 *   a source in the viewer's language.
 * - Declared resolutions above 1080p are only believed when the manifest confirms them: fake
 *   "4K" labels are typical of advert/scam links. Unreported quality sits between 480p and 720p.
 * - Measured sources come first: a verified stream beats an equal unchecked one.
 */
object SourceRanking {
    const val VF_BONUS = 1.95
    const val VOST_BONUS = 0.9
    const val VO_PENALTY = -10.0
    const val UNKNOWN_QUALITY_TIER = 1.5

    /** Bonus of a remembered (not just chosen) version: worth 2.5 steps, so only a huge gap overrides it. */
    const val PREFERRED_BONUS = 2.5

    /** A source worth starting immediately: verified VF of at least 720p. */
    const val EXCELLENT_SCORE = VF_BONUS + 2.0

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
        SourceVersion.VO -> VO_PENALTY
        else -> 0.0
    }

    fun checkBonus(check: SourceCheck): Double = when (check) {
        SourceCheck.Verified -> 0.0
        SourceCheck.Alive -> -0.3
        SourceCheck.Unchecked -> -0.6
    }

    /** Resolution the engine believes: measured, else declared up to 1080p. */
    fun trustedHeight(declared: Int, verified: Int?): Int = verified ?: declared.coerceAtMost(1080)

    /**
     * @param preference a remembered version choice: that version gets [PREFERRED_BONUS] and the
     * others half their default bonus ([SourceVersion.VO] stays a fallback). Auto keeps the default order.
     */
    fun score(version: SourceVersion, height: Int, preference: SourceVersion = SourceVersion.Auto): Double =
        qualityTier(height) + when {
            preference == SourceVersion.Auto || version == SourceVersion.VO -> versionBonus(version)
            version == preference -> PREFERRED_BONUS
            else -> versionBonus(version) / 2
        }

    fun score(metadata: PlaybackSourceMetadata, declaredHeight: Int = 0, preference: SourceVersion = SourceVersion.Auto): Double =
        score(metadata.version, trustedHeight(declaredHeight, metadata.verifiedHeight), preference) +
            checkBonus(metadata.check) - ProviderHealth.playbackPenalty(metadata.providerId ?: metadata.provider)

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
    /** After this long, the best checked source in the viewer's language starts. */
    val maxWaitMs: Long = 8_000,
    /** A verified 720p+ source in the viewer's language starts after this, leaving time for a VF. */
    val goodSourceWaitMs: Long = 4_000,
) {
    /**
     * @param best the best published source so far (published sources passed their checks)
     * @param nativePending bindings in the viewer's language still loading or being checked
     */
    fun shouldStart(best: PlaybackSourceMetadata?, bestScore: Double?, elapsedMs: Long, nativePending: Int, pending: Int,
        bestHeight: Int = best?.verifiedHeight ?: 0): Boolean {
        best ?: return false
        bestScore ?: return false
        if (pending == 0) return true
        val native = best.version != SourceVersion.VO
        return when {
            best.check == SourceCheck.Verified && best.version == SourceVersion.VF && bestScore >= SourceRanking.EXCELLENT_SCORE -> true
            native && nativePending == 0 && best.check != SourceCheck.Unchecked -> true
            native && best.check == SourceCheck.Verified && elapsedMs >= goodSourceWaitMs &&
                bestHeight >= 720 -> true
            native && elapsedMs >= maxWaitMs -> true
            else -> false
        }
    }

    fun shouldUpgrade(currentScore: Double, bestScore: Double): Boolean =
        bestScore - currentScore >= SourceRanking.UPGRADE_MARGIN
}
