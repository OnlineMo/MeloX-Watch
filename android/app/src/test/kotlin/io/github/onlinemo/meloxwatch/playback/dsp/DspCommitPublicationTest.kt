package io.github.onlinemo.meloxwatch.playback.dsp

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.onlinemo.meloxwatch.data.backup.BackupAccessState
import io.github.onlinemo.meloxwatch.data.backup.BackupMutationBarrier
import io.github.onlinemo.meloxwatch.ui.dsp.DspViewModel
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 真实 DataStore 提交、服务观察和页面应答；不回读来掩盖取消后的缺失发布。 */
@OptIn(ExperimentalCoroutinesApi::class)
class DspCommitPublicationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val sessions = ArrayList<Session>()
    private val serviceJobs = ArrayList<Job>()
    private val serviceOwners = ArrayList<ServiceDspSettingsOwner>()
    private val editors = ArrayList<DspViewModel>()
    private val editorStores = ArrayList<ViewModelStore>()

    @Before fun bindMainDispatcher() { Dispatchers.setMain(Dispatchers.Unconfined) }

    @After fun closeOwnersAndStores() = runBlocking {
        try {
            editors.forEach { it.setVisible(false) }
            editorStores.forEach { it.clear() }
            serviceOwners.forEach { it.close() }
            serviceJobs.forEach { it.cancelAndJoin() }
            for (session in sessions.asReversed()) session.job.cancelAndJoin()
        } finally { Dispatchers.resetMain() }
    }

    @Test(timeout = 30_000) fun cancelledSuccessfulWritePublishesToTheServiceAndNextSinkWithoutARead() = runBlocking {
        val barrier = BackupMutationBarrier()
        val held = HeldWrite()
        val session = session(barrier, held)
        initialize(session, barrier)
        val runtime = DspRuntime()
        service(session.store, runtime).observe()
        val previewOwner = Any()
        assertTrue(runtime.preview(previewOwner, 0, DspSettings(leftPercent = 25)))
        var acknowledgement: DspSaveResult? = null
        val writer = launch(Dispatchers.Default) {
            acknowledgement = session.store.compareAndSet(0, DspSettings(leftPercent = 50), emptyList())
        }
        try {
            withTimeout(5_000) { held.entered.await() }
            writer.cancel()
            assertFalse(writer.isCompleted)
            assertEquals(0L, session.store.state.value.saved?.revision)
            held.release.complete(Unit)
            withTimeout(5_000) { writer.join() }
            assertTrue(writer.isCancelled)
            assertNull(acknowledgement)
            assertEquals(DspStored(1, DspSettings(leftPercent = 50)), session.store.state.value.saved)
            awaitRuntime(runtime, DspSettings(leftPercent = 50))
            assertFalse(runtime.hasPreview(previewOwner))
            val nextSink = runtime.attachSink()
            assertEquals(50, runtime.state.value.requested.leftPercent)
            runtime.releaseSink(nextSink)
            assertEquals(1, held.attempts.get())
        } finally {
            held.release.complete(Unit)
            writer.cancelAndJoin()
        }
    }

    @Test(timeout = 30_000) fun cancelledFailedWriteCannotPublishTheProposalOrRetryIt() = runBlocking {
        val barrier = BackupMutationBarrier()
        val held = HeldWrite(fail = true)
        val session = session(barrier, held)
        initialize(session, barrier)
        val before = session.file.readBytes()
        val runtime = DspRuntime()
        service(session.store, runtime).observe()
        var acknowledgement: DspSaveResult? = null
        val writer = launch(Dispatchers.Default) {
            acknowledgement = session.store.compareAndSet(0, DspSettings(rightPercent = 25), emptyList())
        }
        try {
            withTimeout(5_000) { held.entered.await() }
            writer.cancel()
            held.release.complete(Unit)
            withTimeout(5_000) { writer.join() }
            assertTrue(writer.isCancelled)
            assertNull(acknowledgement)
            assertEquals(DspStored(), session.store.state.value.saved)
            assertEquals(DspSettings(), runtime.currentSettings())
            assertArrayEquals(before, session.file.readBytes())
            assertEquals(1, held.attempts.get())
        } finally {
            held.release.complete(Unit)
            writer.cancelAndJoin()
        }
    }

    @Test(timeout = 30_000) fun cancelledExclusiveDspCommitRemainsDeferredUntilTheRealOwnerExits() = runBlocking {
        val barrier = BackupMutationBarrier()
        val held = HeldWrite()
        val session = session(barrier, held)
        initialize(session, barrier)
        val previous = session.store.state.value
        var acknowledgement: DspSaveResult? = null
        val exclusiveOwner = launch(Dispatchers.Default) {
            barrier.exclusively {
                acknowledgement = session.store.compareAndSet(0, DspSettings(rightPercent = 50), emptyList())
            }
        }
        try {
            withTimeout(5_000) { held.entered.await() }
            exclusiveOwner.cancel()
            assertFalse(exclusiveOwner.isCompleted)
            assertEquals(BackupAccessState.EXCLUSIVE_OPERATION, barrier.state.value)
            assertEquals(previous, session.store.state.value)
            held.release.complete(Unit)
            withTimeout(5_000) { exclusiveOwner.join() }
            assertNull(acknowledgement)
            assertEquals(BackupAccessState.READY, barrier.state.value)
            assertEquals(DspStored(1, DspSettings(rightPercent = 50)), session.store.state.value.saved)
            assertEquals(1, held.attempts.get())
        } finally {
            held.release.complete(Unit)
            exclusiveOwner.cancelAndJoin()
        }
    }

    @Test(timeout = 30_000) fun lateServiceInitializationReadCannotUndoANewerBackupCas() = runBlocking {
        val barrier = BackupMutationBarrier()
        val session = session(barrier)
        initialize(session, barrier)
        val readCompleted = CompletableDeferred<DspSnapshot>()
        val deliverRead = CompletableDeferred<Unit>()
        val delayedRead = object : DspSettingsStore by session.store {
            override suspend fun read(): DspSnapshot {
                val actual = session.store.read()
                readCompleted.complete(actual)
                deliverRead.await()
                return actual
            }
        }
        val runtime = DspRuntime()
        val owner = service(delayedRead, runtime)
        owner.observe()
        val initializer = async(Dispatchers.Default) { owner.initialize() }
        try {
            assertEquals(0L, withTimeout(5_000) { readCompleted.await() }.saved?.revision)
            barrier.exclusively {
                assertTrue(session.store.compareAndSet(0, DspSettings(leftPercent = 75), emptyList()) is DspSaveResult.Saved)
            }
            awaitRuntime(runtime, DspSettings(leftPercent = 75))
            deliverRead.complete(Unit)
            withTimeout(5_000) { initializer.await() }
            assertEquals(DspSettings(leftPercent = 75), runtime.currentSettings())
            assertEquals(1L, session.store.state.value.saved?.revision)
        } finally {
            deliverRead.complete(Unit)
            initializer.cancelAndJoin()
        }
    }

    @Test(timeout = 30_000) fun lateSavedAcknowledgementCannotReplaceANewerPublishedEditorGeneration() = runBlocking {
        val barrier = BackupMutationBarrier()
        val session = session(barrier)
        initialize(session, barrier)
        val committed = CompletableDeferred<DspSaveResult>()
        val deliverAcknowledgement = CompletableDeferred<Unit>()
        val delayed = object : DspSettingsStore by session.store {
            override suspend fun compareAndSet(expectedRevision: Long, settings: DspSettings,
                presets: List<DspPreset>): DspSaveResult {
                val actual = session.store.compareAndSet(expectedRevision, settings, presets)
                committed.complete(actual)
                deliverAcknowledgement.await()
                return actual
            }
        }
        val runtime = DspRuntime()
        val model = editor(delayed, runtime, barrier)
        model.setVisible(true)
        withTimeout(5_000) { model.state.first { it.editable } }
        model.change(DspSettings(leftPercent = 25))
        model.save()
        try {
            val first = withTimeout(5_000) { committed.await() } as DspSaveResult.Saved
            assertEquals(1L, first.snapshot.saved?.revision)
            barrier.exclusively {
                assertTrue(session.store.compareAndSet(1, DspSettings(rightPercent = 50), emptyList()) is DspSaveResult.Saved)
            }
            withTimeout(5_000) { model.state.first { it.baseRevision == 2L } }
            deliverAcknowledgement.complete(Unit)
            withTimeout(5_000) { model.state.first { !it.working } }
            assertEquals(2L, model.state.value.baseRevision)
            assertEquals(DspSettings(rightPercent = 50), model.state.value.saved.saved?.settings)
            assertEquals(DspSettings(rightPercent = 50), model.state.value.draft)
            assertEquals(DspSettings(rightPercent = 50), runtime.currentSettings())
            assertFalse(model.state.value.savedFeedback)
            assertEquals(DspSettingsIssue.CONFLICT, model.state.value.storageIssue)
        } finally { deliverAcknowledgement.complete(Unit) }
    }

    @Test(timeout = 30_000) fun hidingDuringCommitStillPublishesDurableValuesWithoutSaveFeedbackOrRetry() = runBlocking {
        val barrier = BackupMutationBarrier()
        val held = HeldWrite()
        val session = session(barrier, held)
        initialize(session, barrier)
        val runtime = DspRuntime()
        service(session.store, runtime).observe()
        val model = editor(session.store, runtime, barrier)
        model.setVisible(true)
        withTimeout(5_000) { model.state.first { it.editable } }
        model.change(DspSettings(leftPercent = 50))
        model.save()
        try {
            withTimeout(5_000) { held.entered.await() }
            model.setVisible(false)
            assertTrue(model.state.value.working)
            held.release.complete(Unit)
            withTimeout(5_000) { model.state.first { !it.working } }
            assertEquals(DspStored(1, DspSettings(leftPercent = 50)), session.store.state.value.saved)
            awaitRuntime(runtime, DspSettings(leftPercent = 50))
            assertFalse(model.state.value.savedFeedback)
            assertTrue(model.state.value.requiresRead)
            assertFalse(model.state.value.previewEnabled)
            assertEquals(1, held.attempts.get())
        } finally { held.release.complete(Unit) }
    }

    private class HeldWrite(private val fail: Boolean = false) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val attempts = AtomicInteger()

        suspend fun beforeCommit() {
            if (attempts.incrementAndGet() != 1) return
            entered.complete(Unit)
            // transform 的取消检查已通过，模拟尚未返回的真实 actor 提交阶段。
            withContext(NonCancellable) { release.await() }
            if (fail) throw IOException("Controlled durable write failure")
        }
    }

    private data class Session(
        val file: File,
        val job: Job,
        val raw: DataStore<Preferences>,
        val store: DataStoreDspSettingsStore,
    )

    private fun session(barrier: BackupMutationBarrier, held: HeldWrite? = null): Session {
        val file = File(temporary.root, "dsp-${sessions.size}.preferences_pb")
        val job = SupervisorJob()
        val raw = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file })
        val controlled = object : DataStore<Preferences> by raw {
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                raw.updateData { stored ->
                    val actual = transform(stored)
                    if (actual != stored) held?.beforeCommit()
                    actual
                }
        }
        return Session(file, job, raw, DataStoreDspSettingsStore(controlled, barrier)).also { sessions += it }
    }

    private suspend fun initialize(session: Session, barrier: BackupMutationBarrier) {
        session.raw.updateData { DspSettingsCodec.encode(DspStored()) }
        barrier.initializeStorage {
            val actual = session.store.read()
            check(actual.saved != null && actual.issue == null)
        }
        assertEquals(BackupAccessState.READY, barrier.state.value)
        assertEquals(DspStored(), session.store.state.value.saved)
        assertTrue(session.file.isFile)
    }

    private fun service(store: DspSettingsStore, runtime: DspRuntime): ServiceDspSettingsOwner {
        val job = SupervisorJob()
        serviceJobs += job
        return ServiceDspSettingsOwner(store, runtime, CoroutineScope(job + Dispatchers.Unconfined)).also { serviceOwners += it }
    }

    private fun editor(store: DspSettingsStore, runtime: DspRuntime, barrier: BackupMutationBarrier): DspViewModel {
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        editorStores += owner.viewModelStore
        return ViewModelProvider(owner, viewModelFactory {
            initializer { DspViewModel(store, runtime, barrier) }
        })[DspViewModel::class.java].also { editors += it }
    }

    private suspend fun awaitRuntime(runtime: DspRuntime, expected: DspSettings) = withTimeout(5_000) {
        while (runtime.currentSettings() != expected) yield()
    }
}
