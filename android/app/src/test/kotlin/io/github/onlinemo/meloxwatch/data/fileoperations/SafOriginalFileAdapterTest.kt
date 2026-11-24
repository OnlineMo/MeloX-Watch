package io.github.onlinemo.meloxwatch.data.fileoperations

import android.content.Intent
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.DocumentsContract
import io.github.onlinemo.meloxwatch.data.fileoperations.fixture.AudioWriteFixtures
import io.github.onlinemo.meloxwatch.data.fileoperations.fixture.FixtureDocumentTree
import io.github.onlinemo.meloxwatch.data.fileoperations.fixture.IndependentAudioTagReader
import io.github.onlinemo.meloxwatch.data.fileoperations.fixture.fixtureFileTarget
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 生产 SAF adapter + 真实文档调用/Files/PFD + 独立格式 oracle；不把 host fixture 当 Android decoder。 */
class SafOriginalFileAdapterTest : FileOperationPhysicalTestFixture() {
    @Test fun flacAndId3UseCompleteSeparateCandidatesAndRetainUnknownBytes() {
        val inputs = listOf(AudioWriteFixtures.flac(), AudioWriteFixtures.mp3())
        for ((index, bytes) in inputs.withIndex()) {
            val extension = if (index == 0) "flac" else "mp3"
            val id = tree.putAudio("source.$extension", bytes, if (index == 0) "audio/flac" else "audio/mpeg")
            val target = provedTarget(id, FileOperationKind.WRITE_TAGS)
            val command = FileOperationCommand.writeTags(AudioTagPatch(mapOf(
                AudioTagField.TITLE to listOf("After 中文"), AudioTagField.ARTISTS to listOf("A", "B"),
                AudioTagField.TRACK_NUMBER to listOf("8/16"), AudioTagField.DISC_NUMBER to listOf("2"),
            )))
            val initial = item(target)
            var recorded = initial
            val applied = adapter.apply(initial, command, false, CancellationSignal()) { recorded = it }
            assertEquals(applied, recorded)
            val published = checkNotNull(applied.artifacts.published)
            val backup = checkNotNull(applied.artifacts.originalBackup)
            assertNotEquals(checkNotNull(target.proof).inode, published.proof.inode)
            assertArrayEquals(bytes, tree.bytes(backup.proof.objectId))
            val before = IndependentAudioTagReader.read(bytes)
            val after = IndependentAudioTagReader.read(tree.bytes(published.proof.objectId))
            IndependentAudioTagReader.requirePreserved(before, after)
            assertEquals(listOf("After 中文"), after.fields["TITLE"])
            assertEquals(listOf("A", "B"), after.fields["ARTIST"])
            assertEquals(listOf("Keep album"), after.fields["ALBUM"])
            assertEquals(listOf("8/16"), after.fields["TRACKNUMBER"])
            val finished = adapter.finishArtifacts(applied.copy(phase = FileItemPhase.INDEX_RECONCILED), CancellationSignal())
            assertEquals(FileItemPhase.INDEX_RECONCILED, finished.phase)
            assertEquals(null, tree.idNamed(backup.proof.displayName))
        }
        assertTrue(tree.modes.all { it == "r" || it == "rw" })
        assertEquals(2, tree.creates.get())
        assertEquals(4, tree.renames.get())
        assertEquals(2, tree.deletes.get())
    }

    @Test fun untaggedMpegGetsId3WithoutChangingItsAudio() {
        val bytes = AudioWriteFixtures.mp3(tagged = false)
        val id = tree.putAudio("untagged.mp3", bytes, "audio/mpeg")
        val target = provedTarget(id, FileOperationKind.WRITE_TAGS)
        val command = FileOperationCommand.writeTags(AudioTagPatch(mapOf(AudioTagField.TITLE to listOf("Added title"))))
        val applied = adapter.apply(item(target), command, false, CancellationSignal()) {}
        val after = IndependentAudioTagReader.read(tree.bytes(checkNotNull(applied.artifacts.published).proof.objectId))
        assertArrayEquals(bytes, after.audio)
        assertEquals(listOf("Added title"), after.fields["TITLE"])
    }

    @Test fun unsupportedId3VersionAndReadonlyFdDoNotChangeOriginal() {
        val bytes = AudioWriteFixtures.mp3().apply { this[3] = 3 }
        val id = tree.putAudio("v23.mp3", bytes, "audio/mpeg")
        val target = fixtureFileTarget(context, authority, tree, id)
        expectIssue(FileOperationIssue.UNSUPPORTED_FORMAT) { adapter.inspect(target, FileOperationKind.WRITE_TAGS, CancellationSignal()) }
        assertArrayEquals(bytes, tree.bytes(id))
        val writableId = tree.putAudio("readonly.flac", AudioWriteFixtures.flac(), "audio/flac")
        val readonly = fixtureFileTarget(context, authority, tree, writableId)
        tree.writable = false
        expectIssue(FileOperationIssue.PERMISSION_DENIED) { adapter.inspect(readonly, FileOperationKind.WRITE_TAGS, CancellationSignal()) }
        assertEquals(0, tree.creates.get())
        assertEquals(0, tree.renames.get())
        assertEquals(0, tree.deletes.get())
    }

    @Test fun sameBytesAtNewInodeCannotUseEarlierOriginalConfirmation() {
        val bytes = AudioWriteFixtures.mp3()
        val id = tree.putAudio("source.mp3", bytes, "audio/mpeg")
        val target = provedTarget(id, FileOperationKind.DELETE_ORIGINAL)
        val replacement = Files.createTempFile(tree.root.toPath(), "replacement-", ".audio")
        Files.write(replacement, bytes)
        Files.move(replacement, tree.file(id).toPath(), StandardCopyOption.REPLACE_EXISTING)
        val inode = (Files.getAttribute(tree.file(id).toPath(), "unix:ino") as Number).toLong()
        assertNotEquals(checkNotNull(target.proof).inode, inode)
        expectIssue(FileOperationIssue.SOURCE_CHANGED) {
            adapter.apply(item(target), FileOperationCommand.deleteOriginal(), false, CancellationSignal()) {}
        }
        assertArrayEquals(bytes, tree.bytes(id))
        assertEquals(0, tree.deletes.get())
    }

    @Test fun revokedProviderReadAndCollisionPreserveFiles() {
        val bytes = AudioWriteFixtures.mp3()
        val id = tree.putAudio("source.mp3", bytes, "audio/mpeg")
        tree.putAudio("TAKEN.mp3", bytes, "audio/mpeg")
        val target = provedTarget(id, FileOperationKind.RENAME)
        expectIssue(FileOperationIssue.TARGET_EXISTS) {
            adapter.apply(item(target), FileOperationCommand.rename("taken.mp3"), false, CancellationSignal()) {}
        }
        tree.readable = false
        expectIssue(FileOperationIssue.PERMISSION_DENIED) {
            adapter.apply(item(target), FileOperationCommand.rename("free.mp3"), false, CancellationSignal()) {}
        }
        tree.readable = true
        assertArrayEquals(bytes, tree.bytes(id))
        assertEquals(0, tree.renames.get())
    }

    @Test fun moveThenSameIdRenameSurvivesJournalFailureWithoutAnotherPhysicalCall() {
        val bytes = AudioWriteFixtures.mp3()
        val id = tree.putAudio("source.mp3", bytes, "audio/mpeg")
        val directory = tree.create(FixtureDocumentTree.ROOT_ID, DocumentsContract.Document.MIME_TYPE_DIR, "target")
        tree.changeIdOnRename = false
        val target = provedTarget(id, FileOperationKind.MOVE)
        val source = checkNotNull(target.descriptor).source
        val command = FileOperationCommand.move(FileOperationDestination(source.spec.sourceId, source.spec.uri, directory, source.authEpoch, "renamed.mp3"))
        var recorded = item(target)
        expectIssue(FileOperationIssue.STORAGE_FULL) {
            adapter.apply(recorded, command, false, CancellationSignal()) { changed ->
                if (changed.phase == FileItemPhase.EXTERNAL_APPLIED) throw FileOperationException(FileOperationIssue.STORAGE_FULL)
                recorded = changed
            }
        }
        assertEquals(FileItemPhase.APPLYING, recorded.phase)
        assertEquals("source.mp3", checkNotNull(recorded.artifacts.published).proof.displayName)
        val recovered = adapter.recover(recorded, command, CancellationSignal()) { recorded = it }
        assertEquals(FileItemPhase.EXTERNAL_APPLIED, recovered.phase)
        assertEquals("renamed.mp3", checkNotNull(recovered.artifacts.published).proof.displayName)
        assertFalse(recovered.artifacts.actualNameDiffers)
        assertArrayEquals(bytes, tree.bytes(checkNotNull(recovered.artifacts.published).proof.objectId))
        adapter.recover(recovered, command, CancellationSignal()) {}
        assertEquals(1, tree.moves.get())
        assertEquals(1, tree.renames.get())
        assertEquals(0, tree.deletes.get())
    }

    @Test fun interruptedTagReplacementCompensatesOnlyProvenBackupAndStagesNeedSeparateDiscard() {
        val bytes = AudioWriteFixtures.flac()
        val id = tree.putAudio("source.flac", bytes, "audio/flac")
        val target = provedTarget(id, FileOperationKind.WRITE_TAGS)
        val command = FileOperationCommand.writeTags(AudioTagPatch(mapOf(AudioTagField.TITLE to listOf("After"))))
        var recorded = item(target)
        val cancelled = CancellationSignal()
        tree.afterRename = { cancelled.cancel() }
        try {
            adapter.apply(recorded, command, false, cancelled) { recorded = it }
            throw AssertionError("Expected interrupted provider result")
        } catch (_: OperationCanceledException) {
            assertEquals(FileItemPhase.APPLYING, recorded.phase)
        }
        tree.afterRename = null
        val observed = adapter.recover(recorded, command, CancellationSignal()) { recorded = it }
        assertEquals(FileItemPhase.NEEDS_RECOVERY, observed.phase)
        val restored = adapter.compensateInterruptedTag(observed, command, CancellationSignal()) { recorded = it }
        assertTrue(restored.artifacts.restoredOriginal)
        assertArrayEquals(bytes, tree.bytes(checkNotNull(restored.artifacts.published).proof.objectId))
        val retainedStage = adapter.finishArtifacts(restored.copy(phase = FileItemPhase.INDEX_RECONCILED), CancellationSignal())
        assertEquals(FileItemPhase.NEEDS_RECOVERY, retainedStage.phase)
        assertEquals(0, tree.deletes.get())
        val discarded = adapter.discardStaging(retainedStage, command, CancellationSignal()) {}
        assertEquals(FileItemPhase.CANCELLED, discarded.phase)
        assertArrayEquals(bytes, tree.bytes(checkNotNull(restored.artifacts.published).proof.objectId))
        assertEquals(1, tree.deletes.get())
    }

    @Test fun crossProviderCopyRetainsBothFilesAndDeleteFailureOnRecovery() {
        val bytes = AudioWriteFixtures.mp3()
        val id = tree.putAudio("source.mp3", bytes, "audio/mpeg")
        val otherAuthority = "${context.packageName}.lp10-secondary"
        val other = newTree(otherAuthority, "secondary")
        val otherUri = DocumentsContract.buildTreeDocumentUri(otherAuthority, FixtureDocumentTree.ROOT_ID)
        context.grantUriPermission(context.packageName, otherUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val target = provedTarget(id, FileOperationKind.MOVE)
        val command = FileOperationCommand.move(FileOperationDestination("other-source", otherUri.toString(), FixtureDocumentTree.ROOT_ID, 1L, "copy.mp3"))
        val copy = adapter.apply(item(target), command, false, CancellationSignal()) {}
        assertEquals(FileItemPhase.COPY_VALIDATED, copy.phase)
        val published = checkNotNull(copy.artifacts.published)
        assertArrayEquals(bytes, tree.bytes(id))
        assertArrayEquals(bytes, other.bytes(published.proof.objectId))
        var recorded = copy.copy(phase = FileItemPhase.COPY_INDEX_RECONCILED)
        tree.deletable = false
        expectIssue(FileOperationIssue.PERMISSION_DENIED) {
            adapter.apply(recorded, command, true, CancellationSignal()) { recorded = it }
        }
        assertTrue(recorded.artifacts.sourceDeletionStarted)
        recorded = recorded.copy(phase = FileItemPhase.NEEDS_RECOVERY, issue = FileOperationIssue.PERMISSION_DENIED)
        val recovered = adapter.recover(recorded, command, CancellationSignal()) {}
        assertFalse(recovered.artifacts.originalDeleted)
        assertEquals(FileOperationIssue.PERMISSION_DENIED, recovered.issue)
        assertArrayEquals(bytes, tree.bytes(id))
        assertArrayEquals(bytes, other.bytes(published.proof.objectId))
        assertEquals(0, tree.deletes.get())
        assertEquals(0, other.deletes.get())
    }
}
