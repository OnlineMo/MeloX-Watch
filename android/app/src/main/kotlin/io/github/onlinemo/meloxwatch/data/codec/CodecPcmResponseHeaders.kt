package io.github.onlinemo.meloxwatch.data.codec

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import java.util.Collections
import java.util.TreeMap

/** The typed observation stays in this process; ReplayGain literals are never HTTP header values. */
@UnstableApi
internal class CodecPcmResponseHeaders(
    private val sourceUri: Uri,
    observed: LocalCodecFormat,
    metadata: Metadata?,
    private val isCurrent: () -> Boolean,
) : Map<String, List<String>> by pcmObservationHeaders(observed) {
    init { require(sourceUri.scheme == "content") }
    private val observation = CodecPcmObservation(observed, metadata)

    fun forSource(uri: Uri): CodecPcmObservation? =
        observation.takeIf { uri.scheme == "content" && uri == sourceUri && isCurrent() }
}

/** Immutable tags belong to the admitted progressive stream, including its already queued tail. */
@UnstableApi
internal class CodecPcmObservation(private val observed: LocalCodecFormat, private val metadata: Metadata?) {
    fun metadataFor(format: Format): Metadata? =
        metadata.takeIf {
            format.sampleMimeType == MimeTypes.AUDIO_RAW && format.pcmEncoding == C.ENCODING_PCM_16BIT &&
                format.sampleRate == observed.pcm.sampleRate && format.channelCount == observed.pcm.channels
        }
}

private fun pcmObservationHeaders(format: LocalCodecFormat): Map<String, List<String>> {
    val headers = TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER)
    headers.putAll(mapOf("Content-Type" to listOf("audio/wav"),
        "X-MeloX-Input-Container" to listOf(format.container),
        "X-MeloX-Input-Encoding" to listOf(format.encoding),
        "X-MeloX-Input-Rate" to listOf(format.inputSampleRate.toString()),
        "X-MeloX-Input-Bits" to listOf(format.inputBitsPerSample.toString()),
        "X-MeloX-Channels" to listOf(format.channels.toString()),
        "X-MeloX-PCM-Rate" to listOf(format.pcm.sampleRate.toString()),
        "X-MeloX-PCM-Bits" to listOf(format.pcm.bitsPerSample.toString())))
    return Collections.unmodifiableMap(headers)
}
