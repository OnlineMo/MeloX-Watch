package io.github.onlinemo.meloxwatch.data.lyrics.store

import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

internal class LyricsDisplayObservationTest : LyricsDisplayFileFixture() {
    @Test(timeout = 30000L)
    fun aLateOldValidSnapshotCannotReplaceTheAtomicallyCommittedRevision() = runBlocking {
        val file = preferencesFile()
        writeDisplayFixture(file, LyricsDisplayCodec.encode(LyricsDisplayPreferences(revision = 3L, textSizeSp = 18f)))
        val gate = DisplayObservationGate()
        val session = openDisplay(file) { HeldDisplayObservationDataStore(it, gate) }
        withTimeout(10000L) { gate.captured.await() }
        val next = LyricsDisplayPreferences(revision = 3L, textSizeSp = 32f)
        assertEquals(LyricsDisplayWriteResult.Saved(next.copy(revision = 4L)), session.store.save(3L, next))
        gate.release.complete(Unit)
        withTimeout(10000L) { gate.delivered.await() }
        assertEquals(next.copy(revision = 4L), session.ready().preferences)
        gate.continueReads.complete(Unit)
        session.close()
        assertEquals(next.copy(revision = 4L), openDisplay(file).ready().preferences)
    }

    @Test(timeout = 30000L)
    fun aLateSnapshotWithNoUsableRevisionIsCheckedAgainstTheRealCurrentFile() = runBlocking {
        val file = preferencesFile()
        writeDisplayFixture(file, LyricsDisplayCodec.encode(LyricsDisplayPreferences(revision = 3L)))
        val old = LyricsDisplayCodec.encode(LyricsDisplayPreferences(revision = 3L)).toMutablePreferences().apply {
            this[intPreferencesKey("schema_version")] = 2
        }
        val gate = DisplayObservationGate()
        val session = openDisplay(file) { HeldDisplayObservationDataStore(it, gate, oldSnapshot = old) }
        withTimeout(10000L) { gate.captured.await() }
        val next = LyricsDisplayPreferences(revision = 3L, blockSpacingDp = 24f)
        assertEquals(LyricsDisplayWriteResult.Saved(next.copy(revision = 4L)), session.store.save(3L, next))
        gate.release.complete(Unit)
        withTimeout(10000L) { gate.delivered.await() }
        assertEquals(LyricsDisplayState.Ready(next.copy(revision = 4L), persisted = true), session.store.state.value)
        gate.continueReads.complete(Unit)
    }

    @Test(timeout = 30000L)
    fun aLateErrorFromTheOldReadCannotHideAConfirmedNewSave() = runBlocking {
        val file = preferencesFile()
        writeDisplayFixture(file, LyricsDisplayCodec.encode(LyricsDisplayPreferences(revision = 3L)))
        val gate = DisplayObservationGate()
        val session = openDisplay(file) { HeldDisplayObservationDataStore(it, gate, failAfterOld = true) }
        withTimeout(10000L) { gate.captured.await() }
        val next = LyricsDisplayPreferences(revision = 3L, horizontalMarginDp = 24f)
        assertEquals(LyricsDisplayWriteResult.Saved(next.copy(revision = 4L)), session.store.save(3L, next))
        gate.release.complete(Unit)
        withTimeout(10000L) { gate.delivered.await() }
        gate.continueReads.complete(Unit)
        withTimeout(10000L) { gate.restarted.await() }
        assertEquals(next.copy(revision = 4L), session.ready().preferences)
        session.close()
        assertEquals(next.copy(revision = 4L), openDisplay(file).ready().preferences)
    }

    @Test(timeout = 30000L)
    fun anOldReadErrorAlsoCannotHideAConfirmedUnchangedSaveAtTheSameRevision() = runBlocking {
        val file = preferencesFile()
        val original = LyricsDisplayPreferences(revision = 3L, horizontalMarginDp = 24f)
        writeDisplayFixture(file, LyricsDisplayCodec.encode(original))
        val gate = DisplayObservationGate()
        val session = openDisplay(file) { HeldDisplayObservationDataStore(it, gate, failAfterOld = true) }
        withTimeout(10000L) { gate.captured.await() }
        assertEquals(LyricsDisplayWriteResult.Unchanged(original), session.store.save(3L, original))
        gate.release.complete(Unit)
        withTimeout(10000L) { gate.delivered.await() }
        gate.continueReads.complete(Unit)
        withTimeout(10000L) { gate.restarted.await() }
        assertEquals(LyricsDisplayState.Ready(original, persisted = true), session.store.state.value)
    }
}
