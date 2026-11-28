package io.github.onlinemo.meloxwatch.companion.transfer

import io.github.onlinemo.meloxwatch.companion.data.CompanionClock
import io.github.onlinemo.meloxwatch.companion.data.CompanionCachedPage
import io.github.onlinemo.meloxwatch.companion.data.CompanionConnectionPhase
import io.github.onlinemo.meloxwatch.companion.data.CompanionDisplayDraft
import io.github.onlinemo.meloxwatch.companion.data.CompanionEditPhase
import io.github.onlinemo.meloxwatch.companion.data.CompanionEndpoint
import io.github.onlinemo.meloxwatch.companion.data.CompanionLimits
import io.github.onlinemo.meloxwatch.companion.data.CompanionPageWindow
import io.github.onlinemo.meloxwatch.companion.data.CompanionPlaylistDraft
import io.github.onlinemo.meloxwatch.companion.data.CompanionTarget
import io.github.onlinemo.meloxwatch.companion.data.CompanionUploadDraft
import io.github.onlinemo.meloxwatch.companion.data.CompanionUploadPhase
import io.github.onlinemo.meloxwatch.companion.ui.CompanionPlaylistSelection
import io.github.onlinemo.meloxwatch.companion.ui.CompanionUiState
import io.github.onlinemo.meloxwatch.companion.ui.prepareCompanionPlaylistDraft
import io.github.onlinemo.meloxwatch.data.transfer.TransferBearer
import io.github.onlinemo.meloxwatch.data.transfer.TransferCapability
import io.github.onlinemo.meloxwatch.data.transfer.TransferCatalogKind
import io.github.onlinemo.meloxwatch.data.transfer.TransferCatalogPage
import io.github.onlinemo.meloxwatch.data.transfer.TransferCatalogRow
import io.github.onlinemo.meloxwatch.data.transfer.TransferDisplayKind
import io.github.onlinemo.meloxwatch.data.transfer.TransferDisplayPatch
import io.github.onlinemo.meloxwatch.data.transfer.TransferDisplayPreview
import io.github.onlinemo.meloxwatch.data.transfer.TransferDisplayState
import io.github.onlinemo.meloxwatch.data.transfer.TransferException
import io.github.onlinemo.meloxwatch.data.transfer.TransferFingerprint
import io.github.onlinemo.meloxwatch.data.transfer.TransferHash
import io.github.onlinemo.meloxwatch.data.transfer.TransferIssue
import io.github.onlinemo.meloxwatch.data.transfer.TransferLibraryRevision
import io.github.onlinemo.meloxwatch.data.transfer.TransferLimits
import io.github.onlinemo.meloxwatch.data.transfer.TransferOperationBinding
import io.github.onlinemo.meloxwatch.data.transfer.TransferPlaylistEdit
import io.github.onlinemo.meloxwatch.data.transfer.TransferProtocolCodec
import io.github.onlinemo.meloxwatch.data.transfer.TransferReceipt
import io.github.onlinemo.meloxwatch.data.transfer.TransferReceiptKind
import io.github.onlinemo.meloxwatch.data.transfer.TransferRequest
import io.github.onlinemo.meloxwatch.data.transfer.TransferResponse
import io.github.onlinemo.meloxwatch.data.transfer.TransferScope
import io.github.onlinemo.meloxwatch.data.transfer.TransferUploadDeclaration
import io.github.onlinemo.meloxwatch.data.transfer.TransferUploadKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 业务控制夹具；真实 TLS owner 故障另由 CompanionTlsConnectionTest 定义。每个 gate 均有 finally 释放。 */
class CompanionTransferClientTest {
    @get:Rule val temporary = TemporaryFolder()
    private val target = TransferFingerprint.digest(byteArrayOf(1))
    private val peer = TransferFingerprint.digest(byteArrayOf(2))
    private val fence = TransferLibraryRevision(7L, 3L)
    // 此类的回环字面量只给 fake factory 使用，不打开 socket。
    private val endpoint = CompanionEndpoint.parse("127.0.0.1", "43210")
    private fun id(number: Long): String = UUID(0L, number).toString()
    private fun selected(): CompanionTarget = CompanionTarget(target, "Fixture watch")
    private class MutableClock(@Volatile var now: Long = 0L) : CompanionClock { override fun monotonicMs(): Long = now }

    private class FakeConnection(
        override val greeting: TransferResponse.Greeting,
        override val greetingObservedAtMs: Long,
        override val peer: TransferFingerprint,
        private val handler: suspend (TransferRequest) -> TransferResponse,
        private val pageHandler: suspend (TransferRequest.ReadPage) -> TransferCatalogPage,
    ) : CompanionWireConnection {
        @Volatile override var retired = false
            private set
        @Volatile override var accepting = true
            private set
        @Volatile var failClose = false
        @Volatile var failRetry = false
        @Volatile var failedCloseObserved = false
        val requests: MutableList<TransferRequest> = Collections.synchronizedList(ArrayList())
        val closeCalls = AtomicInteger()
        private var closing: Deferred<Unit>? = null
        override suspend fun exchange(request: TransferRequest): TransferResponse {
            requests += request
            // These legacy control fixtures have no retained view or receipt; real owner state is tested through Room/TLS.
            return when (request) {
                is TransferRequest.CloseCatalogView -> TransferResponse.Cancelled(request.requestId)
                is TransferRequest.ReadReceipt -> TransferResponse.ReceiptState(request.requestId, null)
                else -> handler(request)
            }
        }
        override suspend fun page(request: TransferRequest.ReadPage): TransferCatalogPage { requests += request; return pageHandler(request) }
        @Synchronized override fun close(): Deferred<Unit> {
            closeCalls.incrementAndGet()
            closing?.let { return it }
            accepting = false
            return CompletableDeferred<Unit>().also {
                if (failClose) { failedCloseObserved = true; it.completeExceptionally(TransferException(TransferIssue.RECOVERY_REQUIRED)) }
                else { retired = true; it.complete(Unit) }
                closing = it
            }
        }
        override suspend fun retryRetirement() {
            if (failRetry) throw TransferException(TransferIssue.RECOVERY_REQUIRED)
            accepting = false; retired = true
        }
    }

    private class FakeFactory(connections: List<FakeConnection>) : CompanionWireFactory {
        private val lock = Any()
        private val queue = ArrayDeque(connections)
        @Volatile var current: FakeConnection? = null
        val opens = AtomicInteger()
        var beforeOpen: suspend () -> Unit = {}
        var beforeRetry: suspend () -> Unit = {}
        override val retirementRequired: Boolean get() = current?.let { it.failedCloseObserved && !it.retired } == true
        override suspend fun open(endpoint: CompanionEndpoint, target: TransferFingerprint, expectedSession: String?): CompanionWireConnection {
            opens.incrementAndGet()
            val selected = synchronized(lock) {
                if (current?.retired == false) throw TransferException(TransferIssue.BUSY)
                queue.removeFirst()
            }
            beforeOpen()
            current = selected
            return selected
        }
        override fun stop(): Deferred<Unit> = current?.takeIf { !it.retired }?.close() ?: CompletableDeferred(Unit)
        override suspend fun retryRetirement() {
            val retained = current ?: return
            beforeRetry()
            retained.retryRetirement()
            if (current === retained && retained.retired) current = null
        }
    }

    private fun connection(clock: MutableClock, remaining: Long = TransferLimits.SESSION_MS, observed: Long = clock.now,
        connectionPeer: TransferFingerprint = peer,
        handler: suspend (TransferRequest) -> TransferResponse = { request ->
            when (request) {
                is TransferRequest.Pair -> TransferResponse.PairPending(request.requestId)
                is TransferRequest.Authenticate -> TransferResponse.Authenticated(request.requestId, TransferBearer.fromWire(ByteArray(32) { 9 }), setOf(TransferCapability.READ_LIBRARY), TransferLimits.TOKEN_MS)
                else -> throw TransferException(TransferIssue.INVALID_INPUT)
            }
        },
        pageHandler: suspend (TransferRequest.ReadPage) -> TransferCatalogPage = { request ->
            TransferCatalogPage(request.kind, id(4), 5L, fence, 0L, 0L, emptyList(), null)
        },
    ): FakeConnection = FakeConnection(TransferResponse.Greeting(id(0), target, id(1), remaining), observed, connectionPeer, handler, pageHandler)

    private suspend fun ready(client: CompanionTransferClient, requested: Set<TransferCapability> = setOf(TransferCapability.READ_LIBRARY)) {
        client.pair(endpoint, selected(), "12345678", requested)
        client.authenticate(endpoint)
    }

    @Test fun pairStopsAtWatchConfirmationWithoutPolling() = runBlocking {
        withTimeout(5000L) {
            val clock = MutableClock()
            val wire = connection(clock)
            val factory = FakeFactory(listOf(wire))
            val client = CompanionTransferClient(factory, clock)
            try {
                client.pair(endpoint, selected(), "12345678", setOf(TransferCapability.READ_LIBRARY))
                assertEquals(CompanionConnectionPhase.WAITING_FOR_WATCH, client.state.value.phase)
                assertEquals(1, factory.opens.get())
                assertEquals(1, wire.requests.size)
                assertTrue(wire.requests.single() is TransferRequest.Pair)
                assertTrue(wire.retired)
            } finally { client.disconnect().await() }
        }
    }

    @Test fun ungrantedCapabilityFailsBeforeAnyBusinessRequest() = runBlocking {
        withTimeout(5000L) {
            val clock = MutableClock()
            val paired = connection(clock)
            val authenticated = connection(clock)
            val client = CompanionTransferClient(FakeFactory(listOf(paired, authenticated)), clock)
            try {
                ready(client, setOf(TransferCapability.READ_LIBRARY, TransferCapability.WRITE_DISPLAY_METADATA))
                assertEquals(setOf(TransferCapability.READ_LIBRARY), client.state.value.capabilities)
                issue(TransferIssue.PERMISSION_DENIED) { client.readDisplay(TransferDisplayKind.ALBUM, "album:fixture") }
                assertEquals(1, authenticated.requests.size)
            } finally { client.disconnect().await() }
        }
    }

    @Test fun authenticationRejectsAnExtraGrantAndErasesItsBearer() = runBlocking {
        withTimeout(5000L) {
            val clock = MutableClock()
            val bearer = TransferBearer.fromWire(ByteArray(32) { 5 })
            val authenticated = connection(clock, handler = { request -> TransferResponse.Authenticated(request.requestId, bearer,
                setOf(TransferCapability.READ_LIBRARY, TransferCapability.UPLOAD_AUDIO), TransferLimits.TOKEN_MS) })
            val client = CompanionTransferClient(FakeFactory(listOf(connection(clock), authenticated)), clock)
            try {
                client.pair(endpoint, selected(), "12345678", setOf(TransferCapability.READ_LIBRARY))
                issue(TransferIssue.PERMISSION_DENIED) { client.authenticate(endpoint) }
                assertArrayEquals(ByteArray(32), bearer.wireBytes())
                assertFalse(client.state.value.phase == CompanionConnectionPhase.READY)
            } finally { client.disconnect().await() }
        }
    }

    @Test fun cachedGreetingCannotRenewAnExpiredWatchSession() = runBlocking {
        withTimeout(5000L) {
            val clock = MutableClock()
            val authenticated = connection(clock)
            val client = CompanionTransferClient(FakeFactory(listOf(connection(clock), authenticated)), clock)
            try {
                ready(client)
                clock.now = TransferLimits.SESSION_MS + 1L
                issue(TransferIssue.EXPIRED) { client.readPage(TransferCatalogKind.TRACKS, "", null) }
                assertEquals(1, authenticated.requests.size)
                assertTrue(authenticated.retired)
            } finally { client.disconnect().await() }
        }
    }

    @Test fun freshConnectionGreetingCanReflectAWatchConfirmedExtension() = runBlocking {
        withTimeout(5000L) {
            val clock = MutableClock()
            val old = connection(clock)
            val fresh = connection(clock, observed = TransferLimits.SESSION_MS, remaining = TransferLimits.SESSION_MS)
            val factory = FakeFactory(listOf(connection(clock), old, fresh))
            val client = CompanionTransferClient(factory, clock)
            try {
                ready(client)
                old.close().await()
                clock.now = TransferLimits.SESSION_MS + 1L
                assertEquals(0L, client.readPage(TransferCatalogKind.TRACKS, "", null).totalCount)
                assertEquals(CompanionConnectionPhase.READY, client.state.value.phase)
                assertEquals(3, factory.opens.get())
                assertEquals(2, fresh.requests.size)
                assertTrue(fresh.requests.first() is TransferRequest.Authenticate)
            } finally { client.disconnect().await() }
        }
    }

    @Test fun differentPhonePeerOnReconnectCannotBorrowThePriorGrant() = runBlocking {
        withTimeout(5000L) {
            val clock = MutableClock()
            val old = connection(clock)
            val other = connection(clock, connectionPeer = TransferFingerprint.digest(byteArrayOf(8)))
            val client = CompanionTransferClient(FakeFactory(listOf(connection(clock), old, other)), clock)
            try {
                ready(client)
                old.close().await()
                issue(TransferIssue.WRONG_TARGET) { client.readPage(TransferCatalogKind.TRACKS, "", null) }
                assertTrue(other.requests.isEmpty())
                assertTrue(other.retired)
            } finally { client.disconnect().await() }
        }
    }

    @Test fun writeDisplayOnlyCanReadPreviewAndApplyWithoutLibraryRead() = runBlocking {
        withTimeout(5000L) {
            val clock = MutableClock()
            val patch = TransferDisplayPatch.Rename("New name")
            val authenticated = connection(clock, handler = { request -> when (request) {
                is TransferRequest.Authenticate -> TransferResponse.Authenticated(request.requestId, TransferBearer.fromWire(ByteArray(32) { 9 }), setOf(TransferCapability.WRITE_DISPLAY_METADATA), TransferLimits.TOKEN_MS)
                is TransferRequest.ReadDisplay -> TransferResponse.DisplayState(request.requestId, TransferDisplayState(request.kind, request.entityId, 7L, fence, "", 20001L))
                is TransferRequest.PreviewDisplay -> TransferResponse.DisplayPreview(request.requestId, TransferDisplayPreview(request.requestId, request.kind, request.entityId, 7L, "", "New name", request.patch, fence, 20001L))
                is TransferRequest.ApplyDisplay -> TransferResponse.Completed(request.requestId,
                    TransferReceipt(TransferProtocolCodec.operationBinding(peer, request), TransferReceiptKind.DISPLAY_COMMITTED, 8L, "album:fixture", null), false)
                else -> throw TransferException(TransferIssue.INVALID_INPUT)
            } })
            val client = CompanionTransferClient(FakeFactory(listOf(connection(clock), authenticated)), clock)
            try {
                ready(client, setOf(TransferCapability.WRITE_DISPLAY_METADATA))
                val state = client.readDisplay(TransferDisplayKind.ALBUM, "album:fixture")
                val draft = CompanionDisplayDraft(id(2), target, state.kind, state.entityId, state.revision, state.libraryRevision, state.currentName, state.trackCount, patch)
                val confirmed = draft.withPreview(client.previewDisplay(draft)).withPhase(CompanionEditPhase.SUBMITTING)
                assertEquals(8L, client.applyDisplay(confirmed).revision)
                assertFalse(authenticated.requests.any { it is TransferRequest.ReadPage })
                issue(TransferIssue.PERMISSION_DENIED) { client.readPage(TransferCatalogKind.TRACKS, "", null) }
            } finally { client.disconnect().await() }
        }
    }

    @Test fun createPlaylistOnlyPermissionUsesNoLibraryFenceAndChecksExactResultId() = runBlocking {
        withTimeout(5000L) {
            val clock = MutableClock()
            var wrongEntity = false
            val authenticated = connection(clock, handler = { request -> when (request) {
                is TransferRequest.Authenticate -> TransferResponse.Authenticated(request.requestId, TransferBearer.fromWire(ByteArray(32) { 9 }), setOf(TransferCapability.WRITE_PLAYLISTS), TransferLimits.TOKEN_MS)
                is TransferRequest.EditPlaylist -> {
                    assertEquals(null, request.expectedLibraryRevision)
                    TransferResponse.Completed(request.requestId, TransferReceipt(TransferProtocolCodec.operationBinding(peer, request), TransferReceiptKind.PLAYLIST_COMMITTED,
                        1L, if (wrongEntity) "playlist:other" else request.playlistId, null), false)
                }
                else -> throw TransferException(TransferIssue.INVALID_INPUT)
            } })
            val client = CompanionTransferClient(FakeFactory(listOf(connection(clock), authenticated)), clock)
            try {
                ready(client, setOf(TransferCapability.WRITE_PLAYLISTS))
                val draft = CompanionPlaylistDraft(id(2), target, "playlist:fixture", "Fixture", 0L, null, TransferPlaylistEdit.Create("Fixture"), CompanionEditPhase.SUBMITTING)
                assertEquals("playlist:fixture", client.editPlaylist(draft).resultId)
                wrongEntity = true
                issue(TransferIssue.CONFLICT) { client.editPlaylist(draft) }
            } finally { client.disconnect().await() }
        }
    }

    @Test fun fullMemberViewRejectsSearchBeforeSendingAFilteredOrdinalRequest() = runBlocking {
        withTimeout(5000L) {
            val clock = MutableClock()
            val authenticated = connection(clock)
            val client = CompanionTransferClient(FakeFactory(listOf(connection(clock), authenticated)), clock)
            try {
                ready(client)
                issue(TransferIssue.INVALID_INPUT) { client.readPage(TransferCatalogKind.PLAYLIST_MEMBERS, "Fixture", "playlist:fixture") }
                assertEquals(1, authenticated.requests.size)
            } finally { client.disconnect().await() }
        }
    }

    @Test fun stopWaitsForOperationBodyButDoesNotJoinItsCallerFinally() = runBlocking {
        withTimeout(5000L) {
            val clock = MutableClock()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val callerFinally = CompletableDeferred<Unit>()
            val authenticated = connection(clock, pageHandler = { request ->
                withContext(NonCancellable) { entered.complete(Unit); release.await() }
                TransferCatalogPage(request.kind, id(4), 5L, fence, 0L, 0L, emptyList(), null)
            })
            val client = CompanionTransferClient(FakeFactory(listOf(connection(clock), authenticated)), clock)
            val callers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                ready(client)
                val caller = callers.async {
                    try { client.readPage(TransferCatalogKind.TRACKS, "", null) }
                    finally { withContext(NonCancellable) { client.disconnect().await(); callerFinally.complete(Unit) } }
                }
                entered.await()
                val stopped = client.disconnect()
                assertFalse(stopped.isCompleted)
                issue(TransferIssue.BUSY) { client.authenticate(endpoint) }
                release.complete(Unit)
                stopped.await()
                callerFinally.await()
                try { caller.await(); fail("Expected interrupted caller") } catch (_: CancellationException) { }
                assertEquals(CompanionConnectionPhase.OFF, client.state.value.phase)
            } finally { release.complete(Unit); callers.cancel(); client.disconnect().await() }
        }
    }

    @Test fun stopDeadlineKeepsAnUnfinishedBodyOwnedUntilExplicitRecovery() = runBlocking {
        withTimeout(5000L) {
            val clock = MutableClock()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val authenticated = connection(clock, pageHandler = { request ->
                withContext(NonCancellable) { entered.complete(Unit); release.await() }
                TransferCatalogPage(request.kind, id(4), 5L, fence, 0L, 0L, emptyList(), null)
            })
            val factory = FakeFactory(listOf(connection(clock), authenticated))
            val client = CompanionTransferClient(factory, clock, retirementWaitMs = 100L)
            val callers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            var caller: Deferred<TransferCatalogPage>? = null
            try {
                ready(client)
                caller = callers.async { client.readPage(TransferCatalogKind.TRACKS, "", null) }
                entered.await()
                val stopped = client.disconnect()
                issue(TransferIssue.RECOVERY_REQUIRED) { stopped.await() }
                assertFalse(checkNotNull(caller).isCompleted)
                assertSame(authenticated, factory.current)
                assertSame(stopped, client.disconnect())
                assertEquals(CompanionConnectionPhase.RECOVERY_REQUIRED, client.state.value.phase)
                issue(TransferIssue.BUSY) { client.authenticate(endpoint) }
                issue(TransferIssue.RECOVERY_REQUIRED) { client.retryRetirement() }
                release.complete(Unit)
                try { checkNotNull(caller).await(); fail("Expected interrupted owned body") } catch (_: CancellationException) { }
                assertEquals(CompanionConnectionPhase.RECOVERY_REQUIRED, client.state.value.phase)
                client.retryRetirement()
                assertEquals(CompanionConnectionPhase.OFF, client.state.value.phase)
                assertEquals(null, factory.current)
            } finally {
                release.complete(Unit); callers.cancel(); caller?.join()
                try { client.disconnect().await() } catch (_: TransferException) { client.retryRetirement() }
            }
        }
    }

    @Test fun retirementRetryDeadlineRetainsTheDetachedPhysicalCloser() = runBlocking {
        withTimeout(5000L) {
            val clock = MutableClock()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val authenticated = connection(clock, pageHandler = { throw TransferException(TransferIssue.INTEGRITY_FAILED) })
            val factory = FakeFactory(listOf(connection(clock), authenticated))
            val client = CompanionTransferClient(factory, clock, retirementWaitMs = 100L)
            val closers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val physical = closers.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                withContext(NonCancellable) { entered.complete(Unit); release.await() }
                authenticated.retryRetirement()
            }
            factory.beforeRetry = { physical.start(); physical.await() }
            try {
                ready(client)
                authenticated.failClose = true
                issue(TransferIssue.INTEGRITY_FAILED) { client.readPage(TransferCatalogKind.TRACKS, "", null) }
                issue(TransferIssue.RECOVERY_REQUIRED) { client.retryRetirement() }
                entered.await()
                assertFalse(physical.isCompleted)
                assertFalse(authenticated.retired)
                assertSame(authenticated, factory.current)
                assertEquals(CompanionConnectionPhase.RECOVERY_REQUIRED, client.state.value.phase)
                issue(TransferIssue.BUSY) { client.authenticate(endpoint) }
                issue(TransferIssue.RECOVERY_REQUIRED) { client.retryRetirement() }
                assertFalse(physical.isCompleted)
                assertSame(authenticated, factory.current)
                release.complete(Unit); physical.await()
                assertTrue(authenticated.retired)
                assertEquals(CompanionConnectionPhase.RECOVERY_REQUIRED, client.state.value.phase)
                client.retryRetirement()
                assertEquals(CompanionConnectionPhase.OFF, client.state.value.phase)
                assertEquals(null, factory.current)
            } finally {
                release.complete(Unit); physical.start(); physical.join(); closers.cancel()
                try { client.disconnect().await() } catch (_: TransferException) { client.retryRetirement() }
            }
        }
    }

    @Test fun aNewExplicitRecoveryCanConfirmAfterItsReusedOlderAttemptFailsLate() = runBlocking {
        withTimeout(5000L) {
            val clock = MutableClock()
            val authenticated = connection(clock)
            val factory = FakeFactory(listOf(connection(clock), authenticated))
            val client = CompanionTransferClient(factory, clock, retirementWaitMs = 100L)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val attempts = AtomicInteger()
            val callers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            var confirmation: Deferred<Unit>? = null
            factory.beforeRetry = {
                if (attempts.incrementAndGet() == 1) {
                    withContext(NonCancellable) { entered.complete(Unit); release.await() }
                    throw TransferException(TransferIssue.RECOVERY_REQUIRED)
                }
            }
            try {
                ready(client)
                authenticated.failClose = true
                val originalControl = client.disconnect()
                issue(TransferIssue.RECOVERY_REQUIRED) { originalControl.await() }
                issue(TransferIssue.RECOVERY_REQUIRED) { client.retryRetirement() }
                entered.await()
                assertEquals(1, attempts.get())
                assertFalse(authenticated.retired)
                assertSame(authenticated, factory.current)
                // UNDISPATCHED 先把本次明确请求停在旧控制任务的 await，再释放迟到失败。
                confirmation = callers.async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    client.retryRetirement()
                }
                assertFalse(checkNotNull(confirmation).isCompleted)
                authenticated.retryRetirement()
                assertTrue(authenticated.retired)
                assertEquals(CompanionConnectionPhase.RECOVERY_REQUIRED, client.state.value.phase)
                assertSame(originalControl, client.disconnect())
                release.complete(Unit)
                checkNotNull(confirmation).await()
                assertEquals(2, attempts.get())
                assertEquals(null, factory.current)
                assertEquals(CompanionConnectionPhase.OFF, client.state.value.phase)
                issue(TransferIssue.RECOVERY_REQUIRED) { originalControl.await() }
            } finally {
                release.complete(Unit)
                callers.cancel()
                confirmation?.join()
                try { client.disconnect().await() } catch (_: TransferException) { client.retryRetirement() }
            }
        }
    }

    @Test fun aFailureOfTheCurrentRecoveryNeedsAnotherExplicitRequest() = runBlocking {
        withTimeout(5000L) {
            val clock = MutableClock()
            val authenticated = connection(clock)
            val factory = FakeFactory(listOf(connection(clock), authenticated))
            val client = CompanionTransferClient(factory, clock, retirementWaitMs = 100L)
            val attempts = AtomicInteger()
            factory.beforeRetry = { attempts.incrementAndGet() }
            try {
                ready(client)
                authenticated.failClose = true
                authenticated.failRetry = true
                val originalControl = client.disconnect()
                issue(TransferIssue.RECOVERY_REQUIRED) { originalControl.await() }
                issue(TransferIssue.RECOVERY_REQUIRED) { client.retryRetirement() }
                assertEquals(1, attempts.get())
                assertSame(authenticated, factory.current)
                assertFalse(authenticated.retired)
                assertEquals(CompanionConnectionPhase.RECOVERY_REQUIRED, client.state.value.phase)
                authenticated.failRetry = false
                client.retryRetirement()
                assertEquals(2, attempts.get())
                assertEquals(null, factory.current)
                assertEquals(CompanionConnectionPhase.OFF, client.state.value.phase)
                issue(TransferIssue.RECOVERY_REQUIRED) { originalControl.await() }
            } finally {
                authenticated.failRetry = false
                try { client.disconnect().await() } catch (_: TransferException) { client.retryRetirement() }
            }
        }
    }

    @Test fun lateOpenFailedCloseKeepsRecoveryAndOriginalOwnerUntilRetry() = runBlocking {
        withTimeout(5000L) {
            val clock = MutableClock()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val late = connection(clock).also { it.failClose = true }
            val factory = FakeFactory(listOf(late))
            factory.beforeOpen = { withContext(NonCancellable) { entered.complete(Unit); release.await() } }
            val client = CompanionTransferClient(factory, clock)
            val callers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val caller = callers.async { client.pair(endpoint, selected(), "12345678", setOf(TransferCapability.READ_LIBRARY)) }
                entered.await()
                val stopped = client.disconnect()
                assertFalse(stopped.isCompleted)
                assertEquals(null, factory.current)
                release.complete(Unit)
                issue(TransferIssue.RECOVERY_REQUIRED) { stopped.await() }
                try { caller.await(); fail("Expected interrupted late open") } catch (_: CancellationException) { }
                assertEquals(CompanionConnectionPhase.RECOVERY_REQUIRED, client.state.value.phase)
                assertSame(late, factory.current)
                assertFalse(late.retired)
                issue(TransferIssue.BUSY) { client.pair(endpoint, selected(), "12345678", setOf(TransferCapability.READ_LIBRARY)) }
                late.failRetry = true
                issue(TransferIssue.RECOVERY_REQUIRED) { client.retryRetirement() }
                assertSame(late, factory.current)
                late.failRetry = false
                client.retryRetirement()
                assertTrue(late.retired)
                assertEquals(CompanionConnectionPhase.OFF, client.state.value.phase)
            } finally {
                release.complete(Unit); callers.cancel(); late.failRetry = false
                try { client.disconnect().await() } catch (_: TransferException) { client.retryRetirement() }
            }
        }
    }

    @Test fun primaryProtocolFailureKeepsRecoveryAsSuppressedAndRejectsNewAdmission() = runBlocking {
        withTimeout(5000L) {
            val clock = MutableClock()
            val authenticated = connection(clock, pageHandler = { throw TransferException(TransferIssue.INTEGRITY_FAILED) })
            val factory = FakeFactory(listOf(connection(clock), authenticated))
            val client = CompanionTransferClient(factory, clock)
            try {
                ready(client)
                authenticated.failClose = true
                try { client.readPage(TransferCatalogKind.TRACKS, "", null); fail("Expected integrity failure") }
                catch (error: TransferException) {
                    assertEquals(TransferIssue.INTEGRITY_FAILED, error.issue)
                    assertTrue(error.suppressed.any { it is TransferException && it.issue == TransferIssue.RECOVERY_REQUIRED })
                }
                assertEquals(CompanionConnectionPhase.RECOVERY_REQUIRED, client.state.value.phase)
                issue(TransferIssue.BUSY) { client.readPage(TransferCatalogKind.TRACKS, "", null) }
                client.retryRetirement()
                assertEquals(CompanionConnectionPhase.OFF, client.state.value.phase)
            } finally { try { client.disconnect().await() } catch (_: TransferException) { client.retryRetirement() } }
        }
    }

    @Test fun lostFinishResponseReadsOriginalReceiptOnExactRetryWithoutResendingChunks() = runBlocking {
        withTimeout(5000L) {
            val clock = MutableClock()
            val content = byteArrayOf(1, 2, 3, 4)
            val file = temporary.newFile("fixture.wav").also { it.writeBytes(content) }
            val draft = CompanionUploadDraft(id(2), target, id(3), TransferUploadDeclaration(TransferUploadKind.AUDIO, "fixture.wav", "audio/wav", 4L, TransferHash.digest(content), 0L), CompanionUploadPhase.SENDING)
            var binding: TransferOperationBinding? = null
            var committed: TransferReceipt? = null
            val chunks = AtomicInteger()
            val finishes = AtomicInteger()
            val ticket = id(8)
            val first = connection(clock, handler = { request -> when (request) {
                is TransferRequest.Authenticate -> TransferResponse.Authenticated(request.requestId, TransferBearer.fromWire(ByteArray(32) { 9 }), setOf(TransferCapability.UPLOAD_AUDIO), TransferLimits.TOKEN_MS)
                is TransferRequest.BeginUpload -> { binding = TransferProtocolCodec.operationBinding(peer, request); TransferResponse.UploadReady(request.requestId, ticket) }
                is TransferRequest.UploadChunk -> { chunks.incrementAndGet(); TransferResponse.ChunkAccepted(request.requestId, ticket, request.offset + request.bytes.size) }
                is TransferRequest.FinishUpload -> {
                    finishes.incrementAndGet()
                    committed = TransferReceipt(checkNotNull(binding), TransferReceiptKind.AUDIO_COMMITTED, 8L, "track:fixture", 4L)
                    throw TransferException(TransferIssue.TRANSPORT_FAILED)
                }
                else -> throw TransferException(TransferIssue.INVALID_INPUT)
            } })
            val retry = connection(clock, handler = { request -> when (request) {
                is TransferRequest.Authenticate -> TransferResponse.Authenticated(request.requestId, TransferBearer.fromWire(ByteArray(32) { 4 }), setOf(TransferCapability.UPLOAD_AUDIO), TransferLimits.TOKEN_MS)
                is TransferRequest.BeginUpload -> TransferResponse.Completed(request.requestId, checkNotNull(committed), true)
                else -> throw TransferException(TransferIssue.CONFLICT)
            } })
            val client = CompanionTransferClient(FakeFactory(listOf(connection(clock), first, retry)), clock)
            try {
                ready(client, setOf(TransferCapability.UPLOAD_AUDIO))
                issue(TransferIssue.TRANSPORT_FAILED) { client.upload(draft, { file }, {}) }
                client.authenticate(endpoint)
                val receipt = client.upload(draft.withResult(CompanionUploadPhase.UNCERTAIN), { file }, {})
                assertSame(committed, receipt)
                assertEquals(1, chunks.get())
                assertEquals(1, finishes.get())
                assertEquals(2, retry.requests.size)
                assertTrue(checkNotNull(binding).sameOperation(receipt.binding))
            } finally { client.disconnect().await() }
        }
    }

    @Test(timeout = 15000L)
    fun fullMemberCatalogStreams20001OrdinalsAndPreparesGlobalBoundaryMoves() = runBlocking {
        withTimeout(10000L) {
            val clock = MutableClock()
            val total = 20001L
            val revision = 11L
            val playlistId = "playlist:folder"
            val pageCalls = AtomicInteger()
            val authenticated = connection(clock, handler = { request ->
                when (request) {
                    is TransferRequest.Authenticate -> TransferResponse.Authenticated(request.requestId, TransferBearer.fromWire(ByteArray(32) { 9 }),
                        setOf(TransferCapability.READ_LIBRARY, TransferCapability.WRITE_PLAYLISTS), TransferLimits.TOKEN_MS)
                    else -> throw TransferException(TransferIssue.INVALID_INPUT)
                }
            }, pageHandler = { request ->
                pageCalls.incrementAndGet()
                assertEquals(TransferCatalogKind.PLAYLIST_MEMBERS, request.kind)
                assertEquals(playlistId, request.parentId)
                assertEquals("", request.search)
                val first = request.cursor?.removePrefix("cursor_")?.toLong() ?: 0L
                if (first == 0L) {
                    assertEquals(null, request.viewId)
                    assertEquals(null, request.expectedRevision)
                    assertEquals(null, request.expectedLibraryRevision)
                } else {
                    assertEquals(id(4), request.viewId)
                    assertEquals(revision, request.expectedRevision)
                    assertTrue(checkNotNull(request.expectedLibraryRevision).sameAs(fence))
                }
                val count = minOf(TransferLimits.PAGE_SIZE.toLong(), total - first).toInt()
                val rows = List(count) { offset ->
                    val ordinal = first + offset
                    TransferCatalogRow.Member("member:$ordinal", ordinal, "track:$ordinal", "Member $ordinal", "", ordinal % 31L != 0L)
                }
                val next = first + count
                val page = TransferCatalogPage(request.kind, id(4), revision, fence, total, first, rows, if (next < total) "cursor_$next" else null)
                // 每页经完整共享协议帧/校验和消费者；不构造包含两万成员的整页或整库缓冲。
                val bytes = ByteArrayOutputStream()
                TransferProtocolCodec.writeResponse(bytes, TransferResponse.Page(request.requestId, page))
                val streamed = ArrayList<TransferCatalogRow>(TransferLimits.PAGE_SIZE)
                val complete = TransferProtocolCodec.readPage(ByteArrayInputStream(bytes.toByteArray()), request.requestId) { row ->
                    assertEquals((row.row as TransferCatalogRow.Member).ordinal, row.ordinal)
                    streamed += row.row
                }
                val begin = complete.begin
                TransferCatalogPage(begin.kind, begin.viewId, begin.revision, begin.libraryRevision, begin.totalCount, begin.firstOrdinal, streamed, begin.nextCursor)
            })
            val client = CompanionTransferClient(FakeFactory(listOf(connection(clock), authenticated)), clock)
            try {
                ready(client, setOf(TransferCapability.READ_LIBRARY, TransferCapability.WRITE_PLAYLISTS))
                var previous: TransferCatalogPage? = null
                var firstPage: TransferCatalogPage? = null
                var window = CompanionPageWindow(TransferCatalogKind.PLAYLIST_MEMBERS, "", playlistId, emptyList(), -1)
                var ordinal = 0L
                do {
                    val page = client.readPage(TransferCatalogKind.PLAYLIST_MEMBERS, "", playlistId, previous)
                    if (firstPage == null) firstPage = page
                    assertEquals(ordinal, page.firstOrdinal)
                    assertEquals(total, page.totalCount)
                    for (item in page.rows) {
                        val member = item as TransferCatalogRow.Member
                        assertEquals(ordinal, member.ordinal)
                        assertEquals("member:$ordinal", member.memberId)
                        ordinal++
                    }
                    window = window.append(CompanionCachedPage(target, id(1), page, 1L))
                    assertTrue(window.pages.size <= CompanionLimits.PAGE_WINDOW)
                    previous = page
                } while (checkNotNull(previous).nextCursor != null)
                assertEquals(total, ordinal)
                assertEquals(201, pageCalls.get())
                assertEquals(3, window.pages.size)
                assertEquals(19800L, window.pages.first().page.firstOrdinal)
                val lastPage = checkNotNull(window.current).page
                assertEquals(20000L, lastPage.firstOrdinal)
                assertEquals(20000L, (lastPage.rows.single() as TransferCatalogRow.Member).ordinal)
                assertEquals(null, lastPage.nextCursor)

                val playlist = TransferCatalogRow.Playlist(playlistId, "Folder", true, revision, total)
                fun selected(page: TransferCatalogPage): CompanionUiState = CompanionUiState(connection = client.state.value,
                    playlist = CompanionPlaylistSelection(target, playlist, fence, id(1)),
                    pageWindow = CompanionPageWindow(TransferCatalogKind.PLAYLIST_MEMBERS, "", playlistId, listOf(CompanionCachedPage(target, id(1), page, 1L)), 0))
                val moves = listOf(
                    prepareCompanionPlaylistDraft(selected(checkNotNull(firstPage)), TransferPlaylistEdit.Move("member:0", total - 1L), id(30)),
                    prepareCompanionPlaylistDraft(selected(lastPage), TransferPlaylistEdit.Move("member:20000", 0L), id(31)),
                )
                for (draft in moves) {
                    assertEquals(revision, draft.expectedRevision)
                    assertTrue(checkNotNull(draft.libraryRevision).sameAs(fence))
                    val edit = draft.edit as TransferPlaylistEdit.Move
                    val bytes = ByteArrayOutputStream()
                    val request = TransferRequest.EditPlaylist(draft.operationId, TransferScope(target, id(1), TransferBearer.fromWire(ByteArray(32) { 9 })),
                        playlistId, draft.expectedRevision, draft.libraryRevision, edit)
                    TransferProtocolCodec.writeRequest(bytes, request)
                    val decoded = TransferProtocolCodec.readRequest(ByteArrayInputStream(bytes.toByteArray())) as TransferRequest.EditPlaylist
                    assertEquals(edit.memberId, (decoded.edit as TransferPlaylistEdit.Move).memberId)
                    assertEquals(edit.destinationOrdinal, (decoded.edit as TransferPlaylistEdit.Move).destinationOrdinal)
                    assertEquals(revision, decoded.expectedRevision)
                    assertTrue(checkNotNull(decoded.expectedLibraryRevision).sameAs(fence))
                }
                // 实际 receiver 的持久调序由独立 Room 正常用例验收，此处只定义流式读取与准确请求。
            } finally { client.disconnect().await() }
        }
    }

    @Test fun authoritativeProtectedRenameAndDeleteDenialsKeepTheOriginalDraft() = runBlocking {
        withTimeout(5000L) {
            for (edit in listOf(TransferPlaylistEdit.Rename("Protected candidate"), TransferPlaylistEdit.Delete(confirmed = true))) {
                val clock = MutableClock()
                val authenticated = connection(clock, handler = { request ->
                    when (request) {
                        is TransferRequest.Authenticate -> TransferResponse.Authenticated(request.requestId, TransferBearer.fromWire(ByteArray(32) { 9 }),
                            setOf(TransferCapability.WRITE_PLAYLISTS), TransferLimits.TOKEN_MS)
                        is TransferRequest.EditPlaylist -> TransferResponse.Failure(request.requestId, TransferIssue.PERMISSION_DENIED)
                        else -> throw TransferException(TransferIssue.INVALID_INPUT)
                    }
                })
                val client = CompanionTransferClient(FakeFactory(listOf(connection(clock), authenticated)), clock)
                try {
                    ready(client, setOf(TransferCapability.WRITE_PLAYLISTS))
                    val draft = CompanionPlaylistDraft(id(2), target, "playlist:protected", "Protected", 11L, null, edit, CompanionEditPhase.SUBMITTING)
                    issue(TransferIssue.PERMISSION_DENIED) { client.editPlaylist(draft) }
                    assertSame(edit, draft.edit)
                    assertEquals(id(2), draft.operationId)
                    assertEquals(null, draft.receipt)
                    assertEquals(TransferIssue.PERMISSION_DENIED, client.state.value.issue)
                    assertTrue(authenticated.requests.last() is TransferRequest.EditPlaylist)
                } finally { client.disconnect().await() }
            }
        }
    }

    @Test fun connectionOpenRateIsBoundedAcrossForegroundRetries() = runBlocking {
        withTimeout(5000L) {
            val clock = MutableClock()
            val factory = FakeFactory(List(TransferLimits.ACCEPTS_PER_MINUTE + 1) { connection(clock) })
            val client = CompanionTransferClient(factory, clock)
            try {
                repeat(TransferLimits.ACCEPTS_PER_MINUTE) { client.pair(endpoint, selected(), "12345678", setOf(TransferCapability.READ_LIBRARY)) }
                issue(TransferIssue.RATE_LIMITED) { client.pair(endpoint, selected(), "12345678", setOf(TransferCapability.READ_LIBRARY)) }
                assertEquals(TransferLimits.ACCEPTS_PER_MINUTE, factory.opens.get())
                clock.now = 60000L
                client.pair(endpoint, selected(), "12345678", setOf(TransferCapability.READ_LIBRARY))
                assertEquals(TransferLimits.ACCEPTS_PER_MINUTE + 1, factory.opens.get())
            } finally { client.disconnect().await() }
        }
    }

    private suspend fun issue(expected: TransferIssue, action: suspend () -> Unit) {
        try { action(); fail("Expected a transfer issue") } catch (error: TransferException) { assertEquals(expected, error.issue) }
    }
}
