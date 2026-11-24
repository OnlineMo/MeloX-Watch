package io.github.onlinemo.meloxwatch.data.meting

import io.github.onlinemo.meloxwatch.data.network.HttpOrigin
import io.github.onlinemo.meloxwatch.data.network.HttpTransportGate
import io.github.onlinemo.meloxwatch.data.network.NetworkException
import okhttp3.HttpUrl

/** 受限查询值模板；不执行脚本，不从路径、参数名或主机推测歌曲身份。 */
internal class MetingEndpoint private constructor(val url: HttpUrl, val mode: MetingEndpointMode) {
    val origin: HttpOrigin get() = HttpOrigin.of(url)
    val storedAddress: String get() = url.toString()
    val hostLabel: String get() {
        val host = if (url.host.contains(':')) "[${url.host}]" else url.host
        val defaultPort = if (url.scheme == "https") 443 else 80
        return if (url.port == defaultPort) host else "$host:${url.port}"
    }

    fun request(trackId: String): HttpUrl {
        requireMetingTrackId(trackId)
        if (mode == MetingEndpointMode.ENDPOINT) {
            return url.newBuilder().apply {
                if (url.queryParameter("server") == null) addQueryParameter("server", "netease")
                if (url.queryParameter("type") == null) addQueryParameter("type", "song")
                addQueryParameter("id", trackId)
            }.build()
        }
        val encoded = url.encodedQuery.orEmpty().split('&')
        val builder = url.newBuilder().encodedQuery(null)
        for (index in 0 until url.querySize) {
            val name = url.queryParameterName(index)
            val value = url.queryParameterValue(index)
            if (value != null && PLACEHOLDER.containsMatchIn(value)) {
                val replaced = PLACEHOLDER.replace(value) { found -> when (found.value) {
                    ":server" -> "netease"
                    ":type" -> "song"
                    ":id" -> trackId
                    else -> throw MetingException(MetingIssue.INVALID_TEMPLATE)
                } }
                builder.addQueryParameter(name, replaced)
            } else {
                // 与模板无关的参数保留原编码，不能破坏服务要求的签名或额外参数。
                val raw = encoded[index]
                val separator = raw.indexOf('=')
                builder.addEncodedQueryParameter(if (separator < 0) raw else raw.substring(0, separator),
                    if (separator < 0) null else raw.substring(separator + 1))
            }
        }
        return builder.build()
    }

    fun privateParameters(trackId: String? = null): List<Pair<String, String?>> = buildList {
        val configured = trackId?.let { request(it) } ?: url
        for (index in 0 until configured.querySize) {
            val name = configured.queryParameterName(index)
            val value = configured.queryParameterValue(index)
            if (name !in RESERVED && (value == null || !PLACEHOLDER.containsMatchIn(value))) add(name to value)
        }
    }

    override fun toString(): String = "MetingEndpoint(mode=$mode, redacted)"

    companion object {
        private val PLACEHOLDER = Regex(":[A-Za-z][A-Za-z0-9_]*")
        private val ALLOWED = setOf(":server", ":type", ":id")
        private val RESERVED = setOf("server", "type", "id")

        fun checked(value: String, mode: MetingEndpointMode, allowCleartext: Boolean): MetingEndpoint {
            val parsed = try { HttpTransportGate.parse(value) }
            catch (_: NetworkException) { throw MetingException(MetingIssue.INVALID_ADDRESS) }
            if (parsed.toString().length > MetingLimits.URL_CHARACTERS || parsed.username.isNotEmpty() || parsed.password.isNotEmpty() || parsed.fragment != null ||
                parsed.querySize > MetingLimits.QUERY_PARAMETERS ||
                parsed.scheme == "http" && !allowCleartext || parsed.scheme !in setOf("https", "http")
            ) throw MetingException(MetingIssue.INVALID_ADDRESS)
            if (parsed.pathSegments.any(PLACEHOLDER::containsMatchIn)) throw MetingException(MetingIssue.INVALID_TEMPLATE)
            val seenReserved = mutableSetOf<String>()
            var idPlaceholder = false
            for (index in 0 until parsed.querySize) {
                val name = parsed.queryParameterName(index)
                val queryValue = parsed.queryParameterValue(index)
                if (name.isEmpty() || PLACEHOLDER.containsMatchIn(name)) throw MetingException(MetingIssue.INVALID_TEMPLATE)
                if (name in RESERVED && !seenReserved.add(name)) throw MetingException(MetingIssue.CONFLICTING_PARAMETERS)
                val tokens = queryValue?.let { PLACEHOLDER.findAll(it).map { match -> match.value }.toList() }.orEmpty()
                if (tokens.any { it !in ALLOWED } || mode == MetingEndpointMode.ENDPOINT && tokens.isNotEmpty()) {
                    throw MetingException(MetingIssue.INVALID_TEMPLATE)
                }
                if (":id" in tokens) idPlaceholder = true
                when (name) {
                    "server" -> if (queryValue != "netease" && !(mode == MetingEndpointMode.TEMPLATE && queryValue == ":server")) {
                        throw MetingException(MetingIssue.CONFLICTING_PARAMETERS)
                    }
                    "type" -> if (queryValue != "song" && !(mode == MetingEndpointMode.TEMPLATE && queryValue == ":type")) {
                        throw MetingException(MetingIssue.CONFLICTING_PARAMETERS)
                    }
                    "id" -> if (mode != MetingEndpointMode.TEMPLATE || queryValue != ":id") {
                        throw MetingException(MetingIssue.CONFLICTING_PARAMETERS)
                    }
                }
            }
            if (mode == MetingEndpointMode.TEMPLATE && !idPlaceholder) throw MetingException(MetingIssue.INVALID_TEMPLATE)
            val endpoint = MetingEndpoint(parsed, mode)
            // 保存前按支持的最长 ID 检查组装长度，非法新输入不能先废弃旧配置再在请求时失败。
            if (endpoint.request("1".repeat(MetingLimits.ID_CHARACTERS)).toString().length > MetingLimits.URL_CHARACTERS) {
                throw MetingException(MetingIssue.INVALID_ADDRESS)
            }
            return endpoint
        }
    }
}
