package io.github.onlinemo.meloxwatch.data.imports

import android.content.Context
import android.os.CancellationSignal
import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import io.github.onlinemo.meloxwatch.data.library.LibraryException
import io.github.onlinemo.meloxwatch.data.library.LibraryIssue
import io.github.onlinemo.meloxwatch.data.library.LocalAudioMetadata
import io.github.onlinemo.meloxwatch.data.local.AndroidLocalSources
import io.github.onlinemo.meloxwatch.data.local.LocalGrantObservation
import io.github.onlinemo.meloxwatch.data.local.LocalMetadataReader
import io.github.onlinemo.meloxwatch.data.local.LocalNodeKind
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.LocalSourceAccess
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceObservation
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

data class ImportProgress(val operationId: String, val copiedBytes: Long, val stopping: Boolean = false)
data class ImportRecoverySummary(val committed: Long, val interrupted: Long, val pending: Long)

/** Explicit copies share the physical provider channel and never modify the selected original. */
class LocalImportManager(
    private val context: Context,
    private val sources: LocalSourceAccess,
    metadataReader: LocalMetadataReader,
    private val ledger: ImportLedger,
    private val io: LocalProviderIo,
    private val readMetadata: (File, CancellationSignal) -> LocalAudioMetadata = metadataReader::readPrivateFile,
    private val openInput: (ObservedCandidate, CancellationSignal) -> InputStream = { candidate, signal ->
        context.contentResolver.openAssetFileDescriptor(AndroidLocalSources.contentUri(candidate.uri), "r", signal)
            ?.createInputStream() ?: throw ImportException(ImportIssue.SOURCE_UNAVAILABLE)
    },
    private val freeBytes: (File) -> Long = { StatFs(it.absolutePath).availableBytes },
    private val syncDirectory: (File) -> Unit = ::syncImportDirectory,
) {
    private val operationLock = Mutex()
    private val stateLock = Any()
    private var preview: ImportPreview? = null
    private var active: Active? = null
    private val mutableState = MutableStateFlow<ImportRecord?>(null)
    val state: StateFlow<ImportRecord?> = mutableState.asStateFlow()
    private val mutableProgress = MutableStateFlow<ImportProgress?>(null)
    val progress: StateFlow<ImportProgress?> = mutableProgress.asStateFlow()

    suspend fun prepare(source: LocalSourceSpec): ImportPreview = exclusive {
        val observed = sources.observe(source)
        if (observed.root.directory || !observed.root.mayContainAudio) throw ImportException(ImportIssue.NOT_AUDIO)
        val result = io.read { signal ->
            signal.throwIfCanceled()
            val root = OwnedAudioProvider.root(context)
            val occupied = ownedBytes(root)
            val available = freeBytes(root)
            val declared = observed.root.sizeBytes
            ImportSpacePolicy.requireRoom(occupied, 0, declared ?: 0, available)
            ImportPreview(ImportIntent(UUID.randomUUID().toString(), observed.source, observed.root,
                observed.identity, observed.providerVersion), declared, occupied, available)
        }
        synchronized(stateLock) { preview = result }
        result
    }

    fun discardPreview(operationId: String) {
        synchronized(stateLock) { if (preview?.intent?.operationId == operationId) preview = null }
    }

    suspend fun confirmCopy(confirmed: ImportPreview): ImportRecord = exclusive {
        val existing = ledger.record(confirmed.intent.operationId)
        if (existing != null) {
            if (existing.intent != confirmed.intent) throw ImportException(ImportIssue.CONFLICT)
            if (existing.phase != ImportPhase.COPYING) return@exclusive recoverRecord(existing)
            // A previous process may have stopped while writing; a retry does not repeat that copy.
            return@exclusive interruptCopy(existing)
        }
        synchronized(stateLock) {
            if (preview != confirmed) throw ImportException(ImportIssue.INVALID_INPUT)
            preview = null
        }
        var record = ledger.begin(confirmed.intent)
        val intent = record.intent
        val task = Active(intent.operationId, currentCoroutineContext()[Job])
        synchronized(stateLock) { active = task }
        mutableState.value = record
        mutableProgress.value = ImportProgress(intent.operationId, 0)
        try {
            assertUnchanged(intent, sources.observe(intent.source))
            val verified = io.read { signal -> copyAndValidate(intent, signal) }
            val afterCopy = sources.observe(intent.source)
            assertUnchanged(intent, afterCopy)
            if (intent.candidate.providerContentToken == null) {
                // Weak size/time facts cannot prove that a provider did not change a same-size file.
                val current = io.read { signal -> openInput(intent.candidate, signal).use { hash(it, signal, verified.bytes) } }
                if (current != verified.sha256) throw ImportException(ImportIssue.SOURCE_CHANGED)
                assertUnchanged(intent, sources.observe(intent.source))
            }
            currentCoroutineContext().ensureActive()
            record = ledger.recordVerified(intent.operationId, verified.bytes, verified.sha256, verified.metadata)
            mutableState.value = record
            val alreadyInstalled = verifyInstallInput(record)
            currentCoroutineContext().ensureActive()
            // Installation is a commit point. Cancellation after it reports the real DB/recovery result.
            val result = withContext(NonCancellable) { installAndCommit(record, alreadyInstalled) }
            mutableState.value = result
            result
        } catch (error: CancellationException) {
            val result = withContext(NonCancellable) {
                io.awaitIdle()
                val durable = ledger.record(intent.operationId) ?: record
                val installed = io.read { OwnedAudioProvider.finalFile(context, intent.fileToken).isFile }
                if (durable.phase == ImportPhase.COMMITTED) durable
                else if (installed && durable.phase in setOf(ImportPhase.VERIFIED, ImportPhase.INSTALLED)) {
                    if (durable.phase == ImportPhase.VERIFIED) ledger.markInstalled(intent.operationId)
                    ledger.markUnfinished(intent.operationId, ImportPhase.INSTALLED, ImportIssue.RECOVERY_REQUIRED)
                } else {
                    removeStaging(intent)
                    ledger.markUnfinished(intent.operationId, ImportPhase.CANCELLED, ImportIssue.CANCELLED)
                }
            }
            mutableState.value = result
            mutableProgress.value = mutableProgress.value?.copy(stopping = false)
            if (result.phase in setOf(ImportPhase.COMMITTED, ImportPhase.INSTALLED)) result else throw error
        } catch (error: Exception) {
            val issue = importIssue(error)
            val result = withContext(NonCancellable) {
                io.awaitIdle()
                val installed = io.read { OwnedAudioProvider.finalFile(context, intent.fileToken).isFile }
                if (installed) {
                    // Even a failure between rename and markInstalled must preserve the original bytes.
                    val durable = ledger.record(intent.operationId) ?: record
                    val installedRecord = if (durable.phase == ImportPhase.VERIFIED) ledger.markInstalled(intent.operationId) else durable
                    if (installedRecord.phase == ImportPhase.COMMITTED) installedRecord else
                        ledger.markUnfinished(intent.operationId, ImportPhase.INSTALLED, ImportIssue.RECOVERY_REQUIRED)
                } else {
                    removeStaging(intent)
                    ledger.markUnfinished(intent.operationId, ImportPhase.FAILED, issue)
                }
            }
            mutableState.value = result
            result
        } finally {
            synchronized(stateLock) { if (active === task) active = null }
        }
    }

    suspend fun cancel(operationId: String) {
        val task = synchronized(stateLock) { active?.takeIf { it.operationId == operationId } } ?: return
        mutableProgress.value = mutableProgress.value?.copy(stopping = true)
        task.job?.cancel(CancellationException("Import cancelled"))
        if (task.job != currentCoroutineContext()[Job]) task.job?.join()
    }

    /** One bounded page at a time. A partial copy is retired, not resumed from an unreliable cursor. */
    suspend fun recover(): ImportRecoverySummary = exclusive {
        var after: String? = null
        var committed = 0L
        var interrupted = 0L
        var pending = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = ledger.pending(after)
            for (record in page.records) {
                currentCoroutineContext().ensureActive()
                val result = recoverRecord(record)
                when (result.phase) {
                    ImportPhase.COMMITTED -> committed = Math.addExact(committed, 1)
                    ImportPhase.FAILED, ImportPhase.CANCELLED -> interrupted = Math.addExact(interrupted, 1)
                    else -> pending = Math.addExact(pending, 1)
                }
                mutableState.value = result
            }
            val next = page.nextOperationId ?: break
            if (next == after || page.records.isEmpty()) throw ImportException(ImportIssue.RECOVERY_REQUIRED)
            after = next
        }
        ImportRecoverySummary(committed, interrupted, pending)
    }

    private suspend fun recoverRecord(record: ImportRecord): ImportRecord = when (record.phase) {
        ImportPhase.COPYING -> interruptCopy(record)
        ImportPhase.VERIFIED, ImportPhase.INSTALLED -> {
            try {
                val alreadyInstalled = verifyInstallInput(record)
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) { installAndCommit(record, alreadyInstalled) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                withContext(NonCancellable) {
                    io.awaitIdle()
                    val exists = io.read { OwnedAudioProvider.finalFile(context, record.intent.fileToken).isFile }
                    if (exists || record.phase == ImportPhase.INSTALLED) {
                        if (record.phase == ImportPhase.VERIFIED) ledger.markInstalled(record.intent.operationId)
                        ledger.markUnfinished(record.intent.operationId, ImportPhase.INSTALLED, ImportIssue.RECOVERY_REQUIRED)
                    } else {
                        removeStaging(record.intent)
                        ledger.markUnfinished(record.intent.operationId, ImportPhase.FAILED, importIssue(error))
                    }
                }
            }
        }
        else -> record
    }

    private suspend fun interruptCopy(record: ImportRecord): ImportRecord = withContext(NonCancellable) {
        io.awaitIdle()
        val exists = io.read { OwnedAudioProvider.finalFile(context, record.intent.fileToken).exists() }
        if (exists) throw ImportException(ImportIssue.RECOVERY_REQUIRED)
        removeStaging(record.intent)
        ledger.markUnfinished(record.intent.operationId, ImportPhase.FAILED, ImportIssue.INTERRUPTED)
    }

    private suspend fun verifyInstallInput(record: ImportRecord): Boolean = io.read { signal ->
        val final = OwnedAudioProvider.finalFile(context, record.intent.fileToken)
        if (final.exists()) {
            verifyPrivate(final, record, signal)
            true
        } else {
            if (record.phase == ImportPhase.INSTALLED) throw ImportException(ImportIssue.RECOVERY_REQUIRED)
            verifyPrivate(OwnedAudioProvider.stagingFile(context, record.intent.fileToken), record, signal)
            false
        }
    }

    private suspend fun installAndCommit(record: ImportRecord, alreadyInstalled: Boolean): ImportRecord {
        val intent = record.intent
        io.read { signal ->
            val final = OwnedAudioProvider.finalFile(context, intent.fileToken)
            val staging = OwnedAudioProvider.stagingFile(context, intent.fileToken)
            signal.throwIfCanceled()
            if (!alreadyInstalled) {
                if (final.exists() || !staging.isFile || staging.length() != record.actualBytes) throw ImportException(ImportIssue.INTEGRITY_FAILED)
                // No non-atomic copy fallback and no replacement of an existing completed object.
                Files.move(staging.toPath(), final.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
            if (!final.isFile || final.length() != record.actualBytes) throw ImportException(ImportIssue.INTEGRITY_FAILED)
            syncDirectory(final.parentFile!!)
        }
        val installed = if (record.phase == ImportPhase.INSTALLED) record else ledger.markInstalled(intent.operationId)
        val uri = OwnedAudioProvider.uri(context, intent.fileToken).toString()
        val spec = LocalSourceSpec("import:${intent.operationId}", LocalSourceKind.APP_OWNED, uri,
            authority = OwnedAudioProvider.authority(context), rootObjectId = intent.fileToken)
        val grant = LocalGrantObservation(
            appOwned = true, read = ObservedCapability.AVAILABLE, offlineRead = ObservedCapability.AVAILABLE,
            enumerate = ObservedCapability.UNAVAILABLE, persistedRead = ObservedCapability.UNAVAILABLE,
            persistedWrite = ObservedCapability.UNAVAILABLE,
        )
        val candidate = ObservedCandidate(spec.sourceId, intent.fileToken, uri, null, intent.candidate.displayName,
            installed.metadata?.mimeType, LocalNodeKind.AUDIO, installed.actualBytes,
            providerContentToken = "sha256:${installed.sha256}", entityProof = "owned:${spec.authority}:${intent.fileToken}")
        val committed = ledger.commitInstalled(intent.operationId, spec, grant, candidate)
        removeStaging(intent)
        return committed
    }

    private fun copyAndValidate(intent: ImportIntent, signal: CancellationSignal): VerifiedCopy {
        val staging = OwnedAudioProvider.stagingFile(context, intent.fileToken)
        val root = staging.parentFile!!
        val occupied = ownedBytes(root)
        val buffer = ByteArray(ImportLimits.BUFFER_BYTES)
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        try {
            FileChannel.open(staging.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                openInput(intent.candidate, signal).use { input ->
                    var emptyReads = 0
                    while (true) {
                        signal.throwIfCanceled()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) {
                            if (++emptyReads > 16) throw ImportException(ImportIssue.SOURCE_UNAVAILABLE)
                            continue
                        }
                        emptyReads = 0
                        ImportSpacePolicy.requireRoom(occupied, copied, read.toLong(), freeBytes(root))
                        val chunk = ByteBuffer.wrap(buffer, 0, read)
                        while (chunk.hasRemaining()) { signal.throwIfCanceled(); output.write(chunk) }
                        digest.update(buffer, 0, read)
                        copied = Math.addExact(copied, read.toLong())
                        mutableProgress.value = ImportProgress(intent.operationId, copied)
                    }
                }
                if (copied == 0L) throw ImportException(ImportIssue.NOT_AUDIO)
                if (intent.candidate.sizeBytes != null && copied != intent.candidate.sizeBytes) throw ImportException(ImportIssue.SOURCE_CHANGED)
                output.force(true)
            }
            signal.throwIfCanceled()
            val metadata = readMetadata(staging, signal)
            if (freeBytes(root) < ImportLimits.FREE_RESERVE_BYTES) throw ImportException(ImportIssue.STORAGE_FULL)
            return VerifiedCopy(copied, digest.digest().hex(), metadata)
        } catch (error: IOException) {
            throw ImportException(if (freeBytes(root) <= ImportLimits.FREE_RESERVE_BYTES) ImportIssue.STORAGE_FULL
                else ImportIssue.SOURCE_UNAVAILABLE, error)
        }
    }

    private fun verifyPrivate(file: File, record: ImportRecord, signal: CancellationSignal) {
        if (!file.isFile || Files.isSymbolicLink(file.toPath()) || file.length() != record.actualBytes) {
            throw ImportException(ImportIssue.INTEGRITY_FAILED)
        }
        val actual = Files.newInputStream(file.toPath()).use { hash(it, signal, record.actualBytes) }
        if (actual != record.sha256) throw ImportException(ImportIssue.INTEGRITY_FAILED)
    }

    private suspend fun removeStaging(intent: ImportIntent) {
        io.read { signal ->
            signal.throwIfCanceled()
            val file = OwnedAudioProvider.stagingFile(context, intent.fileToken)
            if (Files.deleteIfExists(file.toPath())) syncDirectory(file.parentFile!!)
        }
    }

    private fun ownedBytes(root: File): Long {
        var occupied = 0L
        Files.newDirectoryStream(root.toPath()).use { paths ->
            for (path in paths) {
                if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)) throw ImportException(ImportIssue.RECOVERY_REQUIRED)
                occupied = Math.addExact(occupied, Files.size(path))
                if (occupied > ImportLimits.QUOTA_BYTES) throw ImportException(ImportIssue.QUOTA_EXCEEDED)
            }
        }
        return occupied
    }

    private fun assertUnchanged(intent: ImportIntent, observation: LocalSourceObservation) {
        val before = intent.candidate
        val after = observation.root
        if (observation.identity != intent.sourceIdentity || observation.providerVersion != intent.providerVersion ||
            after.objectId != before.objectId || after.uri != before.uri || after.directory ||
            (before.sizeBytes != null && after.sizeBytes != before.sizeBytes) ||
            (before.modifiedEpochMs != null && after.modifiedEpochMs != before.modifiedEpochMs) ||
            (before.providerContentToken != null && after.providerContentToken != before.providerContentToken)
        ) throw ImportException(ImportIssue.SOURCE_CHANGED)
    }

    private suspend fun <T> exclusive(action: suspend () -> T): T {
        if (!operationLock.tryLock()) throw ImportException(ImportIssue.BUSY)
        try { return action() } finally { operationLock.unlock() }
    }

    private fun hash(input: InputStream, signal: CancellationSignal, expectedBytes: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(ImportLimits.BUFFER_BYTES)
        var readBytes = 0L
        var emptyReads = 0
        while (true) {
            signal.throwIfCanceled()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) {
                if (++emptyReads > 16) throw ImportException(ImportIssue.SOURCE_UNAVAILABLE)
                continue
            }
            emptyReads = 0
            readBytes = Math.addExact(readBytes, count.toLong())
            if (readBytes > expectedBytes || readBytes > ImportLimits.QUOTA_BYTES) throw ImportException(ImportIssue.SOURCE_CHANGED)
            digest.update(buffer, 0, count)
        }
        if (readBytes != expectedBytes) throw ImportException(ImportIssue.SOURCE_CHANGED)
        return digest.digest().hex()
    }

    private data class Active(val operationId: String, val job: Job?)
    private data class VerifiedCopy(val bytes: Long, val sha256: String, val metadata: LocalAudioMetadata)
}

internal object ImportSpacePolicy {
    fun requireRoom(ownedBytes: Long, writtenBytes: Long, nextBytes: Long, availableBytes: Long) {
        if (ownedBytes < 0 || writtenBytes < 0 || nextBytes < 0 || availableBytes < 0) throw ImportException(ImportIssue.INVALID_INPUT)
        if (ownedBytes > ImportLimits.QUOTA_BYTES || writtenBytes > ImportLimits.QUOTA_BYTES - ownedBytes ||
            nextBytes > ImportLimits.QUOTA_BYTES - ownedBytes - writtenBytes) throw ImportException(ImportIssue.QUOTA_EXCEEDED)
        if (availableBytes < ImportLimits.FREE_RESERVE_BYTES || nextBytes > availableBytes - ImportLimits.FREE_RESERVE_BYTES) {
            throw ImportException(ImportIssue.STORAGE_FULL)
        }
    }
}

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun syncImportDirectory(directory: File) {
    val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY or OsConstants.O_DIRECTORY, 0)
    try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
}

private fun importIssue(error: Exception): ImportIssue = when (error) {
    is ImportException -> error.issue
    is SourceReadException -> when (error.issue) {
        SourceReadIssue.BUSY -> ImportIssue.BUSY
        SourceReadIssue.PERMISSION_DENIED -> ImportIssue.PERMISSION_DENIED
        SourceReadIssue.PERMISSION_REVOKED -> ImportIssue.PERMISSION_REVOKED
        SourceReadIssue.SOURCE_CHANGED -> ImportIssue.SOURCE_CHANGED
        SourceReadIssue.NOT_AUDIO -> ImportIssue.NOT_AUDIO
        SourceReadIssue.METADATA_UNREADABLE -> ImportIssue.METADATA_UNREADABLE
        SourceReadIssue.UNSUPPORTED_FORMAT -> ImportIssue.UNSUPPORTED_FORMAT
        SourceReadIssue.STORAGE_FULL -> ImportIssue.STORAGE_FULL
        else -> ImportIssue.SOURCE_UNAVAILABLE
    }
    is LibraryException -> when (error.issue) {
        LibraryIssue.BUSY -> ImportIssue.BUSY
        LibraryIssue.CONFLICT, LibraryIssue.SOURCE_CHANGED -> ImportIssue.CONFLICT
        LibraryIssue.STORAGE_FULL -> ImportIssue.STORAGE_FULL
        else -> ImportIssue.RECOVERY_REQUIRED
    }
    is IllegalArgumentException -> ImportIssue.INVALID_INPUT
    else -> ImportIssue.SOURCE_UNAVAILABLE
}
