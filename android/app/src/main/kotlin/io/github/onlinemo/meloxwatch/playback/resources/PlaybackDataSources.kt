package io.github.onlinemo.meloxwatch.playback.resources

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.ContentDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackIssue

/** No backend gets a raw remote URL; both use the same app-controlled acquisition policy. */
@UnstableApi
internal class PlaybackDataSources(
    context: Context,
    resources: RuntimePlaybackResources,
    controlledStreams: HttpsMediaStreams? = null,
) : DataSource.Factory, AutoCloseable {
    private val application = context.applicationContext
    // Tests supply the same bounded transport with deterministic responses; service construction uses the production client.
    private val streams = controlledStreams ?: HttpsMediaStreams(application, resources)
    override fun createDataSource(): DataSource = RoutedDataSource(application, streams)
    fun platform(uri: Uri, onFailure: (QueuePlaybackIssue) -> Unit): RuntimeMediaDataSource = RuntimeMediaDataSource(uri, streams, onFailure)
    override fun close() = streams.close()
}

/** The coordinator owns the refresh allowance; Loader cannot independently reopen failed media. */
@UnstableApi
internal class NoAutomaticPlaybackLoadRetry : DefaultLoadErrorHandlingPolicy(0) {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long = C.TIME_UNSET
}

@UnstableApi
private class RoutedDataSource(private val context: Context, private val streams: HttpsMediaStreams) : DataSource {
    private val listeners = mutableListOf<TransferListener>()
    private var active: DataSource? = null
    override fun addTransferListener(transferListener: TransferListener) {
        if (transferListener !in listeners) listeners.add(transferListener)
        active?.addTransferListener(transferListener)
    }
    override fun open(dataSpec: DataSpec): Long {
        check(active == null)
        val source = when (dataSpec.uri.scheme) {
            "content" -> ContentDataSource(context)
            RuntimePlaybackResources.SCHEME -> RuntimeHttpDataSource(streams)
            else -> throw MediaTransportException(QueuePlaybackIssue.RESOURCE_INVALID)
        }
        active = source
        listeners.forEach(source::addTransferListener)
        return try { source.open(dataSpec) } catch (failure: Exception) {
            close()
            throw failure
        }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = checkNotNull(active).read(buffer, offset, length)
    override fun getUri(): Uri? = active?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders.orEmpty()
    override fun close() {
        val previous = active
        active = null
        previous?.close()
    }
}

@UnstableApi
private class RuntimeHttpDataSource(private val streams: HttpsMediaStreams) : BaseDataSource(true) {
    private var stream: MediaStream? = null
    private var uri: Uri? = null
    private var started = false
    override fun open(dataSpec: DataSpec): Long {
        check(stream == null)
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val opened = streams.open(dataSpec.uri, dataSpec.position, dataSpec.length)
        stream = opened
        transferStarted(dataSpec)
        started = true
        return opened.availableLength
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = checkNotNull(stream).read(buffer, offset, length)
        if (read > 0) bytesTransferred(read)
        return read
    }
    override fun getUri(): Uri? = uri
    override fun getResponseHeaders(): Map<String, List<String>> = stream?.responseHeaders.orEmpty()
    override fun close() {
        stream?.requestClose()
        stream = null
        uri = null
        if (started) {
            started = false
            transferEnded()
        }
    }
}

/** Platform callbacks read bounded chunks on their media worker. getSize/close never start network work. */
internal class RuntimeMediaDataSource(
    private val uri: Uri,
    private val streams: HttpsMediaStreams,
    private val onFailure: (QueuePlaybackIssue) -> Unit,
) : MediaDataSource() {
    private val reads = Any()
    @Volatile private var closed = false
    @Volatile private var stream: MediaStream? = null
    @Volatile private var total = -1L
    @Volatile var supportsSeek = false
        private set
    private var nextPosition = 0L

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int = synchronized(reads) {
        try {
            if (closed) throw MediaTransportException(QueuePlaybackIssue.SOURCE_CHANGED)
            if (position < 0L || offset < 0 || size < 0 || offset > buffer.size - size) throw MediaTransportException(QueuePlaybackIssue.RESOURCE_INVALID)
            if (size == 0) return@synchronized 0
            if (total >= 0L && position >= total) return@synchronized -1
            var current = stream
            if (current == null || position != nextPosition) {
                current?.requestClose()
                current = streams.open(uri, position)
                stream = current
                if (closed) {
                    current.requestClose()
                    throw MediaTransportException(QueuePlaybackIssue.SOURCE_CHANGED)
                }
                total = current.totalSize
                supportsSeek = current.seekable
                nextPosition = position
            }
            val count = current.read(buffer, offset, size)
            if (count > 0) nextPosition += count
            count
        } catch (failure: MediaTransportException) {
            close()
            onFailure(failure.issue)
            throw failure
        }
    }
    override fun getSize(): Long = total
    override fun close() {
        closed = true
        stream?.requestClose()
        stream = null
    }
}
