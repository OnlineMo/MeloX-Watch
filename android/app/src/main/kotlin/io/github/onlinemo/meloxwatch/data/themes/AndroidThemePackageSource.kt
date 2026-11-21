package io.github.onlinemo.meloxwatch.data.themes

import android.content.ContentResolver
import android.os.CancellationSignal
import androidx.core.net.toUri
import io.github.onlinemo.meloxwatch.data.local.LocalInputLimits
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

internal interface ThemePackageOpener {
    fun open(uri: String, signal: CancellationSignal): OpenedThemePackage
}

internal class OpenedThemePackage(
    val input: InputStream,
    val declaredBytes: Long?,
    private val closeDescriptor: () -> Unit = {},
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var failure: Throwable? = null
        try {
            input.close()
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                closeDescriptor()
            } catch (closing: Throwable) {
                if (failure == null) throw closing
                if (failure !== closing) failure.addSuppressed(closing)
            }
        }
    }
}

/** 仅消费调用方已明确选择并有读授权的 content URI，不持久化来源或自行扩大授权。 */
internal class AndroidThemePackageSource(private val resolver: ContentResolver) : ThemePackageOpener {
    override fun open(uri: String, signal: CancellationSignal): OpenedThemePackage {
        if (uri.isBlank() || uri.length > LocalInputLimits.URI_CHARACTERS || uri.any(Char::isISOControl)) {
            themeFail(ThemeIssue.INVALID_INPUT)
        }
        val parsed = uri.toUri()
        if (parsed.scheme != "content" || parsed.authority.isNullOrBlank() || parsed.fragment != null) {
            themeFail(ThemeIssue.INVALID_INPUT)
        }
        signal.throwIfCanceled()
        val descriptor = resolver.openAssetFileDescriptor(parsed, "r", signal) ?: themeFail(ThemeIssue.SOURCE_UNAVAILABLE)
        try {
            val input = descriptor.createInputStream()
            val length = descriptor.length.takeIf { it >= 0 }
            return OpenedThemePackage(input, length, descriptor::close)
        } catch (error: Throwable) {
            try {
                descriptor.close()
            } catch (closing: Throwable) {
                if (error !== closing) error.addSuppressed(closing)
            }
            throw error
        }
    }
}
