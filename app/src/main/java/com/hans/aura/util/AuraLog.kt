package com.hans.aura.util

import android.util.Log

/**
 * The logcat tags Aura ships with.
 *
 * The string values are a public surface: `adb logcat -s AURA_QUEUE` recipes,
 * the docs and the headless verification loop all depend on them, so they are
 * spelled out here EXACTLY as they were inline before this file existed. Never
 * rename a tag — add one instead.
 */
enum class AuraTag(val tag: String) {
    AUTH("AURA_AUTH"),
    NAV("AURA_NAV"),
    MENU("AURA_MENU"),
    QUEUE("AURA_QUEUE"),
    PEEK("AURA_PEEK"),
    THEME("AURA_THEME"),
    LRCLIB("AURA_LRCLIB"),
    ATMOS("AURA_ATMOS"),
    LIBS("AURA_LIBS"),
    MIRROR("AURA_MIRROR"),
    GENRES("AURA_GENRES"),
    AUTOPLAY("AURA_AUTOPLAY"),
    SMART("AURA_SMART"),
    SLEEP("AURA_SLEEP"),
    RESUME("AURA_RESUME"),
    SEARCH("AURA_SEARCH"),
    QC("AURA_QC"),
    AZ("AURA_AZ"),
    SIMILAR("AURA_SIMILAR"),
    LYRICS("AURA_LYRICS"),
    FAV("AURA_FAV"),
    MIX("AURA_MIX"),
    ARTIST("AURA_ARTIST"),

    /** Jellyfin remote-control websocket (PlaybackRemote). */
    REMOTE("AuraRemote"),

    /** Playback reports to Jellyfin (start/progress/stop). */
    REPORT("AuraReport"),

    /** Unity-gain re-assertions. */
    VOL("AuraVol");

    override fun toString(): String = tag
}

/**
 * Thin wrapper over [android.util.Log] that takes an [AuraTag] instead of a
 * string, so breadcrumb tags cannot drift between call sites.
 *
 * Message FORMATS are not owned here: callers keep writing the same strings
 * they always did ("skip: $why", "move from=$from to=$to", …).
 */
object AuraLog {

    fun d(tag: AuraTag, msg: String): Int = Log.d(tag.tag, msg)

    fun i(tag: AuraTag, msg: String): Int = Log.i(tag.tag, msg)

    fun w(tag: AuraTag, msg: String): Int = Log.w(tag.tag, msg)

    fun w(tag: AuraTag, msg: String, tr: Throwable): Int = Log.w(tag.tag, msg, tr)

    fun e(tag: AuraTag, msg: String): Int = Log.e(tag.tag, msg)

    fun e(tag: AuraTag, msg: String, tr: Throwable): Int = Log.e(tag.tag, msg, tr)
}
