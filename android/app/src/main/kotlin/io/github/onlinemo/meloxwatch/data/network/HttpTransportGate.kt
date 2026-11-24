package io.github.onlinemo.meloxwatch.data.network

import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 每个实际 HTTP 请求先经过这里；无服务上下文的客户端只能使用 HTTPS。 */
internal object HttpTransportGate {
    fun parseHttps(value: String): HttpUrl = check(parse(value))

    fun parse(value: String): HttpUrl {
        if (value.length !in 1..NetworkLimits.URL_CHARS || value != value.trim() ||
            value.any { it.code < 32 || it.code == 127 }
        ) invalid()
        return value.toHttpUrlOrNull() ?: invalid()
    }

    fun check(target: HttpUrl, context: ServiceHttpContext? = null, previous: HttpUrl? = null): HttpUrl {
        context?.requireCurrent()
        if (target.toString().length > NetworkLimits.URL_CHARS || target.username.isNotEmpty() ||
            target.password.isNotEmpty() || target.fragment != null || target.querySize > 128
        ) invalid()
        if (target.scheme !in setOf("https", "http")) invalid()
        if (target.scheme == "http" && (context == null || !context.allowCleartext || !context.origin.matches(target))) invalid()
        if (previous?.scheme == "https" && target.scheme == "http") invalid()
        return target
    }

    fun redirect(from: HttpUrl, location: String, context: ServiceHttpContext? = null): HttpUrl {
        if (location.length !in 1..NetworkLimits.URL_CHARS || location != location.trim() ||
            location.any { it.code < 32 || it.code == 127 }
        ) invalid()
        val resolved = from.resolve(location) ?: invalid()
        // 跨 origin 不携带用户配置的 API 查询秘密；其它返回签名的原编码和顺序保留。
        val isolated = context?.withoutPrivateParameters(resolved) ?: resolved
        return check(isolated, context, from)
    }

    private fun invalid(): Nothing = throw NetworkException(NetworkIssue.INVALID_REQUEST)
}

internal data class HttpOrigin(val scheme: String, val host: String, val port: Int) {
    fun matches(url: HttpUrl): Boolean = scheme == url.scheme && host == url.host && port == url.port
    override fun toString(): String = "HttpOrigin(redacted)"
    companion object { fun of(url: HttpUrl) = HttpOrigin(url.scheme, url.host, url.port) }
}

/** 元数据持有至 body 清理；媒体在接受已打开响应的同一配置锁内退休这份待打开登记。 */
internal interface PendingHttpUse : AutoCloseable {
    fun commitOpened(accept: () -> Unit)
}

/** 只由用户配置仓库创建；服务鉴权不成为原生客户端、账户凭据或全局默认 header。 */
internal class ServiceHttpContext(
    val origin: HttpOrigin,
    val allowCleartext: Boolean,
    headers: Map<String, String>,
    privateParameters: List<Pair<String, String?>>,
    private val current: () -> Boolean,
    private val registerPending: (() -> Unit) -> PendingHttpUse,
) {
    private val headers = headers.toMap()
    private val privateParameters = privateParameters.toList()

    fun requireCurrent() {
        if (!current()) throw CancellationException("Service configuration changed")
    }

    fun headersFor(target: HttpUrl): Map<String, String> {
        requireCurrent()
        return if (origin.matches(target)) headers else emptyMap()
    }

    fun ownPending(cancel: () -> Unit): PendingHttpUse {
        requireCurrent()
        return registerPending(cancel)
    }

    fun withoutPrivateParameters(target: HttpUrl): HttpUrl {
        if (origin.matches(target) || privateParameters.isEmpty() || target.encodedQuery == null) return target
        val encoded = requireNotNull(target.encodedQuery).split('&')
        val builder = target.newBuilder().encodedQuery(null)
        for (index in 0 until target.querySize) {
            val pair = target.queryParameterName(index) to target.queryParameterValue(index)
            if (pair in privateParameters) continue
            val raw = encoded[index]
            val separator = raw.indexOf('=')
            builder.addEncodedQueryParameter(if (separator < 0) raw else raw.substring(0, separator),
                if (separator < 0) null else raw.substring(separator + 1))
        }
        return builder.build()
    }

    override fun toString(): String = "ServiceHttpContext(redacted)"
}
