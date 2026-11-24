package io.github.onlinemo.meloxwatch.data.themes

import android.content.Context
import android.graphics.Bitmap
import android.os.CancellationSignal
import android.os.OperationCanceledException
import io.github.onlinemo.meloxwatch.data.fonts.AndroidLyricsFontDecoder
import io.github.onlinemo.meloxwatch.data.fonts.FontWeightCapability
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontDecoder
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontException
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontIssue
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontRole
import io.github.onlinemo.meloxwatch.data.fonts.SfntFontInfo
import io.github.onlinemo.meloxwatch.data.fonts.SfntFontParser
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Installed resources are loaded by a frozen snapshot, without retaining a discarded preview. */
class AppliedThemeResources private constructor(
    private val directoryProvider: () -> File,
    private val io: LocalProviderIo,
    private val bitmaps: ThemeBitmapOwner,
    private val validator: ThemeAssetValidator,
    private val fontDecoder: LyricsFontDecoder,
) {
    constructor(context: Context, io: LocalProviderIo, bitmaps: ThemeBitmapOwner) : this(
        directoryProvider(context), io, bitmaps, ThemeAssetValidator(), AndroidLyricsFontDecoder(),
    )

    internal constructor(
        directory: File,
        io: LocalProviderIo,
        bitmaps: ThemeBitmapOwner,
        validator: ThemeAssetValidator = ThemeAssetValidator(),
        fontDecoder: LyricsFontDecoder = AndroidLyricsFontDecoder(),
    ) : this({ directory }, io, bitmaps, validator, fontDecoder)

    private val fontParser = SfntFontParser()

    suspend fun loadImage(snapshot: ThemeSnapshot, resourceId: String, targetPx: Int): AppliedThemeImageLease {
        currentCoroutineContext().ensureActive()
        if (targetPx !in 1..PlaybackBackgroundLimits.DERIVED_EDGE) themeFail(ThemeIssue.INVALID_INPUT)
        return physical(abandoned = ThemeBitmapLease::close) { signal ->
            val store = ThemeDirectoryStore(directoryProvider())
            store.exclusively {
                val manifest = installedManifest(store, snapshot, signal)
                val resource = manifest.resource(resourceId) ?: themeFail(ThemeIssue.MISSING_RESOURCE)
                if (resource.kind != ThemeResourceKind.IMAGE) themeFail(ThemeIssue.UNSUPPORTED_RESOURCE)
                val ref = (snapshot.effective as ThemeChoice.Installed).ref
                val file = themeContainedFile(store.installationDirectory(ref.installationId), resource.path)
                val bytes = validator.verifiedBytes(file, resource, signal)
                // 上面已核对当前目录修订号及实际文件；目录元数据不改变已验证图像的像素。
                val key = backgroundKey("applied-image-v1", ref.installationId,
                    ref.manifestSha256, resource.id, resource.sha256, targetPx.toString())
                bitmaps.acquire(key)?.let { return@exclusively it }
                val reservation = bitmaps.reserve(PlaybackBackgroundLimits.DECODE_BYTES)
                var decoded: Bitmap? = null
                try {
                    val icon = resource.id in manifest.icons.values
                    val image = validator.decodeImage(bytes, resource.mediaType, targetPx, icon, signal)
                    decoded = image
                    signal.throwIfCanceled()
                    val result = bitmaps.publish(key, image, reservation, if (icon) manifest.palette.textPrimary else null)
                    decoded = null
                    result
                } finally {
                    decoded?.recycle()
                    reservation.close()
                }
            }
        }
    }

    /**
     * The existing font owner consumes synchronously on this worker under the store lock.
     * It must not retain a path/handle, write settings or nest io.read. Its persistent face/ref
     * ownership remains external. abandoned may release memory only, including on Main.
     */
    suspend fun <T> consumeFont(
        snapshot: ThemeSnapshot,
        role: LyricsFontRole,
        abandoned: (T) -> Unit,
        consume: (File, SfntFontInfo, FontWeightCapability, CancellationSignal) -> T,
    ): T? {
        currentCoroutineContext().ensureActive()
        if (snapshot.effective is ThemeChoice.Builtin) return null
        return physical<T?>(abandoned = { value -> if (value != null) abandoned(value) }) { signal ->
            val store = ThemeDirectoryStore(directoryProvider())
            store.exclusively {
                val manifest = installedManifest(store, snapshot, signal)
                val id = manifest.fonts[role] ?: return@exclusively null
                val resource = manifest.resource(id) ?: themeFail(ThemeIssue.MISSING_RESOURCE)
                if (resource.kind != ThemeResourceKind.FONT) themeFail(ThemeIssue.UNSUPPORTED_RESOURCE)
                val ref = (snapshot.effective as ThemeChoice.Installed).ref
                val file = themeContainedFile(store.installationDirectory(ref.installationId), resource.path)
                val bytes = validator.verifiedBytes(file, resource, signal)
                val info = try { fontParser.parse(bytes, signal::throwIfCanceled) }
                catch (error: LyricsFontException) { throw fontFailure(error) }
                // The existing validator's two 512x96 native rasters and two pixel arrays.
                val probe = bitmaps.reserve(FONT_PROBE_BYTES)
                val capability = try {
                    nativeFont { fontDecoder.validate(file, info, signal::throwIfCanceled) }
                } finally { probe.close() }
                signal.throwIfCanceled()
                val result = consume(file, info, capability, signal)
                try {
                    signal.throwIfCanceled()
                    result
                } catch (error: Throwable) {
                    try { abandoned(result) }
                    catch (closing: Throwable) { if (error !== closing) error.addSuppressed(closing) }
                    throw error
                }
            }
        }
    }

    private fun installedManifest(store: ThemeDirectoryStore, snapshot: ThemeSnapshot, signal: CancellationSignal): ThemeManifest {
        signal.throwIfCanceled()
        val ref = (snapshot.effective as? ThemeChoice.Installed)?.ref ?: themeFail(ThemeIssue.MISSING_RESOURCE)
        val revision = snapshot.revision ?: themeFail(ThemeIssue.REVISION_CONFLICT)
        if (store.pendingIssue() == ThemeIssue.RECOVERY_REQUIRED) themeFail(ThemeIssue.RECOVERY_REQUIRED)
        val catalog = store.loadCatalog().catalog
        if (catalog.revision != revision || catalog.selected != snapshot.selected || catalog.previous != snapshot.previous) {
            themeFail(ThemeIssue.REVISION_CONFLICT)
        }
        val manifest = store.manifest(ref, catalog)
        verifyThemePayloadTree(store.installationDirectory(ref.installationId), manifest, signal::throwIfCanceled)
        signal.throwIfCanceled()
        return manifest
    }

    private suspend fun <T> physical(abandoned: (T) -> Unit, action: (CancellationSignal) -> T): T = try {
        io.read(abandoned = abandoned, action = action)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: SourceReadException) {
        val issue = when (error.issue) {
            SourceReadIssue.BUSY -> ThemeIssue.BUSY
            SourceReadIssue.PERMISSION_DENIED, SourceReadIssue.PERMISSION_REVOKED -> ThemeIssue.PERMISSION_DENIED
            else -> if (error.cause is OutOfMemoryError) ThemeIssue.LOW_MEMORY else ThemeIssue.SOURCE_UNAVAILABLE
        }
        throw ThemeException(issue, error)
    } catch (error: OutOfMemoryError) { throw ThemeException(ThemeIssue.LOW_MEMORY, error) }

    private fun <T> nativeFont(action: () -> T): T = try { action() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (cancelled: OperationCanceledException) { throw cancelled }
    catch (error: LyricsFontException) { throw fontFailure(error) }
    catch (error: IOException) { throw ThemeException(ThemeIssue.INVALID_FONT, error) }
    catch (error: RuntimeException) { throw ThemeException(ThemeIssue.INVALID_FONT, error) }

    private fun fontFailure(error: LyricsFontException): ThemeException = ThemeException(
        if (error.issue == LyricsFontIssue.LOW_MEMORY) ThemeIssue.LOW_MEMORY else ThemeIssue.INVALID_FONT, error,
    )

    companion object {
        private const val FONT_PROBE_BYTES = 4L * 512 * 96 * 4
        private fun directoryProvider(context: Context): () -> File {
            val application = context.applicationContext
            return { File(application.noBackupFilesDir.canonicalFile, "themes-v1") }
        }
    }
}
