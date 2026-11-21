package io.github.onlinemo.meloxwatch.data.transfer

import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Locale
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/** Key/certificate construction and validation happen off Main. The private key has no export API here. */
class TransferTlsIdentity(private val key: PrivateKey, private val certificate: X509Certificate) {
    val targetFingerprint: TransferFingerprint = TransferFingerprint.digest(certificate.encoded)
    val peerFingerprint: TransferFingerprint = TransferFingerprint.digest(certificate.publicKey.encoded)

    init {
        TransferTls.validateCertificate(arrayOf(certificate), server = true)
        val challenge = ByteArray(32).also(SecureRandom()::nextBytes)
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(key); signer.update(challenge)
        val signed = signer.sign()
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(certificate.publicKey); verifier.update(challenge)
        if (!verifier.verify(signed)) throw TransferException(TransferIssue.IDENTITY_UNAVAILABLE)
        challenge.fill(0); signed.fill(0)
    }

    internal fun keyManager(): X509ExtendedKeyManager = object : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<Principal>?): Array<String>? = if (keyType == "RSA") arrayOf(ALIAS) else null
        override fun chooseClientAlias(keyType: Array<String>?, issuers: Array<Principal>?, socket: Socket?): String? = if (keyType?.contains("RSA") == true) ALIAS else null
        override fun getServerAliases(keyType: String?, issuers: Array<Principal>?): Array<String>? = if (keyType == "RSA") arrayOf(ALIAS) else null
        override fun chooseServerAlias(keyType: String?, issuers: Array<Principal>?, socket: Socket?): String? = if (keyType == "RSA") ALIAS else null
        override fun getCertificateChain(alias: String?): Array<X509Certificate>? = if (alias == ALIAS) arrayOf(certificate) else null
        override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == ALIAS) key else null
        override fun chooseEngineClientAlias(keyType: Array<String>?, issuers: Array<Principal>?, engine: SSLEngine?): String? = chooseClientAlias(keyType, issuers, null)
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<Principal>?, engine: SSLEngine?): String? = chooseServerAlias(keyType, issuers, null)
    }

    override fun toString(): String = "<local-tls-identity>"
    private companion object { const val ALIAS = "local-transfer" }
}

/** Self-signed TLS candidates prove possession; only TransferSessionAuthority grants application access. */
object TransferTls {
    private const val CIPHER = "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"
    private const val PROTOCOL = "TLSv1.2"

    fun serverContext(identity: TransferTlsIdentity): SSLContext = context(identity, CandidateTrust(server = false, pin = null))
    fun clientContext(identity: TransferTlsIdentity, expectedTarget: TransferFingerprint): SSLContext = context(identity, CandidateTrust(server = true, pin = expectedTarget))

    /** A raw socket is retained by the server so deadlines can interrupt TLS writes by closing it off Main. */
    fun serverSocket(context: SSLContext, raw: Socket): SSLSocket = (context.socketFactory.createSocket(raw, raw.inetAddress.hostAddress, raw.port, true) as SSLSocket).also {
        configure(it, client = false)
        it.needClientAuth = true
    }

    fun clientSocket(context: SSLContext, raw: Socket): SSLSocket = (context.socketFactory.createSocket(raw, raw.inetAddress.hostAddress, raw.port, true) as SSLSocket).also { configure(it, client = true) }

    fun authenticatedPeer(socket: SSLSocket): TransferFingerprint {
        val certificate = socket.session.peerCertificates.singleOrNull() as? X509Certificate ?: throw TransferException(TransferIssue.UNAUTHENTICATED)
        return TransferFingerprint.digest(certificate.publicKey.encoded)
    }

    internal fun validateCertificate(chain: Array<X509Certificate>, server: Boolean) {
        if (chain.size != 1) throw CertificateException("Invalid local certificate")
        val certificate = chain[0]
        val key = certificate.publicKey as? RSAPublicKey ?: throw CertificateException("Invalid local certificate")
        if (certificate.encoded.size > 16 * 1024 || key.modulus.bitLength() !in 2048..4096 || certificate.basicConstraints != -1 || certificate.hasUnsupportedCriticalExtension()) throw CertificateException("Invalid local certificate")
        if (certificate.issuerX500Principal != certificate.subjectX500Principal) throw CertificateException("Invalid local certificate")
        if (certificate.sigAlgName.uppercase(Locale.ROOT) !in setOf("SHA256WITHRSA", "SHA384WITHRSA", "SHA512WITHRSA")) throw CertificateException("Invalid local certificate")
        certificate.checkValidity()
        certificate.verify(key)
        val usage = certificate.keyUsage
        if (usage != null && (usage.isEmpty() || !usage[0])) throw CertificateException("Invalid local certificate")
        val extended = certificate.extendedKeyUsage
        val purpose = if (server) "1.3.6.1.5.5.7.3.1" else "1.3.6.1.5.5.7.3.2"
        if (extended != null && purpose !in extended && "2.5.29.37.0" !in extended) throw CertificateException("Invalid local certificate")
    }

    private fun configure(socket: SSLSocket, client: Boolean) {
        if (PROTOCOL !in socket.supportedProtocols || CIPHER !in socket.supportedCipherSuites) throw TransferException(TransferIssue.INCOMPATIBLE_SCHEMA)
        socket.useClientMode = client
        socket.enabledProtocols = arrayOf(PROTOCOL)
        socket.enabledCipherSuites = arrayOf(CIPHER)
        socket.soTimeout = TransferLimits.IDLE_MS.toInt()
        socket.setSoLinger(false, 0)
    }

    private fun context(identity: TransferTlsIdentity, trust: X509TrustManager): SSLContext = SSLContext.getInstance(PROTOCOL).also {
        it.init(arrayOf(identity.keyManager()), arrayOf(trust), SecureRandom())
    }

    private class CandidateTrust(private val server: Boolean, private val pin: TransferFingerprint?) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            if (server || !authType.contains("RSA")) throw CertificateException("Invalid local certificate")
            validateCertificate(chain, server = false)
        }
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            if (!server || !authType.contains("RSA") || chain.size != 1 || pin == null || TransferFingerprint.digest(chain[0].encoded) != pin) throw CertificateException("Unrecognized local target")
            validateCertificate(chain, server = true)
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
