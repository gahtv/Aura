# AI Usage Disclosure

Aura is a personal project that was built **with substantial AI assistance**.
This file describes exactly how, so anyone evaluating the codebase knows what
they're looking at.

## How the project was built

- Aura was developed **iteratively in collaboration with an AI coding agent**
  (Hermes Agent, driving GLM models from Z.ai / Nous Research tooling), working
  in conversation with the project author.
- The AI wrote effectively **all of the application code** in this repository:
  the Compose TV UI, the Jellyfin API client, the Media3 playback and Atmos
  routing, the cast/remote-control stack, caching, and build configuration.
- The human's role was **directing the project**: deciding what the app should
  do, specifying the UX in detail (Spotify-like look, focus behavior, dark
  theme, album-art theming), testing every build on real hardware (a Google TV
  Streamer and an Android TV emulator), and reporting bugs in short, itemized
  lists. Almost no code was written by hand; design judgment, priorities, and
  acceptance of results came from the human.
- The architecture, README, and this repository's documentation were also
  AI-drafted and human-approved.

## What this means for you

- The code is **not line-by-line hand-reviewed** by a human before it ships.
  Treat it accordingly: it works on the author's hardware (Jellyfin 12.2.0,
  Google TV Streamer, Android TV API 26+), but it has had **no security
  review** and only informal testing.
- You use it with your own Jellyfin server at your own risk. Credentials are
  sent only to your configured Jellyfin server; the app contains no telemetry
  and no third-party network calls beyond your Jellyfin host.
- Contributions, issues, and bug reports are welcome — with the understanding
  that fixes will likely also be AI-assisted.

## Scope of AI use in this repository

| Area | AI involvement |
|---|---|
| Application code (Kotlin, Compose, Media3) | Written by AI agent, human-directed and human-tested |
| Build config (Gradle, R8, scripts) | Written by AI agent |
| Documentation (README, ARCHITECTURE, this file) | AI-drafted, human-approved |
| Testing | Human, on real/emulated hardware |
| Security review | None — AI-assisted development, not audited |
