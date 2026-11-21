package io.github.onlinemo.meloxwatch.data.auth

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** AtomicFile receives ciphertext only; its pending/backup files remain inside noBackupFilesDir. */
internal class AtomicEncryptedSessionStore private constructor(private val file: File, private val cipher: SessionCipher) : SessionStore {
    private val atomic = AtomicFile(file)
    private val mutex = Mutex()
    private val owner = Any()

    override suspend fun load(): StoredSession? = io {
        val bytes = try { readEncrypted() } catch (_: FileNotFoundException) {
            if (file.exists() || File(file.path + ".bak").exists()) throw AuthException(AuthIssue.STORAGE_UNAVAILABLE)
            return@io null
        }
        cipher.open(bytes)
    }

    override suspend fun seal(session: VerifiedSession): SealedSession = io {
        SealedSession(owner, cipher.seal(session.stored))
    }

    override suspend fun write(session: SealedSession): Unit = io {
        val bytes = session.bytes(owner)
        try {
            val output = atomic.startWrite()
            var finishing = false
            try {
                output.write(bytes)
                output.flush()
                output.fd.sync()
                finishing = true
                atomic.finishWrite(output)
                // After finish was attempted, a failed readback is an unknown disk outcome, not a safe rollback.
                if (!MessageDigest.isEqual(bytes, readEncrypted())) throw AuthException(AuthIssue.STORAGE_UNAVAILABLE)
            } catch (error: Throwable) {
                if (!finishing) atomic.failWrite(output)
                throw error
            } finally { output.close() }
        } finally {
            bytes.fill(0)
        }
    }

    override suspend fun clear(): Unit = io {
        atomic.delete()
        // AtomicFile's pending suffix changed across Android versions; clear only our exact reserved files.
        val ownFiles = listOf(file, File(file.path + ".bak"), File(file.path + ".new"))
        for (ownFile in ownFiles) {
            if (ownFile.exists() && !ownFile.delete()) throw AuthException(AuthIssue.STORAGE_UNAVAILABLE)
        }
        if (ownFiles.any(File::exists)) throw AuthException(AuthIssue.STORAGE_UNAVAILABLE)
    }

    private suspend fun <T> io(action: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            try { action() }
            catch (_: IOException) { throw AuthException(AuthIssue.STORAGE_UNAVAILABLE) }
            catch (_: SecurityException) { throw AuthException(AuthIssue.STORAGE_UNAVAILABLE) }
        }
    }

    private fun readEncrypted(): ByteArray = atomic.openRead().use { input ->
        val output = ByteArrayOutputStream(8192)
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer, 0, minOf(buffer.size, SessionCipher.MAX_ENCRYPTED_BYTES - output.size() + 1))
            if (read < 0) break
            if (read == 0 || output.size() + read > SessionCipher.MAX_ENCRYPTED_BYTES) throw AuthException(AuthIssue.SESSION_CORRUPT)
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    }

    companion object {
        suspend fun create(context: Context): AtomicEncryptedSessionStore = withContext(Dispatchers.IO) {
            val application = context.applicationContext
            try {
                val directory = File(application.noBackupFilesDir, "sessions")
                if (!directory.isDirectory && !directory.mkdirs()) throw AuthException(AuthIssue.STORAGE_UNAVAILABLE)
                AtomicEncryptedSessionStore(File(directory, "netease.v1.enc"), SessionCipher(AndroidSessionKeys(), application.packageName))
            } catch (_: SecurityException) { throw AuthException(AuthIssue.STORAGE_UNAVAILABLE) }
        }

        internal fun controlled(file: File, cipher: SessionCipher): AtomicEncryptedSessionStore = AtomicEncryptedSessionStore(file, cipher)
    }
}
