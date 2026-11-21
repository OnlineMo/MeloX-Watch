package io.github.onlinemo.meloxwatch.data.fonts.store

import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSaveResult
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSelection
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSettings
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontTestFixtures as Fixture
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class FontSettingsFailureTest : FontSettingsFileFixture() {
    @Test(timeout = 30000L)
    fun aRealScratchDirectoryWriteFailureKeepsTheOldRolesAndNeedsAnExplicitRetry() = runBlocking {
        val file = settingsFile()
        val current = bothRoles()
        writeSettingsFixture(file, LyricsFontSettingsCodec.encode(current))
        val bytes = file.readBytes()
        val session = openSettings(file)
        val scratch = File(file.absolutePath + ".tmp")
        assertTrue(scratch.mkdir())
        val blocker = File(scratch, "blocker").apply { writeText("actual controlled write failure") }
        val next = current.copy(original = current.original.copy(requestedWeight = 650f))
        expectFontSettingsIssue(LyricsFontSettingsStorageIssue.IO) { session.store.compareAndSet(2L, next) }
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(current, session.store.read())
        assertTrue(blocker.delete())
        assertTrue(scratch.delete())
        assertEquals(LyricsFontSaveResult.Saved(next.copy(revision = 3L)), session.store.compareAndSet(2L, next))
        session.close()
        assertEquals(next.copy(revision = 3L), openSettings(file).store.read())
    }

    @Test(timeout = 30000L)
    fun anErrorAfterTheRealDiskCommitThrowsButTheNextTrueReadFindsTheNewRevision() = runBlocking {
        val file = settingsFile()
        val current = bothRoles()
        writeSettingsFixture(file, LyricsFontSettingsCodec.encode(current))
        lateinit var controls: ControlledFontSettingsDataStore
        val session = openSettings(file) { ControlledFontSettingsDataStore(it).also { controls = it } }
        controls.failAfterNextWrite.set(true)
        val next = current.copy(original = current.original.copy(requestedWeight = 650f))
        expectFontSettingsIssue(LyricsFontSettingsStorageIssue.IO) { session.store.compareAndSet(2L, next) }
        val committed = next.copy(revision = 3L)
        assertEquals(committed, session.store.read())
        val bytes = file.readBytes()
        assertEquals(LyricsFontSaveResult.Conflict(committed), session.store.compareAndSet(2L, next))
        assertArrayEquals(bytes, file.readBytes())
        session.close()
        assertEquals(committed, openSettings(file).store.read())
    }

    @Test(timeout = 30000L)
    fun readIoOrAllocationFailureCannotReturnAnAssertedDefaultOrCreateAFile() = runBlocking {
        val file = settingsFile()
        lateinit var controls: ControlledFontSettingsDataStore
        val session = openSettings(file) { ControlledFontSettingsDataStore(it).also { controls = it } }
        controls.failReads.set(true)
        expectFontSettingsIssue(LyricsFontSettingsStorageIssue.IO) { session.store.read() }
        assertFalse(file.exists())
        assertEquals(1, controls.readSubscriptions.get())
        controls.failReads.set(false)
        controls.failReadAllocation.set(true)
        expectFontSettingsIssue(LyricsFontSettingsStorageIssue.LOW_MEMORY) { session.store.read() }
        assertFalse(file.exists())
        controls.failReadAllocation.set(false)
        assertEquals(LyricsFontSettings(), session.store.read())
        assertEquals(3, controls.readSubscriptions.get())
        assertFalse(file.exists())
    }

    @Test(timeout = 30000L)
    fun onlyOneOperationIsAdmittedAndCancellationBeforeTheTransformDoesNotWrite() = runBlocking {
        val file = settingsFile()
        lateinit var controls: ControlledFontSettingsDataStore
        val session = openSettings(file) { ControlledFontSettingsDataStore(it).also { controls = it } }
        val gate = FontSettingsWriteGate()
        controls.holdNextWrite(gate)
        val next = LyricsFontSettings(original = LyricsFontSelection(Fixture.asset(), 650f))
        val caller = async { session.store.compareAndSet(0L, next) }
        try {
            withTimeout(10000L) { gate.entered.await() }
            expectFontSettingsIssue(LyricsFontSettingsStorageIssue.BUSY) { session.store.read() }
            expectFontSettingsIssue(LyricsFontSettingsStorageIssue.BUSY) { session.store.compareAndSet(0L, next) }
            assertEquals(1, controls.writeCalls.get())
            assertEquals(0, controls.readSubscriptions.get())
            caller.cancel()
            assertFalse(caller.isCompleted)
            expectFontSettingsIssue(LyricsFontSettingsStorageIssue.BUSY) { session.store.read() }
            assertFalse(file.exists())
        } finally { gate.release.complete(Unit) }
        withTimeout(10000L) { caller.join() }
        assertTrue(caller.isCancelled)
        assertFalse(file.exists())
        assertEquals(LyricsFontSettings(), session.store.read())
        assertEquals(LyricsFontSaveResult.Saved(next.copy(revision = 1L)), session.store.compareAndSet(0L, next))
    }

    @Test(timeout = 30000L)
    fun cancellationAfterDurableCommitDoesNotUndoTheSavedRolesOrTheirSharedRevision() = runBlocking {
        val file = settingsFile()
        val current = bothRoles()
        writeSettingsFixture(file, LyricsFontSettingsCodec.encode(current))
        lateinit var controls: ControlledFontSettingsDataStore
        val session = openSettings(file) { ControlledFontSettingsDataStore(it).also { controls = it } }
        val gate = FontSettingsWriteGate(afterCommit = true)
        controls.holdNextWrite(gate)
        val next = current.copy(original = current.original.copy(requestedWeight = 650f))
        val caller = async { session.store.compareAndSet(2L, next) }
        try {
            withTimeout(10000L) { gate.entered.await() }
            caller.cancel()
            assertFalse(caller.isCompleted)
            expectFontSettingsIssue(LyricsFontSettingsStorageIssue.BUSY) { session.store.read() }
        } finally { gate.release.complete(Unit) }
        withTimeout(10000L) { caller.join() }
        assertTrue(caller.isCancelled)
        assertEquals(next.copy(revision = 3L), session.store.read())
        assertEquals(current.translation, session.store.read().translation)
        session.close()
        assertEquals(next.copy(revision = 3L), openSettings(file).store.read())
    }

    @Test(timeout = 30000L)
    fun anIgnoringLateReadKeepsTheOwnerBusyUntilCompletionAndCannotReturnAfterCancellation() = runBlocking {
        val file = settingsFile()
        val current = bothRoles()
        writeSettingsFixture(file, LyricsFontSettingsCodec.encode(current))
        lateinit var controls: ControlledFontSettingsDataStore
        val session = openSettings(file) { ControlledFontSettingsDataStore(it).also { controls = it } }
        val gate = FontSettingsReadGate(ignoreCancellation = true)
        controls.holdNextRead(gate)
        var delivered = false
        val caller = async { session.store.read().also { delivered = true } }
        try {
            withTimeout(10000L) { gate.captured.await() }
            caller.cancel()
            assertFalse(caller.isCompleted)
            expectFontSettingsIssue(LyricsFontSettingsStorageIssue.BUSY) { session.store.read() }
            expectFontSettingsIssue(LyricsFontSettingsStorageIssue.BUSY) { session.store.compareAndSet(2L, current) }
            assertEquals(1, controls.readSubscriptions.get())
            assertEquals(0, controls.writeCalls.get())
        } finally { gate.release.complete(Unit) }
        withTimeout(10000L) { caller.cancelAndJoin() }
        assertFalse(delivered)
        assertEquals(current, session.store.read())
        session.close()
        assertEquals(current, openSettings(file).store.read())
    }

    @Test(timeout = 30000L)
    fun cancellationDuringTheRealPhysicalWriteKeepsBusyUntilCompletionAndRestoresBothRoles() = runBlocking {
        for (afterScratchWrite in listOf(false, true)) {
            val file = settingsFile()
            val current = bothRoles()
            writeSettingsFixture(file, LyricsFontSettingsCodec.encode(current))
            val bytes = file.readBytes()
            val storage = FontSettingsPhysicalStorage(file)
            val session = openStorageSettings(storage)
            assertEquals(current, session.store.read())
            val next = current.copy(original = current.original.copy(requestedWeight = 650f))
            val gate = FontSettingsPhysicalWriteGate(afterScratchWrite)
            storage.holdNextWrite(gate)
            var delivered = false
            val caller = async { session.store.compareAndSet(2L, next).also { delivered = true } }
            try {
                val captured = withTimeout(10000L) { gate.entered.await() }
                assertEquals(next.copy(revision = 3L), LyricsFontSettingsCodec.decode(captured))
                if (afterScratchWrite) assertTrue(File(file.absolutePath + ".tmp").length() > 0L)
                caller.cancel()
                assertFalse(caller.isCompleted)
                expectFontSettingsIssue(LyricsFontSettingsStorageIssue.BUSY) { session.store.read() }
                expectFontSettingsIssue(LyricsFontSettingsStorageIssue.BUSY) { session.store.compareAndSet(2L, next) }
                assertArrayEquals(bytes, file.readBytes())
                assertEquals(1, storage.writeScopes.get())
                assertEquals(0, storage.exitedWrites.get())
                assertEquals(0, storage.committedWrites.get())
            } finally { gate.release.complete(Unit) }
            withTimeout(10000L) { caller.join() }
            assertTrue(caller.isCancelled)
            assertFalse(delivered)
            assertEquals(1, storage.exitedWrites.get())
            assertEquals(1, storage.committedWrites.get())
            val committed = next.copy(revision = 3L)
            assertEquals(committed, session.store.read())
            assertEquals(current.translation, session.store.read().translation)
            assertFalse(bytes.contentEquals(file.readBytes()))
            session.close()
            assertEquals(committed, openSettings(file).store.read())
        }
    }

    private fun bothRoles() = LyricsFontSettings(revision = 2L,
        original = LyricsFontSelection(Fixture.asset(), 400f), translation = LyricsFontSelection(Fixture.asset(), 600f))
}
