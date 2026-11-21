package io.github.onlinemo.meloxwatch.playback

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoTimeoutException
import io.github.onlinemo.meloxwatch.playback.route.MediaVolumeSetRequest
import io.github.onlinemo.meloxwatch.playback.route.RouteTestFixture
import io.github.onlinemo.meloxwatch.playback.route.ServiceMediaVolume
import io.github.onlinemo.meloxwatch.playback.route.ServiceMediaVolumeIssue
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** 真实转发、路由与释放边界搭配受控 Player；结果不证明解码或实际音频输出。 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class RoutingPlaybackPlayerTest {
    @Test
    fun allSourceReplacementOverloadsRetireBeforeDelegationAndRejectTheOldDraft() {
        val next = MediaItem.Builder().setMediaId("next").build()
        val changes: List<(Player) -> Unit> = listOf(
            { it.setMediaItem(next) },
            { it.setMediaItem(next, 500L) },
            { it.setMediaItem(next, false) },
            { it.setMediaItems(listOf(next)) },
            { it.setMediaItems(listOf(next), false) },
            { it.setMediaItems(listOf(next), 0, 500L) },
            { it.clearMediaItems() },
        )
        for (change in changes) Fixture().use { fixture ->
            val old = checkNotNull(fixture.volume.read().snapshot)
            val oldCallback = fixture.source.callbacks.single()
            fixture.delegate.beforeCommand = {
                assertFalse("source must retire before the raw command", fixture.route.cachedState().known)
                oldCallback()
                assertFalse(fixture.route.cachedState().known)
            }
            change(fixture.player)
            assertEquals(1, fixture.delegate.commands.size)
            val result = fixture.volume.setAbsolute(MediaVolumeSetRequest(old, 8))
            assertEquals(ServiceMediaVolumeIssue.OUTPUT_CHANGED, result.issue)
            assertFalse(result.writeAttempted)
            assertTrue(fixture.port.writes.isEmpty())
        }
    }

    @Test
    fun everySeekEntryPointRetiresBeforeItReachesThePlaybackThread() {
        val seeks: List<(Player) -> Unit> = listOf(
            { it.seekTo(1000L) },
            { it.seekTo(2, 1000L) },
            { it.seekToDefaultPosition() },
            { it.seekToDefaultPosition(2) },
            { it.seekBack() },
            { it.seekForward() },
            { it.seekToPreviousMediaItem() },
            { it.seekToPrevious() },
            { it.seekToNextMediaItem() },
            { it.seekToNext() },
        )
        for (seek in seeks) Fixture().use { fixture ->
            val old = checkNotNull(fixture.volume.read().snapshot)
            fixture.delegate.beforeCommand = { assertFalse(fixture.route.cachedState().known) }
            seek(fixture.player)
            assertEquals(1, fixture.delegate.commands.size)
            assertEquals(
                ServiceMediaVolumeIssue.OUTPUT_CHANGED,
                fixture.volume.setAbsolute(MediaVolumeSetRequest(old, 8)).issue,
            )
            assertTrue(fixture.port.writes.isEmpty())
        }
    }

    @Test
    fun aFailedPauseAndLateSinkPlayCannotReviveTheServiceGate() = Fixture().use { fixture ->
        val old = checkNotNull(fixture.volume.read().snapshot)
        fixture.delegate.beforeCommand = {
            assertFalse(fixture.route.cachedState().known)
            throw IllegalStateException("Controlled pause failure")
        }
        expectFailure { fixture.player.pause() }
        fixture.route.setRenderingActive(true)
        fixture.source.callbacks.last().invoke()
        assertFalse(fixture.route.read().known)
        val result = fixture.volume.setAbsolute(MediaVolumeSetRequest(old, 9))
        assertEquals(ServiceMediaVolumeIssue.OUTPUT_CHANGED, result.issue)
        assertFalse(result.writeAttempted)
        assertTrue(fixture.port.writes.isEmpty())
    }

    @Test
    fun setPlayWhenReadyFalseAlsoClosesTheServiceGateBeforeDelegation() = Fixture().use { fixture ->
        fixture.delegate.beforeCommand = { assertFalse(fixture.route.cachedState().known) }
        fixture.player.playWhenReady = false
        fixture.route.setRenderingActive(true)
        assertFalse(fixture.route.read().known)
        assertEquals(listOf("setPlayWhenReady"), fixture.delegate.commands)
    }

    @Test
    fun stopDropsTheOldObservationEvenWhenTheRawStopFails() = Fixture().use { fixture ->
        val oldCallback = fixture.source.callbacks.single()
        fixture.delegate.beforeCommand = {
            assertFalse(fixture.route.cachedState().known)
            throw IllegalStateException("Controlled stop failure")
        }
        expectFailure { fixture.player.stop() }
        fixture.route.setPlaybackActive(true)
        fixture.route.setRenderingActive(true)
        oldCallback()
        assertFalse(fixture.route.read().known)
        assertEquals(1, fixture.source.removals)
    }

    @Test
    fun adjacentWindowEditsPreserveThePhysicalSourceAndItsVolumeOwner() {
        val adjacent = MediaItem.Builder().setMediaId("adjacent").build()
        val edits: List<(Player) -> Unit> = listOf(
            { it.removeMediaItem(0) },
            { it.removeMediaItems(2, 3) },
            { it.replaceMediaItem(0, adjacent) },
            { it.replaceMediaItems(2, 3, listOf(adjacent)) },
            { it.addMediaItem(0, adjacent) },
            { it.addMediaItems(0, listOf(adjacent)) },
            { it.moveMediaItem(1, 0) },
            { it.moveMediaItems(1, 2, 0) },
        )
        for (edit in edits) Fixture().use { fixture ->
            val before = checkNotNull(fixture.volume.read().snapshot)
            edit(fixture.player)
            assertEquals(before.route, fixture.route.read())
            assertEquals(0, fixture.source.removals)
            val result = fixture.volume.setAbsolute(MediaVolumeSetRequest(before, 6))
            assertTrue(result.accepted && result.changed)
            assertEquals(before.route, checkNotNull(result.snapshot).route)
            assertEquals(listOf(6), fixture.port.writes)
        }
    }

    @Test
    fun removingOrReplacingTheCurrentWindowItemRetiresItsOwner() {
        val replacement = MediaItem.Builder().setMediaId("replacement").build()
        val edits: List<(Player) -> Unit> = listOf(
            { it.removeMediaItem(1) },
            { it.removeMediaItems(0, 2) },
            { it.replaceMediaItem(1, replacement) },
            { it.replaceMediaItems(1, 2, listOf(replacement)) },
        )
        for (edit in edits) Fixture().use { fixture ->
            fixture.delegate.beforeCommand = { assertFalse(fixture.route.cachedState().known) }
            edit(fixture.player)
            assertFalse(fixture.route.read().known)
            assertEquals(1, fixture.source.removals)
        }
    }

    @Test
    fun releaseFailureRetiresObservationBeforeTheProcessLeaseIsQuarantined() = Fixture().use { fixture ->
        fixture.delegate.beforeCommand = { name ->
            if (name == "release") {
                assertFalse(fixture.route.cachedState().known)
                throw IllegalStateException("Controlled native release failure")
            }
        }
        val completion = ScheduledPlaybackRelease(fixture.routing.guard, fixture.routing.lease, fixture.player).start()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(completion.isCompleted && completion.isCancelled)
        assertTrue(fixture.routing.guard.hasUnconfirmedRelease())
        fixture.source.callbacks.first().invoke()
        assertFalse(fixture.route.read().known)
        assertEquals(1, fixture.delegate.commands.count { it == "release" })
    }

    @Test
    fun theFacadeDoesNotHideMedia3ReleaseTimeoutEventsFromTheGuard() = Fixture().use { fixture ->
        fixture.delegate.beforeCommand = { name ->
            if (name == "release") {
                fixture.delegate.listeners.toList().forEach { listener ->
                    listener.onPlayerError(
                        PlaybackException(
                            "Controlled release timeout",
                            ExoTimeoutException(ExoTimeoutException.TIMEOUT_OPERATION_RELEASE),
                            PlaybackException.ERROR_CODE_TIMEOUT,
                        ),
                    )
                }
            }
        }
        val completion = ScheduledPlaybackRelease(fixture.routing.guard, fixture.routing.lease, fixture.player).start()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(completion.isCompleted && completion.isCancelled)
        assertTrue(fixture.routing.guard.hasUnconfirmedRelease())
        assertFalse(fixture.route.read().known)
        assertTrue("forwarding listener removal must retain identity", fixture.delegate.listeners.isEmpty())
    }

    private fun expectFailure(action: () -> Unit) {
        try {
            action()
            throw AssertionError("Expected controlled platform failure")
        } catch (_: IllegalStateException) { }
    }

    private class Fixture : AutoCloseable {
        val routing = RouteTestFixture()
        val route = routing.route
        val source = routing.attach()
        val port = RecordingMusicVolume()
        val volume = ServiceMediaVolume(port, route::read)
        val delegate = RecordingPlayer()
        val player = RoutingPlaybackPlayer(delegate.player, route)
        init {
            route.setPlaybackActive(true)
            check(route.read().known)
        }
        override fun close() = route.close()
    }

    private class RecordingPlayer {
        val commands = mutableListOf<String>()
        val listeners = mutableListOf<Player.Listener>()
        var beforeCommand: ((String) -> Unit)? = null
        val player: Player = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "getApplicationLooper" -> Looper.getMainLooper()
                "getCurrentMediaItemIndex" -> 1
                "getMediaItemCount" -> 3
                "addListener" -> {
                    listeners += args[0] as Player.Listener
                    null
                }
                "removeListener" -> {
                    listeners.remove(args[0] as Player.Listener)
                    null
                }
                "equals" -> proxy === args[0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "Controlled forwarding delegate"
                else -> {
                    commands += method.name
                    beforeCommand?.invoke(method.name)
                    null
                }
            }
        } as Player
    }
}
