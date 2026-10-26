package com.lagradost.cloudstream3.ui.player.aggregation

import com.lagradost.cloudstream3.ui.result.aggregation.EpisodeSourceBinding
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkPlayList
import com.lagradost.cloudstream3.utils.Qualities
import java.util.WeakHashMap

enum class SourceVersion { Auto, VF, VOST, Unknown }

data class PlaybackSourceMetadata(val version: SourceVersion, val provider: String, val ordinal: Int)

/** App-owned copies preserve extension objects and playlist subtypes. No extension API changes. */
object PlaybackSources {
    private val metadata = WeakHashMap<ExtractorLink, PlaybackSourceMetadata>()
    private val vf = Regex("(?<![\\p{L}\\p{N}])(?:vf|vff|vfq|french)(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    private val vost = Regex("(?<![\\p{L}\\p{N}])(?:vostfr|vost|subbed|sub)(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)

    fun version(label: String, binding: EpisodeSourceBinding? = null): SourceVersion {
        val french = vf.containsMatchIn(label)
        val original = vost.containsMatchIn(label)
        if (french && original) return SourceVersion.Unknown
        if (french) return SourceVersion.VF
        if (original) return SourceVersion.VOST
        return when {
            binding?.track == "Subbed" -> SourceVersion.VOST
            binding?.track == "Dubbed" && binding.language?.substringBefore('-') == "fr" -> SourceVersion.VF
            else -> SourceVersion.Unknown
        }
    }

    @Synchronized fun metadata(link: ExtractorLink?): PlaybackSourceMetadata? = metadata[link]
    fun version(link: ExtractorLink?): SourceVersion = metadata(link)?.version
        ?: version(link?.name.orEmpty())

    @Suppress("DEPRECATION")
    fun copy(link: ExtractorLink, binding: EpisodeSourceBinding, ordinal: Int): ExtractorLink {
        val version = version(link.name, binding)
        val label = when (version) {
            SourceVersion.VF -> "VF"
            SourceVersion.VOST -> "VOST"
            else -> "Audio ?"
        }
        val name = "$label · ${link.name} · ${binding.apiName}"
        val copy = if (link is ExtractorLinkPlayList) link.copy(name = name,
            headers = link.headers.toMap(), playlist = link.playlist.toList(), audioTracks = link.audioTracks.toList())
        else ExtractorLink(link.source, name, link.url, link.referer, link.quality,
            link.headers.toMap(), link.extractorData, link.type, link.audioTracks.toList())
        synchronized(this) { metadata[copy] = PlaybackSourceMetadata(version, binding.apiName, ordinal) }
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
