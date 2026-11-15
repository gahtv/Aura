# Driving Aura from outside the app

Aura is a standard Jellyfin remote-control target: it advertises capabilities on its session and honours
the server's play/transport/command messages. Anything that can authenticate to Jellyfin — Home Assistant,
a script, another app — can therefore control it without Aura knowing it exists.

This document covers (1) what Aura exposes, (2) how to call it, and (3) the extra Android-TV surface the
Aura Remote bridge adds when a Home Assistant integration needs input events rather than playback commands.

---

## 1. What Aura exposes

Aura announces itself on `/Sessions` once it is running and logged in:

```
GET /Sessions
```

A session is Aura when `Client == "Aura"`. The fields that matter:

| Field | Meaning |
|---|---|
| `Id` | session id — the `{sid}` in every call below |
| `DeviceName` | `Android TV` (streamer) or the emulator name |
| `RemoteEndPoint` | client IP; the streamer is `192.168.2.191` |
| `SupportsRemoteControl` | true only while Aura's websocket is attached |
| `NowPlayingItem` | current track, or null when idle |
| `PlayState` | `PositionTicks`, `IsPaused`, `RepeatMode`, `PlaybackOrder` |

Aura requests exactly these commands from the server:

- `Play` — with `PlayCommand` = `PlayNow` / `PlayNext` / `PlayLast`
- `Playstate` — `PlayPause`, `Pause`, `Unpause`, `Stop`, `NextTrack`, `PreviousTrack`, `Seek`
- `SetRepeatMode`, `SetShuffleQueue`
- `DisplayMessage`

**Not** `SetVolume`. Volume is deliberately absent: Aura plays bit-perfect E-AC-3 JOC and the AVR owns the
level. Sending volume commands is a no-op by design; drive the receiver instead.

### If Aura is not running

There is no session to command. Aura cannot be started through Jellyfin; see section 3.

---

## 2. Calling it

All calls are plain Jellyfin REST with an API key (Dashboard → API Keys) or a user token:

```
Authorization: MediaBrowser Client="ha", Device="Home Assistant", DeviceId="ha-1", Version="1.0", Token="<token>"
```

### Play something

```
POST /Sessions/{sid}/Playing?playCommand=PlayNow&itemIds={id1},{id2},...
```

- `PlayNow` replaces the queue and starts at the first id.
- `PlayNext` inserts after the current track without interrupting playback.
- `PlayLast` appends to the end.

An album id is valid here: Jellyfin expands it to the album's tracks plus `StartIndex`, and Aura honours
the start index.

### Transport

```
POST /Sessions/{sid}/Playing/{Play|Pause|Unpause|PlayPause|Stop|NextTrack|PreviousTrack}
POST /Sessions/{sid}/Playing/Seek?seekPositionTicks=<ticks>
```

Ticks are 100-nanosecond units: `seconds * 10_000_000`.

### Shuffle and repeat

```
POST /Sessions/{sid}/Command
{ "Name": "SetShuffleQueue", "Arguments": { "Shuffle": "true" } }

POST /Sessions/{sid}/Command
{ "Name": "SetRepeatMode", "Arguments": { "RepeatMode": "RepeatAll" } }
```

`RepeatMode` is `RepeatNone` / `RepeatAll` / `RepeatOne`. Both are `GeneralCommand`s; the arguments are
strings, `"true"` not `true`.

Aura reports the resulting state back, so a UI can mirror it by reading
`PlayState.RepeatMode` and `PlayState.PlaybackOrder` (`"Shuffle"` / `"Default"`).

### Pitfalls

- These endpoints are **query-parameter based**. A JSON body is silently ignored — you get a 204 and
  nothing happens.
- Do not call `/Playing/*` for reporting playback progress; those are the client's own `POST /Sessions/Playing`
  (body-based) and interfering corrupts the session's position.
- `PlayNow` with an empty `itemIds` is accepted and does nothing.

---

## 2b. Mirror link (shared screen state)

Aura runs a tiny HTTP server on port 8099 while it is in the foreground (`remote/MirrorServer.kt`). It exists so a
remote can draw its own native version of the screen Aura is on, and so the remote can move the TV.

```
GET  http://<tv>:8099/state
     {"screen":"nowplaying","lyrics":true,
      "playing":true,"positionMs":4193,"durationMs":210000,
      "trackId":"…","trackTitle":"Vanessa","trackArtist":"…",
      "queue":[…],"queueIndex":0,"shuffle":false,"repeat":0}

POST http://<tv>:8099/navigate?screen=collection&itemId=…   -> 204
POST http://<tv>:8099/control?action=pause                  -> 204
POST http://<tv>:8099/input?key=down                        -> 204
```

`control` actions: `play`, `pause`, `playpause`, `next`, `prev`,
`seek&position=<ms>`, `skip&index=<n>`, `shuffle&on=true|false`, `repeat&mode=0|1|2`
(`0` none, `1` one, `2` all). These are semantic — the endpoint moves its own
player, exactly like Spotify Connect, rather than receiving synthetic keystrokes.

`input` keys: `up`, `down`, `left`, `right`, `select`, `back`, `playpause`, `home`
(`home` means Aura's Home screen). They are dispatched **inside the app** as
`KeyEvent`s, so no ADB and no USB debugging is involved.

### Pairing

Every endpoint except `/health` and `/pair/request` requires `X-Aura-Token`.
Pairing follows the USB-debugging model — nothing is trusted until someone in
front of the TV says so:

```
POST http://<tv>:8099/pair/request?name=<device name>
  -> 200 {"token":"…","device":"Living Room TV"}    (after Allow)
  -> 403 {"error":"denied"}                          (after Deny, BACK, or 90 s)
```

Aura shows a full-screen prompt naming the requesting device, with Allow
focused and BACK = deny. Only Allow mints a token, which is stored in Aura's
prefs and survives restarts; `MirrorServer.unpair()` clears it. The prompt is
composed on every branch of the UI, including the signed-out login screen, so a
remote can pair before anyone has signed in on the TV.

`/state` also reports `deviceName` — the name Android/Cast already uses for the
device (`Settings.Global.DEVICE_NAME`, e.g. "Living Room TV"), falling back to
`Build.MODEL`. Aura sends the same name in its Jellyfin capability reports, so
two Auras in a session list no longer both read "Android TV".

`screen` is one of `home`, `artists`, `albums`, `tracks`, `playlists`, `search`, `settings`, `nowplaying`,
`queue`, `collection`, `artist`. `itemId` is set for `collection` and `artist`. `lyrics` is true only while
the Now Playing screen is showing lyrics rather than artwork — that flag is what lets a remote mirror the
lyrics view exactly when the TV shows it.

Notes:

- No state is written outside the app, and calls are LAN-only — but the link is **not** open: every
  endpoint except `/health` and `/pair/request` requires the pairing token (see below).
- It is a foreground-only service: when Aura is closed the port is closed, which is also how a controller
  can tell the app is not running.
- The protocol is deliberately one-way-cheap: poll `/state` at 1–2 Hz; there is no push channel.
- Names are the remote's vocabulary. `collection`/`artist` are published, never the internal stack keys.

The Aura Remote bridge proxies both endpoints so a browser can reach them same-origin:
`GET /api/aura/state`, `POST /api/aura/navigate {screen, itemId}`.

---

## 3. Android TV surface (Aura Remote bridge)

When a controller must do more than playback — wake the TV, launch Aura, press D-pad keys, or read what is
on screen — it needs an agent on the Android side. The bridge in `~/Projects/aura-remote` provides it over
ADB (no root, no app changes):

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/launch` | wake the device and start `com.hans.aura/.MainActivity` |
| GET | `/api/device` | **deprecated** — foreground window; `aura_foreground` tells you if Aura is up |
| POST | `/api/key` | **deprecated** — `{key: up\|down\|left\|right\|center\|back\|home\|playpause\|next\|prev\|wake}`; use Aura's `/input` |
| GET | `/api/tvstate` | **deprecated** — accessibility tree of the current screen as positioned nodes |
| GET | `/api/screen` | **deprecated** — JPEG frame of the TV |
| GET | `/api/targets` | Cast targets on the LAN |
| POST | `/api/load` | `{device, url, content_type}` — cast a direct-play URL |
| POST | `/api/transport` | `{device, action}` on a Cast target |
| GET | `/api/status?device=` | now playing on a Cast target |

The deprecated rows are the ADB surface: with Aura's mirror link (§2b) a controller sends D-pad keys through
the app itself (`/input`) and reads screen state semantically (`/state`), so nothing needs USB debugging
except starting Aura when it is closed. They stay reachable so older scripts do not break; the bridge's own
UI no longer calls them.

**Deprecated.** `/api/tvstate` was the basis for a remote that redraws the TV screen in HTML — each node
carries `text`, normalised `l/t/w/h` (0–1), and `focused`. Aura's own `/state` made it unnecessary.

### Home Assistant sketch

```yaml
rest_command:
  aura_play_album:
    url: "http://192.168.2.166:8096/Sessions/{{ sid }}/Playing?playCommand=PlayNow&itemIds={{ album }}"
    method: POST
    headers:
      Authorization: 'MediaBrowser Client="ha", Device="Home Assistant", DeviceId="ha-1", Version="1.0", Token="<token>"'
  aura_pause:
    url: "http://192.168.2.166:8096/Sessions/{{ sid }}/Playing/Pause"
    method: POST
    headers:
      Authorization: 'MediaBrowser Client="ha", Device="Home Assistant", DeviceId="ha-1", Version="1.0", Token="<token>"'
  aura_launch:
    url: "http://192.168.2.166:8100/api/launch"
    method: POST
```

A full integration would poll `/Sessions` for the Aura session id (it changes per app launch) and merge
`NowPlayingItem` + `PlayState` into a `media_player` entity. Because Aura refuses volume commands, expose
volume through the receiver, not the player.

---

## 4. Guarantees and non-goals

- Aura never attenuates audio in-app: no volume control, unity gain on every track change.
- Queue contents are not published to `/Sessions` by Jellyfin 12.2.0 (the server only binds
  `NowPlayingQueue` on playback stop). Any UI must build its own queue view from what it sent.
- Remote control requires Aura to be foreground and logged in; the session disappears when the app is
  closed, which is what the launch endpoint exists for.
