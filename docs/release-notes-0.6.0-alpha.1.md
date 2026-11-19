# Aura 0.6.0-alpha.1

Pre-release build of **Aura**, the native Android TV / Google TV Jellyfin music player with Dolby Atmos (E-AC-3 JOC) direct play.

## What's New

### Lyrics & Karaoke
- **Word-timed karaoke lyrics** for enhanced LRC, Jellyfin cues, and TTML, with smooth frame-driven word fill, duet voice styling, adlib sub-lines, and instrumental break indicators.
- **Timed translations & romanization** rendered as sub-lines beneath lead lyrics, plus songwriter credits below lyrics.
- **Jellyfin lyric provider search**: find and save lyrics from Jellyfin providers directly in the app, with off-main-thread lyric parsing and absent-lyric caching.

### Visual & TV UX Polish
- **Material 3 Expressive** typography, semantic corner shapes, spring-motion artwork focus scaling, and wavy Now Playing seek bar.
- **Artist logos & banners** on artist pages, plus off-main-thread album-art palette extraction.
- **Multi-disc album tracklists** with disc separators and per-disc track numbers.
- **Now Playing & Queue**: improved D-pad focus navigation, Up next peek jump accuracy, Media3 shuffle order display, and repeat-one cycling.

### Performance & Reliability
- **Concurrent Home shelves** and shared Atmos catalog scans, with Retry support alongside partial results.
- **Hidden-library paging** with bounded merge cursors and accurate count-only totals.
- **MirrorLink & Cast**: concurrent MirrorLink client handling and parallel remote-cast queue loading.
- **Playlist fixes**: accurate occurrence removal on Jellyfin 12, refreshed playlist lists after creation, and compact playlist picker sizing.

## Install

Download `app-release.apk` below and sideload onto your Google TV / Android TV device:

```bash
adb install -r app-release.apk
```
