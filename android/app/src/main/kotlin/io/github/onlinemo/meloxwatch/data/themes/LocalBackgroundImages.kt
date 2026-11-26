package io.github.onlinemo.meloxwatch.data.themes

import io.github.onlinemo.meloxwatch.data.storage.StorageArea
import io.github.onlinemo.meloxwatch.data.storage.StorageDirectorySpec

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.CancellationSignal
import android.util.AtomicFile
import androidx.exifinterface.media.ExifInterface
import io.github.onlinemo.meloxwatch.data.artwork.ArtworkException
import io.github.onlinemo.meloxwatch.data.artwork.ArtworkIssue
import io.github.onlinemo.meloxwatch.data.artwork.ArtworkLimits
import io.github.onlinemo.meloxwatch.data.artwork.BoundedArtworkDecoder
import io.github.onlinemo.meloxwatch.data.local.LocalInputLimits
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.backup.BackupMutationBarrier
import io.github.onlinemo.meloxwatch.data.backup.BackupException
import io.github.onlinemo.meloxwatch.data.backup.BackupIssue
import io.github.onlinemo.meloxwatch.data.backup.BackupResourceWriter
import io.github.onlinemo.meloxwatch.data.backup.publishForBackup
import io.github.onlinemo.meloxwatch.data.backup.publishFailureForBackup
import io.github.onlinemo.meloxwatch.data.backup.withBackupMutation
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One saved custom copy and one preview/retry. Originals are opened read-only and never changed. */
class LocalBackgroundImages private constructor(
    private val directoryProvider: () -> File,
    private val io: LocalProviderIo,
    private val bitmaps: ThemeBitmapOwner,
    private val opener: ThemePackageOpener,
    private val availableBytes: (File) -> Long,
    private val backupBarrier: BackupMutationBarrier?,
) : PlaybackBackgroundStore {
    constructor(context: Context, io: LocalProviderIo, bitmaps: ThemeBitmapOwner, backupBarrier: BackupMutationBarrier? = null) : this(
        directoryProvider(context), io, bitmaps, AndroidThemePackageSource(context.applicationContext.contentResolver), File::getUsableSpace, backupBarrier,
    )

    internal constructor(
        directory: File,
        io: LocalProviderIo,
        bitmaps: ThemeBitmapOwner,
        opener: ThemePackageOpener,
        availableBytes: (File) -> Long = File::getUsableSpace,
        backupBarrier: BackupMutationBarrier? = null,
    ) : this({ directory }, io, bitmaps, opener, availableBytes, backupBarrier)

    private val occupied = AtomicBoolean(false)

    internal fun storageDirectorySpecOnWorker(): StorageDirectorySpec {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
        return StorageDirectorySpec(directoryProvider().absoluteFile, StorageArea.BACKGROUNDS, 1,
            PlaybackBackgroundLimits.DIRECTORY_ENTRIES.toLong(), classify = { parts, directory ->
                val name = parts.singleOrNull()
                when {
                    directory || name == null -> null
                    name in setOf("owner.lock", "settings.bin") -> StorageArea.BACKGROUNDS
                    name in setOf("settings.bin.bak", "settings.bin.new") -> StorageArea.RECOVERY
                    name.endsWith(".image") && isThemeOperationId(name.removeSuffix(".image")) -> StorageArea.BACKGROUNDS
                    name.endsWith(".part") && isThemeOperationId(name.removeSuffix(".part")) -> StorageArea.RECOVERY
                    else -> null
                }
            }, lockFile = "owner.lock", mutableFile = { it.last().endsWith(".part") || it.last().endsWith(".bak") || it.last().endsWith(".new") },
            requireNonEmpty = { it.last().endsWith(".image") }, sentinels = listOf("settings.bin", "settings.bin.bak", "settings.bin.new"))
    }
    private val previewLock = Any()
    private var held: PreviewRecord? = null
    private var backupRetained = emptyMap<String, BackgroundImageRef>()
    private val mutableState = MutableStateFlow(PlaybackBackgroundSnapshot(null))
    val state: StateFlow<PlaybackBackgroundSnapshot> = mutableState.asStateFlow()
    private val mutablePreview = MutableStateFlow<CustomBackgroundPreview?>(null)
    val preview: StateFlow<CustomBackgroundPreview?> = mutablePreview.asStateFlow()

    internal suspend fun readForBackup(): PlaybackBackgroundSettings {
        requireBackupOwner()
        return operation { directory, signal ->
            synchronized(previewLock) {
                val preview = held
                if (preview != null && !preview.retired && backupRetained[preview.preview.image.id] != preview.preview.image) {
                    backgroundFail(PlaybackBackgroundIssue.BUSY)
                }
            }
            val settings = readSettings(directory, signal)
            prune(directory, settings, signal)
            publish(PlaybackBackgroundSnapshot(settings))
            settings
        }
    }

    internal suspend fun retainForBackup(images: List<BackgroundImageRef>) {
        requireBackupOwner()
        if (images.size > 2 || images.map { it.id }.distinct().size != images.size) backgroundFail(PlaybackBackgroundIssue.INVALID_INPUT)
        operation { directory, signal ->
            for (image in images) verifiedImage(directory, image, signal)
            synchronized(previewLock) { backupRetained = images.associateBy { it.id } }
        }
    }

    internal suspend fun exportForBackup(image: BackgroundImageRef, output: BackupResourceWriter) {
        requireBackupOwner()
        operation { directory, signal ->
            verifiedImage(directory, image, signal)
            output.copyVerified(sourceFile(directory, image.id, ".image"), image.byteCount, image.sha256, signal::throwIfCanceled)
        }
    }

    internal suspend fun validateForBackup(file: File, image: BackgroundImageRef) {
        requireBackupOwner()
        operation { _, signal ->
            val bytes = readImageBytes(file, image.byteCount, signal)
            if (ThemeStorageRecords.digest(bytes) != image.sha256) backgroundFail(PlaybackBackgroundIssue.INTEGRITY_FAILED)
            val info = inspect(bytes, signal)
            if (info.mediaType != image.mediaType || info.width != image.width || info.height != image.height || info.orientation != image.orientation) {
                backgroundFail(PlaybackBackgroundIssue.INTEGRITY_FAILED)
            }
            val reservation = bitmaps.reserve(PlaybackBackgroundLimits.DECODE_BYTES)
            var validation: Bitmap? = null
            try { validation = BoundedArtworkDecoder.decode(bytes, ThemeLimits.VALIDATION_IMAGE_EDGE, signal); signal.throwIfCanceled() }
            finally { validation?.recycle(); reservation.close() }
        }
    }

    /** Rollback may select an exact journal-retained original, without inventing a UI preview. */
    internal suspend fun compareAndSetForBackup(expectedRevision: Long, replacement: PlaybackBackgroundOptions): PlaybackBackgroundSaveResult {
        requireBackupOwner()
        return operation { directory, signal ->
            val actual = readSettings(directory, signal)
            if (actual.revision != expectedRevision) return@operation PlaybackBackgroundSaveResult.Conflict(
                publish(PlaybackBackgroundSnapshot(actual, PlaybackBackgroundIssue.SETTINGS_CONFLICT)),
            )
            if (expectedRevision < 0L || expectedRevision == Long.MAX_VALUE) backgroundFail(PlaybackBackgroundIssue.SETTINGS_CONFLICT)
            replacement.customImage?.let { image ->
                val retained = synchronized(previewLock) { backupRetained[image.id] }
                if (retained != image && image != actual.options.customImage) backgroundFail(PlaybackBackgroundIssue.PREVIEW_NOT_FOUND)
                verifiedImage(directory, image, signal)
            }
            if (actual.options == replacement) return@operation PlaybackBackgroundSaveResult.Saved(publish(PlaybackBackgroundSnapshot(actual)))
            val next = PlaybackBackgroundSettings(actual.revision + 1L, replacement)
            signal.throwIfCanceled()
            writeSettings(directory, next, signal)
            publish(PlaybackBackgroundSnapshot(next))
            synchronized(previewLock) {
                held?.takeIf { it.preview.image == replacement.customImage }?.let { held = null; publishPreview(null) }
            }
            prune(directory, next, signal)
            PlaybackBackgroundSaveResult.Saved(publish(PlaybackBackgroundSnapshot(next)))
        }
    }

    internal suspend fun releaseBackupRetention() {
        requireBackupOwner()
        operation { directory, signal ->
            val settings = readSettings(directory, signal)
            synchronized(previewLock) { backupRetained = emptyMap() }
            prune(directory, settings, signal)
        }
    }

    private suspend fun requireBackupOwner() {
        val barrier = backupBarrier ?: throw BackupException(BackupIssue.INVALID_INPUT)
        barrier.requireExclusiveOwner()
    }

    override suspend fun read(): PlaybackBackgroundSnapshot = try {
        operation { directory, signal ->
            val settings = readSettings(directory, signal)
            val issue = try { prune(directory, settings, signal); null }
            catch (error: PlaybackBackgroundException) { error.issue }
            publish(PlaybackBackgroundSnapshot(settings, issue))
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: PlaybackBackgroundException) {
        if (error.issue == PlaybackBackgroundIssue.BUSY) throw error
        publishFailure(PlaybackBackgroundSnapshot(null, error.issue))
    }

    override suspend fun prepareImage(request: CustomBackgroundImportRequest): CustomBackgroundPreview = operation(
        abandoned = { preview -> retire(preview.token) },
    ) { directory, signal ->
        validate(request)
        val settings = readSettings(directory, signal)
        prune(directory, settings, signal)
        val digest = backgroundKey(request.operationId, request.uri, request.displayName)
        synchronized(previewLock) {
            held?.takeIf { !it.retired }?.let { record ->
                if (record.requestDigest == digest) return@operation record.preview
                backgroundFail(PlaybackBackgroundIssue.BUSY)
            }
        }
        if (settings.options.customImage?.id == request.operationId) backgroundFail(PlaybackBackgroundIssue.OPERATION_CONFLICT)
        val part = sourceFile(directory, request.operationId, ".part")
        val installed = sourceFile(directory, request.operationId, ".image")
        if (Files.exists(part.toPath(), LinkOption.NOFOLLOW_LINKS) || Files.exists(installed.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            backgroundFail(PlaybackBackgroundIssue.OPERATION_CONFLICT)
        }
        var failure: Throwable? = null
        var ready = false
        try {
            val copied = copy(request, part, sourceBytes(directory), signal)
            val bytes = readImageBytes(part, copied.second, signal)
            val info = inspect(bytes, signal)
            val reservation = bitmaps.reserve(PlaybackBackgroundLimits.DECODE_BYTES)
            var validation: Bitmap? = null
            try {
                validation = BoundedArtworkDecoder.decode(bytes, ThemeLimits.VALIDATION_IMAGE_EDGE, signal)
                signal.throwIfCanceled()
            } finally {
                validation?.recycle()
                reservation.close()
            }
            val image = BackgroundImageRef(request.operationId, copied.first, copied.second, info.mediaType,
                info.width, info.height, info.orientation)
            Files.move(part.toPath(), installed.toPath(), StandardCopyOption.ATOMIC_MOVE)
            signal.throwIfCanceled()
            val result = CustomBackgroundPreview(UUID.randomUUID().toString(), image, request.displayName, settings.revision)
            synchronized(previewLock) { held = PreviewRecord(result, digest); publishPreview(result) }
            ready = true
            result
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            if (!ready) try {
                deleteSource(part)
                deleteSource(installed)
            } catch (closing: Throwable) {
                if (failure == null) throw closing
                if (failure !== closing) failure.addSuppressed(closing)
                publish(PlaybackBackgroundSnapshot(settings, PlaybackBackgroundIssue.CLEANUP_REQUIRED))
            }
        }
    }

    override fun retirePreview(token: String) { retire(token) }

    override suspend fun discardPreview(token: String) = operation { directory, signal ->
        val settings = readSettings(directory, signal)
        val record = record(token, allowRetired = true)
        retire(token)
        if (settings.options.customImage != record.preview.image && synchronized(previewLock) { record.preview.image.id !in backupRetained }) {
            deleteSource(sourceFile(directory, record.preview.image.id, ".image"))
        }
        synchronized(previewLock) { if (held === record) held = null }
        Unit
    }

    override suspend fun compareAndSet(
        expectedRevision: Long,
        replacement: PlaybackBackgroundOptions,
        previewToken: String?,
    ): PlaybackBackgroundSaveResult = operation { directory, signal ->
        val actual = readSettings(directory, signal)
        if (actual.revision != expectedRevision) {
            return@operation PlaybackBackgroundSaveResult.Conflict(publish(PlaybackBackgroundSnapshot(actual, PlaybackBackgroundIssue.SETTINGS_CONFLICT)))
        }
        if (expectedRevision < 0 || expectedRevision == Long.MAX_VALUE) backgroundFail(PlaybackBackgroundIssue.SETTINGS_CONFLICT)
        val image = replacement.customImage
        val candidate = if (image != null && image != actual.options.customImage) {
            val record = record(previewToken ?: backgroundFail(PlaybackBackgroundIssue.PREVIEW_NOT_FOUND))
            if (record.preview.image != image) backgroundFail(PlaybackBackgroundIssue.PREVIEW_NOT_FOUND)
            verifiedImage(directory, image, signal)
            record
        } else null
        val next = PlaybackBackgroundSettings(actual.revision + 1, replacement)
        signal.throwIfCanceled()
        writeSettings(directory, next, signal)
        // The accepted record is observable even if cancellation wins before result delivery.
        publish(PlaybackBackgroundSnapshot(next))
        candidate?.let { record -> synchronized(previewLock) {
            if (held === record) { held = null; publishPreview(null) }
        } }
        val issue = try { prune(directory, next, signal); null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: PlaybackBackgroundException) { error.issue }
        PlaybackBackgroundSaveResult.Saved(publish(PlaybackBackgroundSnapshot(next, issue)))
    }

    suspend fun loadImage(key: PlaybackBackgroundImageKey.Custom, targetPx: Int): ThemeBitmapLease = operation(
        abandoned = ThemeBitmapLease::close,
    ) { directory, signal ->
        if (targetPx !in 1..PlaybackBackgroundLimits.DERIVED_EDGE) backgroundFail(PlaybackBackgroundIssue.INVALID_INPUT)
        val settings = readSettings(directory, signal)
        if (key.previewToken != null) {
            if (record(key.previewToken).preview.image != key.image) backgroundFail(PlaybackBackgroundIssue.PREVIEW_NOT_FOUND)
        } else if (settings.revision != key.settingsRevision || settings.options.customImage != key.image) {
            backgroundFail(PlaybackBackgroundIssue.SETTINGS_CONFLICT)
        }
        val bytes = verifiedImage(directory, key.image, signal)
        val cacheKey = backgroundKey("background-source-v1", key.image.id, key.image.sha256, targetPx.toString())
        bitmaps.acquire(cacheKey)?.let { return@operation it }
        val reservation = bitmaps.reserve(PlaybackBackgroundLimits.DECODE_BYTES)
        var decoded: Bitmap? = null
        try {
            val original = BoundedArtworkDecoder.decode(bytes, targetPx, signal)
            decoded = original
            if (original.allocationByteCount.toLong() > PlaybackBackgroundLimits.DERIVED_PIXELS * 4L) {
                backgroundFail(PlaybackBackgroundIssue.IMAGE_TOO_LARGE)
            }
            if (key.image.orientation != 1) {
                val transformed = orient(original, key.image.orientation)
                if (transformed !== original) { original.recycle(); decoded = transformed }
            }
            signal.throwIfCanceled()
            val result = bitmaps.publish(cacheKey, checkNotNull(decoded), reservation)
            decoded = null
            result
        } finally {
            decoded?.recycle()
            reservation.close()
        }
    }

    private fun copy(request: CustomBackgroundImportRequest, destination: File, owned: Long, signal: CancellationSignal): Pair<String, Long> {
        val directory = destination.parentFile ?: backgroundFail(PlaybackBackgroundIssue.STORAGE_UNAVAILABLE)
        val opened = sourceRead { opener.open(request.uri, signal) }
        var failure: Throwable? = null
        var written = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            if (opened.declaredBytes != null && opened.declaredBytes !in 1..ArtworkLimits.INPUT_BYTES.toLong()) {
                backgroundFail(if (opened.declaredBytes > ArtworkLimits.INPUT_BYTES) PlaybackBackgroundIssue.IMAGE_TOO_LARGE else PlaybackBackgroundIssue.INVALID_INPUT)
            }
            Files.newOutputStream(destination.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS).use { output ->
                val bytes = ByteArray(ThemeLimits.IO_BYTES)
                while (true) {
                    signal.throwIfCanceled()
                    val count = sourceRead { opened.input.read(bytes) }
                    signal.throwIfCanceled()
                    if (count < 0) break
                    if (count == 0) backgroundFail(PlaybackBackgroundIssue.SOURCE_UNAVAILABLE)
                    if (count > ArtworkLimits.INPUT_BYTES - written) backgroundFail(PlaybackBackgroundIssue.IMAGE_TOO_LARGE)
                    requireRoom(owned, written, count.toLong(), directory)
                    output.write(bytes, 0, count)
                    digest.update(bytes, 0, count)
                    written += count
                }
            }
            if (written == 0L || opened.declaredBytes != null && written != opened.declaredBytes) {
                backgroundFail(PlaybackBackgroundIssue.INTEGRITY_FAILED)
            }
            FileChannel.open(destination.toPath(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try { opened.close() }
            catch (closing: Throwable) {
                if (failure == null) throw PlaybackBackgroundException(
                    if (closing is OutOfMemoryError) PlaybackBackgroundIssue.LOW_MEMORY else PlaybackBackgroundIssue.INPUT_CLOSE_FAILED, closing,
                )
                if (failure !== closing) failure.addSuppressed(closing)
            }
        }
        return themeHex(digest.digest()) to written
    }

    private fun verifiedImage(directory: File, image: BackgroundImageRef, signal: CancellationSignal): ByteArray {
        val file = sourceFile(directory, image.id, ".image")
        val bytes = readImageBytes(file, image.byteCount, signal)
        if (ThemeStorageRecords.digest(bytes) != image.sha256) backgroundFail(PlaybackBackgroundIssue.INTEGRITY_FAILED)
        val info = inspect(bytes, signal)
        if (info.mediaType != image.mediaType || info.width != image.width || info.height != image.height || info.orientation != image.orientation) {
            backgroundFail(PlaybackBackgroundIssue.INTEGRITY_FAILED)
        }
        return bytes
    }

    private fun readImageBytes(file: File, expected: Long, signal: CancellationSignal): ByteArray {
        val bytes = readBoundedThemeFile(file, ArtworkLimits.INPUT_BYTES, ThemeIssue.INTEGRITY_FAILED, signal::throwIfCanceled)
        if (bytes.size.toLong() != expected) backgroundFail(PlaybackBackgroundIssue.INTEGRITY_FAILED)
        return bytes
    }

    private fun inspect(bytes: ByteArray, signal: CancellationSignal): ImageInfo {
        signal.throwIfCanceled()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val mediaType = bounds.outMimeType ?: backgroundFail(PlaybackBackgroundIssue.INVALID_IMAGE)
        if (mediaType !in setOf("image/png", "image/jpeg", "image/webp") || bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            backgroundFail(PlaybackBackgroundIssue.INVALID_IMAGE)
        }
        if (bounds.outWidth.toLong() * bounds.outHeight > ArtworkLimits.INPUT_PIXELS) backgroundFail(PlaybackBackgroundIssue.IMAGE_TOO_LARGE)
        ThemeImageHeaders.verify(bytes, mediaType, signal::throwIfCanceled)
        val orientation = if (mediaType == "image/jpeg") {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } else 1
        if (orientation !in 1..8) backgroundFail(PlaybackBackgroundIssue.INVALID_IMAGE)
        signal.throwIfCanceled()
        return ImageInfo(mediaType, bounds.outWidth, bounds.outHeight, orientation)
    }

    private fun orient(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            2 -> matrix.setScale(-1f, 1f)
            3 -> matrix.setRotate(180f)
            4 -> { matrix.setRotate(180f); matrix.postScale(-1f, 1f) }
            5 -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            6 -> matrix.setRotate(90f)
            7 -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            8 -> matrix.setRotate(-90f)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun readSettings(directory: File, signal: CancellationSignal): PlaybackBackgroundSettings {
        val base = File(directory, "settings.bin")
        validateSettingsFiles(directory)
        if (!base.exists() && !File(directory, "settings.bin.bak").exists()) return PlaybackBackgroundSettings()
        AtomicFile(base).openRead().use { input ->
            val output = ByteArrayOutputStream()
            val bytes = ByteArray(1024)
            while (true) {
                signal.throwIfCanceled()
                val count = input.read(bytes, 0, minOf(bytes.size, PlaybackBackgroundLimits.SETTINGS_BYTES - output.size() + 1))
                if (count < 0) break
                if (count == 0 || count > PlaybackBackgroundLimits.SETTINGS_BYTES - output.size()) {
                    backgroundFail(PlaybackBackgroundIssue.RECOVERY_REQUIRED)
                }
                output.write(bytes, 0, count)
            }
            return BackgroundSettingsRecords.decode(output.toByteArray())
        }
    }

    private fun writeSettings(directory: File, settings: PlaybackBackgroundSettings, signal: CancellationSignal) {
        validateSettingsFiles(directory)
        val atomic = AtomicFile(File(directory, "settings.bin"))
        var output: FileOutputStream? = null
        try {
            val bytes = BackgroundSettingsRecords.encode(settings)
            requireRoom(sourceBytes(directory), 0, bytes.size.toLong(), directory, source = false)
            val stream = atomic.startWrite()
            output = stream
            stream.write(bytes)
            signal.throwIfCanceled()
            atomic.finishWrite(stream)
            output = null
        } catch (error: Throwable) {
            try { atomic.failWrite(output) }
            catch (closing: Throwable) { if (error !== closing) error.addSuppressed(closing) }
            throw error
        }
        // AtomicFile may log a failed sync/rename. A save is acknowledged only after exact readback.
        if (readSettings(directory, CancellationSignal()) != settings) backgroundFail(PlaybackBackgroundIssue.SAVE_FAILED)
    }

    private fun validateSettingsFiles(directory: File) {
        for (name in listOf("settings.bin", "settings.bin.bak", "settings.bin.new")) {
            val file = File(directory, name)
            if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) requireThemeRegularFile(file)
        }
    }

    private fun sourceFiles(directory: File): List<File> {
        val result = ArrayList<File>()
        var count = 0
        Files.newDirectoryStream(directory.toPath()).use { entries ->
            for (path in entries) {
                if (++count > PlaybackBackgroundLimits.DIRECTORY_ENTRIES) backgroundFail(PlaybackBackgroundIssue.RECOVERY_REQUIRED)
                val name = path.fileName.toString()
                if (name in setOf("owner.lock", "settings.bin", "settings.bin.bak", "settings.bin.new")) continue
                val extension = when {
                    name.endsWith(".image") -> ".image"
                    name.endsWith(".part") -> ".part"
                    else -> backgroundFail(PlaybackBackgroundIssue.RECOVERY_REQUIRED)
                }
                val id = name.removeSuffix(extension)
                val file = sourceFile(directory, id, extension)
                requireThemeRegularFile(file)
                result += file
            }
        }
        return result
    }

    private fun sourceBytes(directory: File): Long {
        var bytes = 0L
        for (file in sourceFiles(directory)) {
            val size = Files.size(file.toPath())
            if (size !in 0..ArtworkLimits.INPUT_BYTES.toLong() || size > PlaybackBackgroundLimits.STORED_SOURCE_BYTES - bytes) {
                backgroundFail(PlaybackBackgroundIssue.STORAGE_FULL)
            }
            bytes += size
        }
        return bytes
    }

    private fun prune(directory: File, settings: PlaybackBackgroundSettings, signal: CancellationSignal) {
        val previewId = synchronized(previewLock) { held?.takeIf { !it.retired }?.preview?.image?.id }
        val retain = setOfNotNull(settings.options.customImage?.id, previewId) + synchronized(previewLock) { backupRetained.keys.toSet() }
        for (file in sourceFiles(directory)) {
            signal.throwIfCanceled()
            if (file.name.endsWith(".part") || file.name.removeSuffix(".image") !in retain) deleteSource(file)
        }
        synchronized(previewLock) { if (held?.retired == true) held = null }
    }

    private fun sourceFile(directory: File, id: String, extension: String): File {
        if (!isThemeOperationId(id) || extension !in setOf(".image", ".part")) backgroundFail(PlaybackBackgroundIssue.INVALID_INPUT)
        val file = File(directory, id + extension)
        if (Files.isSymbolicLink(file.toPath()) || file.canonicalFile.parentFile != directory) backgroundFail(PlaybackBackgroundIssue.INTEGRITY_FAILED)
        return file
    }

    private fun deleteSource(file: File) {
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        requireThemeRegularFile(file)
        try { Files.delete(file.toPath()) }
        catch (error: IOException) { throw PlaybackBackgroundException(PlaybackBackgroundIssue.CLEANUP_REQUIRED, error) }
    }

    private fun requireRoom(owned: Long, written: Long, next: Long, directory: File, source: Boolean = true) {
        if (source && (owned < 0 || written < 0 || next > PlaybackBackgroundLimits.STORED_SOURCE_BYTES - owned - written)) {
            backgroundFail(PlaybackBackgroundIssue.STORAGE_FULL)
        }
        val available = availableBytes(directory)
        if (available < ThemeLimits.FREE_RESERVE_BYTES || next > available - ThemeLimits.FREE_RESERVE_BYTES) {
            backgroundFail(PlaybackBackgroundIssue.STORAGE_FULL)
        }
    }

    private fun record(token: String, allowRetired: Boolean = false): PreviewRecord = synchronized(previewLock) {
        held?.takeIf { it.preview.token == token && (!it.retired || allowRetired) }
            ?: backgroundFail(PlaybackBackgroundIssue.PREVIEW_NOT_FOUND)
    }

    private fun retire(token: String) = synchronized(previewLock) {
        held?.takeIf { it.preview.token == token }?.let { it.retired = true; publishPreview(null) }
    }

    private fun validate(request: CustomBackgroundImportRequest) {
        if (!isThemeOperationId(request.operationId) || request.uri.isBlank() || request.uri.length > LocalInputLimits.URI_CHARACTERS ||
            request.displayName.isBlank() || request.displayName.length > ThemeLimits.NAME_CHARACTERS || request.displayName.any(Char::isISOControl)) {
            backgroundFail(PlaybackBackgroundIssue.INVALID_INPUT)
        }
    }

    private suspend fun <T> operation(abandoned: (T) -> Unit = {}, action: (File, CancellationSignal) -> T): T =
        backupBarrier.withBackupMutation({ PlaybackBackgroundException(PlaybackBackgroundIssue.BUSY) }) { operationActual(abandoned, action) }

    private suspend fun <T> operationActual(abandoned: (T) -> Unit, action: (File, CancellationSignal) -> T): T {
        if (!occupied.compareAndSet(false, true)) backgroundFail(PlaybackBackgroundIssue.BUSY)
        val admitted = AtomicBoolean(false)
        try {
            return io.read(abandoned = abandoned, admitted = { admitted.set(true) }) { signal ->
                    val directory = directory()
                    val lockFile = File(directory, "owner.lock")
                    FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                        val lock = try { channel.tryLock() }
                        catch (_: OverlappingFileLockException) { backgroundFail(PlaybackBackgroundIssue.BUSY) }
                            ?: backgroundFail(PlaybackBackgroundIssue.BUSY)
                        var failure: Throwable? = null
                        try { action(directory, signal) }
                        catch (error: Throwable) { failure = error; throw error }
                        finally {
                            try { lock.release() }
                            catch (closing: Throwable) {
                                if (failure == null) throw closing
                                if (failure !== closing) failure.addSuppressed(closing)
                            }
                        }
                    }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
        catch (error: PlaybackBackgroundException) { throw error }
        catch (error: ThemeException) { throw PlaybackBackgroundException(themeIssue(error.issue), error) }
        catch (error: ArtworkException) { throw PlaybackBackgroundException(artworkIssue(error.issue), error) }
        catch (error: SourceReadException) { throw PlaybackBackgroundException(sourceIssue(error), error) }
        catch (error: OutOfMemoryError) { throw PlaybackBackgroundException(PlaybackBackgroundIssue.LOW_MEMORY, error) }
        catch (error: IOException) { throw PlaybackBackgroundException(PlaybackBackgroundIssue.STORAGE_UNAVAILABLE, error) }
        catch (error: SecurityException) { throw PlaybackBackgroundException(PlaybackBackgroundIssue.PERMISSION_DENIED, error) }
        finally {
            try { if (admitted.get()) withContext(NonCancellable) { io.awaitIdle() } }
            finally { occupied.set(false) }
        }
    }

    private fun directory(): File {
        val directory = directoryProvider().absoluteFile
        if (Files.isSymbolicLink(directory.toPath())) backgroundFail(PlaybackBackgroundIssue.INTEGRITY_FAILED)
        if (!directory.isDirectory && !directory.mkdirs()) backgroundFail(PlaybackBackgroundIssue.STORAGE_FULL)
        val canonical = directory.canonicalFile
        if (canonical.parentFile != directory.parentFile?.canonicalFile || canonical.name != directory.name) {
            backgroundFail(PlaybackBackgroundIssue.INTEGRITY_FAILED)
        }
        return canonical
    }

    private fun publish(snapshot: PlaybackBackgroundSnapshot): PlaybackBackgroundSnapshot {
        backupBarrier.publishForBackup(this) { mutableState.value = snapshot }
        return snapshot
    }
    private fun publishFailure(snapshot: PlaybackBackgroundSnapshot): PlaybackBackgroundSnapshot {
        backupBarrier.publishFailureForBackup(this) { mutableState.value = snapshot }
        return snapshot
    }
    private val previewPublicationOwner = Any()
    private fun publishPreview(value: CustomBackgroundPreview?) { backupBarrier.publishForBackup(previewPublicationOwner) { mutablePreview.value = value } }

    private fun themeIssue(issue: ThemeIssue): PlaybackBackgroundIssue = when (issue) {
        ThemeIssue.BUSY -> PlaybackBackgroundIssue.BUSY
        ThemeIssue.LOW_MEMORY -> PlaybackBackgroundIssue.LOW_MEMORY
        ThemeIssue.IMAGE_TOO_LARGE -> PlaybackBackgroundIssue.IMAGE_TOO_LARGE
        ThemeIssue.INVALID_IMAGE, ThemeIssue.UNSUPPORTED_RESOURCE -> PlaybackBackgroundIssue.INVALID_IMAGE
        ThemeIssue.INVALID_INPUT -> PlaybackBackgroundIssue.INVALID_INPUT
        ThemeIssue.PERMISSION_DENIED -> PlaybackBackgroundIssue.PERMISSION_DENIED
        ThemeIssue.SOURCE_UNAVAILABLE -> PlaybackBackgroundIssue.SOURCE_UNAVAILABLE
        else -> PlaybackBackgroundIssue.INTEGRITY_FAILED
    }

    private fun <T> sourceRead(action: () -> T): T = try { action() }
    catch (error: IOException) { throw PlaybackBackgroundException(PlaybackBackgroundIssue.SOURCE_UNAVAILABLE, error) }
    catch (error: SecurityException) { throw PlaybackBackgroundException(PlaybackBackgroundIssue.PERMISSION_DENIED, error) }

    private fun artworkIssue(issue: ArtworkIssue): PlaybackBackgroundIssue = when (issue) {
        ArtworkIssue.BUSY -> PlaybackBackgroundIssue.BUSY
        ArtworkIssue.INPUT_TOO_LARGE -> PlaybackBackgroundIssue.IMAGE_TOO_LARGE
        ArtworkIssue.LOW_MEMORY -> PlaybackBackgroundIssue.LOW_MEMORY
        else -> PlaybackBackgroundIssue.INVALID_IMAGE
    }

    private fun sourceIssue(error: SourceReadException): PlaybackBackgroundIssue = when (error.issue) {
        SourceReadIssue.BUSY -> PlaybackBackgroundIssue.BUSY
        SourceReadIssue.PERMISSION_DENIED, SourceReadIssue.PERMISSION_REVOKED -> PlaybackBackgroundIssue.PERMISSION_DENIED
        else -> if (error.cause is OutOfMemoryError) PlaybackBackgroundIssue.LOW_MEMORY else PlaybackBackgroundIssue.SOURCE_UNAVAILABLE
    }

    private class PreviewRecord(val preview: CustomBackgroundPreview, val requestDigest: String, var retired: Boolean = false)
    private data class ImageInfo(val mediaType: String, val width: Int, val height: Int, val orientation: Int)

    companion object {
        private fun directoryProvider(context: Context): () -> File {
            val application = context.applicationContext
            return { File(application.noBackupFilesDir.canonicalFile, "playback-backgrounds-v1") }
        }
    }
}
