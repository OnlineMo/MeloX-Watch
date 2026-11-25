package io.github.onlinemo.meloxwatch.playback

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.room.Room
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesState
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesWriteResult
import io.github.onlinemo.meloxwatch.data.preferences.ResetConfirmation
import io.github.onlinemo.meloxwatch.data.preferences.UserPreferencesRepository
import io.github.onlinemo.meloxwatch.data.queue.QueueInputPage
import io.github.onlinemo.meloxwatch.data.queue.QueuePageSource
import io.github.onlinemo.meloxwatch.data.queue.QueueReplacementRequest
import io.github.onlinemo.meloxwatch.data.queue.RoomQueueStore
import io.github.onlinemo.meloxwatch.model.FrozenQueueView
import io.github.onlinemo.meloxwatch.model.LocalLocationReference
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.TrackKey
import io.github.onlinemo.meloxwatch.model.UserPreferences
import io.github.onlinemo.meloxwatch.model.UserPreferencesDraft
import io.github.onlinemo.meloxwatch.playback.dsp.DspAudioProcessorChain
import io.github.onlinemo.meloxwatch.playback.dsp.DspAudioSink
import io.github.onlinemo.meloxwatch.playback.dsp.DspRuntime
import io.github.onlinemo.meloxwatch.playback.dsp.DspSettings
import io.github.onlinemo.meloxwatch.playback.dsp.DspStored
import io.github.onlinemo.meloxwatch.playback.resources.metadata.BoundedExtractorsFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowSystemClock

/**
 * 固定 Media3 的真实 WAVE/PCM 与处理链回归。AudioTrack 只记录写入字节和受控播放头，
 * 不连接物理输出；帧完整性、内部时间线及 Room receipt 不能作为设备接缝/首声验收。
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class Media3GaplessPcmTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun realWavePlaybackRefillsTheBoundedQueueWithoutDuplicatingOrLosingSeamFrames() {
        val application = RuntimeEnvironment.getApplication()
        val database = Room.databaseBuilder(application, MeloXDatabase::class.java,
            File(temporaryFolder.root, "gapless-pcm.db").absolutePath).build()
        val store = RoomQueueStore(database, shuffleIndex = { it })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val diagnostics = PcmDiagnostics()
        val provider = RecordingTrackProvider(realtime = true, diagnostics = diagnostics)
        val runtime = DspRuntime()
        val sinkErrors = AtomicInteger()
        val rounds = mutableListOf<String>()
        val failures = mutableListOf<QueuePlaybackFailure>()
        val framesPerTrack = 12_000
        val trackCount = 4
        val pcm = List(trackCount) { waveform(it * framesPerTrack, framesPerTrack) }
        val data = pcm.mapIndexed { index, samples -> "content://fixture/gapless/$index" to wave(samples, SAMPLE_RATE) }.toMap()
        val renderers = object : DefaultRenderersFactory(application) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean): AudioSink = sink(context, provider, runtime, sinkErrors, diagnostics)
        }.setEnableAudioFloatOutput(false).setEnableAudioTrackPlaybackParams(false)
        val player = ExoPlayer.Builder(application).setLooper(Looper.getMainLooper())
            .setUsePlatformDiagnostics(false).setRenderersFactory(renderers)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(5_000, 15_000, 1_000, 2_000)
                .setTargetBufferBytes(4 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false)
                .setBackBuffer(0, false).build())
            .setMediaSourceFactory(DefaultMediaSourceFactory(DataSource.Factory {
                ByteArrayDataSource(ByteArrayDataSource.UriResolver { uri -> checkNotNull(data[uri.toString()]) { "UNKNOWN_WAVE_FIXTURE" } })
            }, BoundedExtractorsFactory()))
            .setReleaseTimeoutMs(1500L).build()
        val actualOperations = Media3PlaybackOperations(player)
        val observedOperations = object : PlaybackBackendOperations by actualOperations {
            override fun setPauseAtBoundary(pause: Boolean) {
                diagnostics.captureMain(player)
                diagnostics.record(PcmEvent.GATE, if (pause) 1 else 0)
                actualOperations.setPauseAtBoundary(pause)
            }
        }
        val coordinator = QueuePlaybackCoordinator(player, store, FixedPreferences(), scope, operations = observedOperations,
            onRoundChanged = { rounds += it.queueEntryId },
            onContinuousRoundChanged = { entry, _ -> rounds += entry.queueEntryId }, onFailure = failures::add)
        var peakWindow = 0
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                diagnostics.captureMain(player)
                peakWindow = maxOf(peakWindow, player.mediaItemCount)
                assertTrue(player.mediaItemCount <= 3)
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                diagnostics.captureMain(player)
                diagnostics.record(PcmEvent.POSITION, reason)
            }
        })
        try {
            await(scope.async {
                coordinator.initialize()
                val view = FrozenQueueView("real-wave", 1L, trackCount.toLong())
                coordinator.replace(QueueReplacementRequest("real-wave", null, 0L, view), QueuePageSource { offset, limit ->
                    QueueInputPage(view, offset, List(minOf(limit.toLong(), view.totalCount - offset).toInt()) {
                        val order = offset + it
                        QueueEntry("entry-$order", TrackKey(TrackKey.LOCAL_PROVIDER, "wave-$order"), order, order,
                            durationMs = framesPerTrack * 1000L / SAMPLE_RATE,
                            location = LocalLocationReference("location-$order", "content://fixture/gapless/$order"))
                    })
                }, explicitPlay = true)
            })
            until {
                coordinator.state.value.snapshot?.currentEntryId == "entry-3" &&
                    !coordinator.state.value.preparing && !player.playWhenReady && player.playbackState == Player.STATE_ENDED
            }
            await(scope.async { coordinator.flush() })
            val played = provider.tracks.filter { it.hasPlayed }
            // 固定类型与数值区分内部 flush 和终流；不输出 URI、元数据或原调用栈。
            println("MELOX_GAPLESS_DIAGNOSTICS " + diagnostics.report(provider.tracks))
            assertEquals(1, played.size)
            val actual = played.single().bytes()
            val expected = join(pcm)
            assertEquals(expected.size, actual.size)
            assertAfterStartupRamp(expected, actual, SAMPLE_RATE)
            assertEquals(List(trackCount) { "entry-$it" }, rounds)
            assertEquals("entry-3", coordinator.state.value.snapshot?.currentEntryId)
            assertEquals(framesPerTrack * 1000L / SAMPLE_RATE, coordinator.state.value.snapshot?.positionMs)
            assertEquals(0, sinkErrors.get())
            assertTrue(failures.isEmpty())
            assertTrue(peakWindow in 2..3)
            assertFalse(player.shuffleModeEnabled)
            assertEquals(Player.REPEAT_MODE_OFF, player.repeatMode)
        } finally {
            coordinator.detach()
            player.release()
            try {
                await(scope.async { coordinator.close() })
            } finally {
                scope.cancel()
                database.close()
                provider.close()
            }
        }
    }

    @Test fun actualSinkTrimsBothAdjacentDelayPaddingPairsBeforeTheFollowingPcmStream() = verifySinkSeams(false)

    @Test fun aLookaheadLimiterDrainsItsRealTailUnderPartialWritesBeforeReusingTheSameTrack() = verifySinkSeams(true)

    @Test fun aSampleRateChangeDrainsTheOldTailBeforeCreatingTheNewOutputTrack() {
        val provider = RecordingTrackProvider(realtime = false, maxWriteFrames = 17, blockedWriteEvery = 5)
        val errors = AtomicInteger()
        val output = sink(RuntimeEnvironment.getApplication(), provider, DspRuntime(), errors)
        val first = waveform(0, 12_007)
        val second = waveform(12_007, 11_009)
        try {
            output.play()
            output.configure(format(SAMPLE_RATE), 4096, null)
            feed(output, first, SAMPLE_RATE, 0L)
            output.configure(format(44_100), 4096, null)
            feed(output, second, 44_100, first.size / FRAME_BYTES * 1_000_000L / SAMPLE_RATE)
            drain(output)
            assertEquals(2, provider.tracks.size)
            assertAfterStartupRamp(first, provider.tracks[0].bytes(), SAMPLE_RATE)
            assertAfterStartupRamp(second, provider.tracks[1].bytes(), 44_100)
            assertEquals(0, errors.get())
        } finally {
            output.reset()
            output.release()
            provider.close()
        }
    }

    private fun verifySinkSeams(limiter: Boolean) {
        val runtime = DspRuntime().apply { setSaved(DspStored(settings = DspSettings(limiterEnabled = limiter))) }
        val provider = RecordingTrackProvider(realtime = false, maxWriteFrames = 17, blockedWriteEvery = 5)
        val errors = AtomicInteger()
        val output = sink(RuntimeEnvironment.getApplication(), provider, runtime, errors)
        val first = waveform(0, 12_007)
        val second = waveform(12_007, 11_009)
        val terminal = waveform(23_016, 2_048)
        try {
            output.play()
            output.configure(format(SAMPLE_RATE, 17, 23), 4096, null)
            feed(output, padded(first, 17, 23), SAMPLE_RATE, 0L)
            output.configure(format(SAMPLE_RATE, 29, 31), 4096, null)
            feed(output, padded(second, 29, 31), SAMPLE_RATE, first.size / FRAME_BYTES * 1_000_000L / SAMPLE_RATE)
            // 固定 trimmer 通过下一份 configure 标识真正的段边界；终项无 padding，不把伪造 EOS 当成边界。
            output.configure(format(SAMPLE_RATE), 4096, null)
            feed(output, terminal, SAMPLE_RATE, (first.size + second.size) / FRAME_BYTES * 1_000_000L / SAMPLE_RATE)
            drain(output)
            assertEquals(1, provider.tracks.size)
            assertEquals(0, provider.tracks.single().flushCalls)
            assertEquals(0, provider.tracks.single().releaseCalls)
            assertAfterStartupRamp(join(listOf(first, second, terminal)), provider.tracks.single().bytes(), SAMPLE_RATE)
            assertEquals(0, errors.get())
            assertEquals(limiter, runtime.state.value.limiterEnabled)
        } finally {
            output.reset()
            output.release()
            provider.close()
        }
    }

    private fun sink(context: Context, provider: RecordingTrackProvider, runtime: DspRuntime,
        errors: AtomicInteger, diagnostics: PcmDiagnostics? = null): AudioSink {
        val chain = DspAudioProcessorChain(runtime)
        val output = DspAudioSink(DefaultAudioSink.Builder(context).setAudioProcessorChain(chain)
            .setEnableFloatOutput(false).setEnableAudioTrackPlaybackParams(false).setAudioTrackProvider(provider).build(), chain).apply {
            setOffloadMode(AudioSink.OFFLOAD_MODE_DISABLED)
            setListener(object : AudioSink.Listener {
                override fun onPositionDiscontinuity() = Unit
                override fun onUnderrun(bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) = Unit
                override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) = Unit
                override fun onAudioSinkError(audioSinkError: Exception) { errors.incrementAndGet() }
            })
        }
        if (diagnostics == null) return output
        return object : ForwardingAudioSink(output) {
            override fun flush() {
                diagnostics.record(PcmEvent.SINK_FLUSH, origin = diagnostics.sinkOriginFlags())
                super.flush()
            }
            override fun reset() {
                diagnostics.record(PcmEvent.SINK_RESET, origin = diagnostics.sinkOriginFlags())
                super.reset()
            }
            override fun playToEndOfStream() {
                diagnostics.record(PcmEvent.SINK_END, origin = diagnostics.sinkOriginFlags())
                super.playToEndOfStream()
            }
        }
    }

    private fun format(rate: Int, delay: Int = 0, padding: Int = 0) = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW).setSampleRate(rate).setChannelCount(2)
        .setPcmEncoding(C.ENCODING_PCM_16BIT).setEncoderDelay(delay).setEncoderPadding(padding).build()

    private fun feed(output: AudioSink, bytes: ByteArray, rate: Int, startUs: Long) {
        val chunks = intArrayOf(1, 31, 1_027, 17, 2_039)
        var offset = 0
        var chunk = 0
        while (offset < bytes.size) {
            val count = minOf(chunks[chunk++ % chunks.size] * FRAME_BYTES, bytes.size - offset)
            val buffer = ByteBuffer.allocateDirect(count).order(ByteOrder.LITTLE_ENDIAN).put(bytes, offset, count).apply { flip() }
            val presentationUs = startUs + offset / FRAME_BYTES * 1_000_000L / rate
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!output.handleBuffer(buffer, presentationUs, 1)) {
                check(System.nanoTime() < deadline) { "PCM_BUFFER_DEADLINE" }
                // PAUSED 的播放头采样需要虚拟时钟前进，墙钟等待不会推进它。
                ShadowSystemClock.advanceBy(1L, TimeUnit.MILLISECONDS)
                Thread.sleep(1L)
            }
            assertEquals(count, buffer.position())
            offset += count
        }
    }

    private fun drain(output: AudioSink) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        do {
            output.playToEndOfStream()
            check(System.nanoTime() < deadline) { "PCM_DRAIN_DEADLINE" }
            if (!output.isEnded) {
                // stop 后的尾帧由同一虚拟时钟估算，保留真实等待及原期限。
                ShadowSystemClock.advanceBy(1L, TimeUnit.MILLISECONDS)
                Thread.sleep(1L)
            }
        } while (!output.isEnded)
    }

    private fun assertAfterStartupRamp(expected: ByteArray, actual: ByteArray, rate: Int) {
        assertEquals(expected.size, actual.size)
        // Media3 1.8.0 只对每份新 AudioTrack 的前 20ms 做启动增益斜坡；相邻边界没有新斜坡。
        val startupBytes = rate * 20 / 1_000 * FRAME_BYTES
        assertArrayEquals(expected.copyOfRange(startupBytes, expected.size), actual.copyOfRange(startupBytes, actual.size))
    }

    private fun waveform(firstFrame: Int, frames: Int): ByteArray = ByteBuffer.allocate(frames * FRAME_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(frames) { offset ->
                val frame = firstFrame + offset
                putShort((6_000 * sin(2 * PI * 997 * frame / SAMPLE_RATE) + frame % 127 - 63).roundToInt().toShort())
                putShort((5_000 * sin(2 * PI * 431 * frame / SAMPLE_RATE) - frame % 113 + 56).roundToInt().toShort())
            }
        }.array()

    private fun padded(samples: ByteArray, delay: Int, padding: Int): ByteArray {
        fun guard(frames: Int) = ByteBuffer.allocate(frames * FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(frames) {
                putShort(20_000.toShort())
                putShort((-20_000).toShort())
            }
        }.array()
        return join(listOf(guard(delay), samples, guard(padding)))
    }

    private fun wave(samples: ByteArray, rate: Int): ByteArray = ByteBuffer.allocate(44 + samples.size)
        .order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + samples.size)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1.toShort())
            putShort(2.toShort())
            putInt(rate)
            putInt(rate * FRAME_BYTES)
            putShort(FRAME_BYTES.toShort())
            putShort(16.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(samples.size)
            put(samples)
        }.array()

    private fun join(values: List<ByteArray>) = ByteArrayOutputStream().apply { values.forEach { write(it) } }.toByteArray()

    private fun <T> await(task: Deferred<T>): T {
        until { task.isCompleted }
        return runBlocking { task.await() }
    }

    private fun until(done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30L)
        while (!done()) {
            check(System.nanoTime() < deadline) { "MEDIA3_PCM_DEADLINE" }
            shadowOf(Looper.getMainLooper()).idleFor(1L, TimeUnit.MILLISECONDS)
            Thread.sleep(1L)
        }
    }

    private class FixedPreferences : UserPreferencesRepository {
        override val state = MutableStateFlow<PreferencesState>(PreferencesState.Ready(UserPreferences()))
        override suspend fun save(expectedRevision: Long, draft: UserPreferencesDraft): PreferencesWriteResult = error("UNEXPECTED_SETTINGS_WRITE")
        override suspend fun reset(expectedRevision: Long, confirmation: ResetConfirmation): PreferencesWriteResult = error("UNEXPECTED_SETTINGS_RESET")
        override fun retryRead() = Unit
    }

    private class RecordingTrackProvider(private val realtime: Boolean, private val maxWriteFrames: Int = 512,
        private val blockedWriteEvery: Int = 0, private val diagnostics: PcmDiagnostics? = null) : DefaultAudioSink.AudioTrackProvider {
        val tracks = CopyOnWriteArrayList<RecordingTrack>()
        override fun getAudioTrack(audioTrackConfig: AudioSink.AudioTrackConfig, audioAttributes: AudioAttributes,
            audioSessionId: Int, context: Context?): AudioTrack = RecordingTrack(audioTrackConfig, realtime, maxWriteFrames,
                blockedWriteEvery, tracks.size, diagnostics).also {
                tracks.add(it)
                diagnostics?.record(PcmEvent.TRACK_CREATE, tracks.lastIndex)
            }
        override fun getAudioTrackChannelConfig(channelCount: Int): Int {
            assertEquals(2, channelCount)
            return AudioFormat.CHANNEL_OUT_STEREO
        }
        fun close() = tracks.forEach(RecordingTrack::release)
    }

    private class RecordingTrack(private val configuration: AudioSink.AudioTrackConfig, private val realtime: Boolean,
        private val maxWriteFrames: Int, private val blockedWriteEvery: Int, private val traceIndex: Int,
        private val diagnostics: PcmDiagnostics?) : AudioTrack(
        android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build(),
        AudioFormat.Builder().setEncoding(configuration.encoding).setSampleRate(configuration.sampleRate)
            .setChannelMask(configuration.channelConfig).build(), configuration.bufferSize,
        AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE,
    ) {
        private val output = ByteArrayOutputStream()
        private var playingState = AudioTrack.PLAYSTATE_STOPPED
        private var consumedFrames = 0L
        private var startedMs = 0L
        private var writes = 0
        private var closed = false
        @Volatile var hasPlayed = false
            private set
        @Volatile var flushCalls = 0
            private set
        @Volatile var releaseCalls = 0
            private set

        @Synchronized override fun getState(): Int = if (closed) AudioTrack.STATE_UNINITIALIZED else AudioTrack.STATE_INITIALIZED
        @Synchronized override fun getPlayState(): Int = playingState
        @Synchronized override fun getPlaybackHeadPosition(): Int {
            val submitted = output.size() / FRAME_BYTES
            return minOf(submitted.toLong(), consumedFrames + if (playingState == AudioTrack.PLAYSTATE_PLAYING) {
                if (realtime) (SystemClock.elapsedRealtime() - startedMs).coerceAtLeast(0L) * configuration.sampleRate / 1_000 else submitted.toLong()
            } else 0L).toInt()
        }
        override fun getTimestamp(timestamp: AudioTimestamp): Boolean = false
        @Synchronized override fun play() {
            consumedFrames = playbackHeadPosition.toLong()
            startedMs = SystemClock.elapsedRealtime()
            playingState = AudioTrack.PLAYSTATE_PLAYING
            hasPlayed = true
            diagnostics?.record(PcmEvent.TRACK_PLAY, traceIndex)
        }
        @Synchronized override fun pause() {
            consumedFrames = playbackHeadPosition.toLong()
            playingState = AudioTrack.PLAYSTATE_PAUSED
        }
        @Synchronized override fun stop() {
            consumedFrames = playbackHeadPosition.toLong()
            playingState = AudioTrack.PLAYSTATE_STOPPED
        }
        @Synchronized override fun flush() {
            flushCalls++
            consumedFrames = 0L
            diagnostics?.record(PcmEvent.TRACK_FLUSH, traceIndex, diagnostics.sinkOriginFlags())
        }
        @Synchronized override fun write(audioData: ByteBuffer, sizeInBytes: Int, writeMode: Int): Int {
            check(!closed)
            if (blockedWriteEvery > 0 && ++writes % blockedWriteEvery == 0) return 0
            val count = minOf(sizeInBytes, audioData.remaining(), maxWriteFrames * FRAME_BYTES)
            assertEquals(0, count % FRAME_BYTES)
            val samples = ByteArray(count)
            audioData.get(samples)
            output.write(samples)
            return count
        }
        @Synchronized fun bytes(): ByteArray = output.toByteArray()
        @Synchronized fun diagnostic(): String = """{"index":$traceIndex,"rate":${configuration.sampleRate},"encoding":${configuration.encoding},"channels":${configuration.channelConfig},"buffer":${configuration.bufferSize},"frames":${output.size() / FRAME_BYTES},"played":$hasPlayed,"flushes":$flushCalls,"releases":$releaseCalls}"""
        @Synchronized override fun release() {
            if (!closed) {
                closed = true
                releaseCalls++
                diagnostics?.record(PcmEvent.TRACK_RELEASE, traceIndex, diagnostics.sinkOriginFlags())
                super.release()
            }
        }
    }

    private enum class PcmEvent {
        GATE, POSITION, TRACK_CREATE, TRACK_PLAY, TRACK_FLUSH, TRACK_RELEASE, SINK_FLUSH, SINK_RESET, SINK_END,
    }

    private data class MainObservation(val ordinal: Int = -1, val positionMs: Long = -1L,
        val state: Int = -1, val playWhenReady: Int = -1)

    private data class PcmRecord(val event: PcmEvent, val clockMs: Long, val main: MainObservation,
        val value: Int, val origin: Int)

    /** 只保存最近64条固定字段；播放线程不访问 Player，Main快照允许有已知采样延迟。 */
    private class PcmDiagnostics {
        @Volatile private var main = MainObservation()
        private val events = ArrayDeque<PcmRecord>()
        private var dropped = 0L

        fun captureMain(player: Player) {
            val id = player.currentMediaItem?.mediaId
            val ordinal = if (id?.startsWith("entry-") == true) id.substring(6).toIntOrNull()?.takeIf { it in 0..3 } ?: -1 else -1
            main = MainObservation(ordinal, player.currentPosition, player.playbackState, if (player.playWhenReady) 1 else 0)
        }

        @Synchronized fun record(event: PcmEvent, value: Int = 0, origin: Int = 0) {
            val observation = main
            val previous = events.peekLast()
            // EOS可能反复排尾；同一已观察曲目与来源只留一次，保留开门/flush事件的预算。
            if (event == PcmEvent.SINK_END && previous?.event == event && previous.main.ordinal == observation.ordinal &&
                previous.value == value && previous.origin == origin) return
            if (events.size == MAX_DIAGNOSTIC_EVENTS) {
                events.removeFirst()
                dropped++
            }
            events.addLast(PcmRecord(event, SystemClock.elapsedRealtime(), observation, value, origin))
        }

        fun sinkOriginFlags(): Int {
            var flags = 0
            // 只匹配固定Media3方法，原Frame、文件名、行号和调用栈均不保留或输出。
            for (frame in Thread.currentThread().stackTrace) {
                if (frame.className == "androidx.media3.exoplayer.audio.MediaCodecAudioRenderer") {
                    flags = flags or when (frame.methodName) {
                        "onPositionReset" -> 1
                        "onDisabled" -> 2
                        "onReset" -> 4
                        "renderToEndOfStream" -> 8
                        else -> 0
                    }
                } else if (frame.className == "androidx.media3.exoplayer.ExoPlayerImplInternal") {
                    flags = flags or when (frame.methodName) {
                        "seekToCurrentPosition" -> 16
                        "setPauseAtEndOfWindowInternal" -> 32
                        else -> 0
                    }
                }
            }
            return flags
        }

        fun report(tracks: List<RecordingTrack>): String {
            val captured = synchronized(this) { events.toList() to dropped }
            val rows = captured.first.joinToString(",") {
                "[\"${it.event.name}\",${it.clockMs},${it.main.ordinal},${it.main.positionMs},${it.main.state},${it.main.playWhenReady},${it.value},${it.origin}]"
            }
            // Track锁与ledger锁不嵌套；输出只含配置数值、帧数和实际生命周期计数。
            val details = tracks.take(MAX_DIAGNOSTIC_TRACKS).joinToString(",") { it.diagnostic() }
            return """{"schema":1,"eventColumns":["type","clockMs","mainOrdinal","mainPositionMs","mainState","playWhenReadyFlag","value","originFlags"],"events":[$rows],"eventsDropped":${captured.second},"trackCount":${tracks.size},"playedTrackCount":${tracks.count { it.hasPlayed }},"trackDetailsOmitted":${(tracks.size - MAX_DIAGNOSTIC_TRACKS).coerceAtLeast(0)},"tracks":[$details]}"""
        }
    }

    companion object {
        private const val MAX_DIAGNOSTIC_EVENTS = 64
        private const val MAX_DIAGNOSTIC_TRACKS = 8
        private const val SAMPLE_RATE = 48_000
        private const val FRAME_BYTES = 4
    }
}
