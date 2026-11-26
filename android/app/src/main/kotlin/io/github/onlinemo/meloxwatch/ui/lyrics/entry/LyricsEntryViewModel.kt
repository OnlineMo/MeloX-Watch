package io.github.onlinemo.meloxwatch.ui.lyrics.entry

import android.view.Choreographer
import androidx.annotation.MainThread
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.onlinemo.meloxwatch.data.library.LibraryException
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsClockMapping
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsEncodingChoice
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsKind
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsLimits
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsMemoryOwner
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsParseException
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsParseIssue
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsTranslation
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsTranslationMatcher
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsUserOffset
import io.github.onlinemo.meloxwatch.data.lyrics.active.LocalLyricsContext
import io.github.onlinemo.meloxwatch.data.lyrics.active.LyricsDocumentResolutionException
import io.github.onlinemo.meloxwatch.data.lyrics.active.LyricsDocuments
import io.github.onlinemo.meloxwatch.data.lyrics.active.LyricsLoadAuthority
import io.github.onlinemo.meloxwatch.data.lyrics.active.lyricsResolutionIssue
import io.github.onlinemo.meloxwatch.data.lyrics.load.LocalLyricsLoadResult
import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsDocumentInput
import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsLoadBinding
import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsLoadFailure
import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsLoadIssue
import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsLoadRequest
import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsLoadSelection
import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsLoadedSource
import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsSourceOrigin
import io.github.onlinemo.meloxwatch.data.lyrics.online.OnlineLyricsIssue
import io.github.onlinemo.meloxwatch.data.lyrics.online.OnlineLyricsOrigin
import io.github.onlinemo.meloxwatch.data.lyrics.online.OnlineLyricsPort
import io.github.onlinemo.meloxwatch.data.lyrics.online.OnlineLyricsRequest
import io.github.onlinemo.meloxwatch.data.lyrics.online.OnlineLyricsResult
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsTrackCommitResult
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsTrackIssue
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsTrackPreferences
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsTrackReadResult
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsTrackStore
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsTrackWriteFence
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.queue.QueueStore
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.QueueSnapshot
import io.github.onlinemo.meloxwatch.model.TrackKey
import io.github.onlinemo.meloxwatch.model.onlineOpaqueHash
import io.github.onlinemo.meloxwatch.ui.lyrics.LyricsPageContent
import io.github.onlinemo.meloxwatch.ui.lyrics.LyricsPageFailure
import io.github.onlinemo.meloxwatch.ui.lyrics.LyricsPageIdentity
import io.github.onlinemo.meloxwatch.ui.lyrics.LyricsPlayPreview
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

enum class LyricsEntrySurface { PAGE, OPTIONS }
enum class LyricsDocumentPurpose { PRIMARY, TRANSLATION }
enum class LyricsEntryNotice { SAVED, CANCELLED, STALE, CONFLICT, NO_PICKER }

data class LyricsDocumentRequest(
    val requestId: String,
    val purpose: LyricsDocumentPurpose,
    val encoding: LyricsEncodingChoice,
)

data class LyricsAssociationPreview(val previewId: String, val purpose: LyricsDocumentPurpose)

data class LyricsEntryUiState(
    val identity: LyricsPageIdentity? = null,
    val documentId: String? = null,
    val content: LyricsPageContent = LyricsPageContent.Missing,
    val preferences: LyricsTrackPreferences? = null,
    val sourceOrigin: LyricsSourceOrigin? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val canChoose: Boolean = false,
    val failure: LyricsLoadFailure? = null,
    val translationFailure: LyricsLoadFailure? = null,
    val trackIssue: LyricsTrackIssue? = null,
    val notice: LyricsEntryNotice? = null,
    val documentRequest: LyricsDocumentRequest? = null,
    val pickerLaunched: Boolean = false,
    val associationPreview: LyricsAssociationPreview? = null,
    val offsetDraftMs: Int? = null,
    val offsetDraftNeedsReview: Boolean = false,
    val canReviewOffsetDraft: Boolean = false,
    val onlineOrigin: OnlineLyricsOrigin? = null,
    val onlineIssue: OnlineLyricsIssue? = null,
    val onlineFromCache: Boolean = false,
    val timelineOriginMs: Long = 0L,
) {
    val mapping: LyricsClockMapping get() = LyricsClockMapping(timelineOriginMs = timelineOriginMs, userOffset = LyricsUserOffset(
        offsetDraftMs ?: preferences?.userDelayMs ?: 0,
    ))
}

/**
 * 当前队列实例、授权加载、关联 CAS 和显式行播放共用一个主线程所有者。
 * collectLatest 先等待旧读取的实际清理，再启动新意图；取消不释放仍在运行的 provider。
 * 页面隐藏停止媒体采样；最后一个展示/认证外部读 lease 关闭才停止业务订阅。
 * 选择器的单个运行时票据可跨 STOP，不恢复播放或确认动作。
 * 同一实际 Entry 路由内关闭展示只留小草稿；下一展示重读校验，不保留旧读权限或解析对象。
 */
@MainThread
class LyricsEntryViewModel(
    private val queues: QueueStore,
    private val resolveContext: suspend (QueueEntry) -> LocalLyricsContext,
    private val validateContext: suspend (LocalLyricsContext) -> Boolean,
    private val tracks: LyricsTrackStore,
    private val documents: LyricsDocuments,
    private val authority: LyricsLoadAuthority,
    private val load: suspend (LyricsLoadRequest) -> LocalLyricsLoadResult,
    private val validate: suspend (LyricsLoadRequest, LocalLyricsLoadResult.Loaded) -> Boolean,
    private val playback: LyricsPlaybackPort,
    private val libraryChanges: Flow<Unit> = emptyFlow(),
    private val translationMatcher: LyricsTranslationMatcher = LyricsTranslationMatcher(),
    private val scopeOverride: CoroutineScope? = null,
    private val awaitDisplayFrame: suspend () -> Unit = ::awaitLyricsDisplayFrame,
    savedStateHandle: SavedStateHandle = SavedStateHandle(),
    private val memoryOwner: LyricsMemoryOwner = LyricsMemoryOwner(),
    private val onlineLyrics: OnlineLyricsPort? = null,
    private val awaitReadReady: suspend () -> Unit = {},
) : ViewModel() {
    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope
    private var savedDrafts = LyricsEntrySavedDrafts(savedStateHandle)
    // This survives the first queue observation's new process generation, unlike runtime SuspendedDraft.
    private var restoredOffset = savedDrafts.readOffset()
    private val mutableState = MutableStateFlow(LyricsEntryUiState(offsetDraftMs = restoredOffset?.value,
        offsetDraftNeedsReview = restoredOffset != null))
    val uiState = mutableState.asStateFlow()
    private val mutableClock = MutableStateFlow<LyricsMediaClock?>(null)
    val mediaClock = mutableClock.asStateFlow()
    private class Visibility(val enabled: Boolean)
    private val visibility = MutableStateFlow(Visibility(false))
    private val work = MutableStateFlow<Work?>(null)
    private val writeAuthority = AtomicReference<WriteTicket?>()
    private var visible = false
    private var uiVisible = false
    private var externalReading: ExternalReading? = null
    private var surface = LyricsEntrySurface.PAGE
    private var closed = false
    private var latestQueue: QueueSnapshot? = null
    private var latestKey: QueueKey? = null
    private var lastSourceKey: SourceKey? = null
    private var sourceGeneration = 0L
    private var requestGeneration = 0L
    private var acquisition: LyricsLoadRequest? = null
    private var owner: Owner? = null
    private var loaded: Loaded? = null
    private var onlineLoaded: OnlineLyricsResult.Loaded? = null
    private var onlineExternalIdentity: Any? = null
    private val onlineAcquisition = AtomicReference<OnlineLyricsRequest?>()
    private var observedProvider: String? = null
    private var displayMemory: LyricsMemoryOwner.Lease? = null
    private var pendingPicker: Picker? = null
    private var pendingPreview: AssociationPreview? = null
    private var entryRoute: EntryRoute? = null
    private var suspendedDraft: SuspendedDraft? = null
    private var sampler: Job? = null
    private var activeReadJob: Job? = null
    private var wordEmphasis = false
    private var visibleLines: List<Int> = emptyList()
    private var timelinePresented = false
    private var deferredLoad = false
    private var presentation: Presentation? = null
    val playConfirmation = LyricsPlayConfirmation(scope, ::playContext, playback, ::validatePlayContext,
        retainContext = { memoryOwner.acquire(this) })

    private val worker = scope.launch {
        work.collectLatest { next -> if (next != null) perform(next) }
    }
    private val observer = scope.launch {
        visibility.collectLatest { visibilityTicket ->
            if (!visibilityTicket.enabled) return@collectLatest
            try { awaitReadReady() }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                if (visible && visibility.value === visibilityTicket) failCurrent(LyricsLoadIssue.SOURCE_UNAVAILABLE)
                return@collectLatest
            }
            coroutineScope {
                launch {
                    try {
                        combine(queues.snapshots(), playback.changes.onStart { emit(Unit) }) { queue, _ -> queue }
                            .collect { queue ->
                                if (visible && visibility.value === visibilityTicket) observeQueue(queue)
                            }
                    } catch (error: CancellationException) { throw error }
                    catch (_: Exception) {
                        if (visible && visibility.value === visibilityTicket) failCurrent(LyricsLoadIssue.SOURCE_UNAVAILABLE)
                    }
                }
                launch {
                    val changes = onlineLyrics?.changes ?: return@launch
                    changes.collect {
                        if (visible && visibility.value === visibilityTicket && observedProvider == "netease") {
                            refreshSource(sourceChanged = true)
                        }
                    }
                }
                launch {
                    try {
                        libraryChanges.collect {
                            if (visible && visibility.value === visibilityTicket && observedProvider == TrackKey.LOCAL_PROVIDER) {
                                refreshSource(sourceChanged = true)
                            }
                        }
                    } catch (error: CancellationException) { throw error }
                    catch (_: Exception) {
                        if (visible && visibility.value === visibilityTicket && observedProvider == TrackKey.LOCAL_PROVIDER) {
                            failCurrent(LyricsLoadIssue.SOURCE_UNAVAILABLE)
                        }
                    }
                }
            }
        }
    }

    /** Register before the Activity's page owner starts any observer or sampler. */
    fun bindPresentation(): Presentation {
        check(!closed) { "The lyrics owner has been cleared" }
        presentation?.close()
        return Presentation().also { presentation = it }
    }

    /** A new Activity registry receives only the bounded draft, never the active reading/session. */
    fun bindSavedDraftState(handle: SavedStateHandle) {
        val oldDraft = savedDrafts.readOffset()
        val next = LyricsEntrySavedDrafts(handle)
        if (oldDraft != null) next.writeOffset(oldDraft)
        savedDrafts = next
        if (oldDraft == null && restoredOffset == null && mutableState.value.offsetDraftMs == null) {
            restoredOffset = next.readOffset()
            mutableState.value = mutableState.value.copy(offsetDraftMs = restoredOffset?.value,
                offsetDraftNeedsReview = restoredOffset != null, canReviewOffsetDraft = false)
        }
    }

    /** Called only after the dedicated receiver session has authenticated on both screens. */
    fun bindExternalReading(onlineReadReady: (String) -> Boolean = { false }): ExternalReading {
        check(!closed)
        check(externalReading == null) { "Only one external receiver may retain a reading" }
        return ExternalReading(onlineReadReady).also {
            externalReading = it
            if (!visible) {
                visible = true
                visibility.value = Visibility(true)
            }
            resumeDeferredLoad()
        }
    }

    inner class ExternalReading internal constructor(private val onlineReadReady: (String) -> Boolean) : AutoCloseable {
        private var open = true
        private var needsHiddenCleanup = false
        private var retiringRead: Job? = null
        private var onlineAttempt: ExternalOnlineAttempt? = null
        val isCurrent: Boolean get() = open && !closed && externalReading === this

        override fun close() {
            if (open) {
                open = false
                if (externalReading !== this) return
                externalReading = null
                if (!uiVisible) retiringRead = activeReadJob
                needsHiddenCleanup = true
            }
            if (needsHiddenCleanup) {
                if (!uiVisible) onHidden()
                needsHiddenCleanup = false
            }
        }

        suspend fun awaitClosed() {
            check(!open && !needsHiddenCleanup)
            retiringRead?.join()
            retiringRead = null
        }

        internal fun canAcquireOnline(entryId: String): Boolean = isCurrent && onlineReadReady(entryId)
        internal fun hasAttemptedOnline(generation: String, revision: Long, entry: String, source: Long): Boolean =
            onlineAttempt == ExternalOnlineAttempt(generation, revision, entry, source)
        internal fun markOnlineAttempt(generation: String, revision: Long, entry: String, source: Long) {
            onlineAttempt = ExternalOnlineAttempt(generation, revision, entry, source)
        }
    }

    /** Saved source/offset only: an unconfirmed association, write or display draft is never sent. */
    fun readExternal(): LyricsExternalRead? {
        if (externalReading?.isCurrent != true || closed) return null
        val state = mutableState.value
        if (state.loading || state.saving || pendingPreview != null || state.associationPreview != null) return null
        val local = loaded
        if (local != null && local.owner === owner && local.owner.key == latestKey &&
            local.owner.identity.sourceGeneration == sourceGeneration) {
            return LyricsExternalRead(local.externalIdentity, local.owner.key.entryId, sourceGeneration, local.primary.document,
                local.translated, LyricsClockMapping(
                    timelineOriginMs = cueLyricsTimelineOrigin(local.owner.context.cueReference, local.primary.source.origin),
                    userOffset = LyricsUserOffset(local.owner.preferences.userDelayMs),
                ))
        }
        val online = onlineLoaded ?: return null
        val fence = online.fence
        if (latestKey != QueueKey(fence.queueGenerationId, fence.queueRevision, fence.queueEntryId) ||
            state.identity?.sourceGeneration != fence.sourceGeneration) return null
        return LyricsExternalRead(onlineExternalIdentity ?: return null, fence.queueEntryId, fence.sourceGeneration, online.document,
            online.translation, LyricsClockMapping())
    }

    /** An old page or Activity cannot hide the successor using the same retained ViewModel. */
    inner class Presentation internal constructor() : AutoCloseable {
        private var open = true
        val isCurrent: Boolean get() = open && !closed && presentation === this

        fun show(nextSurface: LyricsEntrySurface) {
            if (isCurrent) onVisible(nextSurface)
        }

        override fun close() {
            if (!open) return
            open = false
            if (presentation !== this) return
            presentation = null
            val draft = draftForPresentationClose()
            onHidden(preservePicker = true)
            suspendedDraft = draft
            // A retained Activity owner keeps only bounded proposal metadata across STOP/config.
            // Parsed documents, translations and their timelines belong to the visible presentation.
            if (externalReading == null) {
                mutableState.value = LyricsEntryUiState(documentRequest = pendingPicker?.request,
                    pickerLaunched = pendingPicker?.launched == true)
            }
        }
    }

    /** The actual four Entry routes share one runtime identity; recording it starts no work. */
    fun setActualEntryRoute(active: Boolean) {
        if (closed) return
        if (active) {
            if (entryRoute == null) entryRoute = EntryRoute()
        } else {
            entryRoute = null
            clearSavedOffset()
            suspendedDraft = null
            pendingPreview = null
            pendingPicker = null
            mutableState.value = mutableState.value.copy(associationPreview = null, offsetDraftMs = null,
                offsetDraftNeedsReview = false, canReviewOffsetDraft = false, documentRequest = null, pickerLaunched = false)
        }
    }

    fun onVisible(nextSurface: LyricsEntrySurface) {
        if (closed) return
        uiVisible = true
        surface = nextSurface
        timelinePresented = nextSurface == LyricsEntrySurface.PAGE
        if (!visible) {
            visible = true
            visibility.value = Visibility(true)
        }
        playConfirmation.contextChanged()
        updateSampler()
        resumeDeferredLoad()
    }

    fun onHidden(preservePicker: Boolean = true) {
        uiVisible = false
        timelinePresented = false
        sampler?.cancel()
        sampler = null
        mutableClock.value = null
        suspendedDraft = null
        val hadPreview = pendingPreview != null || work.value?.action?.let { it !== Action.Load } == true
        pendingPreview = null
        if (!preservePicker || pendingPicker?.launched != true) pendingPicker = null
        if (externalReading?.isCurrent == true) {
            mutableState.value = mutableState.value.copy(offsetDraftMs = null,
                offsetDraftNeedsReview = false, canReviewOffsetDraft = false, associationPreview = null,
                documentRequest = pendingPicker?.request, pickerLaunched = pendingPicker?.launched == true,
                canChoose = false)
            if (hadPreview || deferredLoad ||
                owner == null && onlineLoaded == null && work.value == null) latestKey?.let(::requestLoad)
            playConfirmation.contextChanged()
            return
        }
        visible = false
        deferredLoad = false
        visibility.value = Visibility(false)
        replaceWork(null)
        mutableState.value = mutableState.value.copy(loading = false, saving = false, associationPreview = null,
            content = LyricsPageContent.Missing, documentId = null, onlineOrigin = null, onlineIssue = null, onlineFromCache = false,
            offsetDraftMs = null, canChoose = false, documentRequest = pendingPicker?.request,
            pickerLaunched = pendingPicker?.launched == true)
        displayMemory?.close()
        displayMemory = null
        playConfirmation.contextChanged()
    }

    fun leave() {
        onHidden(preservePicker = false)
        if (externalReading?.isCurrent == true) {
            clearSavedOffset()
            return
        }
        latestKey = null
        latestQueue = null
        owner = null
        loaded = null
        onlineLoaded = null
        observedProvider = null
        mutableState.value = LyricsEntryUiState()
    }

    fun retry(sourceChanged: Boolean = false) {
        if (!uiVisible) return
        refreshSource(sourceChanged)
    }

    private fun refreshSource(sourceChanged: Boolean) {
        val queue = latestQueue ?: return
        if (!visible || closed) return
        if (sourceChanged && !advanceSourceGeneration()) return
        suspendedDraft = null
        pendingPreview = null
        pendingPicker = null
        requestLoad(queue.key() ?: return)
    }

    fun requestDocument(purpose: LyricsDocumentPurpose, encoding: LyricsEncodingChoice = LyricsEncodingChoice.AUTO_BOM_OR_UTF8): String? {
        val current = owner ?: return null
        val state = mutableState.value
        if (!canEdit(current) || state.associationPreview != null || pendingPicker != null || state.offsetDraftMs != null) return null
        if (purpose == LyricsDocumentPurpose.TRANSLATION && loaded?.primary?.document?.kind != LyricsKind.SYNCHRONIZED) return null
        val request = LyricsDocumentRequest(UUID.randomUUID().toString(), purpose, encoding)
        pendingPicker = Picker(request, current.key, sourceGeneration)
        mutableState.value = state.copy(documentRequest = request, pickerLaunched = false, notice = null)
        return request.requestId
    }

    fun pickerLaunched(requestId: String): Boolean {
        val picker = pendingPicker?.takeIf { it.request.requestId == requestId } ?: return false
        if (!uiVisible || picker.launched || picker.key != latestKey) return false
        picker.launched = true
        mutableState.value = mutableState.value.copy(pickerLaunched = true)
        return true
    }

    fun pickerReturned(requestId: String, uri: String?, returnedFlags: Int) {
        val picker = pendingPicker?.takeIf { it.request.requestId == requestId } ?: return
        if (uri == null) {
            pendingPicker = null
            mutableState.value = mutableState.value.copy(documentRequest = null, pickerLaunched = false,
                notice = LyricsEntryNotice.CANCELLED)
            return
        }
        if (uri.length > io.github.onlinemo.meloxwatch.data.local.LocalInputLimits.URI_CHARACTERS) {
            pendingPicker = null
            failCurrent(LyricsLoadIssue.INVALID_INPUT)
            return
        }
        picker.result = Picked(uri, returnedFlags)
        mutableState.value = mutableState.value.copy(documentRequest = null, pickerLaunched = false)
        if (uiVisible) consumePicker()
    }

    fun noPicker(requestId: String) {
        if (pendingPicker?.request?.requestId != requestId) return
        pendingPicker = null
        mutableState.value = mutableState.value.copy(documentRequest = null, pickerLaunched = false,
            notice = LyricsEntryNotice.NO_PICKER)
    }

    fun confirmAssociation(previewId: String): Boolean {
        val preview = pendingPreview?.takeIf { it.id == previewId } ?: return false
        val current = owner ?: return false
        if (!canEdit(current) || current.key != preview.key || sourceGeneration != preview.sourceGeneration) return false
        pendingPreview = null
        replaceWork(Work(UUID.randomUUID().toString(), preview.key, sourceGeneration, Action.Associate(preview)))
        return true
    }

    fun cancelAssociation(previewId: String) {
        if (!uiVisible || pendingPreview?.id != previewId) return
        pendingPreview = null
        mutableState.value = mutableState.value.copy(associationPreview = null, notice = LyricsEntryNotice.CANCELLED)
        retry()
    }

    fun clearAssociation(purpose: LyricsDocumentPurpose): Boolean {
        val current = owner ?: return false
        if (!canEdit(current) || pendingPreview != null || pendingPicker != null || mutableState.value.offsetDraftMs != null) return false
        replaceWork(Work(UUID.randomUUID().toString(), current.key, sourceGeneration,
            Action.Clear(purpose, current.preferences.revision)))
        return true
    }

    fun beginOffset(): Boolean {
        val current = owner ?: return false
        val previous = loaded ?: return false
        if (!canEdit(current) || loaded?.primary?.document?.kind != LyricsKind.SYNCHRONIZED || pendingPreview != null ||
            pendingPicker != null || mutableState.value.offsetDraftMs != null) return false
        mutableState.value = mutableState.value.copy(offsetDraftMs = current.preferences.userDelayMs, notice = null)
        restoredOffset = null
        rememberOffset(current, previous, current.preferences.userDelayMs)
        playConfirmation.contextChanged()
        return true
    }

    fun adjustOffset(steps: Int) {
        if (!uiVisible) return
        if (mutableState.value.offsetDraftNeedsReview) return
        val current = mutableState.value.offsetDraftMs ?: return
        val value = LyricsUserOffset(current).plusSteps(steps).delayMs
        mutableState.value = mutableState.value.copy(offsetDraftMs = value)
        savedDrafts.updateOffset(value)
        updateSampler()
    }

    fun resetOffset() {
        if (!uiVisible) return
        if (mutableState.value.offsetDraftNeedsReview) return
        if (mutableState.value.offsetDraftMs != null) {
            mutableState.value = mutableState.value.copy(offsetDraftMs = 0)
            savedDrafts.updateOffset(0)
            updateSampler()
        }
    }

    fun cancelOffset() {
        if (!uiVisible) return
        clearSavedOffset()
        suspendedDraft = null
        mutableState.value = mutableState.value.copy(offsetDraftMs = null,
            offsetDraftNeedsReview = false, canReviewOffsetDraft = false)
        playConfirmation.contextChanged()
        updateSampler()
    }

    fun saveOffset(): Boolean {
        val current = owner ?: return false
        val offset = mutableState.value.offsetDraftMs ?: return false
        val previous = loaded ?: return false
        if (!canEdit(current) || previous.owner !== current || mutableState.value.offsetDraftNeedsReview) return false
        replaceWork(Work(UUID.randomUUID().toString(), current.key, sourceGeneration,
            Action.Offset(offset, current.preferences.revision, previous)))
        return true
    }

    /** A fresh read reviews this proposal against current revisions; it never commits or confirms playback. */
    fun reviewOffsetDraft(): Boolean {
        val draft = restoredOffset ?: return false
        val current = owner ?: return false
        if (!canEdit(current) || !mutableState.value.canReviewOffsetDraft || entryRoute == null) return false
        replaceWork(Work(UUID.randomUUID().toString(), current.key, sourceGeneration, Action.ReviewOffset(draft)))
        return true
    }

    fun previewPlay(preview: LyricsPlayPreview): String? {
        if (!uiVisible) return null
        val confirmation = playConfirmation.begin(preview)
        if (confirmation != null) setTimelinePresented(false)
        return confirmation
    }

    /** The original document stays owned for confirmation; its replaced visual body does no work. */
    fun setTimelinePresented(presented: Boolean) {
        if (closed) return
        timelinePresented = uiVisible && presented
        updateSampler()
        resumeDeferredLoad()
    }

    private fun requestLoad(key: QueueKey) {
        if (externalReading == null && uiVisible && surface == LyricsEntrySurface.PAGE && !timelinePresented) {
            deferredLoad = true
            replaceWork(null)
            mutableState.value = mutableState.value.copy(content = LyricsPageContent.Loading, documentId = null,
                onlineOrigin = null, onlineIssue = null, onlineFromCache = false)
            return
        }
        deferredLoad = false
        replaceWork(Work(UUID.randomUUID().toString(), key, sourceGeneration, Action.Load))
    }

    private fun resumeDeferredLoad() {
        if (!deferredLoad || !visible ||
            externalReading == null && (surface != LyricsEntrySurface.PAGE || !timelinePresented)) return
        latestKey?.let(::requestLoad)
    }

    fun setWordEmphasis(enabled: Boolean) {
        if (wordEmphasis == enabled) return
        wordEmphasis = enabled
        updateSampler()
    }

    fun setVisibleLines(indices: List<Int>) {
        visibleLines = indices.asSequence().filter { it >= 0 }.distinct().take(MAX_VISIBLE_LINES).toList()
    }

    private fun observeQueue(queue: QueueSnapshot?) {
        latestQueue = queue
        val key = queue?.key()
        if (key != latestKey) {
            latestKey = key
            if (restoredOffset == null) savedDrafts.clearOffset()
            if (suspendedDraft?.key != key) suspendedDraft = null
            pendingPreview = null
            if (pendingPicker?.key != key) pendingPicker = null
            owner = null
            loaded = null
            onlineLoaded = null
            observedProvider = null
            if (key?.sourceKey() != lastSourceKey) {
                lastSourceKey = key?.sourceKey()
                if (!advanceSourceGeneration()) return
            }
            if (key == null) {
                replaceWork(null)
                mutableState.value = LyricsEntryUiState()
            } else if (!consumePicker() && !consumeDraft()) {
                requestLoad(key)
            }
        } else if (!consumePicker() && !consumeDraft() && key != null) {
            if (canStartExternalOnline(key) || owner == null && work.value == null) requestLoad(key)
        }
        playConfirmation.contextChanged()
        updateSampler()
    }

    private fun canStartExternalOnline(key: QueueKey): Boolean {
        val external = externalReading ?: return false
        return visible && observedProvider == "netease" && onlineLoaded == null && activeReadJob == null &&
            external.canAcquireOnline(key.entryId) &&
            !external.hasAttemptedOnline(key.generationId, key.revision, key.entryId, sourceGeneration)
    }

    private fun consumePicker(): Boolean {
        val picker = pendingPicker ?: return false
        val result = picker.result ?: return false
        if (!uiVisible || latestQueue == null) return false
        pendingPicker = null
        if (picker.key != latestKey || picker.sourceGeneration != sourceGeneration) {
            mutableState.value = mutableState.value.copy(documentRequest = null, pickerLaunched = false,
                notice = LyricsEntryNotice.STALE)
            return false
        }
        replaceWork(Work(UUID.randomUUID().toString(), picker.key, sourceGeneration,
            Action.Preview(picker.request.purpose, result, picker.request.encoding)))
        return true
    }

    private fun draftForPresentationClose(): SuspendedDraft? {
        if (restoredOffset != null) return null
        val route = entryRoute ?: return null
        suspendedDraft?.takeIf { it.route === route && it.key == latestKey && it.sourceGeneration == sourceGeneration }
            ?.let { return it }
        val operation = work.value
        val resuming = operation?.action as? Action.Resume
        if (mutableState.value.loading && operation != null && resuming != null && isCurrent(operation)) return resuming.draft
        val current = owner ?: return null
        val previous = loaded ?: return null
        val state = mutableState.value
        if (!visible || closed || state.loading || state.saving || previous.owner !== current || current.key != latestKey ||
            current.identity.sourceGeneration != sourceGeneration || pendingPicker != null || state.failure != null ||
            state.translationFailure != null || state.trackIssue != null) return null
        val audio = current.binding.draftAudio()
        val preview = pendingPreview
        return when {
            preview != null && state.associationPreview?.previewId == preview.id && preview.key == current.key &&
                preview.sourceGeneration == sourceGeneration && preview.preferencesRevision == current.preferences.revision ->
                SuspendedDraft.Association(route, current.key, sourceGeneration, current.preferences.revision,
                    audio, preview.purpose, preview.input, preview.sha256)
            state.offsetDraftMs != null -> SuspendedDraft.Offset(route, current.key, sourceGeneration,
                current.preferences.revision, audio, state.offsetDraftMs, previous.primary.draftDocument(previous.primaryRequest),
                previous.translationRequest?.let { previous.translation?.draftDocument(it) })
            else -> null
        }
    }

    private fun consumeDraft(): Boolean {
        val draft = suspendedDraft ?: return false
        suspendedDraft = null
        if (!uiVisible || draft.route !== entryRoute || draft.key != latestKey || draft.sourceGeneration != sourceGeneration) return false
        replaceWork(Work(UUID.randomUUID().toString(), draft.key, sourceGeneration, Action.Resume(draft)))
        return true
    }

    private fun replaceWork(next: Work?) {
        writeAuthority.set(null)
        acquisition?.let(authority::retire)
        acquisition = null
        onlineAcquisition.set(null)
        owner = null
        loaded = null
        onlineLoaded = null
        mutableState.value = mutableState.value.copy(loading = next != null,
            saving = next?.action is Action.Associate || next?.action is Action.Offset || next?.action is Action.Clear,
            canChoose = false)
        work.value = next
        sampler?.cancel()
        sampler = null
        playConfirmation.contextChanged()
    }

    private suspend fun perform(operation: Work) {
        var primaryRequest: LyricsLoadRequest? = null
        var operationMemory: LyricsMemoryOwner.Lease? = null
        val attempt = checkNotNull(currentCoroutineContext()[Job])
        activeReadJob = attempt
        try {
            if (!isCurrent(operation)) return
            awaitReadReady()
            ensureCurrent(operation)
            // PAGE and OPTIONS share this gate; cancellation must finish before a new document owns the budget.
            playConfirmation.awaitCleanup()
            ensureCurrent(operation)
            val reserved: LyricsMemoryOwner.Lease = memoryOwner.acquire(this) ?: return failCurrent(LyricsLoadIssue.BUSY)
            operationMemory = reserved
            if (displayMemory == null) displayMemory = reserved.retain()
            owner = null
            loaded = null
            mutableState.value = mutableState.value.copy(loading = true,
                saving = operation.action is Action.Associate || operation.action is Action.Offset || operation.action is Action.Clear,
                content = LyricsPageContent.Loading, associationPreview = null, failure = null, translationFailure = null,
                onlineOrigin = null, onlineIssue = null, onlineFromCache = false, trackIssue = null, notice = operation.noticeAfterLoad, canChoose = false, documentRequest = pendingPicker?.request,
                pickerLaunched = pendingPicker?.launched == true)
            val queue = queues.snapshot()
            // A provider may finish after retirement; only the current Work may publish or commit.
            ensureCurrent(operation)
            if (queue == null || queue.key() != operation.key) return failCurrent(LyricsLoadIssue.STALE_REQUEST)
            val window = queues.window(queue.generationId, operation.key.entryId)
            ensureCurrent(operation)
            if (window.generationId != queue.generationId || window.queueRevision != queue.queueRevision ||
                window.current.queueEntryId != operation.key.entryId) return failCurrent(LyricsLoadIssue.STALE_REQUEST)
            val entry = window.current
            observedProvider = entry.trackKey.providerId
            if (entry.trackKey.providerId == "netease") {
                performOnline(operation, queue, entry, reserved.maxAccountedBytes)
                return
            }
            if (entry.trackKey.providerId != TrackKey.LOCAL_PROVIDER) return failCurrent(LyricsLoadIssue.INVALID_INPUT)
            if (reserved.maxAccountedBytes <= VALIDATION_SCRATCH_BYTES) return failCurrent(LyricsLoadIssue.MEMORY_LIMIT)
            val context = resolveContext(entry)
            ensureCurrent(operation)
            val saved = tracks.read(entry.trackKey)
            ensureCurrent(operation)
            if (saved is LyricsTrackReadResult.Unavailable) {
                suspendedDraft = null
                if (operation.action is Action.Resume) replaceWork(null)
                owner = null
                mutableState.value = mutableState.value.copy(loading = false, saving = false,
                    content = LyricsPageContent.Failed(LyricsPageFailure.READ_FAILED), trackIssue = saved.issue,
                    offsetDraftMs = restoredOffset?.value, offsetDraftNeedsReview = restoredOffset != null,
                    canReviewOffsetDraft = false)
                return
            }
            val preferences = (saved as LyricsTrackReadResult.Ready).preferences
            val expectedRevision = when (val action = operation.action) {
                is Action.Associate -> action.preview.preferencesRevision
                is Action.Offset -> action.expectedRevision
                is Action.Clear -> action.expectedRevision
                is Action.Resume -> action.draft.preferencesRevision
                else -> preferences.revision
            }
            if (preferences.revision != expectedRevision) {
                suspendedDraft = null
                if (operation.action is Action.Resume) replaceWork(null)
                mutableState.value = mutableState.value.copy(preferences = preferences, notice = LyricsEntryNotice.CONFLICT,
                    content = LyricsPageContent.Failed(LyricsPageFailure.READ_FAILED), loading = false, saving = false)
                return
            }
            val next = when (val action = operation.action) {
                is Action.Clear -> if (action.purpose == LyricsDocumentPurpose.PRIMARY) preferences.copy(primary = null)
                    else preferences.copy(translation = null)
                is Action.Offset -> preferences.copy(userDelayMs = action.value)
                else -> preferences
            }
            val identity = LyricsPageIdentity(entry.trackKey, queue.generationId, entry.queueEntryId, operation.sourceGeneration)
            val offsetAction = operation.action as? Action.Offset
            val resumeAction = operation.action as? Action.Resume
            if (resumeAction != null && !resumeAction.draft.audio.matches(context)) return failCurrent(LyricsLoadIssue.SOURCE_CHANGED)
            val binding = if (offsetAction != null) {
                val previousBinding = offsetAction.previous.primaryRequest.binding
                val audio = context.audio
                if (previousBinding.trackKey != audio.trackKey || previousBinding.locationId != audio.location.locationId ||
                    previousBinding.contentRevision != audio.location.contentRevision || previousBinding.sourceId != audio.source.spec.sourceId ||
                    previousBinding.sourceRevision != audio.source.revision || previousBinding.authorizationEpoch != audio.source.authEpoch ||
                    previousBinding.sourceGeneration != operation.sourceGeneration) return failCurrent(LyricsLoadIssue.SOURCE_CHANGED)
                previousBinding
            } else {
                if (requestGeneration == Long.MAX_VALUE) return failCurrent(LyricsLoadIssue.INVALID_INPUT)
                context.audio.let { audio -> LyricsLoadBinding(entry.trackKey,
                    audio.location.locationId, audio.location.contentRevision, audio.source.spec.sourceId,
                    audio.source.revision, audio.source.authEpoch, ++requestGeneration, operation.sourceGeneration) }
            }
            val currentOwner = Owner(operation.key, identity, context, preferences, binding)
            owner = currentOwner
            val ticket = WriteTicket(operation.id)
            writeAuthority.set(ticket)
            if (operation.action is Action.Clear) {
                // 解除只删除一个关联字段，不把保留的旧字段重新宣称为有效输入。
                // 自动歌词缺失、坏关联或解析失败不能阻止撤销；真实音频仍须独立验证。
                val contextValid = validateContext(context)
                ensureCurrent(operation)
                if (!contextValid) return failCurrent(LyricsLoadIssue.SOURCE_CHANGED)
                commit(operation, ticket, currentOwner, next)
                return
            }
            if (offsetAction != null) {
                val previous = offsetAction.previous
                primaryRequest = previous.primaryRequest
                acquisition = primaryRequest
                authority.install(primaryRequest)
                val translationRequest = previous.translationRequest
                if (translationRequest != null) check(authority.attachTranslation(primaryRequest, translationRequest))
                val primaryValid = validateContext(context) && validate(primaryRequest, previous.primary)
                ensureCurrent(operation)
                if (!primaryValid) return failCurrent(LyricsLoadIssue.SOURCE_CHANGED)
                val translationValid = translationRequest == null || previous.translation == null ||
                    validate(translationRequest, previous.translation)
                ensureCurrent(operation)
                if (!translationValid) return failCurrent(LyricsLoadIssue.SOURCE_CHANGED)
                commit(operation, ticket, currentOwner, next)
                return
            }
            val selectedPrimary = selectedInput(operation.action, LyricsDocumentPurpose.PRIMARY)
            ensureCurrent(operation)
            val selected = selectedPrimary ?: next.primary?.let { documents.resolve(it, operation.sourceGeneration) }
            ensureCurrent(operation)
            primaryRequest = LyricsLoadRequest(binding.copy(documentIdentity = selected?.documentId),
                if (selected == null) LyricsLoadSelection.Automatic(siblingScope = context.siblingScope)
                else LyricsLoadSelection.TextDocument(selected),
                minOf(PRIMARY_ACCOUNTED_BYTES, reserved.maxAccountedBytes - VALIDATION_SCRATCH_BYTES))
            acquisition = primaryRequest
            authority.install(primaryRequest)
            val primary = load(primaryRequest)
            ensureCurrent(operation)
            if (primary !is LocalLyricsLoadResult.Loaded) {
                val unavailable = primary as LocalLyricsLoadResult.Unavailable
                if (resumeAction != null) return failCurrent(unavailable.failure)
                publishUnavailable(currentOwner, unavailable.failure)
                return
            }
            var translationRequest: LyricsLoadRequest? = null
            var translation: LocalLyricsLoadResult.Loaded? = null
            var translated: LyricsTranslation? = null
            var translationFailure: LyricsLoadFailure? = null
            if (primary.document.kind == LyricsKind.SYNCHRONIZED) {
                try {
                    val selectedTranslation = selectedInput(operation.action, LyricsDocumentPurpose.TRANSLATION)
                    ensureCurrent(operation)
                    val translationInput = selectedTranslation ?: next.translation?.let { documents.resolve(it, operation.sourceGeneration) }
                    ensureCurrent(operation)
                    if (translationInput != null) {
                        val available = reserved.maxAccountedBytes - VALIDATION_SCRATCH_BYTES - primary.accountedBytes -
                            TRANSLATION_RESULT_ALLOWANCE_BYTES - primary.document.timeline.size.toLong() * Int.SIZE_BYTES
                        if (available <= 0L) throw LyricsParseException(LyricsParseIssue.MEMORY_LIMIT)
                        translationRequest = LyricsLoadRequest(binding.copy(documentIdentity = translationInput.documentId),
                            LyricsLoadSelection.TextDocument(translationInput), available)
                        check(authority.attachTranslation(primaryRequest, translationRequest))
                        val result = load(translationRequest)
                        ensureCurrent(operation)
                        when (result) {
                            is LocalLyricsLoadResult.Unavailable -> translationFailure = result.failure
                            is LocalLyricsLoadResult.Loaded -> {
                                if (result.accountedBytes > available) {
                                    translationFailure = LyricsLoadFailure(LyricsLoadIssue.MEMORY_LIMIT)
                                } else {
                                    translated = translationMatcher.match(primary.document, result.document,
                                        cueLyricsTimelineOrigin(context.cueReference, primary.source.origin),
                                        cueLyricsTimelineOrigin(context.cueReference, result.source.origin))
                                    ensureCurrent(operation)
                                    translation = result
                                }
                            }
                        }
                    }
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { translationFailure = entryFailure(error) }
            }
            ensureCurrent(operation)
            if (resumeAction != null && translationFailure != null) return failCurrent(translationFailure)
            if (operation.action is Action.Associate && operation.action.preview.purpose == LyricsDocumentPurpose.TRANSLATION &&
                translationFailure != null) {
                publishLoaded(currentOwner, primaryRequest, primary, translationRequest, translation, translated,
                    translationFailure, LyricsEntryNotice.STALE)
                return
            }
            when (val action = operation.action) {
                is Action.Preview -> {
                    val candidate = if (action.purpose == LyricsDocumentPurpose.PRIMARY) primary else translation
                    val input = selectedInput(action, action.purpose)
                    ensureCurrent(operation)
                    if (candidate == null || input == null) {
                        publishLoaded(currentOwner, primaryRequest, primary, translationRequest, translation, translated,
                            translationFailure, LyricsEntryNotice.STALE)
                        return
                    }
                    val preview = AssociationPreview(UUID.randomUUID().toString(), currentOwner.key, operation.sourceGeneration,
                        preferences.revision, action.purpose, input, candidate.source.contentSha256)
                    pendingPreview = preview
                    publishLoaded(currentOwner, primaryRequest, primary, translationRequest, translation, translated, translationFailure)
                    mutableState.value = mutableState.value.copy(associationPreview = LyricsAssociationPreview(preview.id, preview.purpose))
                }
                is Action.Associate -> {
                    val candidate = documents.prepareAssociation(action.preview.input, action.preview.sha256)
                    try {
                        ensureCurrent(operation)
                        val replacement = if (action.preview.purpose == LyricsDocumentPurpose.PRIMARY) next.copy(primary = candidate.association)
                            else next.copy(translation = candidate.association)
                        commit(operation, ticket, currentOwner, replacement)
                    } finally { candidate.release() }
                }
                is Action.Resume -> {
                    when (val draft = action.draft) {
                        is SuspendedDraft.Association -> {
                            val candidate = if (draft.purpose == LyricsDocumentPurpose.PRIMARY) primary else translation
                            val input = selectedInput(action, draft.purpose)
                            ensureCurrent(operation)
                            if (candidate == null || input == null || candidate.source != LyricsLoadedSource(
                                LyricsSourceOrigin.EXPLICIT_DOCUMENT, input.source.sourceId, input.candidate.objectId,
                                input.documentId, operation.sourceGeneration, draft.sha256,
                            )) return failCurrent(LyricsLoadIssue.SOURCE_CHANGED)
                            val preview = AssociationPreview(UUID.randomUUID().toString(), currentOwner.key, operation.sourceGeneration,
                                preferences.revision, draft.purpose, input, candidate.source.contentSha256)
                            pendingPreview = preview
                            publishLoaded(currentOwner, primaryRequest, primary, translationRequest, translation, translated, null)
                            mutableState.value = mutableState.value.copy(associationPreview = LyricsAssociationPreview(preview.id, preview.purpose))
                        }
                        is SuspendedDraft.Offset -> {
                            if (primary.draftDocument(primaryRequest) != draft.primary ||
                                translationRequest?.let { translation?.draftDocument(it) } != draft.translation) {
                                return failCurrent(LyricsLoadIssue.SOURCE_CHANGED)
                            }
                            publishLoaded(currentOwner, primaryRequest, primary, translationRequest, translation, translated, null)
                            mutableState.value = mutableState.value.copy(offsetDraftMs = draft.value)
                        }
                    }
                }
                is Action.Offset, is Action.Clear -> error("Handled before document acquisition")
                Action.Load, is Action.ReviewOffset -> {
                    val draft = (action as? Action.ReviewOffset)?.draft ?:
                        restoredOffset.takeIf { uiVisible && entryRoute != null }
                    if (draft == null) publishLoaded(currentOwner, primaryRequest, primary, translationRequest, translation,
                        translated, translationFailure, operation.noticeAfterLoad)
                    else restoreOffsetAfterRead(operation, draft, currentOwner, primaryRequest, primary,
                        translationRequest, translation, translated, translationFailure, action is Action.ReviewOffset)
                }
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            if (isCurrent(operation)) {
                val currentOwner = owner
                if (operation.action is Action.Resume || currentOwner == null) failCurrent(entryFailure(error))
                else publishUnavailable(currentOwner, entryFailure(error))
            }
        }
        catch (_: OutOfMemoryError) { if (isCurrent(operation)) failCurrent(LyricsLoadIssue.MEMORY_LIMIT) }
        finally {
            try {
                if (!isCurrent(operation) || loaded?.primaryRequest !== primaryRequest) primaryRequest?.let(authority::retire)
                if (isCurrent(operation)) {
                    mutableState.value = mutableState.value.copy(loading = false, saving = false,
                        canChoose = uiVisible && owner != null && pendingPreview == null && mutableState.value.offsetDraftMs == null)
                    updateSampler()
                }
            } finally {
                try { operationMemory?.close() }
                finally {
                    if (activeReadJob === attempt) activeReadJob = null
                    if (isCurrent(operation) && canStartExternalOnline(operation.key)) requestLoad(operation.key)
                }
            }
        }
    }

    private suspend fun performOnline(operation: Work, queue: QueueSnapshot, entry: QueueEntry, maxBytes: Long) {
        if (operation.action !== Action.Load) return failCurrent(LyricsLoadIssue.INVALID_INPUT)
        val external = externalReading?.takeIf { it.canAcquireOnline(entry.queueEntryId) }
        external?.markOnlineAttempt(queue.generationId, queue.queueRevision, entry.queueEntryId, operation.sourceGeneration)
        val identity = LyricsPageIdentity(entry.trackKey, queue.generationId, entry.queueEntryId, operation.sourceGeneration)
        val port = onlineLyrics ?: return publishOnlineUnavailable(OnlineLyricsIssue.UNSUPPORTED, identity)
        mutableState.value = LyricsEntryUiState(identity = identity, content = LyricsPageContent.Loading, loading = true)
        val accountId = entry.accountId ?: return publishOnlineUnavailable(OnlineLyricsIssue.LOGIN_REQUIRED, identity)
        // A saved local offset is not an online source or an authority to replay an old proposal.
        restoredOffset = null
        savedDrafts.clearOffset()
        val available = maxBytes - VALIDATION_SCRATCH_BYTES
        if (available <= 0L) return publishOnlineUnavailable(OnlineLyricsIssue.MEMORY_LIMIT, identity)
        val request = OnlineLyricsRequest(entry.trackKey, queue.generationId, queue.queueRevision, entry.queueEntryId,
            operation.sourceGeneration, accountId, available, allowNetwork = external != null ||
                uiVisible && surface == LyricsEntrySurface.PAGE && timelinePresented &&
                playConfirmation.state.value.phase == LyricsPlayPhase.IDLE)
        onlineAcquisition.set(request)
        val result = port.load(request) { onlineAcquisition.get() === request }
        ensureCurrent(operation)
        when (result) {
            is OnlineLyricsResult.Loaded -> {
                val fence = result.fence
                if (result.accountedBytes > available) return publishOnlineUnavailable(OnlineLyricsIssue.MEMORY_LIMIT, identity)
                if (fence.trackKey != entry.trackKey || fence.queueGenerationId != queue.generationId ||
                    fence.queueRevision != queue.queueRevision || fence.queueEntryId != entry.queueEntryId ||
                    fence.sourceGeneration != operation.sourceGeneration || fence.ownerHash != onlineOpaqueHash("netease:$accountId")) {
                    return publishOnlineUnavailable(OnlineLyricsIssue.SOURCE_CHANGED, identity)
                }
                onlineLoaded = result
                onlineExternalIdentity = Any()
                mutableState.value = LyricsEntryUiState(identity = identity, documentId = result.document.documentId,
                    content = LyricsPageContent.Ready(result.document, result.translation), onlineOrigin = fence.origin,
                    onlineFromCache = result.fromCache, onlineIssue = result.translationIssue)
            }
            is OnlineLyricsResult.Unavailable -> publishOnlineUnavailable(result.issue, identity)
        }
    }

    private fun publishOnlineUnavailable(issue: OnlineLyricsIssue, identity: LyricsPageIdentity? = mutableState.value.identity) {
        onlineLoaded = null
        val content = when (issue) {
            OnlineLyricsIssue.NO_LYRICS -> LyricsPageContent.Missing
            OnlineLyricsIssue.INPUT_LIMIT, OnlineLyricsIssue.MEMORY_LIMIT -> LyricsPageContent.Failed(LyricsPageFailure.INPUT_LIMIT)
            OnlineLyricsIssue.INVALID_ENCODING -> LyricsPageContent.Failed(LyricsPageFailure.INVALID_ENCODING)
            OnlineLyricsIssue.UNSUPPORTED -> LyricsPageContent.Failed(LyricsPageFailure.UNSUPPORTED)
            else -> LyricsPageContent.Failed(LyricsPageFailure.READ_FAILED)
        }
        mutableState.value = LyricsEntryUiState(identity = identity, content = content, onlineIssue = issue)
    }

    private suspend fun selectedInput(action: Action, purpose: LyricsDocumentPurpose): LyricsDocumentInput? = when (action) {
        is Action.Preview -> if (action.purpose != purpose) null else {
            action.input ?: documents.preview(action.picked.uri, action.picked.flags, sourceGeneration, action.encoding)
                .also { action.input = it }
        }
        is Action.Associate -> if (action.preview.purpose != purpose) null else action.preview.input.copy(
            expectedContentSha256 = action.preview.sha256,
        )
        is Action.Resume -> (action.draft as? SuspendedDraft.Association)?.takeIf { it.purpose == purpose }?.let { draft ->
            action.input ?: reauthorizeDraft(draft).also { action.input = it }
        }
        else -> null
    }

    private suspend fun reauthorizeDraft(draft: SuspendedDraft.Association): LyricsDocumentInput {
        val old = draft.input
        val fresh = documents.preview(old.candidate.uri, old.source.grantFlags, draft.sourceGeneration, old.encoding)
        currentCoroutineContext().ensureActive()
        // A fresh observation creates its own source ID. Every other candidate/source field and
        // the original bytes must still match; stored flags alone do not prove current read access.
        if (fresh.copy(source = fresh.source.copy(sourceId = old.source.sourceId),
            candidate = fresh.candidate.copy(sourceId = old.candidate.sourceId),
            expectedContentSha256 = old.expectedContentSha256) != old) {
            throw LyricsDocumentResolutionException(LyricsLoadIssue.SOURCE_CHANGED)
        }
        return fresh.copy(expectedContentSha256 = draft.sha256)
    }

    private suspend fun commit(
        operation: Work,
        ticket: WriteTicket,
        current: Owner,
        next: LyricsTrackPreferences,
    ) {
        ensureCurrent(operation)
        val result = tracks.commit(current.preferences.revision, next,
            LyricsTrackWriteFence(current.binding, current.key.generationId, current.key.revision, current.key.entryId)) {
            uiVisible && isCurrent(operation) && writeAuthority.get() === ticket
        }
        ensureCurrent(operation)
        when (result) {
            is LyricsTrackCommitResult.Saved, is LyricsTrackCommitResult.Unchanged -> {
                val preferences = when (result) {
                    is LyricsTrackCommitResult.Saved -> result.preferences
                    is LyricsTrackCommitResult.Unchanged -> result.preferences
                    else -> error("Not a successful commit")
                }
                val offset = operation.action as? Action.Offset
                if (offset != null) {
                    clearSavedOffset()
                    val previous = offset.previous
                    val updated = Owner(current.key, current.identity, current.context, preferences, current.binding)
                    owner = updated
                    publishLoaded(updated, previous.primaryRequest, previous.primary, previous.translationRequest,
                        previous.translation, previous.translated, previous.translationFailure, LyricsEntryNotice.SAVED)
                    return
                }
                if (!advanceSourceGeneration()) return
                pendingPreview = null
                mutableState.value = mutableState.value.copy(offsetDraftMs = null, notice = LyricsEntryNotice.SAVED)
                replaceWork(Work(UUID.randomUUID().toString(), current.key, sourceGeneration, Action.Load,
                    LyricsEntryNotice.SAVED))
            }
            is LyricsTrackCommitResult.Conflict -> {
                mutableState.value = mutableState.value.copy(preferences = result.current, offsetDraftMs = null,
                    notice = LyricsEntryNotice.CONFLICT, content = LyricsPageContent.Failed(LyricsPageFailure.READ_FAILED))
                owner = null
            }
            is LyricsTrackCommitResult.Stale -> {
                mutableState.value = mutableState.value.copy(offsetDraftMs = null, notice = LyricsEntryNotice.STALE,
                    content = LyricsPageContent.Failed(LyricsPageFailure.READ_FAILED))
                owner = null
            }
            is LyricsTrackCommitResult.Failed -> {
                mutableState.value = mutableState.value.copy(offsetDraftMs = null, trackIssue = result.issue,
                    content = LyricsPageContent.Failed(LyricsPageFailure.READ_FAILED))
                owner = null
            }
        }
    }

    private fun publishLoaded(
        current: Owner,
        primaryRequest: LyricsLoadRequest,
        primary: LocalLyricsLoadResult.Loaded,
        translationRequest: LyricsLoadRequest?,
        translation: LocalLyricsLoadResult.Loaded?,
        translated: LyricsTranslation?,
        translationFailure: LyricsLoadFailure?,
        notice: LyricsEntryNotice? = null,
    ) {
        loaded = Loaded(current, primaryRequest, primary, translationRequest, translation, translated, translationFailure)
        mutableState.value = mutableState.value.copy(identity = current.identity,
            documentId = primary.document.documentId,
            content = LyricsPageContent.Ready(primary.document, translated), preferences = current.preferences,
            sourceOrigin = primary.source.origin, failure = null, translationFailure = translationFailure,
            timelineOriginMs = cueLyricsTimelineOrigin(current.context.cueReference, primary.source.origin),
            offsetDraftMs = null, offsetDraftNeedsReview = false, canReviewOffsetDraft = false, notice = notice)
    }

    private fun publishUnavailable(current: Owner, failure: LyricsLoadFailure) {
        suspendedDraft = null
        pendingPreview = null
        loaded = null
        mutableState.value = mutableState.value.copy(identity = current.identity, preferences = current.preferences,
            documentId = null, content = failure.content(), failure = failure, sourceOrigin = null,
            offsetDraftMs = restoredOffset?.value, offsetDraftNeedsReview = restoredOffset != null, canReviewOffsetDraft = false)
    }

    private fun clearSavedOffset() {
        val recovering = restoredOffset != null
        restoredOffset = null
        savedDrafts.clearOffset()
        if (recovering && (work.value?.action is Action.Load || work.value?.action is Action.ReviewOffset)) {
            replaceWork(null)
            if (externalReading?.isCurrent == true && visible) latestKey?.let(::requestLoad)
        }
    }

    private fun rememberOffset(current: Owner, previous: Loaded, value: Int) {
        if (entryRoute != null) savedDrafts.writeOffset(offsetMetadata(current, previous.primaryRequest, previous.primary,
            previous.translationRequest, previous.translation, value))
    }

    private fun offsetMetadata(
        current: Owner,
        primaryRequest: LyricsLoadRequest,
        primary: LocalLyricsLoadResult.Loaded,
        translationRequest: LyricsLoadRequest?,
        translation: LocalLyricsLoadResult.Loaded?,
        value: Int,
    ): LyricsSavedOffsetDraft {
        val audio = current.binding
        return LyricsSavedOffsetDraft(value, current.key.revision, current.preferences.revision,
            lyricsDraftFingerprint(current.key.generationId, current.key.entryId),
            lyricsDraftFingerprint(audio.trackKey.providerId, audio.trackKey.trackId, audio.locationId, audio.sourceId),
            lyricsDraftFingerprint(audio.contentRevision.toString(), audio.sourceRevision.toString(),
                audio.authorizationEpoch.toString(), primary.documentFingerprint(primaryRequest),
                translationRequest?.let { translation?.documentFingerprint(it) }))
    }

    private fun LocalLyricsLoadResult.Loaded.documentFingerprint(request: LyricsLoadRequest): String {
        val input = (request.selection as? LyricsLoadSelection.TextDocument)?.input
        return lyricsDraftFingerprint(source.origin.name, source.sourceId, source.objectId, source.documentId,
            source.contentSha256, source.embeddedCandidateId, document.documentId, input?.encoding?.name,
            input?.candidate?.objectId, input?.candidate?.providerContentToken,
            input?.source?.kind?.name, input?.source?.grantFlags?.toString(), input?.requirePersistedRead?.toString())
    }

    private suspend fun restoreOffsetAfterRead(
        operation: Work,
        draft: LyricsSavedOffsetDraft,
        current: Owner,
        primaryRequest: LyricsLoadRequest,
        primary: LocalLyricsLoadResult.Loaded,
        translationRequest: LyricsLoadRequest?,
        translation: LocalLyricsLoadResult.Loaded?,
        translated: LyricsTranslation?,
        translationFailure: LyricsLoadFailure?,
        explicitlyReviewed: Boolean,
    ) {
        if (restoredOffset !== draft || entryRoute == null) return
        if (translationFailure != null) return failCurrent(translationFailure)
        val primaryValid = validateContext(current.context) && validate(primaryRequest, primary)
        ensureCurrent(operation)
        if (!primaryValid) return failCurrent(LyricsLoadIssue.SOURCE_CHANGED)
        val translationValid = translationRequest == null || translation == null || validate(translationRequest, translation)
        ensureCurrent(operation)
        if (!translationValid) return failCurrent(LyricsLoadIssue.SOURCE_CHANGED)
        if (restoredOffset !== draft || entryRoute == null) return
        val fresh = offsetMetadata(current, primaryRequest, primary, translationRequest, translation, draft.value)
        val sameTarget = draft.sameTarget(fresh)
        val timed = primary.document.kind == LyricsKind.SYNCHRONIZED
        val reviewed = sameTarget && timed && (draft == fresh || explicitlyReviewed)
        publishLoaded(current, primaryRequest, primary, translationRequest, translation, translated, null,
            if (reviewed) null else if (sameTarget) LyricsEntryNotice.CONFLICT else LyricsEntryNotice.STALE)
        mutableState.value = mutableState.value.copy(offsetDraftMs = draft.value,
            offsetDraftNeedsReview = !reviewed, canReviewOffsetDraft = !reviewed && sameTarget && timed)
        if (reviewed) {
            // The only recovered operation is a proposal. Saving still requires a new explicit click and CAS.
            restoredOffset = null
            savedDrafts.writeOffset(fresh)
        }
    }

    private fun playContext(): LyricsPlayContext? {
        val state = mutableState.value
        val clock = playback.clock() ?: return null
        val duration = clock.durationMs?.takeIf { it > 0L } ?: return null
        if (!uiVisible || surface != LyricsEntrySurface.PAGE || state.loading || state.saving ||
            state.associationPreview != null || state.offsetDraftMs != null || pendingPicker != null) return null
        val local = loaded
        if (local != null) {
            if (local.owner.key != latestKey || clock.queueEntryId != local.owner.key.entryId) return null
            return LyricsPlayContext(local.owner.identity, local.owner.key.revision, local.primaryRequest.binding,
                local.primary.document, state.mapping, duration, visible = true, canSeek = clock.canSeek)
        }
        val current = onlineLoaded ?: return null
        val identity = state.identity ?: return null
        val fence = current.fence
        if (clock.queueEntryId != fence.queueEntryId || latestKey != QueueKey(fence.queueGenerationId, fence.queueRevision,
                fence.queueEntryId) || identity.sourceGeneration != fence.sourceGeneration) return null
        return LyricsPlayContext(identity, fence.queueRevision, fence, current.document, state.mapping, duration,
            visible = true, canSeek = clock.canSeek)
    }

    private suspend fun validatePlayContext(context: LyricsPlayContext): Boolean {
        val online = onlineLoaded
        if (online != null) {
            if (playContext()?.identity != context.identity || online.document !== context.document || online.fence != context.audio) return false
            val valid = onlineLyrics?.validate(online) == true
            currentCoroutineContext().ensureActive()
            return valid && onlineLoaded === online && playContext()?.identity == context.identity
        }
        val current = loaded ?: return false
        if (playContext()?.identity != context.identity || current.primary.document !== context.document) return false
        if (!validate(current.primaryRequest, current.primary)) return false
        currentCoroutineContext().ensureActive()
        if (loaded !== current || playContext()?.identity != context.identity) return false
        val translationRequest = current.translationRequest
        val translation = current.translation
        return translationRequest == null || translation == null || validate(translationRequest, translation)
    }

    private fun updateSampler() {
        sampler?.cancel()
        sampler = null
        val state = mutableState.value
        val document = (state.content as? LyricsPageContent.Ready)?.document
        if (!uiVisible || surface != LyricsEntrySurface.PAGE || !timelinePresented || playConfirmation.state.value.phase != LyricsPlayPhase.IDLE || state.loading || state.saving ||
            document?.kind != LyricsKind.SYNCHRONIZED || (loaded?.primary?.document ?: onlineLoaded?.document) !== document) {
            mutableClock.value = null
            return
        }
        mutableClock.value = playback.clock()?.takeIf { it.queueEntryId == latestKey?.entryId }
        if (mutableClock.value?.isPlaying != true) return
        sampler = scope.launch {
            while (uiVisible && surface == LyricsEntrySurface.PAGE && timelinePresented && playConfirmation.state.value.phase == LyricsPlayPhase.IDLE &&
                (mutableState.value.content as? LyricsPageContent.Ready)?.document === document) {
                val sample = playback.clock()?.takeIf { it.queueEntryId == latestKey?.entryId } ?: break
                mutableClock.value = sample
                if (!sample.isPlaying) break
                val interval = lyricsNextSampleDelay(document, mutableState.value.mapping, sample, wordEmphasis, visibleLines)
                delay(interval)
                if (wordEmphasis && document.hasWordTimings) awaitDisplayFrame()
            }
        }
    }

    private fun canEdit(current: Owner): Boolean = uiVisible && !closed && current.key == latestKey &&
        current.identity.sourceGeneration == sourceGeneration && !mutableState.value.loading && !mutableState.value.saving &&
        !playConfirmation.busy && !playback.busy

    // Explicit review consumes restoredOffset before this Work finishes publishing and releasing its flags.
    // Cancellation/route exit replace Work, so the proposal's lifetime cannot retire its successful reader.
    private fun isCurrent(operation: Work): Boolean = !closed && visible && work.value === operation &&
        latestKey == operation.key && sourceGeneration == operation.sourceGeneration &&
        (uiVisible || operation.action === Action.Load) &&
        (operation.action !is Action.Resume || operation.action.draft.route === entryRoute) &&
        (operation.action !is Action.ReviewOffset || entryRoute != null)

    private suspend fun ensureCurrent(operation: Work) {
        currentCoroutineContext().ensureActive()
        if (!isCurrent(operation)) throw CancellationException("Lyrics intent retired")
    }

    private fun advanceSourceGeneration(): Boolean {
        if (sourceGeneration == Long.MAX_VALUE) {
            failCurrent(LyricsLoadIssue.INVALID_INPUT)
            return false
        }
        sourceGeneration++
        suspendedDraft = null
        if (restoredOffset == null) savedDrafts.clearOffset()
        return true
    }

    private fun failCurrent(issue: LyricsLoadIssue) = failCurrent(LyricsLoadFailure(issue))

    private fun failCurrent(failure: LyricsLoadFailure) {
        // Retire suspended readers and the transaction ticket before publishing the terminal state.
        replaceWork(null)
        suspendedDraft = null
        pendingPreview = null
        pendingPicker = null
        if (restoredOffset == null) savedDrafts.clearOffset()
        mutableState.value = mutableState.value.copy(content = failure.content(), failure = failure,
            documentId = null, loading = false, saving = false, canChoose = false,
            associationPreview = null, documentRequest = null, pickerLaunched = false,
            offsetDraftMs = restoredOffset?.value, offsetDraftNeedsReview = restoredOffset != null,
            canReviewOffsetDraft = false, sourceOrigin = null, translationFailure = null, notice = null,
            onlineOrigin = null, onlineIssue = null, onlineFromCache = false)
        playConfirmation.contextChanged()
        updateSampler()
    }

    override fun onCleared() {
        close()
        super.onCleared()
    }

    fun close() {
        if (closed) return
        externalReading?.close()
        presentation?.close()
        leave()
        closed = true
        playConfirmation.close()
        observer.cancel()
        worker.cancel()
    }

    private data class QueueKey(val generationId: String, val revision: Long, val entryId: String)
    private data class SourceKey(val generationId: String, val entryId: String)
    private data class ExternalOnlineAttempt(val generation: String, val revision: Long, val entry: String, val source: Long) {
        override fun toString(): String = "<external-current-track-read-epoch>"
    }
    private class EntryRoute
    private fun QueueKey.sourceKey() = SourceKey(generationId, entryId)
    private fun QueueSnapshot.key(): QueueKey? = currentEntryId?.let { QueueKey(generationId, queueRevision, it) }
    private class WriteTicket(val id: String)
    private class Work(val id: String, val key: QueueKey, val sourceGeneration: Long, val action: Action,
        val noticeAfterLoad: LyricsEntryNotice? = null)
    private sealed interface Action {
        data object Load : Action
        class Preview(val purpose: LyricsDocumentPurpose, val picked: Picked, val encoding: LyricsEncodingChoice) : Action {
            var input: LyricsDocumentInput? = null
        }
        class Associate(val preview: AssociationPreview) : Action
        class Resume(val draft: SuspendedDraft) : Action { var input: LyricsDocumentInput? = null }
        class ReviewOffset(val draft: LyricsSavedOffsetDraft) : Action
        class Offset(val value: Int, val expectedRevision: Long, val previous: Loaded) : Action
        class Clear(val purpose: LyricsDocumentPurpose, val expectedRevision: Long) : Action
    }
    private class Picker(val request: LyricsDocumentRequest, val key: QueueKey, val sourceGeneration: Long) {
        var launched = false
        var result: Picked? = null
    }
    private data class Picked(val uri: String, val flags: Int)
    private class AssociationPreview(val id: String, val key: QueueKey, val sourceGeneration: Long,
        val preferencesRevision: Long, val purpose: LyricsDocumentPurpose, val input: LyricsDocumentInput, val sha256: String)
    private class Owner(val key: QueueKey, val identity: LyricsPageIdentity, val context: LocalLyricsContext,
        val preferences: LyricsTrackPreferences, val binding: LyricsLoadBinding)
    private class Loaded(val owner: Owner, val primaryRequest: LyricsLoadRequest, val primary: LocalLyricsLoadResult.Loaded,
        val translationRequest: LyricsLoadRequest?, val translation: LocalLyricsLoadResult.Loaded?,
        val translated: LyricsTranslation?, val translationFailure: LyricsLoadFailure?) {
        // The external scheduler may retain this token; it never retains a retired full document.
        val externalIdentity = Any()
    }

    /** Memory only: no request authority, parsed document, timeline, SavedState or replayable commit. */
    private sealed class SuspendedDraft(val route: EntryRoute, val key: QueueKey, val sourceGeneration: Long,
        val preferencesRevision: Long, val audio: DraftAudio) {
        class Association(route: EntryRoute, key: QueueKey, sourceGeneration: Long, preferencesRevision: Long,
            audio: DraftAudio, val purpose: LyricsDocumentPurpose, val input: LyricsDocumentInput, val sha256: String) :
            SuspendedDraft(route, key, sourceGeneration, preferencesRevision, audio)
        class Offset(route: EntryRoute, key: QueueKey, sourceGeneration: Long, preferencesRevision: Long,
            audio: DraftAudio, val value: Int, val primary: DraftDocument, val translation: DraftDocument?) :
            SuspendedDraft(route, key, sourceGeneration, preferencesRevision, audio)
    }
    private data class DraftAudio(val trackKey: TrackKey, val locationId: String, val contentRevision: Long,
        val sourceId: String, val sourceRevision: Long, val authorizationEpoch: Long) {
        fun matches(context: LocalLyricsContext): Boolean = context.audio.let { audio ->
            trackKey == audio.trackKey && locationId == audio.location.locationId && contentRevision == audio.location.contentRevision &&
                sourceId == audio.source.spec.sourceId && sourceRevision == audio.source.revision && authorizationEpoch == audio.source.authEpoch
        }
    }
    private data class DraftDocument(val source: LyricsLoadedSource, val parsedDocumentId: String, val selection: LyricsLoadSelection)
    private fun LyricsLoadBinding.draftAudio() = DraftAudio(trackKey, locationId, contentRevision, sourceId, sourceRevision, authorizationEpoch)
    private fun LocalLyricsLoadResult.Loaded.draftDocument(request: LyricsLoadRequest) = DraftDocument(source, document.documentId, request.selection)

    companion object {
        private const val MAX_VISIBLE_LINES = 64
        // Includes the provider fingerprint buffers and envelopes while both documents stay visible.
        private const val VALIDATION_SCRATCH_BYTES = 262_144L
        private const val PRIMARY_ACCOUNTED_BYTES = LyricsLimits.ACCOUNTED_BYTES - VALIDATION_SCRATCH_BYTES
        private const val TRANSLATION_RESULT_ALLOWANCE_BYTES = 4_096L
    }
}

/** 只遍历实际可见行；真实字边界决定提前采样，普通行采样最多 5Hz。 */
internal fun lyricsNextSampleDelay(
    document: io.github.onlinemo.meloxwatch.data.lyrics.LyricsDocument,
    mapping: LyricsClockMapping,
    clock: LyricsMediaClock,
    wordEmphasis: Boolean,
    visibleLines: List<Int>,
): Long {
    if (!wordEmphasis || !document.hasWordTimings) return 200L
    val time = mapping.lyricTime(clock.positionMs, document.sourceOffsetMs) ?: return 200L
    var next = 200L
    for (index in visibleLines.take(64)) {
        if (index !in 0 until document.timeline.size) continue
        val words = document.timeline.line(index).wordTimings
        var low = 0
        var high = words.size
        while (low < high) {
            val middle = low + (high - low) / 2
            if (words[middle].startsAtMs <= time) low = middle + 1 else high = middle
        }
        val start = words.getOrNull(low)?.startsAtMs
        val end = words.getOrNull(low - 1)?.endsAtMs?.takeIf { it > time }
        val boundary = listOfNotNull(start, end).minOrNull() ?: continue
        val wait = ceil((boundary.toDouble() - time.toDouble()) / clock.speed.toDouble()).toLong().coerceAtLeast(1L)
        next = minOf(next, wait)
    }
    return next
}

private suspend fun awaitLyricsDisplayFrame(): Unit = suspendCancellableCoroutine { continuation ->
    val choreographer = Choreographer.getInstance()
    val callback = Choreographer.FrameCallback { if (continuation.isActive) continuation.resume(Unit) }
    continuation.invokeOnCancellation { choreographer.removeFrameCallback(callback) }
    choreographer.postFrameCallback(callback)
}

private fun entryFailure(error: Exception): LyricsLoadFailure = when (error) {
    is LyricsDocumentResolutionException -> LyricsLoadFailure(error.issue)
    is SourceReadException -> LyricsLoadFailure(error.lyricsResolutionIssue(), sourceIssue = error.issue)
    is LibraryException -> LyricsLoadFailure(LyricsLoadIssue.SOURCE_UNAVAILABLE, libraryIssue = error.issue)
    is LyricsParseException -> LyricsLoadFailure(if (error.issue == LyricsParseIssue.MEMORY_LIMIT) LyricsLoadIssue.MEMORY_LIMIT
        else LyricsLoadIssue.PARSE_FAILED, parseIssue = error.issue)
    is IllegalArgumentException -> LyricsLoadFailure(LyricsLoadIssue.INVALID_INPUT)
    else -> LyricsLoadFailure(LyricsLoadIssue.IO_FAILURE)
}

private fun LyricsLoadFailure.content(): LyricsPageContent = when (issue) {
    LyricsLoadIssue.NO_LYRICS -> LyricsPageContent.Missing
    LyricsLoadIssue.PERMISSION_DENIED, LyricsLoadIssue.PERMISSION_REVOKED -> LyricsPageContent.Failed(LyricsPageFailure.PERMISSION_REQUIRED)
    LyricsLoadIssue.INPUT_TOO_LARGE, LyricsLoadIssue.MEMORY_LIMIT, LyricsLoadIssue.READ_LIMIT -> LyricsPageContent.Failed(LyricsPageFailure.INPUT_LIMIT)
    LyricsLoadIssue.PARSE_FAILED -> LyricsPageContent.Failed(if (parseIssue in setOf(LyricsParseIssue.INVALID_ENCODING,
        LyricsParseIssue.ENCODING_MISMATCH, LyricsParseIssue.UNSUPPORTED_ENCODING)) LyricsPageFailure.INVALID_ENCODING else LyricsPageFailure.READ_FAILED)
    LyricsLoadIssue.EMBEDDED_FAILED -> LyricsPageContent.Failed(LyricsPageFailure.UNSUPPORTED)
    else -> LyricsPageContent.Failed(LyricsPageFailure.READ_FAILED)
}
