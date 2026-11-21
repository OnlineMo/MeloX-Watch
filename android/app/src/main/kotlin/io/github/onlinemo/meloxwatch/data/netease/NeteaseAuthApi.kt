/*
 * QR type 3 and account endpoints adapted from youshen2/MeloX,
 * commit 09713edb847a0f0a3515a34f779cc630d4fe5985,
 * MeloXWatch/Networking/WatchNeteaseAPI.swift. GPL-3.0; see LICENSE.
 * Modified 2026-11-16: strict response validation, bounded credentials,
 * verified account identity and no plaintext or implicit session saving.
 */
package io.github.onlinemo.meloxwatch.data.netease

import io.github.onlinemo.meloxwatch.data.auth.AccountIdentity
import io.github.onlinemo.meloxwatch.data.auth.AuthException
import io.github.onlinemo.meloxwatch.data.auth.AuthIssue
import io.github.onlinemo.meloxwatch.data.auth.SessionCredential
import io.github.onlinemo.meloxwatch.data.auth.StoredSession
import io.github.onlinemo.meloxwatch.data.auth.VerifiedSession
import io.github.onlinemo.meloxwatch.data.network.JsonNull
import io.github.onlinemo.meloxwatch.data.network.JsonNumber
import io.github.onlinemo.meloxwatch.data.network.JsonObject
import io.github.onlinemo.meloxwatch.data.network.JsonText
import io.github.onlinemo.meloxwatch.data.network.JsonValue
import io.github.onlinemo.meloxwatch.data.network.NetworkException
import io.github.onlinemo.meloxwatch.data.network.MetadataCallBudget
import io.github.onlinemo.meloxwatch.data.network.jsonLong
import io.github.onlinemo.meloxwatch.data.network.jsonObject
import io.github.onlinemo.meloxwatch.data.network.jsonString
import java.net.URLEncoder

internal class LoginQrKey private constructor(private val key: String) {
    fun requestValue(): String = key
    fun content(): String = "https://music.163.com/login?codekey=" + URLEncoder.encode(key, "UTF-8")
    override fun toString(): String = "LoginQrKey(redacted)"
    companion object {
        fun parse(value: String): LoginQrKey {
            if (value.length !in 1..512 || value.any { it.code !in 33..126 }) invalid()
            return LoginQrKey(value)
        }
    }
}

internal enum class QrCheckPhase { EXPIRED, WAITING_SCAN, WAITING_CONFIRMATION, AUTHORIZED }
internal class QrCheckResult(val phase: QrCheckPhase, val credential: SessionCredential? = null) {
    override fun toString(): String = "QrCheckResult(phase=$phase, redacted)"
}

/**
 * 显式在线登录的可取消请求边界，只申请/查询 key 和验证账号，不保存会话。
 *
 * [verify] 必须取得有效账号响应，非空 Cookie 本身不构成验证。调用方拥有页面可见性、
 * 轮询总预算和 generation 检查，并把验证结果交给会话接受/提交边界。失败使用脱敏的
 * AuthException，调用方取消继续传播，凭据和账号响应不得写入诊断。
 */
internal interface NeteaseLoginApi {
    suspend fun createKey(): LoginQrKey
    suspend fun checkKey(key: LoginQrKey): QrCheckResult
    suspend fun verify(credential: SessionCredential): VerifiedSession
}

internal class NeteaseAuthApi(
    private val eapi: NeteaseEapi,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) : NeteaseLoginApi {
    override suspend fun createKey(): LoginQrKey = checked {
        val response = eapi.post("/api/login/qrcode/unikey", mapOf("type" to JsonNumber("3"))).json
        requireSuccess(response)
        val direct = response["unikey"]
        val nested = response["data"].takeUnless { it == null || it === JsonNull }?.jsonObject()?.get("unikey")
        val key = direct.takeUnless { it == null || it === JsonNull } ?: nested
        LoginQrKey.parse(key.jsonString())
    }

    override suspend fun checkKey(key: LoginQrKey): QrCheckResult = checked {
        val response = eapi.post("/api/login/qrcode/client/login", mapOf("type" to JsonNumber("3"), "key" to JsonText(key.requestValue())))
        when (val code = response.json["code"].jsonLong()) {
            800L -> QrCheckResult(QrCheckPhase.EXPIRED)
            801L -> QrCheckResult(QrCheckPhase.WAITING_SCAN)
            802L -> QrCheckResult(QrCheckPhase.WAITING_CONFIRMATION)
            803L -> {
                val field = response.json["cookie"]
                val cookie = if (field == null || field === JsonNull || field is JsonText && field.value.isEmpty()) {
                    NeteaseCookie.fromSetCookie(response.setCookie, nowEpochMs())
                } else NeteaseCookie.fromHeader(field.jsonString())
                QrCheckResult(QrCheckPhase.AUTHORIZED, cookie.credential())
            }
            else -> throw serverFailure(code)
        }
    }

    override suspend fun verify(credential: SessionCredential): VerifiedSession = verify(credential, MetadataCallBudget())

    internal suspend fun verify(credential: SessionCredential, budget: MetadataCallBudget): VerifiedSession = checked {
        val response = eapi.post("/api/w/nuser/account/get", cookie = NeteaseCookie.fromCredential(credential), budget = budget).json
        requireSuccess(response)
        val profile = response["profile"]
        if (profile == null || profile === JsonNull) throw AuthException(AuthIssue.LOGIN_REQUIRED)
        val fields = profile.jsonObject()
        val id = accountId(fields["userId"])
        val account = response["account"]
        if (account != null && account !== JsonNull && accountId(account.jsonObject()["id"]) != id) invalid()
        val name = fields["nickname"].jsonString()
        if (name.isBlank() || name.length > 128 || name.any { it.code < 32 }) invalid()
        VerifiedSession(StoredSession(AccountIdentity("netease", id, name), credential))
    }

    private fun requireSuccess(response: JsonObject) {
        val code = response["code"].jsonLong()
        if (code != 200L) throw serverFailure(code)
    }

    private inline fun <T> checked(action: () -> T): T = try { action() }
    catch (_: NetworkException) { throw AuthException(AuthIssue.INVALID_RESPONSE) }

    private fun accountId(value: JsonValue?): String {
        val id = when (value) {
            is JsonText -> value.value
            is JsonNumber -> value.value
            else -> invalid()
        }
        if (id.length !in 1..32 || id.first() !in '1'..'9' || id.any { it !in '0'..'9' }) invalid()
        return id
    }
}

private fun serverFailure(code: Long): AuthException = AuthException(when (code) {
    301L, 401L, 403L -> AuthIssue.LOGIN_REQUIRED
    429L -> AuthIssue.RATE_LIMITED
    else -> AuthIssue.INVALID_RESPONSE
}, code.takeIf { it in 0..9999 }?.toInt())

private fun invalid(): Nothing = throw AuthException(AuthIssue.INVALID_RESPONSE)
