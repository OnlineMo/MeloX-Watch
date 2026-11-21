package io.github.onlinemo.meloxwatch.ui.themes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontLimits
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.themes.AppLanguage
import io.github.onlinemo.meloxwatch.data.themes.AppearanceIssue
import io.github.onlinemo.meloxwatch.data.themes.AppearanceOptions
import io.github.onlinemo.meloxwatch.data.themes.AppearancePreferencesStore
import io.github.onlinemo.meloxwatch.data.themes.AppearanceSaveResult
import io.github.onlinemo.meloxwatch.data.themes.AppearanceSnapshot
import io.github.onlinemo.meloxwatch.data.themes.BackgroundCrop
import io.github.onlinemo.meloxwatch.data.themes.BackgroundImageRef
import io.github.onlinemo.meloxwatch.data.themes.CustomBackgroundImportRequest
import io.github.onlinemo.meloxwatch.data.themes.CustomBackgroundPreview
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundException
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundStore
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundImageKey
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundIssue
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundOptions
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundSaveResult
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundSnapshot
import io.github.onlinemo.meloxwatch.data.themes.ThemeApplyRequest
import io.github.onlinemo.meloxwatch.data.themes.ThemeBackgroundMode
import io.github.onlinemo.meloxwatch.data.themes.ThemeChoice
import io.github.onlinemo.meloxwatch.data.themes.ThemeException
import io.github.onlinemo.meloxwatch.data.themes.ThemeImportRequest
import io.github.onlinemo.meloxwatch.data.themes.ThemeIssue
import io.github.onlinemo.meloxwatch.data.themes.ThemePreview
import io.github.onlinemo.meloxwatch.data.themes.ThemeRef
import io.github.onlinemo.meloxwatch.data.themes.ThemeSnapshot
import io.github.onlinemo.meloxwatch.data.themes.ThemeSelectionStore
import io.github.onlinemo.meloxwatch.data.themes.ReceivedThemeSummary
import io.github.onlinemo.meloxwatch.data.themes.TransferredThemeCoordinator
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class AppearanceOperation { IDLE, READ, THEME_PREVIEW, IMAGE_IMPORT, APPLY_THEME, SAVE_BACKGROUND, SAVE_APPEARANCE }
enum class AppearancePicker { THEME, IMAGE }
data class AppearancePickerTicket(val id: String, val picker: AppearancePicker)

data class AppearanceEditorState(
    val appearance: AppearanceSnapshot = AppearanceSnapshot(null),
    val theme: ThemeSnapshot,
    val background: PlaybackBackgroundSnapshot = PlaybackBackgroundSnapshot(null),
    val draftAppearance: AppearanceOptions = AppearanceOptions(),
    val draftBackground: PlaybackBackgroundOptions = PlaybackBackgroundOptions(),
    val appearanceBaseRevision: Long? = null,
    val backgroundBaseRevision: Long? = null,
    val themePreview: ThemePreview? = null,
    val imagePreview: CustomBackgroundPreview? = null,
    val replacement: ThemeRef? = null,
    val fontLicenseAccepted: Boolean = false,
    val foreground: Boolean = false,
    val editorVisible: Boolean = false,
    val previewing: Boolean = false,
    val operation: AppearanceOperation = AppearanceOperation.IDLE,
    val requiresRead: Boolean = true,
    val appearanceIssue: AppearanceIssue? = null,
    val themeIssue: ThemeIssue? = null,
    val backgroundIssue: PlaybackBackgroundIssue? = null,
    val picker: AppearancePickerTicket? = null,
    val pickerResultPending: Boolean = false,
    val pickerUnavailable: AppearancePicker? = null,
    val needsImageSelection: Boolean = false,
    val needsThemeSelection: Boolean = false,
    val savedFeedback: AppearanceOperation? = null,
    val receivedTheme: ReceivedThemeSummary? = null,
    val discardReceivedTheme: ReceivedThemeSummary? = null,
) {
    val busy: Boolean get() = operation != AppearanceOperation.IDLE
    val editable: Boolean get() = foreground && editorVisible && !busy && !requiresRead && picker == null
    val appearanceDirty: Boolean get() = draftAppearance != appearance.effective
    val backgroundDirty: Boolean get() = draftBackground != background.effective
    val canSaveAppearance: Boolean get() = editable && appearance.canWrite && appearanceDirty &&
        appearanceBaseRevision == appearance.settings?.revision && appearanceBaseRevision != Long.MAX_VALUE
    val canSaveBackground: Boolean get() = editable && background.settings != null && backgroundDirty &&
        backgroundBaseRevision == background.settings.revision && backgroundBaseRevision != Long.MAX_VALUE &&
        (draftBackground.mode != ThemeBackgroundMode.CUSTOM_IMAGE || !needsImageSelection && draftBackground.customImage != null)
    val canApplyTheme: Boolean get() = editable && themePreview != null &&
        themePreview.expectedRevision == theme.revision &&
        (themePreview.sameIdInstallations.isEmpty() || replacement in themePreview.sameIdInstallations) &&
        (themePreview.manifest.fonts.isEmpty() || fontLicenseAccepted)

    fun customImageKey(useDraft: Boolean): PlaybackBackgroundImageKey.Custom? {
        val options = if (useDraft) draftBackground else background.effective
        val image = options.customImage ?: return null
        if (useDraft) imagePreview?.takeIf { it.image == image }?.let {
            return PlaybackBackgroundImageKey.Custom(image, previewToken = it.token)
        }
        val saved = background.settings ?: return null
        if (saved.options.customImage != image) return null
        return PlaybackBackgroundImageKey.Custom(image, settingsRevision = saved.revision)
    }
}

/** One admitted task, one pending explicit read and one in-memory picker return; no command replay. */
class AppearanceViewModel(
    private val appearance: AppearancePreferencesStore,
    private val themes: ThemeSelectionStore,
    private val images: PlaybackBackgroundStore,
    private val io: LocalProviderIo,
    private val savedState: SavedStateHandle,
    scope: CoroutineScope? = null,
    private val receivedThemes: TransferredThemeCoordinator? = null,
) : ViewModel() {
    private val workScope = scope ?: viewModelScope
    private val lock = Any()
    private var closed = false
    private var generation = 0L
    private var active: Task? = null
    private var readRequested = false
    private var pendingPicker: PickedInput? = null
    private var themePreviewPresentation: PreviewBinding? = null
    private var retiredThemePreviewPresentation: PreviewBinding? = null
    private val restoredAppearanceDraft = restoredAppearance()
    private val restoredBackgroundDraft = restoredBackground()
    private var preserveAppearanceDraft = restoredAppearanceDraft != null
    private var preserveBackgroundDraft = restoredBackgroundDraft != null
    private val mutableState = MutableStateFlow(AppearanceEditorState(
        theme = themes.state.value,
        draftAppearance = restoredAppearanceDraft?.second ?: AppearanceOptions(),
        appearanceBaseRevision = restoredAppearanceDraft?.first,
        draftBackground = restoredBackgroundDraft?.options ?: PlaybackBackgroundOptions(),
        backgroundBaseRevision = restoredBackgroundDraft?.revision,
        needsImageSelection = restoredBackgroundDraft?.needsImageSelection ?: false,
        needsThemeSelection = savedState.get<Any>(KEY_THEME_SELECTION) as? Boolean == true,
    ))
    val state = mutableState.asStateFlow()

    /** Memory-only bindings need no provider cancellation callback. */
    fun bindThemePreviewPresentation(token: String, releaseMemory: () -> Unit): AutoCloseable? =
        bindThemePreviewPresentation(token, releaseMemory, {})

    /**
     * [releaseMemory] drops this binding's image lease and closes its precise font binding
     * under the editor lock. It must not throw, do I/O, cancel a Job or re-enter this VM.
     * [cancelRetiredWork] consumes work captured by that retirement immediately after the
     * outermost editor lock is released. CancellationSignal listeners can run synchronously.
     * Admission is external: a memory callback cannot recursively bind a successor. This
     * keeps retirement to one exact binding per editor turn, with no retirement queue.
     */
    fun bindThemePreviewPresentation(
        token: String,
        releaseMemory: () -> Unit,
        cancelRetiredWork: () -> Unit,
    ): AutoCloseable? {
        if (Thread.holdsLock(lock)) return null
        return withEditorLock {
            if (!themePreviewVisible(token)) return null
            retireThemePreviewPresentationLocked()
            val binding = PreviewBinding(token, releaseMemory, cancelRetiredWork) { closing ->
                withEditorLock { retireThemePreviewBindingLocked(closing) }
            }
            themePreviewPresentation = binding
            binding
        }
    }

    /** Identity is checked as well as token: an old same-token composition cannot revive. */
    fun isThemePreviewPresented(binding: AutoCloseable?): Boolean = withEditorLock {
        themePreviewPresented(binding)
    }

    /**
     * Admission/publication and memory retirement share the lock. [updateMemory] is bounded
     * memory work only; it must not suspend, cancel provider work or recursively admit a binding.
     */
    fun updateThemePreviewPresentation(binding: AutoCloseable?, updateMemory: () -> Unit): Boolean = withEditorLock {
        if (!themePreviewPresented(binding)) return false
        updateMemory()
        true
    }

    /** Root may call this after the sole Window source becomes hidden/unfocused/locked. */
    fun retireThemePreviewPresentation() = withEditorLock {
        retireThemePreviewPresentationLocked()
    }

    private fun themePreviewPresented(binding: AutoCloseable?): Boolean {
        val current = themePreviewPresentation ?: return false
        return current === binding && current.isOpen && themePreviewVisible(current.token)
    }

    private fun themePreviewVisible(token: String): Boolean {
        val current = mutableState.value
        return !closed && workScope.isActive && active == null && current.foreground && current.editorVisible &&
            current.previewing && !current.requiresRead && current.picker == null && current.themePreview?.token == token
    }

    private fun retireThemePreviewPresentationLocked() {
        val previous = themePreviewPresentation ?: return
        retireThemePreviewBindingLocked(previous)
    }

    private fun retireThemePreviewBindingLocked(binding: PreviewBinding) {
        if (!binding.markRetired()) return
        if (themePreviewPresentation === binding) themePreviewPresentation = null
        check(retiredThemePreviewPresentation == null) { "One presentation may retire per editor turn" }
        // Capture before the memory callback so finally still drains an unexpected callback failure.
        retiredThemePreviewPresentation = binding
        binding.releaseMemory()
    }

    fun setForeground(value: Boolean) {
        val cancelled = withEditorLock {
            if (closed || mutableState.value.foreground == value) return
            retireThemePreviewPresentationLocked()
            generation++
            val current = mutableState.value
            mutableState.value = current.copy(foreground = value, previewing = false,
                requiresRead = current.requiresRead || active != null, operation = AppearanceOperation.IDLE)
            readRequested = value
            active?.job
        }
        cancelled?.cancel()
        scheduleNext()
    }

    fun setEditorVisible(value: Boolean) {
        var retire: Pair<String?, String?> = null to null
        val cancelled = withEditorLock {
            if (closed || mutableState.value.editorVisible == value) return
            retireThemePreviewPresentationLocked()
            generation++
            val current = mutableState.value
            if (!value) retire = current.themePreview?.token to current.imagePreview?.token
            val keepAppearance = value && preserveAppearanceDraft
            val keepBackground = value && preserveBackgroundDraft
            if (!value) pendingPicker = null
            mutableState.value = current.copy(editorVisible = value, previewing = false,
                draftAppearance = if (keepAppearance) current.draftAppearance else current.appearance.effective,
                draftBackground = if (keepBackground) current.draftBackground else current.background.effective,
                appearanceBaseRevision = if (keepAppearance) current.appearanceBaseRevision else current.appearance.settings?.revision,
                backgroundBaseRevision = if (keepBackground) current.backgroundBaseRevision else current.background.settings?.revision,
                themePreview = if (value) current.themePreview else null,
                imagePreview = if (value) current.imagePreview else null,
                replacement = null, fontLicenseAccepted = false, picker = null, pickerResultPending = false,
                discardReceivedTheme = null,
                needsImageSelection = keepBackground && current.needsImageSelection,
                needsThemeSelection = value && current.needsThemeSelection,
                operation = AppearanceOperation.IDLE, requiresRead = true, savedFeedback = null)
            preserveAppearanceDraft = keepAppearance
            preserveBackgroundDraft = keepBackground
            readRequested = current.foreground
            persistDraft()
            active?.job
        }
        retire(retire)
        cancelled?.cancel()
        scheduleNext()
    }

    fun retryRead() {
        withEditorLock {
            if (closed || !mutableState.value.foreground) return
            readRequested = true
            mutableState.value = mutableState.value.copy(requiresRead = true)
        }
        scheduleNext()
    }

    fun changeAppearance(options: AppearanceOptions): Boolean = edit { it.copy(draftAppearance = options, appearanceIssue = null) }
    fun changeBackground(options: PlaybackBackgroundOptions): Boolean = edit {
        it.copy(draftBackground = options, backgroundIssue = if (options.mode == ThemeBackgroundMode.CUSTOM_IMAGE &&
            options.customImage == null) PlaybackBackgroundIssue.NO_IMAGE else null)
    }
    fun changeCrop(crop: BackgroundCrop): Boolean = edit {
        it.copy(draftBackground = it.draftBackground.copy(crop = crop), backgroundIssue = null)
    }
    fun acknowledgeFontLicense(value: Boolean): Boolean = edit { it.copy(fontLicenseAccepted = value) }
    fun chooseReplacement(ref: ThemeRef): Boolean {
        if (ref !in state.value.themePreview?.sameIdInstallations.orEmpty()) return false
        return edit { it.copy(replacement = ref) }
    }
    fun showPreview(value: Boolean): Boolean = edit { it.copy(previewing = value) }

    fun resetAppearanceDraft(): Boolean = changeAppearance(AppearanceOptions())
    fun resetBackgroundDraft(): Boolean {
        val token = withEditorLock {
            if (!canEdit()) return false
            val current = mutableState.value
            mutableState.value = current.copy(draftBackground = PlaybackBackgroundOptions(
                customImage = current.background.effective.customImage), imagePreview = null,
                needsImageSelection = false, backgroundIssue = null, savedFeedback = null)
            preserveBackgroundDraft = true
            persistDraft()
            current.imagePreview?.token
        }
        token?.let(images::retirePreview)
        return true
    }

    /** Review binds the retained draft to the newly read revision; it never retries a write. */
    fun reviewAppearanceDraft(): Boolean = withEditorLock {
        if (!mutableState.value.appearance.canWrite) return false
        edit { it.copy(appearanceBaseRevision = it.appearance.settings?.revision, appearanceIssue = it.appearance.issue) }
    }

    fun reviewBackgroundDraft(): Boolean = withEditorLock {
        if (mutableState.value.background.settings == null) return false
        edit { it.copy(backgroundBaseRevision = it.background.settings?.revision, backgroundIssue = it.background.issue) }
    }

    /** The ticket survives the picker-induced pause, but never navigation away or recreation. */
    fun requestPicker(picker: AppearancePicker): AppearancePickerTicket? = withEditorLock {
        if (!canEdit()) return null
        retireThemePreviewPresentationLocked()
        val ticket = AppearancePickerTicket(UUID.randomUUID().toString(), picker)
        mutableState.value = mutableState.value.copy(picker = ticket, pickerUnavailable = null, savedFeedback = null)
        ticket
    }

    fun pickerUnavailable(ticket: AppearancePickerTicket) = withEditorLock {
        if (pendingPicker == null && mutableState.value.picker == ticket) mutableState.value = mutableState.value.copy(
            picker = null, pickerResultPending = false, pickerUnavailable = ticket.picker)
    }

    fun pickerCancelled(ticket: AppearancePickerTicket) = withEditorLock {
        if (pendingPicker == null && mutableState.value.picker == ticket) mutableState.value = mutableState.value.copy(
            picker = null, pickerResultPending = false)
    }

    fun importPickedTheme(ticket: AppearancePickerTicket, uri: String): Boolean =
        offerPicker(PickedInput.Theme(ticket, uri))

    fun previewTheme(choice: ThemeChoice): Boolean {
        val job = withEditorLock {
            if (!canEdit()) return false
            prepareThemeLocked { themes.previewChoice(choice) }
        }
        job.start()
        return true
    }

    /** 收到的主题复用相同预览/本机批准入口，不从传输回执直接切换外观。 */
    fun previewReceivedTheme(operationId: String): Boolean {
        val received = receivedThemes ?: return false
        val job = withEditorLock {
            if (!canEdit() || mutableState.value.receivedTheme?.operationId != operationId) return false
            mutableState.value = mutableState.value.copy(discardReceivedTheme = null)
            prepareThemeLocked { received.preview(operationId) }
        }
        job.start()
        return true
    }

    fun requestDiscardReceivedTheme(operationId: String): Boolean {
        val received = mutableState.value.receivedTheme?.takeIf { it.operationId == operationId } ?: return false
        return edit { it.copy(discardReceivedTheme = received) }
    }

    fun cancelDiscardReceivedTheme(): Boolean = edit { it.copy(discardReceivedTheme = null) }

    /** 只删除经过本机确认的未应用接收副本，原文件与已安装主题由原所有者保留。 */
    fun confirmDiscardReceivedTheme(operationId: String): Boolean {
        val received = receivedThemes ?: return false
        val job = withEditorLock {
            val current = mutableState.value
            if (!canEdit() || current.discardReceivedTheme?.operationId != operationId ||
                current.receivedTheme?.operationId != operationId) return false
            retireThemePreviewPresentationLocked()
            current.themePreview?.token?.let(themes::retirePreview)
            mutableState.value = current.copy(themePreview = null, previewing = false, discardReceivedTheme = null)
            begin(AppearanceOperation.THEME_PREVIEW) { task ->
                try {
                    onceWhenIdle { received.discard(operationId) }
                    currentCoroutineContext().ensureActive()
                    withEditorLock {
                        if (!accepts(task, editor = true)) return@begin
                        mutableState.value = mutableState.value.copy(receivedTheme = received.pending.value,
                            theme = themes.state.value, themeIssue = null, needsThemeSelection = false)
                        persistDraft()
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { themeFailure(task, error) }
            }
        }
        job.start()
        return true
    }

    private fun prepareThemeLocked(action: suspend () -> ThemePreview): Job {
        retireThemePreviewPresentationLocked()
        val token = mutableState.value.themePreview?.token
        token?.let(themes::retirePreview)
        mutableState.value = mutableState.value.copy(themePreview = null, replacement = null,
            fontLicenseAccepted = false, themeIssue = null, needsThemeSelection = true)
        persistDraft()
        return begin(AppearanceOperation.THEME_PREVIEW) { task ->
            var preview: ThemePreview? = null
            try {
                preview = onceWhenIdle(action)
                currentCoroutineContext().ensureActive()
                withEditorLock {
                    if (!accepts(task, editor = true)) return@begin
                    mutableState.value = mutableState.value.copy(themePreview = preview, themeIssue = null,
                        theme = themes.state.value, needsThemeSelection = false, previewing = true)
                    preview = null
                    persistDraft()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { themeFailure(task, error) }
            catch (_: OutOfMemoryError) { themeFailure(task, ThemeException(ThemeIssue.LOW_MEMORY)) }
            finally { preview?.token?.let(themes::retirePreview) }
        }
    }

    fun importPickedImage(ticket: AppearancePickerTicket, uri: String, displayName: String): Boolean =
        offerPicker(PickedInput.Image(ticket, uri, displayName))

    private fun importImageLocked(uri: String, displayName: String): Job {
        mutableState.value.imagePreview?.token?.let(images::retirePreview)
        mutableState.value = mutableState.value.copy(imagePreview = null, backgroundIssue = null, needsImageSelection = true)
        preserveBackgroundDraft = true
        persistDraft()
        return begin(AppearanceOperation.IMAGE_IMPORT) { task ->
            var preview: CustomBackgroundPreview? = null
            try {
                preview = onceWhenIdle { images.prepareImage(CustomBackgroundImportRequest(
                    UUID.randomUUID().toString(), uri, displayName)) }
                currentCoroutineContext().ensureActive()
                withEditorLock {
                    if (!accepts(task, editor = true)) return@begin
                    val current = mutableState.value
                    mutableState.value = current.copy(imagePreview = preview,
                        draftBackground = current.draftBackground.copy(mode = ThemeBackgroundMode.CUSTOM_IMAGE,
                            customImage = checkNotNull(preview).image), backgroundIssue = null,
                        needsImageSelection = false)
                    preview = null
                    preserveBackgroundDraft = true
                    persistDraft()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { backgroundFailure(task, error) }
            catch (_: OutOfMemoryError) { backgroundFailure(task, io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundException(PlaybackBackgroundIssue.LOW_MEMORY)) }
            finally { preview?.token?.let(images::retirePreview) }
        }
    }

    fun applyTheme(): Boolean {
        val job = withEditorLock {
            val current = mutableState.value
            if (!canEdit() || !current.canApplyTheme) return false
            val preview = checkNotNull(current.themePreview)
            val request = ThemeApplyRequest(UUID.randomUUID().toString(), preview.token, preview.expectedRevision,
                current.replacement, if (current.fontLicenseAccepted) LyricsFontLimits.LICENSE_ACKNOWLEDGMENT_VERSION else null)
            begin(AppearanceOperation.APPLY_THEME) { task ->
                try {
                    val actual = onceWhenIdle { themes.apply(request) }
                    currentCoroutineContext().ensureActive()
                    withEditorLock {
                        if (!accepts(task, editor = true)) return@begin
                        mutableState.value = mutableState.value.copy(theme = actual.snapshot, themePreview = null,
                            replacement = null, fontLicenseAccepted = false, needsThemeSelection = false,
                            receivedTheme = receivedThemes?.pending?.value, discardReceivedTheme = null,
                            themeIssue = if (actual.cleanupRequired) ThemeIssue.CLEANUP_REQUIRED else actual.snapshot.issue,
                            savedFeedback = AppearanceOperation.APPLY_THEME)
                        persistDraft()
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { themeFailure(task, error)
                    readBackTheme(task) }
                catch (_: OutOfMemoryError) { themeFailure(task, ThemeException(ThemeIssue.LOW_MEMORY))
                    readBackTheme(task) }
            }
        }
        job.start()
        return true
    }

    fun saveAppearance(): Boolean {
        val job = withEditorLock {
            val current = mutableState.value
            if (!canEdit() || !current.canSaveAppearance) return false
            val expected = checkNotNull(current.appearanceBaseRevision)
            val options = current.draftAppearance
            begin(AppearanceOperation.SAVE_APPEARANCE) { task ->
                try {
                    when (val result = appearance.compareAndSet(expected, options)) {
                        is AppearanceSaveResult.Saved -> acceptAppearance(task, result.snapshot, saved = true)
                        is AppearanceSaveResult.Conflict -> acceptAppearance(task, result.snapshot, issue = AppearanceIssue.CONFLICT)
                        is AppearanceSaveResult.Failed -> acceptAppearance(task, result.snapshot, issue = result.issue)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { acceptAppearance(task, readAppearanceActual(), issue = AppearanceIssue.STORAGE_UNAVAILABLE) }
                catch (_: OutOfMemoryError) { acceptAppearance(task, readAppearanceActual(), issue = AppearanceIssue.SETTINGS_TOO_LARGE) }
            }
        }
        job.start()
        return true
    }

    fun recoverAppearanceDefaults(): Boolean {
        val job = withEditorLock {
            val current = mutableState.value
            val token = current.appearance.recoveryToken ?: return false
            if (!canEdit() || current.appearance.issue != AppearanceIssue.INVALID_SETTINGS) return false
            begin(AppearanceOperation.SAVE_APPEARANCE) { task ->
                when (val result = appearance.recoverDefaults(token)) {
                    is AppearanceSaveResult.Saved -> acceptAppearance(task, result.snapshot, saved = true)
                    is AppearanceSaveResult.Conflict -> acceptAppearance(task, result.snapshot, issue = AppearanceIssue.CONFLICT)
                    is AppearanceSaveResult.Failed -> acceptAppearance(task, result.snapshot, issue = result.issue)
                }
            }
        }
        job.start()
        return true
    }

    fun saveBackground(): Boolean {
        val job = withEditorLock {
            val current = mutableState.value
            if (!canEdit() || !current.canSaveBackground) return false
            val expected = checkNotNull(current.backgroundBaseRevision)
            val options = current.draftBackground.let {
                if (it.mode != ThemeBackgroundMode.CUSTOM_IMAGE && (it.customImage == null || current.needsImageSelection))
                    it.copy(customImage = current.background.effective.customImage) else it
            }
            val token = current.imagePreview?.takeIf { it.image == options.customImage }?.token
            begin(AppearanceOperation.SAVE_BACKGROUND) { task ->
                try {
                    when (val result = onceWhenIdle { images.compareAndSet(expected, options, token) }) {
                        is PlaybackBackgroundSaveResult.Saved -> acceptBackground(task, result.snapshot, saved = true)
                        is PlaybackBackgroundSaveResult.Conflict -> acceptBackground(task, result.snapshot,
                            issue = PlaybackBackgroundIssue.SETTINGS_CONFLICT)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { backgroundFailure(task, error)
                    readBackBackground(task) }
                catch (_: OutOfMemoryError) { backgroundFailure(task,
                    io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundException(PlaybackBackgroundIssue.LOW_MEMORY))
                    readBackBackground(task) }
            }
        }
        job.start()
        return true
    }

    /** Explicit cancellation never writes old settings over an already durable save. */
    fun cancelDraft() {
        val retired = withEditorLock {
            if (closed) return
            retireThemePreviewPresentationLocked()
            generation++
            val current = mutableState.value
            mutableState.value = current.copy(draftAppearance = current.appearance.effective,
                draftBackground = current.background.effective, themePreview = null, imagePreview = null,
                appearanceBaseRevision = current.appearance.settings?.revision,
                backgroundBaseRevision = current.background.settings?.revision,
                previewing = false, replacement = null, fontLicenseAccepted = false,
                picker = null, pickerResultPending = false, needsImageSelection = false, needsThemeSelection = false,
                discardReceivedTheme = null,
                operation = AppearanceOperation.IDLE,
                requiresRead = current.requiresRead || active != null, savedFeedback = null)
            pendingPicker = null
            preserveAppearanceDraft = false
            preserveBackgroundDraft = false
            readRequested = current.foreground && active != null
            persistDraft()
            Triple(current.themePreview?.token, current.imagePreview?.token, active?.job)
        }
        retire(retired.first to retired.second)
        retired.third?.cancel()
        scheduleNext()
    }

    private fun offerPicker(input: PickedInput): Boolean {
        withEditorLock {
            val current = mutableState.value
            if (closed || !workScope.isActive || !current.editorVisible || current.picker != input.ticket ||
                pendingPicker != null || input.ticket.picker != input.kind) return false
            if (input.uri.isBlank() || input.uri.length > MAX_PICKER_URI ||
                input is PickedInput.Image && input.displayName.length > MAX_IMAGE_NAME) {
                mutableState.value = current.copy(picker = null, pickerResultPending = false,
                    themeIssue = if (input.kind == AppearancePicker.THEME) ThemeIssue.INVALID_INPUT else current.themeIssue,
                    backgroundIssue = if (input.kind == AppearancePicker.IMAGE) PlaybackBackgroundIssue.INVALID_INPUT else current.backgroundIssue)
                return false
            }
            pendingPicker = input
            mutableState.value = current.copy(pickerResultPending = true)
        }
        scheduleNext()
        return true
    }

    private fun schedulePicker() {
        val job = withEditorLock {
            val current = mutableState.value
            val input = pendingPicker ?: return
            if (closed || !workScope.isActive || active != null || !current.foreground ||
                !current.editorVisible || current.requiresRead || current.picker != input.ticket) return
            // Admission and consumption share one lock. A preceding read cannot eat this result.
            mutableState.value = current.copy(picker = null, pickerResultPending = false)
            val admitted = when (input) {
                is PickedInput.Theme -> prepareThemeLocked {
                    themes.prepareImport(ThemeImportRequest(UUID.randomUUID().toString(), input.uri))
                }
                is PickedInput.Image -> importImageLocked(input.uri, input.displayName)
            }
            pendingPicker = null
            admitted
        }
        job.start()
    }

    private fun edit(change: (AppearanceEditorState) -> AppearanceEditorState): Boolean = withEditorLock {
        if (!canEdit()) return false
        val current = mutableState.value
        val changed = change(current)
        if (!changed.previewing) retireThemePreviewPresentationLocked()
        mutableState.value = changed.copy(savedFeedback = null)
        preserveAppearanceDraft = preserveAppearanceDraft || changed.draftAppearance != current.draftAppearance
        preserveBackgroundDraft = preserveBackgroundDraft || changed.draftBackground != current.draftBackground
        persistDraft()
        true
    }

    private fun canEdit(): Boolean = !closed && active == null && workScope.isActive && mutableState.value.editable

    private fun scheduleNext() {
        scheduleRead()
        schedulePicker()
    }

    private fun scheduleRead() {
        val job = withEditorLock {
            if (closed || !workScope.isActive || !mutableState.value.foreground || active != null || !readRequested) return
            readRequested = false
            begin(AppearanceOperation.READ) { task ->
                val a = readAppearanceActual()
                val t = readThemeActual()
                val b = readBackgroundActual()
                currentCoroutineContext().ensureActive()
                withEditorLock {
                    if (!accepts(task)) return@begin
                    val current = mutableState.value
                    val keepAppearance = current.editorVisible && preserveAppearanceDraft
                    val keepBackground = current.editorVisible && preserveBackgroundDraft
                    val draftImage = current.draftBackground.customImage
                    val missingDraftImage = keepBackground && draftImage != null && current.imagePreview?.image != draftImage &&
                        b.settings != null && b.effective.customImage != draftImage
                    mutableState.value = current.copy(appearance = a, theme = t, background = b,
                        receivedTheme = receivedThemes?.pending?.value, discardReceivedTheme = null,
                        draftAppearance = if (keepAppearance) current.draftAppearance else a.effective,
                        draftBackground = if (keepBackground) current.draftBackground else b.effective,
                        appearanceBaseRevision = if (keepAppearance) current.appearanceBaseRevision else a.settings?.revision,
                        backgroundBaseRevision = if (keepBackground) current.backgroundBaseRevision else b.settings?.revision,
                        needsImageSelection = keepBackground && (current.needsImageSelection || missingDraftImage),
                        requiresRead = false, appearanceIssue = if (keepAppearance && a.settings != null && current.appearanceBaseRevision != a.settings.revision)
                            AppearanceIssue.CONFLICT else a.issue,
                        themeIssue = t.issue, backgroundIssue = if (keepBackground && b.settings != null && current.backgroundBaseRevision != b.settings.revision)
                            PlaybackBackgroundIssue.SETTINGS_CONFLICT else b.issue)
                    persistDraft()
                }
            }
        }
        job.start()
    }

    private fun begin(operation: AppearanceOperation, action: suspend (Task) -> Unit): Job {
        check(active == null)
        retireThemePreviewPresentationLocked()
        generation++
        val task = Task(generation, operation)
        val job = workScope.launch(start = CoroutineStart.LAZY) {
            try {
                action(task)
                currentCoroutineContext().ensureActive()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { operationFailure(task, error) }
            catch (_: OutOfMemoryError) { operationFailure(task, lowMemory = true) }
        }
        task.job = job
        active = task
        mutableState.value = mutableState.value.copy(operation = operation, savedFeedback = null)
        job.invokeOnCompletion { failure ->
            withEditorLock {
                if (active === task) {
                    active = null
                    val current = mutableState.value
                    mutableState.value = current.copy(operation = AppearanceOperation.IDLE,
                        requiresRead = current.requiresRead || failure != null && generation == task.generation)
                }
            }
            scheduleNext()
        }
        return job
    }

    private fun accepts(task: Task, editor: Boolean = false): Boolean = !closed && workScope.isActive &&
        task.job?.isActive == true && mutableState.value.foreground && (!editor || mutableState.value.editorVisible) &&
        generation == task.generation && active === task

    private fun acceptAppearance(task: Task, actual: AppearanceSnapshot, saved: Boolean = false, issue: AppearanceIssue? = null) = withEditorLock {
        if (!accepts(task, editor = true)) return@withEditorLock
        val current = mutableState.value
        mutableState.value = current.copy(appearance = actual, appearanceIssue = issue ?: actual.issue,
            draftAppearance = if (saved) actual.effective else current.draftAppearance,
            appearanceBaseRevision = if (saved) actual.settings?.revision else current.appearanceBaseRevision,
            savedFeedback = if (saved) AppearanceOperation.SAVE_APPEARANCE else null)
        if (saved) preserveAppearanceDraft = false
        persistDraft()
    }

    private fun acceptBackground(task: Task, actual: PlaybackBackgroundSnapshot, saved: Boolean = false,
        issue: PlaybackBackgroundIssue? = null) {
        var token: String? = null
        withEditorLock {
            if (!accepts(task, editor = true)) return
            val current = mutableState.value
            if (saved) token = current.imagePreview?.token
            mutableState.value = current.copy(background = actual, backgroundIssue = issue ?: actual.issue,
                draftBackground = if (saved) actual.effective else current.draftBackground,
                backgroundBaseRevision = if (saved) actual.settings?.revision else current.backgroundBaseRevision,
                imagePreview = if (saved) null else current.imagePreview,
                needsImageSelection = if (saved) false else current.needsImageSelection,
                savedFeedback = if (saved) AppearanceOperation.SAVE_BACKGROUND else null)
            if (saved) preserveBackgroundDraft = false
            persistDraft()
        }
        token?.let(images::retirePreview)
    }

    private suspend fun readBackTheme(task: Task) {
        val actual = readThemeActual()
        withEditorLock { if (accepts(task, editor = true)) mutableState.value = mutableState.value.copy(theme = actual) }
    }

    private suspend fun readBackBackground(task: Task) = acceptBackground(task, readBackgroundActual())

    private suspend fun readAppearanceActual(): AppearanceSnapshot = try { appearance.read() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { AppearanceSnapshot(null, AppearanceIssue.STORAGE_UNAVAILABLE) }
    catch (_: OutOfMemoryError) { AppearanceSnapshot(null, AppearanceIssue.SETTINGS_TOO_LARGE) }

    private suspend fun readThemeActual(): ThemeSnapshot = try { onceWhenIdle { themes.read() } }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { unavailableTheme((error as? ThemeException)?.issue ?: ThemeIssue.STORAGE_UNAVAILABLE) }
    catch (_: OutOfMemoryError) { unavailableTheme(ThemeIssue.LOW_MEMORY) }

    private fun unavailableTheme(issue: ThemeIssue): ThemeSnapshot {
        val known = themes.state.value
        return ThemeSnapshot(null, null, null, known.effective, known.installed, issue, known.effectiveManifest)
    }

    private suspend fun readBackgroundActual(): PlaybackBackgroundSnapshot = try { onceWhenIdle { images.read() } }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { PlaybackBackgroundSnapshot(null, backgroundRenderIssue(error)) }
    catch (_: OutOfMemoryError) { PlaybackBackgroundSnapshot(null, PlaybackBackgroundIssue.LOW_MEMORY) }

    /** Unexpected failures end this attempt; a failed read never schedules itself again. */
    private suspend fun operationFailure(task: Task, error: Exception? = null, lowMemory: Boolean = false) {
        when (task.operation) {
            AppearanceOperation.SAVE_APPEARANCE -> acceptAppearance(task, readAppearanceActual(),
                issue = if (lowMemory) AppearanceIssue.SETTINGS_TOO_LARGE else AppearanceIssue.STORAGE_UNAVAILABLE)
            AppearanceOperation.APPLY_THEME -> {
                themeFailure(task, if (lowMemory) ThemeException(ThemeIssue.LOW_MEMORY) else checkNotNull(error))
                readBackTheme(task)
            }
            AppearanceOperation.SAVE_BACKGROUND -> {
                backgroundFailure(task, if (lowMemory) PlaybackBackgroundException(PlaybackBackgroundIssue.LOW_MEMORY) else checkNotNull(error))
                readBackBackground(task)
            }
            AppearanceOperation.THEME_PREVIEW -> themeFailure(task,
                if (lowMemory) ThemeException(ThemeIssue.LOW_MEMORY) else checkNotNull(error))
            AppearanceOperation.IMAGE_IMPORT -> backgroundFailure(task,
                if (lowMemory) PlaybackBackgroundException(PlaybackBackgroundIssue.LOW_MEMORY) else checkNotNull(error))
            AppearanceOperation.READ, AppearanceOperation.IDLE -> withEditorLock {
                if (accepts(task)) mutableState.value = mutableState.value.copy(requiresRead = true,
                    appearanceIssue = if (lowMemory) AppearanceIssue.SETTINGS_TOO_LARGE else AppearanceIssue.STORAGE_UNAVAILABLE,
                    themeIssue = if (lowMemory) ThemeIssue.LOW_MEMORY else ThemeIssue.STORAGE_UNAVAILABLE,
                    backgroundIssue = if (lowMemory) PlaybackBackgroundIssue.LOW_MEMORY else PlaybackBackgroundIssue.STORAGE_UNAVAILABLE)
            }
        }
    }

    private fun themeFailure(task: Task, error: Exception) = withEditorLock {
        if (accepts(task, editor = true)) mutableState.value = mutableState.value.copy(
            themeIssue = (error as? ThemeException)?.issue ?: ThemeIssue.STORAGE_UNAVAILABLE)
    }

    private fun backgroundFailure(task: Task, error: Exception) = withEditorLock {
        if (accepts(task, editor = true)) mutableState.value = mutableState.value.copy(backgroundIssue = backgroundRenderIssue(error))
    }

    private suspend fun <T> onceWhenIdle(action: suspend () -> T): T {
        try { return action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            val busy = error is ThemeException && error.issue == ThemeIssue.BUSY || backgroundRenderIssue(error) == PlaybackBackgroundIssue.BUSY
            if (!busy) throw error
            val idle = withTimeoutOrNull(3_000L) { io.awaitIdle()
                true } ?: false
            currentCoroutineContext().ensureActive()
            if (!idle) throw error
        }
        return action()
    }

    private fun retire(tokens: Pair<String?, String?>) {
        tokens.first?.let(themes::retirePreview)
        tokens.second?.let(images::retirePreview)
    }

    private fun persistDraft() {
        val current = mutableState.value
        val revision = current.appearanceBaseRevision
        savedState[KEY_APPEARANCE] = if (current.editorVisible && preserveAppearanceDraft && revision != null) arrayListOf(
            revision.toString(), current.draftAppearance.scalePercent.toString(), current.draftAppearance.horizontalMarginDp.toString(),
            current.draftAppearance.verticalMarginDp.toString(), current.draftAppearance.compactPlaybackTitle.toString(),
            current.draftAppearance.lyricsKeepBright.toString(), current.draftAppearance.language.name) else null
        val backgroundRevision = current.backgroundBaseRevision
        val options = current.draftBackground
        val unconfirmedImage = current.imagePreview?.image == options.customImage && current.imagePreview != null
        val savedImage = options.customImage?.takeIf { !unconfirmedImage && (it == current.background.effective.customImage ||
            current.background.settings == null && it == restoredBackgroundDraft?.takeIf { restored ->
                !restored.needsImageSelection
            }?.options?.customImage) }
        savedState[KEY_BACKGROUND] = if (current.editorVisible && preserveBackgroundDraft && backgroundRevision != null) arrayListOf(
            backgroundRevision.toString(), options.mode.name, options.blurLevel.toString(), options.darkMaskPercent.toString(),
            options.crop.zoom.toString(), options.crop.horizontal.toString(), options.crop.vertical.toString(),
            (current.needsImageSelection || unconfirmedImage ||
                options.mode == ThemeBackgroundMode.CUSTOM_IMAGE && savedImage == null).toString(),
            savedImage?.id.orEmpty(), savedImage?.sha256.orEmpty(), savedImage?.byteCount?.toString().orEmpty(),
            savedImage?.mediaType.orEmpty(), savedImage?.width?.toString().orEmpty(), savedImage?.height?.toString().orEmpty(),
            savedImage?.orientation?.toString().orEmpty()) else null
        savedState[KEY_THEME_SELECTION] = current.editorVisible && (current.needsThemeSelection || current.themePreview != null)
        // Jobs, picker tickets and confirmation intent are deliberately not serialized.
    }

    private fun restoredAppearance(): Pair<Long, AppearanceOptions>? {
        return try {
            val values = restoredValues(KEY_APPEARANCE, 7) ?: return null
            val revision = values[0].toLong().takeIf { it >= 0L } ?: return null
            revision to AppearanceOptions(values[1].toInt(), values[2].toInt(), values[3].toInt(),
                values[4].toBooleanStrict(), values[5].toBooleanStrict(), AppLanguage.valueOf(values[6]))
        } catch (_: IllegalArgumentException) { null }
    }

    private fun restoredBackground(): RestoredBackground? {
        return try {
            val values = restoredValues(KEY_BACKGROUND, 15) ?: return null
            val revision = values[0].toLong().takeIf { it >= 0L } ?: return null
            val image = if (values.subList(8, 15).all(String::isEmpty)) null else BackgroundImageRef(
                values[8], values[9], values[10].toLong(), values[11], values[12].toInt(), values[13].toInt(), values[14].toInt())
            val options = PlaybackBackgroundOptions(ThemeBackgroundMode.valueOf(values[1]), values[2].toInt(),
                values[3].toInt(), BackgroundCrop(values[4].toFloat(), values[5].toFloat(), values[6].toFloat()), image)
            RestoredBackground(revision, options, values[7].toBooleanStrict() ||
                options.mode == ThemeBackgroundMode.CUSTOM_IMAGE && image == null)
        } catch (_: IllegalArgumentException) { null }
    }

    private fun restoredValues(key: String, size: Int): List<String>? {
        val stored = savedState.get<Any>(key) as? List<*> ?: return null
        if (stored.size != size || stored.any { it !is String || it.length > 64 }) return null
        return stored.map { it as String }
    }

    override fun onCleared() {
        val retired = withEditorLock {
            if (closed) return
            closed = true
            retireThemePreviewPresentationLocked()
            generation++
            readRequested = false
            pendingPicker = null
            val current = mutableState.value
            mutableState.value = current.copy(foreground = false, editorVisible = false,
                discardReceivedTheme = null,
                previewing = false, themePreview = null, imagePreview = null, picker = null, pickerResultPending = false)
            Triple(current.themePreview?.token, current.imagePreview?.token, active?.job)
        }
        retire(retired.first to retired.second)
        retired.third?.cancel()
    }

    /** Native/provider cancellation is never invoked while this thread holds the editor lock. */
    private inline fun <T> withEditorLock(action: () -> T): T {
        val outermost = !Thread.holdsLock(lock)
        var retired: PreviewBinding? = null
        try {
            return synchronized(lock) {
                try { action() }
                finally {
                    if (outermost) {
                        retired = retiredThemePreviewPresentation
                        retiredThemePreviewPresentation = null
                    }
                }
            }
        } finally {
            // Direct close and nested edit/begin calls drain only after the outermost monitor exits.
            retired?.cancelRetiredWork()
        }
    }

    private class Task(val generation: Long, val operation: AppearanceOperation) { var job: Job? = null }
    private class PreviewBinding(
        val token: String,
        val releaseMemory: () -> Unit,
        private val cancelWork: () -> Unit,
        private val onClose: (PreviewBinding) -> Unit,
    ) : AutoCloseable {
        private val open = AtomicBoolean(true)
        private val workCancelled = AtomicBoolean(false)
        val isOpen: Boolean get() = open.get()
        fun markRetired(): Boolean = open.compareAndSet(true, false)
        fun cancelRetiredWork() {
            if (workCancelled.compareAndSet(false, true)) cancelWork()
        }
        override fun close() { onClose(this) }
    }
    private data class RestoredBackground(val revision: Long, val options: PlaybackBackgroundOptions, val needsImageSelection: Boolean)
    private sealed interface PickedInput {
        val ticket: AppearancePickerTicket
        val uri: String
        val kind: AppearancePicker
        data class Theme(override val ticket: AppearancePickerTicket, override val uri: String) : PickedInput {
            override val kind = AppearancePicker.THEME
        }
        data class Image(override val ticket: AppearancePickerTicket, override val uri: String, val displayName: String) : PickedInput {
            override val kind = AppearancePicker.IMAGE
        }
    }
    private companion object {
        const val KEY_APPEARANCE = "appearance.editor.draft-v1"
        const val KEY_BACKGROUND = "appearance.editor.background-draft-v1"
        const val KEY_THEME_SELECTION = "appearance.editor.theme-needs-selection-v1"
        const val MAX_PICKER_URI = 8192
        const val MAX_IMAGE_NAME = 512
    }
}
