package io.github.onlinemo.meloxwatch.playback.route

import io.github.onlinemo.meloxwatch.playback.PlaybackEngineReleaseGuard
import java.util.concurrent.Executor

internal class RouteTestFixture(executor: Executor = Executor { it.run() }, onChanged: () -> Unit = {}) {
    val guard = PlaybackEngineReleaseGuard()
    val lease = guard.acquire()
    val route = PlaybackAudioRoute(lease, guard, executor, onChanged)

    fun attach(source: FakeRoutingSource = FakeRoutingSource()): FakeRoutingSource {
        route.observe(requireNotNull(route.beginTrack()), source)
        return source
    }
}

/** 已入队的回调在移除后仍可送达，以检查票据而不是依赖假 listener 的理想行为。 */
internal class FakeRoutingSource(override val objectIdentity: Any = Any()) : ActualRoutingSource {
    var deviceId: Int? = 7
    var initialized = true
    var playing = true
    var readFailure = false
    var registerFailure = false
    var removalFailure = false
    var onRead: (() -> Unit)? = null
    var onRegister: (() -> Unit)? = null
    var onRemove: (() -> Unit)? = null
    val callbacks = mutableListOf<() -> Unit>()
    var removals = 0

    override fun playingDeviceId(): Int? {
        onRead?.also { onRead = null }?.invoke()
        if (readFailure) throw SecurityException("route query unavailable")
        return deviceId.takeIf { initialized && playing }
    }

    override fun addRoutingListener(changed: () -> Unit): RoutingListenerRemoval {
        if (registerFailure) throw SecurityException("route listener unavailable")
        callbacks.add(changed)
        onRegister?.also { onRegister = null }?.invoke()
        return RoutingListenerRemoval {
            removals++
            onRemove?.also { onRemove = null }?.invoke()
            if (removalFailure) throw IllegalStateException("route listener removal unconfirmed")
        }
    }
}
