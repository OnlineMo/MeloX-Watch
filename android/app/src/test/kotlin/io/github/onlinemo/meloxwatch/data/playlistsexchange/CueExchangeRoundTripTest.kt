package io.github.onlinemo.meloxwatch.data.playlistsexchange

import io.github.onlinemo.meloxwatch.data.cue.CueByteFixture
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class CueExchangeRoundTripTest {
    @Test fun jsonAndM3uRetainPreciseCueIdentityWithoutAnExportableRootOrAccessFields() = runBlocking {
        val portable = CueByteFixture().reference(2).portable()
        val member = ExchangeMember(0L, UUID.randomUUID().toString(), trackKey = portable.trackKey,
            mediaKind = ExchangeMediaKind.CUE_TRACK, cue = ExchangeCueHint(null, 2, portable), title = "Second", unresolved = true)
        val source = object : ExchangeExportSource {
            override val header = ExchangePlaylistHeader("Fixture", exportId = UUID.randomUUID().toString())
            override val memberCount = 1L
            override suspend fun validateSnapshot() = Unit
            override suspend fun page(offset: Long, limit: Int): List<ExchangeMember> = listOf(member).drop(offset.toInt()).take(limit)
        }
        for (json in listOf(true, false)) {
            val output = ByteArrayOutputStream()
            if (json) JsonPlaylistExchange().write(output, source) else M3uPlaylistExchange().write(output, source)
            val text = output.toString("UTF-8")
            assertFalse(text.contains("actualDocument"));
            assertFalse(text.contains("adoptionAliasId"))
            assertFalse(text.contains("grantUri"));
            assertFalse(text.contains("content://"));
            assertFalse(text.contains("unresolved.cue"))
            val members = ArrayList<ExchangeMember>()
            val sink = ExchangePageSink { members.addAll(it) }
            if (json) JsonPlaylistExchange().parse(ByteArrayInputStream(output.toByteArray()), sink)
            else M3uPlaylistExchange().parse(ByteArrayInputStream(output.toByteArray()), sink)
            assertEquals(1, members.size)
            assertEquals(portable, members.single().cue?.reference)
            assertEquals(portable.trackKey, members.single().trackKey)
            assertEquals(ExchangeMediaKind.CUE_TRACK, members.single().mediaKind)
        }
    }
    @Test fun duplicateOutOfOrderOrDifferentIdentityCueMarkersCannotBecomeBareAudio() {
        val fixture = CueByteFixture()
        val ref = fixture.reference(2).portable()
        val line = "#MELOX-CUE-REF:${ExchangeCueReferenceCodec.base64(ref)}"
        val inputs = listOf(
            "$line\n$line\ndisc.cue\n",
            "#EXTINF:1,Second\n$line\ndisc.cue\n",
            "#MELOX-TRACK-ID:${fixture.reference(1).trackKey.trackId}\n$line\ndisc.cue\n",
            "#MELOX-CUE:1,disc.cue\n$line\ndisc.cue\n",
        )
        for (input in inputs) assertThrows(PlaylistExchangeException::class.java) {
            runBlocking { M3uPlaylistExchange().parse(ByteArrayInputStream(input.toByteArray()), ExchangePageSink { throw AssertionError("No member should publish") }) }
        }
        assertThrows(PlaylistExchangeException::class.java) {
            validateExchangeMember(ExchangeMember(0, UUID.randomUUID().toString(), trackKey = TrackKey.newLocal(),
                mediaKind = ExchangeMediaKind.CUE_TRACK, cue = ExchangeCueHint(null, 2, ref)))
        }
    }
    @Test fun legacyRelativeCueHintsRemainCueAndNeverInferAHashIntoTheOriginalSourceIdentity() = runBlocking {
        val members = ArrayList<ExchangeMember>()
        M3uPlaylistExchange().parse(ByteArrayInputStream("#MELOX-CUE:2,disc.cue\ndisc.cue\n".toByteArray()), ExchangePageSink { members.addAll(it) })
        assertEquals(ExchangeMediaKind.CUE_TRACK, members.single().mediaKind)
        assertNotNull(members.single().cue?.relativePath)
        assertEquals(null, members.single().cue?.reference)
    }
}
