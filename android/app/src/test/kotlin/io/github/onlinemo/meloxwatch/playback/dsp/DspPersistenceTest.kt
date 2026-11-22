package io.github.onlinemo.meloxwatch.playback.dsp

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.onlinemo.meloxwatch.data.backup.BackupMutationBarrier
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import java.util.concurrent.atomic.AtomicBoolean

/** 真实 DataStore 文件及 actor 验证落盘、CAS 和取消；不把内存 mock 当恢复证据。 */
class DspPersistenceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val sessions = ArrayList<Session>()

    @After fun closeStores() = runBlocking {
        for (session in sessions.asReversed()) session.close()
        sessions.clear()
    }

    @Test(timeout = 30_000) fun settingsAndNamedCurvesSurviveClosingAndReopeningTheRealFile() = runBlocking {
        val file = File(temporary.root, "dsp.preferences_pb")
        val first = open(file)
        assertEquals(DspStored(), first.store.read().saved)
        val bands = List(10) { it * 25 - 115 }
        val preset = DspPreset("custom_saved", "夜间 😊", bands)
        val settings = DspSettings(equalizerEnabled = true, bandGainTenthsDb = bands,
            selectedPresetId = preset.id, replayGainMode = ReplayGainMode.ALBUM,
            preampTenthsDb = -35, leftPercent = 35, rightPercent = 95, limiterEnabled = true,
            limiterThresholdTenthsDb = -27, limiterAttackMicros = 375, limiterReleaseMillis = 255,
            limiterLookaheadMillis = 7)
        val result = first.store.compareAndSet(0, settings, listOf(preset)) as DspSaveResult.Saved
        assertEquals(DspStored(1, settings, listOf(preset)), result.snapshot.saved)
        assertTrue(file.isFile)
        first.close()
        assertEquals(result.snapshot.saved, open(file).store.read().saved)
    }

    @Test(timeout = 30_000) fun concurrentEditsAtOneRevisionHaveOneWinnerAndPreserveItsWholeRecord() = runBlocking {
        val session = open(File(temporary.root, "cas.preferences_pb"))
        session.store.read()
        val start = CompletableDeferred<Unit>()
        val writes = listOf(DspSettings(leftPercent = 25), DspSettings(rightPercent = 50)).map { settings ->
            async(Dispatchers.Default) {
                start.await()
                session.store.compareAndSet(0, settings, emptyList())
            }
        }
        start.complete(Unit)
        val results = writes.awaitAll()
        assertEquals(1, results.count { it is DspSaveResult.Saved })
        assertEquals(1, results.count { it is DspSaveResult.Conflict })
        val saved = (results.single { it is DspSaveResult.Saved } as DspSaveResult.Saved).snapshot.saved
        assertEquals(saved, session.store.read().saved)
        assertEquals(saved, (results.single { it is DspSaveResult.Conflict } as DspSaveResult.Conflict).actual.saved)
    }

    @Test(timeout = 30_000) fun aNewerSchemaCannotBeOverwrittenBySaveOrRecovery() = runBlocking {
        val file = File(temporary.root, "newer.preferences_pb")
        val session = open(file)
        session.raw.updateData { mutablePreferencesOf(intPreferencesKey("schema") to 2,
            longPreferencesKey("revision") to 4L, stringPreferencesKey("payload") to "future") }
        val before = file.readBytes()
        val read = session.store.read()
        assertEquals(DspSettingsIssue.NEWER_SCHEMA, read.issue)
        assertNull(read.recoveryToken)
        assertEquals(DspSettingsIssue.NEWER_SCHEMA,
            (session.store.compareAndSet(4, DspSettings(), emptyList()) as DspSaveResult.Failed).issue)
        assertEquals(DspSettingsIssue.NEWER_SCHEMA,
            (session.store.recoverDefaults("0".repeat(64)) as DspSaveResult.Failed).issue)
        assertArrayEquals(before, file.readBytes())
    }

    @Test(timeout = 30_000) fun recoveryRequiresTheExactObservedDamagedRecord() = runBlocking {
        val file = File(temporary.root, "damaged.preferences_pb")
        val sentinel = temporary.newFile("library-is-independent")
        sentinel.writeText("Music and library relations are outside this store")
        val sentinelBytes = sentinel.readBytes()
        val session = open(file)
        fun damaged(payload: String): Preferences = mutablePreferencesOf(intPreferencesKey("schema") to 1,
            longPreferencesKey("revision") to 3L, stringPreferencesKey("payload") to payload)
        session.raw.updateData { damaged("first damaged payload") }
        val first = session.store.read()
        assertEquals(DspSettingsIssue.INVALID_SETTINGS, first.issue)
        val token = checkNotNull(first.recoveryToken)
        session.raw.updateData { damaged("second damaged payload") }
        val changedBytes = file.readBytes()
        assertTrue(session.store.recoverDefaults(token) is DspSaveResult.Conflict)
        assertArrayEquals(changedBytes, file.readBytes())
        val current = session.store.read()
        assertTrue(current.recoveryToken != token)
        val recovered = session.store.recoverDefaults(checkNotNull(current.recoveryToken)) as DspSaveResult.Saved
        assertEquals(DspStored(), recovered.snapshot.saved)
        assertArrayEquals(sentinelBytes, sentinel.readBytes())
    }

    @Test(timeout = 30_000) fun aCancelledCallerCannotReleaseBackupAdmissionBeforeTheActorFinishes() = runBlocking {
        val barrier = BackupMutationBarrier()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val hold = AtomicBoolean(true)
        val session = open(File(temporary.root, "held.preferences_pb"), barrier) { raw ->
            object : DataStore<Preferences> by raw {
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                    raw.updateData { stored ->
                        val proposed = transform(stored)
                        if (proposed != stored && hold.compareAndSet(true, false)) {
                            entered.complete(Unit)
                            // 已经过实际 transform；仅模拟不合作的持久操作，保持真实 DataStore 提交。
                            withContext(NonCancellable) { release.await() }
                        }
                        proposed
                    }
            }
        }
        initialize(session, barrier)
        session.store.read()
        var acknowledgement: DspSaveResult? = null
        val writer = launch(Dispatchers.Default) {
            acknowledgement = session.store.compareAndSet(0, DspSettings(leftPercent = 50), emptyList())
        }
        try {
            withTimeout(5_000) { entered.await() }
            writer.cancel()
            assertFalse(writer.isCompleted)
            var blocked = false
            try { barrier.exclusively { } } catch (_: Exception) { blocked = true }
            assertTrue(blocked)
        } finally {
            release.complete(Unit)
            withTimeout(5_000) { writer.join() }
        }
        assertNull(acknowledgement)
        assertEquals(50, session.store.read().saved?.settings?.leftPercent)
        barrier.exclusively { assertEquals(1L, session.store.read().saved?.revision) }
    }

    @Test(timeout = 30_000) fun restoreWritesRemainInvisibleUntilTheExclusiveGenerationPublishes() = runBlocking {
        val barrier = BackupMutationBarrier()
        val session = open(File(temporary.root, "exclusive.preferences_pb"), barrier)
        initialize(session, barrier)
        session.store.read()
        val previous = session.store.state.value
        barrier.exclusively {
            val result = session.store.compareAndSet(0, DspSettings(rightPercent = 25), emptyList()) as DspSaveResult.Saved
            assertEquals(25, result.snapshot.saved?.settings?.rightPercent)
            assertEquals(previous, session.store.state.value)
            assertEquals(result.snapshot.saved, session.store.read().saved)
            assertEquals(previous, session.store.state.value)
        }
        assertEquals(25, session.store.state.value.saved?.settings?.rightPercent)
    }

    @Test fun anOldPreviewCancellationCannotUndoANewerSavedConfiguration() {
        val runtime = DspRuntime()
        runtime.setSaved(DspStored(1, DspSettings(leftPercent = 75)))
        val owner = Any()
        assertTrue(runtime.preview(owner, 1, DspSettings(leftPercent = 25)))
        assertEquals(25, runtime.currentSettings().leftPercent)
        runtime.setSaved(DspStored(2, DspSettings(rightPercent = 50)))
        runtime.endPreview(owner)
        assertEquals(DspSettings(rightPercent = 50), runtime.currentSettings())
        assertFalse(runtime.preview(owner, 1, DspSettings(leftPercent = 0)))
    }

    private fun open(file: File, barrier: BackupMutationBarrier? = null,
        wrap: (DataStore<Preferences>) -> DataStore<Preferences> = { it }): Session {
        val job = SupervisorJob()
        val raw = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file })
        val session = Session(job, raw, DataStoreDspSettingsStore(wrap(raw), barrier))
        sessions += session
        return session
    }

    private suspend fun initialize(session: Session, barrier: BackupMutationBarrier) {
        barrier.initializeStorage {
            val read = session.store.read()
            check(read.saved != null && read.issue == null)
        }
    }

    private class Session(val job: Job, val raw: DataStore<Preferences>, val store: DspSettingsStore) {
        suspend fun close() = job.cancelAndJoin()
    }
}
