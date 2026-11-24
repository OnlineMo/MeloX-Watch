package io.github.onlinemo.meloxwatch.data.auth

import io.github.onlinemo.meloxwatch.data.netease.NeteaseLoginApi
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class AccountRequestToken(val account: AccountIdentity, val generation: Long) {
    override fun toString(): String = "AccountRequestToken(generation=$generation)"
}

internal class LogoutOperation(private val owner: Any, val epoch: Long) {
    val finished = CompletableDeferred<Unit>()
    val started = AtomicBoolean()
    fun belongsTo(expectedOwner: Any): Boolean = owner === expectedOwner
}

/**
 * One app-owned session repository. Memory locks never cover disk or network work.
 * The invalidation callback must be short, nonthrowing and memory-only; consumers bind caches to generation.
 */
internal class SessionRepository(
    private val store: SessionStore,
    private val onAccountInvalidated: (AccountIdentity?) -> Unit = {},
) : AutoCloseable {
    private val memory = Any()
    private val owner = Any()
    private val mutations = Mutex()
    private val mutableSnapshot = MutableStateFlow(SessionSnapshot())
    val snapshot: StateFlow<SessionSnapshot> = mutableSnapshot.asStateFlow()
    private var active: StoredSession? = null
    private var loaded = false
    private var closed = false
    private var logoutEpoch = 0L
    private var pendingLogout: LogoutOperation? = null
    private val requests = mutableSetOf<Job>()

    fun loginEpoch(): Long = synchronized(memory) { logoutEpoch }

    /** Reading ciphertext neither validates the account nor initializes an online API. */
    suspend fun restore(): SessionSnapshot {
        awaitLogout()
        return mutations.withLock {
            val epoch = synchronized(memory) {
                requireOpen()
                if (loaded) return@withLock mutableSnapshot.value
                logoutEpoch
            }
            val restored = try { store.load() } catch (error: AuthException) {
                synchronized(memory) {
                    if (logoutEpoch == epoch && !closed) {
                        loaded = true
                        active = null
                        mutableSnapshot.value = SessionSnapshot(validity = SessionValidity.UNAVAILABLE,
                            generation = nextGeneration(), issue = error.issue)
                    }
                    return@withLock mutableSnapshot.value
                }
            }
            synchronized(memory) {
                if (logoutEpoch == epoch && !closed && pendingLogout == null) {
                    loaded = true
                    active = restored
                    mutableSnapshot.value = SessionSnapshot(restored?.account,
                        if (restored == null) SessionValidity.SIGNED_OUT else SessionValidity.RESTORED_UNVERIFIED,
                        nextGeneration())
                }
                mutableSnapshot.value
            }
        }
    }

    /** Called by an explicit online entry; restored bytes alone never count as a verified login. */
    suspend fun revalidate(api: NeteaseLoginApi): Boolean = coroutineScope {
        val job = currentCoroutineContext()[Job] ?: throw AuthException(AuthIssue.CLOSED)
        val captured = synchronized(memory) {
            requireOpen()
            if (mutableSnapshot.value.validity != SessionValidity.RESTORED_UNVERIFIED) return@coroutineScope false
            val session = active ?: return@coroutineScope false
            if (requests.size >= MAX_ACCOUNT_REQUESTS) throw AuthException(AuthIssue.BUSY)
            requests.add(job)
            Triple(session, logoutEpoch, mutableSnapshot.value.generation)
        }
        try {
            val verified = api.verify(captured.first.credential)
            if (verified.stored.account.providerId != captured.first.account.providerId ||
                verified.stored.account.accountId != captured.first.account.accountId
            ) throw AuthException(AuthIssue.LOGIN_REQUIRED)
            commit(verified, captured.second) { accept ->
                synchronized(memory) {
                    active === captured.first && mutableSnapshot.value.generation == captured.third && accept()
                }
            }
        } catch (error: AuthException) {
            val logout = synchronized(memory) {
                if (closed || active !== captured.first || mutableSnapshot.value.generation != captured.third) null
                else if (error.issue == AuthIssue.LOGIN_REQUIRED) invalidateLocked(error.issue)
                else {
                    mutableSnapshot.value = mutableSnapshot.value.copy(issue = error.issue)
                    null
                }
            }
            if (logout != null) completeInvalidation(logout, job)
            throw error
        } finally {
            synchronized(memory) { requests.remove(job) }
        }
    }

    /**
     * The authorization callback holds the QR intent's short memory lock and calls accept exactly once.
     * Acceptance is the login's ordering point: it precedes the noncancellable ciphertext commit.
     * A later leave/refresh cannot repaint its QR page. A later logout waits, then clears that commit.
     * The budget includes waiting and sealing, and stops at acceptance. Synchronous Keystore/file calls
     * may overrun wall time; no coroutine timeout is claimed to physically interrupt those system calls.
     */
    suspend fun commit(
        session: VerifiedSession,
        expectedLogoutEpoch: Long,
        preAcceptTimeoutMs: Long = PRE_ACCEPT_TIMEOUT_MS,
        authorize: (accept: () -> Boolean) -> Boolean,
    ): Boolean {
        if (preAcceptTimeoutMs <= 0) throw AuthException(AuthIssue.TIMEOUT)
        val caller = currentCoroutineContext()
        var locked = false
        var sealed: SealedSession? = null
        var admitted = false
        try {
            val prepared = try {
                withTimeoutOrNull(minOf(preAcceptTimeoutMs, PRE_ACCEPT_TIMEOUT_MS)) {
                    awaitLogout()
                    mutations.lock()
                    locked = true
                    sealed = store.seal(session)
                    val preparation = currentCoroutineContext()
                    preparation.ensureActive()
                    authorize {
                        synchronized(memory) {
                            if (!preparation.isActive || closed || pendingLogout != null ||
                                logoutEpoch != expectedLogoutEpoch || admitted
                            ) false else {
                                admitted = true
                                true
                            }
                        }
                    }
                    admitted
                }
            } catch (error: CancellationException) {
                if (!admitted) throw error
                // Acceptance cannot be withdrawn by cancellation after its ordering point.
                true
            }
            if (!admitted) {
                if (prepared == null) throw AuthException(AuthIssue.TIMEOUT)
                return false
            }
            val committed = withContext(NonCancellable) {
                try { store.write(checkNotNull(sealed)) } catch (error: AuthException) {
                    synchronized(memory) {
                        if (!closed && logoutEpoch == expectedLogoutEpoch) {
                            mutableSnapshot.value = mutableSnapshot.value.copy(issue = error.issue)
                        }
                    }
                    throw error
                }
                val change = synchronized(memory) {
                    if (closed || logoutEpoch != expectedLogoutEpoch || pendingLogout != null) null
                    else {
                        val previous = active?.account
                        val obsolete = requests.filter { it !== caller[Job] }
                        requests.clear()
                        active = session.stored
                        loaded = true
                        mutableSnapshot.value = SessionSnapshot(session.stored.account, SessionValidity.VERIFIED, nextGeneration())
                        previous to obsolete
                    }
                }
                if (change != null) {
                    change.second.forEach { it.cancel(CancellationException("Account session changed")) }
                    onAccountInvalidated(change.first)
                }
                change != null
            }
            caller.ensureActive()
            return committed
        } finally {
            sealed?.close()
            if (locked) mutations.unlock()
        }
    }

    /** Invalidate immediately; the paired finish operation performs only this session's disk cleanup. */
    fun beginLogout(): LogoutOperation {
        val change = synchronized(memory) {
            requireOpen()
            pendingLogout?.let { return it }
            invalidateLocked()
        }
        change.third.forEach { it.cancel(CancellationException("Account logged out")) }
        onAccountInvalidated(change.second)
        return change.first
    }

    suspend fun finishLogout(operation: LogoutOperation) {
        require(operation.belongsTo(owner))
        if (!operation.started.compareAndSet(false, true)) {
            operation.finished.await()
            return
        }
        withContext(NonCancellable) {
            try {
                mutations.withLock { store.clear() }
                synchronized(memory) { if (pendingLogout === operation) pendingLogout = null }
                operation.finished.complete(Unit)
            } catch (failure: Exception) {
                val error = failure as? AuthException ?: AuthException(AuthIssue.STORAGE_UNAVAILABLE)
                synchronized(memory) {
                    if (pendingLogout === operation) {
                        pendingLogout = null
                        if (!closed) mutableSnapshot.value = SessionSnapshot(validity = SessionValidity.UNAVAILABLE,
                            generation = nextGeneration(), issue = error.issue)
                    }
                }
                operation.finished.completeExceptionally(error)
                throw error
            }
        }
    }

    /** Expired account credentials are removed; device-local data is outside this store. */
    suspend fun expire(token: AccountRequestToken): Boolean {
        val change = synchronized(memory) {
            if (!isCurrent(token)) return false
            invalidateLocked(AuthIssue.LOGIN_REQUIRED)
        }
        completeInvalidation(change, currentCoroutineContext()[Job])
        return true
    }

    private fun invalidateLocked(issue: AuthIssue? = null): Triple<LogoutOperation, AccountIdentity?, List<Job>> {
        if (logoutEpoch == Long.MAX_VALUE) throw AuthException(AuthIssue.CLOSED)
        logoutEpoch++
        val operation = LogoutOperation(owner, logoutEpoch)
        pendingLogout = operation
        val oldAccount = active?.account
        val obsolete = requests.toList()
        requests.clear()
        active = null
        loaded = true
        mutableSnapshot.value = SessionSnapshot(validity = if (issue == null) SessionValidity.SIGNED_OUT else SessionValidity.UNAVAILABLE,
            generation = nextGeneration(), issue = issue)
        return Triple(operation, oldAccount, obsolete)
    }

    private suspend fun completeInvalidation(change: Triple<LogoutOperation, AccountIdentity?, List<Job>>, caller: Job?) {
        change.third.filter { it !== caller }.forEach { it.cancel(CancellationException("Account session expired")) }
        onAccountInvalidated(change.second)
        finishLogout(change.first)
    }

    suspend fun <T> authenticated(operation: suspend (SessionCredential, AccountRequestToken) -> T): T = coroutineScope {
        val job = currentCoroutineContext()[Job] ?: throw AuthException(AuthIssue.CLOSED)
        val captured = synchronized(memory) {
            requireOpen()
            if (mutableSnapshot.value.validity != SessionValidity.VERIFIED) throw AuthException(AuthIssue.LOGIN_REQUIRED)
            val session = active ?: throw AuthException(AuthIssue.LOGIN_REQUIRED)
            if (requests.size >= MAX_ACCOUNT_REQUESTS) throw AuthException(AuthIssue.BUSY)
            requests.add(job)
            session.credential to AccountRequestToken(session.account, mutableSnapshot.value.generation)
        }
        try {
            val result = operation(captured.first, captured.second)
            currentCoroutineContext().ensureActive()
            if (!isCurrent(captured.second)) throw CancellationException("Account session changed")
            result
        } catch (error: AuthException) {
            if (error.issue == AuthIssue.LOGIN_REQUIRED) expire(captured.second)
            throw error
        } finally { synchronized(memory) { requests.remove(job) } }
    }

    fun isCurrent(token: AccountRequestToken): Boolean = synchronized(memory) {
        !closed && mutableSnapshot.value.validity == SessionValidity.VERIFIED &&
            mutableSnapshot.value.generation == token.generation && active?.account == token.account
    }

    private suspend fun awaitLogout() { synchronized(memory) { pendingLogout?.finished }?.await() }
    private fun requireOpen() { if (closed) throw AuthException(AuthIssue.CLOSED) }
    private fun nextGeneration(): Long {
        if (mutableSnapshot.value.generation == Long.MAX_VALUE) throw AuthException(AuthIssue.CLOSED)
        return mutableSnapshot.value.generation + 1
    }

    override fun close() {
        val change = synchronized(memory) {
            if (closed) return
            closed = true
            val previous = active?.account
            active = null
            mutableSnapshot.value = SessionSnapshot(validity = SessionValidity.UNAVAILABLE,
                generation = mutableSnapshot.value.generation.let { if (it == Long.MAX_VALUE) it else it + 1 }, issue = AuthIssue.CLOSED)
            previous to requests.toList().also { requests.clear() }
        }
        change.second.forEach { it.cancel(CancellationException("Session owner closed")) }
        onAccountInvalidated(change.first)
    }

    companion object {
        private const val MAX_ACCOUNT_REQUESTS = 6
        private const val PRE_ACCEPT_TIMEOUT_MS = 8_000L
    }
}
