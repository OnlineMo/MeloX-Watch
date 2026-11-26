package io.github.onlinemo.meloxwatch.ui.lyrics.entry

import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsSourceOrigin
import io.github.onlinemo.meloxwatch.model.LocalCueReference

/** 自动取得的物理歌词使用音频时间线；显式选择的分段歌词从零开始。 */
internal fun cueLyricsTimelineOrigin(reference: LocalCueReference?, origin: LyricsSourceOrigin): Long =
    if (origin == LyricsSourceOrigin.EXPLICIT_DOCUMENT) 0L else reference?.identity?.timelineOriginMs ?: 0L
