package io.github.onlinemo.meloxwatch.data.local

import android.os.CancellationSignal
import java.io.InputStream
import java.security.MessageDigest

/** A token proves a complete bounded read, never a prefix hash or a product file-size limit. */
internal object ContentFingerprint {
    fun read(input: InputStream, signal: CancellationSignal, expectedBytes: Long?): String? {
        if (expectedBytes != null && expectedBytes > LocalInputLimits.READ_PROOF_BYTES) return null
        val buffer = ByteArray(64 * 1024)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        while (true) {
            signal.throwIfCanceled()
            val limit = minOf(buffer.size.toLong(), LocalInputLimits.READ_PROOF_BYTES - total + 1).toInt()
            val count = input.read(buffer, 0, limit)
            if (count < 0) break
            if (count == 0) throw SourceReadException(SourceReadIssue.SOURCE_UNAVAILABLE)
            total += count
            if (expectedBytes != null && total > expectedBytes) throw SourceReadException(SourceReadIssue.SOURCE_CHANGED)
            if (total > LocalInputLimits.READ_PROOF_BYTES) return null
            digest.update(buffer, 0, count)
        }
        if (expectedBytes != null && total != expectedBytes) throw SourceReadException(SourceReadIssue.SOURCE_CHANGED)
        if (total == 0L) throw SourceReadException(SourceReadIssue.NOT_AUDIO)
        return "sha256:" + digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
