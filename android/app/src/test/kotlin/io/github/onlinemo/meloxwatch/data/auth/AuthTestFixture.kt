package io.github.onlinemo.meloxwatch.data.auth

import kotlinx.coroutines.CompletableDeferred

internal fun fixtureSession(id: String = "101", name: String = "Fixture account"): VerifiedSession =
    VerifiedSession(StoredSession(AccountIdentity("netease", id, name), SessionCredential.fromValidatedHeader("MUSIC_U=fixture-$id; __csrf=fixture")))

/** Controls system-call boundaries without a real account, Keystore, network or local-library access. */
internal class ControlledSessionStore(initial: StoredSession? = null) : SessionStore {
    private val owner = Any()
    private val sealed = mutableMapOf<SealedSession, StoredSession>()
    var persistent = initial
        private set
    var loads = 0
        private set
    var seals = 0
        private set
    var writes = 0
        private set
    var clears = 0
        private set
    var loadFailure: AuthIssue? = null
    var sealFailure: AuthIssue? = null
    var writeFailure: AuthIssue? = null
    var clearFailure: AuthIssue? = null
    var beforeSeal: suspend () -> Unit = {}
    var beforeWrite: suspend () -> Unit = {}
    val sealStarted = CompletableDeferred<Unit>()
    val writeStarted = CompletableDeferred<Unit>()

    override suspend fun load(): StoredSession? {
        loads++
        loadFailure?.let { throw AuthException(it) }
        return persistent
    }

    override suspend fun seal(session: VerifiedSession): SealedSession {
        seals++
        sealStarted.complete(Unit)
        beforeSeal()
        sealFailure?.let { throw AuthException(it) }
        return SealedSession(owner, byteArrayOf(1, 2, 3)).also { sealed[it] = session.stored }
    }

    override suspend fun write(session: SealedSession) {
        writeStarted.complete(Unit)
        beforeWrite()
        writeFailure?.let { throw AuthException(it) }
        session.bytes(owner).fill(0)
        persistent = checkNotNull(sealed.remove(session))
        writes++
    }

    override suspend fun clear() {
        clearFailure?.let { throw AuthException(it) }
        persistent = null
        clears++
    }
}
