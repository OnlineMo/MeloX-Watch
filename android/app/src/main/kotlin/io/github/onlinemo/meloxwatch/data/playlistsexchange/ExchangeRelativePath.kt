package io.github.onlinemo.meloxwatch.data.playlistsexchange

import java.text.Normalizer
import java.util.Collections

/** 已授权树内的名称序列；只规范化提示，不解析文件系统/URI或扩大授权。 */
class ExchangeRelativePath private constructor(parts: List<String>) {
    val components: List<String> = Collections.unmodifiableList(ArrayList(parts))

    override fun toString(): String = components.joinToString("/")
    override fun equals(other: Any?): Boolean = other is ExchangeRelativePath && components == other.components
    override fun hashCode(): Int = components.hashCode()

    companion object {
        fun parse(value: String): ExchangeRelativePath {
            validateExchangeText(value)
            if (value.isBlank() || value.startsWith('/') || value.startsWith('\\') || ':' in value ||
                containsExchangeControl(value)) {
                exchangeFail(PlaylistExchangeIssue.INVALID_VALUE)
            }
            val components = mutableListOf<String>()
            // Windows M3U使用反斜杠分隔；百分号文本保持字面值，不能二次解码成父路径或分隔符。
            for (raw in value.replace('\\', '/').split('/')) {
                if (raw == ".") continue
                if (raw.isEmpty() || raw == "..") {
                    exchangeFail(PlaylistExchangeIssue.INVALID_VALUE)
                }
                val normalized = Normalizer.normalize(raw, Normalizer.Form.NFC)
                if (raw.length > 512 || normalized.length > 512) {
                    exchangeFail(PlaylistExchangeIssue.LIMIT_EXCEEDED)
                }
                components.add(normalized)
                // 根算一层，末尾音频名不算目录；与CUE的总目录深度16一致。
                if (components.size > PlaylistExchangeLimits.DEPTH) exchangeFail(PlaylistExchangeIssue.LIMIT_EXCEEDED)
            }
            if (components.isEmpty()) exchangeFail(PlaylistExchangeIssue.INVALID_VALUE)
            val normalizedLength = components.sumOf { it.length } + components.size - 1
            if (normalizedLength > PlaylistExchangeLimits.FIELD_CHARACTERS) {
                exchangeFail(PlaylistExchangeIssue.LIMIT_EXCEEDED)
            }
            return ExchangeRelativePath(components)
        }
    }
}
