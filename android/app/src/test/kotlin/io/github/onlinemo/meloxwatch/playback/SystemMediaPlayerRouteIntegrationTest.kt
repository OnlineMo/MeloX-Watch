package io.github.onlinemo.meloxwatch.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioRouting
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.playback.route.MediaVolumeSetRequest
import io.github.onlinemo.meloxwatch.playback.route.PlaybackAudioRoute
import io.github.onlinemo.meloxwatch.playback.route.ServiceMediaVolume
import io.github.onlinemo.meloxwatch.playback.route.ServiceMediaVolumeIssue
import io.github.onlinemo.meloxwatch.playback.route.SyntheticRoutingDeviceShadow
import io.github.onlinemo.meloxwatch.playback.route.syntheticRoutingDevice
import java.io.File
import java.io.FileDescriptor
import java.io.FileNotFoundException
import java.util.IdentityHashMap
import java.util.concurrent.Executor
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.Resetter

/** 真实适配器经过文件提供方流程；公开 MediaPlayer 回调与文件状态受控，未证明解码或设备输出。 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [30],
    manifest = Config.NONE,
    shadows = [SyntheticRoutingDeviceShadow::class, RouteFixtureOsShadow::class],
)
@LooperMode(LooperMode.Mode.PAUSED)
class SystemMediaPlayerRouteIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun startPauseAndResumeObserveOneNativePlayerAndRetireThePausedOwner() = fixture().use { fixture ->
        fixture.prepare("first")
        fixture.native.beforeStart = { assertFalse(fixture.route.cachedState().known) }
        fixture.adapter.play()
        val first = fixture.route.read()
        assertTrue(first.known)
        assertEquals(1, fixture.native.listeners.size)
        fixture.native.beforePause = { assertFalse(fixture.route.cachedState().known) }
        fixture.adapter.pause()
        assertFalse(fixture.route.read().known)
        val starts = fixture.native.starts
        val relative = fixture.volume.adjust(1)
        assertTrue(relative.accepted && relative.changed)
        assertEquals(5, checkNotNull(relative.snapshot).values.current)
        assertEquals(starts, fixture.native.starts)
        fixture.adapter.play()
        val resumed = fixture.route.read()
        assertTrue(resumed.known)
        assertNotEquals(first.ownerId, resumed.ownerId)
        assertEquals(1, fixture.nativeAllocations)
        assertEquals(1, fixture.native.listeners.size)
    }

    @Test
    fun resetRetiresBeforeTheNativeCallAndOldSourceCallbacksCannotChangeTheNewOwner() = fixture().use { fixture ->
        fixture.prepare("first")
        fixture.adapter.play()
        val first = fixture.route.read()
        val oldCallback = fixture.native.listeners.single().first
        fixture.native.beforeReset = { assertFalse(fixture.route.cachedState().known) }
        fixture.adapter.setMediaItem(fixture.item("second"))
        oldCallback.onRoutingChanged(fixture.native)
        assertFalse(fixture.route.read().known)
        assertTrue(fixture.native.removed.contains(oldCallback))
        fixture.prepareCurrent()
        fixture.adapter.play()
        val second = fixture.route.read()
        assertTrue(second.known)
        assertNotEquals(first.ownerId, second.ownerId)
        assertEquals(1, fixture.native.listeners.size)
        oldCallback.onRoutingChanged(fixture.native)
        assertEquals(second, fixture.route.read())
        assertEquals(1, fixture.nativeAllocations)
        assertEquals(2, fixture.native.dataSources)
    }

    @Test
    fun sourceGenerationLossDuringTheFinalRouteReadRejectsTheAbsoluteWrite() = fixture().use { fixture ->
        fixture.prepare("first")
        fixture.adapter.play()
        val expected = checkNotNull(fixture.volume.read().snapshot)
        fixture.native.onRoutedRead = { fixture.adapter.stop() }
        val result = fixture.volume.setAbsolute(MediaVolumeSetRequest(expected, 9))
        assertEquals(ServiceMediaVolumeIssue.OUTPUT_CHANGED, result.issue)
        assertFalse(result.accepted || result.writeAttempted)
        assertTrue(fixture.port.writes.isEmpty())
        assertFalse(fixture.adapter.isPlaying)
        assertFalse(fixture.route.read().known)
    }

    @Test
    fun failedPlatformStartKeepsTheRouteUnknownAndDoesNotCreateAReplacementPlayer() = fixture().use { fixture ->
        fixture.prepare("first")
        fixture.native.failStart = true
        fixture.adapter.play()
        assertFalse(fixture.route.read().known)
        assertFalse(fixture.adapter.isPlaying)
        assertTrue(fixture.adapter.playerError != null)
        assertEquals(1, fixture.nativeAllocations)
        assertTrue(fixture.native.listeners.isEmpty())
        assertTrue(fixture.port.writes.isEmpty())
    }

    @Test
    fun releaseFailureRetiresBeforeNativeReleaseAndLeavesTheLeaseQuarantined() = fixture().use { fixture ->
        fixture.prepare("first")
        fixture.adapter.play()
        val oldCallback = fixture.native.listeners.single().first
        fixture.native.beforeRelease = { assertFalse(fixture.route.cachedState().known) }
        fixture.native.failRelease = true
        val completion = ScheduledPlaybackRelease(
            fixture.guard,
            fixture.lease,
            fixture.adapter,
            releasePlayer = fixture.adapter::releaseOwnedPlayer,
        ).start()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(completion.isCompleted && completion.isCancelled)
        assertTrue(fixture.guard.hasUnconfirmedRelease())
        oldCallback.onRoutingChanged(fixture.native)
        assertFalse(fixture.route.read().known)
        assertTrue(fixture.native.listeners.isEmpty())
        assertEquals(1, fixture.nativeAllocations)
    }

    @Test
    @Config(sdk = [26])
    fun api26NeverCallsMediaPlayerAudioRoutingAndPausedRelativeVolumeStillWorks() = fixture().use { fixture ->
        fixture.prepare("first")
        fixture.adapter.play()
        assertTrue(fixture.adapter.isPlaying)
        assertFalse(fixture.route.read().known)
        fixture.adapter.pause()
        val starts = fixture.native.starts
        val relative = fixture.volume.adjust(-1)
        assertTrue(relative.accepted && relative.changed)
        assertEquals(3, checkNotNull(relative.snapshot).values.current)
        assertEquals(starts, fixture.native.starts)
        assertFalse(fixture.adapter.isPlaying)
        assertEquals(0, fixture.native.routedReads)
        assertTrue(fixture.native.listeners.isEmpty())
        assertTrue(fixture.native.removed.isEmpty())
    }

    private fun fixture(): Fixture {
        val file = File(temporary.root, "route-input.bin")
        file.writeBytes(ByteArray(32))
        val info = ProviderInfo().apply {
            authority = "route.fixture.audio"
            exported = false
        }
        val provider = Robolectric.buildContentProvider(RouteFixtureAudioProvider::class.java).create(info).get()
        provider.audio = file
        return Fixture()
    }

    private class Fixture : AutoCloseable {
        val guard = PlaybackEngineReleaseGuard()
        val lease = guard.acquire()
        val route = PlaybackAudioRoute(lease, guard, Executor { it.run() })
        val native = ControlledMediaPlayer()
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val port = RecordingMusicVolume()
        val volume = ServiceMediaVolume(port, route::read)
        var nativeAllocations = 0
            private set
        val adapter = SystemMediaPlayerAdapter(
            RuntimeEnvironment.getApplication(),
            LocalProviderIo(dispatcher),
            scope,
            route,
            createPlayer = {
                nativeAllocations++
                native
            },
        )

        init {
            native.outputDevice = syntheticRoutingDevice()
            adapter.initializePlatform()
            adapter.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    route.setPlaybackActive(player.isPlaying && player.playerError == null)
                }
            })
        }

        fun item(id: String): MediaItem = MediaItem.Builder()
            .setMediaId(id)
            .setUri(Uri.parse("content://route.fixture.audio/audio"))
            .build()

        fun prepare(id: String) {
            adapter.setMediaItem(item(id))
            prepareCurrent()
        }

        fun prepareCurrent() {
            adapter.prepare()
            assertEquals(1, dispatcher.pending)
            dispatcher.runNext()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, dispatcher.pending)
            assertEquals(Player.STATE_READY, adapter.playbackState)
            assertFalse(adapter.playWhenReady)
        }

        override fun close() {
            native.failRelease = false
            try {
                adapter.releaseOwnedPlayer()
            } finally {
                scope.cancel()
                if (dispatcher.pending > 0) dispatcher.runNext()
                native.closePlatform()
            }
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val work = ArrayDeque<Runnable>()
        val pending: Int get() = work.size
        override fun dispatch(context: CoroutineContext, block: Runnable) { work.addLast(block) }
        fun runNext() = work.removeFirst().run()
    }

    private class ControlledMediaPlayer : MediaPlayer() {
        private var preparedCallback: MediaPlayer.OnPreparedListener? = null
        private var seekCallback: MediaPlayer.OnSeekCompleteListener? = null
        private var actualParams = PlaybackParams().allowDefaults().setSpeed(1f).setPitch(1f)
        var active = false
        var starts = 0
        var dataSources = 0
        var routedReads = 0
        var failStart = false
        var failRelease = false
        var outputDevice: AudioDeviceInfo? = null
        var beforeStart: (() -> Unit)? = null
        var beforePause: (() -> Unit)? = null
        var beforeReset: (() -> Unit)? = null
        var beforeRelease: (() -> Unit)? = null
        var onRoutedRead: (() -> Unit)? = null
        val listeners = mutableListOf<Pair<AudioRouting.OnRoutingChangedListener, Handler?>>()
        val removed = mutableListOf<AudioRouting.OnRoutingChangedListener>()

        override fun setAudioAttributes(attributes: AudioAttributes) = Unit
        override fun setWakeMode(context: Context, mode: Int) = Unit
        override fun setVolume(leftVolume: Float, rightVolume: Float) = Unit
        override fun setDataSource(fd: FileDescriptor, offset: Long, length: Long) { dataSources++ }
        override fun setOnPreparedListener(listener: MediaPlayer.OnPreparedListener?) { preparedCallback = listener }
        override fun setOnSeekCompleteListener(listener: MediaPlayer.OnSeekCompleteListener?) { seekCallback = listener }
        override fun setOnCompletionListener(listener: MediaPlayer.OnCompletionListener?) = Unit
        override fun setOnInfoListener(listener: MediaPlayer.OnInfoListener?) = Unit
        override fun setOnErrorListener(listener: MediaPlayer.OnErrorListener?) = Unit
        override fun prepareAsync() { preparedCallback?.onPrepared(this) }
        override fun getDuration(): Int = 30_000
        override fun getCurrentPosition(): Int = 0
        override fun setPlaybackParams(params: PlaybackParams) { actualParams = params }
        override fun getPlaybackParams(): PlaybackParams = actualParams
        override fun seekTo(msec: Long, mode: Int) { seekCallback?.onSeekComplete(this) }
        override fun start() {
            beforeStart?.invoke()
            starts++
            if (failStart) throw IllegalStateException("Controlled start failure")
            active = true
        }
        override fun pause() {
            beforePause?.invoke()
            active = false
        }
        override fun reset() {
            beforeReset?.invoke()
            active = false
        }
        override fun isPlaying(): Boolean = active
        override fun getRoutedDevice(): AudioDeviceInfo? {
            routedReads++
            val callback = onRoutedRead
            onRoutedRead = null
            callback?.invoke()
            return outputDevice
        }
        override fun addOnRoutingChangedListener(listener: AudioRouting.OnRoutingChangedListener, handler: Handler?) {
            listeners += listener to handler
        }
        override fun removeOnRoutingChangedListener(listener: AudioRouting.OnRoutingChangedListener) {
            listeners.removeAll { it.first === listener }
            removed += listener
        }
        override fun release() {
            beforeRelease?.invoke()
            if (failRelease) throw IllegalStateException("Controlled release failure")
            active = false
        }
        fun closePlatform() = super.release()
    }
}

/** 只向此套件提供一个实际临时文件，并把每个公开描述符的长度交给受控 Os 边界。 */
class RouteFixtureAudioProvider : ContentProvider() {
    lateinit var audio: File

    override fun onCreate(): Boolean = true
    override fun getType(uri: Uri): String = "audio/mpeg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r" || uri.path != "/audio" || !audio.isFile) {
            throw FileNotFoundException("Route fixture unavailable")
        }
        val descriptor = ParcelFileDescriptor.open(audio, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            RouteFixtureOsShadow.register(descriptor.fileDescriptor, audio.length())
        } catch (error: RuntimeException) {
            descriptor.close()
            throw error
        }
        return descriptor
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
}

/**
 * 仅此套件通过 @Config 启用的文件状态响应；不访问私有 fd 字段，也不跳过真实适配器的资产校验。
 *
 * Robolectric 4.16.1 的 ShadowLinux.fstat(fd) 返回 stat(null)，没有普通文件类型或长度。
 * 这里仅为提供方已注册且仍有效的描述符报告文件长度和位置查询，不能作为设备 I/O 证据。
 */
@Implements(Os::class)
class RouteFixtureOsShadow {
    companion object {
        private val lengths = IdentityHashMap<FileDescriptor, Long>()

        fun register(descriptor: FileDescriptor, lengthBytes: Long) {
            require(descriptor.valid() && lengthBytes > 0L)
            lengths[descriptor] = lengthBytes
        }

        @JvmStatic
        @Resetter
        fun reset() {
            lengths.clear()
        }

        @JvmStatic
        @Implementation
        fun fstat(descriptor: FileDescriptor): StructStat {
            val lengthBytes = registeredLength(descriptor)
            return StructStat(0L, 0L, OsConstants.S_IFREG, 1L, 0, 0, 0L, lengthBytes, 0L, 0L, 0L, 0L, 0L)
        }

        @JvmStatic
        @Implementation
        fun lseek(descriptor: FileDescriptor, offset: Long, whence: Int): Long {
            registeredLength(descriptor)
            if (offset != 0L || whence != OsConstants.SEEK_CUR) throw ErrnoException("lseek", OsConstants.EINVAL)
            return 0L
        }

        private fun registeredLength(descriptor: FileDescriptor): Long {
            if (!descriptor.valid()) throw ErrnoException("fstat", OsConstants.EBADF)
            return lengths[descriptor] ?: throw ErrnoException("fstat", OsConstants.EBADF)
        }
    }
}
