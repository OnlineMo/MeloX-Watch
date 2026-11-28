package io.github.onlinemo.meloxwatch.data.auth

import java.util.UUID

enum class AuthIssue {
    NETWORK_UNAVAILABLE, TIMEOUT, RATE_LIMITED, CERTIFICATE, LOGIN_REQUIRED,
    INVALID_RESPONSE, KEY_UNAVAILABLE, STORAGE_UNAVAILABLE, SESSION_CORRUPT, BUSY, CLOSED,
}

class AuthException(
    val issue: AuthIssue,
    val serverCode: Int? = null,
    val retryAfterSeconds: Long? = null,
) : Exception(issue.name)

/** Account IDs stay opaque here; the NetEase endpoint checks its canonical numeric wire identity. */
data class AccountIdentity(val providerId: String, val accountId: String, val displayName: String) {
    init {
        require(providerId.matches(Regex("[a-z][a-z0-9-]{0,31}")))
        require(accountId.length in 1..32 && accountId.all { it.code in 33..126 })
        require(displayName.isNotBlank() && displayName.length <= 128 && displayName.none { it.code < 32 })
    }
    override fun toString(): String = "AccountIdentity(provider=$providerId, redacted)"
}

enum class SessionValidity { SIGNED_OUT, RESTORED_UNVERIFIED, VERIFIED, UNAVAILABLE }

/** No cookie, signed URL, QR key or store path belongs in a page's account snapshot. */
data class SessionSnapshot(
    val account: AccountIdentity? = null,
    val validity: SessionValidity = SessionValidity.SIGNED_OUT,
    val generation: Long = 0,
    val issue: AuthIssue? = null,
    val lineageId: String? = null,
) {
    override fun toString(): String = "SessionSnapshot(validity=$validity, generation=$generation, issue=$issue, redacted)"
}

internal class SessionCredential private constructor(private val value: String) {
    internal fun headerValue(): String = value
    override fun toString(): String = "SessionCredential(redacted)"

    companion object {
        internal const val MAX_BYTES = 16 * 1024
        internal fun fromValidatedHeader(value: String): SessionCredential {
            if (value.isEmpty() || value.length > MAX_BYTES || value.any { it.code !in 32..126 }) {
                throw AuthException(AuthIssue.INVALID_RESPONSE)
            }
            return SessionCredential(value)
        }
    }
}

/** The opaque persisted lineage carries no credential and is renewed by each explicit login commit. */
internal class StoredSession(val account: AccountIdentity, val credential: SessionCredential,
    val lineageId: String = UUID.randomUUID().toString()) {
    init { require(lineageId.length == 36 && UUID.fromString(lineageId).toString() == lineageId) }
    override fun toString(): String = "StoredSession(redacted)"
}

/** Created by the authenticated account endpoint, never by a nonempty cookie check. */
internal class VerifiedSession(val stored: StoredSession) {
    override fun toString(): String = "VerifiedSession(redacted)"
}
