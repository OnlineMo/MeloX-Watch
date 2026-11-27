package io.github.onlinemo.meloxwatch.data.backup

import io.github.onlinemo.meloxwatch.model.CueTrackReference
import io.github.onlinemo.meloxwatch.model.LocalCueReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class BackupStagePaginationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test(timeout = 30_000L)
    fun streamedDuplicateReferencesKeepMissingFlagsAndRejectConflictingCueHints() {
        fun reference(number: Int) = LocalCueReference(CueTrackReference(
            "source", "document", number, 1L, 0L, 75L, 1_000_000L, null, "sha256:" + "a".repeat(64),
        ))
        val first = reference(1)
        val second = reference(2)
        val firstId = first.trackKey.trackId
        val secondId = second.trackKey.trackId
        SqliteBackupStage.create(BackupPrivateDirectory.open(temporary.newFolder())) {}.use { stage ->
            stage.begin(BackupTestFixtures.header(BackupScope.QUEUE))
            stage.record(BackupTestFixtures.queueMeta(0L))
            stage.sealSnapshot()
            stage.beginRelinkReferences()
            stage.referenceTracks((0 until 100).asSequence().map { index ->
                val cue = if (index % 2 == 0) first else second
                BackupRelinkTrack(cue.trackKey.trackId, index == 99, cue)
            })
            stage.referenceTracks((0 until 100).asSequence().map { index ->
                BackupRelinkTrack(if (index % 2 == 0) secondId else firstId, false, null)
            })
            val actual = stage.referenceTrackPage(null).associateBy { it.trackId }
            assertEquals(2, actual.size)
            assertEquals(BackupRelinkTrack(firstId, false, first), actual[firstId])
            assertEquals(BackupRelinkTrack(secondId, true, second), actual[secondId])
            val conflicting = LocalCueReference(second.identity.copy(groupRevision = 2L))
            try {
                stage.referenceTracks(sequenceOf(BackupRelinkTrack(secondId, true, conflicting)))
                throw AssertionError("Expected conflicting portable CUE hints")
            } catch (failure: BackupException) {
                assertEquals(BackupIssue.INVALID_REFERENCE, failure.issue)
            }
            assertEquals(actual, stage.referenceTrackPage(null).associateBy { it.trackId })
        }
    }

    @Test(timeout = 30_000L)
    fun prefixKeysMissingCursorsAndBothQueueOrdersRemainCompleteAcrossChunks() {
        val keys = listOf("q-0", "q-00", "q-00a") + (0 until 205).map { "r-" + it.toString().padStart(3, '0') }
        val orderedKeys = keys.sorted()
        val root = BackupPrivateDirectory.open(temporary.newFolder())
        SqliteBackupStage.create(root) {}.use { stage ->
            stage.begin(BackupTestFixtures.header(BackupScope.QUEUE))
            val meta = BackupTestFixtures.queueMeta(keys.size.toLong())
            stage.record(BackupRecord(meta.kind, meta.key, meta.values + ("currentEntryId" to BackupCell.Text(keys.first()))))
            for ((ordinal, key) in keys.withIndex()) {
                val row = BackupTestFixtures.queueEntry(ordinal.toLong(), keys.size.toLong())
                stage.record(BackupRecord(row.kind, key, row.values))
            }
            stage.sealSnapshot()

            val actual = ArrayList<String>()
            var after: String? = null
            do {
                val page = stage.pageNow(BackupRecordKind.QUEUE_ENTRY, after)
                assertTrue(page.size <= BackupLimits.ROWS_PER_CHUNK)
                actual += page.map { it.key }
                after = page.lastOrNull()?.key
            } while (after != null)
            assertEquals(orderedKeys, actual)
            for (cursor in listOf("", "q-0", "q-00B", "r-100", "z")) {
                assertEquals(orderedKeys.filter { it > cursor }.take(100),
                    stage.pageNow(BackupRecordKind.QUEUE_ENTRY, cursor).map { it.key })
            }

            for ((family, field) in listOf("queue-original" to "originalOrder", "queue-playback" to "playbackOrder")) {
                val ordinals = ArrayList<Long>()
                var cursor: Long? = null
                do {
                    val page = stage.orderedPage(BackupRecordKind.QUEUE_ENTRY, family, "queue", cursor)
                    assertTrue(page.size <= BackupLimits.ROWS_PER_CHUNK)
                    ordinals += page.map { it.long(field) }
                    cursor = page.lastOrNull()?.long(field)
                } while (cursor != null)
                assertEquals((0L until keys.size.toLong()).toList(), ordinals)
                assertEquals((1L..100L).toList(),
                    stage.orderedPage(BackupRecordKind.QUEUE_ENTRY, family, "queue", 0L).map { it.long(field) })
                assertTrue(stage.orderedPage(BackupRecordKind.QUEUE_ENTRY, family, "queue", keys.size.toLong()).isEmpty())
            }

            // These private indexes share the same verified stage lease and chunk cursor contract.
            stage.clearActualIds()
            stage.beginRelinkReferences()
            for ((ordinal, key) in keys.withIndex()) {
                stage.referenceTrack(key, ordinal % 2 == 0)
                stage.putBinding(BackupStoredBinding(BackupBindingFamily.QUEUE, key, "location-$key", false,
                    mapOf("sentinel" to BackupCell.Text(key))))
            }
            for (cursor in listOf<String?>(null, "q-0", "q-00B", "r-100", "z")) {
                val expected = orderedKeys.filter { cursor == null || it > cursor }.take(100)
                val references = stage.referenceTrackPage(cursor)
                val bindings = stage.bindingPage(BackupBindingFamily.QUEUE, cursor)
                assertEquals(expected, references.map { it.trackId })
                assertEquals(expected, bindings.map { it.incomingId })
                for (item in references) assertEquals(keys.indexOf(item.trackId) % 2 == 0, item.missingQueue)
                for (item in bindings) {
                    assertEquals("location-${item.incomingId}", item.actualId)
                    assertEquals(BackupCell.Text(item.incomingId), item.fences["sentinel"])
                }
            }
        }
    }
}
