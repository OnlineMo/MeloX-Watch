package io.github.onlinemo.meloxwatch.data.netease

import io.github.onlinemo.meloxwatch.data.auth.AuthException
import io.github.onlinemo.meloxwatch.data.auth.AuthIssue
import io.github.onlinemo.meloxwatch.data.network.HttpExchange
import io.github.onlinemo.meloxwatch.data.network.HttpRequest
import io.github.onlinemo.meloxwatch.data.network.HttpResponse
import io.github.onlinemo.meloxwatch.data.network.JsonObject
import io.github.onlinemo.meloxwatch.data.network.NetworkException
import io.github.onlinemo.meloxwatch.data.network.NetworkIssue
import io.github.onlinemo.meloxwatch.data.network.NetworkLimits
import io.github.onlinemo.meloxwatch.data.network.StrictJson
import io.github.onlinemo.meloxwatch.data.network.jsonLong
import io.github.onlinemo.meloxwatch.data.network.jsonObject
import io.github.onlinemo.meloxwatch.data.network.jsonString
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class NeteaseAuthApiTest {
    @Before fun initializeThePinnedCookieParserExplicitly() { OkHttp.initialize(RuntimeEnvironment.getApplication()) }

    @Test fun eapiMatchesTheFixedIndependentDotNetCipherVector() {
        // Generated with .NET AES ECB/PKCS7 and MD5 over public fixture bytes; no application code was run.
        val expected = "params=1CA81A97B7BAEB099F29A9B99A25CD6E58E7BA5B35F25231033065C776830C0A38A128CF8B3EAF44635E50E0B9800A4A037CBDA1E04BD460C02768E3ECD5E66E905A5BCAE52009FB0933EFB373A62E834048DEBDABCCC627F25820A8D94E0A8C"
        assertEquals(expected, NeteaseEapi.encodeParams("/api/login/qrcode/unikey", "{\"type\":3}".toByteArray()).toString(Charsets.US_ASCII))
    }

    @Test fun appliesTheWireBudgetBeforeEncryptionAndLeavesSpaceForTheLongestPath() {
        val path = "/api/" + "a".repeat(251)
        val body = NeteaseEapi.encodeParams(path, ByteArray(31 * 1024) { ' '.code.toByte() })
        assertTrue(body.size <= NetworkLimits.REQUEST_BYTES)
        expect(AuthIssue.INVALID_RESPONSE) { NeteaseEapi.encodeParams(path, ByteArray(31 * 1024 + 1)) }
        expect(AuthIssue.INVALID_RESPONSE) { NeteaseEapi.encodeParams("/api/../other", "{}".toByteArray()) }
    }

    @Test fun qrKeyUsesThePinnedTypeThreeEndpointAndBuildsTheQrLocally() = runBlocking {
        for (body in listOf("{\"code\":200,\"unikey\":\"fixture-key\"}", "{\"code\":200,\"data\":{\"unikey\":\"fixture-key\"}}")) {
            var captured: HttpRequest? = null
            val api = NeteaseAuthApi(NeteaseEapi(HttpExchange {
                captured = it
                json(body)
            }))
            val key = api.createKey()
            assertEquals("https://music.163.com/login?codekey=fixture-key", key.content())
            val request = requireNotNull(captured)
            assertEquals(NeteaseEapi.ORIGIN + "/eapi/login/qrcode/unikey", request.url)
            assertEquals(3L, decrypted(request)["type"].jsonLong())
            assertFalse((decrypted(request)["e_r"] as io.github.onlinemo.meloxwatch.data.network.JsonBoolean).value)
            assertFalse(request.toString().contains("fixture-key"))
        }
        assertEquals("https://music.163.com/login?codekey=a%26b%3Dc", LoginQrKey.parse("a&b=c").content())
    }

    @Test fun qrPollingDistinguishesExpiredScanConfirmationAndAuthorization() = runBlocking {
        for ((code, phase) in listOf(800 to QrCheckPhase.EXPIRED, 801 to QrCheckPhase.WAITING_SCAN,
            802 to QrCheckPhase.WAITING_CONFIRMATION, 803 to QrCheckPhase.AUTHORIZED)) {
            var captured: HttpRequest? = null
            val api = NeteaseAuthApi(NeteaseEapi(HttpExchange {
                captured = it
                json("{\"code\":$code,\"cookie\":\"MUSIC_U=fixture\"}")
            }))
            val result = api.checkKey(LoginQrKey.parse("fixture-key"))
            assertEquals(phase, result.phase)
            assertEquals(phase == QrCheckPhase.AUTHORIZED, result.credential != null)
            assertEquals("fixture-key", decrypted(requireNotNull(captured))["key"].jsonString())
            assertEquals(3L, decrypted(requireNotNull(captured))["type"].jsonLong())
        }
    }

    @Test fun authorizedSetCookieFallbackIsBoundToTheExactAccountOriginAndPath(): Unit = runBlocking {
        val headers = listOf("Set-Cookie" to "MUSIC_U=fixture; Path=/; Secure; HttpOnly", "Set-Cookie" to "__csrf=fixture; Path=/; Secure")
        val api = NeteaseAuthApi(NeteaseEapi(HttpExchange { json("{\"code\":803}", headers) }))
        val credential = requireNotNull(api.checkKey(LoginQrKey.parse("fixture")).credential)
        assertEquals("MUSIC_U=fixture; __csrf=fixture", credential.headerValue())
        expect(AuthIssue.INVALID_RESPONSE) { NeteaseCookie.fromSetCookie(listOf("MUSIC_U=fixture; Path=/different"), 0) }
        expect(AuthIssue.INVALID_RESPONSE) { NeteaseCookie.fromSetCookie(listOf("MUSIC_U=fixture; Max-Age=0; Path=/"), System.currentTimeMillis()) }
        expect(AuthIssue.INVALID_RESPONSE) { NeteaseCookie.fromSetCookie(listOf("MUSIC_U=fixture; Domain=other.invalid; Path=/"), 0) }
    }

    @Test fun anOpaqueCookieStillRequiresAValidAuthenticatedAccountResponse(): Unit = runBlocking {
        val credential = NeteaseCookie.fromHeader("MUSIC_U=fixture").credential()
        val api = NeteaseAuthApi(NeteaseEapi(HttpExchange { json("{\"code\":301,\"profile\":null}") }))
        expect(AuthIssue.LOGIN_REQUIRED) { runBlocking { api.verify(credential) } }
    }

    @Test fun validatesAccountIdentityWithoutFloatingPointIdLoss() = runBlocking {
        val id = "123456789012345678901234567890"
        val api = NeteaseAuthApi(NeteaseEapi(HttpExchange { json("{\"code\":200,\"profile\":{\"userId\":\"$id\",\"nickname\":\"夹具\"},\"account\":{\"id\":\"$id\"}}") }))
        val verified = api.verify(NeteaseCookie.fromHeader("MUSIC_U=fixture").credential())
        assertEquals(id, verified.stored.account.accountId)
        assertEquals("夹具", verified.stored.account.displayName)
        assertFalse(verified.toString().contains(id))
        assertFalse(verified.stored.account.toString().contains(id))
    }

    @Test fun rejectsMissingInvalidOrMismatchedProfileInsteadOfAcceptingCookiePresence() {
        val credential = NeteaseCookie.fromHeader("MUSIC_U=fixture").credential()
        val responses = listOf(
            "{\"code\":200,\"profile\":null}" to AuthIssue.LOGIN_REQUIRED,
            "{\"code\":200,\"profile\":{\"userId\":0,\"nickname\":\"fixture\"}}" to AuthIssue.INVALID_RESPONSE,
            "{\"code\":200,\"profile\":{\"userId\":1e3,\"nickname\":\"fixture\"}}" to AuthIssue.INVALID_RESPONSE,
            "{\"code\":200,\"profile\":{\"userId\":1,\"nickname\":\"\"}}" to AuthIssue.INVALID_RESPONSE,
            "{\"code\":200,\"profile\":{\"userId\":1,\"nickname\":\"fixture\"},\"account\":{\"id\":2}}" to AuthIssue.INVALID_RESPONSE,
            "{\"code\":200,\"code\":403}" to AuthIssue.INVALID_RESPONSE,
        )
        for ((body, issue) in responses) {
            val api = NeteaseAuthApi(NeteaseEapi(HttpExchange { json(body) }))
            expect(issue) { runBlocking { api.verify(credential) } }
        }
    }

    @Test fun cookiesCannotPersistOrOverrideProtocolDeviceMetadata() = runBlocking {
        val cookie = NeteaseCookie.fromHeader("MUSIC_U=fixture; __csrf=fixture; deviceId=old-device; osver=private-os; mobilename=private-model")
        assertFalse(cookie.headerValue().contains("old-device"))
        var captured: HttpRequest? = null
        NeteaseEapi(HttpExchange {
            captured = it
            json("{}")
        }).post("/api/fixture", cookie = cookie)
        val request = requireNotNull(captured)
        assertFalse(request.headers.getValue("Cookie").contains("old-device"))
        assertFalse(request.headers.getValue("Cookie").contains("private-model"))
        val header = decrypted(request)["header"].jsonObject()
        assertTrue(header["deviceId"].jsonString().matches(Regex("[0-9A-F]{32}")))
        assertEquals("", header["mobilename"].jsonString())
    }

    @Test fun rejectsCookieAttributesDuplicatesNonAsciiAndHeaderInjection() {
        for (value in listOf("MUSIC_U=one; MUSIC_U=two", "MUSIC_U=fixture; Domain=music.163.com", "MUSIC_U=音",
            "MUSIC_U=fixture\r\nX-Test: value", "MUSIC_U=\"quoted\"", "deviceId=only-metadata")) {
            expect(AuthIssue.INVALID_RESPONSE) { NeteaseCookie.fromHeader(value) }
        }
    }

    @Test fun mapsTransportFailuresAndPropagatesCancellationWithoutCredentialDiagnostics() {
        for ((failure, issue) in listOf(NetworkException(NetworkIssue.TIMEOUT) to AuthIssue.TIMEOUT,
            NetworkException(NetworkIssue.CERTIFICATE) to AuthIssue.CERTIFICATE,
            NetworkException(NetworkIssue.HTTP, 429, 12) to AuthIssue.RATE_LIMITED)) {
            val api = NeteaseAuthApi(NeteaseEapi(HttpExchange { throw failure }))
            val error = expect(issue) { runBlocking { api.createKey() } }
            assertEquals(failure.retryAfterSeconds, error.retryAfterSeconds)
            assertEquals(null, error.cause)
        }
        val api = NeteaseAuthApi(NeteaseEapi(HttpExchange { throw CancellationException("fixture") }))
        assertThrows(CancellationException::class.java) { runBlocking { api.createKey() } }
    }

    private fun decrypted(request: HttpRequest): JsonObject {
        val hex = request.body.toString(Charsets.US_ASCII).removePrefix("params=")
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec("e82ckenh8dichen8".toByteArray(Charsets.US_ASCII), "AES"))
        val parts = cipher.doFinal(bytes).toString(Charsets.UTF_8).split("-36cd479b6b5-")
        assertEquals(3, parts.size)
        val digest = MessageDigest.getInstance("MD5").digest("nobody${parts[0]}use${parts[1]}md5forencrypt".toByteArray())
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        assertEquals(digest, parts[2])
        return StrictJson.decode(parts[1].toByteArray(Charsets.UTF_8)).jsonObject()
    }
    private fun json(body: String, headers: List<Pair<String, String>> = emptyList()): HttpResponse = HttpResponse(200, headers, body.toByteArray(Charsets.UTF_8))
    private fun expect(issue: AuthIssue, action: () -> Unit): AuthException = assertThrows(AuthException::class.java) { action() }.also { assertEquals(issue, it.issue) }
}
