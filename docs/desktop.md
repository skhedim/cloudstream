# KloudStream for Windows

The desktop app (`kloudstream-desktop/`) runs the same aggregation engine as Android (`shared/`) on the
JVM, with a Compose Desktop interface and mpv for playback. It installs and loads the recommended
CloudStream extensions itself: the user never sees providers.

## Architecture

| Part | Where | What it does |
|---|---|---|
| Extension runtime | `extensions/` | Installs the reviewed `.cs3` (`ReviewedExtensions`, the list Android uses), translates their Android DEX code to JVM classes once (`DexTranslator`, dex2jar with Kotlin names kept), loads each in its own class loader. Updates are checked at every start and applied right away. |
| Android stand-ins | `android/`, `androidx/`, `com/lagradost/cloudstream3/` | The Android classes extensions reference: `Context`, `Log`, `Base64` and `Uri` with Android semantics, persistent `SharedPreferences`, inert views and dialogs (settings screens are not offered), and the app classes extensions call (`Plugin`, `CloudStreamApp`, `MainActivity` events, `DataStore`, a pass-through `CloudflareKiller`). |
| Platform | `platform/` | Installs the shared engine's stores (JSON files written atomically, at most once per second), the provider registry and the artwork sampler. Metadata and ranking follow French. |
| Home | `app/HomeModel.kt` | First pages of the providers in the viewer's language, merged by the shared `AggregatedHomeProjector` (moved from Android to `shared/`), ranked with this week's TMDB trends, with a rotating featured hero and, as on Android, "For you" film and series rows from the viewing history (works watched 30 s or finished; the related titles their provider pages list, kept when TMDB corroborates them; watched works left out; updated a few seconds after a viewing). The last home is saved and shown instantly at the next start while the sources load. |
| Search, detail | `app/SearchModel.kt`, `app/DetailModel.kt` | The shared canonical search engine and detail builder: one card and one page per work, with completion at other providers and TMDB artwork, French synopsis, cast (photos and roles) trailer (YouTube in the browser, else a YouTube search as on Android) and similar titles: the related works every source page lists (Android shows the first page's), one card per work, those TMDB also relates to it first. |
| Playback | `player/` | `CanonicalSourceLoader` checks sources (reachability, real resolution and duration against TMDB); mpv starts as soon as the shared `PlaybackPolicy` allows. A failing or stalled source is replaced silently; once every provider answered, a clearly better verified source replaces the current one at the same position (and reverts if it does not play). The position is saved every 5 s for Continue watching and resume; episodes chain in the same mpv window. |
| Interface | `ui/` | Netflix-like screens: hero, Top 10 with ranks, rows, search grid, detail page with seasons and episodes, downloads, settings. Esc goes back, Ctrl+F opens the search. |
| Downloads | `downloads/` | Movies and episodes saved for offline viewing (`Videos/KloudStream` by default), two at a time, from the best source the engine finds, falling back to the next ones. Files resume with Range requests; HLS takes the best variant up to 1080p, decrypts AES-128 segments, keeps fMP4 init segments and saves a separate French audio rendition next to the video. Original audio gets its French subtitles saved too. A finished download plays from disk through the same player (position, history, subtitles). |
| Settings | `ui/SettingsScreen.kt` | Sources (recommended ones, extensions added from a repository URL, updated like the others), playback (full screen, next episode, preferred version, subtitle size), download folder, image cache and history, update check against the GitHub releases. |

The player bar lists the versions the sources offer (VF, VOSTFR, VO); choosing one switches at the
same position and is remembered, as in Android's Tracks menu. French subtitles are selected only on
original audio: a VO source, or one made VOST by subtitles fetched for it. A source without French
subtitles still offers OpenSubtitles' French ones, unselected (for its original audio track).

The Windows package puts KloudStream's player settings (`packaging/mpv`) in mpv's `portable_config`.
`scripts/kloudstream.lua` draws the controls like a streaming app's: dark gradients, a rounded red
timeline with a knob, round buttons with French tooltips (back, play, ±10 s, volume, subtitles,
audio, speed, next episode, full screen), a pause flash and a loading ring. A click on the picture
pauses, a double click switches full screen, and the controls hide with the cursor after 2.5 s.
The next episode button sends `script-message kloudstream-next-episode` to the app, which tells it
there is one through `user-data/kloudstream/next-episode`. uosc (LGPL-2.1, pinned and checked like
mpv) only shows the menus. mpv's own controls are turned off. `tools/windows/player-preview.sh`
opens the installed player on a generated clip with the repository's settings and saves a
screenshot of each state.

mpv runs in its own window (full screen by default, Settings → Lecture) and is driven through its JSON
IPC: a named pipe on Windows, a unix socket elsewhere. One connection reads events; each command uses
its own connection, because a synchronous Windows pipe cannot read and write at once.

Data lives in `%LOCALAPPDATA%\KloudStream` (`~/.local/share/kloudstream` on Linux): extensions and
their translations, history, preferences, image cache and logs (`logs/kloudstream.log`, `logs/mpv.log`).

## Build and install (from WSL)

Requirements on Windows: JDK 17 (Temurin) and Inno Setup 6. Then, in WSL:

```sh
tools/desktop/package-windows.sh                 # KloudStream-Setup-1.0.0.exe in C:\dev\kloudstream-build\out
tools/windows/win.sh install                     # silent per-user install, then starts the app
```

The jars are built in WSL with Windows natives (`-Pkloudstream.os=windows`); jpackage then makes the
app image with a trimmed Java runtime, and Inno Setup the installer. mpv is a pinned
`shinchiro/mpv-winbuild-cmake` build, checked against its published SHA-256 and bundled in `app\mpv`.
The installer needs no administrator rights (`%LOCALAPPDATA%\Programs\KloudStream`), upgrades in
place and adds Start menu and desktop shortcuts. The release workflow builds the same installer on a
Windows runner and adds it to each GitHub release.

For a quick run without installing: `./gradlew :kloudstream-desktop:stageLibs -Pkloudstream.os=windows`,
copy `kloudstream-desktop/build/stage/lib` to `C:\dev\kloudstream-app\lib`, then
`tools/windows/win.sh run-app` (and `stop-app`). WSLg does not render Compose windows, so the
interface is checked on Windows; `tools/windows/` also takes window screenshots and sends clicks and
keys for automated checks.

## Tests

```sh
./gradlew :kloudstream-desktop:jvmTest                         # Android stand-ins, stores, history
./gradlew :kloudstream-desktop:jvmTest -Pkloudstream.live=true # real extensions and mpv (network)
```

The live tests install the recommended extensions in a temporary folder, search their sites on the
JVM, then search "Dune", open the canonical page and play it in mpv without video or audio output,
checking that a French source plays for 20 seconds.

## Limits

- Extension settings screens (Android views) are not available; extensions use their defaults.
- Dialogs and toasts extensions try to show (donation requests, notices) are never displayed; they
  are written to the log (`ExtensionPrompts`).
- Challenges that need a browser (Cloudflare, WebView extractors) are not solved; those sources are
  skipped like failing ones.
- Only the French catalogues are recommended, as on Android.
