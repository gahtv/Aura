# Aura 0.4.0-alpha.1

This release is about the two copies of everything. A library that keeps a standard FLAC copy of an
album next to its Dolby Atmos master now shows both, and there is a one-press way to see only the Atmos
side of your collection.

Verified on the emulator (Android TV API 34) against Jellyfin 12.2.0; Atmos passthrough itself is
unchanged and still needs a real receiver.

## Library

- **Both versions of an album are listed.** Artist pages no longer hide one copy of a release — the
  Atmos master shows up next to the standard one, marked with the ATMOS badge. Earlier behaviour picked
  one and it was usually the version you did not want to play.
- **Atmos filter.** A small spatial-audio icon at the start of the filter row on the Albums and Tracks
  tabs, and in the artist page header, narrows the view to Atmos albums and Atmos tracks. It is a view
  filter: the queue, Now Playing and your playlists are never touched.
- **Settings > Only show Atmos content** does the same thing everywhere for good — Home shelves, the
  library tabs, artist pages, album tracklists, search and genre pages. Useful when a single library
  holds both the standard and the Atmos release.

## Artist pages

- Similar artists and top tracks are reachable again: the D-pad was stuck on the albums row and could
  not move down, so the rest of the page could only be found by swiping. The page is rebuilt on a
  lazily-laid-out list and the cursor walks albums → similar artists → top tracks.
- Opening an artist always starts at the top of the page, instead of restoring the previous visit's
  scroll position.

## Sorting and jump-to-letter

- Picking a letter on the Albums or Artists grid then tapping a different sort (Most played, Date
  added, …) now actually sorts by it. Before, the sort pill highlighted itself while the list came back
  in alphabetical order, because the letter jump had pinned the sort.
- The letter rail gained an **All** chip so you can drop the letter without leaving it open.

## Small things

- Album covers show the heart only when the album is liked. Unfavourited covers are clean artwork
  again; the fallback was a grey heart on every tile.
- Favouriting from the long-press menu is unchanged, and track rows keep their heart indicator.

## Installing

Download `aura-0.4.0-alpha.1.apk` and sideload it over any previous build — the release is
debug-signed, so `adb install -r` works. This is a prerelease.
