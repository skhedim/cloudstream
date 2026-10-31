# History sync

KloudStream devices (Android, Android TV, Windows) can share their viewing history: a film or an
episode started on one resumes on the others, and For you learns from everything watched. The
history is a `history.json` file in a private repository of the user's GitHub account; nothing else
of the account is reachable.

## Setup (once per account)

1. Create a private repository named `kloudstream-sync` (an empty one with a README is fine).
2. Register a GitHub App (Settings → Developer settings → GitHub Apps → New GitHub App):
   - name `KloudStream Sync` (or any free name), homepage the KloudStream repository;
   - **Enable Device Flow** checked, **Expire user authorization tokens** unchecked (a TV cannot
     sign in again every 8 hours; the access stays revocable);
   - webhook inactive;
   - repository permission **Contents: Read and write**, nothing else;
   - installable only on this account.
3. Install the app on the `kloudstream-sync` repository only.
4. Put the app's client ID in `HistorySyncService.CLIENT_ID` (`shared/…/sync/GitHubSync.kt`). It is
   not a secret: the device flow uses no client secret.

Then on each device: Settings → Synchronisation → Connect to GitHub. The device shows a code; the
user opens github.com/login/device on a phone or computer and enters it. The device then finds the
repository the app is installed on (`kloudstream-sync` first). Disconnecting forgets the access on the
device; GitHub's settings (Applications → Authorized GitHub Apps) revoke it everywhere.

## What is shared

One state per work (`SyncedWork`): its canonical page context (`CanonicalDetailRequest`, a few
bindings and titles), the film or the episode played last (canonical episode key `s1:e4`), position,
duration, finished, removed, time and device. Works are keyed by their TMDB ID, else IMDb ID, else
canonical identity, so the same work matches on every device. At most 400 works are kept.

- Android: Continue watching and finished works (`AndroidSyncedHistory`) of canonical pages. Their
  history IDs follow the work (`CanonicalDetail.pageId`, `episodeId`), so a state from another device
  lands on the same page and episode. Works from providers the device does not have are skipped.
- Desktop: `WatchHistory` entries (`DesktopSyncedHistory`).

## How a sync runs

`HistorySync` reads the file, applies the states newer than the device's (oldest first), then writes
the merge back with the version it read; if another device wrote meanwhile, GitHub refuses it and the
sync reads and merges again. The newest state of a work wins; on a tie the shared one stays, so a
device applying a state writes nothing back. A work the device had and no longer has, unchanged
elsewhere since, stays away: in progress, it was removed from Continue watching, so it is marked
removed for every device; finished, the device kept only its latest finished works.

Devices sync when the app starts (Android: once the providers are loaded), every 2 minutes while it
is open (positions during playback included) and, on Windows, after each viewing. Each write is a
commit of the repository, which keeps the file's history.
