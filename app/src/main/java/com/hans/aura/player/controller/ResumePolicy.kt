package com.hans.aura.player.controller

import com.hans.aura.data.ResumePoints
import com.hans.aura.data.Session
import com.hans.aura.model.Track

/**
 * Resume-where-you-left-off policy, kept separate from the player plumbing so the
 * thresholds are stated in one place.
 *
 * Jellyfin 12.2.0 never writes UserData.PlaybackPositionTicks from playback
 * reports (verified against the live server), so a locally remembered position
 * ([ResumePoints]) wins and the server value is only a
 * fallback for clients that did manage to set it.
 */
class ResumePolicy {

    /**
     * Whether a [playQueue] call should resume at all.
     *
     * null [resume] means "infer": a list started at its first entry is a
     * play-all-from-here and starts at 0, anything else is a tap on a row.
     * Play-all / shuffle / Play Next / Add to queue pass false explicitly.
     */
    fun allowsResume(
        startIndex: Int,
        size: Int,
        playWhenReady: Boolean,
        resume: Boolean?
    ): Boolean = playWhenReady && (resume ?: (startIndex > 0 || size == 1))

    /** Resume position for [item], or null when starting at zero is right. */
    fun positionMs(session: Session, item: Track?): Long? {
        if (item == null) return null
        val local = ResumePoints.position(session, item.id)
        val pos = local ?: item.resumePositionMs ?: return null
        // Shorter than 15s in is "just started"; inside the last 10s is "finished".
        if (pos <= RESUME_MIN_MS) return null
        val dur = item.durationMs
        if (dur > 0 && pos >= dur - RESUME_TAIL_MS) return null
        return pos
    }

    companion object {
        /** Resume only past the first 15s of a track… */
        const val RESUME_MIN_MS = 15_000L

        /** …and never inside its last 10s (that would replay a finished track). */
        const val RESUME_TAIL_MS = 10_000L
    }
}
