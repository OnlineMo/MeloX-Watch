package io.github.onlinemo.meloxwatch.companion.data

import io.github.onlinemo.meloxwatch.data.transfer.TransferCatalogKind
import io.github.onlinemo.meloxwatch.data.transfer.TransferCatalogPage
import io.github.onlinemo.meloxwatch.data.transfer.TransferCatalogRow
import io.github.onlinemo.meloxwatch.data.transfer.TransferDisplayKind
import io.github.onlinemo.meloxwatch.data.transfer.TransferDisplayPatch
import io.github.onlinemo.meloxwatch.data.transfer.TransferDisplayPreview
import io.github.onlinemo.meloxwatch.data.transfer.TransferException
import io.github.onlinemo.meloxwatch.data.transfer.TransferFingerprint
import io.github.onlinemo.meloxwatch.data.transfer.TransferHash
import io.github.onlinemo.meloxwatch.data.transfer.TransferIssue
import io.github.onlinemo.meloxwatch.data.transfer.TransferLibraryRevision
import io.github.onlinemo.meloxwatch.data.transfer.TransferOperationBinding
import io.github.onlinemo.meloxwatch.data.transfer.TransferOperationKind
import io.github.onlinemo.meloxwatch.data.transfer.TransferPlaylistEdit
import io.github.onlinemo.meloxwatch.data.transfer.TransferReceipt
import io.github.onlinemo.meloxwatch.data.transfer.TransferReceiptKind
import io.github.onlinemo.meloxwatch.data.transfer.TransferUploadDeclaration
import io.github.onlinemo.meloxwatch.data.transfer.TransferUploadKind
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CompanionModelsTest {
    private val target = TransferFingerprint.digest(byteArrayOf(1))
    private val fence = TransferLibraryRevision(7L, 3L)
    private fun id(number: Long): String = UUID(0L, number).toString()
    private fun display(patch: TransferDisplayPatch = TransferDisplayPatch.Add(listOf("track:1"))): CompanionDisplayDraft =
        CompanionDisplayDraft(id(2), target, TransferDisplayKind.ALBUM, "album:fixture", 7L, fence, "", 20001L, patch)
    private fun preview(patch: TransferDisplayPatch = TransferDisplayPatch.Add(listOf("track:1")), count: Long = 20001L,
        selectedFence: TransferLibraryRevision = fence, previewId: Long = 2L, current: String = ""): TransferDisplayPreview =
        TransferDisplayPreview(id(previewId), TransferDisplayKind.ALBUM, "album:fixture", selectedFence.catalogRevision, current,
            if (patch is TransferDisplayPatch.Rename) patch.displayName else current, patch, selectedFence, count)

    @Test fun displayDraftAcceptsUnnamedGroupAndPreservesExactPreviewForRetry() {
        val draft = display().withPreview(preview())
        assertEquals("", draft.currentName)
        assertEquals(id(2), draft.previewId)
        assertEquals(CompanionEditPhase.PREVIEWED, draft.phase)
        val unknown = draft.withPhase(CompanionEditPhase.SUBMITTING).withPhase(CompanionEditPhase.UNCERTAIN)
        assertEquals(draft.operationId, unknown.operationId)
        assertEquals(draft.previewId, unknown.previewId)
        assertTrue(unknown.patch.sameAs(draft.patch))
        assertTrue(unknown.libraryRevision.sameAs(draft.libraryRevision))
    }

    @Test fun displayDraftRejectsChangedCountNamePatchFenceOrPreviewId() {
        val draft = display()
        for (changed in listOf(
            preview(count = 20000L), preview(current = "Changed"), preview(previewId = 3L),
            preview(patch = TransferDisplayPatch.Remove(listOf("track:1"))),
            preview(selectedFence = TransferLibraryRevision(7L, 4L)),
        )) issue(TransferIssue.CONFLICT) { draft.withPreview(changed) }
        invalid { display().withPhase(CompanionEditPhase.SUBMITTING) }
    }

    @Test fun editDraftReceiptsMustMatchTheirOperationTargetKindAndEntity() {
        fun receipt(kind: TransferOperationKind, entity: String = "album:fixture", operation: Long = 2L): TransferReceipt {
            val binding = TransferOperationBinding(id(operation), kind, target, TransferFingerprint.digest(byteArrayOf(2)), TransferHash.digest(byteArrayOf(3)))
            val result = if (kind == TransferOperationKind.DISPLAY) TransferReceiptKind.DISPLAY_COMMITTED else TransferReceiptKind.PLAYLIST_COMMITTED
            return TransferReceipt(binding, result, 8L, entity, null)
        }
        val draft = display().withPreview(preview())
        assertEquals(CompanionEditPhase.COMPLETED, draft.withPhase(CompanionEditPhase.COMPLETED, receipt(TransferOperationKind.DISPLAY)).phase)
        invalid { draft.withPhase(CompanionEditPhase.COMPLETED, receipt(TransferOperationKind.PLAYLIST)) }
        invalid { draft.withPhase(CompanionEditPhase.COMPLETED, receipt(TransferOperationKind.DISPLAY, "album:other")) }
        invalid { draft.withPhase(CompanionEditPhase.COMPLETED, receipt(TransferOperationKind.DISPLAY, operation = 3L)) }
        val playlist = CompanionPlaylistDraft(id(2), target, "playlist:fixture", "Fixture", 0L, null, TransferPlaylistEdit.Create("Fixture"))
        assertEquals(CompanionEditPhase.COMPLETED, playlist.withPhase(CompanionEditPhase.COMPLETED, receipt(TransferOperationKind.PLAYLIST, "playlist:fixture")).phase)
        invalid { playlist.withPhase(CompanionEditPhase.COMPLETED, receipt(TransferOperationKind.DISPLAY, "playlist:fixture")) }
    }

    @Test fun writeOnlyPlaylistDraftNeedsLibraryFenceOnlyForAdd() {
        val create = CompanionPlaylistDraft(id(2), target, "playlist:fixture", "Fixture", 0L, null, TransferPlaylistEdit.Create("Fixture"))
        assertNull(create.libraryRevision)
        CompanionPlaylistDraft(id(2), target, "playlist:fixture", "Fixture", 9L, null, TransferPlaylistEdit.Move("member:1", 20000L))
        invalid { CompanionPlaylistDraft(id(2), target, "playlist:fixture", "Fixture", 1L, null, TransferPlaylistEdit.Create("Fixture")) }
        invalid { CompanionPlaylistDraft(id(2), target, "playlist:fixture", "Fixture", 9L, null, TransferPlaylistEdit.Add(listOf("track:1"))) }
    }

    @Test fun displayBatchesAreFrozenDistinctAndOrderedWithoutAGroupCap() {
        val source = mutableListOf("track:2", "track:1")
        val patch = TransferDisplayPatch.Add(source)
        source.reverse()
        assertEquals(listOf("track:2", "track:1"), patch.trackIds)
        assertFalse(patch.sameAs(TransferDisplayPatch.Add(source)))
        invalid { TransferDisplayPatch.Add(listOf("track:1", "track:1")) }
        invalid { TransferDisplayPatch.Remove(emptyList()) }
        invalid { TransferDisplayPatch.Add(List(101) { "track:$it" }) }
        assertEquals(20001L, display(patch).currentTrackCount)
    }

    @Test fun preparedUploadCannotRebindItsTargetOrAcceptAnotherReceiptKind() {
        val declaration = TransferUploadDeclaration(TransferUploadKind.AUDIO, "fixture.wav", "audio/wav", 4L, TransferHash.digest(byteArrayOf(1, 2, 3, 4)), 0L)
        val unbound = CompanionUploadDraft(id(2), null, id(3), declaration, CompanionUploadPhase.READY)
        val draft = unbound.bind(target).withResult(CompanionUploadPhase.UNCERTAIN)
        assertEquals(target, draft.bind(target).target)
        issue(TransferIssue.WRONG_TARGET) { draft.bind(TransferFingerprint.digest(byteArrayOf(8))) }
        val binding = TransferOperationBinding(id(2), TransferOperationKind.THEME, target, TransferFingerprint.digest(byteArrayOf(2)), TransferHash.digest(byteArrayOf(3)))
        val wrongKind = TransferReceipt(binding, TransferReceiptKind.THEME_PENDING_CONFIRMATION, 0L, "theme:fixture", 4L)
        invalid { draft.withResult(CompanionUploadPhase.COMPLETED, wrongKind) }
        assertTrue(unbound.samePreparedPayload(draft))
    }

    @Test fun threePageWindowCanTraverseAll20001RowsAndItsRealLastPage() {
        var window = CompanionPageWindow(TransferCatalogKind.TRACKS, "", null, emptyList(), -1)
        var seen = 0L
        var count = 0
        while (seen < 20001L) {
            val rows = List(minOf(100L, 20001L - seen).toInt()) { index ->
                TransferCatalogRow.Track("track:${seen + index}", "Fixture", "", "", null, true)
            }
            val nextOrdinal = seen + rows.size
            val page = TransferCatalogPage(TransferCatalogKind.TRACKS, id(4), 5L, fence, 20001L, seen, rows,
                if (nextOrdinal == 20001L) null else "cursor_$nextOrdinal")
            window = window.append(CompanionCachedPage(target, id(1), page, count.toLong()))
            assertTrue(window.pages.size <= CompanionLimits.PAGE_WINDOW)
            assertEquals(seen, checkNotNull(window.current).page.firstOrdinal)
            seen = nextOrdinal
            count++
        }
        assertEquals(20001L, seen)
        assertEquals(201, count)
        assertEquals(3, window.pages.size)
        assertEquals(1, checkNotNull(window.current).page.rows.size)
        assertNull(checkNotNull(window.current).page.nextCursor)
        assertEquals(20000L, checkNotNull(window.previous().cachedNext()?.current).page.firstOrdinal)
    }

    @Test fun cachedPageWindowRejectsCrossTargetSessionLeaseFenceAndDuplicateRows() {
        fun cached(ordinal: Long, selectedTarget: TransferFingerprint = target, session: Long = 1L,
            view: Long = 4L, selectedFence: TransferLibraryRevision = fence, trackId: String = "track:$ordinal"): CompanionCachedPage =
            CompanionCachedPage(selectedTarget, id(session), TransferCatalogPage(TransferCatalogKind.TRACKS, id(view), 5L, selectedFence, 3L, ordinal,
                listOf(TransferCatalogRow.Track(trackId, "Fixture", "", "", null, true)), "cursor_${ordinal + 1L}"), 1L)
        val window = CompanionPageWindow(TransferCatalogKind.TRACKS, "", null, listOf(cached(0L)), 0)
        for (next in listOf(
            cached(1L, selectedTarget = TransferFingerprint.digest(byteArrayOf(9))), cached(1L, session = 2L),
            cached(1L, view = 5L), cached(1L, selectedFence = TransferLibraryRevision(7L, 4L)),
            cached(2L), cached(1L, trackId = "track:0"),
        )) invalid { window.append(next) }
    }

    @Test fun endpointParserUsesOnlyNumericalLocalLiteralsAndRedactsDiagnostics() {
        assertEquals(43210, CompanionEndpoint.parse("127.0.0.1", "43210").port)
        assertEquals("<local-endpoint>", CompanionEndpoint.parse("[::1]", "43210").toString())
        for (literal in listOf("fixture.invalid", "0.0.0.0", "::", "224.0.0.1", "127.00.0.1", "127.0.0.1/8", "::1%interface")) {
            issue(TransferIssue.INVALID_INPUT) { CompanionEndpoint.parse(literal, "43210") }
        }
        issue(TransferIssue.INVALID_INPUT) { CompanionEndpoint.parse("127.0.0.1", "443") }
        assertEquals("<paired-target>", CompanionTarget(target, "Fixture watch").toString())
        assertEquals("<display-edit-draft>", display().toString())
    }

    @Test fun boundedInputRejectsAnOversizeOrNonProgressingStream() {
        assertEquals(3, readCompanionBounded(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3).size)
        issue(TransferIssue.QUOTA_EXCEEDED) { readCompanionBounded(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), 3) }
        val noProgress = object : InputStream() {
            override fun read(): Int = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
        }
        issue(TransferIssue.QUOTA_EXCEEDED) { readCompanionBounded(noProgress, 3) }
        var checks = 0
        readCompanionBounded(ByteArrayInputStream(byteArrayOf(1)), 3) { checks++ }
        assertTrue(checks >= 2)
    }

    @Test fun paletteValidationKeepsReadableTextAndControlsAcrossAllSurfaces() {
        CompanionThemeDraft().validatePalette()
        val colors = CompanionColor.entries.associateWith { it.defaultHex }.toMutableMap()
        colors[CompanionColor.TEXT_PRIMARY] = "#111114"
        issue(TransferIssue.INVALID_INPUT) { CompanionThemeDraft(colors = colors).validatePalette() }
        assertTrue(companionContrast("#FFFFFF", "#000000") >= 20.9)
    }

    private fun issue(expected: TransferIssue, action: () -> Unit) {
        try { action(); fail("Expected a model issue") } catch (error: TransferException) { assertEquals(expected, error.issue) }
    }
    private fun invalid(action: () -> Unit) {
        try { action(); fail("Expected invalid model input") } catch (_: IllegalArgumentException) { }
    }
}
