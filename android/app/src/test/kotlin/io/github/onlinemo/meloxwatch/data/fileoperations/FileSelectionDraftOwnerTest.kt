package io.github.onlinemo.meloxwatch.data.fileoperations

import io.github.onlinemo.meloxwatch.data.fileoperations.fixture.AudioWriteFixtures
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileSelectionDraftOwnerTest : FileOperationRoomTestFixture() {
    @Test fun crossPageSelectionAndSelectAllReuseTheOneActualQueueViewLease() = runBlocking {
        val ids = (0..200).map { tree.putAudio("$it.mp3", AudioWriteFixtures.mp3(), "audio/mpeg") }
        scan(*ids.toTypedArray())
        val uiView = library.openView()
        val owner = FileSelectionDraftOwner(library, journal, io)
        var draft = owner.open(uiView.viewId)
        try {
            val first = selected(ids.first())
            val last = selected(ids.last())
            draft = owner.toggle(draft, first.trackKey)
            draft = owner.toggle(draft, last.trackKey)
            assertEquals(setOf(first.trackKey, last.trackKey), owner.membership(draft, listOf(first.trackKey, last.trackKey)))
            draft = owner.selectAll(draft, true)
            draft = owner.toggle(draft, last.trackKey)
            assertEquals(200L, draft.selectedCount)
            val producer = owner.producer(draft)
            try {
                var offset = 0L
                val observed = HashSet<io.github.onlinemo.meloxwatch.model.TrackKey>()
                while (offset < draft.selectedCount) {
                    val page = producer.read(offset, 100)
                    assertEquals(draft.selection, page.selection)
                    assertTrue(page.tracks.size in 1..100)
                    observed += page.tracks.map { it.trackKey }
                    offset += page.tracks.size
                    assertEquals(offset == draft.selectedCount, page.complete)
                }
                assertEquals(200, observed.size)
                assertTrue(first.trackKey in observed)
                assertFalse(last.trackKey in observed)
            } finally { producer.close() }
            assertTrue(owner.membership(draft, listOf(first.trackKey)).isNotEmpty())
        } finally {
            owner.close(draft)
            library.closeView(uiView.viewId)
        }
        assertEquals(null, io.read { journal.selectionDraft(draft.draftId) })
    }

    @Test fun wholeListDefaultSelectionDoesNotMaterializeTwentyThousandAndOneDraftRows() = runBlocking {
        val initial = FileSelectionDraft(java.util.UUID.randomUUID().toString(), FileSelectionToken("full-draft-view", 1L, 20_001L))
        val selected = io.read {
            journal.createSelectionDraft(initial)
            journal.selectAllDraft(initial.draftId, initial.revision, true)
        }
        assertTrue(selected.allSelected)
        assertEquals(20_001L, selected.selectedCount)
        assertEquals(0L, io.read { draftRows(selected.draftId) })
        val tail = io.github.onlinemo.meloxwatch.model.TrackKey(io.github.onlinemo.meloxwatch.model.TrackKey.LOCAL_PROVIDER, "tail-20000")
        val excluded = io.read { journal.toggleSelectionDraft(initial.draftId, selected.revision, FileSelectedTrack(tail, null, null)) }
        assertEquals(20_000L, excluded.selectedCount)
        assertEquals(1L, io.read { draftRows(selected.draftId) })
        assertEquals(setOf(tail), io.read { journal.draftExceptions(initial.draftId, excluded.revision, listOf(tail)) })
        io.read { journal.retireSelectionDraft(initial.draftId) }
    }

    private fun draftRows(id: String): Long = BundledSQLiteDriver().open(File(temporaryFolder.root, "file-operation-journal.db").absolutePath).use { connection ->
        connection.prepare("SELECT COUNT(*) FROM file_draft_tracks WHERE draftId=?").use { query ->
            query.bindText(1, id)
            check(query.step())
            query.getLong(0)
        }
    }
}
