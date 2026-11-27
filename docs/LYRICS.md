# Lyrics formats and timing

Aura reads lyrics supplied by Jellyfin as structured lines, raw LRC or TTML.
Enhanced LRC, Jellyfin word cues and TTML spans can provide word-by-word highlights;
ordinary LRC stays line-synced. Plain text is a static page, not guessed karaoke.
Singer metadata controls alignment and colour when present.

When Jellyfin has no lyrics, Settings > Look up lyrics online enables optional
LRCLIB fallback (off by default). This sends track metadata, not Jellyfin credentials,
to LRCLIB; its LRC uses the same parser. A client-id header is optional (see the
[README configuration](../README.md#configuration)).

## LRC translations

Put the translation immediately after its lead line with the same timestamp:

```text
[00:10.00]Across the sky
[00:10.00]A travers le ciel
[00:18.00]We carry on
[00:18.00]Nous continuons
```

The follower appears beneath the lead, not as another current lyric. This also
works with Jellyfin structured lines sharing `Start`, and repeated-stamp LRC rows.
Inline word stamps on a translation retain their timing. Aura displays supplied
translations; it does not translate text itself.

- Pairing uses adjacent source rows before sorting and offset clamping, not just
  any two lines that happen to end up at the same playback time.
- Only the first same-stamp follower pairs; a third or later row remains separate.
- Blank/header rows interrupt pairing. A follower with an explicit different voice
  remains a separate duet reply.
- Both rows need real line timestamps: `[mm:ss.xx]` in raw LRC or `Start` in
  Jellyfin's structured response. Cue-only or inline-word-only timing is not enough.

## Intros and instrumental breaks

Three breathing dots take the current-line slot during synced intros and known
gaps lasting **at least 4 seconds**, filling in turn as the break elapses. Ended
lyrics rest like other inactive lines; the dots are not a selectable control.

A blank stamped LRC row marks the preceding line's end instead of rendering an
empty row. For example, this creates a 22–34 second instrumental break:

```text
[00:18.00]The last sung words
[00:22.00]
[00:34.00]The next verse
```

Line ends can also come from enhanced LRC's closing `<mm:ss.xx>` after its last
word, Jellyfin's final word cue `End`, or TTML paragraph `end`/`dur` (falling back
to the final word span's end). An earlier overlapping vocal or a timed adlib,
translation or romanization keeps the break from starting until it ends (an
open-ended one, or a lead without an end, runs at least to the next line's start), and a
backing vocal cued before its lead ends the intro or break at that cue: the pane then
follows the upcoming line and fills that sub-line without highlighting either lead.

Without an explicit end, a line is treated as lasting until the next line starts:
a large distance between timestamps alone does not imply an instrumental break.
Such lyrics can still show intro dots before the first line. There are no inferred
outro dots, and empty or unsynced lyrics do not produce break dots.

## Overlapping lines

Playback highlights and auto-follows the current (latest started) line, but an
earlier line rests only once it has actually finished: while its explicit end
time (line end or the latest end of any word, such as a held syllable) is still
ahead of the playback position, it keeps its word-by-word highlight at the
smaller inactive size — including several such lines at once, whether or not
they are still on screen. Lines
without an explicit end count as finished when the next line starts, and
instrumental-break dots suspend overlapping highlights while they run.
While its lead overlaps, a backing vocal, translation or romanization whose
timed parts all have an end (their own, a container's, or the translation
line's) follows only its own window; one with an open-ended timed part follows
the lead.

## Playback adjustments

Manual scrolling pauses auto-follow for 4 seconds. D-pad timing nudges while the
lyric list has focus are remembered per track; plain-text lyrics have no clock
to nudge. If no lyrics are available, Aura shows an explicit no-lyrics state.
