package io.github.onlinemo.meloxwatch.ui.lyrics

import io.github.onlinemo.meloxwatch.data.lyrics.LyricsClockMapping
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsDocument
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsKind
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsPosition
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsSeekTarget
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsTranslation
import io.github.onlinemo.meloxwatch.model.ScreenShape
import io.github.onlinemo.meloxwatch.model.TrackKey

/**
 * 展示与预览绑定当前队列实例和歌词来源代次，不以标题或文件路径代替曲目身份。
 * 来源授权、关联或内容改变时，调用层递增 [sourceGeneration]；此值不授予读文件或播放权限。
 */
data class LyricsPageIdentity(
    val trackKey: TrackKey,
    val queueGenerationId: String,
    val queueEntryId: String,
    val sourceGeneration: Long,
) {
    init {
        require(queueGenerationId.isNotBlank() && queueEntryId.isNotBlank())
        require(sourceGeneration >= 0L)
    }
}

enum class LyricsPageFailure { PERMISSION_REQUIRED, INVALID_ENCODING, INPUT_LIMIT, UNSUPPORTED, READ_FAILED }

/** 加载、确实无文件、已读空文本及读取失败保持不同状态；Ready 不代表功能已验收。 */
sealed interface LyricsPageContent {
    data object Loading : LyricsPageContent
    data object Missing : LyricsPageContent
    data class Failed(val reason: LyricsPageFailure) : LyricsPageContent
    data class Ready(
        val document: LyricsDocument,
        val translation: LyricsTranslation? = null,
        /** 已脱敏、适合展示的来源名称，不接收原始 URI 或凭据。 */
        val sourceLabel: String? = null,
    ) : LyricsPageContent
}

enum class LyricsAlignment { START, CENTER }

/**
 * 只控制显示，不持久化设置或更改时间轴；预览、保存、取消由调用层拥有。
 * 数值来自 17/15 的初始候选。边界校正保留系统字体缩放，不修改系统 density。
 */
data class LyricsDisplayOptions(
    val textSizeSp: Float = 20f,
    val blockSpacingDp: Float = 12f,
    val alignment: LyricsAlignment = LyricsAlignment.CENTER,
    val anchorFraction: Float = 0.5f,
    val horizontalMarginDp: Float = 0f,
    val showTranslation: Boolean = true,
    val emphasizeWords: Boolean = false,
) {
    internal fun bounded(): LyricsDisplayOptions = copy(
        textSizeSp = boundedValue(textSizeSp, 14f, 32f, 20f),
        blockSpacingDp = boundedValue(blockSpacingDp, 4f, 24f, 12f),
        anchorFraction = boundedValue(anchorFraction, 0.25f, 0.75f, 0.5f),
        horizontalMarginDp = boundedValue(horizontalMarginDp, 0f, 24f, 0f),
    )
}

private fun boundedValue(value: Float, minimum: Float, maximum: Float, fallback: Float): Float =
    if (value.isFinite()) value.coerceIn(minimum, maximum) else fallback

/**
 * 只包含“从此处播放”的预览意图；界面从不直接 seek/play。
 * 确认前调用层须复核曲目、来源、偏移和时长，并绑定实际 QueueSeekBinding/输出安全状态。
 */
data class LyricsPlayPreview(
    val identity: LyricsPageIdentity,
    val target: LyricsSeekTarget,
    val clockMapping: LyricsClockMapping,
    val durationMs: Long,
) {
    init { require(durationMs > 0L) }
}

internal data class LyricsDisplayFrame(val position: LyricsPosition?, val anchorIndex: Int?)

/** 隐藏页面不查询行/字位置；再次可见直接消费当前媒体快照，不补播隐藏期间的 tick。 */
internal fun lyricsDisplayFrame(
    document: LyricsDocument,
    mediaPositionMs: Long,
    mapping: LyricsClockMapping,
    visible: Boolean,
    bound: Boolean,
): LyricsDisplayFrame {
    if (!visible || !bound || document.kind != LyricsKind.SYNCHRONIZED) return LyricsDisplayFrame(null, null)
    if (mapping.lyricTime(mediaPositionMs, document.sourceOffsetMs) == null) return LyricsDisplayFrame(null, null)
    val position = document.timeline.position(mediaPositionMs, mapping)
    return LyricsDisplayFrame(position, position?.anchorIndex ?: 0)
}

internal fun lyricsPlayPreview(
    identity: LyricsPageIdentity?,
    document: LyricsDocument,
    lineIndex: Int,
    mediaDurationMs: Long?,
    mapping: LyricsClockMapping,
    canSeek: Boolean,
): LyricsPlayPreview? {
    if (identity == null || !canSeek || document.kind != LyricsKind.SYNCHRONIZED) return null
    val duration = mediaDurationMs ?: return null
    val target = document.timeline.seekTarget(lineIndex, duration, mapping) ?: return null
    return LyricsPlayPreview(identity, target, mapping, duration)
}

internal fun lyricsIsRound(shape: ScreenShape, systemRound: Boolean): Boolean = when (shape) {
    ScreenShape.AUTO -> systemRound
    ScreenShape.ROUND -> true
    ScreenShape.RECTANGULAR -> false
}
