# Roadmap

Where Aura is, where it is going, and what it will deliberately never do. Aura is a
personal project; this list follows what gets used in a real living room.

**Current source version:** `0.7.0-alpha.1` — see the
[release notes](release-notes-main.md).

## Shipped

### Listening
- **Dolby Atmos direct play** — E-AC-3 JOC is requested untouched and bitstreamed to
  the TV/AVR; ATMOS badges, an Atmos filter and a Your Atmos shelf on Home.
- **Synced lyrics** — LRC, enhanced LRC, Jellyfin cues and TTML with word-level
  karaoke, duets, translations, instrumental breaks and per-track offsets; optional
  LRCLIB lookup.
- **Queue control** — reorder, clear upcoming, smart shuffle, resume, sleep timer,
  radio from any item and autoplay when the queue ends.
- **HDMI-friendly pause** — paused passthrough output is released so the HDMI sink is
  free for other apps.

### Browsing
- **Side navigation rail** — Home, Library, Genres, Liked, Search, Settings, with
  Now Playing pinned at the bottom.
- **Library** — albums, artists, tracks and playlists with sorting, paging and A–Z jump;
  artist bios, popular tracks and similar artists.
- **Playlists** — create, add, remove and reorder.
- **Artwork-driven theming and shared-element transitions** between screens.

### Accounts and setup
- **Quick Connect sign-in** and **household profiles** on the same server.
- **Library selection** — hidden libraries are excluded at the query level.
- **Room-backed cache** for library lists and the Atmos catalogue, with manual refresh.

### Android TV and remotes
- **Home-screen channel** with recent and Atmos albums.
- **System search** and `aura://item/…` deep links.
- **Jellyfin "Play On" target** for any Jellyfin client.
- **Paired LAN mirror link** for [Aura Remote](https://github.com/gahtv/aura-remote):
  state, navigation, transport and key input.

### Engineering
- **Clean source seam** — UI depends on `SourceQueries`/`MusicSource` and immutable
  domain models, not Jellyfin DTOs.
- **Material 3 Expressive motion**, lazy shelves and a frame-locked playback clock.
- **Baseline profile** generation for release builds and a ~80-file JVM test suite.

## Next

- **Push instead of polling for remotes.** The mirror link serves HTTP snapshots
  (`/state`); a push channel would make queue and progress changes instant in Aura
  Remote.
- **Per-device Atmos validation matrix.** Record which TVs, streamers and AVRs get true
  Atmos versus a PCM downmix, per sink, instead of assuming it.
- **Portable dev scripts.** Make `scripts/common.sh` read paths and serials from the
  environment so `scripts/check.sh` works on any machine.

Remote launch and discovery belong in [Aura Remote](https://github.com/gahtv/aura-remote):
Aura's own HTTP link cannot start an app whose process is not running.

## Considering

- **Home Assistant volume bridging.** Aura never touches volume (the receiver owns it),
  so an HA slider would have to drive the AVR, not the app.
- **More music sources.** The `MusicSource` seam allows a second backend; none is
  planned until Jellyfin support is complete.
- **Continuous integration** running compile, lint and JVM tests on every PR.

## Not planned

- **Crossfade.** It needs in-app decoding, which breaks the bitstream passthrough that
  makes Atmos work.
- **In-app volume.** Same reason: any attenuation happens after the JOC decode and
  defeats the point.
- **Phone-native remote apps.** Aura Remote is a web app on purpose: one codebase, any
  browser on the network.

## Requests

Open an issue on [Aura](https://github.com/gahtv/Aura/issues) or
[Aura Remote](https://github.com/gahtv/aura-remote/issues) with your setup and what is
missing. Feedback from real living-room setups has driven most of this list.
