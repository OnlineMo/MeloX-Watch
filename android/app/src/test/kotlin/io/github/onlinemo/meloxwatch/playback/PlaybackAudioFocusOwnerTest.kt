package io.github.onlinemo.meloxwatch.playback

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAudioManager

/** Focus stamps and service-gate routing only; a shadow never proves watch focus or audio output. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class PlaybackAudioFocusOwnerTest {
    private lateinit var backend: FocusPlayer
    private lateinit var shadow: ShadowAudioManager
    private lateinit var owner: PlaybackAudioFocusOwner
    private var generation = 1L
    private var foregroundAllowed = true
    private var foregroundCalls = 0
    private var checkpoints = 0
    private var resumes = 0
    private val issues = mutableListOf<PlaybackBackendIssue>()

    @Before fun createOwner() {
        backend = FocusPlayer()
        // manifest=NONE omits core's merged signature permission used by API26 registration.
        val application: Application = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(application).grantPermissions(application.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val manager = checkNotNull(context().getSystemService(AudioManager::class.java))
        shadow = Shadows.shadowOf(manager)
        shadow.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        owner = PlaybackAudioFocusOwner(context(),
            player = { backend.player },
            backendGeneration = { generation },
            ensureForeground = { foregroundCalls += 1; foregroundAllowed },
            pauseQueue = { backend.player.pause(); checkpoints += 1 },
            checkpoint = { checkpoints += 1 },
            resumeQueue = { resumes += 1; owner.requestPlay() },
            onFailure = issues::add,
        )
    }

    @After fun closeOwner() { owner.close() }

    @Test fun transientLossResumesOnlyThroughForegroundGateWithTheSameGrantedRequest() {
        assertTrue(owner.requestPlay())
        val request = shadow.lastAudioFocusRequest
        request.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertFalse(backend.playing)
        assertEquals(1, checkpoints)
        request.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue(backend.playing)
        assertEquals(1, resumes)
        assertEquals(2, foregroundCalls)
        assertSame(request, shadow.lastAudioFocusRequest)
        assertTrue(issues.isEmpty())
    }

    @Test fun aPauseCancelsADelayedGainEvenThoughTheBackendIsAlreadyPaused() {
        shadow.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_DELAYED)
        assertFalse(owner.requestPlay())
        val request = shadow.lastAudioFocusRequest
        assertFalse(backend.playing)
        owner.userPause()
        request.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertFalse(backend.playing)
        assertEquals(0, resumes)
        assertNotNull(shadow.lastAbandonedAudioFocusRequest)
    }

    @Test fun backendReplacementCannotReceiveAnOldDelayedGain() {
        shadow.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_DELAYED)
        assertFalse(owner.requestPlay())
        val request = shadow.lastAudioFocusRequest
        owner.unbindBackend()
        generation += 1L
        backend = FocusPlayer()
        request.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertFalse(backend.playing)
        assertEquals(0, backend.starts)
        assertEquals(0, resumes)
    }

    @Test fun changedMediaIdentityAndExplicitSeekIntentEachRejectStaleResume() {
        assertTrue(owner.requestPlay())
        val old = shadow.lastAudioFocusRequest
        old.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        backend.mediaId = "entry-2"
        old.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertFalse(backend.playing)
        assertEquals(0, resumes)
        assertTrue(owner.requestPlay())
        val current = shadow.lastAudioFocusRequest
        current.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        owner.cancelResumeIntent()
        current.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertFalse(backend.playing)
        assertEquals(0, resumes)
    }

    @Test fun foregroundRefusalStopsExistingPlaybackAndNeverRequestsFocus() {
        backend.playing = true
        foregroundAllowed = false
        assertFalse(owner.requestPlay())
        assertFalse(backend.playing)
        assertEquals(1, checkpoints)
        assertNull(shadow.lastAudioFocusRequest)
        assertEquals(listOf(PlaybackBackendIssue.FOREGROUND_DENIED), issues)
    }

    @Test fun permanentLossAndNoisyRevokeResumeAndHeadsetReconnectDoesNotPlay() {
        assertTrue(owner.requestPlay())
        val old = shadow.lastAudioFocusRequest
        old.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        old.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertFalse(backend.playing)
        assertEquals(0, resumes)
        owner.setManagementEnabled(false)
        assertTrue(owner.requestPlay())
        context().sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertFalse(backend.playing)
        context().sendBroadcast(Intent(AudioManager.ACTION_HEADSET_PLUG).putExtra("state", 1))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertFalse(backend.playing)
        assertEquals(0, resumes)
    }

    private fun context(): Context = RuntimeEnvironment.getApplication()

    private class FocusPlayer {
        var mediaId = "entry-1"
        var playing = false
        var starts = 0
        val player: Player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.singleOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "FocusPlayer"
                else -> {
                    check(Looper.myLooper() == Looper.getMainLooper())
                    when (method.name) {
                        "getCurrentMediaItem" -> MediaItem.Builder().setMediaId(mediaId).setUri("content://fixture/$mediaId").build()
                        "getPlayerError" -> null
                        "getPlayWhenReady" -> playing
                        "play" -> { starts += 1; playing = true; null }
                        "pause" -> { playing = false; null }
                        else -> throw AssertionError("Unexpected focus player method ${method.name}")
                    }
                }
            }
        } as Player
    }
}
