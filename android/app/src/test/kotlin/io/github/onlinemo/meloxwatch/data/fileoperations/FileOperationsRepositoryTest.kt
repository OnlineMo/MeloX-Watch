package io.github.onlinemo.meloxwatch.data.fileoperations

import io.github.onlinemo.meloxwatch.data.database.withTestReaderSql
import io.github.onlinemo.meloxwatch.data.fileoperations.fixture.AudioWriteFixtures
import io.github.onlinemo.meloxwatch.data.library.LibraryException
import io.github.onlinemo.meloxwatch.data.library.LibraryIssue
import io.github.onlinemo.meloxwatch.data.library.LibrarySourceState
import io.github.onlinemo.meloxwatch.data.library.RoomLocalLibraryRepository
import io.github.onlinemo.meloxwatch.data.local.LocalGrantObservation
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileOperationsRepositoryTest : FileOperationRoomTestFixture() {
    @Test fun indexRemovalPersistsMembershipAndExplicitRestoreKeepsOriginal() = runBlocking {
        val bytes = AudioWriteFixtures.mp3()
        val id = tree.putAudio("source.mp3", bytes, "audio/mpeg")
        scan(id)
        val chosen = selected(id)
        val port = repository()
        val preview = prepare(port, chosen, FileOperationCommand.removeFromIndex())
        assertEquals(FileOperationPhase.COMPLETED, port.confirm(confirmation(preview)).phase)
        assertEquals(0L, library.overview().totalTracks)
        assertArrayEquals(bytes, tree.bytes(id))
        assertEquals(0, tree.deletes.get())
        val reopenedLibrary = RoomLocalLibraryRepository(database, { 2_000L }, { 1_794_787_200_000L })
        reopenedLibrary.initialize()
        assertEquals(0L, reopenedLibrary.overview().totalTracks)
        assertEquals(chosen.trackKey, io.read { checkNotNull(journal.indexIgnore(chosen.trackKey)).trackKey })
        port.restoreIndex(preview.operationId)
        assertEquals(1L, library.overview().totalTracks)
        assertArrayEquals(bytes, tree.bytes(id))
    }

    @Test fun indexDatabaseFailureCannotBeReportedCompleted() = runBlocking {
        val id = tree.putAudio("source.mp3", AudioWriteFixtures.mp3(), "audio/mpeg")
        scan(id)
        val failingBridge = object : FileOperationLibrary by bridge {
            override suspend fun reconcile(item: FileOperationItem, command: FileOperationCommand,
                impacts: List<FileOperationImpact>, routes: List<FilePublishedRoute>) {
                bridge.reconcile(item, command, impacts, routes)
                throw LibraryException(LibraryIssue.STORAGE_FULL)
            }
        }
        val port = repository(libraryPort = failingBridge)
        val preview = prepare(port, selected(id), FileOperationCommand.removeFromIndex())
        expectSuspendIssue(FileOperationIssue.STORAGE_FULL) { port.confirm(confirmation(preview)) }
        val progress = port.readProgress(preview.operationId)
        assertEquals(FileOperationPhase.NEEDS_RECOVERY, progress.phase)
        assertNotEquals(FileOperationPhase.COMPLETED, progress.phase)
        assertTrue(io.read { journal.hasUncoordinatedWork(preview.operationId) })
        assertEquals(0, tree.deletes.get())
    }

    @Test fun emptySourceRemovalFailureCannotBeInferredSuccessfulFromZeroRows() = runBlocking {
        val empty = library.registerSource(LocalSourceSpec("empty-source", LocalSourceKind.TREE, "content://empty-fixture/tree/root"),
            LocalGrantObservation(read = ObservedCapability.AVAILABLE, enumerate = ObservedCapability.AVAILABLE))
        val failingBridge = object : FileOperationLibrary by bridge {
            override suspend fun removeSource(sourceId: String, expectedAuthEpoch: Long) {
                throw LibraryException(LibraryIssue.STORAGE_FULL)
            }
        }
        val port = repository(libraryPort = failingBridge)
        val preview = port.prepareSourceRemoval(java.util.UUID.randomUUID().toString(), empty.spec.sourceId, empty.authEpoch)
        expectSuspendIssue(FileOperationIssue.STORAGE_FULL) { port.confirm(confirmation(preview)) }
        assertEquals(FileOperationPhase.FAILED, port.readProgress(preview.operationId).phase)
        assertEquals(LibrarySourceState.ACTIVE, checkNotNull(library.source(empty.spec.sourceId)).state)
    }

    @Test fun indexTicketDoesNotAuthorizeOriginalDeletionAndFullBeforeExternalWritesNothing() = runBlocking {
        val bytes = AudioWriteFixtures.mp3()
        val id = tree.putAudio("source.mp3", bytes, "audio/mpeg")
        scan(id)
        val chosen = selected(id)
        val ordinary = repository()
        val index = prepare(ordinary, chosen, FileOperationCommand.removeFromIndex())
        expectSuspendIssue(FileOperationIssue.CONFIRMATION_REQUIRED) {
            ordinary.confirm(confirmation(index).copy(kind = FileOperationKind.DELETE_ORIGINAL))
        }
        val fullJournal = object : FileOperationJournal by journal {
            override fun updateRecord(record: FileOperationRecord) {
                if (record.phase == FileOperationPhase.CONFIRMED) throw FileOperationException(FileOperationIssue.STORAGE_FULL)
                journal.updateRecord(record)
            }
        }
        val port = repository(journalPort = fullJournal)
        val original = prepare(port, chosen, FileOperationCommand.deleteOriginal())
        expectSuspendIssue(FileOperationIssue.STORAGE_FULL) { port.confirm(confirmation(original)) }
        assertArrayEquals(bytes, tree.bytes(id))
        assertEquals(0, tree.deletes.get())
        assertEquals(FileOperationPhase.PREVIEW, port.readProgress(original.operationId).phase)
    }

    @Test fun startupReadinessRejectsOrdinaryAndPhysicalRecoveryEntrypoints() = runBlocking {
        val id = tree.putAudio("source.mp3", AudioWriteFixtures.mp3(), "audio/mpeg")
        scan(id)
        val port = repository()
        val preview = prepare(port, selected(id), FileOperationCommand.deleteOriginal())
        readinessIssue = FileOperationIssue.RECOVERY_REQUIRED
        expectSuspendIssue(FileOperationIssue.RECOVERY_REQUIRED) { port.confirm(confirmation(preview)) }
        expectSuspendIssue(FileOperationIssue.RECOVERY_REQUIRED) { port.recover(preview.operationId) }
        val recovery = FileRecoveryConfirmation(preview.operationId, "unexecuted-unit", "unapproved-binding",
            FileRecoveryAction.RESTORE_RETAINED_ORIGINAL, true, true)
        expectSuspendIssue(FileOperationIssue.RECOVERY_REQUIRED) { port.restoreOriginal(recovery) }
        expectSuspendIssue(FileOperationIssue.RECOVERY_REQUIRED) { port.discardStaging(recovery.copy(action = FileRecoveryAction.DISCARD_STAGING)) }
        assertEquals(0, tree.deletes.get())
        assertEquals(0, tree.renames.get())
        assertEquals(0, tree.creates.get())
    }

    @Test fun cancellationSurvivesJournalBusyDuringCleanupAndKeepsRecoveryFence() = runBlocking {
        val id = tree.putAudio("source.mp3", AudioWriteFixtures.mp3(), "audio/mpeg")
        scan(id)
        var rejectCleanup = false
        val busyJournal = object : FileOperationJournal by journal {
            override fun updateItem(item: FileOperationItem) {
                if (rejectCleanup) throw FileOperationException(FileOperationIssue.BUSY)
                journal.updateItem(item)
            }
        }
        val cancelling = object : FileOperationLibrary by bridge {
            override suspend fun reconcile(item: FileOperationItem, command: FileOperationCommand,
                impacts: List<FileOperationImpact>, routes: List<FilePublishedRoute>) {
                rejectCleanup = true
                throw CancellationException("Explicit fixture cancellation")
            }
        }
        val port = repository(busyJournal, cancelling)
        val preview = prepare(port, selected(id), FileOperationCommand.removeFromIndex())
        try {
            port.confirm(confirmation(preview))
            throw AssertionError("Expected original cancellation")
        } catch (cancelled: CancellationException) {
            assertTrue(cancelled.suppressed.isNotEmpty())
        }
        rejectCleanup = false
        assertTrue(io.read { journal.hasUncoordinatedWork(preview.operationId) })
        assertNotEquals(FileOperationPhase.COMPLETED, port.readProgress(preview.operationId).phase)
        database.withTestReaderSql { query("PRAGMA foreign_key_check") { assertFalse(it.step()) } }
    }
}
