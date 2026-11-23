# Aura — Material 3 Expressive (M3E) Rewrite Plan

Restarting Aura around Material 3 Expressive (M3E) and a modern Compose TV stack yields meaningful wins in frame-rate and cold-start performance, visual polish and focus fluidity, and architectural cleanliness — with one key TV-specific caveat around `tv-material` vs. mobile M3E.

This plan is grounded in Aura's baseline architecture (~19k LOC, Compose BOM `2024.12.01`, `tv-material 1.0.1`, Coil `2.7.0`).

---

## 1. Performance Gains

### Eliminate hot-path recompositions & per-frame allocations

- **Now Playing 500 ms full-screen recomposition:** `NowPlayingScreen.kt` collects `player.positionMs` at the root composable, recomposing the entire screen (gradient brush, `MotionArtwork`, `AutoFitMarqueeText` / `BoxWithConstraints`, top row, transport buttons) twice a second. Deferring position reads via state-holder lambdas (`() -> Long`) strictly inside the seek bar and time labels drops this to near-zero recompositions.
- **60 fps card recomposition during focus springs:** `ArtworkCard` reads animated `scale` in `.zIndex(if (focused) 2f else if (scale > 1f) 1f else 0f)` during composition and allocates `RoundedCornerShape(...)` inline. Keeping `scale` strictly inside `graphicsLayer` (draw phase) and keying `zIndex` on `focused` alone eliminates ~15–20 recompositions per card on every D-pad move.
- **Eager Home shelves instead of lazy lists:** `HomeScreen.kt` uses `Column(...verticalScroll)` and `ItemShelf` uses `Row(...horizontalScroll)`. Every card across every shelf is composed and laid out upfront (which also forced custom `rememberOnScreen` + `onGloballyPositioned` visibility math). Switching to nested `LazyColumn` + `LazyRow` with `key` and `contentType` (also needed on the Tracks `LazyColumn` in `LibraryScreen.kt`) slashes initial Home inflation time and memory.
- **60 fps draw allocations in Now Playing:**
  - `WavySeekBar.kt` allocates a new `Path()` every frame and walks 1 px at a time (`x + 1f` → ~900 `sin()` + `lineTo()` calls/frame). Reusing a single `Path` with cubic Bézier half-waves (or Compose M3E's `LinearWavyProgressIndicator` approach) cuts draw CPU work by ~20×.
  - `KaraokeLine` allocates a new `Path()` and recomputes `result.getBoundingBox(offset)` + `.distinct()` for every character on every frame. Caching glyph rects once in `remember(layout, line)` and reusing a `Path` makes word-synced karaoke practically free.
- **Unstable UI models:** `JellyfinItem` is a 28-field raw network DTO with nullable `List`/`Map` properties and no `@Immutable` boundary. Mapping to small `@Immutable` UI models avoids deep structural equality checks across grids/tracklists on every recomposition.

### Cold start, media decoders, and TV SoC headroom

- **Persistent local cache (Room/SQLite) instead of RAM-only cache:** `LibraryCache.kt` is in-memory only, and `AtmosCatalog.kt` re-pages the entire audio library (`MediaSources` in 1,000-track chunks) on every cold launch. A lightweight local SQLite/Room index renders Home + Atmos badges + Genres + A–Z in <100 ms on cold start, then delta-syncs in the background.
- **Motion-artwork decoder pool:** `MotionArtwork.kt` constructs and releases a fresh `ExoPlayer` + `PlayerView` every time a tile wins/loses a slot. A tiny fixed pool of 2–3 reusable muted `ExoPlayer` instances swapping `MediaItem`s avoids `MediaCodec` cold-init stalls on TV hardware.
- **Baseline Profiles + Compose BOM 2026.x + R8 resource shrinking:** `build.gradle.kts` has `isMinifyEnabled = true`, but no Baseline Profile and no `isShrinkResources = true`. On low-power TV SoCs (Google TV Streamer), a generated Baseline Profile alone typically cuts cold start and first-fling jank by 20–40%. Moving from Compose BOM `2024.12.01` to current 2026 Compose also brings `Modifier.Node` focus/layout speedups and Coil 3.

---

## 2. UI Gains (Material 3 Expressive on a 10-Foot TV)

Aura's legacy design system is split across raw global `val`s in `Theme.kt`, custom tokens in `Tokens.kt`, inline hex/dp literals in `TopBar.kt` and `LibraryScreen.kt`, and a mix of `androidx.tv.material3` and un-themed `androidx.compose.material3` widgets.

Starting clean with M3 Expressive unlocks:

- **Real HCT Dynamic Color Schemes (replacing 2-color Palette hacks):**
  - Legacy `Dynamic.kt` runs `androidx.palette` + manual HSV rules (`darken(0.30f)`, `capSaturation(0.62f)`, `ensureReadable(0.40f)`) to produce two colors, manually threaded as optional `accent: Color` / `badgeColor: Color` parameters into shared composables.
  - Generating full M3E tonal schemes (`SchemeExpressive` / `SchemeVibrant` / `SchemeTonalSpot` via Material Color Utilities / HCT) provides 30+ contrast-guaranteed roles (`surfaceContainerLowest..Highest`, `primaryContainer`, `onPrimaryContainer`, `secondaryContainer`, `tertiary`, `outlineVariant`).
  - Preserving the strict static Home/Library/TopBar vs. dynamic Detail/Now Playing split is cleaner by wrapping only Artist / Album / Now Playing subtrees in a scoped dynamic `MaterialTheme` — zero manual `accent` parameter drilling, guaranteed readability over bright album covers, and genuine tertiary/secondary tones for duet lyrics, Atmos badges, and active chips instead of `accent.copy(alpha = 0.16f / 0.25f / 0.34f)`.
- **Cohesive `MotionScheme.expressive()` Spring Physics & Shape Morphing:**
  - Replace scattered `tween(500)` / one-off `artworkFocusSpring` with M3E's unified spatial springs (position/size/shape, slight bounce) and effects springs (color/alpha, critically damped).
  - **Shape morphing on state/focus:** Play/Pause morphing smoothly between a circle (paused) and a rounded squircle (playing); filter/sort pills morphing corner radius on focus/selection; connected button groups (Play + Shuffle + Radio) replacing loose rows of identically-shaped pills on `CollectionScreen.kt` and `QueueScreen.kt`.
  - **Expressive loaders & progress:** Morphing-shape `LoadingIndicator` instead of plain `"Loading…"` text in `LibraryScreen.kt` and `LyricsPane.kt`, and a native-style M3E wavy seek bar.
- **Shared-Element & Screen Transitions:**
  - Legacy `AppRoot.kt` swaps screens abruptly via a plain `when (val top = nav.top)`.
  - Using `SharedTransitionLayout` + `AnimatedContent` lets album artwork smoothly expand from a Home shelf or Albums grid into the Album Detail header or Now Playing hero cover, and lets Now Playing glide cleanly into full-bleed Lyrics mode.
- **Snappier, Zero-Delay Focus Navigation:**
  - Legacy `FocusSupport.kt` relies on wall-clock retry timers (`delay(350)` on `BACK`, `delay(150) + repeat(5) { delay(100) }` on push, `delay(250) + repeat(6)` in `NowPlayingScreen`, `delay(60/80/150)` in the A–Z rail), making every push/pop feel ~200–350 ms sluggish.
  - Building directly on modern Compose `focusRestorer` (with fallback lambdas), `focusGroup`, and layout-driven focus handoff (`onLayoutRectChanged` / `LaunchedEffect` after composition placement) makes D-pad focus snap instantaneously.
- **Optional Layout Upgrade — Collapsible Navigation Rail vs. 960 dp Top Bar:**
  - Squeezing 5 tabs + Now Playing pill + Search + Settings into 960 dp (`TopBar.kt`) forced Genres inside Albums and Liked inside Tracks.
  - An M3 TV collapsible Navigation Rail (slim icon rail at rest, spring-expands with labels on focus) gives Genres, Liked, Search, Settings, and Now Playing first-class top-level homes and frees up vertical space for content — if trading the Spotify-web top-bar silhouette is acceptable.

---

## 3. Feature & Architecture Gains

Even keeping feature parity, a clean slate removes structural ceilings that make features harder to add or slightly janky:

- **True multi-source domain layer:** Today `MusicSource` surfaces Jellyfin's `JellyfinItem` everywhere, leading to workarounds like `<albumId>:<trackId>` ID stripping, `runTimeTicks` math in UI code, and Jellyfin-only playlist mutations. A clean domain model (`Track`, `Album`, `Artist`, `Playlist`, `AudioProfile`, `ArtworkRef`) makes multi-backend support, richer codec/format badges (Atmos / lossless / sample rate), and unit tests dramatically simpler.
- **Single reactive playback clock:** Right now there are three competing clocks: the 500 ms ticker in `PlaybackController.kt`, the 50 ms `adlibPosition` polling loop in `LyricsPane.kt`, and per-line `withFrameMillis` loops in `KaraokeLine` and `WavySeekBar`. One shared frame-interpolated clock (`anchorPos + (uptime - anchorTime) * speed`) keeps wavy seek, karaoke syllables, adlibs, and instrumental break dots phase-locked at 60 fps.
- **Reactive store instead of manual cache invalidation:** Replacing `refreshKey++` and `LibraryCache.evictWhere(...)` with `Flow`-backed repositories means liking a song, editing a playlist, or toggling a library updates Home, Albums, Tracks, Queue, and Now Playing automatically with zero stale shelves.
- **Real back-stack ViewModel scoping:** Today every detail ViewModel lives on the `Activity` with manual string keys (`"collection:$id:$userId"`), meaning opened albums/artists leak in memory for the lifetime of the Activity. Using Navigation 3 (or a proper per-entry `ViewModelStoreOwner` + `LifecycleOwner`) automatically disposes popped screens and frees their state cleanly.
- **Push-based Mirror Link (WebSocket / SSE):** Designing `MirrorServer` around the same unified UI/playback `StateFlow` makes instant push updates to `aura-remote` trivial (already on `ROADMAP.md`).

---

## 4. The Catch: Where a Rewrite Won't Help (or Hurts)

- **`androidx.tv:tv-material` does NOT ship mobile M3 Expressive components.** Mobile `androidx.compose.material3` (1.4/1.5+) has `MaterialExpressiveTheme`, `MotionScheme`, `ButtonGroup`, `SplitButton`, `LoadingIndicator`, etc., while `androidx.tv:tv-material` (1.1.0) only covers core TV focus surfaces, cards, and drawers. On TV you still want your own small, TV-first component layer — applying M3E's HCT color math, spring `MotionScheme`, shape morphing, and typography on top of TV focus primitives — rather than dropping touch-centric mobile M3E widgets onto a D-pad UI.
- **Hard-won TV & Atmos invariants aren't "rewriteable" away.** Direct-play E-AC-3 JOC passthrough (`volume = 1f`, no `SetVolume`), `MainActivity.dispatchKeyEvent` `BACK` interception before Compose focus groups, time-based TV long-press + overlay grace windows, `encodeDefaults = true` for Jellyfin remote seek, per-request `AuthDataSourceFactory`, local resume points, and Jellyfin's `MusicGenre` / `LibraryFilter` quirks all have to be ported verbatim either way.

---

## Bottom Line

If you want the highest payoff without throwing away working playback and Jellyfin plumbing:

- **Worth rebuilding from scratch:** The UI theme/component kit (unified M3E HCT color scheme + `MotionScheme` springs + shared-element transitions + zero-delay focus) and screen rendering hot paths (lazy Home shelves, deferred playback clock, cached `Path`/glyph draws).
- **Worth keeping & evolving in place:** `playback/`, `player/controller/`, `remote/`, `data/` Jellyfin quirk handlers, and the `AURA_*` headless verification harness — optionally sliding a Room cache and clean domain models underneath the UI layer.
