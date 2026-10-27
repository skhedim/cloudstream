package com.lagradost.cloudstream4.aggregation

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkPlayList
import com.lagradost.cloudstream3.utils.Qualities
import java.util.WeakHashMap

/**
 * Audio/subtitle version as far as evidence goes, for a French-speaking viewer:
 * - [VF] French audio, [VOST] French subtitles (labels, French-provider tracks, manifests);
 * - [Unknown] a provider in the viewer's language that does not say (usually French audio);
 * - [VO] a provider in another language that does not say: original audio, no French promise.
 */
enum class SourceVersion { Auto, VF, VOST, Unknown, VO }

/** How much the engine checked a stream before offering it. */
enum class SourceCheck {
    /** Reachable and its content length fits the work (HLS duration or file size). */
    Verified,
    /** Reachable, but nothing about its content could be measured. */
    Alive,
    /** Not inspected (budget, unsupported type, inspection timeout). */
    Unchecked,
}

data class PlaybackSourceMetadata(
    val version: SourceVersion,
    val provider: String,
    val ordinal: Int,
    val providerId: String? = null,
    /** Set when the quality/version came from the stream manifest rather than the extension. */
    val probed: Boolean = false,
    val check: SourceCheck = SourceCheck.Unchecked,
    /** Resolution read from the manifest, when there is one. */
    val verifiedHeight: Int? = null,
    val durationSeconds: Double? = null,
    /** Original audio made VOST by French subtitles fetched for it ([ExternalSubtitles]): select them. */
    val addedSubtitles: Boolean = false,
)

/** An app-owned playable copy of an extension link and what the engine knows about it. */
data class PlaybackSource(val link: ExtractorLink, val metadata: PlaybackSourceMetadata) {
    val version: SourceVersion get() = metadata.version
    val quality: Int get() = PlaybackSources.quality(link)
    val score: Double get() = SourceRanking.score(metadata, quality)
}

/**
 * App-owned copies preserve extension objects and playlist subtypes. No extension API changes.
 * Metadata travels in a weak sidecar keyed by the copy, because players handle plain
 * [ExtractorLink]s; copies carry the provider in their name, so equal links from two
 * providers never share an entry.
 */
object PlaybackSources {
    private val metadata = WeakHashMap<ExtractorLink, PlaybackSourceMetadata>()
    private val vf = Regex("(?<![\\p{L}\\p{N}])(?:vf|vff|vfq|vfi|french|truefrench|français|francais)(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    private val vostfr = Regex("(?<![\\p{L}\\p{N}])(?:vostfr|vost)(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    private val subbed = Regex("(?<![\\p{L}\\p{N}])(?:subbed|sub)(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    /** French release convention: MULTi carries the French track plus the original one. */
    private val multi = Regex("(?<![\\p{L}\\p{N}])multi(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)

    /** The viewer's language, e.g. `fr`. */
    fun viewerLanguage(): String = AggregationPlatform.metadataLanguage().substringBefore('-').lowercase()

    private fun nativeProvider(binding: EpisodeSourceBinding?): Boolean? {
        val language = binding?.language?.substringBefore('-')?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        if (language == "multi" || language == "mul" || language == "all") return true
        return language == viewerLanguage()
    }

    fun version(label: String, binding: EpisodeSourceBinding? = null): SourceVersion {
        val french = vf.containsMatchIn(label)
        val original = vostfr.containsMatchIn(label)
        val native = nativeProvider(binding)
        if (french && original) return if (native == false) SourceVersion.VO else SourceVersion.Unknown
        if (french) return SourceVersion.VF
        if (original) return SourceVersion.VOST
        // Generic dub/sub markers only prove VF/VOST(FR) on a provider in the viewer's language;
        // elsewhere "Subbed" is original audio with subtitles in that provider's language.
        return when {
            native == true && multi.containsMatchIn(label) -> SourceVersion.VF
            native == true && (binding?.track == "Subbed" || subbed.containsMatchIn(label)) -> SourceVersion.VOST
            native == true && binding?.track == "Dubbed" -> SourceVersion.VF
            native == false -> SourceVersion.VO
            else -> SourceVersion.Unknown
        }
    }

    @Synchronized fun metadata(link: ExtractorLink?): PlaybackSourceMetadata? = link?.let { metadata[it] }
    fun version(link: ExtractorLink?): SourceVersion = metadata(link)?.version
        ?: version(link?.name.orEmpty())

    /**
     * @param inspection measurements of the stream; they only fill what the extension left unknown.
     * @param subtitled French subtitles were found for the work: original audio becomes VOST.
     */
    @Suppress("DEPRECATION")
    fun copy(
        link: ExtractorLink, binding: EpisodeSourceBinding, ordinal: Int,
        probe: StreamProbe? = null, check: SourceCheck = SourceCheck.Unchecked, durationSeconds: Double? = null,
        subtitled: Boolean = false,
    ): ExtractorLink {
        val labelled = version(link.name, binding)
        val probedVersion = probe?.version
        val measured = when {
            probedVersion == null -> labelled
            labelled == SourceVersion.Unknown || labelled == SourceVersion.VO -> probedVersion
            else -> labelled
        }
        val addedSubtitles = subtitled && measured == SourceVersion.VO
        val version = if (addedSubtitles) SourceVersion.VOST else measured
        val verifiedHeight = probe?.height
        val quality = verifiedHeight ?: link.quality
        val label = when (version) {
            SourceVersion.VF -> "VF"
            SourceVersion.VOST -> "VOST"
            SourceVersion.VO -> "VO"
            else -> "Audio ?"
        }
        // The viewer sees versions and hosts, never which extension found the link.
        val name = "$label · ${link.name}"
        val copy = if (link is ExtractorLinkPlayList) link.copy(name = name, quality = quality,
            headers = link.headers.toMap(), playlist = link.playlist.toList(), audioTracks = link.audioTracks.toList())
        else ExtractorLink(link.source, name, link.url, link.referer, quality,
            link.headers.toMap(), link.extractorData, link.type, link.audioTracks.toList())
        val probed = probe != null && (version != labelled || quality != link.quality)
        synchronized(this) {
            metadata[copy] = PlaybackSourceMetadata(version, binding.apiName, ordinal, binding.providerId, probed,
                check, verifiedHeight, durationSeconds, addedSubtitles)
        }
        return copy
    }

    /** Unknown/adaptive quality is never presented as a verified resolution. */
    fun quality(link: ExtractorLink?): Int = link?.quality?.takeIf { it != Qualities.Unknown.value && it in 1..4320 } ?: 0

    fun versionRank(version: SourceVersion): Int = when (version) {
        SourceVersion.VF -> 4
        SourceVersion.VOST -> 3
        SourceVersion.Unknown -> 2
        SourceVersion.VO -> 1
        SourceVersion.Auto -> 0
    }

    /** Include request context and external audio: identical URLs may require different credentials. */
    fun key(link: ExtractorLink): List<Any?> = listOf(version(link), link.url, link.type, link.referer,
        link.headers.entries.sortedBy { it.key.lowercase() }.map { it.key.lowercase() to it.value },
        link.audioTracks, (link as? ExtractorLinkPlayList)?.playlist, link.extractorData)
}
