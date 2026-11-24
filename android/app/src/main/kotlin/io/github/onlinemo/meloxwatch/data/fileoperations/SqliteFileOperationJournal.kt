package io.github.onlinemo.meloxwatch.data.fileoperations

import android.content.Context
import android.database.sqlite.SQLiteFullException
import android.database.sqlite.SQLiteDatabaseCorruptException
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteException
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.onlinemo.meloxwatch.data.database.toPlatformSQLiteException
import io.github.onlinemo.meloxwatch.data.database.bundledSQLiteErrorCode
import java.io.File
import java.nio.file.Files
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * 持久行仅由共享 LocalProviderIo worker 访问，门面对 Main 安全。
 * 独立 schema 不迁移音乐库，也不导出系统 URI 权。
 */
class SqliteFileOperationJournal private constructor(private val connection: SQLiteConnection) : FileOperationJournal {
    private val lock = Any()
    private var closed = false

    override fun record(operationId: String): FileOperationRecord? = access {
        fileOperationId(operationId)
        prepared("SELECT * FROM file_operations WHERE operationId = ?", operationId) { if (it.step()) readRecord(it) else null }
    }

    override fun create(record: FileOperationRecord) = access {
        fileOperationId(record.operationId)
        require(record.phase == FileOperationPhase.REQUESTED && record.selectedCount == 0L)
        val command = bounded(FileOperationCodec.command(record.command), FileOperationLimits.JOURNAL_REQUEST_BYTES)
        require(record.commandBinding == FileOperationCodec.commandBinding(record.selection, record.command))
        execute("""INSERT INTO file_operations VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            record.operationId, record.selection.viewId, record.selection.revision, record.selection.totalCount,
            command, record.commandBinding, record.phase.name, 0L, 0L, 0L, 0L, 0L, null, false, 1, record.command.kind.name)
    }

    override fun appendSelection(operationId: String, ordinal: Long, selected: FileSelectedTrack, target: FileOperationTarget): Boolean = access {
        transaction {
            val record = requireRecord(operationId)
            require(record.phase == FileOperationPhase.REQUESTED && ordinal == record.selectedCount && ordinal < record.selection.totalCount)
            require(target.trackKey == selected.trackKey)
            val encoded = bounded(FileOperationCodec.target(target), FileOperationLimits.JOURNAL_ROW_BYTES)
            execute("INSERT INTO file_selection VALUES (?,?,?,?,?,?,?)", operationId, ordinal, selected.trackKey.providerId,
                selected.trackKey.trackId, target.unitKey, bounded(FileOperationCodec.selected(selected), FileOperationLimits.JOURNAL_ROW_BYTES), 1)
            val existing = prepared("SELECT target FROM file_units WHERE operationId = ? AND unitKey = ?", operationId, target.unitKey) {
                if (it.step()) FileOperationCodec.target(it.getText(0)) else null
            }
            if (existing == null) {
                execute("INSERT INTO file_units VALUES (?,?,?,?,?,?,?,?,?)", operationId, ordinal, target.unitKey, encoded,
                    FileItemPhase.REQUESTED.name, FileOperationCodec.artifacts(FileOperationArtifacts()), null, null, 1)
            } else {
                require(existing.descriptor?.location?.locationId == target.descriptor?.location?.locationId)
                if (existing.proof != null || target.proof != null) {
                    val old = checkNotNull(existing.proof)
                    val current = checkNotNull(target.proof)
                    if (old.device != current.device || old.inode != current.inode || old.changedTime != current.changedTime ||
                        old.sha256 != current.sha256 || old.bytes != current.bytes) {
                        throw FileOperationException(FileOperationIssue.SOURCE_CHANGED)
                    }
                }
            }
            execute("UPDATE file_operations SET selectedCount = selectedCount + 1, physicalCount = physicalCount + ? WHERE operationId = ?",
                if (existing == null && target.proof != null) 1L else 0L, operationId)
            existing == null
        }
    }

    override fun appendImpacts(operationId: String, unitKey: String, impacts: List<FileOperationImpact>) = access {
        require(impacts.size in 1..FileOperationLimits.PAGE_SIZE)
        transaction {
            val record = requireRecord(operationId)
            require(record.phase == FileOperationPhase.REQUESTED)
            var tracks = 0L
            var aliases = 0L
            var cues = 0L
            for (impact in impacts) {
                val payload = bounded(FileOperationCodec.impact(impact), FileOperationLimits.JOURNAL_ROW_BYTES)
                val counted = prepared("SELECT EXISTS(SELECT 1 FROM file_impacts WHERE operationId=? AND impactKey=? AND kind=? LIMIT 1)",
                    operationId, impact.impactKey, impact.kind.name) { check(it.step())
                        it.getLong(0) != 0L }
                execute("INSERT OR IGNORE INTO file_impacts VALUES (?,?,?,?,?,?)", operationId, unitKey, impact.impactKey,
                    impact.kind.name, impact.sourceId, payload)
                val added = prepared("SELECT changes()") { check(it.step())
                    it.getLong(0) == 1L }
                if (!added) {
                    val original = prepared("SELECT payload FROM file_impacts WHERE operationId=? AND unitKey=? AND impactKey=?",
                        operationId, unitKey, impact.impactKey) { check(it.step())
                            it.getText(0) }
                    if (original != payload) {
                        val old = FileOperationCodec.impact(original)
                        val left = old.source
                        val right = impact.source
                        if (old.copy(source = null) != impact.copy(source = null) || impact.kind != FileImpactKind.SOURCE ||
                            left == null || right == null || left.spec != right.spec || left.grant != right.grant ||
                            left.grantId != right.grantId || left.authEpoch != right.authEpoch || left.state != right.state) {
                            throw FileOperationException(FileOperationIssue.CONFLICT)
                        }
                    }
                } else if (!counted) when (impact.kind) {
                    FileImpactKind.TRACK -> tracks++
                    FileImpactKind.ALIAS -> aliases++
                    FileImpactKind.CUE_SEGMENT -> cues++
                    FileImpactKind.SOURCE -> Unit
                }
            }
            execute("""UPDATE file_operations SET trackImpactCount=trackImpactCount+?,
                aliasImpactCount=aliasImpactCount+?, cueImpactCount=cueImpactCount+? WHERE operationId=?""",
                tracks, aliases, cues, operationId)
        }
    }

    override fun appendSources(operationId: String, sourceIds: List<String>) = access {
        require(sourceIds.size in 1..FileOperationLimits.PAGE_SIZE)
        transaction {
            require(requireRecord(operationId).phase == FileOperationPhase.REQUESTED)
            for (sourceId in sourceIds) {
                require(sourceId.isNotBlank() && sourceId.length <= 128)
                execute("INSERT OR IGNORE INTO file_operation_sources VALUES (?,?)", operationId, sourceId)
            }
        }
    }

    override fun sealPreview(operationId: String): FileOperationRecord = access {
        val record = requireRecord(operationId)
        require(record.phase == FileOperationPhase.REQUESTED && record.selectedCount == record.selection.totalCount)
        val hash = MessageDigest.getInstance("SHA-256")
        fun bind(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            hash.update(ByteBuffer.allocate(8).putLong(bytes.size.toLong()).array())
            hash.update(bytes)
        }
        bind(operationId)
        bind(record.commandBinding)
        // 用长度边界绑定每个冻结行，不在内存拼接完整选择。
        prepared("SELECT payload FROM file_selection WHERE operationId=? ORDER BY ordinal", operationId) {
            while (it.step()) bind(it.getText(0))
        }
        prepared("SELECT target FROM file_units WHERE operationId=? ORDER BY ordinal", operationId) {
            while (it.step()) bind(it.getText(0))
        }
        prepared("SELECT payload FROM file_impacts WHERE operationId=? ORDER BY unitKey,impactKey", operationId) {
            while (it.step()) bind(it.getText(0))
        }
        prepared("SELECT sourceId FROM file_operation_sources WHERE operationId=? ORDER BY sourceId", operationId) {
            while (it.step()) bind(it.getText(0))
        }
        val sealed = record.copy(phase = FileOperationPhase.PREVIEW, previewBinding = fileOperationHex(hash.digest()))
        updateRecord(sealed)
        sealed
    }

    override fun sources(operationId: String, after: String?, limit: Int): List<String> = access {
        pageLimit(limit)
        prepared("SELECT sourceId FROM file_operation_sources WHERE operationId=? AND (? IS NULL OR sourceId>?) " +
            "ORDER BY sourceId LIMIT ?", operationId, after, after, limit) {
            buildList { while (it.step()) add(it.getText(0)) }
        }
    }

    override fun saveRoute(operationId: String, unitKey: String, route: FilePublishedRoute) = access {
        require(route.sourceId.isNotBlank() && route.authEpoch > 0L && route.grantId.isNotBlank())
        require((route.proof == null) == (route.issue != null))
        require(requireRecord(operationId).phase in setOf(FileOperationPhase.RUNNING, FileOperationPhase.NEEDS_RECOVERY))
        execute("INSERT INTO file_published_routes VALUES (?,?,?,?) ON CONFLICT(operationId,unitKey,sourceId) DO UPDATE SET payload=excluded.payload",
            operationId, unitKey, route.sourceId, bounded(FileOperationCodec.route(route), FileOperationLimits.JOURNAL_ROW_BYTES))
    }

    override fun route(operationId: String, unitKey: String, sourceId: String): FilePublishedRoute? = access {
        prepared("SELECT payload FROM file_published_routes WHERE operationId=? AND unitKey=? AND sourceId=?", operationId, unitKey, sourceId) {
            if (it.step()) FileOperationCodec.route(it.getText(0)) else null
        }
    }

    override fun updateRecord(record: FileOperationRecord) = access {
        val old = requireRecord(record.operationId)
        require(old.selection == record.selection && old.commandBinding == record.commandBinding)
        require(FileOperationCodec.command(old.command) == FileOperationCodec.command(record.command))
        require(old.selectedCount == record.selectedCount && old.physicalCount == record.physicalCount &&
            old.trackImpactCount == record.trackImpactCount && old.aliasImpactCount == record.aliasImpactCount && old.cueImpactCount == record.cueImpactCount)
        require(old.previewBinding == null || old.previewBinding == record.previewBinding)
        require(record.phase in recordTransitions(old.phase))
        require(!old.sourceDeletionConfirmed || record.sourceDeletionConfirmed)
        if (!old.sourceDeletionConfirmed && record.sourceDeletionConfirmed) {
            require(record.command.kind == FileOperationKind.MOVE && old.phase == FileOperationPhase.WAITING_SOURCE_CONFIRMATION &&
                record.phase == FileOperationPhase.RUNNING)
        }
        if (record.phase !in setOf(FileOperationPhase.REQUESTED, FileOperationPhase.FAILED, FileOperationPhase.CANCELLED)) {
            require(record.previewBinding?.matches(Regex("[0-9a-f]{64}")) == true)
        }
        execute("UPDATE file_operations SET phase=?, previewBinding=?, sourceDeletionConfirmed=? WHERE operationId=?",
            record.phase.name, record.previewBinding, record.sourceDeletionConfirmed, record.operationId)
    }

    override fun updateItem(item: FileOperationItem) = access {
        val old = prepared("SELECT operationId,ordinal,target,phase,artifacts,issue,reconcileAfter,schemaVersion FROM file_units " +
            "WHERE operationId=? AND unitKey=? AND ordinal=?", item.operationId, item.target.unitKey, item.ordinal) {
            check(it.step())
            readItem(it)
        }
        require(FileOperationCodec.target(old.target) == FileOperationCodec.target(item.target))
        require(item.phase in itemTransitions(old.phase))
        if (old.phase == FileItemPhase.APPLYING && item.phase == FileItemPhase.INDEX_RECONCILED) {
            require(requireRecord(item.operationId).command.kind in setOf(FileOperationKind.REMOVE_FROM_INDEX, FileOperationKind.REMOVE_SOURCE))
        }
        if (old.phase == FileItemPhase.INDEX_RECONCILED && item.phase == FileItemPhase.CANCELLED) require(item.artifacts.restoredOriginal)
        execute("UPDATE file_units SET phase=?, artifacts=?, issue=?, reconcileAfter=? WHERE operationId=? AND unitKey=?",
            item.phase.name, bounded(FileOperationCodec.artifacts(item.artifacts), FileOperationLimits.JOURNAL_REQUEST_BYTES),
            item.issue?.name, item.reconcileAfter, item.operationId, item.target.unitKey)
    }

    override fun items(operationId: String, afterOrdinal: Long?, limit: Int): List<FileOperationItem> = access {
        pageLimit(limit)
        prepared("SELECT operationId,ordinal,target,phase,artifacts,issue,reconcileAfter,schemaVersion FROM file_units " +
            "WHERE operationId=? AND ordinal>? ORDER BY ordinal LIMIT ?", operationId, afterOrdinal ?: -1L, limit) { statement ->
            buildList { while (statement.step()) add(readItem(statement)) }
        }
    }

    override fun unit(operationId: String, unitKey: String): FileOperationItem? = access {
        prepared("SELECT operationId,ordinal,target,phase,artifacts,issue,reconcileAfter,schemaVersion FROM file_units WHERE operationId=? AND unitKey=?",
            operationId, unitKey) { if (it.step()) readItem(it) else null }
    }

    override fun impacts(operationId: String, unitKey: String, after: String?, limit: Int): FileImpactPage = access {
        pageLimit(limit)
        val rows = prepared("SELECT payload FROM file_impacts WHERE operationId=? AND unitKey=? AND (? IS NULL OR impactKey>?) " +
            "ORDER BY impactKey LIMIT ?", operationId, unitKey, after, after, limit + 1) { statement ->
            buildList { while (statement.step()) add(FileOperationCodec.impact(statement.getText(0))) }
        }
        FileImpactPage(rows.take(limit), if (rows.size > limit) rows[limit - 1].impactKey else null)
    }

    override fun progress(operationId: String): FileOperationProgress = access {
        val record = requireRecord(operationId)
        val counts = prepared("SELECT phase,COUNT(*) FROM file_units WHERE operationId=? GROUP BY phase", operationId) { statement ->
            buildMap { while (statement.step()) put(FileItemPhase.valueOf(statement.getText(0)), statement.getLong(1)) }
        }
        val copies = counts[FileItemPhase.COPY_INDEX_RECONCILED] ?: 0L
        val waiting = record.phase == FileOperationPhase.WAITING_SOURCE_CONFIRMATION || record.phase == FileOperationPhase.RUNNING
        val copyFailures = prepared("SELECT COUNT(*) FROM file_units WHERE operationId=? AND phase='COPY_INDEX_RECONCILED' AND issue IS NOT NULL", operationId) {
            check(it.step())
            it.getLong(0)
        }
        FileOperationProgress(operationId, record.phase, counts[FileItemPhase.INDEX_RECONCILED] ?: 0L,
            (counts[FileItemPhase.FAILED] ?: 0L) + copyFailures, (counts[FileItemPhase.REQUESTED] ?: 0L) + (counts[FileItemPhase.CANCELLED] ?: 0L),
            if (waiting) (counts[FileItemPhase.COPY_VALIDATED] ?: 0L) + copies else 0L,
            counts[FileItemPhase.NEEDS_RECOVERY] ?: 0L, if (waiting) 0L else copies)
    }

    override fun pending(afterOperationId: String?, limit: Int): List<FileOperationRecord> = access {
        pageLimit(limit)
        prepared("SELECT * FROM file_operations WHERE (phase IN ('CONFIRMED','RUNNING','WAITING_SOURCE_CONFIRMATION','NEEDS_RECOVERY') " +
            "OR phase='PARTIAL' AND EXISTS(SELECT 1 FROM file_units WHERE file_units.operationId=file_operations.operationId " +
            "AND file_units.phase IN ('STAGING','VALIDATED','APPLYING','EXTERNAL_APPLIED','COPY_VALIDATED','NEEDS_RECOVERY'))) " +
            "AND (? IS NULL OR operationId>?) ORDER BY operationId LIMIT ?", afterOperationId, afterOperationId, limit) { statement ->
            buildList { while (statement.step()) add(readRecord(statement)) }
        }
    }

    override fun hasPendingSource(sourceId: String, excludingOperationId: String?): Boolean = access {
        prepared("""SELECT EXISTS(SELECT 1 FROM file_operation_sources source
            JOIN file_operations operation ON operation.operationId=source.operationId
            WHERE source.sourceId=? AND (? IS NULL OR operation.operationId<>?) AND (operation.phase IN ('CONFIRMED','RUNNING','WAITING_SOURCE_CONFIRMATION','NEEDS_RECOVERY')
            OR operation.phase='PARTIAL' AND EXISTS(SELECT 1 FROM file_units unit WHERE unit.operationId=source.operationId
            AND unit.phase IN ('STAGING','VALIDATED','APPLYING','EXTERNAL_APPLIED','COPY_VALIDATED','NEEDS_RECOVERY'))) LIMIT 1)""", sourceId, excludingOperationId, excludingOperationId) {
            check(it.step())
            it.getLong(0) != 0L
        }
    }

    override fun hasUncoordinatedWork(operationId: String?): Boolean = access {
        prepared("""SELECT EXISTS(SELECT 1 FROM file_units u JOIN file_operations o ON o.operationId=u.operationId
            WHERE u.phase IN ('STAGING','VALIDATED','APPLYING','EXTERNAL_APPLIED','COPY_VALIDATED','NEEDS_RECOVERY')
            AND (? IS NULL OR u.operationId=?) LIMIT 1)""", operationId, operationId) {
            check(it.step())
            it.getLong(0) != 0L
        }
    }

    override fun sourceDeletionPreview(operationId: String): FileOperationPreview = access {
        val record = requireRecord(operationId)
        if (record.command.kind != FileOperationKind.MOVE || record.phase != FileOperationPhase.WAITING_SOURCE_CONFIRMATION &&
            !(record.phase == FileOperationPhase.PARTIAL && record.sourceDeletionConfirmed)) {
            throw FileOperationException(FileOperationIssue.SOURCE_DELETE_CONFIRMATION_REQUIRED)
        }
        val hash = MessageDigest.getInstance("SHA-256")
        fun bind(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            hash.update(ByteBuffer.allocate(8).putLong(bytes.size.toLong()).array())
            hash.update(bytes)
        }
        bind("DELETE_COMPLETED_MOVE_SOURCE:v1")
        bind(operationId)
        bind(checkNotNull(record.previewBinding))
        var physical = 0L
        prepared("SELECT target,artifacts FROM file_units WHERE operationId=? AND phase='COPY_INDEX_RECONCILED' ORDER BY ordinal", operationId) {
            while (it.step()) {
                val artifacts = FileOperationCodec.artifacts(it.getText(1))
                if (artifacts.published == null || artifacts.originalDeleted) throw FileOperationException(FileOperationIssue.RECOVERY_REQUIRED)
                bind(it.getText(0))
                bind(it.getText(1))
                physical++
            }
        }
        if (physical == 0L) throw FileOperationException(FileOperationIssue.EMPTY_SELECTION)
        val counts = prepared("""SELECT i.kind,COUNT(DISTINCT i.impactKey) FROM file_impacts i JOIN file_units u
            ON u.operationId=i.operationId AND u.unitKey=i.unitKey WHERE i.operationId=? AND u.phase='COPY_INDEX_RECONCILED' GROUP BY i.kind""", operationId) {
            buildMap { while (it.step()) put(FileImpactKind.valueOf(it.getText(0)), it.getLong(1)) }
        }
        FileOperationPreview(operationId, FileOperationKind.DELETE_COMPLETED_MOVE_SOURCE, fileOperationHex(hash.digest()), physical, physical,
            counts[FileImpactKind.TRACK] ?: 0L, counts[FileImpactKind.ALIAS] ?: 0L, counts[FileImpactKind.CUE_SEGMENT] ?: 0L, true)
    }

    override fun saveIndexIgnore(ignore: FileIndexIgnore) = access {
        execute("INSERT INTO file_index_ignores VALUES (?,?,?,?) ON CONFLICT(providerId,trackId) DO UPDATE SET operationId=excluded.operationId,unitKey=excluded.unitKey",
            ignore.trackKey.providerId, ignore.trackKey.trackId, ignore.operationId, ignore.unitKey)
    }
    override fun indexIgnore(trackKey: io.github.onlinemo.meloxwatch.model.TrackKey): FileIndexIgnore? = access {
        prepared("SELECT operationId,unitKey FROM file_index_ignores WHERE providerId=? AND trackId=?", trackKey.providerId, trackKey.trackId) {
            if (it.step()) FileIndexIgnore(trackKey, it.getText(0), it.getText(1)) else null
        }
    }
    override fun clearIndexIgnore(ignore: FileIndexIgnore) = access {
        execute("DELETE FROM file_index_ignores WHERE providerId=? AND trackId=? AND operationId=? AND unitKey=?",
            ignore.trackKey.providerId, ignore.trackKey.trackId, ignore.operationId, ignore.unitKey)
    }
    override fun clearIndexUndoAfterLibraryReplacement() = access { execute("DELETE FROM file_index_ignores") }

    override fun queueRepair(operationId: String, unitKey: String, role: FileQueueRepairRole): FileQueueRepairBinding? = access {
        prepared("SELECT operationId,unitKey,role,generationId,baseRevision,finalRevision,completed,schemaVersion FROM file_queue_repairs " +
            "WHERE operationId=? AND unitKey=? AND role=?", operationId, unitKey, role.name) { if (it.step()) readQueueRepair(it) else null }
    }

    override fun bindQueueRepair(binding: FileQueueRepairBinding): FileQueueRepairBinding = access {
        transaction {
            require(!binding.completed)
            val existing = queueRepair(binding.operationId, binding.unitKey, binding.role)
            if (existing != null) return@transaction existing
            require(unit(binding.operationId, binding.unitKey)?.phase in setOf(FileItemPhase.EXTERNAL_APPLIED, FileItemPhase.COPY_VALIDATED))
            execute("INSERT INTO file_queue_repairs VALUES (?,?,?,?,?,?,?,?)", binding.operationId, binding.unitKey, binding.role.name,
                binding.generationId, binding.baseRevision, binding.finalRevision, false, 1)
            binding
        }
    }

    override fun completeQueueRepair(binding: FileQueueRepairBinding) = access {
        val existing = queueRepair(binding.operationId, binding.unitKey, binding.role) ?: throw FileOperationException(FileOperationIssue.RECOVERY_REQUIRED)
        require(existing.copy(completed = false) == binding.copy(completed = false))
        execute("UPDATE file_queue_repairs SET completed=1 WHERE operationId=? AND unitKey=? AND role=?",
            binding.operationId, binding.unitKey, binding.role.name)
    }

    private fun readQueueRepair(statement: SQLiteStatement): FileQueueRepairBinding {
        require(statement.getLong(7) == 1L && statement.getLong(6) in 0L..1L)
        return FileQueueRepairBinding(statement.getText(0), statement.getText(1), FileQueueRepairRole.valueOf(statement.getText(2)), statement.nullableText(3),
            statement.getLong(4), statement.getLong(5), statement.getLong(6) != 0L)
    }

    override fun createSelectionDraft(draft: FileSelectionDraft) = access {
        require(draft == draft.copy(allSelected = false, generation = 0L, revision = 0L, ordinalSequence = 0L, selectedCount = 0L))
        execute("INSERT INTO file_selection_drafts VALUES (?,?,?,?,?,?,?,?,?,?,?)", draft.draftId, draft.view.viewId, draft.view.revision,
            draft.view.totalCount, false, 0L, 0L, 0L, 0L, false, 1)
    }

    override fun selectionDraft(draftId: String): FileSelectionDraft? = access {
        fileOperationId(draftId)
        prepared("SELECT draftId,viewId,libraryRevision,totalCount,allSelected,generation,revision,ordinalSequence,selectedCount,schemaVersion " +
            "FROM file_selection_drafts WHERE draftId=? AND retired=0", draftId) { if (it.step()) readDraft(it) else null }
    }

    override fun toggleSelectionDraft(draftId: String, expectedRevision: Long, selected: FileSelectedTrack): FileSelectionDraft = access {
        transaction {
            val draft = requireDraft(draftId, expectedRevision)
            val present = prepared("SELECT EXISTS(SELECT 1 FROM file_draft_tracks WHERE draftId=? AND generation=? AND providerId=? AND trackId=?)",
                draftId, draft.generation, selected.trackKey.providerId, selected.trackKey.trackId) { check(it.step())
                    it.getLong(0) != 0L }
            if (present) execute("DELETE FROM file_draft_tracks WHERE draftId=? AND generation=? AND providerId=? AND trackId=?",
                draftId, draft.generation, selected.trackKey.providerId, selected.trackKey.trackId)
            else execute("INSERT INTO file_draft_tracks VALUES (?,?,?,?,?,?,?)", draftId, draft.generation, selected.trackKey.providerId,
                selected.trackKey.trackId, draft.ordinalSequence, bounded(FileOperationCodec.selected(selected), FileOperationLimits.JOURNAL_ROW_BYTES), 1)
            val addsSelection = present == draft.allSelected
            val next = draft.copy(revision = Math.addExact(draft.revision, 1L), ordinalSequence = Math.addExact(draft.ordinalSequence, 1L),
                selectedCount = if (addsSelection) Math.addExact(draft.selectedCount, 1L) else draft.selectedCount - 1L)
            writeDraft(next)
            next
        }
    }

    override fun selectAllDraft(draftId: String, expectedRevision: Long, selected: Boolean): FileSelectionDraft = access {
        transaction {
            val draft = requireDraft(draftId, expectedRevision)
            val next = draft.copy(allSelected = selected, generation = Math.addExact(draft.generation, 1L), revision = Math.addExact(draft.revision, 1L),
                ordinalSequence = 0L, selectedCount = if (selected) draft.view.totalCount else 0L)
            writeDraft(next)
            // 代次切换默认选择，不在单事务删除无界选中集；
            // 退出后以有界批回收旧例外行。
            next
        }
    }

    override fun draftExceptions(draftId: String, expectedRevision: Long, keys: List<io.github.onlinemo.meloxwatch.model.TrackKey>): Set<io.github.onlinemo.meloxwatch.model.TrackKey> = access {
        require(keys.size <= FileOperationLimits.PAGE_SIZE)
        val draft = requireDraft(draftId, expectedRevision)
        buildSet {
            for (key in keys) if (prepared("SELECT EXISTS(SELECT 1 FROM file_draft_tracks WHERE draftId=? AND generation=? AND providerId=? AND trackId=?)",
                draftId, draft.generation, key.providerId, key.trackId) { check(it.step())
                    it.getLong(0) != 0L }) add(key)
        }
    }

    override fun selectedDraftPage(draftId: String, expectedRevision: Long, afterOrdinal: Long?, limit: Int): List<FileDraftTrack> = access {
        pageLimit(limit)
        val draft = requireDraft(draftId, expectedRevision)
        require(!draft.allSelected)
        prepared("SELECT ordinal,payload,schemaVersion FROM file_draft_tracks WHERE draftId=? AND generation=? AND ordinal>? ORDER BY ordinal LIMIT ?",
            draftId, draft.generation, afterOrdinal ?: -1L, limit) { statement -> buildList {
                while (statement.step()) {
                    require(statement.getLong(2) == 1L && statement.getLong(0) >= 0L)
                    add(FileDraftTrack(statement.getLong(0), FileOperationCodec.selected(bounded(statement.getText(1), FileOperationLimits.JOURNAL_ROW_BYTES))))
                }
            } }
    }

    override fun retireSelectionDraft(draftId: String) = access {
        fileOperationId(draftId)
        execute("UPDATE file_selection_drafts SET retired=1 WHERE draftId=?", draftId)
    }

    override fun retireOrphanDraftPage(activeDraftIds: Set<String>, afterDraftId: String?, limit: Int): String? = access {
        pageLimit(limit)
        require(activeDraftIds.size <= FileOperationLimits.PAGE_SIZE)
        activeDraftIds.forEach(::fileOperationId)
        transaction {
            val ids = prepared("SELECT draftId FROM file_selection_drafts WHERE retired=0 AND (? IS NULL OR draftId>?) ORDER BY draftId LIMIT ?",
                afterDraftId, afterDraftId, limit + 1) { statement ->
                buildList { while (statement.step()) add(statement.getText(0)) }
            }
            for (id in ids.take(limit)) if (id !in activeDraftIds) retireSelectionDraft(id)
            if (ids.size > limit) ids[limit - 1] else null
        }
    }

    override fun reclaimRetiredDraftPage(limit: Int): Boolean = access {
        pageLimit(limit)
        transaction {
            execute("""DELETE FROM file_draft_tracks WHERE rowid IN (SELECT t.rowid FROM file_draft_tracks t
                JOIN file_selection_drafts d ON d.draftId=t.draftId WHERE d.retired=1 ORDER BY t.rowid LIMIT ?)""", limit)
            execute("""DELETE FROM file_selection_drafts WHERE draftId IN (SELECT d.draftId FROM file_selection_drafts d
                WHERE d.retired=1 AND NOT EXISTS(SELECT 1 FROM file_draft_tracks t WHERE t.draftId=d.draftId)
                ORDER BY d.draftId LIMIT ?)""", limit)
            prepared("SELECT EXISTS(SELECT 1 FROM file_selection_drafts WHERE retired=1 LIMIT 1)") {
                check(it.step())
                it.getLong(0) != 0L
            }
        }
    }

    private fun requireDraft(draftId: String, revision: Long): FileSelectionDraft = (selectionDraft(draftId)
        ?: throw FileOperationException(FileOperationIssue.SOURCE_CHANGED)).also { require(it.revision == revision) }
    private fun readDraft(statement: SQLiteStatement): FileSelectionDraft {
        require(statement.getLong(9) == 1L && statement.getLong(4) in 0L..1L)
        return FileSelectionDraft(statement.getText(0), FileSelectionToken(statement.getText(1), statement.getLong(2), statement.getLong(3)),
            statement.getLong(4) != 0L, statement.getLong(5), statement.getLong(6), statement.getLong(7), statement.getLong(8))
    }
    private fun writeDraft(draft: FileSelectionDraft) {
        execute("UPDATE file_selection_drafts SET allSelected=?,generation=?,revision=?,ordinalSequence=?,selectedCount=? WHERE draftId=? AND retired=0",
            draft.allSelected, draft.generation, draft.revision, draft.ordinalSequence, draft.selectedCount, draft.draftId)
    }

    override fun close() = synchronized(lock) {
        if (!closed) { closed = true
            connection.close() }
    }

    private fun requireRecord(id: String) = prepared("SELECT * FROM file_operations WHERE operationId=?", id) {
        if (!it.step()) throw FileOperationException(FileOperationIssue.INVALID_INPUT)
        readRecord(it)
    }

    private fun readRecord(statement: SQLiteStatement): FileOperationRecord {
        require(statement.getLong(14) == 1L)
        val record = FileOperationRecord(statement.getText(0), FileSelectionToken(statement.getText(1), statement.getLong(2), statement.getLong(3)),
            FileOperationCodec.command(bounded(statement.getText(4), FileOperationLimits.JOURNAL_REQUEST_BYTES)), statement.getText(5), FileOperationPhase.valueOf(statement.getText(6)),
            statement.getLong(7), statement.getLong(8), statement.getLong(9), statement.getLong(10), statement.getLong(11),
            statement.nullableText(12), statement.getLong(13) != 0L)
        fileOperationId(record.operationId)
        require(record.commandBinding == FileOperationCodec.commandBinding(record.selection, record.command))
        require(statement.getText(15) == record.command.kind.name)
        require(record.selectedCount in 0L..record.selection.totalCount && record.physicalCount in 0L..record.selectedCount)
        require(record.trackImpactCount >= 0L && record.aliasImpactCount >= 0L && record.cueImpactCount >= 0L)
        require(statement.getLong(13) in 0L..1L && (!record.sourceDeletionConfirmed || record.command.kind == FileOperationKind.MOVE))
        return record
    }

    private fun readItem(statement: SQLiteStatement): FileOperationItem {
        require(statement.getLong(7) == 1L && statement.getLong(1) >= 0L)
        fileOperationId(statement.getText(0))
        return FileOperationItem(statement.getText(0), statement.getLong(1),
            FileOperationCodec.target(bounded(statement.getText(2), FileOperationLimits.JOURNAL_ROW_BYTES)), FileItemPhase.valueOf(statement.getText(3)),
            FileOperationCodec.artifacts(bounded(statement.getText(4), FileOperationLimits.JOURNAL_REQUEST_BYTES)),
            statement.nullableText(5)?.let(FileOperationIssue::valueOf), statement.nullableText(6))
    }

    private fun <T> access(block: () -> T): T = synchronized(lock) {
        if (closed) throw FileOperationException(FileOperationIssue.STORAGE_UNAVAILABLE)
        try { block() }
        catch (failure: SQLiteException) {
            val platform = failure.toPlatformSQLiteException()
            val code = bundledSQLiteErrorCode(failure.message)?.and(255)
            throw FileOperationException(when {
                code == 5 || code == 6 -> FileOperationIssue.BUSY
                platform is SQLiteFullException -> FileOperationIssue.STORAGE_FULL
                platform is SQLiteDatabaseCorruptException || code == 11 || code == 26 -> FileOperationIssue.RECOVERY_REQUIRED
                else -> FileOperationIssue.STORAGE_UNAVAILABLE
            }, platform)
        }
        catch (failure: IllegalArgumentException) { throw FileOperationException(FileOperationIssue.RECOVERY_REQUIRED, failure) }
    }
    private fun execute(sql: String, vararg arguments: Any?) { prepared(sql, *arguments) { it.step() } }
    private fun <T> prepared(sql: String, vararg arguments: Any?, block: (SQLiteStatement) -> T): T = connection.prepare(sql).use { statement ->
        arguments.forEachIndexed { index, value -> when (value) {
            null -> statement.bindNull(index + 1)
            is String -> statement.bindText(index + 1, value)
            is Boolean -> statement.bindLong(index + 1, if (value) 1L else 0L)
            is Int -> statement.bindLong(index + 1, value.toLong())
            is Long -> statement.bindLong(index + 1, value)
            else -> throw FileOperationException(FileOperationIssue.INVALID_INPUT)
        } }
        block(statement)
    }

    private fun <T> transaction(block: () -> T): T {
        execute("BEGIN IMMEDIATE")
        try { return block().also { execute("COMMIT") } }
        catch (failure: Throwable) {
            try { execute("ROLLBACK") } catch (rollback: Throwable) { if (rollback !== failure) failure.addSuppressed(rollback) }
            throw failure
        }
    }

    private fun bounded(value: String, maximum: Int): String {
        if (value.toByteArray(Charsets.UTF_8).size > maximum) throw FileOperationException(FileOperationIssue.INVALID_INPUT)
        return value
    }
    private fun pageLimit(value: Int) { require(value in 1..FileOperationLimits.PAGE_SIZE) }
    private fun SQLiteStatement.nullableText(index: Int): String? = if (isNull(index)) null else getText(index)

    private fun recordTransitions(phase: FileOperationPhase): Set<FileOperationPhase> = setOf(phase) + when (phase) {
        FileOperationPhase.REQUESTED -> setOf(FileOperationPhase.PREVIEW, FileOperationPhase.CANCELLED, FileOperationPhase.FAILED)
        FileOperationPhase.PREVIEW -> setOf(FileOperationPhase.CONFIRMED, FileOperationPhase.CANCELLED)
        FileOperationPhase.CONFIRMED -> setOf(FileOperationPhase.RUNNING, FileOperationPhase.CANCELLED, FileOperationPhase.FAILED, FileOperationPhase.NEEDS_RECOVERY)
        FileOperationPhase.RUNNING -> setOf(FileOperationPhase.COMPLETED, FileOperationPhase.PARTIAL, FileOperationPhase.CANCELLED,
            FileOperationPhase.FAILED, FileOperationPhase.WAITING_SOURCE_CONFIRMATION, FileOperationPhase.NEEDS_RECOVERY)
        FileOperationPhase.WAITING_SOURCE_CONFIRMATION -> setOf(FileOperationPhase.RUNNING, FileOperationPhase.CANCELLED,
            FileOperationPhase.PARTIAL, FileOperationPhase.NEEDS_RECOVERY)
        FileOperationPhase.PARTIAL -> setOf(FileOperationPhase.RUNNING, FileOperationPhase.NEEDS_RECOVERY, FileOperationPhase.CANCELLED)
        FileOperationPhase.FAILED -> setOf(FileOperationPhase.RUNNING, FileOperationPhase.NEEDS_RECOVERY)
        FileOperationPhase.NEEDS_RECOVERY -> setOf(FileOperationPhase.RUNNING, FileOperationPhase.COMPLETED, FileOperationPhase.PARTIAL,
            FileOperationPhase.CANCELLED, FileOperationPhase.WAITING_SOURCE_CONFIRMATION)
        FileOperationPhase.COMPLETED, FileOperationPhase.CANCELLED -> emptySet()
    }

    private fun itemTransitions(phase: FileItemPhase): Set<FileItemPhase> = setOf(phase) + when (phase) {
        FileItemPhase.REQUESTED -> setOf(FileItemPhase.STAGING, FileItemPhase.APPLYING, FileItemPhase.INDEX_RECONCILED, FileItemPhase.FAILED, FileItemPhase.CANCELLED)
        FileItemPhase.STAGING -> setOf(FileItemPhase.VALIDATED, FileItemPhase.CANCELLED, FileItemPhase.FAILED, FileItemPhase.NEEDS_RECOVERY)
        FileItemPhase.VALIDATED -> setOf(FileItemPhase.APPLYING, FileItemPhase.CANCELLED, FileItemPhase.NEEDS_RECOVERY)
        FileItemPhase.APPLYING -> setOf(FileItemPhase.EXTERNAL_APPLIED, FileItemPhase.COPY_VALIDATED, FileItemPhase.INDEX_RECONCILED,
            FileItemPhase.CANCELLED, FileItemPhase.NEEDS_RECOVERY)
        FileItemPhase.EXTERNAL_APPLIED -> setOf(FileItemPhase.INDEX_RECONCILED, FileItemPhase.NEEDS_RECOVERY)
        FileItemPhase.COPY_VALIDATED -> setOf(FileItemPhase.COPY_INDEX_RECONCILED, FileItemPhase.NEEDS_RECOVERY)
        FileItemPhase.COPY_INDEX_RECONCILED -> setOf(FileItemPhase.APPLYING, FileItemPhase.NEEDS_RECOVERY)
        FileItemPhase.INDEX_RECONCILED -> setOf(FileItemPhase.CANCELLED, FileItemPhase.NEEDS_RECOVERY)
        FileItemPhase.NEEDS_RECOVERY -> setOf(FileItemPhase.APPLYING, FileItemPhase.EXTERNAL_APPLIED, FileItemPhase.COPY_VALIDATED,
            FileItemPhase.INDEX_RECONCILED, FileItemPhase.CANCELLED)
        FileItemPhase.FAILED -> setOf(FileItemPhase.REQUESTED)
        FileItemPhase.CANCELLED -> emptySet()
    }

    companion object {
        const val FILE_NAME = "file-operations-v1.db"

        /** 调用方须进入应用 worker；打开日志不执行或恢复文件变更。 */
        fun open(context: Context): SqliteFileOperationJournal {
            val root = context.noBackupFilesDir.canonicalFile
            return open(File(root, FILE_NAME))
        }

        internal fun open(file: File): SqliteFileOperationJournal {
            if (Files.isSymbolicLink(file.toPath())) throw FileOperationException(FileOperationIssue.RECOVERY_REQUIRED)
            val connection = BundledSQLiteDriver().open(file.absolutePath)
            val journal = SqliteFileOperationJournal(connection)
            try {
                journal.execute("PRAGMA foreign_keys=ON")
                journal.execute("PRAGMA synchronous=FULL")
                val version = journal.prepared("PRAGMA user_version") { check(it.step())
                    it.getLong(0) }
                if (version !in 0L..1L) throw FileOperationException(FileOperationIssue.RECOVERY_REQUIRED)
                if (version == 0L) journal.transaction {
                    journal.execute("""CREATE TABLE file_operations(operationId TEXT NOT NULL PRIMARY KEY,viewId TEXT NOT NULL,
                        libraryRevision INTEGER NOT NULL,totalCount INTEGER NOT NULL,command TEXT NOT NULL,commandBinding TEXT NOT NULL,
                        phase TEXT NOT NULL,selectedCount INTEGER NOT NULL,physicalCount INTEGER NOT NULL,trackImpactCount INTEGER NOT NULL,
                        aliasImpactCount INTEGER NOT NULL,cueImpactCount INTEGER NOT NULL,previewBinding TEXT,
                        sourceDeletionConfirmed INTEGER NOT NULL,schemaVersion INTEGER NOT NULL,commandKind TEXT NOT NULL)""")
                    journal.execute("""CREATE TABLE file_selection(operationId TEXT NOT NULL,ordinal INTEGER NOT NULL,
                        providerId TEXT NOT NULL,trackId TEXT NOT NULL,unitKey TEXT NOT NULL,payload TEXT NOT NULL,schemaVersion INTEGER NOT NULL,
                        PRIMARY KEY(operationId,ordinal),UNIQUE(operationId,providerId,trackId),
                        FOREIGN KEY(operationId) REFERENCES file_operations(operationId) ON DELETE RESTRICT)""")
                    journal.execute("""CREATE TABLE file_units(operationId TEXT NOT NULL,ordinal INTEGER NOT NULL,unitKey TEXT NOT NULL,
                        target TEXT NOT NULL,phase TEXT NOT NULL,artifacts TEXT NOT NULL,issue TEXT,reconcileAfter TEXT,schemaVersion INTEGER NOT NULL,
                        PRIMARY KEY(operationId,unitKey),UNIQUE(operationId,ordinal),
                        FOREIGN KEY(operationId) REFERENCES file_operations(operationId) ON DELETE RESTRICT)""")
                    journal.execute("CREATE INDEX file_units_phase ON file_units(phase,operationId)")
                    journal.execute("""CREATE TABLE file_impacts(operationId TEXT NOT NULL,unitKey TEXT NOT NULL,impactKey TEXT NOT NULL,
                        kind TEXT NOT NULL,sourceId TEXT,payload TEXT NOT NULL,PRIMARY KEY(operationId,unitKey,impactKey),
                        FOREIGN KEY(operationId,unitKey) REFERENCES file_units(operationId,unitKey) ON DELETE RESTRICT)""")
                    journal.execute("CREATE INDEX file_impacts_source ON file_impacts(sourceId,operationId,unitKey)")
                    journal.execute("CREATE INDEX file_impacts_global ON file_impacts(operationId,impactKey,kind)")
                    journal.execute("""CREATE TABLE file_operation_sources(operationId TEXT NOT NULL,sourceId TEXT NOT NULL,
                        PRIMARY KEY(operationId,sourceId),FOREIGN KEY(operationId) REFERENCES file_operations(operationId) ON DELETE RESTRICT)""")
                    journal.execute("CREATE INDEX file_operation_source_fence ON file_operation_sources(sourceId,operationId)")
                    journal.execute("""CREATE TABLE file_published_routes(operationId TEXT NOT NULL,unitKey TEXT NOT NULL,sourceId TEXT NOT NULL,
                        payload TEXT NOT NULL,PRIMARY KEY(operationId,unitKey,sourceId),
                        FOREIGN KEY(operationId,unitKey) REFERENCES file_units(operationId,unitKey) ON DELETE RESTRICT)""")
                    journal.execute("""CREATE TABLE file_index_ignores(providerId TEXT NOT NULL,trackId TEXT NOT NULL,operationId TEXT NOT NULL,unitKey TEXT NOT NULL,
                        PRIMARY KEY(providerId,trackId),FOREIGN KEY(operationId,unitKey) REFERENCES file_units(operationId,unitKey) ON DELETE RESTRICT)""")
                    journal.execute("""CREATE TABLE file_queue_repairs(operationId TEXT NOT NULL,unitKey TEXT NOT NULL,role TEXT NOT NULL,
                        generationId TEXT,baseRevision INTEGER NOT NULL,finalRevision INTEGER NOT NULL,completed INTEGER NOT NULL,schemaVersion INTEGER NOT NULL,
                        PRIMARY KEY(operationId,unitKey,role),FOREIGN KEY(operationId,unitKey) REFERENCES file_units(operationId,unitKey) ON DELETE RESTRICT)""")
                    journal.execute("""CREATE TABLE file_selection_drafts(draftId TEXT NOT NULL PRIMARY KEY,viewId TEXT NOT NULL,
                        libraryRevision INTEGER NOT NULL,totalCount INTEGER NOT NULL,allSelected INTEGER NOT NULL,generation INTEGER NOT NULL,
                        revision INTEGER NOT NULL,ordinalSequence INTEGER NOT NULL,selectedCount INTEGER NOT NULL,retired INTEGER NOT NULL,schemaVersion INTEGER NOT NULL)""")
                    journal.execute("""CREATE TABLE file_draft_tracks(draftId TEXT NOT NULL,generation INTEGER NOT NULL,providerId TEXT NOT NULL,trackId TEXT NOT NULL,
                        ordinal INTEGER NOT NULL,payload TEXT NOT NULL,schemaVersion INTEGER NOT NULL,PRIMARY KEY(draftId,generation,providerId,trackId),
                        UNIQUE(draftId,generation,ordinal),FOREIGN KEY(draftId) REFERENCES file_selection_drafts(draftId) ON DELETE RESTRICT)""")
                    journal.execute("PRAGMA user_version=1")
                }
                // 允许外部工作前先校验真实私有 schema，不能只信版本数字。
                val expected = mapOf(
                    "file_operations" to listOf("operationId", "viewId", "libraryRevision", "totalCount", "command", "commandBinding", "phase",
                        "selectedCount", "physicalCount", "trackImpactCount", "aliasImpactCount", "cueImpactCount", "previewBinding", "sourceDeletionConfirmed", "schemaVersion", "commandKind"),
                    "file_selection" to listOf("operationId", "ordinal", "providerId", "trackId", "unitKey", "payload", "schemaVersion"),
                    "file_units" to listOf("operationId", "ordinal", "unitKey", "target", "phase", "artifacts", "issue", "reconcileAfter", "schemaVersion"),
                    "file_impacts" to listOf("operationId", "unitKey", "impactKey", "kind", "sourceId", "payload"),
                    "file_operation_sources" to listOf("operationId", "sourceId"),
                    "file_published_routes" to listOf("operationId", "unitKey", "sourceId", "payload"),
                    "file_index_ignores" to listOf("providerId", "trackId", "operationId", "unitKey"),
                    "file_queue_repairs" to listOf("operationId", "unitKey", "role", "generationId", "baseRevision", "finalRevision", "completed", "schemaVersion"),
                    "file_selection_drafts" to listOf("draftId", "viewId", "libraryRevision", "totalCount", "allSelected", "generation", "revision", "ordinalSequence", "selectedCount", "retired", "schemaVersion"),
                    "file_draft_tracks" to listOf("draftId", "generation", "providerId", "trackId", "ordinal", "payload", "schemaVersion"),
                )
                for ((table, columns) in expected) {
                    val actual = journal.prepared("PRAGMA table_info($table)") { statement ->
                        buildList { while (statement.step()) add(statement.getText(1)) }
                    }
                    if (actual != columns) throw FileOperationException(FileOperationIssue.RECOVERY_REQUIRED)
                }
                journal.prepared("PRAGMA foreign_key_check") {
                    if (it.step()) throw FileOperationException(FileOperationIssue.RECOVERY_REQUIRED)
                }
                // 未知状态可能代表外部文件已改变；不能因不匹配 pending SQL 就放行保存的播放或新操作。
                val operationPhases = FileOperationPhase.entries.joinToString(",") { "'${it.name}'" }
                val itemPhases = FileItemPhase.entries.joinToString(",") { "'${it.name}'" }
                val commandKinds = FileOperationKind.entries.filter { it != FileOperationKind.DELETE_COMPLETED_MOVE_SOURCE }
                    .joinToString(",") { "'${it.name}'" }
                val invalid = journal.prepared("""SELECT EXISTS(SELECT 1 FROM file_operations WHERE schemaVersion<>1
                    OR phase NOT IN ($operationPhases) OR commandKind NOT IN ($commandKinds)
                    OR sourceDeletionConfirmed NOT IN (0,1) UNION ALL SELECT 1 FROM file_units
                    WHERE schemaVersion<>1 OR phase NOT IN ($itemPhases) LIMIT 1)""") { check(it.step())
                        it.getLong(0) != 0L }
                if (invalid) throw FileOperationException(FileOperationIssue.RECOVERY_REQUIRED)
                return journal
            } catch (failure: Throwable) {
                try { journal.close() } catch (close: Throwable) { if (close !== failure) failure.addSuppressed(close) }
                throw failure
            }
        }
    }
}
