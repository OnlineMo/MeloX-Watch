package io.github.onlinemo.meloxwatch.data.fonts

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import java.io.InputStream

internal fun interface LyricsFontStreamOpener {
    fun open(uri: String, signal: CancellationSignal): OpenedLyricsFontStream
}

internal class OpenedLyricsFontStream(
    val input: InputStream,
    val declaredBytes: Long? = null,
    private val closeDescriptor: () -> Unit = {},
) : AutoCloseable {
    override fun close() {
        var failure: Throwable? = null
        try { input.close() } catch (error: Throwable) { failure = error }
        try { closeDescriptor() } catch (error: Throwable) {
            if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
        }
        failure?.let { throw it }
    }
}

/** Transient SAF read only: no write access, persisted grant or all-files permission is requested. */
internal class AndroidLyricsFontStream(private val resolver: ContentResolver) : LyricsFontStreamOpener {
    override fun open(uri: String, signal: CancellationSignal): OpenedLyricsFontStream {
        val content = Uri.parse(uri)
        if (content.scheme != "content" || content.authority.isNullOrBlank()) fontFail(LyricsFontIssue.INVALID_INPUT)
        signal.throwIfCanceled()
        val descriptor = resolver.openAssetFileDescriptor(content, "r", signal)
            ?: fontFail(LyricsFontIssue.SOURCE_UNAVAILABLE)
        try {
            if (!descriptor.fileDescriptor.valid()) fontFail(LyricsFontIssue.SOURCE_UNAVAILABLE)
            return OpenedLyricsFontStream(
                descriptor.createInputStream(),
                descriptor.declaredLength.takeIf { it >= 0L },
                descriptor::close,
            )
        } catch (error: Throwable) {
            try { descriptor.close() } catch (closing: Throwable) { error.addSuppressed(closing) }
            throw error
        }
    }
}
