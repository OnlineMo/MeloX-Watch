package io.github.onlinemo.meloxwatch.data.lyrics.store

import androidx.datastore.core.FileStorage
import androidx.datastore.core.Serializer
import androidx.datastore.core.Storage
import androidx.datastore.core.StorageConnection
import androidx.datastore.core.WriteScope
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import okio.buffer
import okio.sink
import okio.source

internal class LyricsDisplayPhysicalWriteGate(val afterScratchWrite: Boolean = false) {
    val entered = CompletableDeferred<Preferences>()
    val release = CompletableDeferred<Unit>()
    private val failureRestore = AtomicReference<(() -> Unit)?>()

    fun restoreOriginalOnFailure(restore: () -> Unit) {
        check(afterScratchWrite)
        check(failureRestore.compareAndSet(null, restore))
    }

    fun restoreBeforeRethrow(failure: Throwable) {
        val restore = failureRestore.getAndSet(null) ?: return
        try { restore() }
        catch (restoreFailure: Throwable) {
            if (restoreFailure !== failure) failure.addSuppressed(restoreFailure)
        }
    }
}

/** Gates the public FileStorage writer after transform or real scratch sync, before atomic move. */
internal class LyricsDisplayPhysicalStorage(file: File) : Storage<Preferences> {
    private val delegate = FileStorage(LyricsDisplayPreferencesSerializer) { file }
    private val nextGate = AtomicReference<LyricsDisplayPhysicalWriteGate?>()
    val writeScopes = AtomicInteger()
    val exitedWrites = AtomicInteger()
    val committedWrites = AtomicInteger()

    fun holdNextWrite(gate: LyricsDisplayPhysicalWriteGate) { check(nextGate.compareAndSet(null, gate)) }

    override fun createConnection(): StorageConnection<Preferences> {
        val connection = delegate.createConnection()
        return object : StorageConnection<Preferences> by connection {
            override suspend fun writeScope(block: suspend WriteScope<Preferences>.() -> Unit) {
                writeScopes.incrementAndGet()
                var activeGate: LyricsDisplayPhysicalWriteGate? = null
                try {
                    connection.writeScope {
                        val writer = this
                        block(object : WriteScope<Preferences> by writer {
                            override suspend fun writeData(value: Preferences) {
                                val gate = nextGate.getAndSet(null)?.also { activeGate = it }
                                if (gate != null && !gate.afterScratchWrite) {
                                    gate.entered.complete(value)
                                    gate.release.await()
                                }
                                writer.writeData(value)
                                if (gate != null && gate.afterScratchWrite) {
                                    gate.entered.complete(value)
                                    gate.release.await()
                                }
                            }
                        })
                    }
                    committedWrites.incrementAndGet()
                } catch (failure: Throwable) {
                    // The outer DataStore coordinator still holds its lock. Restore only the
                    // controlled target obstruction before ACK/readback, retaining the real failure.
                    activeGate?.restoreBeforeRethrow(failure)
                    throw failure
                } finally { exitedWrites.incrementAndGet() }
            }
        }
    }
}

/** The public serializer supplies production protobuf bytes; only the stream interface is adapted. */
private object LyricsDisplayPreferencesSerializer : Serializer<Preferences> {
    override val defaultValue: Preferences get() = PreferencesSerializer.defaultValue

    override suspend fun readFrom(input: InputStream): Preferences =
        input.source().buffer().use { PreferencesSerializer.readFrom(it) }

    override suspend fun writeTo(t: Preferences, output: OutputStream) {
        output.sink().buffer().use { PreferencesSerializer.writeTo(t, it) }
    }
}
