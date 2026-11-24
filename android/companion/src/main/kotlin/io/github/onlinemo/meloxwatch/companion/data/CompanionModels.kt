package io.github.onlinemo.meloxwatch.companion.data

import io.github.onlinemo.meloxwatch.data.transfer.TransferCapability
import io.github.onlinemo.meloxwatch.data.transfer.TransferCatalogKind
import io.github.onlinemo.meloxwatch.data.transfer.TransferCatalogPage
import io.github.onlinemo.meloxwatch.data.transfer.TransferCatalogRow
import io.github.onlinemo.meloxwatch.data.transfer.TransferDisplayKind
import io.github.onlinemo.meloxwatch.data.transfer.TransferDisplayPatch
import io.github.onlinemo.meloxwatch.data.transfer.TransferDisplayPreview
import io.github.onlinemo.meloxwatch.data.transfer.TransferException
import io.github.onlinemo.meloxwatch.data.transfer.TransferFingerprint
import io.github.onlinemo.meloxwatch.data.transfer.TransferHash
import io.github.onlinemo.meloxwatch.data.transfer.TransferIssue
import io.github.onlinemo.meloxwatch.data.transfer.TransferLibraryRevision
import io.github.onlinemo.meloxwatch.data.transfer.TransferLimits
import io.github.onlinemo.meloxwatch.data.transfer.TransferOperationKind
import io.github.onlinemo.meloxwatch.data.transfer.TransferPlaylistEdit
import io.github.onlinemo.meloxwatch.data.transfer.TransferReceipt
import io.github.onlinemo.meloxwatch.data.transfer.TransferUploadDeclaration
import io.github.onlinemo.meloxwatch.data.transfer.TransferUploadKind
import java.net.Inet6Address
import java.net.InetAddress
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Collections
import java.util.UUID
import kotlin.math.pow

object CompanionLimits {
    const val PAGE_WINDOW = 3
    const val LOCAL_RECORD_BYTES = 256 * 1024
    const val DRAFTS = 32
    const val STAGED_AUDIO_BYTES = 1024L * 1024 * 1024
    const val STAGED_THEME_BYTES = 32L * 1024 * 1024
    const val FREE_RESERVE_BYTES = 512L * 1024 * 1024
    const val IMAGE_BYTES = 16 * 1024 * 1024
    const val IMAGE_PIXELS = 16_000_000
    const val ICON_EDGE = 256
    const val ICON_BYTES = 1024 * 1024
    const val CONNECT_MS = 5000L
    const val IDLE_MS = 8000L
    const val LOCAL_IO_MS = 60_000L
    // 控制等待上限不代表 provider 或 socket 一定在该时间内物理退出。
    const val RETIREMENT_WAIT_MS = 5000L
}

fun interface CompanionClock { fun monotonicMs(): Long }

/** Only numerical LAN literals are accepted. Parsing never asks DNS to resolve a host. */
class CompanionEndpoint private constructor(val address: InetAddress, val port: Int) {
    override fun toString(): String = "<local-endpoint>"

    companion object {
        fun parse(literal: String, port: String): CompanionEndpoint {
            try {
                val number = port.toIntOrNull() ?: invalid()
                if (number !in 1024..65535) invalid()
                val text = literal.trim().removeSurrounding("[", "]")
                if (text.isEmpty() || text.length > 80) invalid()
                val address = if (':' in text) ipv6(text) else ipv4(text)
                val local = address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress ||
                    (address.address.size == 16 && (address.address[0].toInt() and 0xfe) == 0xfc)
                if (!local || address.isAnyLocalAddress || address.isMulticastAddress) invalid()
                return CompanionEndpoint(address, number)
            } catch (error: TransferException) { throw error }
              catch (_: Exception) { invalid() }
        }

        private fun ipv4(value: String): InetAddress {
            val parts = value.split('.')
            if (parts.size != 4) invalid()
            val bytes = parts.map { part ->
                if (!part.matches(Regex("0|[1-9][0-9]{0,2}"))) invalid()
                val number = part.toInt()
                if (number !in 0..255) invalid()
                number.toByte()
            }.toByteArray()
            return InetAddress.getByAddress(bytes)
        }

        private fun ipv6(value: String): InetAddress {
            val zoneParts = value.split('%')
            if (zoneParts.size > 2) invalid()
            val scope = if (zoneParts.size == 2) zoneParts[1].toIntOrNull()?.takeIf { it > 0 } ?: invalid() else 0
            val literal = zoneParts[0]
            if (!literal.matches(Regex("[0-9a-fA-F:]+"))) invalid()
            val halves = literal.split("::")
            if (halves.size > 2) invalid()
            fun groups(part: String): List<Int> = if (part.isEmpty()) emptyList() else part.split(':').map {
                if (!it.matches(Regex("[0-9a-fA-F]{1,4}"))) invalid()
                it.toInt(16)
            }
            val left = groups(halves[0])
            val right = if (halves.size == 2) groups(halves[1]) else emptyList()
            val omitted = 8 - left.size - right.size
            if (halves.size == 1 && omitted != 0 || halves.size == 2 && omitted < 1) invalid()
            val words = left + List(omitted) { 0 } + right
            if (words.size != 8) invalid()
            val bytes = ByteArray(16)
            words.forEachIndexed {
                index, word -> bytes[index * 2] = (word ushr 8).toByte()
                bytes[index * 2 + 1] = word.toByte()
            }
            return Inet6Address.getByAddress(null, bytes, scope)
        }

        private fun invalid(): Nothing = throw TransferException(TransferIssue.INVALID_INPUT)
    }
}

class CompanionTarget(val fingerprint: TransferFingerprint, val label: String) {
    init { requireLocalText(label, 80) }
    override fun toString(): String = "<paired-target>"
}

enum class CompanionConnectionPhase { OFF, CONNECTING, PAIRING, WAITING_FOR_WATCH, AUTHENTICATING, READY, STOPPING, RECOVERY_REQUIRED }

class CompanionConnectionState(
    val phase: CompanionConnectionPhase = CompanionConnectionPhase.OFF,
    val target: CompanionTarget? = null,
    val sessionId: String? = null,
    capabilities: Set<TransferCapability> = emptySet(),
    val issue: TransferIssue? = null,
    val phoneFingerprint: TransferFingerprint? = null,
) {
    val capabilities: Set<TransferCapability> = Collections.unmodifiableSet(capabilities.toSet())
    override fun toString(): String = "<companion-session-state>"
}

/** The device remains the authority. A cached page is visibly stale once its session closes. */
class CompanionCachedPage(val target: TransferFingerprint, val sessionId: String, val page: TransferCatalogPage, val capturedAtEpochMs: Long) {
    init {
        localUuid(sessionId)
        require(capturedAtEpochMs >= 0L)
    }
    override fun toString(): String = "<local-cached-page>"
}

class CompanionPageWindow(
    val kind: TransferCatalogKind,
    val search: String,
    val parentId: String?,
    pages: List<CompanionCachedPage>,
    val index: Int,
) {
    val pages: List<CompanionCachedPage> = Collections.unmodifiableList(pages.toList())
    val current: CompanionCachedPage? get() = pages.getOrNull(index)
    init {
        require(pages.size <= CompanionLimits.PAGE_WINDOW)
        require(index == -1 && pages.isEmpty() || index in pages.indices)
        require(search.length <= TransferLimits.FIELD_CHARACTERS && search.toByteArray(Charsets.UTF_8).size <= TransferLimits.FIELD_BYTES && '\u0000' !in search)
        require((kind in setOf(TransferCatalogKind.PLAYLIST_MEMBERS, TransferCatalogKind.ALBUM_TRACKS, TransferCatalogKind.ARTIST_TRACKS)) == (parentId != null))
        parentId?.let(::requireLocalIdentifier)
        val first = pages.firstOrNull()
        val identities = HashSet<String>()
        pages.forEachIndexed { position, cached ->
            val page = cached.page
            require(page.kind == kind && cached.target == first?.target && cached.sessionId == first?.sessionId)
            require(page.rows.all { identities.add(companionRowIdentity(it)) })
            if (position > 0) {
                val previous = pages[position - 1].page
                require(previous.nextCursor != null && page.viewId == previous.viewId && page.revision == previous.revision &&
                    page.libraryRevision.sameAs(previous.libraryRevision) && page.totalCount == previous.totalCount &&
                    page.firstOrdinal == Math.addExact(previous.firstOrdinal, previous.rows.size.toLong()))
            }
        }
    }
    fun append(page: CompanionCachedPage): CompanionPageWindow {
        val retained = (pages.take(index + 1) + page).takeLast(CompanionLimits.PAGE_WINDOW)
        return CompanionPageWindow(kind, search, parentId, retained, retained.lastIndex)
    }
    fun previous(): CompanionPageWindow = if (pages.isEmpty()) this else CompanionPageWindow(kind, search, parentId, pages, (index - 1).coerceAtLeast(0))
    fun cachedNext(): CompanionPageWindow? = if (index + 1 in pages.indices) CompanionPageWindow(kind, search, parentId, pages, index + 1) else null
    override fun toString(): String = "<bounded-page-window>"
}

enum class CompanionUploadPhase { PREPARING, READY, SENDING, VERIFYING, COMPLETED, CANCELLED, UNCERTAIN, FAILED, STOPPING, RETIRING_COPY }

class CompanionUploadDraft(
    val operationId: String,
    val target: TransferFingerprint?,
    val fileId: String,
    val declaration: TransferUploadDeclaration,
    val phase: CompanionUploadPhase,
    val receipt: TransferReceipt? = null,
) {
    init {
        localUuid(operationId)
        localUuid(fileId)
        require(declaration.declaredBytes != null)
        if (receipt != null) require(target != null && receipt.binding.operationId == operationId && receipt.binding.target == target &&
            receipt.binding.kind == (if (declaration.kind == TransferUploadKind.AUDIO) TransferOperationKind.AUDIO else TransferOperationKind.THEME) &&
            receipt.actualBytes == declaration.declaredBytes)
        require(phase != CompanionUploadPhase.COMPLETED || receipt != null)
    }
    fun bind(target: TransferFingerprint): CompanionUploadDraft {
        if (this.target != null && this.target != target) throw TransferException(TransferIssue.WRONG_TARGET)
        return CompanionUploadDraft(operationId, target, fileId, declaration, phase, receipt)
    }
    internal fun samePreparedPayload(other: CompanionUploadDraft): Boolean = fileId == other.fileId &&
        declaration.kind == other.declaration.kind && declaration.displayName == other.declaration.displayName &&
        declaration.mediaType == other.declaration.mediaType && declaration.declaredBytes == other.declaration.declaredBytes &&
        declaration.sha256 == other.declaration.sha256 && declaration.expectedRevision == other.declaration.expectedRevision
    fun withResult(phase: CompanionUploadPhase, receipt: TransferReceipt? = null): CompanionUploadDraft = CompanionUploadDraft(operationId, target, fileId, declaration, phase, receipt)
    override fun toString(): String = "<selected-file-draft>"
}

class CompanionUploadProgress(val phase: CompanionUploadPhase, val sentBytes: Long, val totalBytes: Long, val issue: TransferIssue? = null) {
    init { require(totalBytes > 0L && sentBytes in 0..totalBytes) }
    override fun toString(): String = "<local-upload-progress>"
}

enum class CompanionEditPhase { DRAFT, PREVIEWED, SUBMITTING, UNCERTAIN, COMPLETED, CONFLICT }

class CompanionPlaylistDraft(
    val operationId: String,
    val target: TransferFingerprint,
    val playlistId: String,
    val playlistName: String,
    val expectedRevision: Long,
    val libraryRevision: TransferLibraryRevision?,
    val edit: TransferPlaylistEdit,
    val phase: CompanionEditPhase = CompanionEditPhase.DRAFT,
    val receipt: TransferReceipt? = null,
) {
    init {
        localUuid(operationId)
        requireLocalIdentifier(playlistId)
        require(expectedRevision >= 0L)
        requireLocalField(playlistName)
        require(edit !is TransferPlaylistEdit.Add || libraryRevision != null)
        require(edit !is TransferPlaylistEdit.Create || expectedRevision == 0L)
        require(phase != CompanionEditPhase.PREVIEWED)
        if (receipt != null) require(receipt.binding.operationId == operationId && receipt.binding.target == target && receipt.binding.kind == TransferOperationKind.PLAYLIST && receipt.resultId == playlistId)
        require(phase != CompanionEditPhase.COMPLETED || receipt != null)
    }
    fun withPhase(phase: CompanionEditPhase, receipt: TransferReceipt? = this.receipt): CompanionPlaylistDraft =
        CompanionPlaylistDraft(operationId, target, playlistId, playlistName, expectedRevision, libraryRevision, edit, phase, receipt)
    override fun toString(): String = "<playlist-edit-draft>"
}

class CompanionDisplayDraft(
    val operationId: String,
    val target: TransferFingerprint,
    val kind: TransferDisplayKind,
    val entityId: String,
    val expectedRevision: Long,
    val libraryRevision: TransferLibraryRevision,
    val currentName: String,
    val currentTrackCount: Long,
    val patch: TransferDisplayPatch,
    val previewId: String? = null,
    val phase: CompanionEditPhase = CompanionEditPhase.DRAFT,
    val receipt: TransferReceipt? = null,
) {
    val proposedName: String get() = if (patch is TransferDisplayPatch.Rename) patch.displayName else currentName
    init {
        localUuid(operationId)
        requireLocalIdentifier(entityId)
        require(expectedRevision >= 0L && expectedRevision == libraryRevision.catalogRevision)
        requireLocalField(currentName)
        require(currentTrackCount >= 0L)
        previewId?.let(::localUuid)
        require(previewId == null || previewId == operationId)
        require(phase == CompanionEditPhase.DRAFT || phase == CompanionEditPhase.CONFLICT || previewId != null)
        if (receipt != null) require(receipt.binding.operationId == operationId && receipt.binding.target == target && receipt.binding.kind == TransferOperationKind.DISPLAY && receipt.resultId == entityId)
        require(phase != CompanionEditPhase.COMPLETED || receipt != null)
    }
    fun withPreview(preview: TransferDisplayPreview): CompanionDisplayDraft {
        require(phase == CompanionEditPhase.DRAFT)
        if (preview.previewId != operationId || preview.kind != kind || preview.entityId != entityId || preview.revision != expectedRevision ||
            !preview.libraryRevision.sameAs(libraryRevision) || !preview.patch.sameAs(patch) || preview.currentName != currentName ||
            preview.proposedName != proposedName || preview.currentTrackCount != currentTrackCount) throw TransferException(TransferIssue.CONFLICT)
        return CompanionDisplayDraft(operationId, target, kind, entityId, expectedRevision, libraryRevision, currentName, currentTrackCount, patch, preview.previewId, CompanionEditPhase.PREVIEWED)
    }
    fun withPhase(phase: CompanionEditPhase, receipt: TransferReceipt? = this.receipt): CompanionDisplayDraft =
        CompanionDisplayDraft(operationId, target, kind, entityId, expectedRevision, libraryRevision, currentName, currentTrackCount, patch, previewId, phase, receipt)
    override fun toString(): String = "<display-edit-draft>"
}

enum class CompanionColor(val wireName: String, val defaultHex: String) {
    BACKGROUND("background", "#000000"), SURFACE("surface", "#111114"), SURFACE_RAISED("surfaceRaised", "#1C1C1F"),
    SURFACE_PRESSED("surfacePressed", "#29292D"), TEXT_PRIMARY("textPrimary", "#FFFFFF"), TEXT_SECONDARY("textSecondary", "#C7C7CC"),
    ACCENT("accent", "#FF375F"), ERROR("error", "#FFB4AB"), FOCUS("focus", "#A1A1A6"), DISABLED_TEXT("disabledText", "#ADADAD"),
}

enum class CompanionIconRole { PLAY, PAUSE, PREVIOUS, NEXT, MENU, BACK, LYRICS, FAVORITE, QUEUE }
enum class CompanionThemeBackground(val wireName: String) { DEFAULT("default"), BLACK("black"), COVER_BLUR("coverBlur"), FLUID("fluid") }

class CompanionThemeIcon(val role: CompanionIconRole, val fileId: String, val sha256: TransferHash, val byteCount: Long, val licenseName: String, val licenseText: String) {
    init {
        localUuid(fileId)
        require(byteCount in 1..CompanionLimits.ICON_BYTES.toLong())
        requireLocalText(licenseName, 512)
        requireLocalText(licenseText, 16 * 1024, multiline = true)
    }
    override fun toString(): String = "<licensed-icon-draft>"
}

class CompanionThemeDraft(
    val id: String = "phone." + UUID.randomUUID().toString(),
    val name: String = "MeloX",
    val author: String = "Local editor",
    val licenseName: String = "User supplied",
    val licenseText: String = "Original color tokens. Only separately selected, licensed images are included.",
    colors: Map<CompanionColor, String> = CompanionColor.entries.associateWith { it.defaultHex },
    val background: CompanionThemeBackground = CompanionThemeBackground.DEFAULT,
    val blurLevel: Int = 0,
    val darkMaskPercent: Int = 50,
    icons: List<CompanionThemeIcon> = emptyList(),
    val revision: Long = 0L,
) {
    val colors: Map<CompanionColor, String> = Collections.unmodifiableMap(colors.toMap())
    val icons: List<CompanionThemeIcon> = Collections.unmodifiableList(icons.toList())
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}")) && !id.startsWith("builtin."))
        requireLocalText(name, 512)
        requireLocalText(author, 512)
        requireLocalText(licenseName, 512)
        requireLocalText(licenseText, 16 * 1024, multiline = true)
        require(colors.keys == CompanionColor.entries.toSet() && colors.values.all { it.matches(Regex("#[0-9a-fA-F]{6}")) })
        require(blurLevel in 0..20 && darkMaskPercent in 0..100 && revision >= 0L)
        require(icons.size <= CompanionIconRole.entries.size && icons.map { it.role }.distinct().size == icons.size)
    }
    fun edited(
        name: String = this.name, author: String = this.author, licenseName: String = this.licenseName, licenseText: String = this.licenseText,
        colors: Map<CompanionColor, String> = this.colors, background: CompanionThemeBackground = this.background,
        blurLevel: Int = this.blurLevel, darkMaskPercent: Int = this.darkMaskPercent, icons: List<CompanionThemeIcon> = this.icons,
    ): CompanionThemeDraft = CompanionThemeDraft(id, name, author, licenseName, licenseText, colors, background, blurLevel, darkMaskPercent, icons, Math.addExact(revision, 1L))
    fun validatePalette() {
        val surfaces = listOf(CompanionColor.BACKGROUND, CompanionColor.SURFACE, CompanionColor.SURFACE_RAISED, CompanionColor.SURFACE_PRESSED)
        val texts = listOf(CompanionColor.TEXT_PRIMARY, CompanionColor.TEXT_SECONDARY, CompanionColor.ERROR, CompanionColor.DISABLED_TEXT)
        val controls = listOf(CompanionColor.ACCENT, CompanionColor.FOCUS)
        for (surface in surfaces) {
            if (texts.any { companionContrast(colors.getValue(it), colors.getValue(surface)) < 4.5 } ||
                controls.any { companionContrast(colors.getValue(it), colors.getValue(surface)) < 3.0 }) throw TransferException(TransferIssue.INVALID_INPUT)
        }
    }
    override fun toString(): String = "<theme-editor-draft>"
}

internal fun localUuid(value: String) { require(value.length == 36 && UUID.fromString(value).toString() == value) }
internal fun companionRowIdentity(row: TransferCatalogRow): String = when (row) {
    is TransferCatalogRow.Track -> "track:" + row.trackId
    is TransferCatalogRow.Group -> "group:" + row.entityId
    is TransferCatalogRow.Playlist -> "playlist:" + row.playlistId
    is TransferCatalogRow.Member -> "member:" + row.memberId
}
internal fun requireLocalIdentifier(value: String) { require(value.matches(Regex("[A-Za-z0-9:_-]{1,128}"))) }
internal fun requireLocalField(value: String) {
    require(value.length <= TransferLimits.FIELD_CHARACTERS && value.toByteArray(Charsets.UTF_8).size <= TransferLimits.FIELD_BYTES && '\u0000' !in value)
}
internal fun requireLocalText(value: String, maximum: Int, multiline: Boolean = false) {
    require(value.isNotBlank() && value.length <= maximum && value.toByteArray(Charsets.UTF_8).size <= maximum * 4)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        require(!char.isISOControl() || multiline && char in setOf('\n', '\t'))
        if (Character.isHighSurrogate(char)) {
            require(index + 1 < value.length && Character.isLowSurrogate(value[index + 1]))
            index++
        }
        else require(!Character.isLowSurrogate(char))
        index++
    }
}

internal fun companionContrast(first: String, second: String): Double {
    fun luminance(color: String): Double {
        val value = color.substring(1).toInt(16)
        fun channel(shift: Int): Double {
            val raw = (value ushr shift and 255) / 255.0
            return if (raw <= 0.04045) raw / 12.92 else ((raw + 0.055) / 1.055).pow(2.4)
        }
        return channel(16) * 0.2126 + channel(8) * 0.7152 + channel(0) * 0.0722
    }
    val left = luminance(first)
    val right = luminance(second)
    return (maxOf(left, right) + 0.05) / (minOf(left, right) + 0.05)
}

internal fun localFingerprint(value: String): TransferFingerprint {
    val text = value.trim().replace(" ", "").replace(":", "")
    require(text.matches(Regex("[0-9A-Fa-f]{64}")))
    return TransferFingerprint.fromWire(ByteArray(32) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
}

internal fun selectedDeclaration(name: String, mime: String, size: Long, hash: TransferHash, kind: TransferUploadKind, revision: Long = 0L): TransferUploadDeclaration =
    TransferUploadDeclaration(kind, name, mime, size, hash, revision)

internal fun readCompanionBounded(input: InputStream, maximum: Int, check: () -> Unit = {}): ByteArray {
    val output = ByteArrayOutputStream(minOf(maximum, 4096))
    val buffer = ByteArray(8192)
    while (true) {
        check()
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0 || count > maximum - output.size()) throw TransferException(TransferIssue.QUOTA_EXCEEDED)
        output.write(buffer, 0, count)
    }
    check()
    return output.toByteArray()
}
