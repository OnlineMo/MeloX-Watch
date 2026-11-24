package io.github.onlinemo.meloxwatch.data.netease

import io.github.onlinemo.meloxwatch.data.auth.AuthException
import io.github.onlinemo.meloxwatch.data.auth.AuthIssue
import io.github.onlinemo.meloxwatch.data.auth.SessionCredential
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Credentials are scoped to the fixed NetEase HTTPS origin, with no global CookieJar. */
internal class NeteaseCookie private constructor(private val values: Map<String, String>) {
    fun value(name: String): String? = values[name]
    fun headerValue(): String = values.entries.sortedBy { it.key }.joinToString("; ") { "${it.key}=${it.value}" }
    fun credential(): SessionCredential = SessionCredential.fromValidatedHeader(headerValue())
    override fun toString(): String = "NeteaseCookie(redacted)"

    companion object {
        private const val MAX_COOKIES = 32
        private val ATTRIBUTES = setOf("domain", "path", "expires", "max-age", "secure", "httponly", "samesite")
        private val PROTOCOL_METADATA = setOf("os", "osver", "appver", "versioncode", "deviceid", "mobilename",
            "resolution", "channel", "buildver", "requestid")

        fun fromCredential(credential: SessionCredential): NeteaseCookie = fromHeader(credential.headerValue())

        fun fromHeader(value: String): NeteaseCookie {
            if (value.isEmpty() || value.length > SessionCredential.MAX_BYTES || value.any { it.code !in 32..126 }) invalid()
            val cookies = linkedMapOf<String, String>()
            for (pair in value.split(';')) {
                val trimmed = pair.trim()
                if (trimmed.isEmpty()) continue
                val split = trimmed.indexOf('=')
                if (split <= 0 || cookies.size >= MAX_COOKIES) invalid()
                val name = trimmed.substring(0, split).trim()
                val content = trimmed.substring(split + 1).trim()
                if (name.length > 64 || name.any { !token(it) } || name.lowercase(java.util.Locale.ROOT) in ATTRIBUTES ||
                    content.length > 8192 || content.any { !octet(it) } || cookies.put(name, content) != null
                ) invalid()
            }
            // A returned protocol header must not persist or replay the previous device's metadata.
            val credentials = cookies.filterKeys { it.lowercase(java.util.Locale.ROOT) !in PROTOCOL_METADATA }
            if (credentials.isEmpty()) invalid()
            return NeteaseCookie(credentials)
        }

        fun fromSetCookie(headers: List<String>, nowEpochMs: Long): NeteaseCookie {
            if (headers.size > MAX_COOKIES || headers.sumOf { it.length.toLong() } > SessionCredential.MAX_BYTES) invalid()
            val source = (NeteaseEapi.ORIGIN + "/eapi/login/qrcode/client/login").toHttpUrl()
            val target = (NeteaseEapi.ORIGIN + "/eapi/w/nuser/account/get").toHttpUrl()
            val values = linkedMapOf<String, String>()
            for (header in headers) {
                val cookie = Cookie.parse(source, header) ?: invalid()
                if (!cookie.matches(target) || cookie.expiresAt <= nowEpochMs) continue
                if (values.put(cookie.name, cookie.value) != null) invalid()
            }
            return fromHeader(values.entries.joinToString("; ") { "${it.key}=${it.value}" })
        }

        private fun token(char: Char): Boolean = char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in "!#$%&'*+-.^_`|~"
        private fun octet(char: Char): Boolean = char.code == 0x21 || char.code in 0x23..0x2b || char.code in 0x2d..0x3a ||
            char.code in 0x3c..0x5b || char.code in 0x5d..0x7e
        private fun invalid(): Nothing = throw AuthException(AuthIssue.INVALID_RESPONSE)
    }
}
