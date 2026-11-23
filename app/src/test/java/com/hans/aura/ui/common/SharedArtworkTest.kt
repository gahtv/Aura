package com.hans.aura.ui.common

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.currentCompositeKeyHashCode
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsFocused
import coil.ImageLoader
import coil.intercept.Interceptor
import coil.memory.MemoryCache
import coil.request.ImageRequest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SharedArtworkTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun cornerEndpointsUseTheirCounterpartWithoutShapeAssumptions() {
        val corners = ArtworkCorners()
        val card = Any()
        val header = Any()
        assertEquals(7.dp, corners.otherRadius(card, 7.dp))
        corners.endpoints[card] = 7.dp
        corners.endpoints[header] = 29.dp
        assertEquals(29.dp, corners.otherRadius(card, 7.dp))
        assertEquals(7.dp, corners.otherRadius(header, 29.dp))
        corners.endpoints.remove(card)
        assertEquals(29.dp, corners.otherRadius(header, 29.dp))
    }

    @Test
    fun largerHeaderHasTheCachedCardDuringLoading() {
        val url = "https://fixture/album?maxWidth=480"
        val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        var oldStarted = false
        var headerStarted = false
        lateinit var loader: ImageLoader
        compose.setContent {
            val context = LocalContext.current
            loader = remember {
                ImageLoader.Builder(context)
                    .components { add(Interceptor { error("offline fixture") }) }
                    .build().also { it.memoryCache!![MemoryCache.Key(url)] = MemoryCache.Value(bitmap) }
            }
            val header = artworkRequest(url)
            androidx.compose.runtime.LaunchedEffect(Unit) {
                loader.enqueue(ImageRequest.Builder(context).data(url).size(216)
                    .target(onStart = { assertEquals(null, it) })
                    .listener(onStart = { oldStarted = true })
                    .build())
                loader.enqueue(header.newBuilder().size(216)
                    .listener(onStart = {
                        assertSame(bitmap, (loader.memoryCache!![it.placeholderMemoryCacheKey!!]!!.bitmap))
                        headerStarted = true
                    })
                    .target(onStart = { assertSame(bitmap, (it as BitmapDrawable).bitmap) })
                    .build())
            }
        }
        compose.waitUntil { oldStarted && headerStarted }
        compose.runOnIdle { loader.shutdown() }
    }

    @Test
    fun artworkRequestsReuseThePosterAsPlaceholder() {
        compose.setContent {
            val request = artworkRequest("https://fixture/album?maxWidth=480")
            assertEquals(request.memoryCacheKey, request.placeholderMemoryCacheKey)
            assertEquals(request.data, request.memoryCacheKey?.key)
            val empty = artworkRequest(null)
            assertEquals(null, empty.memoryCacheKey)
            assertEquals(null, empty.placeholderMemoryCacheKey)
        }
    }

    @Test
    fun duplicateAlbumOccurrencesHaveDistinctStableCompositionKeys() {
        val refresh = mutableStateOf(0)
        val tokens = mutableMapOf<String, Long>()
        compose.setContent {
            refresh.value
            for (shelf in listOf("recent", "favorites")) key(shelf) {
                key("album") {
                    val token = currentCompositeKeyHashCode
                    tokens.put(shelf, token)?.let { assertEquals(it, token) }
                }
            }
        }
        compose.runOnIdle {
            assertNotEquals(tokens["recent"], tokens["favorites"])
            refresh.value++
        }
        compose.waitForIdle()
    }

    @Test
    fun missingScopesPreserveModifierIdentity() {
        val modifier = Modifier.size(40.dp)
        compose.setContent {
            assertSame(modifier, modifier.sharedArtwork("album", 7.dp))
            SharedTransitionLayout {
                CompositionLocalProvider(LocalArtworkTransition provides this) {
                    assertSame(modifier, modifier.sharedArtwork("album", 7.dp))
                }
            }
        }
    }

    @Test
    fun matchingAlbumIdsShareArtwork() = checkMatch("album", true)

    @Test
    fun differentAlbumIdsDoNotShareArtwork() = checkMatch("other", false)

    @Test
    fun emptyIdDoesNotMatchAnAlbum() = checkMatch("", false)

    @Test
    fun activeDestinationPreservesNonFocusableContentGroup() {
        compose.setContent {
            val requester = remember { FocusRequester() }
            Box(Modifier.focusRequester(requester).focusGroup()) {
                DestinationFocusGate(true) {
                    Column(Modifier.focusGroup()) {
                        Box(Modifier.size(40.dp).testTag("card").focusable())
                    }
                }
            }
            InitialFocusOnPush(true, requester)
        }
        compose.onNodeWithTag("card").assertIsFocused()
    }

    @Test
    fun outgoingDestinationCannotStealFocusWhenItsDataArrives() {
        val active = mutableStateOf(true)
        val ready = mutableStateOf(false)
        compose.setContent {
            Column {
                CompositionLocalProvider(LocalDestinationActive provides active.value) {
                    val requester = remember { FocusRequester() }
                    DestinationFocusGate(active.value) {
                        Box(Modifier.size(40.dp).testTag("outgoing").focusRequester(requester).focusable())
                    }
                    InitialFocusOnPush(true, requester, ready.value)
                    RestoreFocusOnBack(1, requester, ready = ready.value)
                }
                val incoming = remember { FocusRequester() }
                Box(Modifier.size(40.dp).testTag("incoming").focusRequester(incoming).focusable())
                InitialFocusOnPush(!active.value, incoming)
            }
        }
        compose.runOnIdle { active.value = false }
        compose.waitForIdle()
        compose.onNodeWithTag("incoming").assertIsFocused()
        compose.runOnIdle { ready.value = true }
        compose.waitForIdle()
        compose.onNodeWithTag("incoming").assertIsFocused()
    }

    private fun checkMatch(destinationKey: String, expected: Boolean) {
        compose.mainClock.autoAdvance = false
        val destination = mutableStateOf(false)
        var target: SharedTransitionScope.SharedContentState? = null
        compose.setContent {
            SharedTransitionLayout {
                AnimatedContent(destination.value, label = "test") { detail ->
                    CompositionLocalProvider(
                        LocalArtworkTransition provides this@SharedTransitionLayout,
                        LocalArtworkCorners provides remember { mutableMapOf() },
                        LocalArtworkVisibility provides this@AnimatedContent
                    ) {
                        if (detail) {
                            val state = rememberSharedContentState(destinationKey)
                            target = state
                            Box(Modifier.size(120.dp).sharedElement(state, this@AnimatedContent))
                        } else {
                            Box(Modifier.size(40.dp).sharedArtwork("album", 7.dp))
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { destination.value = true }
        repeat(3) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.runOnIdle {
            if (expected) assertTrue(target?.isMatchFound == true)
            else assertFalse(target?.isMatchFound == true)
        }
    }
}
