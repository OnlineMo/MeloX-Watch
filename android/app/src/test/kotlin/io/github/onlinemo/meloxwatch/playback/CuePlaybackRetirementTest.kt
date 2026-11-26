package io.github.onlinemo.meloxwatch.playback

import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import io.github.onlinemo.meloxwatch.data.cue.CueByteFixture
import io.github.onlinemo.meloxwatch.model.CueFileBinding
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.TrackKey
import io.github.onlinemo.meloxwatch.playback.resources.CuePcmDataSources
import io.github.onlinemo.meloxwatch.playback.resources.CuePcmInputFactory
import io.github.onlinemo.meloxwatch.playback.resources.CueSeekableInput
import io.github.onlinemo.meloxwatch.playback.resources.MediaTransportException
import io.github.onlinemo.meloxwatch.playback.resources.RuntimePlaybackResources
import io.github.onlinemo.meloxwatch.playback.resources.RuntimePlaybackResource
import io.github.onlinemo.meloxwatch.playback.resources.MediaHttpTarget
import io.github.onlinemo.meloxwatch.playback.resources.PlaybackResolutionBudget
import io.github.onlinemo.meloxwatch.playback.resources.PlaybackResourceStatus
import io.github.onlinemo.meloxwatch.playback.resources.ResourceCompleteness
import io.github.onlinemo.meloxwatch.playback.resources.ResourceMatch
import io.github.onlinemo.meloxwatch.playback.resources.StreamingQuality
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** 可控字节读取与真实定时释放门；不授予Android输出验收信用。 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(ExperimentalCoroutinesApi::class)
class CuePlaybackRetirementTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    @Before fun installMain() { Dispatchers.setMain(dispatcher) }
    @After fun restoreMain() { Dispatchers.resetMain() }

    @Test fun normalNativeReleaseKeepsTheEngineLeaseUntilPhysicalCueRetirementAcknowledges() = runTest(dispatcher) {
        val guard = PlaybackEngineReleaseGuard()
        val lease = guard.acquire()
        val native = NativePlayer()
        val physical = CompletableDeferred<Unit>()
        val release = ScheduledPlaybackRelease(guard, lease, native.player,
            afterNativeRelease = { withTimeout(5000L) { physical.await() } })
        val completion = release.start()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        assertEquals(1, native.releases.get())
        assertFalse(completion.isCompleted)
        assertTrue(guard.isRetained(lease))
        assertThrows(PlaybackReleaseUnconfirmedException::class.java) { guard.acquire() }
        physical.complete(Unit)
        runCurrent()
        assertTrue(completion.isCompleted)
        completion.await()
        assertFalse(guard.blocksCreation())
    }

    @Test fun blockingProviderCancelRunsOffTheCallerAndRetainsThePlatformUntilActualRetirement() {
        val fixture = CueByteFixture()
        val registry = RuntimePlaybackResources()
        val input = ControlledInputs(fixture)
        val sources = CuePcmDataSources(input, registry)
        val resource = registry.registerCue(fixture.entry(2))
        val platform = sources.platform(resource.mediaUri) { }
        val callerReturned = CountDownLatch(1)
        val callerFailure = AtomicReference<Throwable?>()
        val caller = Thread({
            try { platform.close() } catch (error: Throwable) { callerFailure.set(error) }
            finally { callerReturned.countDown() }
        }, "cue-close-caller").apply { isDaemon = true }
        try {
            assertEquals(44, platform.readAt(0L, ByteArray(44), 0, 44))
            input.blockCancel.set(true)
            caller.start()
            assertTrue("Close must enqueue retirement without entering provider cancellation", callerReturned.await(1L, TimeUnit.SECONDS))
            assertEquals(null, callerFailure.get())
            assertTrue(input.cancelEntered.await(1L, TimeUnit.SECONDS))
            assertNotEquals(caller.name, input.cancelThread.get())
            assertFalse(platform.retirement.isCompleted)
            assertEquals(QueuePlaybackIssue.BUSY, assertThrows(MediaTransportException::class.java) {
                sources.platform(resource.mediaUri) { }
            }.issue)
        } finally {
            input.allowCancel.countDown()
            platform.close()
            caller.join(1000L)
            runBlocking {
                withTimeout(5000L) {
                    platform.retirement.await();
                    sources.awaitRetirement()
                }
            }
            resource.close();
            sources.close()
        }
        assertEquals(0, input.openCount.get())
    }

    @Test fun ignoredReadAndCloseQuarantineTheSameLeaseAtFiveSecondsAndALateExitCannotFreeIt() = runTest(dispatcher) {
        val fixture = CueByteFixture()
        val registry = RuntimePlaybackResources()
        val input = ControlledInputs(fixture)
        val sources = CuePcmDataSources(input, registry)
        val resource = registry.registerCue(fixture.entry(2))
        val platform = sources.platform(resource.mediaUri) { }
        val readFailure = AtomicReference<Throwable?>()
        val reader = Thread({
            try { platform.readAt(44L, ByteArray(4), 0, 4) }
            catch (error: Throwable) { readFailure.set(error) }
        }, "cue-noncooperative-reader").apply { isDaemon = true }
        val guard = PlaybackEngineReleaseGuard()
        val lease = guard.acquire()
        val native = NativePlayer()
        val release = ScheduledPlaybackRelease(guard, lease, native.player,
            afterNativeRelease = { withTimeout(5000L) { sources.awaitRetirement() } })
        try {
            assertEquals(44, platform.readAt(0L, ByteArray(44), 0, 44))
            input.blockRead.set(true)
            input.blockClose.set(true)
            reader.start()
            assertTrue(input.readEntered.await(1L, TimeUnit.SECONDS))
            platform.close()
            assertTrue(input.closeEntered.await(1L, TimeUnit.SECONDS))
            val completion = release.start()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            runCurrent()
            assertEquals(1, native.releases.get())
            assertFalse(completion.isCompleted)
            advanceTimeBy(4999L);
            runCurrent()
            assertFalse(completion.isCompleted)
            assertTrue(guard.isRetained(lease))
            advanceTimeBy(2L);
            runCurrent()
            assertTrue(completion.isCompleted)
            assertThrows(TimeoutCancellationException::class.java) { runBlocking { completion.await() } }
            assertTrue(guard.hasUnconfirmedRelease())
            assertThrows(PlaybackReleaseUnconfirmedException::class.java) { guard.acquire() }
            assertFalse(platform.retirement.isCompleted)
        } finally {
            input.allowRead.countDown();
            input.allowClose.countDown();
            input.allowCancel.countDown()
            platform.close()
            reader.join(1000L)
            runBlocking {
                withTimeout(5000L) {
                    platform.retirement.await();
                    sources.awaitRetirement()
                }
            }
            resource.close();
            sources.close()
        }
        assertFalse(reader.isAlive)
        assertTrue(readFailure.get() != null)
        assertEquals(0, input.openCount.get())
        assertTrue(guard.isRetained(lease))
        assertTrue(guard.hasUnconfirmedRelease())
    }

    @Test fun aLateCancellationFailureKeepsItsTokenQuotaAfterTheNativeReadAndWindowHaveClosed() {
        val fixture = CueByteFixture()
        val registry = RuntimePlaybackResources()
        val input = ControlledInputs(fixture)
        val sources = CuePcmDataSources(input, registry)
        val resource = registry.registerCue(fixture.entry(2))
        val source = sources.createDataSource()
        val online = ArrayList<RuntimePlaybackResource>()
        try {
            source.open(DataSpec.Builder().setUri(resource.mediaUri).build())
            input.lateCancelFailure.set(true)
            sources.close()
            assertTrue(input.cancelEntered.await(1L, TimeUnit.SECONDS))
            // 原生worker和窗口先释放；仍等待的取消worker持有最后一个hold。
            source.close()
            resource.close()
            assertEquals(0, input.openCount.get())
            input.allowCancel.countDown()
            assertThrows(MediaTransportException::class.java) {
                runBlocking { withTimeout(5000L) { sources.awaitRetirement() } }
            }
            assertTrue(requireNotNull(resource.cue).failedReadOwners.contains(source))
            fun onlineToken(index: Int): RuntimePlaybackResource = registry.register(
                QueueEntry("online-$index", TrackKey("netease", "1"), index.toLong(), index.toLong(), accountId = "2"),
                MediaHttpTarget.https("https://example.invalid/audio"), PlaybackResolutionBudget(), null,
                PlaybackResourceStatus("netease", ResourceMatch.PROVIDER_REPORTED, ResourceCompleteness.FULL_REPORTED,
                    StreamingQuality.STANDARD, StreamingQuality.STANDARD, 128_000L), accountCurrent = { true })
            repeat(5) { online += onlineToken(it) }
            assertEquals(QueuePlaybackIssue.BUSY, assertThrows(QueuePlaybackException::class.java) { onlineToken(5) }.failure.issue)
        } finally {
            input.allowCancel.countDown();
            input.allowClose.countDown();
            input.allowRead.countDown()
            source.close();
            resource.close();
            online.forEach(RuntimePlaybackResource::close)
            assertThrows(MediaTransportException::class.java) { sources.close() }
        }
    }

    private class NativePlayer {
        val releases = AtomicInteger()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { proxy, method, arguments ->
            when (method.name) {
                "addListener", "removeListener", "pause" -> null
                "release" -> {
                    releases.incrementAndGet();
                    null
                }
                "equals" -> proxy === arguments?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "ControlledNativePlayer"
                else -> throw AssertionError("Unexpected Player method: ${method.name}")
            }
        } as Player
    }

    private class ControlledInputs(private val fixture: CueByteFixture) : CuePcmInputFactory {
        val openCount = AtomicInteger()
        val blockCancel = AtomicBoolean()
        val blockRead = AtomicBoolean()
        val blockClose = AtomicBoolean()
        val lateCancelFailure = AtomicBoolean()
        private val cancellationCalls = AtomicInteger()
        val cancelThread = AtomicReference<String?>()
        val cancelEntered = CountDownLatch(1)
        val readEntered = CountDownLatch(1)
        val closeEntered = CountDownLatch(1)
        val allowCancel = CountDownLatch(1)
        val allowRead = CountDownLatch(1)
        val allowClose = CountDownLatch(1)
        override fun create(binding: CueFileBinding): CueSeekableInput {
            val audio = binding.objectId == "wave"
            val bytes = if (audio) fixture.wave else fixture.cue
            return object : CueSeekableInput {
                private var position = 0
                private val opened = AtomicBoolean()
                override val length = bytes.size.toLong()
                override fun initialize() {
                    check(opened.compareAndSet(false, true));
                    openCount.incrementAndGet()
                }
                override fun seek(position: Long) {
                    require(position in 0L..length);
                    this.position = position.toInt()
                }
                override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
                    if (audio && blockRead.get()) {
                        readEntered.countDown();
                        await(allowRead)
                    }
                    if (position == bytes.size) return -1
                    val next = minOf(count, bytes.size - position)
                    bytes.copyInto(buffer, offset, position, position + next);
                    position += next
                    return next
                }
                override fun cancel() {
                    if (audio && lateCancelFailure.get() && cancellationCalls.incrementAndGet() == 1) {
                        cancelEntered.countDown();
                        await(allowCancel)
                        throw IOException("Controlled late cancellation failure")
                    }
                    if (audio && blockCancel.get()) {
                        cancelThread.set(Thread.currentThread().name);
                        cancelEntered.countDown();
                        await(allowCancel)
                    }
                }
                override fun close() {
                    if (audio && blockClose.get()) {
                        closeEntered.countDown();
                        await(allowClose)
                    }
                    if (opened.compareAndSet(true, false)) openCount.decrementAndGet()
                }
            }
        }
        private fun await(gate: CountDownLatch) {
            if (!gate.await(10L, TimeUnit.SECONDS)) throw AssertionError("Controlled physical owner was not retired")
        }
    }
}
