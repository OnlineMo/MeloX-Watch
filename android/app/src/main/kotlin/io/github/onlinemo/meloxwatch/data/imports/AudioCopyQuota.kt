package io.github.onlinemo.meloxwatch.data.imports

import android.content.Context
import android.os.CancellationSignal
import android.os.StatFs
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.transfer.TransferException
import io.github.onlinemo.meloxwatch.data.transfer.TransferIssue
import io.github.onlinemo.meloxwatch.data.transfer.TransferLimits
import io.github.onlinemo.meloxwatch.data.transfer.TransferQuotaAccounting
import io.github.onlinemo.meloxwatch.data.transfer.TransferQuotaUsage
import io.github.onlinemo.meloxwatch.data.transfer.TransferUploadKind
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AudioCopySpace(val usedAndReservedBytes: Long, val availableBytes: Long)

/**
 * One coordinator belongs to the application's LocalImportManager and its transfer store. Active
 * files count through their full reservation, installed/unclaimed files through real byte lengths.
 * Physical writes and adoption use the one LocalProviderIo. Directory measurement occurs only at
 * preview, lease creation or retirement; a 60/64 KiB block never rescans the private collection.
 */
class AudioCopyQuota private constructor(
    private val context: Context,
    private val io: LocalProviderIo,
    private val freeBytes: (File) -> Long,
    private val otherPendingBytes: () -> Long,
    private val otherTransferTickets: () -> Set<String>,
) : TransferQuotaAccounting {
    constructor(
        context: Context,
        io: LocalProviderIo,
        freeBytes: (File) -> Long = { StatFs(it.absolutePath).availableBytes },
    ) : this(context, io, freeBytes, { 0L }, { emptySet() })

    constructor(
        context: Context,
        io: LocalProviderIo,
        otherPendingBytes: () -> Long,
        otherTransferTickets: () -> Set<String>,
        freeBytes: (File) -> Long = { StatFs(it.absolutePath).availableBytes },
    ) : this(context, io, freeBytes, otherPendingBytes, otherTransferTickets)
    private val lifecycle = Mutex()
    private val lock = Any()
    private val leases = linkedMapOf<String, Lease>()
    private var storedBytes = 0L
    private var measured = false
    val transferDirectory: File get() = File(context.noBackupFilesDir, TRANSFER_DIRECTORY)

    suspend fun preview(declaredBytes: Long?): AudioCopySpace = lifecycle.withLock {
        if (declaredBytes != null && declaredBytes !in 1..ImportLimits.QUOTA_BYTES) fail(ImportIssue.QUOTA_EXCEEDED)
        io.read { signal ->
            val measured = measure(signal)
            val available = freeBytes(OwnedAudioProvider.root(context))
            synchronized(lock) {
                storedBytes = measured
                this@AudioCopyQuota.measured = true
                val used = usedBytes()
                requireQuota(used, declaredBytes ?: 0L)
                requireSpace(available, pendingBytes() + (declaredBytes ?: 0L))
                AudioCopySpace(used, available)
            }
        }
    }

    suspend fun reserveImport(operationId: String, totalBytes: Long) {
        reserveLease(importKey(operationId), operationId, false, totalBytes)
    }

    /** Known lengths retain their full reservation; unknown lengths grow immediately before a write. */
    fun requireImportBlockOnPhysicalWorker(operationId: String, totalBytes: Long, availableBytes: Long) = synchronized(lock) {
        val key = importKey(operationId)
        val old = leases[key] ?: fail(ImportIssue.RECOVERY_REQUIRED)
        val total = maxOf(totalBytes, old.reservedBytes)
        requireGrowth(key, total)
        val pending = pendingBytes() - (old.reservedBytes - old.physicalBytes) + (total - old.physicalBytes)
        requireSpace(availableBytes, pending)
        leases[key] = old.copy(reservedBytes = total)
    }

    fun recordImportBytesOnPhysicalWorker(operationId: String, totalBytes: Long) {
        recordWritten(importKey(operationId), totalBytes)
    }

    suspend fun releaseImport(operationId: String) { retireLease(importKey(operationId)) }

    override suspend fun reserve(ticket: String, kind: TransferUploadKind, totalBytes: Long): TransferQuotaUsage = transferAccess {
        if (kind != TransferUploadKind.AUDIO) throw TransferException(TransferIssue.UNSUPPORTED_TYPE)
        val key = transferKey(ticket)
        reserveLease(key, ticket, true, totalBytes)
        synchronized(lock) {
            TransferQuotaUsage(storedBytes, leases.filterKeys { it != key }.values.sumOf { it.reservedBytes }, 0L, 0L)
        }
    }

    override fun requirePhysicalRoom(ticket: String, additionalBytes: Long, availableBytes: Long) = transferPhysical {
        synchronized(lock) {
            val lease = leases[transferKey(ticket)] ?: fail(ImportIssue.RECOVERY_REQUIRED)
            if (additionalBytes < 0L || additionalBytes > lease.reservedBytes - lease.physicalBytes) fail(ImportIssue.INTEGRITY_FAILED)
            requireSpace(availableBytes, pendingBytes())
        }
    }

    override fun recordPhysicalBytes(ticket: String, totalBytes: Long) = transferPhysical {
        recordWritten(transferKey(ticket), totalBytes)
    }

    /**
     * Called before an atomic same-volume move, on the physical worker. Both trusted names remain
     * under the same reservation until the transfer stage physically retires. No peer path enters.
     */
    fun adoptTransferOnPhysicalWorker(ticket: String, operationId: String, source: File, actualBytes: Long) = transferPhysical {
        val ownedToken = canonicalId(operationId)
        val expected = File(trustedTransferRoot() ?: fail(ImportIssue.RECOVERY_REQUIRED),
            canonicalId(ticket) + ".partial").toPath()
        if (source.toPath().toAbsolutePath().normalize() != expected) fail(ImportIssue.INTEGRITY_FAILED)
        synchronized(lock) {
            if (leases.containsKey(importKey(operationId))) fail(ImportIssue.CONFLICT)
            val key = transferKey(ticket)
            val old = leases[key] ?: fail(ImportIssue.RECOVERY_REQUIRED)
            if (old.physicalBytes != actualBytes || actualBytes <= 0L || actualBytes > old.reservedBytes ||
                old.ownedToken != null && old.ownedToken != ownedToken) fail(ImportIssue.INTEGRITY_FAILED)
            if (leases.any { (otherKey, value) -> otherKey != key && value.ownedToken == ownedToken }) fail(ImportIssue.CONFLICT)
            leases[key] = old.copy(ownedToken = ownedToken)
        }
    }

    override suspend fun release(ticket: String) = transferAccess { retireLease(transferKey(ticket)) }

    /** 共享空间只读当前真实租约；每个写入块不重新遍历私有音频库。 */
    fun pendingBytesOnPhysicalWorker(): Long = synchronized(lock) { pendingBytes() }

    internal fun transferTicketsOnPhysicalWorker(): Set<String> = synchronized(lock) {
        leases.values.filter { it.transfer }.mapTo(linkedSetOf()) { it.token }
    }

    internal suspend fun snapshotUsage(refresh: Boolean = false): TransferQuotaUsage = transferAccess {
        if (refresh || synchronized(lock) { !measured }) lifecycle.withLock {
            io.read { signal ->
                val actual = measure(signal)
                synchronized(lock) {
                    storedBytes = actual
                    measured = true
                }
            }
        }
        synchronized(lock) {
            TransferQuotaUsage(storedBytes, leases.values.sumOf { it.reservedBytes }, 0L, 0L)
        }
    }

    private suspend fun reserveLease(key: String, token: String, transfer: Boolean, totalBytes: Long) = lifecycle.withLock {
        canonicalId(token)
        if (totalBytes !in 0..ImportLimits.QUOTA_BYTES) fail(ImportIssue.QUOTA_EXCEEDED)
        val existing = synchronized(lock) { leases[key] }
        if (existing != null) {
            synchronized(lock) {
                val current = leases[key] ?: fail(ImportIssue.RECOVERY_REQUIRED)
                if (current.transfer != transfer || current.token != token) fail(ImportIssue.CONFLICT)
                val total = maxOf(totalBytes, current.reservedBytes)
                requireGrowth(key, total)
                leases[key] = current.copy(reservedBytes = total)
            }
            return@withLock
        }
        io.read { signal ->
            val measured = measure(signal)
            val available = freeBytes(OwnedAudioProvider.root(context))
            synchronized(lock) {
                if (leases.size >= MAX_ACTIVE_LEASES) fail(ImportIssue.BUSY)
                storedBytes = measured
                this@AudioCopyQuota.measured = true
                requireQuota(usedBytes(), totalBytes)
                requireSpace(available, pendingBytes() + totalBytes)
                leases[key] = Lease(token, transfer, totalBytes, 0L, if (transfer) null else token)
            }
        }
    }

    private fun recordWritten(key: String, totalBytes: Long) = synchronized(lock) {
        val old = leases[key] ?: fail(ImportIssue.RECOVERY_REQUIRED)
        if (totalBytes < old.physicalBytes || totalBytes > old.reservedBytes) fail(ImportIssue.INTEGRITY_FAILED)
        leases[key] = old.copy(physicalBytes = totalBytes)
    }

    /** A failed deletion retains both the byte reservation and its bounded ownership slot. */
    private suspend fun retireLease(key: String) = lifecycle.withLock {
        val existing = synchronized(lock) { leases[key] } ?: return@withLock
        io.read { signal ->
            signal.throwIfCanceled()
            for (path in paths(existing).filter { it.name.endsWith(".partial") || it.name.endsWith(".part") }) {
                if (Files.exists(path.toPath(), LinkOption.NOFOLLOW_LINKS)) fail(ImportIssue.RECOVERY_REQUIRED)
            }
            val measured = measure(signal, omittedLease = key)
            synchronized(lock) {
                if (leases[key] != existing) fail(ImportIssue.CONFLICT)
                leases.remove(key)
                storedBytes = measured
                this@AudioCopyQuota.measured = true
            }
        }
    }

    private fun measure(signal: CancellationSignal, omittedLease: String? = null): Long {
        val snapshot = synchronized(lock) { leases.filterKeys { it != omittedLease }.toMap() }
        val excluded = snapshot.values.flatMap(::paths).map { it.toPath() }.toSet()
        val physicalByLease = snapshot.keys.associateWith { 0L }.toMutableMap()
        val otherTransfers = otherTransferTickets()
        var total = 0L
        val roots = listOfNotNull(OwnedAudioProvider.root(context), trustedTransferRoot())
        for (root in roots) {
            Files.newDirectoryStream(root.toPath()).use { entries ->
                for (path in entries) {
                    signal.throwIfCanceled()
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) fail(ImportIssue.RECOVERY_REQUIRED)
                    val name = path.fileName.toString()
                    val suffix = if (root.name == TRANSFER_DIRECTORY) ".partial" else if (name.endsWith(".audio")) ".audio" else ".part"
                    if (!name.endsWith(suffix)) fail(ImportIssue.RECOVERY_REQUIRED)
                    val token = canonicalId(name.removeSuffix(suffix))
                    val bytes = Files.size(path)
                    if (root.name == TRANSFER_DIRECTORY && token in otherTransfers) {
                        if (bytes !in 0..TransferLimits.THEME_BYTES) fail(ImportIssue.INTEGRITY_FAILED)
                        continue
                    }
                    if (path in excluded) {
                        val key = snapshot.entries.singleOrNull { (_, lease) -> paths(lease).any { it.toPath() == path } }?.key
                            ?: fail(ImportIssue.RECOVERY_REQUIRED)
                        physicalByLease[key] = Math.addExact(physicalByLease.getValue(key), bytes)
                    } else total = Math.addExact(total, bytes)
                    if (total > ImportLimits.QUOTA_BYTES) fail(ImportIssue.QUOTA_EXCEEDED)
                }
            }
        }
        for ((key, bytes) in physicalByLease) if (bytes > snapshot.getValue(key).reservedBytes) fail(ImportIssue.INTEGRITY_FAILED)
        return total
    }

    private fun paths(lease: Lease): List<File> {
        val result = ArrayList<File>(3)
        if (lease.transfer) result.add(File(trustedTransferRoot() ?: transferDirectory.canonicalFile, lease.token + ".partial"))
        lease.ownedToken?.let { token ->
            result.add(OwnedAudioProvider.stagingFile(context, token))
            result.add(OwnedAudioProvider.finalFile(context, token))
        }
        return result
    }

    private fun trustedTransferRoot(): File? {
        val parent = context.noBackupFilesDir.canonicalFile
        val raw = File(parent, TRANSFER_DIRECTORY)
        if (Files.isSymbolicLink(raw.toPath())) fail(ImportIssue.RECOVERY_REQUIRED)
        if (!raw.exists()) return null
        val root = raw.canonicalFile
        if (root.parentFile != parent || !Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)) fail(ImportIssue.RECOVERY_REQUIRED)
        return root
    }

    private fun usedBytes(): Long = storedBytes + leases.values.sumOf { it.reservedBytes }
    private fun pendingBytes(): Long = leases.values.sumOf { it.reservedBytes - it.physicalBytes }
    private fun requireGrowth(key: String, total: Long) {
        val old = leases[key] ?: fail(ImportIssue.RECOVERY_REQUIRED)
        if (total < old.physicalBytes || total > ImportLimits.QUOTA_BYTES) fail(ImportIssue.QUOTA_EXCEEDED)
        requireQuota(usedBytes() - old.reservedBytes, total)
    }
    private fun requireQuota(used: Long, additional: Long) {
        if (used < 0L || used > ImportLimits.QUOTA_BYTES || additional < 0L || additional > ImportLimits.QUOTA_BYTES - used) fail(ImportIssue.QUOTA_EXCEEDED)
    }
    private fun requireSpace(available: Long, pending: Long) {
        val other = otherPendingBytes()
        if (pending < 0L || other < 0L || other > Long.MAX_VALUE - pending) fail(ImportIssue.RECOVERY_REQUIRED)
        val total = pending + other
        if (available < ImportLimits.FREE_RESERVE_BYTES || total > available - ImportLimits.FREE_RESERVE_BYTES) fail(ImportIssue.STORAGE_FULL)
    }
    private fun importKey(value: String) = "I:" + canonicalId(value)
    private fun transferKey(value: String) = "T:" + canonicalId(value)
    private fun canonicalId(value: String): String {
        val valid = try { value.length == 36 && UUID.fromString(value).toString() == value } catch (_: IllegalArgumentException) { false }
        if (!valid) fail(ImportIssue.INVALID_INPUT)
        return value
    }
    private fun fail(issue: ImportIssue): Nothing = throw ImportException(issue)

    private suspend fun <T> transferAccess(action: suspend () -> T): T = try { action() }
    catch (error: CancellationException) { throw error }
    catch (error: ImportException) { throw quotaTransferError(error) }
    catch (error: SourceReadException) {
        throw TransferException(if (error.issue == SourceReadIssue.BUSY) TransferIssue.BUSY else TransferIssue.STORAGE_UNAVAILABLE)
    }
    private fun <T> transferPhysical(action: () -> T): T = try { action() }
    catch (error: ImportException) { throw quotaTransferError(error) }
    private fun quotaTransferError(error: ImportException): TransferException = TransferException(when (error.issue) {
        ImportIssue.BUSY -> TransferIssue.BUSY
        ImportIssue.INVALID_INPUT -> TransferIssue.INVALID_INPUT
        ImportIssue.CONFLICT -> TransferIssue.CONFLICT
        ImportIssue.QUOTA_EXCEEDED -> TransferIssue.QUOTA_EXCEEDED
        ImportIssue.STORAGE_FULL -> TransferIssue.STORAGE_FULL
        ImportIssue.INTEGRITY_FAILED -> TransferIssue.INTEGRITY_FAILED
        else -> TransferIssue.RECOVERY_REQUIRED
    })

    private data class Lease(val token: String, val transfer: Boolean, val reservedBytes: Long,
        val physicalBytes: Long, val ownedToken: String?)
    companion object {
        const val TRANSFER_DIRECTORY = "incoming-transfer"
        private const val MAX_ACTIVE_LEASES = 4
    }
}
