package io.github.onlinemo.meloxwatch.playback.dsp

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 保存值和预览共用唯一 PCM 所有者；旧预览不能覆盖后来已提交的配置。 */
class DspRuntime {
    private data class Configuration(val saved: DspStored = DspStored(),
        val previewOwner: Any? = null, val preview: DspSettings? = null)
    private val lock = Any()
    private val configuration = AtomicReference(Configuration())
    private var activeSink: Any? = null
    private val actual = MutableStateFlow(DspActualState())
    val state = actual.asStateFlow()

    fun setSaved(value: DspStored) {
        val frozen = value.immutableCopy()
        synchronized(lock) {
            val previous = configuration.get()
            configuration.set(if (previous.saved == frozen) previous else Configuration(frozen))
        }
    }

    /** 预览只存在于当前进程；播放器和音频会话仍由唯一服务建立。 */
    fun preview(owner: Any, expectedRevision: Long, settings: DspSettings): Boolean {
        val frozen = settings.immutableCopy()
        return synchronized(lock) {
            val current = configuration.get()
            if (current.saved.revision != expectedRevision ||
                current.previewOwner != null && current.previewOwner !== owner) false
            else {
                configuration.set(current.copy(previewOwner = owner, preview = frozen))
                true
            }
        }
    }

    fun endPreview(owner: Any) = synchronized(lock) {
        val current = configuration.get()
        if (current.previewOwner === owner) configuration.set(current.copy(previewOwner = null, preview = null))
    }

    fun hasPreview(owner: Any): Boolean = configuration.get().previewOwner === owner

    internal fun currentSettings(): DspSettings = configuration.get().let { it.preview ?: it.saved.settings }

    internal fun attachSink(): Any = synchronized(lock) {
        val saved = configuration.get().saved
        configuration.set(Configuration(saved))
        val ticket = Any()
        activeSink = ticket
        actual.value = DspActualState(requested = currentSettings())
        ticket
    }

    internal fun report(ticket: Any, value: DspActualState) = synchronized(lock) {
        // 只发布配置、格式和生命周期变化，音频帧不驱动界面刷新。
        if (activeSink === ticket) actual.value = value
    }

    internal fun releaseSink(ticket: Any) = synchronized(lock) {
        if (activeSink === ticket) {
            activeSink = null
            configuration.set(Configuration(configuration.get().saved))
            actual.value = DspActualState(requested = currentSettings(), issue = DspRuntimeIssue.CLOSED)
        }
    }

    internal fun unavailable(issue: DspRuntimeIssue) = synchronized(lock) {
        activeSink = null
        configuration.set(Configuration(configuration.get().saved))
        actual.value = DspActualState(requested = currentSettings(), issue = issue)
    }
}
