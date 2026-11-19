# Aura

A native Android TV music app for [Jellyfin](https://jellyfin.org), built for a Google TV
streamer. Spotify-style UI (Compose TV + Media3) that **direct-plays Dolby Atmos** M4A
(E-AC-3 JOC) to an AVR via passthrough.

Companion phone remote: **[Aura Remote](https://github.com/gahtv/aura-remote)**.
Where the project is going: **[Roadmap](docs/ROADMAP.md)**.

Current source version: **0.5.0-alpha.1** (version code 7), with subsequent library,
lyrics and UI improvements on `main`. This is still a prerelease; Atmos passthrough
requires compatible TV/HDMI/receiver hardware, not just an emulator.
See [current main notes](docs/release-notes-main.md) and the
[0.5.0 release notes](docs/release-notes-0.5.0-alpha.1.md).

![Platform](https://img.shields.io/badge/platform-Android%20TV%20(API%2026%2B)-green)
![Kotlin](https://img.shields.io/badge/kotlin-2.x-blue)

## Features

- **Atmos direct play** — E-AC-3 JOC passes through untouched (no transcode) to a
  Dolby Atmos-capable AVR
- Spotify-like browse UI: Home shelves, library tabs (Artists / Albums / Tracks /
  Playlists) with 60-item paging, search across tracks/albums/artists
- **Liked songs** — a heart on liked album covers and on tracks, a Liked view, and
  favourites kept in sync with the server
- **Genres, radio and similar artists** — browse by genre from the Albums tab, start a
  mix from any track/album/artist/genre, and jump to a letter on big grids
- **Synced LRC lyrics** with a D-pad timing nudge remembered per track, a real "no lyrics"
  state, and optional online lookup (LRCLIB) for tracks Jellyfin has none for
- **Word-timed lyrics** from enhanced LRC, Jellyfin cues or TTML when supplied by the
  server; singer/duet markers drive alignment and colour. Line-only lyrics stay line-synced.
- TV-focused visual polish: spring-scaling artwork cards, shared rounded shapes and
  typography, artwork-accented Play controls, and a seek bar that waves while playing
- **Queue you can steer** — reorder and clear from the menu, resume where you left off,
  smart shuffle, an "up next" peek off the transport controls, and a sleep timer
- Playlists: create, add, remove, reorder; server mutations evict cached browse data
- **Sign-in without a keyboard** — Quick Connect (approve a code from your phone or the
  web UI), plus change-server and re-auth from Settings
- **Choose which libraries Aura shows** — switched-off libraries are never queried, so
  their music never reaches Home, search or the caches
- **Cast & remote control**: "Play On" casting, and Aura acts as a full Jellyfin
  remote target (Play/Pause/Seek/Stop/queue/shuffle/repeat) from any web sender
- **Phone remote**: Aura serves a local mirror link (screen state, queue,
  transport, in-app input) and pairs with a browser remote on first use — see
  [Aura Remote](https://github.com/gahtv/aura-remote) and the
  [roadmap](docs/ROADMAP.md)
- Artist/album bios from Jellyfin item overviews, full-screen reader for long text
- Dynamic album-art theming on detail screens (96px palette decode, process-cached)
- ATMOS badges on album covers, artist albums, genre pages and track rows, and an
  **Atmos filter**: an icon on the Albums, Tracks and artist pages, plus
  Settings > Only show Atmos content for when one library holds both versions
- Focus restoration: BACK returns to the exact focused item at the same scroll
  position on every browse screen
- 24h TTL library cache with manual refresh; debug-signed release build optimized
  with R8 for low-power TV hardware

## Building

Requirements: JDK 17 (Temurin), Android SDK (platform 35 + build-tools). The Gradle
wrapper is 8.11.1 — don't substitute a system Gradle 9.x (incompatible with AGP 8.7.3).

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk

./gradlew :app:assembleDebug    # debug build (emulator iteration)
./gradlew :app:assembleRelease  # release build (deployed artifact; debug-signed)
```

The app supports Android TV API 26+ and compiles/targets API 35. To install and launch
on a connected emulator or TV (substitute `app-release.apk` for a release build):

```bash
"$ANDROID_HOME/platform-tools/adb" -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/platform-tools/adb" -s <serial> shell am start -n com.hans.aura/.MainActivity
```

Install scripts source `scripts/common.sh`, which **hardcodes macOS JDK/SDK paths**
and defaults to the author's device serials. Edit those paths/serials before using them;
exporting `JAVA_HOME` alone will not override the script:

```bash
scripts/install-emulator.sh    # debug → emulator
scripts/deploy-streamer.sh     # release → Google TV streamer, incl. launch smoke test
```

## Secrets / configuration

The app talks to Jellyfin with username+password login or Quick Connect — no API keys
are needed for Jellyfin and none are hardcoded. The one outbound call (optional online
lyrics) identifies itself with a client id kept in a root-level `.env` (gitignored):

```
cp .env.example .env
# LRCLIB_APP_ID=your-app-id        # https://lrclib.net — public rate-limit identifier
```

Each `KEY=VALUE` line becomes a `BuildConfig.KEY` String field, generated by
`app/build.gradle.kts` at build time. Builds without `.env` still compile (the field is
empty and LRCLIB requests omit the app-id header; enabling online lookup still works).
Online lookup is off by default and sends track/artist/album/duration metadata to LRCLIB.
`MUSIC_SOURCE` defaults to `jellyfin`; the current `BackendFactory` constructs
`JellyfinSource` unconditionally, so changing that field alone does not switch backends.
Never commit `.env` or hardcode keys in source.

## Project layout

```
app/src/main/java/com/hans/aura/
  di/          AppContainer — builds the stores in a fixed order (Prefs, LibraryCache,
               AtmosFilter, MirrorServer, JellyfinClient, MediaRepository)
  data/        JellyfinClient (Retrofit + kotlinx.serialization), models, AuthStore,
               Prefs (settings + resume points), LibraryFilter (library selection),
               LibraryCache (24h TTL), Favorites, Mixes, LyricsStore, Lrclib, AtmosCatalog,
               MediaRepository, MusicSource + JellyfinSource, SourceQueries (browse entry point),
               timed LRC/TTML parsing and lyric voice metadata
  playback/    PlaybackService (Media3 session, Atmos routing, playback reports),
               PlaybackRemote (cast + remote-control websocket)
  player/      controller/ — PlaybackController, QueueRegistry, ResumePolicy,
               AutoplayPolicy, SleepTimer; ui/player/PlayerConnection is the facade
  remote/      MirrorServer (LAN mirror link on :8099) and the pairing prompt
  ui/          nav/ (destinations + back stack), shell/ (top bar, mirror/debug wiring),
               screens (home, library, genres, search, artist/album, now playing + lyrics,
               queue, playlists, settings, login), common/, theme/ (palette + design tokens)
  util/        AuraLog (log-tag contract)
scripts/       adb install/deploy helpers, check.sh
```

Each screen that fetches has a paired `*ViewModel` with a `StateFlow`; failures surface as a
retry affordance (`ErrorPane`) instead of an empty grid.

## Checks

```bash
# With scripts/common.sh adapted to your machine:
scripts/check.sh    # compile (forced) + lint (baseline) + JVM unit tests — no device

# Portable equivalent using your exported JDK/SDK paths:
./gradlew --max-workers=2 :app:compileDebugKotlin --rerun-tasks
./gradlew --max-workers=2 :app:lintDebug
./gradlew --max-workers=2 :app:testDebugUnitTest
```

The version catalog (`gradle/libs.versions.toml`), the lint baseline and the JVM
suite are the guardrails: a new lint error fails the build, and the tests cover library
filtering, cache keys, the Atmos filter, preferences, LRC parsing, sort mapping and auth
persistence, source routing, timed lyrics, motion scheduling and UI helpers.

## Notes

- Release is signed with the debug key on purpose, so R8-optimized builds install
  over debug builds with `adb install -r`.
- `androidx.tv:tv-material` has no `TextField`/`CircularProgressIndicator` in 1.0.x —
  inputs come from `androidx.compose.material3`.
- Coil decodes artwork as RGB_565 with a 20% memory cache; palette extraction uses a
  96px bitmap. TV hardware is CPU-starved — bench on release builds, not debug.

## AI usage

This project was built with AI assistance — the AI agent wrote essentially all
of the code under iterative direction and testing by the author. Full details
in [AI_USAGE.md](AI_USAGE.md). Use accordingly: it works on the author's
hardware, but has had no security review.

## License

MIT — see [LICENSE](LICENSE).
