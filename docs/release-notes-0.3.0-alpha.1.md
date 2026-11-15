# Aura 0.3.0-alpha.1

A feature release: library selection, favourites, genres and radio, a smarter queue, and a
much better sign-in story. Everything below was exercised on the emulator against a live
Jellyfin 12 server; the playback paths were checked with logcat breadcrumbs, not by ear.

## Signing in

- **Quick Connect.** Aura shows a 6-digit code; approve it from Jellyfin Web or the phone app
  and the TV signs in without a password ever being typed on a remote.
- **Change server / sign in again** from Settings, each behind a confirmation. Changing
  server now really stops the old stream and clears its caches, so two servers cannot bleed
  into each other.

## Library

- **Choose which libraries Aura shows.** Switched-off libraries are never queried, so their
  music never appears in Home, search or the caches.
- **Liked songs.** A heart on every track and album, "Add to favourites" at the top of the
  long-press menu, a heart in Now Playing, and a Liked view on the Tracks tab.
- **Genres.** Browse by genre from the Albums tab; each genre page plays its own radio.
- **A–Z.** Jump to a letter on the Albums and Artists grids instead of scrolling from the
  top.
- **Home** gains "Recently played artists" and "Your Atmos", and artist pages list similar
  artists.

## Queue and playback

- **Reorder** the queue and playlists with Move up / down / top, and clear the rest of the
  queue from its header.
- **Resume where you left off** when you pick a track deliberately, and **keep the music
  going** when the queue runs out (switchable in Settings).
- **Smart shuffle** plays the least-played and longest-unplayed tracks first.
- **Sleep timer** on the queue screen: Off / 15 / 30 / 45 / 60 minutes, pausing at the end
  instead of stopping.
- Press **DOWN** on the transport controls for an "up next" peek, and use **Radio** to restart
  the mix from the track that is playing.

## Lyrics and search

- **Lyrics timing nudge** with the D-pad, remembered per track, plus a real "no lyrics" state.
- **Optional online lyrics** for tracks Jellyfin has none for (off by default).
- **Search** remembers your last few terms as chips, accepts ENTER, and supports voice input
  where the device has a recogniser.

## Under the hood

- One preferences store for app settings, one 24h cache, and one query path that every browse
  screen goes through — which is what makes library selection actually stick.
- Playback positions are stored locally: Jellyfin does not persist them from client reports,
  so this is the only way resume can work.
- Secrets (the lyrics lookup client id) come from the build environment, never the source.

## Known edges

- Voice search needs a recogniser; on a device without one Aura says so and carries on.
- Online lyrics are a fallback, not a replacement — most tracks already have Jellyfin lyrics.
- The played-on-device column in the multi-sink Atmos matrix is still open.
