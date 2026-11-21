package io.github.onlinemo.meloxwatch.data.meting

import io.github.onlinemo.meloxwatch.data.network.HttpOrigin
import io.github.onlinemo.meloxwatch.playback.resources.MediaHttpTarget
import io.github.onlinemo.meloxwatch.playback.resources.ResourceCompleteness

internal object MetingLimits {
    const val URL_CHARACTERS = 2048
    const val ID_CHARACTERS = 32
    const val QUERY_PARAMETERS = 64
    const val HEADER_NAME_CHARACTERS = 64
    const val CREDENTIAL_CHARACTERS = 4096
    const val CONFIGURATION_BYTES = 16 * 1024
    const val ENCRYPTED_BYTES = 20 * 1024
    const val JSON_BYTES = 256 * 1024
    const val RESULTS = 16
    const val DISPLAY_CHARACTERS = 512
    const val PENDING_REQUESTS = 4
    const val JOINED_REQUESTS = 4
}

enum class MetingEndpointMode { ENDPOINT, TEMPLATE }

enum class MetingIssue {
    NOT_CONFIGURED, DISABLED, INVALID_ADDRESS, INVALID_TEMPLATE, CONFLICTING_PARAMETERS,
    CREDENTIAL_INVALID, CREDENTIAL_ORIGIN_CHANGED, ID_REQUIRED, WRONG_SOURCE,
    KEY_UNAVAILABLE, CONFIGURATION_CORRUPT, STORAGE_UNAVAILABLE, CONFIGURATION_CHANGED,
    BUSY, CLOSED, NETWORK_UNAVAILABLE, NETWORK_TIMEOUT, CERTIFICATE, RATE_LIMITED,
    AUTH_REQUIRED, PROTOCOL_INVALID, RESPONSE_TOO_LARGE, EMPTY_RESOURCE,
    CONFLICTING_TRACK, MULTIPLE_RESULTS, RESOURCE_INVALID, REDIRECT_LIMIT,
}

class MetingException(val issue: MetingIssue, val protocolCompatible: Boolean = false) : Exception(issue.name)

enum class MetingCredentialEdit { KEEP, REPLACE, CLEAR }

/** 仅当前设置编辑持有，退出即丢弃；不进入 SavedState、备份、诊断或公开配置快照。 */
internal class MetingSettingsDraft(
    val endpoint: String = "",
    val mode: MetingEndpointMode = MetingEndpointMode.ENDPOINT,
    val enabled: Boolean = false,
    val allowCleartext: Boolean = false,
    val credentialEdit: MetingCredentialEdit = MetingCredentialEdit.KEEP,
    val headerName: String = "Authorization",
    val credentialInput: String = "",
) {
    fun changed(
        endpoint: String = this.endpoint,
        mode: MetingEndpointMode = this.mode,
        enabled: Boolean = this.enabled,
        allowCleartext: Boolean = this.allowCleartext,
        credentialEdit: MetingCredentialEdit = this.credentialEdit,
        headerName: String = this.headerName,
        credentialInput: String = this.credentialInput,
    ) = MetingSettingsDraft(endpoint, mode, enabled, allowCleartext, credentialEdit, headerName, credentialInput)
    override fun toString(): String = "MetingSettingsDraft(redacted)"
}

/** 地址只公开规范化主机及端口；查询、路径、headers 和密文不进入此模型。 */
data class MetingConfigurationSnapshot(
    val generation: Long = 0L,
    val loaded: Boolean = false,
    val configured: Boolean = false,
    val enabled: Boolean = false,
    val hostLabel: String? = null,
    val mode: MetingEndpointMode? = null,
    val allowCleartext: Boolean = false,
    val hasCredential: Boolean = false,
    val changing: Boolean = false,
    val issue: MetingIssue? = null,
) {
    override fun toString(): String = "MetingConfigurationSnapshot(configured=$configured, enabled=$enabled)"
}

/** 值仅在独立服务 origin 的请求构造阶段读取，不提供账户 Cookie 代填。 */
internal class MetingServiceCredential private constructor(val name: String, private val value: String) {
    fun headers(): Map<String, String> = mapOf(name to value)
    internal fun storedValue(): String = value
    override fun toString(): String = "MetingServiceCredential(redacted)"

    companion object {
        fun checked(name: String, value: String): MetingServiceCredential {
            val blocked = setOf("host", "content-length", "transfer-encoding", "connection", "proxy-authorization",
                "range", "accept-encoding", "upgrade", "te", "trailer")
            if (name.length !in 1..MetingLimits.HEADER_NAME_CHARACTERS ||
                name.any { it !in 'a'..'z' && it !in 'A'..'Z' && it !in '0'..'9' && it !in "!#$%&'*+-.^_`|~" } ||
                name.lowercase(java.util.Locale.ROOT) in blocked ||
                value.length !in 1..MetingLimits.CREDENTIAL_CHARACTERS || value.any { it.code !in 32..126 }
            ) throw MetingException(MetingIssue.CREDENTIAL_INVALID)
            return MetingServiceCredential(name, value)
        }
    }
}

internal class MetingStoredConfiguration(
    val generation: Long,
    val endpoint: MetingEndpoint,
    val enabled: Boolean,
    val allowCleartext: Boolean,
    val credential: MetingServiceCredential?,
) {
    val origin: HttpOrigin get() = endpoint.origin
    init { if (generation < 0L) throw MetingException(MetingIssue.CONFIGURATION_CORRUPT) }
    override fun toString(): String = "MetingStoredConfiguration(redacted)"
}

enum class MetingTrackMatch { SINGLE_COMPATIBLE_UNVERIFIED, EXACT_SERVICE_DECLARATION }
enum class MetingPlaybackTestState { NOT_STARTED, WAITING_NEW_RESOURCE, EXISTING_STREAM, BUFFERING, READY, PLAYING, PAUSED, ENDED, FAILED, OTHER_SOURCE }

/** 兼容与取得 URL 分开报告；没有实际播放器观察时不填可播或成功。 */
data class MetingTestReport(
    val addressValid: Boolean = false,
    val protocolCompatible: Boolean = false,
    val resourceResolved: Boolean = false,
    val match: MetingTrackMatch? = null,
    val completeness: ResourceCompleteness? = null,
    val artworkIssue: MetingIssue? = null,
    val lyricsIssue: MetingIssue? = null,
    val playback: MetingPlaybackTestState = MetingPlaybackTestState.NOT_STARTED,
    val issue: MetingIssue? = null,
)

internal class MetingResolvedSong(
    val target: MediaHttpTarget,
    val artwork: MediaHttpTarget?,
    val lyrics: MediaHttpTarget?,
    val match: MetingTrackMatch,
    val displayName: String?,
    val artist: String?,
    val completeness: ResourceCompleteness = ResourceCompleteness.UNKNOWN,
    val artworkIssue: MetingIssue? = null,
    val lyricsIssue: MetingIssue? = null,
) {
    override fun toString(): String = "MetingResolvedSong(redacted)"
}

internal fun requireMetingTrackId(value: String): String {
    if (value.length !in 1..MetingLimits.ID_CHARACTERS || value.first() !in '1'..'9' || value.any { it !in '0'..'9' }) {
        throw MetingException(MetingIssue.ID_REQUIRED)
    }
    return value
}
