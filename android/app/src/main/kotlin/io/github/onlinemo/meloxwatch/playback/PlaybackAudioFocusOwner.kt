package io.github.onlinemo.meloxwatch.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/** The service owns focus and noisy handling for every source and backend. Reconnect never requests play. */
@UnstableApi
internal class PlaybackAudioFocusOwner(
    context: Context,
    private val player: () -> Player?,
    private val backendGeneration: () -> Long,
    private val ensureForeground: () -> Boolean,
    private val pauseQueue: () -> Unit,
    private val checkpoint: () -> Unit,
    private val resumeQueue: () -> Unit,
    private val onFailure: (PlaybackBackendIssue) -> Unit,
) {
    private data class FocusIntent(val requestId: Long, val backendGeneration: Long, val mediaId: String, val resumeRevision: Long)
    private val applicationContext = context.applicationContext
    private val audioManager = checkNotNull(applicationContext.getSystemService(AudioManager::class.java))
    private val handler = Handler(Looper.getMainLooper())
    private var enabled = true
    private var closed = false
    private var receiverRegistered = false
    private var requestId = 0L
    private var resumeRevision = 0L
    private var intent: FocusIntent? = null
    private var request: AudioFocusRequest? = null
    private var granted = false
    private var resumeWanted = false
    private var interrupted = false
    private var resuming = false
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (closed || intent.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            userPause()
            pauseQueue()
        }
    }

    init {
        checkMain()
        ContextCompat.registerReceiver(applicationContext, noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    fun setManagementEnabled(value: Boolean) {
        checkMain()
        if (closed || enabled == value) return
        enabled = value
        userPause()
        // Turning management on has no standing user play request. Pause first; the next
        // explicit play obtains foreground and focus before either backend can emit audio.
        if (value && player()?.playWhenReady == true) pauseQueue()
    }

    fun requestPlay(): Boolean {
        checkMain()
        val current = player() ?: return false
        if (closed || current.currentMediaItem == null || current.playerError != null) return false
        if (!ensureForeground()) {
            userPause()
            current.pause()
            checkpoint()
            onFailure(PlaybackBackendIssue.FOREGROUND_DENIED)
            return false
        }
        if (!enabled) {
            current.play()
            return current.playWhenReady
        }
        val existing = intent
        if (granted && existing != null && canResume(existing) && (resuming || current.playWhenReady)) {
            current.play()
            return current.playWhenReady
        }
        abandonRequest()
        resumeRevision += 1L
        requestId += 1L
        val desired = FocusIntent(requestId, backendGeneration(), current.currentMediaItem?.mediaId ?: return false, resumeRevision)
        intent = desired
        resumeWanted = true
        interrupted = false
        val nextRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change -> onFocusChanged(desired, change) }, handler)
            .build()
        request = nextRequest
        val result = try { audioManager.requestAudioFocus(nextRequest) }
        catch (_: RuntimeException) { AudioManager.AUDIOFOCUS_REQUEST_FAILED }
        return when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                granted = true
                current.play()
                current.playWhenReady
            }
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                granted = false
                interrupted = true
                current.pause()
                checkpoint()
                false
            }
            else -> {
                userPause()
                current.pause()
                checkpoint()
                onFailure(PlaybackBackendIssue.FOCUS_DENIED)
                false
            }
        }
    }

    /** Manual seek/navigation invalidates an old gain, while a playing stream keeps its current focus. */
    fun cancelResumeIntent() {
        checkMain()
        resumeRevision += 1L
        resumeWanted = false
        interrupted = false
        if (player()?.playWhenReady != true) abandonRequest()
    }

    fun userPause() {
        checkMain()
        resumeRevision += 1L
        resumeWanted = false
        interrupted = false
        abandonRequest()
    }

    fun unbindBackend() = userPause()

    fun close() {
        checkMain()
        if (closed) return
        closed = true
        try { userPause() }
        finally {
            val unregister = receiverRegistered
            receiverRegistered = false
            if (unregister) applicationContext.unregisterReceiver(noisyReceiver)
        }
    }

    private fun onFocusChanged(expected: FocusIntent, change: Int) {
        checkMain()
        if (!isCurrentRequest(expected)) return
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                granted = true
                if (interrupted && canResume(expected)) {
                    interrupted = false
                    resuming = true
                    try { resumeQueue() } finally { resuming = false }
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                granted = false
                resumeWanted = resumeWanted && expected.resumeRevision == resumeRevision
                interrupted = resumeWanted
                player()?.pause()
                checkpoint()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                userPause()
                pauseQueue()
            }
        }
    }

    private fun isCurrentRequest(expected: FocusIntent): Boolean = !closed && enabled && intent == expected &&
        expected.backendGeneration == backendGeneration()

    private fun canResume(expected: FocusIntent): Boolean = isCurrentRequest(expected) && resumeWanted &&
        expected.resumeRevision == resumeRevision && player()?.currentMediaItem?.mediaId == expected.mediaId &&
        player()?.playerError == null

    private fun abandonRequest() {
        val previous = request
        request = null
        intent = null
        granted = false
        if (previous != null) {
            try { audioManager.abandonAudioFocusRequest(previous) }
            catch (_: RuntimeException) { /* The abandoned request cannot restore a playback intent. */ }
        }
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper())
}
