package io.github.onlinemo.meloxwatch.companion.data

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.OpenableColumns
import androidx.core.graphics.createBitmap
import io.github.onlinemo.meloxwatch.data.transfer.TransferException
import io.github.onlinemo.meloxwatch.data.transfer.TransferHash
import io.github.onlinemo.meloxwatch.data.transfer.TransferIssue
import io.github.onlinemo.meloxwatch.data.transfer.TransferUploadKind
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.FilterInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class CompanionThemePreview(val draft: CompanionThemeDraft, icons: Map<CompanionIconRole, Bitmap>) {
    val icons: Map<CompanionIconRole, Bitmap> = Collections.unmodifiableMap(icons.toMap())
    init { require(icons.keys == draft.icons.map { it.role }.toSet() && icons.values.all { it.width <= CompanionLimits.ICON_EDGE && it.height <= CompanionLimits.ICON_EDGE }) }
    override fun toString(): String = "<bounded-local-theme-preview>"
}

/** Only a system-granted selected content URI is read. Provider work has one physical owner. */
class CompanionDocuments(
    private val resolver: ContentResolver,
    val store: CompanionLocalStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    private val retirementWaitMs: Long = CompanionLimits.RETIREMENT_WAIT_MS,
    private val deadlineFactory: () -> ScheduledThreadPoolExecutor = {
        ScheduledThreadPoolExecutor(1) { work ->
            Thread(work, "melox-phone-document-close").apply { isDaemon = true }
        }
    },
) {
    init { require(retirementWaitMs > 0L) }
    private val lock = Any()
    private val workers = CoroutineScope(SupervisorJob() + dispatcher)
    private val closeDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val busy = MutableStateFlow(false)
    val working: StateFlow<Boolean> = busy.asStateFlow()
    private val mutableIssue = MutableStateFlow<TransferIssue?>(null)
    val issue: StateFlow<TransferIssue?> = mutableIssue.asStateFlow()
    private var active: Operation? = null
    private var retirement: Operation? = null

    internal class Operation {
        val signal = CancellationSignal()
        private val resources = ArrayList<OwnedClose>()
        private val lock = Any()
        @Volatile var cancelled = false
        var retiring = false
        var cancellation: CompletableDeferred<Unit>? = null
        @Volatile var worker: Job? = null
        @Volatile var cancellationWorker: Job? = null
        var workerExitReported = false
        private var deadline: DocumentDeadline? = null
        fun check() {
            signal.throwIfCanceled()
            if (cancelled) throw CancellationException("Local operation cancelled")
        }
        fun <T : Closeable> own(value: T): T = synchronized(lock) {
            // One output, nine sequential icon inputs and one final hash input fit this fixed registry.
            kotlin.check(resources.size < 16)
            resources += OwnedClose(value)
            check()
            value
        }
        fun close(value: Closeable) { synchronized(lock) { resources.single { it.resource === value } }.close() }
        fun workersCompleted(): Boolean = worker?.isCompleted != false && cancellationWorker?.isCompleted != false &&
            cancellation?.isCompleted != false
        fun inherit(previous: Operation) {
            kotlin.check(previous.workersCompleted())
            val retained = synchronized(previous.lock) { previous.resources.toList() to previous.deadline }
            synchronized(lock) {
                kotlin.check(resources.isEmpty())
                // Reuse the original close wrappers; repeated recovery cannot grow an owner chain.
                resources.addAll(retained.first)
                deadline = retained.second
                check()
                // 已完成的 lazy job 仍可能引用更早的退休操作；交接时释放已完成引用，避免重试累积链。
                previous.worker = null
                previous.cancellationWorker = null
            }
        }
        fun startDeadline(factory: () -> ScheduledThreadPoolExecutor, waitMs: Long, action: () -> Unit) {
            // 旧计时器未确认退出时，恢复不能创建第二个计时器。
            retireDeadline()
            val next = DocumentDeadline(waitMs)
            synchronized(lock) { deadline = next }
            next.start(factory, action)
        }
        fun retireDeadline() {
            val owned = synchronized(lock) { deadline } ?: return
            owned.close()
            synchronized(lock) { if (deadline === owned) deadline = null }
        }
        fun retainsResources(): Boolean = synchronized(lock) {
            resources.isNotEmpty() || deadline != null || cancellation?.isCompleted == false ||
                cancellationWorker?.isCompleted == false
        }
        fun closeAll() {
            val snapshot = synchronized(lock) { resources.toList().asReversed() }
            var failed = false
            snapshot.forEach { try { it.close() } catch (_: Exception) { failed = true } }
            if (failed) throw TransferException(TransferIssue.RECOVERY_REQUIRED)
        }
        private class OwnedClose(val resource: Closeable) {
            private var closed = false
            @Synchronized fun close() {
                if (!closed) {
                    resource.close()
                    closed = true
                }
            }
        }
    }

    private class DocumentDeadline(private val waitMs: Long) : Closeable {
        private var executor: ScheduledThreadPoolExecutor? = null
        private var future: ScheduledFuture<*>? = null
        private var closed = false

        @Synchronized fun start(factory: () -> ScheduledThreadPoolExecutor, action: () -> Unit) {
            check(!closed && executor == null)
            val created = factory()
            // 先记录实际 executor，策略配置或线程/调度建立失败时仍保留原所有者。
            executor = created
            created.removeOnCancelPolicy = true
            created.setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
            created.setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
            future = created.schedule(Runnable { action() }, CompanionLimits.LOCAL_IO_MS, TimeUnit.MILLISECONDS)
        }

        @Synchronized override fun close() {
            if (closed) return
            future?.cancel(false)
            executor?.let { owned ->
                owned.shutdown()
                if (!owned.awaitTermination(waitMs, TimeUnit.MILLISECONDS)) {
                    throw TransferException(TransferIssue.RECOVERY_REQUIRED)
                }
            }
            closed = true
        }
    }

    /** Cancellation dispatches closes off Main. Occupancy ends only after both close paths retire. */
    internal suspend fun <T> onWorker(
        allowRecovery: Boolean = false, retirementOwner: Operation? = null, action: (Operation) -> T,
    ): T = suspendCancellableCoroutine { continuation ->
        val operation = Operation()
        val accepted = synchronized(lock) {
            if (
                active != null || retirement !== retirementOwner ||
                retirementOwner?.workersCompleted() == false ||
                !allowRecovery && (mutableIssue.value == TransferIssue.RECOVERY_REQUIRED || store.retirementRequired)
            ) {
                false
            }
            else {
                // Transfer retained wrappers before cancellation can retire an empty new registry.
                if (retirementOwner != null) operation.inherit(retirementOwner)
                active = operation
                busy.value = true
                true
            }
        }
        if (!accepted) {
            continuation.resumeWithException(TransferException(TransferIssue.BUSY))
            return@suspendCancellableCoroutine
        }
        var worker: Job? = null
        try {
            val launched = workers.async(start = CoroutineStart.LAZY) {
                var outcome: Result<T>? = null
                try {
                    operation.check()
                    operation.startDeadline(deadlineFactory, retirementWaitMs) {
                        val expired = synchronized(lock) {
                            if (active !== operation) false else {
                                mutableIssue.value = TransferIssue.RECOVERY_REQUIRED
                                true
                            }
                        }
                        if (expired) {
                            requestCancel(operation)
                            // The detached worker retains ownership even if a Binder/provider ignores cancel.
                            continuation.cancel(CancellationException("Local operation deadline expired"))
                        }
                    }
                    operation.check()
                    outcome = Result.success(action(operation))
                    operation.check()
                } catch (error: Exception) {
                    val mapped = map(error)
                    if (mapped is TransferException && mapped.issue == TransferIssue.RECOVERY_REQUIRED ||
                        mapped.suppressed.any { it is TransferException && it.issue == TransferIssue.RECOVERY_REQUIRED }) mutableIssue.value = TransferIssue.RECOVERY_REQUIRED
                    outcome = Result.failure(mapped)
                } catch (error: OutOfMemoryError) {
                    val mapped = TransferException(TransferIssue.STORAGE_UNAVAILABLE)
                    if (error.suppressed.any { it is TransferException && it.issue == TransferIssue.RECOVERY_REQUIRED }) {
                        mapped.addSuppressed(TransferException(TransferIssue.RECOVERY_REQUIRED))
                        mutableIssue.value = TransferIssue.RECOVERY_REQUIRED
                    }
                    outcome = Result.failure(mapped)
                } finally {
                    withContext(NonCancellable) {
                        val cancellation = synchronized(lock) {
                            operation.retiring = true
                            operation.cancellation
                        }
                        var failed = false
                        try { operation.closeAll() } catch (_: Exception) { failed = true }
                        try { cancellation?.await() } catch (_: Exception) { failed = true }
                        // 建立阶段抛错也不转移计时器所有权；等 provider/取消收尾后再确认计时线程退出。
                        try { operation.retireDeadline() }
                        catch (_: Exception) { failed = true }
                        catch (_: OutOfMemoryError) { failed = true }
                        synchronized(lock) {
                            if (active === operation) active = null
                            if (store.retirementRequired) mutableIssue.value = TransferIssue.RECOVERY_REQUIRED
                            if (failed) {
                                retirement = operation
                                mutableIssue.value = TransferIssue.RECOVERY_REQUIRED
                                val cleanup = TransferException(TransferIssue.RECOVERY_REQUIRED)
                                val primary = outcome?.exceptionOrNull()
                                if (primary != null) primary.addSuppressed(cleanup) else outcome = Result.failure(cleanup)
                            }
                            else {
                                if (retirement === retirementOwner) retirement = null
                                busy.value = false
                            }
                        }
                    }
                }
                outcome?.fold(
                    onSuccess = { value -> if (continuation.isActive) continuation.resume(value) { _, _, _ -> } },
                    onFailure = { error -> if (continuation.isActive) continuation.resumeWithException(error) },
                )
            }
            worker = launched
            operation.worker = launched
            launched.invokeOnCompletion { failure ->
                if (failure != null) unexpectedWorkerExit(operation, retirementOwner, continuation)
            }
            continuation.invokeOnCancellation { requestCancel(operation) }
            launched.start()
        } catch (_: Exception) {
            failedWorkerStart(operation, retirementOwner, continuation, worker)
        } catch (_: OutOfMemoryError) {
            failedWorkerStart(operation, retirementOwner, continuation, worker)
        }
    }

    private fun failedWorkerStart(
        operation: Operation, retirementOwner: Operation?, continuation: CancellableContinuation<*>, worker: Job?,
    ) {
        worker?.cancel()
        if (worker == null || worker.isCompleted) {
            unexpectedWorkerExit(operation, retirementOwner, continuation)
        } else {
            // start 失败可能与已派发的真实 body 竞争；原 job 和资源必须继续占用。
            synchronized(lock) { if (active === operation) mutableIssue.value = TransferIssue.RECOVERY_REQUIRED }
            try { requestCancel(operation) }
            catch (_: Exception) { /* 仍在执行的原操作保留资源和恢复状态。 */ }
            catch (_: OutOfMemoryError) { /* 下方仍向调用者返回固定的恢复错误码。 */ }
            if (continuation.isActive) continuation.resumeWithException(TransferException(TransferIssue.RECOVERY_REQUIRED))
        }
    }

    private fun unexpectedWorkerExit(
        operation: Operation, retirementOwner: Operation?, continuation: CancellableContinuation<*>,
    ) {
        val failure = synchronized(lock) {
            if (operation.workerExitReported) return
            operation.workerExitReported = true
            if (active === operation) {
                operation.retiring = true
                active = null
                if (operation.retainsResources()) {
                    retirement = operation
                    mutableIssue.value = TransferIssue.RECOVERY_REQUIRED
                    TransferException(TransferIssue.RECOVERY_REQUIRED)
                } else {
                    if (retirement === retirementOwner) retirement = null
                    busy.value = false
                    if (store.retirementRequired) mutableIssue.value = TransferIssue.RECOVERY_REQUIRED
                    TransferException(TransferIssue.STORAGE_UNAVAILABLE)
                }
            } else {
                // 建立阶段的 Error 可先执行 finally 释放占用，再异常完成 job。
                // 此时仍要结束原调用者等待，且不能改动接替操作的状态。
                TransferException(if (retirement === operation || active == null && store.retirementRequired) {
                    TransferIssue.RECOVERY_REQUIRED
                } else TransferIssue.STORAGE_UNAVAILABLE)
            }
        }
        if (continuation.isActive) continuation.resumeWithException(failure)
    }

    fun cancel() { synchronized(lock) { active }?.let(::requestCancel) }
    internal fun markRecoveryRequired() { mutableIssue.value = TransferIssue.RECOVERY_REQUIRED }
    suspend fun awaitStopped() {
        val state = try {
            withTimeout(retirementWaitMs) {
                combine(busy, mutableIssue) { occupied, issue -> occupied to issue }.first { !it.first || it.second == TransferIssue.RECOVERY_REQUIRED }
            }
        } catch (_: TimeoutCancellationException) {
            mutableIssue.value = TransferIssue.RECOVERY_REQUIRED
            throw TransferException(TransferIssue.RECOVERY_REQUIRED)
        }
        if (state.first || state.second == TransferIssue.RECOVERY_REQUIRED) throw TransferException(TransferIssue.RECOVERY_REQUIRED)
    }

    suspend fun retryRetirement() {
        val pending = synchronized(lock) {
            if (active != null) throw TransferException(TransferIssue.BUSY)
            if (retirement?.workersCompleted() == false) {
                throw TransferException(TransferIssue.BUSY)
            }
            retirement
        }
        try {
            withTimeout(retirementWaitMs) {
                // onWorker remains the physical owner when this control wait times out.
                onWorker(allowRecovery = true, retirementOwner = pending) { operation ->
                    operation.check()
                    operation.closeAll()
                    operation.check()
                    store.retryTemporaryRetirement()
                    operation.check()
                    store.retryRead()
                    operation.check()
                    if (store.read().upload?.phase == CompanionUploadPhase.RETIRING_COPY) retireCopy(operation)
                    store.retireUnreferencedCopies(operation::check)
                }
            }
            synchronized(lock) { mutableIssue.value = null }
        } catch (_: TimeoutCancellationException) {
            mutableIssue.value = TransferIssue.RECOVERY_REQUIRED
            throw TransferException(TransferIssue.RECOVERY_REQUIRED)
        } catch (error: Exception) {
            mutableIssue.value = TransferIssue.RECOVERY_REQUIRED
            throw error
        }
    }

    suspend fun initialize(): CompanionLocalState = onWorker(allowRecovery = true) { operation ->
        store.read()
        if (store.read().upload?.phase == CompanionUploadPhase.RETIRING_COPY) retireCopy(operation)
        store.retireUnreferencedCopies(operation::check)
        // Persist the initial theme identity and any recovered uncertain phases before UI use.
        store.update { it }
    }

    suspend fun update(change: (CompanionLocalState) -> CompanionLocalState): CompanionLocalState = onWorker { operation ->
        operation.check()
        store.update(change)
    }

    private fun requestCancel(operation: Operation) {
        val completion = synchronized(lock) {
            if (active !== operation || operation.retiring || operation.cancellation != null) return
            operation.cancelled = true
            CompletableDeferred<Unit>().also { operation.cancellation = it }
        }
        var worker: Job? = null
        try {
            val launched = workers.async(NonCancellable + closeDispatcher, start = CoroutineStart.LAZY) {
                var failed = false
                try { operation.signal.cancel() } catch (_: Exception) { failed = true }
                try { operation.closeAll() } catch (_: Exception) { failed = true }
                if (failed) {
                    // Publish before waiting for a possibly blocked provider body to reach finally.
                    synchronized(lock) { if (active === operation || retirement === operation) mutableIssue.value = TransferIssue.RECOVERY_REQUIRED }
                    completion.completeExceptionally(TransferException(TransferIssue.RECOVERY_REQUIRED))
                } else completion.complete(Unit)
            }
            worker = launched
            operation.cancellationWorker = launched
            launched.invokeOnCompletion { failure ->
                if (failure != null && !completion.isCompleted) cancellationFailed(operation, completion)
            }
            launched.start()
        } catch (_: Exception) {
            worker?.cancel()
            if (worker == null || worker.isCompleted) cancellationFailed(operation, completion)
            else synchronized(lock) { if (active === operation) mutableIssue.value = TransferIssue.RECOVERY_REQUIRED }
        } catch (_: OutOfMemoryError) {
            worker?.cancel()
            if (worker == null || worker.isCompleted) cancellationFailed(operation, completion)
            else synchronized(lock) { if (active === operation) mutableIssue.value = TransferIssue.RECOVERY_REQUIRED }
        }
    }

    private fun cancellationFailed(operation: Operation, completion: CompletableDeferred<Unit>) {
        synchronized(lock) {
            if (active === operation || retirement === operation) mutableIssue.value = TransferIssue.RECOVERY_REQUIRED
        }
        completion.completeExceptionally(TransferException(TransferIssue.RECOVERY_REQUIRED))
    }

    suspend fun prepareAudio(uri: Uri): CompanionUploadDraft = onWorker { operation ->
        require(uri.scheme == ContentResolver.SCHEME_CONTENT)
        val previous = store.read()
        if (previous.upload != null) throw TransferException(TransferIssue.BUSY)
        store.retireUnreferencedCopies(operation::check)
        val metadata = selectedMetadata(uri, operation)
        val type = audioType(resolver.getType(uri), metadata.first)
        val id = UUID.randomUUID().toString()
        val partial = store.audioFile(id, partial = true)
        val final = store.audioFile(id)
        val outputDirectory = partial.parentFile ?: throw TransferException(TransferIssue.STORAGE_UNAVAILABLE)
        val otherBytes = store.audioBytes()
        val expected = metadata.second
        if (expected != null) budget(outputDirectory, expected, otherBytes)
        var published = false
        var created = false
        var moved = false
        var primary: Throwable? = null
        try {
            if (!partial.createNewFile()) throw TransferException(TransferIssue.STORAGE_UNAVAILABLE)
            created = true
            val descriptor = operation.own(resolver.openAssetFileDescriptor(uri, "r", operation.signal) ?: throw TransferException(TransferIssue.STORAGE_UNAVAILABLE))
            val input = operation.own(descriptor.createInputStream())
            val output = operation.own(FileOutputStream(partial))
            val hash = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var written = 0L
            while (true) {
                operation.check()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0 || count > CompanionLimits.STAGED_AUDIO_BYTES - otherBytes - written) throw TransferException(TransferIssue.QUOTA_EXCEEDED)
                if (expected != null && count > expected - written) throw TransferException(TransferIssue.INTEGRITY_FAILED)
                budget(outputDirectory, count.toLong(), Math.addExact(otherBytes, written))
                output.write(buffer, 0, count)
                hash.update(buffer, 0, count)
                written += count
            }
            if (written <= 0L || expected != null && written != expected) throw TransferException(TransferIssue.INTEGRITY_FAILED)
            operation.check()
            output.fd.sync()
            operation.close(output)
            operation.close(input)
            operation.close(descriptor)
            Files.move(partial.toPath(), final.toPath(), StandardCopyOption.ATOMIC_MOVE)
            moved = true
            operation.check()
            val draft = CompanionUploadDraft(UUID.randomUUID().toString(), null, id,
                selectedDeclaration(metadata.first, type, written, TransferHash.fromWire(hash.digest()), TransferUploadKind.AUDIO), CompanionUploadPhase.READY)
            store.recordUpload(draft)
            published = true
            draft
        } catch (error: Exception) {
            primary = error
            throw error
        }
        catch (error: OutOfMemoryError) {
              primary = error
              throw error
          }
        finally {
            if (!published) {
                cleanupPreserving(primary) {
                    operation.closeAll()
                    if (created && partial.exists() && !partial.delete() || moved && final.exists() && !final.delete()) throw TransferException(TransferIssue.RECOVERY_REQUIRED)
                }
            }
        }
    }

    suspend fun prepareIcon(uri: Uri, role: CompanionIconRole, licenseName: String, licenseText: String): CompanionThemeDraft = onWorker { operation ->
        require(uri.scheme == ContentResolver.SCHEME_CONTENT)
        requireLocalText(licenseName, 512)
        requireLocalText(licenseText, 16 * 1024, multiline = true)
        store.retireUnreferencedCopies(operation::check)
        val descriptor = operation.own(resolver.openAssetFileDescriptor(uri, "r", operation.signal) ?: throw TransferException(TransferIssue.STORAGE_UNAVAILABLE))
        val input = operation.own(descriptor.createInputStream())
        val bytes = readCompanionBounded(input, CompanionLimits.IMAGE_BYTES, operation::check)
        operation.close(input)
        operation.close(descriptor)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        validateCompanionImageBounds(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (bounds.outWidth / sample > CompanionLimits.ICON_EDGE * 2 || bounds.outHeight / sample > CompanionLimits.ICON_EDGE * 2) sample *= 2
        operation.check()
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: throw TransferException(TransferIssue.INVALID_INPUT)
        var iconBitmap: Bitmap? = null
        try {
            if (bitmap.width > CompanionLimits.ICON_EDGE * 2 || bitmap.height > CompanionLimits.ICON_EDGE * 2) throw TransferException(TransferIssue.QUOTA_EXCEEDED)
            val square = createBitmap(CompanionLimits.ICON_EDGE, CompanionLimits.ICON_EDGE)
            iconBitmap = square
            val scale = minOf(square.width.toFloat() / bitmap.width, square.height.toFloat() / bitmap.height)
            val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
            val left = (square.width - width) / 2
            val top = (square.height - height) / 2
            Canvas(square).drawBitmap(bitmap, null, Rect(left, top, left + width, top + height), Paint(Paint.FILTER_BITMAP_FLAG))
            val output = ByteArrayOutputStream()
            val limited = IconOutput(output)
            if (!square.compress(Bitmap.CompressFormat.PNG, 100, limited)) throw TransferException(TransferIssue.INVALID_INPUT)
            operation.check()
            val png = output.toByteArray()
            val id = UUID.randomUUID().toString()
            val file = store.iconFile(id)
            val outputDirectory = file.parentFile ?: throw TransferException(TransferIssue.STORAGE_UNAVAILABLE)
            themeBudget(outputDirectory, png.size.toLong(), store.themeBytes())
            var published = false
            var created = false
            var primary: Throwable? = null
            try {
                if (!file.createNewFile()) throw TransferException(TransferIssue.STORAGE_UNAVAILABLE)
                created = true
                val stream = operation.own(FileOutputStream(file))
                stream.write(png)
                stream.fd.sync()
                operation.close(stream)
                operation.check()
                val icon = CompanionThemeIcon(role, id, TransferHash.digest(png), png.size.toLong(), licenseName, licenseText)
                val state = store.update { old -> old.copy(theme = old.theme.edited(icons = old.theme.icons.filter { it.role != role } + icon)) }
                published = true
                store.retireUnreferencedCopies(operation::check)
                state.theme
            } catch (error: Exception) {
                primary = error
                throw error
            }
            catch (error: OutOfMemoryError) {
                  primary = error
                  throw error
              }
            finally {
                if (!published) cleanupPreserving(primary) {
                    operation.closeAll()
                    if (created && file.exists() && !file.delete()) throw TransferException(TransferIssue.RECOVERY_REQUIRED)
                }
            }
        } finally {
            iconBitmap?.recycle()
            bitmap.recycle()
        }
    }

    suspend fun prepareTheme(): CompanionUploadDraft = onWorker { operation ->
        val state = store.read()
        if (state.upload != null) throw TransferException(TransferIssue.BUSY)
        store.retireUnreferencedCopies(operation::check)
        val id = UUID.randomUUID().toString()
        val partial = store.packageFile(id, partial = true)
        val final = store.packageFile(id)
        val outputDirectory = partial.parentFile ?: throw TransferException(TransferIssue.STORAGE_UNAVAILABLE)
        themeBudget(outputDirectory, 16L * 1024 * 1024, store.themeBytes())
        var published = false
        var created = false
        var moved = false
        var primary: Throwable? = null
        try {
            if (!partial.createNewFile()) throw TransferException(TransferIssue.STORAGE_UNAVAILABLE)
            created = true
            val output = operation.own(FileOutputStream(partial))
            CompanionThemeBuilder.write(state.theme, output, { icon ->
                val file = store.iconFile(icon.fileId)
                store.verifyOwnedFile(file)
                val input = operation.own(file.inputStream())
                object : FilterInputStream(input) { override fun close() { operation.close(input) } }
            }, operation::check)
            output.fd.sync()
            operation.close(output)
            operation.check()
            val size = partial.length()
            val hash = hashOwnedFile(partial, size, operation)
            Files.move(partial.toPath(), final.toPath(), StandardCopyOption.ATOMIC_MOVE)
            moved = true
            operation.check()
            // THEME expectedRevision is the durable phone draft, never permission to write a selected watch theme.
            val draft = CompanionUploadDraft(UUID.randomUUID().toString(), null, id,
                selectedDeclaration("MeloX-theme.zip", "application/vnd.melox.theme+zip", size, hash, TransferUploadKind.THEME, state.theme.revision), CompanionUploadPhase.READY)
            store.recordUpload(draft)
            published = true
            draft
        } catch (error: Exception) {
            primary = error
            throw error
        }
        catch (error: OutOfMemoryError) {
              primary = error
              throw error
          }
        finally {
            if (!published) cleanupPreserving(primary) {
                operation.closeAll()
                if (created && partial.exists() && !partial.delete() || moved && final.exists() && !final.delete()) throw TransferException(TransferIssue.RECOVERY_REQUIRED)
            }
        }
    }

    /** Explicit phone preview uses only its already prepared bounded private PNGs. */
    suspend fun previewTheme(): CompanionThemePreview = onWorker { operation ->
        val draft = store.read().theme
        draft.validatePalette()
        val images = LinkedHashMap<CompanionIconRole, Bitmap>()
        try {
            for (icon in draft.icons) {
                operation.check()
                val file = store.iconFile(icon.fileId)
                store.verifyOwnedFile(file)
                val input = operation.own(file.inputStream())
                val bytes = readCompanionBounded(input, CompanionLimits.ICON_BYTES, operation::check)
                operation.close(input)
                if (bytes.size.toLong() != icon.byteCount || TransferHash.digest(bytes) != icon.sha256) throw TransferException(TransferIssue.INTEGRITY_FAILED)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth !in 1..CompanionLimits.ICON_EDGE || bounds.outHeight !in 1..CompanionLimits.ICON_EDGE || bounds.outMimeType != "image/png") throw TransferException(TransferIssue.INVALID_INPUT)
                operation.check()
                val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: throw TransferException(TransferIssue.INVALID_INPUT)
                images[icon.role] = image
                if (image.width !in 1..CompanionLimits.ICON_EDGE || image.height !in 1..CompanionLimits.ICON_EDGE) throw TransferException(TransferIssue.QUOTA_EXCEEDED)
            }
            operation.check()
            CompanionThemePreview(draft, images)
        } catch (error: Exception) {
            images.values.forEach {
                it.recycle()
            }
            throw error
        }
        catch (error: OutOfMemoryError) {
              images.values.forEach {
                  it.recycle()
              }
              throw error
          }
    }

    suspend fun verifiedFile(draft: CompanionUploadDraft): File = onWorker { operation ->
        val file = if (draft.declaration.kind == TransferUploadKind.AUDIO) store.audioFile(draft.fileId) else store.packageFile(draft.fileId)
        store.verifyOwnedFile(file)
        val size = checkNotNull(draft.declaration.declaredBytes)
        if (file.length() != size || hashOwnedFile(file, size, operation) != draft.declaration.sha256) throw TransferException(TransferIssue.INTEGRITY_FAILED)
        file
    }

    /** This removes only the private pending copy; the selected original is never deleted. */
    suspend fun discardPreparedCopy(): CompanionLocalState = onWorker { operation -> retireCopy(operation) }

    private fun retireCopy(operation: Operation): CompanionLocalState {
        val state = store.read()
        val draft = state.upload ?: return state
        val file = if (draft.declaration.kind == TransferUploadKind.AUDIO) store.audioFile(draft.fileId) else store.packageFile(draft.fileId)
        operation.check()
        store.recordUpload(draft.withResult(CompanionUploadPhase.RETIRING_COPY, draft.receipt))
        if (file.exists()) {
            store.verifyOwnedFile(file)
            if (!file.delete()) throw TransferException(TransferIssue.RECOVERY_REQUIRED)
        }
        return store.update {
            old -> if (old.upload?.operationId != draft.operationId) throw TransferException(TransferIssue.CONFLICT)
            old.copy(upload = null)
        }
    }

    private fun selectedMetadata(uri: Uri, operation: Operation): Pair<String, Long?> {
        val cursor = operation.own(resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null, operation.signal) ?: throw TransferException(TransferIssue.STORAGE_UNAVAILABLE))
        if (!cursor.moveToFirst()) throw TransferException(TransferIssue.NOT_FOUND)
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
        val name = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) cursor.getString(nameIndex) else "selected-audio"
        val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
        operation.close(cursor)
        operation.check()
        if (size != null && size !in 1..CompanionLimits.STAGED_AUDIO_BYTES) throw TransferException(TransferIssue.QUOTA_EXCEEDED)
        return name to size
    }

    private fun audioType(supplied: String?, name: String): String {
        val type = when (supplied?.lowercase(Locale.ROOT)) {
            "audio/mp3" -> "audio/mpeg"
            "audio/x-flac" -> "audio/flac"
            else -> supplied?.lowercase(Locale.ROOT)
        }
        if (type in AUDIO_TYPES) return checkNotNull(type)
        val fallback = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "wav" -> "audio/wav"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "m4a" -> "audio/mp4"
            "ogg", "opus" -> "audio/ogg"
            "aac" -> "audio/aac"
            "aif", "aiff" -> "audio/x-aiff"
            else -> throw TransferException(TransferIssue.UNSUPPORTED_TYPE)
        }
        return fallback
    }

    // Both copy budgets preserve physical free space; reclaimable caches are outside that reserve.
    @SuppressLint("UsableSpace")
    private fun budget(directory: File, next: Long, ownedAudio: Long) {
        if (next < 0L || ownedAudio < 0L || next > CompanionLimits.STAGED_AUDIO_BYTES - ownedAudio) throw TransferException(TransferIssue.QUOTA_EXCEEDED)
        val available = directory.usableSpace
        if (available < CompanionLimits.FREE_RESERVE_BYTES || next > available - CompanionLimits.FREE_RESERVE_BYTES) throw TransferException(TransferIssue.STORAGE_FULL)
    }
    @SuppressLint("UsableSpace")
    private fun themeBudget(directory: File, next: Long, owned: Long) {
        if (next < 0L || owned < 0L || next > CompanionLimits.STAGED_THEME_BYTES - owned) throw TransferException(TransferIssue.QUOTA_EXCEEDED)
        val available = directory.usableSpace
        if (available < CompanionLimits.FREE_RESERVE_BYTES || next > available - CompanionLimits.FREE_RESERVE_BYTES) throw TransferException(TransferIssue.STORAGE_FULL)
    }
    private fun hashOwnedFile(file: File, expected: Long, operation: Operation): TransferHash {
        val input = operation.own(file.inputStream())
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var count = 0L
        while (true) {
            operation.check()
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0 || read > expected - count) throw TransferException(TransferIssue.INTEGRITY_FAILED)
            hash.update(buffer, 0, read)
            count += read
        }
        operation.close(input)
        if (count != expected) throw TransferException(TransferIssue.INTEGRITY_FAILED)
        return TransferHash.fromWire(hash.digest())
    }
    private fun map(error: Exception): Exception {
        val mapped = when (error) {
            is TransferException, is CancellationException -> error
            is OperationCanceledException -> CancellationException("Local operation cancelled")
            is SecurityException -> TransferException(TransferIssue.PERMISSION_DENIED)
            is IllegalArgumentException -> TransferException(TransferIssue.INVALID_INPUT)
            else -> TransferException(TransferIssue.STORAGE_UNAVAILABLE)
        }
        // Preserve the fixed retirement code, without retaining provider messages or private paths.
        if (mapped !== error && error.suppressed.any { it is TransferException && it.issue == TransferIssue.RECOVERY_REQUIRED }) {
            mapped.addSuppressed(TransferException(TransferIssue.RECOVERY_REQUIRED))
        }
        return mapped
    }
    private fun cleanupPreserving(primary: Throwable?, action: () -> Unit) {
        try { action() }
        catch (_: Exception) {
            val cleanup = TransferException(TransferIssue.RECOVERY_REQUIRED)
            if (primary != null) primary.addSuppressed(cleanup) else throw cleanup
        }
    }
    private class IconOutput(output: ByteArrayOutputStream) : FilterOutputStream(output) {
        private var written = 0
        override fun write(value: Int) {
            reserve(1)
            out.write(value)
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            reserve(length)
            out.write(bytes, offset, length)
        }
        private fun reserve(count: Int) {
            if (count < 0 || count > CompanionLimits.ICON_BYTES - written) throw TransferException(TransferIssue.QUOTA_EXCEEDED)
            written += count
        }
    }
    private companion object {
        val AUDIO_TYPES = setOf("audio/wav", "audio/x-wav", "audio/mpeg", "audio/flac", "audio/mp4", "audio/ogg", "audio/aac", "audio/x-aiff")
    }
}

internal fun validateCompanionImageBounds(width: Int, height: Int) {
    // 输入预算是十进制 16 MP，字节的 MiB 预算独立；先用 Long 再乘以高度。
    if (width <= 0 || height <= 0) {
        throw TransferException(TransferIssue.INVALID_INPUT)
    }
    if (width.toLong() * height > CompanionLimits.IMAGE_PIXELS) throw TransferException(TransferIssue.QUOTA_EXCEEDED)
}
