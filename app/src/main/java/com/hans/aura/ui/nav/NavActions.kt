package com.hans.aura.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import com.hans.aura.model.Artist as MediaArtist
import com.hans.aura.model.CollectionItem
import kotlinx.serialization.Serializable

/**
 * One entry in [NavState]'s Navigation 3 back stack. The monotonic [id] keeps
 * duplicate pushes (e.g. Album A -> Artist X -> Album A) distinct in
 * `NavDisplay`, `SaveableStateHolderNavEntryDecorator` and
 * `ViewModelStoreNavEntryDecorator`. Serializable so `rememberNavBackStack`
 * can restore the stack after process death.
 */
@Serializable
internal data class StackEntry(
    val id: Long,
    val screen: Screen
) : NavKey

/**
 * The navigation surface screens get. Implemented over a back stack today
 * ([NavState]); screens only ever call these, so the stack can change shape
 * without touching screen signatures again.
 */
internal interface NavActions {
    fun onBack()
    fun onSwitchLibraryCategory(target: Screen)
    fun onOpenSearch()
    fun onOpenNowPlaying()
    fun onOpenQueue()
    fun onOpenGenre(name: String)
    fun onOpenCollection(item: CollectionItem)
    fun onOpenArtist(item: MediaArtist)
}

/**
 * Back stack + focus bookkeeping for the whole app.
 *
 *  - [pendingFocus] is set when a screen is PUSHED, or when a restored stack
 *    deeper than Home comes back, so the top entry claims focus on entry
 *    (TV has no automatic initial focus for new content).
 *  - [focusTick] is bumped on BACK so the revealed screen pulls focus back into
 *    its content.
 */
@Stable
internal class NavState(
    /**
     * Backing stack. [rememberNavState] hands in the `rememberNavBackStack`
     * list so every push/pop is written through to saved state and restored
     * after process death; tests use a plain in-memory list.
     */
    private val backing: MutableList<NavKey> =
        NavBackStack<NavKey>(StackEntry(0L, Screen.Home))
) {
    init {
        // A restored stack from an older build (or an empty one) falls back to
        // Home instead of rendering an unknown key.
        if (backing.isEmpty() || backing.any { it !is StackEntry }) {
            backing.clear()
            backing.add(StackEntry(0L, Screen.Home))
        }
    }

    private var nextEntryId: Long =
        (backing.maxOfOrNull { (it as StackEntry).id } ?: 0L) + 1L

    val entries: List<StackEntry> get() = backing.map { it as StackEntry }

    val stack: List<Screen> get() = entries.map { it.screen }

    val topEntry: StackEntry get() = backing.last() as StackEntry

    val top: Screen get() = topEntry.screen

    var pendingFocus by mutableStateOf(false)
        private set

    var focusTick by mutableStateOf(0)
        private set

    var lastLibraryTab: Screen = Screen.Albums
        private set

    init {
        // Resume the Library category the restored stack was on: Home ->
        // Library on a Tracks/Artists/Playlists stack must not fall back to
        // Albums. The first init block has already normalized the stack.
        lastLibraryTab = backing.asReversed()
            .map { (it as StackEntry).screen }
            .firstOrNull(NavSpec::isLibraryScreen) ?: Screen.Albums
        // A restored stack deeper than Home was a pushed screen, so its top
        // entry claims content focus like a fresh push would. A single-entry
        // Home stack keeps cold-start behavior.
        pendingFocus = backing.size > 1
    }

    private fun newEntry(screen: Screen) = StackEntry(nextEntryId++, screen)

    private fun replaceStack(vararg screens: Screen) {
        require(screens.isNotEmpty())
        Snapshot.withMutableSnapshot {
            backing.clear()
            screens.forEach { backing.add(newEntry(it)) }
        }
    }

    fun push(screen: Screen) {
        pendingFocus = true
        backing.add(newEntry(screen))
    }

    fun pop() {
        if (backing.size > 1) {
            backing.removeAt(backing.lastIndex)
            focusTick++
            pendingFocus = false
        }
    }

    fun back() {
        if (backing.size > 1) pop() else if (top != Screen.Home) resetToHome()
    }

    /** Tab switch resets the stack to that tab. */
    fun selectTab(tab: Screen) {
        val target = if (tab == Screen.Albums) lastLibraryTab else tab
        if (target::class != top::class) {
            pendingFocus = false
            replaceStack(target)
        }
    }

    /** Switch between Library categories without changing the rail destination. */
    fun switchLibraryCategory(target: Screen) {
        if (NavSpec.isLibraryScreen(target)) {
            lastLibraryTab = target
            if (target::class != top::class) {
                pendingFocus = false
                replaceStack(target)
            }
        }
    }

    fun openSearch() {
        if (top != Screen.Search) {
            pendingFocus = true
            replaceStack(Screen.Search)
        }
    }

    fun openNowPlaying() {
        if (top != Screen.NowPlaying) {
            pendingFocus = true
            backing.add(newEntry(Screen.NowPlaying))
        }
    }

    /**
     * Replace the stack with a Home-rooted jump (remote route or debug deep
     * link). [bumpFocus] additionally asks the new screen to re-grab focus,
     * which remote-driven jumps do.
     */
    fun jumpTo(screen: Screen, bumpFocus: Boolean = false) {
        pendingFocus = true
        if (screen == Screen.Home) replaceStack(screen) else replaceStack(Screen.Home, screen)
        if (bumpFocus) focusTick++
    }

    /** BACK on a root tab other than Home: go Home instead of exiting. */
    fun resetToHome() {
        pendingFocus = false
        if (backing.size == 1 && top == Screen.Home) return
        replaceStack(Screen.Home)
    }

    val actions: NavActions = object : NavActions {
        override fun onBack() = back()
        override fun onSwitchLibraryCategory(target: Screen) = switchLibraryCategory(target)
        override fun onOpenSearch() = openSearch()
        override fun onOpenNowPlaying() = openNowPlaying()
        override fun onOpenQueue() = push(Screen.Queue)
        override fun onOpenGenre(name: String) = push(Screen.Genre(name))
        override fun onOpenCollection(item: CollectionItem) = push(Screen.Collection(item))
        override fun onOpenArtist(item: MediaArtist) = push(Screen.Artist(item))
    }
}

/**
 * Session back stack, restored across process death by `rememberNavBackStack`.
 * Call this inside AppRoot's `key(sessionGeneration)` so a profile switch
 * starts a fresh stack instead of restoring the previous profile's screens.
 */
@Composable
internal fun rememberNavState(): NavState {
    val backStack = rememberNavBackStack(StackEntry(0L, Screen.Home))
    return remember(backStack) { NavState(backStack) }
}
