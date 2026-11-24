package io.github.onlinemo.meloxwatch.playback.lyrics

import android.os.Looper
import androidx.media3.common.FlagSet
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import io.github.onlinemo.meloxwatch.model.QueueSeekBinding
import io.github.onlinemo.meloxwatch.model.QueueSeekRequest
import io.github.onlinemo.meloxwatch.ui.lyrics.entry.LyricsMediaClock
import io.github.onlinemo.meloxwatch.ui.lyrics.entry.LyricsPlaybackPort
import java.lang.reflect.Proxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** SDK facade fixtures verify the service boundary, not Android media or Bluetooth acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class ServiceLyricsPlaybackPortTest {
    @Test fun serviceSnapshotReadsTheOnlyPlayerAndLeavesNormalMetadataUnchanged() {
        val fixture = PlayerFixture()
        val port = ServiceLyricsPlaybackPort()
        val binding = port.bindService(fixture.player)
        try {
            fixture.position = 2200L
            fixture.playing = true
            val first = checkNotNull(port.readService())
            assertEquals("entry-fixture-one", first.clock?.queueEntryId)
            assertEquals(2200L, first.clock?.positionMs)
            assertTrue(first.clock?.isPlaying == true)
            assertEquals("Fixture title", first.title)
            assertEquals("Fixture title", fixture.player.mediaMetadata.title)
            fixture.position = 500L
            fixture.playing = false
            fixture.event(Player.EVENT_POSITION_DISCONTINUITY)
            val sought = checkNotNull(port.readService())
            assertEquals(500L, sought.clock?.positionMs)
            assertFalse(sought.clock?.isPlaying == true)
            assertTrue(sought.discontinuity > first.discontinuity)
            fixture.id = "entry-fixture-two"
            fixture.event(Player.EVENT_MEDIA_ITEM_TRANSITION)
            assertEquals("entry-fixture-two", port.readService()?.clock?.queueEntryId)
            assertEquals(0, fixture.releaseCalls)
        } finally { binding.close(); port.close() }
    }

    @Test fun uiClockNotificationsCannotDriveTheExternalServiceEventStream(): Unit = runTest {
        val fixture = PlayerFixture()
        val port = ServiceLyricsPlaybackPort()
        val service = port.bindService(fixture.player)
        val ui = UiPlaybackFixture()
        val uiBinding = port.bindUi(ui, backgroundScope)
        var externalEvents = 0
        val observer = backgroundScope.launch { port.serviceChanges.collect { externalEvents++ } }
        try {
            runCurrent()
            ui.changes.emit(Unit)
            runCurrent()
            assertEquals(0, externalEvents)
            assertEquals(0, ui.clockReads)
            fixture.event(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED)
            runCurrent()
            assertEquals(1, externalEvents)
        } finally { observer.cancel(); uiBinding.close(); service.close(); port.close(); runCurrent() }
    }

    @Test fun closeClearsTheServiceClockEvenWhenListenerRemovalFails() {
        val fixture = PlayerFixture()
        var nativeClosures = 0
        val port = ServiceLyricsPlaybackPort { nativeClosures++ }
        val binding = port.bindService(fixture.player)
        fixture.failRemoval = true
        var failed = false
        try { binding.close() } catch (_: IllegalStateException) { failed = true }
        assertTrue(failed)
        assertNull(port.readService())
        assertEquals(1, nativeClosures)
        assertEquals(0, fixture.releaseCalls)
        port.close()
        assertEquals(1, nativeClosures)
    }

    @Test fun absentMediaAndInvalidSpeedCannotPresentAFalsePlayableClock() {
        val fixture = PlayerFixture()
        val port = ServiceLyricsPlaybackPort()
        val binding = port.bindService(fixture.player)
        try {
            fixture.id = ""
            assertNull(port.readService()?.clock)
            fixture.id = "entry-fixture-one"
            fixture.failParameters = true
            assertNull(port.readService())
        } finally { binding.close(); port.close() }
    }
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
private class PlayerFixture {
    private val listeners = linkedSetOf<Player.Listener>()
    var id = "entry-fixture-one"
    var position = 0L
    var playing = false
    var failRemoval = false
    var failParameters = false
    var releaseCalls = 0
    val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { proxy, method, arguments ->
        when (method.name) {
            "getApplicationLooper" -> Looper.getMainLooper()
            "addListener" -> { listeners += arguments!![0] as Player.Listener; null }
            "removeListener" -> {
                listeners -= arguments!![0] as Player.Listener
                if (failRemoval) throw IllegalStateException("Controlled listener retirement failure")
                null
            }
            "getCurrentMediaItem" -> MediaItem.Builder().setMediaId(id).build()
            "getMediaMetadata" -> MediaMetadata.Builder().setTitle("Fixture title").setArtist("Fixture artist").build()
            "getCurrentPosition" -> position
            "getDuration" -> 5000L
            "getPlaybackState" -> Player.STATE_READY
            "isPlaying" -> playing
            "getPlaybackParameters" -> if (failParameters) throw IllegalStateException("Controlled unavailable parameters") else PlaybackParameters.DEFAULT
            "isCommandAvailable" -> true
            "release" -> { releaseCalls++; null }
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === arguments?.get(0)
            "toString" -> "<controlled-service-player>"
            else -> throw AssertionError("Unexpected operation on the read-only player boundary")
        }
    } as Player
    fun event(flag: Int) {
        val events = Player.Events(FlagSet.Builder().add(flag).build())
        listeners.toList().forEach { it.onEvents(player, events) }
    }
}

private class UiPlaybackFixture : LyricsPlaybackPort {
    override val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val busy = false
    var clockReads = 0
    override fun clock(): LyricsMediaClock? { clockReads++; return null }
    override suspend fun acquireSeekBinding(): QueueSeekBinding? = null
    override fun isSeekBindingCurrent(binding: QueueSeekBinding) = false
    override suspend fun dispatchSeek(request: QueueSeekRequest) = false
}
