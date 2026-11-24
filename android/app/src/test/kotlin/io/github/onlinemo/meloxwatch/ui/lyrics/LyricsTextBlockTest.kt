package io.github.onlinemo.meloxwatch.ui.lyrics

import io.github.onlinemo.meloxwatch.data.lyrics.LyricWordTiming
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsLineId
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsTextLine
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsTimedLine
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsWordPosition
import io.github.onlinemo.meloxwatch.data.lyrics.parseLyrics
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsTextBlockTest {
    @Test
    fun genuineSupplementaryFragmentsKeepTheirCompleteUtf16Range() {
        runBlocking {
            val document = parseLyrics("[00:01]<00:01>\uD83D\uDE03<00:02>whole<00:03>")
            val line = document.timeline.line(0)
            val position = checkNotNull(document.timeline.position(1_500L))
            val range = checkNotNull(lyricsWordRange(line, document.timeline.word(0, position)))
            assertEquals(0, range.start)
            assertEquals(2, range.endExclusive)
            assertEquals("\uD83D\uDE03", line.text.substring(range.start, range.endExclusive))
        }
    }

    @Test
    fun lineOnlyTimingAndStaleOrInventedWordPositionsCannotEmphasizeLetters() {
        runBlocking {
            val lineOnly = parseLyrics("[00:01]Whole line").timeline.line(0)
            val invented = LyricsWordPosition(lineOnly.id, 0, LyricWordTiming(1_000L, null, 0, 5), null)
            assertNull(lyricsWordRange(lineOnly, invented))
            assertNull(lyricsWordRange(lineOnly, null))
            val document = parseLyrics("[00:01]<00:01>one<00:02>two<00:03>")
            val line = document.timeline.line(0)
            val word = checkNotNull(document.timeline.word(0, checkNotNull(document.timeline.position(1_500L))))
            assertNull(lyricsWordRange(line, word.copy(lineId = LyricsLineId(2, 0))))
            assertNull(lyricsWordRange(line, word.copy(wordIndex = 1)))
            assertNull(lyricsWordRange(line, word.copy(timing = word.timing.copy(startsAtMs = 1_001L))))
        }
    }

    @Test
    fun malformedMemberRangesCannotSplitSurrogatePairsOrIndexOutsideTheText() {
        val text = "\uD83D\uDE03a"
        // 核心解析器不会生成这些范围；渲染边界仍拒绝结构上可构造的损坏模型。
        for ((start, end) in listOf(1 to 2, 0 to 1, 0 to 4)) {
            val timing = LyricWordTiming(1_000L, 2_000L, start, end)
            val line = LyricsTimedLine(LyricsLineId(1, 0), 1_000L, LyricsTextLine(1, text, listOf(timing)))
            assertNull(lyricsWordRange(line, LyricsWordPosition(line.id, 0, timing, null)))
        }
    }
}
