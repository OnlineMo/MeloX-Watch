package io.github.onlinemo.meloxwatch.data.lyrics.store

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder

internal open class LyricsDisplayFileFixture {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val sessions = mutableListOf<LyricsDisplayTestSession>()
    private var sequence = 0

    @After
    fun closeDisplayStores() = runBlocking {
        sessions.asReversed().forEach { it.close() }
        sessions.clear()
    }

    protected fun preferencesFile(): File = File(temporaryFolder.root, "display-${sequence++}.preferences_pb")

    protected fun openDisplay(
        file: File,
        wrap: (DataStore<Preferences>) -> DataStore<Preferences> = { it },
    ): LyricsDisplayTestSession {
        val fileJob = SupervisorJob()
        val observerJob = SupervisorJob()
        val backing = createLyricsDisplayDataStore(CoroutineScope(fileJob + Dispatchers.IO)) { file }
        val dataStore = wrap(backing)
        return LyricsDisplayTestSession(DataStoreLyricsDisplayStore(dataStore, CoroutineScope(observerJob + Dispatchers.IO)),
            observerJob, backing, fileJob).also { sessions.add(it) }
    }

    protected fun openPhysicalDisplay(
        storage: LyricsDisplayPhysicalStorage,
        wrap: (DataStore<Preferences>) -> DataStore<Preferences> = { it },
    ): LyricsDisplayTestSession {
        val fileJob = SupervisorJob()
        val observerJob = SupervisorJob()
        val backing = DataStoreFactory.create(storage = storage, corruptionHandler = null,
            migrations = emptyList(), scope = CoroutineScope(fileJob + Dispatchers.IO))
        return LyricsDisplayTestSession(DataStoreLyricsDisplayStore(wrap(backing), CoroutineScope(observerJob + Dispatchers.IO)),
            observerJob, backing, fileJob).also { sessions.add(it) }
    }

    protected suspend fun writeDisplayFixture(file: File, preferences: Preferences) {
        val job = SupervisorJob()
        try {
            createLyricsDisplayDataStore(CoroutineScope(job + Dispatchers.IO)) { file }.updateData { preferences }
        } finally { job.cancelAndJoin() }
    }
}

internal class LyricsDisplayTestSession(
    val store: LyricsDisplayStore,
    private val observerJob: Job,
    val backingDataStore: DataStore<Preferences>,
    private val fileJob: Job,
) {
    val observerCancelled: Boolean get() = observerJob.isCancelled
    val fileOwnerActive: Boolean get() = fileJob.isActive
    suspend fun ready(): LyricsDisplayState.Ready = withTimeout(10000L) {
        store.state.filterIsInstance<LyricsDisplayState.Ready>().first()
    }

    suspend fun unavailable(): LyricsDisplayState.Unavailable = withTimeout(10000L) {
        store.state.filterIsInstance<LyricsDisplayState.Unavailable>().first()
    }

    suspend fun close() = withContext(NonCancellable) {
        // The observer's real identity read-back is NonCancellable. Keep its DataStore actor
        // alive until that read drains, then await the actual file owner's write/close cleanup.
        try { observerJob.cancelAndJoin() }
        finally { fileJob.cancelAndJoin() }
    }
}

/** Scheduling adapters delegate all actual persistence to the real file-backed DataStore. */
internal open class ForwardingDisplayDataStore(protected val delegate: DataStore<Preferences>) : DataStore<Preferences> {
    override val data: Flow<Preferences> get() = delegate.data
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = delegate.updateData(transform)
}

internal class DisplayObservationGate {
    val captured = CompletableDeferred<Preferences>()
    val release = CompletableDeferred<Unit>()
    val delivered = CompletableDeferred<Unit>()
    val continueReads = CompletableDeferred<Unit>()
    val restarted = CompletableDeferred<Unit>()
}

internal class HeldDisplayObservationDataStore(
    delegate: DataStore<Preferences>,
    private val gate: DisplayObservationGate,
    private val oldSnapshot: Preferences? = null,
    private val failAfterOld: Boolean = false,
) : ForwardingDisplayDataStore(delegate) {
    private val holdFirst = AtomicBoolean(true)
    private val subscriptions = AtomicInteger()
    override val data: Flow<Preferences> = flow {
        if (subscriptions.incrementAndGet() > 1) gate.restarted.complete(Unit)
        delegate.data.collect { stored ->
            if (holdFirst.compareAndSet(true, false)) {
                gate.captured.complete(stored)
                gate.release.await()
                emit(oldSnapshot ?: stored)
                gate.delivered.complete(Unit)
                gate.continueReads.await()
                if (failAfterOld) throw IOException("controlled late observation error")
            } else {
                emit(stored)
            }
        }
    }
}

internal class DisplayMutationGate {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    private val armed = AtomicBoolean(false)

    fun arm() { check(armed.compareAndSet(false, true)) }
    fun take(): Boolean = armed.compareAndSet(true, false)
}

internal class HeldDisplayMutationDataStore(
    delegate: DataStore<Preferences>,
    private val gate: DisplayMutationGate,
    private val afterCommit: Boolean = false,
) : ForwardingDisplayDataStore(delegate) {
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        if (afterCommit) {
            val held = gate.take()
            val committed = delegate.updateData(transform)
            if (held) {
                gate.entered.complete(Unit)
                gate.release.await()
            }
            return committed
        }
        return delegate.updateData { stored ->
            if (gate.take()) {
                gate.entered.complete(Unit)
                gate.release.await()
            }
            transform(stored)
        }
    }
}

internal class DisplayPostCommitErrorDataStore(delegate: DataStore<Preferences>) : ForwardingDisplayDataStore(delegate) {
    private val failOnce = AtomicBoolean(true)
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        var changed = false
        val committed = delegate.updateData { stored ->
            transform(stored).also { changed = it != stored }
        }
        if (changed && failOnce.compareAndSet(true, false)) throw IOException("controlled error after durable commit")
        return committed
    }
}

/** Completion follows emit returning, so the application's durable read/publication has finished. */
internal class CompletingDisplayObservationsDataStore(delegate: DataStore<Preferences>) : ForwardingDisplayDataStore(delegate) {
    private val count = AtomicInteger()
    val completions = MutableStateFlow(0)
    override val data: Flow<Preferences> = flow {
        delegate.data.collect { stored ->
            emit(stored)
            completions.value = count.incrementAndGet()
        }
    }
}

internal class CountingDisplayReadsDataStore(
    delegate: DataStore<Preferences>,
    private val failFirst: Boolean = false,
) : ForwardingDisplayDataStore(delegate) {
    private val count = AtomicInteger()
    val subscriptions = MutableStateFlow(0)
    override val data: Flow<Preferences> = flow {
        val attempt = count.incrementAndGet()
        subscriptions.value = attempt
        if (failFirst && attempt == 1) throw IOException("controlled first read failure")
        delegate.data.collect { emit(it) }
    }
}
