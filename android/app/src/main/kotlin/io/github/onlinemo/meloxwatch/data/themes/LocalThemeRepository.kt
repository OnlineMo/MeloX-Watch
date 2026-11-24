package io.github.onlinemo.meloxwatch.data.themes

import android.content.Context
import android.os.CancellationSignal
import io.github.onlinemo.meloxwatch.data.artwork.ArtworkLimits
import io.github.onlinemo.meloxwatch.data.fonts.FontSpacePolicy
import io.github.onlinemo.meloxwatch.data.fonts.FontWeightCapability
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontException
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontIssue
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontLimits
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontRole
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.transfer.TransferLimits
import io.github.onlinemo.meloxwatch.data.transfer.TransferOperationBinding
import io.github.onlinemo.meloxwatch.data.transfer.TransferOperationKind
import io.github.onlinemo.meloxwatch.data.transfer.TransferPermit
import io.github.onlinemo.meloxwatch.data.transfer.TransferUploadKind
import io.github.onlinemo.meloxwatch.data.transfer.TransferVerifiedUpload
import io.github.onlinemo.meloxwatch.data.backup.BackupMutationBarrier
import io.github.onlinemo.meloxwatch.data.backup.BackupException
import io.github.onlinemo.meloxwatch.data.backup.BackupIssue
import io.github.onlinemo.meloxwatch.data.backup.BackupResourceWriter
import io.github.onlinemo.meloxwatch.data.backup.BackupFileIdentity
import io.github.onlinemo.meloxwatch.data.backup.BackupPrivateDirectory
import io.github.onlinemo.meloxwatch.data.backup.publishForBackup
import io.github.onlinemo.meloxwatch.data.backup.publishFailureForBackup
import io.github.onlinemo.meloxwatch.data.backup.withBackupMutation
import io.github.onlinemo.meloxwatch.data.backup.withBackupWorkerMutation
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Root 为应用创建一个实例，注入现有全局物理 I/O 与同一字体所有者的计量。
 * 所有磁盘/解码工作 main-safe、无等待队列；取消后物理 worker 仍持有名额直到真正收尾。
 * 目录提交点之后取消可能先于结果交付，调用方须 read() 查真实状态，不能重放或假报回滚。
 */
class LocalThemeRepository private constructor(
    private val directoryProvider: () -> File,
    private val io: LocalProviderIo,
    private val opener: ThemePackageOpener,
    private val fontBudget: ThemeFontStorageBudget,
    private val validator: ThemeAssetValidator,
    private val availableBytes: (File) -> Long,
    private val faults: ThemeStorageFaults,
    private val bitmaps: ThemeBitmapOwner,
    private val sharedBudget: SharedFontThemeStorageBudget?,
    private val backupBarrier: BackupMutationBarrier?,
) : ThemeSelectionStore {
    constructor(context: Context, io: LocalProviderIo, fontBudget: ThemeFontStorageBudget, backupBarrier: BackupMutationBarrier? = null) : this(
        directoryProvider(context), io, AndroidThemePackageSource(context.applicationContext.contentResolver),
        fontBudget, ThemeAssetValidator(), File::getUsableSpace, ThemeStorageFaults {}, ThemeBitmapOwner(), null, backupBarrier,
    )

    constructor(
        context: Context,
        io: LocalProviderIo,
        sharedBudget: SharedFontThemeStorageBudget,
        bitmaps: ThemeBitmapOwner,
        backupBarrier: BackupMutationBarrier? = null,
    ) : this(
        directoryProvider(context), io, AndroidThemePackageSource(context.applicationContext.contentResolver),
        sharedBudget.themeBudget, ThemeAssetValidator(), sharedBudget::availableBytesForThemesOnWorker,
        ThemeStorageFaults {}, bitmaps, sharedBudget, backupBarrier,
    )

    internal constructor(
        directory: File,
        io: LocalProviderIo,
        opener: ThemePackageOpener,
        fontBudget: ThemeFontStorageBudget,
        validator: ThemeAssetValidator = ThemeAssetValidator(),
        availableBytes: (File) -> Long = File::getUsableSpace,
        faults: ThemeStorageFaults = ThemeStorageFaults {},
        bitmaps: ThemeBitmapOwner = ThemeBitmapOwner(),
        sharedBudget: SharedFontThemeStorageBudget? = null,
        backupBarrier: BackupMutationBarrier? = null,
    ) : this({ directory }, io, opener, fontBudget, validator, availableBytes, faults, bitmaps, sharedBudget, backupBarrier)

    init { require(sharedBudget == null || sharedBudget.uses(io)) { "Theme storage must use the shared physical channel" } }

    private val occupied = AtomicBoolean(false)
    private val previewLock = Any()
    private var heldPreview: PreviewRecord? = null
    private val invalidInstalled = HashMap<ThemeRef, ThemeIssue>()
    private val imageLock = Any()
    private var imageLeases = 0
    private val mutableState = MutableStateFlow(unknownSnapshot())
    override val state: StateFlow<ThemeSnapshot> = mutableState.asStateFlow()
    private val mutablePreview = MutableStateFlow<ThemePreview?>(null)
    val preview: StateFlow<ThemePreview?> = mutablePreview.asStateFlow()

    /** The actual directory transaction and every installed resource are checked, not cached UI. */
    internal suspend fun readCatalogForBackup(): ThemeCatalog {
        requireBackupOwner()
        return operation { store, signal ->
            val loaded = load(store)
            requireHealthy(loaded)
            store.requireMutationAllowed()
            cleanAbandonedPreviews(store)
            requireNoPreview()
            if (store.receivedIds().isNotEmpty()) themeFail(ThemeIssue.BUSY)
            for (installation in loaded.catalog.catalog.installed) {
                validateAssets(store.installationDirectory(installation.ref.installationId), store.manifest(installation.ref, loaded.catalog.catalog), signal)
            }
            publish(snapshot(store, loaded.catalog))
            loaded.catalog.catalog.copy(installed = loaded.catalog.catalog.installed.toList())
        }
    }

    /** Changes choices through the existing native commit; originals remain installed for rollback. */
    internal suspend fun setChoicesForBackup(expectedRevision: Long, selected: ThemeChoice, previous: ThemeChoice, operationId: String): ThemeCatalog {
        requireBackupOwner()
        if (!isThemeOperationId(operationId)) themeFail(ThemeIssue.INVALID_INPUT)
        return operation { store, signal ->
            val loaded = load(store)
            requireHealthy(loaded)
            store.requireMutationAllowed()
            requireNoPreview()
            val before = loaded.catalog
            if (before.catalog.revision != expectedRevision) themeFail(ThemeIssue.REVISION_CONFLICT)
            for (choice in listOf(selected, previous).distinct()) {
                val manifest = definition(store, before.catalog, choice)
                if (choice is ThemeChoice.Installed) validateAssets(store.installationDirectory(choice.ref.installationId), manifest, signal)
            }
            if (before.catalog.selected == selected && before.catalog.previous == previous) return@operation before.catalog
            val digest = operationDigest(listOf("backup-choices", expectedRevision.toString(), selected.toString(), previous.toString()))
            val replacement = before.catalog.copy(revision = nextRevision(before.catalog.revision), selected = selected, previous = previous,
                lastOperationId = operationId, lastOperationDigest = digest)
            val commit = try { store.commit(before, replacement, operationId, null, null, null, signal::throwIfCanceled) }
            catch (failure: Throwable) { refreshAfterFailure(store, before, failure); throw failure }
            publish(snapshot(store, commit.stored, if (commit.cleanupRequired) ThemeIssue.CLEANUP_REQUIRED else null))
            if (commit.cleanupRequired) themeFail(ThemeIssue.CLEANUP_REQUIRED)
            commit.stored.catalog
        }
    }

    internal suspend fun exportForBackup(ref: ThemeRef, output: BackupResourceWriter) {
        requireBackupOwner()
        operation { store, signal ->
            val loaded = load(store)
            requireHealthy(loaded)
            val manifest = store.manifest(ref, loaded.catalog.catalog)
            val payload = store.installationDirectory(ref.installationId)
            validateAssets(payload, manifest, signal)
            val manifestFile = themeContainedFile(payload, ThemeZipIndex.MANIFEST_NAME)
            val manifestBytes = readBoundedThemeFile(manifestFile, ThemeLimits.MANIFEST_BYTES, ThemeIssue.INTEGRITY_FAILED, signal::throwIfCanceled)
            if (ThemeStorageRecords.digest(manifestBytes) != ref.manifestSha256) themeFail(ThemeIssue.INTEGRITY_FAILED)
            ZipOutputStream(output.output, Charsets.UTF_8).use { zip ->
                fun entry(name: String, file: File, expectedBytes: Long, expectedSha256: String) {
                    signal.throwIfCanceled()
                    val identity = BackupFileIdentity.capture(file)
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    val prefix = (expectedBytes + ThemeLimits.EXPANSION_RATIO - 1) / ThemeLimits.EXPANSION_RATIO
                    var copied = 0L
                    zip.setLevel(Deflater.NO_COMPRESSION)
                    zip.putNextEntry(ZipEntry(name).apply { time = 315619200000L })
                    FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                        if (channel.size() != expectedBytes) themeFail(ThemeIssue.INTEGRITY_FAILED)
                        val bytes = ByteBuffer.allocate(ThemeLimits.IO_BYTES)
                        while (true) {
                            signal.throwIfCanceled()
                            bytes.clear()
                            val count = channel.read(bytes)
                            if (count < 0) break
                            if (count == 0 || count.toLong() > expectedBytes - copied) themeFail(ThemeIssue.INTEGRITY_FAILED)
                            digest.update(bytes.array(), 0, count)
                            val plain = minOf(count.toLong(), maxOf(0L, prefix - copied)).toInt()
                            if (plain > 0) zip.write(bytes.array(), 0, plain)
                            if (count > plain) { zip.setLevel(Deflater.BEST_SPEED); zip.write(bytes.array(), plain, count - plain) }
                            copied += count
                        }
                        if (channel.size() != expectedBytes) themeFail(ThemeIssue.INTEGRITY_FAILED)
                    }
                    identity.requireSame(file)
                    if (copied != expectedBytes || themeHex(digest.digest()) != expectedSha256) themeFail(ThemeIssue.INTEGRITY_FAILED)
                    zip.closeEntry()
                }
                entry(ThemeZipIndex.MANIFEST_NAME, manifestFile, manifestBytes.size.toLong(), ref.manifestSha256)
                for (resource in manifest.resources.sortedBy { it.path }) entry(resource.path, themeContainedFile(payload, resource.path), resource.byteCount, resource.sha256)
            }
            output.verifyWrittenFile { file ->
                val archive = ThemeZipArchive(file)
                val index = archive.inspect(signal::throwIfCanceled)
                if (!archive.readManifest(index, signal::throwIfCanceled).contentEquals(manifestBytes)) themeFail(ThemeIssue.INTEGRITY_FAILED)
                archive.validateDeclarations(index, manifest)
            }
        }
    }

    /** Validation extracts only into the backup owner's new isolated generation. */
    internal suspend fun validateForBackup(file: File, payload: BackupPrivateDirectory): ValidatedBackupTheme {
        requireBackupOwner()
        return operation { _, signal ->
            val archive = ThemeZipArchive(file)
            val index = archive.inspect(signal::throwIfCanceled)
            val bytes = archive.readManifest(index, signal::throwIfCanceled)
            val manifest = ThemeManifestReader.read(bytes, signal::throwIfCanceled)
            archive.validateDeclarations(index, manifest)
            var written = 0L
            archive.extract(index, manifest, payload.root, { next ->
                signal.throwIfCanceled()
                if (next < 0L || next > ThemeLimits.UNPACKED_BYTES - written) themeFail(ThemeIssue.EXPANDED_TOO_LARGE)
                val available = Files.getFileStore(payload.root.toPath()).usableSpace
                if (available < ThemeLimits.FREE_RESERVE_BYTES || next > available - ThemeLimits.FREE_RESERVE_BYTES) themeFail(ThemeIssue.STORAGE_FULL)
                written += next
            }, signal::throwIfCanceled)
            ValidatedBackupTheme(manifest, ThemeStorageRecords.digest(bytes), validateAssets(payload.root, manifest, signal))
        }
    }

    private suspend fun requireBackupOwner() {
        val barrier = backupBarrier ?: throw BackupException(BackupIssue.INVALID_INPUT)
        barrier.requireExclusiveOwner()
    }

    /** Memory-only cancellation; the next physical operation cleans this unaccepted stage. */
    override fun retirePreview(token: String) {
        synchronized(previewLock) {
            val record = heldPreview
            if (record != null && record.preview.token == token) {
                record.retired.set(true)
                publishPreview(null)
            }
        }
    }

    /** 只读内存中的精确预览身份；已退休的 token 不能重新获得应用权限。 */
    internal fun receivedPreviewId(token: String): String? = synchronized(previewLock) {
        heldPreview?.takeIf { it.preview.token == token && !it.retired.get() }?.receivedId
    }

    override suspend fun read(): ThemeSnapshot = try {
        operation { store, signal ->
            signal.throwIfCanceled()
            val loaded = load(store)
            var issue = loaded.issue
            if (issue == null) try {
                cleanAbandonedPreviews(store)
            } catch (error: ThemeException) {
                issue = error.issue
            }
            publish(snapshot(store, loaded.catalog, issue, signal))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: ThemeException) {
        if (error.issue == ThemeIssue.BUSY) throw error
        publishFailure(unknownSnapshot(error.issue, mutableState.value.selected, mutableState.value.previous))
    }

    /** 原件只读；完整校验并关闭输入后才发布一份可确认预览，当前选择保持不变。 */
    override suspend fun prepareImport(request: ThemeImportRequest): ThemePreview = operation { store, signal ->
        if (!isThemeOperationId(request.operationId)) themeFail(ThemeIssue.INVALID_INPUT)
        val loaded = mutationBase(store)
        requireNoPreview()
        val token = UUID.randomUUID().toString()
        store.beginStage(token)
        var delivered = false
        var failure: Throwable? = null
        try {
            val copied = copyInput(request.uri, store.stageArchive(token), store, signal)
            val archive = ThemeZipArchive(store.stageArchive(token))
            val index = archive.inspect(signal::throwIfCanceled)
            val bytes = archive.readManifest(index, signal::throwIfCanceled)
            val manifest = ThemeManifestReader.read(bytes, signal::throwIfCanceled)
            archive.validateDeclarations(index, manifest)
            requireFontRoom(store, loaded.catalog.catalog, manifest, signal, additional = true, archiveBytes = copied.bytes, stageId = token)
            val owned = store.measuredOwnedBytes()
            var written = 0L
            archive.extract(index, manifest, store.stagePayload(token), { next ->
                store.requireRoom(owned, written, next)
                written += next
            }, signal::throwIfCanceled)
            val capabilities = validateAssets(store.stagePayload(token), manifest, signal)
            signal.throwIfCanceled()
            val result = ThemePreview(
                token, manifest, loaded.catalog.catalog.revision, copied.sha256, null,
                loaded.catalog.catalog.installed.filter { it.ref.themeId == manifest.id }.map { it.ref }, capabilities,
            )
            val record = PreviewRecord(result, token, ThemeStorageRecords.digest(bytes), copied.bytes)
            hold(record)
            delivered = true
            result
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            if (!delivered) try {
                store.discardStage(token)
            } catch (closing: Throwable) {
                if (failure == null) throw closing
                if (failure !== closing) failure.addSuppressed(closing)
                try {
                    publish(snapshot(store, loaded.catalog, ThemeIssue.CLEANUP_REQUIRED))
                } catch (reporting: Throwable) {
                    if (failure !== reporting) failure.addSuppressed(reporting)
                }
            }
        }
    }

    override suspend fun previewChoice(choice: ThemeChoice): ThemePreview = operation { store, signal ->
        val loaded = mutationBase(store)
        requireNoPreview()
        val manifest: ThemeManifest
        val capabilities: Map<LyricsFontRole, FontWeightCapability>
        try {
            manifest = definition(store, loaded.catalog.catalog, choice)
            capabilities = if (choice is ThemeChoice.Installed) {
                val validated = validateAssets(store.installationDirectory(choice.ref.installationId), manifest, signal)
                invalidInstalled.remove(choice.ref)
                validated
            } else emptyMap()
        } catch (error: ThemeException) {
            invalidate(store, loaded.catalog, choice, error.issue)
            throw error
        }
        signal.throwIfCanceled()
        val result = ThemePreview(
            UUID.randomUUID().toString(), manifest, loaded.catalog.catalog.revision, null, choice, emptyList(), capabilities,
        )
        hold(PreviewRecord(result, null, null))
        result
    }

    /** 取消/退出只删除本预览的暂存副本；清理失败保留精确 token，供同一操作重试。 */
    suspend fun discardPreview(token: String) = operation { store, signal ->
        signal.throwIfCanceled()
        store.requireMutationAllowed()
        val record = held(token, allowRetired = true)
        record.retired.set(true)
        publishPreview(null)
        if (record.stageId != null && record.receivedId == null) store.discardStage(record.stageId)
        clear(record)
    }

    override suspend fun apply(request: ThemeApplyRequest): ThemeApplyResult = operation { store, signal ->
        validateApply(request)
        val loaded = load(store)
        val digest = applyDigest(request)
        val pending = store.receivedApplyIntent()
        if (pending != null && pending.request != request) themeFail(ThemeIssue.RECOVERY_REQUIRED)
        val already = completed(loaded.catalog.catalog, request.operationId, digest)
        if (already) {
            if (loaded.issue != null && loaded.issue != ThemeIssue.CLEANUP_REQUIRED) themeFail(loaded.issue)
            val actual = publish(snapshot(store, loaded.catalog, loaded.issue))
            return@operation ThemeApplyResult(actual, (actual.selected as? ThemeChoice.Installed)?.ref, loaded.issue != null)
        }
        requireHealthy(loaded)
        cleanAbandonedPreviews(store)
        val record = held(request.previewToken)
        if (record.receivedId == null) store.requireMutationAllowed()
        else store.requireMutationAllowed(TransferredThemeApplyIntent(store.receivedRecord(record.receivedId), request))
        val before = loaded.catalog
        if (request.expectedRevision != before.catalog.revision || record.preview.expectedRevision != request.expectedRevision) {
            themeFail(ThemeIssue.REVISION_CONFLICT)
        }
        val manifest = record.preview.manifest
        if (manifest.fonts.isNotEmpty() &&
            request.fontLicenseAcknowledgmentVersion != LyricsFontLimits.LICENSE_ACKNOWLEDGMENT_VERSION) {
            themeFail(ThemeIssue.FONT_LICENSE_ACKNOWLEDGMENT_REQUIRED)
        }
        var installation: ThemeInstallation? = null
        var installId: String? = null
        val choice = if (record.stageId != null) {
            verifyStage(store, record, signal)
            requireFontRoom(store, before.catalog, manifest, signal, additional = true, archiveBytes = record.archiveBytes, stageId = record.stageId)
            val existing = before.catalog.installed.firstOrNull {
                it.packageSha256 == record.preview.packageSha256 && it.ref.manifestSha256 == record.manifestSha256
            }
            if (existing != null) {
                if (request.replaces != null && request.replaces != existing.ref) themeFail(ThemeIssue.THEME_ID_CONFLICT)
                ThemeChoice.Installed(existing.ref)
            } else {
                validateReplacement(before.catalog, record, request.replaces)
                if (before.catalog.installed.size == ThemeLimits.INSTALLED_THEMES) themeFail(ThemeIssue.STORAGE_QUOTA_EXCEEDED)
                installId = record.stageId
                val ref = ThemeRef(record.stageId, manifest.id, checkNotNull(record.manifestSha256))
                installation = ThemeInstallation(
                    ref, manifest.displayName, checkNotNull(record.preview.packageSha256),
                    manifest.resources.sumOf { it.byteCount }, fontBytes(manifest),
                )
                ThemeChoice.Installed(ref)
            }
        } else {
            val selected = checkNotNull(record.preview.installedChoice)
            if (selected is ThemeChoice.Installed) {
                try {
                    val actual = definition(store, before.catalog, selected)
                    validateAssets(store.installationDirectory(selected.ref.installationId), actual, signal)
                    requireFontRoom(store, before.catalog, actual, signal, additional = false)
                    invalidInstalled.remove(selected.ref)
                } catch (error: ThemeException) {
                    invalidate(store, before, selected, error.issue)
                    throw error
                }
            }
            selected
        }
        checkPreview(record, signal)
        val installed = if (installation == null) before.catalog.installed else before.catalog.installed + installation
        val replacement = before.catalog.copy(
            revision = nextRevision(before.catalog.revision), selected = choice, previous = before.catalog.selected,
            installed = installed, lastOperationId = request.operationId, lastOperationDigest = digest,
        )
        val commit = try {
            store.commit(before, replacement, request.operationId, record.stageId, installId, null) {
                checkPreview(record, signal)
            }
        } catch (error: Throwable) {
            refreshAfterFailure(store, before, error)
            throw error
        }
        clear(record)
        if (choice is ThemeChoice.Installed) invalidInstalled.remove(choice.ref)
        val actual = publish(snapshot(store, commit.stored,
            if (commit.cleanupRequired) ThemeIssue.CLEANUP_REQUIRED else null))
        ThemeApplyResult(actual, (choice as? ThemeChoice.Installed)?.ref, commit.cleanupRequired)
    }

    /** retained 必须包含 Root 的保存配置、字体/背景使用、编辑及恢复引用；它不是任意路径集合。 */
    suspend fun remove(
        ref: ThemeRef,
        expectedRevision: Long,
        operationId: String,
        retained: Set<ThemeRef>,
    ): ThemeRemovalResult = operation { store, signal ->
        if (!isThemeOperationId(operationId) || expectedRevision < 0 || retained.size > ThemeLimits.INSTALLED_THEMES) {
            themeFail(ThemeIssue.INVALID_INPUT)
        }
        val retainedCopy = retained.toSet()
        val loaded = load(store)
        store.requireMutationAllowed()
        val digest = removalDigest(ref, expectedRevision, retainedCopy)
        if (completed(loaded.catalog.catalog, operationId, digest)) {
            if (loaded.issue != null && loaded.issue != ThemeIssue.CLEANUP_REQUIRED) themeFail(loaded.issue)
            val actual = publish(snapshot(store, loaded.catalog, loaded.issue))
            val deleted = !Files.exists(store.installationDirectory(ref.installationId).toPath(), LinkOption.NOFOLLOW_LINKS)
            return@operation ThemeRemovalResult(actual, deleted, loaded.issue != null)
        }
        requireHealthy(loaded)
        cleanAbandonedPreviews(store)
        val before = loaded.catalog
        if (before.catalog.revision != expectedRevision) themeFail(ThemeIssue.REVISION_CONFLICT)
        if (before.catalog.installed.none { it.ref == ref }) themeFail(ThemeIssue.THEME_NOT_FOUND)
        val protected = listOf(before.catalog.selected, before.catalog.previous,
            currentPreview()?.preview?.installedChoice).filterIsInstance<ThemeChoice.Installed>().map { it.ref }
        if (ref in protected || ref in retainedCopy) themeFail(ThemeIssue.IN_USE)
        signal.throwIfCanceled()
        val replacement = before.catalog.copy(
            revision = nextRevision(before.catalog.revision), installed = before.catalog.installed.filter { it.ref != ref },
            lastOperationId = operationId, lastOperationDigest = digest,
        )
        val commit = try {
            store.commit(before, replacement, operationId, null, null, ref.installationId, signal::throwIfCanceled)
        } catch (error: Throwable) {
            refreshAfterFailure(store, before, error)
            throw error
        }
        invalidInstalled.remove(ref)
        val actual = publish(snapshot(store, commit.stored,
            if (commit.cleanupRequired) ThemeIssue.CLEANUP_REQUIRED else null))
        val deleted = !Files.exists(store.installationDirectory(ref.installationId).toPath(), LinkOption.NOFOLLOW_LINKS)
        ThemeRemovalResult(actual, deleted, commit.cleanupRequired)
    }

    /** 用于单张预览，不缓存派生背景；Root 渲染接线仍须共用图片缓存和可见性代次。 */
    suspend fun loadPreviewImage(token: String, resourceId: String, targetPx: Int): ThemeImageLease = operation { store, signal ->
        if (targetPx !in 1..ArtworkLimits.OUTPUT_EDGE) themeFail(ThemeIssue.INVALID_INPUT)
        val loaded = load(store)
        requireHealthy(loaded)
        val record = held(token)
        val resource = record.preview.manifest.resource(resourceId) ?: themeFail(ThemeIssue.MISSING_RESOURCE)
        if (resource.kind != ThemeResourceKind.IMAGE) themeFail(ThemeIssue.UNSUPPORTED_RESOURCE)
        val payload = payload(store, record)
        val bytes = try {
            validator.verifiedBytes(themeContainedFile(payload, resource.path), resource, signal)
        } catch (error: ThemeException) {
            invalidate(store, loaded.catalog, record.preview.installedChoice, error.issue)
            throw error
        }
        val icon = resourceId in record.preview.manifest.icons.values
        acquireImage()
        var reservation: ThemeBitmapReservation? = null
        var lease: ThemeImageLease? = null
        var bitmap: android.graphics.Bitmap? = null
        try {
            val reserved = bitmaps.reserve(PlaybackBackgroundLimits.DECODE_BYTES)
            reservation = reserved
            val decoded = validator.decodeImage(bytes, resource.mediaType, targetPx, icon, signal)
            bitmap = decoded
            reserved.resize(decoded.allocationByteCount.toLong())
            val ready = ThemeImageLease(decoded, if (icon) record.preview.manifest.palette.textPrimary else null) {
                reserved.close()
                releaseImage()
            }
            lease = ready
            checkPreview(record, signal)
            ready
        } catch (error: Throwable) {
            if (lease == null) {
                bitmap?.recycle()
                reservation?.close()
                releaseImage()
            } else lease.close()
            if (error is ThemeException) invalidate(store, loaded.catalog, record.preview.installedChoice, error.issue)
            throw error
        }
    }

    /** Application previews use the same four-entry/four-lease owner as the playback surface. */
    suspend fun loadOwnedPreviewImage(token: String, resourceId: String, targetPx: Int): ThemeBitmapLease = operation { store, signal ->
        if (targetPx !in 1..PlaybackBackgroundLimits.DERIVED_EDGE) themeFail(ThemeIssue.INVALID_INPUT)
        val loaded = load(store)
        requireHealthy(loaded)
        val record = held(token)
        val resource = record.preview.manifest.resource(resourceId) ?: themeFail(ThemeIssue.MISSING_RESOURCE)
        if (resource.kind != ThemeResourceKind.IMAGE) themeFail(ThemeIssue.UNSUPPORTED_RESOURCE)
        val bytes = validator.verifiedBytes(themeContainedFile(payload(store, record), resource.path), resource, signal)
        val icon = resourceId in record.preview.manifest.icons.values
        val key = backgroundKey("theme-preview-v1", record.preview.manifest.id,
            record.manifestSha256.orEmpty(), resource.id, resource.sha256, targetPx.toString())
        bitmaps.acquire(key)?.let { cached ->
            try { checkPreview(record, signal)
                return@operation cached }
            catch (error: Throwable) { cached.close()
                throw error }
        }
        val reservation = bitmaps.reserve(PlaybackBackgroundLimits.DECODE_BYTES)
        var bitmap: android.graphics.Bitmap? = null
        var lease: ThemeBitmapLease? = null
        try {
            val decoded = validator.decodeImage(bytes, resource.mediaType, targetPx, icon, signal)
            bitmap = decoded
            checkPreview(record, signal)
            val result = bitmaps.publish(key, decoded, reservation, if (icon) record.preview.manifest.palette.textPrimary else null)
            lease = result
            bitmap = null
            checkPreview(record, signal)
            result
        } catch (error: Throwable) {
            lease?.close()
            throw error
        } finally {
            bitmap?.recycle()
            reservation.close()
        }
    }

    /**
     * 只在物理 worker 和目录锁内让既有字体所有者读取已核验文件；回调不得保存路径/打开句柄、
     * 嵌套 I/O 请求或写选择配置，并必须沿同一 signal 取消。持久接线由同一编辑所有者完成。
     * 字体偏好、两类活动 face、缺字回退以及全局配额/保留仍由 LP-07 的同一所有者集成。
     */
    suspend fun <T> consumePreviewFont(
        token: String,
        role: LyricsFontRole,
        consume: (File, ThemeResource, FontWeightCapability, CancellationSignal) -> T,
    ): T = operation { store, signal ->
        val loaded = load(store)
        requireHealthy(loaded)
        val record = held(token)
        val id = record.preview.manifest.fonts[role] ?: themeFail(ThemeIssue.MISSING_RESOURCE)
        val resource = record.preview.manifest.resource(id) ?: themeFail(ThemeIssue.MISSING_RESOURCE)
        if (resource.kind != ThemeResourceKind.FONT) themeFail(ThemeIssue.UNSUPPORTED_RESOURCE)
        val file = themeContainedFile(payload(store, record), resource.path)
        try {
            validator.verifiedBytes(file, resource, signal)
        } catch (error: ThemeException) {
            invalidate(store, loaded.catalog, record.preview.installedChoice, error.issue)
            throw error
        }
        checkPreview(record, signal)
        val capability = record.preview.fontCapabilities[role] ?: themeFail(ThemeIssue.INVALID_FONT)
        val result = consume(file, resource, capability, signal)
        checkPreview(record, signal)
        result
    }

    /** 字体导入所有者在同一串行修改操作内先计量；不得嵌套调用已占用的物理 I/O。 */
    suspend fun ownedFontBytes(): Long = operation { store, signal ->
        signal.throwIfCanceled()
        val loaded = mutationBase(store)
        val record = currentPreview()
        if (record != null && record.stageId != null) {
            val manifestBytes = store.stageManifestBytes(record.stageId, signal::throwIfCanceled)
            if (ThemeStorageRecords.digest(manifestBytes) != record.manifestSha256) themeFail(ThemeIssue.INTEGRITY_FAILED)
            verifyThemePayloadTree(store.stagePayload(record.stageId), record.preview.manifest, signal::throwIfCanceled)
            val archive = store.stageArchive(record.stageId)
            requireThemeRegularFile(archive)
            val archiveBytes = FileChannel.open(archive.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { it.size() }
            if (archiveBytes != record.archiveBytes) themeFail(ThemeIssue.INTEGRITY_FAILED)
        }
        val total = ThemeFontStorageMeasurement.measureLocked(store, signal)
        signal.throwIfCanceled()
        if (total > LyricsFontLimits.OWNED_BYTES) themeFail(ThemeIssue.FONT_QUOTA_EXCEEDED)
        total
    }

    /** 复用 upload.readOnPhysicalWorker，不创建 provider URI 或第二个物理任务。 */
    internal fun receiveTransferredOnWorker(
        binding: TransferOperationBinding,
        upload: TransferVerifiedUpload,
        input: File,
        signal: CancellationSignal,
        permit: TransferPermit,
        transferQuota: ThemeTransferQuota,
    ): TransferredThemeRecord = backupBarrier.withBackupWorkerMutation({ ThemeException(ThemeIssue.BUSY) }) {
        receiveTransferredActual(binding, upload, input, signal, permit, transferQuota)
    }

    private fun receiveTransferredActual(binding: TransferOperationBinding, upload: TransferVerifiedUpload, input: File,
        signal: CancellationSignal, permit: TransferPermit, transferQuota: ThemeTransferQuota): TransferredThemeRecord {
        if (!occupied.compareAndSet(false, true)) themeFail(ThemeIssue.BUSY)
        try {
            return ownedWorker(signal) { store ->
                val declaration = upload.declaration
                if (!upload.binding.sameOperation(binding) || !upload.sharesPhysicalWorker(io) ||
                    binding.kind != TransferOperationKind.THEME || declaration.kind != TransferUploadKind.THEME ||
                    binding.target != permit.target || binding.peer != permit.peer || permit.capability != binding.kind.capability ||
                    upload.actualBytes !in 1..ThemeLimits.PACKAGE_BYTES ||
                    declaration.declaredBytes != null && declaration.declaredBytes != upload.actualBytes) {
                    themeFail(ThemeIssue.INVALID_INPUT)
                }
                fun checkActive() { signal.throwIfCanceled()
                    permit.checkActive() }
                checkActive()
                val loaded = mutationBase(store)
                requireNoPreview()
                val ids = store.receivedIds()
                if (ids.isNotEmpty()) {
                    if (ids.single() != binding.operationId) themeFail(ThemeIssue.BUSY)
                    val original = store.receivedRecord(binding.operationId)
                    if (!original.matches(binding) || original.packageSha256 != upload.sha256.forLocalStorage() ||
                        original.packageBytes != upload.actualBytes) themeFail(ThemeIssue.OPERATION_CONFLICT)
                    verifyReceived(store, original, signal)
                    checkActive()
                    return@ownedWorker original
                }
                var sealed = false
                var failure: Throwable? = null
                try {
                    store.beginReceived(binding.operationId)
                    requireThemeRegularFile(input)
                    val archiveBytes = upload.actualBytes
                    val archiveSha256 = digestBoundedThemeFile(input, archiveBytes, ThemeLimits.PACKAGE_BYTES, ::checkActive)
                    if (archiveSha256 != upload.sha256.forLocalStorage()) themeFail(ThemeIssue.INTEGRITY_FAILED)
                    checkActive()
                    store.adoptReceivedArchive(binding.operationId, input)
                    transferQuota.adoptOnPhysicalWorker(upload.ticket, binding.operationId, store.stageArchive(binding.operationId), archiveBytes)
                    val archive = ThemeZipArchive(store.stageArchive(binding.operationId))
                    val index = archive.inspect(::checkActive)
                    val manifestBytes = archive.readManifest(index, ::checkActive)
                    val manifest = ThemeManifestReader.read(manifestBytes, ::checkActive)
                    archive.validateDeclarations(index, manifest)
                    requireFontRoom(store, loaded.catalog.catalog, manifest, signal, additional = true,
                        archiveBytes = archiveBytes, stageId = binding.operationId)
                    val owned = store.measuredOwnedBytes()
                    var written = 0L
                    archive.extract(index, manifest, store.stagePayload(binding.operationId), { next ->
                        checkActive()
                        store.requireRoom(owned, written, next)
                        written += next
                    }, ::checkActive)
                    validateAssets(store.stagePayload(binding.operationId), manifest, signal)
                    checkActive()
                    val record = TransferredThemeRecord(TransferLimits.SCHEMA, binding.operationId,
                        binding.target.forLocalDisplay(), binding.peer.forLocalDisplay(), binding.payloadHash.forLocalStorage(),
                        archiveSha256, archiveBytes, ThemeStorageRecords.digest(manifestBytes), manifest.id, manifest.displayName,
                        loaded.catalog.catalog.revision)
                    store.sealReceived(record)
                    sealed = true
                    // 迟到的取消不能发布 Room；恢复保留这份有界副本。
                    record
                } catch (error: Throwable) {
                    failure = error
                    throw error
                } finally {
                    if (!sealed) try { store.discardReceived(binding.operationId) }
                    catch (closing: Throwable) {
                        if (failure == null) throw closing
                        if (failure !== closing) failure.addSuppressed(closing)
                    }
                }
            }
        } finally { occupied.set(false) }
    }

    internal suspend fun previewTransferred(record: TransferredThemeRecord): ThemePreview = operation { store, signal ->
        val loaded = mutationBase(store)
        requireNoPreview()
        val verified = verifyReceived(store, record, signal)
        val manifest = verified.manifest
        signal.throwIfCanceled()
        val result = ThemePreview(UUID.randomUUID().toString(), manifest, loaded.catalog.catalog.revision,
            record.packageSha256, null, loaded.catalog.catalog.installed.filter { it.ref.themeId == manifest.id }.map { it.ref },
            verified.fontCapabilities)
        hold(PreviewRecord(result, record.operationId, record.manifestSha256, record.packageBytes, record.operationId))
        result
    }

    internal suspend fun validateTransferred(record: TransferredThemeRecord) = operation { store, signal ->
        val loaded = load(store)
        requireHealthy(loaded)
        store.requireMutationAllowed()
        verifyReceived(store, record, signal)
        Unit
    }

    internal suspend fun prepareTransferredApply(record: TransferredThemeRecord, request: ThemeApplyRequest): TransferredThemeApplyIntent =
        operation { store, signal ->
            validateApply(request)
            val loaded = load(store)
            requireHealthy(loaded)
            val preview = held(request.previewToken)
            if (preview.receivedId != record.operationId || preview.preview.expectedRevision != request.expectedRevision ||
                loaded.catalog.catalog.revision != request.expectedRevision) themeFail(ThemeIssue.REVISION_CONFLICT)
            verifyReceived(store, record, signal)
            checkPreview(preview, signal)
            val intent = TransferredThemeApplyIntent(record, request)
            store.prepareReceivedApply(intent)
            intent
        }

    internal suspend fun transferredStorage(): TransferredThemeStorage = operation { store, signal ->
        signal.throwIfCanceled()
        // recover() 只协调已保存的文件事务，不产生新的应用批准。
        load(store)
        TransferredThemeStorage(store.receivedIds(), store.receivedApplyIntent())
    }

    internal suspend fun discardTransferred(recordId: String) = operation { store, signal ->
        signal.throwIfCanceled()
        store.requireMutationAllowed()
        currentPreview()?.takeIf { it.receivedId == recordId }?.let(::clear)
        store.discardReceived(recordId)
    }

    internal suspend fun transferredApplyOutcome(intent: TransferredThemeApplyIntent): TransferredThemeApplyOutcome =
        operation { store, signal ->
            val loaded = load(store)
            if (store.receivedApplyIntent() != intent) themeFail(ThemeIssue.RECOVERY_REQUIRED)
            if (loaded.issue != null && loaded.issue != ThemeIssue.CLEANUP_REQUIRED) themeFail(loaded.issue)
            val catalog = loaded.catalog.catalog
            val digest = applyDigest(intent.request)
            if (catalog.lastOperationId == intent.request.operationId) {
                if (catalog.lastOperationDigest != digest || intent.request.expectedRevision == Long.MAX_VALUE ||
                    catalog.revision != intent.request.expectedRevision + 1) themeFail(ThemeIssue.RECOVERY_REQUIRED)
                val selected = catalog.selected as? ThemeChoice.Installed ?: themeFail(ThemeIssue.RECOVERY_REQUIRED)
                val installed = catalog.installed.firstOrNull { it.ref == selected.ref } ?: themeFail(ThemeIssue.RECOVERY_REQUIRED)
                if (installed.ref.themeId != intent.candidate.themeId || installed.ref.manifestSha256 != intent.candidate.manifestSha256 ||
                    installed.packageSha256 != intent.candidate.packageSha256) themeFail(ThemeIssue.RECOVERY_REQUIRED)
                val actual = publish(snapshot(store, loaded.catalog, loaded.issue, signal))
                TransferredThemeApplyOutcome(ThemeApplyResult(actual, selected.ref, loaded.issue != null))
            } else {
                if (catalog.revision != intent.request.expectedRevision) themeFail(ThemeIssue.RECOVERY_REQUIRED)
                verifyReceived(store, intent.candidate, signal)
                TransferredThemeApplyOutcome(null)
            }
        }

    internal suspend fun releaseTransferredApply(intent: TransferredThemeApplyIntent) = operation { store, signal ->
        signal.throwIfCanceled()
        store.clearReceivedApply(intent)
        currentPreview()?.takeIf { it.receivedId == intent.candidate.operationId }?.let(::clear)
        val loaded = load(store)
        publish(snapshot(store, loaded.catalog, loaded.issue, signal))
    }

    private fun verifyReceived(store: ThemeDirectoryStore, record: TransferredThemeRecord, signal: CancellationSignal): VerifiedReceived {
        if (store.receivedRecord(record.operationId) != record) themeFail(ThemeIssue.INTEGRITY_FAILED)
        val bytes = store.stageManifestBytes(record.operationId, signal::throwIfCanceled)
        if (ThemeStorageRecords.digest(bytes) != record.manifestSha256 ||
            digestBoundedThemeFile(store.stageArchive(record.operationId), record.packageBytes, ThemeLimits.PACKAGE_BYTES,
                signal::throwIfCanceled) != record.packageSha256) themeFail(ThemeIssue.INTEGRITY_FAILED)
        val manifest = ThemeManifestReader.read(bytes, signal::throwIfCanceled)
        if (manifest.id != record.themeId || manifest.displayName != record.displayName) themeFail(ThemeIssue.INTEGRITY_FAILED)
        val capabilities = validateAssets(store.stagePayload(record.operationId), manifest, signal)
        return VerifiedReceived(manifest, capabilities)
    }

    private data class VerifiedReceived(
        val manifest: ThemeManifest,
        val fontCapabilities: Map<LyricsFontRole, FontWeightCapability>,
    )

    private fun copyInput(uri: String, destination: File, store: ThemeDirectoryStore, signal: CancellationSignal): CopiedInput {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val owned = store.measuredOwnedBytes()
        val ownedFonts = ThemeFontStorageMeasurement.measureLocked(store, signal, destination.parentFile?.name)
        val otherFonts = fontBudget.otherOwnedBytes() ?: themeFail(ThemeIssue.FONT_BUDGET_UNAVAILABLE)
        if (otherFonts < 0L || otherFonts > LyricsFontLimits.OWNED_BYTES - ownedFonts) themeFail(ThemeIssue.FONT_QUOTA_EXCEEDED)
        var written = 0L
        val opened = sourceRead { opener.open(uri, signal) }
        // listener 会在取消线程或晚注册线程同步执行；provider 只由此物理 worker 关闭。
        // 不配合取消的 read/close 结束前仍持有原名额，取消只使结果失效。
        var failure: Throwable? = null
        try {
            if (opened.declaredBytes != null && opened.declaredBytes !in 1..ThemeLimits.PACKAGE_BYTES) {
                themeFail(if (opened.declaredBytes > ThemeLimits.PACKAGE_BYTES) ThemeIssue.PACKAGE_TOO_LARGE else ThemeIssue.INVALID_INPUT)
            }
            FileChannel.open(destination.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS).use { output ->
                val bytes = ByteArray(ThemeLimits.IO_BYTES)
                while (true) {
                    signal.throwIfCanceled()
                    val count = sourceRead { opened.input.read(bytes) }
                    signal.throwIfCanceled()
                    if (count < 0) break
                    if (count == 0) themeFail(ThemeIssue.SOURCE_UNAVAILABLE)
                    if (count > ThemeLimits.PACKAGE_BYTES - written) themeFail(ThemeIssue.PACKAGE_TOO_LARGE)
                    store.requireRoom(owned, written, count.toLong())
                    requireSharedFontRoom(otherFonts + ownedFonts, written, count.toLong(), store.root)
                    val buffer = ByteBuffer.wrap(bytes, 0, count)
                    while (buffer.hasRemaining()) {
                        signal.throwIfCanceled()
                        if (output.write(buffer) <= 0) throw IOException("Theme package write did not advance")
                    }
                    digest.update(bytes, 0, count)
                    written += count
                }
                if (written == 0L || opened.declaredBytes != null && written != opened.declaredBytes) {
                    themeFail(ThemeIssue.INTEGRITY_FAILED)
                }
                signal.throwIfCanceled()
                output.force(true)
            }
        } catch (error: Throwable) {
            val outgoing = if (error is IOException || error is SecurityException) ThemeException(ThemeIssue.SAVE_FAILED, error) else error
            failure = outgoing
            throw outgoing
        } finally {
            try {
                opened.close()
            } catch (closing: Throwable) {
                if (failure == null) throw ThemeException(
                    if (closing is OutOfMemoryError) ThemeIssue.LOW_MEMORY else ThemeIssue.INPUT_CLOSE_FAILED, closing,
                )
                if (failure !== closing) failure.addSuppressed(closing)
            }
        }
        return CopiedInput(themeHex(digest.digest()), written)
    }

    private fun verifyStage(store: ThemeDirectoryStore, record: PreviewRecord, signal: CancellationSignal) {
        val stage = checkNotNull(record.stageId)
        val bytes = store.stageManifestBytes(stage, signal::throwIfCanceled)
        if (ThemeStorageRecords.digest(bytes) != record.manifestSha256 ||
            digestBoundedThemeFile(store.stageArchive(stage), record.archiveBytes, ThemeLimits.PACKAGE_BYTES,
                signal::throwIfCanceled) != record.preview.packageSha256) {
            themeFail(ThemeIssue.INTEGRITY_FAILED)
        }
        validateAssets(store.stagePayload(stage), record.preview.manifest, signal)
        checkPreview(record, signal)
    }

    private fun requireFontRoom(
        store: ThemeDirectoryStore,
        catalog: ThemeCatalog,
        manifest: ThemeManifest,
        signal: CancellationSignal,
        additional: Boolean,
        archiveBytes: Long = 0L,
        stageId: String? = null,
    ) {
        if (archiveBytes !in 0..ThemeLimits.PACKAGE_BYTES) themeFail(ThemeIssue.INTEGRITY_FAILED)
        // 输入包也可能保留一份字体，因此以整个包的字节量保守计入32 MiB，不能仅统计解包副本。
        val candidate = if (additional) fontBytes(manifest) + archiveBytes else 0L
        if (manifest.fonts.isEmpty()) return
        val other = fontBudget.otherOwnedBytes() ?: themeFail(ThemeIssue.FONT_BUDGET_UNAVAILABLE)
        val existing = ThemeFontStorageMeasurement.measureLocked(store, signal, stageId)
        if (other < 0 || other > LyricsFontLimits.OWNED_BYTES - existing) themeFail(ThemeIssue.FONT_QUOTA_EXCEEDED)
        requireSharedFontRoom(other + existing, 0, candidate, store.root)
    }

    /** 计量前先核真实目录集合和文件长度；损坏目录中的未声明副本不能被当作零字节。 */
    private fun requireSharedFontRoom(owned: Long, written: Long, next: Long, root: File) {
        try { FontSpacePolicy.requireRoom(owned, written, next, availableBytes(root)) }
        catch (error: LyricsFontException) {
            throw ThemeException(if (error.issue == LyricsFontIssue.STORAGE_FULL) ThemeIssue.STORAGE_FULL
                else ThemeIssue.FONT_QUOTA_EXCEEDED, error)
        }
    }

    private fun validateAssets(payload: File, manifest: ThemeManifest, signal: CancellationSignal): Map<LyricsFontRole, FontWeightCapability> {
        // Validation is sequential: two font rasters or one sampled icon and its pixel array.
        val reservation = bitmaps.reserve(VALIDATION_BYTES)
        try { return validator.validate(payload, manifest, signal) }
        finally { reservation.close() }
    }

    private fun validateReplacement(catalog: ThemeCatalog, record: PreviewRecord, replaces: ThemeRef?) {
        val matching = catalog.installed.filter { it.ref.themeId == record.preview.manifest.id }
        if (matching.isEmpty()) {
            if (replaces != null) themeFail(ThemeIssue.THEME_ID_CONFLICT)
        } else if (replaces == null || matching.none { it.ref == replaces } || replaces !in record.preview.sameIdInstallations) {
            themeFail(ThemeIssue.THEME_ID_CONFLICT)
        }
    }

    private fun mutationBase(store: ThemeDirectoryStore): Loaded {
        val loaded = load(store)
        requireHealthy(loaded)
        store.requireMutationAllowed()
        cleanAbandonedPreviews(store)
        publish(snapshot(store, loaded.catalog))
        return loaded
    }

    private fun load(store: ThemeDirectoryStore): Loaded {
        val issue = store.recover()
        val catalog = if (issue == ThemeIssue.RECOVERY_REQUIRED) store.beforeCatalog() ?: store.loadCatalog() else store.loadCatalog()
        return Loaded(catalog, issue)
    }

    private fun requireHealthy(loaded: Loaded) {
        if (loaded.issue != null) themeFail(loaded.issue)
    }

    private fun <T> sourceRead(action: () -> T): T = try {
        action()
    } catch (error: IOException) {
        throw ThemeException(ThemeIssue.SOURCE_UNAVAILABLE, error)
    } catch (error: SecurityException) {
        throw ThemeException(ThemeIssue.PERMISSION_DENIED, error)
    } catch (error: IllegalArgumentException) {
        throw ThemeException(ThemeIssue.INVALID_INPUT, error)
    }

    private fun refreshAfterFailure(store: ThemeDirectoryStore, fallback: ThemeStoredCatalog, error: Throwable) {
        try {
            val pending = store.pendingIssue()
            val actual = if (pending == ThemeIssue.RECOVERY_REQUIRED) store.beforeCatalog() ?: fallback else store.loadCatalog()
            val issue = pending ?: when (error) {
                is ThemeException -> error.issue
                is IOException -> ThemeIssue.SAVE_FAILED
                is OutOfMemoryError -> ThemeIssue.LOW_MEMORY
                else -> null
            }
            publish(snapshot(store, actual, issue))
        } catch (reporting: Throwable) {
            if (reporting !== error) error.addSuppressed(reporting)
            try {
                publishFailure(unknownSnapshot(ThemeIssue.RECOVERY_REQUIRED, fallback.catalog.selected, fallback.catalog.previous))
            } catch (lastFailure: Throwable) {
                if (lastFailure !== error) error.addSuppressed(lastFailure)
            }
        }
    }

    private fun invalidate(store: ThemeDirectoryStore, stored: ThemeStoredCatalog, choice: ThemeChoice?, issue: ThemeIssue) {
        if (choice !is ThemeChoice.Installed || stored.catalog.installed.none { it.ref == choice.ref }) return
        invalidInstalled[choice.ref] = issue
        publish(snapshot(store, stored, issue))
    }

    private fun cleanAbandonedPreviews(store: ThemeDirectoryStore) {
        val record = currentPreview()
        val retained = if (record != null && !record.retired.get() && record.stageId != null) setOf(record.stageId) else emptySet()
        store.cleanupOrphanStages(retained)
        if (record != null && record.retired.get()) clear(record)
    }

    private fun snapshot(
        store: ThemeDirectoryStore,
        stored: ThemeStoredCatalog,
        issue: ThemeIssue? = null,
        validationSignal: CancellationSignal? = null,
    ): ThemeSnapshot {
        val catalog = stored.catalog
        invalidInstalled.keys.retainAll(catalog.installed.map { it.ref }.toSet())
        var effective = ThemeBuiltins.defaultChoice
        var manifest = ThemeBuiltins.manifest(BuiltinTheme.DARK)
        var failure = issue
        for (choice in listOf(catalog.selected, catalog.previous, ThemeBuiltins.defaultChoice).distinct()) {
            try {
                if (validationSignal == null && choice is ThemeChoice.Installed && choice.ref in invalidInstalled) {
                    themeFail(invalidInstalled.getValue(choice.ref))
                }
                val candidate = definition(store, catalog, choice)
                if (choice is ThemeChoice.Installed && validationSignal != null) {
                    validateAssets(store.installationDirectory(choice.ref.installationId), candidate, validationSignal)
                    invalidInstalled.remove(choice.ref)
                }
                effective = choice
                manifest = candidate
                break
            } catch (error: ThemeException) {
                if (choice is ThemeChoice.Installed) invalidInstalled[choice.ref] = error.issue
                if (failure == null) failure = error.issue
            }
        }
        val summaries = catalog.installed.map {
            InstalledThemeSummary(it.ref, it.displayName, it.packageSha256, it.resourceBytes)
        }
        return ThemeSnapshot(catalog.revision, catalog.selected, catalog.previous, effective, summaries, failure, manifest)
    }

    private fun definition(store: ThemeDirectoryStore, catalog: ThemeCatalog, choice: ThemeChoice): ThemeManifest = when (choice) {
        is ThemeChoice.Builtin -> ThemeBuiltins.manifest(choice.theme)
        is ThemeChoice.Installed -> store.manifest(choice.ref, catalog)
    }

    private fun payload(store: ThemeDirectoryStore, record: PreviewRecord): File = if (record.stageId != null) {
        store.stagePayload(record.stageId)
    } else {
        val installed = record.preview.installedChoice as? ThemeChoice.Installed ?: themeFail(ThemeIssue.MISSING_RESOURCE)
        store.installationDirectory(installed.ref.installationId)
    }

    private fun publish(snapshot: ThemeSnapshot): ThemeSnapshot {
        backupBarrier.publishForBackup(this) { mutableState.value = snapshot }
        return snapshot
    }
    private fun publishFailure(snapshot: ThemeSnapshot): ThemeSnapshot {
        backupBarrier.publishFailureForBackup(this) { mutableState.value = snapshot }
        return snapshot
    }
    private val previewPublicationOwner = Any()
    private fun publishPreview(value: ThemePreview?) { backupBarrier.publishForBackup(previewPublicationOwner) { mutablePreview.value = value } }

    private fun currentPreview(): PreviewRecord? = synchronized(previewLock) { heldPreview }
    private fun held(token: String, allowRetired: Boolean = false): PreviewRecord {
        val record = currentPreview()
        if (record == null || record.preview.token != token || !allowRetired && record.retired.get()) themeFail(ThemeIssue.PREVIEW_NOT_FOUND)
        return record
    }

    private fun requireNoPreview() {
        if (currentPreview() != null) themeFail(ThemeIssue.BUSY)
    }

    private fun hold(record: PreviewRecord) {
        synchronized(previewLock) {
            if (heldPreview != null) themeFail(ThemeIssue.BUSY)
            heldPreview = record
            publishPreview(record.preview)
        }
    }

    private fun clear(record: PreviewRecord) {
        synchronized(previewLock) {
            if (heldPreview === record) {
                heldPreview = null
                publishPreview(null)
            }
        }
    }

    private fun checkPreview(record: PreviewRecord, signal: CancellationSignal) {
        signal.throwIfCanceled()
        if (currentPreview() !== record || record.retired.get()) themeFail(ThemeIssue.PREVIEW_NOT_FOUND)
    }

    private fun abandon(value: Any?) {
        if (value is ThemePreview) synchronized(previewLock) {
            val record = heldPreview
            if (record != null && record.preview.token == value.token) {
                record.retired.set(true)
                publishPreview(null)
            }
        }
        if (value is ThemeImageLease) value.close()
        if (value is ThemeBitmapLease) value.close()
    }

    private fun acquireImage() {
        synchronized(imageLock) {
            if (imageLeases == 2) themeFail(ThemeIssue.BUSY)
            imageLeases++
        }
    }

    private fun releaseImage() {
        synchronized(imageLock) { imageLeases-- }
    }

    private suspend fun <T> operation(action: (ThemeDirectoryStore, CancellationSignal) -> T): T =
        backupBarrier.withBackupMutation({ ThemeException(ThemeIssue.BUSY) }) { operationActual(action) }

    private suspend fun <T> operationActual(action: (ThemeDirectoryStore, CancellationSignal) -> T): T {
        if (!occupied.compareAndSet(false, true)) themeFail(ThemeIssue.BUSY)
        val admitted = AtomicBoolean(false)
        try {
            return io.read(
                admitted = { admitted.set(true) },
                abandoned = ::abandon,
            ) { signal ->
                try {
                    ownedWorker(signal) { action(it, signal) }
                } catch (error: IOException) {
                    throw ThemeException(ThemeIssue.STORAGE_UNAVAILABLE, error)
                } catch (error: SecurityException) {
                    throw ThemeException(ThemeIssue.STORAGE_UNAVAILABLE, error)
                } catch (error: OutOfMemoryError) {
                    throw ThemeException(ThemeIssue.LOW_MEMORY, error)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ThemeException) {
            throw error
        } catch (error: SourceReadException) {
            val issue = when (error.issue) {
                SourceReadIssue.BUSY -> ThemeIssue.BUSY
                SourceReadIssue.PERMISSION_DENIED, SourceReadIssue.PERMISSION_REVOKED -> ThemeIssue.PERMISSION_DENIED
                SourceReadIssue.INVALID_SOURCE -> ThemeIssue.INVALID_INPUT
                SourceReadIssue.METADATA_UNREADABLE -> if (error.cause is OutOfMemoryError) ThemeIssue.LOW_MEMORY else ThemeIssue.SOURCE_UNAVAILABLE
                else -> ThemeIssue.SOURCE_UNAVAILABLE
            }
            throw ThemeException(issue, error)
        } catch (error: IOException) {
            throw ThemeException(ThemeIssue.STORAGE_UNAVAILABLE, error)
        } catch (error: SecurityException) {
            throw ThemeException(ThemeIssue.PERMISSION_DENIED, error)
        } catch (error: OutOfMemoryError) {
            throw ThemeException(ThemeIssue.LOW_MEMORY, error)
        } finally {
            try { if (admitted.get()) withContext(NonCancellable) { io.awaitIdle() } }
            finally { occupied.set(false) }
        }
    }

    private fun <T> ownedWorker(signal: CancellationSignal, action: (ThemeDirectoryStore) -> T): T {
        val store = ThemeDirectoryStore(directoryProvider(), availableBytes, faults)
        val shared = sharedBudget
        return if (shared == null) store.exclusively { action(store) }
        else shared.themesOnWorker(signal) { store.exclusively { action(store) } }
    }

    private fun completed(catalog: ThemeCatalog, operationId: String, digest: String): Boolean {
        if (catalog.lastOperationId != operationId) return false
        if (catalog.lastOperationDigest != digest) themeFail(ThemeIssue.OPERATION_CONFLICT)
        return true
    }

    private fun validateApply(request: ThemeApplyRequest) {
        if (!isThemeOperationId(request.operationId) || !isThemeOperationId(request.previewToken) || request.expectedRevision < 0) {
            themeFail(ThemeIssue.INVALID_INPUT)
        }
    }

    private fun applyDigest(request: ThemeApplyRequest): String = operationDigest(listOf(
        "apply", request.previewToken, request.expectedRevision.toString(), request.replaces?.installationId.orEmpty(),
        request.replaces?.themeId.orEmpty(), request.replaces?.manifestSha256.orEmpty(),
        request.fontLicenseAcknowledgmentVersion?.toString().orEmpty(),
    ))

    private fun removalDigest(ref: ThemeRef, revision: Long, retained: Set<ThemeRef>): String = operationDigest(
        listOf("remove", ref.installationId, ref.themeId, ref.manifestSha256, revision.toString()) +
            retained.sortedBy { it.installationId }.flatMap { listOf(it.installationId, it.themeId, it.manifestSha256) },
    )

    private fun operationDigest(parts: List<String>): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            for (part in parts) output.writeUTF(part)
        }
        return ThemeStorageRecords.digest(bytes.toByteArray())
    }

    private fun nextRevision(revision: Long): Long {
        if (revision == Long.MAX_VALUE) themeFail(ThemeIssue.REVISION_CONFLICT)
        return revision + 1
    }

    private fun fontBytes(manifest: ThemeManifest): Long = manifest.resources.filter { it.kind == ThemeResourceKind.FONT }.sumOf { it.byteCount }

    private data class Loaded(val catalog: ThemeStoredCatalog, val issue: ThemeIssue?)
    private data class CopiedInput(val sha256: String, val bytes: Long)
    private class PreviewRecord(
        val preview: ThemePreview,
        val stageId: String?,
        val manifestSha256: String?,
        val archiveBytes: Long = 0L,
        val receivedId: String? = null,
    ) {
        val retired = AtomicBoolean(false)
    }

    companion object {
        private const val VALIDATION_BYTES = PlaybackBackgroundLimits.DECODE_BYTES + 4L * ThemeLimits.ICON_EDGE * ThemeLimits.ICON_EDGE

        internal fun directoryProvider(context: Context): () -> File {
            val application = context.applicationContext
            return { File(application.noBackupFilesDir.canonicalFile, "themes-v1") }
        }

        private fun unknownSnapshot(
            issue: ThemeIssue? = null,
            selected: ThemeChoice? = null,
            previous: ThemeChoice? = null,
        ): ThemeSnapshot = ThemeSnapshot(null, selected, previous, ThemeBuiltins.defaultChoice, emptyList(), issue)
    }
}

internal class ValidatedBackupTheme(val manifest: ThemeManifest, val manifestSha256: String,
    val fontCapabilities: Map<LyricsFontRole, FontWeightCapability>) {
    override fun toString(): String = "ValidatedBackupTheme"
}
