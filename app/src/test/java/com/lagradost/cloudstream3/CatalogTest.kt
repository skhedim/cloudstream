package com.lagradost.cloudstream3

import android.app.Application
import com.lagradost.cloudstream3.plugins.*
import com.lagradost.cloudstream3.ui.catalog.*
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CatalogTest {
    private val movies = CatalogSelection(setOf("fr"), setOf(TvType.Movie), recommendedOnly = false)
    private fun plugin(name: String, lang: String = "fr", types: List<String> = listOf("Movie"),
        status: Int = 1, version: Int = 20, url: String = "https://fixture.invalid/$name.cs3", api: Int = 1): PluginWrapper {
        val repo = Repository(null, "Fixture", null, 1, emptyList())
        return PluginWrapper(repo, RepositoryData("Fixture", "https://fixture.invalid/repo.json"),
            SitePlugin(url, status, version, api, name, name, emptyList(), null, null, types, lang, null, null, null))
    }
    @Test fun megaIndexSupportsObjectsStringsDuplicatesAndRejectsUnusableUrls() {
        assertEquals(listOf("https://a.invalid/repo", "https://b.invalid/repo"),
            CatalogIndex.parse("""[{"url":"https://a.invalid/repo","verified":true},"https://b.invalid/repo","https://a.invalid/repo",{},null,"file:///oops"]"""))
    }
    @Test fun languagesAndMediaAreSelectedBeforeInstallingAnyArchive() {
        val fr = plugin("French"); val en = plugin("CineStream", "en"); val vi = plugin("Vietnamese", "vi")
        val series = plugin("Series", types = listOf("TvSeries"))
        val plan = listOf(fr, en, vi, series, plugin("MegaProvider", "en"))
        assertEquals(listOf(fr), movies.plan(plan))
        assertEquals(setOf(fr, en), movies.copy(languages = setOf("fr", "en")).plan(plan).toSet())
        assertEquals(listOf(vi), movies.copy(languages = setOf("vi")).plan(plan))
        assertEquals(setOf(fr, series), movies.copy(types = setOf(TvType.Movie, TvType.TvSeries)).plan(plan).toSet())
    }
    @Test fun wizardUpdatesOnlyItsOwnCategoriesAndKeepsOtherNativePreferences() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
        val key = ctx.getString(R.string.prefer_media_type_key)
        prefs.edit().putStringSet(key, setOf(TvType.Audio.ordinal.toString(), TvType.TvSeries.ordinal.toString()))
            .putBoolean("fixture_native_setting", true).commit()
        CatalogPreferences.save(ctx, movies)
        assertEquals(setOf(TvType.Movie.ordinal.toString(), TvType.Audio.ordinal.toString()), prefs.getStringSet(key, emptySet()))
        assertTrue(prefs.getBoolean("fixture_native_setting", false))
        assertEquals(setOf(TvType.Movie), CatalogPreferences.read(ctx).types)
    }
    @Test fun recommendationLoadingAppliesOnlyToWizardManagedArchivesAndManualInstallCanReleaseThem() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val manual = PluginData("GenericVideo", "https://fixture.invalid/GenericVideo.cs3", true, "/fixture/GenericVideo.cs3", 20)
        assertTrue(CatalogPreferences.mayLoadArchive(ctx, manual))
        CatalogPreferences.manageArchives(ctx, listOf(manual.filePath))
        assertFalse(CatalogPreferences.mayLoadArchive(ctx, manual))
        assertTrue(CatalogPreferences.mayLoadArchive(ctx, manual.copy(isOnline = false)))
        CatalogPreferences.releaseManagedArchive(ctx, manual.filePath)
        assertTrue(CatalogPreferences.mayLoadArchive(ctx, manual))
        CatalogPreferences.manageArchives(ctx, listOf(manual.filePath))
        CatalogPreferences.save(ctx, movies.copy(recommendedOnly = false))
        assertTrue(CatalogPreferences.mayLoadArchive(ctx, manual))
    }
    @Test fun regionalLanguageTagsUseTheSamePolicyForArchivesAndLoadedProviders() {
        val regional = movies.copy(languages = setOf("fr-FR"))
        val api = object : MainAPI() {
            override var lang = "fr"
            override val supportedTypes = setOf(TvType.Movie)
        }
        assertTrue(regional.accepts(plugin("French")))
        assertTrue(regional.normalProvider(api))
        assertFalse(regional.accepts(plugin("English", "en")))
    }
    @Test fun recommendedDefaultRequiresReviewedPackageAndOriginButExtendedVideoRemainsAvailable() {
        val selection = CatalogSelection(setOf("en"), setOf(TvType.Movie))
        assertFalse(selection.accepts(plugin("French", "en")))
        assertFalse(selection.accepts(plugin("CineStream", "en")))
        assertTrue(selection.accepts(plugin("CineStream", "en", url =
            "https://raw.githubusercontent.com/SaurabhKaperwan/CSX/builds/CineStream.cs3")))
        assertTrue(selection.copy(recommendedOnly = false).accepts(plugin("GenericVideo", "en")))
    }
    @Test fun intrusiveRatingGamesAndUtilitiesCannotReenterEvenWithMovieTagsOrExtendedSelection() {
        val all = CatalogSelection(setOf(AllLanguagesName), TvType.entries.toSet(), recommendedOnly = false)
        assertFalse(all.accepts(plugin("Rate", url = "https://raw.githubusercontent.com/RVRBEAST76/allforu-repo/builds/Rate-v8.cs3")))
        assertFalse(all.accepts(plugin("Doom", url = "https://raw.githubusercontent.com/errorcode26/Ayu-CloudStream-Games/builds/Doom.cs3")))
        assertFalse(all.accepts(plugin("M3UPlayer")))
        assertFalse(all.accepts(plugin("LibriVoxAudiobook")))
        assertFalse(all.accepts(plugin("AudioOnly", types = listOf("AudioBook"))))
        assertTrue(CatalogCuration.blockedClass("com.allforu.RatePlugin"))
        assertFalse(CatalogCuration.blockedClass("com.megix.CineStream"))
    }
    @Test fun downBetaIncompatibleAndKnownBrokenVersionsAreSkippedButFutureFixesAllowed() {
        assertTrue(movies.plan(listOf(plugin("Down", status = 0), plugin("Beta", status = 3),
            plugin("NewAPI", api = 2), plugin("FStream", version = 16))).isEmpty())
        assertTrue(movies.accepts(plugin("FStream", version = 17)))
        assertTrue(movies.accepts(plugin("Slow", status = 2)))
    }
    @Test fun mirroredPackagesAreInstalledOnceAndExistingOriginIsPreferred() {
        val original = plugin("Movie", version = 2)
        val copy = plugin("Movie", version = 40, url = "https://copy.invalid/Movie.cs3")
        assertEquals(listOf(original), movies.plan(listOf(copy, original, original), setOf(original.plugin.url)))
        assertEquals(listOf(copy), movies.plan(listOf(original, copy)))
    }
    @Test fun partialInstallationContinuesAndRetryOnlyDownloadsWhatIsStillMissing() = runBlocking {
        val plan = listOf(plugin("Ready"), plugin("Fails"), plugin("New"))
        val installed = mutableSetOf("Ready")
        val attempts = mutableListOf<String>()
        var retry = false
        suspend fun install(): CatalogInstallation = CatalogProvisioner.run(plan,
            current = { it.plugin.name in installed }, download = {
                val name = it.plugin.name; attempts += name
                if (name == "Fails" && !retry) error("offline")
                installed += name; true
            }, progress = { done, total, _ -> assertTrue(done <= total) })
        assertEquals(CatalogInstallation(1, 1, listOf("Fails")), install())
        retry = true
        assertEquals(CatalogInstallation(1, 2, emptyList()), install())
        assertEquals(listOf("Fails", "New", "Fails"), attempts)
    }
    @Test fun cancellationStopsTheQueueAndIsNeverReportedAsSuccessfulInstallation() = runBlocking {
        val started = CompletableDeferred<Unit>(); var calls = 0; var finished = false
        val job = launch {
            CatalogProvisioner.run(listOf(plugin("One"), plugin("Two")), { false }, {
                calls++; started.complete(Unit); delay(Long.MAX_VALUE); true
            }, { _, _, _ -> })
            finished = true
        }
        started.await(); job.cancelAndJoin()
        assertEquals(1, calls); assertFalse(finished)
    }
}
