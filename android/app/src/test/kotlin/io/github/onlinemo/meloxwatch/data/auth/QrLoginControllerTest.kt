package io.github.onlinemo.meloxwatch.data.auth

import io.github.onlinemo.meloxwatch.data.netease.LoginQrKey
import io.github.onlinemo.meloxwatch.data.netease.NeteaseLoginApi
import io.github.onlinemo.meloxwatch.data.netease.QrCheckPhase
import io.github.onlinemo.meloxwatch.data.netease.QrCheckResult
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrLoginControllerTest {
    @Test(timeout = 5_000) fun offlineAndHiddenPagesDoNotStartRequests() = runBlocking {
        withFixture { f ->
            f.controller.enter(false)
            assertEquals(QrLoginPhase.OFFLINE, f.controller.state.value.phase)
            assertEquals(0, f.api.keyCount)
            f.controller.leave()
            f.controller.networkChanged(true)
            assertEquals(QrLoginPhase.HIDDEN, f.controller.state.value.phase)
            assertEquals(0, f.api.keyCount)
            assertEquals(0, f.api.checkCount)
        }
    }

    @Test(timeout = 5_000) fun visiblePollingUsesTwoAndAHalfSecondsAndKeepsConfirmationState() = runBlocking {
        withFixture { f ->
            showKey(f)
            val first = nextCheck(f)
            first.result.complete(QrCheckResult(QrCheckPhase.WAITING_CONFIRMATION))
            f.controller.state.first { it.phase == QrLoginPhase.WAITING_CONFIRMATION }
            val second = nextCheck(f)
            second.result.complete(QrCheckResult(QrCheckPhase.WAITING_SCAN))
            yield()
            assertEquals(QrLoginPhase.WAITING_CONFIRMATION, f.controller.state.value.phase)
            assertEquals(2, f.api.checkCount)
            assertEquals(0, f.store.seals)
        }
    }

    @Test(timeout = 5_000) fun manualRefreshRejectsALateAuthorizedCallbackFromTheOldGeneration() = runBlocking {
        withFixture { f ->
            f.api.lateCheck = true
            showKey(f)
            val oldGeneration = f.controller.state.value.generation
            val old = nextCheck(f)
            f.controller.refresh()
            val next = f.api.keys.receive()
            next.result.complete(LoginQrKey.parse("fixture-second"))
            f.controller.state.first { it.phase == QrLoginPhase.WAITING_SCAN && it.generation > oldGeneration }
            old.result.complete(authorized())
            yield()
            assertEquals("fixture-second", f.controller.state.value.qr?.requestValue())
            assertEquals(0, f.api.verifyCount)
            assertEquals(0, f.store.seals)
            assertEquals(0, f.store.writes)
        }
    }

    @Test(timeout = 5_000) fun expirationRefreshesOnlyOnceAndUsesANewGenerationWithoutResettingTheRun() = runBlocking {
        withFixture { f ->
            showKey(f)
            val firstGeneration = f.controller.state.value.generation
            nextCheck(f).result.complete(QrCheckResult(QrCheckPhase.EXPIRED))
            val second = f.api.keys.receive()
            second.result.complete(LoginQrKey.parse("fixture-second"))
            f.controller.state.first { it.phase == QrLoginPhase.WAITING_SCAN && it.generation > firstGeneration }
            nextCheck(f).result.complete(QrCheckResult(QrCheckPhase.EXPIRED))
            f.controller.state.first { it.phase == QrLoginPhase.EXPIRED }
            f.controller.networkChanged(false)
            f.controller.networkChanged(true)
            yield()
            assertEquals(2, f.api.keyCount)
            assertNull(f.controller.state.value.qr)
            assertEquals(QrLoginPhase.EXPIRED, f.controller.state.value.phase)
        }
    }

    @Test(timeout = 5_000) fun leavingDuringNoncooperativeAccountVerificationCannotSealOrSaveALogin() = runBlocking {
        withFixture { f ->
            f.api.lateVerify = true
            showKey(f)
            nextCheck(f).result.complete(authorized())
            val verify = f.api.verifications.receive()
            f.controller.leave()
            verify.result.complete(fixtureSession())
            yield()
            assertEquals(QrLoginPhase.HIDDEN, f.controller.state.value.phase)
            assertEquals(0, f.store.seals)
            assertEquals(0, f.store.writes)
            assertNull(f.sessions.snapshot.value.account)
        }
    }

    @Test(timeout = 5_000) fun invalidAccountVerificationDoesNotSaveTheCookieAndItsErrorIsTerminal() = runBlocking {
        withFixture { f ->
            showKey(f)
            nextCheck(f).result.complete(authorized())
            f.api.verifications.receive().result.completeExceptionally(AuthException(AuthIssue.LOGIN_REQUIRED))
            val state = f.controller.state.first { it.phase == QrLoginPhase.ERROR }
            assertEquals(AuthIssue.LOGIN_REQUIRED, state.issue)
            assertNull(state.qr)
            f.controller.networkChanged(false)
            f.controller.networkChanged(true)
            yield()
            assertEquals(1, f.api.keyCount)
            assertEquals(1, f.api.checkCount)
            assertEquals(0, f.store.seals)
            assertEquals(0, f.store.writes)
        }
    }

    @Test(timeout = 5_000) fun normalLoginVerifiesBeforeSavingAndCannotRepollAfterSuccess() = runBlocking {
        withFixture { f ->
            showKey(f)
            nextCheck(f).result.complete(authorized())
            val verify = f.api.verifications.receive()
            assertEquals(0, f.store.seals)
            verify.result.complete(fixtureSession())
            f.controller.state.first { it.phase == QrLoginPhase.SIGNED_IN }
            assertEquals(SessionValidity.VERIFIED, f.sessions.snapshot.value.validity)
            assertEquals(1, f.store.writes)
            assertNull(f.controller.state.value.qr)
            f.controller.networkChanged(false)
            f.controller.networkChanged(true)
            yield()
            assertEquals(1, f.api.keyCount)
            assertEquals(1, f.api.checkCount)
            assertEquals(1, f.api.verifyCount)
        }
    }

    @Test(timeout = 5_000) fun acceptedPersistenceCanFinishOfflineAndReportsItsActualResult() = runBlocking {
        withFixture { f ->
            val release = CompletableDeferred<Unit>()
            f.store.beforeWrite = { release.await() }
            showKey(f)
            nextCheck(f).result.complete(authorized())
            f.api.verifications.receive().result.complete(fixtureSession())
            f.store.writeStarted.await()
            assertEquals(QrLoginPhase.SAVING, f.controller.state.value.phase)
            f.controller.networkChanged(false)
            f.clock.now = QrLoginLimits.FLOW_LIFETIME_MS + 1
            release.complete(Unit)
            f.controller.state.first { it.phase == QrLoginPhase.SIGNED_IN }
            f.controller.networkChanged(true)
            assertEquals(1, f.api.keyCount)
            assertEquals(1, f.api.checkCount)
            assertEquals(1, f.store.writes)
            assertEquals(SessionValidity.VERIFIED, f.sessions.snapshot.value.validity)
        }
    }

    @Test(timeout = 5_000) fun persistenceFailureIsAnErrorAndCannotCreateASuccessfulSession() = runBlocking {
        withFixture { f ->
            f.store.writeFailure = AuthIssue.STORAGE_UNAVAILABLE
            showKey(f)
            nextCheck(f).result.complete(authorized())
            f.api.verifications.receive().result.complete(fixtureSession())
            val state = f.controller.state.first { it.phase == QrLoginPhase.ERROR }
            assertEquals(AuthIssue.STORAGE_UNAVAILABLE, state.issue)
            assertNull(f.sessions.snapshot.value.account)
            assertEquals(SessionValidity.SIGNED_OUT, f.sessions.snapshot.value.validity)
            assertEquals(0, f.store.writes)
        }
    }

    @Test(timeout = 5_000) fun logoutAfterAcceptanceClearsTheSavedBytesAndKeepsTheOldPageHidden() = runBlocking {
        withFixture { f ->
            val release = CompletableDeferred<Unit>()
            f.store.beforeWrite = { release.await() }
            showKey(f)
            nextCheck(f).result.complete(authorized())
            f.api.verifications.receive().result.complete(fixtureSession())
            f.store.writeStarted.await()
            val logout = f.controller.logout()
            release.complete(Unit)
            logout.join()
            assertEquals(QrLoginPhase.HIDDEN, f.controller.state.value.phase)
            assertEquals(SessionValidity.SIGNED_OUT, f.sessions.snapshot.value.validity)
            assertEquals(1, f.store.writes)
            assertEquals(1, f.store.clears)
            assertNull(f.store.persistent)
        }
    }

    @Test(timeout = 5_000) fun cancelledKeyRequestHasAnUnknownOutcomeAndConnectivityCannotRequestUnboundedKeys() = runBlocking {
        withFixture { f ->
            f.api.lateKey = true
            f.controller.enter(true)
            val old = f.api.keys.receive()
            f.controller.networkChanged(false)
            f.controller.networkChanged(true)
            f.controller.state.first { it.phase == QrLoginPhase.ERROR }
            old.result.complete(LoginQrKey.parse("late-key"))
            yield()
            assertEquals(1, f.api.keyCount)
            assertNull(f.controller.state.value.qr)
            assertEquals(0, f.api.checkCount)
        }
    }

    @Test(timeout = 5_000) fun offlineTimeStillConsumesTheOriginalFlowDeadline() = runBlocking {
        withFixture { f ->
            f.controller.enter(false)
            f.clock.now = QrLoginLimits.FLOW_LIFETIME_MS + 1
            f.controller.networkChanged(true)
            f.controller.state.first { it.phase == QrLoginPhase.EXPIRED }
            assertEquals(0, f.api.keyCount)
            assertEquals(0, f.api.checkCount)
        }
    }

    @Test(timeout = 5_000) fun closingCannotLeaveAWindowForAnotherEnterOrNetworkRestart() = runBlocking {
        withFixture { f ->
            f.controller.enter(false)
            f.controller.close()
            f.controller.enter(true)
            f.controller.refresh()
            f.controller.networkChanged(true)
            yield()
            assertEquals(QrLoginPhase.HIDDEN, f.controller.state.value.phase)
            assertEquals(0, f.api.keyCount)
        }
    }

    private suspend fun showKey(f: Fixture) {
        f.controller.enter(true)
        f.api.keys.receive().result.complete(LoginQrKey.parse("fixture-first"))
        f.controller.state.first { it.phase == QrLoginPhase.WAITING_SCAN }
    }
    private suspend fun nextCheck(f: Fixture): CheckCall {
        val pause = f.clock.pauses.receive()
        assertEquals(QrLoginLimits.POLL_INTERVAL_MS, pause.milliseconds)
        f.clock.now += pause.milliseconds
        pause.resume.complete(Unit)
        return f.api.checks.receive()
    }
    private fun authorized(): QrCheckResult = QrCheckResult(QrCheckPhase.AUTHORIZED, fixtureSession().stored.credential)
    private suspend fun withFixture(action: suspend (Fixture) -> Unit) = coroutineScope {
        val f = Fixture(coroutineContext)
        try { action(f) } finally { f.close() }
    }
    private class Fixture(context: CoroutineContext) {
        val owner = SupervisorJob(context[Job])
        val scope = CoroutineScope(context + owner)
        val api = ControlledLoginApi()
        val clock = ControlledClock()
        val store = ControlledSessionStore()
        val sessions = SessionRepository(store)
        val controller = QrLoginController(api, sessions, scope, clock)
        suspend fun close() {
            controller.close()
            sessions.close()
            owner.cancelAndJoin()
        }
    }
    private class Pause(val milliseconds: Long, val resume: CompletableDeferred<Unit>)
    private class ControlledClock : LoginClock {
        var now = 0L
        val pauses = Channel<Pause>(8)
        override fun nowElapsedMs(): Long = now
        override suspend fun wait(milliseconds: Long) {
            assertTrue(milliseconds > 0)
            val resume = CompletableDeferred<Unit>()
            pauses.send(Pause(milliseconds, resume))
            resume.await()
        }
    }
    private class KeyCall(val result: CompletableDeferred<LoginQrKey> = CompletableDeferred())
    private class CheckCall(val key: LoginQrKey, val result: CompletableDeferred<QrCheckResult> = CompletableDeferred())
    private class VerifyCall(val result: CompletableDeferred<VerifiedSession> = CompletableDeferred())
    private class ControlledLoginApi : NeteaseLoginApi {
        val keys = Channel<KeyCall>(4)
        val checks = Channel<CheckCall>(4)
        val verifications = Channel<VerifyCall>(4)
        var keyCount = 0
        var checkCount = 0
        var verifyCount = 0
        var lateKey = false
        var lateCheck = false
        var lateVerify = false
        override suspend fun createKey(): LoginQrKey {
            keyCount++
            val call = KeyCall()
            keys.send(call)
            return await(call.result, lateKey)
        }
        override suspend fun checkKey(key: LoginQrKey): QrCheckResult {
            checkCount++
            val call = CheckCall(key)
            checks.send(call)
            return await(call.result, lateCheck)
        }
        override suspend fun verify(credential: SessionCredential): VerifiedSession {
            assertTrue(credential.headerValue().startsWith("MUSIC_U=fixture"))
            verifyCount++
            val call = VerifyCall()
            verifications.send(call)
            return await(call.result, lateVerify)
        }
        private suspend fun <T> await(value: CompletableDeferred<T>, late: Boolean): T =
            if (late) withContext(NonCancellable) { value.await() } else value.await()
    }
}
