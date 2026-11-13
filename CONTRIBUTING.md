# Contributing

Personal project, but PRs are welcome. Ground rules:

- **Verify on a release build** before claiming a perf or playback fix — debug builds are
  not representative of TV hardware behavior.
- **No hardcoded secrets.** API keys go in a gitignored root `.env` (see `.env.example`);
  they surface as `BuildConfig` fields.
- Follow the existing patterns: manual back-stack navigation in `AppRoot`, shared
  `JellyfinClient` `Json` with `encodeDefaults = true`, overlays at screen root,
  `docs/ARCHITECTURE.md` has the full list of invariants.
- Keep the user-visible UX rules: unmistakable focus outlines, ~120dp bottom spacer so
  the now-playing bar never covers the last row, BACK restores exact focus + scroll.
