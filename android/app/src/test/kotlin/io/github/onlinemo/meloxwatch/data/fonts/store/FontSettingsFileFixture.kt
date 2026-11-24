package io.github.onlinemo.meloxwatch.data.fonts.store

import androidx.datastore.core.DataStore
import androidx.datastore.core.Storage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSettings
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSettingsStore
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.rules.TemporaryFolder

internal open class FontSettingsFileFixture {
    @get:Rule val temporary = TemporaryFolder()
    private val sessions = mutableListOf<FontSettingsTestSession>()
    private var sequence = 0

    @After
    fun closeFontSettingsFiles() = runBlocking {
        sessions.asReversed().forEach { it.close() }
        sessions.clear()
    }

    protected fun settingsFile(): File = File(temporary.root, "font-settings-${sequence++}.preferences_pb")

    protected fun openSettings(
        file: File,
        wrap: (DataStore<Preferences>) -> DataStore<Preferences> = { it },
    ): FontSettingsTestSession {
        val job = SupervisorJob()
        val dataStore = wrap(createLyricsFontSettingsDataStore(CoroutineScope(job + Dispatchers.IO)) { file })
        return FontSettingsTestSession(DataStoreLyricsFontSettingsStore(dataStore), dataStore, job).also { sessions.add(it) }
    }

    protected fun openStorageSettings(storage: Storage<Preferences>): FontSettingsTestSession {
        val job = SupervisorJob()
        val dataStore = PreferenceDataStoreFactory.create(storage = storage, scope = CoroutineScope(job + Dispatchers.IO))
        return FontSettingsTestSession(DataStoreLyricsFontSettingsStore(dataStore), dataStore, job).also { sessions.add(it) }
    }

    protected suspend fun writeSettingsFixture(file: File, preferences: Preferences) {
        val job = SupervisorJob()
        try {
            createLyricsFontSettingsDataStore(CoroutineScope(job + Dispatchers.IO)) { file }.updateData { preferences }
        } finally { job.cancelAndJoin() }
    }
}

internal class FontSettingsTestSession(
    actualStore: LyricsFontSettingsStore,
    val dataStore: DataStore<Preferences>,
    private val job: Job,
) {
    // The fixture identifies an explicit read before a serializer can fail. Both production
    // operations use updateData; the marker keeps the original read faults/gates distinct from CAS.
    val store: LyricsFontSettingsStore = object : LyricsFontSettingsStore by actualStore {
        override suspend fun read(): LyricsFontSettings = withContext(FontSettingsReadOperation) { actualStore.read() }
    }

    suspend fun close() { job.cancelAndJoin() }
}

private object FontSettingsReadKey : CoroutineContext.Key<FontSettingsReadOperation>
private object FontSettingsReadOperation : AbstractCoroutineContextElement(FontSettingsReadKey)

internal class FontSettingsWriteGate(val afterCommit: Boolean = false) {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
}

internal class FontSettingsReadGate(val ignoreCancellation: Boolean = false) {
    val captured = CompletableDeferred<Preferences>()
    val release = CompletableDeferred<Unit>()
}

/** Faults and gates surround the real DataStore; no in-memory setting substitutes for file persistence. */
internal class ControlledFontSettingsDataStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
    val readSubscriptions = AtomicInteger()
    val writeCalls = AtomicInteger()
    val failReads = AtomicBoolean(false)
    val failReadAllocation = AtomicBoolean(false)
    val failAfterNextWrite = AtomicBoolean(false)
    private val writeGate = AtomicReference<FontSettingsWriteGate?>()
    private val readGate = AtomicReference<FontSettingsReadGate?>()

    fun holdNextWrite(gate: FontSettingsWriteGate) { check(writeGate.compareAndSet(null, gate)) }
    fun holdNextRead(gate: FontSettingsReadGate) { check(readGate.compareAndSet(null, gate)) }

    override val data: Flow<Preferences> = flow {
        beginRead()
        delegate.data.collect { stored -> emit(gatedRead(stored)) }
    }

    private fun beginRead() {
        readSubscriptions.incrementAndGet()
        if (failReads.get()) throw IOException("Controlled setting read failure")
        if (failReadAllocation.get()) throw OutOfMemoryError("Controlled setting read allocation failure")
    }

    private suspend fun gatedRead(stored: Preferences): Preferences {
        readGate.getAndSet(null)?.let { gate ->
            gate.captured.complete(stored)
            if (gate.ignoreCancellation) withContext(NonCancellable) { gate.release.await() }
            else gate.release.await()
        }
        return stored
    }

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        if (currentCoroutineContext()[FontSettingsReadKey] != null) {
            beginRead()
            return gatedRead(delegate.updateData(transform))
        }
        writeCalls.incrementAndGet()
        val gate = writeGate.getAndSet(null)
        val committed = delegate.updateData { stored ->
            if (gate != null && !gate.afterCommit) {
                gate.entered.complete(Unit)
                gate.release.await()
            }
            transform(stored)
        }
        if (gate != null && gate.afterCommit) {
            gate.entered.complete(Unit)
            gate.release.await()
        }
        if (failAfterNextWrite.compareAndSet(true, false)) throw IOException("Controlled error after durable setting commit")
        return committed
    }
}

internal suspend fun expectFontSettingsIssue(issue: LyricsFontSettingsStorageIssue, action: suspend () -> Unit) {
    try {
        action()
        throw AssertionError("Expected $issue")
    } catch (error: LyricsFontSettingsStorageException) { assertEquals(issue, error.issue) }
}
