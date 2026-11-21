package io.github.onlinemo.meloxwatch.companion.transfer

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.onlinemo.meloxwatch.companion.data.CompanionDocuments
import io.github.onlinemo.meloxwatch.data.transfer.TransferException
import io.github.onlinemo.meloxwatch.data.transfer.TransferIssue
import io.github.onlinemo.meloxwatch.data.transfer.TransferTlsIdentity
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.util.Date
import javax.security.auth.x500.X500Principal

internal object CompanionTlsIdentity {
    private const val KEY_ALIAS = "melox.phone.transfer.v1"

    /** Explicit pairing/connect only. An unavailable existing identity is retained, never silently rotated. */
    suspend fun load(documents: CompanionDocuments): TransferTlsIdentity = documents.onWorker { operation ->
        operation.check()
        try {
            val store = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
            if (!store.containsAlias(KEY_ALIAS)) {
                val now = Instant.now()
                val parameters = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateSubject(X500Principal("CN=MeloX Companion Local Transfer"))
                    .setCertificateSerialNumber(BigInteger(127, SecureRandom()).add(BigInteger.ONE))
                    .setCertificateNotBefore(Date.from(now.minus(Duration.ofDays(1))))
                    .setCertificateNotAfter(Date.from(now.plus(Duration.ofDays(20L * 365))))
                    .build()
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply { initialize(parameters) }.generateKeyPair()
            }
            operation.check()
            val key = store.getKey(KEY_ALIAS, null) as? PrivateKey ?: throw TransferException(TransferIssue.IDENTITY_UNAVAILABLE)
            val certificate = store.getCertificate(KEY_ALIAS) as? X509Certificate ?: throw TransferException(TransferIssue.IDENTITY_UNAVAILABLE)
            TransferTlsIdentity(key, certificate).also { operation.check() }
        } catch (error: TransferException) { throw error }
          catch (error: kotlinx.coroutines.CancellationException) { throw error }
          catch (error: android.os.OperationCanceledException) { throw error }
          catch (_: Exception) { throw TransferException(TransferIssue.IDENTITY_UNAVAILABLE) }
    }
}
