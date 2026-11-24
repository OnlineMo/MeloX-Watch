package io.github.onlinemo.meloxwatch.playback.dsp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 唯一服务观察磁盘权威；页面退出只撤销预览，不关闭处理器或播放器。 */
internal class ServiceDspSettingsOwner(
    private val store: DspSettingsStore,
    private val runtime: DspRuntime,
    private val scope: CoroutineScope,
) {
    private var observer: Job? = null

    suspend fun initialize() {
        withContext(Dispatchers.IO) { store.read() }
        // IO 返回到服务线程期间可能已有新的 CAS；只采用当前已发布的完整代次。
        store.state.value.saved?.let(runtime::setSaved)
    }

    fun observe() {
        if (observer != null) return
        observer = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            store.state.collect {
                store.state.value.saved?.let(runtime::setSaved)
            }
        }
    }

    fun close() {
        observer?.cancel()
        observer = null
    }
}
