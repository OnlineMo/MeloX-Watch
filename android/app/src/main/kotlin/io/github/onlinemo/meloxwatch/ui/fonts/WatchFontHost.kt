package io.github.onlinemo.meloxwatch.ui.fonts

import android.content.res.Resources
import android.os.OperationCanceledException
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.font.FontFamily
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontException
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontIssue
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontLimits
import io.github.onlinemo.meloxwatch.data.themes.ThemePreview
import io.github.onlinemo.meloxwatch.data.themes.ThemeSnapshot
import io.github.onlinemo.meloxwatch.ui.lyrics.LyricsFonts
import io.github.onlinemo.meloxwatch.ui.lyrics.LyricsPageContent
import io.github.onlinemo.meloxwatch.ui.themes.LocalWatchThemeFonts
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect

/** Frozen source metadata; no URI, file read, face or worker belongs to a request. */
sealed interface WatchFontRequest {
    val kind: LyricsFontPresentationKind
    val identity: Any
    val coverageIdentity: Any

    class Personal(val ready: LyricsPageContent.Ready) : WatchFontRequest {
        override val kind = LyricsFontPresentationKind.PERSONAL_LYRICS
        override val coverageIdentity: Any = PersonalKey(ready.document.documentId,
            ready.translation?.primaryDocumentId, ready.translation?.translationDocumentId)
        override val identity: Any = coverageIdentity
    }

    class ThemeUi(
        val snapshot: ThemeSnapshot,
        val resources: Resources,
        val localeKey: String,
        val currentText: ThemeUiFontText,
    ) : WatchFontRequest {
        override val kind = LyricsFontPresentationKind.THEME_UI
        override val coverageIdentity: Any = UiCoverageKey(resources, localeKey, currentText)
        override val identity: Any = UiKey(snapshot, coverageIdentity)
    }

    class Preview(
        val preview: ThemePreview,
        val text: LyricsFontPreviewText,
        val weights: LyricsFontRequestedWeights = LyricsFontRequestedWeights(),
    ) : WatchFontRequest {
        override val kind = LyricsFontPresentationKind.PREVIEW
        override val coverageIdentity: Any = ExactObject(text)
        override val identity: Any = PreviewKey(ExactObject(preview), ExactObject(text), weights)
    }

    class Editor(val text: LyricsFontPreviewText) : WatchFontRequest {
        override val kind = LyricsFontPresentationKind.EDITOR
        override val coverageIdentity: Any = ExactObject(text)
        override val identity: Any = coverageIdentity
    }
}

/** Values borrowed from the sole Application owner; retirement callbacks target this exact host. */
class WatchFontHostValue internal constructor(
    private val reading: Boolean,
    val coverageIssue: LyricsFontIssue?,
    val previewText: LyricsFontPreviewText?,
    private val work: WatchFontWork,
) {
    // A host value or event callback must not cache the pair after its exact binding retires.
    val state: LyricsFontRuntimeState get() = work.currentState()
    val fonts: LyricsFonts get() {
        val current = state
        return lyricsFontFamilies(current.original.font, current.translation.font)
    }
    val loading: Boolean get() = work.visibleNow() && (reading || state.loading)
    val editorState: LyricsFontEditorState? get() = work.editorMetadata()
    val canAdjustFontWeight: Boolean get() = work.canAdjustFontWeight()
    val releaseMemory: () -> Unit = work::releaseMemory
    val cancelRetiredWork: () -> Unit = work::cancelRetiredWork

    /** Main's event scope owns the call; each action gets a structured child, no launch or queue. */
    suspend fun edit(action: suspend (LyricsFontEditor) -> Unit): Boolean = work.edit(action)
}

/**
 * The actual Application supplies its unique runtime/owner/three-argument editor. install must
 * register the two exact callbacks in the current PageBinding before coverage or native I/O.
 * Its returned registration closes both phases outside the Page/Appearance editor locks.
 * Each immutable work identity gets its own LaunchedEffect child Job and exactly one font
 * binding. A retired Job is never reused for a successor Presentation. The sole UI observer
 * holds only a change counter and retires with this page; settings are not observed.
 */
@Composable
fun WatchFontHost(
    runtime: LyricsFontRuntime,
    owner: LyricsFontPresentationOwner,
    editor: LyricsFontEditor,
    request: WatchFontRequest,
    pageEpoch: Any,
    visibleNow: () -> Boolean,
    install: (LyricsFontPresentationKind, () -> Unit, () -> Unit) -> AutoCloseable?,
    retryIdentity: Any = Unit,
    content: @Composable (WatchFontHostValue) -> Unit,
) {
    content(rememberWatchFontHost(runtime, owner, editor, request, pageEpoch, visibleNow, install, retryIdentity))
}

/** Resource admission can change while its caller keeps page content at one composition site. */
@Composable
internal fun rememberWatchFontHost(
    runtime: LyricsFontRuntime,
    owner: LyricsFontPresentationOwner,
    editor: LyricsFontEditor,
    request: WatchFontRequest,
    pageEpoch: Any,
    visibleNow: () -> Boolean,
    install: (LyricsFontPresentationKind, () -> Unit, () -> Unit) -> AutoCloseable?,
    retryIdentity: Any = Unit,
): WatchFontHostValue {
    // Only one completed bounded text pair is memoized by source document/UI identity. There
    // is no map and no face cache; page/Window changes can reread settings without rebuilding text.
    val coverage = remember(request.coverageIdentity) { mutableStateOf<LyricsFontPreviewText?>(null) }
    val work = remember(runtime, owner, editor, request.identity, pageEpoch, retryIdentity) {
        WatchFontWork(runtime, editor, request, visibleNow)
    }
    var loading by remember(work) { mutableStateOf(false) }
    var coverageIssue by remember(work) { mutableStateOf<LyricsFontIssue?>(null) }
    var displayChange by remember(work) { mutableIntStateOf(0) }

    DisposableEffect(work) {
        val registration = if (work.canInstall()) install(work.request.kind,
            work::releaseMemory, work::cancelRetiredWork) else null
        if (registration != null) work.admit() else {
            work.releaseMemory()
            work.cancelRetiredWork()
        }
        onDispose {
            // The registration itself verifies exact identity. A stale close cannot clear a new slot.
            if (registration != null) registration.close() else {
                work.releaseMemory()
                work.cancelRetiredWork()
            }
        }
    }

    LaunchedEffect(work, owner) {
        val observer = checkNotNull(currentCoroutineContext()[Job])
        if (!work.beginObserver(observer)) return@LaunchedEffect
        try {
            // Draft/retry can change while the final runtime font pair stays equal, especially
            // for synchronous system-font actions. This one observer projects only an Int;
            // neither source's face-bearing emission is stored in Compose or a font cache.
            if (work.request.kind == LyricsFontPresentationKind.EDITOR) {
                editor.state.collect {
                    work.checkActive()
                    displayChange++
                }
            } else {
                owner.state.collect {
                    work.checkActive()
                    displayChange++
                }
            }
        } finally { work.finishObserver(observer) }
    }

    LaunchedEffect(work) {
        val caller = checkNotNull(currentCoroutineContext()[Job])
        if (!work.beginCaller(caller)) return@LaunchedEffect
        try {
            work.checkActive()
            loading = true
            coverageIssue = null
            val captured = work.request
            val memoized = captured is WatchFontRequest.Personal || captured is WatchFontRequest.ThemeUi
            val text = if (memoized) coverage.value ?: acquireCoverage(captured, work::visibleNow).also {
                work.checkActive()
                coverage.value = it
            } else acquireCoverage(captured, work::visibleNow)
            work.checkActive()
            if (work.bind(text)) work.render(text)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (cancelled: OperationCanceledException) { throw cancelled }
        catch (error: LyricsFontException) {
            work.checkActive()
            coverageIssue = error.issue
        } catch (_: Resources.NotFoundException) {
            work.checkActive()
            coverageIssue = LyricsFontIssue.SOURCE_UNAVAILABLE
        } catch (_: IOException) {
            work.checkActive()
            coverageIssue = LyricsFontIssue.SOURCE_UNAVAILABLE
        } catch (_: OutOfMemoryError) {
            work.checkActive()
            coverageIssue = LyricsFontIssue.LOW_MEMORY
        } finally {
            if (work.isAlive) loading = false
            work.finishCaller(caller)
        }
    }

    // Reading the counter observes the sole Flow without putting its face-bearing value in a
    // Compose State holder. The value itself borrows current state only when its content reads it.
    return remember(work, loading, coverageIssue, displayChange) {
        WatchFontHostValue(loading, coverageIssue, work.previewText, work)
    }
}

/** Back/retry/reset/errors/licenses remain readable even when the selected font lacks glyphs. */
@Composable
fun WatchSystemFontControls(content: @Composable () -> Unit) {
    val inherited = MaterialTheme.typography
    val typography = remember(inherited) { inherited.copy(
        displayLarge = inherited.displayLarge.copy(fontFamily = FontFamily.Default),
        displayMedium = inherited.displayMedium.copy(fontFamily = FontFamily.Default),
        displaySmall = inherited.displaySmall.copy(fontFamily = FontFamily.Default),
        headlineLarge = inherited.headlineLarge.copy(fontFamily = FontFamily.Default),
        headlineMedium = inherited.headlineMedium.copy(fontFamily = FontFamily.Default),
        headlineSmall = inherited.headlineSmall.copy(fontFamily = FontFamily.Default),
        titleLarge = inherited.titleLarge.copy(fontFamily = FontFamily.Default),
        titleMedium = inherited.titleMedium.copy(fontFamily = FontFamily.Default),
        titleSmall = inherited.titleSmall.copy(fontFamily = FontFamily.Default),
        bodyLarge = inherited.bodyLarge.copy(fontFamily = FontFamily.Default),
        bodyMedium = inherited.bodyMedium.copy(fontFamily = FontFamily.Default),
        bodySmall = inherited.bodySmall.copy(fontFamily = FontFamily.Default),
        labelLarge = inherited.labelLarge.copy(fontFamily = FontFamily.Default),
        labelMedium = inherited.labelMedium.copy(fontFamily = FontFamily.Default),
        labelSmall = inherited.labelSmall.copy(fontFamily = FontFamily.Default),
    ) }
    val systemStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Default)
    MaterialTheme(typography = typography) {
        CompositionLocalProvider(LocalWatchThemeFonts provides LyricsFonts(),
            LocalTextStyle provides systemStyle, content = content)
    }
}

/** One exact registration/binding and one current caller; this owns no Typefaces or I/O worker. */
internal class WatchFontWork(
    private val runtime: LyricsFontRuntime,
    private val editor: LyricsFontEditor,
    val request: WatchFontRequest,
    private val pageVisibleNow: () -> Boolean,
) {
    private val lock = Any()
    @Volatile private var admitted = false
    @Volatile private var retired = false
    private var caller: Job? = null
    private var retiredCaller: Job? = null
    private var observer: Job? = null
    private var retiredObserver: Job? = null
    private var presentation: LyricsFontRuntime.Presentation? = null
    private var editorBinding: LyricsFontPresentationOwner.Binding? = null

    // Runtime's font-owner lock invokes visibleNow. It must not take this work lock in reverse
    // order; retirement/bind below use work → font owner and publish reads only these flags.
    val isAlive: Boolean get() = admitted && !retired
    val previewText: LyricsFontPreviewText? get() = when (request) {
        is WatchFontRequest.Preview -> request.text
        is WatchFontRequest.Editor -> request.text
        else -> null
    }

    fun visibleNow(): Boolean = isAlive && pageVisibleNow()

    fun canInstall(): Boolean = synchronized(lock) { !retired && pageVisibleNow() }

    fun admit() = synchronized(lock) { if (!retired) admitted = true }

    fun beginCaller(job: Job): Boolean = synchronized(lock) {
        if (!admitted || retired || caller != null) return@synchronized false
        caller = job
        true
    }

    fun finishCaller(job: Job) = synchronized(lock) {
        if (caller === job) caller = null
    }

    fun beginObserver(job: Job): Boolean = synchronized(lock) {
        if (!admitted || retired || observer != null) return@synchronized false
        observer = job
        true
    }

    fun finishObserver(job: Job) = synchronized(lock) {
        if (observer === job) observer = null
    }

    suspend fun checkActive() {
        currentCoroutineContext().ensureActive()
        if (!visibleNow()) throw CancellationException("Font host page retired")
    }

    fun bind(text: LyricsFontPreviewText): Boolean = synchronized(lock) {
        if (!admitted || retired || !pageVisibleNow()) return@synchronized false
        check(presentation == null && editorBinding == null)
        when (val source = request) {
            is WatchFontRequest.Personal -> presentation = runtime.bindPersonalLyrics(text)
            is WatchFontRequest.ThemeUi -> presentation = runtime.bindThemeUi(source.snapshot, text)
            is WatchFontRequest.Preview -> presentation = runtime.bindPreview(source.preview, source.text, source.weights)
            is WatchFontRequest.Editor -> editorBinding = editor.bindPresentation()
        }
        true
    }

    /** Capture only this work's old Job while holding memory locks; cancellation is a later phase. */
    fun releaseMemory() = synchronized(lock) {
        if (retired) return@synchronized
        retired = true
        retiredCaller = caller
        caller = null
        retiredObserver = observer
        observer = null
        presentation?.close()
        presentation = null
        editorBinding?.close()
        editorBinding = null
    }

    fun cancelRetiredWork() {
        val old = synchronized(lock) {
            Pair(retiredCaller, retiredObserver).also {
                retiredCaller = null
                retiredObserver = null
            }
        }
        old.first?.cancel(CancellationException("Font host registration retired"))
        old.second?.cancel(CancellationException("Font host observer retired"))
        // The runtime also drains a retired read admitted before this exact slot replacement.
        // It cannot cancel a current successor, whose binding and caller Job are independent.
        runtime.cancelRetiredWork()
    }

    fun currentState(): LyricsFontRuntimeState = synchronized(lock) {
        if (!admitted || retired || !pageVisibleNow()) return@synchronized EMPTY
        presentation?.takeIf { it.isCurrent }?.state
            ?: editorBinding?.takeIf { it.isCurrent }?.state ?: EMPTY
    }

    fun editorMetadata(): LyricsFontEditorState? = synchronized(lock) {
        val exact = editorBinding ?: return@synchronized null
        if (!visibleNow() || !exact.isCurrent) return@synchronized null
        val current = editor.state.value
        // The retirement helper clears active as well as faces. Current face-free UI metadata
        // must retain its exact presentation gate so the real Save and Retry controls can act.
        current.withoutTypefaces().copy(
            presentationActive = current.presentationActive && exact.isCurrent && visibleNow(),
        )
    }

    fun canAdjustFontWeight(): Boolean = synchronized(lock) {
        visibleNow() && editorBinding?.isCurrent == true && editor.state.value.canAdjustWeight
    }

    suspend fun render(text: LyricsFontPreviewText) {
        checkActive()
        runtime.cancelRetiredWork()
        val bound = synchronized(lock) { presentation }
        if (bound != null) {
            val loaded = runtime.render(bound, ::visibleNow)
            if ((!loaded || bound.state.isBusy) && bound.isCurrent && visibleNow() && runtime.awaitIdle()) {
                checkActive()
                if (bound.isCurrent) runtime.render(bound, ::visibleNow)
            }
        } else {
            val binding = synchronized(lock) { editorBinding } ?: return
            if (!binding.isCurrent) return
            editor.resume(text)
            // An occupied old editor action publishes BUSY into its retired safe state. The
            // newly bound display may still be EMPTY, so observe its transaction metadata too.
            val transaction = editor.state.value
            val busy = binding.state.isBusy || transaction.working || transaction.issue == LyricsFontIssue.BUSY
            if (binding.isCurrent && busy && visibleNow() && runtime.awaitIdle()) {
                checkActive()
                if (binding.isCurrent) editor.resume(text)
            }
        }
    }

    suspend fun edit(action: suspend (LyricsFontEditor) -> Unit): Boolean = coroutineScope {
        if (request !is WatchFontRequest.Editor) return@coroutineScope false
        val job = checkNotNull(currentCoroutineContext()[Job])
        if (!beginCaller(job)) return@coroutineScope false
        try {
            checkActive()
            val binding = synchronized(lock) { editorBinding } ?: return@coroutineScope false
            if (!binding.isCurrent) return@coroutineScope false
            action(editor)
            true
        } finally { finishCaller(job) }
    }

    companion object { private val EMPTY = LyricsFontRuntimeState() }
}

private suspend fun acquireCoverage(source: WatchFontRequest, visibleNow: () -> Boolean): LyricsFontPreviewText =
    when (source) {
        is WatchFontRequest.Personal -> lyricsFontCoverage(source.ready, visibleNow)
        is WatchFontRequest.ThemeUi -> themeUiFontCoverage(source.resources, source.currentText, visibleNow)
        is WatchFontRequest.Preview -> checkedSample(source.text, visibleNow)
        is WatchFontRequest.Editor -> checkedSample(source.text, visibleNow)
    }

private suspend fun checkedSample(text: LyricsFontPreviewText, visibleNow: () -> Boolean): LyricsFontPreviewText {
    fontCoverageCheckpoint(visibleNow)
    if (text.original.length > LyricsFontLimits.TEXT_CHARACTERS || text.translation.length > LyricsFontLimits.TEXT_CHARACTERS) {
        throw LyricsFontException(LyricsFontIssue.INVALID_INPUT)
    }
    return text
}

private data class PersonalKey(val original: String, val primary: String?, val translation: String?)
private data class UiCoverageKey(val resources: Resources, val locale: String, val text: ThemeUiFontText)
private data class UiKey(val snapshot: ThemeSnapshot, val coverage: Any)
private data class PreviewKey(val preview: ExactObject, val text: ExactObject, val weights: LyricsFontRequestedWeights)
private class ExactObject(private val value: Any) {
    override fun equals(other: Any?): Boolean = other is ExactObject && value === other.value
    override fun hashCode(): Int = System.identityHashCode(value)
}
