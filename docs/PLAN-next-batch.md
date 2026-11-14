# Next batch — implementation plan

Six user-requested items. Ordered so the cheap independent ones land first and the
cross-cutting ones (dynamic accent, text fitting) come after, because they touch shared
components that the earlier items also edit.

Verification convention for the whole batch: `./gradlew :app:compileDebugKotlin --rerun-tasks`
per phase. **Emulator only** — the Google TV Streamer is off-limits for this batch: no
`deploy-streamer.sh`, no `install-streamer-debug.sh`, no release install on
`192.168.2.191:5555`. Installs go through `scripts/install-emulator.sh` (debug build,
emulator serial only) and never `./gradlew installDebug`, which targets every connected
device. After the last phase, the emulator gets one launch pass via the debug deep links
(`--es aura_debug_screen nowplaying --ez aura_debug_autoplay true`) with
`AURA_NAV` / `AURA_MENU` logcat checks for FATAL. No screenshots.

---

## 1. Volume is never controlled by Jellyfin "Play On"

Goal: Aura always plays at unity gain; the AVR owns the level. Nothing arrives from the
server that attenuates the bitstream.

- `data/JellyfinModels.kt` — `CapabilitiesDto.supportedCommands`: drop `"SetVolume"`,
  keep `Play`, `Playstate`, `DisplayMessage` (repeat/shuffle stay in the raw-post path).
- `playback/PlaybackRemote.kt` — the raw capabilities JSON (~line 66) drops `SetVolume`
  too. Server caps must match the DTO or Jellyfin web shows a volume slider that appears
  to work.
- `playback/PlaybackRemote.kt` — inbound `SetVolume` / `VolumeUp` / `VolumeDown`: log
  `AURA_VOL ignored` and return instead of writing `player.volume`.
- `playback/PlaybackService.kt` — after the ExoPlayer is built and on every
  `onMediaItemTransition`, force `player.volume = 1f` (defensive against a stale value
  restored by the session).
- Check the playback-report DTO against the OpenAPI spec for `VolumeLevel`; if present,
  report `100` so the web/HA slider snaps back instead of showing a lie.
- Note it in README: "Volume is controlled by your AVR/receiver; the Jellyfin volume
  slider does nothing in Aura." See ROADMAP for the Home Assistant follow-up (not now).

## 2. Last library refresh

Local timestamps, no admin API dependency.

- `data/LibraryCache.kt` — add `markLoaded()` (any successful fetch/put) and
  `markManualRefresh()` (Settings button), both persisted in a `aura_cache`
  SharedPreferences file so they survive restarts; expose
  `lastLoadedAtMs` / `lastManualRefreshAtMs`. `clear()` keeps the manual stamp, bumps
  the loaded stamp. `PagedLibrary.clear()` + `AtmosCatalog.clear()` stay in the refresh
  handler.
- Init the prefs from `AuraApp.onCreate` (one-line `LibraryCache.init(this)`).
- `ui/settings/SettingsScreen.kt` — under the Library block: "Last manual refresh: …",
  "Data loaded: … ago", replacing the bare "refreshes every 24 hours" sentence's job
  (keep the sentence, add the facts below it). Relative formatting helper ("3 h ago",
  "yesterday", else date) shared in `ui/common/Components.kt`.
- Optional: also show the server-side scan time from `GET /ScheduledTasks`
  (`RefreshLibrary` → `LastExecutionResult.EndDateUtc`). Admin-only endpoint — wrap in
  `runCatching`, render only when it returns, nothing when it 401/403s.

## 3. ATMOS badge on artist-page albums

- `ui/common/Components.kt` — `ArtworkCard` already takes `badge`; add
  `badgeColor: Color = Accent` so the chip can be tinted (item 4).
- `ui/collection/ArtistScreen.kt` — the screen already computes `atmosAlbumIds`, so pass
  `badge = "ATMOS".takeIf { album.id in atmosAlbumIds }` on the album cards. No extra
  scan cost.

## 4. Dynamic colouring of the artist page

- `ArtistScreen.kt` — swap `rememberDynamicColor` for `rememberDynamicColors`; take
  `primary` (background wash) + `secondary` (accent, already contrast-corrected by
  `ensureReadable`). Keep `darken(primary, 0.30f)` for the top of the gradient, add a
  second mid stop `darken(primary, 0.14f)` before `Canvas` so the wash has depth instead
  of a flat fade. The header scrim `0x990E0C0A` and its rounded panel stay — readability
  is non-negotiable on bright artist images.
- Thread the accent as an **optional parameter with the global default**, not a
  CompositionLocal (fewer surprises, and other screens must not inherit the tint):
  - `ArtworkCard(accent: Color = Accent)` → focus ring + badge colour.
  - `TrackRow(accent: Color = Accent)` → left focus bar + index/current-track colour.
  - Section headers ("Albums", "Top tracks") get a 3dp accent underline rule so the page
    reads as one themed surface.
- Pass `accent = dyn?.secondary` at the ArtistScreen call sites only. Same call in
  CollectionScreen can follow later if the user likes it on artist pages first.

## 5. More noticeable highlight on menu items ("hover")

Interpretation: D-pad focus on the long-press context menu is too quiet. Fix the menu,
and normalise the other weak focus states.

- `ui/common/ContextMenu.kt` — replace the tv-material `Button` action rows with a
  private `MenuAction` composable: 52dp row, `SurfaceHigh` fill on focus, 2dp **grey**
  outline (`FocusOutline = Color(0xFF8C8175)` added to `Theme.kt`), 4dp accent bar on the
  left edge, 18sp label that goes `TextPrimary`. Keep: first action owns `firstFocus`,
  the OK grace-swallow on the overlay root, `AURA_MENU select[i]=` logging, and the
  `focusProperties { exit = Cancel }` trap.
- `ui/AppRoot.kt` — `TabButton`: unselected-focused gets the same grey outline (selected
  stays the solid amber pill). `IconNavButton`: keep the accent ring, standardise on 3dp.
- Leave the playlist picker's buttons as tv-material Buttons; it already has an
  initial-focus request and reads fine.

## 6. Long titles: shrink, then marquee

- New `ui/common/FitText.kt`:
  - `AutoFitText(text, maxWidth: Dp, sizes: List<TextUnit>, ...)` — uses
    `rememberTextMeasurer().measure(...).size.width` to pick the first size that fits;
    renders at that size.
  - `AutoFitMarqueeText(...)` — when even the smallest size overflows, render it with
    `Modifier.basicMarquee(animationMode = MarqueeAnimationMode.Immediately,
    initialDelayMillis = 1200, repeatDelayMillis = 1000, velocity = 30.dp)`, wrapped in
    `clipToBounds()` + `widthIn(max = maxWidth)`. **Pitfall:** `basicMarquee`'s default
    mode is focus-driven and TV titles are never focused, so `Immediately` is required or
    nothing moves. `@OptIn(ExperimentalFoundationApi::class)` (foundation 1.7.6 via BOM
    2024.12.01).
- `ui/nowplaying/NowPlayingScreen.kt` — big title: sizes 40 → 34 → 28sp, max width =
  the `weight(1f)` column's measured width (`onSizeChanged`), marquee at 28sp.
- `NowPlayingChip` (lyrics view) — 20 → 18 → 16sp, cap 380dp, marquee at 16sp.
- `ui/AppRoot.kt` `NowPlayingPill` — 14 → 13 → 12sp inside the existing 340dp cap
  (`widthIn` + clip so a marquee can never widen the pill and push Search/Settings off
  screen). Artist subtitle keeps plain ellipsis.

---

## Build order

| Phase | Items | Risk |
|---|---|---|
| A | 1 volume, 2 refresh stamp, 3 artist badge | low, independent |
| B | 5 menu/focus outlines | low, one file + AppRoot |
| C | 4 dynamic artist accent | medium — shared component params, check every call site uses named args |
| D | 6 text fitting | medium — experimental API, layout in three places |

Re-run `./gradlew :app:compileDebugKotlin --rerun-tasks` per phase, then at the end:
`./gradlew :app:assembleRelease`, `deploy-streamer.sh` only if the user asks, and a
`com.hans.aura/.MainActivity --es aura_debug_screen nowplaying --ez aura_debug_autoplay true`
pass on the emulator to log that the transport and title layout survive.

## Status (all six shipped, emulator-verified)

| # | Status | Evidence |
|---|---|---|
| 1 | done | ws echo shows `SupportedCommands` without `SetVolume`; inbound `SetVolume` logs "ignored (volume is AVR-controlled)"; reports carry `VolumeLevel:100`; player forced to unity gain in `PlaybackService` |
| 2 | done | Settings shows "Last manual refresh: …" / "Data loaded: …"; both stamps persisted in `aura_cache` prefs; tapping Refresh flips the label to "just now" |
| 3 | done | ATMOS chip on artist album covers (Kendrick Lamar → GNX) |
| 4 | done | Arlo Parks page: teal wash (12,25,33 → canvas), blue-violet accent on the section rule/badges. Artist pages entered from a context menu use a stub item, so the screen now resolves the real item before reading the image tag |
| 5 | done | Context-menu rows: 52dp, `SurfaceHigh` fill, 2dp grey outline, amber leading bar |
| 6 | done | 28-char title renders at 40sp on one line; 114-char title shrinks then marquees inside its column; top-bar pill clipped + marquees and the Search/Settings buttons stay on screen (tabs padded 22→14dp and the pill title capped at 200dp to make the whole bar fit 960dp) |

Pitfalls found while verifying (now in the project skill): palette `primary` is already darkened — darkening
again collapses every wash to black; and an explicitly sized marquee text refuses to shrink inside a Row,
which zero-sizes the siblings after it.

## Deferred (see docs/ROADMAP.md)

- Home Assistant ↔ AVR volume: read the receiver's level and mirror it into the Jellyfin
  session so the web/HA slider tells the truth. Out of scope now — Aura has no AVR
  transport and adding one is a different project.
