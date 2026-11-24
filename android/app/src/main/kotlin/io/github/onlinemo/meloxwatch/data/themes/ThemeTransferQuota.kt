package io.github.onlinemo.meloxwatch.data.themes

import android.content.Context
import android.os.CancellationSignal
import io.github.onlinemo.meloxwatch.data.imports.AudioCopyQuota
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.transfer.TransferException
import io.github.onlinemo.meloxwatch.data.transfer.TransferIssue
import io.github.onlinemo.meloxwatch.data.transfer.TransferLimits
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class ThemeTransferUsage(val ownedBytes: Long, val otherReservedBytes: Long)

/**
 * 同一物理通道计量主题目录和上传租约。已接收包、旧安装、恢复文件都计入真实占用。
 * 在文件所有者操作边界失效缓存；连续上传块只调整已知租约，不重复遍历资源目录。
 */
class ThemeTransferQuota private constructor(
    private val themesDirectory: () -> File,
    private val incomingDirectory: () -> File,
    private val io: LocalProviderIo,
    private val audioPendingBytes: () -> Long,
    private val audioTickets: () -> Set<String>,
    private val freeBytes: (File) -> Long,
) {
    constructor(
        context: Context,
        io: LocalProviderIo,
        audioPendingBytes: () -> Long,
        audioTickets: () -> Set<String>,
    ) : this(
        LocalThemeRepository.directoryProvider(context),
        { File(context.noBackupFilesDir.canonicalFile, AudioCopyQuota.TRANSFER_DIRECTORY) },
        io, audioPendingBytes, audioTickets, File::getUsableSpace,
    )

    internal constructor(
        themes: File,
        incoming: File,
        io: LocalProviderIo,
        audioPendingBytes: () -> Long,
        audioTickets: () -> Set<String>,
        freeBytes: (File) -> Long = File::getUsableSpace,
    ) : this({ themes }, { incoming }, io, audioPendingBytes, audioTickets, freeBytes)

    private val lifecycle = Mutex()
    private val lock = Any()
    private val leases = linkedMapOf<String, Lease>()
    private var ownedBytes: Long? = null

    internal fun invalidateThemeMeasurement() = synchronized(lock) { ownedBytes = null }

    internal suspend fun reserve(ticket: String, totalBytes: Long): ThemeTransferUsage = lifecycle.withLock {
        requireId(ticket)
        if (totalBytes !in 0..ThemeLimits.PACKAGE_BYTES) fail(TransferIssue.QUOTA_EXCEEDED)
        ensureMeasurement()
        val opening = synchronized(lock) { ticket !in leases }
        val available = if (opening) io.read { signal ->
            signal.throwIfCanceled()
            freeBytes(themesDirectory())
        } else null
        val otherPending = audioPendingBytes()
        synchronized(lock) {
            val previous = leases[ticket]
            if (previous?.adoptedOperationId != null) fail(TransferIssue.CLOSED)
            if (previous == null && leases.isNotEmpty()) fail(TransferIssue.BUSY)
            val reserved = maxOf(totalBytes, previous?.reservedBytes ?: 0L)
            val written = previous?.physicalBytes ?: 0L
            if (reserved < written) fail(TransferIssue.INTEGRITY_FAILED)
            val actual = ownedBytes ?: fail(TransferIssue.RECOVERY_REQUIRED)
            val others = leases.filterKeys { it != ticket }.values.sumOf { it.reservedBytes }
            requireQuota(actual, others, reserved)
            if (available != null) {
                requireSpace(available, pendingLocked() - (previous?.pendingBytes ?: 0L) + reserved - written, otherPending)
            }
            leases[ticket] = Lease(reserved, written, previous?.hasFonts, null)
            ThemeTransferUsage(actual, others)
        }
    }

    internal suspend fun snapshotUsage(refresh: Boolean = false): ThemeTransferUsage {
        if (refresh) invalidateThemeMeasurement()
        ensureMeasurement()
        return synchronized(lock) {
            ThemeTransferUsage(ownedBytes ?: fail(TransferIssue.RECOVERY_REQUIRED), leases.values.sumOf { it.reservedBytes })
        }
    }

    private suspend fun ensureMeasurement() {
        if (synchronized(lock) { ownedBytes != null }) return
        io.read { signal ->
            val actual = measureOnPhysicalWorker(signal)
            // 缓存与测量在同一 worker 发布，不能由迟到的 waiter 覆盖后续所有者的失效信号。
            synchronized(lock) { ownedBytes = actual }
        }
    }

    internal fun requirePhysicalRoom(ticket: String, additionalBytes: Long, availableBytes: Long) {
        val pending = synchronized(lock) {
            val lease = leases[ticket] ?: fail(TransferIssue.RECOVERY_REQUIRED)
            if (lease.adoptedOperationId != null || additionalBytes < 0L || additionalBytes > lease.pendingBytes) {
                fail(TransferIssue.INTEGRITY_FAILED)
            }
            pendingLocked()
        }
        requireSpace(availableBytes, pending, audioPendingBytes())
    }

    internal fun recordPhysicalBytes(ticket: String, totalBytes: Long) = synchronized(lock) {
        val lease = leases[ticket] ?: fail(TransferIssue.RECOVERY_REQUIRED)
        if (lease.adoptedOperationId != null || totalBytes < lease.physicalBytes || totalBytes > lease.reservedBytes) {
            fail(TransferIssue.INTEGRITY_FAILED)
        }
        leases[ticket] = lease.copy(physicalBytes = totalBytes)
    }

    internal fun classifyOnPhysicalWorker(file: File, manifest: ThemeManifest) {
        val ticket = incomingTicket(file)
        synchronized(lock) {
            val lease = leases[ticket] ?: fail(TransferIssue.RECOVERY_REQUIRED)
            if (lease.adoptedOperationId != null || Files.size(file.toPath()) != lease.physicalBytes) fail(TransferIssue.INTEGRITY_FAILED)
            leases[ticket] = lease.copy(hasFonts = manifest.fonts.isNotEmpty())
        }
    }

    /** 只有已原子移入本主题根的真实文件，才能从上传预留转入文件所有者的占用。 */
    internal fun adoptOnPhysicalWorker(ticket: String, operationId: String, archive: File, actualBytes: Long) {
        requireId(ticket)
        requireId(operationId)
        val expected = File(File(File(themesDirectory(), "received"), operationId), "input.zip")
        if (archive != expected || archive != archive.canonicalFile ||
            Files.exists(File(incomingDirectory(), "$ticket.partial").toPath(), LinkOption.NOFOLLOW_LINKS)) {
            fail(TransferIssue.INTEGRITY_FAILED)
        }
        requireThemeRegularFile(archive)
        if (Files.size(archive.toPath()) != actualBytes) fail(TransferIssue.INTEGRITY_FAILED)
        synchronized(lock) {
            val lease = leases[ticket] ?: fail(TransferIssue.RECOVERY_REQUIRED)
            if (lease.physicalBytes != actualBytes || lease.reservedBytes < actualBytes || actualBytes <= 0L) {
                fail(TransferIssue.INTEGRITY_FAILED)
            }
            // 文件已由 received 目录拥有；租约句柄保留至服务器实际退休，不重复计算同一份字节。
            leases[ticket] = lease.copy(reservedBytes = 0L, physicalBytes = 0L, adoptedOperationId = operationId)
            ownedBytes = null
        }
    }

    internal suspend fun release(ticket: String) = lifecycle.withLock {
        val previous = synchronized(lock) { leases[ticket] } ?: return@withLock
        io.read { signal ->
            signal.throwIfCanceled()
            val incoming = File(incomingDirectory(), "$ticket.partial")
            if (Files.exists(incoming.toPath(), LinkOption.NOFOLLOW_LINKS)) fail(TransferIssue.RECOVERY_REQUIRED)
            val actual = measureOnPhysicalWorker(signal)
            synchronized(lock) {
                if (leases[ticket] != previous) fail(TransferIssue.CONFLICT)
                leases.remove(ticket)
                ownedBytes = actual
            }
        }
    }

    fun pendingBytesOnPhysicalWorker(): Long = synchronized(lock) { pendingLocked() }

    internal fun ticketsOnPhysicalWorker(): Set<String> = synchronized(lock) { leases.keys.toSet() }

    /** 未分类的输入按完整预留计入字体边界；不把未知字体资源作为零。 */
    internal fun incomingFontBytesOnPhysicalWorker(signal: CancellationSignal): Long {
        validateIncoming(signal)
        return synchronized(lock) {
            leases.values.filter { it.adoptedOperationId == null && it.hasFonts != false }.sumOf { it.reservedBytes }
        }
    }

    private fun measureOnPhysicalWorker(signal: CancellationSignal): Long {
        signal.throwIfCanceled()
        validateIncoming(signal)
        val store = ThemeDirectoryStore(themesDirectory())
        return store.exclusively {
            signal.throwIfCanceled()
            store.pendingIssue()?.let(::themeFail)
            store.loadCatalog()
            val actual = store.measuredOwnedBytes()
            signal.throwIfCanceled()
            actual
        }
    }

    private fun validateIncoming(signal: CancellationSignal) {
        val root = incomingDirectory()
        if (Files.isSymbolicLink(root.toPath()) || root != root.canonicalFile) fail(TransferIssue.RECOVERY_REQUIRED)
        if (!Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        if (!Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)) fail(TransferIssue.RECOVERY_REQUIRED)
        val knownThemes = synchronized(lock) { leases.toMap() }
        val knownAudio = audioTickets()
        var count = 0
        Files.newDirectoryStream(root.toPath()).use { entries ->
            for (path in entries) {
                signal.throwIfCanceled()
                if (++count > TransferLimits.PAGE_SIZE || Files.isSymbolicLink(path) ||
                    !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) fail(TransferIssue.RECOVERY_REQUIRED)
                val file = path.toFile()
                val ticket = incomingTicket(file)
                val theme = knownThemes[ticket]
                if (theme != null) {
                    if (theme.adoptedOperationId != null || Files.size(path) != theme.physicalBytes) fail(TransferIssue.INTEGRITY_FAILED)
                } else if (ticket !in knownAudio) fail(TransferIssue.RECOVERY_REQUIRED)
            }
        }
    }

    private fun incomingTicket(file: File): String {
        if (!file.name.endsWith(".partial") || file.parentFile != incomingDirectory() || file != file.canonicalFile) {
            fail(TransferIssue.INTEGRITY_FAILED)
        }
        val ticket = file.name.removeSuffix(".partial")
        requireId(ticket)
        return ticket
    }

    private fun pendingLocked(): Long = leases.values.sumOf { it.pendingBytes }

    private fun requireQuota(owned: Long, other: Long, next: Long) {
        if (owned < 0L || other < 0L || next < 0L || owned > ThemeLimits.STORED_BYTES ||
            other > ThemeLimits.STORED_BYTES - owned || next > ThemeLimits.STORED_BYTES - owned - other) {
            fail(TransferIssue.QUOTA_EXCEEDED)
        }
    }

    private fun requireSpace(available: Long, themePending: Long, audioPending: Long) {
        if (themePending < 0L || audioPending < 0L || audioPending > Long.MAX_VALUE - themePending) fail(TransferIssue.RECOVERY_REQUIRED)
        if (available < ThemeLimits.FREE_RESERVE_BYTES || themePending + audioPending > available - ThemeLimits.FREE_RESERVE_BYTES) {
            fail(TransferIssue.STORAGE_FULL)
        }
    }

    private fun requireId(value: String) {
        val valid = try { value.length == 36 && UUID.fromString(value).toString() == value }
        catch (_: IllegalArgumentException) { false }
        if (!valid) fail(TransferIssue.INVALID_INPUT)
    }

    private fun fail(issue: TransferIssue): Nothing = throw TransferException(issue)

    private data class Lease(val reservedBytes: Long, val physicalBytes: Long, val hasFonts: Boolean?, val adoptedOperationId: String?) {
        val pendingBytes: Long get() = reservedBytes - physicalBytes
    }
}
