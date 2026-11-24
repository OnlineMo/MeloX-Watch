/*
 * NetEase EAPI framing and endpoints adapted from youshen2/MeloX,
 * commit 09713edb847a0f0a3515a34f779cc630d4fe5985,
 * MeloXWatch/Networking/WatchNeteaseClient.swift and WatchNeteaseAPI.swift.
 * GPL-3.0; the complete license is in the repository LICENSE.
 * Modified 2026-11-16: Android crypto/HTTP, bounded parsing, cancellation,
 * ephemeral protocol identifiers, fixed origin and explicit credential scope.
 */
package io.github.onlinemo.meloxwatch.data.netease

import android.annotation.SuppressLint
import io.github.onlinemo.meloxwatch.data.auth.AuthException
import io.github.onlinemo.meloxwatch.data.auth.AuthIssue
import io.github.onlinemo.meloxwatch.data.network.HttpExchange
import io.github.onlinemo.meloxwatch.data.network.HttpRequest
import io.github.onlinemo.meloxwatch.data.network.JsonBoolean
import io.github.onlinemo.meloxwatch.data.network.JsonObject
import io.github.onlinemo.meloxwatch.data.network.JsonText
import io.github.onlinemo.meloxwatch.data.network.JsonValue
import io.github.onlinemo.meloxwatch.data.network.NetworkException
import io.github.onlinemo.meloxwatch.data.network.NetworkIssue
import io.github.onlinemo.meloxwatch.data.network.NetworkLimits
import io.github.onlinemo.meloxwatch.data.network.MetadataCallBudget
import io.github.onlinemo.meloxwatch.data.network.JsonBounds
import io.github.onlinemo.meloxwatch.data.network.StrictJson
import io.github.onlinemo.meloxwatch.data.network.jsonObject
import java.security.MessageDigest
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class EapiResponse(val json: JsonObject, val setCookie: List<String>) {
    override fun toString(): String = "EapiResponse(redacted)"
}

internal class EapiEnvironment(
    val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    private val deviceToken = ByteArray(16).also(random::nextBytes).hex(uppercase = true)
    fun header(cookie: NeteaseCookie?): Map<String, String> = buildMap {
        put("os", "android")
        put("osver", "")
        put("appver", "9.0.90")
        put("versioncode", "140")
        put("deviceId", deviceToken)
        put("mobilename", "")
        put("resolution", "")
        put("channel", "distribution")
        put("buildver", (nowEpochMs() / 1000).toString())
        put("requestId", "${nowEpochMs()}_${random.nextInt(10_000).toString().padStart(4, '0')}")
        put("__csrf", cookie?.value("__csrf").orEmpty())
        cookie?.value("MUSIC_U")?.let { put("MUSIC_U", it) }
        cookie?.value("MUSIC_A")?.let { put("MUSIC_A", it) }
    }
}

internal class NeteaseEapi(private val exchange: HttpExchange, private val environment: EapiEnvironment = EapiEnvironment()) {
    suspend fun post(
        path: String,
        fields: Map<String, JsonValue> = emptyMap(),
        cookie: NeteaseCookie? = null,
        budget: MetadataCallBudget = MetadataCallBudget(),
        jsonBounds: JsonBounds = JsonBounds(bytes = budget.responseBytes),
    ): EapiResponse {
        if (path.length > 256 || !PATH.matches(path) || "header" in fields || "e_r" in fields) {
            throw AuthException(AuthIssue.INVALID_RESPONSE)
        }
        val started = System.nanoTime()
        return try {
            withTimeoutOrNull(budget.callTimeoutMs) {
                val request = withContext(Dispatchers.Default) {
                    val context = currentCoroutineContext()
                    val header = environment.header(cookie)
                    val payload = JsonObject(fields + mapOf(
                        "e_r" to JsonBoolean(false),
                        "header" to JsonObject(header.mapValues { JsonText(it.value) }),
                    ))
                    val json = StrictJson.encode(payload, maxBytes = MAX_JSON_BYTES, checkCancelled = context::ensureActive)
                    val body = encodeParams(path, json)
                    context.ensureActive()
                    val httpCookie = (cookie?.let { fromCookie(it) }.orEmpty() + header).entries.sortedBy { it.key }
                        .joinToString("; ") { "${it.key}=${it.value}" }
                    val spent = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)
                    val remaining = budget.callTimeoutMs - spent
                    if (remaining <= 0L) throw AuthException(AuthIssue.TIMEOUT)
                    HttpRequest(ORIGIN + path.replaceFirst("/api/", "/eapi/"), mapOf(
                        "Content-Type" to "application/x-www-form-urlencoded",
                        "User-Agent" to "NeteaseMusic/9.0.90 (Android)",
                        "Cookie" to httpCookie,
                    ), body, budget.copy(callTimeoutMs = remaining,
                        connectTimeoutMs = minOf(budget.connectTimeoutMs, remaining)))
                }
                val response = exchange.execute(request)
                withContext(Dispatchers.Default) {
                    val context = currentCoroutineContext()
                    EapiResponse(StrictJson.decode(response.body, jsonBounds.copy(bytes = minOf(jsonBounds.bytes, budget.responseBytes)),
                        checkCancelled = context::ensureActive).jsonObject(),
                        response.headerValues("Set-Cookie"))
                }
            } ?: throw AuthException(AuthIssue.TIMEOUT)
        } catch (error: NetworkException) {
            throw AuthException(when (error.issue) {
                NetworkIssue.UNAVAILABLE -> AuthIssue.NETWORK_UNAVAILABLE
                NetworkIssue.TIMEOUT -> AuthIssue.TIMEOUT
                NetworkIssue.CERTIFICATE -> AuthIssue.CERTIFICATE
                NetworkIssue.BUSY -> AuthIssue.BUSY
                NetworkIssue.CLOSED -> AuthIssue.CLOSED
                NetworkIssue.HTTP -> when (error.statusCode) {
                    401, 403 -> AuthIssue.LOGIN_REQUIRED
                    429 -> AuthIssue.RATE_LIMITED
                    else -> AuthIssue.INVALID_RESPONSE
                }
                else -> AuthIssue.INVALID_RESPONSE
            }, error.statusCode, error.retryAfterSeconds)
        }
    }

    companion object {
        const val ORIGIN = "https://interface.music.163.com"
        // Hex doubles the encrypted payload; leave space for the longest path, framing and AES padding.
        private const val MAX_JSON_BYTES = 31 * 1024
        private val PATH = Regex("/api/[A-Za-z0-9_/]+")
        private fun fromCookie(cookie: NeteaseCookie): Map<String, String> = cookie.headerValue().split("; ").associate {
            val split = it.indexOf('=')
            it.substring(0, split) to it.substring(split + 1)
        }

        // ECB and MD5 are the public EAPI wire format, not the AES-GCM session storage cipher.
        @SuppressLint("GetInstance")
        internal fun encodeParams(path: String, jsonBytes: ByteArray): ByteArray {
            if (path.length > 256 || !PATH.matches(path) || jsonBytes.size > MAX_JSON_BYTES) {
                throw AuthException(AuthIssue.INVALID_RESPONSE)
            }
            val json = jsonBytes.toString(Charsets.UTF_8)
            return try {
                val digest = MessageDigest.getInstance("MD5").digest("nobody${path}use${json}md5forencrypt".toByteArray(Charsets.UTF_8)).hex(false)
                val payload = "${path}-36cd479b6b5-${json}-36cd479b6b5-${digest}".toByteArray(Charsets.UTF_8)
                val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec("e82ckenh8dichen8".toByteArray(Charsets.US_ASCII), "AES"))
                ("params=" + cipher.doFinal(payload).hex(true)).toByteArray(Charsets.US_ASCII).also {
                    if (it.size > NetworkLimits.REQUEST_BYTES) throw AuthException(AuthIssue.INVALID_RESPONSE)
                }
            } catch (_: GeneralSecurityException) { throw AuthException(AuthIssue.INVALID_RESPONSE) }
            catch (_: ProviderException) { throw AuthException(AuthIssue.INVALID_RESPONSE) }
            catch (_: SecurityException) { throw AuthException(AuthIssue.INVALID_RESPONSE) }
        }
    }
}

private fun ByteArray.hex(uppercase: Boolean): String {
    val digits = if (uppercase) "0123456789ABCDEF" else "0123456789abcdef"
    return buildString(size * 2) {
        for (value in this@hex) {
            append(digits[(value.toInt() and 255) ushr 4])
            append(digits[value.toInt() and 15])
        }
    }
}
