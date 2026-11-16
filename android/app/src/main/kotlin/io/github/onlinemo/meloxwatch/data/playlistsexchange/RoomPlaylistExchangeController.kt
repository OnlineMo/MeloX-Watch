package io.github.onlinemo.meloxwatch.data.playlistsexchange

import android.content.Context
import android.os.SystemClock
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.database.withPreservedTransaction
import io.github.onlinemo.meloxwatch.data.library.LibraryQuery
import io.github.onlinemo.meloxwatch.data.library.LibraryViewToken
import io.github.onlinemo.meloxwatch.data.library.RoomLocalLibraryRepository
import io.github.onlinemo.meloxwatch.data.library.playlistControllerAction
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.playlists.FrozenPlaylistInput
import io.github.onlinemo.meloxwatch.data.playlists.LocalPlaylistRepository
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistCommit
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistException
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistInputPage
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistIssue
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistKind
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistLibraryRevision
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistLimits
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutation
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutationRequest
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistPageSource
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistQueueAvailability
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistViewOwner
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistViewToken
import io.github.onlinemo.meloxwatch.data.playlists.playlistDigest
import io.github.onlinemo.meloxwatch.data.playlists.requirePlaylistIdentifier
import io.github.onlinemo.meloxwatch.ui.playlists.AuthorizedPlaylistDocument
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistExchangeController
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistExportReceipt
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistImportConfirmation
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistImportDestination
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistImportMemberIssue
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistImportPage
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistImportPreview
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistImportRow
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistImportTarget
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistPreviewRevision
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistTransferEncoding
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistTransferFormat
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** One private import generation and one writer. Referenced audio is never opened by an exchange. */
class RoomPlaylistExchangeController(
    context: Context,
    private val database: MeloXDatabase,
    private val playlists: LocalPlaylistRepository,
    private val library: RoomLocalLibraryRepository,
    private val documents: PlaylistDocumentRegistry,
    providerIo: LocalProviderIo,
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
    private val epochMs: () -> Long = System::currentTimeMillis,
    private val cleanupTimeoutMs: Long = CLOSE_MS,
) : PlaylistExchangeController {
    private val owner = Mutex()
    private val dao = database.playlistExchangeDao()
    private val files = PlaylistDocumentIo(context.applicationContext, providerIo)
    private val processId = UUID.randomUUID().toString()
    private var recovered = false
    private var retained: ImportState? = null
    private var importAttempt: ImportAttempt? = null
    private var exportLibraryLease: LibraryViewToken? = null
    private var exportPlaylistLease: PlaylistViewToken? = null
    private var pendingDocument: PendingDocument? = null

    init { require(cleanupTimeoutMs in 1L..CLOSE_MS) }

    private class ImportState(val id: String, val view: LibraryViewToken) {
        var preview: PlaylistImportPreview? = null
        var closing = false
    }
    private data class ImportAttempt(val intent: PlaylistImportConfirmation, val mutation: PlaylistMutationRequest)

    /** One deferred owner survives caller cancellation until the physical worker and its leases retire. */
    private class PendingDocument(val document: DocumentLease, val exporting: Boolean) {
        val output = if (exporting) PlaylistOwnedOutput() else null
        var file: File? = null
        var importState: ImportState? = null
        var closeImport = false
        var committed = false
        var documentClosed = false
        var targetSettled = false
        var protectedTargetIssue: PlaylistIssue? = null
    }

    /** Startup removes abandoned private staging; it never repeats a previous confirmation or target write. */
    suspend fun recoverAbandoned() = action { finishPendingDocument(); ensureRecovered() }

    private suspend fun ensureRecovered() {
        if (recovered) return
        library.initialize()
        playlists.initialize()
        files.recoverPrivateFiles()
        reclaimAbandoned()
        recovered = true
    }

    override suspend fun inspectImport(
        format: PlaylistTransferFormat,
        encoding: PlaylistTransferEncoding,
        authorizedDocument: AuthorizedPlaylistDocument,
        target: PlaylistImportTarget?,
    ): PlaylistImportPreview = action {
        validateTransfer(format, encoding, exporting = false)
        finishPendingDocument()
        ensureRecovered()
        releaseImport()
        releaseExportViews()
        files.recoverPrivateFiles()
        importAttempt = null
        reclaimAbandoned()
        target?.let {
            val snapshot = playlists.snapshot(it.playlistId) ?: fail(PlaylistIssue.NOT_FOUND)
            if (snapshot.kind != PlaylistKind.ORDINARY) fail(PlaylistIssue.AUTOMATIC_MEMBERS)
            if (snapshot.revision != it.expectedRevision) fail(PlaylistIssue.CONFLICT)
        }
        val document = documents.borrowImport(authorizedDocument)
        val pending = PendingDocument(document, exporting = false).also { pendingDocument = it }
        var state: ImportState? = null
        var delivered = false
        var failure: Throwable? = null
        try {
            currentCoroutineContext().ensureActive()
            val binding = document.rootBinding
            if (binding != null && !binding.subtree) fail(PlaylistIssue.INVALID_INPUT)
            val query = if (binding == null) LibraryQuery(includeHidden = true) else LibraryQuery(
                sourceId = binding.sourceId, folderNodeId = binding.nodeId, folderSubtree = true, includeHidden = true,
            )
            // This context protects the single lease handoff; parsing and all row batches stay cancellable.
            val id = UUID.randomUUID().toString()
            val view = withContext(NonCancellable) {
                library.openView(query).also { token ->
                    state = ImportState(id, token).also { retained = it; pending.importState = it }
                }
            }
            currentCoroutineContext().ensureActive()
            val expires = database.withPreservedTransaction {
                database.libraryDao().view(view.viewId)?.expiresElapsedMs ?: fail(PlaylistIssue.VIEW_EXPIRED)
            }
            database.withPreservedTransaction {
                dao.insertPreview(PlaylistExchangePreviewEntity(id, processId, "STAGING", view.revision,
                    view.rulesRevision, expires))
            }
            val privateInput = files.newPrivateFile("input").also { pending.file = it }
            val copied = files.copyImport(document.uri, privateInput)
            currentCoroutineContext().ensureActive()
            val resolver = RoomPlaylistImportResolver(library, view, binding)
            var next = 0L
            val sink = ExchangePageSink { page ->
                currentCoroutineContext().ensureActive()
                if (page.isEmpty() || page.size > PlaylistExchangeLimits.PAGE_SIZE ||
                    page.withIndex().any { (index, member) -> member.ordinal != next + index }) {
                    fail(PlaylistIssue.INCOMPLETE)
                }
                val rows = resolver.resolve(id, page)
                database.withPreservedTransaction { dao.insertRows(rows) }
                next += page.size
            }
            val parsed = FileInputStream(privateInput).use { stream ->
                when (format) {
                    PlaylistTransferFormat.JSON -> JsonPlaylistExchange().parse(stream, sink)
                    PlaylistTransferFormat.M3U, PlaylistTransferFormat.M3U8 -> M3uPlaylistExchange().parse(
                        stream, sink, format.m3uKind(), encoding.exchangeEncoding(),
                    )
                }
            }
            if (parsed.byteCount != copied.byteCount || parsed.sha256 != copied.sha256 || parsed.memberCount != next) {
                fail(PlaylistIssue.INCOMPLETE)
            }
            library.validatePlaylistView(view)
            val counts = database.withPreservedTransaction { dao.counts(id) }
            if (counts.memberCount != next) fail(PlaylistIssue.INCOMPLETE)
            val preview = PlaylistImportPreview(id, format, encoding, parsed.header.name,
                1.takeIf { format == PlaylistTransferFormat.JSON }, PlaylistPreviewRevision(view.revision, view.rulesRevision),
                target, next, counts.matchedCount, next - counts.matchedCount, counts.ambiguousCount,
                counts.blockedReferenceCount, counts.duplicateCount)
            database.withPreservedTransaction {
                val staged = dao.preview(id) ?: fail(PlaylistIssue.VIEW_EXPIRED)
                if (staged.state != "STAGING" || staged.processId != processId || staged.expiresElapsedMs <= elapsedMs()) {
                    fail(PlaylistIssue.VIEW_EXPIRED)
                }
                if (dao.updatePreview(staged.copy(state = "READY", memberCount = next, nameHint = preview.nameHint,
                        byteCount = copied.byteCount, sha256 = copied.sha256)) != 1) fail(PlaylistIssue.RECOVERY_REQUIRED)
            }
            currentCoroutineContext().ensureActive()
            finishPendingDocument()
            currentCoroutineContext().ensureActive()
            checkNotNull(state).preview = preview
            delivered = true
            preview
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            if (!delivered) {
                pending.closeImport = true
                state?.closing = true
                if (pendingDocument === pending) cleanupPendingAfterFailure(failure)
                else if (state != null) cleanupImportAfterFailure(failure)
            }
        }
    }

    override suspend fun importPage(previewId: String, firstOrdinal: Long, limit: Int): PlaylistImportPage = action {
        require(firstOrdinal >= 0L && limit in 1..PlaylistLimits.PAGE_SIZE)
        finishPendingDocument()
        val state = liveImport(previewId)
        val preview = checkNotNull(state.preview)
        if (firstOrdinal > preview.memberCount) fail(PlaylistIssue.INVALID_INPUT)
        val wanted = minOf(limit.toLong(), preview.memberCount - firstOrdinal).toInt()
        val rows = database.withPreservedTransaction { dao.page(previewId, firstOrdinal, limit) }
        requireRows(rows, firstOrdinal, wanted)
        val next = firstOrdinal + rows.size
        PlaylistImportPage(previewId, firstOrdinal, rows.map {
            PlaylistImportRow(it.ordinal, it.title, it.artist, PlaylistQueueAvailability.valueOf(it.availability),
                PlaylistImportMemberIssue.valueOf(it.importIssue))
        }, next.takeIf { it < preview.memberCount })
    }

    override suspend fun confirmImport(request: PlaylistImportConfirmation): PlaylistCommit = action {
        requirePlaylistIdentifier(request.operationId)
        requirePlaylistIdentifier(request.previewId)
        val prior = importAttempt?.takeIf { it.intent.operationId == request.operationId }
        if (prior != null && prior.intent != request) fail(PlaylistIssue.INVALID_INPUT)
        val existing = database.withPreservedTransaction {
            if (dao.exportReceipt(request.operationId) != null) fail(PlaylistIssue.INVALID_INPUT)
            database.playlistDao().receipt(request.operationId) != null
        }
        if (existing) {
            // Only this controller's exact accepted attempt may replay a shared-core receipt.
            if (prior == null) fail(PlaylistIssue.INVALID_INPUT)
            val result = playlists.apply(prior.mutation)
            releaseImportAfterCommit()
            return@action result
        }
        finishPendingDocument()
        val state = liveImport(request.previewId)
        val preview = checkNotNull(state.preview)
        if (preview.unresolvedCount > 0L && !request.unresolvedConfirmed) fail(PlaylistIssue.INVALID_INPUT)
        val input = FrozenPlaylistInput(preview.previewId, preview.revision.catalogRevision, preview.memberCount)
        val mutation = prior?.mutation ?: when (val destination = request.destination) {
            is PlaylistImportDestination.New -> {
                if (preview.target != null) fail(PlaylistIssue.INVALID_INPUT)
                PlaylistMutationRequest(request.operationId, destination.playlistId, 0L,
                    PlaylistMutation.Create(destination.name, input), preview.libraryRevision())
            }
            is PlaylistImportDestination.Merge -> {
                if (preview.target != destination.target) fail(PlaylistIssue.INVALID_INPUT)
                val target = playlists.snapshot(destination.target.playlistId) ?: fail(PlaylistIssue.NOT_FOUND)
                if (target.kind != PlaylistKind.ORDINARY) fail(PlaylistIssue.AUTOMATIC_MEMBERS)
                if (target.revision != destination.target.expectedRevision) fail(PlaylistIssue.CONFLICT)
                PlaylistMutationRequest(request.operationId, target.playlistId, target.revision,
                    PlaylistMutation.Add(input), preview.libraryRevision())
            }
        }
        importAttempt = ImportAttempt(request, mutation)
        val source = PlaylistPageSource { offset, limit ->
            require(offset in 0L..input.totalCount && limit in 1..PlaylistLimits.PAGE_SIZE)
            liveImport(preview.previewId)
            val expected = minOf(limit.toLong(), input.totalCount - offset).toInt()
            val rows = database.withPreservedTransaction { dao.page(preview.previewId, offset, limit) }
            requireRows(rows, offset, expected)
            PlaylistInputPage(input, offset, rows.map { it.reference() }, offset + rows.size == input.totalCount)
        }
        try {
            currentCoroutineContext().ensureActive()
            val result = playlists.apply(mutation, source)
            releaseImportAfterCommit()
            result
        } catch (error: CancellationException) {
            cleanupImportAfterFailure(error)
            throw error
        }
    }

    override suspend fun export(
        operationId: String,
        view: PlaylistViewToken,
        authorizedDestination: AuthorizedPlaylistDocument,
        format: PlaylistTransferFormat,
        encoding: PlaylistTransferEncoding,
    ): PlaylistExportReceipt = action {
        requirePlaylistIdentifier(operationId)
        validateTransfer(format, encoding, exporting = true)
        val fingerprint = exportFingerprint(operationId, view, authorizedDestination, format, encoding)
        val replay = database.withPreservedTransaction {
            if (database.playlistDao().receipt(operationId) != null) fail(PlaylistIssue.INVALID_INPUT)
            dao.exportReceipt(operationId)?.also {
                if (it.intentFingerprint != fingerprint) fail(PlaylistIssue.INVALID_INPUT)
            }
        }
        if (replay != null) return@action replay.model()
        finishPendingDocument()
        ensureRecovered()
        releaseExportViews()
        files.recoverPrivateFiles()
        val document = documents.borrowExport(authorizedDestination, operationId)
        val pending = PendingDocument(document, exporting = true).also { pendingDocument = it }
        val owned = checkNotNull(pending.output)
        var failure: Throwable? = null
        try {
            currentCoroutineContext().ensureActive()
            val adopted = withContext(NonCancellable) {
                playlists.adoptView(view.viewId, PlaylistViewOwner.UI).also { exportPlaylistLease = it }
            }
            currentCoroutineContext().ensureActive()
            if (adopted.snapshot != view.snapshot) fail(PlaylistIssue.SOURCE_CHANGED)
            withContext(NonCancellable) {
                library.openView(LibraryQuery(includeHidden = true)).also { exportLibraryLease = it }
            }
            currentCoroutineContext().ensureActive()
            val source = RoomPlaylistExportSource(database, playlists, library, adopted, checkNotNull(exportLibraryLease),
                UUID.randomUUID().toString(), singleLineTitles = format != PlaylistTransferFormat.JSON)
            source.prepareRelativeRoot()
            val staged = files.newPrivateFile("output").also { pending.file = it }
            val written = FileOutputStream(staged).use { stream ->
                val result = when (format) {
                    PlaylistTransferFormat.JSON -> JsonPlaylistExchange().write(stream, source)
                    PlaylistTransferFormat.M3U, PlaylistTransferFormat.M3U8 -> M3uPlaylistExchange().write(stream, source)
                }
                stream.flush()
                stream.fd.sync()
                result
            }
            if (written.memberCount != view.snapshot.memberCount) fail(PlaylistIssue.INCOMPLETE)
            val proof = PlaylistFileProof(written.byteCount, written.sha256)
            currentCoroutineContext().ensureActive()
            files.write(document.uri, staged, proof, owned)
            currentCoroutineContext().ensureActive()
            files.verify(document.uri, proof, owned)
            source.validateSnapshot()
            currentCoroutineContext().ensureActive()
            val receipt = PlaylistExportReceiptEntity(operationId, fingerprint, written.memberCount, proof.byteCount,
                proof.sha256, document.label, epochMs())
            withContext(NonCancellable) {
                val result = database.withPreservedTransaction {
                    if (!owned.verified) fail(PlaylistIssue.INCOMPLETE)
                    if (database.playlistDao().receipt(operationId) != null) fail(PlaylistIssue.INVALID_INPUT)
                    val old = dao.exportReceipt(operationId)
                    if (old != null) {
                        if (old.intentFingerprint != fingerprint || old.sha256 != proof.sha256 || old.byteCount != proof.byteCount) {
                            fail(PlaylistIssue.CONFLICT)
                        }
                        old
                    } else {
                        dao.insertExportReceipt(receipt)
                        receipt
                    }
                }
                // Set inside the protected handoff: cancellation after commit cannot erase accepted target bytes.
                pending.committed = true
                result.model()
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                finishPendingDocument()
            } catch (cleanup: Throwable) {
                // A durable receipt is final even while its bounded cleanup owner remains pending.
                if (!pending.committed) {
                    val original = failure
                    if (original == null) throw cleanup
                    if (original !== cleanup) original.addSuppressed(cleanup)
                }
            }
        }
    }

    override suspend fun closePreview(previewId: String) = action {
        requirePlaylistIdentifier(previewId)
        finishPendingDocument()
        if (retained?.id == previewId) releaseImport()
        releaseExportViews()
    }

    private suspend fun liveImport(id: String): ImportState {
        val state = retained?.takeIf { it.id == id && !it.closing && it.preview != null }
            ?: fail(PlaylistIssue.VIEW_EXPIRED)
        database.withPreservedTransaction {
            val row = dao.preview(id) ?: fail(PlaylistIssue.VIEW_EXPIRED)
            if (row.processId != processId || row.state != "READY" || row.expiresElapsedMs <= elapsedMs() ||
                row.memberCount != checkNotNull(state.preview).memberCount || row.catalogRevision != state.view.revision ||
                row.rulesRevision != state.view.rulesRevision) fail(PlaylistIssue.VIEW_EXPIRED)
        }
        library.validatePlaylistView(state.view)
        return state
    }

    private suspend fun releaseImport() = boundedCleanup { releaseImportOwned() }

    private suspend fun releaseImportOwned() {
        val state = retained ?: return
        state.closing = true
        database.withPreservedTransaction { dao.discard(state.id) }
        library.closePlaylistView(state.view.viewId, cleanupTimeoutMs)
        database.withPreservedTransaction {
            dao.deleteRowsBatch(state.id)
            dao.deleteEmptyPreview(state.id)
        }
        retained = null
    }

    private suspend fun releaseImportAfterCommit() {
        try { finishPendingDocument(); releaseImport() } catch (_: Exception) {
            // Retain cleanup ownership for closePreview/retry; the durable core receipt remains successful.
        }
    }

    private suspend fun cleanupImportAfterFailure(original: Throwable?) {
        try { releaseImport() }
        catch (cleanup: Throwable) {
            if (original == null) throw cleanup
            if (original !== cleanup) original.addSuppressed(cleanup)
        }
    }

    private suspend fun releaseExportViews() = boundedCleanup { releaseExportViewsOwned() }

    private suspend fun releaseExportViewsOwned() {
        exportLibraryLease?.let { view ->
            library.closePlaylistView(view.viewId, cleanupTimeoutMs)
            exportLibraryLease = null
        }
        exportPlaylistLease?.let { view ->
            playlists.closeView(view.viewId)
            exportPlaylistLease = null
        }
    }

    private suspend fun finishPendingDocument() {
        val pending = pendingDocument ?: return
        boundedCleanup {
            // Await the real shared worker. A deadline never releases its IO channel or deletes its private file.
            files.awaitPhysicalClose()
            val output = pending.output
            val staged = pending.file
            if (output != null && !pending.committed && !pending.targetSettled) {
                if (staged == null && output.wrote) fail(PlaylistIssue.RECOVERY_REQUIRED)
                val outcome = if (staged == null) PlaylistOutputCleanup.CLEARED
                    else files.clearUncommitted(pending.document.uri, staged, output)
                when (outcome) {
                    PlaylistOutputCleanup.CLEARED -> pending.targetSettled = true
                    PlaylistOutputCleanup.NOT_OWNED -> {
                        // A remapped or externally changed target is protected and is never retried as our output.
                        pending.targetSettled = true
                        pending.protectedTargetIssue = PlaylistIssue.SOURCE_CHANGED
                    }
                    PlaylistOutputCleanup.UNAVAILABLE -> fail(PlaylistIssue.RECOVERY_REQUIRED)
                }
            }
            files.awaitPhysicalClose()
            pending.file?.let { files.deletePrivate(it); pending.file = null }
            if (!pending.documentClosed) { pending.document.close(); pending.documentClosed = true }
            if (pending.closeImport && retained === pending.importState) releaseImportOwned()
            if (pending.exporting) releaseExportViewsOwned()
            pendingDocument = null
        }
        pending.protectedTargetIssue?.let { fail(it) }
    }

    private suspend fun cleanupPendingAfterFailure(original: Throwable?) {
        try { finishPendingDocument() }
        catch (cleanup: Throwable) {
            if (original == null) throw cleanup
            if (original !== cleanup) original.addSuppressed(cleanup)
        }
    }

    private suspend fun <T> boundedCleanup(block: suspend () -> T): T = try {
        withContext(NonCancellable) { withTimeout(cleanupTimeoutMs) { block() } }
    } catch (_: TimeoutCancellationException) {
        fail(PlaylistIssue.BUSY)
    }

    private suspend fun reclaimAbandoned() {
        while (true) {
            currentCoroutineContext().ensureActive()
            val previews = database.withPreservedTransaction { dao.abandoned(processId) }
            if (previews.isEmpty()) return
            for (preview in previews) {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val removed = database.withPreservedTransaction { dao.deleteRowsBatch(preview.previewId) }
                    if (removed == 0) break
                }
                database.withPreservedTransaction { dao.deleteEmptyPreview(preview.previewId) }
            }
        }
    }

    private fun requireRows(rows: List<PlaylistExchangeRowEntity>, first: Long, count: Int) {
        if (rows.size != count || rows.withIndex().any { (index, row) -> row.ordinal != first + index }) {
            fail(PlaylistIssue.INCOMPLETE)
        }
    }

    private suspend fun <T> action(block: suspend () -> T): T = try {
        playlistControllerAction(owner, block)
    } catch (error: CancellationException) {
        throw CancellationException("Playlist exchange cancelled").also { it.stackTrace = error.stackTrace }
    } catch (error: PlaylistExchangeException) {
        throw error
    } catch (error: PlaylistException) {
        throw PlaylistException(error.issue, diagnostic(error))
    } catch (error: SourceReadException) {
        val issue = when (error.issue) {
            SourceReadIssue.BUSY -> PlaylistIssue.BUSY
            SourceReadIssue.INVALID_SOURCE -> PlaylistIssue.INVALID_INPUT
            SourceReadIssue.SOURCE_CHANGED -> PlaylistIssue.SOURCE_CHANGED
            else -> PlaylistIssue.SOURCE_UNAVAILABLE
        }
        throw PlaylistException(issue, diagnostic(error))
    } catch (error: IOException) {
        throw PlaylistException(PlaylistIssue.STORAGE_UNAVAILABLE, diagnostic(error))
    } catch (error: SecurityException) {
        throw PlaylistException(PlaylistIssue.SOURCE_UNAVAILABLE, diagnostic(error))
    }

    private fun diagnostic(error: Throwable): Throwable = RuntimeException(error.javaClass.name).also { it.stackTrace = error.stackTrace }
    private fun fail(issue: PlaylistIssue): Nothing = throw PlaylistException(issue)

    private companion object { const val CLOSE_MS = 5_000L }
}

private fun validateTransfer(format: PlaylistTransferFormat, encoding: PlaylistTransferEncoding, exporting: Boolean) {
    require(encoding == PlaylistTransferEncoding.UTF8 || !exporting && format == PlaylistTransferFormat.M3U)
}

private fun PlaylistTransferFormat.m3uKind(): M3uFileKind = when (this) {
    PlaylistTransferFormat.M3U -> M3uFileKind.M3U
    PlaylistTransferFormat.M3U8 -> M3uFileKind.M3U8
    PlaylistTransferFormat.JSON -> error("A JSON document has no M3U kind")
}

private fun PlaylistTransferEncoding.exchangeEncoding(): PlaylistExchangeEncoding = when (this) {
    PlaylistTransferEncoding.UTF8 -> PlaylistExchangeEncoding.UTF8
    PlaylistTransferEncoding.GB18030 -> PlaylistExchangeEncoding.GB18030
}

private fun PlaylistImportPreview.libraryRevision() = PlaylistLibraryRevision(revision.catalogRevision, revision.rulesRevision)

private fun PlaylistExportReceiptEntity.model() = PlaylistExportReceipt(operationId, memberCount, byteCount, sha256, destinationLabel)

private fun exportFingerprint(
    operationId: String, view: PlaylistViewToken, document: AuthorizedPlaylistDocument,
    format: PlaylistTransferFormat, encoding: PlaylistTransferEncoding,
): String {
    val snapshot = view.snapshot
    return playlistDigest(listOf("playlist-export-v1", operationId,
        snapshot.playlistId, snapshot.generationId, snapshot.revision.toString(), snapshot.name, snapshot.kind.name,
        snapshot.memberCount.toString(), snapshot.createdEpochMs.toString(), snapshot.updatedEpochMs.toString(),
        snapshot.folderBinding?.sourceId, snapshot.folderBinding?.nodeId, snapshot.folderBinding?.subtree?.toString(),
        document.selectionId, document.label, format.name, encoding.name))
}
