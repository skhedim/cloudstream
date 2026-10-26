package com.lagradost.cloudstream4.domain.media

data class EpisodeNumbers(val season: Int?, val episode: Int)

/** Read explicit numbering labels, never list order, bare numbers or arbitrary episode titles. */
object EpisodeNumbering {
    private const val languageSuffix = "(?:\\s+(?:VF|VOSTFR|VOST|DUB|SUB))?(?:\\s*(?:📜|🇫🇷|🇬🇧))*"
    private val seasonEpisode = Regex("^s(\\d{1,3})\\s*e(\\d{1,4})$languageSuffix(?:\\s*[-:–—.]\\s*.*)?$", RegexOption.IGNORE_CASE)
    private val episodeOnly = Regex("^(?:épisode|episode|ep\\.?|e)\\s*(\\d{1,4})$languageSuffix(?:\\s*[-:–—.]\\s*.*)?$", RegexOption.IGNORE_CASE)

    fun fromLabel(label: String?): EpisodeNumbers? {
        val text = label?.trim() ?: return null
        seasonEpisode.matchEntire(text)?.let {
            val number = it.groupValues[2].toIntOrNull()?.takeIf { n -> n > 0 } ?: return null
            return EpisodeNumbers(it.groupValues[1].toInt(), number)
        }
        val match = episodeOnly.matchEntire(text) ?: return null
        val number = match.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        return EpisodeNumbers(null, number)
    }
}
