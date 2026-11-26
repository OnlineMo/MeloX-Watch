package io.github.onlinemo.meloxwatch.data.storage

import android.content.Context
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationException
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationIssue
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationsRuntime
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** 明确的一次统计或清理；进入设置不扫描外部目录或获取元数据。 */
class FileStorageRepository(
    private val context: Context,
    private val io: LocalProviderIo,
    private val operations: FileOperationsRuntime,
    private val artworkMeasure: suspend () -> StorageCategory,
    private val artworkClear: suspend () -> StorageCategory,
    private val lyricsMeasure: suspend () -> StorageCategory,
    private val lyricsClear: suspend () -> StorageCategory,
    private val privateMeasure: suspend () -> StoragePrivateReport,
) {
    private val commands = Mutex()
    private val mutableClear = MutableStateFlow<StorageClearReport?>(null)
    val clearState = mutableClear.asStateFlow()

    suspend fun measure(): StorageReport = command {
        var originals = StorageCategory(0L, 0L)
        var after: String? = null
        do {
            currentCoroutineContext().ensureActive()
            val page = operations.storageReferencePage(after)
            for (descriptor in page.references) {
                currentCoroutineContext().ensureActive()
                val observed = if (descriptor == null) StorageCategory(null, 0L, 1L, StorageIssue.PERMISSION_REVOKED) else try {
                    val size = io.read { signal -> FileStoragePhysical.reference(context, descriptor, signal) }
                    if (size == null) StorageCategory(null, 0L, 1L, StorageIssue.INCOMPLETE) else StorageCategory(size, 1L)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { StorageCategory(null, 0L, 1L, issue(failure)) }
                originals = combineStorage(originals, observed)
            }
            if (page.nextLocationId != null && page.nextLocationId == after) throw StorageException(StorageIssue.SOURCE_CHANGED)
            after = page.nextLocationId
        } while (after != null)
        val owned = privateMeasure()
        StorageReport(originals, owned.audioCopies, owned.lyricsCopies, owned.savedArtwork, owned.fonts, owned.themes, owned.backgrounds,
            measured(artworkMeasure), measured(lyricsMeasure), combineStorage(operations.measureRecoveryStorage(), owned.staging),
            owned.availableBytes)
    }

    suspend fun clearCaches(): StorageClearReport = command {
        val beforeArtwork = measured(artworkMeasure)
        val beforeLyrics = measured(lyricsMeasure)
        val availableBefore = io.read { FileStoragePhysical.available(context) }
        var failure: StorageIssue? = null
        var cancellation: CancellationException? = null
        try {
            artworkClear()
            currentCoroutineContext().ensureActive()
            lyricsClear()
        } catch (cancelled: CancellationException) {
            cancellation = cancelled
            failure = StorageIssue.CANCELLED
        } catch (error: Exception) { failure = issue(error) }
        val result = withContext(NonCancellable) {
            try { awaitCommandRetired() }
            catch (retired: Throwable) {
                val cancelled = cancellation
                if (cancelled == null) throw retired
                if (cancelled !== retired) cancelled.addSuppressed(retired)
                throw cancelled
            }
            StorageClearReport(beforeArtwork, measured(artworkMeasure), beforeLyrics, measured(lyricsMeasure), availableBefore,
                io.read { FileStoragePhysical.available(context) }, failure)
        }
        mutableClear.value = result
        cancellation?.let { throw it }
        result
    }

    private suspend fun measured(action: suspend () -> StorageCategory): StorageCategory = try {
        action().let { it.copy(bytes = it.observedBytes) }
    }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { StorageCategory(null, 0L, 1L, issue(error)) }

    private suspend fun <T> command(action: suspend () -> T): T {
        if (!commands.tryLock()) throw StorageException(StorageIssue.BUSY)
        try {
            val retirement = io.trackReads()
            return withContext(retirement) {
                operations.withExclusiveLibraryMutation(requireNoPending = false) {
                    var failure: Throwable? = null
                    try { action() }
                    catch (error: Throwable) { failure = error; throw error }
                    finally {
                        try { withContext(NonCancellable) { retirement.awaitRetired() } }
                        catch (retired: Throwable) {
                            val original = failure
                            if (original == null) throw retired
                            if (original !== retired) original.addSuppressed(retired)
                        }
                    }
                }
            }
        }
        finally { commands.unlock() }
    }

    private suspend fun awaitCommandRetired() {
        val retirement = currentCoroutineContext()[LocalProviderIo.ReadRetirement]
            ?: throw StorageException(StorageIssue.UNAVAILABLE)
        check(retirement.io === io)
        retirement.awaitRetired()
    }
    private fun issue(failure: Exception) = when (failure) {
        is StorageException -> failure.issue
        is SecurityException -> StorageIssue.PERMISSION_REVOKED
        is FileOperationException -> when (failure.issue) {
            FileOperationIssue.BUSY -> StorageIssue.BUSY
            FileOperationIssue.PERMISSION_DENIED, FileOperationIssue.PERMISSION_REVOKED -> StorageIssue.PERMISSION_REVOKED
            FileOperationIssue.SOURCE_CHANGED -> StorageIssue.SOURCE_CHANGED
            FileOperationIssue.STORAGE_FULL -> StorageIssue.STORAGE_FULL
            else -> StorageIssue.UNAVAILABLE
        }
        is IOException -> StorageIssue.UNAVAILABLE
        else -> StorageIssue.UNAVAILABLE
    }
}
