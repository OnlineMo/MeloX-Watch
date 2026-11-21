package io.github.onlinemo.meloxwatch.data.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.KeyPermanentlyInvalidatedException
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Android Keystore is initialized only by explicit session IO, without GMS or plaintext fallback. */
internal class AndroidSessionKeys : SessionKeyProvider {
    override fun encryptionKey(): SecretKey = access {
        val store = store()
        when (val key = store.getKey(ALIAS, null)) {
            null -> if (store.containsAlias(ALIAS)) throw AuthException(AuthIssue.KEY_UNAVAILABLE) else create()
            is SecretKey -> key
            else -> throw AuthException(AuthIssue.KEY_UNAVAILABLE)
        }
    }
    override fun existingKey(): SecretKey? = access { store().getKey(ALIAS, null) as? SecretKey }
    override fun replacePermanentlyInvalidatedKey(): SecretKey = access {
        store().deleteEntry(ALIAS)
        create()
    }

    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun create(): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
        init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .build())
        generateKey()
    }

    private inline fun <T> access(action: () -> T): T = try { action() }
    catch (error: KeyPermanentlyInvalidatedException) { throw error }
    catch (_: GeneralSecurityException) { throw AuthException(AuthIssue.KEY_UNAVAILABLE) }
    catch (_: IOException) { throw AuthException(AuthIssue.KEY_UNAVAILABLE) }
    catch (_: ProviderException) { throw AuthException(AuthIssue.KEY_UNAVAILABLE) }
    catch (_: SecurityException) { throw AuthException(AuthIssue.KEY_UNAVAILABLE) }

    companion object { private const val ALIAS = "meloxwatch.auth.aes-gcm.v1" }
}
