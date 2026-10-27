package com.lagradost.cloudstream4.aggregation

/** What a reviewed extension brings to a French-speaking viewer. */
enum class ExtensionRole {
    /** A French catalogue: home rows, search and sources. */
    Catalogue,
    /**
     * Original-version films and series: the titles French catalogues lack, and VO tracks (VOST
     * with French subtitles) where they only have VF. Searched and played, never on the home.
     */
    OriginalVersion,
    /** Anime, often with French subtitles in the stream. Searched and played, never on the home. */
    Anime,
}

/**
 * An extension the provider survey measured worth installing by default (docs/catalog-setup.md).
 * Android and the desktop app install the same list.
 * @param apis the providers the app uses when the extension registers several (null: all of them)
 * @param preferences extension settings written before its first load
 */
data class ReviewedExtension(
    val key: String,
    val internalName: String,
    val repository: String,
    val role: ExtensionRole,
    val apis: Set<String>? = null,
    val preferences: Map<String, Any> = emptyMap(),
) {
    /** The repository's owner and name, letters and digits only: found in its plugin and file URLs. */
    val origin: String get() = ReviewedExtensions.origin(repository)
}

object ReviewedExtensions {
    private const val KARMA = "https://raw.githubusercontent.com/Kraptor123/cs-Karma/refs/heads/master/repo.json"
    private const val YORIK = "https://raw.githubusercontent.com/yorik100/Cloudstream/refs/heads/builds/repo.json"
    private const val AMSC = "https://raw.githubusercontent.com/mouradchaouche/cloudstream-frenchrepo/main/repo.json"
    private const val RAGHAV = "https://raw.githubusercontent.com/KSHITIJ8473/raghav/builds/repo.json"

    /**
     * - French catalogues (survey of 2026-10-26): Movix plays every title in 720p+ and covers anime
     *   through Anime-Sama; Frembed and Xalaflix are backups; the French Frembed feeds the home rows.
     * - Complements (survey of 2026-10-27, 17 films and series, 10 anime): Movix played all 27, but
     *   rarely in VOST. KDesa and Movy play 16 of the 17 in original version, 12 with French
     *   subtitles; AniPM plays all 10 anime with French subtitles, Anv backs it up (10, 5). Both
     *   give the year in search results, without which a title is never merged (KickassAnime,
     *   AniStream do not). All four show a donation request on Android, removed before it appears.
     */
    val all: List<ReviewedExtension> = listOf(
        ReviewedExtension("movix", "Movix", KARMA, ExtensionRole.Catalogue),
        ReviewedExtension("frembed", "Frembed", YORIK, ExtensionRole.Catalogue),
        ReviewedExtension("xalaflix", "Xalaflix", YORIK, ExtensionRole.Catalogue),
        ReviewedExtension("frembed-fr", "Frembed", AMSC, ExtensionRole.Catalogue),
        ReviewedExtension("kdesa", "Kdesa", RAGHAV, ExtensionRole.OriginalVersion),
        ReviewedExtension("movy", "Movy", RAGHAV, ExtensionRole.OriginalVersion),
        ReviewedExtension("anipm", "AniPM", RAGHAV, ExtensionRole.Anime),
        ReviewedExtension("anv", "Anv", RAGHAV, ExtensionRole.Anime),
    )

    fun key(value: String): String = value.lowercase().filter(Char::isLetterOrDigit)

    /** `raw.githubusercontent.com/<owner>/<repo>/…` → `ownerrepo`; other hosts keep their whole path. */
    fun origin(repository: String): String {
        val path = repository.substringAfter("://").split('/')
        return key(if (path.firstOrNull() == "raw.githubusercontent.com" && path.size > 2) path[1] + path[2] else repository)
    }

    /** The reviewed entry for a package [name] from [location] (repository URL, plugin URL or file path). */
    fun find(name: String, location: String): ReviewedExtension? {
        val where = key(location)
        return all.firstOrNull { key(it.internalName) == key(name) && it.origin in where }
    }

    /** Whether the app uses provider [apiName] of that extension. */
    fun usesApi(extension: ReviewedExtension, apiName: String): Boolean =
        extension.apis == null || extension.apis.any { it.equals(apiName, true) }
}
