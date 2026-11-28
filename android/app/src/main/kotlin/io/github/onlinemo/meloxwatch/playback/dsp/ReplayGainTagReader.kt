package io.github.onlinemo.meloxwatch.playback.dsp

import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import io.github.onlinemo.meloxwatch.data.codec.ApeReplayGainEntry
import io.github.onlinemo.meloxwatch.data.codec.CodecReplayGainReadFailure
import java.util.Locale

enum class ReplayGainTagSource { NONE, ID3, VORBIS, APE, MIXED }
enum class ReplayGainTagIssue { INVALID_VALUE, CONFLICTING_VALUES, METADATA_TOO_LARGE, TAG_UNREADABLE }

data class ReplayGainTagResult(
    val tags: ReplayGainTags = ReplayGainTags(),
    val source: ReplayGainTagSource = ReplayGainTagSource.NONE,
    val issue: ReplayGainTagIssue? = null,
)

/** 只读取解码器已提取的原标签，不新增文件读取、seek 或网络请求。 */
@androidx.annotation.OptIn(UnstableApi::class)
internal object ReplayGainTagReader {
    private val fields = setOf("REPLAYGAIN_TRACK_GAIN", "REPLAYGAIN_TRACK_PEAK",
        "REPLAYGAIN_ALBUM_GAIN", "REPLAYGAIN_ALBUM_PEAK")
    private val number = Regex("[+-]?(?:[0-9]{1,3}(?:\\.[0-9]{1,12})?|\\.[0-9]{1,12})")

    fun read(metadata: Metadata?): ReplayGainTagResult {
        if (metadata == null) return ReplayGainTagResult()
        if (metadata.length() > 64) return ReplayGainTagResult(issue = ReplayGainTagIssue.METADATA_TOO_LARGE)
        val values = LinkedHashMap<String, Double>()
        val invalid = HashSet<String>()
        var issue: ReplayGainTagIssue? = null
        val sources = HashSet<ReplayGainTagSource>()

        fun accept(key: String?, value: String, source: ReplayGainTagSource) {
            if (key == null || key.length > 64) return
            val name = key.trim().uppercase(Locale.ROOT)
            if (name !in fields) return
            sources += source
            val peak = name.endsWith("_PEAK")
            val parsed = parse(value, peak)
            if (parsed == null) {
                invalid += name
                issue = issue ?: ReplayGainTagIssue.INVALID_VALUE
            } else {
                val previous = values[name]
                if (previous != null && previous != parsed) {
                    invalid += name
                    issue = ReplayGainTagIssue.CONFLICTING_VALUES
                } else values[name] = parsed
            }
        }

        for (index in 0 until metadata.length()) {
            when (val entry = metadata[index]) {
                is CodecReplayGainReadFailure -> return ReplayGainTagResult(issue =
                    if (entry.resourceLimit) ReplayGainTagIssue.METADATA_TOO_LARGE else ReplayGainTagIssue.TAG_UNREADABLE)
                is ApeReplayGainEntry -> accept(entry.key, entry.value, ReplayGainTagSource.APE)
                is VorbisComment -> accept(entry.key, entry.value, ReplayGainTagSource.VORBIS)
                is TextInformationFrame -> if (entry.id == "TXXX") {
                    if (entry.values.size > 16) return ReplayGainTagResult(issue = ReplayGainTagIssue.METADATA_TOO_LARGE)
                    for (value in entry.values) accept(entry.description, value, ReplayGainTagSource.ID3)
                }
            }
        }
        fun value(key: String): Double? = values[key]?.takeUnless { key in invalid }
        val tags = ReplayGainTags(value("REPLAYGAIN_TRACK_GAIN"), value("REPLAYGAIN_TRACK_PEAK"),
            value("REPLAYGAIN_ALBUM_GAIN"), value("REPLAYGAIN_ALBUM_PEAK"))
        return ReplayGainTagResult(tags, when {
            sources.size > 1 -> ReplayGainTagSource.MIXED
            sources.size == 1 -> sources.single()
            else -> ReplayGainTagSource.NONE
        }, issue)
    }

    private fun parse(raw: String, peak: Boolean): Double? {
        if (raw.length > 96) return null
        val trimmed = raw.trim()
        val text = if (!peak && trimmed.endsWith("dB", ignoreCase = true)) trimmed.dropLast(2).trimEnd() else trimmed
        if (!number.matches(text)) return null
        val value = text.toDoubleOrNull() ?: return null
        return value.takeIf { it.isFinite() && if (peak) it > 0.0 && it <= 64.0 else it in -60.0..60.0 }
    }
}
