# Aura architecture notes

Single-module Kotlin app. Compose TV UI, Media3 playback, Retrofit + kotlinx.serialization
for Jellyfin.

## Layers

- **`data/`** — one shared `JellyfinClient` (OkHttp/Retrofit/kotlinx-serialization).
  Auth tokens are in-memory via `AuthStore` and injected as headers per request; stream
  URIs never embed the token. `LibraryCache` keys browse queries with a 24h TTL;
  `AtmosCatalog` pages the whole audio library once to mark Atmos albums.
- **`playback/`** — `PlaybackService` (Media3 session) owns ExoPlayer; media3 `MediaItem`s
  carry only `mediaId` + URI, with full `JellyfinItem`s held in an in-process registry map
  in `PlayerConnection` (models are kotlinx-serializable, not Serializable).
  `PlaybackRemote` handles capabilities + the Jellyfin websocket: receives remote commands
  (Play/Pause/Seek/Stop/PlayNext/PlayLast/SetRepeat/SetShuffle) and reports back
  RepeatMode/PlaybackOrder so web remotes mirror Aura's state.
- **`ui/`** — Compose TV. Navigation is a manual back-stack of a sealed `Screen` in
  `AppRoot.kt`; BACK is intercepted at the Activity level (`dispatchKeyEvent`) because
  Compose focus groups consume it otherwise. Per-screen state (scroll + focused item)
  is restored via `SaveableStateHolder` + per-item `FocusRequester`s (`FocusKeeper`).

## Rules that keep it working

- ExoPlayer access from ANY non-main context throws; snapshot player state on the main
  handler and pass values into coroutines.
- The shared `Json` must use `encodeDefaults = true`, or defaulted request fields are
  silently omitted and the server resets state.
- All TV text fields: `singleLine = true` + `KeyboardOptions` imeAction.
- Full-screen overlays render at the screen root (screen-level `Box`), beat the
  now-playing bar via `OverlayVisibility.dialogOpen`, and trap D-pad focus inside.
- The deployed artifact is the **release** build (debug builds are markedly slower on
  TV hardware and are only for emulator UI iteration). R8 needs the keep rules in
  `app/proguard-rules.pro`; after every release build, smoke-test the launch and grep
  logcat for FATAL.
