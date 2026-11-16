package io.github.onlinemo.meloxwatch.ui.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.github.onlinemo.meloxwatch.playback.input.RotaryStep
import io.github.onlinemo.meloxwatch.playback.input.RotaryTarget
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowAudioManager
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30], manifest = Config.NONE, shadows = [SystemMediaVolumeTest.VolumeAudioShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class SystemMediaVolumeTest {
    @Test fun visibleStateReadsTheMediaStreamAndUsesTheMinimumApiOnlyWhereAvailable() = withFixture { fixture ->
        fixture.audioShadow.minimum = 2
        fixture.volume.onVisible()
        assertEquals(3, fixture.volume.state.value.current)
        assertEquals(10, fixture.volume.state.value.maximum)
        assertEquals(if (Build.VERSION.SDK_INT >= 28) 2 else 0, fixture.volume.state.value.minimum)
        assertTrue(fixture.volume.state.value.adjustable)
        if (Build.VERSION.SDK_INT >= 28) assertTrue(fixture.audioShadow.minimumReads.all { it == AudioManager.STREAM_MUSIC })
        else assertTrue(fixture.audioShadow.minimumReads.isEmpty())
        assertTrue(fixture.audioShadow.writes.isEmpty())
        assertEquals(7, fixture.audio.getStreamVolume(AudioManager.STREAM_RING))
    }

    @Test fun draggingOnlyPreviewsAndCancellationNeverWritesTheDraft() = withFixture { fixture ->
        fixture.volume.onVisible()
        val id = requireNotNull(fixture.volume.begin())
        assertTrue(fixture.volume.drag(id, 0.8))
        assertEquals(8, fixture.volume.state.value.preview)
        assertEquals(3, fixture.volume.state.value.current)
        assertEquals(3, fixture.audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertTrue(fixture.audioShadow.writes.isEmpty())
        fixture.volume.cancel(id)
        assertFalse(fixture.volume.busy)
        assertNull(fixture.volume.state.value.preview)
        assertNull(fixture.volume.state.value.gestureId)
        assertFalse(fixture.volume.release(id))
        fixture.audioShadow.setObservedMedia(6)
        fixture.volume.refresh()
        assertEquals(6, fixture.volume.state.value.current)
        assertTrue(fixture.audioShadow.writes.isEmpty())
    }

    @Test fun anExplicitReleaseWritesOnlyMusicAndAcceptsItsActualReadbackOnce() = withFixture { fixture ->
        fixture.volume.onVisible()
        val id = requireNotNull(fixture.volume.begin())
        assertTrue(fixture.volume.drag(id, 0.7))
        assertTrue(fixture.volume.release(id))
        assertFalse(fixture.volume.release(id))
        assertEquals(listOf(VolumeWrite(AudioManager.STREAM_MUSIC, 7, 0)), fixture.audioShadow.writes)
        assertEquals(7, fixture.volume.state.value.current)
        assertEquals(7, fixture.audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertEquals(7, fixture.audio.getStreamVolume(AudioManager.STREAM_RING))
        assertNull(fixture.volume.state.value.issue)
        assertNull(fixture.volume.state.value.preview)
        assertFalse(fixture.volume.busy)
    }

    @Test fun labelledStepsAndAccessibleProgressWorkWithoutAPlaybackOwner() = withFixture { fixture ->
        fixture.volume.onVisible()
        assertTrue(fixture.volume.setProgress(0.5))
        assertTrue(fixture.volume.change(1))
        assertTrue(fixture.volume.change(-1))
        assertEquals(listOf(5, 6, 5), fixture.audioShadow.writes.map { it.index })
        assertTrue(fixture.audioShadow.writes.all { it.stream == AudioManager.STREAM_MUSIC })
        assertEquals(5, fixture.volume.state.value.current)
        assertEquals(7, fixture.audio.getStreamVolume(AudioManager.STREAM_RING))
    }

    @Test fun rejectedOrChangedPlatformReadbackNeverDisplaysTheRequestedValueAsSuccess() = withFixture { fixture ->
        fixture.volume.onVisible()
        fixture.audioShadow.ignoreWrites = true
        assertFalse(fixture.volume.change(1))
        assertEquals(3, fixture.volume.state.value.current)
        assertEquals(MediaVolumeIssue.SET_REJECTED, fixture.volume.state.value.issue)
        fixture.audioShadow.ignoreWrites = false
        fixture.audioShadow.afterWrite = { fixture.audioShadow.setObservedMedia(2) }
        assertFalse(fixture.volume.change(1))
        assertEquals(2, fixture.audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertEquals(2, fixture.volume.state.value.current)
        assertEquals(MediaVolumeIssue.SET_REJECTED, fixture.volume.state.value.issue)
        assertEquals(2, fixture.audioShadow.writes.size)
    }

    @Test fun writeFailurePreservesTheActualValueAndAnExplicitRetryCanSucceed() = withFixture { fixture ->
        fixture.volume.onVisible()
        fixture.audioShadow.writeFailure = SecurityException("volume denied")
        assertFalse(fixture.volume.change(1))
        assertEquals(3, fixture.volume.state.value.current)
        assertEquals(MediaVolumeIssue.SET_REJECTED, fixture.volume.state.value.issue)
        fixture.audioShadow.writeFailure = null
        assertTrue(fixture.volume.change(1))
        assertEquals(4, fixture.volume.state.value.current)
        assertNull(fixture.volume.state.value.issue)
        assertEquals(2, fixture.audioShadow.writes.size)
    }

    @Test fun recoveryFromUnavailableReadsClearsTheBlockingIssueWithoutAWrite() = withFixture { fixture ->
        fixture.audioShadow.readFailure = true
        fixture.volume.onVisible()
        assertEquals(MediaVolumeIssue.UNAVAILABLE, fixture.volume.state.value.issue)
        assertFalse(fixture.volume.state.value.adjustable)
        assertNull(fixture.volume.begin())
        fixture.audioShadow.readFailure = false
        fixture.volume.refresh()
        assertEquals(3, fixture.volume.state.value.current)
        assertNull(fixture.volume.state.value.issue)
        assertTrue(fixture.volume.state.value.adjustable)
        fixture.volume.cancel(requireNotNull(fixture.volume.begin()))
        assertTrue(fixture.audioShadow.writes.isEmpty())
    }

    @Test fun returningFromRemoteOutputRestoresAnAdjustableLocalState() = withFixture { fixture ->
        fixture.volume.onVisible()
        fixture.select(fixture.userRoute(remote = true))
        assertEquals(MediaVolumeIssue.REMOTE_OUTPUT, fixture.volume.state.value.issue)
        assertFalse(fixture.volume.state.value.adjustable)
        assertNull(fixture.volume.begin())
        assertFalse(fixture.volume.change(1))
        fixture.select(fixture.router.defaultRoute)
        assertNull(fixture.volume.state.value.issue)
        assertTrue(fixture.volume.state.value.adjustable)
        fixture.volume.cancel(requireNotNull(fixture.volume.begin()))
        assertTrue(fixture.audioShadow.writes.isEmpty())
    }

    @Test fun releasingFixedVolumeRestoresTheFreshLocalConstraint() = withFixture { fixture ->
        fixture.audioShadow.fixedVolume = true
        fixture.volume.onVisible()
        assertEquals(MediaVolumeIssue.FIXED, fixture.volume.state.value.issue)
        assertFalse(fixture.volume.state.value.adjustable)
        assertNull(fixture.volume.begin())
        assertFalse(fixture.volume.rotary(volumeStep()))
        fixture.audioShadow.fixedVolume = false
        fixture.volume.refresh()
        assertNull(fixture.volume.state.value.issue)
        assertTrue(fixture.volume.state.value.adjustable)
        assertTrue(fixture.audioShadow.writes.isEmpty())
    }

    @Test fun remoteRouteSelectionCancelsAnOldDraftAndKeepsTheSliderDisabled() = withFixture { fixture ->
        fixture.volume.onVisible()
        val id = requireNotNull(fixture.volume.begin())
        assertTrue(fixture.volume.drag(id, 0.8))
        fixture.select(fixture.userRoute(remote = true))
        assertFalse(fixture.volume.busy)
        assertEquals(MediaVolumeIssue.REMOTE_OUTPUT, fixture.volume.state.value.issue)
        assertFalse(fixture.volume.state.value.adjustable)
        assertNull(fixture.volume.state.value.preview)
        assertFalse(fixture.volume.release(id))
        assertFalse(fixture.volume.setProgress(0.8))
        assertTrue(fixture.audioShadow.writes.isEmpty())
    }

    @Test fun localRouteIdentityChangeRetiresTheDraftEvenWhenBothVolumesAreEqual() = withFixture { fixture ->
        fixture.volume.onVisible()
        val originalRoute = fixture.router.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO)
        val id = requireNotNull(fixture.volume.begin())
        assertTrue(fixture.volume.drag(id, 0.8))
        val replacement = fixture.userRoute(remote = false)
        fixture.select(replacement)
        assertNotEquals(originalRoute, fixture.router.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO))
        assertEquals(3, fixture.volume.state.value.current)
        assertEquals(MediaVolumeIssue.OUTPUT_CHANGED, fixture.volume.state.value.issue)
        assertFalse(fixture.volume.release(id))
        assertTrue(fixture.audioShadow.writes.isEmpty())
        assertTrue(fixture.volume.setProgress(0.4))
        assertEquals(listOf(VolumeWrite(AudioManager.STREAM_MUSIC, 4, 0)), fixture.audioShadow.writes)
    }

    @Test fun anInPlaceRouteTypeChangeCannotReuseTheLocalDraftSnapshot() = withFixture { fixture ->
        val route = fixture.userRoute(remote = false)
        fixture.select(route)
        fixture.volume.onVisible()
        val id = requireNotNull(fixture.volume.begin())
        route.setPlaybackType(MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE)
        assertSame(route, fixture.router.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO))
        assertFalse(fixture.volume.drag(id, 0.8))
        assertFalse(fixture.volume.busy)
        assertFalse(fixture.volume.state.value.adjustable)
        assertEquals(MediaVolumeIssue.REMOTE_OUTPUT, fixture.volume.state.value.issue)
        assertFalse(fixture.volume.release(id))
        assertTrue(fixture.audioShadow.writes.isEmpty())
    }

    @Test fun fixedVolumeBecomingActiveDuringTheWriteCannotReportSuccess() = withFixture { fixture ->
        fixture.volume.onVisible()
        fixture.audioShadow.afterWrite = { fixture.audioShadow.fixedVolume = true }
        assertFalse(fixture.volume.change(1))
        assertEquals(4, fixture.volume.state.value.current)
        assertTrue(fixture.volume.state.value.fixed)
        assertFalse(fixture.volume.state.value.adjustable)
        assertEquals(MediaVolumeIssue.FIXED, fixture.volume.state.value.issue)
    }

    @Test fun anInPlaceRemoteTransitionDuringTheWriteCannotReportSuccess() = withFixture { fixture ->
        val route = fixture.userRoute(remote = false)
        fixture.select(route)
        fixture.volume.onVisible()
        fixture.audioShadow.afterWrite = { route.setPlaybackType(MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE) }
        assertFalse(fixture.volume.change(1))
        assertSame(route, fixture.router.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO))
        assertEquals(4, fixture.volume.state.value.current)
        assertFalse(fixture.volume.state.value.adjustable)
        assertEquals(MediaVolumeIssue.REMOTE_OUTPUT, fixture.volume.state.value.issue)
    }

    @Test fun changingTheRouteWithinThePlatformSetCallRejectsItsLateAcknowledgement() = withFixture { fixture ->
        fixture.volume.onVisible()
        val replacement = fixture.userRoute(remote = false)
        fixture.audioShadow.afterWrite = { fixture.select(replacement) }
        assertFalse(fixture.volume.change(1))
        assertSame(replacement, fixture.router.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO))
        assertEquals(4, fixture.volume.state.value.current)
        assertEquals(MediaVolumeIssue.SET_REJECTED, fixture.volume.state.value.issue)
        assertTrue(fixture.volume.state.value.adjustable)
    }

    @Test fun maximumChangingWithinTheWriteCannotReportSuccess() = withFixture { fixture ->
        fixture.volume.onVisible()
        fixture.audioShadow.afterWrite = { fixture.audioShadow.setStreamMaxVolume(20) }
        assertFalse(fixture.volume.change(1))
        assertEquals(4, fixture.volume.state.value.current)
        assertEquals(20, fixture.volume.state.value.maximum)
        assertEquals(MediaVolumeIssue.SET_REJECTED, fixture.volume.state.value.issue)
    }

    @Test
    @Config(sdk = [30])
    fun minimumChangingWithinTheWriteCannotReportSuccess() = withFixture { fixture ->
        fixture.volume.onVisible()
        fixture.audioShadow.afterWrite = { fixture.audioShadow.minimum = 1 }
        assertFalse(fixture.volume.change(1))
        assertEquals(4, fixture.volume.state.value.current)
        assertEquals(1, fixture.volume.state.value.minimum)
        assertEquals(MediaVolumeIssue.SET_REJECTED, fixture.volume.state.value.issue)
    }

    @Test fun remoteTransitionFollowedByAPlatformExceptionKeepsTheRemoteConstraint() = withFixture { fixture ->
        val route = fixture.userRoute(remote = false)
        fixture.select(route)
        fixture.volume.onVisible()
        fixture.audioShadow.afterWrite = {
            route.setPlaybackType(MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE)
            throw SecurityException("remote output transition")
        }
        assertFalse(fixture.volume.change(1))
        assertEquals(4, fixture.volume.state.value.current)
        assertEquals(MediaVolumeIssue.REMOTE_OUTPUT, fixture.volume.state.value.issue)
        assertFalse(fixture.volume.state.value.adjustable)
    }

    @Test fun fixedTransitionFollowedByAPlatformExceptionKeepsTheFixedConstraint() = withFixture { fixture ->
        fixture.volume.onVisible()
        fixture.audioShadow.afterWrite = {
            fixture.audioShadow.fixedVolume = true
            throw SecurityException("fixed output transition")
        }
        assertFalse(fixture.volume.change(1))
        assertEquals(4, fixture.volume.state.value.current)
        assertEquals(MediaVolumeIssue.FIXED, fixture.volume.state.value.issue)
        assertFalse(fixture.volume.state.value.adjustable)
    }

    @Test fun anExceptionWithUnavailableReadbackKeepsTheActualUnavailableState() = withFixture { fixture ->
        fixture.volume.onVisible()
        fixture.audioShadow.afterWrite = {
            fixture.audioShadow.readFailure = true
            throw SecurityException("unavailable readback")
        }
        assertFalse(fixture.volume.change(1))
        assertNull(fixture.volume.state.value.current)
        assertNull(fixture.volume.state.value.maximum)
        assertEquals(MediaVolumeIssue.UNAVAILABLE, fixture.volume.state.value.issue)
        assertFalse(fixture.volume.state.value.adjustable)
    }

    @Test fun outputCallbacksRetireDraftsAndStopUpdatingTheHiddenPage() = withFixture { fixture ->
        fixture.volume.onVisible()
        fixture.volume.onVisible()
        assertEquals(1, fixture.audioShadow.registrations)
        val id = requireNotNull(fixture.volume.begin())
        assertTrue(fixture.volume.drag(id, 0.8))
        val speaker = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).build()
        fixture.audioShadow.addOutputDevice(speaker, true)
        assertFalse(fixture.volume.busy)
        assertFalse(fixture.volume.release(id))
        assertEquals(MediaVolumeIssue.OUTPUT_CHANGED, fixture.volume.state.value.issue)
        assertTrue(fixture.audioShadow.writes.isEmpty())
        fixture.volume.onHidden()
        assertEquals(1, fixture.audioShadow.unregistrations)
        val hiddenState = fixture.volume.state.value
        fixture.audioShadow.setObservedMedia(6)
        val headset = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_WIRED_HEADPHONES).build()
        fixture.audioShadow.addOutputDevice(headset, true)
        assertEquals(hiddenState, fixture.volume.state.value)
        fixture.volume.onVisible()
        assertEquals(2, fixture.audioShadow.registrations)
        assertEquals(6, fixture.volume.state.value.current)
        assertNull(fixture.volume.state.value.preview)
    }

    @Test fun boundaryButtonsPublishTheFreshSystemValueWithoutWriting() = withFixture { fixture ->
        fixture.volume.onVisible()
        fixture.audioShadow.setObservedMedia(0)
        assertFalse(fixture.volume.change(-1))
        assertEquals(0, fixture.volume.state.value.current)
        fixture.audioShadow.setObservedMedia(10)
        assertFalse(fixture.volume.change(1))
        assertEquals(10, fixture.volume.state.value.current)
        assertFalse(fixture.volume.rotary(volumeStep()))
        assertEquals(10, fixture.volume.state.value.current)
        assertFalse(fixture.volume.change(0))
        assertFalse(fixture.volume.change(2))
        fixture.audioShadow.setObservedMedia(5)
        assertFalse(fixture.volume.rotary(volumeStep().copy(target = RotaryTarget.SEEK)))
        assertEquals(5, fixture.volume.state.value.current)
        assertTrue(fixture.audioShadow.writes.isEmpty())
    }

    @Test fun rotarySuccessRequiresAnActualReadbackChange() = withFixture { fixture ->
        fixture.volume.onVisible()
        assertTrue(fixture.volume.rotary(volumeStep()))
        assertEquals(4, fixture.volume.state.value.current)
        fixture.audioShadow.ignoreWrites = true
        assertFalse(fixture.volume.rotary(volumeStep(delta = -1)))
        assertEquals(4, fixture.volume.state.value.current)
        assertEquals(MediaVolumeIssue.SET_REJECTED, fixture.volume.state.value.issue)
        assertEquals(listOf(4, 3), fixture.audioShadow.writes.map { it.index })
    }

    @Test
    @Config(sdk = [30])
    fun rotaryUsesTheActualNonzeroMinimumAndStopsAtThatBoundary() = withFixture { fixture ->
        fixture.audioShadow.minimum = 2
        fixture.volume.onVisible()
        assertTrue(fixture.volume.rotary(volumeStep(delta = -1)))
        assertEquals(2, fixture.volume.state.value.current)
        assertEquals(2, fixture.volume.state.value.minimum)
        assertFalse(fixture.volume.rotary(volumeStep(delta = -1)))
        assertEquals(1, fixture.audioShadow.writes.size)
        assertEquals(2, fixture.audioShadow.writes.single().index)
    }

    @Test fun revokedUseAndHidingCancelUnsubmittedAdjustments() = withFixture { fixture ->
        fixture.volume.onVisible()
        val id = requireNotNull(fixture.volume.begin())
        assertTrue(fixture.volume.drag(id, 0.8))
        fixture.canUse = false
        assertFalse(fixture.volume.release(id))
        assertFalse(fixture.volume.busy)
        assertNull(fixture.volume.begin())
        assertFalse(fixture.volume.change(1))
        assertFalse(fixture.volume.rotary(volumeStep()))
        fixture.canUse = true
        val visibleId = requireNotNull(fixture.volume.begin())
        assertTrue(fixture.volume.drag(visibleId, 0.9))
        fixture.volume.onHidden()
        assertFalse(fixture.volume.release(visibleId))
        assertNull(fixture.volume.state.value.preview)
        assertTrue(fixture.audioShadow.writes.isEmpty())
        assertEquals(3, fixture.audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    @Test fun invalidFractionsRetireDraftsAndUnrelatedGestureIdsCannotCommitThem() = withFixture { fixture ->
        fixture.volume.onVisible()
        for (fraction in listOf(-0.1, 1.1, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            val id = requireNotNull(fixture.volume.begin())
            assertFalse(fixture.volume.drag("unrelated-gesture", 0.8))
            assertTrue(fixture.volume.busy)
            assertFalse(fixture.volume.drag(id, fraction))
            assertFalse(fixture.volume.busy)
            assertFalse(fixture.volume.release(id))
        }
        assertTrue(fixture.audioShadow.writes.isEmpty())
        assertEquals(3, fixture.volume.state.value.current)
    }

    @Test fun previewAndRefreshKeepTheOpaqueOwnerButInvalidationAndOutputChangeReplaceIt() = withFixture { fixture ->
        fixture.volume.onVisible()
        val owner = requireNotNull(fixture.volume.state.value.ownerId)
        val id = requireNotNull(fixture.volume.begin())
        assertTrue(fixture.volume.drag(id, 0.8))
        assertEquals(owner, fixture.volume.state.value.ownerId)
        fixture.volume.refresh()
        assertEquals(owner, fixture.volume.state.value.ownerId)
        fixture.volume.invalidate()
        val invalidatedOwner = requireNotNull(fixture.volume.state.value.ownerId)
        assertNotEquals(owner, invalidatedOwner)
        assertFalse(fixture.volume.release(id))
        fixture.audioShadow.addOutputDevice(AudioDeviceInfoBuilder.newBuilder().build(), true)
        assertNotEquals(invalidatedOwner, fixture.volume.state.value.ownerId)
        val routeOwner = fixture.volume.state.value.ownerId
        fixture.volume.onHidden()
        assertNotEquals(routeOwner, fixture.volume.state.value.ownerId)
        assertTrue(fixture.audioShadow.writes.isEmpty())
    }

    @Test(timeout = 30000L) fun aSynchronousPreviewObserverCanCancelBeforeAnyLateReleaseWrites() = runBlocking {
        val fixture = Fixture()
        fixture.volume.onVisible()
        val observer = launch(context = Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            fixture.volume.state.first { state ->
                if (state.preview == null || state.gestureId == null) false else {
                    fixture.volume.cancel(state.gestureId)
                    true
                }
            }
        }
        try {
            val id = requireNotNull(fixture.volume.begin())
            observer.join()
            assertFalse(fixture.volume.busy)
            assertFalse(fixture.volume.release(id))
            assertNull(fixture.volume.state.value.preview)
            assertTrue(fixture.audioShadow.writes.isEmpty())
            assertEquals(3, fixture.audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        } finally {
            observer.cancelAndJoin()
            fixture.close()
        }
    }

    private class Fixture {
        val context: Context = RuntimeEnvironment.getApplication()
        val audio = requireNotNull(context.getSystemService(AudioManager::class.java))
        val audioShadow = Shadow.extract<VolumeAudioShadow>(audio)
        val router = requireNotNull(context.getSystemService(MediaRouter::class.java))
        var canUse = true
        val volume = SystemMediaVolume(context) { canUse }

        init {
            audioShadow.setStreamMaxVolume(10)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, 3, 0)
            audio.setStreamVolume(AudioManager.STREAM_RING, 7, 0)
            audioShadow.writes.clear()
        }

        fun userRoute(remote: Boolean): MediaRouter.UserRouteInfo {
            val route = router.createUserRoute(router.createRouteCategory("Volume fixture", false))
            route.setName(if (remote) "Fixture remote" else "Fixture local")
            // Public user routes advertise only USER. This provider fixture also advertises
            // LIVE_AUDIO so real platform selection/filtering reaches the production callback.
            ReflectionHelpers.setField(route, "mSupportedTypes", MediaRouter.ROUTE_TYPE_LIVE_AUDIO or MediaRouter.ROUTE_TYPE_USER)
            route.setPlaybackType(if (remote) MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE else MediaRouter.RouteInfo.PLAYBACK_TYPE_LOCAL)
            route.setPlaybackStream(AudioManager.STREAM_MUSIC)
            route.setVolumeHandling(MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE)
            route.setVolumeMax(10)
            route.setVolume(3)
            router.addUserRoute(route)
            return route
        }

        fun select(route: MediaRouter.RouteInfo) {
            router.selectRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO, route)
            shadowOf(Looper.getMainLooper()).idle()
        }

        fun close() = volume.onHidden()
    }

    data class VolumeWrite(val stream: Int, val index: Int, val flags: Int)

    /** Uses the pinned Robolectric stream state while controlling failures and callback timing. */
    @Implements(AudioManager::class)
    class VolumeAudioShadow : ShadowAudioManager() {
        val writes = mutableListOf<VolumeWrite>()
        val minimumReads = mutableListOf<Int>()
        var minimum = 0
        var fixedVolume = false
        var readFailure = false
        var writeFailure: RuntimeException? = null
        var ignoreWrites = false
        var afterWrite: () -> Unit = {}
        var registrations = 0
        var unregistrations = 0

        @Implementation
        protected override fun getStreamVolume(streamType: Int): Int {
            if (readFailure) throw SecurityException("volume unavailable")
            return super.getStreamVolume(streamType)
        }

        @Implementation(minSdk = 28)
        protected fun getStreamMinVolume(streamType: Int): Int {
            minimumReads.add(streamType)
            return minimum
        }

        @Implementation
        protected fun isVolumeFixed(): Boolean = fixedVolume

        @Implementation
        protected override fun setStreamVolume(streamType: Int, index: Int, flags: Int) {
            writes.add(VolumeWrite(streamType, index, flags))
            writeFailure?.let { throw it }
            if (!ignoreWrites) super.setStreamVolume(streamType, index, flags)
            afterWrite()
        }

        @Implementation(minSdk = 23)
        protected override fun registerAudioDeviceCallback(callback: AudioDeviceCallback, handler: Handler?) {
            registrations++
            super.registerAudioDeviceCallback(callback, handler)
        }

        @Implementation(minSdk = 23)
        protected override fun unregisterAudioDeviceCallback(callback: AudioDeviceCallback) {
            unregistrations++
            super.unregisterAudioDeviceCallback(callback)
        }

        fun setObservedMedia(index: Int) = super.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
    }

    private fun <T> withFixture(block: (Fixture) -> T): T {
        val fixture = Fixture()
        return try { block(fixture) } finally { fixture.close() }
    }

    private fun volumeStep(delta: Int = 1) = RotaryStep("volume-focus", 1L, RotaryTarget.MEDIA_VOLUME, delta, 1_000L)
}
