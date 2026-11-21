package io.github.onlinemo.meloxwatch.companion.data

import io.github.onlinemo.meloxwatch.data.transfer.TransferDisplayKind
import io.github.onlinemo.meloxwatch.data.transfer.TransferDisplayPatch
import io.github.onlinemo.meloxwatch.data.transfer.TransferException
import io.github.onlinemo.meloxwatch.data.transfer.TransferFingerprint
import io.github.onlinemo.meloxwatch.data.transfer.TransferHash
import io.github.onlinemo.meloxwatch.data.transfer.TransferIssue
import io.github.onlinemo.meloxwatch.data.transfer.TransferLibraryRevision
import io.github.onlinemo.meloxwatch.data.transfer.TransferOperationBinding
import io.github.onlinemo.meloxwatch.data.transfer.TransferOperationKind
import io.github.onlinemo.meloxwatch.data.transfer.TransferPlaylistEdit
import io.github.onlinemo.meloxwatch.data.transfer.TransferReceipt
import io.github.onlinemo.meloxwatch.data.transfer.TransferReceiptKind
import io.github.onlinemo.meloxwatch.data.transfer.TransferUploadDeclaration
import io.github.onlinemo.meloxwatch.data.transfer.TransferUploadKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

data class CompanionLocalState(
    val target: CompanionTarget? = null,
    val upload: CompanionUploadDraft? = null,
    val playlist: CompanionPlaylistDraft? = null,
    val display: CompanionDisplayDraft? = null,
    val theme: CompanionThemeDraft = CompanionThemeDraft(),
    val lastReceipt: TransferReceipt? = null,
)

/**
 * One small checksummed record in noBackupFilesDir. No endpoint, URI, session or bearer is serialized.
 * Methods are blocking and must run on the single document worker. A damaged record is retained;
 * it must never be replaced by a guessed empty state. Atomic rename failure retains the old record.
 */
class CompanionLocalStore(private val directory: File) {
    private val lock = Any()
    private var current: CompanionLocalState? = null
    private var failed = false
    private var failedTemporary: File? = null
    val retirementRequired: Boolean get() = synchronized(lock) { failedTemporary != null }

    fun read(): CompanionLocalState = synchronized(lock) {
        if (failed) throw TransferException(TransferIssue.RECOVERY_REQUIRED)
        current?.let { return@synchronized it }
        try {
            prepareDirectory()
            // The unshipped schema-1 author draft is never interpreted as schema 2.
            if (File(directory, "state.v1").exists()) invalid()
            val path = File(directory, STATE_FILE)
            val state = if (!path.exists()) CompanionLocalState() else {
                regular(path)
                val length = path.length()
                if (length !in 1..CompanionLimits.LOCAL_RECORD_BYTES.toLong()) invalid()
                val bytes = path.inputStream().use { readCompanionBounded(it, CompanionLimits.LOCAL_RECORD_BYTES) }
                if (bytes.size.toLong() != length || bytes.size > CompanionLimits.LOCAL_RECORD_BYTES) invalid()
                decode(bytes)
            }
            val restoredUpload = state.upload?.let {
                if (it.phase in setOf(CompanionUploadPhase.SENDING, CompanionUploadPhase.VERIFYING, CompanionUploadPhase.STOPPING)) it.withResult(CompanionUploadPhase.UNCERTAIN, it.receipt) else it
            }
            state.copy(
                upload = restoredUpload,
                playlist = state.playlist?.let { if (it.phase == CompanionEditPhase.SUBMITTING) it.withPhase(CompanionEditPhase.UNCERTAIN) else it },
                display = state.display?.let { if (it.phase == CompanionEditPhase.SUBMITTING) it.withPhase(CompanionEditPhase.UNCERTAIN) else it },
            ).also { current = it }
        } catch (error: Exception) {
            failed = true
            if (error is TransferException) throw error
            throw TransferException(TransferIssue.RECOVERY_REQUIRED)
        }
    }

    fun update(change: (CompanionLocalState) -> CompanionLocalState): CompanionLocalState = synchronized(lock) {
        if (failedTemporary != null) throw TransferException(TransferIssue.RECOVERY_REQUIRED)
        val next = change(read())
        val data = encode(next)
        if (data.size > CompanionLimits.LOCAL_RECORD_BYTES) throw TransferException(TransferIssue.QUOTA_EXCEEDED)
        val destination = File(directory, STATE_FILE)
        if (destination.exists()) regular(destination)
        val temporary = File(directory, "state." + UUID.randomUUID().toString() + ".tmp")
        var installed = false
        var created = false
        var primary: Exception? = null
        try {
            if (!temporary.createNewFile()) throw TransferException(TransferIssue.STORAGE_UNAVAILABLE)
            created = true
            regular(temporary)
            FileOutputStream(temporary).use {
                stream -> stream.write(data)
                stream.fd.sync()
            }
            // The private record and its temporary share a filesystem; there is no destructive fallback.
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            installed = true
            current = next
            next
        } catch (error: Exception) {
            val mapped = if (error is TransferException) error else TransferException(TransferIssue.STORAGE_UNAVAILABLE)
            primary = mapped
            throw mapped
        }
        finally {
            if (!installed && created && temporary.exists() && !temporary.delete()) {
                failedTemporary = temporary
                val cleanup = TransferException(TransferIssue.RECOVERY_REQUIRED)
                primary?.addSuppressed(cleanup) ?: throw cleanup
            }
        }
    }

    fun audioFile(id: String, partial: Boolean = false): File = owned("audio", id, if (partial) ".partial" else ".audio")
    fun iconFile(id: String): File = owned("icons", id, ".png")
    fun packageFile(id: String, partial: Boolean = false): File = owned("packages", id, if (partial) ".partial" else ".zip")

    fun verifyOwnedFile(file: File) {
        prepareDirectory()
        require(file.parentFile?.parentFile?.canonicalFile == directory.canonicalFile)
        regular(file)
    }

    fun audioBytes(): Long {
        return measured(entries("audio", setOf("audio", "partial"), CompanionLimits.STAGED_AUDIO_BYTES), CompanionLimits.STAGED_AUDIO_BYTES)
    }

    fun themeBytes(): Long = measured(
        entries("icons", setOf("png"), CompanionLimits.ICON_BYTES.toLong()) + entries("packages", setOf("zip", "partial"), 16L * 1024 * 1024),
        CompanionLimits.STAGED_THEME_BYTES,
    )

    /** A private orphan is retired only after validating the entire bounded inventory and its references. */
    fun retireUnreferencedCopies(check: () -> Unit = {}) = synchronized(lock) {
        val state = read()
        val audio = entries("audio", setOf("audio", "partial"), CompanionLimits.STAGED_AUDIO_BYTES)
        val icons = entries("icons", setOf("png"), CompanionLimits.ICON_BYTES.toLong())
        val packages = entries("packages", setOf("zip", "partial"), 16L * 1024 * 1024)
        measured(audio, CompanionLimits.STAGED_AUDIO_BYTES)
        measured(icons + packages, CompanionLimits.STAGED_THEME_BYTES)
        val retained = HashSet<File>()
        state.upload?.let { draft ->
            val file = if (draft.declaration.kind == TransferUploadKind.AUDIO) audioFile(draft.fileId) else packageFile(draft.fileId)
            if (draft.phase != CompanionUploadPhase.RETIRING_COPY) {
                regular(file)
                if (file.length() != draft.declaration.declaredBytes) invalid()
            }
            retained += file
        }
        state.theme.icons.forEach { icon ->
            val file = iconFile(icon.fileId)
            regular(file)
            if (file.length() != icon.byteCount) invalid()
            retained += file
        }
        val temporary = ArrayList<File>()
        Files.newDirectoryStream(directory.toPath()).use { stream ->
            var nodes = 0
            for (entry in stream) {
                check()
                if (++nodes > CompanionLimits.DRAFTS * 2 + 4) invalid()
                val name = entry.fileName.toString()
                if (name in setOf("audio", "icons", "packages", STATE_FILE)) continue
                if (!name.matches(Regex("state\\.[0-9a-f-]{36}\\.tmp"))) invalid()
                localUuid(name.removePrefix("state.").removeSuffix(".tmp"))
                regular(entry.toFile())
                if (entry.toFile().length() > CompanionLimits.LOCAL_RECORD_BYTES) invalid()
                temporary += entry.toFile()
            }
        }
        (audio + icons + packages + temporary).filter { it !in retained }.forEach { file ->
            check()
            regular(file)
            if (!file.delete()) throw TransferException(TransferIssue.RECOVERY_REQUIRED)
            if (failedTemporary == file) failedTemporary = null
        }
    }

    fun retryTemporaryRetirement() = synchronized(lock) {
        val file = failedTemporary ?: return@synchronized
        if (file.exists()) {
            regular(file)
            if (!file.delete()) throw TransferException(TransferIssue.RECOVERY_REQUIRED)
        }
        failedTemporary = null
    }

    /** Retry the same bytes after storage recovery; invalid records remain intact and fail again. */
    fun retryRead(): CompanionLocalState = synchronized(lock) {
        if (failedTemporary != null) throw TransferException(TransferIssue.RECOVERY_REQUIRED)
        failed = false
        read()
    }

    private fun entries(folder: String, suffixes: Set<String>, maximum: Long): List<File> {
        val result = ArrayList<File>()
        Files.newDirectoryStream(ownedDirectory(folder).toPath()).use { stream ->
            for (entry in stream) {
                if (result.size >= CompanionLimits.DRAFTS * 2) invalid()
                val name = entry.fileName.toString()
                if (name.substringAfterLast('.', "") !in suffixes) invalid()
                localUuid(name.substringBefore('.'))
                val file = entry.toFile()
                regular(file)
                if (file.length() !in 0..maximum) invalid()
                result += file
            }
        }
        return result
    }

    private fun measured(files: List<File>, maximum: Long): Long {
        var total = 0L
        files.forEach { file ->
            val length = file.length()
            if (length < 0L || length > maximum - total) throw TransferException(TransferIssue.QUOTA_EXCEEDED)
            total += length
        }
        return total
    }

    /** A retry of an uncertain operation retains exactly the original operation and payload. */
    fun recordUpload(draft: CompanionUploadDraft): CompanionLocalState = update { old ->
        if (old.upload != null && old.upload.operationId != draft.operationId) throw TransferException(TransferIssue.BUSY)
        old.upload?.let { prepared ->
            if (!prepared.samePreparedPayload(draft)) throw TransferException(TransferIssue.CONFLICT)
            if (prepared.target != null && prepared.target != draft.target) throw TransferException(TransferIssue.WRONG_TARGET)
        }
        old.copy(upload = draft)
    }

    private fun owned(folder: String, id: String, suffix: String): File {
        localUuid(id)
        return File(ownedDirectory(folder), id + suffix)
    }
    private fun ownedDirectory(name: String): File {
        prepareDirectory()
        val result = File(directory, name)
        if (!result.exists() && !result.mkdir()) throw TransferException(TransferIssue.STORAGE_UNAVAILABLE)
        if (!Files.isDirectory(result.toPath(), LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(result.toPath())) invalid()
        return result
    }
    private fun prepareDirectory() {
        if (!directory.exists() && !directory.mkdirs()) throw TransferException(TransferIssue.STORAGE_UNAVAILABLE)
        if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory.toPath())) invalid()
    }
    private fun regular(file: File) {
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file.toPath())) invalid()
    }

    private fun encode(state: CompanionLocalState): ByteArray {
        val writer = Writer()
        writer.optional(state.target) {
            fingerprint(it.fingerprint)
            text(it.label, 80)
        }
        writer.optional(state.upload) {
            text(it.operationId, 36)
            optional(it.target) {
                target -> fingerprint(target)
            }
            text(it.fileId, 36)
            declaration(it.declaration)
            number(it.phase.ordinal)
            optional(it.receipt) {
                receipt(it)
            }
        }
        writer.optional(state.playlist) {
            text(it.operationId, 36)
            fingerprint(it.target)
            text(it.playlistId, 128)
            text(it.playlistName, 4096)
            long(it.expectedRevision)
            optional(it.libraryRevision) {
                libraryRevision(it)
            }
            edit(it.edit)
            number(it.phase.ordinal)
            optional(it.receipt) {
                receipt(it)
            }
        }
        writer.optional(state.display) {
            text(it.operationId, 36)
            fingerprint(it.target)
            number(it.kind.ordinal)
            text(it.entityId, 128)
            long(it.expectedRevision)
            libraryRevision(it.libraryRevision)
            text(it.currentName, 4096)
            long(it.currentTrackCount)
            patch(it.patch)
            optional(it.previewId) {
                text(it, 36)
            }
            number(it.phase.ordinal)
            optional(it.receipt) {
                receipt(it)
            }
        }
        writer.theme(state.theme)
        writer.optional(state.lastReceipt) { receipt(it) }
        val payload = writer.finish()
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { stream ->
                stream.writeInt(MAGIC)
                stream.writeInt(VERSION)
                stream.writeInt(payload.size)
                stream.write(payload)
                stream.write(MessageDigest.getInstance("SHA-256").digest(payload))
            }
        }.toByteArray()
    }

    private fun decode(bytes: ByteArray): CompanionLocalState {
        val stream = DataInputStream(ByteArrayInputStream(bytes))
        if (stream.readInt() != MAGIC || stream.readInt() != VERSION) invalid()
        val length = stream.readInt()
        if (length < 0 || length > CompanionLimits.LOCAL_RECORD_BYTES - 44 || stream.available() != length + 32) invalid()
        val payload = ByteArray(length).also(stream::readFully)
        val hash = ByteArray(32).also(stream::readFully)
        if (!MessageDigest.isEqual(hash, MessageDigest.getInstance("SHA-256").digest(payload))) invalid()
        val reader = Reader(payload)
        val target = reader.optional { CompanionTarget(fingerprint(), text(80)) }
        val upload = reader.optional { CompanionUploadDraft(text(36), optional { fingerprint() }, text(36), declaration(), enum<CompanionUploadPhase>(), optional { receipt() }) }
        val playlist = reader.optional { CompanionPlaylistDraft(text(36), fingerprint(), text(128), text(4096), long(), optional { libraryRevision() }, edit(), enum<CompanionEditPhase>(), optional { receipt() }) }
        val display = reader.optional { CompanionDisplayDraft(text(36), fingerprint(), enum<TransferDisplayKind>(), text(128), long(), libraryRevision(), text(4096), long(), patch(), optional { text(36) }, enum<CompanionEditPhase>(), optional { receipt() }) }
        val theme = reader.theme()
        val receipt = reader.optional { receipt() }
        if (!reader.ended()) invalid()
        return CompanionLocalState(target, upload, playlist, display, theme, receipt)
    }

    private class Writer {
        private val bytes = ByteArrayOutputStream()
        private val output = DataOutputStream(bytes)
        fun finish(): ByteArray {
            output.flush()
            return bytes.toByteArray()
        }
        fun number(value: Int) { output.writeInt(value) }
        fun long(value: Long) { output.writeLong(value) }
        fun fingerprint(value: TransferFingerprint) { output.write(value.wireBytes()) }
        fun hash(value: TransferHash) { output.write(value.wireBytes()) }
        fun libraryRevision(value: TransferLibraryRevision) {
            long(value.catalogRevision)
            long(value.rulesRevision)
        }
        fun text(value: String, maximum: Int) {
            require(value.length <= maximum)
            val encoded = value.toByteArray(Charsets.UTF_8)
            require(encoded.size <= maximum * 4)
            number(encoded.size)
            output.write(encoded)
            if (bytes.size() > CompanionLimits.LOCAL_RECORD_BYTES) throw TransferException(TransferIssue.QUOTA_EXCEEDED)
        }
        fun <T> optional(value: T?, action: Writer.(T) -> Unit) {
            output.writeBoolean(value != null)
            value?.let {
                action(it)
            }
        }
        fun identifiers(ids: List<String>) {
            require(ids.size <= 100)
            number(ids.size)
            ids.forEach {
                text(it, 128)
            }
        }
        fun declaration(value: TransferUploadDeclaration) {
            number(value.kind.ordinal)
            text(value.displayName, 510)
            text(value.mediaType, 128)
            long(value.declaredBytes ?: -1L)
            hash(value.sha256)
            long(value.expectedRevision)
        }
        fun edit(value: TransferPlaylistEdit) { when (value) {
                is TransferPlaylistEdit.Create -> {
                    number(1)
                    text(value.name, 4096)
                }
                is TransferPlaylistEdit.Rename -> {
                    number(2)
                    text(value.name, 4096)
                }
                is TransferPlaylistEdit.Delete -> {
                    number(3)
                    output.writeBoolean(value.confirmed)
                }
                is TransferPlaylistEdit.Add -> {
                    number(4)
                    identifiers(value.trackIds)
                }
                is TransferPlaylistEdit.Remove -> {
                    number(5)
                    text(value.memberId, 128)
                    output.writeBoolean(value.confirmed)
                }
                is TransferPlaylistEdit.Move -> {
                    number(6)
                    text(value.memberId, 128)
                    long(value.destinationOrdinal)
                }
        } }
        fun patch(value: TransferDisplayPatch) { when (value) {
                is TransferDisplayPatch.Rename -> {
                    number(1)
                    text(value.displayName, 4096)
                }
                is TransferDisplayPatch.Add -> {
                    number(2)
                    identifiers(value.trackIds)
                }
                is TransferDisplayPatch.Remove -> {
                    number(3)
                    identifiers(value.trackIds)
                }
        } }
        fun receipt(value: TransferReceipt) {
            val binding = value.binding
            text(binding.operationId, 36)
            number(binding.kind.ordinal)
            fingerprint(binding.target)
            fingerprint(binding.peer)
            hash(binding.payloadHash)
            number(value.result.ordinal)
            long(value.revision)
            optional(value.resultId) {
                text(it, 128)
            }
            long(value.actualBytes ?: -1L)
        }
        fun theme(value: CompanionThemeDraft) {
            text(value.id, 64)
            text(value.name, 512)
            text(value.author, 512)
            text(value.licenseName, 512)
            text(value.licenseText, 16 * 1024)
            CompanionColor.entries.forEach { text(value.colors.getValue(it), 7) }
            number(value.background.ordinal)
            number(value.blurLevel)
            number(value.darkMaskPercent)
            long(value.revision)
            number(value.icons.size)
            value.icons.forEach {
                number(it.role.ordinal)
                text(it.fileId, 36)
                hash(it.sha256)
                long(it.byteCount)
                text(it.licenseName, 512)
                text(it.licenseText, 16 * 1024)
            }
        }
    }

    private class Reader(bytes: ByteArray) {
        private val input = DataInputStream(ByteArrayInputStream(bytes))
        fun ended(): Boolean = input.available() == 0
        fun number(): Int = input.readInt()
        fun long(): Long = input.readLong()
        fun fingerprint(): TransferFingerprint = TransferFingerprint.fromWire(ByteArray(32).also(input::readFully))
        fun hash(): TransferHash = TransferHash.fromWire(ByteArray(32).also(input::readFully))
        fun libraryRevision(): TransferLibraryRevision = TransferLibraryRevision(long(), long())
        fun text(maximum: Int): String {
            val count = number()
            if (count !in 0..maximum * 4 || count > input.available()) invalid()
            val data = ByteArray(count).also(input::readFully)
            val value = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()
            if (value.length > maximum) invalid()
            return value
        }
        fun boolean(): Boolean = when (input.readUnsignedByte()) {
            0 -> false
            1 -> true
            else -> invalid()
        }
        fun <T> optional(action: Reader.() -> T): T? = if (boolean()) action() else null
        inline fun <reified T : Enum<T>> enum(): T = enumValues<T>().getOrNull(number()) ?: invalid()
        fun identifiers(): List<String> {
            val count = number()
            if (count !in 0..100) invalid()
            return List(count) {
                text(128).also(::requireLocalIdentifier)
            }
        }
        fun declaration(): TransferUploadDeclaration {
            val kind = enum<TransferUploadKind>()
            val name = text(510)
            val mime = text(128)
            val size = long()
            return TransferUploadDeclaration(kind, name, mime, if (size == -1L) null else size, hash(), long())
        }
        fun edit(): TransferPlaylistEdit = when (number()) {
            1 -> TransferPlaylistEdit.Create(text(4096))
            2 -> TransferPlaylistEdit.Rename(text(4096))
            3 -> TransferPlaylistEdit.Delete(boolean())
            4 -> TransferPlaylistEdit.Add(identifiers())
            5 -> TransferPlaylistEdit.Remove(text(128), boolean())
            6 -> TransferPlaylistEdit.Move(text(128), long())
            else -> invalid()
        }
        fun patch(): TransferDisplayPatch = when (number()) {
            1 -> TransferDisplayPatch.Rename(text(4096))
            2 -> TransferDisplayPatch.Add(identifiers())
            3 -> TransferDisplayPatch.Remove(identifiers())
            else -> invalid()
        }
        fun receipt(): TransferReceipt {
            val binding = TransferOperationBinding(text(36), enum<TransferOperationKind>(), fingerprint(), fingerprint(), hash())
            val kind = enum<TransferReceiptKind>()
            val revision = long()
            val resultId = optional {
                text(128)
            }
            val bytes = long()
            return TransferReceipt(binding, kind, revision, resultId, if (bytes == -1L) null else bytes)
        }
        fun theme(): CompanionThemeDraft {
            val id = text(64)
            val name = text(512)
            val author = text(512)
            val license = text(512)
            val notice = text(16 * 1024)
            val colors = CompanionColor.entries.associateWith { text(7) }
            val background = enum<CompanionThemeBackground>()
            val blur = number()
            val mask = number()
            val revision = long()
            val count = number()
            if (count !in 0..CompanionIconRole.entries.size) invalid()
            val icons = List(count) { CompanionThemeIcon(enum<CompanionIconRole>(), text(36), hash(), long(), text(512), text(16 * 1024)) }
            return CompanionThemeDraft(id, name, author, license, notice, colors, background, blur, mask, icons, revision)
        }
    }

    override fun toString(): String = "<local-companion-store>"
    private companion object {
        const val MAGIC = 0x4d584350
        const val VERSION = 2
        const val STATE_FILE = "state.v2"
        fun invalid(): Nothing = throw TransferException(TransferIssue.RECOVERY_REQUIRED)
    }
}
