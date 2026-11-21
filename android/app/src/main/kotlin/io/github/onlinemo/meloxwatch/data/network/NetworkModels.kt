package io.github.onlinemo.meloxwatch.data.network

internal object NetworkLimits {
    const val ACTIVE_REQUESTS = 2
    const val WAITING_REQUESTS = 4
    const val REQUEST_BYTES = 64 * 1024
    const val RESPONSE_BYTES = 1024 * 1024
    const val HEADER_BYTES = 32 * 1024
    const val HEADER_COUNT = 128
    const val URL_CHARS = 2048
    const val CALL_TIMEOUT_MS = 8_000L
    const val CONNECT_TIMEOUT_MS = 5_000L
    const val IDLE_READ_TIMEOUT_MS = 10_000L
}

enum class NetworkIssue {
    INVALID_REQUEST, BUSY, CLOSED, UNAVAILABLE, TIMEOUT, CERTIFICATE,
    REDIRECT, HTTP, RESPONSE_TOO_LARGE, INVALID_RESPONSE,
}

/** Raw transport exceptions may contain an address or credentials; they never become UI errors. */
class NetworkException(
    val issue: NetworkIssue,
    val statusCode: Int? = null,
    val retryAfterSeconds: Long? = null,
) : Exception(issue.name)

/** Per-call limits can narrow the transport maxima; they never start a fresh resolver budget. */
internal data class MetadataCallBudget(
    val callTimeoutMs: Long = NetworkLimits.CALL_TIMEOUT_MS,
    val connectTimeoutMs: Long = NetworkLimits.CONNECT_TIMEOUT_MS,
    val responseBytes: Int = NetworkLimits.RESPONSE_BYTES,
) {
    init {
        if (callTimeoutMs !in 1..NetworkLimits.CALL_TIMEOUT_MS ||
            connectTimeoutMs !in 1..minOf(NetworkLimits.CONNECT_TIMEOUT_MS, callTimeoutMs) ||
            responseBytes !in 1..NetworkLimits.RESPONSE_BYTES
        ) throw NetworkException(NetworkIssue.INVALID_REQUEST)
    }
}

internal class HttpRequest(
    val url: String,
    headers: Map<String, String>,
    body: ByteArray,
    val budget: MetadataCallBudget = MetadataCallBudget(),
) {
    init {
        if (url.length > NetworkLimits.URL_CHARS || body.size > NetworkLimits.REQUEST_BYTES ||
            headers.size > NetworkLimits.HEADER_COUNT
        ) throw NetworkException(NetworkIssue.INVALID_REQUEST)
    }
    val headers = headers.toMap()
    val body = body.copyOf()
    override fun toString(): String = "HttpRequest(redacted)"
}

internal class HttpResponse(
    val statusCode: Int,
    val headers: List<Pair<String, String>>,
    val body: ByteArray,
) {
    fun headerValues(name: String): List<String> = headers.filter { it.first.equals(name, true) }.map { it.second }
    override fun toString(): String = "HttpResponse(status=$statusCode, redacted)"
}

/**
 * 有界元数据 HTTP 交换边界，调用方只接收已读取的响应字节，不接管底层 response body。
 *
 * 实现负责关闭 body 并遵守请求、等待和读取预算。调用方取消时传播取消；已经活动的调用
 * 保留 admission lease，直到其终止回调完成资源清理。失败只返回脱敏的 [NetworkException]。
 */
internal fun interface HttpExchange {
    suspend fun execute(request: HttpRequest): HttpResponse
}
