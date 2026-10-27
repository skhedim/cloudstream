# KloudStream releases

KloudStream is published from `skhedim/cloudstream`, branch `main`. The `upstream` remote is the official repository (`recloudstream/cloudstream`). Libraries and namespaces stay CloudStream's, so the extension API is unchanged.

## Install and updates

Install the APK from [the latest release](https://github.com/skhedim/cloudstream/releases/latest). Its file name is the version, for example `1.0.4.apk`. The package is `com.lagradost.cloudstream3.skhedim`, labelled KloudStream (`app/src/release`). It installs next to official CloudStream and the development package `com.lagradost.cloudstream3.debug`.

The first install starts with empty data: export a backup from the other app and restore it here to bring history and settings. Later updates keep this package's data because every release is signed with the same key. Android may ask for permission to install from the app.

Both of CloudStream's update mechanisms only check this repository. The updater picks the APK whose name matches the tag, checks GitHub's digest when one is provided, and compares versions numerically, so it never reinstalls or downgrades. Android also verifies the APK signature. There is one channel, stable.

## CI

- `.github/workflows/fork-release.yml` runs on every push to `main`, or manually from Actions. It checks the extension ABI, runs the shared JVM, Android and desktop unit tests, and builds the signed APK. It verifies the APK with `apksigner`, then publishes a public release with `SHA256SUMS`. A second job builds the Windows installer on a Windows runner (see [the desktop app](desktop.md)) and adds it to the same release.
- `.github/workflows/fork-checks.yml` runs the same checks on pull requests to `main` and builds a debug APK, without access to the signing key.
- The upstream workflows (pre-release, archive, Dokka, locales, instrumented tests, pull requests) keep their original content. A single `if: github.repository == 'recloudstream/cloudstream'` line stops them from running here.

The version is `1.0.<run number>`, the Android version code `1000000 + run number`, and the tag `v1.0.<run number>`. Do not rename or replace the release workflow: that would reset the counter. A new manual run creates a new version; re-running a job never replaces a published release. Publications are serialized, so the latest release cannot go backwards.

## Signing

The private key is never in Git. The Actions secrets are `FORK_KEYSTORE_BASE64`, `FORK_KEY_ALIAS`, `FORK_KEY_PASSWORD` and `FORK_STORE_PASSWORD`. Keep a private backup of the key and its passwords: a different key cannot update an existing install. Optional third-party account integrations use the secrets CloudStream already reads (`SIMKL_CLIENT_ID`, `SIMKL_CLIENT_SECRET`, `TRAKT_CLIENT_ID`, `MDL_API_KEY`, `MAL_KEY`, `ANILIST_KEY`).

## Keeping up with upstream

`main` is CloudStream's `master` plus the KloudStream commits on top. To take a new upstream version:

```sh
git fetch upstream
git switch main
git rebase upstream/master
./gradlew :library:checkKotlinAbi :shared:jvmTest :app:testStableDebugUnitTest :app:assembleStableDebug
git push --force-with-lease origin main
```

The push publishes a new release; version numbers come from the run counter, so rebasing never changes them.

KloudStream keeps upstream files as close to the original as possible so rebases stay simple:

- **Fork-only files, which never conflict**: `.github/README.md` (shown instead of `README.md`), `.github/workflows/fork-*.yml`, `app/src/release/` (app name), `shared/src/jvmCommonMain/` (aggregation engine), `ui/*/aggregation/`, `ui/catalog/`, `kloudstream-desktop/` (Windows app), `docs/`, `tools/`.
- **One line in an upstream file**: `settings.gradle.kts` includes `:kloudstream-desktop`.
- **Small edits to upstream files**: the `if:` line in upstream workflows, and in `app/build.gradle.kts` the signing, version and package suffix.
- **Integration points that may need attention**, by size of change:
  - home: `HomeViewModel`, `HomeFragment`, the row adapters and `homepage_parent*.xml`;
  - search: `SearchViewModel`, `SearchFragment`, `QuickSearchFragment`, `SearchAdaptor`;
  - player: `GeneratorPlayer`, `PlayerGeneratorViewModel`, `RepoLinkGenerator`;
  - result page: `ResultViewModel2`, `ResultFragmentTv`;
  - TV focus in `LinearListLayout`, extension repositories in `AndroidRepositoryManager`;
  - the updater: `GithubReleases`, `InAppUpdater`.

## Local builds

JDK 17, the Android SDK and the platform named in `gradle/libs.versions.toml` are required. Development builds keep the debug package and the existing scripts:

```sh
./gradlew :shared:jvmTest :app:testStableDebugUnitTest :app:assembleStableDebug
```

To build a signed release locally, set `SIGNING_STORE_FILE`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD` and `SIGNING_STORE_PASSWORD` in a private environment, then run:

```sh
./gradlew :app:assembleStableRelease -PforkVersionName=1.0.0 -PforkVersionCode=1000000
```

The local default version (`1.0.0`) is lower than every CI version. Never install a local build with a higher version code than future releases.
