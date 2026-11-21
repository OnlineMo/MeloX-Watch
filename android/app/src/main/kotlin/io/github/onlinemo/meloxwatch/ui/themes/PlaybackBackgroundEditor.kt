package io.github.onlinemo.meloxwatch.ui.themes

import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.themes.BackgroundCrop
import io.github.onlinemo.meloxwatch.data.themes.CustomBackgroundImportRequest
import io.github.onlinemo.meloxwatch.data.themes.CustomBackgroundPreview
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundException
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundImageKey
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundIssue
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundLimits
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundOptions
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundSaveResult
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundSettings
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundSnapshot
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundStore
import io.github.onlinemo.meloxwatch.data.themes.ThemeBackgroundMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class BackgroundEditorOperation { IDLE, READ, IMPORT_IMAGE, SAVE }

data class PlaybackBackgroundEditorState(
    val saved: PlaybackBackgroundSnapshot = PlaybackBackgroundSnapshot(null),
    val draft: PlaybackBackgroundOptions = PlaybackBackgroundOptions(),
    val preview: CustomBackgroundPreview? = null,
    val visible: Boolean = false,
    val operation: BackgroundEditorOperation = BackgroundEditorOperation.IDLE,
    val requiresRead: Boolean = true,
    val issue: PlaybackBackgroundIssue? = null,
) {
    val busy: Boolean get() = operation != BackgroundEditorOperation.IDLE
    val dirty: Boolean get() = draft != saved.effective
    val editable: Boolean get() = visible && !busy && !requiresRead && saved.settings != null
    val canConfirm: Boolean get() = editable && dirty && saved.settings?.revision != Long.MAX_VALUE &&
        (draft.mode != ThemeBackgroundMode.CUSTOM_IMAGE || draft.customImage != null)

    fun customImageKey(): PlaybackBackgroundImageKey.Custom? {
        val image = draft.customImage ?: return null
        preview?.takeIf { it.image == image }?.let { return PlaybackBackgroundImageKey.Custom(image, previewToken = it.token) }
        val actual = saved.settings ?: return null
        if (actual.options.customImage != image) return null
        return PlaybackBackgroundImageKey.Custom(image, settingsRevision = actual.revision)
    }
}

/** Preview is a draft. Only confirm performs CAS; failed/uncertain saves read actual bytes. */
class PlaybackBackgroundEditor(
    private val scope: CoroutineScope,
    private val io: LocalProviderIo,
    private val store: PlaybackBackgroundStore,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false
    private var generation = 0L
    private var active: EditorTask? = null
    private var readRequested = false
    private var preserveDraft = false
    private var pendingSave: SaveIntent? = null
    private val mutableState = MutableStateFlow(PlaybackBackgroundEditorState())
    val state: StateFlow<PlaybackBackgroundEditorState> = mutableState.asStateFlow()

    fun open() {
        synchronized(lock) {
            if (closed) return
            mutableState.value = mutableState.value.copy(visible = true, requiresRead = true)
            readRequested = true
        }
        scheduleRead()
    }

    fun setVisible(value: Boolean) {
        if (value) { open(); return }
        val cancel = synchronized(lock) {
            if (closed || !mutableState.value.visible) return
            generation++
            readRequested = false
            mutableState.value = mutableState.value.copy(visible = false,
                requiresRead = mutableState.value.requiresRead || active != null,
                operation = BackgroundEditorOperation.IDLE)
            active?.job
        }
        cancel?.cancel()
    }

    fun retryRead() {
        synchronized(lock) {
            if (closed || !mutableState.value.visible) return
            mutableState.value = mutableState.value.copy(requiresRead = true)
            readRequested = true
        }
        scheduleRead()
    }

    fun changeMode(mode: ThemeBackgroundMode): Boolean = change { it.copy(mode = mode) }

    fun changeBlur(level: Int): Boolean {
        if (level !in 0..PlaybackBackgroundLimits.BLUR_MAX) return invalidEdit()
        synchronized(lock) { if (!mutableState.value.draft.supportsBlur) return invalidEdit() }
        return change { it.copy(blurLevel = level) }
    }

    fun changeMask(percent: Int): Boolean = if (percent in 0..100) change { it.copy(darkMaskPercent = percent) } else invalidEdit()

    fun changeCrop(crop: BackgroundCrop): Boolean = change { it.copy(crop = crop) }

    /** Reset keeps the accepted managed source available for a later custom-mode selection. */
    fun resetDefault(): Boolean {
        val token = synchronized(lock) {
            if (!canEdit()) return false
            val current = mutableState.value
            mutableState.value = current.copy(draft = PlaybackBackgroundOptions(customImage = current.saved.effective.customImage),
                preview = null, issue = null)
            preserveDraft = true
            current.preview?.token
        }
        token?.let(store::retirePreview)
        return true
    }

    fun importImage(request: CustomBackgroundImportRequest): Boolean {
        var retired: String? = null
        val job = synchronized(lock) {
            if (!canEdit()) return false
            val current = mutableState.value
            retired = current.preview?.token
            val draft = if (current.preview?.image == current.draft.customImage) {
                current.draft.copy(customImage = current.saved.effective.customImage)
            } else current.draft
            mutableState.value = current.copy(draft = draft, preview = null, issue = null)
            preserveDraft = true
            begin(BackgroundEditorOperation.IMPORT_IMAGE) { ticket ->
                var result: CustomBackgroundPreview? = null
                try {
                    result = onceWhenIdle { store.prepareImage(request) }
                    currentCoroutineContext().ensureActive()
                    synchronized(lock) {
                        if (accepts(ticket)) {
                            val actual = mutableState.value
                            val candidate = checkNotNull(result)
                            val stale = actual.saved.settings?.revision != candidate.expectedRevision
                            mutableState.value = actual.copy(
                                draft = actual.draft.copy(mode = ThemeBackgroundMode.CUSTOM_IMAGE, customImage = candidate.image),
                                preview = candidate, requiresRead = stale,
                                issue = if (stale) PlaybackBackgroundIssue.SETTINGS_CONFLICT else null,
                            )
                            if (stale) readRequested = true
                            result = null
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { fail(ticket, backgroundRenderIssue(error)) }
                catch (error: OutOfMemoryError) { fail(ticket, PlaybackBackgroundIssue.LOW_MEMORY) }
                finally { result?.let { store.retirePreview(it.token) } }
            }
        }
        retired?.let(store::retirePreview)
        job.start()
        return true
    }

    fun confirm(): Boolean {
        val job = synchronized(lock) {
            if (closed || !scope.isActive || active != null || !mutableState.value.canConfirm) return false
            val current = mutableState.value
            val actual = checkNotNull(current.saved.settings)
            val token = current.preview?.takeIf { it.image == current.draft.customImage }?.token
            val intent = SaveIntent(actual, current.draft, token)
            pendingSave = intent
            mutableState.value = current.copy(requiresRead = true, issue = null)
            begin(BackgroundEditorOperation.SAVE) { ticket -> save(ticket, intent) }
        }
        job.start()
        return true
    }

    /** Cancellation never writes the former value over a save that already became durable. */
    fun cancel() {
        val retired = synchronized(lock) {
            if (closed) return
            generation++
            readRequested = false
            preserveDraft = false
            pendingSave?.explicitlyCancelled = true
            val current = mutableState.value
            mutableState.value = current.copy(draft = current.saved.effective, preview = null,
                operation = BackgroundEditorOperation.IDLE, requiresRead = current.requiresRead || active != null,
                issue = current.saved.issue)
            current.preview?.token to active?.job
        }
        retired.first?.let(store::retirePreview)
        retired.second?.cancel()
    }

    override fun close() {
        val retired = synchronized(lock) {
            if (closed) return
            closed = true
            generation++
            readRequested = false
            val result = mutableState.value.preview?.token to active?.job
            mutableState.value = PlaybackBackgroundEditorState()
            result
        }
        retired.first?.let(store::retirePreview)
        retired.second?.cancel()
    }

    private fun change(replacement: (PlaybackBackgroundOptions) -> PlaybackBackgroundOptions): Boolean = synchronized(lock) {
        if (!canEdit()) return false
        val current = mutableState.value
        val draft = replacement(current.draft)
        preserveDraft = true
        mutableState.value = current.copy(draft = draft,
            issue = if (draft.mode == ThemeBackgroundMode.CUSTOM_IMAGE && draft.customImage == null) PlaybackBackgroundIssue.NO_IMAGE else null)
        true
    }

    private fun invalidEdit(): Boolean = synchronized(lock) {
        if (!closed) mutableState.value = mutableState.value.copy(issue = PlaybackBackgroundIssue.INVALID_INPUT)
        false
    }

    private fun canEdit(): Boolean = !closed && active == null && scope.isActive && mutableState.value.editable

    private fun scheduleRead() {
        val job = synchronized(lock) {
            if (closed || !scope.isActive || !mutableState.value.visible || active != null || !readRequested) return
            readRequested = false
            val keep = preserveDraft
            begin(BackgroundEditorOperation.READ) { ticket ->
                try {
                    val actual = onceWhenIdle { store.read() }
                    currentCoroutineContext().ensureActive()
                    acceptRead(ticket, actual, keep)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { unreadable(ticket, backgroundRenderIssue(error)) }
                catch (error: OutOfMemoryError) { unreadable(ticket, PlaybackBackgroundIssue.LOW_MEMORY) }
            }
        }
        job.start()
    }

    /** Called under lock; the lazy job starts only after the caller leaves its short state lock. */
    private fun begin(operation: BackgroundEditorOperation, action: suspend (EditorTask) -> Unit): Job {
        check(active == null)
        generation++
        val ticket = EditorTask(generation)
        val job = scope.launch(start = CoroutineStart.LAZY) { action(ticket) }
        ticket.job = job
        active = ticket
        mutableState.value = mutableState.value.copy(operation = operation)
        job.invokeOnCompletion {
            synchronized(lock) {
                if (active === ticket) {
                    active = null
                    mutableState.value = mutableState.value.copy(operation = BackgroundEditorOperation.IDLE)
                }
            }
            scheduleRead()
        }
        return job
    }

    private fun accepts(ticket: EditorTask): Boolean = !closed && mutableState.value.visible &&
        generation == ticket.generation && active === ticket

    private suspend fun save(ticket: EditorTask, intent: SaveIntent) {
        try {
            when (val result = onceWhenIdle { store.compareAndSet(intent.revision, intent.options, intent.previewToken) }) {
                is PlaybackBackgroundSaveResult.Saved -> {
                    if (!intent.matches(result.snapshot)) throw PlaybackBackgroundException(PlaybackBackgroundIssue.SAVE_FAILED)
                    acceptRead(ticket, result.snapshot, keepDraft = false)
                }
                is PlaybackBackgroundSaveResult.Conflict -> acceptConflict(ticket, result.snapshot)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { refreshAfterSave(ticket, intent, backgroundRenderIssue(error)) }
        catch (error: OutOfMemoryError) { refreshAfterSave(ticket, intent, PlaybackBackgroundIssue.LOW_MEMORY) }
    }

    private suspend fun refreshAfterSave(ticket: EditorTask, intent: SaveIntent, issue: PlaybackBackgroundIssue) {
        synchronized(lock) { if (pendingSave === intent) intent.failure = issue }
        try {
            val actual = onceWhenIdle { store.read() }
            currentCoroutineContext().ensureActive()
            acceptRead(ticket, actual, keepDraft = true)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { unreadable(ticket, backgroundRenderIssue(error)) }
        catch (error: OutOfMemoryError) { unreadable(ticket, PlaybackBackgroundIssue.LOW_MEMORY) }
    }

    private fun acceptRead(ticket: EditorTask, actual: PlaybackBackgroundSnapshot, keepDraft: Boolean) {
        var retired: String? = null
        synchronized(lock) {
            if (!accepts(ticket)) return
            val current = mutableState.value
            val intent = pendingSave
            val saved = intent?.matches(actual) == true
            val acceptedPreview = current.preview?.takeIf { it.image == actual.settings?.options?.customImage }
            retired = acceptedPreview?.token
            val preview = if (acceptedPreview == null) current.preview else null
            val draft = if (!keepDraft || saved) actual.effective else current.draft
            val issue = when {
                actual.settings == null -> actual.issue ?: PlaybackBackgroundIssue.RECOVERY_REQUIRED
                saved -> actual.issue
                intent?.explicitlyCancelled == true -> if (actual.settings == intent.previous) actual.issue else PlaybackBackgroundIssue.SETTINGS_CONFLICT
                intent != null -> intent.failure ?: if (actual.settings.revision == intent.revision) {
                    PlaybackBackgroundIssue.SAVE_FAILED
                } else PlaybackBackgroundIssue.SETTINGS_CONFLICT
                else -> actual.issue
            }
            mutableState.value = current.copy(saved = actual, draft = draft, preview = preview,
                requiresRead = actual.settings == null, issue = issue)
            preserveDraft = draft != actual.effective || preview != null
            if (actual.settings != null) pendingSave = null
        }
        retired?.let(store::retirePreview)
    }

    private fun acceptConflict(ticket: EditorTask, actual: PlaybackBackgroundSnapshot) {
        var retired: String? = null
        synchronized(lock) {
            if (!accepts(ticket)) return
            val current = mutableState.value
            val acceptedPreview = current.preview?.takeIf { it.image == actual.settings?.options?.customImage }
            retired = acceptedPreview?.token
            mutableState.value = current.copy(saved = actual,
                preview = if (acceptedPreview == null) current.preview else null,
                requiresRead = actual.settings == null, issue = PlaybackBackgroundIssue.SETTINGS_CONFLICT)
            preserveDraft = true
            pendingSave = null
        }
        retired?.let(store::retirePreview)
    }

    private fun fail(ticket: EditorTask, issue: PlaybackBackgroundIssue) = synchronized(lock) {
        if (accepts(ticket)) mutableState.value = mutableState.value.copy(issue = issue)
    }

    private fun unreadable(ticket: EditorTask, issue: PlaybackBackgroundIssue) = synchronized(lock) {
        if (accepts(ticket)) mutableState.value = mutableState.value.copy(
            saved = PlaybackBackgroundSnapshot(null, issue), requiresRead = true, issue = issue,
        )
    }

    private suspend fun <T> onceWhenIdle(action: suspend () -> T): T {
        try { return action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (backgroundRenderIssue(error) != PlaybackBackgroundIssue.BUSY) throw error
            val idle = withTimeoutOrNull(3_000) {
                io.awaitIdle()
                true
            } ?: false
            currentCoroutineContext().ensureActive()
            if (!idle) throw error
        }
        return action()
    }

    private class EditorTask(val generation: Long) { var job: Job? = null }
    private class SaveIntent(val previous: PlaybackBackgroundSettings, val options: PlaybackBackgroundOptions, val previewToken: String?) {
        val revision = previous.revision
        var failure: PlaybackBackgroundIssue? = null
        var explicitlyCancelled = false
        fun matches(snapshot: PlaybackBackgroundSnapshot): Boolean {
            val actual = snapshot.settings ?: return false
            return revision != Long.MAX_VALUE && actual.revision == revision + 1 && actual.options == options
        }
    }
}
