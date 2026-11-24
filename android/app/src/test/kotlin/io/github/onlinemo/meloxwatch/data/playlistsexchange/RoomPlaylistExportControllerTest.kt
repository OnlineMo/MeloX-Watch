package io.github.onlinemo.meloxwatch.data.playlistsexchange

import io.github.onlinemo.meloxwatch.data.database.withPreservedTransaction
import io.github.onlinemo.meloxwatch.data.database.withTestWriterSql
import io.github.onlinemo.meloxwatch.data.database.withTestWriterTransaction
import io.github.onlinemo.meloxwatch.data.playlists.LocalPlaylistRepository
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistIssue
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistPage
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistViewToken
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistImportConfirmation
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistImportDestination
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistTransferEncoding
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistTransferFormat
import java.io.FileInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RoomPlaylistExportControllerTest : RoomPlaylistExchangeFixture() {
    @Test fun jsonExportReopensActualBytesAndRoundTripsTwoMembersInTheirOriginalOrder() = runBlocking {
        val original = createOrdinary()
        val view = playlists.openView(original.playlistId)
        val document = output("json-export")
        val overview = library.overview()
        val receipt = exchange.export("json-export", view, document, PlaylistTransferFormat.JSON, PlaylistTransferEncoding.UTF8)
        val bytes = documentFile.readBytes()
        assertEquals(2L, receipt.memberCount)
        assertEquals(bytes.size.toLong(), receipt.byteCount)
        assertEquals(exchangeTestSha256(bytes), receipt.sha256)
        assertEquals(listOf("rw", "rw", "rw", "r"), provider.openModes.toList())
        assertEquals(original, playlists.snapshot(original.playlistId))
        assertEquals(overview, library.overview())
        val json = bytes.toString(Charsets.UTF_8)
        assertFalse(json.contains("content://"))
        assertFalse(json.contains("providerContentToken"))
        assertFalse(json.contains("uriGrant"))
        assertEquals(receipt, exchange.export("json-export", view, document, PlaylistTransferFormat.JSON, PlaylistTransferEncoding.UTF8))
        assertEquals(4, provider.openModes.size)
        registry.discard(document.selectionId)
        val importedDocument = registry.registerImport(documentUri, READ)
        val preview = exchange.inspectImport(PlaylistTransferFormat.JSON, PlaylistTransferEncoding.UTF8, importedDocument)
        assertEquals(original.name, preview.nameHint)
        assertEquals(2L, preview.matchedCount)
        assertNull(playlists.snapshot("json-round-trip"))
        val imported = exchange.confirmImport(PlaylistImportConfirmation("json-round-trip-operation", preview.previewId,
            PlaylistImportDestination.New("json-round-trip", checkNotNull(preview.nameHint)), false))
        assertEquals(listOf(sameNamedDecoy, replacement), members(checkNotNull(imported.snapshot)).map { it.reference.trackKey })
        assertEquals(original, playlists.snapshot(original.playlistId))
        registry.discard(importedDocument.selectionId)
        playlists.closeView(view.viewId)
        assertProviderClosed()
        assertNoPrivateStages()
        integrity()
    }

    @Test fun bothUtf8M3uKindsKeepKnownIdentityAndOrderWithoutCopyingAudio() = runBlocking {
        val original = createOrdinary()
        val view = playlists.openView(original.playlistId)
        for (format in listOf(PlaylistTransferFormat.M3U8, PlaylistTransferFormat.M3U)) {
            documentFile.writeText("")
            val operation = "export-${format.name}"
            val destination = output(operation)
            val receipt = exchange.export(operation, view, destination, format, PlaylistTransferEncoding.UTF8)
            val all = mutableListOf<ExchangeMember>()
            val parsed = FileInputStream(documentFile).use { input -> M3uPlaylistExchange().parse(input,
                ExchangePageSink { all += it }, if (format == PlaylistTransferFormat.M3U8) M3uFileKind.M3U8 else M3uFileKind.M3U) }
            assertEquals(2L, parsed.memberCount)
            assertEquals(receipt.sha256, parsed.sha256)
            assertEquals(listOf(sameNamedDecoy, replacement), all.map { it.trackKey })
            assertEquals(listOf(0L, 1L), all.map { it.ordinal })
            assertEquals(original.name, parsed.header.name)
            assertEquals(0, provider.childQueryCount.get())
            registry.discard(destination.selectionId)
        }
        playlists.closeView(view.viewId)
        assertProviderClosed()
        assertNoPrivateStages()
        integrity()
    }

    @Test fun anEmptyPlaylistHasARealVerifiedJsonReceipt() = runBlocking {
        val empty = createOrdinary("empty-list", emptyList())
        val view = playlists.openView(empty.playlistId)
        val document = output("empty-export")
        val receipt = exchange.export("empty-export", view, document, PlaylistTransferFormat.JSON, PlaylistTransferEncoding.UTF8)
        var emitted = false
        val parsed = FileInputStream(documentFile).use { input ->
            JsonPlaylistExchange().parse(input, ExchangePageSink { emitted = true })
        }
        assertEquals(0L, receipt.memberCount)
        assertEquals(0L, parsed.memberCount)
        assertFalse(emitted)
        assertEquals(receipt.byteCount, parsed.byteCount)
        assertEquals(receipt.sha256, parsed.sha256)
        assertTrue(receipt.byteCount > 0L)
        registry.discard(document.selectionId)
        playlists.closeView(view.viewId)
        assertProviderClosed()
        assertNoPrivateStages()
    }

    @Test fun anExistingTargetIsRejectedBeforeAnyWriteAndTheOriginalPlaylistRemainsComplete() = runBlocking {
        val original = createOrdinary()
        val view = playlists.openView(original.playlistId)
        documentFile.writeText("Another application's existing document")
        val before = documentFile.readBytes()
        val document = output("existing-target-export")
        expectIssue(PlaylistIssue.CONFLICT) {
            exchange.export("existing-target-export", view, document, PlaylistTransferFormat.JSON, PlaylistTransferEncoding.UTF8)
        }
        assertTrue(documentFile.readBytes().contentEquals(before))
        assertNull(database.playlistExchangeDao().exportReceipt("existing-target-export"))
        assertEquals(original, playlists.snapshot(original.playlistId))
        registry.discard(document.selectionId)
        playlists.closeView(view.viewId)
        assertProviderClosed()
        assertNoPrivateStages()
        integrity()
    }

    @Test fun remappingTheUriDuringIndependentVerificationNeverClearsTheForeignObject() = runBlocking {
        val original = createOrdinary()
        val view = playlists.openView(original.playlistId)
        val document = output("remapped-export")
        val foreign = temporaryFolder.newFile("foreign-output.txt").apply { writeText("Foreign contents must survive") }
        val foreignBytes = foreign.readBytes()
        provider.beforeOpen = { if (provider.openModes.size == 4) provider.document = foreign }
        expectIssue(PlaylistIssue.SOURCE_CHANGED) {
            exchange.export("remapped-export", view, document, PlaylistTransferFormat.JSON, PlaylistTransferEncoding.UTF8)
        }
        provider.beforeOpen = null
        assertTrue(foreign.readBytes().contentEquals(foreignBytes))
        assertTrue(documentFile.length() > 0L)
        assertNull(database.playlistExchangeDao().exportReceipt("remapped-export"))
        registry.discard(document.selectionId)
        playlists.closeView(view.viewId)
        assertProviderClosed()
        assertNoPrivateStages()
        integrity()
    }

    @Test fun sameLengthExternalEditsFailTheHashCheckAndAreProtectedFromFailureCleanup() = runBlocking {
        val original = createOrdinary()
        val view = playlists.openView(original.playlistId)
        val document = output("externally-edited-export")
        var foreign = byteArrayOf()
        provider.beforeOpen = {
            if (provider.openModes.size == 4) {
                foreign = documentFile.readBytes().also { it[0] = '!'.code.toByte() }
                documentFile.writeBytes(foreign)
            }
        }
        expectIssue(PlaylistIssue.INCOMPLETE) {
            exchange.export("externally-edited-export", view, document, PlaylistTransferFormat.JSON, PlaylistTransferEncoding.UTF8)
        }
        provider.beforeOpen = null
        assertTrue(foreign.isNotEmpty())
        assertTrue(documentFile.readBytes().contentEquals(foreign))
        assertNull(database.playlistExchangeDao().exportReceipt("externally-edited-export"))
        registry.discard(document.selectionId)
        playlists.closeView(view.viewId)
        assertProviderClosed()
        assertNoPrivateStages()
        integrity()
    }

    @Test(timeout = 30_000L)
    fun anActualSqlitePageCeilingPreventsAReceiptAndClearsOnlyThisUncommittedOutput() = runBlocking {
        val original = createOrdinary()
        val view = playlists.openView(original.playlistId)
        database.withTestWriterTransaction {
            execute("CREATE TABLE exchange_full_probe(bytes BLOB)")
            execute("CREATE TRIGGER exchange_receipt_full BEFORE INSERT ON playlist_export_receipts " +
                "BEGIN INSERT INTO exchange_full_probe(bytes) VALUES(zeroblob(1048576)); END")
        }
        var previousMaximum: Long? = null
        val port = object : LocalPlaylistRepository by playlists {
            override suspend fun page(view: PlaylistViewToken, firstOrdinal: Long, limit: Int): PlaylistPage {
                val page = playlists.page(view, firstOrdinal, limit)
                if (provider.openModes.size >= 4 && previousMaximum == null) {
                    database.withPreservedTransaction {
                        previousMaximum = writerPragma("max_page_count")
                        val ceiling = writerPragma("page_count") + 8L
                        assertEquals(ceiling, writerPragma("max_page_count = $ceiling"))
                    }
                }
                return page
            }
        }
        exchange = exchangeController(port)
        val document = output("full-storage-export")
        try {
            expectIssue(PlaylistIssue.STORAGE_FULL) {
                exchange.export("full-storage-export", view, document, PlaylistTransferFormat.JSON, PlaylistTransferEncoding.UTF8)
            }
            assertTrue(previousMaximum != null)
            assertNull(database.playlistExchangeDao().exportReceipt("full-storage-export"))
            assertEquals(original, playlists.snapshot(original.playlistId))
        } finally {
            database.withTestWriterTransaction {
                previousMaximum?.let { writerPragma("max_page_count = $it") }
                execute("DROP TRIGGER exchange_receipt_full")
                execute("DROP TABLE exchange_full_probe")
            }
        }
        exchange.closePreview("retry-after-page-ceiling")
        assertEquals(0L, documentFile.length())
        assertEquals(listOf(sameNamedDecoy, replacement), members(original).map { it.reference.trackKey })
        registry.discard(document.selectionId)
        playlists.closeView(view.viewId)
        assertProviderClosed()
        assertNoPrivateStages()
        integrity()
    }

    private suspend fun writerPragma(expression: String): Long = database.withTestWriterSql {
        query("PRAGMA $expression") {
            assertTrue(it.step())
            it.getLong(0)
        }
    }
}
