package io.github.onlinemo.meloxwatch.data.themes

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import android.os.OperationCanceledException
import kotlinx.coroutines.CancellationException

internal enum class ThemeStoragePoint {
    BEFORE_BACKUP_WRITE, AFTER_BACKUP_WRITE,
    BEFORE_PLAN_WRITE, AFTER_PLAN_WRITE,
    BEFORE_INSTALL_MOVE, AFTER_INSTALL_MOVE,
    BEFORE_CATALOG_MOVE, AFTER_CATALOG_MOVE,
    BEFORE_COMMIT_MARKER, AFTER_COMMIT_MARKER,
    BEFORE_ROLLBACK_MOVE, AFTER_ROLLBACK_MOVE,
    BEFORE_DELETE, AFTER_DELETE, BEFORE_STAGE_DELETE, AFTER_STAGE_DELETE,
    BEFORE_JOURNAL_CLEANUP, AFTER_JOURNAL_RETIRE,
}

internal fun interface ThemeStorageFaults {
    fun at(point: ThemeStoragePoint)
}

internal data class ThemeStoredCatalog(val catalog: ThemeCatalog, val bytes: ByteArray)
internal data class ThemeStoreCommit(val stored: ThemeStoredCatalog, val cleanupRequired: Boolean)

/**
 * 私有目录有一个文件锁和一份持久事务。payload 先安装，目录选择最后原子替换；
 * 未完成事务使用真实 before 字节回滚，回滚失败保留日志和旧副本供启动/显式恢复。
 */
internal class ThemeDirectoryStore(
    directory: File,
    private val availableBytes: (File) -> Long = File::getUsableSpace,
    private val faults: ThemeStorageFaults = ThemeStorageFaults {},
) {
    val root: File = directory.absoluteFile

    fun <T> exclusively(action: () -> T): T {
        ensureDirectories()
        val lockFile = rootFile("owner.lock")
        FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                themeFail(ThemeIssue.BUSY)
            } ?: themeFail(ThemeIssue.BUSY)
            var failure: Throwable? = null
            try {
                return action()
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                try {
                    lock.release()
                } catch (closing: Throwable) {
                    if (failure == null) throw closing
                    if (failure !== closing) failure.addSuppressed(closing)
                }
            }
        }
    }

    fun loadCatalog(): ThemeStoredCatalog {
        val file = rootFile(CATALOG)
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            if (children(installedDirectory()).isNotEmpty()) themeFail(ThemeIssue.CORRUPT_STORAGE)
            val bytes = ThemeStorageRecords.encode(ThemeCatalog())
            writeAtomic(file, bytes)
        }
        val bytes = readBounded(file, ThemeLimits.CATALOG_BYTES)
        val catalog = ThemeStorageRecords.decode(bytes)
        val actual = children(installedDirectory())
        val required = catalog.installed.map { it.ref.installationId }.toSet()
        val optionalDelete = committedDeletion(bytes)
        val names = actual.map { it.name }.toSet()
        if (actual.any { !isThemeOperationId(it.name) } || !names.containsAll(required) ||
            names.any { it !in required && it != optionalDelete }) themeFail(ThemeIssue.CORRUPT_STORAGE)
        actual.forEach(::requireDirectory)
        return ThemeStoredCatalog(catalog, bytes)
    }

    private fun committedDeletion(catalogBytes: ByteArray): String? {
        if (!transactionPending() || !Files.exists(transactionFile("committed").toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        val plan = ThemeStorageRecords.decodePlan(readBounded(transactionFile("plan.bin"), ThemeLimits.CATALOG_BYTES))
        verifyCommittedCatalog(plan, catalogBytes)
        return plan.deleteId
    }

    fun transactionPending(): Boolean = Files.exists(transactionDirectory().toPath(), LinkOption.NOFOLLOW_LINKS)

    fun pendingIssue(): ThemeIssue? {
        if (!transactionPending()) return if (Files.exists(retiredTransaction().toPath(), LinkOption.NOFOLLOW_LINKS)) {
            ThemeIssue.CLEANUP_REQUIRED
        } else null
        return try {
            val marker = transactionFile("committed")
            if (!Files.exists(marker.toPath(), LinkOption.NOFOLLOW_LINKS)) ThemeIssue.RECOVERY_REQUIRED else {
                val plan = ThemeStorageRecords.decodePlan(readBounded(transactionFile("plan.bin"), ThemeLimits.CATALOG_BYTES))
                verifyCommittedCatalog(plan)
                ThemeIssue.CLEANUP_REQUIRED
            }
        } catch (_: Exception) {
            ThemeIssue.RECOVERY_REQUIRED
        }
    }

    /** 返回具体未完成状态；仅提交标记和当前 catalog 摘要同时匹配才继续已批准清理。 */
    fun recover(): ThemeIssue? {
        if (Files.exists(retiredTransaction().toPath(), LinkOption.NOFOLLOW_LINKS)) {
            try {
                deleteTree(retiredTransaction())
            } catch (_: Exception) {
                return ThemeIssue.CLEANUP_REQUIRED
            }
        }
        val transaction = transactionDirectory()
        if (!Files.exists(transaction.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        requireDirectory(transaction)
        val planFile = transactionFile("plan.bin")
        if (!Files.exists(planFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            if (Files.exists(transactionFile("committed").toPath(), LinkOption.NOFOLLOW_LINKS) ||
                Files.exists(transactionFile("next.bin").toPath(), LinkOption.NOFOLLOW_LINKS)) {
                themeFail(ThemeIssue.CORRUPT_STORAGE)
            }
            return try {
                deleteTree(transaction)
                null
            } catch (_: Exception) {
                ThemeIssue.CLEANUP_REQUIRED
            }
        }
        val plan = ThemeStorageRecords.decodePlan(readBounded(planFile, ThemeLimits.CATALOG_BYTES))
        val catalogFile = rootFile(CATALOG)
        val current = if (Files.exists(catalogFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            ThemeStorageRecords.digest(readBounded(catalogFile, ThemeLimits.CATALOG_BYTES))
        } else null
        val marker = transactionFile("committed")
        val committed = Files.exists(marker.toPath(), LinkOption.NOFOLLOW_LINKS)
        if (committed && !readBounded(marker, 64).contentEquals(plan.newCatalogSha256.toByteArray(Charsets.US_ASCII))) {
            themeFail(ThemeIssue.CORRUPT_STORAGE)
        }
        if (committed) {
            // marker 是提交边界；与当前目录矛盾时保留现场，不能将已提交事务当作旧目录回滚。
            if (current != plan.newCatalogSha256) themeFail(ThemeIssue.CORRUPT_STORAGE)
            verifyCommittedCatalog(plan)
            return if (cleanupCommitted(plan)) null else ThemeIssue.CLEANUP_REQUIRED
        }
        if (current != plan.oldCatalogSha256 && current != plan.newCatalogSha256) {
            themeFail(ThemeIssue.CORRUPT_STORAGE)
        }
        return try {
            rollback(plan)
            null
        } catch (_: Exception) {
            pendingIssue() ?: ThemeIssue.RECOVERY_REQUIRED
        }
    }

    fun beforeCatalog(): ThemeStoredCatalog? {
        val planFile = transactionFile("plan.bin")
        if (!Files.exists(planFile.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        val plan = ThemeStorageRecords.decodePlan(readBounded(planFile, ThemeLimits.CATALOG_BYTES))
        val before = transactionFile("before.bin")
        val file = if (Files.exists(before.toPath(), LinkOption.NOFOLLOW_LINKS)) before else rootFile(CATALOG)
        val bytes = readBounded(file, ThemeLimits.CATALOG_BYTES)
        if (ThemeStorageRecords.digest(bytes) != plan.oldCatalogSha256) themeFail(ThemeIssue.CORRUPT_STORAGE)
        return ThemeStoredCatalog(ThemeStorageRecords.decode(bytes), bytes)
    }

    fun beginStage(id: String): File {
        val directory = stageDirectory(id)
        if (Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) themeFail(ThemeIssue.OPERATION_CONFLICT)
        Files.createDirectory(directory.toPath())
        try {
            Files.createDirectory(File(directory, PAYLOAD).toPath())
        } catch (error: Throwable) {
            try {
                deleteTree(directory)
            } catch (closing: Throwable) {
                if (error !== closing) error.addSuppressed(closing)
            }
            throw error
        }
        return directory
    }

    fun stageDirectory(id: String): File {
        val staged = identifierChild(stagingDirectory(), id)
        val received = receivedDirectory(id)
        val inStage = Files.exists(staged.toPath(), LinkOption.NOFOLLOW_LINKS)
        val inReceived = Files.exists(received.toPath(), LinkOption.NOFOLLOW_LINKS)
        if (inStage && inReceived) themeFail(ThemeIssue.CORRUPT_STORAGE)
        return if (inReceived) received else staged
    }
    fun stagePayload(id: String): File = safeChild(stageDirectory(id), PAYLOAD)
    fun stageArchive(id: String): File = safeChild(stageDirectory(id), "input.zip")
    fun installationDirectory(id: String): File = identifierChild(installedDirectory(), id)
    fun stageManifestBytes(id: String, checkCancellation: () -> Unit): ByteArray = readBoundedThemeFile(
        themeContainedFile(stagePayload(id), ThemeZipIndex.MANIFEST_NAME), ThemeLimits.MANIFEST_BYTES,
        ThemeIssue.INTEGRITY_FAILED, checkCancellation,
    )

    fun discardStage(id: String) {
        if (isReceived(id)) themeFail(ThemeIssue.IN_USE)
        faults.at(ThemeStoragePoint.BEFORE_STAGE_DELETE)
        deleteTree(stageDirectory(id))
        faults.at(ThemeStoragePoint.AFTER_STAGE_DELETE)
    }

    fun beginReceived(id: String) {
        if (receivedIds().isNotEmpty()) themeFail(ThemeIssue.BUSY)
        val directory = receivedDirectory(id)
        if (Files.exists(identifierChild(stagingDirectory(), id).toPath(), LinkOption.NOFOLLOW_LINKS)) {
            themeFail(ThemeIssue.OPERATION_CONFLICT)
        }
        Files.createDirectory(directory.toPath())
        Files.createDirectory(File(directory, PAYLOAD).toPath())
        syncDirectory(receivedRoot())
    }

    fun isReceived(id: String): Boolean = Files.exists(receivedDirectory(id).toPath(), LinkOption.NOFOLLOW_LINKS)

    /** 输入来自已验证的应用私有上传，不复制另一份压缩包或修改发送端原件。 */
    fun adoptReceivedArchive(id: String, input: File) {
        val output = stageArchive(id)
        requireThemeRegularFile(input)
        if (Files.exists(output.toPath(), LinkOption.NOFOLLOW_LINKS)) themeFail(ThemeIssue.DUPLICATE_PATH)
        FileChannel.open(input.toPath(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
        Files.move(input.toPath(), output.toPath(), StandardCopyOption.ATOMIC_MOVE)
        syncDirectory(input.parentFile ?: themeFail(ThemeIssue.INVALID_PATH))
        syncDirectory(receivedDirectory(id))
    }
    fun receivedDirectory(id: String): File = identifierChild(receivedRoot(), id)

    fun receivedIds(): List<String> = children(receivedRoot()).map {
        if (!isThemeOperationId(it.name)) themeFail(ThemeIssue.CORRUPT_STORAGE)
        requireDirectory(it)
        it.name
    }.also { if (it.size > 1) themeFail(ThemeIssue.CORRUPT_STORAGE) }

    fun receivedRecord(id: String): TransferredThemeRecord = TransferredThemeRecords.decode(
        readBounded(safeChild(receivedDirectory(id), "candidate.bin"), TransferredThemeRecords.MAXIMUM_BYTES),
    ).also { if (it.operationId != id) themeFail(ThemeIssue.CORRUPT_STORAGE) }

    fun sealReceived(record: TransferredThemeRecord) {
        val directory = receivedDirectory(record.operationId)
        requireDirectory(directory)
        syncPayloadTree(safeChild(directory, PAYLOAD))
        writeNew(safeChild(directory, "candidate.bin"), TransferredThemeRecords.encode(record))
        syncDirectory(directory)
        syncDirectory(receivedRoot())
    }

    /** Room 已标记此精确副本丢弃，或已证明它没有提交收据。 */
    fun discardReceived(id: String) {
        if (receivedApplyIntent()?.candidate?.operationId == id) themeFail(ThemeIssue.RECOVERY_REQUIRED)
        deleteTree(receivedDirectory(id))
    }

    fun receivedApplyIntent(): TransferredThemeApplyIntent? {
        val file = rootFile("transfer-apply.bin")
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        return TransferredThemeRecords.decodeIntent(readBounded(file, TransferredThemeRecords.MAXIMUM_BYTES))
    }

    fun prepareReceivedApply(intent: TransferredThemeApplyIntent) {
        val existing = receivedApplyIntent()
        if (existing != null) {
            if (existing != intent) themeFail(ThemeIssue.RECOVERY_REQUIRED)
            return
        }
        if (receivedRecord(intent.candidate.operationId) != intent.candidate) themeFail(ThemeIssue.INTEGRITY_FAILED)
        writeAtomic(rootFile("transfer-apply.bin"), TransferredThemeRecords.encode(intent))
    }

    fun clearReceivedApply(intent: TransferredThemeApplyIntent) {
        if (receivedApplyIntent() != intent) themeFail(ThemeIssue.RECOVERY_REQUIRED)
        Files.delete(rootFile("transfer-apply.bin").toPath())
        syncDirectory(root)
    }

    fun requireMutationAllowed(intent: TransferredThemeApplyIntent? = null) {
        val pending = receivedApplyIntent()
        if (pending != intent) themeFail(ThemeIssue.RECOVERY_REQUIRED)
    }

    fun cleanupOrphanStages(retain: Set<String>) {
        if (retain.size > 1 || retain.any { !isThemeOperationId(it) }) themeFail(ThemeIssue.INVALID_INPUT)
        for (file in children(stagingDirectory())) {
            if (!isThemeOperationId(file.name)) themeFail(ThemeIssue.CORRUPT_STORAGE)
            if (file.name !in retain) {
                try {
                    discardStage(file.name)
                } catch (error: Exception) {
                    throw ThemeException(ThemeIssue.CLEANUP_REQUIRED, error)
                }
            }
        }
    }

    fun manifest(ref: ThemeRef, catalog: ThemeCatalog): ThemeManifest {
        val record = catalog.installed.firstOrNull { it.ref == ref } ?: themeFail(ThemeIssue.THEME_NOT_FOUND)
        val directory = installationDirectory(ref.installationId)
        requireDirectory(directory)
        val bytes = readBounded(themeContainedFile(directory, ThemeZipIndex.MANIFEST_NAME), ThemeLimits.MANIFEST_BYTES)
        if (ThemeStorageRecords.digest(bytes) != ref.manifestSha256) themeFail(ThemeIssue.INTEGRITY_FAILED)
        val manifest = ThemeManifestReader.read(bytes)
        if (manifest.id != ref.themeId || manifest.displayName != record.displayName ||
            manifest.resources.sumOf { it.byteCount } != record.resourceBytes ||
            manifest.resources.filter { it.kind == ThemeResourceKind.FONT }.sumOf { it.byteCount } != record.fontBytes) {
            themeFail(ThemeIssue.INTEGRITY_FAILED)
        }
        for (resource in manifest.resources) {
            val file = themeContainedFile(directory, resource.path)
            requireThemeRegularFile(file)
            if (file.length() != resource.byteCount) themeFail(ThemeIssue.INTEGRITY_FAILED)
        }
        return manifest
    }

    fun commit(
        before: ThemeStoredCatalog,
        replacement: ThemeCatalog,
        operationId: String,
        stageId: String?,
        installId: String?,
        deleteId: String?,
        checkCancellation: () -> Unit,
    ): ThemeStoreCommit {
        pendingIssue()?.let(::themeFail)
        val current = loadCatalog()
        if (!current.bytes.contentEquals(before.bytes)) themeFail(ThemeIssue.REVISION_CONFLICT)
        val after = ThemeStorageRecords.encode(replacement)
        val plan = ThemeTransactionPlan(
            operationId, ThemeStorageRecords.digest(before.bytes), ThemeStorageRecords.digest(after), installId, stageId, deleteId,
        )
        val planBytes = ThemeStorageRecords.encodePlan(plan)
        validatePlanChange(plan, current.catalog, replacement)
        val stored = ThemeStoredCatalog(replacement, after)
        checkCancellation()
        Files.createDirectory(transactionDirectory().toPath())
        var committed = false
        var postCommitFailure = false
        try {
            syncDirectory(root)
            faults.at(ThemeStoragePoint.BEFORE_BACKUP_WRITE)
            writeNew(transactionFile("before.bin"), before.bytes)
            faults.at(ThemeStoragePoint.AFTER_BACKUP_WRITE)
            faults.at(ThemeStoragePoint.BEFORE_PLAN_WRITE)
            val nextPlan = transactionFile("plan-next.bin")
            writeNew(nextPlan, planBytes)
            Files.move(nextPlan.toPath(), transactionFile("plan.bin").toPath(), StandardCopyOption.ATOMIC_MOVE)
            syncDirectory(transactionDirectory())
            faults.at(ThemeStoragePoint.AFTER_PLAN_WRITE)
            checkCancellation()
            if (installId != null) {
                val source = stagePayload(checkNotNull(stageId))
                val destination = installationDirectory(installId)
                requireDirectory(source)
                syncPayloadTree(source)
                if (Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) themeFail(ThemeIssue.OPERATION_CONFLICT)
                faults.at(ThemeStoragePoint.BEFORE_INSTALL_MOVE)
                checkCancellation()
                Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
                syncDirectory(installedDirectory())
                syncDirectory(source.parentFile!!)
                faults.at(ThemeStoragePoint.AFTER_INSTALL_MOVE)
            }
            checkCancellation()
            val next = transactionFile("next.bin")
            writeNew(next, after)
            faults.at(ThemeStoragePoint.BEFORE_CATALOG_MOVE)
            checkCancellation()
            Files.move(next.toPath(), rootFile(CATALOG).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncDirectory(root)
            faults.at(ThemeStoragePoint.AFTER_CATALOG_MOVE)
            checkCancellation()
            faults.at(ThemeStoragePoint.BEFORE_COMMIT_MARKER)
            checkCancellation()
            val nextMarker = transactionFile("commit-next.bin")
            writeNew(nextMarker, plan.newCatalogSha256.toByteArray(Charsets.US_ASCII))
            checkCancellation()
            Files.move(nextMarker.toPath(), transactionFile("committed").toPath(), StandardCopyOption.ATOMIC_MOVE)
            // 原子标记是提交边界；之后失败或调用方取消都不能改回旧选择。
            committed = true
            syncDirectory(transactionDirectory())
            faults.at(ThemeStoragePoint.AFTER_COMMIT_MARKER)
        } catch (error: Exception) {
            if (committed) {
                if (error is CancellationException || error is OperationCanceledException) throw error
                postCommitFailure = true
            } else {
                rollbackAfterFailure(plan, error)
                throw saveFailure(error)
            }
        } catch (error: OutOfMemoryError) {
            if (committed) postCommitFailure = true else {
                rollbackAfterFailure(plan, error)
                throw error
            }
        }
        // 到此只有有界清理可以失败；不能将已提交选择误报为未提交，调用者可读取真实 catalog。
        return ThemeStoreCommit(stored, postCommitFailure || !cleanupCommitted(plan))
    }

    fun measuredOwnedBytes(): Long {
        var bytes = 0L
        var nodes = 0
        Files.walk(root.toPath(), ThemeLimits.OWNED_DEPTH).use { entries ->
            val iterator = entries.iterator()
            while (iterator.hasNext()) {
                val path = iterator.next()
                if (++nodes > ThemeLimits.OWNED_NODES || Files.isSymbolicLink(path)) themeFail(ThemeIssue.STORAGE_QUOTA_EXCEEDED)
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    if (root.toPath().relativize(path).nameCount == ThemeLimits.OWNED_DEPTH) {
                        Files.newDirectoryStream(path).use { nested ->
                            if (nested.iterator().hasNext()) themeFail(ThemeIssue.STORAGE_QUOTA_EXCEEDED)
                        }
                    }
                } else {
                    requireThemeRegularFile(path.toFile())
                    val size = Files.size(path)
                    if (size < 0 || size > ThemeLimits.STORED_BYTES - bytes) themeFail(ThemeIssue.STORAGE_QUOTA_EXCEEDED)
                    bytes += size
                }
            }
        }
        return bytes
    }

    fun requireRoom(owned: Long, written: Long, next: Long) {
        if (owned < 0 || written < 0 || next < 0) themeFail(ThemeIssue.INVALID_INPUT)
        if (owned > ThemeLimits.STORED_BYTES || written > ThemeLimits.STORED_BYTES - owned ||
            next > ThemeLimits.STORED_BYTES - owned - written) themeFail(ThemeIssue.STORAGE_QUOTA_EXCEEDED)
        val available = availableBytes(root)
        if (available < ThemeLimits.FREE_RESERVE_BYTES || next > available - ThemeLimits.FREE_RESERVE_BYTES) {
            themeFail(ThemeIssue.STORAGE_FULL)
        }
    }

    private fun rollbackAfterFailure(plan: ThemeTransactionPlan, error: Throwable) {
        try {
            if (Files.exists(transactionFile("plan.bin").toPath(), LinkOption.NOFOLLOW_LINKS)) rollback(plan)
            else deleteTree(transactionDirectory())
        } catch (closing: Throwable) {
            val issue = try {
                pendingIssue() ?: ThemeIssue.CLEANUP_REQUIRED
            } catch (reporting: Throwable) {
                if (closing !== reporting) closing.addSuppressed(reporting)
                ThemeIssue.RECOVERY_REQUIRED
            }
            if (error is CancellationException || error is OperationCanceledException) {
                error.addSuppressed(ThemeException(issue, closing))
                throw error
            }
            val failure = ThemeException(issue, error)
            failure.addSuppressed(closing)
            throw failure
        }
    }

    private fun rollback(plan: ThemeTransactionPlan) {
        if (Files.exists(transactionFile("committed").toPath(), LinkOption.NOFOLLOW_LINKS)) themeFail(ThemeIssue.CORRUPT_STORAGE)
        val catalog = rootFile(CATALOG)
        val currentBytes = if (Files.exists(catalog.toPath(), LinkOption.NOFOLLOW_LINKS)) readBounded(catalog, ThemeLimits.CATALOG_BYTES) else null
        val current = currentBytes?.let(ThemeStorageRecords::digest)
        val backup = transactionFile("before.bin")
        val beforeBytes = if (current == plan.oldCatalogSha256) checkNotNull(currentBytes) else readBounded(backup, ThemeLimits.CATALOG_BYTES)
        if (ThemeStorageRecords.digest(beforeBytes) != plan.oldCatalogSha256) themeFail(ThemeIssue.CORRUPT_STORAGE)
        val before = ThemeStorageRecords.decode(beforeBytes)
        if (plan.installId != null && before.installed.any { it.ref.installationId == plan.installId } ||
            plan.deleteId != null && before.installed.none { it.ref.installationId == plan.deleteId }) themeFail(ThemeIssue.CORRUPT_STORAGE)
        if (current != plan.oldCatalogSha256) {
            faults.at(ThemeStoragePoint.BEFORE_ROLLBACK_MOVE)
            // 使用已持久化 before 本身，空间不足时不再分配另一份数据。
            Files.move(backup.toPath(), catalog.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncDirectory(root)
            faults.at(ThemeStoragePoint.AFTER_ROLLBACK_MOVE)
        }
        if (plan.installId != null) {
            val installed = installationDirectory(plan.installId)
            val stage = stagePayload(checkNotNull(plan.stageId))
            if (Files.exists(installed.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                if (Files.exists(stage.toPath(), LinkOption.NOFOLLOW_LINKS)) themeFail(ThemeIssue.CORRUPT_STORAGE)
                requireDirectory(installed)
                requireDirectory(stage.parentFile ?: themeFail(ThemeIssue.CORRUPT_STORAGE))
                Files.move(installed.toPath(), stage.toPath(), StandardCopyOption.ATOMIC_MOVE)
                syncDirectory(installedDirectory())
                syncDirectory(stage.parentFile!!)
            }
        }
        retireJournal()
    }

    private fun cleanupCommitted(plan: ThemeTransactionPlan): Boolean = try {
        verifyCommittedCatalog(plan)
        if (plan.deleteId != null) {
            faults.at(ThemeStoragePoint.BEFORE_DELETE)
            deleteTree(installationDirectory(plan.deleteId))
            faults.at(ThemeStoragePoint.AFTER_DELETE)
        }
        if (plan.stageId != null) {
            faults.at(ThemeStoragePoint.BEFORE_STAGE_DELETE)
            deleteTree(stageDirectory(plan.stageId))
            faults.at(ThemeStoragePoint.AFTER_STAGE_DELETE)
        }
        retireJournal()
        true
    } catch (_: Exception) {
        false
    } catch (_: OutOfMemoryError) {
        false
    }

    /** 清理授权同时绑定 before/current/marker 与真实单次目录变化，不能只凭一个 deleteId 删除资源。 */
    private fun verifyCommittedCatalog(
        plan: ThemeTransactionPlan,
        catalogBytes: ByteArray = readBounded(rootFile(CATALOG), ThemeLimits.CATALOG_BYTES),
    ) {
        val beforeBytes = readBounded(transactionFile("before.bin"), ThemeLimits.CATALOG_BYTES)
        if (!readBounded(transactionFile("committed"), 64).contentEquals(plan.newCatalogSha256.toByteArray(Charsets.US_ASCII)) ||
            ThemeStorageRecords.digest(catalogBytes) != plan.newCatalogSha256 ||
            ThemeStorageRecords.digest(beforeBytes) != plan.oldCatalogSha256) themeFail(ThemeIssue.CORRUPT_STORAGE)
        validatePlanChange(plan, ThemeStorageRecords.decode(beforeBytes), ThemeStorageRecords.decode(catalogBytes))
    }

    private fun validatePlanChange(plan: ThemeTransactionPlan, before: ThemeCatalog, after: ThemeCatalog) {
        if (before.revision == Long.MAX_VALUE || after.revision != before.revision + 1 ||
            after.lastOperationId != plan.operationId) themeFail(ThemeIssue.CORRUPT_STORAGE)
        val old = before.installed.associateBy { it.ref.installationId }
        val next = after.installed.associateBy { it.ref.installationId }
        if (next.keys - old.keys != listOfNotNull(plan.installId).toSet() ||
            old.keys - next.keys != listOfNotNull(plan.deleteId).toSet() ||
            old.keys.intersect(next.keys).any { old.getValue(it) != next.getValue(it) }) themeFail(ThemeIssue.CORRUPT_STORAGE)
    }

    /** 破坏性清理已完成后原子退休整份日志，不能先删 plan/marker 再把新选择误判为未提交。 */
    private fun retireJournal() {
        faults.at(ThemeStoragePoint.BEFORE_JOURNAL_CLEANUP)
        if (Files.exists(retiredTransaction().toPath(), LinkOption.NOFOLLOW_LINKS)) themeFail(ThemeIssue.CLEANUP_REQUIRED)
        Files.move(transactionDirectory().toPath(), retiredTransaction().toPath(), StandardCopyOption.ATOMIC_MOVE)
        syncDirectory(root)
        faults.at(ThemeStoragePoint.AFTER_JOURNAL_RETIRE)
        deleteTree(retiredTransaction())
    }

    private fun syncPayloadTree(payload: File) {
        var nodes = 0
        Files.walk(payload.toPath(), ThemeLimits.DIRECTORY_DEPTH + 1).use { entries ->
            val iterator = entries.iterator()
            while (iterator.hasNext()) {
                val path = iterator.next()
                if (++nodes > ThemeLimits.OWNED_NODES || Files.isSymbolicLink(path)) themeFail(ThemeIssue.LINK_OR_SPECIAL_FILE)
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) syncDirectory(path.toFile())
                else requireThemeRegularFile(path.toFile())
            }
        }
    }

    private fun saveFailure(error: Exception): Exception = when (error) {
        is ThemeException, is CancellationException, is OperationCanceledException -> error
        is IOException, is SecurityException -> ThemeException(ThemeIssue.SAVE_FAILED, error)
        else -> error
    }

    private fun ensureDirectories() {
        val parent = root.parentFile ?: themeFail(ThemeIssue.INVALID_INPUT)
        requireDirectory(parent)
        if (parent != parent.canonicalFile || root.canonicalFile != root) themeFail(ThemeIssue.LINK_OR_SPECIAL_FILE)
        createDirectory(root)
        createDirectory(installedDirectory())
        createDirectory(stagingDirectory())
        createDirectory(receivedRoot())
    }

    private fun createDirectory(file: File) {
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createDirectory(file.toPath())
            } catch (_: FileAlreadyExistsException) {
                requireDirectory(file)
            }
        }
        requireDirectory(file)
    }

    private fun requireDirectory(file: File) {
        if (Files.isSymbolicLink(file.toPath()) || !Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            themeFail(ThemeIssue.LINK_OR_SPECIAL_FILE)
        }
    }

    private fun rootFile(name: String): File = safeChild(root, name)
    private fun transactionDirectory(): File = rootFile("transaction")
    private fun retiredTransaction(): File = rootFile("retired-transaction")
    private fun transactionFile(name: String): File = safeChild(transactionDirectory(), name)
    private fun installedDirectory(): File = rootFile("installed")
    private fun stagingDirectory(): File = rootFile("staging")
    private fun receivedRoot(): File = rootFile("received")

    private fun identifierChild(parent: File, id: String): File {
        if (!isThemeOperationId(id)) themeFail(ThemeIssue.INVALID_INPUT)
        return safeChild(parent, id)
    }

    private fun safeChild(parent: File, name: String): File {
        if (!name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))) themeFail(ThemeIssue.INVALID_PATH)
        val file = File(parent, name)
        if (Files.isSymbolicLink(parent.toPath()) || Files.isSymbolicLink(file.toPath()) ||
            parent != parent.canonicalFile || file.canonicalFile.parentFile != parent) themeFail(ThemeIssue.LINK_OR_SPECIAL_FILE)
        return file
    }

    private fun writeAtomic(file: File, bytes: ByteArray) {
        val temporary = rootFile("catalog-next-" + UUID.randomUUID().toString())
        var failure: Throwable? = null
        try {
            writeNew(temporary, bytes)
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncDirectory(file.parentFile ?: root)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                Files.deleteIfExists(temporary.toPath())
            } catch (closing: Throwable) {
                if (failure == null) throw closing
                if (failure !== closing) failure.addSuppressed(closing)
            }
        }
    }

    private fun writeNew(file: File, bytes: ByteArray) {
        requireRoom(measuredOwnedBytes(), 0, bytes.size.toLong())
        FileChannel.open(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                if (channel.write(buffer) <= 0) throw IOException("Theme metadata write did not advance")
            }
            channel.force(true)
        }
    }

    private fun readBounded(file: File, maximum: Int): ByteArray =
        readBoundedThemeFile(file, maximum, ThemeIssue.CORRUPT_STORAGE)

    /** 只删除已解析到本私有根下的目标；walk 不跟随链接，节点与深度均有上限。 */
    private fun deleteTree(directory: File) {
        val path = directory.toPath().toAbsolutePath().normalize()
        val rootPath = root.toPath().toAbsolutePath().normalize()
        if (path == rootPath || !path.startsWith(rootPath)) themeFail(ThemeIssue.INVALID_PATH)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        val nodes = ArrayList<java.nio.file.Path>()
        Files.walk(path, ThemeLimits.OWNED_DEPTH).use { entries ->
            val iterator = entries.iterator()
            while (iterator.hasNext()) {
                val node = iterator.next()
                if (nodes.size == ThemeLimits.OWNED_NODES || !node.toAbsolutePath().normalize().startsWith(path)) {
                    themeFail(ThemeIssue.STORAGE_QUOTA_EXCEEDED)
                }
                nodes.add(node)
            }
        }
        for (node in nodes.sortedByDescending { it.nameCount }) Files.deleteIfExists(node)
        syncDirectory(directory.parentFile ?: root)
    }

    private fun children(directory: File): List<File> {
        requireDirectory(directory)
        val files = ArrayList<File>()
        Files.newDirectoryStream(directory.toPath()).use { entries ->
            for (entry in entries) {
                if (files.size == ThemeLimits.OWNED_NODES) themeFail(ThemeIssue.STORAGE_QUOTA_EXCEEDED)
                files += entry.toFile()
            }
        }
        return files
    }

    private fun syncDirectory(directory: File) {
        requireDirectory(directory)
        FileChannel.open(directory.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            channel.force(true)
        }
    }

    companion object {
        private const val CATALOG = "catalog.bin"
        private const val PAYLOAD = "payload"
    }
}
