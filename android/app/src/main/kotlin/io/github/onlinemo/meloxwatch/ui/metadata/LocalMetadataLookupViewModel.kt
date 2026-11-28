package io.github.onlinemo.meloxwatch.ui.metadata

import androidx.annotation.MainThread
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.onlinemo.meloxwatch.data.artwork.ArtworkException
import io.github.onlinemo.meloxwatch.data.artwork.ArtworkIssue
import io.github.onlinemo.meloxwatch.data.auth.AuthException
import io.github.onlinemo.meloxwatch.data.auth.AuthIssue
import io.github.onlinemo.meloxwatch.data.backup.BackupException
import io.github.onlinemo.meloxwatch.data.backup.BackupIssue
import io.github.onlinemo.meloxwatch.data.library.LibraryException
import io.github.onlinemo.meloxwatch.data.library.LibraryIssue
import io.github.onlinemo.meloxwatch.data.lyrics.LocalLyricsParser
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsLimits
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsMemoryOwner
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsParseException
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsParseIssue
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.metadata.LocalMetadataClient
import io.github.onlinemo.meloxwatch.data.metadata.LocalMetadataStore
import io.github.onlinemo.meloxwatch.data.metadata.MetadataClearTarget
import io.github.onlinemo.meloxwatch.data.metadata.MetadataLookupException
import io.github.onlinemo.meloxwatch.data.metadata.MetadataLookupIssue
import io.github.onlinemo.meloxwatch.data.metadata.MetadataLookupTarget
import io.github.onlinemo.meloxwatch.data.metadata.MetadataLyricsPages
import io.github.onlinemo.meloxwatch.data.metadata.MetadataProvenance
import io.github.onlinemo.meloxwatch.data.metadata.MetadataPurpose
import io.github.onlinemo.meloxwatch.data.metadata.MetadataSearchQuery
import io.github.onlinemo.meloxwatch.data.metadata.MetadataStoreException
import io.github.onlinemo.meloxwatch.data.metadata.MetadataStoreIssue
import io.github.onlinemo.meloxwatch.data.metadata.metadataSha256
import io.github.onlinemo.meloxwatch.data.netease.NeteaseLyricsException
import io.github.onlinemo.meloxwatch.data.netease.NeteaseLyricsIssue
import io.github.onlinemo.meloxwatch.data.network.NetworkException
import io.github.onlinemo.meloxwatch.data.network.NetworkIssue
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesFailure
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesState
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesWriteResult
import io.github.onlinemo.meloxwatch.data.preferences.UserPreferencesRepository
import io.github.onlinemo.meloxwatch.data.queue.QueueStore
import io.github.onlinemo.meloxwatch.data.themes.ThemeBitmapLease
import io.github.onlinemo.meloxwatch.data.themes.ThemeException
import io.github.onlinemo.meloxwatch.data.themes.ThemeIssue
import io.github.onlinemo.meloxwatch.model.OnlineCatalogException
import io.github.onlinemo.meloxwatch.model.OnlineCatalogIssue
import io.github.onlinemo.meloxwatch.model.QueueSnapshot
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Search, preview and confirmation are separate intents. One collectLatest worker joins actual
 * transport/provider retirement before the latest pending intent starts. SavedState carries only
 * a small reviewed draft; neither a request nor an association confirmation is replayed.
 */
@MainThread
internal class LocalMetadataLookupViewModel(
    private val store: LocalMetadataStore,
    private val client: suspend () -> LocalMetadataClient,
    private val preferences: UserPreferencesRepository,
    private val queues: QueueStore,
    private val memory: LyricsMemoryOwner,
    private val previewArtwork: suspend (String, ByteArray) -> ThemeBitmapLease?,
    private val awaitReadReady: suspend () -> Unit = {},
    savedStateHandle: SavedStateHandle = SavedStateHandle(),
    private val scopeOverride: CoroutineScope? = null,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val scope get() = scopeOverride ?: viewModelScope
    private val saved = savedStateHandle
    private val mutableState = MutableStateFlow(LocalMetadataLookupUiState())
    val state = mutableState.asStateFlow()
    private class Page(val kind: LocalMetadataKind)
    private val visibility = MutableStateFlow<Page?>(null)
    private val work = MutableStateFlow<Work?>(null)
    private val authority = AtomicReference<Work?>()
    private val watchedTrack = MutableStateFlow<TrackKey?>(null)
    @Volatile private var closed = false
    @Volatile private var latestQueue: QueueKey? = null
    private var target: MetadataLookupTarget? = null
    private var clearTarget: MetadataClearTarget? = null
    private var preview: Preview? = null
    private var candidates: List<MetadataProvenance> = emptyList()
    private var searched: MetadataSearchQuery? = null
    private var nextOffset = 0L
    private var pendingSetting: Boolean? = null
    private var deferredCheck = false
    private var retry: Retry = Retry.Open
    private var draftTrack: String? = saved[TRACK]
    private var draftKind: String? = saved[KIND]
    private var draftTitle: String = bounded(saved[TITLE], 256)
    private var draftArtist: String = bounded(saved[ARTIST], 512)
    private var draftSendTitle: Boolean = saved[SEND_TITLE] ?: true
    private var draftSendArtist: Boolean = saved[SEND_ARTIST] ?: true
    private var presentation: Presentation? = null

    private val worker = scope.launch {
        work.collectLatest { operation ->
            if (operation == null) return@collectLatest
            try { perform(operation) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: OutOfMemoryError) { failOperation(operation, LocalMetadataIssue.LOW_MEMORY) }
            catch (failure: Exception) { failOperation(operation, metadataFailure(failure)) }
            finally {
                if (authority.compareAndSet(operation, null)) {
                    mutableState.value = mutableState.value.copy(working = false, initializing = false)
                    work.value = null
                    if (deferredCheck && visibility.value != null) {
                        deferredCheck = false
                        replace(Action.Check)
                    }
                }
            }
        }
    }

    private val observer = scope.launch {
        visibility.collectLatest { page ->
            if (page == null) return@collectLatest
            try {
                awaitReadReady()
                coroutineScope {
                    launch {
                        queues.snapshots().collect { queue ->
                            if (visibility.value !== page) return@collect
                            val next = queue?.key()
                            val previous = latestQueue
                            latestQueue = next
                            if (previous != null && previous != next) {
                                reopen(LocalMetadataIssue.SOURCE_CHANGED)
                            } else if (next == null && target != null) reopen(LocalMetadataIssue.NO_TRACK)
                        }
                    }
                    launch {
                        preferences.state.collect { current ->
                            if (visibility.value !== page) return@collect
                            val enabled = (current as? PreferencesState.Ready)?.preferences?.metadataLookupEnabled == true &&
                                pendingSetting != false
                            val wasEnabled = mutableState.value.enabled
                            mutableState.value = mutableState.value.copy(enabled = enabled)
                            if (wasEnabled && !enabled && authority.get()?.action !is Action.Setting) {
                                reopen(if (current is PreferencesState.Ready) null else LocalMetadataIssue.STORAGE)
                            }
                        }
                    }
                    launch {
                        watchedTrack.collectLatest { key ->
                            if (key == null) return@collectLatest
                            store.changes(key, page.kind.purpose()).collect {
                                if (visibility.value !== page || watchedTrack.value != key) return@collect
                                when (authority.get()?.action) {
                                    is Action.Confirm, is Action.Clear, is Action.Setting, is Action.Open -> deferredCheck = true
                                    else -> replace(Action.Check)
                                }
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: OutOfMemoryError) { failObservation(page, LocalMetadataIssue.LOW_MEMORY) }
            catch (failure: Exception) { failObservation(page, metadataFailure(failure)) }
        }
    }

    /** Activity/window ownership retires authority synchronously, before Compose disposes the page. */
    fun bindPresentation(kind: LocalMetadataKind): Presentation {
        check(!closed)
        presentation?.close()
        return Presentation(kind).also { presentation = it }
    }

    inner class Presentation internal constructor(private val kind: LocalMetadataKind) : AutoCloseable {
        private var open = true
        fun show() { if (open && presentation === this) setVisible(true, kind) }
        override fun close() {
            if (!open) return
            open = false
            if (presentation !== this) return
            presentation = null
            setVisible(false, kind)
        }
    }

    fun setVisible(value: Boolean, kind: LocalMetadataKind = mutableState.value.kind) {
        if (closed) return
        if (!value) {
            if (visibility.value == null) return
            saveDraft()
            visibility.value = null
            replace(null)
            retirePreview()
            target = null
            clearTarget = null
            watchedTrack.value = null
            candidates = emptyList()
            searched = null
            latestQueue = null
            mutableState.value = mutableState.value.copy(initializing = false, lookupTargetReady = false, working = false,
                candidates = emptyList(), more = false, preview = null, associated = null, canClear = false,
                associationPending = false,
                targetTitle = null, targetArtist = null, issue = LocalMetadataIssue.CLOSED, notice = null)
            return
        }
        if (visibility.value?.kind == kind) return
        retirePreview()
        visibility.value = Page(kind)
        latestQueue = null
        target = null
        clearTarget = null
        watchedTrack.value = null
        mutableState.value = LocalMetadataLookupUiState(kind = kind)
        replace(Action.Open())
    }

    fun setEnabled(value: Boolean) {
        if (!canAct() || value == mutableState.value.enabled && pendingSetting == null) return
        val current = preferences.state.value as? PreferencesState.Ready ?: return
        if (!current.canWrite) return
        pendingSetting = value
        retirePreview()
        candidates = emptyList()
        searched = null
        mutableState.value = mutableState.value.copy(enabled = if (value) mutableState.value.enabled else false,
            candidates = emptyList(), more = false, issue = null, notice = null)
        retry = Retry.Open
        replace(Action.Setting(value, current.preferences.revision))
    }

    fun setTitle(value: String) = edit { it.copy(title = clipMetadataField(value, 256)) }
    fun setArtist(value: String) = edit { it.copy(artist = clipMetadataField(value, 512)) }
    fun setSendTitle(value: Boolean) = edit { it.copy(sendTitle = value) }
    fun setSendArtist(value: Boolean) = edit { it.copy(sendArtist = value) }

    private fun edit(transform: (LocalMetadataLookupUiState) -> LocalMetadataLookupUiState) {
        if (!idle() || preview != null || !mutableState.value.enabled) return
        candidates = emptyList()
        searched = null
        nextOffset = 0L
        mutableState.value = transform(mutableState.value).copy(candidates = emptyList(), more = false, issue = null, notice = null)
        saveDraft()
    }

    fun search() {
        if (!idle() || preview != null || !networkEnabled() || target == null) return
        val current = mutableState.value
        val query = try { MetadataSearchQuery.reviewed(current.title, current.artist, current.sendTitle, current.sendArtist) }
        catch (_: MetadataStoreException) {
            mutableState.value = current.copy(issue = LocalMetadataIssue.INVALID_QUERY, notice = null)
            return
        }
        retirePreview()
        candidates = emptyList()
        searched = query
        nextOffset = 0L
        mutableState.value = current.copy(candidates = emptyList(), more = false, issue = null, notice = null)
        saveDraft()
        retry = Retry.Search(query, 0L)
        replace(Action.Search(query, 0L, target!!))
    }

    fun more() {
        val query = searched ?: return
        val captured = target ?: return
        if (!idle() || !networkEnabled() || !mutableState.value.more || preview != null) return
        retry = Retry.Search(query, nextOffset)
        replace(Action.Search(query, nextOffset, captured))
    }

    fun preview(id: String) {
        val selected = candidates.singleOrNull { it.songId == id } ?: return
        val captured = target ?: return
        if (!idle() || !networkEnabled()) return
        retirePreview()
        retry = Retry.Preview(id)
        replace(Action.Preview(selected, captured))
    }

    fun previewPage(page: Int) {
        val current = preview ?: return
        val pages = current.pages ?: return
        if (!idle() || page !in 0 until pages.count) return
        mutableState.value = mutableState.value.copy(preview = current.display(page))
    }

    fun cancelPreview() {
        if (!canAct()) return
        replace(null)
        retirePreview()
        retry = Retry.Open
        mutableState.value = mutableState.value.copy(working = false, preview = null, issue = null, notice = null)
    }

    fun confirm(id: String) {
        val current = preview ?: return
        if (!idle() || !networkEnabled() || current.id != id || current.target !== target) return
        retry = Retry.Open
        replace(Action.Confirm(current))
    }

    fun clear() {
        val current = clearTarget ?: return
        if (!idle() || !mutableState.value.canClear) return
        retry = Retry.Open
        retirePreview()
        replace(Action.Clear(current))
    }

    /** Retries reads or an explicitly selected request; a possibly committed write is never replayed. */
    fun retry() {
        if (!idle()) return
        when (val next = retry) {
            Retry.Open -> { preferences.retryRead(); reopen(null, restartObservation = true) }
            is Retry.Search -> {
                val captured = target ?: return reopen(null)
                if (!networkEnabled()) return
                replace(Action.Search(next.query, next.offset, captured))
            }
            is Retry.Preview -> preview(next.id)
        }
    }

    private suspend fun perform(operation: Work) {
        requireCurrent(operation)
        when (val action = operation.action) {
            is Action.Open -> open(operation, action)
            Action.Check -> {
                val lookup = target
                val clear = clearTarget
                val valid = (lookup == null || store.isCurrent(lookup)) &&
                    (clear == null || store.clearStillCurrent(clear))
                requireCurrent(operation)
                if (!valid) reopen(LocalMetadataIssue.SOURCE_CHANGED)
            }
            is Action.Setting -> {
                val current = preferences.state.value as? PreferencesState.Ready
                    ?: throw MetadataStoreException(MetadataStoreIssue.IO)
                val result = preferences.save(action.revision, current.preferences.asDraft().copy(metadataLookupEnabled = action.enabled))
                requireCurrent(operation)
                pendingSetting = null
                when (result) {
                    is PreferencesWriteResult.Saved -> mutableState.value = mutableState.value.copy(enabled = result.preferences.metadataLookupEnabled)
                    is PreferencesWriteResult.Unchanged -> mutableState.value = mutableState.value.copy(enabled = result.preferences.metadataLookupEnabled)
                    is PreferencesWriteResult.Failed -> throw MetadataStoreException(if (result.failure == PreferencesFailure.BUSY)
                        MetadataStoreIssue.BUSY else MetadataStoreIssue.IO)
                    else -> throw MetadataStoreException(MetadataStoreIssue.CONFLICT)
                }
                reopen(null, restartObservation = true)
            }
            is Action.Search -> {
                requireNetwork(operation, action.target)
                val page = client().search(action.query, action.offset)
                requireNetwork(operation, action.target)
                if (page.rawCount > Long.MAX_VALUE - action.offset) throw MetadataStoreException(MetadataStoreIssue.INVALID_RECORD)
                candidates = page.candidates
                nextOffset = action.offset + page.rawCount
                mutableState.value = mutableState.value.copy(candidates = candidates.map(MetadataProvenance::display),
                    more = page.more, preview = null, issue = if (candidates.isEmpty()) LocalMetadataIssue.NO_RESULTS else null, notice = null)
            }
            is Action.Preview -> loadPreview(operation, action)
            is Action.Confirm -> {
                val selected = action.preview
                val retained = selected.lyricsMemory?.retain()
                try {
                    requireNetwork(operation, selected.target)
                    val sha256 = withContext(workDispatcher) { metadataSha256(selected.bytes) }
                    if (sha256 != selected.sha256) throw MetadataStoreException(MetadataStoreIssue.SOURCE_CHANGED)
                    requireCurrent(operation)
                    val committed = store.apply(selected.target, selected.bytes, selected.provenance) {
                        isCurrent(operation) && networkEnabled()
                    }
                    requireCurrent(operation)
                    target = committed.target
                    retirePreview()
                    mutableState.value = mutableState.value.copy(associated = committed.association.provenance?.display(),
                        associationPending = false, notice = LocalMetadataNotice.SAVED, issue = null)
                    replace(Action.Open(notice = LocalMetadataNotice.SAVED))
                } finally { retained?.close() }
            }
            is Action.Clear -> {
                if (!store.clearStillCurrent(action.target)) throw MetadataStoreException(MetadataStoreIssue.SOURCE_CHANGED)
                requireCurrent(operation)
                val committed = store.clear(action.target) { isCurrent(operation) }
                requireCurrent(operation)
                clearTarget = committed.target
                mutableState.value = mutableState.value.copy(associated = null, associationPending = false, canClear = false,
                    notice = LocalMetadataNotice.CLEARED, issue = null)
                replace(Action.Open(notice = LocalMetadataNotice.CLEARED))
            }
        }
    }

    private suspend fun open(operation: Work, action: Action.Open) {
        awaitReadReady()
        requireCurrent(operation)
        pendingSetting = null
        val purpose = operation.page.kind.purpose()
        val clear = store.captureClear(purpose)
        val association = store.read(clear.trackKey, purpose)
        requireCurrent(operation)
        clearTarget = clear
        val sameDraft = draftTrack == clear.trackKey.trackId && draftKind == operation.page.kind.name
        if (!sameDraft) {
            draftTitle = clipMetadataField(clear.title.orEmpty(), 256)
            draftArtist = clipMetadataField(clear.artist.orEmpty(), 512)
            draftSendTitle = true
            draftSendArtist = true
            draftTrack = clear.trackKey.trackId
            draftKind = operation.page.kind.name
        }
        val settings = preferences.state.value as? PreferencesState.Ready
        mutableState.value = mutableState.value.copy(enabled = settings?.preferences?.metadataLookupEnabled == true && pendingSetting != false,
            targetTitle = clear.displayTitle, targetArtist = clear.artist, title = draftTitle, artist = draftArtist,
            sendTitle = draftSendTitle, sendArtist = draftSendArtist, associated = association.provenance?.display(),
            associationPending = association.sha256 != null && association.assetId == null,
            canClear = association.sha256 != null, issue = action.issue, notice = action.notice)
        saveDraft()
        try {
            val captured = store.capture(purpose)
            requireCurrent(operation)
            if (captured.context.audio.trackKey != clear.trackKey || captured.associationRevision != association.revision ||
                captured.trackVersionId != clear.trackVersionId || captured.lyricsRevision != clear.lyricsRevision ||
                captured.fence.queueGenerationId != clear.generationId || captured.fence.queueRevision != clear.queueRevision ||
                captured.fence.queueEntryId != clear.entryId ||
                latestQueue != null && latestQueue != captured.key()) throw MetadataStoreException(MetadataStoreIssue.SOURCE_CHANGED)
            target = captured
            mutableState.value = mutableState.value.copy(lookupTargetReady = true)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            requireCurrent(operation)
            target = null
            mutableState.value = mutableState.value.copy(lookupTargetReady = false, issue = metadataFailure(failure))
        }
        watchedTrack.value = clear.trackKey
    }

    private suspend fun loadPreview(operation: Work, action: Action.Preview) {
        requireNetwork(operation, action.target)
        var reserved: LyricsMemoryOwner.Lease? = null
        var image: ThemeBitmapLease? = null
        try {
            val api = client()
            val artifact: io.github.onlinemo.meloxwatch.data.metadata.MetadataArtifact
            var pages: MetadataLyricsPages? = null
            if (action.target.purpose == MetadataPurpose.LYRICS) {
                reserved = memory.acquire(this) ?: throw MetadataStoreException(MetadataStoreIssue.LOW_MEMORY)
                val allowance = reserved.maxAccountedBytes - VALIDATION_BYTES - LyricsLimits.INPUT_BYTES - PAGE_INDEX_BYTES
                if (allowance <= 0L) throw MetadataStoreException(MetadataStoreIssue.LOW_MEMORY)
                artifact = api.lyrics(action.candidate, allowance)
                requireNetwork(operation, action.target)
                val document = LocalLyricsParser(LyricsLimits(maxAccountedBytes = allowance), workDispatcher).parse(artifact.bytes)
                val remaining = reserved.maxAccountedBytes - document.accountedResidentBytes -
                    artifact.bytes.size.toLong() * 2L - VALIDATION_BYTES
                if (remaining <= 0L) throw MetadataStoreException(MetadataStoreIssue.LOW_MEMORY)
                pages = MetadataLyricsPages.create(document, minOf(PAGE_INDEX_BYTES, remaining))
            } else {
                artifact = api.artwork(action.candidate)
                requireNetwork(operation, action.target)
            }
            val id = UUID.randomUUID().toString()
            val sha256 = withContext(workDispatcher) { metadataSha256(artifact.bytes) }
            if (action.target.purpose == MetadataPurpose.ARTWORK) {
                image = previewArtwork(metadataSha256(("metadata-preview-v1:$id:$sha256").toByteArray()), artifact.bytes)
                    ?: throw MetadataLookupException(MetadataLookupIssue.NO_ARTWORK)
            }
            requireNetwork(operation, action.target)
            val ready = Preview(id, action.target, artifact.bytes, sha256, artifact.provenance, pages, reserved, image)
            retirePreview()
            preview = ready
            reserved = null
            image = null
            mutableState.value = mutableState.value.copy(preview = ready.display(0), issue = null, notice = null)
        } finally { reserved?.close(); image?.close() }
    }

    private suspend fun requireNetwork(operation: Work, captured: MetadataLookupTarget) {
        requireCurrent(operation)
        if (!networkEnabled()) throw MetadataStoreException(MetadataStoreIssue.SOURCE_CHANGED)
        if (!store.isCurrent(captured)) throw MetadataStoreException(MetadataStoreIssue.SOURCE_CHANGED)
        requireCurrent(operation)
    }

    private fun failOperation(operation: Work, issue: LocalMetadataIssue) {
        if (!isCurrent(operation)) return
        if (operation.action is Action.Confirm || operation.action is Action.Clear) {
            target = null
            clearTarget = null
            retry = Retry.Open
            retirePreview()
            mutableState.value = mutableState.value.copy(lookupTargetReady = false, canClear = false)
        }
        if (operation.action is Action.Setting) pendingSetting = null
        if (issue == LocalMetadataIssue.SOURCE_CHANGED) {
            target = null
            candidates = emptyList()
            searched = null
            retry = Retry.Open
            retirePreview()
            mutableState.value = mutableState.value.copy(lookupTargetReady = false)
        }
        mutableState.value = mutableState.value.copy(issue = issue, notice = null,
            enabled = networkEnabled(), candidates = candidates.map(MetadataProvenance::display),
            more = if (target == null) false else mutableState.value.more)
    }

    private fun failObservation(page: Page, issue: LocalMetadataIssue) {
        if (visibility.value !== page) return
        replace(null)
        retirePreview()
        target = null
        clearTarget = null
        watchedTrack.value = null
        retry = Retry.Open
        mutableState.value = mutableState.value.copy(initializing = false, lookupTargetReady = false,
            working = false, canClear = false, issue = issue)
    }

    private fun networkEnabled(): Boolean = pendingSetting != false &&
        (preferences.state.value as? PreferencesState.Ready)?.preferences?.metadataLookupEnabled == true

    private fun canAct(): Boolean = !closed && visibility.value != null
    private fun idle(): Boolean = canAct() && !mutableState.value.initializing && !mutableState.value.working
    private fun isCurrent(operation: Work): Boolean = !closed && visibility.value === operation.page &&
        authority.get() === operation && (operation.key == null || latestQueue == operation.key)
    private suspend fun requireCurrent(operation: Work) {
        currentCoroutineContext().ensureActive()
        if (!isCurrent(operation)) throw CancellationException("Metadata intent retired")
    }

    private fun replace(action: Action?) {
        val page = visibility.value
        val next = if (action == null || page == null) null else Work(page, action,
            if (action is Action.Open || action is Action.Setting) null else latestQueue)
        authority.set(next)
        work.value = next
        mutableState.value = mutableState.value.copy(working = next != null && action !is Action.Open && action !== Action.Check,
            lookupTargetReady = if (action is Action.Open) false else mutableState.value.lookupTargetReady,
            initializing = action is Action.Open, issue = if (action is Action.Open) action.issue else mutableState.value.issue)
    }

    private fun reopen(issue: LocalMetadataIssue?, restartObservation: Boolean = false) {
        if (restartObservation) visibility.value?.let { visibility.value = Page(it.kind) }
        retirePreview()
        candidates = emptyList()
        searched = null
        target = null
        clearTarget = null
        watchedTrack.value = null
        retry = Retry.Open
        deferredCheck = false
        mutableState.value = mutableState.value.copy(candidates = emptyList(), more = false, lookupTargetReady = false, canClear = false,
            preview = null, issue = issue, notice = null)
        replace(Action.Open(issue = issue))
    }

    private fun retirePreview() {
        val old = preview
        preview = null
        old?.lyricsMemory?.close()
        old?.image?.close()
        mutableState.value = mutableState.value.copy(preview = null)
    }

    private fun saveDraft() {
        val current = mutableState.value
        if (current.targetTitle != null) {
            draftTitle = current.title
            draftArtist = current.artist
            draftSendTitle = current.sendTitle
            draftSendArtist = current.sendArtist
        }
        saved[TRACK] = draftTrack
        saved[KIND] = draftKind
        saved[TITLE] = draftTitle
        saved[ARTIST] = draftArtist
        saved[SEND_TITLE] = draftSendTitle
        saved[SEND_ARTIST] = draftSendArtist
    }

    fun close() {
        if (closed) return
        presentation?.close()
        setVisible(false)
        closed = true
        authority.set(null)
        worker.cancel()
        observer.cancel()
    }

    override fun onCleared() { close(); super.onCleared() }

    private data class QueueKey(val generation: String, val revision: Long, val entry: String)
    private fun QueueSnapshot.key(): QueueKey? = currentEntryId?.let { QueueKey(generationId, queueRevision, it) }
    private fun MetadataLookupTarget.key() = QueueKey(fence.queueGenerationId, fence.queueRevision, fence.queueEntryId)
    private class Work(val page: Page, val action: Action, val key: QueueKey?)
    private sealed interface Action {
        class Open(val issue: LocalMetadataIssue? = null, val notice: LocalMetadataNotice? = null) : Action
        data object Check : Action
        class Setting(val enabled: Boolean, val revision: Long) : Action
        class Search(val query: MetadataSearchQuery, val offset: Long, val target: MetadataLookupTarget) : Action
        class Preview(val candidate: MetadataProvenance, val target: MetadataLookupTarget) : Action
        class Confirm(val preview: LocalMetadataLookupViewModel.Preview) : Action
        class Clear(val target: MetadataClearTarget) : Action
    }
    private sealed interface Retry {
        data object Open : Retry
        class Search(val query: MetadataSearchQuery, val offset: Long) : Retry
        class Preview(val id: String) : Retry
    }
    private class Preview(val id: String, val target: MetadataLookupTarget, val bytes: ByteArray,
        val sha256: String, val provenance: MetadataProvenance, val pages: MetadataLyricsPages?,
        val lyricsMemory: LyricsMemoryOwner.Lease?, val image: ThemeBitmapLease?) {
        fun display(page: Int) = LocalMetadataPreview(id, provenance.display(), pages?.text(page),
            page, pages?.count ?: 1, image?.key)
    }

    companion object {
        private const val VALIDATION_BYTES = 262_144L
        private const val PAGE_INDEX_BYTES = 131_072L
        private const val TRACK = "metadata.draft.track"
        private const val KIND = "metadata.draft.kind"
        private const val TITLE = "metadata.draft.title"
        private const val ARTIST = "metadata.draft.artist"
        private const val SEND_TITLE = "metadata.draft.sendTitle"
        private const val SEND_ARTIST = "metadata.draft.sendArtist"
        private fun bounded(value: String?, limit: Int): String = value?.takeIf { it.length <= limit && it.none(Char::isISOControl) }.orEmpty()
    }
}

private fun clipMetadataField(value: String, limit: Int): String {
    var end = minOf(value.length, limit)
    if (end < value.length && end > 0 && Character.isHighSurrogate(value[end - 1]) && Character.isLowSurrogate(value[end])) end--
    return value.substring(0, end)
}

private fun LocalMetadataKind.purpose(): MetadataPurpose = when (this) {
    LocalMetadataKind.LYRICS -> MetadataPurpose.LYRICS
    LocalMetadataKind.ARTWORK -> MetadataPurpose.ARTWORK
}
private fun MetadataProvenance.display() = LocalMetadataCandidate(songId, title, artist, album)

private fun metadataFailure(failure: Exception): LocalMetadataIssue = when (failure) {
    is BackupException -> if (failure.issue == BackupIssue.BUSY) LocalMetadataIssue.BUSY else LocalMetadataIssue.STORAGE
    is LibraryException -> when (failure.issue) {
        LibraryIssue.BUSY -> LocalMetadataIssue.BUSY
        LibraryIssue.STORAGE_FULL -> LocalMetadataIssue.LOW_SPACE
        LibraryIssue.STORAGE_UNAVAILABLE, LibraryIssue.RECOVERY_REQUIRED -> LocalMetadataIssue.STORAGE
        else -> LocalMetadataIssue.SOURCE_CHANGED
    }
    is MetadataStoreException -> when (failure.issue) {
        MetadataStoreIssue.INVALID_INPUT -> LocalMetadataIssue.NOT_LOCAL
        MetadataStoreIssue.INVALID_RECORD -> LocalMetadataIssue.INVALID_RESPONSE
        MetadataStoreIssue.TRACK_MISSING -> LocalMetadataIssue.NO_TRACK
        MetadataStoreIssue.BUSY -> LocalMetadataIssue.BUSY
        MetadataStoreIssue.SOURCE_CHANGED, MetadataStoreIssue.CONFLICT -> LocalMetadataIssue.SOURCE_CHANGED
        MetadataStoreIssue.INPUT_LIMIT -> LocalMetadataIssue.INPUT_LIMIT
        MetadataStoreIssue.LOW_SPACE -> LocalMetadataIssue.LOW_SPACE
        MetadataStoreIssue.LOW_MEMORY -> LocalMetadataIssue.LOW_MEMORY
        MetadataStoreIssue.IO -> LocalMetadataIssue.STORAGE
    }
    is MetadataLookupException -> when (failure.issue) {
        MetadataLookupIssue.NO_LYRICS -> LocalMetadataIssue.NO_LYRICS
        MetadataLookupIssue.NO_ARTWORK -> LocalMetadataIssue.NO_ARTWORK
        MetadataLookupIssue.INVALID_RESPONSE -> LocalMetadataIssue.INVALID_RESPONSE
    }
    is AuthException -> when (failure.issue) {
        AuthIssue.NETWORK_UNAVAILABLE -> LocalMetadataIssue.OFFLINE
        AuthIssue.TIMEOUT -> LocalMetadataIssue.TIMEOUT
        AuthIssue.CERTIFICATE -> LocalMetadataIssue.CERTIFICATE
        AuthIssue.RATE_LIMITED -> LocalMetadataIssue.RATE_LIMITED
        AuthIssue.BUSY -> LocalMetadataIssue.BUSY
        AuthIssue.CLOSED -> LocalMetadataIssue.CLOSED
        AuthIssue.LOGIN_REQUIRED -> LocalMetadataIssue.SERVICE_UNAVAILABLE
        else -> LocalMetadataIssue.INVALID_RESPONSE
    }
    is OnlineCatalogException -> when (failure.issue) {
        OnlineCatalogIssue.OFFLINE -> LocalMetadataIssue.OFFLINE
        OnlineCatalogIssue.TIMEOUT -> LocalMetadataIssue.TIMEOUT
        OnlineCatalogIssue.CERTIFICATE -> LocalMetadataIssue.CERTIFICATE
        OnlineCatalogIssue.RATE_LIMITED -> LocalMetadataIssue.RATE_LIMITED
        OnlineCatalogIssue.BUSY -> LocalMetadataIssue.BUSY
        OnlineCatalogIssue.CLOSED -> LocalMetadataIssue.CLOSED
        OnlineCatalogIssue.LOGIN_REQUIRED, OnlineCatalogIssue.SESSION_UNAVAILABLE -> LocalMetadataIssue.SERVICE_UNAVAILABLE
        OnlineCatalogIssue.STORAGE_FULL -> LocalMetadataIssue.LOW_SPACE
        OnlineCatalogIssue.STORAGE_UNAVAILABLE -> LocalMetadataIssue.STORAGE
        OnlineCatalogIssue.STALE_VIEW -> LocalMetadataIssue.SOURCE_CHANGED
        else -> LocalMetadataIssue.INVALID_RESPONSE
    }
    is NetworkException -> when (failure.issue) {
        NetworkIssue.UNAVAILABLE -> LocalMetadataIssue.OFFLINE
        NetworkIssue.TIMEOUT -> LocalMetadataIssue.TIMEOUT
        NetworkIssue.CERTIFICATE -> LocalMetadataIssue.CERTIFICATE
        NetworkIssue.BUSY -> LocalMetadataIssue.BUSY
        NetworkIssue.CLOSED -> LocalMetadataIssue.CLOSED
        NetworkIssue.RESPONSE_TOO_LARGE -> LocalMetadataIssue.INPUT_LIMIT
        NetworkIssue.HTTP -> if (failure.statusCode == 429) LocalMetadataIssue.RATE_LIMITED else LocalMetadataIssue.SERVICE_UNAVAILABLE
        else -> LocalMetadataIssue.INVALID_RESPONSE
    }
    is ArtworkException -> when (failure.issue) {
        ArtworkIssue.BUSY -> LocalMetadataIssue.BUSY
        ArtworkIssue.INPUT_TOO_LARGE -> LocalMetadataIssue.INPUT_LIMIT
        ArtworkIssue.LOW_MEMORY -> LocalMetadataIssue.LOW_MEMORY
        ArtworkIssue.NO_ARTWORK -> LocalMetadataIssue.NO_ARTWORK
        ArtworkIssue.PERMISSION_DENIED, ArtworkIssue.SOURCE_UNAVAILABLE -> LocalMetadataIssue.SOURCE_CHANGED
        ArtworkIssue.CACHE_UNAVAILABLE -> LocalMetadataIssue.STORAGE
        ArtworkIssue.INVALID_IMAGE -> LocalMetadataIssue.INVALID_IMAGE
    }
    is ThemeException -> if (failure.issue == ThemeIssue.BUSY) LocalMetadataIssue.LOW_MEMORY else LocalMetadataIssue.INVALID_IMAGE
    is LyricsParseException -> when (failure.issue) {
        LyricsParseIssue.MEMORY_LIMIT -> LocalMetadataIssue.LOW_MEMORY
        LyricsParseIssue.INVALID_TEXT, LyricsParseIssue.INVALID_ENCODING, LyricsParseIssue.ENCODING_MISMATCH,
        LyricsParseIssue.UNSUPPORTED_ENCODING -> LocalMetadataIssue.INVALID_RESPONSE
        else -> LocalMetadataIssue.INPUT_LIMIT
    }
    is NeteaseLyricsException -> when (failure.issue) {
        NeteaseLyricsIssue.MEMORY_LIMIT -> LocalMetadataIssue.LOW_MEMORY
        NeteaseLyricsIssue.INPUT_TOO_LARGE -> LocalMetadataIssue.INPUT_LIMIT
        NeteaseLyricsIssue.INVALID_RESPONSE -> LocalMetadataIssue.INVALID_RESPONSE
    }
    is SourceReadException -> when {
        failure.cause is OutOfMemoryError -> LocalMetadataIssue.LOW_MEMORY
        failure.issue == SourceReadIssue.BUSY -> LocalMetadataIssue.BUSY
        else -> LocalMetadataIssue.SOURCE_CHANGED
    }
    else -> LocalMetadataIssue.STORAGE
}
