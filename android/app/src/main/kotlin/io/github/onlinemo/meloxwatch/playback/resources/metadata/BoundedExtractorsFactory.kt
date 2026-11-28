package io.github.onlinemo.meloxwatch.playback.resources.metadata

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ForwardingTrackOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SniffFailure
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.flac.FlacExtractor
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.ogg.OggExtractor
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.AdtsExtractor
import androidx.media3.extractor.wav.WavExtractor
import io.github.onlinemo.meloxwatch.data.codec.CodecPcmObservation
import io.github.onlinemo.meloxwatch.data.codec.CodecPcmResponseHeaders
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackIssue
import io.github.onlinemo.meloxwatch.playback.resources.MediaTransportException

/** 保持 delegate 的 MIME/URI 顺序与配置，给已经审读的 bundled 音频 extractor 加准入边界。 */
@UnstableApi
internal class BoundedExtractorsFactory(private var delegate: ExtractorsFactory = DefaultExtractorsFactory()) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = wrap(delegate.createExtractors())
    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        wrap(delegate.createExtractors(uri, responseHeaders),
            (responseHeaders as? CodecPcmResponseHeaders)?.forSource(uri))
    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory {
        delegate = delegate.setSubtitleParserFactory(subtitleParserFactory)
        return this
    }
    // DEPRECATION / OVERRIDE_DEPRECATION：兼容固定 1.8.0 已弃用入口；仅限此 override 与内部调用。
    // 跟踪 LP-05-media3-metadata-admission 的 factory 转发回归；接口升级时移除，云编译仍未执行。
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled: Boolean): ExtractorsFactory {
        delegate = delegate.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled)
        return this
    }
    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies: Int): ExtractorsFactory {
        delegate = delegate.experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies)
        return this
    }
    private fun wrap(extractors: Array<Extractor>, observation: CodecPcmObservation? = null): Array<Extractor> = extractors.mapNotNull { extractor ->
        val kind = when (extractor.underlyingImplementation) {
            is Mp3Extractor -> GuardedContainer.MP3
            is FlacExtractor -> GuardedContainer.FLAC
            is OggExtractor -> GuardedContainer.OGG
            is Mp4Extractor -> GuardedContainer.MP4
            is FragmentedMp4Extractor -> GuardedContainer.FRAGMENTED_MP4
            is WavExtractor -> GuardedContainer.WAVE
            is AdtsExtractor -> GuardedContainer.ADTS
            // 其他容器有不同的分配语法；其完整准入契约审读后再加入支持路径。
            else -> null
        }
        val guarded: Extractor? = if (kind != null) {
            BoundedExtractor(extractor, kind, observation.takeIf { kind == GuardedContainer.WAVE })
        } else {
            // factory 创建但不会交给 adapter 的实例，由这里释放。
            extractor.release()
            null
        }
        guarded
    }.toTypedArray()
}

internal enum class GuardedContainer { MP3, FLAC, OGG, MP4, FRAGMENTED_MP4, WAVE, ADTS }

@UnstableApi
internal class BoundedExtractor(
    private val delegate: Extractor,
    private val kind: GuardedContainer,
    private var codecObservation: CodecPcmObservation? = null,
) : Extractor {
    private var budget = MetadataBudget()
    private var id3 = Id3Admission(budget)
    private var admission: ContainerAdmission = container()
    private var sniffed = false
    private var oggSeekMapDelivered = false
    private var oggHeadersRetained = false
    private fun container(): ContainerAdmission = when (kind) {
        GuardedContainer.MP3 -> object : ContainerAdmission {}
        GuardedContainer.FLAC -> FlacAdmission(budget)
        GuardedContainer.OGG -> OggAdmission(budget)
        GuardedContainer.MP4 -> Mp4Admission(budget, fragmented = false)
        GuardedContainer.FRAGMENTED_MP4 -> Mp4Admission(budget, fragmented = true)
        GuardedContainer.WAVE -> WaveAdmission()
        GuardedContainer.ADTS -> AdtsAdmission()
    }
    override fun sniff(input: ExtractorInput): Boolean = translate {
        // sniff 可先分配 ID3/ftyp；其临时状态不能与 extraction 的固定 scratch identity 混用。
        val sniffContainer = when (kind) {
            GuardedContainer.MP4, GuardedContainer.FRAGMENTED_MP4 -> Mp4SniffAdmission(input.peekPosition)
            else -> object : ContainerAdmission {}
        }
        val result = delegate.sniff(BoundedExtractorInput(input, Id3Admission(MetadataBudget()), sniffContainer))
        if (result) sniffed = true
        result
    }
    override fun init(output: ExtractorOutput) = delegate.init(object : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput {
            val track = output.track(id, type)
            if (kind != GuardedContainer.WAVE || type != C.TRACK_TYPE_AUDIO || codecObservation == null) return track
            return object : ForwardingTrackOutput(track) {
                override fun format(format: Format) {
                    val originalTags = codecObservation?.metadataFor(format)
                    if (originalTags == null) {
                        super.format(format)
                        return
                    }
                    admit(originalTags.length() <= MetadataLimits.ENTRIES, MetadataAdmissionIssue.RESOURCE_LIMIT)
                    admit((format.metadata?.length() ?: 0) <= MetadataLimits.ENTRIES - originalTags.length(),
                        MetadataAdmissionIssue.RESOURCE_LIMIT)
                    val merged = format.metadata?.copyWithAppendedEntriesFrom(originalTags) ?: originalTags
                    super.format(format.buildUpon().setMetadata(merged).build())
                }
            }
        }
        override fun endTracks() = output.endTracks()
        override fun seekMap(seekMap: SeekMap) {
            output.seekMap(seekMap)
            oggSeekMapDelivered = true
        }
    })
    override fun getSniffFailureDetails(): List<SniffFailure> = delegate.sniffFailureDetails
    override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = translate {
        // 单 extractor adapter 可省略 sniff；先完成 Ogg 的临时识别，避免把其 scratch 当成 OggPacket。
        if (kind == GuardedContainer.OGG && !sniffed) {
            admit(input.position == 0L, MetadataAdmissionIssue.UNSUPPORTED_SURFACE)
            admit(sniff(input), MetadataAdmissionIssue.UNSUPPORTED_SURFACE)
            input.resetPeekPosition()
        }
        try {
            delegate.read(BoundedExtractorInput(input, id3, admission), seekPosition)
        } finally {
            // 固定 StreamReader 在 seekMap 回调返回后设置 seekMapSet；等本次 read 结束再承接该阶段。
            if (kind == GuardedContainer.OGG && oggSeekMapDelivered) oggHeadersRetained = true
        }
    }
    override fun seek(position: Long, timeUs: Long) {
        admission.seek(position)
        if (position == 0L && (kind != GuardedContainer.OGG || !oggHeadersRetained)) {
            budget = MetadataBudget()
            id3 = Id3Admission(budget)
            admission = container()
        }
        delegate.seek(position, timeUs)
    }
    override fun release() {
        try {
            delegate.release()
        } finally {
            codecObservation = null
            admission.release()
        }
    }
    private fun <T> translate(action: () -> T): T = try { action() }
    catch (error: MetadataAdmissionException) {
        val issue = if (error.issue == MetadataAdmissionIssue.MALFORMED) QueuePlaybackIssue.SOURCE_DAMAGED else QueuePlaybackIssue.SOURCE_UNSUPPORTED
        throw MediaTransportException(issue).also { it.initCause(error) }
    }
}
