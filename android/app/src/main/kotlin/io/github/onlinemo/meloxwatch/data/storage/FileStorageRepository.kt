package io.github.onlinemo.meloxwatch.data.storage

import android.content.Context
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationException
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationIssue
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationsRuntime
import io.github.onlinemo.meloxwatch.data.imports.AudioCopyQuota
import io.github.onlinemo.meloxwatch.data.imports.OwnedAudioProvider
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
        StorageReport(originals, privateAudio(OwnedAudioProvider.DIRECTORY, ".audio"), measured(artworkMeasure), measured(lyricsMeasure),
            combineStorage(operations.measureRecoveryStorage(), combineStorage(privateAudio(OwnedAudioProvider.DIRECTORY, ".part"),
                privateAudio(AudioCopyQuota.TRANSFER_DIRECTORY, ".partial"))), io.read { FileStoragePhysical.available(context) })
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
        } catch (cancelled: CancellationException) { cancellation = cancelled
        failure = StorageIssue.CANCELLED }
        catch (error: Exception) { failure = issue(error) }
        val result = withContext(NonCancellable) {
            io.awaitIdle()
            StorageClearReport(beforeArtwork, measured(artworkMeasure), beforeLyrics, measured(lyricsMeasure), availableBefore,
                io.read { FileStoragePhysical.available(context) }, failure)
        }
        mutableClear.value = result
        cancellation?.let { throw it }
        result
    }

    private suspend fun privateAudio(directory: String, suffix: String): StorageCategory = try {
        io.read { signal -> FileStoragePhysical.privateAudio(context, directory, suffix, signal) }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { StorageCategory(null, 0L, 1L, issue(error)) }

    private suspend fun measured(action: suspend () -> StorageCategory): StorageCategory = try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { StorageCategory(null, 0L, 1L, issue(error)) }

    private suspend fun <T> command(action: suspend () -> T): T {
        if (!commands.tryLock()) throw StorageException(StorageIssue.BUSY)
        try { return operations.withExclusiveLibraryMutation(requireNoPending = false, action = action) }
        finally { commands.unlock() }
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
