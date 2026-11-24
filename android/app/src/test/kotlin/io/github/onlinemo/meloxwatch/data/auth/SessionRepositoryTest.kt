package io.github.onlinemo.meloxwatch.data.auth

import io.github.onlinemo.meloxwatch.data.netease.LoginQrKey
import io.github.onlinemo.meloxwatch.data.netease.NeteaseLoginApi
import io.github.onlinemo.meloxwatch.data.netease.QrCheckResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRepositoryTest {
    @Test fun restoreReadsOnlyTheLocalStoreAndNeverCountsSavedBytesAsVerified() = runBlocking {
        val store = ControlledSessionStore(fixtureSession().stored)
        val sessions = SessionRepository(store)
        val result = sessions.restore()
        assertEquals(SessionValidity.RESTORED_UNVERIFIED, result.validity)
        assertEquals("101", result.account?.accountId)
        assertEquals(result, sessions.restore())
        assertEquals(1, store.loads)
        assertEquals(0, store.seals)
        assertEquals(0, store.writes)
        expect(AuthIssue.LOGIN_REQUIRED) { runBlocking { sessions.authenticated { _, _ -> error("must not run") } } }
        sessions.close()
    }

    @Test fun corruptOrUnavailableSessionReadsDoNotClearAnything() = runBlocking {
        for (issue in listOf(AuthIssue.SESSION_CORRUPT, AuthIssue.KEY_UNAVAILABLE, AuthIssue.STORAGE_UNAVAILABLE)) {
            val store = ControlledSessionStore(fixtureSession().stored).apply { loadFailure = issue }
            val sessions = SessionRepository(store)
            assertEquals(SessionValidity.UNAVAILABLE, sessions.restore().validity)
            assertEquals(issue, sessions.snapshot.value.issue)
            assertEquals(0, store.clears)
            assertEquals("101", store.persistent?.account?.accountId)
            sessions.close()
        }
    }

    @Test fun refusedOrCancelledPreAcceptanceNeverWritesOrReplacesTheSession() = runBlocking {
        val store = ControlledSessionStore()
        val sessions = SessionRepository(store)
        assertFalse(sessions.commit(fixtureSession(), sessions.loginEpoch()) { false })
        assertEquals(0, store.writes)
        val release = CompletableDeferred<Unit>()
        store.beforeSeal = { release.await() }
        val work = launch(start = CoroutineStart.UNDISPATCHED) { sessions.commit(fixtureSession(), sessions.loginEpoch()) { it() } }
        work.cancelAndJoin()
        release.complete(Unit)
        assertEquals(0, store.writes)
        assertEquals(SessionValidity.SIGNED_OUT, sessions.snapshot.value.validity)
        sessions.close()
    }

    @Test fun preAcceptanceTimeoutDoesNotWriteButPersistenceCanOutlastThatBudgetAfterAcceptance() = runBlocking {
        val store = ControlledSessionStore().apply { beforeSeal = { delay(200) } }
        val sessions = SessionRepository(store)
        expect(AuthIssue.TIMEOUT) { runBlocking { sessions.commit(fixtureSession(), sessions.loginEpoch(), 30) { it() } } }
        assertEquals(0, store.writes)
        store.beforeSeal = {}
        store.beforeWrite = { delay(200) }
        assertTrue(sessions.commit(fixtureSession(), sessions.loginEpoch(), 30) { it() })
        assertEquals(1, store.writes)
        assertEquals(SessionValidity.VERIFIED, sessions.snapshot.value.validity)
        sessions.close()
    }

    @Test fun cancellationAfterAcceptancePreservesTheActualCommitAndDoesNotReportASignedOutStore() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val store = ControlledSessionStore().apply { beforeWrite = { release.await() } }
        val sessions = SessionRepository(store)
        val work = launch { sessions.commit(fixtureSession(), sessions.loginEpoch()) { it() } }
        store.writeStarted.await()
        work.cancel()
        release.complete(Unit)
        work.join()
        assertTrue(work.isCancelled)
        assertEquals(1, store.writes)
        assertEquals(SessionValidity.VERIFIED, sessions.snapshot.value.validity)
        sessions.close()
    }

    @Test fun sealingAndWritingFailuresNeverDisplayAnUncommittedAccountAsLoggedIn() = runBlocking {
        for (duringSeal in listOf(true, false)) {
            val store = ControlledSessionStore().apply {
                if (duringSeal) sealFailure = AuthIssue.KEY_UNAVAILABLE else writeFailure = AuthIssue.STORAGE_UNAVAILABLE
            }
            val sessions = SessionRepository(store)
            expect(if (duringSeal) AuthIssue.KEY_UNAVAILABLE else AuthIssue.STORAGE_UNAVAILABLE) {
                runBlocking { sessions.commit(fixtureSession(), sessions.loginEpoch()) { it() } }
            }
            assertNull(sessions.snapshot.value.account)
            assertEquals(SessionValidity.SIGNED_OUT, sessions.snapshot.value.validity)
            assertEquals(0, store.writes)
            sessions.close()
        }
    }

    @Test fun logoutBeforeAcceptanceRejectsTheLoginAndFinishesItsOwnCleanup() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val store = ControlledSessionStore().apply { beforeSeal = { release.await() } }
        val sessions = SessionRepository(store)
        val work = async { sessions.commit(fixtureSession(), sessions.loginEpoch()) { it() } }
        store.sealStarted.await()
        val logout = sessions.beginLogout()
        release.complete(Unit)
        assertFalse(work.await())
        sessions.finishLogout(logout)
        assertEquals(0, store.writes)
        assertEquals(1, store.clears)
        assertNull(store.persistent)
        sessions.close()
    }

    @Test fun logoutAfterAcceptanceWaitsForTheWriteThenClearsItBeforeAnyNewLogin() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val store = ControlledSessionStore().apply { beforeWrite = { release.await() } }
        val sessions = SessionRepository(store)
        val work = async { sessions.commit(fixtureSession(), sessions.loginEpoch()) { it() } }
        store.writeStarted.await()
        val logout = sessions.beginLogout()
        val finish = launch(start = CoroutineStart.UNDISPATCHED) { sessions.finishLogout(logout) }
        assertEquals(SessionValidity.SIGNED_OUT, sessions.snapshot.value.validity)
        assertFalse(finish.isCompleted)
        val next = async { sessions.commit(fixtureSession("202"), sessions.loginEpoch()) { it() } }
        release.complete(Unit)
        assertFalse(work.await())
        finish.join()
        assertTrue(next.await())
        assertEquals(2, store.writes)
        assertEquals(1, store.clears)
        assertEquals("202", store.persistent?.account?.accountId)
        sessions.close()
    }

    @Test fun repeatedLogoutIsIdempotentAndForeignOrOldTicketsCannotClearANewerLogin() = runBlocking {
        val store = ControlledSessionStore()
        val sessions = SessionRepository(store)
        val other = SessionRepository(ControlledSessionStore())
        val first = sessions.beginLogout()
        assertSame(first, sessions.beginLogout())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { other.finishLogout(first) } }
        sessions.finishLogout(first)
        assertTrue(sessions.commit(fixtureSession("202"), sessions.loginEpoch()) { it() })
        sessions.finishLogout(first)
        assertEquals(1, store.clears)
        assertEquals("202", store.persistent?.account?.accountId)
        sessions.close()
        other.close()
    }

    @Test fun failedLogoutReportsPersistenceFailureAndDoesNotLeaveFutureRequestsWaitingForever() = runBlocking {
        val store = ControlledSessionStore().apply { clearFailure = AuthIssue.STORAGE_UNAVAILABLE }
        val sessions = SessionRepository(store)
        expect(AuthIssue.STORAGE_UNAVAILABLE) { runBlocking { sessions.finishLogout(sessions.beginLogout()) } }
        assertEquals(SessionValidity.UNAVAILABLE, sessions.snapshot.value.validity)
        assertEquals(AuthIssue.STORAGE_UNAVAILABLE, sessions.snapshot.value.issue)
        store.clearFailure = null
        assertTrue(sessions.commit(fixtureSession(), sessions.loginEpoch()) { it() })
        sessions.close()
    }

    @Test fun revalidationFailureRetainsUnverifiedStateAndAccountMismatchClearsOnlyTheSession() = runBlocking {
        val store = ControlledSessionStore(fixtureSession().stored)
        val sessions = SessionRepository(store)
        sessions.restore()
        expect(AuthIssue.NETWORK_UNAVAILABLE) { runBlocking { sessions.revalidate(verificationApi { throw AuthException(AuthIssue.NETWORK_UNAVAILABLE) }) } }
        assertEquals(SessionValidity.RESTORED_UNVERIFIED, sessions.snapshot.value.validity)
        assertEquals(AuthIssue.NETWORK_UNAVAILABLE, sessions.snapshot.value.issue)
        assertEquals(0, store.writes)
        expect(AuthIssue.LOGIN_REQUIRED) { runBlocking { sessions.revalidate(verificationApi { fixtureSession("202") }) } }
        assertEquals(AuthIssue.LOGIN_REQUIRED, sessions.snapshot.value.issue)
        assertNull(store.persistent)
        assertEquals(1, store.clears)
        sessions.close()
    }

    @Test fun explicitRevalidationCanProduceAVerifiedGeneration() = runBlocking {
        val store = ControlledSessionStore(fixtureSession().stored)
        val sessions = SessionRepository(store)
        val old = sessions.restore().generation
        assertTrue(sessions.revalidate(verificationApi { fixtureSession() }))
        assertEquals(SessionValidity.VERIFIED, sessions.snapshot.value.validity)
        assertTrue(sessions.snapshot.value.generation > old)
        assertEquals(1, store.writes)
        sessions.close()
    }

    @Test fun accountRequestConcurrencyIsBoundedAndLogoutCancelsEveryOldRequest() = runBlocking {
        val store = ControlledSessionStore()
        val invalidations = mutableListOf<AccountIdentity?>()
        val sessions = SessionRepository(store) { invalidations.add(it) }
        sessions.commit(fixtureSession(), sessions.loginEpoch()) { it() }
        val tokens = mutableListOf<AccountRequestToken>()
        val requests = List(6) { launch(start = CoroutineStart.UNDISPATCHED) {
            sessions.authenticated { _, token ->
                tokens.add(token)
                awaitCancellation()
            }
        } }
        expect(AuthIssue.BUSY) { runBlocking { sessions.authenticated { _, _ -> Unit } } }
        val logout = sessions.beginLogout()
        requests.forEach {
            it.join()
            assertTrue(it.isCancelled)
        }
        assertTrue(tokens.none(sessions::isCurrent))
        sessions.finishLogout(logout)
        assertEquals("101", invalidations.last()?.accountId)
        sessions.close()
    }

    @Test fun serverLoginRequiredExpiresTheMatchingGenerationAndRemovesItsCredential() = runBlocking {
        val store = ControlledSessionStore()
        val sessions = SessionRepository(store)
        sessions.commit(fixtureSession(), sessions.loginEpoch()) { it() }
        var token: AccountRequestToken? = null
        expect(AuthIssue.LOGIN_REQUIRED) { runBlocking { sessions.authenticated { _, captured ->
            token = captured
            throw AuthException(AuthIssue.LOGIN_REQUIRED)
        } } }
        assertFalse(sessions.isCurrent(requireNotNull(token)))
        assertNull(store.persistent)
        assertEquals(AuthIssue.LOGIN_REQUIRED, sessions.snapshot.value.issue)
        assertFalse(sessions.expire(requireNotNull(token)))
        sessions.close()
    }

    @Test fun accountSwitchAndCloseInvalidateTokensAndLeaveTheStoredSessionForExplicitRestore(): Unit = runBlocking {
        val store = ControlledSessionStore()
        val sessions = SessionRepository(store)
        sessions.commit(fixtureSession(), sessions.loginEpoch()) { it() }
        var token: AccountRequestToken? = null
        val old = launch(start = CoroutineStart.UNDISPATCHED) {
            sessions.authenticated { _, captured ->
                token = captured
                awaitCancellation()
            }
        }
        sessions.commit(fixtureSession("202"), sessions.loginEpoch()) { it() }
        old.join()
        assertTrue(old.isCancelled)
        assertFalse(sessions.isCurrent(requireNotNull(token)))
        sessions.close()
        assertEquals(SessionValidity.UNAVAILABLE, sessions.snapshot.value.validity)
        assertEquals(AuthIssue.CLOSED, sessions.snapshot.value.issue)
        assertEquals("202", store.persistent?.account?.accountId)
        expect(AuthIssue.CLOSED) { runBlocking { sessions.authenticated { _, _ -> Unit } } }
    }

    private fun verificationApi(verify: suspend () -> VerifiedSession): NeteaseLoginApi = object : NeteaseLoginApi {
        override suspend fun createKey(): LoginQrKey = error("QR was not requested")
        override suspend fun checkKey(key: LoginQrKey): QrCheckResult = error("QR was not requested")
        override suspend fun verify(credential: SessionCredential): VerifiedSession = verify()
    }
    private fun expect(issue: AuthIssue, action: () -> Unit): AuthException = assertThrows(AuthException::class.java) { action() }.also { assertEquals(issue, it.issue) }
}
