package io.github.onlinemo.meloxwatch.data.fonts.store

import androidx.datastore.core.FileStorage
import androidx.datastore.core.Serializer
import androidx.datastore.core.Storage
import androidx.datastore.core.StorageConnection
import androidx.datastore.core.WriteScope
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import okio.buffer
import okio.sink
import okio.source

internal class FontSettingsPhysicalWriteGate(val afterScratchWrite: Boolean = false) {
    val entered = CompletableDeferred<Preferences>()
    val release = CompletableDeferred<Unit>()
}

/** Holds the real FileStorage writer after transform, including scratch sync before atomic move. */
internal class FontSettingsPhysicalStorage(file: File) : Storage<Preferences> {
    private val delegate = FileStorage(FontSettingsPreferencesSerializer) { file }
    private val nextGate = AtomicReference<FontSettingsPhysicalWriteGate?>()
    private val nextFailureRestoration = AtomicReference<(() -> Unit)?>()
    val writeScopes = AtomicInteger()
    val exitedWrites = AtomicInteger()
    val committedWrites = AtomicInteger()
    val failedWrites = AtomicInteger()

    fun holdNextWrite(gate: FontSettingsPhysicalWriteGate) { check(nextGate.compareAndSet(null, gate)) }

    // Restore a fixture's obstructed destination only after FileStorage has really failed and
    // removed scratch; the Application actor has not yet delivered its failure acknowledgement.
    fun afterNextFailedWrite(restore: () -> Unit) { check(nextFailureRestoration.compareAndSet(null, restore)) }

    override fun createConnection(): StorageConnection<Preferences> {
        val connection = delegate.createConnection()
        return object : StorageConnection<Preferences> by connection {
            override suspend fun writeScope(block: suspend WriteScope<Preferences>.() -> Unit) {
                writeScopes.incrementAndGet()
                try {
                    connection.writeScope {
                        val writer = this
                        block(object : WriteScope<Preferences> by writer {
                            override suspend fun writeData(value: Preferences) {
                                val gate = nextGate.getAndSet(null)
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
                } catch (error: IOException) {
                    failedWrites.incrementAndGet()
                    nextFailureRestoration.getAndSet(null)?.invoke()
                    throw error
                } finally { exitedWrites.incrementAndGet() }
            }
        }
    }
}

/** Public PreferencesSerializer supplies the production protobuf bytes; this only adapts streams. */
private object FontSettingsPreferencesSerializer : Serializer<Preferences> {
    override val defaultValue: Preferences get() = PreferencesSerializer.defaultValue

    override suspend fun readFrom(input: InputStream): Preferences =
        input.source().buffer().use { PreferencesSerializer.readFrom(it) }

    override suspend fun writeTo(t: Preferences, output: OutputStream) {
        output.sink().buffer().use { PreferencesSerializer.writeTo(t, it) }
    }
}
