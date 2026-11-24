package io.github.onlinemo.meloxwatch.playback.resources.metadata

import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ForwardingExtractorInput
import java.io.InterruptedIOException

/** 先检查刚读取的 header，再把长度交给 extractor；payload 内计数在其下游解析前检查。 */
@UnstableApi
internal class BoundedExtractorInput(
    private val source: ExtractorInput,
    private val id3: Id3Admission,
    private val container: ContainerAdmission,
) : ForwardingExtractorInput(source) {
    private fun check() { if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Metadata read cancelled") }
    private fun peekBudget(count: Int) {
        admit(count >= 0)
        val wanted = boundedAdd(source.peekPosition - source.position, count.toLong())
        admit(wanted in 0..MetadataLimits.PEEK_WINDOW.toLong(), MetadataAdmissionIssue.RESOURCE_LIMIT)
    }
    private fun returned(at: Long, bytes: ByteArray, offset: Int, count: Int, peek: Boolean) {
        check()
        id3.returned(at, bytes, offset, count, peek, source.length)
        container.returned(at, bytes, offset, count, peek, source.length)
        check()
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        check()
        val at = source.position
        val count = source.read(buffer, offset, length)
        check()
        if (count > 0) returned(at, buffer, offset, count, false)
        return count
    }
    override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
        check()
        val at = source.position
        val read = source.readFully(target, offset, length, allowEndOfInput)
        check()
        if (read) returned(at, target, offset, length, false)
        return read
    }
    override fun readFully(target: ByteArray, offset: Int, length: Int) { readFully(target, offset, length, false) }
    override fun peek(target: ByteArray, offset: Int, length: Int): Int {
        check()
        peekBudget(length)
        val at = source.peekPosition
        val count = source.peek(target, offset, length)
        check()
        if (count > 0) returned(at, target, offset, count, true)
        return count
    }
    override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
        check()
        peekBudget(length)
        val at = source.peekPosition
        val read = source.peekFully(target, offset, length, allowEndOfInput)
        check()
        if (read) returned(at, target, offset, length, true)
        return read
    }
    override fun peekFully(target: ByteArray, offset: Int, length: Int) { peekFully(target, offset, length, false) }
    override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean {
        check()
        peekBudget(length)
        val advanced = source.advancePeekPosition(length, allowEndOfInput)
        check()
        return advanced
    }
    override fun advancePeekPosition(length: Int) { advancePeekPosition(length, false) }
    override fun skip(length: Int): Int {
        check()
        admit(length >= 0)
        val at = source.position
        val count = source.skip(length)
        if (count > 0) container.skipped(at, count)
        check()
        return count
    }
    override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean {
        check()
        admit(length >= 0)
        val at = source.position
        val skipped = source.skipFully(length, allowEndOfInput)
        if (skipped) container.skipped(at, length)
        check()
        return skipped
    }
    override fun skipFully(length: Int) { skipFully(length, false) }
}
