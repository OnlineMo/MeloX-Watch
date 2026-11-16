package io.github.onlinemo.meloxwatch.data.cue

import java.text.Normalizer
import java.util.Locale

/** A name sequence for proven enumeration edges, never an OS path or a URI to open. */
class CueRelativePath private constructor(val components: List<String>) {
    override fun equals(other: Any?): Boolean = other is CueRelativePath && components == other.components

    override fun hashCode(): Int = components.hashCode()

    companion object {
        fun parse(value: String): CueRelativePath {
            if (value.isEmpty() || value.length > CueLimits.PATH_CHARACTERS) {
                throw CueException(if (value.isEmpty()) CueIssue.INVALID_PATH else CueIssue.LIMIT_EXCEEDED)
            }
            if (value.startsWith('/') || value.startsWith('\\') || containsForbiddenPathCharacter(value)) {
                throw CueException(CueIssue.INVALID_PATH)
            }
            val result = ArrayList<String>()
            // Backslashes are literal CUE separators, not quote escapes. Percent escapes are not decoded.
            for (component in value.replace('\\', '/').split('/')) {
                if (component.isEmpty() || component == "..") throw CueException(CueIssue.INVALID_PATH)
                if (component == ".") continue
                if (component.length > CueLimits.PATH_COMPONENT_CHARACTERS) throw CueException(CueIssue.LIMIT_EXCEEDED)
                val normalized = Normalizer.normalize(component, Normalizer.Form.NFC)
                if (normalized.length > CueLimits.PATH_COMPONENT_CHARACTERS) throw CueException(CueIssue.LIMIT_EXCEEDED)
                result += normalized
                if (result.size > CueLimits.DIRECTORY_DEPTH) throw CueException(CueIssue.LIMIT_EXCEEDED)
            }
            if (result.isEmpty()) throw CueException(CueIssue.INVALID_PATH)
            return CueRelativePath(result.toList())
        }
    }
}

internal fun cueNameKey(name: String, policy: CueNamePolicy): String {
    if (name.length > CueLimits.PATH_COMPONENT_CHARACTERS) throw CueException(CueIssue.LIMIT_EXCEEDED)
    if (name.isEmpty() || containsForbiddenPathCharacter(name) || name.contains('/') || name.contains('\\')) {
        throw CueException(CueIssue.INVALID_PATH)
    }
    val normalized = Normalizer.normalize(name, Normalizer.Form.NFC)
    if (normalized.length > CueLimits.PATH_COMPONENT_CHARACTERS) throw CueException(CueIssue.LIMIT_EXCEEDED)
    return if (policy == CueNamePolicy.EXACT) normalized else
        Normalizer.normalize(normalized.lowercase(Locale.ROOT), Normalizer.Form.NFC)
}

private fun containsForbiddenPathCharacter(value: String): Boolean {
    var offset = 0
    while (offset < value.length) {
        // Classify code points so supplementary format characters cannot hide in surrogate pairs.
        val codePoint = Character.codePointAt(value, offset)
        val type = Character.getType(codePoint)
        if (codePoint == ':'.code || Character.isISOControl(codePoint) ||
            type == Character.FORMAT.toInt() || type == Character.SURROGATE.toInt()) return true
        offset += Character.charCount(codePoint)
    }
    return false
}
