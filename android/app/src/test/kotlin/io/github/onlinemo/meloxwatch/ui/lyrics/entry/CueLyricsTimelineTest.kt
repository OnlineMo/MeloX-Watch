package io.github.onlinemo.meloxwatch.ui.lyrics.entry

import io.github.onlinemo.meloxwatch.data.cue.CueByteFixture
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsClockMapping
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsTranslationMatcher
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsUserOffset
import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsSourceOrigin
import io.github.onlinemo.meloxwatch.data.lyrics.parseLyrics
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CueLyricsTimelineTest {
    @Test fun wholeAudioLyricsUseFrameOriginWhileExplicitSegmentLyricsKeepTheirRelativeClock() {
        val ref = CueByteFixture().reference(2)
        val origin = cueLyricsTimelineOrigin(ref, LyricsSourceOrigin.SAME_NAME_LRC)
        assertEquals(1013L, origin)
        assertEquals(origin, cueLyricsTimelineOrigin(ref, LyricsSourceOrigin.EMBEDDED_SYNCHRONIZED))
        assertEquals(0L, cueLyricsTimelineOrigin(ref, LyricsSourceOrigin.EXPLICIT_DOCUMENT))
        val physical = LyricsClockMapping(origin, LyricsUserOffset(100))
        assertEquals(1913L, physical.lyricTime(1000L, 0L))
        assertEquals(1000L, physical.mediaTime(1913L, 0L))
        val selected = LyricsClockMapping(0L, LyricsUserOffset(100))
        assertEquals(900L, selected.lyricTime(1000L, 0L))
    }
    @Test fun translationUsesIndependentDocumentOriginsAndNeverMatchesAnAdjacentTimestamp() = runBlocking {
        val whole = parseLyrics("[offset:100]\n[01:02.100]Whole file\n[01:03.100]Next")
        val segment = parseLyrics("[00:02]Segment translation\n[00:03.001]Wrong time")
        val paired = LyricsTranslationMatcher().match(whole, segment, 60_000L, 0L)
        assertEquals("Segment translation", paired.line(0, whole.documentId)?.text)
        assertNull(paired.line(1, whole.documentId))
    }
}
