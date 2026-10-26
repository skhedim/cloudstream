package com.lagradost.cloudstream4.aggregation

import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlin.math.abs

/**
 * Detects a stream that is not the requested work (dead link replaced by an advert loop, a
 * mislabelled upload) from facts the player reports, without inspecting the content.
 */
object PlaybackVerification {
    /** Another source of the same work that was playing: different cuts and intros fit in 10 % / 3 min. */
    fun sameWork(actualMs: Long, playingMs: Long): Boolean =
        actualMs <= 0 || playingMs <= 0 || abs(actualMs - playingMs) <= maxOf(playingMs / 10, 180_000L)

    /** A movie's catalogue runtime is approximate (credits, versions): 30 % / 10 min. */
    fun matchesRuntime(actualMs: Long, runtimeMinutes: Int?): Boolean {
        val expected = (runtimeMinutes ?: return true).takeIf { it in 20..400 }?.let { it * 60_000L } ?: return true
        return actualMs <= 0 || abs(actualMs - expected) <= maxOf(expected * 3 / 10, 600_000L)
    }

    /** A source counts as played once this much of it has been watched with a plausible duration. */
    const val CONFIRMED_AFTER_MS = 20_000L

    /** Ranking score of a published link: version, believable quality, check level, provider record. */
    fun score(link: ExtractorLink?, preference: SourceVersion = SourceVersion.Auto): Double {
        val metadata = PlaybackSources.metadata(link)
            ?: return SourceRanking.score(PlaybackSources.version(link), SourceRanking.trustedHeight(PlaybackSources.quality(link), null), preference)
        return SourceRanking.score(metadata, PlaybackSources.quality(link), preference)
    }
}
