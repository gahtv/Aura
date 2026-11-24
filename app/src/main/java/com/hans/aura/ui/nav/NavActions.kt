package com.hans.aura.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.hans.aura.model.Artist as MediaArtist
import com.hans.aura.model.CollectionItem

/**
 * One entry in [NavState]'s Navigation 3 back stack. The monotonic [id] keeps
 * duplicate pushes (e.g. Album A -> Artist X -> Album A) distinct in
 * `NavDisplay`, `SaveableStateHolderNavEntryDecorator` and
 * `ViewModelStoreNavEntryDecorator`.
 */
internal data class StackEntry(
    val id: Long,
    val screen: Screen
)

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
 *  - [pendingFocus] is set when a screen is PUSHED so it claims focus on entry
 *    (TV has no automatic initial focus for new content).
 *  - [focusTick] is bumped on BACK so the revealed screen pulls focus back into
 *    its content.
 */
@Stable
internal class NavState {
    private var nextEntryId = 1L

    val entries: SnapshotStateList<StackEntry> = mutableStateListOf(StackEntry(0L, Screen.Home))

    val stack: List<Screen> get() = entries.map { it.screen }

    val topEntry: StackEntry get() = entries.last()

    val top: Screen get() = topEntry.screen

    var pendingFocus by mutableStateOf(false)
        private set

    var focusTick by mutableStateOf(0)
        private set

    var lastLibraryTab: Screen = Screen.Albums
        private set

    private fun newEntry(screen: Screen) = StackEntry(nextEntryId++, screen)

    private fun replaceStack(vararg screens: Screen) {
        require(screens.isNotEmpty())
        Snapshot.withMutableSnapshot {
            entries.clear()
            screens.forEach { entries.add(newEntry(it)) }
        }
    }

    fun push(screen: Screen) {
        pendingFocus = true
        entries.add(newEntry(screen))
    }

    fun pop() {
        if (entries.size > 1) {
            entries.removeAt(entries.lastIndex)
            focusTick++
            pendingFocus = false
        }
    }

    fun back() {
        if (entries.size > 1) pop() else if (top != Screen.Home) resetToHome()
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
            entries.add(newEntry(Screen.NowPlaying))
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
        if (entries.size == 1 && top == Screen.Home) return
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

@Composable
internal fun rememberNavState(): NavState = remember { NavState() }
