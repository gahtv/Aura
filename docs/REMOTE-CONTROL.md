# Driving Aura from outside the app

Aura exposes a Jellyfin remote-control session and a paired local HTTP mirror link.
The companion browser remote is maintained in [Aura Remote](https://github.com/gahtv/aura-remote);
its deployment and bridge endpoints are documented there, not specified by this repository.

## 1. Jellyfin remote target

Once signed in with its playback service running, Aura registers capabilities and keeps
a Jellyfin websocket attached (`playback/PlaybackRemote.kt`). Find it with `GET /Sessions`
on Jellyfin: `Client == "Aura"`, with the Android/Cast device name (falling back to
`Build.MODEL`), session `Id`, `NowPlayingItem` and `PlayState`. Discover the session ID
rather than hardcoding it. A missing session cannot be launched through Jellyfin.

Authenticate requests to **Jellyfin**, using a user token or server API key:

```text
Authorization: MediaBrowser Client="ha", Device="Home Assistant", DeviceId="ha-1", Version="1.0", Token="<token>"
```

### Play and transport

```text
POST /Sessions/{sid}/Playing?playCommand=PlayNow&itemIds={id1},{id2}
POST /Sessions/{sid}/Playing/Pause
POST /Sessions/{sid}/Playing/Seek?seekPositionTicks=<ticks>
```

- `PlayNow` replaces the queue; `PlayNext` inserts after the current track; `PlayLast`
  appends. Aura honours `StartIndex` and `StartPositionTicks` in the server's Play message.
  An empty item list does nothing.
- Playstate commands handled: `Play`, `Unpause`, `Pause`, `PlayPause`, `Stop`, `NextTrack`,
  `PreviousTrack`, `Rewind`, `FastForward`, `Seek`. Ticks = seconds × 10,000,000.
- Play requests use Jellyfin query parameters, not a JSON playback-report body. The
  separate `POST /Sessions/Playing` report endpoints are for clients reporting playback.

### Shuffle and repeat

```text
POST /Sessions/{sid}/Command
{ "Name": "SetShuffleQueue", "Arguments": { "Shuffle": "true" } }

POST /Sessions/{sid}/Command
{ "Name": "SetRepeatMode", "Arguments": { "RepeatMode": "RepeatAll" } }
```

Arguments are strings. Repeat values are `RepeatNone`, `RepeatOne`, `RepeatAll`.
Aura reports playback state back to Jellyfin so senders can track it.

Aura advertises `Play`, `Playstate`, `DisplayMessage`, `SetRepeatMode` and
`SetShuffleQueue`, but **does not currently handle DisplayMessage**. Volume commands
are deliberately ignored: drive the AVR, not Aura. Do not rely on Jellyfin's session
queue as Aura's live queue; use the mirror link below.

## 2. Mirror link

`remote/MirrorServer.kt` starts a plain HTTP listener on **0.0.0.0:8099** when
`MainActivity.onCreate` calls `MirrorLink.start()`. It has no stop hook: the port can
remain open while the process lives, even after the Activity is backgrounded or closed.
An open `/health` does **not** prove Aura is visible, signed in, or has a bound player.
The listener cannot start Aura after its process has stopped.

### Pairing and security

```text
GET  http://<tv>:8099/health
  -> 200 ok
POST http://<tv>:8099/pair/request?name=<URL-encoded device name>
  -> 200 {"token":"…","device":"Living Room TV"}  (Allow)
  -> 403 {"error":"denied"}                     (Deny, BACK, or 90 s timeout)
```

Only these two paths are unauthenticated. All other requests require
`X-Aura-Token: <token>`; missing/wrong tokens return 401, including before first pairing.
The prompt also appears on the signed-out screen. Approval stores **one shared token**
in Aura's private preferences, surviving restarts. A new approval replaces that token
and invalidates older clients; `MirrorServer.unpair()` clears it. This is not a
per-device token registry.

HTTP is unencrypted and the listener is not restricted to a particular interface or
subnet. Use only on a trusted LAN; do not expose port 8099 to the internet. Pair requests
wait for consent on a bounded pool of HTTP workers; other requests can proceed during
pairing. Pairing prompts are serialized. Clients should use a pairing timeout longer
than 90 seconds. Incomplete requests are dropped after 2 seconds without input;
this read timeout does not limit the consent wait. At capacity (8 active and 16 queued
connections), new connections are closed.

### State and navigation

```text
GET /state
  {"deviceName":"Living Room TV","screen":"nowplaying","itemId":null,"title":null,
   "lyrics":true,"playing":true,"positionMs":4193,"durationMs":210000,
   "trackId":"…","trackTitle":"Vanessa","trackArtist":"…",
   "queue":[{"id":"…","title":"Vanessa","artist":"…"}],
   "queueIndex":0,"shuffle":false,"repeat":0}

POST /navigate?screen=collection&itemId=<id>  -> 204
```

All examples in this section require the token header. State is a polled snapshot, not
a push stream; 1–2 Hz is sufficient. The queue snapshot is capped at 300 entries, while
`queueIndex` is the player's actual index (which can exceed the returned list).
Without a player, transport fields return idle defaults; metadata may be null.

Screen names and item routes are defined in `ui/nav/Destinations.kt`; navigation is
applied in `ui/shell/MirrorWiring.kt`. `lyrics` describes the Now Playing lyrics/artwork
face. Names are `home`, `artists`, `albums`, `tracks`, `playlists`, `search`, `settings`,
`nowplaying`, `queue`, `collection`, `artist`; genre screens publish as `albums` and have
no separate remote route. `itemId` identifies collection/artist details.
Root navigation replaces the stack; detail routes resolve an item before opening.
204 acknowledges enqueueing navigation, not proof that a screen opened; unknown routes
or missing items can be ignored downstream.

### Transport and input

```text
POST /control?action=pause
POST /control?action=seek&position=12000
POST /input?key=down
```

Control actions: `play`, `pause`, `playpause`, `next`, `prev`, `seek&position=<ms>`,
`skip&index=<n>`, `shuffle&on=true|false`, `repeat&mode=0|1|2` (off/one/all).
Commands are posted to the main thread. A recognised action with a bound player returns
204 even if a required numeric argument is missing/invalid or execution later fails;
unknown actions or no player return 400. There is no mirror stop or volume action.

Input keys: `up`, `down`, `left`, `right`, `select`, `back`, `playpause`, `home`.
They dispatch in-app Android key events, without ADB. `home` navigates to Aura Home,
not Android's launcher. Unknown keys or no bound Activity return 400 (app-level `home`
does not require the Activity binding). Accepted input returns 204.

Unknown authenticated paths return 404. Parameters are URL query parameters, not JSON
bodies. Input/control responses acknowledge dispatch, so poll state to observe results.

## 3. Boundaries

- The Jellyfin session requires login; local pairing and health do not. Pairing is not
  Jellyfin authentication and grants no ability to launch a stopped process.
- Keep receiver volume separate: Aura avoids in-app attenuation to preserve passthrough.
- For browser setup, discovery, wake/launch support or Home Assistant bridge integration,
  consult [Aura Remote](https://github.com/gahtv/aura-remote). Its endpoints have their
  own lifecycle and are not Aura's port-8099 protocol.
