package io.github.onlinemo.meloxwatch.preferences

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.preferences.core.Preferences
import io.github.onlinemo.meloxwatch.data.backup.BackupException
import io.github.onlinemo.meloxwatch.data.backup.BackupIssue
import io.github.onlinemo.meloxwatch.data.backup.BackupMutationBarrier
import io.github.onlinemo.meloxwatch.data.preferences.DataStoreUserPreferencesRepository
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesCodec
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesState
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesWriteResult
import io.github.onlinemo.meloxwatch.model.ScreenShape
import io.github.onlinemo.meloxwatch.model.UserPreferences
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The real DataStore actor owns a gated file write after the cancellable transform has returned. */
class UserPreferencesActorCompletionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test(timeout = 30_000L)
    fun cancellationDuringTheActualWriteKeepsBackupAdmissionUntilCommitAndReopen() = runBlocking {
        verifyWriteCompletion(failWrite = false)
    }

    @Test(timeout = 30_000L)
    fun cancellationDuringAFailedActualWriteKeepsOldBytesAndAllowsASeparateRetry() = runBlocking {
        verifyWriteCompletion(failWrite = true)
    }

    private suspend fun verifyWriteCompletion(failWrite: Boolean) {
        val file = File(temporary.root, "actor.preferences")
        val initial = UserPreferences(revision = 3L, screenShape = ScreenShape.ROUND)
        val expected = initial.copy(revision = 4L, screenShape = ScreenShape.RECTANGULAR)
        val barrier = BackupMutationBarrier()
        barrier.initializeStorage {}
        val serializer = HeldFileSerializer(failWrite)
        val session = open(file, serializer, barrier, initial)
        var writing: Job? = null
        try {
            assertEquals(initial, session.ready(initial.revision).preferences)
            val before = file.readBytes()
            serializer.holdNextWrite()
            var result: PreferencesWriteResult? = null
            val write = CoroutineScope(currentCoroutineContext()).launch {
                result = session.repository.save(initial.revision, expected.asDraft())
            }
            writing = write
            withTimeout(10_000L) { serializer.entered.await() }
            write.cancel()
            yield()
            assertFalse(serializer.release.isCompleted)
            assertFalse(write.isCompleted)
            assertNull(result)
            try {
                barrier.exclusively {}
                throw AssertionError("An unfinished actor write must still hold backup admission")
            } catch (failure: BackupException) { assertEquals(BackupIssue.BUSY, failure.issue) }

            serializer.release.complete(Unit)
            withTimeout(10_000L) { write.join() }
            assertTrue(write.isCancelled)
            assertNull(result)
            if (failWrite) {
                assertArrayEquals(before, file.readBytes())
                assertEquals(initial, session.ready(initial.revision).preferences)
                val retry = session.repository.save(initial.revision, expected.asDraft())
                assertEquals(expected, (retry as PreferencesWriteResult.Saved).preferences)
            } else {
                assertEquals(expected, session.ready(expected.revision).preferences)
            }
            // Inspect the committed file independently of DataStore's notification/cache path.
            DataInputStream(file.inputStream()).use { actual ->
                assertEquals(expected.revision, actual.readLong())
                assertEquals(expected.screenShape.name, actual.readUTF())
                assertEquals(-1, actual.read())
            }
            session.close()
            val reopened = open(file, HeldFileSerializer(false), barrier)
            try { assertEquals(expected, reopened.ready(expected.revision).preferences) }
            finally { reopened.close() }
            barrier.exclusively {}
        } finally {
            serializer.release.complete(Unit)
            withContext(NonCancellable) {
                withTimeout(10_000L) { writing?.cancelAndJoin(); session.close() }
            }
        }
    }

    private suspend fun open(file: File, serializer: HeldFileSerializer, barrier: BackupMutationBarrier,
        seed: UserPreferences? = null): Session {
        val actor = SupervisorJob()
        val observer = SupervisorJob(actor)
        try {
            val store = DataStoreFactory.create(serializer = serializer,
                scope = CoroutineScope(actor + Dispatchers.IO), produceFile = { file })
            if (seed != null) store.updateData { PreferencesCodec.encode(seed) }
            return Session(actor, observer, DataStoreUserPreferencesRepository(store,
                CoroutineScope(observer + Dispatchers.IO), barrier))
        } catch (failure: Throwable) {
            withContext(NonCancellable) { withTimeout(10_000L) { actor.cancelAndJoin() } }
            throw failure
        }
    }

    private class Session(private val actor: Job, private val observer: Job,
        val repository: DataStoreUserPreferencesRepository) {
        suspend fun ready(revision: Long): PreferencesState.Ready = withTimeout(10_000L) {
            repository.state.filterIsInstance<PreferencesState.Ready>().first { it.preferences.revision == revision }
        }
        suspend fun close() { observer.cancelAndJoin(); actor.cancelAndJoin() }
    }

    /** A compact fixture format keeps the disk observations separate from the Preferences codec. */
    private class HeldFileSerializer(private val failHeldWrite: Boolean) : Serializer<Preferences> {
        private val held = AtomicBoolean(false)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        override val defaultValue: Preferences = PreferencesCodec.encode(UserPreferences())

        fun holdNextWrite() { check(held.compareAndSet(false, true)) }

        override suspend fun readFrom(input: InputStream): Preferences {
            val file = DataInputStream(input)
            return PreferencesCodec.encode(UserPreferences(revision = file.readLong(),
                screenShape = ScreenShape.valueOf(file.readUTF())))
        }

        override suspend fun writeTo(t: Preferences, output: OutputStream) {
            if (held.getAndSet(false)) {
                entered.complete(Unit)
                release.await()
                if (failHeldWrite) throw IOException("Synthetic actor write failure")
            }
            val value = PreferencesCodec.decode(t).preferences
            val file = DataOutputStream(output)
            file.writeLong(value.revision)
            file.writeUTF(value.screenShape.name)
            file.flush()
        }
    }
}
