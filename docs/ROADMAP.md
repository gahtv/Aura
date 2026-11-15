# Roadmap

What Aura does today, what is being worked on next, and what is deliberately out of scope. Written for
people using Aura, not for the issue tracker — plans change, and this is a direction rather than a promise.

Companion app: **[Aura Remote](https://github.com/gahtv/aura-remote)** — a browser remote with queue,
lyrics, mirroring and pairing.

## Shipped

**Under the hood** (0.5.0)
- The app was restructured so screens, navigation and playback each have one owner: one
  destination table instead of three copies of the same mapping, screen state that survives
  navigation instead of dying with the view, and a single place where the player is wired up.
  Behaviour is unchanged — the visible differences are the fixes listed in the release notes
  (no phantom Now Playing screen at launch, and the correct app version reported to the server).

**Playback**
- Direct play of E-AC-3 JOC (Dolby Atmos) M4A from Jellyfin, bitstream untouched to an Atmos-capable
  receiver. No transcoding, no downmix, no in-app attenuation.
- Gapless queue playback, shuffle and repeat, seek, and playback reports so server play counts stay right.

**Library**
- Home shelves, Artists / Albums / Tracks / Playlists with paged loading, and search across all three types.
- Artist pages with albums and top tracks, album pages with full tracklists, artist/album bios.
- ATMOS badges on albums and tracks that carry an E-AC-3 JOC stream, and an Atmos filter: an icon on the
  Albums, Tracks and artist pages shows only Atmos items, and Settings > Only show Atmos content does it
  everywhere (handy when one library holds both the standard and the Atmos release).
- Both copies of an album are listed when your library has a standard and an Atmos version — the Atmos one
  is marked with the badge.
- Colour theming derived from the artwork on artist, album and Now Playing screens.

**Now Playing**
- Synced LRC lyrics, top-anchored with the current line held in view.
- Transport controls, jump ±10 s, queue view, and a now-playing pill in the top bar.

**Integration**
- Full Jellyfin remote-control target ("Play On"): play now/next/last, pause, seek, stop, shuffle, repeat,
  with state reported back so other senders stay in sync.
- Mirror link: a local API that publishes the current screen, playback and queue, and accepts navigation,
  transport and input — the foundation for the phone remote.
- Pairing: a remote must be approved on the TV before it can control anything, and device names come from
  the same place Android and Cast get them.

**Accounts and libraries**
- Sign in without a password on the TV: Quick Connect shows a 6-digit code that you approve from your phone
  or Jellyfin Web.
- Change server, or sign in again on the same server, from Settings — no clearing app data. Destructive
  actions ask first.
- Choose which Jellyfin libraries Aura shows. Switched-off libraries are never queried, so their music never
  reaches Home, search or the caches.

**Library and discovery**
- Liked songs: a heart on liked album covers and on tracks, "Add to favorites" at the top of the long-press
  menu, a heart in Now Playing, and a Liked view on the Tracks tab.
- Genres: browse by genre from the Albums tab, with a "Play genre radio" button on each genre page.
- Start radio from a track, an album or an artist — the queue is seeded from Jellyfin's own instant mix.
- Artist pages list similar artists and top tracks, scroll all the way down with the D-pad; Home gains
  "Recently played artists" and "Your Atmos".
- Jump to a letter on the Albums and Artists grids instead of scrolling from A.
- Reorder the queue and playlists with Move up / down / top, and clear the rest of the queue from its header.

**Listening**
- Resume a track where you left off when you pick it deliberately.
- Keep the music going: when the queue runs out, more tracks follow automatically (switchable in Settings).
- Smart shuffle puts the least-played and longest-unplayed tracks first.
- Sleep timer on the queue screen: Off / 15 / 30 / 45 / 60 minutes, pausing at the end instead of stopping.
- Lyrics you can line up: nudge a track's timing with the D-pad when the words run early or late — remembered per
  track — and a clear "no lyrics" state when there are none.
- Optional online lyrics lookup for tracks Jellyfin has none for (off by default; LRCLIB).
- Press DOWN on the transport controls for a quick "up next" peek, and a Radio button that restarts the mix from
  the track that is playing.
- Search remembers your last few terms as chips you can re-run, takes voice input from the remote, and accepts
  ENTER without losing the field.

## Next

- **Retire ADB.** Auto-launching Aura from the remote is the only thing that still needs USB debugging;
  moving to the Android TV Remote v2 protocol (PIN pairing) removes it entirely.
- **Live state instead of polling.** The remote polls the mirror link every couple of seconds; a push
  channel would make queue and progress changes instant.

## Considering

- A multi-format validation matrix — which devices get true Atmos versus a stereo downmix, documented per
  sink rather than assumed.
- Home Assistant volume bridging. Aura deliberately never touches volume (the receiver owns it), so an HA
  slider has to drive the AVR, not the app.

## Not planned

- **Crossfade.** It requires decoding the audio in-app, which destroys the bitstream passthrough that
  makes Atmos work.
- **In-app volume.** Same reason: any attenuation happens after the JOC decode and defeats the point.
- **Phone-native apps.** The remote is a web app on purpose — one codebase in any browser on the network.

## Requests

Open an issue on either repository with what you use and what is missing. Feedback from real living-room
setups has driven most of what is here.
