package io.github.onlinemo.meloxwatch.playback.route

import android.content.Context
import android.media.AudioManager
import android.media.MediaRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAudioManager
import org.robolectric.util.ReflectionHelpers

/** Records real public AudioManager calls under Robolectric; no device volume/routing evidence is claimed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30], manifest = Config.NONE, shadows = [RecordingMusicVolumeShadow::class])
class AndroidMusicVolumeTest {
    @Test
    fun writesOnlyTheMusicStreamWithNoAudibleFeedbackFlagAndPreservesOtherStreams() {
        val fixture = Fixture()
        fixture.port.set(6)
        assertEquals(listOf(RecordedMusicVolumeWrite(AudioManager.STREAM_MUSIC, 6, 0)), fixture.shadow.writes)
        assertEquals(6, fixture.audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertEquals(3, fixture.audio.getStreamVolume(AudioManager.STREAM_RING))
        assertEquals(2, fixture.audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION))
        assertEquals(0, fixture.shadow.writes.single().flags and AudioManager.FLAG_PLAY_SOUND)
    }

    @Test
    fun eachReadReturnsTheFreshMusicIndexWithoutQueryingAnUnrelatedStream() {
        val fixture = Fixture()
        val first = fixture.port.read()
        fixture.audio.setStreamVolume(AudioManager.STREAM_MUSIC, 2, 0)
        val second = fixture.port.read()
        assertEquals(4, first.current)
        assertEquals(2, second.current)
        assertEquals(setOf(AudioManager.STREAM_MUSIC), fixture.shadow.readStreams.toSet())
        assertFalse(first.remote)
        assertFalse(first.fixed)
        assertFalse(first.muted)
    }

    @Test
    @Config(sdk = [26])
    fun api26NeverCallsTheLaterMinimumVolumeApi() {
        val fixture = Fixture()
        fixture.shadow.minimumIndex = 7
        assertEquals(0, fixture.port.read().minimum)
        assertEquals(0, fixture.shadow.minimumReads)
    }

    @Test
    @Config(sdk = [30])
    fun newerApiKeepsRealMutedZeroEvenWithAPositiveUnmutedMinimum() {
        val fixture = Fixture()
        fixture.shadow.minimumIndex = 2
        fixture.shadow.setIsStreamMute(AudioManager.STREAM_MUSIC, true)
        fixture.audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        fixture.shadow.resetRecords()
        val values = fixture.port.read()
        assertEquals(0, values.current)
        assertEquals(2, values.minimum)
        assertTrue(values.muted)
        assertEquals(1, fixture.shadow.minimumReads)
        assertEquals(setOf(AudioManager.STREAM_MUSIC), fixture.shadow.readStreams.toSet())
        assertTrue(fixture.shadow.writes.isEmpty())
    }

    @Test
    fun serviceRelativeAdjustmentUsesTheRealAdapterWhileNoTrackIsActive() {
        val fixture = Fixture()
        val service = ServiceMediaVolume(fixture.port) { PlaybackRouteSessionState() }
        val initial = requireNotNull(service.read().snapshot)
        assertFalse(initial.absoluteAdjustable)
        fixture.audio.setStreamVolume(AudioManager.STREAM_MUSIC, 2, 0)
        fixture.shadow.resetRecords()
        val result = service.adjust(1)
        assertTrue(result.accepted)
        assertTrue(result.changed)
        assertEquals(3, result.snapshot?.values?.current)
        assertFalse(requireNotNull(result.snapshot).route.known)
        assertEquals(listOf(RecordedMusicVolumeWrite(AudioManager.STREAM_MUSIC, 3, 0)), fixture.shadow.writes)
    }

    @Test
    fun publicFixedVolumeFlagRemainsAReadbackAndRejectsAServiceWrite() {
        val fixture = Fixture()
        fixture.shadow.fixedOutput = true
        val service = ServiceMediaVolume(fixture.port) { PlaybackRouteSessionState() }
        val initial = service.read()
        assertTrue(requireNotNull(initial.snapshot).values.fixed)
        assertEquals(ServiceMediaVolumeIssue.FIXED, initial.issue)
        assertEquals(ServiceMediaVolumeIssue.FIXED, service.adjust(1).issue)
        assertTrue(fixture.shadow.writes.isEmpty())
    }

    @Test
    fun selectedPublicRemoteRouteOnlyRejectsVolumeControlAndNeverCreatesAnActualPlaybackOwner() {
        val fixture = Fixture()
        val router = requireNotNull(fixture.context.getSystemService(MediaRouter::class.java))
        val output = router.createUserRoute(router.createRouteCategory("Synthetic volume route", false)).apply {
            setName("Synthetic remote output")
            // UserRouteInfo has no public type setter; the real synthetic route must match this adapter's query.
            ReflectionHelpers.setField(this, "mSupportedTypes", supportedTypes or MediaRouter.ROUTE_TYPE_LIVE_AUDIO)
            setPlaybackType(MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE)
        }
        router.addUserRoute(output)
        try {
            router.selectRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO, output)
            assertSame(output, router.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO))
            val service = ServiceMediaVolume(fixture.port) { PlaybackRouteSessionState() }
            val result = service.read()
            assertTrue(requireNotNull(result.snapshot).values.remote)
            assertFalse(requireNotNull(result.snapshot).route.known)
            assertEquals(ServiceMediaVolumeIssue.REMOTE_OUTPUT, result.issue)
            assertEquals(ServiceMediaVolumeIssue.REMOTE_OUTPUT, service.adjust(1).issue)
            assertTrue(fixture.shadow.writes.isEmpty())
        } finally {
            router.selectRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO, router.defaultRoute)
            router.removeUserRoute(output)
        }
    }

    @Test
    fun unreadablePublicMusicVolumeDoesNotWriteAndALaterReadCanRecover() {
        val fixture = Fixture()
        val service = ServiceMediaVolume(fixture.port) { PlaybackRouteSessionState() }
        fixture.shadow.failVolumeRead = true
        val unavailable = service.read()
        assertNull(unavailable.snapshot)
        assertEquals(ServiceMediaVolumeIssue.UNAVAILABLE, unavailable.issue)
        assertEquals(ServiceMediaVolumeIssue.UNAVAILABLE, service.adjust(1).issue)
        assertTrue(fixture.shadow.writes.isEmpty())
        fixture.shadow.failVolumeRead = false
        assertEquals(4, service.read().snapshot?.values?.current)
    }

    private class Fixture {
        val context = RuntimeEnvironment.getApplication() as Context
        val audio = requireNotNull(context.getSystemService(AudioManager::class.java))
        val shadow = Shadow.extract<RecordingMusicVolumeShadow>(audio)
        val port = AndroidMusicVolume(context)

        init {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, 4, 0)
            audio.setStreamVolume(AudioManager.STREAM_RING, 3, 0)
            audio.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 2, 0)
            shadow.resetRecords()
        }
    }
}

/** Synthetic restrictions augment the existing shadow; all stream storage still uses its public API model. */
@Implements(AudioManager::class)
class RecordingMusicVolumeShadow : ShadowAudioManager() {
    var minimumIndex = 0
    var minimumReads = 0
    var fixedOutput = false
    var failVolumeRead = false
    val readStreams = mutableListOf<Int>()
    val writes = mutableListOf<RecordedMusicVolumeWrite>()

    @Implementation
    protected override fun getStreamVolume(streamType: Int): Int {
        readStreams.add(streamType)
        if (failVolumeRead) throw SecurityException("music volume unavailable")
        return super.getStreamVolume(streamType)
    }

    @Implementation
    protected override fun getStreamMaxVolume(streamType: Int): Int {
        readStreams.add(streamType)
        return super.getStreamMaxVolume(streamType)
    }

    @Implementation(minSdk = 28)
    protected fun getStreamMinVolume(streamType: Int): Int {
        readStreams.add(streamType)
        minimumReads++
        return minimumIndex
    }

    @Implementation
    protected fun isVolumeFixed(): Boolean = fixedOutput

    @Implementation
    protected override fun isStreamMute(streamType: Int): Boolean {
        readStreams.add(streamType)
        return super.isStreamMute(streamType)
    }

    @Implementation
    protected override fun setStreamVolume(streamType: Int, index: Int, flags: Int) {
        writes.add(RecordedMusicVolumeWrite(streamType, index, flags))
        super.setStreamVolume(streamType, index, flags)
    }

    fun resetRecords() {
        minimumReads = 0
        readStreams.clear()
        writes.clear()
    }
}

data class RecordedMusicVolumeWrite(val stream: Int, val index: Int, val flags: Int)
