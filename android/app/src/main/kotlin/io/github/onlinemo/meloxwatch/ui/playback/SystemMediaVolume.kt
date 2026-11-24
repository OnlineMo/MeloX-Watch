package io.github.onlinemo.meloxwatch.ui.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import io.github.onlinemo.meloxwatch.playback.input.RotaryInputPolicy
import io.github.onlinemo.meloxwatch.playback.input.RotaryStep
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MediaVolumeIssue { UNAVAILABLE, FIXED, REMOTE_OUTPUT, OUTPUT_CHANGED, SET_REJECTED }

data class MediaVolumeState(
    val current: Int? = null,
    val minimum: Int = 0,
    val maximum: Int? = null,
    val fixed: Boolean = false,
    val preview: Int? = null,
    val gestureId: String? = null,
    val issue: MediaVolumeIssue? = null,
    val ownerId: String? = null,
) {
    val adjustable: Boolean
        get() = current != null && maximum != null && maximum > minimum && !fixed &&
            issue !in setOf(MediaVolumeIssue.UNAVAILABLE, MediaVolumeIssue.REMOTE_OUTPUT)
}

/** A volume draft owns no audio state. Only an explicit completion writes STREAM_MUSIC. */
@MainThread
class SystemMediaVolume(context: Context, private val canUse: () -> Boolean) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val router = context.getSystemService(MediaRouter::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(MediaVolumeState())
    val state = mutableState.asStateFlow()
    private var observing = false
    private var routeEpoch = UUID.randomUUID().toString()
    private var draft: VolumeDraft? = null
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = outputChanged()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = outputChanged()
    }
    private val routeCallback = object : MediaRouter.SimpleCallback() {
        override fun onRouteSelected(router: MediaRouter, type: Int, info: MediaRouter.RouteInfo) = outputChanged()
        override fun onRouteUnselected(router: MediaRouter, type: Int, info: MediaRouter.RouteInfo) = outputChanged()
        override fun onRouteChanged(router: MediaRouter, info: MediaRouter.RouteInfo) = refresh()
        override fun onRouteVolumeChanged(router: MediaRouter, info: MediaRouter.RouteInfo) = refresh()
    }

    val busy: Boolean get() = draft != null

    fun onVisible() {
        if (!observing) {
            observing = true
            try { audio?.registerAudioDeviceCallback(deviceCallback, handler) }
            catch (_: RuntimeException) { }
            try { router?.addCallback(MediaRouter.ROUTE_TYPE_LIVE_AUDIO, routeCallback) }
            catch (_: RuntimeException) { }
        }
        refresh()
    }

    fun onHidden() {
        observing = false
        invalidate()
        try { audio?.unregisterAudioDeviceCallback(deviceCallback) }
        catch (_: RuntimeException) { }
        try { router?.removeCallback(routeCallback) }
        catch (_: RuntimeException) { }
    }

    fun refresh() {
        val observed = read()
        if (observed == null) {
            draft = null
            mutableState.value = MediaVolumeState(issue = MediaVolumeIssue.UNAVAILABLE)
            return
        }
        val active = draft
        if (active != null && !matches(active.observed, observed)) {
            draft = null
            mutableState.value = observed.toState(MediaVolumeIssue.OUTPUT_CHANGED)
        } else {
            val issue = mutableState.value.issue?.takeIf {
                it == MediaVolumeIssue.OUTPUT_CHANGED || it == MediaVolumeIssue.SET_REJECTED
            }
            mutableState.value = observed.toState(issue).copy(
                preview = active?.target, gestureId = active?.id)
        }
    }

    fun begin(): String? {
        if (!canUse() || draft != null) return null
        refresh()
        val observed = read() ?: return null
        if (!observed.adjustable) return null
        val value = VolumeDraft(UUID.randomUUID().toString(), observed, observed.current)
        draft = value
        mutableState.value = observed.toState().copy(preview = value.target, gestureId = value.id)
        return value.id
    }

    fun drag(gestureId: String, fraction: Double): Boolean {
        val active = draft?.takeIf { it.id == gestureId } ?: return false
        if (!canUse() || !fraction.isFinite() || fraction !in 0.0..1.0) {
            cancel(gestureId)
            return false
        }
        val observed = read()
        if (observed == null || !matches(active.observed, observed)) {
            outputChanged()
            return false
        }
        val target = observed.minimum + (fraction * (observed.maximum - observed.minimum)).toInt()
        active.target = target.coerceIn(observed.minimum, observed.maximum)
        mutableState.value = observed.toState().copy(preview = active.target, gestureId = active.id)
        return true
    }

    fun release(gestureId: String): Boolean {
        val active = draft?.takeIf { it.id == gestureId } ?: return false
        draft = null
        if (!canUse()) {
            refresh()
            return false
        }
        return applyTarget(active.observed, active.target)
    }

    fun tap(gestureId: String, fraction: Double): Boolean = drag(gestureId, fraction) && release(gestureId)

    fun cancel(gestureId: String) {
        if (draft?.id == gestureId) invalidate()
    }

    fun setProgress(fraction: Double): Boolean {
        val id = begin() ?: return false
        return tap(id, fraction)
    }

    fun change(delta: Int): Boolean {
        if (!canUse() || draft != null || delta !in listOf(-1, 1)) return false
        val observed = read() ?: run {
            refresh()
            return false
        }
        if (!observed.adjustable) {
            refresh()
            return false
        }
        val target = (observed.current + delta).coerceIn(observed.minimum, observed.maximum)
        if (target == observed.current) {
            mutableState.value = observed.toState()
            return false
        }
        return applyTarget(observed, target)
    }

    /** Success vibration requires the fresh platform readback to have actually changed. */
    fun rotary(step: RotaryStep): Boolean {
        if (!canUse() || draft != null) return false
        val observed = read() ?: run {
            refresh()
            return false
        }
        val relative = RotaryInputPolicy.mediaVolumeTarget(step, observed.current - observed.minimum,
            observed.maximum - observed.minimum, !observed.adjustable) ?: run {
                refresh()
                return false
            }
        if (!applyTarget(observed, relative + observed.minimum)) return false
        return mutableState.value.current != observed.current
    }

    fun invalidate() {
        draft = null
        routeEpoch = UUID.randomUUID().toString()
        mutableState.value = mutableState.value.copy(preview = null, gestureId = null, ownerId = routeEpoch)
    }

    private fun outputChanged() {
        routeEpoch = UUID.randomUUID().toString()
        val hadDraft = draft != null
        invalidate()
        if (observing) {
            refresh()
            if (hadDraft && mutableState.value.adjustable) {
                mutableState.value = mutableState.value.copy(issue = MediaVolumeIssue.OUTPUT_CHANGED)
            }
        }
    }

    private fun applyTarget(expected: ObservedVolume, target: Int): Boolean {
        val actual = read()
        if (actual == null || !matches(expected, actual) || !actual.adjustable) {
            outputChanged()
            return false
        }
        return try {
            audio?.setStreamVolume(AudioManager.STREAM_MUSIC, target.coerceIn(actual.minimum, actual.maximum), 0)
            val readback = read()
            if (readback == null) {
                refresh()
                false
            }
            else {
                val sameOutput = expected.routeEpoch == readback.routeEpoch && expected.route === readback.route &&
                    expected.devices == readback.devices && expected.minimum == readback.minimum &&
                    expected.maximum == readback.maximum && expected.fixed == readback.fixed &&
                    expected.remote == readback.remote && readback.adjustable
                val accepted = sameOutput && readback.current == target.coerceIn(readback.minimum, readback.maximum)
                mutableState.value = readback.toState(if (accepted) null else MediaVolumeIssue.SET_REJECTED)
                accepted
            }
        } catch (_: RuntimeException) {
            refresh()
            if (mutableState.value.adjustable) mutableState.value = mutableState.value.copy(issue = MediaVolumeIssue.SET_REJECTED)
            false
        }
    }

    private fun read(): ObservedVolume? = try {
        val manager = audio
        if (manager == null) null else {
            val current = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val minimum = if (Build.VERSION.SDK_INT >= 28) manager.getStreamMinVolume(AudioManager.STREAM_MUSIC) else 0
            val maximum = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val selected = router?.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO)
            if (current !in minimum..maximum || minimum < 0) null else ObservedVolume(current, minimum, maximum,
                manager.isVolumeFixed, selected, selected?.playbackType == MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE,
                manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.id }.sorted(), routeEpoch)
        }
    } catch (_: RuntimeException) { null }

    private fun matches(expected: ObservedVolume, actual: ObservedVolume): Boolean =
        expected.routeEpoch == actual.routeEpoch && expected.route === actual.route && expected.devices == actual.devices &&
            expected.current == actual.current && expected.minimum == actual.minimum &&
            expected.maximum == actual.maximum && expected.fixed == actual.fixed && expected.remote == actual.remote

    private data class ObservedVolume(val current: Int, val minimum: Int, val maximum: Int, val fixed: Boolean,
        val route: MediaRouter.RouteInfo?, val remote: Boolean, val devices: List<Int>, val routeEpoch: String) {
        val adjustable: Boolean get() = !fixed && !remote && maximum > minimum
        fun toState(issue: MediaVolumeIssue? = null) = MediaVolumeState(current, minimum, maximum, fixed,
            issue = when {
                remote -> MediaVolumeIssue.REMOTE_OUTPUT
                fixed -> MediaVolumeIssue.FIXED
                else -> issue
            }, ownerId = routeEpoch)
    }

    private class VolumeDraft(val id: String, val observed: ObservedVolume, var target: Int)
}
