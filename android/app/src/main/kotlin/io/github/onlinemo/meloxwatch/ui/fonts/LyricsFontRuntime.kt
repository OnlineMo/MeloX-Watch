package io.github.onlinemo.meloxwatch.ui.fonts

import android.os.OperationCanceledException
import io.github.onlinemo.meloxwatch.data.fonts.AndroidLyricsFontFiles
import io.github.onlinemo.meloxwatch.data.fonts.LoadedLyricsFont
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontException
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontIssue
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontLimits
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontRole
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSelection
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSettings
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSettingsStore
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.themes.AppliedThemeResources
import io.github.onlinemo.meloxwatch.data.themes.LocalThemeRepository
import io.github.onlinemo.meloxwatch.data.themes.ThemeException
import io.github.onlinemo.meloxwatch.data.themes.ThemeIssue
import io.github.onlinemo.meloxwatch.data.themes.ThemePreview
import io.github.onlinemo.meloxwatch.data.themes.ThemeSnapshot
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Application injects the same presentation owner and physical font worker as the editor.
 * Calls are structured caller work: one admitted render, two serial roles, no observer, cache,
 * request queue or launched job. Personal null is system, never inheritance from an applied theme.
 */
class LyricsFontRuntime internal constructor(
    private val settings: LyricsFontSettingsStore,
    private val presentationOwner: LyricsFontPresentationOwner,
    private val loader: Loader,
) {
    constructor(
        settings: LyricsFontSettingsStore,
        files: AndroidLyricsFontFiles,
        themes: LocalThemeRepository,
        appliedResources: AppliedThemeResources,
        providerIo: LocalProviderIo,
        presentationOwner: LyricsFontPresentationOwner,
    ) : this(settings, presentationOwner, AndroidLoader(files, themes, appliedResources, providerIo))

    /** Requests contain only frozen metadata and bounded text; a presentation never caches faces. */
    class Presentation internal constructor(
        internal val runtime: LyricsFontRuntime,
        internal val binding: LyricsFontPresentationOwner.Binding,
        internal val request: Request,
    ) : AutoCloseable {
        val kind: LyricsFontPresentationKind get() = binding.kind
        val isCurrent: Boolean get() = binding.isCurrent
        val state: LyricsFontRuntimeState get() = binding.state

        override fun close() {
            // May run under Appearance's editor lock. No cancellation, token retirement or I/O.
            binding.close()
        }
    }

    fun bindPersonalLyrics(text: LyricsFontPreviewText): Presentation = bind(Source.Personal, text)

    fun bindThemeUi(snapshot: ThemeSnapshot, text: LyricsFontPreviewText): Presentation =
        bind(Source.ThemeUi(snapshot), text)

    fun bindPreview(
        preview: ThemePreview,
        text: LyricsFontPreviewText,
        requestedWeights: LyricsFontRequestedWeights = LyricsFontRequestedWeights(),
    ): Presentation = bind(Source.Preview(preview, requestedWeights), text)

    /**
     * A changed document/language gets a new identity and drops the old pair before any read.
     * Identical coverage is a no-op. An old or foreign presentation cannot replace the current one.
     * The caller memoizes full document text by document identity, never by media-position ticks.
     */
    fun updateText(presentation: Presentation, text: LyricsFontPreviewText): Presentation? {
        if (!ownsCurrent(presentation)) return null
        val coverage = Coverage.of(text)
        if (presentation.request.coverage == coverage) return presentation
        val next = presentationOwner.replace(presentation.binding, presentation.kind) ?: return null
        return Presentation(this, next, presentation.request.copy(coverage = coverage))
    }

    /**
     * true means this call was admitted and completed its role loads, including typed fallbacks.
     * false means busy, retired or hidden. visibleNow reads only current Window/route StateFlow
     * values; it must not reenter AppearanceViewModel while the presentation lock is held.
     */
    suspend fun render(presentation: Presentation, visibleNow: () -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        if (!canRender(presentation, visibleNow)) return false
        val work = Work(presentation.binding, checkNotNull(currentCoroutineContext()[Job]))
        if (!active.compareAndSet(null, work)) {
            if (active.get()?.binding !== presentation.binding) publishBusy(presentation, visibleNow)
            return false
        }
        try {
            val request = presentation.request
            if (!presentationOwner.publish(presentation.binding,
                LyricsFontRuntimeState(kind = presentation.kind, loading = true), visibleNow)) return false
            val read = if (request.needsPersonalSettings) readSettings() else SettingsRead()
            currentCoroutineContext().ensureActive()
            if (!canRender(presentation, visibleNow)) return false
            val initial = LyricsFontRuntimeState(
                kind = presentation.kind,
                original = requestedRole(request, LyricsFontRole.ORIGINAL, read),
                translation = requestedRole(request, LyricsFontRole.TRANSLATION, read),
                saved = read.settings,
                loading = true,
                issue = read.issue,
            )
            if (!presentationOwner.publish(presentation.binding, initial, visibleNow)) return false
            // Each helper returns only an admission bit; a prior face is not held in this frame
            // across the other role's suspended read or a later cleanup/storage operation.
            for (role in ROLES) {
                if (!loadAndPublish(presentation, role, read, visibleNow)) return false
            }
            return true
        } finally {
            try { finishPresentation(presentation, visibleNow) }
            finally {
                active.compareAndSet(work, null)
                work.done.complete(Unit)
            }
        }
    }

    /** Caller invokes this after leaving the Appearance editor lock, using the exact retired job. */
    fun cancelRetiredWork() {
        val work = active.get() ?: return
        if (!work.binding.isCurrent) work.job.cancel(CancellationException("Font presentation retired"))
    }

    /** Idle is an observation, not a reserved admission. The caller may retry at most once. */
    suspend fun awaitIdle(timeoutMillis: Long = IDLE_WAIT_MILLIS): Boolean {
        require(timeoutMillis in 1L..IDLE_WAIT_MILLIS)
        return withTimeoutOrNull(timeoutMillis) {
            active.get()?.done?.await()
            loader.awaitIdle()
            true
        } ?: false
    }

    private val active = AtomicReference<Work?>(null)

    private fun bind(source: Source, text: LyricsFontPreviewText): Presentation {
        val request = Request(source, Coverage.of(text))
        return Presentation(this, presentationOwner.bind(source.kind), request)
    }

    private fun ownsCurrent(presentation: Presentation): Boolean =
        presentation.runtime === this && presentation.binding.isCurrent

    private fun canRender(presentation: Presentation, visibleNow: () -> Boolean): Boolean {
        if (!ownsCurrent(presentation)) return false
        if (visibleNow()) return true
        presentation.close()
        return false
    }

    private suspend fun loadAndPublish(
        presentation: Presentation,
        role: LyricsFontRole,
        read: SettingsRead,
        visibleNow: () -> Boolean,
    ): Boolean {
        currentCoroutineContext().ensureActive()
        if (!canRender(presentation, visibleNow)) return false
        val loaded = loadRole(presentation.request, role, read)
        currentCoroutineContext().ensureActive()
        if (!canRender(presentation, visibleNow)) return false
        return publishRole(presentation, role, loaded, visibleNow)
    }

    private suspend fun loadRole(request: Request, role: LyricsFontRole, read: SettingsRead): LyricsFontRuntimeRole {
        val requested = requestedRole(request, role, read)
        if (request.coverage.invalid(role)) return requested.copy(
            font = LoadedLyricsFont(issue = LyricsFontIssue.INVALID_INPUT),
        )
        val source = request.source
        val text = request.coverage.text.forRole(role)
        val weight = (source as? Source.Preview)?.weights?.forRole(role)
        if (requested.requestedSource == LyricsFontRequestedSource.THEME_FILE && weight?.isFinite() == false) {
            return requested.copy(font = LoadedLyricsFont(issue = LyricsFontIssue.INVALID_INPUT))
        }
        if (requested.requestedSource == LyricsFontRequestedSource.SYSTEM) return requested
        return try {
            val font = when (requested.requestedSource) {
                LyricsFontRequestedSource.PERSONAL_FILE -> loader.personal(checkNotNull(read.settings).selection(role), text)
                LyricsFontRequestedSource.THEME_FILE -> when (source) {
                    is Source.ThemeUi -> loader.themeUi(source.snapshot, role, text)
                        ?: return requested.copy(themeIssue = ThemeIssue.MISSING_RESOURCE)
                    is Source.Preview -> loader.preview(source.preview, role, weight, text)
                    Source.Personal -> error("Personal source has no theme role")
                }
                LyricsFontRequestedSource.SYSTEM -> error("System font does not decode a file")
            }
            requested.copy(font = font)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (cancelled: OperationCanceledException) { throw cancelled }
        catch (error: LyricsFontException) { requested.copy(font = LoadedLyricsFont(issue = error.issue)) }
        catch (error: ThemeException) { requested.copy(themeIssue = error.issue) }
        catch (_: SecurityException) { requested.copy(font = LoadedLyricsFont(issue = LyricsFontIssue.PERMISSION_DENIED)) }
        catch (_: IOException) { requested.copy(font = LoadedLyricsFont(issue = LyricsFontIssue.SOURCE_UNAVAILABLE)) }
        catch (_: RuntimeException) { requested.copy(font = LoadedLyricsFont(issue = LyricsFontIssue.NATIVE_DECODE_FAILED)) }
        catch (_: OutOfMemoryError) { requested.copy(font = LoadedLyricsFont(issue = LyricsFontIssue.LOW_MEMORY)) }
    }

    private fun requestedRole(request: Request, role: LyricsFontRole, read: SettingsRead): LyricsFontRuntimeRole {
        val fromTheme = when (val source = request.source) {
            Source.Personal -> false
            is Source.ThemeUi -> source.snapshot.effectiveManifest.fonts[role] != null
            is Source.Preview -> source.preview.manifest.fonts[role] != null
        }
        if (fromTheme) return LyricsFontRuntimeRole(requestedSource = LyricsFontRequestedSource.THEME_FILE)
        if (request.source is Source.ThemeUi) return LyricsFontRuntimeRole()
        val selection = read.settings?.selection(role)
        val requestedSource = if (selection?.asset == null) LyricsFontRequestedSource.SYSTEM
            else LyricsFontRequestedSource.PERSONAL_FILE
        // A disabled selection uses real system defaults, even if an old null-asset record has
        // a finite weight. The frozen schema is unchanged and no implicit repair is written.
        return LyricsFontRuntimeRole(
            font = LoadedLyricsFont(issue = read.issue),
            requestedSource = requestedSource,
        )
    }

    private suspend fun readSettings(): SettingsRead = try { SettingsRead(settings.read()) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (cancelled: OperationCanceledException) { throw cancelled }
    catch (error: LyricsFontException) {
        SettingsRead(issue = if (error.issue == LyricsFontIssue.LOW_MEMORY) error.issue else LyricsFontIssue.SETTINGS_UNAVAILABLE)
    } catch (_: IOException) { SettingsRead(issue = LyricsFontIssue.SETTINGS_UNAVAILABLE) }
    catch (_: RuntimeException) { SettingsRead(issue = LyricsFontIssue.SETTINGS_UNAVAILABLE) }
    catch (_: OutOfMemoryError) { SettingsRead(issue = LyricsFontIssue.LOW_MEMORY) }

    private fun publishRole(
        presentation: Presentation,
        role: LyricsFontRole,
        loaded: LyricsFontRuntimeRole,
        visibleNow: () -> Boolean,
    ): Boolean {
        val current = presentation.state
        val next = when (role) {
            LyricsFontRole.ORIGINAL -> current.copy(original = loaded)
            LyricsFontRole.TRANSLATION -> current.copy(translation = loaded)
        }
        return presentationOwner.publish(presentation.binding, next, visibleNow)
    }

    private fun publishBusy(presentation: Presentation, visibleNow: () -> Boolean) {
        presentationOwner.publish(presentation.binding,
            LyricsFontRuntimeState(kind = presentation.kind, issue = LyricsFontIssue.BUSY), visibleNow)
    }

    private fun finishPresentation(presentation: Presentation, visibleNow: () -> Boolean) {
        if (!canRender(presentation, visibleNow)) return
        presentationOwner.publish(presentation.binding, presentation.state.copy(loading = false), visibleNow)
    }

    private class Work(val binding: LyricsFontPresentationOwner.Binding, val job: Job) {
        val done = CompletableDeferred<Unit>()
    }

    private data class SettingsRead(val settings: LyricsFontSettings? = null, val issue: LyricsFontIssue? = null)

    internal sealed interface Source {
        val kind: LyricsFontPresentationKind
        object Personal : Source { override val kind = LyricsFontPresentationKind.PERSONAL_LYRICS }
        data class ThemeUi(val snapshot: ThemeSnapshot) : Source {
            override val kind = LyricsFontPresentationKind.THEME_UI
        }
        data class Preview(val preview: ThemePreview, val weights: LyricsFontRequestedWeights) : Source {
            override val kind = LyricsFontPresentationKind.PREVIEW
        }
    }

    internal data class Request(val source: Source, val coverage: Coverage) {
        val needsPersonalSettings: Boolean get() = when (source) {
            Source.Personal -> true
            is Source.ThemeUi -> false
            is Source.Preview -> ROLES.any { source.preview.manifest.fonts[it] == null }
        }
    }

    /** Reject rather than truncate; an over-bound role drops its text reference and uses system. */
    internal data class Coverage(
        val text: LyricsFontPreviewText,
        private val invalidOriginal: Boolean,
        private val invalidTranslation: Boolean,
    ) {
        fun invalid(role: LyricsFontRole): Boolean = when (role) {
            LyricsFontRole.ORIGINAL -> invalidOriginal
            LyricsFontRole.TRANSLATION -> invalidTranslation
        }

        companion object {
            fun of(text: LyricsFontPreviewText): Coverage {
                val original = text.original.length > LyricsFontLimits.TEXT_CHARACTERS
                val translation = text.translation.length > LyricsFontLimits.TEXT_CHARACTERS
                return Coverage(LyricsFontPreviewText(
                    if (original) "" else text.original, if (translation) "" else text.translation,
                ), original, translation)
            }
        }
    }

    /** A narrow loader seam; the production implementation always reuses the existing worker. */
    internal interface Loader {
        suspend fun personal(selection: LyricsFontSelection, text: String): LoadedLyricsFont
        suspend fun themeUi(snapshot: ThemeSnapshot, role: LyricsFontRole, text: String): LoadedLyricsFont?
        suspend fun preview(preview: ThemePreview, role: LyricsFontRole, weight: Float?, text: String): LoadedLyricsFont
        suspend fun awaitIdle()
    }

    private class AndroidLoader(
        private val files: AndroidLyricsFontFiles,
        private val themes: LocalThemeRepository,
        private val appliedResources: AppliedThemeResources,
        private val providerIo: LocalProviderIo,
    ) : Loader {
        override suspend fun personal(selection: LyricsFontSelection, text: String): LoadedLyricsFont =
            files.loadFont(selection, text)

        override suspend fun themeUi(snapshot: ThemeSnapshot, role: LyricsFontRole, text: String): LoadedLyricsFont? =
            appliedResources.consumeFont(snapshot, role, abandoned = {}) { file, info, capability, signal ->
                files.consumeThemeFontOnWorker(file, info, capability, null, text, signal)
            }

        override suspend fun preview(preview: ThemePreview, role: LyricsFontRole, weight: Float?, text: String): LoadedLyricsFont =
            themes.consumePreviewFont(preview.token, role) { file, resource, capability, signal ->
                files.consumePreviewFontOnWorker(file, resource, capability, weight, text, signal)
            }

        override suspend fun awaitIdle() = providerIo.awaitIdle()
    }

    companion object {
        private const val IDLE_WAIT_MILLIS = 3_000L
        private val ROLES = arrayOf(LyricsFontRole.ORIGINAL, LyricsFontRole.TRANSLATION)
    }
}
