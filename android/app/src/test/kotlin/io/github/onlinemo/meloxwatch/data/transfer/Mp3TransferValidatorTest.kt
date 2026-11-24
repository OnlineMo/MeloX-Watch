package io.github.onlinemo.meloxwatch.data.transfer

import android.os.CancellationSignal
import android.os.OperationCanceledException
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Synthetic frame structure only. These bytes do not stand in for actual MP3 decoder evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class Mp3TransferValidatorTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun completeCbrAndVbrBoundariesWithId3AreAcceptedByTheStructuralWalker() {
        validate(frame(9) + frame(9))
        val tag = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0, 0, 0, 0, 3) + byteArrayOf(1, 2, 3)
        val suffix = ByteArray(128).also { it[0] = 'T'.code.toByte(); it[1] = 'A'.code.toByte(); it[2] = 'G'.code.toByte() }
        validate(tag + frame(9) + frame(10) + suffix)
    }

    @Test fun truncatedFramesGarbageAndAChangedStreamFormatCannotPassACompleteHash() {
        val bytes = frame(9) + frame(9)
        reject(bytes.copyOf(bytes.size - 1))
        reject(bytes + byteArrayOf(1))
        reject(frame(9) + frame(9, sampleRateIndex = 1))
        reject(bytes.copyOf().also { it[1] = 0xfd.toByte() })
    }

    @Test fun malformedSynchsafeTagAndFooterAreRejectedBeforeAnyDecode() {
        val tag = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0, 0, 0, 0, 0)
        reject(tag.copyOf().also { it[9] = 0x80.toByte() } + frame(9))
        reject(tag.copyOf().also { it[5] = 0x10 } + frame(9))
    }

    @Test fun structuralSuccessStillNeedsTheRealDecoderAndItsFailureIsNotSuccess() {
        val bytes = frame(9) + frame(9)
        val file = temporary.newFile().also { it.writeBytes(bytes) }
        var entered = false
        val validator = PlatformAudioTransferValidator { _, _ -> entered = true; throw TransferException(TransferIssue.UNSUPPORTED_TYPE) }
        try { validator.validate(file, declaration(bytes), CancellationSignal()); throw AssertionError("Expected decoder refusal") }
        catch (error: TransferException) { assertEquals(TransferIssue.UNSUPPORTED_TYPE, error.issue) }
        assertEquals(true, entered)
        entered = false
        val broken = bytes.copyOf(bytes.size - 1)
        file.writeBytes(broken)
        try { validator.validate(file, declaration(broken), CancellationSignal()); throw AssertionError("Expected structural failure") }
        catch (error: TransferException) { assertEquals(TransferIssue.NOT_AUDIO, error.issue) }
        assertFalse(entered)
    }

    @Test fun cancellationIsPropagatedFromTheBoundedFrameWalk() {
        val bytes = frame(9) + frame(9)
        val file = temporary.newFile().also { it.writeBytes(bytes) }
        val signal = CancellationSignal().also { it.cancel() }
        try { Mp3TransferValidator.validate(file, declaration(bytes), signal); throw AssertionError("Expected cancellation") }
        catch (_: OperationCanceledException) {}
    }

    private fun validate(bytes: ByteArray) {
        Mp3TransferValidator.validate(temporary.newFile().also { it.writeBytes(bytes) }, declaration(bytes), CancellationSignal())
    }
    private fun reject(bytes: ByteArray) {
        try { validate(bytes); throw AssertionError("Expected invalid full structure") }
        catch (error: TransferException) { assertEquals(TransferIssue.NOT_AUDIO, error.issue) }
    }
    private fun declaration(bytes: ByteArray) = TransferUploadDeclaration(TransferUploadKind.AUDIO, "Audio.mp3", "audio/mpeg",
        bytes.size.toLong(), TransferHash.digest(bytes), 0L)
    private fun frame(bitrateIndex: Int, sampleRateIndex: Int = 0): ByteArray {
        val bitrate = if (bitrateIndex == 9) 128_000 else 160_000
        val sampleRate = if (sampleRateIndex == 0) 44100 else 48000
        val count = 144 * bitrate / sampleRate
        val header = 0xfffb0000.toInt() or (bitrateIndex shl 12) or (sampleRateIndex shl 10)
        return ByteBuffer.allocate(count).putInt(header).array()
    }
}
