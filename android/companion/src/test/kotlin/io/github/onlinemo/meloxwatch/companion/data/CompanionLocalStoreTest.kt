package io.github.onlinemo.meloxwatch.companion.data

import io.github.onlinemo.meloxwatch.data.transfer.TransferDisplayKind
import io.github.onlinemo.meloxwatch.data.transfer.TransferDisplayPatch
import io.github.onlinemo.meloxwatch.data.transfer.TransferDisplayPreview
import io.github.onlinemo.meloxwatch.data.transfer.TransferException
import io.github.onlinemo.meloxwatch.data.transfer.TransferFingerprint
import io.github.onlinemo.meloxwatch.data.transfer.TransferHash
import io.github.onlinemo.meloxwatch.data.transfer.TransferIssue
import io.github.onlinemo.meloxwatch.data.transfer.TransferLibraryRevision
import io.github.onlinemo.meloxwatch.data.transfer.TransferPlaylistEdit
import io.github.onlinemo.meloxwatch.data.transfer.TransferUploadDeclaration
import io.github.onlinemo.meloxwatch.data.transfer.TransferUploadKind
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CompanionLocalStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val target = TransferFingerprint.digest(byteArrayOf(1))
    private val fence = TransferLibraryRevision(7L, 3L)
    private fun id(number: Long): String = UUID(0L, number).toString()
    private fun root(name: String = "state"): File = temporary.newFolder(name)
    private fun upload(fileId: Long = 6L, phase: CompanionUploadPhase = CompanionUploadPhase.READY): CompanionUploadDraft =
        CompanionUploadDraft(id(5), target, id(fileId), TransferUploadDeclaration(TransferUploadKind.AUDIO, "fixture.wav", "audio/wav", 4L, TransferHash.digest(byteArrayOf(1, 2, 3, 4)), 0L), phase)
    private fun display(phase: CompanionEditPhase = CompanionEditPhase.PREVIEWED): CompanionDisplayDraft {
        val patch = TransferDisplayPatch.Remove(listOf("track:2", "track:1"))
        val draft = CompanionDisplayDraft(id(3), target, TransferDisplayKind.ARTIST, "artist:fixture", 7L, fence, "", 20001L, patch)
        return draft.withPreview(TransferDisplayPreview(id(3), draft.kind, draft.entityId, 7L, "", "", patch, fence, 20001L)).withPhase(phase)
    }

    @Test fun durableWriteOnlyCreateAndDisplayPreviewRestoreTheirExactPayloads() {
        val directory = root()
        val playlist = CompanionPlaylistDraft(id(2), target, "playlist:fixture", "Fixture", 0L, null, TransferPlaylistEdit.Create("Fixture"))
        CompanionLocalStore(directory).update { it.copy(target = CompanionTarget(target, "Fixture watch"), playlist = playlist, display = display()) }
        val restored = CompanionLocalStore(directory).read()
        assertEquals(target, restored.target?.fingerprint)
        assertEquals(id(2), restored.playlist?.operationId)
        assertNull(restored.playlist?.libraryRevision)
        assertEquals(0L, restored.playlist?.expectedRevision)
        assertEquals("Fixture", (checkNotNull(restored.playlist).edit as TransferPlaylistEdit.Create).name)
        val patch = checkNotNull(restored.display)
        assertEquals(id(3), patch.previewId)
        assertEquals(CompanionEditPhase.PREVIEWED, patch.phase)
        assertEquals("", patch.currentName)
        assertEquals(20001L, patch.currentTrackCount)
        assertEquals(listOf("track:2", "track:1"), (patch.patch as TransferDisplayPatch.Remove).trackIds)
        assertTrue(patch.libraryRevision.sameAs(fence))
        val bytes = File(directory, "state.v2").readBytes()
        assertEquals(2, ByteBuffer.wrap(bytes, 4, 4).int)
        assertFalse(File(directory, "state.v1").exists())
    }

    @Test fun interruptedCommitRestoresUncertainAndRetainsTheOriginalOperation() {
        val directory = root()
        val playlist = CompanionPlaylistDraft(id(2), target, "playlist:fixture", "Fixture", 9L, fence, TransferPlaylistEdit.Add(listOf("track:2", "track:1")), CompanionEditPhase.SUBMITTING)
        CompanionLocalStore(directory).update { it.copy(upload = upload(phase = CompanionUploadPhase.SENDING), playlist = playlist, display = display(CompanionEditPhase.SUBMITTING)) }
        val restored = CompanionLocalStore(directory).read()
        assertEquals(CompanionUploadPhase.UNCERTAIN, restored.upload?.phase)
        assertEquals(id(5), restored.upload?.operationId)
        assertEquals(id(6), restored.upload?.fileId)
        assertEquals(CompanionEditPhase.UNCERTAIN, restored.playlist?.phase)
        assertEquals(9L, restored.playlist?.expectedRevision)
        assertEquals(listOf("track:2", "track:1"), (checkNotNull(restored.playlist).edit as TransferPlaylistEdit.Add).trackIds)
        assertEquals(CompanionEditPhase.UNCERTAIN, restored.display?.phase)
        assertEquals(id(3), restored.display?.previewId)
    }

    @Test fun checksumVersionAndTrailingBytesFailuresRetainRecordAndOrphansOnRetry() {
        for (kind in listOf("checksum", "version", "trailing")) {
            val directory = root(kind)
            val writer = CompanionLocalStore(directory)
            writer.update { it }
            val record = File(directory, "state.v2")
            val original = record.readBytes()
            val damaged = when (kind) {
                "checksum" -> original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
                "version" -> original.copyOf().also { it[7] = 3 }
                else -> original + byteArrayOf(0)
            }
            record.writeBytes(damaged)
            val orphan = writer.audioFile(id(12)).also { it.writeBytes(byteArrayOf(8)) }
            val reader = CompanionLocalStore(directory)
            issue(TransferIssue.RECOVERY_REQUIRED) { reader.read() }
            issue(TransferIssue.RECOVERY_REQUIRED) { reader.retryRead() }
            issue(TransferIssue.RECOVERY_REQUIRED) { reader.update { it.copy(target = null) } }
            issue(TransferIssue.RECOVERY_REQUIRED) { reader.retireUnreferencedCopies() }
            assertArrayEquals(damaged, record.readBytes())
            assertTrue(orphan.exists())
        }
    }

    @Test fun historicalUnshippedSchema1RecordIsPreservedWithoutReinterpretation() {
        val directory = root()
        val legacy = File(directory, "state.v1")
        val marker = byteArrayOf(1, 4, 1)
        legacy.writeBytes(marker)
        val store = CompanionLocalStore(directory)
        issue(TransferIssue.RECOVERY_REQUIRED) { store.read() }
        issue(TransferIssue.RECOVERY_REQUIRED) { store.retryRead() }
        assertArrayEquals(marker, legacy.readBytes())
        assertFalse(File(directory, "state.v2").exists())
    }

    @Test fun checksumValidUnknownOptionalTagAlsoFailsWithoutReplacingRecord() {
        val directory = root()
        CompanionLocalStore(directory).update { it }
        val record = File(directory, "state.v2")
        val damaged = record.readBytes()
        val payloadLength = ByteBuffer.wrap(damaged, 8, 4).int
        damaged[12] = 2
        val digest = MessageDigest.getInstance("SHA-256").digest(damaged.copyOfRange(12, 12 + payloadLength))
        digest.copyInto(damaged, 12 + payloadLength)
        record.writeBytes(damaged)
        issue(TransferIssue.RECOVERY_REQUIRED) { CompanionLocalStore(directory).read() }
        assertArrayEquals(damaged, record.readBytes())
    }

    @Test fun recordQuotaFailureLeavesCurrentStateAndOldBytesUnchanged() {
        val directory = root()
        val store = CompanionLocalStore(directory)
        val old = store.update { it.copy(target = CompanionTarget(target, "Fixture watch")) }
        val bytes = File(directory, "state.v2").readBytes()
        val notice = "许".repeat(16 * 1024)
        val icons = CompanionIconRole.entries.mapIndexed { index, role ->
            CompanionThemeIcon(role, id(20L + index), TransferHash.digest(byteArrayOf(1)), 1L, "Fixture license", notice)
        }
        val large = old.theme.edited(icons = icons)
        issue(TransferIssue.QUOTA_EXCEEDED) { store.update { it.copy(theme = large) } }
        assertSame(old, store.read())
        assertArrayEquals(bytes, File(directory, "state.v2").readBytes())
        assertTrue(directory.listFiles()?.none { it.name.endsWith(".tmp") } == true)
    }

    @Test fun uncertainUploadUpdateKeepsItsOperationPayloadAndBoundTarget() {
        val directory = root()
        val store = CompanionLocalStore(directory)
        val draft = upload(phase = CompanionUploadPhase.UNCERTAIN)
        val old = store.recordUpload(draft)
        val record = File(directory, "state.v2")
        val bytes = record.readBytes()
        val declaration = draft.declaration
        for (changed in listOf(
            CompanionUploadDraft(draft.operationId, target, id(7), declaration, draft.phase),
            CompanionUploadDraft(draft.operationId, target, draft.fileId,
                TransferUploadDeclaration(declaration.kind, "changed.wav", declaration.mediaType, declaration.declaredBytes, declaration.sha256, declaration.expectedRevision), draft.phase),
            CompanionUploadDraft(draft.operationId, target, draft.fileId,
                TransferUploadDeclaration(declaration.kind, declaration.displayName, declaration.mediaType, declaration.declaredBytes, TransferHash.digest(byteArrayOf(8)), declaration.expectedRevision), draft.phase),
        )) issue(TransferIssue.CONFLICT) { store.recordUpload(changed) }
        issue(TransferIssue.WRONG_TARGET) { store.recordUpload(CompanionUploadDraft(draft.operationId, null, draft.fileId, declaration, draft.phase)) }
        issue(TransferIssue.WRONG_TARGET) { store.recordUpload(CompanionUploadDraft(draft.operationId, TransferFingerprint.digest(byteArrayOf(8)), draft.fileId, declaration, draft.phase)) }
        issue(TransferIssue.BUSY) { store.recordUpload(CompanionUploadDraft(id(9), target, draft.fileId, declaration, draft.phase)) }
        assertSame(old, store.read())
        assertArrayEquals(bytes, record.readBytes())
        assertEquals(CompanionUploadPhase.SENDING, store.recordUpload(draft.withResult(CompanionUploadPhase.SENDING)).upload?.phase)
    }

    @Test fun orphanCleanupValidatesEveryReferenceBeforeDeletingAnyPrivateCopy() {
        for (missing in listOf(true, false)) {
            val directory = root(if (missing) "missing" else "wrong-size")
            val store = CompanionLocalStore(directory)
            store.recordUpload(upload())
            if (!missing) store.audioFile(id(6)).writeBytes(byteArrayOf(1))
            val orphan = store.audioFile(id(12)).also { it.writeBytes(byteArrayOf(8)) }
            issue(TransferIssue.RECOVERY_REQUIRED) { store.retireUnreferencedCopies() }
            assertTrue(orphan.exists())
            assertEquals(id(5), store.read().upload?.operationId)
        }
    }

    @Test fun successfulOrphanCleanupPreservesBothPreparedFilesAndTheirState() {
        val directory = root()
        val store = CompanionLocalStore(directory)
        val draft = upload()
        val iconBytes = byteArrayOf(5, 6)
        val icon = CompanionThemeIcon(CompanionIconRole.PLAY, id(8), TransferHash.digest(iconBytes), 2L, "Fixture license", "Fixture notice")
        store.update { it.copy(upload = draft, theme = it.theme.edited(icons = listOf(icon))) }
        val retainedAudio = store.audioFile(id(6)).also { it.writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val retainedIcon = store.iconFile(id(8)).also { it.writeBytes(iconBytes) }
        val orphan = store.packageFile(id(12), partial = true).also { it.writeBytes(byteArrayOf(8)) }
        store.retireUnreferencedCopies()
        assertTrue(retainedAudio.exists())
        assertTrue(retainedIcon.exists())
        assertFalse(orphan.exists())
        assertEquals(id(5), store.read().upload?.operationId)
        assertEquals(id(8), store.read().theme.icons.single().fileId)
    }

    @Test fun damagedIconReferencePreventsUnrelatedAudioCleanup() {
        val directory = root()
        val store = CompanionLocalStore(directory)
        val icon = CompanionThemeIcon(CompanionIconRole.PLAY, id(8), TransferHash.digest(byteArrayOf(5, 6)), 2L, "Fixture license", "Fixture notice")
        store.update { it.copy(theme = it.theme.edited(icons = listOf(icon))) }
        store.iconFile(id(8)).writeBytes(byteArrayOf(5))
        val orphan = store.audioFile(id(12)).also { it.writeBytes(byteArrayOf(8)) }
        issue(TransferIssue.RECOVERY_REQUIRED) { store.retireUnreferencedCopies() }
        assertTrue(orphan.exists())
    }

    private fun issue(expected: TransferIssue, action: () -> Unit) {
        try { action(); fail("Expected a local storage issue") } catch (error: TransferException) { assertEquals(expected, error.issue) }
    }
}
