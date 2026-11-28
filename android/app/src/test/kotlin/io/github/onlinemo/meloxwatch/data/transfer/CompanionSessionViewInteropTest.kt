package io.github.onlinemo.meloxwatch.data.transfer

import android.content.Context
import io.github.onlinemo.meloxwatch.companion.data.CompanionClock
import io.github.onlinemo.meloxwatch.companion.data.CompanionDisplayDraft
import io.github.onlinemo.meloxwatch.companion.data.CompanionEditPhase
import io.github.onlinemo.meloxwatch.companion.data.CompanionEndpoint
import io.github.onlinemo.meloxwatch.companion.data.CompanionTarget
import io.github.onlinemo.meloxwatch.companion.transfer.CompanionTransferClient
import io.github.onlinemo.meloxwatch.companion.transfer.CompanionWireConnection
import io.github.onlinemo.meloxwatch.companion.transfer.CompanionWireFactory
import io.github.onlinemo.meloxwatch.companion.transfer.TlsCompanionConnection
import io.github.onlinemo.meloxwatch.data.database.withTestWriterSql
import io.github.onlinemo.meloxwatch.data.library.LocalAudioMetadata
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Production phone Client/Connection, actual TLS and native Room; Wi-Fi routing and devices remain separate gates. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CompanionSessionViewInteropTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test(timeout = 45_000L)
    fun anIdleWireRetiresWhileTheSameLogicalViewContinuesThroughTheLastPage(): Unit = runBlocking {
        val node = node(251)
        try {
            val expected = node.room.tracks().map { it.trackKey.trackId }
            val first = node.client.readPage(TransferCatalogKind.TRACKS, "", null)
            assertEquals(100, first.rows.size)
            val prior = checkNotNull(node.factory.current)
            delay(9_000L)
            await { prior.retired && node.server.activeConnectionCount() == 0 }
            assertEquals(1L, node.views())
            val stranger = LoopbackFactory(TransferTestFixtures.identity())
            stranger.open(node.endpoint, node.handle.opening.target, node.handle.opening.sessionId).close().await()
            await { node.server.activeConnectionCount() == 0 }
            assertEquals(1L, node.views())
            val second = node.client.readPage(TransferCatalogKind.TRACKS, "", null, first)
            val last = node.client.readPage(TransferCatalogKind.TRACKS, "", null, second)
            assertEquals(first.viewId, second.viewId)
            assertEquals(first.viewId, last.viewId)
            assertTrue(first.libraryRevision.sameAs(last.libraryRevision))
            assertEquals(100L, second.firstOrdinal)
            assertEquals(200L, last.firstOrdinal)
            assertEquals(51, last.rows.size)
            assertNull(last.nextCursor)
            assertEquals(expected, (first.rows + second.rows + last.rows).map { (it as TransferCatalogRow.Track).trackId })
            assertEquals(3, node.backend.reads.get())
        } finally { node.close() }
    }

    @Test(timeout = 45_000L)
    fun confirmationAfterRealIdleRecreatesOnlyTheExactOriginalPreviewAndReplaysTheReceipt(): Unit = runBlocking {
        val node = node(3)
        try {
            val draft = node.draft()
            assertNotNull(node.room.database.transferRoomDao().preview(draft.operationId))
            val prior = checkNotNull(node.factory.current)
            delay(9_000L)
            await { prior.retired && node.server.activeConnectionCount() == 0 }
            assertNull(node.room.database.transferRoomDao().preview(draft.operationId))
            val result = node.client.applyDisplay(draft)
            assertEquals(draft.entityId, result.resultId)
            assertEquals(1, node.backend.applies.get())
            val replay = node.client.applyDisplay(draft.withPhase(CompanionEditPhase.UNCERTAIN))
            assertTrue(result.binding.sameOperation(replay.binding))
            assertEquals(result.revision, replay.revision)
            assertEquals(1, node.backend.applies.get())
            assertEquals(2, node.backend.previews.get())
        } finally { node.close() }
    }

    @Test(timeout = 30_000L)
    fun sixFreshQueriesRetireTheirPredecessorWithoutRaisingTheFourSlotLimit(): Unit = runBlocking {
        val node = node(3)
        try {
            val ids = linkedSetOf<String>()
            repeat(6) {
                val page = node.client.readPage(TransferCatalogKind.TRACKS, "", null)
                ids += page.viewId
                assertEquals(1L, node.views())
                assertEquals(3L, page.totalCount)
            }
            assertEquals(6, ids.size)
            assertEquals(6, node.backend.reads.get())
            assertEquals(5, node.backend.closes.get())
        } finally { node.close() }
    }

    @Test(timeout = 30_000L)
    fun failedExactViewRetirementNeverRequestsAFreshView(): Unit = runBlocking {
        val node = node(3)
        try {
            node.client.readPage(TransferCatalogKind.TRACKS, "", null)
            node.room.database.withTestWriterSql { execute("""
                CREATE TRIGGER interop_view_retirement BEFORE DELETE ON library_views
                BEGIN SELECT RAISE(ABORT, 'controlled exact view retirement failure'); END;
            """.trimIndent()) }
            try {
                expectTransferIssue(TransferIssue.RECOVERY_REQUIRED) { node.client.readPage(TransferCatalogKind.TRACKS, "", null) }
                assertEquals(1, node.backend.reads.get())
                assertEquals(1L, node.views())
            } finally {
                node.room.database.withTestWriterSql { execute("DROP TRIGGER interop_view_retirement") }
            }
        } finally { node.close() }
    }

    @Test(timeout = 30_000L)
    fun revokedOrExpiredSessionsReleaseLogicalViewsEvenWithNoRemainingWire(): Unit = runBlocking {
        val revoked = node(3)
        try {
            revoked.client.readPage(TransferCatalogKind.TRACKS, "", null)
            revoked.client.disconnect().await()
            await { revoked.server.activeConnectionCount() == 0 }
            assertEquals(1L, revoked.views())
            revoked.server.revokeForLocalUi(revoked.phone.peerFingerprint)
            await { revoked.views() == 0L }
            expectTransferIssue(TransferIssue.NOT_APPROVED) { revoked.client.authenticate(revoked.endpoint) }
        } finally { revoked.close() }
        val expired = node(3)
        try {
            expired.client.readPage(TransferCatalogKind.TRACKS, "", null)
            expired.client.disconnect().await()
            await { expired.server.activeConnectionCount() == 0 }
            // The scheduled expiry dispatches this same stop/retirement path; no ten-minute test sleep.
            expired.server.stop(TransferIssue.EXPIRED).await()
            assertEquals(0L, expired.views())
            assertEquals(TransferServerPhase.OFF, expired.server.phase.value)
        } finally { expired.close() }
    }

    @Test(timeout = 45_000L)
    fun stopDuringRealIdlePreviewRetirementWaitsForTheActualRoomViewClose(): Unit = runBlocking {
        val node = node(3)
        val gate = CompletableDeferred<Unit>()
        node.backend.previewRetirementGate = gate
        try {
            val draft = node.draft()
            withTimeout(14_000L) { node.backend.previewRetirementEntered.await() }
            assertNull(node.room.database.transferRoomDao().preview(draft.operationId))
            assertEquals(1L, node.views())
            assertEquals(1, node.server.activeConnectionCount())
            val stopped = node.server.stop()
            assertFalse(stopped.isCompleted)
            assertEquals(1L, node.views())
            gate.complete(Unit)
            withTimeout(5_000L) { stopped.await() }
            assertEquals(0L, node.views())
            assertEquals(0, node.server.activeConnectionCount())
            assertEquals(TransferServerPhase.OFF, node.server.phase.value)
        } finally { gate.complete(Unit); node.close() }
    }

    @Test(timeout = 45_000L)
    fun revokeDuringRealIdleRetainsAFailedIndependentRoomViewCloseUntilExplicitRecovery(): Unit = runBlocking {
        val node = node(3)
        val gate = CompletableDeferred<Unit>()
        node.backend.previewRetirementGate = gate
        try {
            val draft = node.draft()
            withTimeout(14_000L) { node.backend.previewRetirementEntered.await() }
            assertNull(node.room.database.transferRoomDao().preview(draft.operationId))
            node.room.database.withTestWriterSql { execute("""
                CREATE TRIGGER interop_idle_view_retirement BEFORE DELETE ON library_views
                BEGIN SELECT RAISE(ABORT, 'controlled idle view retirement failure'); END;
            """.trimIndent()) }
            try {
                node.server.revokeForLocalUi(node.phone.peerFingerprint)
                assertEquals(1L, node.views())
                assertEquals(1, node.server.activeConnectionCount())
                gate.complete(Unit)
                await { node.server.phase.value == TransferServerPhase.RECOVERY_REQUIRED }
                expectTransferIssue(TransferIssue.RECOVERY_REQUIRED) { node.server.stop().await() }
                assertEquals(0, node.server.activeConnectionCount())
                assertEquals(1L, node.views())
            } finally {
                node.room.database.withTestWriterSql { execute("DROP TRIGGER interop_idle_view_retirement") }
            }
            withTimeout(5_000L) { node.server.retryRetirement() }
            assertEquals(0L, node.views())
            assertEquals(TransferServerPhase.OFF, node.server.phase.value)
        } finally { gate.complete(Unit); node.close() }
    }

    @Test(timeout = 30_000L)
    fun aSchemaTwoReceiptIsReadBeforeAnyNewPreviewOrMutation(): Unit = runBlocking {
        val node = node(3)
        try {
            val draft = node.draft()
            val request = node.applyRequest(draft)
            val binding = TransferProtocolCodec.operationBinding(node.phone.peerFingerprint, request)
            assertEquals(TransferHash.digest(legacyApplyIdentity(request, node.phone.peerFingerprint)), binding.payloadHash)
            node.room.database.withTestWriterSql { execute("""
                INSERT INTO transfer_room_operations
                (operationId, protocolSchema, operationKind, targetFingerprint, peerFingerprint, payloadHash, resultKind, appliedRevision, resultId, actualBytes)
                VALUES (?, 2, ?, ?, ?, ?, ?, ?, ?, NULL)
            """.trimIndent(), binding.operationId, binding.kind.wire, binding.target.forLocalDisplay(), binding.peer.forLocalDisplay(),
                binding.payloadHash.forLocalStorage(), TransferReceiptKind.DISPLAY_COMMITTED.wire, draft.expectedRevision + 1L, draft.entityId) }
            val previews = node.backend.previews.get()
            val result = node.client.applyDisplay(draft.withPhase(CompanionEditPhase.UNCERTAIN))
            assertTrue(binding.sameOperation(result.binding))
            assertEquals(draft.entityId, result.resultId)
            assertEquals(previews, node.backend.previews.get())
            assertEquals(0, node.backend.applies.get())
            assertEquals(2, node.room.database.transferRoomDao().operation(binding.operationId)?.protocolSchema)
        } finally { node.close() }
    }

    @Test(timeout = 30_000L)
    fun aChangedBaseOrForeignReceiptNeverBecomesAnImplicitConfirmation(): Unit = runBlocking {
        val changed = node(3)
        try {
            val draft = changed.draft()
            val source = checkNotNull(changed.room.library.source("room-source"))
            changed.room.append(source, listOf("later" to LocalAudioMetadata(title = "Later", album = "Other album", artist = "Artist")))
            expectTransferIssue(TransferIssue.CONFLICT) { changed.client.applyDisplay(draft) }
            assertEquals(0, changed.backend.applies.get())
            assertEquals("Renamed album", (draft.patch as TransferDisplayPatch.Rename).displayName)
        } finally { changed.close() }
        val foreign = node(3)
        try {
            val draft = foreign.draft()
            val binding = TransferProtocolCodec.operationBinding(foreign.phone.peerFingerprint, foreign.applyRequest(draft))
            foreign.room.database.withTestWriterSql { execute("""
                INSERT INTO transfer_room_operations
                (operationId, protocolSchema, operationKind, targetFingerprint, peerFingerprint, payloadHash, resultKind, appliedRevision, resultId, actualBytes)
                VALUES (?, 2, ?, ?, ?, ?, ?, ?, 'album:foreign', NULL)
            """.trimIndent(), binding.operationId, binding.kind.wire, binding.target.forLocalDisplay(), binding.peer.forLocalDisplay(),
                binding.payloadHash.forLocalStorage(), TransferReceiptKind.DISPLAY_COMMITTED.wire, draft.expectedRevision + 1L) }
            val previews = foreign.backend.previews.get()
            expectTransferIssue(TransferIssue.CONFLICT) { foreign.client.applyDisplay(draft) }
            assertEquals(previews, foreign.backend.previews.get())
            assertEquals(0, foreign.backend.applies.get())
        } finally { foreign.close() }
    }

    @Test(timeout = 30_000L)
    fun aReadOnlyPeerCannotLookUpOrRevalidateAnUnapprovedEdit(): Unit = runBlocking {
        val node = node(3, setOf(TransferCapability.READ_LIBRARY))
        try {
            val page = node.client.readPage(TransferCatalogKind.ALBUMS, "", null)
            val group = page.rows.single() as TransferCatalogRow.Group
            val operation = UUID.randomUUID().toString()
            val draft = CompanionDisplayDraft(operation, node.handle.opening.target, TransferDisplayKind.ALBUM,
                group.entityId, group.revision, page.libraryRevision, group.title, group.trackCount,
                TransferDisplayPatch.Rename("Unapproved"), previewId = operation, phase = CompanionEditPhase.SUBMITTING)
            expectTransferIssue(TransferIssue.PERMISSION_DENIED) { node.client.applyDisplay(draft) }
            assertEquals(0, node.backend.lookups.get())
            assertEquals(0, node.backend.previews.get())
            assertEquals(0, node.backend.applies.get())
        } finally { node.close() }
    }

    private suspend fun node(count: Int, capabilities: Set<TransferCapability> = setOf(
        TransferCapability.READ_LIBRARY, TransferCapability.WRITE_DISPLAY_METADATA)): Node {
        val room = CompanionRoomFixture(RuntimeEnvironment.getApplication() as Context, File(temporary.newFolder(), "room.db"))
        room.initialize()
        val source = room.source()
        room.append(source, List(count) { index -> "track-$index" to LocalAudioMetadata(
            title = "Track " + index.toString().padStart(4, '0'), album = "Fixture album", artist = "Fixture artist") })
        val store = TransferTestFixtures.StoreFixture(temporary.newFolder())
        val phone = TransferTestFixtures.identity()
        val factory = LoopbackFactory(phone)
        val backend = CountingBackend(room.backend)
        val server = LocalTransferServer(store.store, backend)
        val result = Node(room, store, phone, factory, backend, server, CompanionTransferClient(factory))
        try {
            result.handle = server.start(TransferTestFixtures.identity(), InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
            result.endpoint = CompanionEndpoint.parse("127.0.0.1", result.handle.port.toString())
            result.client.pair(result.endpoint, CompanionTarget(result.handle.opening.target, "Fixture watch"),
                checkNotNull(server.pairingCodeForLocalUi()), capabilities)
            server.approveForLocalUi(phone.peerFingerprint, capabilities)
            result.client.authenticate(result.endpoint)
            return result
        } catch (failure: Throwable) {
            result.close()
            throw failure
        }
    }

    private class Node(val room: CompanionRoomFixture, val store: TransferTestFixtures.StoreFixture,
        val phone: TransferTlsIdentity, val factory: LoopbackFactory, val backend: CountingBackend,
        val server: LocalTransferServer, val client: CompanionTransferClient) {
        lateinit var handle: LocalTransferHandle
        lateinit var endpoint: CompanionEndpoint
        suspend fun views(): Long = room.database.withTestWriterSql {
            scalarLong("SELECT COUNT(*) FROM library_views WHERE owner = 'TRANSFER'")
        }
        suspend fun draft(): CompanionDisplayDraft {
            val groups = client.readPage(TransferCatalogKind.ALBUMS, "", null)
            val group = groups.rows.single() as TransferCatalogRow.Group
            val context = client.readDisplay(TransferDisplayKind.ALBUM, group.entityId)
            val proposed = CompanionDisplayDraft(UUID.randomUUID().toString(), handle.opening.target, context.kind,
                context.entityId, context.revision, context.libraryRevision, context.currentName, context.trackCount,
                TransferDisplayPatch.Rename("Renamed album"))
            return proposed.withPreview(client.previewDisplay(proposed)).withPhase(CompanionEditPhase.SUBMITTING)
        }
        fun applyRequest(draft: CompanionDisplayDraft): TransferRequest.ApplyDisplay = TransferRequest.ApplyDisplay(
            draft.operationId, TransferScope(handle.opening.target, handle.opening.sessionId, TransferBearer.fromWire(ByteArray(32))),
            checkNotNull(draft.previewId), draft.expectedRevision, true)
        suspend fun close() = withContext(NonCancellable) {
            try { client.disconnect(forget = true).await() }
            finally {
                try { server.stop().await() }
                finally { store.providerIo.awaitIdle(); room.close() }
            }
        }
    }

    /** Only the selected Wi-Fi binding is substituted; the original TLS worker, codec and retirement run. */
    private class LoopbackFactory(private val identity: TransferTlsIdentity) : CompanionWireFactory {
        var current: TlsCompanionConnection? = null
            private set
        override val retirementRequired: Boolean get() = current?.retirementFailed == true
        override suspend fun open(endpoint: CompanionEndpoint, target: TransferFingerprint, expectedSession: String?): CompanionWireConnection {
            check(current?.retired != false)
            val connection = TlsCompanionConnection(identity, CompanionClock { System.nanoTime() / 1_000_000L })
            current = connection
            try {
                connection.prepare {
                    connection.raw.connect(InetSocketAddress(endpoint.address, endpoint.port), 5000)
                    connection.prepareTls(target, expectedSession)
                }
                return connection
            } catch (failure: Throwable) {
                withContext(NonCancellable) { connection.close().await() }
                throw failure
            }
        }
        override fun stop(): Deferred<Unit> = current?.close() ?: CompletableDeferred(Unit)
        override suspend fun retryRetirement() {
            current?.retryRetirement()
            if (current?.retired == true) current = null
        }
    }

    private class CountingBackend(private val owner: TransferBackend) : TransferBackend by owner {
        val reads = AtomicInteger()
        val closes = AtomicInteger()
        val previews = AtomicInteger()
        val applies = AtomicInteger()
        val lookups = AtomicInteger()
        val previewRetirementEntered = CompletableDeferred<Unit>()
        @Volatile var previewRetirementGate: CompletableDeferred<Unit>? = null
        override suspend fun readReceipt(binding: TransferOperationBinding, permit: TransferPermit): TransferReceipt? {
            lookups.incrementAndGet(); return owner.readReceipt(binding, permit)
        }
        override suspend fun closePeerPreviews(peer: TransferFingerprint, sessionId: String) {
            owner.closePeerPreviews(peer, sessionId)
            previewRetirementGate?.let { gate -> previewRetirementEntered.complete(Unit); gate.await() }
        }
        override suspend fun readPage(request: TransferRequest.ReadPage, permit: TransferPermit): TransferCatalogPage {
            reads.incrementAndGet(); return owner.readPage(request, permit)
        }
        override suspend fun closeCatalogView(request: TransferRequest.CloseCatalogView, permit: TransferPermit) {
            closes.incrementAndGet(); owner.closeCatalogView(request, permit)
        }
        override suspend fun previewDisplay(request: TransferRequest.PreviewDisplay, permit: TransferPermit): TransferDisplayPreview {
            previews.incrementAndGet(); return owner.previewDisplay(request, permit)
        }
        override suspend fun applyDisplay(binding: TransferOperationBinding, request: TransferRequest.ApplyDisplay, permit: TransferPermit): TransferReceipt {
            applies.incrementAndGet(); return owner.applyDisplay(binding, request, permit)
        }
    }

    private suspend fun await(condition: suspend () -> Boolean) = withTimeout(5_000L) {
        while (!condition()) delay(20L)
    }

    /** Frozen schema-2 mutation identity bytes, independent of the new control-frame schema. */
    private fun legacyApplyIdentity(request: TransferRequest.ApplyDisplay, peer: TransferFingerprint): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(2); output.writeInt(TransferOperationKind.DISPLAY.wire)
            output.write(request.scope.target.wireBytes()); output.write(peer.wireBytes())
            for (value in listOf(request.requestId, request.previewId)) {
                val uuid = UUID.fromString(value)
                output.writeLong(uuid.mostSignificantBits); output.writeLong(uuid.leastSignificantBits)
            }
            output.writeLong(request.expectedRevision); output.writeBoolean(request.confirmed)
        }
        return bytes.toByteArray()
    }
}
