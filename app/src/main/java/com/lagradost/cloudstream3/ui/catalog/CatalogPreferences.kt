package com.lagradost.cloudstream3.ui.catalog

import android.content.Context
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.AllLanguagesName
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.plugins.PluginData
import java.util.Locale

object CatalogPreferences {
    const val RECOMMENDED_ONLY = "catalog_recommended_only"
    const val MANAGED_ARCHIVES = "catalog_managed_archives"
    fun mayLoadArchive(context: Context, data: PluginData): Boolean = !data.isOnline ||
        data.filePath !in PreferenceManager.getDefaultSharedPreferences(context)
            .getStringSet(MANAGED_ARCHIVES, emptySet()).orEmpty() ||
        !read(context).recommendedOnly || CatalogCuration.recommended(data.internalName, data.url.orEmpty() + data.filePath)
    @Synchronized
    fun manageArchives(context: Context, paths: Collection<String>) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putStringSet(MANAGED_ARCHIVES, prefs.getStringSet(MANAGED_ARCHIVES, emptySet()).orEmpty() + paths).apply()
    }
    @Synchronized
    fun releaseManagedArchive(context: Context, path: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (path in prefs.getStringSet(MANAGED_ARCHIVES, emptySet()).orEmpty())
            prefs.edit().putStringSet(MANAGED_ARCHIVES, prefs.getStringSet(MANAGED_ARCHIVES, emptySet()).orEmpty() - path).apply()
    }
    fun read(context: Context): CatalogSelection {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return CatalogSelection(
            prefs.getStringSet(context.getString(R.string.provider_lang_key), setOf(AllLanguagesName)).orEmpty().toSet(),
            prefs.getStringSet(context.getString(R.string.prefer_media_type_key),
                CatalogCuration.videoTypes.map { it.ordinal.toString() }.toSet()).orEmpty()
                .mapNotNull { ordinal -> TvType.entries.getOrNull(ordinal.toIntOrNull() ?: -1) }
                .intersect(CatalogCuration.videoTypes).ifEmpty { setOf(TvType.Movie, TvType.TvSeries) },
            prefs.getBoolean(RECOMMENDED_ONLY, true))
    }
    fun initial(context: Context): CatalogSelection {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val existing = read(context)
        return existing.copy(languages = if (!prefs.contains(context.getString(R.string.provider_lang_key)))
            com.lagradost.cloudstream3.APIHolder.apis.withLock {
                com.lagradost.cloudstream3.APIHolder.apis.map { it.lang }.toSet().ifEmpty { setOf(Locale.getDefault().language) }
            } else existing.languages,
            types = existing.types.takeIf { prefs.contains(context.getString(R.string.prefer_media_type_key)) }
                .orEmpty().ifEmpty { setOf(TvType.Movie, TvType.TvSeries) })
    }
    fun save(context: Context, selection: CatalogSelection) {
        require(selection.languages.isNotEmpty() && selection.types.isNotEmpty())
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val key = context.getString(R.string.prefer_media_type_key)
        // This wizard owns only its catalogue categories; preserve other native preferences.
        val owned = CatalogCuration.videoTypes.map { it.ordinal.toString() }.toSet()
        val otherTypes = prefs.getStringSet(key, emptySet()).orEmpty() - owned
        prefs.edit()
            .putStringSet(context.getString(R.string.provider_lang_key), selection.languages)
            .putStringSet(key, otherTypes + selection.types.intersect(CatalogCuration.videoTypes).map { it.ordinal.toString() })
            .putBoolean(RECOMMENDED_ONLY, selection.recommendedOnly).apply()
    }
}
