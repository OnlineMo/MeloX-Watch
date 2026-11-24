package io.github.onlinemo.meloxwatch.data.transfer

import io.github.onlinemo.meloxwatch.data.imports.AudioCopyQuota
import io.github.onlinemo.meloxwatch.data.themes.ThemeException
import io.github.onlinemo.meloxwatch.data.themes.ThemeTransferQuota
import io.github.onlinemo.meloxwatch.data.themes.transferThemeFailure

/** 上传与本机导入复用各自真实的文件所有者，保留失败预留直到实际清理回执。 */
class LocalTransferQuotaAccounting(
    private val audio: AudioCopyQuota,
    private val themes: ThemeTransferQuota,
) : TransferQuotaAccounting {
    private val lock = Any()
    private val routes = linkedMapOf<String, TransferUploadKind>()

    override suspend fun reserve(ticket: String, kind: TransferUploadKind, totalBytes: Long): TransferQuotaUsage = translate {
        val opening = synchronized(lock) {
            val previous = routes[ticket]
            if (previous != null && previous != kind) throw TransferException(TransferIssue.CONFLICT)
            if (previous == null && routes.size >= TransferLimits.CONNECTIONS) throw TransferException(TransferIssue.BUSY)
            routes[ticket] = kind
            previous == null
        }
        when (kind) {
            TransferUploadKind.AUDIO -> {
                val own = audio.reserve(ticket, kind, totalBytes)
                val other = themes.snapshotUsage(refresh = opening)
                TransferQuotaUsage(own.ownedAudioBytes, own.otherReservedAudioBytes, other.ownedBytes, other.otherReservedBytes)
            }
            TransferUploadKind.THEME -> {
                val other = audio.snapshotUsage(refresh = opening)
                val own = themes.reserve(ticket, totalBytes)
                TransferQuotaUsage(other.ownedAudioBytes, other.otherReservedAudioBytes, own.ownedBytes, own.otherReservedBytes)
            }
        }
    }

    override fun requirePhysicalRoom(ticket: String, additionalBytes: Long, availableBytes: Long) {
        when (route(ticket)) {
            TransferUploadKind.AUDIO -> audio.requirePhysicalRoom(ticket, additionalBytes, availableBytes)
            TransferUploadKind.THEME -> themes.requirePhysicalRoom(ticket, additionalBytes, availableBytes)
        }
    }

    override fun recordPhysicalBytes(ticket: String, totalBytes: Long) {
        when (route(ticket)) {
            TransferUploadKind.AUDIO -> audio.recordPhysicalBytes(ticket, totalBytes)
            TransferUploadKind.THEME -> themes.recordPhysicalBytes(ticket, totalBytes)
        }
    }

    override suspend fun release(ticket: String) = translate {
        val kind = route(ticket)
        when (kind) {
            TransferUploadKind.AUDIO -> audio.release(ticket)
            TransferUploadKind.THEME -> themes.release(ticket)
        }
        synchronized(lock) {
            if (routes[ticket] != kind) throw TransferException(TransferIssue.CONFLICT)
            routes.remove(ticket)
        }
        Unit
    }

    private fun route(ticket: String): TransferUploadKind = synchronized(lock) {
        routes[ticket] ?: throw TransferException(TransferIssue.RECOVERY_REQUIRED)
    }

    private suspend fun <T> translate(action: suspend () -> T): T = try { action() }
    catch (error: ThemeException) { throw transferThemeFailure(error) }
}
