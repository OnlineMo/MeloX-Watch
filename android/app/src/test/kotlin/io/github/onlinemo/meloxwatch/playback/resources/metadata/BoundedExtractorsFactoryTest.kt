package io.github.onlinemo.meloxwatch.playback.resources.metadata

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.flac.FlacExtractor
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.ogg.OggExtractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.AdtsExtractor
import androidx.media3.extractor.wav.WavExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class BoundedExtractorsFactoryTest {
    @Test fun defaultMimeAndUriOrderingKeepsEveryReviewedBundledAudioExtractor() {
        val uri = Uri.parse("content://metadata-fixtures/source.m4a")
        val headers = mapOf("Content-Type" to listOf("audio/mp4"))
        val original = DefaultExtractorsFactory().createExtractors(uri, headers)
        val actual = BoundedExtractorsFactory().createExtractors(uri, headers)
        try {
            val expected = original.filter(::reviewed).map { it.underlyingImplementation.javaClass }
            assertEquals(expected, actual.map { it.underlyingImplementation.javaClass })
            assertTrue(actual.any { it.underlyingImplementation is AdtsExtractor })
            assertTrue(actual.any { it.underlyingImplementation is FlacExtractor })
        } finally {
            original.forEach(Extractor::release)
            actual.forEach(Extractor::release)
        }
    }

    // DEPRECATION：固定 1.8.0 兼容入口的转发回归；只覆盖该方法中的已弃用调用。
    @Suppress("DEPRECATION")
    @Test fun configurationReturnValuesAndExactUriHeadersAreForwardedToTheCurrentDelegate() {
        val second = RecordingFactory(WavExtractor())
        val first = RecordingFactory(Mp3Extractor(), subtitleReplacement = second)
        val wrapper = BoundedExtractorsFactory(first)
        val subtitle = DefaultSubtitleParserFactory()
        assertSame(wrapper, wrapper.setSubtitleParserFactory(subtitle))
        assertSame(subtitle, first.subtitle)
        val configured: ExtractorsFactory = wrapper
        assertSame(wrapper, configured.experimentalSetTextTrackTranscodingEnabled(false))
        assertSame(wrapper, wrapper.experimentalSetCodecsToParseWithinGopSampleDependencies(0))
        assertEquals(false, second.transcoding)
        assertEquals(0, second.codecs)
        val uri = Uri.parse("content://metadata-fixtures/source.wav")
        val headers = mapOf("Content-Type" to listOf("audio/wav"))
        val extractors = wrapper.createExtractors(uri, headers)
        try {
            assertSame(uri, second.uri)
            assertSame(headers, second.headers)
            assertTrue(extractors.single().underlyingImplementation is WavExtractor)
        } finally { extractors.forEach(Extractor::release) }
    }

    @Test fun underlyingMp3IdentityPreservesTheAdaptersDisableSeekingControl() {
        val native = Mp3Extractor()
        val wrapper = BoundedExtractorsFactory(ExtractorsFactory { arrayOf<Extractor>(native) }).createExtractors().single()
        assertSame(native, wrapper.underlyingImplementation)
        (wrapper.underlyingImplementation as Mp3Extractor).disableSeeking()
        ExtractorFixtureHarness(GuardedContainer.MP3, AudioMetadataFixtures.mp3(), extractor = wrapper).use { harness ->
            assertTrue(harness.sniff())
            assertEquals(8, harness.drain().audio().sampleSizes.size)
            assertFalse(harness.output.seekMaps.last().isSeekable)
        }
    }

    @Test fun discardedUnreviewedExtractorIsReleasedByItsCreatingFactory() {
        var released = 0
        val discarded = object : Extractor {
            override fun sniff(input: ExtractorInput): Boolean = false
            override fun init(output: ExtractorOutput) = Unit
            override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = Extractor.RESULT_END_OF_INPUT
            override fun seek(position: Long, timeUs: Long) = Unit
            override fun release() { released++ }
        }
        val factory = BoundedExtractorsFactory(ExtractorsFactory { arrayOf<Extractor>(discarded) })
        assertTrue(factory.createExtractors().isEmpty())
        assertEquals(1, released)
    }

    private fun reviewed(extractor: Extractor): Boolean = when (extractor.underlyingImplementation) {
        is Mp3Extractor, is FlacExtractor, is OggExtractor, is Mp4Extractor, is FragmentedMp4Extractor, is WavExtractor, is AdtsExtractor -> true
        else -> false
    }

    private class RecordingFactory(private val extractor: Extractor, private val subtitleReplacement: ExtractorsFactory? = null) : ExtractorsFactory {
        var uri: Uri? = null
        var headers: Map<String, List<String>>? = null
        var subtitle: SubtitleParser.Factory? = null
        var transcoding: Boolean? = null
        var codecs: Int? = null
        override fun createExtractors(): Array<Extractor> = arrayOf(extractor)
        override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> {
            this.uri = uri
            headers = responseHeaders
            return createExtractors()
        }
        override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory {
            subtitle = subtitleParserFactory
            return subtitleReplacement ?: this
        }
        // OVERRIDE_DEPRECATION：该 fixture 必须实现固定已弃用接口来观察真实转发；升级接口后移除。
        @Suppress("OVERRIDE_DEPRECATION")
        override fun experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled: Boolean): ExtractorsFactory {
            transcoding = textTrackTranscodingEnabled
            return this
        }
        override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies: Int): ExtractorsFactory {
            codecs = codecsToParseWithinGopSampleDependencies
            return this
        }
    }
}
