package io.github.onlinemo.meloxwatch.ui

import androidx.annotation.MainThread
import io.github.onlinemo.meloxwatch.data.library.LibraryTrack
import io.github.onlinemo.meloxwatch.data.themes.AppearanceSnapshot
import io.github.onlinemo.meloxwatch.data.themes.BackgroundViewport
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundImageKey
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundOptions
import io.github.onlinemo.meloxwatch.data.themes.ThemeSnapshot
import io.github.onlinemo.meloxwatch.model.ArtworkReadPath
import io.github.onlinemo.meloxwatch.ui.fonts.LyricsFontRuntime
import io.github.onlinemo.meloxwatch.ui.themes.AppearanceViewModel
import io.github.onlinemo.meloxwatch.ui.themes.PlaybackBackgroundDisplay
import io.github.onlinemo.meloxwatch.ui.themes.PlaybackBackgroundViewModel
import io.github.onlinemo.meloxwatch.ui.watch.WatchWindowRuntime
import io.github.onlinemo.meloxwatch.ui.watch.WatchWindowVisibility
import io.github.onlinemo.meloxwatch.ui.watch.lyricsKeepBrightRequested
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class ProbePresentedPageKind { THEME_UI, PLAYBACK, LYRICS, APPEARANCE, LYRICS_FONT }
internal enum class ProbeFontSlot { THEME_UI, PERSONAL_LYRICS, EDITOR, THEME_PREVIEW }

/** Read from the actual PagerState, including an in-progress drag, rather than page == route. */
internal data class ProbePageSelectionSnapshot(
    val route: ProbePage,
    val current: Boolean,
    val settled: Boolean,
    val scrolling: Boolean,
) {
    val selected: Boolean get() = current && settled && !scrolling
}

/**
 * One Activity owns one page and four fixed resource slots. Navigation/media producers and the
 * sole Window callback retire these slots synchronously; Compose only starts caller work for a
 * newly admitted identity. This class starts no coroutine, observer, queue, cache or file read.
 * The bookkeeping monitor never encloses a VM call, resource callback or Job cancellation.
 */
@MainThread
internal class ProbePageRuntime internal constructor(
    private val uiState: () -> ProbeUiState,
    private val windowVisibility: () -> WatchWindowVisibility,
    private val activityPresentation: () -> ProbeUiPresentation.Binding?,
    private val resources: Resources,
) : AutoCloseable {
    constructor(
        uiState: () -> ProbeUiState,
        window: WatchWindowRuntime,
        activityPresentation: () -> ProbeUiPresentation.Binding?,
        appearance: AppearanceViewModel,
        fonts: LyricsFontRuntime,
        background: PlaybackBackgroundViewModel,
    ) : this(uiState, { window.visibility.value }, activityPresentation, object : Resources {
        override val savedAppearance: AppearanceSnapshot get() = appearance.state.value.appearance
        override fun setForeground(value: Boolean) = appearance.setForeground(value)
        override fun setEditorVisible(value: Boolean) = appearance.setEditorVisible(value)
        override fun cancelRetiredFonts() = fonts.cancelRetiredWork()
        override fun ownsBackgroundModel(model: PlaybackBackgroundViewModel): Boolean = background === model
        override fun acquireLyricsLease(): LyricsLease {
            val exact = window.acquireLyricsLease()
            return object : LyricsLease {
                override fun update(requested: Boolean) = exact.update(requested)
                override fun close() = exact.close()
            }
        }
        override fun setBackgroundVisible(owner: Any, value: Boolean): Boolean = background.setVisible(owner, value)
        override fun updateBackground(owner: Any, scene: BackgroundScene): Boolean = background.updateScene(
            owner, scene.theme, scene.options, scene.customKey, scene.playingTrack, scene.artworkPath,
            scene.artworkRevision, scene.viewport,
        )
        override fun isBackgroundPresented(
            owner: Any,
            display: PlaybackBackgroundDisplay,
            viewport: BackgroundViewport?,
        ): Boolean = background.isPresented(owner, display, viewport)
    })

    /** The test boundary replaces effects only; production always injects the actual sole owners. */
    internal interface Resources {
        val savedAppearance: AppearanceSnapshot
        fun setForeground(value: Boolean)
        fun setEditorVisible(value: Boolean)
        fun cancelRetiredFonts()
        fun acquireLyricsLease(): LyricsLease
        fun ownsBackgroundModel(model: PlaybackBackgroundViewModel): Boolean = false
        fun setBackgroundVisible(owner: Any, value: Boolean): Boolean
        fun updateBackground(owner: Any, scene: BackgroundScene): Boolean
        fun isBackgroundPresented(owner: Any, display: PlaybackBackgroundDisplay, viewport: BackgroundViewport?): Boolean
    }

    internal interface LyricsLease : AutoCloseable {
        fun update(requested: Boolean)
    }

    internal data class BackgroundScene(
        val theme: ThemeSnapshot,
        val options: PlaybackBackgroundOptions,
        val customKey: PlaybackBackgroundImageKey.Custom?,
        val playingTrack: LibraryTrack?,
        val artworkPath: ArtworkReadPath,
        val artworkRevision: Long,
        val viewport: BackgroundViewport,
    )

    private val lock = Any()
    @Volatile private var closed = false
    @Volatile private var activityBinding: ProbeUiPresentation.Binding? = null
    @Volatile private var currentSelection: SelectionBinding? = null
    @Volatile private var currentPage: PageBinding? = null
    @Volatile private var observedSource = SourceStamp.of(uiState())
    @Volatile private var observedWindow = windowVisibility()
    @Volatile private var sourceIdentity: Any = Any()
    @Volatile private var windowIdentity: Any = Any()
    private val mutableSourceEpoch = MutableStateFlow(sourceIdentity)
    private val mutableWindowEpoch = MutableStateFlow(windowIdentity)
    val sourceEpoch = mutableSourceEpoch.asStateFlow()
    val windowEpoch = mutableWindowEpoch.asStateFlow()

    /** bindUiPresentation delivers its initial value before returning; attach after assignment. */
    fun attachActivity(binding: ProbeUiPresentation.Binding) {
        check(!closed && activityBinding == null && binding.isCurrent && activityPresentation() === binding)
        activityBinding = binding
        onUiStateChanged()
    }

    /** ProbeUiPresentation calls this before every producing navigation/media call returns. */
    fun onUiStateChanged() {
        synchronizeInputs()
        if (!ownsActivity()) {
            currentPage?.close()
            return
        }
        // Window hide preserves the draft. Only actual navigation out of APPEARANCE exits it.
        resources.setEditorVisible(uiState().page == ProbePage.APPEARANCE)
        if (ownsActivity()) resources.setForeground(windowVisibility().visible)
        refreshLyricsRequest()
    }

    /** Called directly by this Activity's unique WatchWindowRuntime, never by a delayed collector. */
    fun onWindowVisibilityChanged() {
        synchronizeInputs()
        if (ownsActivity()) resources.setForeground(windowVisibility().visible)
        refreshLyricsRequest()
    }

    /** Caller observes saved settings; the gate reads saved state again and never reads a draft. */
    fun onAppearanceChanged() {
        synchronizeInputs()
        refreshLyricsRequest()
    }

    /** Root installs this once for the actual pager or the actual single-page route container. */
    fun bindSelection(readActual: () -> ProbePageSelectionSnapshot): SelectionBinding? {
        synchronizeInputs()
        if (!ownsActivity()) return null
        currentSelection?.close()
        if (!ownsActivity() || currentSelection != null) return null
        return SelectionBinding(readActual, readActual()).also { currentSelection = it }
    }

    /** Hidden neighbors must fail before retiring the currently admitted page or starting I/O. */
    fun bindPage(kind: ProbePresentedPageKind, selection: SelectionBinding): PageBinding? {
        synchronizeInputs()
        selection.refresh()
        if (!canBind(kind, selection)) return null
        currentPage?.close()
        val page = synchronized(lock) {
            if (currentPage != null || !canBind(kind, selection)) return null
            PageBinding(kind, observedSource, activityBinding!!, selection, selection.currentEpoch,
                sourceIdentity, windowIdentity).also { currentPage = it }
        }
        if (kind == ProbePresentedPageKind.LYRICS) {
            try {
                val lease = resources.acquireLyricsLease()
                val admitted = synchronized(lock) {
                    if (!page.visibleNow) false else {
                        page.lyricsLease = lease
                        true
                    }
                }
                if (!admitted) lease.close()
            } catch (failure: RuntimeException) {
                page.close()
                throw failure
            }
        }
        refreshLyricsRequest()
        return page.takeIf { it.visibleNow } ?: run {
            page.close()
            null
        }
    }

    private fun synchronizeInputs() {
        val nextSource = SourceStamp.of(uiState())
        val nextWindow = windowVisibility()
        synchronized(lock) {
            if (observedSource != nextSource) {
                observedSource = nextSource
                sourceIdentity = Any()
            }
            if (observedWindow != nextWindow) {
                observedWindow = nextWindow
                windowIdentity = Any()
            }
        }
        currentPage?.takeUnless { it.visibleNow }?.close()
        // A collector can resume inline. Publish only after all outer bookkeeping locks and
        // retirement callbacks finish, using the current identity after any callback re-entry.
        mutableSourceEpoch.value = sourceIdentity
        mutableWindowEpoch.value = windowIdentity
    }

    private fun ownsActivity(): Boolean {
        val exact = activityBinding ?: return false
        return !closed && exact.isCurrent && activityPresentation() === exact
    }

    private fun canBind(kind: ProbePresentedPageKind, selection: SelectionBinding): Boolean {
        val state = uiState()
        return ownsActivity() && windowVisibility().visible && observedWindow == windowVisibility() &&
            observedSource == SourceStamp.of(state) && selection.isCurrent && selection.selectedNow(state.page) &&
            kind.matches(state.page) && (kind != ProbePresentedPageKind.LYRICS || validLyricsMedia(state.playback))
    }

    private fun refreshLyricsRequest() {
        val page = currentPage ?: return
        val saved = resources.savedAppearance
        val enabled = saved.settings != null && saved.issue == null && saved.settings.options.lyricsKeepBright
        page.lyricsLease?.update(lyricsKeepBrightRequested(enabled,
            page.kind == ProbePresentedPageKind.LYRICS && page.visibleNow, uiState().playback))
    }

    /** An old Activity's close only retires its exact slots; it cannot reset the successor's VMs. */
    override fun close() {
        if (closed) return
        closed = true
        currentSelection?.close()
        currentPage?.close()
    }

    inner class SelectionBinding internal constructor(
        private val readActual: () -> ProbePageSelectionSnapshot,
        initial: ProbePageSelectionSnapshot,
    ) : AutoCloseable {
        private val open = AtomicBoolean(true)
        @Volatile private var observed = initial
        @Volatile private var epochIdentity: Any = Any()
        private val mutableEpoch = MutableStateFlow(epochIdentity)
        val epoch = mutableEpoch.asStateFlow()
        internal val currentEpoch: Any get() = epochIdentity
        val identity: Any get() = this
        val isCurrent: Boolean get() = open.get() && !closed && currentSelection === this && ownsActivity()

        /** Call on actual current/settled/scrolling transitions, including a drag that returns. */
        fun refresh() {
            if (!isCurrent) return
            val actual = readActual()
            if (actual != observed) {
                observed = actual
                epochIdentity = Any()
            }
            currentPage?.takeIf { it.selection === this && !it.visibleNow }?.close()
            mutableEpoch.value = epochIdentity
        }

        /**
         * The actual pager/route container calls this before a new motion or programmatic jump.
         * An observed Boolean round trip alone cannot identify a missed hidden interval.
         */
        fun invalidate() {
            if (!isCurrent) return
            epochIdentity = Any()
            currentPage?.takeIf { it.selection === this }?.close()
            mutableEpoch.value = epochIdentity
        }

        internal fun selectedNow(route: ProbePage): Boolean {
            if (!isCurrent) return false
            val actual = readActual()
            return actual == observed && actual.route == route && actual.selected
        }

        override fun close() {
            if (!open.compareAndSet(true, false)) return
            synchronized(lock) { if (currentSelection === this) currentSelection = null }
            currentPage?.takeIf { it.selection === this }?.close()
        }
    }

    inner class PageBinding internal constructor(
        val kind: ProbePresentedPageKind,
        private val source: SourceStamp,
        private val activity: ProbeUiPresentation.Binding,
        internal val selection: SelectionBinding,
        val selectionEpoch: Any,
        val sourceEpoch: Any,
        val windowEpoch: Any,
    ) : AutoCloseable {
        private val open = AtomicBoolean(true)
        @Volatile private var font: FontRegistration? = null
        @Volatile private var background: BackgroundBinding? = null
        @Volatile private var uiWork: UiWorkRegistration? = null
        @Volatile internal var lyricsLease: LyricsLease? = null
        val identity: Any get() = this
        val epoch: Any get() = this
        val route: ProbePage get() = source.route

        /** No runtime monitor or Appearance VM call: the font owner may evaluate this under its lock. */
        val visibleNow: Boolean get() = open.get() && !closed && currentPage === this && ownsActivity() &&
            activityBinding === activity && windowVisibility().visible && observedWindow == windowVisibility() &&
            windowIdentity === windowEpoch && sourceIdentity === sourceEpoch && SourceStamp.of(uiState()) == source &&
            selection.isCurrent && selection.currentEpoch === selectionEpoch && selection.selectedNow(route)
        val isCurrent: Boolean get() = visibleNow

        internal fun ownsFont(exact: FontRegistration): Boolean = font === exact
        internal fun detachFont(exact: FontRegistration) = synchronized(lock) { if (font === exact) font = null }
        internal fun ownsBackground(exact: BackgroundBinding): Boolean = background === exact
        internal fun detachBackground(exact: BackgroundBinding) = synchronized(lock) {
            if (background === exact) background = null
        }
        internal fun ownsUiWork(exact: UiWorkRegistration): Boolean = uiWork === exact
        internal fun detachUiWork(exact: UiWorkRegistration) = synchronized(lock) {
            if (uiWork === exact) uiWork = null
        }

        /** The host registers before coverage/native work and owns one exact binding and caller Job. */
        fun installFont(
            role: ProbeFontSlot,
            releaseMemory: () -> Unit,
            cancelRetiredWork: () -> Unit,
        ): FontRegistration? {
            if (!visibleNow || !kind.accepts(role)) return null
            font?.close()
            return synchronized(lock) {
                if (!visibleNow || font != null) null else FontRegistration(this, role,
                    releaseMemory, cancelRetiredWork).also { font = it }
            }
        }

        /** The actual surface must pass its model; a mismatched model cannot steal this slot. */
        fun bindBackground(model: PlaybackBackgroundViewModel? = null): BackgroundBinding? {
            if (!visibleNow || kind !in BACKGROUND_KINDS ||
                model != null && !resources.ownsBackgroundModel(model)) return null
            background?.close()
            val exact = synchronized(lock) {
                if (!visibleNow || background != null) return null
                BackgroundBinding(this).also { background = it }
            }
            if (!resources.setBackgroundVisible(exact.owner, true) || !exact.isCurrent) {
                exact.close()
                return null
            }
            return exact
        }

        /**
         * Register the exact Entry or local-metadata ticket before its observer/context work starts.
         * One fixed slot covers playback lyrics/options/offset/association and metadata lookup;
         * font memory callbacks cannot own cancellation of this separate work.
         */
        fun installUiWork(closeExact: () -> Unit): UiWorkRegistration? {
            if (!visibleNow || route !in UI_WORK_ROUTES) return null
            uiWork?.close()
            return synchronized(lock) {
                if (!visibleNow || uiWork != null) null else UiWorkRegistration(this, closeExact).also { uiWork = it }
            }
        }

        override fun close() {
            val retired = synchronized(lock) {
                if (!open.compareAndSet(true, false)) return
                if (currentPage === this) currentPage = null
                RetiredSlots(font, background, lyricsLease, uiWork).also {
                    font = null
                    background = null
                    lyricsLease = null
                    uiWork = null
                }
            }
            // Visibility/identity was revoked first. All drops finish before this call returns.
            try { retired.font?.releaseMemory() }
            finally {
                try { retired.lyrics?.close() }
                finally {
                    // Background and Entry ticket close can cancel Jobs. Neither runs inside a
                    // runtime monitor or a font memory-only callback, even if another drop fails.
                    try { retired.background?.close() }
                    finally {
                        try { retired.uiWork?.close() }
                        finally { retired.font?.cancelRetiredWork() }
                    }
                }
            }
        }
    }

    inner class UiWorkRegistration internal constructor(
        private val page: PageBinding,
        private val closeExact: () -> Unit,
    ) : AutoCloseable {
        private val open = AtomicBoolean(true)
        val identity: Any get() = this
        val isCurrent: Boolean get() = open.get() && page.ownsUiWork(this) && page.visibleNow

        /** Only closes the captured ticket; a delayed dispose cannot hide its successor. */
        override fun close() {
            if (!open.compareAndSet(true, false)) return
            page.detachUiWork(this)
            closeExact()
        }
    }

    /** Memory close may run under Appearance's editor lock; cancellation is a separate exact phase. */
    inner class FontRegistration internal constructor(
        private val page: PageBinding,
        val role: ProbeFontSlot,
        private val release: () -> Unit,
        private val cancel: () -> Unit,
    ) : AutoCloseable {
        private val phase = AtomicInteger(FONT_OPEN)
        private val retirementLock = Any()
        val identity: Any get() = this
        val isCurrent: Boolean get() = phase.get() == FONT_OPEN && page.ownsFont(this) && page.visibleNow

        fun releaseMemory() {
            synchronized(retirementLock) {
                if (!phase.compareAndSet(FONT_OPEN, FONT_RETIRING)) return
                page.detachFont(this)
                try { release() }
                finally { phase.set(FONT_RETIRED) }
            }
        }

        fun cancelRetiredWork() {
            synchronized(retirementLock) {
                if (!phase.compareAndSet(FONT_RETIRED, FONT_CANCELLED)) return
            }
            try { cancel() }
            finally { resources.cancelRetiredFonts() }
        }

        /** Use outside the editor monitor. Preview binds releaseMemory and cancelRetiredWork separately. */
        override fun close() {
            try { releaseMemory() }
            finally { cancelRetiredWork() }
        }
    }

    inner class BackgroundBinding internal constructor(private val page: PageBinding) : AutoCloseable {
        private val open = AtomicBoolean(true)
        val owner: Any = Any()
        val isCurrent: Boolean get() = open.get() && page.ownsBackground(this) && page.visibleNow

        fun updateScene(scene: BackgroundScene): Boolean = isCurrent && resources.updateBackground(owner, scene)

        fun isPresented(display: PlaybackBackgroundDisplay, viewport: BackgroundViewport?): Boolean =
            isCurrent && resources.isBackgroundPresented(owner, display, viewport)

        override fun close() {
            if (!open.compareAndSet(true, false)) return
            page.detachBackground(this)
            resources.setBackgroundVisible(owner, false)
        }
    }

    private data class RetiredSlots(
        val font: FontRegistration?,
        val background: BackgroundBinding?,
        val lyrics: LyricsLease?,
        val uiWork: UiWorkRegistration?,
    )

    internal data class SourceStamp(val route: ProbePage, val mediaId: String?, val validMedia: Boolean?) {
        companion object {
            fun of(state: ProbeUiState): SourceStamp = if (state.page == ProbePage.PLAYBACK || state.page in UI_WORK_ROUTES) {
                SourceStamp(state.page, state.playback.mediaId, validLyricsMedia(state.playback))
            } else SourceStamp(state.page, null, null)
        }
    }

    private companion object {
        const val FONT_OPEN = 0
        const val FONT_RETIRING = 1
        const val FONT_RETIRED = 2
        const val FONT_CANCELLED = 3
        val BACKGROUND_KINDS = setOf(ProbePresentedPageKind.PLAYBACK, ProbePresentedPageKind.LYRICS,
            ProbePresentedPageKind.APPEARANCE)
        val LYRICS_ENTRY_ROUTES = setOf(ProbePage.LYRICS, ProbePage.LYRICS_OPTIONS,
            ProbePage.LYRICS_OFFSET, ProbePage.LYRICS_ASSOCIATION)
        val UI_WORK_ROUTES = LYRICS_ENTRY_ROUTES + setOf(ProbePage.LOCAL_LYRICS_LOOKUP, ProbePage.LOCAL_ARTWORK_LOOKUP)

        fun validLyricsMedia(playback: ProbePlaybackState): Boolean = lyricsKeepBrightRequested(true, true, playback)

        fun ProbePresentedPageKind.matches(route: ProbePage): Boolean = when (this) {
            ProbePresentedPageKind.PLAYBACK -> route == ProbePage.PLAYBACK
            ProbePresentedPageKind.LYRICS -> route == ProbePage.LYRICS
            ProbePresentedPageKind.APPEARANCE -> route == ProbePage.APPEARANCE
            ProbePresentedPageKind.LYRICS_FONT -> route == ProbePage.LYRICS_FONTS
            ProbePresentedPageKind.THEME_UI -> route != ProbePage.PLAYBACK && route != ProbePage.LYRICS &&
                route != ProbePage.APPEARANCE && route != ProbePage.LYRICS_FONTS
        }

        fun ProbePresentedPageKind.accepts(role: ProbeFontSlot): Boolean = when (this) {
            ProbePresentedPageKind.LYRICS -> role == ProbeFontSlot.PERSONAL_LYRICS
            ProbePresentedPageKind.LYRICS_FONT -> role == ProbeFontSlot.EDITOR
            ProbePresentedPageKind.APPEARANCE -> role == ProbeFontSlot.THEME_UI || role == ProbeFontSlot.THEME_PREVIEW
            ProbePresentedPageKind.PLAYBACK, ProbePresentedPageKind.THEME_UI -> role == ProbeFontSlot.THEME_UI
        }
    }
}
