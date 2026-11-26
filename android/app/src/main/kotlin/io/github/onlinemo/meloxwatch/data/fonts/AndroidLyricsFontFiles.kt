package io.github.onlinemo.meloxwatch.data.fonts

import android.content.Context
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.Looper
import androidx.annotation.WorkerThread
import io.github.onlinemo.meloxwatch.data.local.LocalInputLimits
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.themes.SharedFontThemeStorageBudget
import io.github.onlinemo.meloxwatch.data.themes.ThemeBitmapOwner
import io.github.onlinemo.meloxwatch.data.themes.ThemeResource
import io.github.onlinemo.meloxwatch.data.themes.ThemeResourceKind
import io.github.onlinemo.meloxwatch.data.backup.BackupMutationBarrier
import io.github.onlinemo.meloxwatch.data.backup.BackupException
import io.github.onlinemo.meloxwatch.data.backup.BackupIssue
import io.github.onlinemo.meloxwatch.data.backup.BackupResourceWriter
import io.github.onlinemo.meloxwatch.data.backup.withBackupMutation
import io.github.onlinemo.meloxwatch.data.storage.StorageArea
import io.github.onlinemo.meloxwatch.data.storage.StorageDirectorySpec
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * The sole font file owner keeps two saved roles and one editor draft/retry. Validation shares
 * the global physical read channel. A cancelled provider cannot free it or delete a live .part.
 */
class AndroidLyricsFontFiles private constructor(
    private val directoryProvider: () -> File,
    private val io: LocalProviderIo,
    private val opener: LyricsFontStreamOpener,
    private val decoder: LyricsFontDecoder,
    private val availableBytes: (File) -> Long,
    private val cleanupDispatcher: CoroutineDispatcher,
    private val sharedBudget: SharedFontThemeStorageBudget,
    private val backupBarrier: BackupMutationBarrier?,
) : LyricsFontFiles {
    constructor(
        context: Context,
        io: LocalProviderIo,
        sharedBudget: SharedFontThemeStorageBudget = SharedFontThemeStorageBudget(context, io),
        bitmaps: ThemeBitmapOwner = ThemeBitmapOwner(),
        backupBarrier: BackupMutationBarrier? = null,
    ) : this(
        fontDirectoryProvider(context),
        io,
        AndroidLyricsFontStream(context.applicationContext.contentResolver),
        BudgetedLyricsFontDecoder(bitmaps),
        File::getUsableSpace,
        Dispatchers.IO.limitedParallelism(1),
        sharedBudget,
        backupBarrier,
    )

    internal constructor(
        directory: File,
        io: LocalProviderIo,
        opener: LyricsFontStreamOpener,
        decoder: LyricsFontDecoder,
        availableBytes: (File) -> Long = File::getUsableSpace,
        cleanupDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
        sharedBudget: SharedFontThemeStorageBudget = SharedFontThemeStorageBudget(
            directory, File(directory.parentFile, "themes-v1"), io,
        ),
        backupBarrier: BackupMutationBarrier? = null,
    ) : this({ directory }, io, opener, decoder, availableBytes, cleanupDispatcher, sharedBudget, backupBarrier)

    init { require(sharedBudget.uses(io)) { "Font storage must use the shared physical channel" } }

    private val occupied = AtomicBoolean(false)
    private val parser = SfntFontParser()
    private val backupPinLock = Any()
    private var backupRetained = emptyMap<String, LyricsFontAsset>()

    internal fun storageDirectorySpecOnWorker(): StorageDirectorySpec {
        check(Looper.myLooper() != Looper.getMainLooper())
        return StorageDirectorySpec(directoryProvider().absoluteFile, StorageArea.FONTS, 1,
            LyricsFontLimits.DIRECTORY_ENTRIES.toLong(), classify = { parts, directory ->
                val name = parts.singleOrNull()
                if (directory || name == null || managedId(name) == null) null
                else if (name.endsWith(FONT_EXTENSION)) StorageArea.FONTS else StorageArea.RECOVERY
            }, mutableFile = { it.last().endsWith(PART_EXTENSION) }, requireNonEmpty = { it.last().endsWith(FONT_EXTENSION) })
    }

    /** Only the journal owner can retain the two old and two prospective saved roles. */
    internal suspend fun retainForBackup(assets: List<LyricsFontAsset>) {
        requireBackupOwner()
        if (assets.size > 4 || assets.map { it.id }.distinct().size != assets.size) fontFail(LyricsFontIssue.INVALID_INPUT)
        operation {
            physical { signal ->
                val directory = directory()
                for (asset in assets) verifiedInfo(managedFile(directory, asset.id, FONT_EXTENSION), asset, signal)
                synchronized(backupPinLock) { backupRetained = assets.associateBy { it.id } }
            }
        }
    }

    internal suspend fun exportForBackup(asset: LyricsFontAsset, output: BackupResourceWriter) {
        requireBackupOwner()
        operation {
            physical { signal ->
                val file = managedFile(directory(), asset.id, FONT_EXTENSION)
                verifiedInfo(file, asset, signal)
                output.copyVerified(file, asset.byteCount, asset.sha256, signal::throwIfCanceled)
            }
        }
    }

    /** Validate isolated archive bytes with this same parser, decoder and physical owner. */
    internal suspend fun validateForBackup(file: File, asset: LyricsFontAsset): FontWeightCapability {
        requireBackupOwner()
        return operation { physical { signal ->
            val info = verifiedInfo(file, asset, signal)
            native { decoder.validate(file, info, signal::throwIfCanceled) }
        } }
    }

    internal suspend fun releaseBackupRetention(retain: Set<String>) {
        requireBackupOwner()
        validateRetain(retain)
        operation {
            physical { signal ->
                synchronized(backupPinLock) { backupRetained = emptyMap() }
                pruneFiles(directory(), retain, signal)
            }
        }
    }

    private suspend fun requireBackupOwner() {
        val barrier = backupBarrier ?: throw BackupException(BackupIssue.INVALID_INPUT)
        barrier.requireExclusiveOwner()
    }

    override suspend fun importFont(request: LyricsFontImportRequest, retain: Set<String>): PreparedLyricsFont = operation {
        validateRetain(retain)
        validateRequest(request)
        val id = UUID.randomUUID().toString()
        val entered = AtomicBoolean(false)
        var delivered = false
        var failure: Throwable? = null
        try {
            val result = physical { signal ->
                entered.set(true)
                val directory = directory()
                pruneFiles(directory, retain, signal)
                val ownBytes = measuredOwnedBytes(directory, signal)
                val themeBytes = sharedBudget.themeBytesForFonts(signal)
                if (themeBytes > LyricsFontLimits.OWNED_BYTES - ownBytes) fontFail(LyricsFontIssue.QUOTA_EXCEEDED)
                val owned = ownBytes + themeBytes
                val part = managedFile(directory, id, PART_EXTENSION)
                if (!part.createNewFile()) fontFail(LyricsFontIssue.INTEGRITY_FAILED)
                val asset = copyInput(request, id, part, owned, signal)
                val info = verifiedInfo(part, asset, signal)
                val capability = native { decoder.validate(part, info, signal::throwIfCanceled) }
                signal.throwIfCanceled()
                val installed = managedFile(directory, id, FONT_EXTENSION)
                if (installed.exists()) fontFail(LyricsFontIssue.INTEGRITY_FAILED)
                Files.move(part.toPath(), installed.toPath(), StandardCopyOption.ATOMIC_MOVE)
                signal.throwIfCanceled()
                PreparedLyricsFont(asset, info, capability)
            }
            currentCoroutineContext().ensureActive()
            delivered = true
            result
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            if (!delivered && entered.get()) {
                // physical() has awaited real close on cancellation; this owner alone knows id.
                try { withContext(NonCancellable + cleanupDispatcher) { physical { cleanupOwn(id) } } }
                catch (closing: Throwable) {
                    if (failure == null) throw closing else if (failure !== closing) failure.addSuppressed(closing)
                }
            }
        }
    }

    override suspend fun loadFont(selection: LyricsFontSelection, text: String): LoadedLyricsFont {
        if (text.length > LyricsFontLimits.TEXT_CHARACTERS) return fallback(selection, LyricsFontIssue.INVALID_INPUT)
        val asset = selection.asset ?: return LoadedLyricsFont(requestedWeight = selection.requestedWeight)
        return try {
            operation {
                physical { signal ->
                    val file = managedFile(directory(), asset.id, FONT_EXTENSION)
                    val info = verifiedInfo(file, asset, signal)
                    val capability = native { decoder.validate(file, info, signal::throwIfCanceled) }
                    val coverage = info.checkText(text, signal::throwIfCanceled)
                    val clamped = info.weightAxis?.clamp(selection.requestedWeight)
                    if (coverage.missing != 0) {
                        LoadedLyricsFont(
                            asset = asset, requestedWeight = selection.requestedWeight, clampedWeight = clamped,
                            capability = capability, issue = LyricsFontIssue.MISSING_GLYPHS,
                            missingCodePoints = coverage.missing, firstMissingCodePoint = coverage.firstMissing,
                        )
                    } else {
                        signal.throwIfCanceled()
                        val decoded = native { decoder.decode(file, info, capability, selection.requestedWeight) }
                        signal.throwIfCanceled()
                        LoadedLyricsFont(
                            typeface = decoded.typeface, asset = asset, requestedWeight = selection.requestedWeight,
                            clampedWeight = clamped, configuredAxisWeight = decoded.configuredAxisWeight,
                            nativeStyleWeight = decoded.nativeStyleWeight, capability = capability,
                        )
                    }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: LyricsFontException) { fallback(selection, error.issue) }
        catch (error: SourceReadException) { fallback(selection, mapSource(error)) }
        catch (_: IOException) { fallback(selection, LyricsFontIssue.SOURCE_UNAVAILABLE) }
        catch (_: SecurityException) { fallback(selection, LyricsFontIssue.SOURCE_UNAVAILABLE) }
        catch (_: OutOfMemoryError) { fallback(selection, LyricsFontIssue.LOW_MEMORY) }
    }

    override suspend fun discard(asset: LyricsFontAsset, retain: Set<String>) = operation {
        validateRetain(retain)
        if (asset.id !in effectiveRetain(retain)) physical { signal ->
            signal.throwIfCanceled()
            val directory = directory()
            deleteManaged(managedFile(directory, asset.id, FONT_EXTENSION))
            deleteManaged(managedFile(directory, asset.id, PART_EXTENSION))
        }
        Unit
    }

    /**
     * LocalThemeRepository.consumePreviewFont owns this worker and the theme directory lock.
     * Recheck the declared bounded bytes with this owner's existing parser, then use its same
     * decoder. No standalone asset identity, nested physical request or extra face cache is made.
     */
    @WorkerThread
    fun consumePreviewFontOnWorker(
        file: File,
        resource: ThemeResource,
        capability: FontWeightCapability,
        requestedWeight: Float?,
        text: String,
        signal: CancellationSignal,
    ): LoadedLyricsFont {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Theme preview font decoding requires the physical worker" }
        signal.throwIfCanceled()
        if (resource.kind != ThemeResourceKind.FONT || text.length > LyricsFontLimits.TEXT_CHARACTERS ||
            requestedWeight?.isFinite() == false) {
            return LoadedLyricsFont(requestedWeight = requestedWeight, issue = LyricsFontIssue.INVALID_INPUT)
        }
        return try {
            val info = verifiedInfo(file, resource.byteCount, resource.sha256, signal)
            if (capability.axis != info.weightAxis) fontFail(LyricsFontIssue.INTEGRITY_FAILED)
            consumeThemeFontOnWorker(file, info, capability, requestedWeight, text, signal)
        } catch (error: LyricsFontException) {
            LoadedLyricsFont(requestedWeight = requestedWeight, issue = error.issue)
        } catch (_: IOException) {
            LoadedLyricsFont(requestedWeight = requestedWeight, issue = LyricsFontIssue.SOURCE_UNAVAILABLE)
        } catch (_: SecurityException) {
            LoadedLyricsFont(requestedWeight = requestedWeight, issue = LyricsFontIssue.SOURCE_UNAVAILABLE)
        } catch (_: OutOfMemoryError) {
            LoadedLyricsFont(requestedWeight = requestedWeight, issue = LyricsFontIssue.LOW_MEMORY)
        }
    }

    /**
     * Root calls this only from AppliedThemeResources.consumeFont's physical worker/file lock.
     * That caller verifies the exact theme revision/resource/hash and caches only the returned
     * bounded pair of faces. It must reject late generations before replacing current LyricsFonts.
     * No path, byte array or descriptor becomes UI state; no nested physical read is performed.
     */
    @WorkerThread
    fun consumeThemeFontOnWorker(
        file: File,
        info: SfntFontInfo,
        capability: FontWeightCapability,
        requestedWeight: Float?,
        text: String,
        signal: CancellationSignal,
    ): LoadedLyricsFont {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Theme font decoding requires the physical worker" }
        if (text.length > LyricsFontLimits.TEXT_CHARACTERS || requestedWeight?.isFinite() == false) {
            return LoadedLyricsFont(requestedWeight = requestedWeight, issue = LyricsFontIssue.INVALID_INPUT)
        }
        if (!occupied.compareAndSet(false, true)) fontFail(LyricsFontIssue.BUSY)
        try {
            signal.throwIfCanceled()
            val coverage = info.checkText(text, signal::throwIfCanceled)
            if (coverage.missing != 0) return LoadedLyricsFont(requestedWeight = requestedWeight,
                clampedWeight = info.weightAxis?.clamp(requestedWeight), capability = capability,
                issue = LyricsFontIssue.MISSING_GLYPHS, missingCodePoints = coverage.missing,
                firstMissingCodePoint = coverage.firstMissing)
            val decoded = native { decoder.decode(file, info, capability, requestedWeight) }
            signal.throwIfCanceled()
            return LoadedLyricsFont(typeface = decoded.typeface, requestedWeight = requestedWeight,
                clampedWeight = info.weightAxis?.clamp(requestedWeight), capability = capability,
                configuredAxisWeight = decoded.configuredAxisWeight, nativeStyleWeight = decoded.nativeStyleWeight)
        } catch (error: LyricsFontException) {
            return LoadedLyricsFont(requestedWeight = requestedWeight, issue = error.issue)
        } catch (_: OutOfMemoryError) {
            return LoadedLyricsFont(requestedWeight = requestedWeight, issue = LyricsFontIssue.LOW_MEMORY)
        } finally { occupied.set(false) }
    }

    override suspend fun prune(retain: Set<String>) = operation {
        validateRetain(retain)
        physical { signal -> pruneFiles(directory(), retain, signal) }
    }

    private fun copyInput(
        request: LyricsFontImportRequest,
        id: String,
        part: File,
        owned: Long,
        signal: CancellationSignal,
    ): LyricsFontAsset {
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        opener.open(request.uri, signal).use { opened ->
            if (opened.declaredBytes != null && opened.declaredBytes > LyricsFontLimits.FILE_BYTES) {
                fontFail(LyricsFontIssue.TOO_LARGE)
            }
            if (opened.declaredBytes != null && opened.declaredBytes < 0L) fontFail(LyricsFontIssue.INVALID_INPUT)
            val buffer = ByteArray(LyricsFontLimits.IO_BYTES)
            FileOutputStream(part).use { output ->
                while (true) {
                    signal.throwIfCanceled()
                    val count = opened.input.read(buffer)
                    signal.throwIfCanceled()
                    if (count < 0) break
                    if (count == 0) fontFail(LyricsFontIssue.SOURCE_UNAVAILABLE)
                    if (count > LyricsFontLimits.FILE_BYTES - written) fontFail(LyricsFontIssue.TOO_LARGE)
                    FontSpacePolicy.requireRoom(owned, written, count.toLong(), availableBytes(part.parentFile!!))
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    written += count
                }
                if (written == 0L || opened.declaredBytes != null && opened.declaredBytes != written) {
                    fontFail(LyricsFontIssue.INTEGRITY_FAILED)
                }
                signal.throwIfCanceled()
                output.fd.sync()
            }
        }
        return LyricsFontAsset(id, digest.digest().hex(), written, request.displayName, request.licenseAcknowledgmentVersion)
    }

    private fun verifiedInfo(file: File, asset: LyricsFontAsset, signal: CancellationSignal): SfntFontInfo =
        verifiedInfo(file, asset.byteCount, asset.sha256, signal)

    private fun verifiedInfo(file: File, expectedBytes: Long, expectedSha256: String, signal: CancellationSignal): SfntFontInfo {
        signal.throwIfCanceled()
        if (expectedBytes !in 1..LyricsFontLimits.FILE_BYTES.toLong() ||
            !expectedSha256.matches(Regex("[0-9a-f]{64}"))) fontFail(LyricsFontIssue.INTEGRITY_FAILED)
        if (Files.isSymbolicLink(file.toPath()) || !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            fontFail(LyricsFontIssue.SOURCE_UNAVAILABLE)
        }
        val length = file.length()
        if (length != expectedBytes) {
            fontFail(LyricsFontIssue.INTEGRITY_FAILED)
        }
        val bytes = ByteArray(length.toInt())
        FileInputStream(file).use { input ->
            var offset = 0
            while (offset < bytes.size) {
                signal.throwIfCanceled()
                val count = input.read(bytes, offset, minOf(LyricsFontLimits.IO_BYTES, bytes.size - offset))
                if (count <= 0) fontFail(LyricsFontIssue.INTEGRITY_FAILED)
                offset += count
            }
            signal.throwIfCanceled()
            if (input.read() != -1) fontFail(LyricsFontIssue.INTEGRITY_FAILED)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var offset = 0
        while (offset < bytes.size) {
            signal.throwIfCanceled()
            val count = minOf(LyricsFontLimits.IO_BYTES, bytes.size - offset)
            digest.update(bytes, offset, count)
            offset += count
        }
        if (digest.digest().hex() != expectedSha256) fontFail(LyricsFontIssue.INTEGRITY_FAILED)
        return parser.parse(bytes, signal::throwIfCanceled)
    }

    private fun directory(): File {
        val directory = directoryProvider().absoluteFile
        if (Files.isSymbolicLink(directory.toPath())) fontFail(LyricsFontIssue.INTEGRITY_FAILED)
        if (!directory.isDirectory && !directory.mkdirs()) fontFail(LyricsFontIssue.STORAGE_FULL)
        val canonical = directory.canonicalFile
        val parent = directory.parentFile?.canonicalFile ?: fontFail(LyricsFontIssue.INVALID_INPUT)
        if (canonical.parentFile != parent || canonical.name != directory.name) fontFail(LyricsFontIssue.INTEGRITY_FAILED)
        return canonical
    }

    private fun managedFile(directory: File, id: String, extension: String): File {
        if (!isLyricsFontAssetId(id)) fontFail(LyricsFontIssue.INVALID_INPUT)
        return File(directory, id + extension).also { file ->
            if (Files.isSymbolicLink(file.toPath()) || file.canonicalFile.parentFile != directory) {
                fontFail(LyricsFontIssue.INTEGRITY_FAILED)
            }
        }
    }

    private fun managedFiles(directory: File): List<File> {
        val result = ArrayList<File>()
        var count = 0
        Files.newDirectoryStream(directory.toPath()).use { entries ->
            for (entry in entries) {
                if (++count > LyricsFontLimits.DIRECTORY_ENTRIES) fontFail(LyricsFontIssue.QUOTA_EXCEEDED)
                val file = entry.toFile()
                val id = managedId(file.name) ?: fontFail(LyricsFontIssue.INTEGRITY_FAILED)
                val safe = managedFile(directory, id, file.name.takeLast(5))
                if (!Files.isRegularFile(safe.toPath(), LinkOption.NOFOLLOW_LINKS)) fontFail(LyricsFontIssue.INTEGRITY_FAILED)
                result += safe
            }
        }
        return result
    }

    private fun pruneFiles(directory: File, retain: Set<String>, signal: CancellationSignal) {
        pruneFiles(directory, retain, signal::throwIfCanceled)
    }

    private fun pruneFiles(directory: File, retain: Set<String>, checkCancellation: () -> Unit) {
        val retained = effectiveRetain(retain)
        managedFiles(directory).forEach { file ->
            checkCancellation()
            val id = checkNotNull(managedId(file.name))
            if (id !in retained || file.name.endsWith(PART_EXTENSION)) deleteManaged(file)
        }
    }

    private fun cleanupOwn(id: String) {
        val directory = directory()
        deleteManaged(managedFile(directory, id, PART_EXTENSION))
        deleteManaged(managedFile(directory, id, FONT_EXTENSION))
    }

    private fun deleteManaged(file: File) {
        if (file.exists() && !file.delete()) fontFail(LyricsFontIssue.STORAGE_FULL)
    }

    private suspend fun <T> operation(action: suspend () -> T): T =
        backupBarrier.withBackupMutation({ LyricsFontException(LyricsFontIssue.BUSY) }) { operationActual(action) }

    private suspend fun <T> operationActual(action: suspend () -> T): T {
        if (!occupied.compareAndSet(false, true)) fontFail(LyricsFontIssue.BUSY)
        try { return action() }
        catch (error: SourceReadException) { throw LyricsFontException(mapSource(error), error) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: IOException) { throw LyricsFontException(LyricsFontIssue.SOURCE_UNAVAILABLE, error) }
        catch (error: SecurityException) { throw LyricsFontException(LyricsFontIssue.SOURCE_UNAVAILABLE, error) }
        catch (error: OutOfMemoryError) { throw LyricsFontException(LyricsFontIssue.LOW_MEMORY, error) }
        finally { occupied.set(false) }
    }

    private suspend fun <T> physical(action: (CancellationSignal) -> T): T {
        val admitted = AtomicBoolean(false)
        try {
            return io.read(admitted = { admitted.set(true) }) { signal -> sharedBudget.fontsOnWorker(signal) { action(signal) } }
        } finally {
            if (admitted.get()) withContext(NonCancellable) { io.awaitIdle() }
        }
    }

    private fun <T> native(action: () -> T): T = try { action() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (cancelled: OperationCanceledException) { throw cancelled }
    catch (error: LyricsFontException) { throw error }
    catch (error: IOException) { throw LyricsFontException(LyricsFontIssue.NATIVE_DECODE_FAILED, error) }
    catch (error: RuntimeException) { throw LyricsFontException(LyricsFontIssue.NATIVE_DECODE_FAILED, error) }

    private fun validateRetain(retain: Set<String>) {
        if (retain.size > 3 || retain.any { !isLyricsFontAssetId(it) }) fontFail(LyricsFontIssue.INVALID_INPUT)
    }

    private fun effectiveRetain(retain: Set<String>): Set<String> = synchronized(backupPinLock) {
        (retain + backupRetained.keys).also { if (it.size > 4) fontFail(LyricsFontIssue.INVALID_INPUT) }
    }

    private fun validateRequest(request: LyricsFontImportRequest) {
        if (request.licenseAcknowledgmentVersion != LyricsFontLimits.LICENSE_ACKNOWLEDGMENT_VERSION) {
            fontFail(LyricsFontIssue.LICENSE_ACKNOWLEDGMENT_REQUIRED)
        }
        if (!validFontDisplayName(request.displayName) || request.uri.isBlank() || request.uri.length > LocalInputLimits.URI_CHARACTERS) {
            fontFail(LyricsFontIssue.INVALID_INPUT)
        }
    }

    private fun fallback(selection: LyricsFontSelection, issue: LyricsFontIssue): LoadedLyricsFont = LoadedLyricsFont(
        asset = selection.asset, requestedWeight = selection.requestedWeight, issue = issue,
    )

    private fun mapSource(error: SourceReadException): LyricsFontIssue = when (error.issue) {
        SourceReadIssue.BUSY -> LyricsFontIssue.BUSY
        SourceReadIssue.PERMISSION_DENIED, SourceReadIssue.PERMISSION_REVOKED -> LyricsFontIssue.PERMISSION_DENIED
        SourceReadIssue.INVALID_SOURCE -> LyricsFontIssue.INVALID_INPUT
        SourceReadIssue.METADATA_UNREADABLE -> if (error.cause is OutOfMemoryError) LyricsFontIssue.LOW_MEMORY else LyricsFontIssue.INVALID_FONT
        SourceReadIssue.STORAGE_FULL -> LyricsFontIssue.STORAGE_FULL
        else -> LyricsFontIssue.SOURCE_UNAVAILABLE
    }

    private fun managedId(name: String): String? {
        val extension = when {
            name.endsWith(FONT_EXTENSION) -> FONT_EXTENSION
            name.endsWith(PART_EXTENSION) -> PART_EXTENSION
            else -> return null
        }
        return name.removeSuffix(extension).takeIf(::isLyricsFontAssetId)
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    companion object {
        private const val DIRECTORY_NAME = "lyrics-fonts-v1"
        private const val FONT_EXTENSION = ".font"
        private const val PART_EXTENSION = ".part"
        internal fun fontDirectoryProvider(context: Context): () -> File {
            val application = context.applicationContext
            return { File(application.filesDir.canonicalFile, DIRECTORY_NAME) }
        }

        /** Worker-only reciprocal measurement includes real .part bytes and rejects unknown entries. */
        internal fun measuredOwnedBytes(input: File, signal: CancellationSignal): Long {
            signal.throwIfCanceled()
            val directory = input.absoluteFile
            val parent = directory.parentFile?.canonicalFile ?: fontFail(LyricsFontIssue.INTEGRITY_FAILED)
            if (Files.isSymbolicLink(directory.toPath()) || directory.canonicalFile.parentFile != parent) {
                fontFail(LyricsFontIssue.INTEGRITY_FAILED)
            }
            if (!Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) return 0L
            if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) fontFail(LyricsFontIssue.INTEGRITY_FAILED)
            var count = 0
            var total = 0L
            Files.newDirectoryStream(directory.toPath()).use { entries ->
                for (path in entries) {
                    signal.throwIfCanceled()
                    if (++count > LyricsFontLimits.DIRECTORY_ENTRIES) fontFail(LyricsFontIssue.QUOTA_EXCEEDED)
                    val name = path.fileName.toString()
                    val extension = when {
                        name.endsWith(FONT_EXTENSION) -> FONT_EXTENSION
                        name.endsWith(PART_EXTENSION) -> PART_EXTENSION
                        else -> fontFail(LyricsFontIssue.INTEGRITY_FAILED)
                    }
                    if (!isLyricsFontAssetId(name.removeSuffix(extension)) || Files.isSymbolicLink(path) ||
                        !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || path.toFile().canonicalFile.parentFile != directory.canonicalFile) {
                        fontFail(LyricsFontIssue.INTEGRITY_FAILED)
                    }
                    val size = Files.size(path)
                    if (size < 0L || size > LyricsFontLimits.OWNED_BYTES - total) fontFail(LyricsFontIssue.QUOTA_EXCEEDED)
                    total += size
                }
            }
            signal.throwIfCanceled()
            return total
        }
    }
}

internal object FontSpacePolicy {
    fun requireRoom(owned: Long, written: Long, next: Long, available: Long) {
        if (owned < 0L || written < 0L || next < 0L || available < 0L) fontFail(LyricsFontIssue.INVALID_INPUT)
        if (owned > LyricsFontLimits.OWNED_BYTES || written > LyricsFontLimits.OWNED_BYTES - owned ||
            next > LyricsFontLimits.OWNED_BYTES - owned - written) fontFail(LyricsFontIssue.QUOTA_EXCEEDED)
        if (available < LyricsFontLimits.FREE_RESERVE_BYTES || next > available - LyricsFontLimits.FREE_RESERVE_BYTES) {
            fontFail(LyricsFontIssue.STORAGE_FULL)
        }
    }
}
