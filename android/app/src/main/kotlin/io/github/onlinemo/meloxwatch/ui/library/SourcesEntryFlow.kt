package io.github.onlinemo.meloxwatch.ui.library

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.annotation.MainThread
import androidx.lifecycle.SavedStateHandle
import io.github.onlinemo.meloxwatch.data.local.LocalInputLimits
import java.util.UUID
import kotlinx.coroutines.CancellationException

enum class SourcesEntryAvailability { UNKNOWN, AVAILABLE, UNAVAILABLE }

/** Component presence is separate from source read permission and successful provider access. */
data class SourcesEntryCapabilities(
    val file: SourcesEntryAvailability = SourcesEntryAvailability.UNKNOWN,
    val tree: SourcesEntryAvailability = SourcesEntryAvailability.UNKNOWN,
    val media: SourcesEntryAvailability = SourcesEntryAvailability.UNKNOWN,
    val checkedAtElapsedMs: Long? = null,
)

enum class SourcesMediaPermission { UNKNOWN, REQUESTABLE, GRANTED, SETTINGS_ONLY }

@MainThread
internal fun sourcesEntryCapabilities(context: Context): SourcesEntryCapabilities {
    fun observed(block: () -> Boolean): SourcesEntryAvailability = try {
        if (block()) SourcesEntryAvailability.AVAILABLE else SourcesEntryAvailability.UNAVAILABLE
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: RuntimeException) { SourcesEntryAvailability.UNKNOWN }
    catch (_: OutOfMemoryError) { SourcesEntryAvailability.UNKNOWN }
    return SourcesEntryCapabilities(
        file = observed { sourcesPickerIntent(SourcesPickerKind.FILE).resolveActivity(context.packageManager) != null },
        tree = observed { sourcesPickerIntent(SourcesPickerKind.TREE).resolveActivity(context.packageManager) != null },
        media = observed { context.packageManager.resolveContentProvider(MediaStore.AUTHORITY, 0)?.let { provider ->
            provider.enabled && provider.applicationInfo?.enabled != false &&
                (provider.exported || provider.packageName == context.packageName)
        } == true },
        checkedAtElapsedMs = SystemClock.elapsedRealtime(),
    )
}

internal enum class SourcesPickerKind {
    FILE, TREE, MEDIA_ALL, MEDIA_PRIMARY;
    val isMedia: Boolean get() = this == MEDIA_ALL || this == MEDIA_PRIMARY
}

internal fun sourcesPickerIntent(kind: SourcesPickerKind): Intent {
    require(!kind.isMedia)
    val intent = if (kind == SourcesPickerKind.TREE) Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        else Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*")
    return intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
}

/** An opaque platform slot; it contains no URI, permission or retained Activity. */
internal class SourcesPickerTicket(val kind: SourcesPickerKind?, val id: String = UUID.randomUUID().toString()) {
    override fun toString(): String = "SourcesPickerTicket"
}

internal enum class SourcesPickerOutcome { IGNORED, EXPIRED, CANCELLED, INVALID, PERMISSION_DENIED, DEFERRED }

internal class SourcesReturnedInput(
    val kind: SourcesPickerKind,
    val uri: Uri?,
    val flags: Int,
    val isCurrent: () -> Boolean,
) {
    override fun toString(): String = "SourcesReturnedInput"
}

/**
 * One platform request and one bounded in-memory return. OPEN_DOCUMENT does not echo a nonce,
 * so a retired launch must drain before another launch can use the same registered callbacks.
 * SavedState restores only that drain obligation, never an active request or read authorization.
 */
@MainThread
internal class SourcesPickerRequests(private val saved: SavedStateHandle) {
    private class Request(val ticket: SourcesPickerTicket, val owner: LibraryNavigationOwner, val route: Any)
    private class Returned(val request: Request, val uri: Uri?, val flags: Int)
    private var owner: LibraryNavigationOwner? = null
    private var route: Any? = null
    private var request: Request? = null
    private var platform: SourcesPickerTicket? = restorePlatform()
    private var returned: Returned? = null

    val awaitingPlatform: Boolean get() = platform != null
    val awaitingForeground: Boolean get() = returned != null
    val restoredDrain: Boolean get() = platform != null && request == null

    fun selectPage(next: LibraryNavigationOwner?): Boolean {
        if (owner === next && next?.isCurrent == true && route != null) return false
        val changed = owner != null || request != null || returned != null
        request = null
        returned = null
        owner = next?.takeIf { it.isCurrent }
        route = owner?.let { Any() }
        return changed
    }

    fun newExternalIntent() {
        request = null
        returned = null
        route = owner?.takeIf { it.isCurrent }?.let { Any() }
    }

    fun currentFence(): () -> Boolean {
        val currentOwner = owner
        val currentRoute = route
        return { currentOwner != null && currentOwner.isCurrent && owner === currentOwner &&
            currentRoute != null && route === currentRoute }
    }

    fun begin(kind: SourcesPickerKind): SourcesPickerTicket? {
        val current = owner?.takeIf { it.isCurrent } ?: return null
        val currentRoute = route ?: return null
        if (platform != null || returned != null) return null
        val ticket = SourcesPickerTicket(kind)
        request = Request(ticket, current, currentRoute)
        platform = ticket
        recordPlatform()
        return ticket
    }

    fun awaiting(kind: SourcesPickerKind): SourcesPickerTicket? = platform?.takeIf { it.kind == null || it.kind == kind }
    fun awaitingMedia(): SourcesPickerTicket? = platform?.takeIf { it.kind == null || it.kind.isMedia }

    fun launchFailed(ticket: SourcesPickerTicket, definitelyNotLaunched: Boolean): Boolean {
        if (platform !== ticket) return false
        val current = request?.takeIf { it.ticket === ticket && current(it) } != null
        request = null
        returned = null
        if (definitelyNotLaunched) { platform = null; recordPlatform() }
        return current
    }

    fun documentResult(ticket: SourcesPickerTicket, resultCode: Int, uri: Uri?, flags: Int): SourcesPickerOutcome {
        if (ticket.kind?.isMedia == true) return SourcesPickerOutcome.IGNORED
        return consumePlatform(ticket) { accepted ->
            when {
                resultCode != Activity.RESULT_OK -> SourcesPickerOutcome.CANCELLED
                uri == null || uri.scheme != "content" || uri.authority.isNullOrBlank() ||
                    uri.toString().length > LocalInputLimits.URI_CHARACTERS -> SourcesPickerOutcome.INVALID
                else -> {
                    returned = Returned(accepted, uri, flags)
                    SourcesPickerOutcome.DEFERRED
                }
            }
        }
    }

    fun mediaResult(ticket: SourcesPickerTicket, granted: Boolean): SourcesPickerOutcome {
        if (ticket.kind != null && !ticket.kind.isMedia) return SourcesPickerOutcome.IGNORED
        return consumePlatform(ticket) { accepted ->
            if (!granted) SourcesPickerOutcome.PERMISSION_DENIED
            else {
                returned = Returned(accepted, null, 0)
                SourcesPickerOutcome.DEFERRED
            }
        }
    }

    private fun consumePlatform(ticket: SourcesPickerTicket, consume: (Request) -> SourcesPickerOutcome): SourcesPickerOutcome {
        if (platform !== ticket) return SourcesPickerOutcome.IGNORED
        platform = null
        recordPlatform()
        val accepted = request
        request = null
        if (accepted == null || accepted.ticket !== ticket || !current(accepted)) {
            return SourcesPickerOutcome.EXPIRED
        }
        return consume(accepted)
    }

    fun takeReturn(): SourcesReturnedInput? {
        val pending = returned ?: return null
        returned = null
        val accepted = pending.request
        if (!current(accepted)) return null
        return SourcesReturnedInput(requireNotNull(accepted.ticket.kind), pending.uri, pending.flags) { current(accepted) }
    }

    private fun current(value: Request): Boolean = value.owner.isCurrent && owner === value.owner && route === value.route

    private fun recordPlatform() {
        val ticket = platform
        if (ticket == null) saved.remove<Any?>(KEY_PLATFORM)
        else saved[KEY_PLATFORM] = arrayListOf<Any>(1, ticket.kind?.name ?: "UNKNOWN", ticket.id)
    }

    private fun restorePlatform(): SourcesPickerTicket? {
        if (!saved.contains(KEY_PLATFORM)) return null
        val fields = saved.get<Any?>(KEY_PLATFORM) as? List<*>
        val name = if (fields?.size == 3) fields[1] as? String else null
        val id = if (fields?.size == 3) fields[2] as? String else null
        val kind = SourcesPickerKind.entries.firstOrNull { it.name == name }
        if (fields?.size == 3 && fields[0] as? Int == 1 && (kind != null || name == "UNKNOWN") &&
            id != null && id.matches(UUID_PATTERN)) return SourcesPickerTicket(kind, id)
        // An unclassified old slot can only discard its eventual result, never accept a new launch.
        return SourcesPickerTicket(null).also {
            saved[KEY_PLATFORM] = arrayListOf<Any>(1, "UNKNOWN", it.id)
        }
    }

    companion object {
        private const val KEY_PLATFORM = "sources:platform-drain-v1"
        private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
