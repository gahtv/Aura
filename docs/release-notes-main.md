# Aura 0.7.0-alpha.1 and development notes

The rewrite source declares **0.7.0-alpha.1**, version code **9**. This is a prerelease
source version, not an APK publication announcement. The earlier release notes remain
a record of their original releases, including the checks reported at the time.

## Material 3 Expressive rewrite

- A side navigation rail replaces the top bar. It expands on D-pad focus and puts Home,
  Library, Genres, Liked, Search, Settings and Now Playing within reach.
- Album and artist artwork seeds dark Material 3 colour schemes in HCT (hue, chroma,
  tone), giving artwork-led screens coordinated surfaces, text and accents.
- Room persists cached library lists and Atmos catalog results across app restarts.
  Browse lists have a 24-hour lifetime and can be cleared with **Refresh library now**.
- Screens and playback state use immutable domain models such as `Track`, `Album` and
  `Artist` instead of Jellyfin's network DTOs.

## Library and data

- Browse work routes through `SourceQueries` and a `MusicSource` selected at startup.
  Jellyfin is the only implemented backend; this is not multi-server-provider support.
- Album-artist queries and album letter jumps work through that seam.
- Jellyfin bios, timed lyrics and play-history hooks are wired into source capabilities.
  Manual library refresh also drops detail/bio/lyric caches; failed server lyric fetches
  can be retried rather than remembered as permanent misses.

## Lyrics

- Enhanced LRC, Jellyfin word cues and TTML can supply word timings for karaoke highlights.
  TTML relative span times resolve against their paragraph, and lines sort by playback time.
- Singer markers are retained and duet/group lyrics receive distinct alignment/colour.
  Lyrics without word timing still highlight whole lines; unmarked lyrics stay centred.
- Adjacent same-timestamp LRC rows pair as lead and translation, with the translation
  shown beneath the lead. This applies to raw LRC (including LRCLIB) and Jellyfin
  structured lines with `Start`; cue-only lines and explicit different-voice replies
  remain separate. Repeated-stamp copies retain their translations.
- Three breathing dots fill during synced intros and known instrumental gaps of at
  least 4 seconds. Blank timestamp rows mark the previous line's end instead of
  rendering empty rows. Without explicit line ends, only intro dots can be inferred;
  no outro is guessed. See [lyrics formats and timing](LYRICS.md).

## TV polish

- Shared M3 typography and semantic corner shapes, warmer sign-in fields and focus rings.
- Spring-scaling artwork cards clipped to their rounded outline while focused.
- Accent-filled collection Play and Now Playing Play/Pause controls with readable text/icon ink.
- A seek bar that waves while playing and flattens when paused; D-pad seeks use the live position.
- Now Playing D-pad: DOWN from Play/Pause focuses Shuffle; DOWN from Shuffle or Repeat opens
  the "up next" peek, and closing it returns focus to the control that opened it.
- Clearer current-lyric emphasis and aligned Now Playing title/album/artist text.

## Boundaries

- Motion-artwork rendering and catalog-import hooks exist, but Jellyfin does not enable
  these capabilities. Album covers remain still in the current build.
- No Atmos hardware validation is implied by these source notes. Use a compatible TV,
  HDMI path and receiver to verify passthrough; an emulator cannot prove it.
