package io.github.onlinemo.meloxwatch.data.backup

import io.github.onlinemo.meloxwatch.data.cue.CueReferenceCodec
import io.github.onlinemo.meloxwatch.model.LocalCueReference
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** 私有日志封装保留可移植的分段提示，不改动旧日志 SQL。 */
internal object BackupRelinkHintCodec {
    private val magic = byteArrayOf(67, 85, 69, 49)
    fun encode(record: BackupRecord, reference: LocalCueReference?): ByteArray {
        val logical = BackupJsonCodec.record(record)
        if (reference == null) return logical
        require(!reference.isBound)
        val cue = CueReferenceCodec.encode(reference).toByteArray(Charsets.UTF_8)
        val bytes = Math.addExact(8, Math.addExact(logical.size, cue.size))
        if (bytes > BackupLimits.RECORD_BYTES) throw BackupException(BackupIssue.TOO_LARGE)
        return ByteBuffer.allocate(bytes).put(magic).putInt(logical.size).put(logical).put(cue).array()
    }
    fun decode(bytes: ByteArray): Pair<BackupRecord, LocalCueReference?> {
        if (bytes.size > BackupLimits.RECORD_BYTES) throw BackupException(BackupIssue.TOO_LARGE)
        if (bytes.size < 4 || !bytes.copyOfRange(0, 4).contentEquals(magic)) return BackupJsonCodec.readRecord(bytes) to null
        if (bytes.size < 9) throw BackupException(BackupIssue.INVALID_INPUT)
        val input = ByteBuffer.wrap(bytes)
        input.position(4)
        val size = input.int
        if (size <= 0 || size >= input.remaining() || input.remaining() - size > CueReferenceCodec.MAX_BYTES) {
            throw BackupException(BackupIssue.INVALID_INPUT)
        }
        val logical = ByteArray(size).also { input.get(it) }
        val cue = try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(input).toString()
            CueReferenceCodec.decode(text, portableOnly = true)
        } catch (failure: Exception) { throw BackupException(BackupIssue.INVALID_INPUT, failure) }
        return BackupJsonCodec.readRecord(logical) to cue
    }
}
