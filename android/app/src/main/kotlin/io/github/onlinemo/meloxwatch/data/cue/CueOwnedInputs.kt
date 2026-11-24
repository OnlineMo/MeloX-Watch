package io.github.onlinemo.meloxwatch.data.cue

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.AssetFileDescriptor
import android.os.Build
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import androidx.core.net.toUri
import io.github.onlinemo.meloxwatch.model.CueFileBinding
import io.github.onlinemo.meloxwatch.model.LocalAccess
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** 分配对象不执行IO；操作所有者在initialize之前注册该对象。 */
interface CueOwnedInput : AutoCloseable {
    val length: Long
    fun initialize()
    fun seek(position: Long)
    fun read(buffer: ByteArray, offset: Int, count: Int): Int
    fun checkCurrent(probe: Boolean = false) { if (length <= 0L) throw CueException(CueIssue.SOURCE_CHANGED) }
    fun requestCancellation() {}
    fun cancel()
}

/** 一个物理epoch持有输入、asset及路由probe，包含晚到的open结果。 */
class AndroidCueOwnedInput(context: Context, private val binding: CueFileBinding,
    private val cancellation: CancellationSignal = CancellationSignal()) : CueOwnedInput {
    private val application = context.applicationContext
    private val resolver = application.contentResolver
    private val ioLock = Any()
    private val stopped = AtomicBoolean()
    private var asset: AssetFileDescriptor? = null
    private var input: FileInputStream? = null
    private var probeAsset: AssetFileDescriptor? = null
    private var identity: StructStat? = null
    private var base = 0L
    private var position = 0L
    private var initialized = false
    override val length: Long get() = binding.sizeBytes

    override fun initialize(): Unit = synchronized(ioLock) {
        check(!initialized && asset == null && input == null && probeAsset == null)
        checkpoint()
        checkPermission()
        val opened = resolver.openAssetFileDescriptor(binding.location.uri.toUri(), "r", cancellation)
            ?: throw SourceReadException(SourceReadIssue.FILE_MISSING)
        asset = opened
        checkpoint()
        validateAsset(opened)
        base = opened.startOffset
        val stream = opened.createInputStream()
        input = stream
        try {
            val actual = Os.fstat(opened.fileDescriptor)
            if (!OsConstants.S_ISREG(actual.st_mode)) throw SourceReadException(SourceReadIssue.UNSUPPORTED_FORMAT)
            if (actual.st_size < Math.addExact(base, length) ||
                opened.declaredLength < 0L && actual.st_size != Math.addExact(base, length)) changed()
            stream.channel.position(base)
            identity = actual
        } catch (failure: IOException) { throw SourceReadException(SourceReadIssue.UNSUPPORTED_FORMAT, failure) }
        initialized = true
        position = 0L
        checkpoint()
    }

    override fun seek(position: Long): Unit = synchronized(ioLock) {
        checkpoint()
        check(initialized)
        if (position !in 0L..length) throw CueException(CueIssue.INVALID_INPUT)
        try { checkNotNull(input).channel.position(Math.addExact(base, position)) }
        catch (failure: IOException) { throw SourceReadException(SourceReadIssue.UNSUPPORTED_FORMAT, failure) }
        this.position = position
        checkpoint()
    }

    override fun read(buffer: ByteArray, offset: Int, count: Int): Int = synchronized(ioLock) {
        checkpoint()
        check(initialized)
        require(offset >= 0 && count >= 0 && offset <= buffer.size - count)
        if (count == 0) return@synchronized 0
        if (position == length) return@synchronized -1
        // AutoCloseInputStream的剩余字节计数无法由channel seek重置。
        val read = checkNotNull(input).channel.read(ByteBuffer.wrap(buffer, offset, minOf(count.toLong(), length - position).toInt()))
        if (read < 0) changed()
        if (read > 0) position = Math.addExact(position, read.toLong())
        checkpoint()
        read
    }

    override fun checkCurrent(probe: Boolean): Unit = synchronized(ioLock) {
        checkpoint()
        check(initialized)
        checkPermission()
        val opened = checkNotNull(asset)
        validateAsset(opened)
        val expected = checkNotNull(identity)
        val current = Os.fstat(opened.fileDescriptor)
        if (!sameObject(current, expected)) changed()
        if (probe) {
            check(probeAsset == null)
            val observed = resolver.openAssetFileDescriptor(binding.location.uri.toUri(), "r", cancellation)
                ?: throw SourceReadException(SourceReadIssue.FILE_MISSING)
            probeAsset = observed
            checkpoint()
            validateAsset(observed)
            if (observed.startOffset != base || !sameObject(Os.fstat(observed.fileDescriptor), expected)) changed()
            // 仅在实际descriptor关闭成功后清空该字段。
            observed.close()
            probeAsset = null
        }
        checkpoint()
    }

    override fun requestCancellation() { stopped.set(true) }
    override fun cancel() {
        stopped.set(true);
        cancellation.cancel()
    }
    override fun close() {
        stopped.set(true)
        var failure: Exception? = null
        try { cancellation.cancel() } catch (error: Exception) { failure = error }
        synchronized(ioLock) {
            fun close(action: () -> Unit) {
                try { action() } catch (error: Exception) {
                    if (failure == null) failure = error else if (failure !== error) failure?.addSuppressed(error)
                }
            }
            close {
                probeAsset?.close();
                probeAsset = null
            }
            close {
                input?.close();
                input = null
            }
            close {
                asset?.close();
                asset = null
            }
            if (failure == null) {
                initialized = false;
                identity = null
            }
        }
        failure?.let { throw it }
    }

    private fun checkPermission() {
        val uri = binding.location.uri.toUri()
        val grant = binding.location.grantUri.toUri()
        if (uri.scheme != "content" || uri.authority != grant.authority) changed()
        if (DocumentsContract.isTreeUri(grant) && (!DocumentsContract.isTreeUri(uri) ||
                DocumentsContract.getTreeDocumentId(uri) != DocumentsContract.getTreeDocumentId(grant) ||
                DocumentsContract.getDocumentId(uri) != binding.objectId)) changed()
        if (binding.location.access == LocalAccess.PERSISTED && resolver.persistedUriPermissions.none {
                it.isReadPermission && it.uri == grant }) throw SourceReadException(SourceReadIssue.PERMISSION_REVOKED)
        if (binding.location.access == LocalAccess.MEDIA_PERMISSION) {
            val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
            if (application.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                throw SourceReadException(SourceReadIssue.PERMISSION_DENIED)
            }
        }
    }
    private fun validateAsset(opened: AssetFileDescriptor) {
        if (!opened.fileDescriptor.valid() || opened.startOffset < 0L ||
            opened.declaredLength >= 0L && opened.declaredLength != length) changed()
    }
    private fun sameObject(observed: StructStat, expected: StructStat) = observed.st_dev == expected.st_dev &&
        observed.st_ino == expected.st_ino && observed.st_size == expected.st_size &&
        observed.st_size >= Math.addExact(base, length) && OsConstants.S_ISREG(observed.st_mode)
    private fun checkpoint() {
        if (stopped.get() || Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("CUE input retired")
        cancellation.throwIfCanceled()
    }
    private fun changed(): Nothing = throw CueException(CueIssue.SOURCE_CHANGED)
}
