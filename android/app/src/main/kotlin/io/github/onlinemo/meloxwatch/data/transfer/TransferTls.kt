package io.github.onlinemo.meloxwatch.data.transfer

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
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

object AndroidTransferIdentity {
    private const val KEY_ALIAS = "melox.local.transfer.v1"

    /** Explicit local session preparation only. An invalid existing identity is not silently rotated. */
    suspend fun loadOrCreate(providerIo: LocalProviderIo): TransferTlsIdentity = providerIo.read { signal ->
        signal.throwIfCanceled()
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val random = SecureRandom()
                val now = Instant.now()
                val parameters = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateSubject(X500Principal("CN=MeloX Local Transfer"))
                    .setCertificateSerialNumber(BigInteger(127, random).add(BigInteger.ONE))
                    .setCertificateNotBefore(Date.from(now.minus(Duration.ofDays(1))))
                    .setCertificateNotAfter(Date.from(now.plus(Duration.ofDays(20L * 365))))
                    .build()
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply { initialize(parameters) }.generateKeyPair()
            }
            signal.throwIfCanceled()
            val key = keyStore.getKey(KEY_ALIAS, null) as? PrivateKey ?: throw TransferException(TransferIssue.IDENTITY_UNAVAILABLE)
            val certificate = keyStore.getCertificate(KEY_ALIAS) as? X509Certificate ?: throw TransferException(TransferIssue.IDENTITY_UNAVAILABLE)
            TransferTlsIdentity(key, certificate).also { signal.throwIfCanceled() }
        } catch (cancelled: android.os.OperationCanceledException) { throw cancelled }
          catch (_: Exception) { throw TransferException(TransferIssue.IDENTITY_UNAVAILABLE) }
    }
}
