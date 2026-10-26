package com.lagradost.cloudstream4.aggregation

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkPlayList
import com.lagradost.cloudstream3.utils.Qualities
import java.util.WeakHashMap

enum class SourceVersion { Auto, VF, VOST, Unknown }

data class PlaybackSourceMetadata(
    val version: SourceVersion,
    val provider: String,
    val ordinal: Int,
    val providerId: String? = null,
    /** Set when the quality/version came from the stream manifest rather than the extension. */
    val probed: Boolean = false,
)

/** An app-owned playable copy of an extension link and what the engine knows about it. */
data class PlaybackSource(val link: ExtractorLink, val metadata: PlaybackSourceMetadata) {
    val version: SourceVersion get() = metadata.version
    val quality: Int get() = PlaybackSources.quality(link)
    val score: Double get() = SourceRanking.score(version, quality)
}

/**
 * App-owned copies preserve extension objects and playlist subtypes. No extension API changes.
 * Metadata travels in a weak sidecar keyed by the copy, because players handle plain
 * [ExtractorLink]s; copies carry the provider in their name, so equal links from two
 * providers never share an entry.
 */
object PlaybackSources {
    private val metadata = WeakHashMap<ExtractorLink, PlaybackSourceMetadata>()
    private val vf = Regex("(?<![\\p{L}\\p{N}])(?:vf|vff|vfq|french|truefrench)(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    private val vost = Regex("(?<![\\p{L}\\p{N}])(?:vostfr|vost|subbed|sub)(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)

    fun version(label: String, binding: EpisodeSourceBinding? = null): SourceVersion {
        val french = vf.containsMatchIn(label)
        val original = vost.containsMatchIn(label)
        if (french && original) return SourceVersion.Unknown
        if (french) return SourceVersion.VF
        if (original) return SourceVersion.VOST
        // A dub/sub track only proves VF/VOST(FR) on a French-language provider; elsewhere
        // "Subbed" is usually original audio with English subtitles.
        val frenchProvider = binding?.language?.substringBefore('-') == "fr"
        return when {
            binding?.track == "Subbed" && frenchProvider -> SourceVersion.VOST
            binding?.track == "Dubbed" && frenchProvider -> SourceVersion.VF
            else -> SourceVersion.Unknown
        }
    }

    @Synchronized fun metadata(link: ExtractorLink?): PlaybackSourceMetadata? = link?.let { metadata[it] }
    fun version(link: ExtractorLink?): SourceVersion = metadata(link)?.version
        ?: version(link?.name.orEmpty())

    /**
     * @param probe manifest evidence; it only fills what the extension left unknown.
     */
    @Suppress("DEPRECATION")
    fun copy(link: ExtractorLink, binding: EpisodeSourceBinding, ordinal: Int, probe: StreamProbe? = null): ExtractorLink {
        val labelled = version(link.name, binding)
        val version = if (labelled == SourceVersion.Unknown) probe?.version ?: labelled else labelled
        val quality = if (quality(link) == 0) probe?.height ?: link.quality else link.quality
        val label = when (version) {
            SourceVersion.VF -> "VF"
            SourceVersion.VOST -> "VOST"
            else -> "Audio ?"
        }
        val name = "$label · ${link.name} · ${binding.apiName}"
        val copy = if (link is ExtractorLinkPlayList) link.copy(name = name, quality = quality,
            headers = link.headers.toMap(), playlist = link.playlist.toList(), audioTracks = link.audioTracks.toList())
        else ExtractorLink(link.source, name, link.url, link.referer, quality,
            link.headers.toMap(), link.extractorData, link.type, link.audioTracks.toList())
        val probed = probe != null && (version != labelled || quality != link.quality)
        synchronized(this) {
            metadata[copy] = PlaybackSourceMetadata(version, binding.apiName, ordinal, binding.providerId, probed)
        }
        return copy
    }

    /** Unknown/adaptive quality is never presented as a verified resolution. */
    fun quality(link: ExtractorLink?): Int = link?.quality?.takeIf { it != Qualities.Unknown.value && it in 1..4320 } ?: 0

    fun versionRank(version: SourceVersion): Int = when (version) {
        SourceVersion.VF -> 3
        SourceVersion.VOST -> 2
        SourceVersion.Unknown -> 1
        SourceVersion.Auto -> 0
    }

    /** Include request context and external audio: identical URLs may require different credentials. */
    fun key(link: ExtractorLink): List<Any?> = listOf(version(link), link.url, link.type, link.referer,
        link.headers.entries.sortedBy { it.key.lowercase() }.map { it.key.lowercase() to it.value },
        link.audioTracks, (link as? ExtractorLinkPlayList)?.playlist, link.extractorData)
}
