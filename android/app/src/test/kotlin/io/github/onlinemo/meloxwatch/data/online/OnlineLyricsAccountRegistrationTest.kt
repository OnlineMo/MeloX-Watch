package io.github.onlinemo.meloxwatch.data.online

import io.github.onlinemo.meloxwatch.data.auth.AccountRequestToken
import io.github.onlinemo.meloxwatch.data.auth.AuthIssue
import io.github.onlinemo.meloxwatch.data.auth.ControlledSessionStore
import io.github.onlinemo.meloxwatch.data.auth.SessionRepository
import io.github.onlinemo.meloxwatch.data.auth.SessionValidity
import io.github.onlinemo.meloxwatch.data.auth.fixtureSession
import io.github.onlinemo.meloxwatch.data.lyrics.online.DiskOnlineLyricsCache
import io.github.onlinemo.meloxwatch.data.lyrics.online.OnlineLyricsCacheKey
import io.github.onlinemo.meloxwatch.data.lyrics.online.OnlineLyricsIssue
import io.github.onlinemo.meloxwatch.data.lyrics.online.OnlineLyricsOrigin
import io.github.onlinemo.meloxwatch.data.lyrics.online.OnlineLyricsText
import io.github.onlinemo.meloxwatch.data.netease.NeteaseAuthApi
import io.github.onlinemo.meloxwatch.data.netease.NeteaseEapi
import io.github.onlinemo.meloxwatch.data.network.HttpExchange
import io.github.onlinemo.meloxwatch.model.onlineOpaqueHash
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real owned cache bytes and session mutations; the initial observer cannot rely on a scheduler turn. */
@OptIn(ExperimentalCoroutinesApi::class)
class OnlineLyricsAccountRegistrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test(timeout = 5_000) fun initialSwitchBeforeTheFirstSchedulerTurnClearsOnlyTheOriginalAccount(): Unit = runTest {
        val fixture = fixture()
        try {
            assertTrue(fixture.sessions.commit(fixtureSession("102"), fixture.sessions.loginEpoch()) { accept -> accept() })
            assertTrue(fixture.attempts.isEmpty())
            runCurrent()
            assertEquals(listOf("101"), fixture.attempts)
            fixture.cleared.await()
            assertNull(fixture.cache.read(fixture.firstKey))
            assertEquals("102", fixture.sessions.snapshot.value.account?.accountId)
            assertNull(fixture.login.lyricsCleanupIssue)
            fixture.assertOtherDataPreserved()
        } finally { fixture.close(); runCurrent() }
    }

    @Test(timeout = 5_000) fun initialExpiryBeforeTheFirstSchedulerTurnClearsOnlyTheOriginalAccount(): Unit = runTest {
        val fixture = fixture()
        try {
            assertTrue(fixture.sessions.expire(fixture.token))
            assertTrue(fixture.attempts.isEmpty())
            runCurrent()
            assertEquals(listOf("101"), fixture.attempts)
            fixture.cleared.await()
            assertNull(fixture.cache.read(fixture.firstKey))
            assertNull(fixture.sessions.snapshot.value.account)
            assertEquals(AuthIssue.LOGIN_REQUIRED, fixture.sessions.snapshot.value.issue)
            assertEquals(1, fixture.store.clears)
            assertNull(fixture.login.lyricsCleanupIssue)
            fixture.assertOtherDataPreserved()
        } finally { fixture.close(); runCurrent() }
    }

    @Test(timeout = 5_000) fun closedSessionBeforeTheFirstSchedulerTurnKeepsItsCacheForRestart(): Unit = runTest {
        val fixture = fixture()
        var restored: SessionRepository? = null
        try {
            fixture.sessions.close()
            runCurrent()
            assertTrue(fixture.attempts.isEmpty())
            assertEquals(AuthIssue.CLOSED, fixture.sessions.snapshot.value.issue)
            assertEquals(0, fixture.store.clears)
            assertArrayEquals(fixture.firstCacheBytes, fixture.firstFile.readBytes())
            restored = SessionRepository(fixture.store)
            val account = restored.restore()
            assertEquals("101", account.account?.accountId)
            assertEquals(SessionValidity.RESTORED_UNVERIFIED, account.validity)
            assertArrayEquals(fixture.firstText.primary, fixture.cache.read(fixture.firstKey)?.primary)
            assertNull(fixture.login.lyricsCleanupIssue)
            fixture.assertOtherDataPreserved()
        } finally { restored?.close(); fixture.close(); runCurrent() }
    }

    @Test(timeout = 5_000) fun initialSwitchCleanupFailureKeepsTheCacheAndReportsTheOriginalReadIssue(): Unit = runTest {
        val fixture = fixture(IOException("fixture cache failure"))
        try {
            assertTrue(fixture.sessions.commit(fixtureSession("102"), fixture.sessions.loginEpoch()) { accept -> accept() })
            assertTrue(fixture.attempts.isEmpty())
            runCurrent()
            assertEquals(listOf("101"), fixture.attempts)
            assertEquals(OnlineLyricsIssue.READ_FAILED, fixture.login.lyricsCleanupIssue)
            assertEquals("102", fixture.sessions.snapshot.value.account?.accountId)
            assertArrayEquals(fixture.firstCacheBytes, fixture.firstFile.readBytes())
            fixture.assertOtherDataPreserved()
        } finally { fixture.close(); runCurrent() }
    }

    @Test(timeout = 5_000) fun initialExpiryCleanupMemoryFailureKeepsTheCacheAndReportsTheOriginalMemoryIssue(): Unit = runTest {
        val fixture = fixture(OutOfMemoryError("fixture cache failure"))
        try {
            assertTrue(fixture.sessions.expire(fixture.token))
            assertTrue(fixture.attempts.isEmpty())
            runCurrent()
            assertEquals(listOf("101"), fixture.attempts)
            assertEquals(OnlineLyricsIssue.MEMORY_LIMIT, fixture.login.lyricsCleanupIssue)
            assertEquals(AuthIssue.LOGIN_REQUIRED, fixture.sessions.snapshot.value.issue)
            assertArrayEquals(fixture.firstCacheBytes, fixture.firstFile.readBytes())
            fixture.assertOtherDataPreserved()
        } finally { fixture.close(); runCurrent() }
    }

    @Test(timeout = 5_000) fun closedSessionNeverInvokesAFailingCleanupOrReplacesItsOwnIssue(): Unit = runTest {
        val fixture = fixture(IOException("cleanup must stay unused during shutdown"))
        try {
            fixture.sessions.close()
            runCurrent()
            assertTrue(fixture.attempts.isEmpty())
            assertEquals(AuthIssue.CLOSED, fixture.sessions.snapshot.value.issue)
            assertNull(fixture.login.lyricsCleanupIssue)
            assertEquals(0, fixture.store.clears)
            assertArrayEquals(fixture.firstCacheBytes, fixture.firstFile.readBytes())
            fixture.assertOtherDataPreserved()
        } finally { fixture.close(); runCurrent() }
    }

    private suspend fun TestScope.fixture(cleanupFailure: Throwable? = null): RegistrationFixture {
        val directory = temporary.newFolder("owned-cache")
        val cache = DiskOnlineLyricsCache(directory)
        val firstKey = key("101")
        val otherKey = key("102")
        val firstText = OnlineLyricsText("[00:01]account one".toByteArray(), "[00:01]translation one".toByteArray())
        assertTrue(cache.write(firstKey, firstText) { true })
        assertTrue(cache.write(otherKey, OnlineLyricsText("[00:01]account two".toByteArray(), null)) { true })
        val firstFile = directory.resolve(firstKey.fileName)
        val otherFile = directory.resolve(otherKey.fileName)
        val userLyrics = temporary.newFile("user-original.lrc").apply { writeText("[00:01]keep user lyrics") }
        val library = temporary.newFile("library.fixture").apply { writeBytes(ByteArray(128) { it.toByte() }) }
        val sentinels = listOf(userLyrics, library).map { it to it.readBytes() }
        val store = ControlledSessionStore()
        val sessions = SessionRepository(store)
        assertTrue(sessions.commit(fixtureSession(), sessions.loginEpoch()) { accept -> accept() })
        val token = sessions.authenticated { _, captured -> captured }
        val attempts = ArrayList<String>()
        val cleared = CompletableDeferred<Unit>()
        val httpCalls = AtomicInteger()
        val observerScope = CoroutineScope(coroutineContext + StandardTestDispatcher(testScheduler))
        val login = OnlineLoginPresenter(NeteaseAuthApi(NeteaseEapi(HttpExchange {
            httpCalls.incrementAndGet()
            error("Account cache cleanup must not create a network request")
        })), sessions, observerScope) { account ->
            attempts += account
            cleanupFailure?.let { throw it }
            cache.clearOwner(onlineOpaqueHash("netease:$account"))
            cleared.complete(Unit)
            null
        }
        return RegistrationFixture(cache, firstKey, firstText, firstFile, firstFile.readBytes(), otherFile,
            otherFile.readBytes(), sentinels, store, sessions, token, login, attempts, cleared, httpCalls)
    }

    private class RegistrationFixture(
        val cache: DiskOnlineLyricsCache,
        val firstKey: OnlineLyricsCacheKey,
        val firstText: OnlineLyricsText,
        val firstFile: File,
        val firstCacheBytes: ByteArray,
        private val otherFile: File,
        private val otherCacheBytes: ByteArray,
        private val sentinels: List<Pair<File, ByteArray>>,
        val store: ControlledSessionStore,
        val sessions: SessionRepository,
        val token: AccountRequestToken,
        val login: OnlineLoginPresenter,
        val attempts: ArrayList<String>,
        val cleared: CompletableDeferred<Unit>,
        private val httpCalls: AtomicInteger,
    ) : AutoCloseable {
        fun assertOtherDataPreserved() {
            assertArrayEquals(otherCacheBytes, otherFile.readBytes())
            for ((file, bytes) in sentinels) assertArrayEquals(bytes, file.readBytes())
            assertEquals(0, httpCalls.get())
        }
        override fun close() { login.close(); sessions.close() }
    }

    private fun key(account: String) = OnlineLyricsCacheKey.of(onlineOpaqueHash("netease:$account"), "201",
        OnlineLyricsOrigin.NETEASE_LRC, null)
}
