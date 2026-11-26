package io.github.onlinemo.meloxwatch.data.backup

/** 名称和原 CUE 位置用于复核；不暴露 URI、授权或原生访问围栏。 */
class CueRelinkMemberPreview internal constructor(val trackNumber: Int, val originalTitle: String?, val targetTitle: String,
    val originalStartFrame: Long?, val originalEndFrameExclusive: Long?, val originalEndUsExclusive: Long?,
    val targetStartFrame: Long, val targetEndFrameExclusive: Long?, val targetEndUsExclusive: Long) {
    override fun toString(): String = "CueRelinkMemberPreview(track=$trackNumber)"
}
