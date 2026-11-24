package io.github.onlinemo.meloxwatch.data.auth

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30], manifest = Config.NONE)
class AtomicEncryptedSessionStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun storesOnlyCiphertextAndCanReopenTheSameSession() = runBlocking {
        val file = File(temporary.root, "session.enc")
        val keys = FixtureSessionKeys()
        val first = store(file, keys)
        assertNull(first.load())
        first.seal(fixtureSession()).use { first.write(it) }
        val bytes = file.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(bytes.contains("MUSIC_U"))
        assertFalse(bytes.contains("Fixture account"))
        assertEquals("101", store(file, keys).load()?.account?.accountId)
        assertFalse(File(file.path + ".new").exists())
    }

    @Test fun incompletePendingCiphertextDoesNotReplaceTheLastValidBase() = runBlocking {
        val file = File(temporary.root, "session.enc")
        val keys = FixtureSessionKeys()
        val store = store(file, keys)
        store.seal(fixtureSession()).use { store.write(it) }
        File(file.path + ".new").writeBytes(byteArrayOf(1, 2, 3))
        assertEquals("101", store(file, keys).load()?.account?.accountId)
    }

    @Test fun clearRemovesOnlyThisSessionsAtomicFilesAndPreservesOtherFiles() = runBlocking {
        val file = File(temporary.root, "session.enc")
        val other = File(temporary.root, "library-marker").apply { writeText("keep") }
        val store = store(file)
        store.seal(fixtureSession()).use { store.write(it) }
        File(file.path + ".new").writeText("pending ciphertext")
        store.clear()
        assertNull(store.load())
        assertEquals("keep", other.readText())
        assertFalse(file.exists())
        assertFalse(File(file.path + ".bak").exists())
        assertFalse(File(file.path + ".new").exists())
    }

    @Test fun corruptOrOversizedEncryptedFilesReportFailureWithoutDeletion() = runBlocking {
        val file = File(temporary.root, "session.enc")
        val store = store(file)
        for (bytes in listOf(byteArrayOf(1, 2, 3), ByteArray(SessionCipher.MAX_ENCRYPTED_BYTES + 1))) {
            file.writeBytes(bytes)
            expect(AuthIssue.SESSION_CORRUPT) { runBlocking { store.load() } }
            assertEquals(bytes.size.toLong(), file.length())
        }
    }

    @Test fun foreignSealedTokensAndUnwritableTargetsNeverReportSavedSessions() = runBlocking {
        val first = store(File(temporary.root, "first.enc"))
        val secondFile = File(temporary.root, "second.enc")
        val second = store(secondFile)
        first.seal(fixtureSession()).use { token -> expect(AuthIssue.STORAGE_UNAVAILABLE) { runBlocking { second.write(token) } } }
        assertFalse(secondFile.exists())
        val obstruction = File(temporary.root, "obstruction").apply { writeText("must stay a file") }
        val blocked = store(File(obstruction, "session.enc"))
        blocked.seal(fixtureSession()).use { token -> expect(AuthIssue.STORAGE_UNAVAILABLE) { runBlocking { blocked.write(token) } } }
        assertEquals("must stay a file", obstruction.readText())
    }

    private fun store(file: File, keys: FixtureSessionKeys = FixtureSessionKeys()): AtomicEncryptedSessionStore =
        AtomicEncryptedSessionStore.controlled(file, SessionCipher(keys, "fixture.app"))
    private fun expect(issue: AuthIssue, action: () -> Unit): AuthException = assertThrows(AuthException::class.java) { action() }.also { assertEquals(issue, it.issue) }
}
