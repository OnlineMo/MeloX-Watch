package io.github.onlinemo.meloxwatch.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.icu.text.BreakIterator
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.Process
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine

enum class ProbeFileIssue {
    PERMISSION_DENIED,
    INVALID_URI,
    UNREADABLE_FILE,
    NOT_AUDIO,
    SOURCE_UNAVAILABLE,
    SOURCE_BUSY,
}

enum class ProbeAccess {
    TEMPORARY,
    PERSISTED,
    MEDIA_PERMISSION,
    PERSISTENCE_REJECTED,
}

sealed interface ProbeFileResult<out T> {
    data class Success<T>(val value: T) : ProbeFileResult<T>
    data class Failure(val issue: ProbeFileIssue) : ProbeFileResult<Nothing>
}

data class ProbeAudio(
    val uri: Uri,
    val mediaId: String,
    val displayName: String,
    val durationMs: Long?,
    val grantUri: Uri,
    val grantFlags: Int,
    val access: ProbeAccess,
)

data class ProbeDocument(
    val uri: Uri,
    val displayName: String,
)

data class ProbeTreeSample(
    val treeUri: Uri,
    val grantFlags: Int,
    val access: ProbeAccess,
    val audio: List<ProbeDocument>,
    val inspectedChildren: Int,
    val reachedLimit: Boolean,
)

/**
 * 仅检查用户选中的来源，样本不构成完整音乐库或递归扫描。
 *
 * 阻塞提供方调用使用单个 IO 通道，取消立即传给 CancellationSignal；结果由调用方校验代次。
 * 本类不保存曲库、不复制音频、不向网络发请求，也不从 URI 猜测磁盘路径。
 */
class ProbeFiles(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) {
    private val resolver = context.contentResolver
    private val workerInFlight = AtomicBoolean(false)

    suspend fun inspectAudio(
        uri: Uri,
        grantUri: Uri = uri,
        grantFlags: Int = 0,
        usesMediaPermission: Boolean = false,
    ): ProbeFileResult<ProbeAudio> = readResult { signal ->
        requireContentUri(uri)
        if (!usesMediaPermission && !hasReadAccess(uri)) {
            throw SecurityException("Missing URI read grant")
        }
        resolver.openAssetFileDescriptor(uri, "r", signal)?.use { descriptor ->
            if (!descriptor.fileDescriptor.valid()) {
                throw FileNotFoundException("No readable descriptor")
            }
        } ?: throw FileNotFoundException("No readable descriptor")
        signal.throwIfCanceled()
        val displayName = documentName(uri, signal)
        val retriever = MediaMetadataRetriever()
        try {
            try {
                retriever.setDataSource(context, uri)
            } catch (error: SecurityException) {
                throw error
            } catch (_: IllegalArgumentException) {
                return@readResult ProbeFileResult.Failure(ProbeFileIssue.NOT_AUDIO)
            }
            signal.throwIfCanceled()
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            if (!hasAudio || hasVideo) {
                return@readResult ProbeFileResult.Failure(ProbeFileIssue.NOT_AUDIO)
            }
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.takeIf { duration -> duration >= 0L }
            val access = if (usesMediaPermission) ProbeAccess.MEDIA_PERMISSION else accessFor(grantUri)
            ProbeFileResult.Success(
                ProbeAudio(uri, mediaId(uri), displayName, durationMs, grantUri, grantFlags, access),
            )
        } finally {
            retriever.release()
        }
    }

    suspend fun mediaSample(): ProbeFileResult<List<ProbeDocument>> = readResult { signal ->
        val queryArguments = Bundle()
        queryArguments.putInt(ContentResolver.QUERY_ARG_LIMIT, MEDIA_SAMPLE_LIMIT)
        queryArguments.putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Audio.Media._ID))
        queryArguments.putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_ASCENDING)
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME)
        val cursor = resolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, queryArguments, signal)
            ?: return@readResult ProbeFileResult.Failure(ProbeFileIssue.SOURCE_UNAVAILABLE)
        val audio = ArrayList<ProbeDocument>(MEDIA_SAMPLE_LIMIT)
        cursor.use {
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            while (audio.size < MEDIA_SAMPLE_LIMIT && cursor.moveToNext()) {
                signal.throwIfCanceled()
                val uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cursor.getLong(idColumn).toString())
                audio.add(ProbeDocument(uri, boundedName(cursor.getString(nameColumn))))
            }
        }
        ProbeFileResult.Success(audio.toList())
    }

    suspend fun treeSample(treeUri: Uri, grantFlags: Int): ProbeFileResult<ProbeTreeSample> = readResult { signal ->
        requireContentUri(treeUri)
        if (!DocumentsContract.isTreeUri(treeUri) || !hasReadAccess(treeUri)) {
            throw SecurityException("Missing tree read grant")
        }
        val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val cursor = resolver.query(childrenUri, projection, null, null, null, signal)
            ?: return@readResult ProbeFileResult.Failure(ProbeFileIssue.SOURCE_UNAVAILABLE)
        var inspectedChildren = 0
        val audio = ArrayList<ProbeDocument>()
        var reachedLimit = false
        cursor.use {
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (inspectedChildren < TREE_CHILD_LIMIT && cursor.moveToNext()) {
                signal.throwIfCanceled()
                inspectedChildren += 1
                val mime = cursor.getString(mimeColumn).orEmpty()
                if (mime.startsWith("audio/")) {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idColumn))
                    audio.add(ProbeDocument(uri, boundedName(cursor.getString(nameColumn))))
                }
            }
            reachedLimit = inspectedChildren == TREE_CHILD_LIMIT && cursor.moveToNext()
        }
        ProbeFileResult.Success(
            ProbeTreeSample(treeUri, grantFlags, accessFor(treeUri), audio.toList(), inspectedChildren, reachedLimit),
        )
    }

    /** 只在用户明确保留访问权、且选择器实际返回持久读授权时申请持久化。 */
    suspend fun persistReadAccess(uri: Uri, grantFlags: Int): ProbeFileResult<ProbeAccess> = readResult { signal ->
        requireContentUri(uri)
        signal.throwIfCanceled()
        val canPersist = (grantFlags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0
        val hasReadFlag = (grantFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0
        if (!canPersist || !hasReadFlag) {
            return@readResult ProbeFileResult.Success(accessFor(uri))
        }
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            return@readResult ProbeFileResult.Success(ProbeAccess.PERSISTENCE_REJECTED)
        }
        val access = accessFor(uri)
        ProbeFileResult.Success(if (access == ProbeAccess.PERSISTED) access else ProbeAccess.PERSISTENCE_REJECTED)
    }

    private fun hasReadAccess(uri: Uri): Boolean = context.checkUriPermission(
        uri,
        Process.myPid(),
        Process.myUid(),
        Intent.FLAG_GRANT_READ_URI_PERMISSION,
    ) == PackageManager.PERMISSION_GRANTED

    private fun accessFor(uri: Uri): ProbeAccess {
        val isPersisted = resolver.persistedUriPermissions.any { permission -> permission.uri == uri && permission.isReadPermission }
        return if (isPersisted) ProbeAccess.PERSISTED else ProbeAccess.TEMPORARY
    }

    private fun documentName(uri: Uri, signal: CancellationSignal): String {
        val cursor = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null, signal)
        cursor?.use {
            val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameColumn >= 0 && cursor.moveToFirst()) {
                return boundedName(cursor.getString(nameColumn))
            }
        }
        return ""
    }

    private fun requireContentUri(uri: Uri) {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT && !uri.authority.isNullOrEmpty())
        require(uri.toString().length <= MAX_URI_LENGTH)
    }

    private fun boundedName(name: String?): String {
        val text = name.orEmpty()
        if (text.codePointCount(0, text.length) <= MAX_NAME_LENGTH) return text
        val maximumEnd = text.offsetByCodePoints(0, MAX_NAME_LENGTH)
        val boundaries = BreakIterator.getCharacterInstance()
        boundaries.setText(text)
        val safeEnd = boundaries.preceding(maximumEnd + 1).coerceAtLeast(0)
        return text.substring(0, safeEnd)
    }

    private fun mediaId(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray(Charsets.UTF_8))
        return "probe:" + digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private suspend fun <T> readResult(
        action: (CancellationSignal) -> ProbeFileResult<T>,
    ): ProbeFileResult<T> {
        return try {
            readBlocking(action)
        } catch (error: CancellationException) {
            throw error
        } catch (_: ProbeReadBusyException) {
            ProbeFileResult.Failure(ProbeFileIssue.SOURCE_BUSY)
        } catch (_: SecurityException) {
            ProbeFileResult.Failure(ProbeFileIssue.PERMISSION_DENIED)
        } catch (_: FileNotFoundException) {
            ProbeFileResult.Failure(ProbeFileIssue.UNREADABLE_FILE)
        } catch (_: IllegalArgumentException) {
            ProbeFileResult.Failure(ProbeFileIssue.INVALID_URI)
        } catch (_: IOException) {
            ProbeFileResult.Failure(ProbeFileIssue.UNREADABLE_FILE)
        } catch (_: RuntimeException) {
            ProbeFileResult.Failure(ProbeFileIssue.SOURCE_UNAVAILABLE)
        }
    }

    private suspend fun <T> readBlocking(action: (CancellationSignal) -> T): T = suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        // 提供方若忽略取消，仍只留一个阻塞操作，不把后续点击排进无界队列。
        if (!workerInFlight.compareAndSet(false, true)) {
            continuation.resumeWithException(ProbeReadBusyException())
            return@suspendCancellableCoroutine
        }
        continuation.invokeOnCancellation { signal.cancel() }
        val operation = Runnable {
            try {
                signal.throwIfCanceled()
                val result = action(signal)
                if (continuation.isActive) continuation.resume(result)
            } catch (error: OperationCanceledException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally {
                workerInFlight.set(false)
            }
        }
        try {
            dispatcher.dispatch(continuation.context, operation)
        } catch (error: RuntimeException) {
            // 派发失败时没有 worker 执行 finally，必须在此释放通道才能再次尝试。
            workerInFlight.set(false)
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }

    private class ProbeReadBusyException : IllegalStateException()

    companion object {
        const val MEDIA_SAMPLE_LIMIT = 20
        const val TREE_CHILD_LIMIT = 32
        private const val MAX_URI_LENGTH = 8192
        private const val MAX_NAME_LENGTH = 512
    }
}
