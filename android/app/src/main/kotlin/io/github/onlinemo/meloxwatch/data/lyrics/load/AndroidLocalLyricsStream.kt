package io.github.onlinemo.meloxwatch.data.lyrics.load

import android.content.ContentResolver
import android.os.CancellationSignal
import androidx.core.net.toUri
import java.io.InputStream

internal fun interface LocalLyricsStreamOpener {
    /** Called only inside the shared physical provider worker, with independently owned resources. */
    fun open(uri: String, signal: CancellationSignal): LocalLyricsOpenedStream
}

internal class LocalLyricsOpenedStream(
    val input: InputStream,
    val declaredSizeBytes: Long? = null,
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

internal class AndroidLocalLyricsStream(private val resolver: ContentResolver) : LocalLyricsStreamOpener {
    override fun open(uri: String, signal: CancellationSignal): LocalLyricsOpenedStream {
        val content = uri.toUri()
        if (content.scheme != "content" || content.authority.isNullOrBlank()) lyricsLoadFail(LyricsLoadIssue.INVALID_INPUT)
        signal.throwIfCanceled()
        val descriptor = resolver.openAssetFileDescriptor(content, "r", signal)
            ?: lyricsLoadFail(LyricsLoadIssue.SOURCE_UNAVAILABLE)
        try {
            if (!descriptor.fileDescriptor.valid()) lyricsLoadFail(LyricsLoadIssue.SOURCE_UNAVAILABLE)
            val input = descriptor.createInputStream()
            return LocalLyricsOpenedStream(input, descriptor.declaredLength.takeIf { it >= 0L }, descriptor::close)
        } catch (error: Throwable) {
            try { descriptor.close() } catch (closing: Throwable) { error.addSuppressed(closing) }
            throw error
        }
    }
}
