package io.github.onlinemo.meloxwatch.ui.playback

import android.app.Application
import android.os.Bundle
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import io.github.onlinemo.meloxwatch.model.PlaybackControlsPreferences
import io.github.onlinemo.meloxwatch.model.QueueSeekBinding
import io.github.onlinemo.meloxwatch.playback.input.RotaryAppliedStep
import io.github.onlinemo.meloxwatch.playback.input.SeekGestureSurface
import io.github.onlinemo.meloxwatch.playback.route.MediaVolumeCommands
import io.github.onlinemo.meloxwatch.playback.route.MediaVolumeSetRequest
import io.github.onlinemo.meloxwatch.playback.route.MediaVolumeSnapshot
import io.github.onlinemo.meloxwatch.playback.route.MusicVolumePort
import io.github.onlinemo.meloxwatch.playback.route.MusicVolumeValues
import io.github.onlinemo.meloxwatch.playback.route.PlaybackRouteSessionState
import io.github.onlinemo.meloxwatch.playback.route.ServiceMediaVolume
import io.github.onlinemo.meloxwatch.playback.route.ServiceMediaVolumeIssue
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Controlled controller replies exercise production host/client/input code, without an audio device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
@androidx.annotation.OptIn(UnstableApi::class)
class ServiceMediaVolumeHostTest {
    @Test
    fun theThreeVolumeCommandsKeepTheirExactArgumentsAndOriginalFuture() {
        val context = context()
        val host = ServiceMediaVolumeHost { context }
        val connection = RecordingConnection(context.connectionGeneration)
        host.attach(connection)
        val commands = listOf(
            MediaVolumeCommands.read to MediaVolumeCommands.readArguments(),
            MediaVolumeCommands.set to MediaVolumeCommands.setArguments(MediaVolumeSetRequest(snapshot(), 6)),
            MediaVolumeCommands.adjust to MediaVolumeCommands.adjustArguments(-1),
        )
        for ((command, arguments) in commands) {
            val future = host.dispatch(context, command, arguments)
            val request = connection.requests.last()
            assertSame(command, request.command)
            assertSame(arguments, request.arguments)
            assertSame(request.future, future)
        }
        assertNull(host.dispatch(context, SessionCommand("unrelated-command", Bundle.EMPTY), Bundle.EMPTY))
        assertEquals(3, connection.requests.size)
        assertTrue(host.release(context.connectionGeneration))
        assertTrue(connection.requests.all { !it.future.isCancelled })
    }

    @Test
    fun changedConnectionBackendRouteAndUiOwnersCannotEnterTheController() {
        val original = context()
        var actual = original
        val host = ServiceMediaVolumeHost { actual }
        val connection = RecordingConnection(original.connectionGeneration)
        host.attach(connection)
        val replacements = listOf(
            original.copy(connectionGeneration = original.connectionGeneration + 1L),
            original.copy(backendGeneration = 5L),
            original.copy(backendGeneration = null),
            original.copy(route = PlaybackRouteSessionState(OTHER_ROUTE_OWNER, true)),
            original.copy(route = PlaybackRouteSessionState()),
            original.copy(ownerId = "another-page:focus:settings"),
            original.copy(ownerId = null),
            original.copy(eligible = false),
        )
        for (replacement in replacements) {
            actual = replacement
            assertNull(host.dispatch(original, MediaVolumeCommands.read, MediaVolumeCommands.readArguments()))
            assertTrue(connection.requests.isEmpty())
        }
        actual = original
        val future = host.dispatch(original, MediaVolumeCommands.read, MediaVolumeCommands.readArguments())
        assertSame(connection.requests.single().future, future)
        host.release(original.connectionGeneration)
    }

    @Test
    fun aDisconnectOrSynchronousRetirementDuringContextCapturePreventsIpc() {
        for (releaseDuringCapture in listOf(false, true)) {
            val context = context()
            lateinit var host: ServiceMediaVolumeHost
            val connection = RecordingConnection(context.connectionGeneration)
            host = ServiceMediaVolumeHost {
                if (releaseDuringCapture) host.release(context.connectionGeneration) else connection.connected = false
                context
            }
            host.attach(connection)
            assertNull(host.dispatch(context, MediaVolumeCommands.adjust, MediaVolumeCommands.adjustArguments(1)))
            assertTrue(connection.requests.isEmpty())
            if (!releaseDuringCapture) host.release(context.connectionGeneration)
            assertEquals(1, connection.releases)
        }
    }

    @Test
    fun anOldTimeoutCannotReleaseAReplacementControllerConnection() {
        var context = context()
        val host = ServiceMediaVolumeHost { context }
        val original = RecordingConnection(context.connectionGeneration)
        host.attach(original)
        assertTrue(host.release(original.generation))
        context = context.copy(connectionGeneration = original.generation + 1L)
        val replacement = RecordingConnection(context.connectionGeneration)
        host.attach(replacement)
        assertFalse(host.release(original.generation))
        assertEquals(0, replacement.releases)
        val future = host.dispatch(context, MediaVolumeCommands.read, MediaVolumeCommands.readArguments())
        assertSame(replacement.requests.single().future, future)
        host.release(replacement.generation)
    }

    @Test
    fun releaseCallbacksCannotDispatchOrReplaceTheDetachedConnection() {
        val context = context()
        val host = ServiceMediaVolumeHost { context }
        val connection = RecordingConnection(context.connectionGeneration)
        host.attach(connection)
        connection.onRelease = {
            assertFalse(host.canConnect)
            assertFalse(host.release(connection.generation))
            assertNull(host.dispatch(context, MediaVolumeCommands.adjust, MediaVolumeCommands.adjustArguments(1)))
            expectFailure<IllegalStateException> { host.attach(RecordingConnection(context.connectionGeneration + 1L)) }
        }
        assertTrue(host.release(connection.generation))
        assertTrue(host.canConnect)
        assertNull(host.retiringGeneration)
        assertTrue(connection.requests.isEmpty())
    }

    @Test
    fun aFailedReleaseRetainsTheExactConnectionForDeliberateCleanup() {
        val context = context()
        val host = ServiceMediaVolumeHost { context }
        val connection = RecordingConnection(context.connectionGeneration)
        host.attach(connection)
        val failure = IllegalStateException("controlled connection release failure")
        connection.releaseFailure = failure
        assertSame(failure, runCatching { host.release(connection.generation) }.exceptionOrNull())
        assertFalse(host.canConnect)
        assertEquals(connection.generation, host.retiringGeneration)
        assertNull(host.dispatch(context, MediaVolumeCommands.read, MediaVolumeCommands.readArguments()))
        assertFalse(host.release(connection.generation + 1L))
        expectFailure<IllegalStateException> { host.attach(RecordingConnection(connection.generation + 1L)) }
        assertEquals(1, connection.releases)
        connection.releaseFailure = null
        assertTrue(host.release(connection.generation))
        assertEquals(2, connection.releases)
        assertTrue(host.canConnect)
    }

    @Test
    fun backgroundThreadsCannotAttachDispatchOrReleaseTheHostConnection() {
        val context = context()
        val host = ServiceMediaVolumeHost { context }
        val connection = RecordingConnection(context.connectionGeneration)
        host.attach(connection)
        val operations: List<() -> Unit> = listOf(
            { host.attach(RecordingConnection(context.connectionGeneration + 1L)) },
            { host.dispatch(context, MediaVolumeCommands.read, MediaVolumeCommands.readArguments()) },
            { host.release(context.connectionGeneration) },
        )
        for (operation in operations) {
            val failure = AtomicReference<Throwable?>()
            val worker = Thread { failure.set(runCatching(operation).exceptionOrNull()) }
            worker.start()
            worker.join(5_000L)
            assertFalse(worker.isAlive)
            assertTrue(failure.get() is IllegalStateException)
        }
        assertEquals(0, connection.releases)
        assertTrue(connection.requests.isEmpty())
        host.release(connection.generation)
    }

    @Test
    fun aPausedUnobservedOutputCanAdjustWithoutABackendOrQueueOwner() = Fixture(paused = true).use { fixture ->
        assertNull(fixture.mediaContext().backendGeneration)
        assertFalse(fixture.mediaContext().route.known)
        assertNull(fixture.inputContext().serviceOwnerId)
        assertFalse(fixture.client.state.value.absoluteAdjustable)
        assertTrue(fixture.client.state.value.relativeAdjustable)
        assertTrue(fixture.client.change(1))
        val request = fixture.connection.requests.last()
        assertEquals(MediaVolumeCommands.adjust, request.command)
        assertEquals(1, MediaVolumeCommands.readAdjust(request.arguments))
        fixture.reply(request)
        assertEquals(6, fixture.client.state.value.current)
        assertEquals(1, fixture.writes)
        assertTrue(fixture.applied.isEmpty())
    }

    @Test
    fun ownedRotaryVolumeKeepsItsAckEligibleAndBlocksEveryOtherInput() = Fixture().use { fixture ->
        assertTrue(fixture.input.rotary(1_000L, 1.0))
        val request = fixture.connection.requests.last()
        assertEquals(MediaVolumeCommands.adjust, request.command)
        assertTrue(fixture.input.ownsRotaryVolume)
        assertTrue(fixture.input.busy)
        assertTrue(fixture.client.busy)
        assertFalse(fixture.inputContext().commandPending)
        assertTrue(fixture.mediaContext().eligible)
        fixture.input.contextChanged()
        fixture.client.contextChanged()
        assertNull(fixture.input.begin(SeekGestureSurface.MAIN_PROGRESS))
        assertFalse(fixture.client.change(-1))
        fixture.input.rotary(1_200L, 1.0)
        assertEquals(2, fixture.connection.requests.size)
        assertFalse(request.future.isCancelled)
        fixture.reply(request)
        assertFalse(fixture.input.busy)
        assertFalse(fixture.client.busy)
        assertEquals(1, fixture.applied.size)
        assertTrue(fixture.applied.single().changed)
        assertEquals(6, fixture.client.state.value.current)
    }

    @Test
    fun aButtonRequestBlocksRotaryAndBothProgressSurfacesUntilItsReply() = Fixture().use { fixture ->
        assertTrue(fixture.client.change(1))
        val request = fixture.connection.requests.last()
        assertTrue(fixture.inputContext().commandPending)
        assertFalse(fixture.input.ownsRotaryVolume)
        assertFalse(fixture.input.rotary(1_000L, 1.0))
        assertNull(fixture.input.begin(SeekGestureSurface.MAIN_PROGRESS))
        assertNull(fixture.input.begin(SeekGestureSurface.INDEPENDENT_CONTROLS))
        assertFalse(fixture.client.change(-1))
        assertEquals(2, fixture.connection.requests.size)
        fixture.reply(request)
        assertFalse(fixture.inputContext().commandPending)
        assertTrue(fixture.input.rotary(1_200L, -1.0))
        fixture.reply(fixture.connection.requests.last())
        assertEquals(5, fixture.client.state.value.current)
    }

    @Test
    fun anAcquiringProgressGestureBlocksMediaVolumeUntilTheOriginalBindingSettles() = Fixture().use { fixture ->
        fixture.holdBinding = true
        assertTrue(fixture.input.begin(SeekGestureSurface.MAIN_PROGRESS) != null)
        assertTrue(fixture.input.busy)
        assertFalse(fixture.input.ownsRotaryVolume)
        assertFalse(fixture.mediaContext().eligible)
        assertFalse(fixture.client.change(1))
        assertEquals(1, fixture.connection.requests.size)
        fixture.binding.complete(null)
        fixture.idle()
        assertFalse(fixture.input.busy)
        assertTrue(fixture.client.change(1))
        fixture.reply(fixture.connection.requests.last())
        assertEquals(6, fixture.client.state.value.current)
    }

    @Test
    fun focusSettingsAndListPriorityChangesCannotConfirmTheOldRotaryAck() {
        val retirements: List<(Fixture) -> Unit> = listOf(
            { it.inputFocused = false },
            { it.windowFocused = false },
            { it.controlsKey = "new-settings-revision" },
            { it.listHasPriority = true },
        )
        for (retire in retirements) Fixture().use { fixture ->
            assertTrue(fixture.input.rotary(1_000L, 1.0))
            val original = fixture.connection.requests.last()
            retire(fixture)
            fixture.input.contextChanged()
            fixture.client.contextChanged()
            assertFalse(original.future.isCancelled)
            assertTrue(fixture.input.busy)
            fixture.reply(original)
            assertEquals(1, fixture.writes)
            assertTrue(fixture.applied.isEmpty())
            assertFalse(fixture.input.busy)
        }
    }

    @Test
    fun timeoutReleasesOnceAndSamplingWaitsForAnExplicitRetry() = Fixture().use { fixture ->
        assertTrue(fixture.client.change(1))
        val originalConnection = fixture.connection
        val original = originalConnection.requests.last()
        fixture.advanceReplyDeadline()
        assertEquals(listOf(originalConnection.generation), fixture.timeouts)
        assertEquals(1, originalConnection.releases)
        assertTrue(fixture.foreground)
        assertTrue(fixture.windowFocused)
        assertTrue(fixture.client.busy)
        assertFalse(original.future.isCancelled)
        fixture.client.sample()
        fixture.client.contextChanged()
        assertEquals(2, originalConnection.requests.size)
        fixture.attachReplacement()
        fixture.client.contextChanged()
        fixture.client.sample()
        assertTrue(fixture.connection.requests.isEmpty())
        fixture.reply(original)
        assertFalse(fixture.client.busy)
        fixture.client.sample()
        assertTrue(fixture.connection.requests.isEmpty())
        fixture.client.refresh()
        assertEquals(1, fixture.connection.requests.size)
        assertEquals(MediaVolumeCommands.read, fixture.connection.requests.single().command)
        fixture.reply(fixture.connection.requests.single())
        assertEquals(6, fixture.client.state.value.current)
        assertEquals(1, fixture.writes)
        assertEquals(1, originalConnection.releases)
    }

    @Test
    fun aFailedTimeoutReleaseCannotReplaceTheConnectionOrDropItsOriginalReply() = Fixture().use { fixture ->
        assertTrue(fixture.client.change(1))
        val originalConnection = fixture.connection
        val original = originalConnection.requests.last()
        originalConnection.releaseFailure = IllegalStateException("controlled release failure")
        fixture.advanceReplyDeadline()
        assertFalse(fixture.host.canConnect)
        assertEquals(originalConnection.generation, fixture.host.retiringGeneration)
        assertTrue(fixture.client.busy)
        assertFalse(original.future.isCancelled)
        assertEquals(ServiceMediaVolumeIssue.UNAVAILABLE, fixture.client.state.value.issue)
        fixture.client.sample()
        expectFailure<IllegalStateException> { fixture.attachReplacement() }
        assertEquals(2, originalConnection.requests.size)
        originalConnection.releaseFailure = null
        assertTrue(fixture.host.release(originalConnection.generation))
        fixture.attachReplacement()
        fixture.client.refresh()
        assertTrue(fixture.connection.requests.isEmpty())
        assertTrue(fixture.client.busy)
        fixture.reply(original)
        assertEquals(1, fixture.writes)
        assertEquals(1, fixture.connection.requests.size)
        assertEquals(MediaVolumeCommands.read, fixture.connection.requests.single().command)
        fixture.reply(fixture.connection.requests.single())
        assertEquals(6, fixture.client.state.value.current)
    }

    @Test
    fun theSliderClampDoesNotRewriteAMutedZeroOrUnmuteAnUntouchedDraft() {
        Fixture(initialValues = values(current = 0, minimum = 2, muted = true)).use { fixture ->
            val state = fixture.client.state.value
            assertEquals(0, state.current)
            assertTrue(state.muted)
            assertEquals(0.0, mediaVolumeSliderFraction(state) ?: -1.0, 0.0)
            assertTrue(state.absoluteAdjustable)
            val id = requireNotNull(fixture.client.begin())
            assertEquals(0, fixture.client.state.value.preview)
            assertFalse(fixture.client.release(id))
            assertEquals(0, fixture.writes)
            assertEquals(0, fixture.values.current)
        }
        for (constrained in listOf(values(fixed = true), values(remote = true), values(current = 2, minimum = 2, maximum = 2))) {
            val state = ServiceMediaVolumeUiState(MediaVolumeSnapshot(constrained, route()), ownerId = "volume-page")
            assertEquals(constrained.current, state.current)
            assertFalse(state.relativeAdjustable)
            assertFalse(state.absoluteAdjustable)
            mediaVolumeSliderFraction(state)?.let { assertTrue(it in 0.0..1.0) }
        }
    }

    private data class Request(val command: SessionCommand, val arguments: Bundle, val future: SettableFuture<SessionResult>)

    private class RecordingConnection(override val generation: Long) : ServiceMediaVolumeHost.Connection {
        override val applicationLooper: Looper = Looper.getMainLooper()
        override var connected = true
        val requests = mutableListOf<Request>()
        var releases = 0
        var onRelease: () -> Unit = {}
        var releaseFailure: RuntimeException? = null
        override fun send(command: SessionCommand, arguments: Bundle): ListenableFuture<SessionResult> {
            val future = SettableFuture.create<SessionResult>()
            requests.add(Request(command, arguments, future))
            return future
        }
        override fun release() {
            releases += 1
            connected = false
            onRelease()
            releaseFailure?.let { throw it }
        }
    }

    private class Fixture(paused: Boolean = false, initialValues: MusicVolumeValues = values()) : AutoCloseable {
        var generation = 7L
        var backendGeneration: Long? = if (paused) null else 4L
        var routeState = if (paused) PlaybackRouteSessionState() else route()
        var foreground = true
        var windowFocused = true
        var inputFocused = true
        var controlsKey = "saved-controls:1"
        var listHasPriority = false
        var playbackPending = false
        var holdBinding = false
        val binding = CompletableDeferred<QueueSeekBinding?>()
        var values = initialValues
        var writes = 0
        val applied = mutableListOf<RotaryAppliedStep>()
        val timeouts = mutableListOf<Long>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val host = ServiceMediaVolumeHost(::mediaContext)
        var connection = RecordingConnection(generation)
            private set
        private val connections = mutableListOf(connection)
        private val service = ServiceMediaVolume(object : MusicVolumePort {
            override fun read() = values
            override fun set(index: Int) {
                writes += 1
                values = values.copy(current = index, muted = false)
            }
        }, { routeState })
        val client: ServiceMediaVolumeClient = ServiceMediaVolumeClient(scope, ::mediaContext, host::dispatch) { timedOut ->
            timeouts.add(timedOut)
            if (timedOut == generation) {
                generation += 1L
                connection.connected = false
                input.invalidate()
                clientContextChanged()
                host.release(timedOut)
            }
        }
        lateinit var input: PlaybackPageInput
            private set
        private val controls = PlaybackControlsPreferences(rotaryEnabled = true)

        init {
            input = PlaybackPageInput(scope, ::inputContext,
                acquireBinding = { if (holdBinding) binding.await() else null },
                isBindingCurrent = { false }, dispatchSeek = { false }, applyVolumeStep = client::rotary,
                onRotaryApplied = { step, _ -> applied.add(step) })
            host.attach(connection)
            client.onVisible()
            reply(connection.requests.single())
        }

        fun mediaContext(): MediaVolumeClientContext = MediaVolumeClientContext(generation,
            "volume-page:focus:$controlsKey".takeIf { foreground && windowFocused && inputFocused },
            backendGeneration, routeState,
            mediaVolumeInputAvailable(foreground = foreground, windowFocused = windowFocused,
                inputFocused = inputFocused, pageVisible = true, connected = connection.connected,
                backendSwitching = false, playbackPending = playbackPending,
                inputBusy = ::input.isInitialized && input.busy,
                ownsRotaryVolume = ::input.isInitialized && input.ownsRotaryVolume))

        fun inputContext(): PlaybackPageInputContext = PlaybackPageInputContext(
            serviceOwnerId = "seek-owner".takeIf { connection.connected && backendGeneration != null },
            focusToken = "actual-focus".takeIf { windowFocused && inputFocused },
            foreground = foreground && windowFocused, mainVisible = true, independentVisible = false,
            commandPending = playbackPendingForMediaVolume(playbackPending, client.busy,
                ::input.isInitialized && input.ownsRotaryVolume),
            positionMs = 25_000L, durationMs = if (backendGeneration == null) null else 100_000L,
            controls = controls, controlsStateKey = controlsKey, settingsRevision = 1L,
            listHasPriority = listHasPriority)

        private fun clientContextChanged() = client.contextChanged()

        fun reply(request: Request) {
            val result = when (request.command) {
                MediaVolumeCommands.read -> {
                    MediaVolumeCommands.readRead(request.arguments)
                    service.read()
                }
                MediaVolumeCommands.set -> service.setAbsolute(MediaVolumeCommands.readSet(request.arguments))
                MediaVolumeCommands.adjust -> service.adjust(MediaVolumeCommands.readAdjust(request.arguments))
                else -> error("Unexpected volume protocol command")
            }
            request.future.set(SessionResult(SessionResult.RESULT_SUCCESS, MediaVolumeCommands.resultArguments(result)))
            idle()
        }

        fun attachReplacement() {
            val replacement = RecordingConnection(generation)
            host.attach(replacement)
            connection = replacement
            connections.add(replacement)
        }

        fun idle() = shadowOf(Looper.getMainLooper()).idle()

        fun advanceReplyDeadline() = shadowOf(Looper.getMainLooper()).idleFor(
            Duration.ofMillis(ServiceMediaVolumeClient.REPLY_DEADLINE_MS))

        override fun close() {
            input.close()
            client.close()
            connections.forEach { active ->
                active.releaseFailure = null
                active.requests.forEach { if (!it.future.isDone) it.future.set(SessionResult(SessionError.ERROR_INVALID_STATE)) }
            }
            idle()
            host.retiringGeneration?.let(host::release)
            host.release(connection.generation)
            scope.cancel()
        }
    }

    private inline fun <reified T : Throwable> expectFailure(block: () -> Unit) {
        assertTrue(runCatching(block).exceptionOrNull() is T)
    }

    companion object {
        private const val ROUTE_OWNER = "20f76c62-80c8-4d67-b345-af443236c3ce"
        private const val OTHER_ROUTE_OWNER = "f4b96a0f-93f0-43a0-aa2a-f8319d5c9021"
        private fun route() = PlaybackRouteSessionState(ROUTE_OWNER, true)
        private fun values(current: Int = 5, minimum: Int = 0, maximum: Int = 10,
            fixed: Boolean = false, remote: Boolean = false, muted: Boolean = false) =
            MusicVolumeValues(current, minimum, maximum, fixed, muted, remote)
        private fun snapshot() = MediaVolumeSnapshot(values(), route())
        private fun context() = MediaVolumeClientContext(7L, "volume-page:focus:saved-controls:1", 4L, route(), eligible = true)
    }
}
