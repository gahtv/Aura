# Review guidelines

Instructions for Devin Review and other automated reviewers of Aura, a Kotlin /
Compose TV Jellyfin music player for Android TV. [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
gives the reasons behind each rule below; [CONTRIBUTING.md](CONTRIBUTING.md) has the
ground rules. When this file and the code disagree, flag the disagreement and don't
pick a side.

Aura is AI-written and has had no security review (see [AI_USAGE.md](AI_USAGE.md)),
so look at what the diff actually does instead of trusting its commit messages.
Report correctness bugs before style issues.

## How to verify

- The required checks are `scripts/check.sh`, or these commands on non-macOS machines
  (`scripts/common.sh` hardcodes macOS paths):
  `./gradlew :app:compileDebugKotlin --rerun-tasks`, `./gradlew :app:lintDebug`, then
  `./gradlew :app:testDebugUnitTest`.
  Without `--rerun-tasks`, a compile can report UP-TO-DATE without building the edit.
- JVM tests live in `app/src/test/` and run on Robolectric, with no device needed.
  A behaviour change should come with a test next to the existing ones for that area.
  A bug fix should come with a test that reproduces the bug.
- An emulator can't confirm a playback, perf or Atmos claim. Those need a release
  build on real hardware, so treat a claim like that as unverified unless the PR says
  how it was checked.

## Critical areas

### Threading and Media3
- Calling ExoPlayer or `MediaController` from any non-main thread throws. This covers
  coroutines on `Dispatchers.IO`, websocket callbacks, OkHttp callbacks and
  `MirrorServer` HTTP workers. Flag any player read or write that doesn't run on the
  main thread. Snapshot the fields on main, then pass the snapshot along.
- `MirrorServer.bindPlayer` / `MirrorLink.bindPlayer` commands must hop to the main
  thread. Otherwise the request hangs.

### Playback and Atmos (`playback/`, `player/controller/`, `ui/player/`)
- Streams are direct-play only. Flag any change that adds transcoding, decoding,
  in-app volume, crossfade, gain or audio effects. Each of these breaks E-AC-3 JOC
  bitstream passthrough.
- `player.volume` stays at `1f`. `SetVolume` must not appear in the advertised
  capabilities, and playback reports send `VolumeLevel = 100`.
- Capabilities are declared in two places: `CapabilitiesDto` (`data/JellyfinModels.kt`)
  and the hand-built JSON in `playback/PlaybackRemote.kt`. A change to one needs the
  same change to the other.
- `AuthDataSourceFactory` must build the `Authorization` header on every
  `createDataSource()` call. Flag a header that is cached or captured once.
- A stop or progress report for an item that has been left must carry the **old**
  item's position, not `currentPosition` after the transition.
- Resume points stay local (`Prefs.ResumePoints`, keyed per server + user), because
  Jellyfin 12.2.0 doesn't save positions from report endpoints.
- `PausedAudioOutput`: after a passthrough pause, the resume path has to prepare again
  from `STATE_IDLE`.
- `AutoplayPolicy` must not check `playWhenReady`.
- `playQueue(...)` is the only entry point for starting a queue. Queue mutations run
  on main and keep `QueueRegistry` in sync.

### Sign-in, session and secrets (`data/AuthStore.kt`, `ui/shell/SessionActions.kt`)
- Sign-out and server changes go through `SessionActions.endSession`, and profile
  switches go through `SessionActions.switchSession`. Both follow the same order:
  `PlayerConnection.teardown()` (clear the queue registry, stop playback, release the
  controller and service), then cancel pending mutations, clear the session caches
  (`LibraryCache`, `Favorites`, `PagedLibrary`, `AtmosCatalog`, `ArtworkSeedCache`), and
  only then change credentials. A new session-scoped cache must be cleared in **both**
  functions.
- `JellyfinClient.api` is a getter keyed by base URL. Flag code that caches a Retrofit
  instance across a server change. Pre-session flows such as Quick Connect must use
  `apiForServer(url)`.
- Tokens, passwords and `X-Aura-Token` values must never reach logs, `AuraLog`
  messages, exceptions or the mirror `/state` payload.
- Keys come from the gitignored root `.env` through `buildConfigField`. A checkout with
  no `.env` must still compile, so every field needs a default. Flag any literal key
  or client id in source.
- LRCLIB is the only third-party network call. It stays off by default and sends
  track metadata only, never Jellyfin credentials or server URLs.

### Mirror link (`remote/MirrorServer.kt`)
- The server listens on `0.0.0.0:8099` on the LAN. Every endpoint except `/health`
  and `/pair/request` must require a valid `X-Aura-Token`. Only an explicit Allow on
  the TV can mint a token. Flag new endpoints or routes that skip the check, as well
  as unbounded request bodies and missing input validation.
- A protocol change must also update `docs/REMOTE-CONTROL.md`, because
  [aura-remote](https://github.com/gahtv/aura-remote) consumes it.

### Jellyfin API and serialization (`data/`)
- Retrofit/kotlinx serialization uses the shared `JellyfinClient` `Json`, which sets
  `encodeDefaults = true`. A second `Json` instance, or one that drops that setting,
  silently omits defaulted fields. That is how remote seek broke before. The hand-built
  capabilities body in `PlaybackRemote.postCapabilities` is a deliberate exception,
  because the Retrofit path didn't register capabilities reliably.
- Retrofit `Response<T>` doesn't throw on 4xx/5xx. A mutation needs an `isSuccessful`
  check before it's treated as done.
- Server mutations (playlists, favourites) must evict the affected `LibraryCache` keys.
- New `@Serializable` DTOs or Retrofit interfaces called through reflection need keep
  rules in `app/proguard-rules.pro`, or release (R8) builds crash.

### Library filtering, caching and the source seam
- Screens and ViewModels browse only through `SourceQueries` and get immutable
  `model/` types (`Track`, `Album`, ...). Flag UI code that calls `MediaRepository`,
  `LibraryFilter`, `JellyfinApi`, or reads `JellyfinItem` fields directly.
- Library browse cache keys (`LibraryCache`, `PagedLibrary`) include
  `LibraryFilter.cacheSuffix()`. Browse queries for music item types must respect hidden
  libraries. Direct id lookups, and caches keyed only by an artwork URL such as
  `ArtworkSeedCache`, are exempt.
- "Only Atmos" is a filter, never a dedupe. The FLAC and Atmos copies of an album stay
  separate items. With the filter off both are shown, and with it on, `AtmosFilter.keep`
  hides albums and tracks that aren't in the Atmos catalog. Artists, playlists, genres,
  queues, Now Playing and playlist contents are never filtered.
- Room (`CacheDatabase`, `app/schemas/`): bumping the schema version needs an exported
  schema JSON and a migration, or a deliberate and documented destructive fallback.

### Navigation and focus (`ui/nav/`, `ui/AppRoot.kt`, `ui/common/FocusSupport.kt`)
- A new destination is a `Screen` case plus a `ScreenMeta` entry. Flag a new
  string-to-screen `when` that duplicates `NavSpec`.
- Saveable-state keys (`home`, `albums`, `col:<id>`, `artist:<id>`, `genre:<name>`)
  must stay byte-identical, because focus restore depends on them.
- `Screen` subtypes stay `@Serializable`. Item screens persist only id + name + kind,
  with the rest marked `@Transient`. Flag a full item or a large list added to the
  saved stack, since it risks `TransactionTooLargeException`.
- `rememberNavState` is keyed by `SessionKey(serverUrl, userId)`, never by an
  in-memory counter.
- BACK is handled in `MainActivity.dispatchKeyEvent`, then by `BackHandler`. BACK must
  return focus and scroll to exactly where they were.
- `.onFocusChanged {}` must come before `.clickable {}` / `.focusable()`.
- A clickable control nested inside a focusable card or row can't be reached by D-pad.
  Flag it.
- TV text fields need `singleLine = true` plus a `KeyboardOptions` imeAction. Material3
  `TextField`s in dialogs need an explicit `onPreviewKeyEvent` for DOWN.
- Overlays are the last child of the screen-root `Box`, trap focus, and set
  `OverlayVisibility.dialogOpen`.
- A `MutableStateFlow` counter used as an event trigger needs `.drop(1)`, or its
  initial value fires on cold start. That is how a 0-valued "open Now Playing" counter
  pushed the screen on every launch. Nullable request flows that are consumed and reset
  to `null`, such as `AppIntents.request`, must **not** drop, or the launch request is
  lost.

### Lyrics (`data/LyricsStore.kt`, `TtmlLyrics.kt`, `LyricBreaks.kt`, `ui/nowplaying/LyricsPane.kt`)
- Changes must keep the rules in [docs/LYRICS.md](docs/LYRICS.md). Translation pairing
  uses adjacent source rows before sorting and clamping, and only the first follower
  pairs. Breaks need a gap of at least 4 s after every earlier vocal. Unsynced lyrics
  get no break dots.
- Parser changes need tests in `LyricsStoreTest`, `TtmlLyricsTest` or `LyricBreaksTest`.

## Conventions to enforce

- Dependency versions live only in `gradle/libs.versions.toml`, never in a module
  build file.
- Use the tokens from `ui/theme/Tokens.kt` (`AuraSpacing`, `AuraTypography`,
  `AuraShapes`, `AuraSizes`, `AuraColors`) and `AuraMotion` springs. Flag hardcoded
  dp/sp/colour values and one-off `tween`s in shared UI.
- Focused elements need a visible focus ring. Scrolling screens keep a bottom spacer
  of about 120 dp so the now-playing bar never covers the last row.
- Lists use `LazyColumn`/`LazyRow` with stable keys and content types. Apply focus
  scale in `graphicsLayer`. Flag timer-driven focus hand-off and recomposition on every
  frame.
- Heavy screens have a `ViewModel` exposing a `StateFlow` of a state data class.
  Failures are shown with `UiError` and `ErrorPane`, never as an empty grid.
- `AuraTag` values in `util/AuraLog.kt` are a contract for the headless verification
  harness. Renaming or removing a tag is a breaking change.
- Coil config (`RGB_565`, 20% memory cap, no crossfade) is set only in
  `AuraApp.newImageLoader`.
- New code talks to `remote/MirrorServer` directly rather than going through the
  `ui/MirrorLink.kt` / `ui/MirrorPairing.kt` shims.
- `ui/DebugNav.kt` deep links work only in debuggable builds. Flag a debug path that
  can be reached in release.

## Lint baseline

`app/lint-baseline.xml` freezes existing findings, and `abortOnError = true` fails the
build on new ones. Shrinking the baseline is fine. Flag a PR that grows it or adds
`@SuppressLint` without a reason.

## Low priority / skip

- `app/src/release/generated/baselineProfiles/*.txt` are generated baseline profiles.
  Skip them unless the PR also changes the generator in `baselineprofile/`.
- `app/schemas/**.json` are Room exports. Only check that they match the entity
  changes.
- `app/lint-baseline.xml`: review only whether it grows or shrinks.
- `gradle/wrapper/`, `gradlew`, `gradlew.bat`: skip unless the Gradle version changes.
- `docs/images/`, `docs/release-notes-*.md`: skip, except to check facts against the
  code.
- `scripts/common.sh` hardcodes the author's macOS paths and device serials on
  purpose. Don't flag that.
- Release builds signed with the debug key are intentional. Don't flag them.
