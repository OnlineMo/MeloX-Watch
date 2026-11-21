package io.github.onlinemo.meloxwatch.ui.lyrics

import io.github.onlinemo.meloxwatch.data.lyrics.LyricsClockMapping
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsUserOffset
import io.github.onlinemo.meloxwatch.data.lyrics.parseLyrics
import io.github.onlinemo.meloxwatch.model.ScreenShape
import io.github.onlinemo.meloxwatch.model.TrackKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsPresentationTest {
    @Test
    fun beforeTheFirstTimestampTheFirstLineIsAnAnchorWithoutAFalseCurrentHighlight() {
        runBlocking {
            val document = parseLyrics("[00:01]One\n[00:01]Also one\n[00:02]Next")
            val before = lyricsDisplayFrame(document, 999L, LyricsClockMapping(), visible = true, bound = true)
            assertNull(before.position)
            assertEquals(0, before.anchorIndex)
            val current = lyricsDisplayFrame(document, 1_000L, LyricsClockMapping(), visible = true, bound = true)
            val position = checkNotNull(current.position)
            assertEquals(0, current.anchorIndex)
            assertTrue(position.isActive(0) && position.isActive(1))
            assertFalse(position.isActive(2))
        }
    }

    @Test
    fun hiddenAndUnboundPagesDiscardTheDisplayCursorAndResumeAtTheCurrentMediaSnapshot() {
        runBlocking {
            val document = parseLyrics("[00:01]One\n[00:02]Two\n[00:03]Three")
            val mapping = LyricsClockMapping()
            val hidden = lyricsDisplayFrame(document, 20_000L, mapping, visible = false, bound = true)
            val unbound = lyricsDisplayFrame(document, 20_000L, mapping, visible = true, bound = false)
            assertNull(hidden.position)
            assertNull(hidden.anchorIndex)
            assertNull(unbound.position)
            assertNull(unbound.anchorIndex)
            val resumed = lyricsDisplayFrame(document, 2_100L, mapping, visible = true, bound = true)
            assertEquals(1, resumed.anchorIndex)
            assertEquals(2_100L, checkNotNull(resumed.position).lyricTimeMs)
        }
    }

    @Test
    fun plainEmptyNegativeAndOverflowedTimeInputsDoNotCreateACurrentLine() {
        runBlocking {
            for (document in listOf(parseLyrics("Readable plain text"), parseLyrics(""))) {
                val frame = lyricsDisplayFrame(document, 1_000L, LyricsClockMapping(), visible = true, bound = true)
                assertNull(frame.position)
                assertNull(frame.anchorIndex)
            }
            val timed = parseLyrics("[00:01]Timed")
            val negative = lyricsDisplayFrame(timed, -1L, LyricsClockMapping(), visible = true, bound = true)
            val overflow = lyricsDisplayFrame(timed, 1L, LyricsClockMapping(Long.MAX_VALUE), visible = true, bound = true)
            assertNull(negative.position)
            assertNull(negative.anchorIndex)
            assertNull(overflow.position)
            assertNull(overflow.anchorIndex)
        }
    }

    @Test
    fun previewAndDisplayUseTheSameInverseForSourceOffsetUserDelayAndWholeFileCueOrigin() {
        runBlocking {
            val document = parseLyrics("[offset:1000]\n[01:31.125]Whole file\n[01:35.500]Next")
            val mapping = LyricsClockMapping(90_000L, LyricsUserOffset(500))
            val preview = checkNotNull(lyricsPlayPreview(identity(), document, 0, 20_000L, mapping, canSeek = true))
            assertEquals(625L, preview.target.mediaPositionMs)
            assertEquals(identity(), preview.identity)
            assertEquals(document.documentId, preview.target.documentId)
            assertEquals(document.timeline.line(0).id, preview.target.lineId)
            assertEquals(mapping, preview.clockMapping)
            val frame = lyricsDisplayFrame(document, preview.target.mediaPositionMs, mapping, visible = true, bound = true)
            assertEquals(document.timeline.line(0).startsAtMs, checkNotNull(frame.position).lyricTimeMs)
        }
    }

    @Test
    fun previewRequiresACurrentIdentityRealTimingSeekCapabilityAndAnInRangeKnownDuration() {
        runBlocking {
            val document = parseLyrics("[00:00]Zero\n[00:01]One\n[00:02]End")
            val mapping = LyricsClockMapping()
            assertEquals(0L, checkNotNull(lyricsPlayPreview(identity(), document, 0, 2_000L, mapping, true)).target.mediaPositionMs)
            assertNull(lyricsPlayPreview(null, document, 0, 2_000L, mapping, true))
            assertNull(lyricsPlayPreview(identity(), document, 0, 2_000L, mapping, false))
            assertNull(lyricsPlayPreview(identity(), document, 0, null, mapping, true))
            assertNull(lyricsPlayPreview(identity(), document, 0, 0L, mapping, true))
            assertNull(lyricsPlayPreview(identity(), document, 1, 999L, mapping, true))
            assertNull(lyricsPlayPreview(identity(), document, 2, 2_000L, mapping, true))
            assertNull(lyricsPlayPreview(identity(), document, -1, 2_000L, mapping, true))
            assertNull(lyricsPlayPreview(identity(), document, 3, 2_000L, mapping, true))
            val plain = parseLyrics("Plain text")
            assertNull(lyricsPlayPreview(identity(), plain, 0, 2_000L, mapping, true))
            val negative = parseLyrics("[offset:2000]\n[00:01]Early")
            assertNull(lyricsPlayPreview(identity(), negative, 0, 2_000L, mapping, true))
        }
    }

    @Test
    fun explicitRectangularAndRoundChoicesOverrideOnlyTheAutomaticSystemShape() {
        assertTrue(lyricsIsRound(ScreenShape.AUTO, systemRound = true))
        assertFalse(lyricsIsRound(ScreenShape.AUTO, systemRound = false))
        assertFalse(lyricsIsRound(ScreenShape.RECTANGULAR, systemRound = true))
        assertTrue(lyricsIsRound(ScreenShape.ROUND, systemRound = false))
    }

    private fun identity(): LyricsPageIdentity = LyricsPageIdentity(TrackKey("local", "fixture-track"), "queue", "entry", 1L)
}
