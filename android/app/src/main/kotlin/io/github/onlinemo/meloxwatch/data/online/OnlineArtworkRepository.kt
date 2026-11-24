package io.github.onlinemo.meloxwatch.data.online

import android.os.SystemClock
import io.github.onlinemo.meloxwatch.data.artwork.LocalArtworkRepository
import io.github.onlinemo.meloxwatch.data.auth.AccountRequestToken
import io.github.onlinemo.meloxwatch.data.auth.AuthException
import io.github.onlinemo.meloxwatch.data.auth.SessionRepository
import io.github.onlinemo.meloxwatch.data.netease.NativeCatalogEntry
import io.github.onlinemo.meloxwatch.data.network.BoundedHttpGetClient
import io.github.onlinemo.meloxwatch.data.network.HttpGetKind
import io.github.onlinemo.meloxwatch.data.network.HttpGetLimits
import io.github.onlinemo.meloxwatch.data.network.HttpTransportGate
import io.github.onlinemo.meloxwatch.data.network.NetworkException
import io.github.onlinemo.meloxwatch.data.network.NetworkIssue
import io.github.onlinemo.meloxwatch.data.themes.ThemeBitmapLease
import io.github.onlinemo.meloxwatch.model.OnlineCatalogException
import io.github.onlinemo.meloxwatch.model.OnlineCatalogIssue
import io.github.onlinemo.meloxwatch.model.onlineOpaqueHash
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl

/** URLs never enter rows or UI. Only a visible, verified account request may acquire a registered target. */
internal class OnlineArtworkRepository(
    private val sessions: SessionRepository,
    private val images: LocalArtworkRepository,
    private val http: BoundedHttpGetClient,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private class Target(val url: HttpUrl, val token: AccountRequestToken)
    private val memory = Any()
    private val targets = LinkedHashMap<String, Target>(16, 0.75f, true)

    fun register(native: NativeCatalogEntry, token: AccountRequestToken): String? {
        val value = native.artworkUrl ?: return null
        if (!sessions.isCurrent(token)) return null
        val url = try { HttpTransportGate.parseHttps(value) } catch (_: NetworkException) { return null }
        val key = owner(token) + ":" + onlineOpaqueHash(url.toString())
        synchronized(memory) {
            targets.entries.removeAll { !sessions.isCurrent(it.value.token) }
            while (targets.size >= MAX_TARGETS && key !in targets) targets.remove(targets.keys.first())
            targets[key] = Target(url, token)
        }
        return key
    }

    fun hasTarget(key: String): Boolean = synchronized(memory) {
        targets[key]?.let { sessions.isCurrent(it.token) } == true
    }

    suspend fun load(key: String, targetPx: Int, allowNetwork: Boolean): ThemeBitmapLease? {
        if (!KEY.matches(key)) return null
        var pending: ThemeBitmapLease? = null
        try {
            return sessions.authenticated { _, token ->
                if (key.substringBefore(':') != owner(token)) return@authenticated null
                val derivedKey = onlineOpaqueHash("online-artwork:$key:$targetPx")
                images.derived(derivedKey, targetPx)?.let { cached ->
                    pending = cached
                    return@authenticated cached
                }
                if (!allowNetwork) return@authenticated null
                val registered = synchronized(memory) { targets[key] } ?: return@authenticated null
                if (!sessions.isCurrent(registered.token) || registered.token.account != token.account) return@authenticated null
                val started = nowMs()
                var target = registered.url
                var redirects = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (!sessions.isCurrent(token)) throw OnlineCatalogException(OnlineCatalogIssue.LOGIN_REQUIRED)
                    val elapsed = nowMs() - started
                    if (elapsed < 0L || elapsed >= TOTAL_MS) throw OnlineCatalogException(OnlineCatalogIssue.TIMEOUT)
                    val remaining = TOTAL_MS - elapsed
                    val response = http.get(HttpTransportGate.check(target),
                        HttpGetLimits(remaining, minOf(remaining, 3_000L), 1024 * 1024, HttpGetKind.IMAGE), context = null)
                    if (response.code in REDIRECTS) {
                        if (++redirects > MAX_REDIRECTS) throw OnlineCatalogException(OnlineCatalogIssue.INVALID_RESPONSE)
                        target = HttpTransportGate.redirect(target,
                            response.location ?: throw OnlineCatalogException(OnlineCatalogIssue.INVALID_RESPONSE))
                        continue
                    }
                    if (response.code !in 200..299) throw OnlineCatalogException(when (response.code) {
                        429 -> OnlineCatalogIssue.RATE_LIMITED
                        else -> OnlineCatalogIssue.INVALID_RESPONSE
                    })
                    currentCoroutineContext().ensureActive()
                    val ready = images.derived(derivedKey, targetPx, response.bytes)
                    pending = ready
                    return@authenticated ready
                }
                @Suppress("UNREACHABLE_CODE")
                null
            }.also { pending = null }
        } catch (error: AuthException) { throw OnlineCatalogException(authCatalogIssue(error.issue)) }
        catch (error: NetworkException) { throw OnlineCatalogException(when (error.issue) {
            NetworkIssue.UNAVAILABLE -> OnlineCatalogIssue.OFFLINE
            NetworkIssue.TIMEOUT -> OnlineCatalogIssue.TIMEOUT
            NetworkIssue.CERTIFICATE -> OnlineCatalogIssue.CERTIFICATE
            NetworkIssue.BUSY -> OnlineCatalogIssue.BUSY
            NetworkIssue.CLOSED -> OnlineCatalogIssue.CLOSED
            NetworkIssue.HTTP -> if (error.statusCode == 429) OnlineCatalogIssue.RATE_LIMITED else OnlineCatalogIssue.INVALID_RESPONSE
            else -> OnlineCatalogIssue.INVALID_RESPONSE
        }) }
        finally { pending?.close() }
    }

    private fun owner(token: AccountRequestToken) = onlineOpaqueHash(token.account.providerId + ":" + token.account.accountId)
    companion object {
        private const val MAX_TARGETS = 256
        private const val TOTAL_MS = 8_000L
        private const val MAX_REDIRECTS = 3
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private val KEY = Regex("[0-9a-f]{64}:[0-9a-f]{64}")
    }
}
