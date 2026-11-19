## Aura Remote

Historical release notes. For current Aura protocol/lifecycle details see
[Remote control](REMOTE-CONTROL.md); for the companion's current features and setup see
[Aura Remote](https://github.com/gahtv/aura-remote).

Aura now has a companion remote you can open in any browser on your network —
no app store, no ADB, no keypad pairing. It behaves like Spotify Connect: the TV
keeps playing and owns the queue, the phone just reads it and sends commands.

**https://github.com/gahtv/aura-remote**

Browser remote for Aura: library with ATMOS badges, live queue, lyrics, and
semantic mirroring — the phone shows its own version of whatever screen the TV
is on (album, artist, library tab, Now Playing with lyrics), and navigation
travels both ways.

## What's new in Aura itself

**Mirror link** — after launch Aura serves a small local API on port
8099: current screen, playback state, the live queue, and commands for
navigation and transport. Nothing is written outside the app. The listener lives
with the process, not strictly with foreground visibility.

**Pairing** — the first time a remote connects, Aura asks you, on the TV, whether
to allow that device, the way USB debugging does. Allow mints a token; deny,
BACK, or ignoring the prompt means no access. Everything but the health check
requires that token.

**Real device names** — Aura now reports the name Android and Cast already use
for the device ("Living Room TV") instead of "Android TV", so two Auras in a
session list are finally distinguishable.

**Remote control, still intact** — the existing Jellyfin "Play On" integration is
unchanged; the new link sits alongside it. See `docs/REMOTE-CONTROL.md` for the
full protocol if you want to build your own controller or a Home Assistant
integration.

## Verified

- Mirror link: state, navigation and transport exercised against the emulator
  and live playback (`pause` → paused, `next` → next track, `skip` → queue jump).
- Pairing: unauthenticated requests rejected (401), prompt shown on-device,
  Allow issues a token, token then accepted, denial/timeout refused.
- Device name: read back from the Streamer (`Living Room TV`).
- Library, queue, lyrics and ATMOS badges verified through the remote.

## Notes

- Any existing Jellyfin remote-control setup keeps working; no migration needed.
- Your first remote connection needs one approval on the TV. It is asked once.
- Volume stays out of Aura's hands by design — the receiver owns the level.
