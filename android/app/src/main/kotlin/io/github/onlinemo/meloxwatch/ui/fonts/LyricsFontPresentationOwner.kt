package io.github.onlinemo.meloxwatch.ui.fonts

import io.github.onlinemo.meloxwatch.data.fonts.LoadedLyricsFont
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Application 注入的唯一两角色展示所有者。切换先去掉旧 pair，再允许新来源解码；无任务、
 * 队列或缓存。close 只失效具体 binding 并丢弃内存，不能在 Appearance editor 锁内取消 I/O。
 * 锁序为 Appearance editor → 本锁；不读取 ViewModel、文件或设置。visibleNow 只可读取
 * Window/route 的 StateFlow 当前值，不能回调 AppearanceViewModel。
 */
class LyricsFontPresentationOwner {
    private val lock = Any()
    private var current: Binding? = null
    private val mutable = MutableStateFlow(EMPTY)
    val state: StateFlow<LyricsFontRuntimeState> = mutable.asStateFlow()

    /** 同 token 的不同 binding 仍是不同展示；旧 close 不能清除新 pair。 */
    class Binding internal constructor(
        private val owner: LyricsFontPresentationOwner,
        val kind: LyricsFontPresentationKind,
        internal val onRetire: (() -> Unit)?,
    ) : AutoCloseable {
        val isCurrent: Boolean get() = owner.isCurrent(this)
        val state: LyricsFontRuntimeState get() = owner.stateFor(this)

        override fun close() {
            owner.retire(this)
        }
    }

    /** 生产 editor 构造不抢当前 pair；首次可见才通过 bind 成为真正 current。 */
    internal fun detachedEditor(onRetire: () -> Unit): Binding =
        Binding(this, LyricsFontPresentationKind.EDITOR, onRetire)

    internal fun bind(kind: LyricsFontPresentationKind, onRetire: (() -> Unit)? = null): Binding =
        synchronized(lock) {
            retireCurrentLocked()
            val next = Binding(this, kind, onRetire)
            current = next
            mutable.value = EMPTY.copy(kind = kind)
            next
        }

    internal fun replace(binding: Binding, kind: LyricsFontPresentationKind): Binding? = synchronized(lock) {
        if (current !== binding) return@synchronized null
        retireCurrentLocked()
        val next = Binding(this, kind, null)
        current = next
        mutable.value = EMPTY.copy(kind = kind)
        next
    }

    internal fun publish(
        binding: Binding,
        next: LyricsFontRuntimeState,
        visibleNow: () -> Boolean,
    ): Boolean = synchronized(lock) {
        if (current !== binding || !visibleNow()) return@synchronized false
        check(next.kind == binding.kind)
        mutable.value = next
        true
    }

    /**
     * 同一个 editor 的变换与提交都在本锁内，仅做内存操作。每次提交同时准备去 face 的
     * 退场状态。过期操作可记录真实 CAS/read 元数据，不能发布旧字体或覆盖新展示的 pair。
     */
    internal fun publishEditor(
        binding: Binding,
        createNext: () -> LyricsFontEditorState,
        commit: (LyricsFontEditorState, LyricsFontEditorState) -> Unit,
    ) = synchronized(lock) {
        check(binding.kind == LyricsFontPresentationKind.EDITOR)
        val next = createNext()
        val safe = next.withoutTypefaces()
        if (current !== binding) {
            commit(safe, safe)
            return@synchronized
        }
        val rendered = next.copy(presentationActive = true)
        mutable.value = LyricsFontRuntimeState(
            kind = LyricsFontPresentationKind.EDITOR,
            original = editorRole(rendered.original),
            translation = editorRole(rendered.translation),
            saved = rendered.saved,
            loading = rendered.working,
            issue = rendered.issue,
        )
        commit(rendered, safe)
    }

    private fun isCurrent(binding: Binding): Boolean = synchronized(lock) { current === binding }

    private fun stateFor(binding: Binding): LyricsFontRuntimeState = synchronized(lock) {
        if (current === binding) mutable.value else EMPTY
    }

    private fun retire(binding: Binding) = synchronized(lock) {
        if (current === binding) retireCurrentLocked()
    }

    private fun retireCurrentLocked() {
        val previous = current
        current = null
        mutable.value = EMPTY
        // Editor 回调只赋已准备的无 face 状态；绝不 cancel/restore/prune/discard 或回读 VM。
        previous?.onRetire?.invoke()
    }

    private fun editorRole(font: LoadedLyricsFont): LyricsFontRuntimeRole = LyricsFontRuntimeRole(
        font = font,
        requestedSource = if (font.asset == null) LyricsFontRequestedSource.SYSTEM
            else LyricsFontRequestedSource.PERSONAL_FILE,
    )

    companion object {
        private val EMPTY = LyricsFontRuntimeState()
    }
}
