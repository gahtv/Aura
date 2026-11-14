# Wave 3 — feature expansion plan

Two sources of work, deliberately kept apart:

- **Part A — things you asked for and that never shipped.** Recovered from the earlier
  session logs, quoted verbatim so nothing gets re-litigated or silently dropped.
- **Part B — features from other music players** that fit a TV + Atmos setup, ordered so
  the cheap/high-value ones land first.

Nothing here is approved yet. Read it, delete what you don't want, say go.

**Standing rules for every phase in this document** (unchanged from wave 2): builds are
`./gradlew :app:compileDebugKotlin --rerun-tasks` per phase; the emulator is the only
verification target unless you ask otherwise; `scripts/install-emulator.sh` only, never
`./gradlew installDebug`; no streamer deploy, no screenshots — verification goes through
DebugNav deep links plus `AURA_` logcat breadcrumbs. Any interactive feature ships its
headless test hook in the same commit (see the skill's "build the test hooks in the same
change" rule).

---

## 0. What already exists (so it doesn't get re-planned)

Top bar (Home / Artists / Albums / Tracks / Playlists / Search / Settings / now-playing
pill), full-bleed Home shelves (Recently played, Recently added, Artists, Playlists),
paged library tabs with 6 sort modes, search across three types in home-style shelves,
artist and album pages with bios + dynamic colour, line-synced lyrics with a bottom-left
chip, queue screen (jump/remove/shuffle/repeat), playlist create/add/remove, long-press
context menus (add to playlist / play next / add to queue / view artist / view album),
Atmos badges, Jellyfin "Play On" + full remote-control loop, 24h TTL cache with manual
refresh, back-stack state restore with exact-item focus, keep-screen-awake.

Everything in Part A/B below is *not* in that list.

---

## Part A — your own items that never shipped

| # | What you asked for | Where it came from | Status |
|---|---|---|---|
| A1 | Quick Connect login | "quick connect using the Jellyfin documentation JSON" (feature list, 13 Nov) | never built; still in ROADMAP |
| A2 | Hide/disable specific libraries (audiobook lib, separate FLAC/Atmos libs), with the hidden ones never entering the cache | same list, with the explicit "make sure if you hide it, the other libraries won't be stored in cache" | never built; still in ROADMAP |
| A3 | Settings for "what libraries should be included **and changing servers**" | nitpick list, 12 Nov | library half never built; change-server never built |
| A4 | Queue drag-reordering | your first deferred list, 12 Nov | never built; still in ROADMAP |
| A5 | Web UI able to see Aura's queue | "add queue functionality from jellyfin" (13 Nov) → built, then you reverted it | pinned: server 12.2.0 never surfaces a reported queue; official clients share the gap |
| A6 | HA ↔ AVR volume bridge | "keep a pin on Home Assistant integration maybe … don't implement for now" | pinned on purpose, stays out |
| A7 | Multi-format / sink validation matrix (Atmos vs stereo downmix per device) | first deferred list | never built (QA work, not a feature) |

Notes on A4/A5, because the shape matters:

- **A4 is not a drag gesture on TV.** A remote has no pointer; a "drag" on a DPAD list is
  a liability (it fights the focus system the way every other scroll/drag hack in this
  project did). Ship **"Move up" / "Move to top" / "Move to bottom"** in the track
  context menu plus a queue header action, and reserve real drag for touch. Same outcome,
  no new input model.
- **A5 stays pinned.** Re-verified 2026-11-13: Jellyfin 12.2.0 binds `NowPlayingQueue`
  only on `/Sessions/Playing/Stopped`, so a live queue never reaches Jellyfin Web. Do not
  spend another session on it.

---

## Part B — features from other players

Sized S (a session), M (a few), L (a project). Everything is client-side on the existing
Jellyfin 12 API — no server plugins, no new dependencies beyond what's already vendored.

### B1. Liked songs / favorites — Spotify, Apple Music, Plexamp — **S**

The single biggest gap. Jellyfin has favorites for free; Aura ignores them.

- `data/JellyfinModels.kt` — `UserData` gains `@SerialName("IsFavorite") val isFavorite: Boolean? = null`.
- `data/JellyfinApi.kt` — `POST|DELETE UserFavoriteItems/{itemId}?userId=` (returns `UserData`).
  `getItems` gains `@Query("Filters") filters: String?` and `@Query("EnableUserData") enableUserData: Boolean = true`;
  add `IsFavorite` to the default `Fields`.
- New `data/Favorites.kt`: in-process `Set<itemId>` mirroring the server, `toggle(item)` with
  optimistic update + revert on failure, and `LibraryCache.evict()` of the affected keys
  (`home_recent`, `tracks:*`, `album:*`) on success — otherwise a shelf keeps showing the
  old heart.
- `ui/common/Components.kt` — `TrackRow(..., favorite: Boolean? = null, onToggleFavorite: (() -> Unit)? = null)`
  draws a small heart at the row's trailing edge; `ArtworkCard` gets the same badge for albums.
- Context menu (`ui/common/ContextMenus.kt`) — "Add to favorites" / "Remove from favorites"
  as the first action, above "Play next".
- Library — new **Liked** tab (or a `Liked` pill on Tracks; a tab is more discoverable):
  `getItems(filters = "IsFavorite", includeItemTypes = "Audio", enableUserData = true)`, same
  60-item paging as the other tabs.
- Now Playing — heart button next to the queue/add buttons in `NpControlButton` row.

Verification: `AURA_FAV item=<id> now=<true|false>` in logcat; assert by re-reading
`/Users/{uid}/Items?Ids=<id>&EnableUserData=true` from curl. Debug extra `--es aura_debug_favorite <itemId>`.

### B2. Instant Mix / "start radio" — Spotify radio, Jellyfin Web, Plexamp — **M**

Jellyfin already computes the mix server-side; this is mostly wiring.

- Endpoints (verified in the OpenAPI 12.4.0 spec): `/Items/{itemId}/InstantMix`,
  `/Artists/{itemId}/InstantMix`, `/MusicGenres/{name}/InstantMix`, `/Songs/{itemId}/InstantMix`.
- New `data/Mixes.kt` with `fun trackMix(item)`, `albumMix(item)`, `artistMix(item)`, `genreMix(name)`
  and one entry point `playMix(kind, seed)` that fetches ~50 items and calls the existing
  `PlayerConnection.playQueue(items, 0)`.
- Entry points: track context menu **"Start radio"**; artist page header **"Radio"** button
  beside Play/Shuffle; album header too; Now Playing gets a small radio icon that re-seeds
  from the current track.
- Label the resulting queue in `QueueScreen` ("Radio · seed name") so it doesn't look like a
  plain playlist.

Risk: mix quality is server-side and can be mediocre on a small library. Cheap to try,
cheap to remove.

### B3. Similar artists / "More like this" — Plexamp, Apple Music — **S–M**

- `GET /Artists/{itemId}/Similar?userId=&limit=` and `/Albums/{itemId}/Similar`.
- Artist page: a "Similar artists" shelf under the albums; Album page: "More from <artist>"
  reusing the existing artist-albums query, plus "Similar albums" when the user wants it.
- Reuses `ItemShelf`; no new UI primitives.

### B4. Resume where you left off — Apple Music, Spotify, Plexamp — **M**

Jellyfin already stores `UserData.PlaybackPositionTicks` per track; Aura throws it away.

- `PlayerConnection.playQueue` gets `resume: Boolean = true`: for the tapped item, if
  `userData.playbackPositionTicks > 15s` and `< duration - 10s`, start at that position.
  Never resume for Play Next / Add to queue / shuffle.
- Home gets a **"Continue listening"** shelf: `getItems(includeItemTypes="Audio",
  sortBy="DatePlayed", sortOrder="Descending", filters="IsResumable")` — if 12.2 doesn't
  honour `IsResumable`, filter client-side on `playbackPositionTicks > 0` from the
  `Recently played` response already on screen (costs nothing).
- Needs `EnableUserData=true` + `Fields=...UserData` on the shelf query (see B1).

This one needs care: a resumed track still has to count as "played" and report the right
start position to the server, or HA/web history jumps around. Verify with a report trace.

### B5. Autoplay when the queue runs out — Spotify, Apple Music — **S–M**

- In `playback/PlaybackService` / `PlayerConnection`, on `onMediaItemTransition` with
  `playbackState == ENDED` and repeat off: fetch an instant mix seeded from the last track,
  keep only ids not already in the queue, append up to 20.
- Gate behind a Settings toggle ("Keep the music going"), default **on**, persisted in a new
  `aura_prefs` SharedPreferences (a settings store is needed for B6/B10/B11 anyway).
- Log `AURA_AUTOPLAY appended=N seed=<id>`.

### B6. Sleep timer — Plexamp, Spotify, Apple Music — **S**

- `PlayerConnection.startSleepTimer(minutes)`, `cancelSleepTimer()`, `sleepRemaining: StateFlow<Long?>`.
  On expiry: pause (never stop — the queue should survive) and show a one-line toast in
  Now Playing.
- UI: a context-menu-free path is best on TV — Now Playing's top row gets a **Sleep** chip
  that cycles Off → 15 → 30 → 45 → 60 → End of track; show the remaining time on the chip.
- Settings mirror: "Sleep timer" row for discoverability.

### B7. Gapless playback, and an honest answer on crossfade — **M (investigation) / L (crossfade)**

- Gapless: ExoPlayer does it natively when the container carries encoder delay/padding
  (MP3 LAME header, AAC `iTunSMPB`, Opus pre-skip). **M4A/E-AC-3 Atmos almost never carries
  it**, so gapless may be a no-op for your reference library. Phase work = measure first:
  play a gapless-mastered album (e.g. a continuous mix) on the emulator and log
  `onMediaItemTransition` timestamps; if there's an audible gap, the fix is a custom
  `AudioSink` or a pre-buffered `ConcatenatingMediaSource` — expensive and risky for the
  Atmos bitstream. Do **not** touch the audio pipeline until the gap is measured.
- Crossfade: not supported by ExoPlayer at all (needs a custom audio processor mixing two
  decoders). On an Atmos passthrough chain, crossfading would require decoding E-AC-3 JOC
  in-app, which defeats the entire purpose of this app. **Recommend: never.**

### B8. Smart shuffle — Spotify "Smart Shuffle", Plexamp "Sonic Adventure"-lite — **S**

- Current shuffle is `shuffleModeEnabled`; the queue order is whatever the server returned.
- Add a "Smart" option in the queue screen: client-side reorder of the *upcoming* items
  weighted against `UserData.playCount` and `LastPlayedDate` — least-played and
  longest-unplayed first, with ~20% of the slots kept for well-loved tracks. Implemented as
  a pure function `smartOrder(items): List<JellyfinItem>` so it's unit-testable and
  reversible (keep the original list to "undo").

### B9. Genres browse + genre radio — Navidrome/Feishin, Plexamp, Jellyfin Web — **M**

- `GET /Genres?userId=&includeItemTypes=Audio` (or derive from the library, cheaper).
- New **Genres** tab (a 6th tab won't fit the 960dp top bar — see the budget note in the
  skill: 5 tabs at 14dp padding is already the limit). Two options: (a) Genres lives as a
  pill inside Albums/Tracks, (b) replace the Playlists tab with a combined "Collections"
  screen that lists Playlists + Genres. Prefer (a) until the top bar is re-thought.
- Selecting a genre → `getItems(genres=<name>, includeItemTypes="Audio")` grid + a "Play
  genre radio" button using `/MusicGenres/{name}/InstantMix`.

### B10. Lyrics polish — Apple Music, Spotify — **S–M**

- **Offset nudge**: ±0.5s steps stored per track id in `aura_prefs` (`lyrics_offset_<id>`),
  bound to the existing D-pad left/right while the lyrics pane has focus, with a small
  "offset +0.5s" chip that fades. Fixes the common case where LRC from the server is a
  beat behind.
- **"No lyrics" state**: currently blank when the server has none. Show the track's
  metadata card and a "No synced lyrics for this track" line.
- **Secondary lyrics source**: optional LRCLIB lookup when Jellyfin returns none (plain
  HTTPS GET, no key). Keep it off by default; a Settings toggle. This is the one feature
  here that leaves the server.

### B11. Search: history, voice, keyboard sanity — YouTube Music, Spotify — **S–M**

- Recent searches: last 8 terms in `aura_prefs`, shown as chips above the field when the
  query is empty; OK re-runs, long-press removes.
- **Voice search**: fire `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` from a mic button in the
  search field — on a TV remote this is a real win and needs no Jellyfin work.
- Keyboard: the current field commits on Done; also accept ENTER and keep focus after submit
  so a second search doesn't require a re-focus.

### B12. Playlist management — Spotify, Apple Music — **M**

- Rename: `POST /Playlists/{playlistId}` with `{Name}`. Delete exists in the API layer
  (`deletePlaylist`) but has **no UI** — wire it with a confirm dialog.
- Reorder: `POST /Playlists/{playlistId}/Items/{itemId}/Move/{newIndex}` — same
  "Move up/down" menu as A4, so one implementation serves both queue and playlist.
- Playlist header: track count + total runtime (already fetched items, sum `runTimeTicks`),
  and a Play/Shuffle pair matching album pages.
- Playlist picker gets a search field once there are >20 playlists (it's already a text-input
  dialog; reuse the D-pad traps from `PlaylistDialogs.kt`).

### B13. Home shelves worth adding — Spotify "Jump back in", Apple "Recently played artists" — **S**

Cheap, all reuse the existing `ItemShelf` + cache:
- **Continue listening** (B4) — first shelf, above Recently played.
- **Recently played artists** — `getItems(includeItemTypes="MusicArtist", sortBy="DatePlayed")`.
- **Your Atmos** — a shelf of Atmos albums, reusing `AtmosCatalog` (already scans the whole
  library; no new cost once it has run).
- Keep total Home shelves ≤ 6; every extra shelf costs a cold-start query on the streamer.

### B14. Now Playing polish — **S**

- Favorite button (B1).
- Queue peek: pressing DOWN from the transport shows the next 3 tracks inline (a compact
  list while the queue button still opens the full screen).
- Track radio button (B2).
- Swipe/D-pad scrub is already there via the seek bar — leave it.

### B15. Alpha jump for big grids — Plexamp, Spotify — **S**

- On Artists/Albums/Tracks, LONG-press or a dedicated "A–Z" pill opens a letter rail;
  selecting a letter starts the paged query at that `NameStartsWith`. `getItems` already
  supports `nameStartsWithOrGreater`; add the param and skip counting.
- Only worth building if your library is big enough that scrolling 20 pages hurts — your
  call.

---

## Part C — the plumbing phases (Part A)

### C1. Quick Connect login (A1) — **M**

Flow, straight from the spec: `POST /QuickConnect/Initiate` → `{Secret, Code, Authenticated}`;
render the 6-digit `Code` in a full-screen box, poll `GET /QuickConnect/Connect?secret=` every
2s until `Authenticated: true`; then `POST /Users/AuthenticateWithQuickConnect` with
`{Secret}` → `AuthResponse`. Gate on `GET /QuickConnect/Enabled` and hide the button when the
server says no.

- `data/JellyfinApi.kt` — 4 new calls; all unauthenticated, so the OkHttp client must not
  require a token (the auth interceptor already tolerates a null session).
- `ui/login/LoginScreen.kt` — "Sign in with a code" button under the password field; a
  `QuickConnectPanel` showing the code at 64sp + the server URL, with BACK to cancel.
- Debug hook: `--es aura_debug_screen quickconnect` renders the panel with a fake code (no
  network) so layout can be checked headlessly.

### C2. Library selection (A2) — **M**

- `GET /UserViews?userId=` returns the user's libraries. Render them as toggle rows in
  Settings grouped "Libraries shown in Aura". Persist hidden ids in `aura_prefs`.
- Enforce at the **data layer**, not the UI: a single `LibraryFilter` object consulted by
  every `getItems` call site (or one wrapper in `JellyfinClient`) that injects
  `ParentId`/`ExcludeItemIds`, so hidden libraries are never fetched — which also satisfies
  your "won't be stored in the cache" requirement. Do not filter after caching; that still
  stores them.
- `LibraryCache.clear()` + `PagedLibrary.clear()` + `AtmosCatalog.clear()` on any change, or
  the previously loaded (visible) library lingers for 24h.

### C3. Change server / re-auth from Settings (A3) — **S**

- Settings → Server block gains "Change server" (returns to a login screen pre-filled with
  the current URL but no credentials) and "Sign in again" (same server, clears the token).
- `AuthStore.clear()` already nukes prefs; add `AuthStore.clearSession()` that keeps the
  server URL so the login screen can pre-fill it. Evict every cache on switch — mixing two
  servers' item ids in one `LibraryCache` would render wrong artwork.

### C4. Queue/playlist reorder (A4 + B12) — **M**

- `PlayerConnection` gains `moveInQueue(from, to)` (MediaController `moveMediaItem`) and
  `clearQueue()` (keep the current item or stop — ask).
- `ui/queue/QueueScreen.kt` — per-row context menu with Move up / Move down / Move to top /
  Remove; header gets "Clear upcoming".
- Same menu composable reused by playlist rows via
  `POST /Playlists/{id}/Items/{itemId}/Move/{newIndex}`.

---

## Build order and risk

| Phase | Contents | Risk | Why here |
|---|---|---|---|
| 1 | prefs store, `UserData.IsFavorite`, API additions (B1 groundwork) | low | every later phase touches this store |
| 2 | B1 favorites + Liked tab + context menu + Now Playing heart | low | biggest payoff per line |
| 3 | C4 reorder + clear queue, B8 smart shuffle | low–med | one menu, two screens; MediaController mutations need live checks |
| 4 | B2 radio/mixes, B3 similar | low | pure adds, server-computed |
| 5 | B6 sleep timer, B4 resume, B5 autoplay | med | all three touch playback reporting — do them together and verify reports |
| 6 | B9 genres, B13 shelves, B14 Now Playing extras | low | UI-only, reuses shelves |
| 7 | B10 lyrics offset, B11 search history/voice, B15 alpha jump | med | text input on TV has a history of focus traps — follow the PlaylistDialogs pattern |
| 8 | C1 quick connect, C2 library selection, C3 change server | med–high | auth + cache invalidation; needs a second server/user to test honestly |
| 9 | B7 gapless measurement | low (measurement) | only if the gap is real |
| — | B12 playlist management | low | whenever; independent |

Release discipline: phases 1–7 are the next alpha's content. C1–C3 (phase 8) deserve their
own release because a broken login is unrecoverable without adb.

## Verification protocol (per phase)

1. `./gradlew :app:compileDebugKotlin --rerun-tasks`.
2. `scripts/install-emulator.sh`, then launch straight to the touched screen:
   `adb shell am force-stop com.hans.aura; adb shell am start -n com.hans.aura/.MainActivity --es aura_debug_screen <screen>`.
3. Prove behaviour from logcat (`AURA_*` lines) and, where the change is server-visible
   (favorites, playlists, resume positions), from a `curl` read against Jellyfin — not from
   a screenshot.
4. New debug extras go into `ui/DebugNav.kt` (and `MainActivity.onNewIntent`) in the same
   commit as the feature.

## Explicit non-goals

- Crossfade (B7) — would break Atmos passthrough.
- Web/HA mirror of Aura's queue (A5) — server limitation, pinned.
- HA ↔ AVR volume bridge (A6) — stays external, pinned in ROADMAP.
- Offline downloads / local caching of audio — a TV with wired Ethernet and a LAN server
  gains nothing, and it duplicates Jellyfin's sync semantics.
- Last.fm scrobbling — needs a server-side plugin; Aura shouldn't talk to a third party.
- In-app equalizer / loudness normalisation — any DSP before the bitstream kills the
  Atmos passthrough.

## Open questions

1. Liked songs: add a **Liked** tab to the top bar (would need the 5-tab budget reworked) or
   a pill inside Tracks?
2. Sleep timer: pause at expiry, or stop and clear the queue?
3. Resume: always, or only when you tap the track deliberately (never from a shelf's
   "play all")?
4. Autoplay default on or off?
5. Genres: worth a tab, or is the library already browsable enough?
6. Crossfade: confirm you're OK with "never" given Atmos.
