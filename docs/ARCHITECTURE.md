# Aura architecture notes

Single Gradle module (`:app`), Kotlin 2.x, no DI framework. Compose TV for UI, Media3 for
playback, Retrofit + kotlinx.serialization for Jellyfin. `minSdk 26`, `targetSdk 35`.

Package root `com.hans.aura`:

```
AuraApp.kt          Application: JellyfinClient, Prefs, LibraryCache, MirrorLink, Coil config
MainActivity.kt     single Activity: mirror-link start/bind, BACK interception, debug deep links

data/               Jellyfin I/O, caching, preferences, lyrics, device identity
  JellyfinApi.kt      Retrofit interface (one method per endpoint)
  JellyfinClient.kt   OkHttp + shared Json; one Retrofit per server URL; sign-in paths
  JellyfinModels.kt   @Serializable DTOs, incl. CapabilitiesDto and UserData
  AuthStore.kt        session + remembered server address (separate keys)
  Prefs.kt            aura_prefs: app settings, library selection, resume points
  LibraryFilter.kt    the only path for browse queries (library selection lives here)
  LibraryCache.kt     24h-TTL browse cache + persisted refresh stamps
  PagedLibrary.kt     in-process page store for the library tabs
  AtmosCatalog.kt     one whole-library scan -> Atmos album ids AND track ids
  AtmosBrowse.kt      the "Atmos only" browse path: hydrate by id, sort, page
  AtmosFilter.kt      the "only show Atmos content" preference as a browse filter
  Favorites.kt        server-backed favourites + optimistic overlay
  Mixes.kt            Instant Mix ("radio") entry points
  LyricsStore.kt      /Audio/{id}/lyrics -> normalized LyricLine list
  Lrclib.kt           optional online lyrics fallback
  DeviceNames.kt      Settings.Global.DEVICE_NAME (fallback Build.MODEL)

playback/           the Media3 session and the Jellyfin websocket
  PlaybackService.kt  MediaSessionService owning ExoPlayer; playback reports
  PlaybackRemote.kt   capability POST + websocket: inbound commands

ui/                 Compose screens
  AppRoot.kt          back-stack, TopBar, NowPlaying bar, overlay hosts, session exit
  player/PlayerConnection.kt  controller wrapper: state flows, queue mutations, item registry
  home/ library/ genres/ collection/ nowplaying/ queue/ search/ playlist/ login/ settings/
  nowplaying/LyricsPane.kt    lyrics view: LRC parsing, auto-scroll, offset, fallback
  common/             Components, ContextMenu(s), ConfirmDialog, FitText, BioDialog, NavRequests
  theme/              Theme (static palette) + Dynamic (per-image palette)
  MirrorLink.kt       LAN HTTP server for external remotes
  MirrorPairing.kt    on-TV allow/deny prompt
  DebugNav.kt         debug-build deep-link request channel
```

## Accounts and sign-in

`AuthStore` keeps two things apart: the session (`aura_session`) and the last server
address (`last_server_url`). "Sign in again" drops only the token and the login screen
comes back prefilled; "Change server" drops both. `JellyfinClient.api` is a **getter**
keyed by base URL — Retrofit instances are bound to a URL, so a server change must rebuild
it, otherwise calls keep going to the old host.

Two sign-in paths, both ending in `AuthStore.save`:

- **Password** — `POST /Users/AuthenticateByName`.
- **Quick Connect** — `LoginScreen` calls `/QuickConnect/Enabled`, `/QuickConnect/Initiate`,
  polls `/QuickConnect/Connect?secret=` every 2 s, then
  `POST /Users/AuthenticateWithQuickConnect`. These run **before** a session exists, so they
  go through `JellyfinClient.apiForServer(url)` (the `api` getter resolves from the session
  and would point at localhost). The panel is a screen-root overlay with BACK-to-cancel and
  a cancellable polling job.

Every exit path (sign out, sign in again, change server) funnels through one function in
`AppRoot`, in this order: stop playback and release the controller
(`PlayerConnection.teardown()`, which also stops `PlaybackService`), evict `LibraryCache`,
`PagedLibrary`, `AtmosCatalog`, `PaletteCache` and the player item registry, then drop the
credentials. Getting that order wrong leaves the old server streaming under a login screen,
or resolves new-server item ids against old-server metadata. Destructive exits are behind
`ConfirmDialog` (Cancel focused first, focus trapped, BACK dismisses), because a broken
login on a TV is unrecoverable without adb.

## Library selection

Hidden libraries are enforced in the **data layer**, not the UI. `LibraryFilter.queryItems`
and `queryAlbumArtists` are the only browse entry points; with nothing hidden they pass
straight through to the original single query (identical cost and behaviour), and with a
hidden set they issue one recursive `ParentId` query per visible library and merge the
pages client-side.

Rules that make it correct rather than approximate:

- Music item types (`Audio`, `MusicAlbum`, `MusicArtist`, `Audiobook`) only fan out into
  libraries whose `CollectionType` is `music` or unknown. A movies/tvshows/playlists root
  *does* return items of the requested type, so pointing a music query at them leaves
  albums on the grid with every music library switched off.
- Direct lookups are exempt: `getItem`, `/Items?Ids=`, playlist items and anything the
  remote "Play On" path fetches. Filtering those empties detail screens and breaks casting.
- Every cache key carries `LibraryFilter.cacheSuffix()` (empty when nothing is hidden), so
  data fetched under one selection is never served under another.
- Any change to the selection clears `LibraryCache`, `PagedLibrary` and `AtmosCatalog`;
  `AppRoot` bumps a `refreshKey` so open screens reload.

## Playback pipeline

`PlayerConnection` builds a `MediaController` against `PlaybackService` through a
`SessionToken`; connecting to the `MediaSessionService` is what brings it up, and it stays
resident once started. `PlaybackService` owns ExoPlayer and constructs `PlaybackRemote` in
`onCreate`. `PlayerConnection` wraps the controller and exposes `StateFlow`s (current item,
queue, index, isPlaying, position, duration, shuffle, repeat, sleep remaining, smart
shuffle) that every screen collects.

media3 `MediaItem`s carry only `mediaId` + stream URI. Full `JellyfinItem` objects live in
an in-process `Map<mediaId, JellyfinItem>` inside `PlayerConnection`, because the models are
kotlinx-serializable, not `java.io.Serializable`, and media3 1.6 exposes no extras field.
Remote playback ("Play On") bypasses that registry, so `syncItem` fetches any unknown id and
publishes it — the UI never assumes the queue was started locally.

Streams are direct-played: no transcoding, no decoding in-app, so the E-AC-3 JOC bitstream
reaches the AVR untouched. Volume is therefore not an app concern — `SetVolume` is absent
from advertised capabilities, inbound volume commands are logged and dropped, and the player
is pinned to `volume = 1f` on construction and every item transition.

`PlaybackRemote` registers the session with the server (capabilities are built **twice**:
`CapabilitiesDto` in the model and a hand-written JSON body in `PlaybackRemote`), then holds
the websocket open. Inbound commands map onto the `Player` on the main handler; outbound
playback reports keep the server session alive and carry `RepeatMode` / `PlaybackOrder` so
web remotes mirror Aura's shuffle and repeat state.

### Queue behaviour

`playQueue(items, startIndex, playWhenReady, resume)` is the single entry point. Callers
state intent: a row tap passes `resume = true`, Play/Shuffle/Radio pass `resume = false`.
Tapping the row that is already playing from the same queue opens Now Playing instead of
restarting; that check compares the tapped ids to the live queue as a multiset plus Aura's
own order snapshot, so a reorder does not turn the tap into a restart while a remote Play
Next still falls back to the strict comparison.

Mutations (`moveInQueue`, `clearUpcoming`, `removeFromQueue`, remote Play Next/Last) run on
the main thread, keep the id snapshot in step, and let `onTimelineChanged` republish the
mirror — the queue screen renders the player's real order, never a separate list.

- **Resume** — positions are remembered **locally** (`Prefs.ResumePoints`, 30-day TTL,
  400 entries) because Jellyfin 12.2.0 never writes `UserData.PlaybackPositionTicks` from
  the report endpoints. Saved from the reporter tick and on item switch; a deliberate tap
  resumes when the point is past 15 s and not within 10 s of the end.
- **Autoplay** — on `STATE_ENDED` with repeat off and the preference on, an Instant Mix
  seeded from the last track is appended (up to 20 new ids). Not gated on `playWhenReady`:
  a player that just finished reports it false, and gating on it silently killed the queue.
- **Smart shuffle** — `smartOrder()` is a pure function (least-played / longest-unplayed
  first, ~20 % of slots for well-loved tracks); only the items after the current one are
  reordered, so the playing track and its position are untouched, and the original order is
  kept for "Normal order".
- **Sleep timer** — `startSleepTimer(seconds)` / `sleepRemaining`; expiry **pauses** and
  leaves the queue intact. The control lives on the queue screen, not Now Playing.

## Discovery

- **Radio** — `Mixes.playMix(kind, seed)` wraps the Instant Mix endpoints and calls
  `playQueue(..., resume = false)`; entry points are the track context menu, album and
  artist headers, the genre page and the Now Playing transport row.
- **Genres** — the grid is built from the `Genres` field of the library's own tracks, not
  from the server's `MusicGenre` table (that table keeps every genre the library ever saw,
  most matching nothing, and `Genres=` compares the full string). Scanned pages are cached;
  every entry is guaranteed non-empty.
- **A–Z** — `nameStartsWithOrGreater` restarts the paged query at a letter; the cache key
  carries the letter so BACK restores that list. The jump is SortName+Ascending only, so
  picking any other sort or filter CLEARS the letter first — otherwise the pill looked
  selected while the list stayed alphabetical.
- **Home** — shelves reuse `ItemShelf` and the 24h cache; "Your Atmos" reads the existing
  `AtmosCatalog` rather than triggering its own scan.

## Favourites

Favourites are server state (`POST|DELETE /UserFavoriteItems/{id}` returning `UserData`)
with an in-process optimistic overlay in `Favorites` that reverts on a non-2xx and evicts
the affected cache keys on success. The heart on cards and rows is an **indicator**: a
focusable child inside a focusable card cannot be reached with a D-pad. The actions are the
first row of the long-press context menu and the Now Playing transport button.

## Lyrics

`LyricsPane` owns the LRC parse (via `LyricsStore`), the top-anchored auto-scroll
(current line held ~3 line-heights below the top, second instant scroll pass after the
animated one), a 4 s auto-follow pause on manual scroll, and per-track timing nudges
(`-`/`+` on the D-pad, ±0.5 s, stored as `lyrics_offset_<trackId>`).

When Jellyfin has nothing and "Look up lyrics online" is on, `Lrclib` queries
`lrclib.net/api/get` (falling back to `/api/search`), identifying itself with
`Lrclib-App-Id` from `BuildConfig.LRCLIB_APP_ID` (root `.env`, never in source) and
`Lrclib-Client`. Results are filtered by duration (±2 s) and instrumentals are skipped;
hits and misses are cached per track, errors are not. With neither source, the pane shows a
real empty state instead of a blank area.

## Search

One query with `IncludeItemTypes=Audio,MusicAlbum,MusicArtist,Playlist`, split client-side
into three stacked shelves — no tabs. The field auto-focuses on entry (retry loop), ENTER
and ImeAction both submit without dropping focus, recent terms live in `Prefs` as chips that
re-run on OK and delete on long-press, and the mic button fires `RecognizerIntent` behind a
`resolveActivity` check so a device without a recogniser just shows a one-line notice.

## Theming

Two colour sources, deliberately split:

- **Static palette** (`ui/theme/Theme.kt`) — warm-orange accent on Home, library grids and
  the TopBar. These screens never tint.
- **Dynamic palette** (`ui/theme/Dynamic.kt`) — only artist, album and Now Playing
  screens. A 96 px decode is scored for hue harmony with the dominant swatch (greys
  skipped, saturation capped), darkened for contrast, and cached process-wide by image
  URL. Values stored in the cache are already processed — call sites use `primary` as-is.

The secondary colour is threaded into shared components (`TrackRow`, `ArtworkCard`,
`AtmosBadge`, `SectionHeader`) as an optional parameter defaulting to the global accent,
never a `CompositionLocal` that would leak the tint into other screens.

## Navigation and focus

Navigation is a manual back-stack of a private sealed `Screen` in `AppRoot.kt`
(`Home`, `Artists`, `Albums`, `Tracks`, `Playlists`, `Search`, `Settings`, `NowPlaying`,
`Queue`, `Genres`, `Genre(name)`, `Collection(item)`, `Artist(item)`) with push/pop. A TV
has no pointer, so there is no router or deep-link graph; every destination is reached by
D-pad.

BACK is intercepted in `MainActivity.dispatchKeyEvent` (`ACTION_DOWN` + `repeatCount == 0`)
because Compose focus groups consume it as "move focus out" before any `BackHandler` runs.
The dispatcher then routes to the enabled `BackHandler` (pop, dismiss overlay, root tab ->
Home) or the default (exit, Home only). Overlays declare their `BackHandler` only while
open, and only one can be enabled at a time.

Screen state survives BACK via `rememberSaveableStateHolder().SaveableStateProvider` keyed
by stack index + screen key. Exact-focus restore is a second layer: `FocusKeeper` holds
per-item `FocusRequester`s, each card/row reports the focused id via `onFocused`, and
`RestoreFocusOnBack` re-requests that item ~350 ms after the pop (scroll settles). A
group-level `requestFocus()` alone lands a row above the target, because the saved scroll
offset applies after focus is placed.

`AppRoot` owns the persistent TopBar, the now-playing bar and the overlay hosts.
`OverlayVisibility.dialogOpen` is set while a full-screen overlay is composed, which
suppresses the now-playing bar so it cannot draw over (and take focus from) a dialog.
Overlays are rendered as the last child of a screen-root `Box`, trap focus with
`FocusRequester.Cancel`, request focus shortly after composition, and are the only
sanctioned place for a text input.

## Data and caching

`LibraryCache` keys browse queries and holds a 24h TTL; expired entries refetch lazily. It
also persists last-refresh timestamps, because `/ScheduledTasks` and `/ActivityLog/Entries`
are admin-only and unreadable with a normal user token. Any server mutation (playlist
add/remove/move, favourite toggle) must evict the affected keys or Home shelves go stale,
and Settings -> Refresh library clears the cache and bumps a `refreshKey` that re-keys
screen `LaunchedEffect`s.

Large lists (Artists/Albums/Tracks/Playlists) load 60-item pages: page 1 from cache, then
append on scroll-end detected via `derivedStateOf` over `visibleItemsInfo`, reading
`TotalRecordCount` as the total. `PagedLibrary` holds those pages in-process.

`AtmosCatalog` pages the whole audio library once per process and caches the ids of albums
and tracks containing an E-AC-3 JOC stream; the album detail header derives its badge from
the tracklist it already fetched instead. That one scan feeds three consumers: the ATMOS
badges, the per-view `AtmosFilterPill` (Albums, Tracks, artist page → `AtmosBrowse`),
and the `Prefs.atmosOnly` switch (`AtmosFilter.keep`) applied to browse lists — Home
shelves inside `ItemShelf`, the library tabs, artist albums/top tracks, an album's own
tracklist, search and genre results. Queues, Now Playing and playlist contents are never
filtered.

Album dedupe is NOT a thing: the same release exists in the FLAC library and the Atmos
library, and both tiles are shown (the Atmos copy carries the badge). "Only Atmos" is a
filter, never a dedupe rule.

Sort state is per-tab in a session map (not persisted); each tab maps its `SortMode` to a
Jellyfin `SortBy` and the cache key includes sort field + direction, so BACK restores the
exact list under the sort in use.

All app preferences live in the single `aura_prefs` store (`Prefs`): library selection,
autoplay toggle, lyrics web lookup, lyrics offsets, recent searches, resume points. The
session lives in `aura_session` and cache stamps in `aura_cache`; there is no third store.

## Rules that keep it working

- ExoPlayer access from **any** non-main context throws. Read player fields on the main
  handler tick, snapshot them, and pass the snapshot into coroutines — including websocket
  callbacks and HTTP workers.
- The shared `Json` sets `encodeDefaults = true`; otherwise defaulted request fields are
  omitted and the server resets its state (this silently broke remote seek).
- Retrofit methods returning `Response<T>` do not throw on 4xx/5xx. Check `isSuccessful`
  before treating a mutation as done.
- A playback stop report must carry the **old** item's position. When the id changes the
  player has already moved on, so `currentPosition` is 0:00 and every skipped track would be
  saved at zero.
- Jellyfin 12.2.0 does not persist playback positions from the report endpoints; anything
  that needs a resume point must store it locally.
- All TV text fields: `singleLine = true` + `KeyboardOptions` imeAction. A material3
  `TextField` in a TV dialog consumes D-pad direction keys, so DOWN-to-buttons needs an
  explicit `onPreviewKeyEvent` mapping.
- `.onFocusChanged {}` must precede `.clickable {}` / `.focusable()` in the modifier chain,
  or the focus flag never flips and focused styling never renders.
- A control nested inside a focusable card/row is unreachable by D-pad (focus search does
  not descend into a clickable parent). Make such a control an indicator and put the action
  in the context menu or in a real button row.
- The deployed artifact is the **release** build (R8 + resource shrink, ~3.5 MB vs 13.9 MB
  debug) — debug builds are markedly slower on TV hardware and are only for emulator UI
  iteration. R8 needs the keep rules in `app/proguard-rules.pro` for the reflective surface
  (Retrofit interface, DTOs and their `$$serializers`, media3); after every release build,
  smoke-test the launch and grep logcat for `FATAL`.
- Secrets (LRCLIB client id, any future key) come from the root `.env` via
  `buildConfigField`; `.env` is gitignored and `.env.example` documents each key. Builds
  without `.env` must still compile, so every generated field has a default.
- Coil decodes artwork as `RGB_565` with a 20% memory-cache cap and no crossfade, set once
  in `AuraApp.newImageLoader`.
