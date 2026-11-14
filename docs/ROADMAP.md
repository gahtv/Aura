# Roadmap

What Aura does today, what is being worked on next, and what is deliberately out of scope. Written for
people using Aura, not for the issue tracker — plans change, and this is a direction rather than a promise.

Companion app: **[Aura Remote](https://github.com/gahtv/aura-remote)** — a browser remote with queue,
lyrics, mirroring and pairing.

## Shipped

**Playback**
- Direct play of E-AC-3 JOC (Dolby Atmos) M4A from Jellyfin, bitstream untouched to an Atmos-capable
  receiver. No transcoding, no downmix, no in-app attenuation.
- Gapless queue playback, shuffle and repeat, seek, and playback reports so server play counts stay right.

**Library**
- Home shelves, Artists / Albums / Tracks / Playlists with paged loading, and search across all three types.
- Artist pages with albums and top tracks, album pages with full tracklists, artist/album bios.
- ATMOS badges on albums and tracks that carry an E-AC-3 JOC stream.
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

## Next

- **Retire ADB.** Auto-launching Aura from the remote is the only thing that still needs USB debugging;
  moving to the Android TV Remote v2 protocol (PIN pairing) removes it entirely.
- **Live state instead of polling.** The remote polls the mirror link every couple of seconds; a push
  channel would make queue and progress changes instant.
- **Library selection.** Choose which Jellyfin libraries Aura shows, and keep the rest out of the caches.
- **Queue editing.** Reorder the queue and playlists from the TV remote through menu actions
  (move up / down / top / bottom) rather than drag, which fights the focus model.
- **Quick-connect and server switching.** Sign in without typing a password on a TV remote, and change
  servers from Settings without clearing the app.

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
