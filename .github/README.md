# KloudStream

KloudStream is a build of [CloudStream](https://github.com/recloudstream/cloudstream) that hides providers behind a single catalogue. Each film or series appears once, whichever extensions offer it, and playback picks a working source by itself. Existing CloudStream extensions work unchanged.

**[Download the latest APK](https://github.com/skhedim/cloudstream/releases/latest)**

> [!WARNING]
> Like CloudStream, KloudStream provides no content by itself: video comes from third-party extensions you choose to install.

## What it adds

- **Discover home**: this week's Top 10, latest additions, trends, and "For you" rows built from your viewing history, merged across providers. On Android TV, each row keeps its place as you move between rows.
- **Unified search**: one result per work, matched across providers by TMDB/IMDb IDs and by titles, ignoring accents and sequel numbering. Results are ranked by relevance.
- **One page per title**: seasons and episodes are merged across providers. A title found in a single catalogue is looked up in the others when opened.
- **Checked playback**: before a source can play, it is checked for reachability, real resolution and real duration against TMDB. This drops fake 4K files and wrong films. Playback starts within a few seconds on a good French source, then switches silently to a better checked one.
- **Recommended extensions**: catalogue setup suggests French extensions selected from on-device measurements. See [catalogue setup](https://github.com/skhedim/cloudstream/blob/main/docs/catalog-setup.md).
- **Shared engine**: the aggregation engine is a Kotlin Multiplatform module shared by the Android app and the Windows app.

## Install and updates

- **Android**: install the APK from [Releases](https://github.com/skhedim/cloudstream/releases). The app is named KloudStream and installs alongside official CloudStream (package `com.lagradost.cloudstream3.skhedim`). The two apps keep separate data; use the built-in backup and restore to move your history and settings. New versions are offered inside the app.
- **Windows**: run `KloudStream-Setup-<version>.exe` from the same release. It installs for the current user without administrator rights, bundles its video player (mpv) and installs the recommended sources on first start. See [the desktop app](https://github.com/skhedim/cloudstream/blob/main/docs/desktop.md).

## Development

- Every push to `main` runs the tests and publishes a signed APK and the Windows installer as a GitHub release ([workflow](https://github.com/skhedim/cloudstream/actions/workflows/fork-release.yml)).
- This fork is regularly rebased on CloudStream's `master`. Its changes stay in separate files where possible, so updates apply cleanly.

[Releases, signing and upstream sync](https://github.com/skhedim/cloudstream/blob/main/docs/releases.md) · [Aggregation architecture](https://github.com/skhedim/cloudstream/blob/main/docs/aggregated-search.md)

## Credits

KloudStream is based on [CloudStream](https://github.com/recloudstream/cloudstream) by recloudstream and its contributors, and is distributed under the same [GPL-3.0 license](https://github.com/skhedim/cloudstream/blob/main/LICENSE).
