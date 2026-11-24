package io.github.onlinemo.meloxwatch.data.transfer

import android.annotation.SuppressLint
import android.os.CancellationSignal
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

fun interface TransferDiskSpace { fun availableBytes(directory: File): Long }

// 上传须保留实际空闲空间；尚未回收的缓存不能扩大分块写入和既有预留的预算。
@SuppressLint("UsableSpace")
private fun physicalTransferAvailableBytes(directory: File): Long = directory.usableSpace

/**
 * Only an isolated noBackup directory is accepted. The caller never passes peer filenames as paths.
 * All physical I/O uses the existing LocalProviderIo; a cancelled waiter does not free ownership.
 */
class TransferUploadStore(
    private val directory: File,
    internal val providerIo: LocalProviderIo,
    private val accounting: TransferQuotaAccounting,
    private val validator: TransferContentValidator,
    private val diskSpace: TransferDiskSpace = TransferDiskSpace(::physicalTransferAvailableBytes),
    private val clock: TransferClock = TransferClock { System.nanoTime() / 1_000_000L },
) {
    private val lock = Any()
    private var opening = false
    private var openingTicket: String? = null
    private var active: TransferUploadStage? = null
    private var retiringStage: TransferUploadStage? = null
    private var failedOpening = false
    private var openingOutcome = TransferUploadPhase.FAILED
    private var openingIssue: TransferIssue? = null
    private val idle = MutableStateFlow(true)
    private val mutableProgress = MutableStateFlow<TransferUploadStatus?>(null)
    private var lastProgressAt = 0L
    val progress: StateFlow<TransferUploadStatus?> = mutableProgress.asStateFlow()

    suspend fun awaitIdle() { idle.first { it } }

    suspend fun begin(binding: TransferOperationBinding, declaration: TransferUploadDeclaration, permit: TransferPermit): TransferUploadStage {
        if (binding.target != permit.target || binding.peer != permit.peer || binding.kind.capability != declaration.kind.capability || permit.capability != declaration.kind.capability) throw TransferException(TransferIssue.WRONG_TARGET)
        permit.checkActive()
        val ticket = UUID.randomUUID().toString()
        synchronized(lock) {
            if (opening || active != null || retiringStage != null) throw TransferException(TransferIssue.BUSY)
            opening = true; openingTicket = ticket; failedOpening = false; idle.value = false
            openingIssue = null
            mutableProgress.value = TransferUploadStatus(permit.sessionId, binding, declaration, TransferUploadPhase.PREPARING, null, null, false, null)
            lastProgressAt = clock.monotonicMs()
        }
        val created = AtomicReference<TransferUploadStage?>()
        try {
            val reservedBytes = declaration.declaredBytes ?: 0L
            val usage = accounting.reserve(ticket, declaration.kind, reservedBytes)
            checkQuota(declaration.kind, reservedBytes, usage)
            return providerIo.read(
                abandoned = { stage -> created.set(stage) },
            ) { signal ->
                signal.throwIfCanceled(); permit.checkActive()
                val root = privateDirectory()
                if (Files.newDirectoryStream(root.toPath()).use { it.iterator().hasNext() }) throw TransferException(TransferIssue.RECOVERY_REQUIRED)
                checkSpace(root, declaration.declaredBytes ?: 0L)
                accounting.requirePhysicalRoom(ticket, declaration.declaredBytes ?: 0L, diskSpace.availableBytes(root))
                val path = root.toPath().resolve(ticket + ".partial")
                val stage = TransferUploadStage(this, path.toFile(), ticket, binding, declaration, permit)
                created.set(stage)
                Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { }
                signal.throwIfCanceled(); permit.checkActive()
                synchronized(lock) { check(opening && active == null); active = stage; opening = false }
                publishStage(stage, TransferUploadPhase.RECEIVING)
                stage
            }
        } catch (error: Exception) {
            val failure = mapTransferIo(error)
            withContext(NonCancellable) {
                // The cancellation handler only sets CancellationSignal. Wait for physical I/O
                // before inspecting its result or returning the global upload/reservation slot.
                if (error is CancellationException || error is android.os.OperationCanceledException) providerIo.awaitIdle()
                val stage = created.get()
                val cancelled = failure is CancellationException
                val issue = if (failure is TransferException) failure.issue else TransferIssue.INTERRUPTED
                synchronized(lock) {
                    failedOpening = true; retiringStage = stage
                    openingOutcome = if (cancelled) TransferUploadPhase.CANCELLED else if (issue == TransferIssue.RECOVERY_REQUIRED) TransferUploadPhase.RECOVERY_REQUIRED else TransferUploadPhase.FAILED
                    openingIssue = issue
                }
                try {
                    if (stage != null) discard(stage, issue, cancelled) else {
                        publishOpening(ticket, TransferUploadPhase.RETIRING, issue)
                        accounting.release(ticket)
                        releaseOpening(ticket)
                    }
                } catch (_: Exception) {
                    publishOpening(ticket, TransferUploadPhase.RECOVERY_REQUIRED, TransferIssue.RECOVERY_REQUIRED)
                    throw TransferException(TransferIssue.RECOVERY_REQUIRED)
                }
            }
            throw failure
        }
    }

    internal suspend fun append(stage: TransferUploadStage, permit: TransferPermit, offset: Long, bytes: ByteArray): Long {
        require(bytes.isNotEmpty() && bytes.size <= TransferLimits.CHUNK_BYTES)
        stage.claimOperation(permit)
        var queued = false
        try {
            val count = stage.actualBytes
            if (offset != count) throw TransferException(TransferIssue.INVALID_OFFSET)
            if (bytes.size.toLong() > stage.declaration.kind.maximumBytes - count) throw TransferException(TransferIssue.QUOTA_EXCEEDED)
            val next = count + bytes.size
            if (stage.declaration.declaredBytes != null && next > stage.declaration.declaredBytes) throw TransferException(TransferIssue.INTEGRITY_FAILED)
            val usage = accounting.reserve(stage.ticket, stage.declaration.kind, maxOf(next, stage.declaration.declaredBytes ?: 0L))
            checkQuota(stage.declaration.kind, maxOf(next, stage.declaration.declaredBytes ?: 0L), usage)
            queued = true
            return providerIo.read(failed = { stage.releaseOperation() }) { signal ->
                try {
                    stage.checkForIo(permit, signal)
                    val root = privateDirectory()
                    checkFile(stage.file)
                    if (fileBytes(stage.file) != count) throw TransferException(TransferIssue.INTEGRITY_FAILED)
                    checkSpace(root, bytes.size.toLong())
                    accounting.requirePhysicalRoom(stage.ticket, bytes.size.toLong(), diskSpace.availableBytes(root))
                    Files.newOutputStream(stage.file.toPath(), StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS).use { output ->
                        stage.checkForIo(permit, signal)
                        output.write(bytes)
                    }
                    if (fileBytes(stage.file) != next) throw TransferException(TransferIssue.INTEGRITY_FAILED)
                    stage.actualBytes = next
                    accounting.recordPhysicalBytes(stage.ticket, next)
                    stage.checkForIo(permit, signal)
                    publishStage(stage, TransferUploadPhase.RECEIVING, sampled = true)
                    next
                } finally { stage.releaseOperation() }
            }
        } catch (error: Exception) {
            if (!queued) stage.releaseOperation()
            throw mapTransferIo(error)
        }
    }

    internal suspend fun verify(stage: TransferUploadStage, permit: TransferPermit): TransferVerifiedUpload {
        stage.claimOperation(permit)
        publishStage(stage, TransferUploadPhase.VERIFYING)
        try {
            return providerIo.read(failed = { stage.releaseOperation() }) { signal ->
                try {
                    stage.checkForIo(permit, signal)
                    checkFile(stage.file)
                    if (stage.actualBytes == 0L || stage.declaration.declaredBytes != null && stage.actualBytes != stage.declaration.declaredBytes || fileBytes(stage.file) != stage.actualBytes) throw TransferException(TransferIssue.INTEGRITY_FAILED)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var count = 0L
                    val buffer = ByteArray(TransferLimits.CHUNK_BYTES)
                    Files.newInputStream(stage.file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
                        while (true) {
                            stage.checkForIo(permit, signal)
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) throw TransferException(TransferIssue.INTERRUPTED)
                            if (read.toLong() > stage.declaration.kind.maximumBytes - count) throw TransferException(TransferIssue.QUOTA_EXCEEDED)
                            count += read
                            digest.update(buffer, 0, read)
                        }
                    }
                    if (count != stage.actualBytes || stage.declaration.sha256 != TransferHash.fromWire(digest.digest())) throw TransferException(TransferIssue.INTEGRITY_FAILED)
                    validator.validate(stage.file, stage.declaration, signal)
                    stage.checkForIo(permit, signal)
                    stage.verified = true
                    TransferVerifiedUpload(stage, permit)
                } finally { stage.releaseOperation() }
            }
        } catch (error: Exception) {
            val failure = mapTransferIo(error)
            if (!stage.retiring.get()) publishStage(stage, TransferUploadPhase.RECEIVING, if (failure is TransferException) failure.issue else TransferIssue.INTERRUPTED)
            throw failure
        }
    }

    internal suspend fun discard(stage: TransferUploadStage, issue: TransferIssue = TransferIssue.INTERRUPTED, cancelled: Boolean = true) = withContext(NonCancellable) {
        // One retirement owner spans the physical cleanup and accounting receipt. Late joiners
        // recheck after that receipt, so they cannot reinstall an already retired UI/slot owner.
        stage.retirementLock.withLock {
            if (stage.physicallyRetired) return@withLock
            val backendRetirement = synchronized(stage.commitLock) {
                if (stage.retiring.compareAndSet(false, true)) {
                    stage.retirementIssue = issue
                    stage.retirementPhase = when {
                        stage.phase == TransferUploadPhase.COMMITTING && stage.receipt == null || issue == TransferIssue.RECOVERY_REQUIRED -> TransferUploadPhase.RECOVERY_REQUIRED
                        cancelled -> TransferUploadPhase.CANCELLED
                        else -> TransferUploadPhase.FAILED
                    }
                }
                stage.backendRetirement
            }
            synchronized(lock) { retiringStage = stage }
            publishStage(stage, TransferUploadPhase.RETIRING, stage.retirementIssue)
            // awaitIdle is an acknowledgement of physical completion, not cancelled coroutine completion.
            try {
                // An idle disk worker does not acknowledge an in-flight Room publication.
                backendRetirement?.await()
                repeat(3) {
                    providerIo.awaitIdle()
                    try {
                        providerIo.read { stage.deleteFileOnPhysicalWorker() }
                        accounting.release(stage.ticket)
                        releaseStage(stage)
                        return@withLock
                    } catch (error: SourceReadException) {
                        if (error.issue != SourceReadIssue.BUSY) throw mapTransferIo(error)
                        yield()
                    }
                }
            } catch (_: Exception) {
                publishStage(stage, TransferUploadPhase.RECOVERY_REQUIRED, TransferIssue.RECOVERY_REQUIRED)
                throw TransferException(TransferIssue.RECOVERY_REQUIRED)
            }
            // Keep the slot occupied and retain the partial for explicit retry/recovery.
            publishStage(stage, TransferUploadPhase.RECOVERY_REQUIRED, TransferIssue.RECOVERY_REQUIRED)
            throw TransferException(TransferIssue.RECOVERY_REQUIRED)
        }
    }

    /** Root invokes only after startup recovery excludes a live session; bounded and never recursive. */
    suspend fun recoverAbandoned(limit: Int = TransferLimits.PAGE_SIZE): Int {
        require(limit in 1..TransferLimits.PAGE_SIZE)
        synchronized(lock) {
            if (opening || active != null || retiringStage != null) throw TransferException(TransferIssue.BUSY)
            opening = true; idle.value = false
        }
        try { return providerIo.read { signal ->
            val root = privateDirectory()
            var removed = 0
            Files.newDirectoryStream(root.toPath()).use { entries ->
                for (entry in entries) {
                    signal.throwIfCanceled()
                    if (removed == limit) break
                    val name = entry.fileName.toString()
                    if (!name.endsWith(".partial")) throw TransferException(TransferIssue.RECOVERY_REQUIRED)
                    try { requireTransferUuid(name.removeSuffix(".partial")) }
                    catch (_: IllegalArgumentException) { throw TransferException(TransferIssue.RECOVERY_REQUIRED) }
                    if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) throw TransferException(TransferIssue.RECOVERY_REQUIRED)
                    Files.deleteIfExists(entry)
                    removed++
                }
            }
            removed
        } } catch (error: Exception) { throw mapTransferIo(error) } finally {
            withContext(NonCancellable) {
                providerIo.awaitIdle()
                synchronized(lock) { opening = false; idle.value = active == null }
            }
        }
    }

    /** Retry a previously failed retirement; callers must not report it physically complete yet. */
    suspend fun retireAbandoned() {
        val stage = synchronized(lock) { retiringStage?.takeIf { it.retiring.get() } }
        if (stage != null) discard(stage) else {
            val ticket = synchronized(lock) { openingTicket?.takeIf { active == null && failedOpening } }
            if (ticket != null) {
                providerIo.awaitIdle()
                accounting.release(ticket)
                releaseOpening(ticket)
            }
        }
    }

    internal fun checkFile(file: File) {
        val root = privateDirectory().toPath()
        val path = file.toPath().toAbsolutePath().normalize()
        if (path.parent != root || !path.fileName.toString().endsWith(".partial")) throw TransferException(TransferIssue.RECOVERY_REQUIRED)
        requireTransferUuid(path.fileName.toString().removeSuffix(".partial"))
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!attributes.isRegularFile || attributes.isSymbolicLink) throw TransferException(TransferIssue.INTEGRITY_FAILED)
    }

    private fun privateDirectory(): File {
        val requested = directory.toPath().toAbsolutePath().normalize()
        // Context's trusted noBackup parent may use an OS alias. Resolve it on this worker;
        // the transfer child itself must never be a link into another directory.
        if (Files.isSymbolicLink(requested)) throw TransferException(TransferIssue.RECOVERY_REQUIRED)
        val parent = requested.parent ?: throw TransferException(TransferIssue.INVALID_INPUT)
        Files.createDirectories(parent)
        val path = parent.toRealPath().resolve(requested.fileName)
        if (Files.isSymbolicLink(path)) throw TransferException(TransferIssue.RECOVERY_REQUIRED)
        Files.createDirectories(path)
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw TransferException(TransferIssue.STORAGE_UNAVAILABLE)
        val real = path.toRealPath()
        if (real != path) throw TransferException(TransferIssue.RECOVERY_REQUIRED)
        return real.toFile()
    }

    private fun fileBytes(file: File): Long = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).size()

    private fun checkSpace(root: File, additional: Long) {
        val available = diskSpace.availableBytes(root)
        if (additional < 0L || available < TransferLimits.FREE_RESERVE_BYTES || additional > available - TransferLimits.FREE_RESERVE_BYTES) throw TransferException(TransferIssue.STORAGE_FULL)
    }

    private fun checkQuota(kind: TransferUploadKind, ownReservation: Long, usage: TransferQuotaUsage) {
        val owned = if (kind == TransferUploadKind.AUDIO) usage.ownedAudioBytes else usage.ownedThemeBytes
        val other = if (kind == TransferUploadKind.AUDIO) usage.otherReservedAudioBytes else usage.otherReservedThemeBytes
        val maximum = if (kind == TransferUploadKind.AUDIO) TransferLimits.AUDIO_BYTES else TransferLimits.THEME_STORED_BYTES
        if (owned > maximum || other > maximum - owned || ownReservation > maximum - owned - other) throw TransferException(TransferIssue.QUOTA_EXCEEDED)
    }

    internal fun markCommitting(stage: TransferUploadStage) {
        if (!stage.verified || stage.retiring.get()) throw TransferException(TransferIssue.CLOSED)
        publishStage(stage, TransferUploadPhase.COMMITTING)
    }

    /** A real result can arrive after revocation; recording it must not pretend the commit was undone. */
    internal fun recordCommitted(stage: TransferUploadStage, receipt: TransferReceipt, replay: Boolean = false) {
        validateUploadReceipt(stage.binding, stage.declaration, receipt)
        if (!replay && (!stage.verified || receipt.actualBytes != stage.actualBytes)) throw TransferException(TransferIssue.CONFLICT)
        synchronized(lock) {
            val previous = stage.receipt
            if (previous != null && (previous.resultId != receipt.resultId || previous.revision != receipt.revision || previous.actualBytes != receipt.actualBytes)) throw TransferException(TransferIssue.CONFLICT)
            stage.receipt = receipt; stage.replay = replay
            val phase = when {
                stage.physicallyRetired -> TransferUploadPhase.COMPLETED
                stage.phase == TransferUploadPhase.RECOVERY_REQUIRED -> TransferUploadPhase.RECOVERY_REQUIRED
                stage.retiring.get() -> TransferUploadPhase.RETIRING
                else -> TransferUploadPhase.COMMITTING
            }
            publishStage(stage, phase, if (phase == TransferUploadPhase.RECOVERY_REQUIRED) TransferIssue.RECOVERY_REQUIRED else null)
        }
    }

    internal fun recordReplay(permit: TransferPermit, declaration: TransferUploadDeclaration, receipt: TransferReceipt) {
        permit.checkActive()
        validateUploadReceipt(receipt.binding, declaration, receipt)
        synchronized(lock) {
            // Another authorized connection's replay cannot replace the active upload's UI state.
            if (!opening && active == null && retiringStage == null) {
                mutableProgress.value = TransferUploadStatus(permit.sessionId, receipt.binding, declaration, TransferUploadPhase.COMPLETED, receipt.actualBytes, receipt, true, null)
                lastProgressAt = clock.monotonicMs()
            }
        }
    }

    private fun validateUploadReceipt(binding: TransferOperationBinding, declaration: TransferUploadDeclaration, receipt: TransferReceipt) {
        val kind = if (declaration.kind == TransferUploadKind.AUDIO) TransferOperationKind.AUDIO else TransferOperationKind.THEME
        if (!receipt.binding.sameOperation(binding) || receipt.binding.kind != kind || declaration.declaredBytes != null && declaration.declaredBytes != receipt.actualBytes) throw TransferException(TransferIssue.CONFLICT)
    }

    private fun publishStage(stage: TransferUploadStage, phase: TransferUploadPhase, issue: TransferIssue? = null, sampled: Boolean = false) = synchronized(lock) {
        if (sampled && stage.retiring.get()) return@synchronized
        val current = mutableProgress.value ?: return@synchronized
        if (current.sessionId != stage.sessionId || !current.binding.sameOperation(stage.binding)) return@synchronized
        val now = clock.monotonicMs()
        if (sampled && current.phase == phase && now - lastProgressAt < 1000L) return@synchronized
        stage.phase = phase
        mutableProgress.value = TransferUploadStatus(stage.sessionId, stage.binding, stage.declaration, phase, stage.receipt?.actualBytes ?: stage.actualBytes, stage.receipt, stage.replay, issue)
        lastProgressAt = now
    }

    private fun publishOpening(ticket: String, phase: TransferUploadPhase, issue: TransferIssue?) = synchronized(lock) {
        if (openingTicket != ticket || active != null) return@synchronized
        val current = mutableProgress.value ?: return@synchronized
        mutableProgress.value = TransferUploadStatus(current.sessionId, current.binding, current.declaration, phase, current.receivedBytes, null, false, issue)
        lastProgressAt = clock.monotonicMs()
    }

    private fun releaseOpening(ticket: String) = synchronized(lock) {
        if (openingTicket == ticket) {
            publishOpening(ticket, openingOutcome, openingIssue)
            openingTicket = null; opening = false; failedOpening = false; retiringStage = null
            if (active == null) idle.value = true
        }
    }
    private fun releaseStage(stage: TransferUploadStage) = synchronized(lock) {
        if (active === stage || openingTicket == stage.ticket) {
            stage.physicallyRetired = true
            val phase = if (stage.receipt != null) TransferUploadPhase.COMPLETED else stage.retirementPhase
            val issue = if (phase == TransferUploadPhase.COMPLETED) null else if (phase == TransferUploadPhase.RECOVERY_REQUIRED) TransferIssue.RECOVERY_REQUIRED else stage.retirementIssue
            publishStage(stage, phase, issue)
            active = null; opening = false; openingTicket = null; retiringStage = null; failedOpening = false; idle.value = true
        }
    }
}

class TransferUploadStage internal constructor(
    private val store: TransferUploadStore,
    internal val file: File,
    val ticket: String,
    val binding: TransferOperationBinding,
    val declaration: TransferUploadDeclaration,
    private val initialPermit: TransferPermit,
) {
    internal val retiring = AtomicBoolean(false)
    internal val retirementLock = Mutex()
    internal val commitLock = Any()
    internal var backendRetirement: CompletableDeferred<Unit>? = null
    private val operation = AtomicBoolean(false)
    @Volatile var actualBytes: Long = 0L
        internal set
    @Volatile internal var verified = false
    @Volatile internal var phase = TransferUploadPhase.PREPARING
    @Volatile internal var retirementPhase = TransferUploadPhase.CANCELLED
    @Volatile internal var retirementIssue: TransferIssue? = null
    @Volatile internal var receipt: TransferReceipt? = null
    @Volatile internal var replay = false
    @Volatile internal var physicallyRetired = false
    internal val sessionId: String get() = initialPermit.sessionId

    suspend fun append(permit: TransferPermit, offset: Long, bytes: ByteArray): Long = store.append(this, permit, offset, bytes.copyOf())
    suspend fun verify(permit: TransferPermit): TransferVerifiedUpload = store.verify(this, permit)
    suspend fun discard() { store.discard(this) }
    internal suspend fun discard(issue: TransferIssue, cancelled: Boolean) { store.discard(this, issue, cancelled) }
    internal fun markCommitting() { store.markCommitting(this) }
    internal fun recordCommitted(receipt: TransferReceipt, replay: Boolean = false) { store.recordCommitted(this, receipt, replay) }

    internal fun claimOperation(permit: TransferPermit) {
        if (!initialPermit.sameGrant(permit)) throw TransferException(TransferIssue.WRONG_TARGET)
        permit.checkActive()
        if (retiring.get() || verified) throw TransferException(TransferIssue.CLOSED)
        if (!operation.compareAndSet(false, true)) throw TransferException(TransferIssue.BUSY)
    }
    internal fun releaseOperation() { operation.set(false) }
    internal fun checkForIo(permit: TransferPermit, signal: CancellationSignal) {
        signal.throwIfCanceled()
        if (retiring.get()) throw TransferException(TransferIssue.CLOSED)
        if (!initialPermit.sameGrant(permit)) throw TransferException(TransferIssue.WRONG_TARGET)
        permit.checkActive()
    }
    internal fun deleteFileOnPhysicalWorker() { Files.deleteIfExists(file.toPath()) }
    internal fun providerIo(): LocalProviderIo = store.providerIo
    internal fun validatePrivatePath() { store.checkFile(file) }
    override fun toString(): String = "<private-upload-stage>"
}

/** The private path is visible only inside a bounded synchronous physical-worker callback. */
class TransferVerifiedUpload internal constructor(private val stage: TransferUploadStage, private val permit: TransferPermit) {
    val binding: TransferOperationBinding get() = stage.binding
    val ticket: String get() = stage.ticket
    val declaration: TransferUploadDeclaration get() = stage.declaration
    val actualBytes: Long get() = stage.actualBytes
    val sha256: TransferHash get() = stage.declaration.sha256
    internal fun sharesPhysicalWorker(io: LocalProviderIo): Boolean = stage.providerIo() === io

    internal fun beginBackendCommit(): TransferUploadCommitOwner = synchronized(stage.commitLock) {
        if (!stage.verified) throw TransferException(TransferIssue.CLOSED)
        stage.checkForIo(permit, CancellationSignal())
        if (stage.backendRetirement != null) throw TransferException(TransferIssue.BUSY)
        val completed = CompletableDeferred<Unit>()
        stage.backendRetirement = completed
        TransferUploadCommitOwner(stage, permit, completed)
    }

    suspend fun <T> readOnPhysicalWorker(action: (File, CancellationSignal) -> T): T {
        if (!stage.verified || stage.retiring.get()) throw TransferException(TransferIssue.CLOSED)
        try {
            return stage.providerIo().read { signal ->
                stage.checkForIo(permit, signal)
                stage.validatePrivatePath()
                action(stage.file, signal).also { stage.checkForIo(permit, signal) }
            }
        } catch (error: Exception) { throw mapTransferIo(error) }
    }

    /**
     * Integration may call this only after the real durable commit/receipt is published.
     * It preserves a known result if its waiter was cancelled later. No file/Room/network I/O.
     */
    fun recordCommitted(receipt: TransferReceipt) {
        stage.recordCommitted(receipt)
    }
    override fun toString(): String = "<verified-private-upload>"
}

/** One accepted backend operation; close acknowledges its durable/error and physical cleanup outcome. */
internal class TransferUploadCommitOwner(
    private val stage: TransferUploadStage,
    private val initialPermit: TransferPermit,
    private val completed: CompletableDeferred<Unit>,
) {
    fun checkActive() = synchronized(stage.commitLock) {
        if (completed.isCompleted) throw TransferException(TransferIssue.CLOSED)
        stage.checkForIo(initialPermit, CancellationSignal())
    }

    /** Executed inside the real Room transaction, linearized with cancellation of this upload. */
    fun claimCommit(permit: TransferPermit, binding: TransferOperationBinding): TransferCommitClaim = synchronized(stage.commitLock) {
        if (completed.isCompleted || !initialPermit.sameGrant(permit) || !stage.binding.sameOperation(binding)) throw TransferException(TransferIssue.CONFLICT)
        stage.checkForIo(permit, CancellationSignal())
        permit.claimCommit(binding)
    }

    fun complete() { completed.complete(Unit) }
}

internal fun mapTransferIo(error: Exception): Exception = when (error) {
    is TransferException -> error
    is CancellationException, is android.os.OperationCanceledException -> CancellationException("Transfer cancelled")
    is SourceReadException -> TransferException(when (error.issue) {
        SourceReadIssue.BUSY -> TransferIssue.BUSY
        SourceReadIssue.INVALID_SOURCE -> TransferIssue.INVALID_INPUT
        SourceReadIssue.SOURCE_CHANGED -> TransferIssue.INTEGRITY_FAILED
        SourceReadIssue.NOT_AUDIO -> TransferIssue.NOT_AUDIO
        SourceReadIssue.METADATA_UNREADABLE, SourceReadIssue.UNSUPPORTED_FORMAT -> TransferIssue.UNSUPPORTED_TYPE
        SourceReadIssue.STORAGE_FULL -> TransferIssue.STORAGE_FULL
        else -> TransferIssue.STORAGE_UNAVAILABLE
    })
    is IOException, is SecurityException -> TransferException(TransferIssue.STORAGE_UNAVAILABLE)
    else -> TransferException(TransferIssue.INVALID_INPUT)
}
