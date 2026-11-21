package io.github.onlinemo.meloxwatch.ui.fonts

import io.github.onlinemo.meloxwatch.data.fonts.LoadedLyricsFont
import io.github.onlinemo.meloxwatch.data.fonts.FontWeightSupport
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontAsset
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontException
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontFiles
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontImportRequest
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontIssue
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontRole
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSaveResult
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSelection
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSettings
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSettingsStore
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * One controlled draft, no internal jobs or request queue. The caller owns lifecycle cancellation
 * and supplies both real text samples; confirmed settings and render outcomes stay separate.
 */
class LyricsFontEditor private constructor(
    private val settings: LyricsFontSettingsStore,
    private val files: LyricsFontFiles,
    private val presentationOwner: LyricsFontPresentationOwner,
    bindInitially: Boolean,
) {
    /** Standalone file/transaction tests retain their existing two-argument entry point. */
    constructor(settings: LyricsFontSettingsStore, files: LyricsFontFiles) :
        this(settings, files, LyricsFontPresentationOwner(), true)

    /** The real Application shares one owner; constructing a hidden editor does not bind it. */
    constructor(settings: LyricsFontSettingsStore, files: LyricsFontFiles, presentationOwner: LyricsFontPresentationOwner) :
        this(settings, files, presentationOwner, false)

    private val occupied = AtomicBoolean(false)
    private val mutable = MutableStateFlow(LyricsFontEditorState())
    val state: StateFlow<LyricsFontEditorState> = mutable.asStateFlow()
    private var retiredState = mutable.value
    @Volatile private var presentationBinding = if (bindInitially) presentationOwner.bind(
        LyricsFontPresentationKind.EDITOR, ::retirePresentation,
    ) else presentationOwner.detachedEditor(::retirePresentation)
    @Volatile private var actionBinding: LyricsFontPresentationOwner.Binding? = null

    /** 可见 editor 才重接同一个 pair。close 只退场内存，不保存、取消草稿或清理文件。 */
    fun bindPresentation(): LyricsFontPresentationOwner.Binding {
        val next = presentationOwner.bind(LyricsFontPresentationKind.EDITOR, ::retirePresentation)
        presentationBinding = next
        return next
    }

    /**
     * 重新可见时真实读设置并恢复仍保有的草稿/重试；不重放 CAS。别的已完成修订转显式重试，
     * 取消后实际已提交的同一提案则直接认真实值。先放掉旧 pair，再串行加载当前两角色。
     */
    suspend fun resume(text: LyricsFontPreviewText) = action {
        val before = mutable.value.withoutTypefaces()
        val actual = readActual()
        var draft = before.draft
        var retry = before.retry
        val proposal = retry ?: draft
        if (proposal != null && actual.selection(proposal.role) == proposal.selection &&
            actual.revision > proposal.baseRevision) {
            draft = null
            retry = null
        } else if (draft != null && draft.baseRevision != actual.revision) {
            retry = draft
            draft = null
        }
        val issue = if (retry == null) null else if (retry.baseRevision == actual.revision)
            LyricsFontIssue.SAVE_FAILED else LyricsFontIssue.SETTINGS_CONFLICT
        installActual(actual, text, draft = draft, retry = retry, issue = issue)
        if (draft != null && renderBinding().isCurrent) {
            previewDraft(draft, text)
        }
        pruneKnown()
    }

    suspend fun restore(text: LyricsFontPreviewText) = action {
        val actual = readActual()
        installActual(actual, text)
        pruneKnown()
    }

    suspend fun begin(role: LyricsFontRole, text: LyricsFontPreviewText) = action {
        if (mutable.value.draft != null || mutable.value.retry != null) fail(LyricsFontIssue.INVALID_INPUT)
        val actual = readActual()
        installActual(actual, text, draft = LyricsFontDraft(role, actual.revision, actual.selection(role)))
    }

    suspend fun importFont(request: LyricsFontImportRequest, text: LyricsFontPreviewText) = action {
        val before = mutable.value.withoutTypefaces()
        val draft = before.draft ?: fail(LyricsFontIssue.INVALID_INPUT)
        if (before.retry != null) fail(LyricsFontIssue.INVALID_INPUT)
        var owned: LyricsFontAsset? = null
        try {
            val prepared = files.importFont(request, before.retainedIds())
            owned = prepared.asset
            val selection = LyricsFontSelection(prepared.asset, draft.selection.requestedWeight)
            previewDraft(draft.copy(selection = selection), text)
            owned = null
            retire(draft.selection.asset)
        } finally {
            owned?.let { asset -> withContext(NonCancellable) { retire(asset) } }
        }
    }

    suspend fun previewWeight(requested: Float?, text: LyricsFontPreviewText) = action {
        val before = mutable.value.withoutTypefaces()
        val draft = before.draft ?: fail(LyricsFontIssue.INVALID_INPUT)
        if (before.retry != null || requested != null && !requested.isFinite()) fail(LyricsFontIssue.INVALID_INPUT)
        if (requested != null && before.font(draft.role).capability.support != FontWeightSupport.AVAILABLE) {
            fail(LyricsFontIssue.INVALID_INPUT)
        }
        val selection = draft.selection.copy(requestedWeight = requested)
        previewDraft(draft.copy(selection = selection), text)
    }

    suspend fun previewSystemFont() = action {
        val before = mutable.value.withoutTypefaces()
        val draft = before.draft ?: fail(LyricsFontIssue.INVALID_INPUT)
        if (before.retry != null) fail(LyricsFontIssue.INVALID_INPUT)
        publish(mutable.value.withFont(draft.role, LoadedLyricsFont()).copy(
            draft = draft.copy(selection = LyricsFontSelection()), working = true, issue = null,
        ))
        retire(draft.selection.asset)
    }

    suspend fun cancel(text: LyricsFontPreviewText) = action {
        val actual = readActual(keepRetry = false)
        installActual(actual, text)
        pruneKnown()
    }

    suspend fun confirm(text: LyricsFontPreviewText) = action {
        val before = mutable.value.withoutTypefaces()
        val draft = before.draft ?: fail(LyricsFontIssue.INVALID_INPUT)
        val saved = before.saved ?: fail(LyricsFontIssue.SETTINGS_UNAVAILABLE)
        if (before.retry != null || before.font(draft.role).issue != null) fail(LyricsFontIssue.INVALID_INPUT)
        save(draft, saved, text)
    }

    /** Explicit retry re-reads storage, re-previews the retained proposal and uses a fresh CAS. */
    suspend fun retrySave(text: LyricsFontPreviewText) = action {
        val proposal = mutable.value.retry ?: fail(LyricsFontIssue.INVALID_INPUT)
        val actual = readActual()
        if (actual.selection(proposal.role) == proposal.selection && actual.revision > proposal.baseRevision) {
            installActual(actual, text)
            pruneKnown()
            return@action
        }
        installActual(actual, text, retry = proposal)
        val refreshed = proposal.copy(baseRevision = actual.revision)
        previewDraft(refreshed, text, requireValid = true)
        save(refreshed, actual, text)
    }

    private suspend fun save(draft: LyricsFontDraft, saved: LyricsFontSettings, text: LyricsFontPreviewText) {
        if (draft.baseRevision != saved.revision || saved.revision == Long.MAX_VALUE) fail(LyricsFontIssue.SETTINGS_CONFLICT)
        val replacement = saved.withSelection(draft.role, draft.selection)
        val result = try { settings.compareAndSet(draft.baseRevision, replacement) }
        catch (cancelled: CancellationException) {
            // A cancelled durable write may have committed. Keep its asset for the next true read.
            update { it.copy(saved = null, draft = null, retry = draft, issue = LyricsFontIssue.SETTINGS_UNAVAILABLE) }
            throw cancelled
        } catch (_: IOException) {
            recoverWrite(draft, text)
            return
        } catch (_: LyricsFontException) {
            recoverWrite(draft, text)
            return
        } catch (_: RuntimeException) {
            recoverWrite(draft, text)
            return
        } catch (_: OutOfMemoryError) {
            recoverWrite(draft, text)
            return
        }
        when (result) {
            is LyricsFontSaveResult.Saved -> {
                if (result.settings.revision != saved.revision + 1L ||
                    result.settings.original != replacement.original || result.settings.translation != replacement.translation) {
                    recoverWrite(draft, text)
                    return
                }
                publish(mutable.value.copy(saved = result.settings, draft = null, retry = null, issue = null))
                pruneKnown()
            }
            is LyricsFontSaveResult.Conflict -> {
                installActual(result.settings, text, retry = draft, issue = LyricsFontIssue.SETTINGS_CONFLICT)
                pruneKnown()
            }
        }
    }

    private suspend fun recoverWrite(proposal: LyricsFontDraft, text: LyricsFontPreviewText) {
        storageUnknown(proposal)
        val actual = try { settings.read() }
        catch (cancelled: CancellationException) {
            update { it.copy(saved = null, draft = null, retry = proposal, issue = LyricsFontIssue.SETTINGS_UNAVAILABLE) }
            throw cancelled
        } catch (_: IOException) {
            storageUnknown(proposal)
            return
        } catch (_: LyricsFontException) {
            storageUnknown(proposal)
            return
        } catch (_: RuntimeException) {
            storageUnknown(proposal)
            return
        } catch (_: OutOfMemoryError) {
            storageUnknown(proposal)
            return
        }
        if (actual.selection(proposal.role) == proposal.selection && actual.revision > proposal.baseRevision) {
            installActual(actual, text)
        } else installActual(actual, text, retry = proposal, issue = LyricsFontIssue.SAVE_FAILED)
        pruneKnown()
    }

    private suspend fun readActual(keepRetry: Boolean = true): LyricsFontSettings = try { settings.read() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (error: IOException) {
        storageUnknown(if (keepRetry) mutable.value.retry ?: mutable.value.draft else null)
        throw LyricsFontException(LyricsFontIssue.SETTINGS_UNAVAILABLE, error)
    } catch (error: LyricsFontException) {
        storageUnknown(if (keepRetry) mutable.value.retry ?: mutable.value.draft else null)
        throw LyricsFontException(LyricsFontIssue.SETTINGS_UNAVAILABLE, error)
    } catch (error: RuntimeException) {
        storageUnknown(if (keepRetry) mutable.value.retry ?: mutable.value.draft else null)
        throw LyricsFontException(LyricsFontIssue.SETTINGS_UNAVAILABLE, error)
    } catch (error: OutOfMemoryError) {
        storageUnknown(if (keepRetry) mutable.value.retry ?: mutable.value.draft else null)
        throw LyricsFontException(LyricsFontIssue.LOW_MEMORY, error)
    }

    private suspend fun installActual(
        actual: LyricsFontSettings,
        text: LyricsFontPreviewText,
        draft: LyricsFontDraft? = null,
        retry: LyricsFontDraft? = null,
        issue: LyricsFontIssue? = null,
    ) {
        publish(LyricsFontEditorState(saved = actual, draft = draft, retry = retry, working = true, issue = issue))
        if (!renderBinding().isCurrent) return
        loadActualRole(LyricsFontRole.ORIGINAL, actual.original, text.original, issue)
        loadActualRole(LyricsFontRole.TRANSLATION, actual.translation, text.translation, issue)
    }

    private suspend fun loadActualRole(
        role: LyricsFontRole,
        selection: LyricsFontSelection,
        text: String,
        issue: LyricsFontIssue?,
    ) {
        // 候选立即交给唯一 owner；上一 role 的 face 不留在跨下一次 I/O 的 coroutine frame。
        val font = loadFont(selection, text)
        update { current ->
            val next = current.withFont(role, font)
            next.copy(issue = issue ?: next.original.issue ?: next.translation.issue)
        }
    }

    private fun storageUnknown(proposal: LyricsFontDraft?) {
        publish(LyricsFontEditorState(retry = proposal, working = true, issue = LyricsFontIssue.SETTINGS_UNAVAILABLE))
    }

    private suspend fun pruneKnown() {
        val current = mutable.value.withoutTypefaces()
        if (current.saved != null) files.prune(current.retainedIds())
    }

    private suspend fun retire(asset: LyricsFontAsset?) {
        if (asset == null) return
        val current = mutable.value.withoutTypefaces()
        if (current.saved != null) files.discard(asset, current.retainedIds())
    }

    private suspend fun action(block: suspend () -> Unit) {
        if (!occupied.compareAndSet(false, true)) {
            update { it.copy(issue = LyricsFontIssue.BUSY) }
            return
        }
        actionBinding = presentationBinding
        update { it.copy(working = true, issue = null) }
        try {
            currentCoroutineContext().ensureActive()
            if (!renderBinding().isCurrent) throw CancellationException("Font editor presentation retired")
            block()
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: LyricsFontException) { update { it.copy(issue = error.issue) } }
        catch (_: IOException) { update { it.copy(issue = LyricsFontIssue.SOURCE_UNAVAILABLE) } }
        catch (_: OutOfMemoryError) {
            update { current -> current.copy(
                original = LoadedLyricsFont(asset = current.saved?.original?.asset, issue = LyricsFontIssue.LOW_MEMORY),
                translation = LoadedLyricsFont(asset = current.saved?.translation?.asset, issue = LyricsFontIssue.LOW_MEMORY),
                issue = LyricsFontIssue.LOW_MEMORY,
            ) }
        }
        finally {
            update { it.copy(working = false) }
            actionBinding = null
            occupied.set(false)
        }
    }

    private suspend fun loadFont(selection: LyricsFontSelection, text: String): LoadedLyricsFont {
        val binding = renderBinding()
        if (!binding.isCurrent) throw CancellationException("Font editor presentation retired")
        if (selection.asset == null) return LoadedLyricsFont()
        val font = files.loadFont(selection, text)
        currentCoroutineContext().ensureActive()
        if (!binding.isCurrent) throw CancellationException("Font editor presentation retired")
        return font
    }

    private suspend fun previewDraft(
        draft: LyricsFontDraft,
        text: LyricsFontPreviewText,
        requireValid: Boolean = false,
    ) {
        // 当前 pair 保留至唯一候选通过；取消不换字。候选只在此函数内保有，提交后不随清理 I/O 留存。
        val font = loadFont(draft.selection, text.forRole(draft.role))
        if (font.issue == LyricsFontIssue.BUSY || requireValid && font.issue != null) fail(checkNotNull(font.issue))
        publish(mutable.value.withFont(draft.role, font).copy(
            draft = draft, retry = null, working = true, issue = font.issue,
        ))
    }

    private fun renderBinding(): LyricsFontPresentationOwner.Binding = actionBinding ?: presentationBinding

    private fun publish(next: LyricsFontEditorState) = commitPresentation { next }

    private fun update(transform: (LyricsFontEditorState) -> LyricsFontEditorState) =
        commitPresentation { transform(mutable.value) }

    private fun commitPresentation(createNext: () -> LyricsFontEditorState) {
        presentationOwner.publishEditor(renderBinding(), createNext) { rendered, retired ->
            retiredState = retired
            mutable.value = rendered
        }
    }

    private fun retirePresentation() {
        // safe 预先准备且不含 face；锁内只赋内存，不取消底层 signal 或触发文件清理。
        mutable.value = retiredState
    }

    private fun fail(issue: LyricsFontIssue): Nothing = throw LyricsFontException(issue)
}
