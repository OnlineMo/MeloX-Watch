package io.github.onlinemo.meloxwatch.data.online

import io.github.onlinemo.meloxwatch.data.auth.ControlledSessionStore
import io.github.onlinemo.meloxwatch.data.auth.SessionRepository
import io.github.onlinemo.meloxwatch.data.auth.fixtureSession
import io.github.onlinemo.meloxwatch.data.lyrics.online.DiskOnlineLyricsCache
import io.github.onlinemo.meloxwatch.data.lyrics.online.OnlineLyricsCacheKey
import io.github.onlinemo.meloxwatch.data.lyrics.online.OnlineLyricsOrigin
import io.github.onlinemo.meloxwatch.data.lyrics.online.OnlineLyricsText
import io.github.onlinemo.meloxwatch.data.lyrics.online.OnlineLyricsIssue
import java.io.IOException
import io.github.onlinemo.meloxwatch.data.netease.NeteaseAuthApi
import io.github.onlinemo.meloxwatch.data.netease.NeteaseEapi
import io.github.onlinemo.meloxwatch.data.network.HttpExchange
import io.github.onlinemo.meloxwatch.model.onlineOpaqueHash
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Explicit logout/account replacement waits for owned cleanup; no QR, account network or local data cleanup is used. */
@OptIn(ExperimentalCoroutinesApi::class)
class OnlineLyricsLogoutTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun key(account: String) = OnlineLyricsCacheKey.of(onlineOpaqueHash("netease:$account"), "201", OnlineLyricsOrigin.NETEASE_LRC, null)

    @Test fun cancelledLogoutWaiterStillCompletesTheExactAccountsCacheCleanup() = runTest {
        val cache = DiskOnlineLyricsCache(temporary.newFolder())
        cache.write(key("101"), OnlineLyricsText("account one".toByteArray(), null)) { true }
        cache.write(key("102"), OnlineLyricsText("account two".toByteArray(), null)) { true }
        val local = temporary.newFile("library-sentinel.txt").apply { writeText("local library remains") }
        val store = ControlledSessionStore()
        val sessions = SessionRepository(store)
        sessions.commit(fixtureSession(), sessions.loginEpoch()) { accept -> accept() }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val ids = ArrayList<String>()
        var nativeCalls = 0
        val login = OnlineLoginPresenter(NeteaseAuthApi(NeteaseEapi(HttpExchange {
            nativeCalls++
            error("Logout must not create a native request")
        })), sessions, this) { id ->
            ids += id
            entered.complete(Unit)
            release.await()
            cache.clearOwner(onlineOpaqueHash("netease:$id"))
            null
        }
        try {
            val logout = login.logout()
            runCurrent()
            assertTrue(entered.isCompleted)
            assertEquals(1, store.clears)
            logout.cancel()
            runCurrent()
            assertFalse(logout.isCompleted)
            release.complete(Unit)
            logout.join()
            assertNull(cache.read(key("101")))
            assertEquals("account two", cache.read(key("102"))?.primary?.toString(Charsets.UTF_8))
            assertTrue(ids.isNotEmpty() && ids.all { it == "101" })
            assertEquals(0, nativeCalls)
            assertEquals("local library remains", local.readText())
            assertNull(login.lyricsCleanupIssue)
        } finally { release.complete(Unit); login.close(); sessions.close(); runCurrent() }
    }

    @Test fun accountReplacementClearsOnlyTheOldAccountAndSessionClosePreservesTheNewAccountsCache() = runTest {
        val cache = DiskOnlineLyricsCache(temporary.newFolder())
        cache.write(key("101"), OnlineLyricsText("account one".toByteArray(), null)) { true }
        cache.write(key("102"), OnlineLyricsText("account two".toByteArray(), null)) { true }
        val sessions = SessionRepository(ControlledSessionStore())
        sessions.commit(fixtureSession(), sessions.loginEpoch()) { accept -> accept() }
        val cleared = CompletableDeferred<Unit>()
        val ids = ArrayList<String>()
        val login = OnlineLoginPresenter(NeteaseAuthApi(NeteaseEapi(HttpExchange { error("No account HTTP during owned cleanup") })),
            sessions, this) { id ->
            ids += id
            cache.clearOwner(onlineOpaqueHash("netease:$id"))
            cleared.complete(Unit)
            null
        }
        try {
            runCurrent()
            sessions.commit(fixtureSession("102"), sessions.loginEpoch()) { accept -> accept() }
            cleared.await()
            assertEquals(listOf("101"), ids)
            assertNull(cache.read(key("101")))
            sessions.close()
            runCurrent()
            assertEquals(listOf("101"), ids)
            assertEquals("account two", cache.read(key("102"))?.primary?.toString(Charsets.UTF_8))
        } finally { login.close(); sessions.close(); runCurrent() }
    }

    @Test fun cleanupFailureIsAStableIssueAfterTheSessionLogoutAndDoesNotEscapeTheUiJob() = runTest {
        val store = ControlledSessionStore()
        val sessions = SessionRepository(store)
        sessions.commit(fixtureSession(), sessions.loginEpoch()) { accept -> accept() }
        val login = OnlineLoginPresenter(NeteaseAuthApi(NeteaseEapi(HttpExchange { error("No logout network") })),
            sessions, this) { throw IOException("synthetic cleanup failure") }
        try {
            login.logout().join()
            assertEquals(1, store.clears)
            assertNull(store.persistent)
            assertEquals(OnlineLyricsIssue.READ_FAILED, login.lyricsCleanupIssue)
        } finally { login.close(); sessions.close(); runCurrent() }
    }
}
