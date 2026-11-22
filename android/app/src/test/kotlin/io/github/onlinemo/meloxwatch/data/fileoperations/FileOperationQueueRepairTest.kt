package io.github.onlinemo.meloxwatch.data.fileoperations

import io.github.onlinemo.meloxwatch.data.fileoperations.fixture.AudioWriteFixtures
import io.github.onlinemo.meloxwatch.data.local.AndroidLocalSources
import io.github.onlinemo.meloxwatch.data.local.LocalInputLimits
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.queue.QueueInputPage
import io.github.onlinemo.meloxwatch.data.queue.QueuePageSource
import io.github.onlinemo.meloxwatch.data.queue.QueueReplacementRequest
import io.github.onlinemo.meloxwatch.model.FrozenQueueView
import io.github.onlinemo.meloxwatch.model.QueueCheckpoint
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.QueueMode
import io.github.onlinemo.meloxwatch.model.QueueRepeatMode
import io.github.onlinemo.meloxwatch.model.QueueSnapshot
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileOperationQueueRepairTest : FileOperationRoomTestFixture() {
    private suspend fun fullQueue(chosen: FileSelectedTrack, count: Long): QueueSnapshot {
        val descriptor = library.resolveLocalRead(chosen.trackKey, chosen.locationId, chosen.contentRevision)
        val view = FrozenQueueView("queue-file-operation-fixture", library.overview().revision, count)
        val commit = queues.replace(QueueReplacementRequest(UUID.randomUUID().toString(), null, 0L, view,
            mode = QueueMode(repeat = QueueRepeatMode.LIST, cycleLimit = 3)), QueuePageSource { offset, limit ->
            val end = minOf(count, offset + limit)
            QueueInputPage(view, offset, (offset until end).map { number ->
                QueueEntry("persistent-instance-$number", chosen.trackKey, number, number, "Before", location = descriptor.location)
            })
        })
        return queues.saveCheckpoint(commit.snapshot.generationId, commit.snapshot.checkpointRevision,
            QueueCheckpoint("persistent-instance-${count - 1L}", 3210L, 1))
    }

    @Test fun twentyThousandAndOneQueueInstancesKeepIdsOrderCheckpointAndOneRevision() = runBlocking {
        val id = tree.putAudio("source.mp3", AudioWriteFixtures.mp3(), "audio/mpeg")
        scan(id)
        val chosen = selected(id)
        val before = fullQueue(chosen, 20_001L)
        val port = repository()
        val preview = prepare(port, chosen, FileOperationCommand.rename("renamed.mp3"))
        assertEquals(FileOperationPhase.COMPLETED, port.confirm(confirmation(preview)).phase)
        val after = checkNotNull(queues.snapshot())
        val publishedUri = checkNotNull(port.items(preview.operationId).single().artifacts.published).proof.uri
        assertEquals(before.generationId, after.generationId)
        assertEquals(before.entryCount, after.entryCount)
        assertEquals(before.queueRevision + 1L, after.queueRevision)
        assertEquals(before.checkpointRevision, after.checkpointRevision)
        assertEquals(before.checkpoint, after.checkpoint)
        assertEquals(before.mode, after.mode)
        var offset = 0L
        while (offset < after.entryCount) {
            val page = queues.page(after.generationId, offset, 100)
            assertTrue(page.size <= 100 && page.isNotEmpty())
            for (row in page) {
                assertEquals("persistent-instance-$offset", row.queueEntryId)
                assertEquals(offset, row.originalOrder)
                assertEquals(offset++, row.playbackOrder)
                val location = checkNotNull(row.location)
                assertNotEquals(chosen.contentRevision, location.contentRevision)
                assertEquals(publishedUri, location.uri)
            }
        }
        port.recover(preview.operationId)
        assertEquals(after, queues.snapshot())
    }

    @Test fun largeWeakSafReadRemainsUnknownWhileAllPublishedUrisAndRevisionsAreUpdated() = runBlocking {
        val bytes = ByteArrayOutputStream().apply { repeat(1000) { write(AudioWriteFixtures.mp3(tagged = false)) } }.toByteArray()
        assertTrue(bytes.size > LocalInputLimits.READ_PROOF_BYTES)
        val id = tree.putAudio("large.mp3", bytes, "audio/mpeg")
        scan(id)
        val chosen = selected(id)
        val before = fullQueue(chosen, 201L)
        val originalUri = library.resolveLocalRead(chosen.trackKey, chosen.locationId, chosen.contentRevision).location.uri
        val port = repository()
        val preview = prepare(port, chosen, FileOperationCommand.rename("large-renamed.mp3"))
        assertEquals(FileOperationPhase.COMPLETED, port.confirm(confirmation(preview)).phase)
        val after = checkNotNull(queues.snapshot())
        val publishedUri = checkNotNull(port.items(preview.operationId).single().artifacts.published).proof.uri
        assertEquals(before.queueRevision + 1L, after.queueRevision)
        for (offset in listOf(0L, 100L, 200L)) {
            for (row in queues.page(after.generationId, offset, 100)) {
                val location = checkNotNull(row.location)
                assertNotEquals(chosen.contentRevision, location.contentRevision)
                assertNotEquals(originalUri, location.uri)
                assertEquals(publishedUri, location.uri)
                val descriptor = library.resolveLocalRead(row.trackKey, location.locationId, location.contentRevision)
                try {
                    AndroidLocalSources(context, io).validateRead(descriptor)
                    throw AssertionError("Large weak SAF content must retain unknown state")
                } catch (failure: SourceReadException) {
                    assertEquals(SourceReadIssue.CONTENT_UNKNOWN, failure.issue)
                }
            }
        }
    }

    @Test fun queueCommitBeforePrivateAcknowledgementDoesNotBumpRevisionTwice() = runBlocking {
        val id = tree.putAudio("source.mp3", AudioWriteFixtures.mp3(), "audio/mpeg")
        scan(id)
        val chosen = selected(id)
        val before = fullQueue(chosen, 201L)
        var rejected = false
        val interruptedJournal = object : FileOperationJournal by journal {
            override fun completeQueueRepair(binding: FileQueueRepairBinding) {
                if (!rejected) {
                    rejected = true
                    throw FileOperationException(FileOperationIssue.STORAGE_FULL)
                }
                journal.completeQueueRepair(binding)
            }
        }
        val port = repository(journalPort = interruptedJournal)
        val preview = prepare(port, chosen, FileOperationCommand.rename("renamed.mp3"))
        expectSuspendIssue(FileOperationIssue.STORAGE_FULL) { port.confirm(confirmation(preview)) }
        port.recover(preview.operationId)
        val after = checkNotNull(queues.snapshot())
        assertTrue(rejected)
        assertEquals(before.queueRevision + 1L, after.queueRevision)
        assertEquals(before.checkpoint, after.checkpoint)
        assertEquals(FileOperationPhase.COMPLETED, port.readProgress(preview.operationId).phase)
    }
}
