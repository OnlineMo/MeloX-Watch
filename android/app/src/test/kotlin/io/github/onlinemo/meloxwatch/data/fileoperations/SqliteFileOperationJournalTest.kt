package io.github.onlinemo.meloxwatch.data.fileoperations

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.onlinemo.meloxwatch.data.fileoperations.fixture.AudioWriteFixtures
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 每次使用真实 BundledSQLite 文件；这里只定义回归，未执行 journal/SQL 或模拟设备验收。 */
class SqliteFileOperationJournalTest : FileOperationPhysicalTestFixture() {
    private fun record(total: Long, command: FileOperationCommand = FileOperationCommand.removeFromIndex()): FileOperationRecord {
        val selection = FileSelectionToken("journal-fixture", 1L, total)
        return FileOperationRecord(UUID.randomUUID().toString(), selection, command, FileOperationCodec.commandBinding(selection, command))
    }

    private fun indexTarget(number: Long): FileOperationTarget {
        val key = TrackKey(TrackKey.LOCAL_PROVIDER, "journal-track-$number")
        return FileOperationTarget("track:${key.trackId}", key, "version-$number", null, null, null, null)
    }

    @Test fun exactOperationAndUnitColumnsReopenWithUncoordinatedIndexWork() {
        val file = File(temporaryFolder.root, "journal-reopen.db")
        val original = record(1L)
        val target = indexTarget(1L)
        SqliteFileOperationJournal.open(file).use { journal ->
            journal.create(original)
            journal.appendSelection(original.operationId, 0L, FileSelectedTrack(target.trackKey, null, null), target)
            val preview = journal.sealPreview(original.operationId)
            journal.updateRecord(preview.copy(phase = FileOperationPhase.CONFIRMED))
            journal.updateRecord(preview.copy(phase = FileOperationPhase.RUNNING))
            journal.updateItem(checkNotNull(journal.unit(original.operationId, target.unitKey)).copy(phase = FileItemPhase.APPLYING))
        }
        SqliteFileOperationJournal.open(file).use { reopened ->
            val saved = checkNotNull(reopened.record(original.operationId))
            assertEquals(FileOperationPhase.RUNNING, saved.phase)
            assertEquals(original.commandBinding, saved.commandBinding)
            assertEquals(FileOperationKind.REMOVE_FROM_INDEX, saved.command.kind)
            val item = checkNotNull(reopened.unit(original.operationId, target.unitKey))
            assertEquals(FileItemPhase.APPLYING, item.phase)
            assertEquals(target, item.target)
            assertTrue(reopened.hasUncoordinatedWork(original.operationId))
            assertEquals(listOf(original.operationId), reopened.pending().map { it.operationId })
        }
    }

    @Test fun twentyThousandAndOneFrozenRowsRemainCompleteInPagesOfOneHundred() {
        val file = File(temporaryFolder.root, "journal-complete-selection.db")
        val original = record(20_001L)
        SqliteFileOperationJournal.open(file).use { journal ->
            journal.create(original)
            for (number in 0L until original.selection.totalCount) {
                val target = indexTarget(number)
                journal.appendSelection(original.operationId, number, FileSelectedTrack(target.trackKey, null, null), target)
                journal.appendImpacts(original.operationId, target.unitKey, listOf(
                    FileOperationImpact("T:${target.trackKey.trackId}", FileImpactKind.TRACK, target.trackKey, null, null, null, null)))
            }
            val sealed = journal.sealPreview(original.operationId)
            assertEquals(20_001L, sealed.selectedCount)
            assertEquals(20_001L, sealed.trackImpactCount)
        }
        SqliteFileOperationJournal.open(file).use { reopened ->
            var after: Long? = null
            var observed = 0L
            while (true) {
                val page = reopened.items(original.operationId, after, 100)
                if (page.isEmpty()) break
                assertTrue(page.size <= 100)
                page.forEach { assertEquals(observed++, it.ordinal) }
                after = page.last().ordinal
            }
            assertEquals(20_001L, observed)
        }
    }

    @Test fun physicalUnitDedupStillCountsEveryRelatedTrackAliasAndPreparedCue() {
        val id = tree.putAudio("shared.flac", AudioWriteFixtures.flac(), "audio/flac")
        val target = provedTarget(id, FileOperationKind.WRITE_TAGS)
        val cueKey = TrackKey(TrackKey.LOCAL_PROVIDER, "cue-v1-${UUID.randomUUID()}")
        val cue = target.copy(trackKey = cueKey, trackVersionId = "cue-version",
            descriptor = checkNotNull(target.descriptor).copy(trackKey = cueKey))
        val operation = record(2L, FileOperationCommand.writeTags(AudioTagPatch(mapOf(AudioTagField.TITLE to listOf("After")))))
        SqliteFileOperationJournal.open(File(temporaryFolder.root, "journal-shared-unit.db")).use { journal ->
            journal.create(operation)
            assertTrue(journal.appendSelection(operation.operationId, 0L,
                FileSelectedTrack(target.trackKey, target.descriptor?.location?.locationId, 1L), target))
            assertFalse(journal.appendSelection(operation.operationId, 1L,
                FileSelectedTrack(cueKey, cue.descriptor?.location?.locationId, 1L), cue))
            journal.appendImpacts(operation.operationId, target.unitKey, listOf(
                FileOperationImpact("T:${target.trackKey.trackId}", FileImpactKind.TRACK, target.trackKey, null, null, null, null),
                FileOperationImpact("C:${cueKey.trackId}", FileImpactKind.CUE_SEGMENT, cueKey, null, null, null, null),
                FileOperationImpact("A:first", FileImpactKind.ALIAS, null, null, "first", "first-node", id),
                FileOperationImpact("A:second", FileImpactKind.ALIAS, null, null, "second", "second-node", id),
            ))
            val sealed = journal.sealPreview(operation.operationId)
            assertEquals(2L, sealed.selectedCount)
            assertEquals(1L, sealed.physicalCount)
            assertEquals(1L, sealed.trackImpactCount)
            assertEquals(1L, sealed.cueImpactCount)
            assertEquals(2L, sealed.aliasImpactCount)
            assertEquals(1, journal.items(operation.operationId).size)
        }
    }

    @Test fun retiredDraftRowsReclaimOnePageAndProcessReopenCannotRecoverConfirmation() {
        val file = File(temporaryFolder.root, "journal-draft.db")
        val draft = FileSelectionDraft(UUID.randomUUID().toString(), FileSelectionToken("draft-view", 1L, 201L))
        SqliteFileOperationJournal.open(file).use { journal ->
            journal.createSelectionDraft(draft)
            var selected = draft
            for (number in 0L..200L) selected = journal.toggleSelectionDraft(draft.draftId, selected.revision,
                FileSelectedTrack(indexTarget(number).trackKey, null, null))
            assertEquals(201L, selected.selectedCount)
        }
        SqliteFileOperationJournal.open(file).use { reopened ->
            reopened.retireOrphanDraftPage(emptySet(), null)
            assertEquals(null, reopened.selectionDraft(draft.draftId))
            assertTrue(reopened.reclaimRetiredDraftPage(100))
            assertTrue(reopened.reclaimRetiredDraftPage(100))
            assertFalse(reopened.reclaimRetiredDraftPage(100))
            assertTrue(reopened.pending().isEmpty())
        }
    }

    @Test fun unknownPersistedStateRefusesOpenInsteadOfEscapingPendingQuery() {
        val file = File(temporaryFolder.root, "journal-unknown-phase.db")
        val operation = record(0L)
        SqliteFileOperationJournal.open(file).use { it.create(operation) }
        BundledSQLiteDriver().open(file.absolutePath).use { connection ->
            connection.prepare("UPDATE file_operations SET phase='UNKNOWN_AFTER_EXTERNAL_CALL'").use { it.step() }
        }
        expectIssue(FileOperationIssue.RECOVERY_REQUIRED) { SqliteFileOperationJournal.open(file).close() }
        assertEquals(0, tree.deletes.get())
        assertEquals(0, tree.renames.get())
    }
}
