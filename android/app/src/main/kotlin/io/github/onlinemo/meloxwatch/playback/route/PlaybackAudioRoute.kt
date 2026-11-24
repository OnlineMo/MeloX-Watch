package io.github.onlinemo.meloxwatch.playback.route

import io.github.onlinemo.meloxwatch.playback.PlaybackEngineReleaseGuard
import java.util.UUID
import java.util.concurrent.Executor

internal fun interface RoutingListenerRemoval {
    fun remove()
}

/** 只观察已归现有后端所有的对象，不负责创建、播放或释放它。 */
internal interface ActualRoutingSource {
    val objectIdentity: Any
    fun playingDeviceId(): Int?
    fun addRoutingListener(changed: () -> Unit): RoutingListenerRemoval
}

internal class PlaybackAudioRoute(
    private val lease: PlaybackEngineReleaseGuard.Lease,
    private val releaseGuard: PlaybackEngineReleaseGuard,
    private val callbackExecutor: Executor,
    private val onChanged: () -> Unit = {},
) {
    internal class TrackTicket internal constructor(
        internal val owner: PlaybackAudioRoute,
        internal val lease: PlaybackEngineReleaseGuard.Lease,
        internal val sourceEpoch: Any,
    )

    private class Observation(val ticket: TrackTicket, val source: ActualRoutingSource) {
        val identity = source.objectIdentity
        var removal: RoutingListenerRemoval? = null
        var registered = false
        var removalStarted = false
    }

    private val lock = Any()
    private var sourceEpoch = Any()
    private var queryEpoch = Any()
    private var currentTicket: TrackTicket? = null
    private var observation: Observation? = null
    private var nativeObservation: Observation? = null
    private var pendingObservation: Observation? = null
    private var playbackActive = false
    private var renderingActive = true
    private var closed = false
    private var listenerFailed = false
    private var routedDeviceId: Int? = null
    private var state = PlaybackRouteSessionState()
    private var published = state
    private var publishPending = false

    fun cachedState(): PlaybackRouteSessionState = synchronized(lock) { state }

    /** 主线程的暂停门禁独立于播放线程；迟到的 sink.play 不能重新打开它。 */
    fun setPlaybackActive(active: Boolean) {
        synchronized(lock) {
            if (playbackActive != active) {
                playbackActive = active
                retireConfidenceLocked()
            }
        }
        publish()
        if (active) read()
    }

    fun setRenderingActive(active: Boolean) {
        synchronized(lock) {
            if (renderingActive != active) {
                renderingActive = active
                retireConfidenceLocked()
            }
        }
        publish()
        if (active) read()
    }

    /** 在平台分配前发票据；分配期间换来源/后端时，返回的旧对象不能恢复观察。 */
    fun beginTrack(): TrackTicket? {
        if (!leaseUsable()) {
            close()
            return null
        }
        val previous: Observation?
        val ticket: TrackTicket?
        synchronized(lock) {
            previous = observation
            observation = null
            pendingObservation = null
            retireConfidenceLocked()
            ticket = if (closed || listenerFailed) null else TrackTicket(this, lease, sourceEpoch)
            currentTicket = ticket
        }
        remove(previous)
        publish()
        return ticket
    }

    /** 可复用的同一 AudioTrack 也换 listener 票据，拒绝旧来源已入队的回调。 */
    fun sourceChanged(keepSource: Boolean = false) {
        val previous: Observation?
        val ticket: TrackTicket?
        val sourceToKeep: ActualRoutingSource?
        synchronized(lock) {
            sourceEpoch = Any()
            sourceToKeep = if (keepSource) observation?.source ?: pendingObservation?.source else null
            previous = observation
            observation = null
            pendingObservation = null
            retireConfidenceLocked()
            ticket = if (sourceToKeep != null && !closed && !listenerFailed) {
                TrackTicket(this, lease, sourceEpoch)
            } else null
            currentTicket = ticket
        }
        remove(previous)
        publish()
        if (ticket != null && sourceToKeep != null) observe(ticket, sourceToKeep)
    }

    fun observe(ticket: TrackTicket, source: ActualRoutingSource) {
        if (!leaseUsable()) {
            close()
            return
        }
        val candidate = Observation(ticket, source)
        val admitted = synchronized(lock) {
            if (!currentLocked(ticket) || observation != null) {
                false
            } else if (nativeObservation != null) {
                // 注册或移除未返回时只保留最新意图，不能累计未确认的原生 listener。
                pendingObservation = candidate
                false
            } else {
                observation = candidate
                nativeObservation = candidate
                pendingObservation = null
                true
            }
        }
        if (!admitted) return
        register(candidate)
        read()
    }

    private fun register(candidate: Observation) {
        val removal = try {
            candidate.source.addRoutingListener { routingChanged(candidate) }
        } catch (_: RuntimeException) {
            failListener()
            return
        }
        val accepted = synchronized(lock) {
            candidate.removal = removal
            if (observation !== candidate || nativeObservation !== candidate || !currentLocked(candidate.ticket)) {
                false
            } else {
                candidate.registered = true
                true
            }
        }
        if (!accepted) {
            remove(candidate)
        }
    }

    /** 每次提交都同步核真对象；缓存的会话身份不替代这次查询。 */
    fun read(): PlaybackRouteSessionState {
        if (!leaseUsable()) {
            close()
            return PlaybackRouteSessionState()
        }
        observePending()
        val current: Observation?
        val epoch: Any
        synchronized(lock) {
            current = observation?.takeIf {
                currentLocked(it.ticket) && it.registered && playbackActive && renderingActive
            }
            epoch = queryEpoch
            if (current == null) retireConfidenceLocked()
        }
        if (current == null) {
            publish()
            return PlaybackRouteSessionState()
        }
        val device = try {
            current.source.playingDeviceId()?.takeIf { it > 0 }
        } catch (_: RuntimeException) {
            null
        }
        if (!leaseUsable()) {
            close()
            return PlaybackRouteSessionState()
        }
        val result = synchronized(lock) {
            if (observation !== current || !currentLocked(current.ticket) || queryEpoch !== epoch ||
                !current.registered || !playbackActive || !renderingActive) {
                PlaybackRouteSessionState()
            } else if (device == null) {
                retireConfidenceLocked()
                state
            } else {
                if (!state.known || routedDeviceId != device) {
                    queryEpoch = Any()
                    routedDeviceId = device
                    state = PlaybackRouteSessionState(UUID.randomUUID().toString(), true)
                }
                state
            }
        }
        publish()
        return result
    }

    /** 原生释放成功与否不影响退役；观察者从不释放播放器或 AudioTrack。 */
    fun close() {
        val previous = synchronized(lock) {
            closed = true
            playbackActive = false
            currentTicket = null
            pendingObservation = null
            retireConfidenceLocked()
            observation.also { observation = null }
        }
        remove(previous)
        publish()
    }

    private fun routingChanged(candidate: Observation) {
        if (!leaseUsable()) {
            close()
            return
        }
        val admitted = synchronized(lock) {
            if (observation !== candidate || observation?.identity !== candidate.identity ||
                !currentLocked(candidate.ticket) || !candidate.registered) {
                false
            } else {
                // 同 id 通知也退役，不能把已观察到的 A→B→A 当成未改变。
                retireConfidenceLocked()
                true
            }
        }
        if (admitted) {
            publish()
            read()
        }
    }

    private fun currentLocked(ticket: TrackTicket): Boolean = !closed && !listenerFailed &&
        ticket.owner === this && ticket.lease === lease && ticket.sourceEpoch === sourceEpoch &&
        currentTicket === ticket

    private fun leaseUsable(): Boolean = releaseGuard.isRetained(lease) &&
        !releaseGuard.releaseStarted(lease) && !releaseGuard.hasUnconfirmedRelease()

    private fun retireConfidenceLocked() {
        queryEpoch = Any()
        routedDeviceId = null
        state = PlaybackRouteSessionState()
    }

    private fun remove(previous: Observation?) {
        if (previous == null) return
        val removal = synchronized(lock) {
            if (nativeObservation !== previous || previous.removalStarted) null
            else previous.removal?.also { previous.removalStarted = true }
        } ?: return
        try {
            removal.remove()
        } catch (_: RuntimeException) {
            failListener()
            return
        }
        synchronized(lock) {
            if (nativeObservation === previous) nativeObservation = null
        }
    }

    private fun observePending() {
        val candidate = synchronized(lock) {
            val pending = pendingObservation
            if (pending == null || nativeObservation != null || observation != null) {
                null
            } else if (!currentLocked(pending.ticket)) {
                pendingObservation = null
                null
            } else {
                pendingObservation = null
                observation = pending
                nativeObservation = pending
                pending
            }
        }
        if (candidate != null) register(candidate)
    }

    private fun failListener() {
        synchronized(lock) {
            // 未确认移除时不在同一后端累加 listener；播放继续，绝对草稿保持不可用。
            listenerFailed = true
            currentTicket = null
            pendingObservation = null
            retireConfidenceLocked()
            observation = null
        }
        publish()
    }

    private fun publish() {
        val schedule = synchronized(lock) {
            if (publishPending || published == state) {
                false
            } else {
                publishPending = true
                true
            }
        }
        if (!schedule) return
        try {
            callbackExecutor.execute {
                val notify = synchronized(lock) {
                    publishPending = false
                    if (published == state) {
                        false
                    } else {
                        published = state
                        true
                    }
                }
                try {
                    if (notify) onChanged()
                } catch (_: RuntimeException) {
                    // 观察回调失败不能终止播放线程；后续状态仍可重新发布。
                }
            }
        } catch (_: RuntimeException) {
            synchronized(lock) { publishPending = false }
        }
    }
}
