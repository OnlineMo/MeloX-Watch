package io.github.onlinemo.meloxwatch.data.cue

import io.github.onlinemo.meloxwatch.model.CueFileBinding
import io.github.onlinemo.meloxwatch.model.CuePcmWaveFacts
import io.github.onlinemo.meloxwatch.model.CueSegmentBinding
import io.github.onlinemo.meloxwatch.model.CueTrackReference
import io.github.onlinemo.meloxwatch.model.LocalCueReference
import io.github.onlinemo.meloxwatch.model.LocalLocationReference
import io.github.onlinemo.meloxwatch.model.QueueEntry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** 独立RIFF字节和有辨识度的立体声样本；不推定解码或设备结果。 */
internal class CueByteFixture(val rate: Int = 44_100, val sampleCount: Int = rate * 2,
    tail: ByteArray = ByteArray(0),
) {
    val cue = """FILE "disc.wav" WAVE
        |TRACK 01 AUDIO
        |TITLE "First"
        |INDEX 01 00:00:00
        |TRACK 02 AUDIO
        |TITLE "Second"
        |INDEX 01 00:01:01
        |""".trimMargin().toByteArray(Charsets.UTF_8)
    val wave = ByteArray(44 + sampleCount * 4 + tail.size).also { bytes ->
        val out = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun id(value: String) { out.put(value.toByteArray(Charsets.US_ASCII)) }
        id("RIFF");
        out.putInt(bytes.size - 8);
        id("WAVE");
        id("fmt ");
        out.putInt(16)
        out.putShort(1);
        out.putShort(2);
        out.putInt(rate);
        out.putInt(rate * 4);
        out.putShort(4);
        out.putShort(16)
        id("data");
        out.putInt(sampleCount * 4)
        repeat(sampleCount) { sample ->
            val left = sampleValue(sample)
            out.putShort(left);
            out.putShort((-left).toShort())
        }
        out.put(tail)
    }
    val facts = CuePcmWaveFacts(wave.size.toLong(), 44L, (sampleCount * 4).toLong(), rate, 2)
    fun reference(number: Int, start: Long = if (number == 1) 0L else 76L,
        end: Long? = if (number == 1) 76L else null): LocalCueReference {
        val endUs = end?.let { it / 75L * 1_000_000L + it % 75L * 1_000_000L / 75L }
            ?: sampleCount.toLong() * 1_000_000L / rate
        val identity = CueTrackReference("fixture-source", "fixture-cue", number, 1L, start, end, endUs, null, sha(cue),
            audioContentTokenHint = sha(wave))
        fun file(objectId: String, bytes: ByteArray): CueFileBinding = CueFileBinding("fixture-source", 1, 1,
            "fixture-grant", "alias-$objectId", objectId,
            LocalLocationReference("location-$objectId", "content://cue-byte/tree/root/document/$objectId",
                grantUri = "content://cue-byte/tree/root", contentRevision = 1L), null, sha(bytes), bytes.size.toLong())
        return LocalCueReference(identity, CueSegmentBinding(file("sheet", cue), file("wave", wave), facts))
    }
    fun entry(number: Int): QueueEntry {
        val ref = reference(number)
        return QueueEntry("entry-$number", ref.trackKey, (number - 1).toLong(), (number - 1).toLong(),
            "Segment $number", requireNotNull(ref.pcmWindow).durationUs / 1000L,
            requireNotNull(ref.binding).audio.location, cueReference = ref)
    }
    companion object {
        fun sha(bytes: ByteArray): String = "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        fun sampleValue(sample: Int): Short = ((sample * 17 % 50_003) - 25_001).toShort()
    }
}
