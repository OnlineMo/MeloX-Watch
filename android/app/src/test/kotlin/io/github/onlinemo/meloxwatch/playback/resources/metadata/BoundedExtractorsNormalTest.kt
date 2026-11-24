package io.github.onlinemo.meloxwatch.playback.resources.metadata

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.metadata.flac.PictureFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class BoundedExtractorsNormalTest {
    @Test fun ordinaryPcmWaveAndOddUnknownChunksKeepActualSampleBytes() {
        val extra = AudioMetadataFixtures.chunk("JUNK", byteArrayOf(1, 2, 3))
        ExtractorFixtureHarness(GuardedContainer.WAVE, AudioMetadataFixtures.wave(extra)).use { harness ->
            assertTrue(harness.sniff())
            val audio = harness.drain().audio()
            val format = audio.formats.last()
            assertEquals(MimeTypes.AUDIO_RAW, format.sampleMimeType)
            assertEquals(44_100, format.sampleRate)
            assertEquals(2, format.channelCount)
            assertEquals(C.ENCODING_PCM_16BIT, format.pcmEncoding)
            assertArrayEquals(AudioMetadataFixtures.pcm, audio.data)
            assertTrue(audio.sampleSizes.isNotEmpty())
        }
    }

    @Test fun realMpegFramesWithId3V2V3V4ExtendedFooterAndUnsyncStayAdmitted() {
        val tags = listOf(
            AudioMetadataFixtures.id3Title(version = 2),
            AudioMetadataFixtures.id3Title(version = 3, extended = true),
            AudioMetadataFixtures.id3Title(version = 3, unsync = true),
            AudioMetadataFixtures.id3Title(version = 4, extended = true, footer = true),
            AudioMetadataFixtures.id3Title(version = 4, unsync = true),
            AudioMetadataFixtures.nestedId3(depth = 2),
        )
        for (tag in tags) {
            ExtractorFixtureHarness(GuardedContainer.MP3, AudioMetadataFixtures.mp3(tag)).use { harness ->
                assertTrue(harness.sniff())
                val audio = harness.drain().audio()
                val format = audio.formats.last()
                assertEquals(MimeTypes.AUDIO_MPEG, format.sampleMimeType)
                assertEquals(44_100, format.sampleRate)
                assertEquals(2, format.channelCount)
                assertNotNull(format.metadata)
                assertTrue(checkNotNull(format.metadata).length() > 0)
                assertEquals(8, audio.sampleSizes.size)
                assertEquals(8 * 417, audio.data.size)
            }
        }
    }

    @Test fun nativeFlacCommentsPictureAndCrcFramesReachTheRealExtractorOutput() {
        val picture = AudioMetadataFixtures.picture()
        val metadata = AudioMetadataFixtures.block(4, AudioMetadataFixtures.comments(), last = false) +
            AudioMetadataFixtures.block(6, picture)
        ExtractorFixtureHarness(GuardedContainer.FLAC, AudioMetadataFixtures.flac(metadata)).use { harness ->
            assertTrue(harness.sniff())
            val audio = harness.drain().audio()
            val format = audio.formats.last()
            assertEquals(MimeTypes.AUDIO_FLAC, format.sampleMimeType)
            assertEquals(44_100, format.sampleRate)
            assertEquals(1, format.channelCount)
            assertTrue(checkNotNull(format.metadata).length() >= 3)
            val cover = (0 until checkNotNull(format.metadata).length())
                .map { checkNotNull(format.metadata)[it] }.filterIsInstance<PictureFrame>().single()
            assertTrue(cover.pictureData.size > 32)
            assertEquals(3, audio.sampleSizes.size)
            assertEquals(listOf(12, 12, 12), audio.sampleSizes)
        }
    }

    @Test fun oggOpusHandlesEmptyPagesInternalSeekerAndReplayFromZero() {
        for (gap in listOf(false, true)) {
            ExtractorFixtureHarness(GuardedContainer.OGG, AudioMetadataFixtures.opusPackets(emptyGap = gap)).use { harness ->
                assertTrue(harness.sniff())
                val audio = harness.drain().audio()
                assertEquals(MimeTypes.AUDIO_OPUS, audio.formats.last().sampleMimeType)
                assertEquals(48_000, audio.formats.last().sampleRate)
                assertEquals(2, audio.formats.last().channelCount)
                assertEquals(listOf(3, 3), audio.sampleSizes)
                assertTrue(harness.output.seekMaps.isNotEmpty())
                harness.seekAndDrain(0, 0)
                assertEquals(listOf(3, 3, 3, 3), audio.sampleSizes)
            }
        }
    }

    @Test fun oggSeekBeforeSeekMapRevalidatesHeadersAndDoesNotReuseTheSkipHeadersPhase() {
        ExtractorFixtureHarness(GuardedContainer.OGG, AudioMetadataFixtures.opusPackets()).use { harness ->
            assertTrue(harness.sniff())
            harness.start()
            assertEquals(Extractor.RESULT_CONTINUE, harness.readStep())
            assertFalse(harness.output.audio().formats.isEmpty())
            assertTrue(harness.output.seekMaps.isEmpty())
            harness.seekAndDrain(0, 0)
            assertEquals(listOf(3, 3), harness.output.audio().sampleSizes)
        }
    }

    @Test fun oggVorbisAndOggFlacRetainTheirNativeHeadersAndAudioSamples() {
        val cases = listOf(
            MimeTypes.AUDIO_VORBIS to AudioMetadataFixtures.vorbisPackets(),
            MimeTypes.AUDIO_FLAC to AudioMetadataFixtures.oggFlac(),
        )
        for ((mime, bytes) in cases) {
            ExtractorFixtureHarness(GuardedContainer.OGG, bytes).use { harness ->
                assertTrue(harness.sniff())
                val audio = harness.drain().audio()
                assertEquals(mime, audio.formats.last().sampleMimeType)
                assertEquals(44_100, audio.formats.last().sampleRate)
                assertEquals(1, audio.formats.last().channelCount)
                assertEquals(2, audio.sampleSizes.size)
            }
        }
    }

    @Test fun audioOnlyM4aMetadataAndAacSamplesRemainAvailable() {
        for (metadata in listOf(M4aMetadataFixtures.metadata(), M4aMetadataFixtures.freeformMetadata())) {
            ExtractorFixtureHarness(GuardedContainer.MP4, M4aMetadataFixtures.movie(metadata = metadata)).use { harness ->
                assertTrue(harness.sniff())
                val audio = harness.drain().audio()
                assertEquals(MimeTypes.AUDIO_AAC, audio.formats.last().sampleMimeType)
                assertEquals(44_100, audio.formats.last().sampleRate)
                assertEquals(2, audio.formats.last().channelCount)
                assertNotNull(audio.formats.last().metadata)
                assertArrayEquals(AudioMetadataFixtures.aacSample + AudioMetadataFixtures.aacSample, audio.data)
                assertEquals(listOf(6, 6), audio.sampleSizes)
            }
        }
    }

    @Test fun manyOrdinaryFragmentsUseCurrentGraphAndRetainedPeaksWithoutAStreamHistoryCap() {
        ExtractorFixtureHarness(GuardedContainer.FRAGMENTED_MP4, M4aMetadataFixtures.fragmented(frames = 1700)).use { harness ->
            assertTrue(harness.sniff())
            val audio = harness.drain().audio()
            assertEquals(MimeTypes.AUDIO_AAC, audio.formats.last().sampleMimeType)
            assertEquals(1700, audio.sampleSizes.size)
            assertTrue(audio.sampleSizes.all { it == AudioMetadataFixtures.aacSample.size })
        }
    }

    @Test fun adtsWithBothHeaderShapesAndInbandId3SurvivesSingleByteTransportChunks() {
        for (crc in listOf(false, true)) {
            val tag = AudioMetadataFixtures.id3Title(version = 3, extended = true)
            ExtractorFixtureHarness(
                GuardedContainer.ADTS,
                AudioMetadataFixtures.adts(crcHeader = crc, inband = tag),
                maximumRead = 1,
            ).use { harness ->
                assertTrue(harness.sniff())
                val output = harness.drain()
                val audio = output.audio()
                assertEquals(MimeTypes.AUDIO_AAC, audio.formats.last().sampleMimeType)
                assertEquals(44_100, audio.formats.last().sampleRate)
                assertEquals(2, audio.formats.last().channelCount)
                assertEquals(40, audio.sampleSizes.size)
                val metadata = output.tracks.values.single { it.type == C.TRACK_TYPE_METADATA }
                assertEquals(listOf(tag.size), metadata.sampleSizes)
                assertArrayEquals(tag, metadata.data)
            }
        }
    }
}
