package io.github.onlinemo.meloxwatch.data.codec

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.TransferListener
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicBoolean

/** Service-owned local inputs. A blocked/failed close retains its slot and prevents silent reuse. */
@UnstableApi
internal class LocalPcmDataSources(
    private val rawFactory: DataSource.Factory,
    private val powerSaveMode: () -> Boolean = { false },
) : DataSource.Factory, AutoCloseable {
    private val lock = Any()
    private var closed = false
    private val failedClosers = LinkedHashSet<LocalCodecDataSource>()
    private val active = LinkedHashSet<LocalCodecDataSource>()

    override fun createDataSource(): DataSource = LocalCodecDataSource(this, rawFactory, powerSaveMode)

    internal fun acquire(source: LocalCodecDataSource) = synchronized(lock) {
        codecRequire(!closed && failedClosers.isEmpty(), CodecReadIssue.SOURCE_CHANGED)
        codecRequire(active.size < 3 && source !in active, CodecReadIssue.RESOURCE_LIMIT)
        active.add(source)
    }
    internal fun released(source: LocalCodecDataSource) = synchronized(lock) {
        active.remove(source)
        failedClosers.remove(source)
    }
    internal fun closeFailed(source: LocalCodecDataSource) = synchronized(lock) { failedClosers.add(source) }

    override fun close() {
        val sources = synchronized(lock) { closed = true; active.toList() }
        // No disk/provider work on the service thread. Loader owns each physical close; until it
        // completes, the registry deliberately keeps that source occupied rather than freeing early.
        sources.forEach(LocalCodecDataSource::requestCancellation)
        check(synchronized(lock) { failedClosers.isEmpty() }) { "Local codec input release was not confirmed" }
    }
}

@UnstableApi
internal class LocalCodecDataSource(
    private val owner: LocalPcmDataSources,
    private val rawFactory: DataSource.Factory,
    private val powerSaveMode: () -> Boolean,
) : DataSource {
    private val listeners = ArrayList<TransferListener>()
    private var input: DataSourceCodecInput? = null
    private var pcm: PcmWaveStream? = null
    private var codecHeaders: CodecPcmResponseHeaders? = null
    @Volatile private var ticket: ReadTicket? = null
    private var remaining = C.LENGTH_UNSET.toLong()
    private var originalUri: Uri? = null
    private var registered = false

    override fun addTransferListener(transferListener: TransferListener) {
        if (transferListener !in listeners) listeners += transferListener
        input?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        check(input == null && ticket == null && !registered)
        codecRequire(dataSpec.uri.scheme == "content")
        codecRequire(dataSpec.position >= 0 && (dataSpec.length == C.LENGTH_UNSET.toLong() || dataSpec.length >= 0))
        val current = ReadTicket(powerSaveMode)
        ticket = current
        originalUri = dataSpec.uri
        try {
            owner.acquire(this)
            registered = true
            val raw = DataSourceCodecInput(rawFactory, dataSpec, current, listeners)
            input = raw
            raw.initialize()
            val decoded = LocalPcmCodecs.open(raw) {
                // The expensive kernel is constructed only after actual format validation and the
                // source's power policy. Later checks run during reads, with no hidden polling task.
                current.enableCodecPolicy()
            }
            val total = if (decoded == null) {
                raw.seek(dataSpec.position)
                if (raw.length == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else raw.length - dataSpec.position
            } else {
                val stream = PcmWaveStream(decoded)
                pcm = stream
                codecHeaders = CodecPcmResponseHeaders(dataSpec.uri, decoded.format,
                    CodecReplayGainMetadata.read(raw, decoded.format), current::metadataIsCurrent)
                if (dataSpec.position > stream.length) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
                stream.seek(dataSpec.position)
                stream.length - dataSpec.position
            }
            remaining = when {
                dataSpec.length == C.LENGTH_UNSET.toLong() -> total
                total == C.LENGTH_UNSET.toLong() -> dataSpec.length
                else -> minOf(dataSpec.length, total)
            }
            current.check()
            return if (dataSpec.length == C.LENGTH_UNSET.toLong()) remaining else dataSpec.length
        } catch (failure: OutOfMemoryError) {
            val error = CodecReadException(CodecReadIssue.RESOURCE_LIMIT).also { it.initCause(failure) }
            try { close() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            throw error
        } catch (failure: Exception) {
            try { close() } catch (cleanup: Exception) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        codecRequire(offset >= 0 && length >= 0 && offset <= buffer.size - length)
        val current = checkNotNull(ticket)
        current.check()
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val count = if (remaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), remaining).toInt()
        val stream = pcm
        val read = if (stream == null) checkNotNull(input).read(buffer, offset, count) else stream.read(buffer, offset, count)
        current.check()
        if (read == 0) throw CodecReadException(CodecReadIssue.INVALID_CONTAINER)
        if (read > 0 && remaining != C.LENGTH_UNSET.toLong()) remaining -= read
        if (read < 0 && remaining > 0 && (stream != null || input?.length != C.LENGTH_UNSET.toLong())) {
            throw CodecReadException(CodecReadIssue.INVALID_CONTAINER)
        }
        if (read < 0) remaining = 0L
        return read
    }

    override fun getUri(): Uri? = originalUri

    override fun getResponseHeaders(): Map<String, List<String>> =
        codecHeaders ?: input?.responseHeaders.orEmpty()

    internal fun requestCancellation() { ticket?.cancel() }

    override fun close() {
        ticket?.cancel()
        codecHeaders = null
        var failure: Exception? = null
        try { pcm?.close(); pcm = null }
        catch (error: Exception) { failure = error }
        try { input?.close(); input = null }
        catch (error: Exception) { if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error) }
        if (failure != null) {
            owner.closeFailed(this)
            throw failure
        }
        ticket = null
        originalUri = null
        remaining = C.LENGTH_UNSET.toLong()
        if (registered) { registered = false; owner.released(this) }
    }
}

private class ReadTicket(private val powerSaveMode: () -> Boolean) {
    private val cancelled = AtomicBoolean(false)
    private var codec = false
    private var policyRead: Long? = null
    fun cancel() { cancelled.set(true) }
    fun metadataIsCurrent(): Boolean = !cancelled.get() && !Thread.currentThread().isInterrupted
    fun enableCodecPolicy() { codec = true; policyRead = null; check() }
    fun check() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) throw InterruptedIOException("Local codec read cancelled")
        if (codec) {
            val now = System.nanoTime()
            val previous = policyRead
            if (previous == null || now - previous >= 1_000_000_000L) {
                policyRead = now
                if (powerSaveMode()) throw CodecReadException(CodecReadIssue.LOW_POWER_RESTRICTED)
            }
        }
    }
}

/** One raw source is open at a time. Reopening at a seek verifies the original known byte extent. */
@UnstableApi
private class DataSourceCodecInput(
    private val factory: DataSource.Factory,
    private val spec: DataSpec,
    private val ticket: ReadTicket,
    private val listeners: List<TransferListener>,
) : CodecInput {
    private var active: DataSource? = null
    override var length = C.LENGTH_UNSET.toLong()
        private set
    override var position = 0L
        private set
    val responseHeaders: Map<String, List<String>> get() = active?.responseHeaders.orEmpty()

    fun addTransferListener(listener: TransferListener) { active?.addTransferListener(listener) }
    fun initialize() { length = openAt(0) }
    private fun openAt(next: Long): Long {
        ticket.check()
        closeActive()
        val source = factory.createDataSource()
        active = source
        listeners.forEach(source::addTransferListener)
        val available = source.open(spec.buildUpon().setPosition(next).setLength(C.LENGTH_UNSET.toLong()).build())
        ticket.check()
        if (length >= 0) codecRequire(available >= 0 && available == length - next, CodecReadIssue.SOURCE_CHANGED)
        position = next
        return available
    }
    override fun seek(position: Long) {
        if (position < 0 || length >= 0 && position > length) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        if (active == null || position != this.position) openAt(position)
    }
    override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
        ticket.check()
        if (count == 0) return 0
        val read = checkNotNull(active).read(bytes, offset, minOf(count, 16 * 1024))
        ticket.check()
        if (read > 0) position += read
        return read
    }
    override fun checkCancellation() = ticket.check()
    private fun closeActive() {
        val previous = active
        previous?.close()
        active = null
    }
    override fun close() = closeActive()
}
