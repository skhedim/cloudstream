package com.lagradost.cloudstream3.ui.player.aggregation

import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream4.aggregation.ExternalSubtitles
import com.lagradost.cloudstream4.aggregation.PlaybackSources
import com.lagradost.cloudstream4.aggregation.SourceVersion

/**
 * Subtitle auto-selection for canonical sources, whatever the language remembered for other
 * extensions: French subtitles go with original audio only (a VO source, or one that is VOST
 * through subtitles fetched for it, those first). VF and VOST streams carry what they need.
 */
object CanonicalSubtitles {
    class Choice(val subtitle: SubtitleData?)

    /** Null when [link] is not a canonical source: the player's own rules apply. */
    fun autoSelect(link: ExtractorLink?, subtitles: Collection<SubtitleData>): Choice? {
        val metadata = PlaybackSources.metadata(link) ?: return null
        if (!metadata.addedSubtitles && metadata.version != SourceVersion.VO) return Choice(null)
        val french = subtitles.filter { it.matchesLanguageCode("fr") }
        return Choice(french.firstOrNull { it.originalName.startsWith(ExternalSubtitles.LABEL) } ?: french.firstOrNull())
    }
}
