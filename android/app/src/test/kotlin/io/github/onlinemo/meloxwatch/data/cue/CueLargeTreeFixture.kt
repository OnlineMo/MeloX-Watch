package io.github.onlinemo.meloxwatch.data.cue

import android.content.Context
import android.content.Intent
import android.os.CancellationSignal
import android.os.SystemClock
import io.github.onlinemo.meloxwatch.data.library.LibraryTrack
import io.github.onlinemo.meloxwatch.data.library.LocalAudioMetadata
import io.github.onlinemo.meloxwatch.data.local.AndroidLocalSources
import io.github.onlinemo.meloxwatch.data.local.LocalMetadataReader
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.scan.LocalScanEngine
import io.github.onlinemo.meloxwatch.data.scan.ScanPhase
import io.github.onlinemo.meloxwatch.model.CuePcmWaveFacts
import io.github.onlinemo.meloxwatch.model.CueFileBinding
import io.github.onlinemo.meloxwatch.model.LocalCueReference
import io.github.onlinemo.meloxwatch.model.QueueEntry
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/** 使用生产TREE路由、扫描器和完整校验；只模拟Android返回的grant。 */
internal class CueLargeTreeFixture(val context: Context, val directory: File,
    cueInputs: ((CueFileBinding, CancellationSignal) -> CueOwnedInput)? = null,
    cueNowMs: (() -> Long)? = null,
    providerIo: LocalProviderIo? = null,
) : AutoCloseable {
    val room = RoomCueLibraryFixture(context, File(directory, "large-cue.db"))
    val io = providerIo ?: LocalProviderIo()
    val sources = if (cueInputs == null && cueNowMs == null) AndroidLocalSources(context, io) else
        AndroidLocalSources(context, io, cueInputs ?: { binding, signal -> AndroidCueOwnedInput(context, binding, signal) },
            cueNowMs ?: SystemClock::elapsedRealtime)
    val metadata = LocalMetadataReader(context, io)
    val provider = fixtureCueDocuments(context, "cue-large-documents")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val sampleFrames = 2_097_152L
    val facts = CuePcmWaveFacts(44L + sampleFrames * 4L, 44L, sampleFrames * 4L, 44_100, 2)
    private val files = LinkedHashMap<String, File>()
    suspend fun open() { room.open() }
    suspend fun scan(root: String): List<LibraryTrack> {
        provider.roots.add(root)
        val folder = File(directory, "audio-$root").apply { check(mkdirs()) }
        val cue = File(folder, "disc.cue").apply { writeText(RoomCueLibraryFixture.CUE) }
        val wave = File(folder, "disc.wav")
        writeWave(wave)
        files[root] = wave
        provider.documents["$root-cue"] = cue
        provider.documents["$root-wave"] = wave
        val observation = sources.observe(LocalSourceSpec("source-$root", LocalSourceKind.TREE,
            "content://cue-large-documents/tree/$root", Intent.FLAG_GRANT_READ_URI_PERMISSION))
        val source = room.library.registerSource(observation.source, observation.access)
        val candidateCursor = sources.open(source.spec, root)
        val before = try { candidateCursor.nextBatch().single { it.objectId == "$root-wave" } }
            finally { candidateCursor.closeAndAwait() }
        assertNull(before.providerContentToken)
        val opens = provider.opened.size
        assertNull(metadata.contentToken(before))
        assertEquals(opens, provider.opened.size)
        val reader = CueScanReader(room.library, CuePathMatcher(sources), AndroidCueLocalDocuments(sources))
        val engine = LocalScanEngine(sources, { LocalAudioMetadata(title = "Whole file", durationMs = facts.durationUs / 1000L,
            mimeType = "audio/wav") }, room.library, io, scope, metadata::contentToken,
            sources::verifyObservedCandidate, reader::read)
        val token = engine.start(source, room.library.rules().revision)
        val terminal = withTimeout(30_000L) { engine.state.filterNotNull().first { it.token.scanId == token.scanId &&
            it.phase in setOf(ScanPhase.COMPLETED, ScanPhase.FAILED, ScanPhase.CANCELLED) } }
        assertEquals(ScanPhase.COMPLETED, terminal.phase)
        val tracks = room.tracks().filter { it.cueReference?.identity?.sourceId == source.spec.sourceId }
            .sortedBy { checkNotNull(it.cueReference).identity.trackNumber }
        assertEquals(2, tracks.size)
        val actual = fileToken(wave)
        for (track in tracks) {
            val read = room.library.resolveCueRead(track.trackKey, track.cueReference)
            assertEquals(facts, checkNotNull(read.reference.binding).pcm)
            assertNull(read.audio.providerContentToken)
            assertEquals(actual, read.audio.verifiedContentToken)
            assertEquals(read.audio, sources.validateCueRead(read.audio).copy(contentValidation = read.audio.contentValidation))
        }
        assertEquals(0, provider.activeCursors.get())
        assertTrue(room.tracks().any { it.cueReference == null && it.title == "Whole file" })
        return tracks
    }
    fun file(root: String): File = checkNotNull(files[root])
    fun entry(reference: LocalCueReference): QueueEntry = QueueEntry("large-${reference.identity.trackNumber}",
        reference.trackKey, 0L, 0L, "Large segment", checkNotNull(reference.pcmWindow).durationUs / 1000L,
        checkNotNull(reference.binding).audio.location, cueReference = reference)
    fun expected(reference: LocalCueReference, position: Long, count: Int): ByteArray {
        val window = checkNotNull(reference.pcmWindow)
        val header = CuePcmWaveParser.transportHeader(checkNotNull(reference.binding).pcm, window.payloadBytes)
        return ByteArray(count) { relative ->
            val virtual = position + relative
            if (virtual < 44L) header[virtual.toInt()] else {
                val payload = window.physicalByteOffset - 44L + virtual - 44L
                val frame = payload / 4L
                val channel = (payload % 4L / 2L).toInt()
                val sample = sample(frame, channel).toInt() and 0xffff
                (sample ushr ((payload % 2L).toInt() * 8)).toByte()
            }
        }
    }
    override fun close() {
        scope.cancel()
        room.close()
    }
    private fun writeWave(file: File) {
        FileOutputStream(file).use { output ->
            output.write(CuePcmWaveParser.transportHeader(facts, facts.dataBytes))
            val bytes = ByteArray(CueReadLimits.STREAM_BYTES)
            val block = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var frame = 0L
            while (frame < sampleFrames) {
                block.clear()
                while (block.remaining() >= 4 && frame < sampleFrames) {
                    block.putShort(sample(frame, 0));
                    block.putShort(sample(frame, 1));
                    frame++
                }
                output.write(bytes, 0, block.position())
            }
        }
    }
    companion object {
        private fun sample(frame: Long, channel: Int): Short {
            val left = ((frame % 30_001L) - 15_000L).toInt()
            return (if (channel == 0) left else -left).toShort()
        }
        fun fileToken(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = ByteArray(CueReadLimits.STREAM_BYTES)
            FileInputStream(file).use { input ->
                while (true) {
                    val count = input.read(bytes);
                    if (count < 0) break;
                    check(count > 0);
                    digest.update(bytes, 0, count)
                }
            }
            return CueCompleteContentProof.token(digest.digest())
        }
    }
}
