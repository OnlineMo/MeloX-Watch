package io.github.onlinemo.meloxwatch.ui.fonts

import android.app.Activity
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontRole
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Actual return effect and host observer. Real Main/Window producers remain a separate gate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "en-rUS-w192dp-h192dp-mdpi-round")
@LooperMode(LooperMode.Mode.PAUSED)
class ProbeFontPickerReturnHostTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var controller: ActivityController<ComponentActivity>? = null
    private val activity: ComponentActivity get() = checkNotNull(controller).get()

    @Before
    fun createActivityWithinTheComposeRule() {
        compose.runOnUiThread {
            controller = Robolectric.buildActivity(ComponentActivity::class.java).also { it.setup() }
        }
    }

    @After
    fun retireTheActualEffectsBeforeTheRuleFinishes() {
        compose.runOnUiThread {
            try { controller?.close() } finally { controller = null }
        }
    }

    @Test(timeout = 20_000L)
    fun hiddenPickerReturnUsesTheFreshHostAndItsOwnWorkingRecompositionDoesNotCancelImport() {
        val fixture = FontPickerFixture()
        val route = fixture.picker.enterFontsRoute(Any())
        val pageEpoch = mutableStateOf<Any>(Any())
        val shown = mutableStateOf(true)
        val last = AtomicReference<WatchFontHostValue?>()
        val launched = AtomicReference<ProbeFontPickerTicket?>()
        val slot = FontPickerSlot()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.files.onImport = {
            entered.complete(Unit)
            release.await()
        }
        try {
            compose.runOnUiThread {
                activity.setContent {
                    if (shown.value) {
                        val events = rememberCoroutineScope()
                        WatchFontHost(fixture.runtime, fixture.owner, fixture.editor,
                            WatchFontRequest.Editor(fixture.text), pageEpoch.value,
                            visibleNow = { shown.value }, install = slot::install) { host ->
                            SideEffect { last.set(host) }
                            ProbeFontPickerReturnHost(fixture.picker, route, pageEpoch.value,
                                host, visible = true, visibleNow = { shown.value })
                            BasicText("Choose", Modifier.testTag("picker_choose").clickable {
                                events.launch {
                                    host.edit { it.begin(LyricsFontRole.ORIGINAL, fixture.text) }
                                    fixture.picker.acknowledgeLicense(route, host, true)
                                    fixture.picker.launchFontPicker(route, host) { ticket, _ -> launched.set(ticket) }
                                }
                            })
                        }
                    }
                }
            }
            compose.waitUntil(5_000L) { last.get()?.editorState?.saved != null && last.get()?.loading == false }
            val saved = fixture.store.actual
            compose.onNodeWithTag("picker_choose").performClick()
            compose.waitUntil(5_000L) { launched.get() != null }
            val ticket = checkNotNull(launched.get())
            val oldHost = checkNotNull(last.get())
            val oldSlot = checkNotNull(slot.current)
            compose.runOnIdle {
                shown.value = false
                oldSlot.close()
            }
            compose.waitForIdle()
            assertNull(oldHost.state.original.font.typeface)
            assertSame(ticket, fixture.picker.awaitingPlatformTicket())
            compose.runOnIdle {
                fixture.picker.onPickerResult(ticket, Activity.RESULT_OK, fixture.uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            assertSame(ticket, fixture.picker.state.value.pendingReturn)
            assertEquals(0, fixture.nameReads.get())
            assertEquals(0, fixture.files.imports.get())
            compose.runOnIdle {
                shown.value = true
                pageEpoch.value = Any()
            }
            compose.waitUntil(5_000L) { entered.isCompleted && last.get()?.editorState?.working == true }
            compose.waitForIdle()
            assertNotSame(oldHost, last.get())
            assertSame(ticket, fixture.picker.state.value.pendingReturn)
            assertTrue(fixture.picker.state.value.consuming)
            // WatchFontHost's actual observer has produced new host values during import.
            // Keying the return effect by host or working would have cancelled this exact child.
            assertTrue(fixture.files.importJobs.single().isActive)
            release.complete(Unit)
            compose.waitUntil(5_000L) {
                fixture.picker.state.value.pendingReturn == null &&
                    last.get()?.editorState?.draft?.selection?.asset == fixture.files.prepared.asset
            }
            assertEquals(1, fixture.nameReads.get())
            assertEquals(1, fixture.files.imports.get())
            assertEquals(saved, fixture.store.actual)
            assertEquals(0, fixture.store.writes.get())
            compose.runOnIdle { oldSlot.close() }
            assertEquals(fixture.files.prepared.asset, last.get()?.state?.original?.font?.asset)
        } finally {
            release.complete(Unit)
            compose.runOnIdle {
                fixture.picker.leaveFontsRouteToken(route)
                shown.value = false
                slot.current?.close()
            }
        }
    }

    /** The concrete PageBinding/Window producer is covered by Root's independent integration tests. */
    private class FontPickerSlot {
        var current: Registration? = null
            private set
        fun install(kind: LyricsFontPresentationKind, memory: () -> Unit, cancel: () -> Unit): AutoCloseable {
            check(kind == LyricsFontPresentationKind.EDITOR)
            current?.close()
            return Registration(memory, cancel).also { current = it }
        }
        inner class Registration(
            private val memory: () -> Unit,
            private val cancel: () -> Unit,
        ) : AutoCloseable {
            override fun close() {
                memory()
                if (current === this) current = null
                cancel()
            }
        }
    }
}
