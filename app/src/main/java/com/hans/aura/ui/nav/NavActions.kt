package com.hans.aura.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.hans.aura.data.JellyfinItem

/**
 * The navigation surface screens get. Implemented over a back stack today
 * ([NavState]); screens only ever call these, so the stack can change shape
 * without touching screen signatures again.
 */
internal interface NavActions {
    fun onBack()
    fun onSelectTab(tab: Screen)
    fun onOpenSearch()
    fun onOpenSettings()
    fun onOpenNowPlaying()
    fun onOpenQueue()
    fun onOpenGenres()
    fun onOpenGenre(name: String)
    fun onOpenCollection(item: JellyfinItem)
    fun onOpenArtist(item: JellyfinItem)
}

/**
 * Back stack + focus bookkeeping for the whole app.
 *
 *  - [pendingFocus] is set when a screen is PUSHED so it claims focus on entry
 *    (TV has no automatic initial focus for new content).
 *  - [focusTick] is bumped on BACK so the revealed screen pulls focus back into
 *    its content.
 */
@Stable
internal class NavState {
    var stack by mutableStateOf(listOf<Screen>(Screen.Home))
        private set

    val top: Screen get() = stack.last()

    var pendingFocus by mutableStateOf(false)
        private set

    var focusTick by mutableStateOf(0)
        private set

    fun push(screen: Screen) {
        pendingFocus = true
        stack = stack + screen
    }

    fun pop() {
        if (stack.size > 1) {
            stack = stack.dropLast(1)
            focusTick++
            pendingFocus = false
        }
    }

    /** Tab switch resets the stack to that tab. */
    fun selectTab(tab: Screen) {
        if (tab::class != top::class) {
            pendingFocus = false
            stack = listOf(tab)
        }
    }

    fun openSearch() {
        if (top != Screen.Search) {
            pendingFocus = true
            stack = listOf(Screen.Search)
        }
    }

    fun openSettings() {
        if (top != Screen.Settings) {
            pendingFocus = false
            stack = listOf(Screen.Settings)
        }
    }

    fun openNowPlaying() {
        if (top != Screen.NowPlaying) {
            pendingFocus = true
            stack = stack + Screen.NowPlaying
        }
    }

    /**
     * Replace the stack with a Home-rooted jump (remote route or debug deep
     * link). [bumpFocus] additionally asks the new screen to re-grab focus,
     * which remote-driven jumps do.
     */
    fun jumpTo(screen: Screen, bumpFocus: Boolean = false) {
        pendingFocus = true
        stack = if (screen == Screen.Home) listOf(screen) else listOf(Screen.Home, screen)
        if (bumpFocus) focusTick++
    }

    /** BACK on a root tab other than Home: go Home instead of exiting. */
    fun resetToHome() {
        pendingFocus = false
        stack = listOf(Screen.Home)
    }

    val actions: NavActions = object : NavActions {
        override fun onBack() = pop()
        override fun onSelectTab(tab: Screen) = selectTab(tab)
        override fun onOpenSearch() = openSearch()
        override fun onOpenSettings() = openSettings()
        override fun onOpenNowPlaying() = openNowPlaying()
        override fun onOpenQueue() = push(Screen.Queue)
        override fun onOpenGenres() = push(Screen.Genres)
        override fun onOpenGenre(name: String) = push(Screen.Genre(name))
        override fun onOpenCollection(item: JellyfinItem) = push(Screen.Collection(item))
        override fun onOpenArtist(item: JellyfinItem) = push(Screen.Artist(item))
    }
}

@Composable
internal fun rememberNavState(): NavState = remember { NavState() }
