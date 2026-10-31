# Content aggregation: discovery, search and playback

Search and Quick Search group matching results by default. Cards show the content
(title, artwork, rating and year when available), with no provider names or provider
picker. The provider filter is hidden in aggregated Search; searches use the enabled
languages and media preferences across installed extensions. Type chips remain.
The microphone has a voice-search accessibility description.

The **Group matching search results** setting is enabled by default under UI settings.
It takes precedence over **Advanced search** (provider rows). Disable grouping to use
the upstream views again. Quality filters apply to individual bindings before grouping.
Quick Search preserves explicit caller scope but hides provider-specific query hints
when aggregation is enabled.

Clicking or long-pressing a card opens one canonical detail page using the upstream
phone/TV layouts. All known bindings load with bounded concurrency, then their numbered
seasons/episodes are combined. Episode bindings preserve each extension’s opaque data.
Each numbered episode is shown once, with VF/VOST attached to its source bindings.
Language/version selection is in the player's **Tracks → Version** menu, alongside
native embedded audio/video tracks. Providers contribute links through their unchanged
`loadLinks()` API; the app checks and ranks the links (see *Sources and playback (v2)*).
New canonical playback/favourite identities are independent of the provider URL.
Old upstream history is retained but is not automatically migrated. Milestone-3 canonical
version-specific episode progress is consolidated when those episodes are opened.

## Common discovery home and YouTube trailers

The **Common discovery home** UI setting (`aggregate_home`, enabled by default) opens
content shelves instead of a provider homepage. It is independent of search grouping;
disabling it retains the upstream provider home. Refresh and search controls use
content wording, and no provider names or picker appear on the common home. The
existing continue-watching and bookmark controls remain below the discovery shelves.
The single-provider Android launcher channel is not republished from app-only cards.

`ui/home/aggregation` selects up to four first-page requests per enabled homepage
extension, preferring explicit recent additions and diversifying films/series/anime.
It respects enabled extensions, language/media preferences and quality exclusions.
Four providers run concurrently, with cooperative deadlines of eight seconds per
section, twenty seconds per provider and forty-five seconds including queueing.
Partial successful sections survive failures. Completed nonempty feeds are cached
in memory for ten minutes (60 provider keys); refresh invalidates the applicable
entries. The visible page is reused across navigation until refresh, a provider change,
or a relevant history/profile change. These changes cancel the previous generation, and stale
work cannot update the visible page.

Rows appear progressively in stable provider/rank order, not completion order or
release-year order. **Latest additions** requires explicit recency labels; popular,
trending and upcoming feeds remain discovery content. The app does not manufacture
addition timestamps. **Films**, **Series** and **Anime/animation** use declared media
kinds; explicit animation sections and animation-only extension capabilities can also
classify the animation shelf without rewriting a work's canonical identity. Genre
rows (documentary, action, comedy, thriller, science fiction) require explicit section
evidence, with at least three titles for the narrower genres. No IMDb account, API key
or new metadata service is required. Genres absent from the returned data stay absent.

The home keeps a 24-card preview per shelf. A dedicated See more button on phone and
TV opens a multi-column grid, retaining up to 120 items per returned section and 500
cards per shelf. Load more pages only on demand, from sections that contributed to
the selected shelf (at most four providers concurrently and twenty pages per section).
Transient failures preserve the cursor for a manual retry; repeated pages stop paging.
The grid uses standard Android focus navigation to scroll off-screen cards. A title
counter and focus on newly loaded cards make additions visible. Up to three request
batches can skip alternate URLs for already displayed works before returning control.
Dismissing the grid cancels its pending request. A home/profile change prevents old
requests from publishing into the new selection. Personalized lists use the available
recommendation batches rather than unrelated catalogue pages. One global
`MediaAggregator` pass preserves every observed binding on each card across shelves.
The same work can appear in different thematic shelves, but not twice in one shelf
when matching evidence suffices. After acquisition, up to six ambiguous bindings can reuse the existing provider detail
loader to recover missing years/IDs (two concurrently, five seconds each). Successful
detail metadata feeds the same search metadata cache; unique titles need no detail
requests. Then at most twelve validated TMDB
poster thumbnails (two concurrently, 2.5 seconds each) reuse search's cautious visual
matching for unresolved duplicate titles. Conflicting years/IDs still block merges.
Cards open the existing canonical detail and multi-provider source flow. Unknown
identity evidence remains separate instead of receiving a title-only merge.

`cache/aggregated-home.json` contains Debug-only counts, section labels, titles and
binding counts, without navigation payloads or credentials. Extensions can open their
own dialogs during calls; the aggregator does not suppress or complete third-party
verification, donation or sign-in screens.

**Trailer** is a separate action next to the detail's existing controls on phone/TV.
It sends an Android `ACTION_VIEW` intent for an encoded YouTube search consisting of
title, available year, and “bande annonce officielle” / “official trailer” according
to the device language. The installed YouTube/browser handler is selected by Android;
there is no automatic video playback or guessed trailer URL. If no app can open the
link, the existing app webview is used when available. This uses Android's documented
[view intents](https://developer.android.com/guide/components/intents-common).
The existing provider-supplied trailer player remains available independently.

Host validation now passes **124 tests** (48 shared, 76 app), including feed planning,
identity/navigation, conservative genre inference, thumbnail refinement, bounded
concurrency, caching/retry/cancellation and correctly escaped YouTube queries.
Device evidence is generated under `app/build/android-dev/discovery/`.

### Personalized recommendations

**Films for you** and **Series for you** appear after Latest additions when recommendations
are available. The app reuses `LoadResponse.recommendations` from up to three distinct movies
and three distinct series/anime in the current local profile. Continue-watching entries
take priority over Completed-only library entries. Thirty seconds of playback is required
for a positive signal; a library entry marked Completed also counts, and so does a finished work:
finishing removes it from Continue watching, so `DataStoreHelper.setFinished` keeps the 50 most
recent (films finished earlier are found by their saved position). Merely opening a detail page does not count, and Dropped titles cannot seed
recommendations. Continue-watching positions, cached headers and Completed/Dropped library
entries use the existing account-scoped stores. Canonical aliases restore all known bindings.

Up to eight different enabled providers per seed can be tried when a page fails, describes
another work, or has no recommendations of the requested family. Seed identity is checked
against the loaded page (family, conflicting IDs, shared trusted IDs, or compatible title/year).
A movie list returned for a series does not prevent trying the next provider. Acquisition
interleaves movie/series seeds; initial attempts are queued fairly before fast fallbacks.
Requests run two at a time, with cooperative five-second page and twenty-second total
deadlines. When the seed has a trusted TMDB ID, the existing metadata client can retrieve
recommendations/similar works (two concurrent lookups, 2.5 seconds each, inside the same total
budget). Nonempty metadata evidence filters generic provider lists before accepting a source;
the next provider is tried if none of its items match. No metadata-only unplayable cards are
invented. Missing/unavailable metadata leaves provider-only recommendations usable. Public
related-work evidence is cached for 24 hours with at most 50 keys including language. News feeds publish independently. Successful nonempty results are cached in memory
for thirty minutes, with at most 32 keys including account, seed and implementation; refresh
invalidates those keys. Failed/empty results can be retried at the next reload. This adds no
credentials or extension API. Existing detail URLs are requested from enabled extensions;
known work IDs may also query the app’s existing TMDB access. The complete history is not uploaded.

Recommendations and current feeds share one identity pass, so a suggested card retains all
observed playable bindings and opens the common detail. History contributes matching evidence
but cannot introduce synthetic playable bindings. Already played, Completed and Dropped works
are excluded from For you with the same cautious year/type/ID/artwork matching; remakes and
unsupported unknown-year homonyms remain separate. Loading a legacy history page can recover
a missing year/ID before this exclusion is finalized. Separate rows show movies and series
(including anime), each with at most sixty cards. Each watched work contributes at most twenty
choices, interleaved round robin across seed works. Recommendations shared by several seeds
lead each seed queue, then the original recommendation order is retained. A single prolific
horror-film list cannot occupy all sixty positions or displace series from the plan.
Recommendations do not fabricate Latest additions or reorder existing news. Other discovery
rows and Continue watching are preserved. Without usable history/recommendations the row stays
absent. Returning home, changing local profile, deleting history or updating library state
re-evaluates the plan; cancellation, generation and account checks reject stale publications.
Reload detection compares persisted viewing signals independently of metadata caches and
preference key iteration order, so learning titles/IDs while browsing does not refresh the page.

`PersonalizedRecommendationsTest` covers seed selection, accidental playback, library states,
ranking, binding preservation/navigation, watched remakes, unknown years, news ordering,
cache isolation, retries, deadlines and cancellation. Regression cases cover short-but-real
series playback, independent seed quotas, diversity, family filtering and wrong detail pages. `PersonalizedHomeInstrumentedTest` uses
temporary local profiles and in-process extensions to exercise persisted history → home →
common detail and a switch to an empty profile. It verifies both movie and series rows,
including a series watched for 46 seconds with two playable bindings. It restores the original profile, language
preferences and fixture history afterwards. Evidence lives in `app/build/android-dev/personalized-home/`.

## Shared aggregation engine (2026-10-26)

The aggregation layer is shared by Android and the future desktop (JVM) app. Android only
supplies UI, its stores and its extension wrappers.

| Location | Contents |
|---|---|
| `shared/src/commonMain/.../domain/media` | Pure domain: `MediaAggregator`, `EpisodeAggregator`, `MediaReferenceResolver`, planners, `SearchSession`. |
| `shared/src/jvmCommonMain/.../aggregation` (Android + JVM) | `CanonicalSearchEngine`, `SearchMediaMapper`, identity evidence/adapters, TMDB access (`SearchMetadataCatalog`), poster signatures, `CanonicalDetailRequest/Store/Loader/Builder`, `CanonicalSourceLoader`, `SourceRanking`, `PlaybackPolicy`, `PlaybackVerification`, `StreamProbe`, `ProviderHealth`, `SourceRegistry`, `ProviderGateway`. |
| `shared/src/jvmMain/.../aggregation` | Desktop image decoding (`ImageIoArtworkSampler`). |
| `shared/src/jvmCommonMain/.../ui/home/aggregation` | Home projection shared with the desktop app (`AggregatedHomeProjector`, `HomeFeedPolicy`, `HomeShelf`, personalized recommendations); Android maps shelves to its string resources (`HomeShelfTitles.kt`). |
| `app/.../ui/search/aggregation/AndroidAggregation.kt` | Installs the Android platform: `CloudStreamApp` key/value store, `APIRepository` gateway (timeouts, load cache), `BitmapFactory` sampler. |
| `app/...` | `SearchViewModel` (LiveData bridge), `AggregatedSearchResponse`, `CanonicalDetail` (projection onto upstream detail UI, progress migration), player integration, diagnostics. |

`AggregationPlatform` holds the four platform pieces: `KeyValueStore`, `SourceRegistry`,
`ProviderGateway` and `ArtworkSampler`. `SourceRegistry` returns `RegisteredProvider`s
(`MainAPI` + stable ID) from `APIHolder`; the engine never sees repositories or plugin files.
Stable provider IDs are `pluginFile|implementationClass|name`; saved bindings carry them
(`DetailBinding.providerId`, `EpisodeSourceBinding.providerId`) and resolve by ID, then by
exact name/domain, then by a unique name. Older saved contexts without IDs still resolve.

### Search
`CanonicalSearchEngine` is the former `SearchViewModel` pipeline, confined to one sequential
dispatcher off the UI thread and published as a `StateFlow`. Additions:
- matching ignores accents, `&`/and/et and Roman sequel numbers (II–IX) through
  `MediaAggregator.matchKey`; persistent identities keep `normalizeTitle`;
- known years may differ by one; an exact year is preferred for groups and TMDB references,
  and a wider spread stays separate;
- explicit catalogue labels (`- série`, `(film)`) are stripped and correct the search type;
- TMDB references learn their IMDb ID (4 per query), so a provider that only knows the IMDb ID
  joins a TMDB-identified work despite another title;
- show references include season posters, and alternate posters of seasons 1–2 for
  unresolved whole-show entries (8 season requests per query);
- a candidate whose own IDs are shared with several groups merges them when no ID, family,
  year or edition contradicts it. Title evidence never bridges groups;
- cards are ranked by relevance (`SearchRelevance`): the exact title (accents, punctuation and
  sequel numbers folded), then titles starting with the query, then containing it. Exact titles
  offered by more providers lead; otherwise the providers' order is kept, so results no longer
  depend on which provider answered first.

### Detail
`CanonicalDetailLoader` loads 6 bindings at once, at most 2 per provider, and reports progress.
The page appears once a whole-show page and 60 % of bindings are in, or after 4 s, then is
republished once with every binding (selected season and range are kept).
`CanonicalDetailBuilder` accepts pages independently of completion order: wrong family,
conflicting known ID, or minority learned ID (ties go to the base page) reject a page; without
a shared ID, a year more than one apart or titles unrelated to every observed title also
reject it (contexts saved before titles were recorded only check the year).
Flat, season-less, unscoped episode lists use absolute keys (`abs:eN`) that merge only with
other absolute lists; when no seasoned list exists they are shown as season 1, otherwise in an
"Absolute numbering" section. Absolute numbers are never converted to seasons.

### Enabled extensions only
Saved contexts only load the bindings of extensions that are enabled now
(`CanonicalDetailRequest.restrictedTo`): disabling or removing an extension never leaves the page
waiting for it.

### Completion of single-catalogue cards
A card found in one catalogue only (home, recommendations) is completed on its first opening:
`CanonicalBindingCompleter` searches the other enabled providers (6 at once, 8 s per search,
12 s in total) with the canonical title, then the original title. It is strict: a shared ID, or
the same title, family and a known year at most one apart, with no contradicting ID; season
entries need a shared ID. Found pages are verified again by the builder. `completedAt` limits
this to once a day per work.

### Sources and playback (v2)
Every source gets a **version** and a **check** before it can win:
- Version: VF, VOST, unspecified, or VO. VO means a provider outside the viewer's language
  that does not label its links: it is a fallback only and never beats a native source.
  `Subbed`/`Dubbed`/`MULTI` prove VOST/VF only on French-language providers.
- Check: `StreamInspector` downloads the HLS manifest (or the file headers) with the link's own
  headers, follows the first variant and adds up segment durations (playlists over 4 MB or
  truncated count as unknown duration). `StreamChecks` compares it with the TMDB runtime
  (`RuntimeCatalog`): a movie must last 0.75–1.45× the runtime, an episode 0.5–2.2×, and any
  stream at least 4 min; files without a playlist need 120 kbit/s on average (25 MB without a
  runtime). Failures are rejected (recorded in `rejections`); 401/403/429 answers stay
  `Unchecked` because the player may be allowed where we are not. Results: `Verified`,
  `Alive` (reachable, duration unknown) or `Unchecked`.
- Inspection runs on 4 workers in score order, at most 30 per playback, cached for 20 min.
- Original-version and anime providers (outside the viewer's language) complement the French
  ones: unless VO or VOST was chosen, they start once the French bindings have answered and their
  links were checked, and they are skipped when those already offer both VF and VOST.
- VOST where none exists: when such a provider's link carries original audio, French subtitles are
  looked up once for the work (`ExternalSubtitles`: the public OpenSubtitles catalogue, keyed by
  IMDb ID, resolved from TMDB if needed, web releases first). If there are some, the source becomes
  VOST (`addedSubtitles`), the subtitles are added to the playback and the players select them for
  it, and only for it: VF and VOST streams carry their own, so nothing is selected over them.

Score = quality tier (480p 1, 720p 2, 1080p 3, 1440p 3.5, 2160p 4, unreported 1.5) + version
bonus (VF 1.95, VOST 0.9, VO −10) + check (Verified 0, Alive −0.3, Unchecked −0.6) − provider
playback penalty − 1 for added subtitles (they may be timed for another release). Declared resolutions above 1080p count as 1080p unless the manifest confirms
them (fake "4K" labels are typical of scam links). A remembered version is worth 2.5 (others half
their bonus); a version chosen in the player for the current playback is a hard filter.

`PlaybackPolicy` starts playback at once on a verified VF of 720p or more, after 4 s on a verified
native 720p+, when no native binding is pending, and in any case after 8 s with the best native
source. VO (or VOST through added subtitles) starts once the native bindings are done and gave
nothing better, as soon as one is verified. Acquisition continues afterwards
(6 bindings at once, 2 per provider, 15 s per binding, 30 s in total).

The player switches silently, without a dialog, only to a `Verified`, non-VO, reachable source
at least 0.75 better, unless the user chose a source. On trial its duration must match the
replaced source or the runtime; an error or mismatch returns to the previous source at the same
position and play state, and counts as a playback failure for that provider. French audio tracks
are selected by default.

`ProviderHealth` (persisted) records resolve success, timeouts, latency and playback results. It
orders the queue; providers whose links fail in the player lose up to two quality steps;
repeatedly failing providers (≥ 3 attempts, < 25 % success) only run as a fallback, are skipped
once playback could start and are retried after 6 h. A provider whose search returned nothing
12 times in a row while others found the work rests for 12 h. Source prefetch on the detail page
is disabled: some extensions (AfterDark) open verification dialogs from `loadLinks()`.

### Discovery home (v2)
- Feeds and recommendations come only from providers in the viewer's language; foreign
  catalogues, when enabled, only add sources to works that exist natively.
- Shelves: **Top 10** (this week's TMDB trends, movies and shows interleaved, restricted to
  works a provider offers), **Latest additions**, **Trending**, **Films for you**,
  **Series for you**, then the genre rows. `DiscoveryCatalog` never creates cards on its own.
- The hero shows the first Top 10 work (else the first recent one) with TMDB backdrop, title
  logo, overview, rating, year, runtime and genres in the viewer's language; the hero and
  Continue watching sit at the top of the page. It is built once five works are known (not after
  every feed), and its frame keeps its place while loading, so the page never jumps when it
  arrives.
- TV navigation: Down from the top buttons goes to the hero, Continue watching, then the rows
  (hidden parts are skipped). Each row keeps its place: moving up or down lands on the card last
  focused in that row (else its first visible card), never on the geometrically aligned one,
  which used to scroll the row. Preview rows end with a "See more" card that opens the whole
  shelf (no header button on TV; the Top 10 has none).
- Removing a Continue watching entry (or any history change) recomputes For you without hiding
  the hero or the For you rows meanwhile. The status line follows the header when scrolling.

### Recommended extensions (survey)
`ProviderSurveyInstrumentedTest` (androidTest) measures each installed extension on 47 TMDB
titles (movies and series, French and international, old and recent): search hit, detail
match, links, declared and measured quality, and duration against TMDB. The recommended
catalogue (`CatalogCuration`) follows the results:

| Extension | Found | Playable | Titles only it offers | Kept |
|---|---|---|---|---|
| Movix | 47/47 | 100 % (all ≥ 720p) | 6 | yes |
| Frembed (yorik100) | 98 % | 83 % | 0 | yes, backup |
| Xalaflix | 85 % | 72 % | 0 | yes, backup |
| Frembed (French repo) | 100 % | — (no links) | — | yes, catalogue and home rows |
| AfterDark | 100 % | — (no links) | — | no: verification page during playback |
| FsMirrorLol | 74 % | 64 % (2 titles ≥ 720p) | 0 | no |

FsMirrorLol's pages also fail verification since its site changed (movies are declared as
series, series pages have no title). Coflix, FrenchAnime, Flemmix, Yakmov, Un jour Un Film, Wiflix,
Arte and ReelFren found none of the 47 titles. None of these is recommended. Legitimate French streams matched the TMDB
runtime in every case but one.

On 2026-10-27 the survey ran on the desktop runtime (`tools/desktop/provider-survey.sh`) over 55
original-version and anime candidates (3 titles each), then the best ones over 27 titles (10 films
including October 2026 releases and niche ones, 7 series, 10 anime) against the French catalogues:

| Extension | Playable | With French subtitles | Kept |
|---|---|---|---|
| Movix (French) | 27/27 | — (VOST rarely: 1 film, 5 anime) | already |
| KDesa | 14/17 | 11 | yes, original version |
| Movy | 15/17 (first link in 0.2 s) | 8 | yes, original version |
| AniPM | 10/10 anime | 10 | yes, anime |
| Anv | 10/10 anime | 5 | yes, anime backup |
| KickassAnime | 10/10 anime (up to 2160p) | 9 | no: no year in search results, never merged |
| AniStream | 7/10 anime | 6 | no: fewer titles, no year in search results |
| JustPlay, Multimovies, NetNaija, 4K HDHub, DiviCast | 4–15/17 | 0–6 | no: slower or fewer |
| CineStream | most titles, 40 s per title, many dead links | — | no: too slow |

No candidate found a title Movix lacks: they add the original version, and VOST with French
subtitles, where the French catalogues only have VF. Anime catalogues list English titles: TMDB's
English title is added to the search references and to detail completion, and an absolute
episode list joins the first season within its known length (absolute N is S1EN). Pages of
providers in the viewer's language represent the work (plot, artwork, episode titles) and their
sources come first; an episode borrows missing artwork and runtime from any provider, and a
missing plot only from one in the viewer's language. The extensions of three repositories
(Phisher, CNC, raghav) show a donation request on Android, a `DonationDialogFragment`;
`ExtensionPromptBlocker` removes it before it is built (the desktop never shows extension dialogs,
see `ExtensionPrompts`). 19 candidates did not load on the desktop runtime (Android classes they
need at load) and were not measured; the kept ones run on both apps.

The survey lifts the reviewed-only filter in memory (`CatalogPreferences.surveyAllProviders`),
never in the saved preferences, so a crashing extension cannot leave every extension enabled.

### Measured on the Shield (2026-10-26, French extensions)
| | Before | After |
|---|---|---|
| Breaking Bad search | 16 bindings, misclassified FsMirrorLol separate | 10 providers on one card |
| Detail page shown | after all bindings, up to 30 s | 1.6–2.1 s (complete in 5–10 s) |
| S01E01 playback start | ~25 s (acquisition barrier) | 5–6 s, VF 1080p |
| S01E01 links / with known quality | 26 / 3 | 65–94 / ~85 % |
| Frieren | French and English providers on two cards | one card (IMDb link) |

Debug builds also write `cache/canonical-playback.json` (start, upgrade decisions, rejections
and the ranking the player saw) and add timings/rejections to `canonical-detail.json` and
readiness, checks and rejections to `canonical-sources.json`.

## Boundaries (before the shared engine)

The sections below describe milestones 1–4; where they disagree with the section above, the
shared engine section is current.


- `shared/.../domain/media`: Android-independent `CanonicalMedia`, `ProviderBinding`,
  `MediaAggregator`, `MetadataEnrichmentPlanner` and the `SearchSession` page reducer.
- `app/.../ui/search/aggregation`: maps existing responses to candidates, extracts
  canonical evidence, preserves original navigation payloads, caches detail metadata
  and limits asynchronous detail enrichment.
- `SearchViewModel`: owns query state on the main dispatcher. Provider calls run on
  IO. Results are interleaved in pinned-provider/name order, not completion order.
  Search/page requests have generation and attempt tokens. Enrichment jobs belong to
  the current query and are cancelled on clear/replacement/destruction.
- `AggregatedSearchResponse` delegates the existing card interface and supplies a
  canonical display title. Its complete binding context is serialized into navigation. No extension API,
  `library/src` or API snapshot changes are needed.

## Matching evidence and guards

1. IDs are namespaced. Shared IDs take precedence; conflicting IDs in a namespace
   block merging, including indirect conflicts. TMDB movie/TV IDs never collide.
   Generic `SearchResponse.id`, unknown provider URL numbers and arbitrary JSON remain
   opaque. Canonical TMDB/IMDb URLs supply typed IDs.
2. Three optional adapters recognize reviewed TMDB route contracts by implementation
   class (Movix, AfterDark, AMSC Frembed), same origin, exact path, supported media
   family and a canonical TMDB poster. Display names and mutable domains are not
   hardcoded. Unsupported/changed protocols opt out. Source links and supported
   contracts are documented in `SearchProviderIdentityAdapters.kt` and covered by tests.
3. Without an ID, title/verified alias, media family and edition must agree. Known
   years equal or one apart suffice (exact preferred). Missing years need independent artwork evidence. Different
   known years and conflicting IDs remain guards. Season entries may join the whole
   series only with a shared trusted show ID; part/cour entries remain separate.
   An anchored season suffix supplies a title alias, never identity evidence alone.
4. Normalize case, whitespace and punctuation; for matching, also fold accents,
   `&`/and/et and Roman sequel numbers (identities keep accents); preserve subtitles and
   sequel numbers. Strip explicit trailing language tags and recognize parenthesized release
   years. Unknown kinds do not become title-only matches. Candidate anchors are
   evaluated before incomplete entries, then original rank order is restored.
5. Exact artwork identities accept only canonical HTTPS TMDB asset URLs and disregard
   image size. Lookalike hosts, credentials, arbitrary provider images and named
   placeholders are rejected. A poster is not a TMDB movie/TV ID by itself.
6. For unresolved identical titles/types, near-identical copies of a poster may have
   different TMDB asset names. A bounded thumbnail comparison uses 16×24 RGB samples,
   maximum mean channel difference 5/255, aspect-ratio tolerance 0.03 and minimum
   spatial contrast. Uniform images are rejected. This adds visual corroboration;
   it never overrides title/type, conflicting year/ID or edition guards. It is not
   semantic image matching and does not match unrelated titles.

## Canonical metadata and bounded enrichment

Provider results appear immediately. Aggregation optionally reuses CloudStream's
existing TMDB access (already used by suggestions and `TmdbProvider`), without reading
extension credentials or requiring another account/key. Per query, at most two TMDB
search pages supply localized/original titles, years, types, IDs and primary artwork.
Only candidates with a provider binding appear; metadata search never creates
unavailable catalogue entries.

`MediaReferenceResolver` requires a unique compatible reference plus an existing ID,
exact title/alias with known equal year and family, or exact title/alias with verified
artwork. In the latter case authoritative artwork can correct a search response that
incorrectly labels a series as a movie. No bare-title reference assignment occurs.
Ambiguous remakes and contradictory IDs remain unresolved. Canonical display titles
use the localized reference title; original provider objects are preserved.

For unresolved season-scoped titles, at most six additional TV season-image requests
verify that a provider’s season artwork belongs to the reference show. Each request
has a three-second deadline, two execute concurrently, and results are cached for a day.
This can resolve the parent show ID while keeping the binding’s season scope.

For remaining exact-title matches, at most six reference poster-list requests identify
alternate posters. At most six small TMDB thumbnails compare near-identical encodings.
These stages use two concurrent requests, three-second and 2.5-second cooperative
per-request deadlines respectively. Search has a five-second metadata deadline;
lookup caches are bounded and live for a day (failed thumbnails for a minute).
The APIs are documented in [TMDB multi-search](https://developer.themoviedb.org/reference/search-multi),
[TV images](https://developer.themoviedb.org/reference/tv-series-images) and
[movie images](https://developer.themoviedb.org/reference/movie-images).

After a 400 ms coalescing delay and metadata stages, unresolved duplicate-title
candidates can use their existing provider detail loader: at most 12 attempts per
query across pages, two concurrently, five seconds each. This also verifies erroneous
search types. Known-year remakes, unique titles and already resolved cards need no
provider detail requests. Failed attempts are not rescheduled within the query.
Successful detail metadata is cached for ten minutes (at most 200 bindings), using the
repository's stable identity even if an extension changes its runtime domain.
Provider detail implementations may fetch many episodes and must cooperate with
cancellation; the core cannot forcibly stop noncooperative plugin code.

Every stage belongs to the query's coroutine lifetime. Replacement/clear cancels it;
cancelled work cannot publish into the next query. Updates regroup progressively.
If metadata services/providers fail or evidence remains ambiguous, retain separate
results rather than manufacture an identity. Translations without supplied aliases,
matching IDs or sufficient independent evidence can still remain distinct.

Debug builds also write a debounced `cache/search-aggregation.json` for device testing.
It records titles, years, kinds, evidence and binding indexes, with no navigation
payloads, headers or API keys. This file is not written by release builds.

## Canonical detail/episode boundary (milestones 3–4)

`app/.../ui/result/aggregation` owns the detail layer. `CanonicalDetailRequest` carries
page URLs, provider keys, canonical evidence and season scopes through a JSON Bundle.
It is a snapshot of bindings known when the card is opened; reopen the card to include
providers that finish searching later.
It has no episode data, artwork binaries or video links. A private persistent index
aliases provider URLs and reliable external IDs to the saved canonical context, so
bookmarks/resume survive process death and a newly learned ID does not reset progress.
Bindings already known to that context can fill gaps on a later search. Contradictory
IDs/known years do not reuse the saved identity. This does not migrate pre-fork history
or reconcile progress from already independent saved canonical records. IDs remain the
upstream 32-bit integer storage format and inherit its theoretical hash collision risk.

`CanonicalDetailLoader` loads three bindings concurrently, with 12 seconds per request
and 30 seconds including queueing. Failures/timeouts are isolated. `CanonicalDetail`
rejects incompatible response families or contradictory detail IDs, then copies metadata
and episodes into app-owned `MovieLoadResponse`/`TvSeriesLoadResponse`/`AnimeLoadResponse`
projections. The extension responses in `APIRepository`'s cache remain untouched.
A partial page displays a neutral availability message; the provider label is hidden
on canonical phone/TV details, while its internal value is retained for existing share links.
Shared links still target one existing provider; cross-device canonical sharing is not introduced.
All unavailable bindings produce a retryable detail error.
Unknown/live/custom result kinds keep the existing detail flow.

`shared/.../CanonicalEpisode.kt` defines `CanonicalEpisode`, `EpisodeProviderBinding`,
`EpisodeAggregator`, explicit-label `EpisodeNumbering` and deterministic `CanonicalIdentity`. Episode matching uses an
explicit season (including specials 0) and positive episode number. Dub/sub versions
are retained on bindings and do not split the numbered episode identity. An anchored `Épisode 3` or `S02E03` label may supply missing coordinates;
arbitrary titles, bare numbers and list order do not. VF/VOSTFR suffixes are accepted
as labels but do not assign an unknown language. Provider `SeasonData.displaySeason` is applied first. An explicit binding scope
can fill a missing season; contradictory scopes and missing numbering stay independent
in an **Episodes to identify** section. List index and episode title are never cross-provider
matching evidence. Absolute anime numbering is not converted to season numbering.

A private projection token connects each UI row to its canonical episode. Before publishing
`ResultEpisode`, the token is replaced with that episode’s first original `apiName`/`data`,
plus a serializable list of `EpisodeSourceBinding` alternatives, including track and
provider language. `RepoLinkGenerator` sends canonical episodes through the bounded
source loader; legacy episodes retain the original single-provider path. New numbered
episode IDs derive from the saved canonical identity and season/episode. Existing
milestone-3 canonical IDs (`None`/`Dubbed`/`Subbed`) are retained as aliases. Once per
account/episode, the greatest saved legacy position and watched state are copied if
no new state exists. A migration marker prevents a later unwatch action resurrecting
old state. Resume actions also recognize those aliases. The parent media ID is unchanged.
Unspecified audio is not guessed from a provider's locale alone. Unlocated episode IDs depend on the original binding/index and may change if that
provider reorders its unidentified list. Loading a new detail cancels the previous job;
clearing the ViewModel cancels detail and link work. Extension cancellation is cooperative.

Debug builds also write `cache/canonical-detail.json`: requested/loaded/rejected binding
counts, seasons, episode coordinates/tracks and binding counts. URLs, opaque payloads,
headers and video links are excluded. Both diagnostics files are local debug evidence,
not product telemetry.

## Canonical playback and versions (milestone 4)

`app/.../ui/player/aggregation` owns the app-only source acquisition and metadata:

- `CanonicalSourceLoader` resolves each binding by provider name/domain, passes its
  opaque `data` unchanged, and acquires up to three providers concurrently, with 15
  seconds per binding and 30 seconds including queueing. One declared VF/VOST/unknown
  binding starts before filling the queue with more unknown audio providers. Failures
  and cooperative timeouts preserve other providers' links and already emitted partial
  links. Cancellation closes callback gates; old jobs cannot publish into a new episode.
- Positive completed loads are cached per full binding/payload/version and casting
  mode for 20 minutes, at most 200 entries. Concurrent identical loads share a mutex.
  Failure, timeout and false-return partial results are retried on the next request;
  forced reload also invalidates positive results. No opaque payload is written to logs.
- (Superseded on 2026-10-26: sources are published progressively and playback starts
  early, see "Shared aggregation engine".)
- `PlaybackSources` creates app-owned copies, preserving headers, referrer, extractor
  verification data, external audio and playlist subtypes. A weak metadata sidecar
  retains provider/version/order. Exact deduplication includes URL, version, type,
  referrer, header values, external audio, playlist segments and extractor data. Signed
  URLs are never rewritten. Duplicate requests retain the highest known quality.
  Subtitle deduplication preserves differing credential contexts.
- Explicit VF/French or VOST/Subbed labels identify versions; a French provider's
  declared Dubbed binding may supply VF, and a Subbed binding may supply VOST. Locale
  alone, a non-French dub, a bare VO label or contradictory multi-version labels do
  not prove VF/VOST. Such sources remain **Unspecified audio**. VOST does not guarantee
  French subtitle availability; supplied subtitles/native tracks remain selectable.

(Superseded on 2026-10-26 by the score in "Shared aggregation engine".) The canonical player defaulted to **Auto**: VF first, then VOST, then unspecified
sources, ordered within that preference by the existing quality-profile priority,
reported resolution and stable binding/name/URL ties. This chooses the highest known
quality under default profiles while preserving explicit upstream profile priorities
and source exclusions. Unknown/adaptive quality is not reported as a verified resolution.
If a saved version preference is absent on the next episode, selection uses Auto for
that episode rather than inventing a source or reporting no links.

**Tracks → Version** lists Auto and only the available versions (VF, VOST, unspecified).
Selection applies on **Apply**, persists per account and chooses the best eligible link.
Changing streams uses `sameEpisode` and explicitly restores the prior play/pause state;
position is preserved. The Tracks button remains available with only one embedded audio
track. Embedded codec/channel/track selection is retained separately in the same dialog.
The detail page exposes one episode list with no dub/sub selector.
Position preservation uses elapsed time; different provider cuts or intros are not
aligned to the same scene.

`cache/canonical-sources.json` is Debug-only and records source counts, provider outcomes,
version/quality histograms and timings, never URLs/headers/payloads. Quality uses provider
metadata, not a bandwidth probe or a verification of the video's native resolution.
Existing upstream mirror-error behavior remains; no additional reliability model,
refresh/retry policy or dedicated provider-level fallback was added here. Casting and
downloads reuse acquisition but have not been validated for the in-player version UI.

A reproducible native test uses generated local clips (VF 720p, VF 1080p, VOST 1080p
with burnt-in French test subtitles), served on WSL loopback through `adb reverse`:

```sh
ANDROID_SERIAL=192.0.2.10:5555 tools/canonical-playback-fixture.sh
```

It builds/installs only Debug and its test APK, injects in-memory test providers,
checks the common detail and absent language selector, decodes 1080p on the device,
opens the real Tracks button, switches VF→VOST at 9 seconds and checks pause/position.
It saves screenshots and a JSON report under `app/build/android-dev/milestone4/`.
FFmpeg must expose libx264/AAC and the subtitles filter (`CLOUDSTREAM_FFMPEG` override).
No Windows tool or external media service is required for this native fixture test.
On this Shield firmware, the shell `app_process` running `am instrument` sometimes
exits 139 after JUnit has reported success: the crash buffer identifies UID 2000 and
NVIDIA's EGL shutdown destructor, rather than the application process. The script
records the ADB status and crash buffer, accepts that status only alongside a complete
JUnit success result, and still fails on test failures or other command errors.

Final Shield validation also uses installed French extensions: Breaking Bad has one
2008 search card with 16 bindings (71 received results, 17 total cards). Its common
detail loads 21 known bindings including persisted context, with 62 main episodes
and nine specials, instead of 166 episode/version rows. S1E1 combines 11 episode
bindings into 26 links (three VF, two VOST, 21 unspecified) and four subtitles despite
one failed and two timed-out providers. Real playback switches from VOST 640×360 to
Auto selecting VF 1920×1080, retaining pause and the 1:51 elapsed position. These are
observed snapshots, not availability guarantees. Local reports, screenshots and APK
checksums are under `app/build/android-dev/milestone4/README.md` and `report.json`.

## Paging, identity and validation

The footer requests the next page of each unfinished provider and retries failed pages
without losing their earlier results. It remains available if a page only adds bindings.
Repeated URLs deduplicate within a provider. Late results from old searches/attempts
cannot update the current session. Group IDs survive paging/enrichment merges where
possible and split groups never share a card ID. They are session-local, not saved
playback/favourite IDs. Enrichment can remove duplicate cards; result selection during
these updates needs device-level TV checks.

```sh
source ~/.config/cloudstream/env.sh
./gradlew :shared:jvmTest :app:testStableDebugUnitTest :app:assembleStableDebug --max-workers=4
```

The latest host validation passes 179 tests (97 shared, 82 app). Tests cover
remakes, missing years, canonical references, visual artwork, contradictory IDs, punctuation,
media families, editions, ordering, stable identities, opaque payloads, enrichment
budgets/timeouts/concurrency/cancellation, both poster layouts, navigation without a
provider dialog and pagination retries. Run the real-provider checks on the Shield for
`Breaking Bad`, then an ambiguous title/remake, a film, pagination and opening a card
with the remote. Host tests do not establish third-party search/playback availability.

The full Android Lint scan was previously interrupted after approximately fifteen
minutes, with no final report; it is not recorded as passing. `library:jvmTest` has two
known pre-existing `EpisodeDateTest` failures in Europe/Paris, outside this work.

## Development device history

The sections below retain earlier setup and milestone-1 runtime findings. Current
milestone-2 build/device evidence is recorded separately after validation.

## Shared WSL emulator

The local SDK now includes Android Emulator and an Android 15 AOSP x86_64 image.
The `cloudstream_api35` AVD uses 2 GB RAM, two CPU cores and software graphics.
On this WSL host, WSLg and `/dev/kvm` are present; the current user still needs
KVM group membership and the Ubuntu GUI/audio dependencies:

```sh
sudo apt-get update
sudo apt-get install -y libpulse0 libxcb-cursor0 libxcb-icccm4 libxcb-keysyms1 libxkbcommon-x11-0
sudo usermod -aG kvm "$USER"
```

`tools/android-dev.sh emulator` starts the emulator in a WSL terminal, with its window
displayed through WSLg. It uses `sg kvm` when necessary to refresh group membership
without restarting VS Code or other WSL processes. Keep that terminal open.
From another terminal in the repository:

```sh
tools/android-dev.sh deploy    # Build, install/update the debug APK, then open it
tools/android-dev.sh capture   # Screenshot, UI hierarchy, app logcat, device properties
tools/android-dev.sh test      # Host JVM/Robolectric tests; no emulator required
```

Captures are saved under `app/build/android-dev/`. Device commands select the sole
emulator by default; set `ANDROID_SERIAL` explicitly when using several devices.
Hierarchy capture retries transient accessibility failures; screenshots and logs are
retained even if a Compose screen does not expose its hierarchy.
KVM acceleration and Android 15 boot have now been verified on this WSL host. The debug
APK installs and opens through the exported account-selection launcher. Screenshot,
UI hierarchy and app-logcat capture also work. Later extension setup exposed host
emulator RenderThread crashes; see the runtime limitations below. MegaRepo has since been configured on this development device. Its bootstrap adds
repository listings; French extensions are installed separately. The current setup
contains 15 French extensions across six repositories, plus the MegaProvider
bootstrap. This includes the four beta extensions in the French providers repository.

The setup inventory and installation report are kept outside Git under
`~/.cache/cloudstream-setup/` (`megarepo-french-inventory.json` and
`french-extension-install-report.json`). Downloaded artifacts were checked against
published hashes when available; ZIP/DEX/manifest contents were checked for all of
them. Original app preferences were backed up before installing. FStream's repository
endpoint returned HTML inside Android, so its artifact was downloaded through WSL
and installed into the debug app's normal extension storage. That endpoint may also
prevent its automatic updates. Some AMSC catalog versions differ from their actual
artifact manifests; the installed version follows the artifact, as in CloudStream's
normal loader.

Installation does not establish that each provider's search or playback endpoint is
operational. These extensions belong to the local development emulator and are not
bundled in the APK or committed to the repository.


Runtime verification after extension installation confirmed successful native loads
for 13 French extensions plus MegaProvider. FStream fails because its compiled plugin
references the removed core class
`com.lagradost.cloudstream3.syncproviders.InAppAuthAPIManager`; Movix startup was not
confirmed before the host emulator exited. No compatibility shim was added to the
core. The local report is `app/build/android-dev/extensions-installation/README.md`,
with installation metadata and startup logs alongside it.

The host emulator is currently unstable: 37.2.12 and 36.3.10 both exhibited
RenderThread SIGSEGV exits. Software/hardware rendering, Vulkan disabled, no snapshots,
and reduced display resolution did not resolve the issue. The installed SDK emulator
is now 36.3.10; the previous one is backed up at
`~/Android/emulator-backups/emulator-37.2.12`. The AVD currently uses a 720×1560 display
at 320 dpi and four virtual CPU cores. All app data and installed extensions were
preserved. The command-line workflow remains available, but reliable interactive tests
require resolving this host emulator issue.


### Physical Android TV test device

A SHIELD Android TV running Android 11 is now connected over network ADB. The debug
APK installs alongside the user's normal CloudStream package. ADB installation,
launcher resolution, UI hierarchy, screenshots and app logs work on this device.
15 French extensions plus MegaProvider are installed in the debug app; 14 French
plugins load successfully, including Movix. FStream still requires a legacy core class
and cannot load. Device-specific evidence is kept outside Git under
`app/build/android-dev/shield-extensions-installation/`.

For each terminal session, explicitly select the network device before running the
existing workflow (replace the address with the Shield's current address):

```sh
source ~/.config/cloudstream/env.sh
adb connect 192.0.2.10:5555
export ANDROID_SERIAL=192.0.2.10:5555
tools/android-dev.sh deploy
tools/android-dev.sh capture
```

The first connection requires accepting the authorization prompt on the TV. Keep the
Shield on the same local network; a DHCP address change requires updating the address.
Without `ANDROID_SERIAL`, the tooling intentionally continues to target an emulator.
The `stop` command is for emulators only; do not use it to disconnect this physical
device. Use `adb disconnect "$ANDROID_SERIAL"` instead. The existing CloudStream
installation and its preferences have not been changed. The launcher command wakes the
selected device before opening the debug app.


A first real-provider search for `Breaking Bad` on the Shield returned results and
confirmed that the aggregated card layout is used. Identical titles still appeared
on separate provider cards, including `Breaking in Badly` and `ISMO`. This is a
matching gap to investigate using the captured real data; successful provider loading
and passing synthetic tests do not validate deduplication for these providers. Evidence
is stored with the device installation report (`search-screen.png`,
`search-window.xml`, `search-logcat.txt`).


## Milestone 2 device validation (2026-10-25)

The debug APK is built and deployed on the Shield. All 68 targeted tests pass
(40 shared-domain, 28 app; no failures/errors/skips). `library/src` and public API
snapshots remain unchanged. The existing CloudStream installation is separate.

Real searches confirmed:

- `Breaking Bad`: 72 provider responses → 23 cards; the complete series is one card
  with six bindings, including the originally misclassified movie response and the
  visually identical poster with a different asset name. Season-scoped entries remain
  distinct for the next milestone.
- `Barbie`: 153 responses → 54 cards; Barbie (2023) is one card with ten bindings.
- Pagination: 193 responses → 66 cards, retaining that Barbie card ID and all ten
  bindings. Every received binding is represented exactly once in all these snapshots.
- No provider labels or provider-filter control appear in the captured aggregated
  search hierarchies. The microphone announces voice search.
- D-pad navigation opens the original Breaking Bad detail page with episodes, without
  a provider dialog. This validates the navigation bridge, not canonical episodes or
  cross-provider video playback.

Screenshots, sanitized candidate/group evidence, UI hierarchies, logs, APK hash and test
summary are in `app/build/android-dev/milestone2/README.md` (local, ignored by Git).
The unrelated legacy provider/episode/date issues described above are not fixed by
this milestone. The working tree contains both milestone-1 and milestone-2 changes;
no commit, upstream push or PR has been made.

## Milestone 3 device validation (2026-10-25)

The Debug APK was built with 87 passing host tests (48 shared-domain, 39 app) and
installed on the Shield. No extension API/library snapshot was changed.

- Breaking Bad: 72 provider responses → 17 cards; its single 2008 series card keeps
  16 page bindings, including the five season-scoped entries from each of two providers.
  A whole-series poster is used. A regression test ensures adding proven seasons
  does not block missing-ID whole-series artwork matches or lend identity to an
  unproven different season.
- The first canonical detail snapshot loads 16/16 bindings, with no rejected/unlocated episodes.
  A subsequent check on the final APK loads 15/16, displays partial availability and
  retains all 62 main episodes plus nine specials. The complete and partial snapshots
  are recorded separately in the device report. Its unspecified audio track has the main seasons 7/13/13/13/16
  (62 episodes, seven source bindings each in the full snapshot), plus nine specials. Dubbed/subtitled
  selections remain distinct (62/33 rows), yielding 166 episode/track entries over
  71 unique season/episode coordinates. Season selection was checked with native UI.
- Barbie (2023) exercises movie detail aggregation and partial availability. The
  device report records accepted, rejected and failed binding counts. Failed or
  incompatible pages do not prevent opening the common film page.
- Host fixtures verify original `loadLinks()` payload/provider routing (including
  name collisions), stable progress IDs, persistent navigation aliases, serialization,
  missing numbering, metadata contradictions, bounded fan-out and cancellation.

Captures, sanitized diagnostics, the APK hash, test counts and manual reproduction
steps are in `app/build/android-dev/milestone3/README.md` and `report.json` (local,
ignored by Git). Actual video playback, casting, downloading and physical-phone UI
were not validated. Source fan-out, quality scoring and automatic playback fallback
are not part of this milestone. The working tree retains uncommitted milestones 1–3.


## Configuration automatique du catalogue

Le wizard sélectionne les langues et catégories vidéo avant les téléchargements
d’extensions. Le même menu est accessible depuis Découvrir et Réglages → Général.
Voir [catalog-setup.md](catalog-setup.md) pour la sélection, les limites et les tests.


## Expanded shelf regression

`HomeShelfPagerTest` uses neutral in-memory films to check retention past the old limits,
first-page cache cursors, contributor-only pagination, canonical bindings, duplicate
removal, repeated pages, retry, timeout and cancellation.

```bash
ANDROID_SERIAL=192.0.2.10:5555 tools/home-shelves-fixture.sh
```

The native fixture opens a shelf with the remote-control centre button, verifies a
24-card preview and a 30-card multi-column grid, retries a simulated failure, expands
to 60 unique films, moves D-pad focus beyond the first twelve cards, skips a duplicate
canonical page, displays newly loaded cards, stops on a repeated page and reopens the retained list. It uses a
temporary profile and neutral in-process providers. No remote media is requested.
Artifacts are exported to `app/build/android-dev/home-shelves/`.
