package io.github.onlinemo.meloxwatch.data.lyrics.store

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class LyricsDisplayFailureTest : LyricsDisplayFileFixture() {
    @Test(timeout = 30000L)
    fun aRealNonemptyScratchDirectoryRejectsTheWriteAndExplicitRetryUsesTheTrueOldRevision() = runBlocking {
        val file = preferencesFile()
        val session = openDisplay(file)
        session.ready()
        val current = (session.store.save(0L, LyricsDisplayPreferences(textSizeSp = 18f)) as LyricsDisplayWriteResult.Saved).preferences
        val bytes = file.readBytes()
        val scratch = File(file.absolutePath + ".tmp")
        assertTrue(scratch.mkdir())
        val blocker = File(scratch, "blocker").apply { writeText("controlled actual filesystem failure") }
        val next = current.copy(textSizeSp = 30f)

        assertEquals(LyricsDisplayWriteResult.Failed(current, LyricsDisplayIssue.IO), session.store.save(1L, next))
        assertEquals(current, session.ready().preferences)
        assertArrayEquals(bytes, file.readBytes())
        assertTrue(blocker.delete())
        assertTrue(scratch.delete())
        assertEquals(LyricsDisplayWriteResult.Saved(next.copy(revision = 2L)), session.store.save(1L, next))
        session.close()
        assertEquals(next.copy(revision = 2L), openDisplay(file).ready().preferences)
    }

    @Test(timeout = 30000L)
    fun anIoErrorAfterTheRealDiskCommitIsResolvedByReadingBackCommittedBytes() = runBlocking {
        val file = preferencesFile()
        val session = openDisplay(file, ::DisplayPostCommitErrorDataStore)
        session.ready()
        val next = LyricsDisplayPreferences(blockSpacingDp = 24f)
        assertEquals(LyricsDisplayWriteResult.Saved(next.copy(revision = 1L)), session.store.save(0L, next))
        assertEquals(next.copy(revision = 1L), session.ready().preferences)
        session.close()
        assertEquals(next.copy(revision = 1L), openDisplay(file).ready().preferences)
    }

    @Test(timeout = 30000L)
    fun aReadIoFailureWaitsForOneExplicitRetryWithoutCreatingADefaultFile() = runBlocking {
        val file = preferencesFile()
        lateinit var reads: CountingDisplayReadsDataStore
        val session = openDisplay(file) { delegate -> CountingDisplayReadsDataStore(delegate, failFirst = true).also { reads = it } }
        assertEquals(LyricsDisplayState.Unavailable(null, LyricsDisplayIssue.IO), session.unavailable())
        assertNull(withTimeoutOrNull(100L) { reads.subscriptions.first { it > 1 } })
        assertFalse(file.exists())
        session.store.retryRead()
        assertEquals(LyricsDisplayState.Ready(LyricsDisplayPreferences(), persisted = false), session.ready())
        assertEquals(2, reads.subscriptions.value)
        assertFalse(file.exists())
    }

    @Test(timeout = 30000L)
    fun onlyOneMutationIsAdmittedAndCancellationBeforeTransformDoesNotWrite() = runBlocking {
        val file = preferencesFile()
        val gate = DisplayMutationGate()
        val session = openDisplay(file) { HeldDisplayMutationDataStore(it, gate) }
        session.ready()
        gate.arm()
        val first = async { session.store.save(0L, LyricsDisplayPreferences(textSizeSp = 22f)) }
        try {
            withTimeout(10000L) { gate.entered.await() }
            assertEquals(LyricsDisplayWriteResult.Failed(LyricsDisplayPreferences(), LyricsDisplayIssue.BUSY),
                session.store.save(0L, LyricsDisplayPreferences(textSizeSp = 30f)))
            first.cancel()
            assertFalse(first.isCompleted)
            assertEquals(LyricsDisplayWriteResult.Failed(LyricsDisplayPreferences(), LyricsDisplayIssue.BUSY),
                session.store.save(0L, LyricsDisplayPreferences(textSizeSp = 30f)))
        } finally {
            gate.release.complete(Unit)
            withTimeout(10000L) { first.cancelAndJoin() }
        }
        assertTrue(first.isCancelled)
        assertFalse(file.exists())
        assertEquals(LyricsDisplayState.Ready(LyricsDisplayPreferences(), persisted = false), session.ready())
        assertEquals(LyricsDisplayWriteResult.Saved(LyricsDisplayPreferences(revision = 1L, textSizeSp = 26f)),
            session.store.save(0L, LyricsDisplayPreferences(textSizeSp = 26f)))
    }

    @Test(timeout = 30000L)
    fun cancellationAfterDurableCommitNeverClaimsTheOldValueWasRestored() = runBlocking {
        val file = preferencesFile()
        val gate = DisplayMutationGate()
        val session = openDisplay(file) { HeldDisplayMutationDataStore(it, gate, afterCommit = true) }
        session.ready()
        val next = LyricsDisplayPreferences(legacyKeepScreenOn = true)
        gate.arm()
        val caller = launch { session.store.save(0L, next) }
        try {
            withTimeout(10000L) { gate.entered.await() }
            caller.cancel()
            assertFalse(caller.isCompleted)
            assertEquals(LyricsDisplayIssue.BUSY,
                (session.store.save(0L, next) as LyricsDisplayWriteResult.Failed).issue)
        } finally {
            gate.release.complete(Unit)
            withTimeout(10000L) { caller.cancelAndJoin() }
        }
        assertTrue(caller.isCancelled)
        val actual = withTimeout(10000L) {
            session.store.state.first { it is LyricsDisplayState.Ready && it.preferences.revision == 1L }
        } as LyricsDisplayState.Ready
        assertEquals(next.copy(revision = 1L), actual.preferences)
        session.close()
        assertEquals(next.copy(revision = 1L), openDisplay(file).ready().preferences)
    }

    @Test(timeout = 30000L)
    fun cancellationAfterTransformBeforeRealSerializationRetainsAdmissionUntilTheActualWriterExits() = runBlocking {
        val file = preferencesFile()
        val storage = LyricsDisplayPhysicalStorage(file)
        val session = openPhysicalDisplay(storage)
        session.ready()
        val current = (session.store.save(0L, LyricsDisplayPreferences(textSizeSp = 18f)) as LyricsDisplayWriteResult.Saved).preferences
        val bytes = file.readBytes()
        val writes = storage.writeScopes.get()
        val exited = storage.exitedWrites.get()
        val committed = storage.committedWrites.get()
        val gate = LyricsDisplayPhysicalWriteGate()
        storage.holdNextWrite(gate)
        val next = current.copy(textSizeSp = 30f)
        val caller = launch { session.store.save(1L, next) }
        try {
            assertEquals(next.copy(revision = 2L), LyricsDisplayCodec.decode(withTimeout(10000L) { gate.entered.await() }).preferences)
            assertEquals(writes + 1, storage.writeScopes.get())
            assertEquals(exited, storage.exitedWrites.get())
            caller.cancel()
            assertFalse(caller.isCompleted)
            assertEquals(LyricsDisplayWriteResult.Failed(current, LyricsDisplayIssue.BUSY), session.store.save(1L, next))
            assertArrayEquals(bytes, file.readBytes())
        } finally {
            gate.release.complete(Unit)
            withTimeout(10000L) { caller.cancelAndJoin() }
        }
        assertTrue(caller.isCancelled)
        assertEquals(exited + 1, storage.exitedWrites.get())
        assertEquals(committed + 1, storage.committedWrites.get())
        val actual = withTimeout(10000L) {
            session.store.state.first { it is LyricsDisplayState.Ready && it.preferences.revision == 2L }
        } as LyricsDisplayState.Ready
        assertEquals(next.copy(revision = 2L), actual.preferences)
        val after = actual.preferences.copy(blockSpacingDp = 20f)
        assertEquals(LyricsDisplayWriteResult.Saved(after.copy(revision = 3L)), session.store.save(2L, after))
        session.close()
        assertEquals(after.copy(revision = 3L), openDisplay(file).ready().preferences)
    }

    @Test(timeout = 30000L)
    fun cancellationAfterRealScratchSyncWaitsForRenameFailureAndRetainsItsIoEvidence() = runBlocking {
        val file = preferencesFile()
        val storage = LyricsDisplayPhysicalStorage(file)
        val session = openPhysicalDisplay(storage)
        session.ready()
        val current = (session.store.save(0L, LyricsDisplayPreferences(textSizeSp = 18f)) as LyricsDisplayWriteResult.Saved).preferences
        val bytes = file.readBytes()
        val exited = storage.exitedWrites.get()
        val committed = storage.committedWrites.get()
        val gate = LyricsDisplayPhysicalWriteGate(afterScratchWrite = true)
        storage.holdNextWrite(gate)
        val next = current.copy(textSizeSp = 30f)
        val cancellation = CompletableDeferred<CancellationException>()
        val caller = launch {
            try {
                session.store.save(1L, next)
                throw AssertionError("the cancelled caller must not receive a normal write result")
            } catch (cancelled: CancellationException) {
                cancellation.complete(cancelled)
                throw cancelled
            }
        }
        val blocker = try {
            assertEquals(next.copy(revision = 2L), LyricsDisplayCodec.decode(withTimeout(10000L) { gate.entered.await() }).preferences)
            assertEquals(exited, storage.exitedWrites.get())
            caller.cancel()
            assertFalse(caller.isCompleted)
            assertEquals(LyricsDisplayWriteResult.Failed(current, LyricsDisplayIssue.BUSY), session.store.save(1L, next))
            assertArrayEquals(bytes, file.readBytes())
            obstructAtomicMove(file, gate)
        } finally {
            gate.release.complete(Unit)
            withTimeout(10000L) { caller.cancelAndJoin() }
        }
        val failure = withTimeout(10000L) { cancellation.await() }
        assertTrue(failure.suppressed.any { it is IOException })
        assertTrue(caller.isCancelled)
        assertEquals(exited + 1, storage.exitedWrites.get())
        assertEquals(committed, storage.committedWrites.get())
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(current, session.ready().preferences)
        // The real DataStore cache still contains the uncommitted proposal. It cannot confirm disk.
        assertEquals(next.copy(revision = 2L), LyricsDisplayCodec.decode(session.backingDataStore.data.first()).preferences)
        clearMoveObstruction(blocker)
        assertEquals(LyricsDisplayWriteResult.Saved(next.copy(revision = 2L)), session.store.save(1L, next))
        session.close()
        assertEquals(next.copy(revision = 2L), openDisplay(file).ready().preferences)
    }

    @Test(timeout = 30000L)
    fun aRealRenameFailureCannotConfirmTheCachedProposalAndRetryUsesTheActualFileRevision() = runBlocking {
        val file = preferencesFile()
        val storage = LyricsDisplayPhysicalStorage(file)
        val session = openPhysicalDisplay(storage)
        session.ready()
        val current = (session.store.save(0L, LyricsDisplayPreferences(textSizeSp = 18f)) as LyricsDisplayWriteResult.Saved).preferences
        val bytes = file.readBytes()
        val committed = storage.committedWrites.get()
        val gate = LyricsDisplayPhysicalWriteGate(afterScratchWrite = true)
        storage.holdNextWrite(gate)
        val next = current.copy(horizontalMarginDp = 24f)
        val write = async { session.store.save(1L, next) }
        val blocker = try {
            withTimeout(10000L) { gate.entered.await() }
            obstructAtomicMove(file, gate)
        } finally { gate.release.complete(Unit) }
        assertEquals(LyricsDisplayWriteResult.Failed(current, LyricsDisplayIssue.IO), withTimeout(10000L) { write.await() })
        assertEquals(committed, storage.committedWrites.get())
        assertEquals(next.copy(revision = 2L), LyricsDisplayCodec.decode(session.backingDataStore.data.first()).preferences)
        assertEquals(LyricsDisplayState.Ready(current, persisted = true), session.store.state.value)
        assertArrayEquals(bytes, file.readBytes())
        clearMoveObstruction(blocker)
        assertEquals(LyricsDisplayWriteResult.Saved(next.copy(revision = 2L)), session.store.save(1L, next))
        session.close()
        assertEquals(next.copy(revision = 2L), openDisplay(file).ready().preferences)
    }

    @Test(timeout = 30000L)
    fun aFailedDirectDataStoreWriteCannotPublishItsCachedProposalAsObservedSavedSettings() = runBlocking {
        val file = preferencesFile()
        val current = LyricsDisplayPreferences(revision = 1L, textSizeSp = 18f)
        writeDisplayFixture(file, LyricsDisplayCodec.encode(current))
        val bytes = file.readBytes()
        val storage = LyricsDisplayPhysicalStorage(file)
        lateinit var observations: CompletingDisplayObservationsDataStore
        val session = openPhysicalDisplay(storage) { delegate ->
            CompletingDisplayObservationsDataStore(delegate).also { observations = it }
        }
        assertEquals(current, session.ready().preferences)
        withTimeout(10000L) { observations.completions.first { it >= 1 } }
        val completed = observations.completions.value
        val gate = LyricsDisplayPhysicalWriteGate(afterScratchWrite = true)
        storage.holdNextWrite(gate)
        val proposal = current.copy(revision = 2L, emphasizeWords = true)
        val write = async { runCatching { session.backingDataStore.updateData { LyricsDisplayCodec.encode(proposal) } } }
        val blocker = try {
            withTimeout(10000L) { gate.entered.await() }
            obstructAtomicMove(file, gate)
        } finally { gate.release.complete(Unit) }
        assertTrue(withTimeout(10000L) { write.await() }.exceptionOrNull() is IOException)
        withTimeout(10000L) { observations.completions.first { it > completed } }
        assertEquals(proposal, LyricsDisplayCodec.decode(session.backingDataStore.data.first()).preferences)
        assertEquals(LyricsDisplayState.Ready(current, persisted = true), session.store.state.value)
        assertArrayEquals(bytes, file.readBytes())
        clearMoveObstruction(blocker)
        session.close()
        assertEquals(current, openDisplay(file).ready().preferences)
    }

    private fun obstructAtomicMove(file: File, gate: LyricsDisplayPhysicalWriteGate): File {
        val scratch = File(file.absolutePath + ".tmp")
        assertTrue(scratch.isFile)
        val original = File(file.absolutePath + ".original-before-move")
        val retiredDestination = File(file.absolutePath + ".failed-move-destination")
        assertFalse(original.exists())
        assertFalse(retiredDestination.exists())
        assertTrue(file.isFile)
        assertTrue(file.renameTo(original))
        val restoreOriginal = {
            if (file.exists()) {
                check(file.isDirectory)
                check(file.renameTo(retiredDestination))
            }
            check(original.renameTo(file))
        }
        try {
            // REPLACE_EXISTING may unlink a regular target. A nonempty destination rejects
            // the actual move while the separately retained original keeps its old bytes.
            assertTrue(file.mkdir())
            File(file, "blocker").writeText("controlled nonempty actual move destination")
            gate.restoreOriginalOnFailure(restoreOriginal)
        } catch (failure: Throwable) {
            try { restoreOriginal() }
            catch (restoreFailure: Throwable) {
                if (restoreFailure !== failure) failure.addSuppressed(restoreFailure)
            }
            throw failure
        }
        return File(retiredDestination, "blocker")
    }

    private fun clearMoveObstruction(blocker: File) {
        assertTrue(blocker.delete())
        assertTrue(blocker.parentFile.delete())
    }
}
