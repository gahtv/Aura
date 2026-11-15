# Aura 0.5.0-alpha.1

This is the refactor release: the app's insides were reorganised so screens, navigation and
playback each have one owner. Nothing was removed and no screen was redesigned — the point is that
the next round of features gets built on a structure that does not fight back. A handful of real
bugs fell out of the work and are fixed here.

Verified on the emulator (Android TV API 34) against Jellyfin 12.2.0, debug and R8 release builds,
plus the full check suite (compile, lint, 90 JVM tests). Atmos passthrough is untouched and still
needs a real receiver to confirm.

## Fixed

- **Now Playing no longer opens by itself.** On a cold start with nothing playing, the app pushed
  Now Playing over whatever screen you were on. It has been doing this for a while without anyone
  noticing, because it looked like "the app resumed something".
- **The server sees the right version.** Every request told Jellyfin that Aura was `0.1.0`, so Jellyfin
  Web's device list and its remote panel showed a version that had not shipped in months. It now
  reports the version you actually installed.
- **Signing in to a different server no longer keeps streaming with the old token.** The audio source
  used to capture the Jellyfin token when playback started; after a re-sign-in or a server change it
  kept using the stale one. The token is now read per request.
- **Leaving the app no longer leaves a zombie remote session.** Closing the playback service now also
  cancels its websocket loop, which previously woke up a few seconds later and reconnected forever —
  each one showing up as a duplicate Aura in Jellyfin's session list.
- **Crashes on a dropped session are gone.** Places that assumed a signed-in session (`session!!`) now
  exit cleanly instead, so signing out on another device no longer force-closes the app.

## Under the hood

- One destination table: the mapping from a screen to its saveable-state key, its remote name, its
  debug alias and its library kind used to exist three times. It exists once.
- Screen state lives in a `ViewModel` per heavy screen (Library, Home, Album, Artist, Genres, Search)
  instead of `remember` inside the composable, keyed so a second album or a second account never
  inherits the previous screen's state.
- The player facade was split into the pieces it was hiding: controller/ticker, item registry, resume
  policy, autoplay policy, sleep timer. The main-thread rule for playback did not move.
- The LAN mirror server and its pairing prompt moved out of `ui/` into their own package; the old
  entry points still exist, so the phone remote and the protocol in `docs/REMOTE-CONTROL.md` are
  unchanged.
- Design tokens (`Tokens.kt`): colours, spacing, type sizes and TV constants are named instead of
  repeated as literals across screens. The top bar's width budget and the queue header's action row
  were re-measured at 1080p and still fit.
- Guardrails: dependencies moved into a Gradle version catalog, a lint baseline that fails on *new*
  errors only, `scripts/check.sh` (compile + lint + tests) as the one command before landing anything,
  and 90 JVM unit tests over the parts that have quietly broken before — library filtering, cache
  keys, the Atmos filter, preferences, LRC parsing, sort mapping, auth persistence.

## Known gaps

- Album/artist/genre screens still read the data singletons directly instead of the new browse
  facade; that is the last structural step, and it is not visible from the couch.
- The error pane ("couldn't load, retry") is wired on Home, Library, Album, Artist, Genres and Search,
  but the retry path was not exercised with the server actually stopped this round.

## Installing

Download `aura-0.5.0-alpha.1.apk` and sideload it over any previous build — the release is
debug-signed, so `adb install -r` works. This is a prerelease.
