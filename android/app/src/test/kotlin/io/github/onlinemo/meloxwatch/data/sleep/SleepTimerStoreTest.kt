package io.github.onlinemo.meloxwatch.data.sleep

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import io.github.onlinemo.meloxwatch.playback.sleep.SleepTimerClockSample
import io.github.onlinemo.meloxwatch.playback.sleep.SleepTimerEndMode
import io.github.onlinemo.meloxwatch.playback.sleep.SleepTimerReducer
import io.github.onlinemo.meloxwatch.playback.sleep.SleepTimerState
import io.github.onlinemo.meloxwatch.playback.sleep.SleepTimerTrackBinding
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SleepTimerStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val jobs = mutableListOf<Job>()

    @After fun closeStores() = runBlocking {
        jobs.asReversed().forEach { it.cancelAndJoin() }
        jobs.clear()
    }

    private fun timer() = SleepTimerReducer.arm(1, SleepTimerEndMode.AFTER_CURRENT_TRACK,
        SleepTimerClockSample("fixture-boot", 1_000L)).state

    @Test(timeout = 30000L) fun activeAndCancelledTimersSurviveRealFileReopenWithoutPlayAuthority() = runBlocking {
        val file = file()
        val first = session(file)
        val opened = first.store.open().ready()
        assertEquals(SleepTimerState(), opened.stored.state)
        val armed = first.store.save(opened, timer()).saved().lease
        assertEquals(1L, armed.stored.revision)
        first.job.cancelAndJoin()
        val second = session(file)
        val restored = second.store.open().ready()
        assertEquals(armed.stored.state, restored.stored.state)
        val paused = SleepTimerReducer.restore(restored.stored.state, SleepTimerClockSample("fixture-boot", 2_000L))
        assertTrue(paused.pauseRequired)
        val cancelled = SleepTimerReducer.cancel(paused.state).state
        second.store.save(restored, cancelled)
        second.job.cancelAndJoin()
        assertEquals(cancelled, session(file).store.open().ready().stored.state)
    }

    @Test(timeout = 30000L) fun aNewServiceLeaseFencesOldWritesAndOldTeardown() = runBlocking {
        val session = session(file())
        val previous = session.store.open().ready()
        val first = session.store.save(previous, timer()).saved().lease
        val current = session.store.open().ready()
        val oldCancel = session.store.save(first, SleepTimerReducer.cancel(first.stored.state).state)
        assertEquals(SleepTimerStoreIssue.RETIRED_OWNER, (oldCancel as SleepTimerStoreWriteResult.Failed).issue)
        session.store.retire(first)
        val changed = session.store.save(current, SleepTimerReducer.cancel(current.stored.state).state).saved()
        assertTrue(changed.changed)
        assertEquals(2L, changed.lease.stored.revision)
    }

    @Test(timeout = 30000L) fun aStaleRevisionCannotOverwriteTheAcceptedReplacement() = runBlocking {
        val session = session(file())
        val opened = session.store.open().ready()
        val accepted = session.store.save(opened, timer()).saved().lease
        val conflict = session.store.save(opened, SleepTimerState()) as SleepTimerStoreWriteResult.Conflict
        assertEquals(accepted.stored, conflict.current)
        assertEquals(accepted.stored.state, session.store.open().ready().stored.state)
    }

    @Test(timeout = 30000L) fun repeatedCanonicalStateDoesNotRewriteOrIncreaseRevision() = runBlocking {
        val file = file()
        val session = session(file)
        val accepted = session.store.save(session.store.open().ready(), timer()).saved().lease
        val original = file.readBytes()
        val replay = session.store.save(accepted, accepted.stored.state).saved()
        assertFalse(replay.changed)
        assertEquals(accepted.stored.revision, replay.lease.stored.revision)
        assertArrayEquals(original, file.readBytes())
    }

    @Test(timeout = 30000L) fun validCoreWaitingStatesWithLongIdentitiesFailWithoutChangingTheAcceptedFile() = runBlocking {
        val file = file()
        val session = session(file)
        val accepted = session.store.save(session.store.open().ready(), timer()).saved().lease
        val armed = accepted.stored.state
        val token = requireNotNull(armed.active).token
        val original = file.readBytes()
        val longIdentity = "x".repeat(129)
        val bindings = listOf(
            SleepTimerTrackBinding(longIdentity, "queue", "entry", 0L),
            SleepTimerTrackBinding("session", longIdentity, "entry", 0L),
            SleepTimerTrackBinding("session", "queue", longIdentity, 0L),
        )
        for (binding in bindings) {
            val waiting = SleepTimerReducer.onDeadline(armed, token,
                SleepTimerClockSample("fixture-boot", 61_000L), binding).state
            assertEquals(binding, waiting.active?.waitingFor)
            val refused = session.store.save(accepted, waiting) as SleepTimerStoreWriteResult.Failed
            assertEquals(SleepTimerStoreIssue.INPUT_BOUND, refused.issue)
            assertArrayEquals(original, file.readBytes())
        }
        val cancelled = SleepTimerReducer.cancel(armed).state
        val retry = session.store.save(accepted, cancelled).saved().lease
        assertEquals(accepted.stored.revision + 1L, retry.stored.revision)
        session.job.cancelAndJoin()
        assertEquals(cancelled, session(file).store.open().ready().stored.state)
    }

    @Test(timeout = 30000L) fun boundaryLengthWaitingIdentitiesSurviveARealFileReopenWithoutTruncation() = runBlocking {
        val file = file()
        val first = session(file)
        val armed = timer()
        val binding = SleepTimerTrackBinding("s".repeat(128), "q".repeat(128), "e".repeat(128), 7L)
        val waiting = SleepTimerReducer.onDeadline(armed, requireNotNull(armed.active).token,
            SleepTimerClockSample("fixture-boot", 61_000L), binding).state
        val accepted = first.store.save(first.store.open().ready(), waiting).saved().lease
        assertEquals(binding, accepted.stored.state.active?.waitingFor)
        first.job.cancelAndJoin()
        val reopened = session(file).store.open().ready().stored
        assertEquals(accepted.stored.revision, reopened.revision)
        assertEquals(waiting, reopened.state)
        assertNull(reopened.recovery)
    }

    @Test(timeout = 30000L) fun futureSchemaAndRevisionOverflowPreserveTheOriginalFile() = runBlocking {
        val file = file()
        val session = session(file)
        session.raw.updateData { mutablePreferencesOf(intPreferencesKey("schema_version") to 2,
            longPreferencesKey("revision") to 11L) }
        val original = file.readBytes()
        val lease = session.store.open().ready()
        assertFalse(lease.stored.canWrite)
        val refused = session.store.save(lease, timer()) as SleepTimerStoreWriteResult.Failed
        assertEquals(SleepTimerStoreIssue.UNSUPPORTED_SCHEMA, refused.issue)
        assertArrayEquals(original, file.readBytes())

        session.raw.updateData { SleepTimerStoreCodec.encode(Long.MAX_VALUE, SleepTimerState()) }
        val ceiling = session.store.open().ready()
        val ceilingBytes = file.readBytes()
        val exhausted = session.store.save(ceiling, timer()) as SleepTimerStoreWriteResult.Failed
        assertEquals(SleepTimerStoreIssue.REVISION_EXHAUSTED, exhausted.issue)
        assertArrayEquals(ceilingBytes, file.readBytes())
    }

    @Test(timeout = 30000L) fun binaryCorruptionSettlesDisabledWithADurableExplanation() = runBlocking {
        val file = file()
        file.writeBytes(byteArrayOf(0x80.toByte()))
        val session = session(file)
        val recovered = session.store.open().ready()
        assertEquals(SleepTimerState(), recovered.stored.state)
        assertEquals(SleepTimerStoreRecovery.CORRUPT_FILE, recovered.stored.recovery)
        session.job.cancelAndJoin()
        val second = session(file)
        val reopened = second.store.open().ready()
        assertEquals(SleepTimerStoreRecovery.CORRUPT_FILE, reopened.stored.recovery)
        val repaired = second.store.save(reopened, timer()).saved().lease
        assertNull(repaired.stored.recovery)
    }

    @Test(timeout = 30000L) fun aRealWriteFailureKeepsAcceptedBytesAndAnExplicitRetryCanSave() = runBlocking {
        val file = file()
        val session = session(file)
        val accepted = session.store.save(session.store.open().ready(), timer()).saved().lease
        val original = file.readBytes()
        val scratch = File(file.absolutePath + ".tmp")
        assertTrue(scratch.mkdir())
        val blocker = File(scratch, "owned-test-blocker").apply { writeText("block atomic timer write") }
        val cancelled = SleepTimerReducer.cancel(accepted.stored.state).state
        val failed = session.store.save(accepted, cancelled) as SleepTimerStoreWriteResult.Failed
        assertEquals(SleepTimerStoreIssue.IO, failed.issue)
        assertArrayEquals(original, file.readBytes())
        assertTrue(blocker.delete())
        assertTrue(scratch.delete())
        assertEquals(cancelled, session.store.save(accepted, cancelled).saved().lease.stored.state)
    }

    @Test(timeout = 30000L) fun cancellationBeforeTheTransformDoesNotProduceAFakeReceipt() = runBlocking {
        val file = file()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val session = session(file) { raw -> object : DataStore<Preferences> by raw {
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = raw.updateData {
                entered.complete(Unit)
                release.await()
                transform(it)
            }
        } }
        session.raw.updateData { SleepTimerStoreCodec.encode(3L, SleepTimerState()) }
        val lease = session.store.open().ready()
        val original = file.readBytes()
        var receipt: SleepTimerStoreWriteResult? = null
        val write = launch { receipt = session.store.save(lease, timer()) }
        withTimeout(10_000L) { entered.await() }
        write.cancelAndJoin()
        assertNull(receipt)
        assertArrayEquals(original, file.readBytes())
        release.complete(Unit)
        assertTrue(session.store.save(lease, timer()) is SleepTimerStoreWriteResult.Saved)
    }

    private fun file() = File(temporaryFolder.root, "sleep.preferences_pb")
    private fun SleepTimerStoreOpenResult.ready() = (this as SleepTimerStoreOpenResult.Ready).lease
    private fun SleepTimerStoreWriteResult.saved() = this as SleepTimerStoreWriteResult.Saved

    private fun session(file: File, wrap: (DataStore<Preferences>) -> DataStore<Preferences> = { it }): StoreSession {
        val job = SupervisorJob().also { jobs.add(it) }
        val raw = createSleepTimerDataStore(CoroutineScope(job + Dispatchers.IO)) { file }
        return StoreSession(job, raw, SleepTimerStore(wrap(raw)))
    }

    private data class StoreSession(val job: Job, val raw: DataStore<Preferences>, val store: SleepTimerStore)
}
