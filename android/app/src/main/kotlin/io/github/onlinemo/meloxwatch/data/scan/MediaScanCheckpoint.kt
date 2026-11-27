package io.github.onlinemo.meloxwatch.data.scan

import java.util.Base64

/** Published only with a successful, complete source reconciliation; wall-clock time is not a watermark. */
data class MediaScanCheckpoint(
    val sourceId: String,
    val scanId: String,
    val sourceRevision: Long,
    val authEpoch: Long,
    val rulesRevision: Long,
    val providerVersion: String,
    val generation: Long,
    val mode: ScanMode,
) {
    init {
        require(identifier.matches(sourceId) && identifier.matches(scanId))
        require(listOf(sourceRevision, authEpoch, rulesRevision, generation).all { it >= 0L })
        require(providerVersion.isNotBlank() && providerVersion.length <= 4096)
    }

    fun matches(token: ScanToken): Boolean = sourceId == token.sourceId && sourceRevision == token.sourceRevision &&
        authEpoch == token.authEpoch && rulesRevision == token.rulesRevision

    fun matchesScan(token: ScanToken): Boolean = matches(token) && scanId == token.scanId && mode == token.mode

    fun encode(): String = listOf("media2", sourceId, scanId, sourceRevision.toString(), authEpoch.toString(),
        rulesRevision.toString(), Base64.getUrlEncoder().withoutPadding().encodeToString(providerVersion.toByteArray(Charsets.UTF_8)),
        generation.toString(), mode.name).joinToString("|")

    companion object {
        private val identifier = Regex("[A-Za-z0-9._:-]{1,256}")
        private val integer = Regex("0|[1-9][0-9]{0,18}")

        /** Legacy or damaged watermarks require an explicit full recheck, never a guessed generation. */
        fun decode(value: String?): MediaScanCheckpoint? {
            if (value == null || value.length > 8192) return null
            val parts = value.split('|')
            if (parts.size != 9 || parts[0] != "media2" || !identifier.matches(parts[1]) || !identifier.matches(parts[2])) return null
            fun number(index: Int): Long? = parts[index].takeIf(integer::matches)?.toLongOrNull()
            val revision = number(3) ?: return null
            val auth = number(4) ?: return null
            val rules = number(5) ?: return null
            val generation = number(7) ?: return null
            val mode = ScanMode.entries.firstOrNull { it.name == parts[8] } ?: return null
            val version = try { String(Base64.getUrlDecoder().decode(parts[6]), Charsets.UTF_8) }
                catch (_: IllegalArgumentException) { return null }
            if (version.isBlank() || version.length > 4096 ||
                Base64.getUrlEncoder().withoutPadding().encodeToString(version.toByteArray(Charsets.UTF_8)) != parts[6]) return null
            return MediaScanCheckpoint(parts[1], parts[2], revision, auth, rules, version, generation, mode)
        }
    }
}
