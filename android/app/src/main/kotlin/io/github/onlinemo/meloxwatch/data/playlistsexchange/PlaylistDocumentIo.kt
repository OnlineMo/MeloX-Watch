package io.github.onlinemo.meloxwatch.data.playlistsexchange

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistException
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistIssue
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class PlaylistFileProof(val byteCount: Long, val sha256: String)
internal data class PlaylistOutputIdentity(val device: Long, val inode: Long)
internal enum class PlaylistOutputCleanup { CLEARED, NOT_OWNED, UNAVAILABLE }
internal class PlaylistOwnedOutput {
    @Volatile var identity: PlaylistOutputIdentity? = null
    @Volatile var proof: PlaylistFileProof? = null
    @Volatile var wrote = false
    @Volatile var verified = false
}

/** The provider channel remains occupied until descriptors really close, including cancelled callers. */
internal class PlaylistDocumentIo(private val context: Context, private val io: LocalProviderIo) {
    private val directory get() = File(context.noBackupFilesDir, "playlist-exchange")

    fun newPrivateFile(suffix: String): File {
        require(suffix == "input" || suffix == "output")
        val parent = directory
        if (!parent.isDirectory && !parent.mkdirs()) fail(PlaylistIssue.STORAGE_UNAVAILABLE)
        return File.createTempFile("playlist-", ".$suffix", parent)
    }

    /** Iterate one private entry at a time. No selected URI is reopened or written during startup. */
    suspend fun recoverPrivateFiles() {
        val parent = directory
        if (!parent.exists()) return
        Files.newDirectoryStream(parent.toPath()).use { entries ->
            for (path in entries) {
                currentCoroutineContext().ensureActive()
                val name = path.fileName.toString()
                if (!name.startsWith("playlist-") || !name.endsWith(".input") && !name.endsWith(".output") ||
                    !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) fail(PlaylistIssue.RECOVERY_REQUIRED)
                deletePrivate(path.toFile())
            }
        }
    }

    suspend fun copyImport(uri: Uri, destination: File): PlaylistFileProof = io.read { signal ->
        val descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r", signal)
            ?: fail(PlaylistIssue.SOURCE_UNAVAILABLE)
        descriptor.use {
            if (it.declaredLength > PlaylistExchangeLimits.BYTES) exchangeFail(PlaylistExchangeIssue.LIMIT_EXCEEDED)
            it.createInputStream().use { input ->
                FileOutputStream(destination).use { output ->
                    val proof = copy(input, output, signal)
                    signal.throwIfCanceled()
                    output.flush()
                    output.fd.sync()
                    proof
                }
            }
        }
    }

    /** The CreateDocument flight owns a new, empty, seekable target. Registration alone did not prove this. */
    suspend fun write(uri: Uri, source: File, expected: PlaylistFileProof, owned: PlaylistOwnedOutput) {
        owned.proof = expected
        io.read { signal ->
            val descriptor = context.contentResolver.openFileDescriptor(uri, "rw", signal)
                ?: fail(PlaylistIssue.SOURCE_UNAVAILABLE)
            descriptor.use { target ->
                val status = Os.fstat(target.fileDescriptor)
                if (!OsConstants.S_ISREG(status.st_mode) || target.statSize != 0L) fail(PlaylistIssue.CONFLICT)
                val identity = PlaylistOutputIdentity(status.st_dev, status.st_ino)
                if (owned.identity != null && owned.identity != identity) fail(PlaylistIssue.SOURCE_CHANGED)
                owned.identity = identity
                Os.lseek(target.fileDescriptor, 0L, OsConstants.SEEK_SET)
                signal.throwIfCanceled()
                // AutoCloseOutputStream owns the descriptor; ParcelFileDescriptor.close is idempotent.
                ParcelFileDescriptor.AutoCloseOutputStream(target).use { output ->
                    FileInputStream(source).use { input ->
                        owned.wrote = true
                        val observed = copy(input, output, signal)
                        if (observed != expected) fail(PlaylistIssue.SOURCE_CHANGED)
                        signal.throwIfCanceled()
                        output.flush()
                        output.fd.sync()
                    }
                }
            }
        }
    }

    /** A separate resolver open verifies the actual output; cached writer bytes are never a success receipt. */
    suspend fun verify(uri: Uri, expected: PlaylistFileProof, owned: PlaylistOwnedOutput) {
        io.read { signal ->
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r", signal)
                ?: fail(PlaylistIssue.SOURCE_UNAVAILABLE)
            descriptor.use { target ->
                val status = Os.fstat(target.fileDescriptor)
                if (!OsConstants.S_ISREG(status.st_mode) ||
                    PlaylistOutputIdentity(status.st_dev, status.st_ino) != owned.identity ||
                    target.statSize != expected.byteCount) fail(PlaylistIssue.SOURCE_CHANGED)
                ParcelFileDescriptor.AutoCloseInputStream(target).use { input ->
                    if (copy(input, null, signal) != expected) fail(PlaylistIssue.INCOMPLETE)
                }
                signal.throwIfCanceled()
                owned.verified = true
            }
        }
    }

    /** Never delete a URI. Clear only the same opened object while its bytes are still our output prefix. */
    suspend fun clearUncommitted(uri: Uri, source: File, owned: PlaylistOwnedOutput): PlaylistOutputCleanup {
        io.awaitIdle()
        if (!owned.wrote) return PlaylistOutputCleanup.CLEARED
        return try {
            io.read { signal ->
                // A retained private file must still be the exact serialization used by the writer.
                val proof = owned.proof ?: return@read PlaylistOutputCleanup.UNAVAILABLE
                if (FileInputStream(source).use { copy(it, null, signal) } != proof) {
                    return@read PlaylistOutputCleanup.UNAVAILABLE
                }
                val descriptor = context.contentResolver.openFileDescriptor(uri, "rw", signal)
                    ?: return@read PlaylistOutputCleanup.UNAVAILABLE
                descriptor.use { target ->
                    val status = Os.fstat(target.fileDescriptor)
                    if (!OsConstants.S_ISREG(status.st_mode) ||
                        PlaylistOutputIdentity(status.st_dev, status.st_ino) != owned.identity ||
                        target.statSize !in 0L..PlaylistExchangeLimits.BYTES) return@read PlaylistOutputCleanup.NOT_OWNED
                    Os.lseek(target.fileDescriptor, 0L, OsConstants.SEEK_SET)
                    val duplicate = ParcelFileDescriptor.dup(target.fileDescriptor)
                    val matches = ParcelFileDescriptor.AutoCloseInputStream(duplicate).use { actual ->
                        FileInputStream(source).use { expected -> prefixMatches(actual, expected, signal) }
                    }
                    if (!matches) return@read PlaylistOutputCleanup.NOT_OWNED
                    signal.throwIfCanceled()
                    // ftruncate acts on the verified descriptor, even if the provider remaps the URI later.
                    Os.ftruncate(target.fileDescriptor, 0L)
                    Os.fsync(target.fileDescriptor)
                    if (target.statSize != 0L) return@read PlaylistOutputCleanup.UNAVAILABLE
                    owned.wrote = false
                    owned.verified = false
                    PlaylistOutputCleanup.CLEARED
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) { PlaylistOutputCleanup.UNAVAILABLE }
    }

    /** The controller supplies a bounded deadline and retains ownership when this wait is cancelled. */
    suspend fun awaitPhysicalClose() { io.awaitIdle() }

    fun deletePrivate(file: File) {
        if (file.parentFile?.canonicalFile != directory.canonicalFile) fail(PlaylistIssue.RECOVERY_REQUIRED)
        if (file.exists() && !file.delete()) fail(PlaylistIssue.STORAGE_UNAVAILABLE)
    }

    private fun copy(input: InputStream, output: OutputStream?, signal: CancellationSignal): PlaylistFileProof {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32 * 1024)
        var count = 0L
        while (true) {
            signal.throwIfCanceled()
            val wanted = minOf(buffer.size.toLong(), PlaylistExchangeLimits.BYTES - count + 1L).toInt()
            val read = input.read(buffer, 0, wanted)
            signal.throwIfCanceled()
            if (read < 0) break
            if (read == 0 || read > wanted) fail(PlaylistIssue.SOURCE_UNAVAILABLE)
            if (read > PlaylistExchangeLimits.BYTES - count) exchangeFail(PlaylistExchangeIssue.LIMIT_EXCEEDED)
            output?.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            count += read
        }
        return PlaylistFileProof(count, playlistExchangeHex(digest.digest()))
    }

    private fun prefixMatches(actual: InputStream, expected: InputStream, signal: CancellationSignal): Boolean {
        val actualBytes = ByteArray(32 * 1024)
        val expectedBytes = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            signal.throwIfCanceled()
            val read = actual.read(actualBytes)
            if (read < 0) return true
            if (read == 0 || total + read > PlaylistExchangeLimits.BYTES) return false
            var offset = 0
            while (offset < read) {
                val count = expected.read(expectedBytes, offset, read - offset)
                if (count <= 0) return false
                offset += count
            }
            for (index in 0 until read) if (actualBytes[index] != expectedBytes[index]) return false
            total += read
        }
    }

    private fun fail(issue: PlaylistIssue): Nothing = throw PlaylistException(issue)
}

internal fun playlistExchangeHex(bytes: ByteArray): String {
    val digits = "0123456789abcdef"
    val result = CharArray(bytes.size * 2)
    for ((index, value) in bytes.withIndex()) {
        result[index * 2] = digits[(value.toInt() and 0xff) ushr 4]
        result[index * 2 + 1] = digits[value.toInt() and 0x0f]
    }
    return String(result)
}
