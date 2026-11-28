package io.github.onlinemo.meloxwatch.data.auth

import android.security.keystore.KeyPermanentlyInvalidatedException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.CharBuffer
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface SessionKeyProvider {
    fun encryptionKey(): SecretKey
    fun existingKey(): SecretKey?
    fun replacePermanentlyInvalidatedKey(): SecretKey
}

/** The EAPI protocol key is never used here. A fresh provider-generated GCM nonce seals each save. */
internal class SessionCipher(private val keys: SessionKeyProvider, applicationId: String) {
    private val associatedData = "MeloXWatch/session/v1/$applicationId".toByteArray(Charsets.UTF_8)

    fun seal(session: StoredSession): ByteArray {
        val plaintext = SessionRecordCodec.encode(session)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            try {
                cipher.init(Cipher.ENCRYPT_MODE, keys.encryptionKey())
            } catch (_: KeyPermanentlyInvalidatedException) {
                // A new verified login may replace a confirmed unusable key. Transient failures never delete keys.
                cipher.init(Cipher.ENCRYPT_MODE, keys.replacePermanentlyInvalidatedKey())
            }
            cipher.updateAAD(associatedData)
            val encrypted = cipher.doFinal(plaintext)
            val iv = cipher.iv
            if (iv.size != IV_BYTES || encrypted.size + HEADER_BYTES + iv.size > MAX_ENCRYPTED_BYTES) {
                throw AuthException(AuthIssue.KEY_UNAVAILABLE)
            }
            return ByteBuffer.allocate(HEADER_BYTES + iv.size + encrypted.size)
                .putInt(MAGIC).putInt(VERSION).putInt(iv.size).putInt(encrypted.size).put(iv).put(encrypted).array()
        } catch (_: GeneralSecurityException) {
            throw AuthException(AuthIssue.KEY_UNAVAILABLE)
        } catch (_: ProviderException) {
            throw AuthException(AuthIssue.KEY_UNAVAILABLE)
        } catch (_: SecurityException) {
            throw AuthException(AuthIssue.KEY_UNAVAILABLE)
        } finally {
            plaintext.fill(0)
        }
    }

    fun open(bytes: ByteArray): StoredSession {
        if (bytes.size !in (HEADER_BYTES + IV_BYTES + 16)..MAX_ENCRYPTED_BYTES) corrupt()
        val input = ByteBuffer.wrap(bytes)
        if (input.int != MAGIC || input.int != VERSION || input.int != IV_BYTES) corrupt()
        val length = input.int
        if (length < 16 || length != input.remaining() - IV_BYTES) corrupt()
        val iv = ByteArray(IV_BYTES).also(input::get)
        val encrypted = ByteArray(length).also(input::get)
        val plaintext = try {
            val key = keys.existingKey() ?: throw AuthException(AuthIssue.KEY_UNAVAILABLE)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(associatedData)
            cipher.doFinal(encrypted)
        } catch (_: BadPaddingException) {
            corrupt()
        } catch (_: GeneralSecurityException) {
            throw AuthException(AuthIssue.KEY_UNAVAILABLE)
        } catch (_: ProviderException) {
            throw AuthException(AuthIssue.KEY_UNAVAILABLE)
        } catch (_: SecurityException) {
            throw AuthException(AuthIssue.KEY_UNAVAILABLE)
        }
        return try { SessionRecordCodec.decode(plaintext) { legacyLineage(bytes) } } finally { plaintext.fill(0) }
    }

    // A legacy authenticated envelope has no lineage field. Its stable ciphertext identity
    // keeps repeated offline restores consistent without exposing plaintext outside this layer.
    private fun legacyLineage(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").apply {
            update("MeloXWatch/session-lineage/legacy-v1".toByteArray(Charsets.UTF_8))
            update(associatedData)
            update(bytes)
        }.digest()
        return try { ByteBuffer.wrap(digest).let { UUID(it.long, it.long).toString() } }
        finally { digest.fill(0) }
    }

    companion object {
        const val MAX_ENCRYPTED_BYTES = 32 * 1024
        private const val MAGIC = 0x4d585343
        private const val VERSION = 1
        private const val HEADER_BYTES = 16
        private const val IV_BYTES = 12
        private fun corrupt(): Nothing = throw AuthException(AuthIssue.SESSION_CORRUPT)
    }
}

private object SessionRecordCodec {
    private const val MAGIC = 0x4d585352
    private const val VERSION = 2
    fun encode(session: StoredSession): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use {
            it.writeInt(MAGIC)
            it.writeInt(VERSION)
            text(it, session.account.providerId, 32)
            text(it, session.account.accountId, 32)
            text(it, session.account.displayName, 512)
            text(it, session.credential.headerValue(), SessionCredential.MAX_BYTES)
            text(it, session.lineageId, 36)
        }
        return output.toByteArray()
    }

    fun decode(bytes: ByteArray, legacyLineage: () -> String): StoredSession = try {
        DataInputStream(ByteArrayInputStream(bytes)).use {
            if (it.readInt() != MAGIC) corrupt()
            val version = it.readInt()
            if (version !in 1..VERSION) corrupt()
            val provider = text(it, 32)
            val id = text(it, 32)
            val name = text(it, 512)
            val credential = text(it, SessionCredential.MAX_BYTES)
            val lineage = if (version == VERSION) text(it, 36) else null
            if (it.available() != 0) corrupt()
            val account = AccountIdentity(provider, id, name)
            val validatedCredential = SessionCredential.fromValidatedHeader(credential)
            // Derive legacy identity only after authentication and full record validation succeeded.
            StoredSession(account, validatedCredential, lineage ?: legacyLineage())
        }
    } catch (_: IOException) { corrupt() }
    catch (_: IllegalArgumentException) { corrupt() }
    catch (_: AuthException) { corrupt() }

    private fun text(output: DataOutputStream, value: String, limit: Int) {
        val bytes = try {
            val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
            ByteArray(encoded.remaining()).also(encoded::get)
        } catch (_: CharacterCodingException) { throw AuthException(AuthIssue.INVALID_RESPONSE) }
        if (bytes.isEmpty() || bytes.size > limit) throw AuthException(AuthIssue.INVALID_RESPONSE)
        output.writeInt(bytes.size)
        output.write(bytes)
    }
    private fun text(input: DataInputStream, limit: Int): String {
        val length = input.readInt()
        if (length !in 1..limit || length > input.available()) corrupt()
        val bytes = ByteArray(length).also(input::readFully)
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) { corrupt() }
        finally { bytes.fill(0) }
    }
    private fun corrupt(): Nothing = throw AuthException(AuthIssue.SESSION_CORRUPT)
}
