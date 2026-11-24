package io.github.onlinemo.meloxwatch.ui.lyrics

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import io.github.onlinemo.meloxwatch.R
import io.github.onlinemo.meloxwatch.data.lyrics.LocalLyricsParser
import io.github.onlinemo.meloxwatch.model.ScreenShape
import io.github.onlinemo.meloxwatch.model.TrackKey
import io.github.onlinemo.meloxwatch.ui.watch.WatchLyricsPage
import kotlin.math.hypot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Actual page placement and touch targets; pure geometry does not cover an RTL parent origin. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "en-rUS-w192dp-h192dp-round-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class WatchLyricsPagePlacementTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var controller: ActivityController<ComponentActivity>? = null
    private val hostActivity: ComponentActivity get() = checkNotNull(controller).get()

    @Before
    fun createHostWithinTheComposeRule() {
        compose.runOnUiThread {
            val host = Robolectric.buildActivity(ComponentActivity::class.java)
            controller = host
            host.setup()
            WindowCompat.setDecorFitsSystemWindows(host.get().window, false)
        }
    }

    @After
    fun retireHostBeforeTheComposeRuleFinishes() {
        compose.runOnUiThread {
            try {
                // close follows the actual lifecycle even when setup or an assertion failed.
                controller?.close()
            } finally {
                controller = null
            }
        }
    }

    @Test
    fun ltrRoundPageKeepsTextAndToolbarActionsReachableWithAsymmetricInsets() {
        assertActualPlacement(LayoutDirection.Ltr)
    }

    @Test
    fun rtlRoundPageKeepsTextAndToolbarActionsReachableWithAsymmetricInsets() {
        assertActualPlacement(LayoutDirection.Rtl)
    }

    private fun assertActualPlacement(direction: LayoutDirection) {
        val document = runBlocking { LocalLyricsParser().parse("[00:01]$LYRIC\n[00:02]Next".toByteArray()) }
        val identity = LyricsPageIdentity(TrackKey("local", "placement-track"), "queue", "entry", 1L)
        var captured: WindowSnapshot? = null
        var backCalls = 0
        var settingsCalls = 0
        val previews = ArrayList<LyricsPlayPreview>()
        compose.runOnUiThread {
            hostActivity.setContent {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    val density = LocalDensity.current
                    val window = LocalWindowInfo.current.containerSize
                    val insets = WindowInsets.safeDrawing.union(WindowInsets.ime)
                    val snapshot = with(density) {
                        WindowSnapshot(window.width.toDp().value, window.height.toDp().value,
                            insets.getLeft(this, direction).toDp().value, insets.getTop(this).toDp().value,
                            insets.getRight(this, direction).toDp().value, insets.getBottom(this).toDp().value)
                    }
                    SideEffect { captured = snapshot }
                    MaterialTheme {
                        WatchLyricsPage(identity, LyricsPageContent.Ready(document), 1_000L, 60_000L,
                            ScreenShape.ROUND, visible = true,
                            options = LyricsDisplayOptions(alignment = LyricsAlignment.START), canSeek = true,
                            onPreviewPlayFromLine = { previews += it }, onBack = { backCalls++ },
                            onSettings = { settingsCalls++ }, modifier = Modifier.testTag(PAGE_TAG))
                    }
                }
            }
        }
        compose.runOnUiThread {
            val content = hostActivity.findViewById<ViewGroup>(android.R.id.content)
            check(content.childCount > 0)
            val fixture = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(12, 8, 8, 14))
                .setVisible(WindowInsetsCompat.Type.systemBars(), true)
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE)
                .setVisible(WindowInsetsCompat.Type.ime(), false)
                .build()
            // Fix the outer host input while retaining Compose's own inset listener and layout.
            ViewCompat.setOnApplyWindowInsetsListener(content) { _, _ -> fixture }
            ViewCompat.dispatchApplyWindowInsets(content, fixture)
        }
        compose.waitForIdle()
        val window = compose.runOnIdle { checkNotNull(captured) }
        // Verify the real host/insets before using them as the bounds oracle.
        assertEquals(192f, window.width, TOLERANCE)
        assertEquals(192f, window.height, TOLERANCE)
        assertEquals(12f, window.left, TOLERANCE)
        assertEquals(8f, window.top, TOLERANCE)
        assertEquals(8f, window.right, TOLERANCE)
        assertEquals(14f, window.bottom, TOLERANCE)
        val page = bounds(compose.onNodeWithTag(PAGE_TAG))
        assertEquals(0f, page.left, TOLERANCE)
        assertEquals(0f, page.top, TOLERANCE)
        assertEquals(window.width, page.width, TOLERANCE)
        assertEquals(window.height, page.height, TOLERANCE)
        val viewport = Rect(window.left, window.top, window.width - window.right, window.height - window.bottom)
        val back = compose.onNodeWithContentDescription(hostActivity.getString(R.string.lyrics_back))
        val settings = compose.onNodeWithContentDescription(hostActivity.getString(R.string.lyrics_settings))
        for (action in listOf(back, settings)) {
            action.assertIsDisplayed()
            val target = bounds(action)
            assertInside(viewport, target)
            assertEquals(48f, target.width, TOLERANCE)
            assertEquals(48f, target.height, TOLERANCE)
            assertTrue(hypot((target.center.x - page.center.x).toDouble(),
                (target.center.y - page.center.y).toDouble()) + 24.0 <= page.width / 2.0 - 8.0 + TOLERANCE)
        }
        val line = compose.onNode(hasText(LYRIC) and hasClickAction()).assertIsDisplayed()
        val reading = bounds(line)
        assertTrue(reading.left >= viewport.left - TOLERANCE && reading.right <= viewport.right + TOLERANCE)
        assertTrue(reading.width >= 96f - TOLERANCE)
        assertTrue(reading.center.y in viewport.top..viewport.bottom)
        assertTrue(hypot((reading.center.x - page.center.x).toDouble(),
            (reading.center.y - page.center.y).toDouble()) <= page.width / 2.0 - 8.0 + TOLERANCE)

        // Pointer delivery also proves reachability; a semantics-only click could bypass clipping.
        back.performTouchInput { click() }
        settings.performTouchInput { click() }
        line.performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, backCalls)
            assertEquals(1, settingsCalls)
            assertEquals(1, previews.size)
            assertEquals(identity, previews.single().identity)
            assertEquals(document.documentId, previews.single().target.documentId)
            assertEquals(1_000L, previews.single().target.mediaPositionMs)
        }
    }

    private fun bounds(node: SemanticsNodeInteraction): Rect {
        val measured = node.getUnclippedBoundsInRoot()
        return Rect(measured.left.value, measured.top.value, measured.right.value, measured.bottom.value)
    }

    private fun assertInside(viewport: Rect, target: Rect) {
        assertTrue(target.left >= viewport.left - TOLERANCE && target.top >= viewport.top - TOLERANCE)
        assertTrue(target.right <= viewport.right + TOLERANCE && target.bottom <= viewport.bottom + TOLERANCE)
    }

    private data class WindowSnapshot(
        val width: Float,
        val height: Float,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    )

    companion object {
        private const val LYRIC = "أغنية"
        private const val PAGE_TAG = "placement:lyrics"
        private const val TOLERANCE = 0.75f
    }
}
