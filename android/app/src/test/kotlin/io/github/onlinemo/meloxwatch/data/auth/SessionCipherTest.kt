package io.github.onlinemo.meloxwatch.data.auth

import android.security.keystore.KeyPermanentlyInvalidatedException
import java.nio.ByteBuffer
import java.security.ProviderException
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class SessionCipherTest {
    @Test fun authenticatedRoundTripUsesFreshNoncesAndContainsNoCredentialPlaintext() {
        val keys = FixtureSessionKeys()
        val cipher = SessionCipher(keys, "fixture.app")
        val session = fixtureSession().stored
        val first = cipher.seal(session)
        val second = cipher.seal(session)
        assertNotEquals(first.toList(), second.toList())
        assertNotEquals(first.copyOfRange(16, 28).toList(), second.copyOfRange(16, 28).toList())
        assertEquals(session.account, cipher.open(first).account)
        assertEquals(session.credential.headerValue(), cipher.open(first).credential.headerValue())
        assertFalse(first.toString(Charsets.ISO_8859_1).contains("MUSIC_U"))
        assertFalse(first.toString(Charsets.ISO_8859_1).contains("Fixture account"))
    }

    @Test fun applicationIdentityIsAuthenticatedAndTamperingCannotOpenTheRecord() {
        val keys = FixtureSessionKeys()
        val cipher = SessionCipher(keys, "fixture.app")
        val bytes = cipher.seal(fixtureSession().stored)
        expect(AuthIssue.SESSION_CORRUPT) { SessionCipher(keys, "other.app").open(bytes) }
        val damaged = bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        expect(AuthIssue.SESSION_CORRUPT) { cipher.open(damaged) }
    }

    @Test fun rejectsWrongMagicVersionNonceLengthCiphertextLengthAndOversizedEnvelopes() {
        val cipher = SessionCipher(FixtureSessionKeys(), "fixture.app")
        val original = cipher.seal(fixtureSession().stored)
        for ((offset, value) in listOf(0 to 0, 4 to 2, 8 to 13, 12 to 1)) {
            val bytes = original.copyOf().also { ByteBuffer.wrap(it).putInt(offset, value) }
            expect(AuthIssue.SESSION_CORRUPT) { cipher.open(bytes) }
        }
        expect(AuthIssue.SESSION_CORRUPT) { cipher.open(original.copyOf(20)) }
        expect(AuthIssue.SESSION_CORRUPT) { cipher.open(ByteArray(SessionCipher.MAX_ENCRYPTED_BYTES + 1)) }
    }

    @Test fun missingOrUnavailableKeysDoNotClearOrReplaceTheOriginalKey() {
        val keys = FixtureSessionKeys()
        val cipher = SessionCipher(keys, "fixture.app")
        val bytes = cipher.seal(fixtureSession().stored)
        keys.missing = true
        expect(AuthIssue.KEY_UNAVAILABLE) { cipher.open(bytes) }
        keys.missing = false
        keys.failure = ProviderException("fixture provider detail")
        val error = expect(AuthIssue.KEY_UNAVAILABLE) { cipher.open(bytes) }
        assertEquals(0, keys.replacements)
        assertEquals(null, error.cause)
    }

    @Test fun onlyANewSealCanReplaceAConfirmedPermanentlyInvalidatedKey() {
        val keys = FixtureSessionKeys()
        val cipher = SessionCipher(keys, "fixture.app")
        val bytes = cipher.seal(fixtureSession().stored)
        keys.invalidated = KeyPermanentlyInvalidatedException()
        expect(AuthIssue.KEY_UNAVAILABLE) { cipher.open(bytes) }
        assertEquals(0, keys.replacements)
        val replacement = cipher.seal(fixtureSession("202").stored)
        assertEquals(1, keys.replacements)
        assertEquals("202", cipher.open(replacement).account.accountId)
        expect(AuthIssue.SESSION_CORRUPT) { cipher.open(bytes) }
    }

    @Test fun malformedUnicodeCannotBeSavedAsReplacementCharacters() {
        val cipher = SessionCipher(FixtureSessionKeys(), "fixture.app")
        expect(AuthIssue.INVALID_RESPONSE) { cipher.seal(fixtureSession(name = "broken\uD800").stored) }
    }

    @Test fun sealedBuffersAreScopedCopiedAndErasedOnClose() {
        val owner = Any()
        val bytes = byteArrayOf(1, 2, 3)
        val sealed = SealedSession(owner, bytes)
        val copy = sealed.bytes(owner).also { it[0] = 9 }
        assertArrayEquals(byteArrayOf(1, 2, 3), sealed.bytes(owner))
        expect(AuthIssue.STORAGE_UNAVAILABLE) { sealed.bytes(Any()) }
        sealed.close()
        assertArrayEquals(byteArrayOf(0, 0, 0), bytes)
        assertEquals(9.toByte(), copy[0])
        expect(AuthIssue.STORAGE_UNAVAILABLE) { sealed.bytes(owner) }
    }

    private fun expect(issue: AuthIssue, action: () -> Unit): AuthException = assertThrows(AuthException::class.java) { action() }.also { assertEquals(issue, it.issue) }
}

internal class FixtureSessionKeys : SessionKeyProvider {
    private var key: SecretKey = SecretKeySpec(ByteArray(32) { 7 }, "AES")
    var missing = false
    var failure: RuntimeException? = null
    var invalidated: KeyPermanentlyInvalidatedException? = null
    var replacements = 0
        private set
    override fun encryptionKey(): SecretKey {
        failure?.let { throw it }
        invalidated?.let { throw it }
        return key
    }
    override fun existingKey(): SecretKey? {
        failure?.let { throw it }
        invalidated?.let { throw it }
        return if (missing) null else key
    }
    override fun replacePermanentlyInvalidatedKey(): SecretKey {
        replacements++
        failure = null
        invalidated = null
        key = SecretKeySpec(ByteArray(32) { 11 }, "AES")
        return key
    }
}
