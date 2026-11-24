package io.github.onlinemo.meloxwatch.data.transfer

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

fun interface TransferClock { fun monotonicMs(): Long }

class TransferPendingPeer(val fingerprint: TransferFingerprint, requested: Set<TransferCapability>) {
    val requested: Set<TransferCapability> = transferFrozenSet(requested)
    override fun toString(): String = "<pending-local-peer>"
}

class TransferLocalSessionState(
    val active: Boolean,
    val target: TransferFingerprint?,
    val sessionId: String?,
    val pending: TransferPendingPeer?,
    val approvedPeer: TransferFingerprint?,
    approvedCapabilities: Set<TransferCapability>,
    val issue: TransferIssue?,
) {
    val approvedCapabilities: Set<TransferCapability> = transferFrozenSet(approvedCapabilities)
    override fun toString(): String = "<local-transfer-state>"
}

class TransferOpening(val target: TransferFingerprint, val sessionId: String, val remainingMs: Long) {
    override fun toString(): String = "<local-transfer-opening>"
}

class TransferAuthentication(val bearer: TransferBearer, capabilities: Set<TransferCapability>, val remainingMs: Long) {
    val capabilities: Set<TransferCapability> = transferFrozenSet(capabilities)
    override fun toString(): String = "<local-transfer-authentication>"
}

internal class TransferGrant(
    val target: TransferFingerprint, val sessionId: String, val peer: TransferFingerprint,
    val epoch: Long, val grantId: String, capabilities: Set<TransferCapability>,
) {
    val capabilities: Set<TransferCapability> = transferFrozenSet(capabilities)
    fun sameAs(other: TransferGrant): Boolean = target == other.target && sessionId == other.sessionId && peer == other.peer && epoch == other.epoch && grantId == other.grantId
}

/**
 * A permit is an authorization snapshot, not a file handle. Recheck it inside the actual
 * transaction and at bounded copy/validation steps. An already claimed commit may finish
 * after cancellation; the durable result must then be retained rather than reported undone.
 */
class TransferPermit internal constructor(
    private val authority: TransferSessionAuthority,
    internal val grant: TransferGrant,
    private val tokenId: String,
    val capability: TransferCapability,
) {
    val target: TransferFingerprint get() = grant.target
    val peer: TransferFingerprint get() = grant.peer
    val sessionId: String get() = grant.sessionId
    fun checkActive() { authority.check(grant, tokenId, capability) }
    internal fun sameGrant(other: TransferPermit): Boolean = grant.sameAs(other.grant) && capability == other.capability

    /** Call at the transaction's final authorization boundary, not before a suspending copy. */
    fun claimCommit(binding: TransferOperationBinding): TransferCommitClaim = authority.claimCommit(grant, tokenId, capability, binding)
    override fun toString(): String = "<local-transfer-permit>"
}

class TransferCommitClaim internal constructor(val binding: TransferOperationBinding) {
    override fun toString(): String = "<local-commit-claim>"
}

/** Pure bounded state changes; onRevoked must only dispatch cancellation/close, never do I/O. */
class TransferSessionAuthority(
    private val clock: TransferClock = TransferClock { System.nanoTime() / 1_000_000L },
    private val random: SecureRandom = SecureRandom(),
    private val onRevoked: (TransferFingerprint?) -> Unit = {},
) {
    private val lock = Any()
    private var epoch = 0L
    private var opening: TransferOpening? = null
    private var deadline = 0L
    private var pairingDeadline = 0L
    private var code: ByteArray? = null
    private var failures = 0
    private var pending: TransferPendingPeer? = null
    private var grant: TransferGrant? = null
    private val tokens = ArrayDeque<Token>()
    private val mutableState = MutableStateFlow(closedState(null))
    val state: StateFlow<TransferLocalSessionState> = mutableState.asStateFlow()

    private class Token(val id: String, val value: TransferBearer, val deadline: Long)

    fun begin(target: TransferFingerprint): TransferOpening = synchronized(lock) {
        if (opening != null) throw TransferException(TransferIssue.BUSY)
        val now = clock.monotonicMs()
        epoch = Math.addExact(epoch, 1L)
        deadline = Math.addExact(now, TransferLimits.SESSION_MS)
        pairingDeadline = Math.addExact(now, TransferLimits.PAIRING_MS)
        code = String.format(Locale.ROOT, "%08d", random.nextInt(100_000_000)).toByteArray(Charsets.US_ASCII)
        failures = 0
        pending = null
        grant = null
        clearTokens()
        TransferOpening(target, UUID.randomUUID().toString(), TransferLimits.SESSION_MS).also { opening = it
            publish(null) }
    }

    /** The UI may display this locally. It is never persisted, copied to diagnostics or sent in a greeting. */
    fun pairingCodeForLocalUi(): String? = synchronized(lock) {
        if (opening == null || clock.monotonicMs() >= pairingDeadline || clock.monotonicMs() >= deadline) {
            eraseCode()
            null
        } else code?.toString(Charsets.US_ASCII)
    }

    fun remainingMs(): Long = synchronized(lock) {
        if (opening == null) 0L else (deadline - clock.monotonicMs()).coerceAtLeast(0L)
    }

    fun currentOpening(): TransferOpening = synchronized(lock) {
        val active = requireActive()
        TransferOpening(active.target, active.sessionId, (deadline - clock.monotonicMs()).coerceAtLeast(0L))
    }

    fun pair(peer: TransferFingerprint, request: TransferRequest.Pair) = synchronized(lock) {
        checkTarget(request.target, request.sessionId)
        if (grant != null || pending != null) throw TransferException(TransferIssue.BUSY)
        if (clock.monotonicMs() >= pairingDeadline) { eraseCode()
            throw TransferException(TransferIssue.EXPIRED) }
        val expected = code ?: throw TransferException(TransferIssue.PAIRING_LOCKED)
        if (!MessageDigest.isEqual(expected, request.code.toByteArray(Charsets.US_ASCII))) {
            failures++
            if (failures >= TransferLimits.PAIRING_FAILURES) eraseCode()
            throw TransferException(if (failures >= TransferLimits.PAIRING_FAILURES) TransferIssue.PAIRING_LOCKED else TransferIssue.PAIRING_FAILED)
        }
        eraseCode()
        pending = TransferPendingPeer(peer, request.requested.toSet())
        publish(null)
    }

    /** A correct code does not authorize data. The local user confirms this exact TLS public key and subset. */
    fun approveForLocalUi(peer: TransferFingerprint, capabilities: Set<TransferCapability>) = synchronized(lock) {
        val approved = transferFrozenSet(capabilities)
        val active = requireActive()
        if (clock.monotonicMs() >= pairingDeadline) { pending = null
            publish(TransferIssue.EXPIRED)
            throw TransferException(TransferIssue.EXPIRED) }
        val selected = pending ?: throw TransferException(TransferIssue.NOT_FOUND)
        if (selected.fingerprint != peer) throw TransferException(TransferIssue.WRONG_TARGET)
        if (approved.isEmpty() || !selected.requested.containsAll(approved)) throw TransferException(TransferIssue.PERMISSION_DENIED)
        grant = TransferGrant(active.target, active.sessionId, peer, epoch, UUID.randomUUID().toString(), approved)
        pending = null
        publish(null)
    }

    fun revokeForLocalUi(peer: TransferFingerprint) {
        val changed = synchronized(lock) {
            if (grant?.peer != peer && pending?.fingerprint != peer) false else {
                grant = null
                pending = null
                eraseCode()
                clearTokens()
                publish(TransferIssue.NOT_APPROVED)
                true
            }
        }
        if (changed) onRevoked(peer)
    }

    fun authenticate(peer: TransferFingerprint, target: TransferFingerprint, sessionId: String): TransferAuthentication = synchronized(lock) {
        checkTarget(target, sessionId)
        val selected = grant ?: throw TransferException(TransferIssue.NOT_APPROVED)
        if (selected.peer != peer) throw TransferException(TransferIssue.NOT_APPROVED)
        val now = clock.monotonicMs()
        while (tokens.isNotEmpty() && tokens.first().deadline <= now) tokens.removeFirst().value.erase()
        while (tokens.size >= TransferLimits.CONNECTIONS) tokens.removeFirst().value.erase()
        val bearer = TransferBearer.generate(random)
        val tokenDeadline = minOf(Math.addExact(now, TransferLimits.TOKEN_MS), deadline)
        tokens.addLast(Token(UUID.randomUUID().toString(), bearer, tokenDeadline))
        TransferAuthentication(TransferBearer.fromWire(bearer.wireBytes()), selected.capabilities.toSet(), tokenDeadline - now)
    }

    fun authorize(peer: TransferFingerprint, scope: TransferScope, capability: TransferCapability): TransferPermit =
        authorizeOneOf(peer, scope, setOf(capability))

    /** 返回 permit 必须命名此 grant 实际批准的能力。 */
    fun authorizeOneOf(peer: TransferFingerprint, scope: TransferScope, allowed: Set<TransferCapability>): TransferPermit = synchronized(lock) {
        if (allowed.isEmpty()) throw TransferException(TransferIssue.INVALID_INPUT)
        checkTarget(scope.target, scope.sessionId)
        val selected = grant ?: throw TransferException(TransferIssue.NOT_APPROVED)
        if (selected.peer != peer) throw TransferException(TransferIssue.NOT_APPROVED)
        val capability = TransferCapability.entries.firstOrNull { it in allowed && it in selected.capabilities }
            ?: throw TransferException(TransferIssue.PERMISSION_DENIED)
        val token = tokens.firstOrNull { it.value.sameAs(scope.bearer) } ?: throw TransferException(TransferIssue.UNAUTHENTICATED)
        if (clock.monotonicMs() >= token.deadline) throw TransferException(TransferIssue.EXPIRED)
        TransferPermit(this, selected, token.id, capability)
    }

    internal fun check(expected: TransferGrant, tokenId: String, capability: TransferCapability) = synchronized(lock) {
        checkGrantLocked(expected, capability)
        val token = tokens.firstOrNull { it.id == tokenId } ?: throw TransferException(TransferIssue.UNAUTHENTICATED)
        if (clock.monotonicMs() >= token.deadline) throw TransferException(TransferIssue.EXPIRED)
    }

    internal fun claimCommit(expected: TransferGrant, tokenId: String, capability: TransferCapability, binding: TransferOperationBinding): TransferCommitClaim = synchronized(lock) {
        check(expected, tokenId, capability)
        if (binding.target != expected.target || binding.peer != expected.peer || binding.kind.capability != capability) throw TransferException(TransferIssue.WRONG_TARGET)
        TransferCommitClaim(binding)
    }

    /** Local explicit extension only; the server must reschedule its close deadline with this value. */
    fun extendForLocalUi(): Long = synchronized(lock) {
        requireActive()
        deadline = Math.addExact(clock.monotonicMs(), TransferLimits.SESSION_MS)
        publish(null)
        TransferLimits.SESSION_MS
    }

    fun stop(issue: TransferIssue = TransferIssue.CLOSED) {
        val changed = synchronized(lock) {
            if (opening == null) false else {
                epoch = Math.addExact(epoch, 1L)
                opening = null
                pending = null
                grant = null
                eraseCode()
                clearTokens()
                mutableState.value = closedState(issue)
                true
            }
        }
        if (changed) onRevoked(null)
    }

    private fun requireActive(): TransferOpening {
        val active = opening ?: throw TransferException(TransferIssue.CLOSED)
        if (clock.monotonicMs() >= deadline) throw TransferException(TransferIssue.EXPIRED)
        return active
    }

    private fun checkTarget(target: TransferFingerprint, sessionId: String) {
        val active = requireActive()
        if (active.target != target || active.sessionId != sessionId) throw TransferException(TransferIssue.WRONG_TARGET)
    }

    private fun checkGrantLocked(expected: TransferGrant, capability: TransferCapability) {
        requireActive()
        val selected = grant ?: throw TransferException(TransferIssue.NOT_APPROVED)
        if (!selected.sameAs(expected) || capability !in selected.capabilities) throw TransferException(TransferIssue.PERMISSION_DENIED)
    }

    private fun clearTokens() { tokens.forEach { it.value.erase() }
        tokens.clear() }
    private fun eraseCode() { code?.fill(0)
        code = null }
    private fun publish(issue: TransferIssue?) {
        val active = opening
        mutableState.value = TransferLocalSessionState(active != null, active?.target, active?.sessionId, pending, grant?.peer, grant?.capabilities?.toSet() ?: emptySet(), issue)
    }
    private fun closedState(issue: TransferIssue?): TransferLocalSessionState = TransferLocalSessionState(false, null, null, null, null, emptySet(), issue)
}
