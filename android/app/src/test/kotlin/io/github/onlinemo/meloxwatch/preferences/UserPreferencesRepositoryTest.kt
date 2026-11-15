package io.github.onlinemo.meloxwatch.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.onlinemo.meloxwatch.data.preferences.DataStoreUserPreferencesRepository
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesFailure
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesField
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesRecoveryReason
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesState
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesWriteResult
import io.github.onlinemo.meloxwatch.data.preferences.ResetConfirmation
import io.github.onlinemo.meloxwatch.data.preferences.createUserPreferencesDataStore
import io.github.onlinemo.meloxwatch.model.ScreenShape
import io.github.onlinemo.meloxwatch.model.UserPreferences
import io.github.onlinemo.meloxwatch.model.UserPreferencesDraft
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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

class UserPreferencesRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()
    private val sessions = mutableListOf<StoreSession>()

    @After
    fun closeStores() = runBlocking {
        sessions.asReversed().forEach { session -> session.close() }
        sessions.clear()
    }

    @Test(timeout = 30000L)
    fun emptyStoreLoadsSafeDefaults() = runBlocking {
        val session = open(preferencesFile())
        val loaded = session.ready()
        assertEquals(UserPreferences(), loaded.preferences)
        assertNull(loaded.recovery)
        assertTrue(loaded.canWrite)
    }

    @Test(timeout = 30000L)
    fun committedValuesSurviveClosingAndReopeningTheRealFile() = runBlocking {
        val file = preferencesFile()
        val first = open(file)
        first.ready()
        val result = first.repository.save(0L, UserPreferencesDraft(false, ScreenShape.ROUND))
        assertTrue(result is PreferencesWriteResult.Saved)
        val saved = (result as PreferencesWriteResult.Saved).preferences
        assertEquals(1L, saved.revision)
        assertTrue(file.isFile)
        first.close()

        val reopened = open(file).ready()
        assertEquals(saved, reopened.preferences)
        assertNull(reopened.recovery)
    }

    @Test(timeout = 30000L)
    fun repeatedSaveDoesNotIncreaseRevisionOrRewriteTheFile() = runBlocking {
        val file = preferencesFile()
        val session = open(file)
        session.ready()
        val draft = UserPreferencesDraft(false, ScreenShape.RECTANGULAR)
        session.repository.save(0L, draft)
        val committedBytes = file.readBytes()

        val result = session.repository.save(1L, draft)
        assertTrue(result is PreferencesWriteResult.Unchanged)
        assertEquals(1L, (result as PreferencesWriteResult.Unchanged).preferences.revision)
        assertArrayEquals(committedBytes, file.readBytes())
    }

    @Test(timeout = 30000L)
    fun concurrentEditsAtTheSameRevisionCannotOverwriteEachOther() = runBlocking {
        val file = preferencesFile()
        val session = open(file)
        session.ready()
        val start = CompletableDeferred<Unit>()
        val edits = listOf(ScreenShape.ROUND, ScreenShape.RECTANGULAR).map { shape ->
            async(Dispatchers.Default) {
                start.await()
                session.repository.save(0L, UserPreferencesDraft(false, shape))
            }
        }
        start.complete(Unit)
        val outcomes = edits.awaitAll()
        assertEquals(1, outcomes.count { outcome -> outcome is PreferencesWriteResult.Saved })
        assertEquals(1, outcomes.count { outcome -> outcome is PreferencesWriteResult.Conflict })
        val saved = outcomes.filterIsInstance<PreferencesWriteResult.Saved>().single().preferences
        val conflict = outcomes.filterIsInstance<PreferencesWriteResult.Conflict>().single()
        assertEquals(saved, conflict.current)
        assertEquals(saved, session.ready().preferences)
        session.close()
        assertEquals(saved, open(file).ready().preferences)
    }

    @Test(timeout = 30000L)
    fun aDelayedOldObservationCannotReplaceAnAlreadyCommittedRevision() = runBlocking {
        val file = preferencesFile()
        writeFixture(file, fixture(schema = 1, revision = 3L))
        val observation = FirstObservationGate()
        val session = open(file) { store -> HeldFirstObservationDataStore(store, observation) }
        withTimeout(10000L) { observation.captured.await() }

        val result = session.repository.save(3L, UserPreferencesDraft(true, ScreenShape.RECTANGULAR))
        val committed = (result as PreferencesWriteResult.Saved).preferences
        assertEquals(4L, committed.revision)
        observation.release.complete(Unit)
        withTimeout(10000L) { observation.delivered.await() }
        assertEquals(committed, session.ready().preferences)
        observation.continueReads.complete(Unit)
        session.close()
        assertEquals(committed, open(file).ready().preferences)
    }

    @Test(timeout = 30000L)
    fun cancellingResetPreservesPreferencesAndConfirmedResetTouchesOnlyPreferences() = runBlocking {
        val file = preferencesFile()
        val library = temporaryFolder.newFile("library-sentinel")
        library.writeText("unrelated library and audio remain intact")
        val libraryBytes = library.readBytes()
        val session = open(file)
        session.ready()
        session.repository.save(0L, UserPreferencesDraft(false, ScreenShape.ROUND))
        val savedBytes = file.readBytes()

        val cancelled = session.repository.reset(1L, ResetConfirmation.CANCELLED)
        assertTrue(cancelled is PreferencesWriteResult.Cancelled)
        assertArrayEquals(savedBytes, file.readBytes())

        val confirmed = session.repository.reset(1L, ResetConfirmation.CONFIRMED)
        assertTrue(confirmed is PreferencesWriteResult.Saved)
        assertEquals(UserPreferences(revision = 2L), (confirmed as PreferencesWriteResult.Saved).preferences)
        assertArrayEquals(libraryBytes, library.readBytes())
        session.close()
        assertEquals(UserPreferences(revision = 2L), open(file).ready().preferences)
    }

    @Test(timeout = 30000L)
    fun binaryCorruptionIsRepairedWithADurableExplanation() = runBlocking {
        val file = preferencesFile()
        val corruptBytes = byteArrayOf(0x80.toByte())
        file.writeBytes(corruptBytes)
        val first = open(file)
        val recovered = first.ready()
        assertEquals(UserPreferences(), recovered.preferences)
        assertEquals(PreferencesRecoveryReason.CORRUPT_FILE, recovered.recovery?.reason)
        assertEquals(PreferencesField.entries.toSet(), recovered.recovery?.fields)
        assertFalse(corruptBytes.contentEquals(file.readBytes()))
        first.close()

        val second = open(file)
        assertEquals(PreferencesRecoveryReason.CORRUPT_FILE, second.ready().recovery?.reason)
        val repair = second.repository.save(0L, UserPreferencesDraft(false, ScreenShape.RECTANGULAR))
        assertTrue(repair is PreferencesWriteResult.Saved)
        second.close()

        val reopened = open(file).ready()
        assertNull(reopened.recovery)
        assertEquals(
            UserPreferences(revision = 1L, restorePosition = false, screenShape = ScreenShape.RECTANGULAR),
            reopened.preferences,
        )
    }

    @Test(timeout = 30000L)
    fun wrongTypedFieldsAndUnknownEnumsUseExplainedDefaults() = runBlocking {
        val file = preferencesFile()
        writeFixture(
            file,
            mutablePreferencesOf(
                intPreferencesKey("schema_version") to 1,
                longPreferencesKey("revision") to 8L,
                stringPreferencesKey("restore_position") to "not a boolean",
                stringPreferencesKey("screen_shape") to "OVAL",
            ),
        )
        val originalBytes = file.readBytes()
        val session = open(file)
        val recovered = session.ready()
        assertEquals(UserPreferences(revision = 8L), recovered.preferences)
        assertEquals(PreferencesRecoveryReason.INVALID_FIELDS, recovered.recovery?.reason)
        assertEquals(
            setOf(PreferencesField.RESTORE_POSITION, PreferencesField.SCREEN_SHAPE),
            recovered.recovery?.fields,
        )
        assertArrayEquals(originalBytes, file.readBytes())

        val saved = session.repository.save(8L, UserPreferencesDraft(false, ScreenShape.ROUND))
        assertTrue(saved is PreferencesWriteResult.Saved)
        session.close()
        assertEquals(
            UserPreferences(revision = 9L, restorePosition = false, screenShape = ScreenShape.ROUND),
            open(file).ready().preferences,
        )
    }

    @Test(timeout = 30000L)
    fun wrongTypedSchemaAndRevisionArePreservedUntilExplicitRepair() = runBlocking {
        val wrongSchemaFile = File(temporaryFolder.root, "wrong-schema.preferences_pb")
        writeFixture(
            wrongSchemaFile,
            mutablePreferencesOf(
                stringPreferencesKey("schema_version") to "1",
                longPreferencesKey("revision") to 7L,
            ),
        )
        val schemaBytes = wrongSchemaFile.readBytes()
        val wrongSchema = open(wrongSchemaFile).ready()
        assertEquals(UserPreferences(revision = 7L), wrongSchema.preferences)
        assertEquals(PreferencesRecoveryReason.UNRECOGNIZED_SCHEMA, wrongSchema.recovery?.reason)
        assertNull(wrongSchema.recovery?.originalSchemaVersion)
        assertArrayEquals(schemaBytes, wrongSchemaFile.readBytes())

        val wrongRevisionFile = File(temporaryFolder.root, "wrong-revision.preferences_pb")
        writeFixture(
            wrongRevisionFile,
            mutablePreferencesOf(
                intPreferencesKey("schema_version") to 1,
                stringPreferencesKey("revision") to "invalid",
                booleanPreferencesKey("restore_position") to false,
                stringPreferencesKey("screen_shape") to ScreenShape.ROUND.name,
            ),
        )
        val revisionBytes = wrongRevisionFile.readBytes()
        val session = open(wrongRevisionFile)
        val recovered = session.ready()
        assertEquals(UserPreferences(restorePosition = false, screenShape = ScreenShape.ROUND), recovered.preferences)
        assertEquals(PreferencesRecoveryReason.INVALID_FIELDS, recovered.recovery?.reason)
        assertEquals(setOf(PreferencesField.REVISION), recovered.recovery?.fields)
        assertArrayEquals(revisionBytes, wrongRevisionFile.readBytes())

        val repair = session.repository.save(0L, recovered.preferences.asDraft())
        assertEquals(1L, (repair as PreferencesWriteResult.Saved).preferences.revision)
        session.close()
        assertNull(open(wrongRevisionFile).ready().recovery)
    }

    @Test(timeout = 30000L)
    fun anUnrecognizedOlderSchemaRequiresAnExplicitSaveToReplaceIt() = runBlocking {
        val file = preferencesFile()
        writeFixture(file, fixture(schema = 0, revision = 3L))
        val originalBytes = file.readBytes()
        val session = open(file)
        val recovered = session.ready()
        assertEquals(UserPreferences(revision = 3L), recovered.preferences)
        assertEquals(PreferencesRecoveryReason.UNRECOGNIZED_SCHEMA, recovered.recovery?.reason)
        assertArrayEquals(originalBytes, file.readBytes())

        val saved = session.repository.reset(3L, ResetConfirmation.CONFIRMED)
        assertTrue(saved is PreferencesWriteResult.Saved)
        session.close()
        val reopened = open(file).ready()
        assertEquals(UserPreferences(revision = 4L), reopened.preferences)
        assertNull(reopened.recovery)
    }

    @Test(timeout = 30000L)
    fun aNewerSchemaRemainsUntouchedEvenWhenSaveOrResetIsRequested() = runBlocking {
        val file = preferencesFile()
        writeFixture(file, fixture(schema = 2, revision = 12L))
        val originalBytes = file.readBytes()
        val session = open(file)
        val loaded = session.ready()
        assertEquals(UserPreferences(revision = 12L), loaded.preferences)
        assertEquals(PreferencesRecoveryReason.NEWER_SCHEMA, loaded.recovery?.reason)
        assertFalse(loaded.canWrite)

        val saved = session.repository.save(12L, UserPreferencesDraft(false, ScreenShape.ROUND))
        val reset = session.repository.reset(12L, ResetConfirmation.CONFIRMED)
        assertEquals(PreferencesFailure.UNSUPPORTED_SCHEMA, (saved as PreferencesWriteResult.Failed).failure)
        assertEquals(PreferencesFailure.UNSUPPORTED_SCHEMA, (reset as PreferencesWriteResult.Failed).failure)
        assertArrayEquals(originalBytes, file.readBytes())
    }

    @Test(timeout = 30000L)
    fun revisionOverflowCannotCommitANegativeRevision() = runBlocking {
        val file = preferencesFile()
        writeFixture(file, fixture(schema = 1, revision = Long.MAX_VALUE))
        val originalBytes = file.readBytes()
        val session = open(file)
        session.ready()
        val result = session.repository.save(Long.MAX_VALUE, UserPreferencesDraft())
        assertEquals(PreferencesFailure.REVISION_EXHAUSTED, (result as PreferencesWriteResult.Failed).failure)
        assertArrayEquals(originalBytes, file.readBytes())
    }

    @Test(timeout = 30000L)
    fun writeFailureUsesTheAtomicCurrentValueAndPreservesCommittedBytes() = runBlocking {
        val file = preferencesFile()
        val observation = FirstObservationGate()
        lateinit var realStore: DataStore<Preferences>
        val session = open(file) { store ->
            realStore = store
            HeldFirstObservationDataStore(store, observation)
        }
        withTimeout(10000L) { observation.captured.await() }
        session.repository.save(0L, UserPreferencesDraft(false, ScreenShape.ROUND))
        // 同一个真实 DataStore 内的 fixture 前进到 revision 2；观察回调保持滞后以验证权威读取。
        realStore.updateData { fixture(schema = 1, revision = 2L) }
        assertEquals(1L, session.ready().preferences.revision)
        val committed = UserPreferences(revision = 2L, restorePosition = false, screenShape = ScreenShape.ROUND)
        val committedBytes = file.readBytes()

        // DataStore 1.1.7 使用该暂存名；非空目录制造真实写盘故障，不替换存储为假实现。
        val scratch = File(file.absolutePath + ".tmp")
        assertTrue(scratch.mkdir())
        val blocker = File(scratch, "write-blocker")
        blocker.writeText("this fixture owns only the temporary directory")
        val result = session.repository.save(2L, UserPreferencesDraft(true, ScreenShape.RECTANGULAR))
        assertEquals(PreferencesFailure.IO, (result as PreferencesWriteResult.Failed).failure)
        assertEquals(committed, result.current)
        assertEquals(committed, session.ready().preferences)
        assertArrayEquals(committedBytes, file.readBytes())

        assertTrue(blocker.delete())
        assertTrue(scratch.delete())
        val retry = session.repository.save(2L, UserPreferencesDraft(true, ScreenShape.RECTANGULAR))
        assertTrue(retry is PreferencesWriteResult.Saved)
        session.close()
        assertEquals(
            UserPreferences(revision = 3L, screenShape = ScreenShape.RECTANGULAR),
            open(file).ready().preferences,
        )
    }

    @Test(timeout = 30000L)
    fun readFailureIsUnavailableUntilAnExplicitRetrySucceeds() = runBlocking {
        val file = preferencesFile()
        assertTrue(file.mkdir())
        val session = open(file)
        val failure = withTimeout(10000L) {
            session.repository.state.filterIsInstance<PreferencesState.Unavailable>().first()
        }
        assertEquals(PreferencesFailure.IO, failure.failure)
        assertNull(failure.lastKnown)

        assertTrue(file.delete())
        session.repository.retryRead()
        assertEquals(UserPreferences(), session.ready().preferences)
    }

    @Test(timeout = 30000L)
    fun aSuccessfulExplicitSaveAlsoResumesAnObserverWaitingAfterReadFailure() = runBlocking {
        val file = preferencesFile()
        assertTrue(file.mkdir())
        val failure = ReadFailureGate()
        failure.release.complete(Unit)
        val session = open(file) { store -> HeldReadFailureDataStore(store, failure) }
        withTimeout(10000L) {
            session.repository.state.filterIsInstance<PreferencesState.Unavailable>().first()
        }
        assertTrue(file.delete())

        val result = session.repository.save(0L, UserPreferencesDraft(false, ScreenShape.ROUND))
        val committed = (result as PreferencesWriteResult.Saved).preferences
        withTimeout(10000L) { failure.observed.await() }
        assertEquals(committed, session.ready().preferences)
    }

    @Test(timeout = 30000L)
    fun aDelayedReadIOExceptionCannotHideANewerConfirmedSave() = runBlocking {
        val file = preferencesFile()
        assertTrue(file.mkdir())
        val failure = ReadFailureGate()
        val session = open(file) { store -> HeldReadFailureDataStore(store, failure) }
        withTimeout(10000L) { failure.captured.await() }
        assertTrue(file.delete())

        val result = session.repository.save(0L, UserPreferencesDraft(false, ScreenShape.ROUND))
        val committed = (result as PreferencesWriteResult.Saved).preferences
        failure.release.complete(Unit)
        withTimeout(10000L) { failure.observed.await() }
        assertEquals(committed, session.ready().preferences)
    }

    @Test(timeout = 30000L)
    fun cancellationBeforeTheAtomicTransformDoesNotReturnAFakeFailureOrCommit() = runBlocking {
        val file = preferencesFile()
        writeFixture(file, fixture(schema = 1, revision = 3L))
        val committedBytes = file.readBytes()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val session = open(file) { store -> GatedUpdateDataStore(store, entered, release) }
        session.ready()
        var result: PreferencesWriteResult? = null
        val write = launch { result = session.repository.save(3L, UserPreferencesDraft(true, ScreenShape.RECTANGULAR)) }
        withTimeout(10000L) { entered.await() }
        write.cancelAndJoin()
        assertTrue(write.isCancelled)
        assertNull(result)
        assertArrayEquals(committedBytes, file.readBytes())

        release.complete(Unit)
        val retry = session.repository.save(3L, UserPreferencesDraft(true, ScreenShape.RECTANGULAR))
        assertTrue(retry is PreferencesWriteResult.Saved)
    }

    private fun preferencesFile(): File = File(temporaryFolder.root, "user.preferences_pb")

    private fun open(
        file: File,
        wrap: (DataStore<Preferences>) -> DataStore<Preferences> = { store -> store },
    ): StoreSession {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val store = createUserPreferencesDataStore(scope) { file }
        return StoreSession(job, DataStoreUserPreferencesRepository(wrap(store), scope)).also { session ->
            sessions.add(session)
        }
    }

    private suspend fun writeFixture(file: File, fixture: Preferences) {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        try {
            val store = createUserPreferencesDataStore(scope) { file }
            store.updateData { fixture }
        } finally {
            job.cancelAndJoin()
        }
    }

    private fun fixture(schema: Int, revision: Long): Preferences = mutablePreferencesOf(
        intPreferencesKey("schema_version") to schema,
        longPreferencesKey("revision") to revision,
        booleanPreferencesKey("restore_position") to false,
        stringPreferencesKey("screen_shape") to ScreenShape.ROUND.name,
    )

    private class StoreSession(
        private val job: Job,
        val repository: DataStoreUserPreferencesRepository,
    ) {
        suspend fun close() = job.cancelAndJoin()

        suspend fun ready(): PreferencesState.Ready = withTimeout(10000L) {
            repository.state.filterIsInstance<PreferencesState.Ready>().first()
        }
    }

    private class FirstObservationGate {
        val captured = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val delivered = CompletableDeferred<Unit>()
        val continueReads = CompletableDeferred<Unit>()
    }

    private class HeldFirstObservationDataStore(
        private val delegate: DataStore<Preferences>,
        private val gate: FirstObservationGate,
    ) : DataStore<Preferences> by delegate {
        override val data: Flow<Preferences> = flow {
            var first = true
            delegate.data.collect { stored ->
                if (first) {
                    first = false
                    gate.captured.complete(Unit)
                    gate.release.await()
                    emit(stored)
                    gate.delivered.complete(Unit)
                    gate.continueReads.await()
                } else {
                    emit(stored)
                }
            }
        }
    }

    private class ReadFailureGate {
        val captured = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val observed = CompletableDeferred<Unit>()
    }

    private class HeldReadFailureDataStore(
        private val delegate: DataStore<Preferences>,
        private val gate: ReadFailureGate,
    ) : DataStore<Preferences> by delegate {
        override val data: Flow<Preferences> = flow {
            try {
                delegate.data.collect { stored ->
                    emit(stored)
                    gate.observed.complete(Unit)
                }
            } catch (error: IOException) {
                // 延迟的是真实文件读取异常，测试只控制回调交付次序。
                gate.captured.complete(Unit)
                gate.release.await()
                throw error
            }
        }
    }

    private class GatedUpdateDataStore(
        private val delegate: DataStore<Preferences>,
        private val entered: CompletableDeferred<Unit>,
        private val release: CompletableDeferred<Unit>,
    ) : DataStore<Preferences> by delegate {
        override suspend fun updateData(
            transform: suspend (Preferences) -> Preferences,
        ): Preferences = delegate.updateData { stored ->
            entered.complete(Unit)
            release.await()
            transform(stored)
        }
    }
}
