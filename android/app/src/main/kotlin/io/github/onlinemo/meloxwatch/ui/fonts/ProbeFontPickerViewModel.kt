package io.github.onlinemo.meloxwatch.ui.fonts

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import io.github.onlinemo.meloxwatch.MeloXApplication
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontException
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontImportRequest
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontIssue
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontLimits
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontRole
import io.github.onlinemo.meloxwatch.data.local.LocalInputLimits
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout

/** Opaque transport identity. Its default string representation contains no selected document. */
class ProbeFontPickerTicket internal constructor()

/** Retained route generation: stable through Activity recreation, fresh after a real leave. */
class ProbeFontPickerRoute internal constructor()

/** Only safe UI metadata is observable; document URIs, flags and names never enter this flow. */
data class ProbeFontPickerState(
    val licenseRole: LyricsFontRole? = null,
    val acknowledgmentIdentity: Any? = null,
    val awaitingPlatform: Boolean = false,
    val pendingReturn: ProbeFontPickerTicket? = null,
    val consuming: Boolean = false,
    val issue: LyricsFontIssue? = null,
)

enum class ProbeFontPickerConsumption { DEFERRED, REJECTED, CONSUMED }

/**
 * One Activity-retained memory ticket, no SavedState, retained host, jobs or request queue.
 * Window STOP retires the caller/host, not the outstanding system-picker request. Main calls
 * leaveFontsRoute only for actual navigation and retires that page's host independently.
 */
class ProbeFontPickerViewModel internal constructor(
    application: Application,
    private val readDisplayName: suspend (String) -> String,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, fontPickerNameReader(application))

    private val lock = Any()
    private val mutable = MutableStateFlow(ProbeFontPickerState())
    val state: StateFlow<ProbeFontPickerState> = mutable.asStateFlow()
    private var routeKey: Any? = null
    private var route: ProbeFontPickerRoute? = null
    private var acknowledgment: Acknowledgment? = null
    private var platform: ProbeFontPickerTicket? = null
    private var request: Request? = null
    private var issue: LyricsFontIssue? = null

    /** Main supplies its stable page key; the ViewModel owns the actual route generation. */
    fun enterFontsRoute(routeIdentity: Any): ProbeFontPickerRoute = synchronized(lock) {
        val current = route
        if (routeKey === routeIdentity && current != null) return@synchronized current
        clearRequestLocked()
        acknowledgment = null
        routeKey = routeIdentity
        val next = ProbeFontPickerRoute()
        route = next
        issue = null
        publishLocked()
        next
    }

    fun currentFontsRouteToken(routeIdentity: Any): ProbeFontPickerRoute? = synchronized(lock) {
        route.takeIf { routeKey === routeIdentity }
    }

    /** Only Main's actual presentation transition calls the page-key variant. */
    fun leaveFontsRoute(routeIdentity: Any) = synchronized(lock) {
        if (routeKey !== routeIdentity) return@synchronized
        retireRouteLocked()
    }

    /** An event that carries an old route generation cannot retire a new same-page entry. */
    fun leaveFontsRouteToken(routeIdentity: ProbeFontPickerRoute) = synchronized(lock) {
        if (route !== routeIdentity) return@synchronized
        retireRouteLocked()
    }

    private fun retireRouteLocked() {
        routeKey = null
        route = null
        acknowledgment = null
        clearRequestLocked()
        issue = null
        // A retired launch must drain its one platform result before any successor may launch.
        // ACTION_OPEN_DOCUMENT does not echo our ticket, so overlapping launches are forbidden.
        publishLocked()
    }

    fun licenseAcknowledgedFor(draft: LyricsFontDraft?): Boolean = synchronized(lock) {
        draft != null && route != null && acknowledgment?.draft === draft
    }

    suspend fun acknowledgeLicense(
        routeIdentity: ProbeFontPickerRoute,
        host: WatchFontHostValue,
        checked: Boolean,
    ): Boolean {
        var changed = false
        host.edit { editor ->
            val admission = editorAdmission(editor)
            val draft = admission.draft
            if (!admission.ready || draft == null) return@edit
            synchronized(lock) {
                if (route !== routeIdentity) return@synchronized
                acknowledgment = if (checked) Acknowledgment(draft) else null
                issue = null
                publishLocked()
                changed = true
            }
        }
        return changed
    }

    /** Launch is a current Editor event, never an automatic effect or a mount-time replay. */
    suspend fun launchFontPicker(
        routeIdentity: ProbeFontPickerRoute,
        host: WatchFontHostValue,
        launch: (ProbeFontPickerTicket, Intent) -> Unit,
    ): Boolean {
        var launched = false
        host.edit { editor ->
            val admission = editorAdmission(editor)
            val draft = admission.draft
            if (!admission.ready || draft == null) return@edit
            val ticket = synchronized(lock) {
                if (route !== routeIdentity) return@synchronized null
                if (platform != null || request != null) {
                    issue = LyricsFontIssue.BUSY
                    publishLocked()
                    return@synchronized null
                }
                val accepted = acknowledgment
                if (accepted == null || accepted.draft !== draft) {
                    issue = LyricsFontIssue.LICENSE_ACKNOWLEDGMENT_REQUIRED
                    publishLocked()
                    return@synchronized null
                }
                ProbeFontPickerTicket().also {
                    request = Request(it, routeIdentity, draft, accepted.identity)
                    platform = it
                    issue = null
                    publishLocked()
                }
            } ?: return@edit
            try {
                launch(ticket, fontPickerIntent())
                launched = true
            } catch (cancelled: CancellationException) {
                abortLaunch(ticket, null)
                throw cancelled
            } catch (_: SecurityException) {
                abortLaunch(ticket, LyricsFontIssue.PERMISSION_DENIED)
            } catch (_: ActivityNotFoundException) {
                abortLaunch(ticket, LyricsFontIssue.SOURCE_UNAVAILABLE)
            } catch (_: RuntimeException) {
                abortLaunch(ticket, LyricsFontIssue.SOURCE_UNAVAILABLE)
            } catch (_: OutOfMemoryError) {
                abortLaunch(ticket, LyricsFontIssue.LOW_MEMORY)
            }
        }
        return launched
    }

    /** The ActivityResult registration uses this serialized transport; it never retains a host. */
    fun awaitingPlatformTicket(): ProbeFontPickerTicket? = synchronized(lock) { platform }

    fun onPickerResult(
        ticket: ProbeFontPickerTicket,
        resultCode: Int,
        uri: Uri?,
        flags: Int,
    ): Boolean = synchronized(lock) {
        if (platform !== ticket) return@synchronized false
        platform = null
        val pending = request
        if (pending == null || pending.ticket !== ticket || pending.phase != Phase.LAUNCHED || route !== pending.routeIdentity) {
            publishLocked()
            return@synchronized false
        }
        if (resultCode != Activity.RESULT_OK) {
            clearRequestLocked()
            issue = null
            publishLocked()
            return@synchronized true
        }
        val encoded = uri?.toString()
        val failure = when {
            acknowledgment?.identity !== pending.acknowledgmentIdentity ->
                LyricsFontIssue.LICENSE_ACKNOWLEDGMENT_REQUIRED
            uri == null || encoded.isNullOrBlank() || encoded.length > LocalInputLimits.URI_CHARACTERS ||
                encoded.any(Char::isISOControl) || uri.scheme != "content" || uri.authority.isNullOrBlank() ->
                LyricsFontIssue.INVALID_INPUT
            flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0 -> LyricsFontIssue.PERMISSION_DENIED
            else -> null
        }
        if (failure != null) {
            clearRequestLocked()
            issue = failure
        } else {
            pending.uri = checkNotNull(encoded)
            pending.phase = Phase.RETURNED
            issue = null
        }
        publishLocked()
        true
    }

    fun isPendingReturn(routeIdentity: ProbeFontPickerRoute, ticket: ProbeFontPickerTicket): Boolean = synchronized(lock) {
        route === routeIdentity && request?.ticket === ticket && request?.phase == Phase.RETURNED
    }

    /**
     * Call only with the newly presented current host. Admission and exact draft/role/consent
     * checks consume the ticket before the name read. Cancellation after consumption cannot
     * replay import or save on a replacement host, even if the provider finishes late.
     */
    suspend fun consumeReturn(
        routeIdentity: ProbeFontPickerRoute,
        ticket: ProbeFontPickerTicket,
        host: WatchFontHostValue,
    ): ProbeFontPickerConsumption {
        var consumption = ProbeFontPickerConsumption.DEFERRED
        val admitted = host.edit { editor ->
            val admission = editorAdmission(editor)
            if (!admission.active || admission.working) return@edit
            val draft = admission.draft
            val payload = synchronized(lock) {
                val pending = request
                if (route !== routeIdentity || pending == null || pending.ticket !== ticket || pending.phase != Phase.RETURNED) {
                    consumption = ProbeFontPickerConsumption.REJECTED
                    return@synchronized null
                }
                if (!admission.ready || draft == null || draft !== pending.draft ||
                    draft.role != pending.draft.role ||
                    acknowledgment?.identity !== pending.acknowledgmentIdentity) {
                    clearRequestLocked()
                    issue = LyricsFontIssue.INVALID_INPUT
                    publishLocked()
                    consumption = ProbeFontPickerConsumption.REJECTED
                    return@synchronized null
                }
                val selected = pending.uri ?: return@synchronized null
                pending.uri = null
                pending.phase = Phase.CONSUMING
                consumption = ProbeFontPickerConsumption.CONSUMED
                issue = null
                // Keep the same pendingReturn identity until the action has finished. A new
                // effect key here would cancel the import because of its own UI notification.
                publishLocked()
                ReturnedPayload(selected, pending.draft)
            } ?: return@edit
            try {
                val name = withTimeout(NAME_READ_TIMEOUT_MILLIS) { readDisplayName(payload.uri) }
                currentCoroutineContext().ensureActive()
                val current = editorAdmission(editor)
                val currentDraft = current.draft
                if (!ownsConsumption(routeIdentity, ticket)) return@edit
                if (!current.ready || currentDraft == null || currentDraft !== payload.draft ||
                    currentDraft.role != payload.draft.role || host.editorState?.draft !== payload.draft) {
                    recordIssue(routeIdentity, ticket, LyricsFontIssue.INVALID_INPUT)
                    return@edit
                }
                if (name.isBlank() || name.length > LyricsFontLimits.DISPLAY_NAME_CHARACTERS || name.any(Char::isISOControl)) {
                    throw LyricsFontException(LyricsFontIssue.INVALID_INPUT)
                }
                val text = host.previewText ?: return@edit
                editor.importFont(LyricsFontImportRequest(payload.uri, name,
                    LyricsFontLimits.LICENSE_ACKNOWLEDGMENT_VERSION), text)
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                recordIssue(routeIdentity, ticket, LyricsFontIssue.BUSY)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: LyricsFontException) {
                recordIssue(routeIdentity, ticket, error.issue)
            } catch (error: SourceReadException) {
                recordIssue(routeIdentity, ticket, when (error.issue) {
                    SourceReadIssue.BUSY -> LyricsFontIssue.BUSY
                    SourceReadIssue.PERMISSION_DENIED, SourceReadIssue.PERMISSION_REVOKED -> LyricsFontIssue.PERMISSION_DENIED
                    else -> LyricsFontIssue.SOURCE_UNAVAILABLE
                })
            } catch (_: SecurityException) {
                recordIssue(routeIdentity, ticket, LyricsFontIssue.PERMISSION_DENIED)
            } catch (_: Exception) {
                recordIssue(routeIdentity, ticket, LyricsFontIssue.SOURCE_UNAVAILABLE)
            } catch (_: OutOfMemoryError) {
                recordIssue(routeIdentity, ticket, LyricsFontIssue.LOW_MEMORY)
            } finally {
                synchronized(lock) {
                    if (request?.ticket === ticket && request?.phase == Phase.CONSUMING) {
                        clearRequestLocked()
                        acknowledgment = null
                        publishLocked()
                    }
                }
            }
        }
        return if (admitted) consumption else ProbeFontPickerConsumption.DEFERRED
    }

    internal fun expireReturn(routeIdentity: ProbeFontPickerRoute, ticket: ProbeFontPickerTicket) = synchronized(lock) {
        if (route !== routeIdentity || request?.ticket !== ticket || request?.phase != Phase.RETURNED) return@synchronized
        clearRequestLocked()
        issue = LyricsFontIssue.BUSY
        publishLocked()
    }

    private fun ownsConsumption(routeIdentity: ProbeFontPickerRoute, ticket: ProbeFontPickerTicket): Boolean = synchronized(lock) {
        val pending = request
        route === routeIdentity && pending != null && pending.ticket === ticket && pending.phase == Phase.CONSUMING &&
            acknowledgment?.identity === pending.acknowledgmentIdentity
    }

    private fun recordIssue(routeIdentity: ProbeFontPickerRoute, ticket: ProbeFontPickerTicket, failure: LyricsFontIssue) = synchronized(lock) {
        if (route === routeIdentity && request?.ticket === ticket) {
            issue = failure
            publishLocked()
        }
    }

    private fun abortLaunch(ticket: ProbeFontPickerTicket, failure: LyricsFontIssue?) = synchronized(lock) {
        if (platform !== ticket) return@synchronized
        platform = null
        if (request?.ticket === ticket) {
            clearRequestLocked()
            issue = failure
        }
        publishLocked()
    }

    private fun clearRequestLocked() {
        request?.uri = null
        request = null
    }

    private fun publishLocked() {
        mutable.value = ProbeFontPickerState(
            licenseRole = acknowledgment?.draft?.role,
            acknowledgmentIdentity = acknowledgment?.identity,
            awaitingPlatform = platform != null,
            pendingReturn = request?.takeIf { it.phase != Phase.LAUNCHED }?.ticket,
            consuming = request?.phase == Phase.CONSUMING,
            issue = issue,
        )
    }

    override fun onCleared() {
        synchronized(lock) {
            routeKey = null
            route = null
            acknowledgment = null
            platform = null
            clearRequestLocked()
            issue = null
            publishLocked()
        }
        super.onCleared()
    }

    private class Acknowledgment(val draft: LyricsFontDraft) { val identity = Any() }
    private class Request(
        val ticket: ProbeFontPickerTicket,
        val routeIdentity: ProbeFontPickerRoute,
        val draft: LyricsFontDraft,
        val acknowledgmentIdentity: Any,
    ) {
        var phase = Phase.LAUNCHED
        var uri: String? = null
    }
    private class ReturnedPayload(val uri: String, val draft: LyricsFontDraft)
    private enum class Phase { LAUNCHED, RETURNED, CONSUMING }

    private class EditorAdmission(
        val active: Boolean,
        val working: Boolean,
        val draft: LyricsFontDraft?,
        val ready: Boolean,
    )

    /** Do not retain the face-bearing editor state across any suspension or in the ViewModel. */
    private fun editorAdmission(editor: LyricsFontEditor): EditorAdmission {
        val current = editor.state.value
        val draft = current.draft
        val ready = current.presentationActive && !current.working && current.retry == null &&
            draft != null && current.saved?.revision == draft.baseRevision
        return EditorAdmission(current.presentationActive, current.working, draft, ready)
    }

    companion object {
        private const val NAME_READ_TIMEOUT_MILLIS = 2_500L
    }
}

private fun fontPickerIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
    addCategory(Intent.CATEGORY_OPENABLE)
    type = "*/*"
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // Font MIME declarations vary by provider. The existing bounded TTF/OTF parser is the
    // authority; selection grants temporary read access only, never original-file writes.
}

private fun fontPickerNameReader(application: Application): suspend (String) -> String {
    val context = application.applicationContext
    val io = (application as MeloXApplication).localProviderIo
    return { encoded ->
        io.read { signal ->
            context.contentResolver.query(encoded.toUri(), arrayOf(OpenableColumns.DISPLAY_NAME),
                null, null, null, signal)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column < 0 || !cursor.moveToFirst() || cursor.isNull(column)) {
                    throw LyricsFontException(LyricsFontIssue.SOURCE_UNAVAILABLE)
                }
                cursor.getString(column) ?: throw LyricsFontException(LyricsFontIssue.SOURCE_UNAVAILABLE)
            } ?: throw LyricsFontException(LyricsFontIssue.SOURCE_UNAVAILABLE)
        }
    }
}
