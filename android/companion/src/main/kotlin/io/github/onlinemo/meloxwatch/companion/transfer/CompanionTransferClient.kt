package io.github.onlinemo.meloxwatch.companion.transfer

import io.github.onlinemo.meloxwatch.companion.data.CompanionClock
import io.github.onlinemo.meloxwatch.companion.data.CompanionConnectionPhase
import io.github.onlinemo.meloxwatch.companion.data.CompanionConnectionState
import io.github.onlinemo.meloxwatch.companion.data.CompanionDisplayDraft
import io.github.onlinemo.meloxwatch.companion.data.CompanionEditPhase
import io.github.onlinemo.meloxwatch.companion.data.CompanionEndpoint
import io.github.onlinemo.meloxwatch.companion.data.CompanionLimits
import io.github.onlinemo.meloxwatch.companion.data.CompanionPlaylistDraft
import io.github.onlinemo.meloxwatch.companion.data.CompanionTarget
import io.github.onlinemo.meloxwatch.companion.data.CompanionUploadDraft
import io.github.onlinemo.meloxwatch.companion.data.CompanionUploadPhase
import io.github.onlinemo.meloxwatch.companion.data.CompanionUploadProgress
import io.github.onlinemo.meloxwatch.data.transfer.TransferBearer
import io.github.onlinemo.meloxwatch.data.transfer.TransferCapability
import io.github.onlinemo.meloxwatch.data.transfer.TransferCatalogKind
import io.github.onlinemo.meloxwatch.data.transfer.TransferCatalogPage
import io.github.onlinemo.meloxwatch.data.transfer.TransferDisplayKind
import io.github.onlinemo.meloxwatch.data.transfer.TransferDisplayPreview
import io.github.onlinemo.meloxwatch.data.transfer.TransferDisplayState
import io.github.onlinemo.meloxwatch.data.transfer.TransferException
import io.github.onlinemo.meloxwatch.data.transfer.TransferFingerprint
import io.github.onlinemo.meloxwatch.data.transfer.TransferHash
import io.github.onlinemo.meloxwatch.data.transfer.TransferIssue
import io.github.onlinemo.meloxwatch.data.transfer.TransferLimits
import io.github.onlinemo.meloxwatch.data.transfer.TransferProtocolCodec
import io.github.onlinemo.meloxwatch.data.transfer.TransferReceipt
import io.github.onlinemo.meloxwatch.data.transfer.TransferRequest
import io.github.onlinemo.meloxwatch.data.transfer.TransferResponse
import io.github.onlinemo.meloxwatch.data.transfer.TransferScope
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * 业务只接纳一个任务，不排队，凭据只留内存。
 * 前台明确请求可续短期授权，设备会话仍由手表决定。
 * 断连先使旧代次失效；有界控制等待返回恢复状态时仍保留真实任务，
 * 取消的提交保持结果不确定，重试必须使用同一操作身份。
 */
internal class CompanionTransferClient(
    private val factory: CompanionWireFactory,
    private val clock: CompanionClock = CompanionClock { System.nanoTime() / 1_000_000L },
    private val retirementWaitMs: Long = CompanionLimits.RETIREMENT_WAIT_MS,
) {
    init { require(retirementWaitMs > 0L) }
    private val lock = Any()
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var epoch = 0L
    private var admitted: Admission? = null
    private var wire: CompanionWireConnection? = null
    private var endpoint: CompanionEndpoint? = null
    private var pending: Pending? = null
    private var session: Session? = null
    private var stopping: CompletableDeferred<Unit>? = null
    private var retryingRetirement = false
    private var retirementWork: Deferred<Unit>? = null
    private val connectionTimes = ArrayDeque<Long>()
    private val mutableState = MutableStateFlow(CompanionConnectionState())
    val state: StateFlow<CompanionConnectionState> = mutableState.asStateFlow()

    fun clearIssue() = synchronized(lock) {
        val old = mutableState.value
        if (old.phase != CompanionConnectionPhase.RECOVERY_REQUIRED) publish(old.phase, old.target, old.sessionId, old.capabilities)
    }

    private class Admission(val owner: Job, val complete: CompletableDeferred<Unit> = CompletableDeferred())
    private class Pending(val target: CompanionTarget, val sessionId: String, val requested: Set<TransferCapability>, val peer: TransferFingerprint)
    private class Session(
        val pending: Pending, val capabilities: Set<TransferCapability>, var bearer: TransferBearer,
        var tokenDeadline: Long, var sessionDeadline: Long, var deadlineConnection: CompanionWireConnection,
    )

    suspend fun pair(address: CompanionEndpoint, target: CompanionTarget, code: String, requested: Set<TransferCapability>) = operation { ticket ->
        require(requested.isNotEmpty())
        synchronized(lock) {
            checkEpoch(ticket)
            session?.bearer?.erase()
            session = null
            pending = null
            endpoint = address
            publish(CompanionConnectionPhase.CONNECTING, target)
        }
        closeWire()
        val connection = open(target.fingerprint, expectedSession = null, ticket)
        checkEpoch(ticket)
        val greeting = CompanionResponseValidator.greeting(connection.greeting, target.fingerprint, null)
        synchronized(lock) {
            checkEpoch(ticket)
            publish(CompanionConnectionPhase.PAIRING, target, greeting.sessionId)
        }
        val request = TransferRequest.Pair(id(), target.fingerprint, greeting.sessionId, code, requested)
        val response = CompanionResponseValidator.response(request.requestId, connection.exchange(request))
        if (response !is TransferResponse.PairPending) throw TransferException(TransferIssue.INVALID_INPUT)
        checkEpoch(ticket)
        // 等用户在手表核对手机指纹期间释放连接，不进行轮询。
        closeWire()
        checkEpoch(ticket)
        synchronized(lock) {
            checkEpoch(ticket)
            pending = Pending(target, greeting.sessionId, requested.toSet(), connection.peer)
            publish(CompanionConnectionPhase.WAITING_FOR_WATCH, target, greeting.sessionId)
        }
    }

    suspend fun authenticate(address: CompanionEndpoint): CompanionTarget = operation { ticket ->
        val selected = synchronized(lock) { pending } ?: throw TransferException(TransferIssue.NOT_APPROVED)
        synchronized(lock) {
            checkEpoch(ticket)
            endpoint = address
            publish(CompanionConnectionPhase.AUTHENTICATING, selected.target, selected.sessionId)
        }
        val connection = connection(selected, ticket)
        val authentication = authenticateOn(connection, selected)
        var adopted = false
        try {
            synchronized(lock) {
                checkEpoch(ticket)
                session?.bearer?.erase()
                session = authentication
                adopted = true
                publish(CompanionConnectionPhase.READY, selected.target, selected.sessionId, authentication.capabilities)
            }
        } finally { if (!adopted) authentication.bearer.erase() }
        selected.target
    }

    suspend fun readPage(kind: TransferCatalogKind, search: String, parentId: String?, previous: TransferCatalogPage? = null): TransferCatalogPage = operation { ticket ->
        if (previous != null && previous.nextCursor == null) throw TransferException(TransferIssue.INVALID_INPUT)
        // 移动使用完整歌单的绝对位置，因此成员视图不能过滤。
        if (kind == TransferCatalogKind.PLAYLIST_MEMBERS && search.isNotEmpty()) throw TransferException(TransferIssue.INVALID_INPUT)
        val selected = requireSession(TransferCapability.READ_LIBRARY)
        val connection = connection(selected.pending, ticket)
        val scope = scope(connection, selected, TransferCapability.READ_LIBRARY, ticket)
        val request = TransferRequest.ReadPage(id(), scope, kind, previous?.viewId, previous?.revision, previous?.libraryRevision, previous?.nextCursor, parentId, search, TransferLimits.PAGE_SIZE)
        val page = connection.page(request)
        checkEpoch(ticket)
        CompanionResponseValidator.page(request, page, previous?.let { it.firstOrdinal + it.rows.size } ?: 0L, previous?.totalCount)
    }

    suspend fun editPlaylist(draft: CompanionPlaylistDraft): TransferReceipt = operation { ticket ->
        if (draft.phase !in setOf(CompanionEditPhase.SUBMITTING, CompanionEditPhase.UNCERTAIN)) throw TransferException(TransferIssue.INVALID_INPUT)
        val selected = requireSession(TransferCapability.WRITE_PLAYLISTS)
        if (selected.pending.target.fingerprint != draft.target) throw TransferException(TransferIssue.WRONG_TARGET)
        val connection = connection(selected.pending, ticket)
        val request = TransferRequest.EditPlaylist(draft.operationId, scope(connection, selected, TransferCapability.WRITE_PLAYLISTS, ticket), draft.playlistId, draft.expectedRevision, draft.libraryRevision, draft.edit)
        val binding = TransferProtocolCodec.operationBinding(connection.peer, request)
        val result = CompanionResponseValidator.completed(request.requestId, binding, connection.exchange(request))
        checkEpoch(ticket)
        if (result.resultId != draft.playlistId) throw TransferException(TransferIssue.CONFLICT)
        result
    }

    suspend fun readDisplay(kind: TransferDisplayKind, entityId: String): TransferDisplayState = operation { ticket ->
        val selected = requireSession(TransferCapability.WRITE_DISPLAY_METADATA)
        val connection = connection(selected.pending, ticket)
        val request = TransferRequest.ReadDisplay(id(), scope(connection, selected, TransferCapability.WRITE_DISPLAY_METADATA, ticket), kind, entityId)
        val result = CompanionResponseValidator.displayState(request, connection.exchange(request)).state
        checkEpoch(ticket)
        result
    }

    suspend fun previewDisplay(draft: CompanionDisplayDraft): TransferDisplayPreview = operation { ticket ->
        if (draft.phase != CompanionEditPhase.DRAFT) throw TransferException(TransferIssue.CONFLICT)
        val selected = requireSession(TransferCapability.WRITE_DISPLAY_METADATA)
        if (selected.pending.target.fingerprint != draft.target) throw TransferException(TransferIssue.WRONG_TARGET)
        val connection = connection(selected.pending, ticket)
        val request = TransferRequest.PreviewDisplay(draft.operationId, scope(connection, selected, TransferCapability.WRITE_DISPLAY_METADATA, ticket), draft.kind, draft.entityId, draft.expectedRevision, draft.libraryRevision, draft.patch)
        val result = CompanionResponseValidator.preview(request, connection.exchange(request)).preview
        checkEpoch(ticket)
        result
    }

    suspend fun applyDisplay(draft: CompanionDisplayDraft): TransferReceipt = operation { ticket ->
        if (draft.phase !in setOf(CompanionEditPhase.SUBMITTING, CompanionEditPhase.UNCERTAIN)) throw TransferException(TransferIssue.INVALID_INPUT)
        val selected = requireSession(TransferCapability.WRITE_DISPLAY_METADATA)
        if (selected.pending.target.fingerprint != draft.target) throw TransferException(TransferIssue.WRONG_TARGET)
        val connection = connection(selected.pending, ticket)
        val request = TransferRequest.ApplyDisplay(draft.operationId, scope(connection, selected, TransferCapability.WRITE_DISPLAY_METADATA, ticket),
            draft.previewId ?: throw TransferException(TransferIssue.CONFLICT), draft.expectedRevision, confirmed = true)
        val binding = TransferProtocolCodec.operationBinding(connection.peer, request)
        val result = CompanionResponseValidator.completed(request.requestId, binding, connection.exchange(request))
        checkEpoch(ticket)
        if (result.resultId != draft.entityId) throw TransferException(TransferIssue.CONFLICT)
        result
    }

    suspend fun upload(draft: CompanionUploadDraft, verifiedFile: suspend () -> File, progress: (CompanionUploadProgress) -> Unit): TransferReceipt = operation { ticket ->
        if (draft.phase !in setOf(CompanionUploadPhase.SENDING, CompanionUploadPhase.UNCERTAIN)) throw TransferException(TransferIssue.INVALID_INPUT)
        val capability = draft.declaration.kind.capability
        val selected = requireSession(capability)
        if (draft.target == null || draft.target != selected.pending.target.fingerprint) throw TransferException(TransferIssue.WRONG_TARGET)
        val size = checkNotNull(draft.declaration.declaredBytes)
        val file = verifiedFile()
        checkEpoch(ticket)
        val connection = connection(selected.pending, ticket)
        val request = TransferRequest.BeginUpload(draft.operationId, scope(connection, selected, capability, ticket), draft.declaration)
        val binding = TransferProtocolCodec.operationBinding(connection.peer, request)
        val started = CompanionResponseValidator.response(request.requestId, connection.exchange(request))
        if (started is TransferResponse.Completed) {
            val replay = CompanionResponseValidator.completed(request.requestId, binding, started, size)
            checkEpoch(ticket)
            return@operation replay
        }
        val ready = started as? TransferResponse.UploadReady ?: throw TransferException(TransferIssue.INVALID_INPUT)
        var sent = 0L
        var lastChunk = Long.MIN_VALUE
        var lastProgress = Long.MIN_VALUE
        val hash = MessageDigest.getInstance("SHA-256")
        withContext(Dispatchers.IO.limitedParallelism(1)) {
            file.inputStream().use { input ->
                val buffer = ByteArray(TransferLimits.CHUNK_BYTES)
                while (sent < size) {
                    coroutineContext.ensureActive()
                    checkEpoch(ticket)
                    val expected = minOf(buffer.size.toLong(), size - sent).toInt()
                    var filled = 0
                    while (filled < expected) {
                        val read = input.read(buffer, filled, expected - filled)
                        if (read <= 0) throw TransferException(TransferIssue.INTEGRITY_FAILED)
                        filled += read
                    }
                    if (lastChunk != Long.MIN_VALUE) delay((34L - (clock.monotonicMs() - lastChunk)).coerceAtLeast(0L))
                    val chunk = TransferRequest.UploadChunk(id(), scope(connection, selected, capability, ticket), ready.ticket, sent, buffer.copyOf(filled))
                    lastChunk = clock.monotonicMs()
                    val response = CompanionResponseValidator.response(chunk.requestId, connection.exchange(chunk)) as? TransferResponse.ChunkAccepted ?: throw TransferException(TransferIssue.INVALID_INPUT)
                    if (response.ticket != ready.ticket || response.nextOffset != sent + filled) throw TransferException(TransferIssue.INTEGRITY_FAILED)
                    hash.update(buffer, 0, filled)
                    sent += filled
                    checkEpoch(ticket)
                    val observed = clock.monotonicMs()
                    if (lastProgress == Long.MIN_VALUE || observed - lastProgress >= 100L || sent == size) {
                        progress(CompanionUploadProgress(CompanionUploadPhase.SENDING, sent, size))
                        lastProgress = observed
                    }
                }
                if (input.read() != -1 || TransferHash.fromWire(hash.digest()) != draft.declaration.sha256) throw TransferException(TransferIssue.INTEGRITY_FAILED)
            }
        }
        checkEpoch(ticket)
        progress(CompanionUploadProgress(CompanionUploadPhase.VERIFYING, sent, size))
        val finish = TransferRequest.FinishUpload(id(), scope(connection, selected, capability, ticket), ready.ticket)
        val receipt = CompanionResponseValidator.completed(finish.requestId, binding, connection.exchange(finish), size)
        checkEpoch(ticket)
        receipt
    }

    fun disconnect(forget: Boolean = false): Deferred<Unit> {
        val result: CompletableDeferred<Unit>
        val work: Admission?
        synchronized(lock) {
            if (forget) pending = null
            stopping?.let { return it }
            epoch = Math.addExact(epoch, 1L)
            session?.bearer?.erase()
            session = null
            endpoint = null
            work = admitted
            result = CompletableDeferred()
            stopping = result
            retirementWork = null
            publish(CompanionConnectionPhase.STOPPING, pending?.target, pending?.sessionId)
        }
        work?.owner?.cancel(CancellationException("Companion disconnected"))
        val closed = try { factory.stop() } catch (_: Exception) { CompletableDeferred<Unit>().also { it.completeExceptionally(TransferException(TransferIssue.RECOVERY_REQUIRED)) } }
        closeScope.launch(NonCancellable) {
            var failed = false
            try {
                withTimeout(retirementWaitMs) {
                    try { closed.await() }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) { failed = true }
                    // 已知必要关闭失败便返回恢复；原任务仍保留，不能等待它掩盖失败。
                    if (!failed) work?.complete?.await()
                }
            } catch (_: Exception) { failed = true }
            // 空工厂快照之后仍可能到达旧 open 结果，关闭失败必须继续可见。
            try { if (factory.retirementRequired) failed = true } catch (_: Exception) { failed = true }
            synchronized(lock) { if (wire?.retired == false) failed = true }
            if (!failed) {
                synchronized(lock) {
                    wire = null
                    admitted = null
                    stopping = null
                    publish(CompanionConnectionPhase.OFF, pending?.target, pending?.sessionId)
                }
                result.complete(Unit)
            } else {
                synchronized(lock) { publish(CompanionConnectionPhase.RECOVERY_REQUIRED, pending?.target, pending?.sessionId, issue = TransferIssue.RECOVERY_REQUIRED) }
                result.completeExceptionally(TransferException(TransferIssue.RECOVERY_REQUIRED))
            }
        }
        return result
    }

    suspend fun retryRetirement() {
        val previous: Deferred<Unit>?
        val physical = synchronized(lock) {
            if (retryingRetirement || stopping?.isCompleted == false) throw TransferException(TransferIssue.BUSY)
            if (mutableState.value.phase !in setOf(CompanionConnectionPhase.RECOVERY_REQUIRED, CompanionConnectionPhase.OFF)) throw TransferException(TransferIssue.BUSY)
            retryingRetirement = true
            previous = retirementWork?.takeIf { !it.isCompleted || !it.isCancelled }
            previous ?: newRetirementWork(admitted).also { retirementWork = it }
        }
        physical.start()
        try {
            withTimeout(retirementWaitMs) {
                try { physical.await() }
                catch (error: TransferException) {
                    if (previous !== physical || error.issue != TransferIssue.RECOVERY_REQUIRED) throw error
                    // 本次明确重试可能接住旧等待的迟到失败；先等旧 owner 退出，再用同一预算确认。
                    physical.join()
                    val confirmation = synchronized(lock) {
                        if (!retryingRetirement || retirementWork !== physical || !physical.isCompleted) throw error
                        newRetirementWork(admitted).also { retirementWork = it }
                    }
                    confirmation.start()
                    confirmation.await()
                }
            }
            synchronized(lock) {
                if (admitted != null || wire?.retired == false || factory.retirementRequired) throw TransferException(TransferIssue.RECOVERY_REQUIRED)
                wire = null
                stopping = null
                retirementWork = null
                publish(CompanionConnectionPhase.OFF, pending?.target, pending?.sessionId)
            }
        } catch (_: TimeoutCancellationException) {
            synchronized(lock) { publish(CompanionConnectionPhase.RECOVERY_REQUIRED, pending?.target, pending?.sessionId, issue = TransferIssue.RECOVERY_REQUIRED) }
            throw TransferException(TransferIssue.RECOVERY_REQUIRED)
        } catch (error: Exception) {
            synchronized(lock) { publish(CompanionConnectionPhase.RECOVERY_REQUIRED, pending?.target, pending?.sessionId, issue = TransferIssue.RECOVERY_REQUIRED) }
            throw error
        } finally { synchronized(lock) { retryingRetirement = false } }
    }

    private fun newRetirementWork(owner: Admission?): Deferred<Unit> = closeScope.async(
        NonCancellable, start = CoroutineStart.LAZY,
    ) {
        // 旧业务即使仍阻塞也能触达真实关闭；等待取消不销毁这个物理所有者。
        factory.retryRetirement()
        owner?.complete?.await()
        // open 的迟到结果可能在第一次工厂快照之后才登记。
        factory.retryRetirement()
        val retained = synchronized(lock) { wire }
        if (retained?.retired == false || factory.retirementRequired) throw TransferException(TransferIssue.RECOVERY_REQUIRED)
    }

    private fun sessionDeadline(connection: CompanionWireConnection, selected: Pending): Long {
        val greeting = CompanionResponseValidator.greeting(connection.greeting, selected.target.fingerprint, selected.sessionId)
        val observed = connection.greetingObservedAtMs
        if (observed > clock.monotonicMs()) throw TransferException(TransferIssue.EXPIRED)
        return Math.addExact(observed, greeting.remainingMs)
    }

    private suspend fun authenticateOn(connection: CompanionWireConnection, selected: Pending): Session {
        val deadline = sessionDeadline(connection, selected)
        val started = clock.monotonicMs()
        if (started >= deadline) throw TransferException(TransferIssue.EXPIRED)
        val request = TransferRequest.Authenticate(id(), selected.target.fingerprint, selected.sessionId)
        val response = CompanionResponseValidator.response(request.requestId, connection.exchange(request)) as? TransferResponse.Authenticated ?: throw TransferException(TransferIssue.INVALID_INPUT)
        try {
            if (response.capabilities.isEmpty() || !selected.requested.containsAll(response.capabilities) || response.remainingMs !in 1..TransferLimits.TOKEN_MS) throw TransferException(TransferIssue.PERMISSION_DENIED)
            val tokenDeadline = minOf(deadline, Math.addExact(started, response.remainingMs))
            if (clock.monotonicMs() >= tokenDeadline) throw TransferException(TransferIssue.EXPIRED)
            return Session(selected, response.capabilities, response.bearer, tokenDeadline, deadline, connection)
        } catch (error: Exception) {
            response.bearer.erase()
            throw error
        }
    }

    private suspend fun scope(connection: CompanionWireConnection, selected: Session, capability: TransferCapability, ticket: Long): TransferScope {
        checkEpoch(ticket)
        if (!connection.accepting || selected.pending.peer != connection.peer) throw TransferException(TransferIssue.INTERRUPTED)
        if (selected.deadlineConnection !== connection) {
            // 只有新连接实际收到的问候才能体现手表确认的会话续期。
            selected.sessionDeadline = sessionDeadline(connection, selected.pending)
            selected.deadlineConnection = connection
        }
        if (clock.monotonicMs() >= selected.sessionDeadline) throw TransferException(TransferIssue.EXPIRED)
        if (selected.tokenDeadline - clock.monotonicMs() <= TransferLimits.OPERATION_MS) {
            val renewed = authenticateOn(connection, selected.pending)
            var adopted = false
            try {
                if (renewed.capabilities != selected.capabilities) throw TransferException(TransferIssue.NOT_APPROVED)
                synchronized(lock) {
                    checkEpoch(ticket)
                    selected.bearer.erase()
                    selected.bearer = renewed.bearer
                    selected.tokenDeadline = renewed.tokenDeadline
                    adopted = true
                }
            } finally { if (!adopted) renewed.bearer.erase() }
        }
        if (capability !in selected.capabilities || clock.monotonicMs() >= selected.tokenDeadline) throw TransferException(TransferIssue.PERMISSION_DENIED)
        return TransferScope(selected.pending.target.fingerprint, selected.pending.sessionId, selected.bearer)
    }

    private fun requireSession(capability: TransferCapability): Session = synchronized(lock) {
        val active = session ?: throw TransferException(TransferIssue.NOT_APPROVED)
        if (capability !in active.capabilities) throw TransferException(TransferIssue.PERMISSION_DENIED)
        active
    }

    private suspend fun connection(selected: Pending, ticket: Long): CompanionWireConnection {
        val old = synchronized(lock) { wire }
        if (old != null && old.accepting && !old.retired) {
            CompanionResponseValidator.greeting(old.greeting, selected.target.fingerprint, selected.sessionId)
            if (old.peer != selected.peer) throw TransferException(TransferIssue.WRONG_TARGET)
            return old
        }
        closeWire()
        checkEpoch(ticket)
        return open(selected.target.fingerprint, selected.sessionId, ticket).also {
            CompanionResponseValidator.greeting(it.greeting, selected.target.fingerprint, selected.sessionId)
            if (it.peer != selected.peer) throw TransferException(TransferIssue.WRONG_TARGET)
        }
    }

    private suspend fun open(target: TransferFingerprint, expectedSession: String?, ticket: Long): CompanionWireConnection {
        synchronized(lock) {
            checkEpoch(ticket)
            val now = clock.monotonicMs()
            while (connectionTimes.isNotEmpty() && now - connectionTimes.first() >= 60_000L) connectionTimes.removeFirst()
            if (connectionTimes.size >= TransferLimits.ACCEPTS_PER_MINUTE) throw TransferException(TransferIssue.RATE_LIMITED)
            connectionTimes.addLast(now)
        }
        val address = synchronized(lock) { endpoint } ?: throw TransferException(TransferIssue.INVALID_INPUT)
        val result = factory.open(address, target, expectedSession)
        try {
            synchronized(lock) {
                checkEpoch(ticket)
                wire = result
            }
        }
        catch (error: Exception) {
            withContext(NonCancellable) {
                try { awaitRetirement(result.close()) }
                catch (_: Exception) { error.addSuppressed(TransferException(TransferIssue.RECOVERY_REQUIRED)) }
            }
            throw error
        }
        return result
    }

    private suspend fun closeWire() = withContext(NonCancellable) {
        val owned = synchronized(lock) { wire }
        if (owned != null && !owned.retired) {
            awaitRetirement(owned.close())
            if (!owned.retired) throw TransferException(TransferIssue.RECOVERY_REQUIRED)
        }
        synchronized(lock) { if (wire === owned) wire = null }
    }

    private suspend fun awaitRetirement(receipt: Deferred<Unit>) {
        try { withTimeout(retirementWaitMs) { receipt.await() } }
        catch (_: TimeoutCancellationException) { throw TransferException(TransferIssue.RECOVERY_REQUIRED) }
    }

    private suspend fun <T> operation(action: suspend (Long) -> T): T {
        val job = checkNotNull(coroutineContext[Job])
        val admission = Admission(job)
        val ticket = synchronized(lock) {
            if (admitted != null || stopping != null || retryingRetirement || factory.retirementRequired || mutableState.value.phase == CompanionConnectionPhase.RECOVERY_REQUIRED) throw TransferException(TransferIssue.BUSY)
            admitted = admission
            epoch
        }
        try { return action(ticket).also {
                coroutineContext.ensureActive()
                synchronized(lock) {
                    checkEpoch(ticket)
                    session?.let { active -> publish(CompanionConnectionPhase.READY, active.pending.target, active.pending.sessionId, active.capabilities) }
                }
        } }
        catch (error: Exception) {
            var cleanupFailed = false
            withContext(NonCancellable) { try { closeWire() } catch (_: Exception) { cleanupFailed = true } }
            cleanupFailed = cleanupFailed || factory.retirementRequired
            if (cleanupFailed) error.addSuppressed(TransferException(TransferIssue.RECOVERY_REQUIRED))
            val cancelled = error is CancellationException
            val issue = if (error is TransferException) error.issue else TransferIssue.INVALID_INPUT
            synchronized(lock) {
                if (epoch == ticket) {
                    if (cleanupFailed || cancelled || issue in setOf(TransferIssue.NOT_APPROVED, TransferIssue.EXPIRED, TransferIssue.CLOSED, TransferIssue.WRONG_TARGET, TransferIssue.UNAUTHENTICATED, TransferIssue.TRANSPORT_FAILED, TransferIssue.RECOVERY_REQUIRED)) {
                        session?.bearer?.erase()
                        session = null
                    }
                    val phase = if (cleanupFailed || issue == TransferIssue.RECOVERY_REQUIRED) CompanionConnectionPhase.RECOVERY_REQUIRED else if (session != null) CompanionConnectionPhase.READY else CompanionConnectionPhase.OFF
                    val visibleIssue = if (cleanupFailed) TransferIssue.RECOVERY_REQUIRED else if (cancelled) null else issue
                    publish(phase, pending?.target, pending?.sessionId, session?.capabilities.orEmpty(), visibleIssue)
                }
            }
            if (error is TransferException || error is CancellationException) throw error
            throw TransferException(issue).also { if (cleanupFailed) it.addSuppressed(TransferException(TransferIssue.RECOVERY_REQUIRED)) }
        } finally {
            synchronized(lock) { if (admitted === admission) admitted = null }
            admission.complete.complete(Unit)
        }
    }

    private fun checkEpoch(expected: Long) { coroutineContextCheck(expected) }
    private fun coroutineContextCheck(expected: Long) = synchronized(lock) { if (epoch != expected || stopping != null) throw CancellationException("Companion operation superseded") }
    private fun publish(phase: CompanionConnectionPhase, target: CompanionTarget?, sessionId: String? = null, capabilities: Set<TransferCapability> = emptySet(), issue: TransferIssue? = null) {
        mutableState.value = CompanionConnectionState(phase, target, sessionId, capabilities, issue, pending?.peer ?: session?.pending?.peer)
    }
    private fun id(): String = UUID.randomUUID().toString()
}
