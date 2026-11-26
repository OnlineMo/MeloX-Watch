package io.github.onlinemo.meloxwatch.data.playlistsexchange

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.RoomRawQuery
import io.github.onlinemo.meloxwatch.data.cue.CueByteFixture
import io.github.onlinemo.meloxwatch.data.cue.CueReferenceCodec
import io.github.onlinemo.meloxwatch.data.cue.RoomCueLibraryFixture
import io.github.onlinemo.meloxwatch.data.library.LocalAudioMetadata
import io.github.onlinemo.meloxwatch.data.library.RoomPlaylistFences
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.playlists.FrozenPlaylistInput
import io.github.onlinemo.meloxwatch.data.playlists.LocalPlaylistRepository
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistCommit
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistException
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistFolderBinding
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistInputPage
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistIssue
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistLimits
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMember
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutation
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutationRequest
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistPageSource
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistQueueAvailability
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistReference
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistSnapshot
import io.github.onlinemo.meloxwatch.data.playlists.RoomPlaylistRepository
import io.github.onlinemo.meloxwatch.model.LocalCueReference
import io.github.onlinemo.meloxwatch.ui.playlists.AuthorizedPlaylistDocument
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistImportConfirmation
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistImportDestination
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistImportMemberIssue
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistImportPreview
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistTransferEncoding
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistTransferFormat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** 使用真实 Room 与文档字节；合成来源授权不满足提供方或设备验收。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE,
    shadows = [PlaylistDocumentFixtureOsShadow::class, PlaylistDocumentFixtureParcelShadow::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CueRoomExchangeTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var fixture: RoomCueLibraryFixture
    private lateinit var playlists: RoomPlaylistRepository
    private lateinit var io: LocalProviderIo
    private lateinit var registry: PlaylistDocumentRegistry
    private lateinit var provider: FixturePlaylistDocumentProvider
    private lateinit var documentFile: File
    private lateinit var documentUri: Uri
    private lateinit var exchange: RoomPlaylistExchangeController
    private val selections = linkedSetOf<String>()
    private val previews = linkedSetOf<String>()
    private val registrations = AtomicInteger()
    private var failAfterRegistration: Int? = null

    @Before fun open(): Unit = runBlocking {
        context = RuntimeEnvironment.getApplication()
        fixture = RoomCueLibraryFixture(context, File(temporary.root, "cue-exchange.db"))
        fixture.open()
        installWriter()
        io = LocalProviderIo()
        registry = PlaylistDocumentRegistry(context, io)
        documentFile = temporary.newFile("cue-exchange.txt")
        val authority = "${context.packageName}.cue-exchange-fixture"
        provider = fixturePlaylistDocumentProvider(context, authority, documentFile)
        documentUri = DocumentsContract.buildDocumentUri(authority, FixturePlaylistDocumentProvider.DOCUMENT_ID)
        exchange = controller()
    }

    @After fun close(): Unit = runBlocking {
        try {
            if (this@CueRoomExchangeTest::exchange.isInitialized) {
                for (preview in previews.toList()) exchange.closePreview(preview)
            }
            if (this@CueRoomExchangeTest::registry.isInitialized) {
                for (selection in selections.toList()) registry.discard(selection)
            }
            if (this@CueRoomExchangeTest::io.isInitialized) io.awaitIdle()
        } finally {
            if (this@CueRoomExchangeTest::fixture.isInitialized) fixture.close()
        }
    }

    @Test(timeout = 60_000L)
    fun jsonAndM3uExportsReopenTheirActualBytesAndMatchTheAcceptedSameOriginCueRows(): Unit = runBlocking {
        val bound = scan().reversed()
        val portable = bound.map { it.portable() }
        val original = createCuePlaylist(portable)
        val before = fixture.library.overview()
        val payloads = exportPayloads(original, portable)
        for ((format, bytes) in payloads) {
            val document = input(bytes, format)
            try {
                val preview = inspect(format, document)
                assertEquals(2L, preview.memberCount)
                assertEquals(2L, preview.matchedCount)
                assertEquals(0L, preview.unresolvedCount)
                assertTrue(exchange.importPage(preview.previewId, 0L).rows.all { it.issue == PlaylistImportMemberIssue.NONE })
                assertEquals(portable, previewReferences(preview))
                val suffix = format.name.lowercase()
                val request = PlaylistImportConfirmation("cue-same-$suffix", preview.previewId,
                    PlaylistImportDestination.New("cue-same-list-$suffix", "Same origin CUE"), false)
                val count = registrations.get()
                val accepted = exchange.confirmImport(request)
                assertEquals(2, registrations.get() - count)
                assertEquals(portable.map { it.trackKey }, members(checkNotNull(accepted.snapshot)).map { it.reference.trackKey })
                assertEquals(portable, members(checkNotNull(accepted.snapshot)).map { it.reference.cueReference })
                assertTrue(exchange.confirmImport(request).wasReplay)
                for (reference in bound) assertEquals(reference, fixture.library.resolveCueRead(reference.trackKey).reference)
                assertEquals(before, fixture.library.overview())
                closePreview(preview)
                assertTrue(bytes.contentEquals(documentFile.readBytes()))
            } finally { discard(document) }
        }
        assertProviderClosed()
    }

    @Test(timeout = 60_000L)
    fun sameBytesAndRelativeNamesFromAnotherSourcePreserveTheOriginalPendingCueIdentity(): Unit = runBlocking {
        val old = scan().reversed()
        val portable = old.map { it.portable() }
        val payloads = exportPayloads(createCuePlaylist(portable), portable)
        exchange.recoverAbandoned()
        fixture.close()
        fixture = RoomCueLibraryFixture(context, File(temporary.root, "cue-exchange-target.db"))
        fixture.open()
        installWriter()
        exchange = controller()
        val target = scan("cue-new-source").reversed()
        for ((original, candidate) in old.zip(target)) {
            assertNotEquals(original.trackKey, candidate.trackKey)
            assertEquals(original.identity.cueContentToken, candidate.identity.cueContentToken)
            assertEquals(original.identity.audioContentTokenHint, candidate.identity.audioContentTokenHint)
            assertEquals(original.identity.startFrame, candidate.identity.startFrame)
            assertEquals(original.identity.endFrameExclusive, candidate.identity.endFrameExclusive)
        }
        val root = checkNotNull(fixture.database.libraryDao().node("cue-new-source", "root"))
        val binding = PlaylistFolderBinding("cue-new-source", root.nodeId)
        for ((format, bytes) in payloads) {
            val document = input(bytes, format, binding)
            try {
                val preview = inspect(format, document)
                assertEquals(0L, preview.matchedCount)
                assertEquals(2L, preview.unresolvedCount)
                assertTrue(exchange.importPage(preview.previewId, 0L).rows.all {
                    it.issue == PlaylistImportMemberIssue.REQUIRES_REASSOCIATION
                })
                assertEquals(portable, previewReferences(preview))
                val suffix = format.name.lowercase()
                val destination = PlaylistImportDestination.New("cue-pending-list-$suffix", "Pending original CUE")
                expect(PlaylistIssue.INVALID_INPUT) {
                    exchange.confirmImport(PlaylistImportConfirmation("cue-pending-rejected-$suffix", preview.previewId, destination, false))
                }
                assertNull(playlists.snapshot(destination.playlistId))
                val count = registrations.get()
                val accepted = exchange.confirmImport(PlaylistImportConfirmation("cue-pending-$suffix", preview.previewId, destination, true))
                assertEquals(2, registrations.get() - count)
                val all = members(checkNotNull(accepted.snapshot))
                assertEquals(portable.map { it.trackKey }, all.map { it.reference.trackKey })
                assertEquals(portable, all.map { it.reference.cueReference })
                assertTrue(all.all { it.reference.placeholderId == null })
                for (reference in portable) {
                    val saved = checkNotNull(fixture.database.libraryDao().currentTrack(reference.trackKey.trackId))
                    assertEquals(reference, CueReferenceCodec.decode(checkNotNull(saved.cueReference)))
                    assertFalse(saved.member)
                    assertNull(fixture.library.source(reference.identity.sourceId))
                }
                for (reference in target) assertEquals(reference, fixture.library.resolveCueRead(reference.trackKey).reference)
                closePreview(preview)
            } finally { discard(document) }
        }
        assertProviderClosed()
    }

    @Test(timeout = 30_000L)
    fun aFailureAfterTheFirstPendingHintRollsBackBothRoomDomainsAndNeedsAFreshConfirmedAttempt(): Unit = runBlocking {
        val source = CueByteFixture()
        val portable = listOf(source.reference(1).portable(), source.reference(2).portable())
        val bytes = portableInput(portable)
        val document = input(bytes, PlaylistTransferFormat.JSON)
        try {
            val beforeLibrary = fixture.library.overview()
            val beforePlaylists = playlists.overview()
            val preview = inspect(PlaylistTransferFormat.JSON, document)
            failAfterRegistration = 1
            expect(PlaylistIssue.SOURCE_CHANGED) {
                exchange.confirmImport(PlaylistImportConfirmation("cue-writer-failure", preview.previewId,
                    PlaylistImportDestination.New("cue-writer-list", "Pending CUE"), true))
            }
            assertEquals(1, registrations.get())
            assertNull(playlists.snapshot("cue-writer-list"))
            assertNull(fixture.database.playlistDao().receipt("cue-writer-failure"))
            for (reference in portable) {
                assertNull(fixture.database.libraryDao().track(reference.trackKey.trackId))
                assertNull(fixture.database.libraryDao().currentTrack(reference.trackKey.trackId))
            }
            assertEquals(beforeLibrary, fixture.library.overview())
            assertEquals(beforePlaylists, playlists.overview())
            assertTrue(bytes.contentEquals(documentFile.readBytes()))
            closePreview(preview)
            failAfterRegistration = null
            val reviewed = inspect(PlaylistTransferFormat.JSON, document)
            val accepted = exchange.confirmImport(PlaylistImportConfirmation("cue-writer-retry", reviewed.previewId,
                PlaylistImportDestination.New("cue-writer-list", "Pending CUE"), true))
            assertEquals(portable, members(checkNotNull(accepted.snapshot)).map { it.reference.cueReference })
            for (reference in portable) assertEquals(reference, CueReferenceCodec.decode(checkNotNull(
                fixture.database.libraryDao().currentTrack(reference.trackKey.trackId)?.cueReference)))
            closePreview(reviewed)
        } finally {
            failAfterRegistration = null
            discard(document)
        }
        assertProviderClosed()
    }

    @Test(timeout = 30_000L)
    fun cancellingActualCoreStagingCannotRegisterPendingCueRowsOrReuseOldConsent(): Unit = runBlocking {
        withTimeout(10_000L) {
            val source = CueByteFixture()
            val portable = listOf(source.reference(1).portable(), source.reference(2).portable())
            val beforeLibrary = fixture.library.overview()
            val entered = CompletableDeferred<Unit>()
            val core = playlists
            val port = object : LocalPlaylistRepository by core {
                override suspend fun apply(request: PlaylistMutationRequest, source: PlaylistPageSource?): PlaylistCommit {
                    val wrapped = source?.let { input -> PlaylistPageSource { offset, limit ->
                        input.read(offset, limit)
                        entered.complete(Unit)
                        awaitCancellation()
                    } }
                    return core.apply(request, wrapped)
                }
            }
            exchange = controller(port)
            val bytes = portableInput(portable)
            val document = input(bytes, PlaylistTransferFormat.JSON)
            try {
                val preview = inspect(PlaylistTransferFormat.JSON, document)
                val request = PlaylistImportConfirmation("cue-cancelled-input", preview.previewId,
                    PlaylistImportDestination.New("cue-cancelled-list", "Must stay absent"), true)
                val caller = async(Dispatchers.IO) { exchange.confirmImport(request) }
                try {
                    entered.await()
                    caller.cancelAndJoin()
                    assertTrue(caller.isCancelled)
                    assertEquals(0, registrations.get())
                    assertNull(playlists.snapshot("cue-cancelled-list"))
                    assertNull(fixture.database.playlistDao().receipt(request.operationId))
                    assertNull(fixture.database.playlistExchangeDao().preview(preview.previewId))
                    for (reference in portable) assertNull(fixture.database.libraryDao().currentTrack(reference.trackKey.trackId))
                    assertEquals(beforeLibrary, fixture.library.overview())
                    assertTrue(bytes.contentEquals(documentFile.readBytes()))
                    expect(PlaylistIssue.VIEW_EXPIRED) { exchange.confirmImport(request) }
                    closePreview(preview)
                } finally { caller.cancelAndJoin() }
            } finally { discard(document) }
            assertProviderClosed()
        }
    }

    @Test(timeout = 60_000L)
    fun exportsKeepMismatchedStoredCueProofAndRequireExplicitReassociation(): Unit = runBlocking {
        val current = scan().first()
        val portable = current.portable()
        val stored = portable.copy(identity = portable.identity.copy(cueContentToken = "sha256:" + "e".repeat(64)))
        assertEquals(current.trackKey, stored.trackKey)
        assertNotEquals(current.identity.cueContentToken, stored.identity.cueContentToken)
        assertPendingExportRetainsHint(stored, current)
    }

    @Test(timeout = 60_000L)
    fun exportsDoNotFillUnknownStoredCueAudioProofFromCurrentBinding(): Unit = runBlocking {
        val current = scan().first()
        val portable = current.portable()
        val stored = portable.copy(identity = portable.identity.copy(audioContentTokenHint = null))
        assertEquals(current.trackKey, stored.trackKey)
        assertNull(stored.identity.audioContentTokenHint)
        assertTrue(portable.identity.audioContentTokenHint != null)
        assertPendingExportRetainsHint(stored, current)
    }

    @Test(timeout = 60_000L)
    fun legacyKeyOnlyMembersStillExportCurrentCueAndResolveActualReadback(): Unit = runBlocking {
        val current = scan().first()
        val frozen = FrozenPlaylistInput("cue-legacy-input", 1L, 1L)
        val snapshot = checkNotNull(playlists.apply(PlaylistMutationRequest("cue-legacy-create", "cue-legacy-list", 0L,
            PlaylistMutation.Create("Legacy CUE", frozen)), PlaylistPageSource { offset, _ ->
            check(offset == 0L)
            PlaylistInputPage(frozen, offset, listOf(PlaylistReference(current.trackKey, titleHint = "Legacy CUE")), true)
        }).snapshot)
        assertNull(members(snapshot).single().reference.cueReference)
        val beforeLibrary = fixture.library.overview()
        val beforeGrants = grantCount()
        for (format in FORMATS) {
            val (bytes, parsed) = exportMembers(snapshot, format)
            val exported = parsed.single()
            assertEquals(current.portable(), exported.cue?.reference)
            assertTrue(exported.relativePath != null && exported.cue?.relativePath != null)
            assertFalse(exported.unresolved)
            val document = input(bytes, format)
            try {
                val preview = inspect(format, document)
                try {
                    val row = fixture.database.playlistExchangeDao().page(preview.previewId, 0L, PlaylistLimits.PAGE_SIZE).single()
                    assertEquals(PlaylistImportMemberIssue.NONE.name, row.importIssue)
                    assertEquals(PlaylistQueueAvailability.AVAILABLE.name, row.availability)
                    assertEquals(current.portable(), row.reference().cueReference)
                } finally {
                    closePreview(preview)
                }
            } finally {
                discard(document)
            }
            assertProviderClosed()
        }
        assertEquals(beforeLibrary, fixture.library.overview())
        assertEquals(beforeGrants, grantCount())
        assertEquals(current, fixture.library.resolveCueRead(current.trackKey).reference)
    }

    private suspend fun assertPendingExportRetainsHint(stored: LocalCueReference, current: LocalCueReference) {
        val snapshot = createCuePlaylist(listOf(stored))
        val beforeMembers = members(snapshot)
        assertEquals(stored, beforeMembers.single().reference.cueReference)
        val beforeLibrary = fixture.library.overview()
        val beforeGrants = grantCount()
        for (format in FORMATS) {
            val (bytes, parsed) = exportMembers(snapshot, format)
            val exported = parsed.single()
            assertEquals(stored, exported.cue?.reference)
            assertFalse(checkNotNull(exported.cue?.reference).isBound)
            assertNull(exported.relativePath)
            assertNull(exported.cue?.relativePath)
            assertTrue(exported.unresolved)
            val document = input(bytes, format)
            try {
                // 已重新打开的真实导出文档进入预览解析与resolveCue，保留原确认依据。
                val preview = inspect(format, document)
                try {
                    assertEquals(0L, preview.matchedCount)
                    assertEquals(1L, preview.unresolvedCount)
                    val row = fixture.database.playlistExchangeDao().page(preview.previewId, 0L, PlaylistLimits.PAGE_SIZE).single()
                    assertEquals(PlaylistImportMemberIssue.REQUIRES_REASSOCIATION.name, row.importIssue)
                    assertEquals(PlaylistQueueAvailability.MISSING.name, row.availability)
                    assertEquals(stored, row.reference().cueReference)
                    val suffix = format.name.lowercase()
                    val destination = PlaylistImportDestination.New("cue-hint-import-$suffix", "Pending saved CUE")
                    expect(PlaylistIssue.INVALID_INPUT) {
                        exchange.confirmImport(PlaylistImportConfirmation("cue-hint-rejected-$suffix", preview.previewId, destination, false))
                    }
                    assertNull(playlists.snapshot(destination.playlistId))
                    val accepted = exchange.confirmImport(PlaylistImportConfirmation("cue-hint-confirmed-$suffix", preview.previewId, destination, true))
                    assertEquals(stored, members(checkNotNull(accepted.snapshot)).single().reference.cueReference)
                } finally {
                    closePreview(preview)
                }
            } finally {
                discard(document)
            }
            assertProviderClosed()
        }
        assertEquals(beforeMembers, members(snapshot))
        assertEquals(beforeLibrary, fixture.library.overview())
        assertEquals(beforeGrants, grantCount())
        assertEquals(current, fixture.library.resolveCueRead(current.trackKey).reference)
    }

    private suspend fun exportMembers(snapshot: PlaylistSnapshot, format: PlaylistTransferFormat): Pair<ByteArray, List<ExchangeMember>> {
        val view = playlists.openView(snapshot.playlistId)
        // 结果离开解析作用域后再返回，避免挂起清理保留已失活的局部变量。
        val result = try {
            prepareDocument(format)
            documentFile.writeBytes(byteArrayOf())
            val operation = "cue-hint-export-${format.name.lowercase()}"
            val document = registry.registerExport(documentUri, READ_WRITE, operation).also { selections += it.selectionId }
            try {
                val receipt = exchange.export(operation, view, document, format, PlaylistTransferEncoding.UTF8)
                val bytes = documentFile.readBytes()
                val parsed = mutableListOf<ExchangeMember>()
                val sink = ExchangePageSink { parsed += it }
                val proof = ByteArrayInputStream(bytes).use { stream ->
                    if (format == PlaylistTransferFormat.JSON) JsonPlaylistExchange().parse(stream, sink)
                    else M3uPlaylistExchange().parse(stream, sink,
                        if (format == PlaylistTransferFormat.M3U8) M3uFileKind.M3U8 else M3uFileKind.M3U)
                }
                assertEquals(snapshot.memberCount, receipt.memberCount)
                assertEquals(bytes.size.toLong(), receipt.byteCount)
                assertEquals(receipt.sha256, proof.sha256)
                assertEquals(snapshot.memberCount, proof.memberCount)
                assertFalse(bytes.toString(Charsets.UTF_8).contains("content://"))
                assertFalse(bytes.toString(Charsets.UTF_8).contains("http://"))
                assertFalse(bytes.toString(Charsets.UTF_8).contains("https://"))
                bytes to parsed
            } finally {
                discard(document)
            }
        } finally {
            playlists.closeView(view.viewId)
        }
        return result
    }

    private suspend fun grantCount(): Long = fixture.database.libraryDao().scalar(RoomRawQuery("SELECT COUNT(*) FROM local_grants"))

    private suspend fun installWriter() {
        registrations.set(0)
        val fences = RoomPlaylistFences(fixture.database)
        playlists = RoomPlaylistRepository(fixture.database, fixture.database.playlistDao(), { fixture.elapsed }, { EPOCH_MS },
            "cue-room-exchange", fences, fences, cueInputRegistrar = ::registerPortableInputs)
        playlists.initialize()
    }

    private suspend fun registerPortableInputs(generation: String) {
        var first = 0L
        while (true) {
            val page = fixture.database.playlistDao().membersPage(generation, first, PlaylistLimits.PAGE_SIZE)
            if (page.isEmpty()) break
            for (member in page) {
                val cue = member.cueReference?.let { CueReferenceCodec.decode(it, portableOnly = true) } ?: continue
                fixture.library.registerPortableCueHintUnlocked(cue, LocalAudioMetadata(title = member.titleHint,
                    artist = member.artistHint, album = member.albumHint,
                    durationMs = member.durationMs ?: cue.identity.durationUs / 1000L, trackNumber = cue.identity.trackNumber),
                    "CUE ${cue.identity.trackNumber}")
                // 注入失败发生在共享的最终写事务中，且此前已真实写入待确认行。
                if (registrations.incrementAndGet() == failAfterRegistration) throw PlaylistException(PlaylistIssue.SOURCE_CHANGED)
            }
            first = Math.addExact(page.last().ordinal, 1L)
        }
    }

    private fun controller(port: LocalPlaylistRepository = playlists) = RoomPlaylistExchangeController(
        context, fixture.database, port, fixture.library, registry, io, { fixture.elapsed }, { EPOCH_MS },
    )

    private suspend fun scan(sourceId: String = RoomCueLibraryFixture.SOURCE_ID): List<LocalCueReference> {
        val staged = fixture.stage(sourceId = sourceId)
        fixture.accept(staged)
        fixture.finish(staged)
        return fixture.segments(sourceId).map { checkNotNull(it.cueReference) }.also { assertEquals(2, it.size) }
    }

    private suspend fun createCuePlaylist(references: List<LocalCueReference>): PlaylistSnapshot {
        val input = FrozenPlaylistInput("cue-export-input", 1L, references.size.toLong())
        return checkNotNull(playlists.apply(PlaylistMutationRequest("cue-export-create", "cue-export-source", 0L,
            PlaylistMutation.Create("Saved CUE order", input)), PlaylistPageSource { offset, limit ->
            val end = minOf(references.size.toLong(), offset + limit).toInt()
            val page = references.subList(offset.toInt(), end).map { PlaylistReference(it.trackKey,
                titleHint = "CUE ${it.identity.trackNumber}", durationMs = it.identity.durationUs / 1000L, cueReference = it) }
            PlaylistInputPage(input, offset, page, end.toLong() == input.totalCount)
        }).snapshot)
    }

    private suspend fun exportPayloads(snapshot: PlaylistSnapshot, references: List<LocalCueReference>): Map<PlaylistTransferFormat, ByteArray> {
        val payloads = linkedMapOf<PlaylistTransferFormat, ByteArray>()
        val view = playlists.openView(snapshot.playlistId)
        try {
            for (format in FORMATS) {
                prepareDocument(format)
                documentFile.writeBytes(byteArrayOf())
                val operation = "cue-export-${format.name.lowercase()}"
                val document = registry.registerExport(documentUri, READ_WRITE, operation).also { selections += it.selectionId }
                try {
                    val receipt = exchange.export(operation, view, document, format, PlaylistTransferEncoding.UTF8)
                    val bytes = documentFile.readBytes()
                    assertEquals(references.size.toLong(), receipt.memberCount)
                    assertEquals(bytes.size.toLong(), receipt.byteCount)
                    val parsed = mutableListOf<ExchangeMember>()
                    val sink = ExchangePageSink { parsed += it }
                    val proof = ByteArrayInputStream(bytes).use { stream ->
                        if (format == PlaylistTransferFormat.JSON) JsonPlaylistExchange().parse(stream, sink)
                        else M3uPlaylistExchange().parse(stream, sink,
                            if (format == PlaylistTransferFormat.M3U8) M3uFileKind.M3U8 else M3uFileKind.M3U)
                    }
                    assertEquals(receipt.sha256, proof.sha256)
                    assertEquals(references, parsed.map { it.cue?.reference })
                    assertTrue(parsed.all { it.mediaKind == ExchangeMediaKind.CUE_TRACK && it.cue?.relativePath != null })
                    assertFalse(bytes.toString(Charsets.UTF_8).contains("content://"))
                    payloads[format] = bytes
                } finally { discard(document) }
            }
        } finally { playlists.closeView(view.viewId) }
        return payloads
    }

    private suspend fun portableInput(references: List<LocalCueReference>): ByteArray {
        val members = references.mapIndexed { index, cue -> ExchangeMember(index.toLong(),
            UUID.randomUUID().toString(), trackKey = cue.trackKey, mediaKind = ExchangeMediaKind.CUE_TRACK,
            cue = ExchangeCueHint(null, cue.identity.trackNumber, cue), title = "CUE ${cue.identity.trackNumber}", unresolved = true) }
        val source = object : ExchangeExportSource {
            override val header = ExchangePlaylistHeader("Portable original CUE", exportId = UUID.randomUUID().toString())
            override val memberCount = members.size.toLong()
            override suspend fun validateSnapshot() = Unit
            override suspend fun page(offset: Long, limit: Int): List<ExchangeMember> =
                members.drop(offset.toInt()).take(limit)
        }
        return ByteArrayOutputStream().use { output ->
            JsonPlaylistExchange().write(output, source)
            output.toByteArray()
        }
    }

    private fun prepareDocument(format: PlaylistTransferFormat) {
        provider.displayName = when (format) {
            PlaylistTransferFormat.JSON -> "fixture.json"
            PlaylistTransferFormat.M3U8 -> "fixture.m3u8"
            PlaylistTransferFormat.M3U -> "fixture.m3u"
        }
        provider.mimeType = if (format == PlaylistTransferFormat.JSON) "application/json" else "audio/x-mpegurl"
    }

    private suspend fun input(bytes: ByteArray, format: PlaylistTransferFormat,
        binding: PlaylistFolderBinding? = null): AuthorizedPlaylistDocument {
        prepareDocument(format)
        documentFile.writeBytes(bytes)
        return registry.registerImport(documentUri, READ, binding).also { selections += it.selectionId }
    }

    private suspend fun inspect(format: PlaylistTransferFormat, document: AuthorizedPlaylistDocument): PlaylistImportPreview =
        exchange.inspectImport(format, PlaylistTransferEncoding.UTF8, document).also { previews += it.previewId }

    private suspend fun previewReferences(preview: PlaylistImportPreview): List<LocalCueReference?> =
        fixture.database.playlistExchangeDao().page(preview.previewId, 0L, PlaylistLimits.PAGE_SIZE).map { it.reference().cueReference }

    private suspend fun members(snapshot: PlaylistSnapshot): List<PlaylistMember> {
        val view = playlists.openView(snapshot.playlistId)
        return try {
            val all = mutableListOf<PlaylistMember>()
            var first = 0L
            while (true) {
                val page = playlists.page(view, first)
                all += page.members
                first = page.nextOrdinal ?: break
            }
            all
        } finally { playlists.closeView(view.viewId) }
    }

    private suspend fun closePreview(preview: PlaylistImportPreview) {
        exchange.closePreview(preview.previewId)
        previews -= preview.previewId
    }

    private suspend fun discard(document: AuthorizedPlaylistDocument) {
        registry.discard(document.selectionId)
        selections -= document.selectionId
    }

    private fun assertProviderClosed() {
        assertEquals(provider.cursorCount.get(), provider.cursorCloseCount.get())
        assertTrue(provider.allDescriptorsClosed)
        assertEquals(0, provider.childQueryCount.get())
        assertEquals(0, provider.deleteCount.get())
    }

    private suspend fun expect(issue: PlaylistIssue, action: suspend () -> Any?) {
        try {
            action();
            throw AssertionError("Expected $issue")
        }
        catch (error: PlaylistException) { assertEquals(issue, error.issue) }
    }

    private companion object {
        const val EPOCH_MS = 1_790_000_000_000L
        const val READ = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val READ_WRITE = READ or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val FORMATS = listOf(PlaylistTransferFormat.JSON, PlaylistTransferFormat.M3U8, PlaylistTransferFormat.M3U)
    }
}
