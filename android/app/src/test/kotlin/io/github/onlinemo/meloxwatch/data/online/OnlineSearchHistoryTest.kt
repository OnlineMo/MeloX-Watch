package io.github.onlinemo.meloxwatch.data.online

import io.github.onlinemo.meloxwatch.data.auth.ControlledSessionStore
import io.github.onlinemo.meloxwatch.data.auth.SessionRepository
import io.github.onlinemo.meloxwatch.data.auth.SessionValidity
import io.github.onlinemo.meloxwatch.data.auth.fixtureSession
import io.github.onlinemo.meloxwatch.data.netease.NeteaseAuthApi
import io.github.onlinemo.meloxwatch.data.netease.NeteaseEapi
import io.github.onlinemo.meloxwatch.data.network.HttpExchange
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real AtomicFile bytes and account sessions; definitions do not substitute for device or cloud execution. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30], manifest = Config.NONE)
class OnlineSearchHistoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun queriesReopenWithTheSavedAccountAndRemainBoundedDeduplicatedAndLocal() = runBlocking {
        val file = File(temporary.root, "history")
        val store = ControlledSessionStore()
        val sessions = SessionRepository(store)
        try {
            sessions.commit(fixtureSession(), sessions.loginEpoch()) { accept -> accept() }
            val history = OnlineSearchHistory.controlled(file, sessions)
            assertFalse(file.exists())
            val scope = sessions.snapshot.value
            for (number in 0..11) history.record(scope, "Fixture search $number")
            val expected = (11 downTo 4).map { "Fixture search $it" }
            assertEquals(expected, history.read(scope).queries)
            history.record(scope, "Fixture search 7")
            assertEquals(listOf("Fixture search 7") + expected.filter { it != "Fixture search 7" }, history.read(scope).queries)
            val query = "歌".repeat(256)
            history.record(scope, query)
            assertEquals(query, history.read(scope).queries.first())
            assertEquals(OnlineSearchHistory.MAX_QUERIES, history.read(scope).queries.size)
            assertTrue(file.length() <= OnlineSearchHistory.MAX_BYTES)
            val plaintext = file.readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(plaintext.contains("MUSIC_U"))
            assertFalse(plaintext.contains("Fixture account"))
            assertFalse(plaintext.contains("netease:101"))
            assertFalse(plaintext.contains("https://"))
            val restored = SessionRepository(ControlledSessionStore(checkNotNull(store.persistent)))
            try {
                val account = restored.restore()
                assertEquals(SessionValidity.RESTORED_UNVERIFIED, account.validity)
                val reopened = OnlineSearchHistory.controlled(file, restored)
                assertEquals(history.read(scope).queries, reopened.read(account).queries)
                assertNull(reopened.clearAccount(account))
                assertTrue(reopened.read(account).queries.isEmpty())
            } finally { restored.close() }
        } finally { sessions.close() }
    }

    @Test fun anotherAccountAndASameAccountReloginCannotBeErasedByAnOldScopeOrView() = runBlocking {
        val file = File(temporary.root, "history")
        val sessions = SessionRepository(ControlledSessionStore())
        try {
            sessions.commit(fixtureSession(), sessions.loginEpoch()) { accept -> accept() }
            val history = OnlineSearchHistory.controlled(file, sessions)
            val first = sessions.snapshot.value
            val oldView = history.record(first, "Shared fixture term")
            val oldBytes = file.readBytes()
            sessions.commit(fixtureSession("102"), sessions.loginEpoch()) { accept -> accept() }
            assertTrue(history.read(sessions.snapshot.value).queries.isEmpty())
            assertArrayEquals(oldBytes, file.readBytes())
            history.record(sessions.snapshot.value, "Shared fixture term")
            assertNull(history.clearAccount(first))
            assertEquals(listOf("Shared fixture term"), history.read(sessions.snapshot.value).queries)
            expect(OnlineSearchHistoryIssue.STALE_ACCOUNT) { history.clear(oldView) }
            sessions.commit(fixtureSession(), sessions.loginEpoch()) { accept -> accept() }
            history.record(sessions.snapshot.value, "New same-account fixture")
            val newBytes = file.readBytes()
            assertNull(history.clearAccount(first))
            assertArrayEquals(newBytes, file.readBytes())
            val earlier = history.read(sessions.snapshot.value)
            history.record(sessions.snapshot.value, "Later fixture")
            expect(OnlineSearchHistoryIssue.STALE_ACCOUNT) { history.clear(earlier) }
            assertEquals(listOf("Later fixture", "New same-account fixture"), history.read(sessions.snapshot.value).queries)
        } finally { sessions.close() }
    }

    @Test fun corruptionAndOversizeArePreservedUntilExplicitClearAndARepairedFileRejectsTheOldDamageAction() = runBlocking {
        val file = File(temporary.root, "history")
        val sentinel = temporary.newFile("local-library").apply { writeText("preserve local music") }
        val sessions = SessionRepository(ControlledSessionStore())
        try {
            sessions.commit(fixtureSession(), sessions.loginEpoch()) { accept -> accept() }
            val history = OnlineSearchHistory.controlled(file, sessions)
            for (bytes in listOf(byteArrayOf(1, 2, 3), ByteArray(OnlineSearchHistory.MAX_BYTES + 1))) {
                file.writeBytes(bytes)
                val damaged = history.read(sessions.snapshot.value)
                assertEquals(OnlineSearchHistoryIssue.CORRUPT, damaged.issue)
                assertTrue(damaged.queries.isEmpty())
                assertArrayEquals(bytes, file.readBytes())
                expect(OnlineSearchHistoryIssue.CORRUPT) { history.record(sessions.snapshot.value, "Fixture search") }
                assertArrayEquals(bytes, file.readBytes())
                assertTrue(history.clear(damaged).queries.isEmpty())
                assertFalse(file.exists())
                history.record(sessions.snapshot.value, "Repaired fixture")
                expect(OnlineSearchHistoryIssue.STALE_ACCOUNT) { history.clear(damaged) }
                assertEquals(listOf("Repaired fixture"), history.read(sessions.snapshot.value).queries)
            }
            assertEquals("preserve local music", sentinel.readText())
        } finally { sessions.close() }
    }

    @Test fun aFailedWritePreservesTheLastSnapshotAndAnUnwritableParentIsReported() = runBlocking {
        val file = File(temporary.root, "history")
        val sessions = SessionRepository(ControlledSessionStore())
        try {
            sessions.commit(fixtureSession(), sessions.loginEpoch()) { accept -> accept() }
            val good = OnlineSearchHistory.controlled(file, sessions)
            good.record(sessions.snapshot.value, "Original fixture")
            val before = file.readBytes()
            val failing = OnlineSearchHistory.controlled(file, sessions, afterStartWrite = { throw IOException("synthetic write boundary") })
            expect(OnlineSearchHistoryIssue.WRITE_FAILED) { failing.record(sessions.snapshot.value, "Failed fixture") }
            assertArrayEquals(before, file.readBytes())
            assertEquals(listOf("Original fixture"), good.read(sessions.snapshot.value).queries)
            val obstruction = temporary.newFile("not-a-directory").apply { writeText("retain obstruction") }
            val blocked = OnlineSearchHistory.controlled(File(obstruction, "history"), sessions)
            expect(OnlineSearchHistoryIssue.WRITE_FAILED) { blocked.record(sessions.snapshot.value, "Blocked fixture") }
            assertEquals("retain obstruction", obstruction.readText())
        } finally { sessions.close() }
    }

    @Test fun cancelledAcceptedWriteRetainsItsOwnerAndDuplicateCleanupReturnsTheActualSuccessfulReceipt() = runBlocking {
        val file = File(temporary.root, "history")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val armed = AtomicBoolean(false)
        val sessions = SessionRepository(ControlledSessionStore())
        try {
            sessions.commit(fixtureSession(), sessions.loginEpoch()) { accept -> accept() }
            val history = OnlineSearchHistory.controlled(file, sessions, afterStartWrite = {
                if (armed.compareAndSet(true, false)) {
                    entered.countDown()
                    check(release.await(5L, TimeUnit.SECONDS))
                }
            })
            val old = sessions.snapshot.value
            history.record(old, "Original fixture")
            armed.set(true)
            val writing = async(Dispatchers.Default) { history.record(old, "Accepted fixture") }
            assertTrue(entered.await(3L, TimeUnit.SECONDS))
            writing.cancel()
            assertFalse(writing.isCompleted)
            sessions.finishLogout(sessions.beginLogout())
            val cleanup = async(start = CoroutineStart.UNDISPATCHED) { history.clearAccount(old) }
            val duplicate = async(start = CoroutineStart.UNDISPATCHED) { history.clearAccount(old) }
            val cancelledWaiter = async(start = CoroutineStart.UNDISPATCHED) { history.clearAccount(old) }
            cancelledWaiter.cancel()
            cancelledWaiter.join()
            assertFalse(cleanup.isCompleted)
            assertFalse(duplicate.isCompleted)
            release.countDown()
            writing.join()
            assertTrue(writing.isCancelled)
            assertNull(cleanup.await())
            assertNull(duplicate.await())
            assertFalse(file.exists())
            assertTrue(history.read(sessions.snapshot.value).queries.isEmpty())
        } finally { release.countDown(); sessions.close() }
    }

    @Test fun incompletePendingBytesDoNotReplaceTheValidSnapshotAndClearTouchesOnlyReservedFiles() = runBlocking {
        val file = File(temporary.root, "history")
        val sentinel = temporary.newFile("local-library").apply { writeText("keep") }
        val sessions = SessionRepository(ControlledSessionStore())
        try {
            sessions.commit(fixtureSession(), sessions.loginEpoch()) { accept -> accept() }
            val history = OnlineSearchHistory.controlled(file, sessions)
            history.record(sessions.snapshot.value, "Original fixture")
            File(file.path + ".new").writeBytes(byteArrayOf(1, 2, 3))
            val view = history.read(sessions.snapshot.value)
            assertEquals(listOf("Original fixture"), view.queries)
            history.clear(view)
            assertFalse(file.exists())
            assertFalse(File(file.path + ".bak").exists())
            assertFalse(File(file.path + ".new").exists())
            assertEquals("keep", sentinel.readText())
        } finally { sessions.close() }
    }

    @Test(timeout = 20_000L) fun realRestorationRevalidationPreservesThePersistedLineageAndItsHistory() = runBlocking {
        val file = File(temporary.root, "history")
        val initialStore = ControlledSessionStore()
        val initial = SessionRepository(initialStore)
        var presenter: OnlineLoginPresenter? = null
        var restored: SessionRepository? = null
        var nativeCalls = 0
        try {
            initial.commit(fixtureSession(), initial.loginEpoch()) { accept -> accept() }
            OnlineSearchHistory.controlled(file, initial).record(initial.snapshot.value, "Previous boot fixture")
            val reopenedSessions = SessionRepository(ControlledSessionStore(checkNotNull(initialStore.persistent))).also { restored = it }
            val account = reopenedSessions.restore()
            val history = OnlineSearchHistory.controlled(file, reopenedSessions)
            assertEquals(listOf("Previous boot fixture"), history.read(account).queries)
            history.record(account, "Restored fixture")
            val api = NeteaseAuthApi(NeteaseEapi(HttpExchange { request ->
                check(request.url.endsWith("/eapi/w/nuser/account/get"))
                nativeCalls++
                catalogJson("{\"code\":200,\"profile\":{\"userId\":101,\"nickname\":\"Revalidated fixture account\"}}")
            }))
            presenter = OnlineLoginPresenter(api, reopenedSessions, this, searchHistory = history)
            assertTrue(reopenedSessions.revalidate(api))
            val verified = reopenedSessions.snapshot.value
            assertEquals(account.lineageId, verified.lineageId)
            assertEquals(SessionValidity.VERIFIED, verified.validity)
            assertEquals(1, nativeCalls)
            assertEquals(listOf("Restored fixture", "Previous boot fixture"), history.read(verified).queries)
            history.record(verified, "Verified fixture")
            assertEquals(listOf("Verified fixture", "Restored fixture", "Previous boot fixture"), history.read(verified).queries)
        } finally { presenter?.close(); restored?.close(); initial.close() }
    }

    @Test(timeout = 20_000L) fun logoutRetiresOldWordsBeforeBlockedLyricCleanupAndCannotRetagThemIntoTheSameAccountRelogin() = runBlocking {
        val file = File(temporary.root, "history")
        val sessions = SessionRepository(ControlledSessionStore())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var presenter: OnlineLoginPresenter? = null
        var nativeCalls = 0
        try {
            sessions.commit(fixtureSession(), sessions.loginEpoch()) { accept -> accept() }
            val history = OnlineSearchHistory.controlled(file, sessions)
            history.record(sessions.snapshot.value, "Retired fixture")
            val login = OnlineLoginPresenter(NeteaseAuthApi(NeteaseEapi(HttpExchange { nativeCalls++; error("No logout HTTP") })),
                sessions, this, searchHistory = history) {
                entered.complete(Unit)
                release.await()
                null
            }.also { presenter = it }
            val logout = login.logout()
            entered.await()
            assertFalse(logout.isCompleted)
            sessions.commit(fixtureSession(), sessions.loginEpoch()) { accept -> accept() }
            val current = sessions.snapshot.value
            assertTrue(history.read(current).queries.isEmpty())
            history.record(current, "New same-account fixture")
            val bytes = file.readBytes()
            assertEquals(listOf("New same-account fixture"), history.read(current).queries)
            release.complete(Unit)
            logout.join()
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(listOf("New same-account fixture"), history.read(current).queries)
            assertEquals(0, nativeCalls)
        } finally { release.complete(Unit); presenter?.close(); sessions.close() }
    }

    @Test fun aConflatedAccountReplacementCannotRestoreOrRetagAnOlderSameAccountFile() = runBlocking {
        val file = File(temporary.root, "history")
        val sessions = SessionRepository(ControlledSessionStore())
        try {
            sessions.commit(fixtureSession(), sessions.loginEpoch()) { accept -> accept() }
            val history = OnlineSearchHistory.controlled(file, sessions)
            val old = sessions.snapshot.value
            history.record(old, "Retired fixture")
            val bytes = file.readBytes()
            sessions.commit(fixtureSession("102"), sessions.loginEpoch()) { accept -> accept() }
            sessions.commit(fixtureSession(), sessions.loginEpoch()) { accept -> accept() }
            assertTrue(history.read(sessions.snapshot.value).queries.isEmpty())
            assertArrayEquals(bytes, file.readBytes())
            history.record(sessions.snapshot.value, "Current fixture")
            assertEquals(listOf("Current fixture"), history.read(sessions.snapshot.value).queries)
            assertNull(history.clearAccount(old))
            assertEquals(listOf("Current fixture"), history.read(sessions.snapshot.value).queries)
        } finally { sessions.close() }
    }

    @Test fun ordinaryPartialClearCanRetryTheSameSurvivingDirectoryBeforeOrAfterReopeningHistory() = runBlocking {
        val sessions = SessionRepository(ControlledSessionStore())
        val local = temporary.newFile("local-library-sentinel").apply { writeText("keep local music") }
        try {
            sessions.commit(fixtureSession(), sessions.loginEpoch()) { accept -> accept() }
            for (reopen in listOf(false, true)) {
                val file = File(temporary.root, "history-clear-$reopen")
                val history = OnlineSearchHistory.controlled(file, sessions)
                val scope = sessions.snapshot.value
                val original = history.record(scope, "Original fixture")
                val pending = File(file.path + ".new").apply { check(mkdir()) }
                val obstruction = File(pending, "obstruction").apply { writeText("synthetic obstruction") }
                val failure = expect(OnlineSearchHistoryIssue.CLEAR_FAILED) { history.clear(original) }
                val retry = checkNotNull(failure.recoverableView)
                assertFalse(file.exists())
                assertTrue(pending.isDirectory)
                assertEquals(OnlineSearchHistoryIssue.CLEAR_FAILED, retry.issue)
                assertTrue(retry.clearToken != null)
                assertTrue(obstruction.delete())
                // Keep the original directory inode; clearing its obstruction is recovery, not a new history write.
                val chosen = if (reopen) history.read(scope) else retry
                assertEquals(OnlineSearchHistoryIssue.CLEAR_FAILED, chosen.issue)
                assertTrue(chosen.clearToken != null)
                val cleared = history.clear(chosen)
                assertNull(cleared.issue)
                assertTrue(cleared.queries.isEmpty())
                assertFalse(file.exists())
                assertFalse(pending.exists())
                assertFalse(File(file.path + ".bak").exists())
            }
            assertEquals("keep local music", local.readText())
        } finally { sessions.close() }
    }

    @Test fun partialClearCapabilityRejectsANewBaseAndAnIdenticalNewRecordWithoutDeletingEither() = runBlocking {
        val sessions = SessionRepository(ControlledSessionStore())
        try {
            sessions.commit(fixtureSession(), sessions.loginEpoch()) { accept -> accept() }
            for (applicationWrite in listOf(false, true)) {
                val file = File(temporary.root, "history-replacement-$applicationWrite")
                val history = OnlineSearchHistory.controlled(file, sessions)
                val scope = sessions.snapshot.value
                val original = history.record(scope, "Original fixture")
                val bytes = file.readBytes()
                val pending = File(file.path + ".new").apply { check(mkdir()) }
                val obstruction = File(pending, "obstruction").apply { writeText("synthetic obstruction") }
                val failed = expect(OnlineSearchHistoryIssue.CLEAR_FAILED) { history.clear(original) }
                val retry = checkNotNull(failed.recoverableView)
                assertFalse(file.exists())
                assertTrue(obstruction.delete())
                assertTrue(pending.delete())
                if (applicationWrite) history.record(scope, "Original fixture") else file.writeBytes(bytes)
                assertArrayEquals(bytes, file.readBytes())
                expect(OnlineSearchHistoryIssue.STALE_ACCOUNT) { history.clear(retry) }
                assertArrayEquals(bytes, file.readBytes())
                assertEquals(listOf("Original fixture"), history.read(scope).queries)
            }
        } finally { sessions.close() }
    }

    @Test fun partialClearCapabilityRejectsNewDanglingReservedLinksBeforeDeletionOrReload() = runBlocking {
        val sessions = SessionRepository(ControlledSessionStore())
        val local = temporary.newFile("local-library-link-sentinel").apply { writeText("keep local music") }
        try {
            sessions.commit(fixtureSession(), sessions.loginEpoch()) { accept -> accept() }
            for (suffix in listOf(".bak", ".new")) {
                for (reload in listOf(false, true)) {
                    val file = File(temporary.root, "history-dangling-$suffix-$reload")
                    val history = OnlineSearchHistory.controlled(file, sessions)
                    val scope = sessions.snapshot.value
                    val original = history.record(scope, "Original fixture")
                    val pending = File(file.path + ".new").apply { check(mkdir()) }
                    val obstruction = File(pending, "obstruction").apply { writeText("synthetic obstruction") }
                    val failed = expect(OnlineSearchHistoryIssue.CLEAR_FAILED) { history.clear(original) }
                    val retry = checkNotNull(failed.recoverableView)
                    assertFalse(file.exists())
                    assertTrue(obstruction.delete())
                    assertTrue(pending.delete())
                    val replacement = File(file.path + suffix)
                    val missing = File(temporary.root, "missing-$suffix-$reload")
                    assertFalse(missing.exists())
                    Files.createSymbolicLink(replacement.toPath(), missing.toPath())
                    assertFalse(replacement.exists())
                    assertTrue(Files.exists(replacement.toPath(), LinkOption.NOFOLLOW_LINKS))
                    assertTrue(Files.isSymbolicLink(replacement.toPath()))
                    if (reload) expect(OnlineSearchHistoryIssue.CLEAR_FAILED) { history.read(scope) }
                    else expect(OnlineSearchHistoryIssue.CLEAR_FAILED) { history.clear(retry) }
                    assertTrue(Files.exists(replacement.toPath(), LinkOption.NOFOLLOW_LINKS))
                    assertTrue(Files.isSymbolicLink(replacement.toPath()))
                    assertFalse(file.exists())
                    assertEquals("keep local music", local.readText())
                }
            }
        } finally { sessions.close() }
    }

    private suspend fun expect(issue: OnlineSearchHistoryIssue, action: suspend () -> Unit): OnlineSearchHistoryException {
        try { action(); throw AssertionError("Expected fixed history issue") }
        catch (error: OnlineSearchHistoryException) { assertEquals(issue, error.issue); return error }
    }
}
