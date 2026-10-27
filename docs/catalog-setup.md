# Catalogue setup

The setup wizard asks for languages and video categories before downloading extensions. The same screen is available from **Discover → Set up the catalogue** and **Settings → General → Set up the catalogue**.

The recommended selection only installs the packages and origins listed in `CatalogCuration`. They are chosen from the extension survey (`ProviderSurveyInstrumentedTest`): Movix, Frembed (yorik100 and the French repository) and Xalaflix. For now only French catalogues are recommended; original-version and anime extensions have not been evaluated yet. The following are not recommended:

- AfterDark: no playable links, and its site shows a verification page during playback;
- FsMirrorLol: offers no title the others lack, and its pages can no longer be read;
- Coflix, Flemmix and FrenchAnime: found no title in the 2026-10-26 survey.

Language, category, status and compatibility filters still apply. Unticking the recommended selection gives access to the other video extensions of the catalogue. Known intrusive packages (AllForU/All4U) and game repositories are excluded; utilities are never installed automatically.

French and English are listed first; other languages come from the extensions' metadata. An extension's language does not guarantee its audio tracks: VF/VOST is chosen in the player.

**Update (N)** installs or refreshes the packages that match the selection and keeps those already up to date. Failures are isolated and reported; running the action again retries them. The wizard never deletes existing files, and it preserves the categories it does not manage and the other native preferences.

## Architecture

- `CatalogIndex` reads the public MegaRepo index and the repositories already configured, without running MegaProvider.
- `CatalogInstaller` limits concurrent reads and their duration. Downloads go through `PluginManager`, with published checksums and atomic file replacement.
- `CatalogSelection` picks packages by language, video type, status and API version. A package mirrored in several repositories is installed once, preferring the origin already installed.
- `CatalogPreferences` only stores the language, catalogue category and recommended-selection choices.
- The loader keeps its protections against known intrusive packages. Packages installed by the wizard follow its recommended selection. Manual installs follow the native loader; installing a package explicitly from the extensions menu removes it from the wizard's management.
- The extension API is unchanged: same episode interfaces, same `loadLinks()`.

## Discover and See more

Discover merges the films, series and video categories available. Matching works are merged with their original bindings. Film and series recommendations come from the viewing history, and works already watched are left out.

Each home row shows 24 cards. **See more** opens a multi-column grid. **Load more** requests the next pages of the sections that fed the row, keeping deduplication and canonical bindings.

Vertical navigation in the grid uses Android's standard focus handling, so the remote scrolls to off-screen cards. The counter shows the number of titles, and after a successful load the focus moves to the new cards. Errors offer a retry; a page that repeats stops its section. One action can go through up to three batches of pages when they only bring variants of works already shown.

Requests are limited to four providers at a time, eight seconds per attempt, twenty pages per section and 500 cards per row. Closing the grid cancels its requests. Changing profile or catalogue prevents earlier responses from updating the new page.

## Checks

```bash
./gradlew :shared:jvmTest :app:testStableDebugUnitTest \
  :app:assembleStableDebug :app:assembleStableDebugAndroidTest \
  --max-workers=4 --console=plain
ANDROID_SERIAL=192.0.2.10:5555 tools/catalog-fixture.sh --skip-build
ANDROID_SERIAL=192.0.2.10:5555 tools/home-shelves-fixture.sh --skip-build
```

The tests use fictional films and series and temporary profiles. The catalogue scenario also checks repository metadata, a local download with a wrong checksum, keeping a valid archive, registering an archive already downloaded, and the first-run flow (language → catalogue → layout). The grid scenario checks that focus really moves past the first twelve cards, scrolling after a load, the counter, retries, and that results are kept when the grid is reopened.
