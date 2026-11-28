package io.github.onlinemo.meloxwatch.data.auth

import io.github.onlinemo.meloxwatch.data.netease.NeteaseAuthApi
import io.github.onlinemo.meloxwatch.data.netease.NeteaseEapi
import io.github.onlinemo.meloxwatch.data.network.HttpExchange
import io.github.onlinemo.meloxwatch.data.online.OnlineSearchHistory
import io.github.onlinemo.meloxwatch.data.online.OnlineSearchHistoryIssue
import io.github.onlinemo.meloxwatch.data.online.catalogJson
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import javax.crypto.Cipher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real repository, native EAPI QR flow, GCM/AtomicFile migration and persisted history; synthetic accounts only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30], manifest = Config.NONE)
class SessionLineageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun publicCommitAlwaysMintsANewLineageAndPersistsTheSamePreparedIdentity() = runBlocking {
        val store = ControlledSessionStore()
        val sessions = SessionRepository(store)
        try {
            sessions.commit(fixtureSession(), sessions.loginEpoch()) { accept -> accept() }
            val original = sessions.snapshot.value
            val injected = VerifiedSession(StoredSession(checkNotNull(store.persistent).account,
                checkNotNull(store.persistent).credential, checkNotNull(original.lineageId)))
            sessions.commit(injected, sessions.loginEpoch()) { accept -> accept() }
            val current = sessions.snapshot.value
            assertNotEquals(original.lineageId, current.lineageId)
            assertEquals(current.lineageId, store.persistent?.lineageId)
            assertFalse(sessions.commit(injected, sessions.loginEpoch()) { false })
            assertEquals(current, sessions.snapshot.value)
            assertEquals(current.lineageId, store.persistent?.lineageId)
        } finally { sessions.close() }
    }

    @Test fun legacyAuthenticatedEnvelopeRestoresStableLineageAndResealsAsCompatibleV2WithoutPlaintextExposure() {
        val keys = FixtureSessionKeys()
        val cipher = SessionCipher(keys, "fixture.app")
        val session = fixtureSession().stored
        val legacy = legacyEnvelope(keys, session)
        val first = cipher.open(legacy)
        val again = cipher.open(legacy)
        assertEquals(first.lineageId, again.lineageId)
        assertEquals(session.account, first.account)
        assertEquals(session.credential.headerValue(), first.credential.headerValue())
        val upgraded = cipher.seal(first)
        assertNotEquals(legacy.toList(), upgraded.toList())
        assertEquals(first.lineageId, cipher.open(upgraded).lineageId)
        assertEquals(first.credential.headerValue(), cipher.open(upgraded).credential.headerValue())
        assertFalse(upgraded.toString(Charsets.ISO_8859_1).contains(first.lineageId))
        assertFalse(upgraded.toString(Charsets.ISO_8859_1).contains("MUSIC_U"))
        val trailing = legacyEnvelope(keys, session, trailing = true)
        assertEquals(AuthIssue.SESSION_CORRUPT, assertThrows(AuthException::class.java) { cipher.open(trailing) }.issue)
        val malformed = legacyEnvelope(keys, session, version = 2, lineage = "invalid-lineage")
        assertEquals(AuthIssue.SESSION_CORRUPT, assertThrows(AuthException::class.java) { cipher.open(malformed) }.issue)
    }

    @Test(timeout = 20_000L) fun actualQrLoginFromRestoredSameAccountGetsANewLineageAndCannotInheritItsOldHistory() = runBlocking {
        val store = ControlledSessionStore(fixtureSession().stored)
        val sessions = SessionRepository(store)
        var controller: QrLoginController? = null
        val endpoints = ArrayList<String>()
        try {
            val restored = sessions.restore()
            val history = OnlineSearchHistory.controlled(File(temporary.root, "history"), sessions)
            history.record(restored, "Previous session fixture")
            val api = NeteaseAuthApi(NeteaseEapi(HttpExchange { request ->
                endpoints += request.url.substringAfter("/eapi/")
                when {
                    request.url.endsWith("/eapi/login/qrcode/unikey") -> catalogJson("{\"code\":200,\"unikey\":\"fixture-key\"}")
                    request.url.endsWith("/eapi/login/qrcode/client/login") -> catalogJson("{\"code\":803,\"cookie\":\"MUSIC_U=fixture-qr; __csrf=fixture\"}")
                    request.url.endsWith("/eapi/w/nuser/account/get") -> catalogJson("{\"code\":200,\"profile\":{\"userId\":101,\"nickname\":\"Fixture account\"}}")
                    else -> error("Unexpected synthetic QR endpoint")
                }
            }))
            val qr = QrLoginController(api, sessions, this).also { controller = it }
            qr.enter(true)
            val result = withTimeout(10_000L) { qr.state.first { it.phase == QrLoginPhase.SIGNED_IN || it.phase == QrLoginPhase.ERROR } }
            assertEquals(QrLoginPhase.SIGNED_IN, result.phase)
            val current = sessions.snapshot.value
            assertEquals(restored.account?.accountId, current.account?.accountId)
            assertEquals(restored.generation + 1L, current.generation)
            assertNotEquals(restored.lineageId, current.lineageId)
            assertEquals(current.lineageId, store.persistent?.lineageId)
            assertEquals(listOf("login/qrcode/unikey", "login/qrcode/client/login", "w/nuser/account/get"), endpoints)
            assertTrue(history.read(current).queries.isEmpty())
            history.record(current, "New QR fixture")
            assertEquals(listOf("New QR fixture"), history.read(current).queries)
            assertNull(history.clearAccount(restored))
            assertEquals(listOf("New QR fixture"), history.read(current).queries)
        } finally { controller?.close(); sessions.close() }
    }

    @Test fun failedOldHistoryCleanupCannotReappearAfterSameAccountReloginAndEncryptedStoreRecreation() = runBlocking {
        val credentialFile = File(temporary.root, "session.enc")
        val historyFile = File(temporary.root, "history")
        val keys = FixtureSessionKeys()
        fun credentialStore() = AtomicEncryptedSessionStore.controlled(credentialFile, SessionCipher(keys, "fixture.app"))
        val sessions = SessionRepository(credentialStore())
        var reopened: SessionRepository? = null
        try {
            sessions.commit(fixtureSession(), sessions.loginEpoch()) { accept -> accept() }
            val old = sessions.snapshot.value
            val history = OnlineSearchHistory.controlled(historyFile, sessions)
            history.record(old, "Retired fixture")
            val before = historyFile.readBytes()
            val failing = OnlineSearchHistory.controlled(historyFile, sessions,
                beforeAccountCleanup = { throw IOException("synthetic cleanup read failure") })
            assertEquals(OnlineSearchHistoryIssue.CLEAR_FAILED, failing.clearAccount(old))
            assertArrayEquals(before, historyFile.readBytes())
            sessions.finishLogout(sessions.beginLogout())
            sessions.commit(fixtureSession(), sessions.loginEpoch()) { accept -> accept() }
            val newLineage = sessions.snapshot.value.lineageId
            assertNotEquals(old.lineageId, newLineage)
            sessions.close()
            val next = SessionRepository(credentialStore()).also { reopened = it }
            val restored = next.restore()
            assertEquals(newLineage, restored.lineageId)
            val nextHistory = OnlineSearchHistory.controlled(historyFile, next)
            assertTrue(nextHistory.read(restored).queries.isEmpty())
            assertArrayEquals(before, historyFile.readBytes())
            nextHistory.record(restored, "New process fixture")
            val current = historyFile.readBytes()
            assertEquals(listOf("New process fixture"), nextHistory.read(restored).queries)
            assertNull(history.clearAccount(old))
            assertArrayEquals(current, historyFile.readBytes())
        } finally { reopened?.close(); sessions.close() }
    }

    /** Frozen v1 plaintext/envelope fixture; production seals v2 and never runs this encoder. */
    private fun legacyEnvelope(keys: FixtureSessionKeys, session: StoredSession, version: Int = 1,
        lineage: String? = null, trailing: Boolean = false): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(0x4d585352); data.writeInt(version)
            fun text(value: String) { val bytes = value.toByteArray(Charsets.UTF_8); data.writeInt(bytes.size); data.write(bytes) }
            text(session.account.providerId); text(session.account.accountId); text(session.account.displayName)
            text(session.credential.headerValue())
            if (version == 2) text(checkNotNull(lineage))
            if (trailing) data.writeByte(1)
        }
        val plain = output.toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keys.encryptionKey())
        cipher.updateAAD("MeloXWatch/session/v1/fixture.app".toByteArray(Charsets.UTF_8))
        val encrypted = try { cipher.doFinal(plain) } finally { plain.fill(0) }
        val iv = cipher.iv
        return ByteBuffer.allocate(16 + iv.size + encrypted.size).putInt(0x4d585343).putInt(1)
            .putInt(iv.size).putInt(encrypted.size).put(iv).put(encrypted).array()
    }
}
