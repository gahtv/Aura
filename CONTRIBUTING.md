# Contributing

Personal project, but PRs are welcome. Ground rules:

- Use JDK 17 and the Gradle wrapper. Run compile, lint and JVM tests as described in
  [README.md](README.md#checks); `scripts/check.sh` first needs its shared macOS paths
  adapted on other machines. Playback and focus changes also need device checks.

- **Verify on a release build** before claiming a perf or playback fix — debug builds are
  not representative of TV hardware behavior.
- **No hardcoded secrets.** API keys go in a gitignored root `.env` (see `.env.example`);
  they surface as `BuildConfig` fields.
- Follow the existing patterns: manual back-stack navigation in `AppRoot`, shared
  `JellyfinClient` `Json` with `encodeDefaults = true`, overlays at screen root,
  `docs/ARCHITECTURE.md` has the full list of invariants.
- Route browse work through `SourceQueries` / `MusicSource`; keep Jellyfin-specific
  implementation in the data layer. Use the shared theme typography and shape tokens.
- For lyric changes, preserve the [format and timing rules](docs/LYRICS.md).
  Parser coverage lives in `LyricsStoreTest`, `TtmlLyricsTest` and `LyricBreaksTest`;
  changes to lyric presentation also need device checks.
- Keep the user-visible UX rules: unmistakable focus outlines, ~120dp bottom spacer so
  the now-playing bar never covers the last row, BACK restores exact focus + scroll.

### Regenerate the Baseline Profile

Connect a disposable Android TV device or emulator (API 33+ or rooted API 28–32), install Aura with
`./gradlew :app:installRelease`, and sign in to a Jellyfin music library. Pick
a playable album visible on Home without scrolling. With that device still
connected, run:

```sh
./gradlew :app:generateReleaseBaselineProfile -Pandroid.testInstrumentationRunnerArguments.profileAlbum="Your Album"
```

Commit the updated generated rules. Generation tests a non-minified build and
uninstalls the app from the device afterward, clearing its local data. Ordinary
release builds use the checked-in profiles and require no device or server.
