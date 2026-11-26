package io.github.onlinemo.meloxwatch.playback.lyrics

import androidx.annotation.MainThread
import androidx.media3.common.Player
import io.github.onlinemo.meloxwatch.data.bluetoothlyrics.BluetoothLyricsLimits
import io.github.onlinemo.meloxwatch.data.bluetoothlyrics.BluetoothLyricsIssue
import io.github.onlinemo.meloxwatch.data.bluetoothlyrics.BluetoothLyricsPayload
import io.github.onlinemo.meloxwatch.data.bluetoothlyrics.BluetoothLyricsPlayback
import io.github.onlinemo.meloxwatch.data.bluetoothlyrics.BluetoothLyricsText
import io.github.onlinemo.meloxwatch.data.bluetoothlyrics.BluetoothLyricsSource
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsKind
import io.github.onlinemo.meloxwatch.ui.lyrics.entry.LyricsEntryViewModel
import io.github.onlinemo.meloxwatch.ui.lyrics.entry.LyricsExternalRead
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/**
 * Authenticated output follows real service events and one next-line boundary, never a UI ticker.
 * Formatting copies at most one bounded text group on Main; no asynchronous job retains a document.
 */
@MainThread
class BluetoothLyricsSender(
    private val model: LyricsEntryViewModel,
    private val playback: ServiceLyricsPlaybackPort,
    scope: CoroutineScope,
    private val offer: (BluetoothLyricsPayload) -> Unit,
    private val onFailure: (BluetoothLyricsIssue) -> Unit,
) : BluetoothLyricsSource {
    private val reading = model.bindExternalReading { entryId ->
        val service = playback.readService() ?: return@bindExternalReading false
        service.clock?.queueEntryId == entryId &&
            (service.state == Player.STATE_READY || service.state == Player.STATE_BUFFERING)
    }
    // 观察器和行边界任务同属这次外部阅读；取消不能提前证明它们已经退出。
    private val sourceWork = Job(scope.coroutineContext[Job])
    private val workScope = CoroutineScope(scope.coroutineContext + sourceWork)
    private var open = true
    private var readingRetired = false
    private var timer: Job? = null
    private var epoch = 0L
    private var fingerprint: Fingerprint? = null
    private var previous: BluetoothLyricsPayload? = null
    init {
        workScope.launch {
            merge(model.uiState.map { Unit }, playback.serviceChanges).onStart { emit(Unit) }.collect { safeUpdate() }
        }
    }

    // Tokens have no document back-reference, so a scheduled boundary cannot retain old parse data.
    private data class Fingerprint(val entryId: String?, val reading: Any?, val discontinuity: Long)

    private fun safeUpdate() {
        try { update() }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            onFailure(BluetoothLyricsIssue.SOURCE_UNAVAILABLE)
        }
    }

    private fun update() {
        timer?.cancel()
        timer = null
        if (!open || !reading.isCurrent) return
        val service = playback.readService()
        val clock = service?.clock
        val serviceState = service?.state ?: Player.STATE_IDLE
        val candidate = model.readExternal()?.takeIf { it.queueEntryId == clock?.queueEntryId }
        val nextFingerprint = Fingerprint(clock?.queueEntryId, candidate?.identity, service?.discontinuity ?: -1L)
        if (nextFingerprint != fingerprint) {
            if (epoch == Long.MAX_VALUE) { close(); return }
            fingerprint = nextFingerprint
            epoch++
        }
        if (epoch == 0L) epoch = 1L
        val currentMedia = service?.takeIf { it.clock != null }
        val title = BluetoothLyricsText.clip(currentMedia?.title.orEmpty(), BluetoothLyricsLimits.TITLE_BYTES)
        val artist = BluetoothLyricsText.clip(currentMedia?.artist.orEmpty(), BluetoothLyricsLimits.ARTIST_BYTES)
        var original = BluetoothLyricsText("", false)
        var translation = BluetoothLyricsText("", false)
        var lineTime = -1L
        val state = when {
            clock == null -> BluetoothLyricsPlayback.NO_TRACK
            serviceState == Player.STATE_ENDED -> BluetoothLyricsPlayback.ENDED
            model.uiState.value.loading || serviceState == Player.STATE_BUFFERING -> BluetoothLyricsPlayback.LOADING
            candidate == null -> if (model.uiState.value.failure != null || model.uiState.value.onlineIssue != null ||
                model.uiState.value.associationPreview != null) BluetoothLyricsPlayback.SOURCE_UNAVAILABLE
                else BluetoothLyricsPlayback.NO_LYRICS
            candidate.document.kind == LyricsKind.EMPTY -> BluetoothLyricsPlayback.NO_LYRICS
            candidate.document.kind == LyricsKind.PLAIN_TEXT -> BluetoothLyricsPlayback.PLAIN_TEXT
            clock.isPlaying -> BluetoothLyricsPlayback.PLAYING
            else -> BluetoothLyricsPlayback.PAUSED
        }
        if (candidate != null && clock != null && state !in setOf(BluetoothLyricsPlayback.NO_TRACK,
                BluetoothLyricsPlayback.ENDED, BluetoothLyricsPlayback.LOADING)) {
            if (candidate.document.kind == LyricsKind.PLAIN_TEXT) {
                original = group(candidate.document.sourceLines.size, BluetoothLyricsLimits.ORIGINAL_BYTES) {
                    candidate.document.sourceLines[it].text
                }
            } else if (candidate.document.kind == LyricsKind.SYNCHRONIZED) {
                val timeline = candidate.document.timeline
                val position = timeline.position(clock.positionMs, candidate.mapping)
                if (position != null) {
                    val start = position.firstActiveIndex
                    val count = position.activeEndExclusive - start
                    original = group(count, BluetoothLyricsLimits.ORIGINAL_BYTES) { timeline.line(start + it).text }
                    translation = group(count, BluetoothLyricsLimits.TRANSLATION_BYTES) {
                        candidate.translation?.line(start + it, candidate.document.documentId)?.text.orEmpty()
                    }
                    lineTime = candidate.mapping.mediaTime(timeline.line(start).startsAtMs,
                        candidate.document.sourceOffsetMs)?.takeIf { it >= 0L } ?: -1L
                }
                if (clock.isPlaying) scheduleNext(candidate, clock.positionMs, clock.speed,
                    position?.activeEndExclusive ?: 0, clock.durationMs)
            }
        }
        val output = BluetoothLyricsPayload(epoch, clock?.positionMs ?: 0L, lineTime, state,
            title.value, artist.value, original.value, translation.value,
            title.shortened || artist.shortened || original.shortened || translation.shortened,
            clock?.isPlaying == true)
        if (!sameDisplay(previous, output)) {
            previous = output
            offer(output)
        }
    }

    private fun scheduleNext(current: LyricsExternalRead, position: Long, speed: Float, index: Int, duration: Long?) {
        if (index !in 0 until current.document.timeline.size) return
        val boundary = current.mapping.mediaTime(current.document.timeline.line(index).startsAtMs,
            current.document.sourceOffsetMs) ?: return
        if (boundary <= position || duration != null && boundary >= duration) return
        val wait = ceil((boundary.toDouble() - position.toDouble()) / speed.toDouble()).toLong()
            .coerceIn(1L, BluetoothLyricsLimits.SESSION_MS)
        // Only the scalar delay and owner are captured; the borrowed reading ends with this call.
        timer = workScope.launch { delay(wait); if (open && reading.isCurrent) safeUpdate() }
    }

    private fun group(count: Int, maximum: Int, text: (Int) -> String): BluetoothLyricsText {
        val builder = StringBuilder(maximum)
        var consumed = 0
        var shortened = false
        var index = 0
        while (index < count) {
            val value = text(index)
            if (value.isNotEmpty()) {
                if (builder.isNotEmpty()) {
                    if (consumed == maximum) { shortened = true; break }
                    builder.append('\n')
                    consumed++
                }
                val remaining = maximum - consumed
                if (remaining == 0) { shortened = true; break }
                val clipped = BluetoothLyricsText.clip(value, remaining)
                builder.append(clipped.value)
                consumed += clipped.value.toByteArray(Charsets.UTF_8).size
                if (clipped.shortened) { shortened = true; break }
            }
            index++
        }
        return BluetoothLyricsText(builder.toString(), shortened)
    }

    private fun sameDisplay(old: BluetoothLyricsPayload?, next: BluetoothLyricsPayload): Boolean = old != null &&
        old.trackEpoch == next.trackEpoch && old.lineMediaTimeMs == next.lineMediaTimeMs &&
        old.playback == next.playback && old.isPlaying == next.isPlaying &&
        old.title == next.title && old.artist == next.artist && old.original == next.original &&
        old.translation == next.translation && old.shortened == next.shortened

    override fun close() {
        if (open) {
            open = false
            sourceWork.cancel()
            timer = null
            previous = null
            fingerprint = null
        }
        if (!readingRetired) {
            reading.close()
            readingRetired = true
        }
    }

    override suspend fun awaitClosed() {
        try {
            reading.awaitClosed()
        } finally {
            // 即使读取退休失败，仍核验这次输出的协程树；上层的 2.5 秒边界保持生效。
            sourceWork.join()
        }
    }
}
