package io.github.onlinemo.meloxwatch.data.themes

import android.content.Context
import android.os.CancellationSignal
import io.github.onlinemo.meloxwatch.data.fonts.AndroidLyricsFontFiles
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontException
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontIssue
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.transfer.TransferException
import java.io.File

/**
 * Application injects one bridge and the existing physical channel into both file owners.
 * io owns serialization; this bridge never queues work or holds a second in-process mutex.
 * Order is io worker -> optional theme file lock -> synchronous measurement. Font measurement
 * takes no file lock, and reciprocal measurement never calls io.read or acquires an owner gate.
 */
class SharedFontThemeStorageBudget private constructor(
    private val io: LocalProviderIo,
    private val fontsDirectory: () -> File,
    private val themesDirectory: () -> File,
    private val incomingThemes: ThemeTransferQuota?,
    private val audioPendingBytes: () -> Long,
) {
    constructor(
        context: Context,
        io: LocalProviderIo,
        incomingThemes: ThemeTransferQuota? = null,
        audioPendingBytes: () -> Long = { 0L },
    ) : this(
        io, AndroidLyricsFontFiles.fontDirectoryProvider(context), LocalThemeRepository.directoryProvider(context),
        incomingThemes, audioPendingBytes,
    )

    internal constructor(
        fonts: File,
        themes: File,
        io: LocalProviderIo,
        incomingThemes: ThemeTransferQuota? = null,
        audioPendingBytes: () -> Long = { 0L },
    ) : this(io, { fonts }, { themes }, incomingThemes, audioPendingBytes)

    private enum class Owner { FONTS, THEMES }
    private data class Worker(val owner: Owner, val signal: CancellationSignal)
    private val worker = ThreadLocal<Worker>()

    internal fun uses(channel: LocalProviderIo): Boolean = channel === io

    internal fun <T> fontsOnWorker(signal: CancellationSignal, action: () -> T): T = onWorker(Owner.FONTS, signal, action)
    internal fun <T> themesOnWorker(signal: CancellationSignal, action: () -> T): T {
        incomingThemes?.invalidateThemeMeasurement()
        try { return onWorker(Owner.THEMES, signal, action) }
        finally { incomingThemes?.invalidateThemeMeasurement() }
    }

    val themeBudget: ThemeFontStorageBudget = ThemeFontStorageBudget {
        val active = requireWorker(Owner.THEMES)
        try {
            val own = AndroidLyricsFontFiles.measuredOwnedBytes(fontsDirectory(), active.signal)
            val incoming = incomingThemes?.incomingFontBytesOnPhysicalWorker(active.signal) ?: 0L
            Math.addExact(own, incoming)
        } catch (error: LyricsFontException) {
            throw ThemeException(
                if (error.issue == LyricsFontIssue.QUOTA_EXCEEDED) ThemeIssue.FONT_QUOTA_EXCEEDED
                else ThemeIssue.FONT_BUDGET_UNAVAILABLE, error,
            )
        } catch (error: TransferException) {
            throw ThemeException(ThemeIssue.FONT_BUDGET_UNAVAILABLE, error)
        }
    }

    internal fun themeBytesForFonts(signal: CancellationSignal): Long {
        val active = requireWorker(Owner.FONTS)
        check(active.signal === signal) { "Reciprocal measurement must use the same cancellation signal" }
        return try {
            val own = ThemeFontStorageMeasurement.measure(themesDirectory(), signal)
            val incoming = incomingThemes?.incomingFontBytesOnPhysicalWorker(signal) ?: 0L
            Math.addExact(own, incoming)
        } catch (error: ThemeException) {
            throw LyricsFontException(
                if (error.issue == ThemeIssue.FONT_QUOTA_EXCEEDED) LyricsFontIssue.QUOTA_EXCEEDED
                else LyricsFontIssue.FONT_BUDGET_UNAVAILABLE, error,
            )
        } catch (error: TransferException) {
            throw LyricsFontException(LyricsFontIssue.FONT_BUDGET_UNAVAILABLE, error)
        }
    }

    /** 实际可用空间扣除两类尚未写入的预留，主题复制不能占用已接受音频的空间。 */
    internal fun availableBytesForThemesOnWorker(directory: File): Long {
        requireWorker(Owner.THEMES)
        val audio = audioPendingBytes()
        val themes = incomingThemes?.pendingBytesOnPhysicalWorker() ?: 0L
        if (audio < 0L || themes < 0L || themes > Long.MAX_VALUE - audio) themeFail(ThemeIssue.STORAGE_FULL)
        val pending = audio + themes
        val actual = directory.usableSpace
        return if (actual <= pending) 0L else actual - pending
    }

    private fun requireWorker(owner: Owner): Worker {
        val active = worker.get() ?: throw IllegalStateException("Font/theme accounting requires the physical worker")
        check(active.owner == owner) { "Accounting cannot enter the other owner's operation" }
        active.signal.throwIfCanceled()
        return active
    }

    private fun <T> onWorker(owner: Owner, signal: CancellationSignal, action: () -> T): T {
        check(worker.get() == null) { "Nested physical work is forbidden" }
        signal.throwIfCanceled()
        worker.set(Worker(owner, signal))
        try { return action() }
        finally { worker.remove() }
    }
}
