package io.github.onlinemo.meloxwatch.data.backup

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.onlinemo.meloxwatch.data.themes.ThemeLimits
import io.github.onlinemo.meloxwatch.data.themes.ThemeStorageRecords
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.UUID

internal enum class BackupRestorePhase { PREPARING, CONFIGURING, MAIN_PREPARED, FINISHING_FORWARD, ROLLING_BACK, FINISHING_ROLLBACK }
internal enum class BackupConfigDirection { FORWARD, ROLLBACK }
internal enum class BackupResourceOwner { FONT, BACKGROUND, THEME, LYRICS, METADATA_ARTWORK }

internal class BackupRestoreRun(
    val id: String,
    val exportId: String,
    val archiveSha256: String,
    val incomingGeneration: String,
    val beforeGeneration: String,
    val scopes: Set<BackupScope>,
    val mode: BackupMergeMode,
    val beforeMainDigest: String,
    val phase: BackupRestorePhase = BackupRestorePhase.PREPARING,
    val targetGeneration: String? = null,
    val targetMainDigest: String? = null,
    val nativeBindings: Map<String, BackupCell> = emptyMap(),
) {
    init {
        listOf(id, exportId, incomingGeneration, beforeGeneration).forEach(::requireBackupUuid)
        targetGeneration?.let(::requireBackupUuid)
        requireBackupDigest(archiveSha256); requireBackupDigest(beforeMainDigest)
        targetMainDigest?.let(::requireBackupDigest)
        require((targetGeneration == null) == (targetMainDigest == null))
        BackupSelection(scopes)
        require(setOfNotNull(incomingGeneration, beforeGeneration, targetGeneration).size == if (targetGeneration == null) 2 else 3)
        require(nativeBindings.size <= 16)
        validateRunBindings(phase, scopes, targetGeneration != null, nativeBindings)
    }
    override fun toString(): String = "BackupRestoreRun"
}

internal class BackupConfigStep(
    val key: String,
    val before: BackupConfigVersion,
    val forward: BackupRecord?,
    val direction: BackupConfigDirection?,
    val attemptBefore: BackupConfigVersion?,
    val attemptTarget: BackupRecord?,
    val confirmedRevision: Long?,
) {
    override fun toString(): String = "BackupConfigStep"
}

/** Native resource identities are private journal data, never archive grants or diagnostics. */
internal class BackupResourcePlan(
    val owner: BackupResourceOwner,
    val slot: String,
    val resourceId: String,
    metadata: Map<String, BackupCell>,
    val confirmed: Boolean = false,
) {
    val metadata: Map<String, BackupCell> = metadata.toMap()
    init {
        require(slot.isNotBlank() && slot.length <= 128 && slot.none(Char::isISOControl))
        requireBackupDigest(resourceId)
        require(metadata.size <= 32)
        BackupResourcePlanSchema.validate(owner, slot, resourceId, this.metadata)
    }
    override fun toString(): String = "BackupResourcePlan(${owner.name})"
}

internal class BackupRelinkHint(val id: String, val trackId: String, val locationId: String, val archiveSha256: String,
    val record: BackupRecord, val cueReference: io.github.onlinemo.meloxwatch.model.LocalCueReference? = null) {
    init {
        requireBackupUuid(id); requireBackupDigest(archiveSha256)
        require(trackId.isNotBlank() && trackId.length <= 128 && locationId.isNotBlank() && locationId.length <= 128)
        require(record.kind == BackupRecordKind.LOCATION_HINT)
        BackupLogicalSchema.validate(record)
        require(record.key.length <= 128 && trackId.none(Char::isISOControl) && locationId.none(Char::isISOControl))
        require(locationId == "restored-location-" + backupStableId(archiveSha256, BackupBindingFamily.LOCATION.name, record.key))
        require(id == stableUuid(archiveSha256, trackId, locationId))
        require(cueReference == null || !cueReference.isBound && cueReference.trackKey == io.github.onlinemo.meloxwatch.model.TrackKey("local", trackId))
    }
    override fun toString(): String = "BackupRelinkHint"
}

/** One stable private journal survives native CAS, directory commits and the Room COMMIT boundary. */
internal class BackupRestoreJournal private constructor(
    private val directory: BackupPrivateDirectory,
    private val ownerChannel: FileChannel,
) : AutoCloseable {
    private var openedConnection: SQLiteConnection? = null
    private var ownerLock: FileLock? = null
    private val connection: SQLiteConnection
        get() = openedConnection ?: throw BackupException(BackupIssue.RECOVERY_REQUIRED)
    private var connectionClosed = false
    fun registerGeneration(owner: BackupGenerationOwner) {
        transaction { connection.prepare("INSERT INTO generation_owners(generationId,device,inode) VALUES(?,?,?)").use {
            it.bindText(1, owner.id); it.bindLong(2, owner.device); it.bindLong(3, owner.inode); it.step()
        } }
    }
    fun generationOwner(id: String): BackupGenerationOwner? = connection.prepare(
        "SELECT device,inode FROM generation_owners WHERE generationId=?",
    ).use { it.bindText(1, id); if (it.step()) BackupGenerationOwner(id, it.getLong(0), it.getLong(1)) else null }
    fun generationPage(after: String?): List<BackupGenerationOwner> = connection.prepare(
        "SELECT generationId,device,inode FROM generation_owners WHERE (? IS NULL OR generationId>?) ORDER BY generationId LIMIT 100",
    ).use {
        it.bindNullableText(1, after); it.bindNullableText(2, after)
        buildList { while (it.step()) add(BackupGenerationOwner(it.getText(0), it.getLong(1), it.getLong(2))) }
    }
    fun retireGenerationOwner(id: String) {
        val run = readRun()
        if (run != null && id in setOfNotNull(run.incomingGeneration, run.beforeGeneration, run.targetGeneration)) {
            throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        }
        transaction { connection.prepare("DELETE FROM generation_owners WHERE generationId=?").use { it.bindText(1, id); it.step() } }
    }

    fun readRun(): BackupRestoreRun? = connection.prepare(
        "SELECT runId,exportId,archiveSha256,incomingGeneration,beforeGeneration,scopes,mode,beforeMainDigest,phase,targetGeneration,targetMainDigest,nativeBindings FROM restore_run WHERE id=1",
    ).use { statement ->
        if (!statement.step()) null else BackupRestoreRun(statement.getText(0), statement.getText(1), statement.getText(2),
            statement.getText(3), statement.getText(4), decodeScopes(statement.getBlob(5)), enumAt(statement.getLong(6)), statement.getText(7),
            enumAt(statement.getLong(8)), statement.nullableText(9), statement.nullableText(10),
            BackupJsonCodec.decodeValues(statement.getBlob(11)))
    }

    fun begin(run: BackupRestoreRun, configurations: Map<String, BackupConfigVersion>, themeBaseline: ByteArray) {
        if (run.phase != BackupRestorePhase.PREPARING || readRun() != null || configurations.keys != BackupPreferencesCodec.KEYS ||
            themeBaseline.size > 64 * 1024) throw BackupException(BackupIssue.BUSY)
        if (generationOwner(run.incomingGeneration) == null || generationOwner(run.beforeGeneration) == null) {
            throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        }
        ThemeStorageRecords.decode(themeBaseline)
        transaction {
            connection.prepare("INSERT INTO restore_run(id,runId,exportId,archiveSha256,incomingGeneration,beforeGeneration,scopes,mode,beforeMainDigest,phase,targetGeneration,targetMainDigest,nativeBindings,themeBaseline) VALUES(1,?,?,?,?,?,?,?,?,?,NULL,NULL,?,?)").use {
                it.bindText(1, run.id); it.bindText(2, run.exportId); it.bindText(3, run.archiveSha256)
                it.bindText(4, run.incomingGeneration); it.bindText(5, run.beforeGeneration); it.bindBlob(6, encodeScopes(run.scopes))
                it.bindLong(7, run.mode.ordinal.toLong()); it.bindText(8, run.beforeMainDigest); it.bindLong(9, run.phase.ordinal.toLong())
                it.bindBlob(10, BackupJsonCodec.encodeValues(run.nativeBindings)); it.bindBlob(11, themeBaseline); it.step()
            }
            for ((key, value) in configurations) connection.prepare(
                "INSERT INTO config_steps(configKey,beforeRevision,beforePayload) VALUES(?,?,?)",
            ).use {
                if (value.record.key != key) throw BackupException(BackupIssue.INVALID_INPUT)
                it.bindText(1, key); it.bindLong(2, value.revision); it.bindBlob(3, BackupJsonCodec.record(value.record)); it.step()
            }
        }
    }

    fun themeBaseline(): ByteArray = connection.prepare("SELECT themeBaseline FROM restore_run WHERE id=1").use {
        if (!it.step()) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        it.getBlob(0).also { value -> if (value.size > 64 * 1024) throw BackupException(BackupIssue.RECOVERY_REQUIRED) }
    }

    fun configureTargets(runId: String, targets: Map<String, BackupRecord>) {
        requireRun(runId, BackupRestorePhase.PREPARING)
        if (targets.keys != BackupPreferencesCodec.KEYS) throw BackupException(BackupIssue.INVALID_INPUT)
        if (connection.prepare("SELECT COUNT(*) FROM resource_plans WHERE confirmed!=1").use { it.step(); it.getLong(0) } != 0L) {
            throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        }
        transaction {
            for ((key, record) in targets) connection.prepare("UPDATE config_steps SET forwardPayload=? WHERE configKey=? AND forwardPayload IS NULL").use {
                val canonical = canonicalConfigPreference(record)
                if (key != canonical.key) throw BackupException(BackupIssue.INVALID_INPUT)
                it.bindBlob(1, BackupJsonCodec.record(canonical)); it.bindText(2, key); it.step(); requireOneChanged()
            }
            phase(runId, BackupRestorePhase.CONFIGURING)
        }
    }

    fun planConfig(runId: String, direction: BackupConfigDirection, current: BackupConfigVersion, target: BackupRecord) {
        val run = requireRun(runId)
        if (direction == BackupConfigDirection.FORWARD && run.phase != BackupRestorePhase.CONFIGURING ||
            direction == BackupConfigDirection.ROLLBACK && run.phase !in setOf(BackupRestorePhase.ROLLING_BACK, BackupRestorePhase.FINISHING_ROLLBACK)) {
            throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        }
        val canonical = canonicalConfigPreference(target)
        if (canonical.key != current.record.key) throw BackupException(BackupIssue.INVALID_INPUT)
        val step = configs().single { it.key == canonical.key }
        val expectedTarget = if (direction == BackupConfigDirection.FORWARD) step.forward else step.before.record
        if (expectedTarget == null || !expectedTarget.sameContent(canonical)) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        transaction {
            connection.prepare("UPDATE config_steps SET direction=?,attemptRevision=?,attemptBefore=?,attemptTarget=?,confirmedRevision=NULL WHERE configKey=?").use {
                it.bindLong(1, direction.ordinal.toLong()); it.bindLong(2, current.revision); it.bindBlob(3, BackupJsonCodec.record(current.record))
                it.bindBlob(4, BackupJsonCodec.record(canonical)); it.bindText(5, canonical.key); it.step(); requireOneChanged()
            }
        }
    }

    fun confirmConfig(runId: String, actual: BackupConfigVersion) {
        requireRun(runId)
        val step = configs().single { it.key == actual.record.key }
        val before = step.attemptBefore ?: throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        val target = step.attemptTarget ?: throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        val maximum = if (before.revision == Long.MAX_VALUE) before.revision else before.revision + 1L
        if (!actual.disclosuresDisabled || !actual.record.sameContent(target) || actual.revision !in before.revision..maximum ||
            actual.revision == before.revision && !before.record.sameContent(target)) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        transaction {
            connection.prepare("UPDATE config_steps SET confirmedRevision=? WHERE configKey=?").use {
                it.bindLong(1, actual.revision); it.bindText(2, actual.record.key); it.step(); requireOneChanged()
            }
        }
    }

    fun configs(): List<BackupConfigStep> = connection.prepare(
        "SELECT configKey,beforeRevision,beforePayload,forwardPayload,direction,attemptRevision,attemptBefore,attemptTarget,confirmedRevision FROM config_steps ORDER BY configKey",
    ).use { statement ->
        buildList {
            while (statement.step()) {
                if (size == BackupPreferencesCodec.KEYS.size) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
                val key = statement.getText(0)
                if (key !in BackupPreferencesCodec.KEYS) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
                val before = BackupConfigVersion(statement.getLong(1), canonicalJournalRecord(statement.getBlob(2), key))
                val forward = statement.nullableBlob(3)?.let { canonicalJournalRecord(it, key) }
                val direction = statement.nullableLong(4)?.let { enumAt<BackupConfigDirection>(it) }
                val revision = statement.nullableLong(5)
                val attempt = statement.nullableBlob(6)?.let { BackupConfigVersion(revision ?: throw BackupException(BackupIssue.RECOVERY_REQUIRED), canonicalJournalRecord(it, key)) }
                val target = statement.nullableBlob(7)?.let { canonicalJournalRecord(it, key) }
                val confirmed = statement.nullableLong(8)
                if (listOf(direction, revision, attempt, target).count { it != null } !in setOf(0, 4) ||
                    confirmed != null && attempt == null) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
                if (target != null) {
                    val expected = if (direction == BackupConfigDirection.FORWARD) forward else before.record
                    if (expected == null || !target.sameContent(expected)) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
                }
                if (confirmed != null && attempt != null && target != null) {
                    val next = if (attempt.revision == Long.MAX_VALUE) attempt.revision else attempt.revision + 1L
                    if (confirmed !in attempt.revision..next || confirmed == attempt.revision && !attempt.record.sameContent(target)) {
                        throw BackupException(BackupIssue.RECOVERY_REQUIRED)
                    }
                }
                add(BackupConfigStep(key, before, forward, direction, attempt, target, confirmed))
            }
        }.also { if (it.map { step -> step.key }.toSet() != BackupPreferencesCodec.KEYS) throw BackupException(BackupIssue.RECOVERY_REQUIRED) }
    }

    fun planResource(runId: String, plan: BackupResourcePlan) {
        requireRun(runId, BackupRestorePhase.PREPARING)
        val encoded = BackupJsonCodec.encodeValues(plan.metadata)
        if (encoded.size > 32 * 1024) throw BackupException(BackupIssue.TOO_LARGE)
        transaction {
            connection.prepare("INSERT INTO resource_plans(owner,slot,resourceId,metadata,confirmed) VALUES(?,?,?,?,0)").use {
                it.bindLong(1, plan.owner.ordinal.toLong()); it.bindText(2, plan.slot); it.bindText(3, plan.resourceId); it.bindBlob(4, encoded); it.step()
            }
        }
    }

    fun confirmResource(runId: String, owner: BackupResourceOwner, slot: String) {
        requireRun(runId, BackupRestorePhase.PREPARING)
        transaction { connection.prepare("UPDATE resource_plans SET confirmed=1 WHERE owner=? AND slot=?").use {
            it.bindLong(1, owner.ordinal.toLong()); it.bindText(2, slot); it.step(); requireOneChanged()
        } }
    }

    fun resourcePage(owner: BackupResourceOwner, after: String? = null): List<BackupResourcePlan> = connection.prepare(
        "SELECT slot,resourceId,metadata,confirmed FROM resource_plans WHERE owner=? AND (? IS NULL OR slot>?) ORDER BY slot LIMIT 100",
    ).use { statement ->
        statement.bindLong(1, owner.ordinal.toLong()); statement.bindNullableText(2, after); statement.bindNullableText(3, after)
        buildList { while (statement.step()) add(BackupResourcePlan(owner, statement.getText(0), statement.getText(1),
            BackupJsonCodec.decodeValues(statement.getBlob(2)), statement.getLong(3) == 1L)) }
    }

    fun resource(owner: BackupResourceOwner, slot: String): BackupResourcePlan? = connection.prepare(
        "SELECT resourceId,metadata,confirmed FROM resource_plans WHERE owner=? AND slot=?",
    ).use {
        it.bindLong(1, owner.ordinal.toLong()); it.bindText(2, slot)
        if (!it.step()) null else BackupResourcePlan(owner, slot, it.getText(0), BackupJsonCodec.decodeValues(it.getBlob(1)), it.getLong(2) == 1L)
    }

    /** Called inside the preserved Room writer, before its native COMMIT may become durable. */
    fun prepareMain(runId: String, targetGeneration: String, digest: String, nativeBindings: Map<String, BackupCell>) {
        requireRun(runId, BackupRestorePhase.CONFIGURING)
        requireBackupUuid(targetGeneration); requireBackupDigest(digest)
        validateRunBindings(BackupRestorePhase.MAIN_PREPARED, requireRun(runId).scopes, true, nativeBindings)
        if (generationOwner(targetGeneration) == null) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        if (configs().any { it.direction != BackupConfigDirection.FORWARD || it.confirmedRevision == null }) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        transaction {
            connection.prepare("UPDATE restore_run SET targetGeneration=?,targetMainDigest=?,nativeBindings=?,phase=? WHERE id=1 AND runId=?").use {
                it.bindText(1, targetGeneration); it.bindText(2, digest); it.bindBlob(3, BackupJsonCodec.encodeValues(nativeBindings))
                it.bindLong(4, BackupRestorePhase.MAIN_PREPARED.ordinal.toLong()); it.bindText(5, runId); it.step(); requireOneChanged()
            }
        }
    }

    fun beginRollback(runId: String) {
        val run = requireRun(runId)
        if (run.phase == BackupRestorePhase.FINISHING_FORWARD) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        if (run.phase in setOf(BackupRestorePhase.ROLLING_BACK, BackupRestorePhase.FINISHING_ROLLBACK)) return
        transaction { phase(runId, BackupRestorePhase.ROLLING_BACK) }
    }

    fun finishDirection(runId: String, direction: BackupConfigDirection, cleanupBindings: Map<String, BackupCell>) {
        val run = requireRun(runId)
        val expected = if (direction == BackupConfigDirection.FORWARD) BackupRestorePhase.FINISHING_FORWARD else BackupRestorePhase.FINISHING_ROLLBACK
        val admitted = if (direction == BackupConfigDirection.FORWARD) setOf(BackupRestorePhase.MAIN_PREPARED, expected)
            else setOf(BackupRestorePhase.ROLLING_BACK, expected)
        if (run.phase !in admitted) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        if (configs().any { it.direction != direction || it.confirmedRevision == null }) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        val combined = run.nativeBindings + cleanupBindings
        if (combined.size > 16) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        validateRunBindings(expected, run.scopes, run.targetGeneration != null, combined)
        transaction {
            connection.prepare("UPDATE restore_run SET phase=?,nativeBindings=? WHERE id=1 AND runId=?").use {
                it.bindLong(1, expected.ordinal.toLong()); it.bindBlob(2, BackupJsonCodec.encodeValues(combined)); it.bindText(3, runId)
                it.step(); requireOneChanged()
            }
        }
    }

    fun historyDomainMerged(archiveSha256: String, epoch: Long): Boolean = connection.prepare(
        "SELECT 1 FROM history_merge_receipts WHERE archiveSha256=? AND epoch=?",
    ).use { it.bindText(1, archiveSha256); it.bindLong(2, epoch); it.step() }

    fun statisticsDomainMerged(archiveSha256: String, epoch: Long): Boolean = connection.prepare(
        "SELECT 1 FROM statistics_merge_receipts WHERE archiveSha256=? AND epoch=?",
    ).use { it.bindText(1, archiveSha256); it.bindLong(2, epoch); it.step() }

    /** Called only after the real main digest proves COMMIT, including a crash with a lost ack. */
    fun finishHistoryMerge(runId: String) {
        val run = requireRun(runId, BackupRestorePhase.FINISHING_FORWARD)
        if (BackupScope.HISTORY !in run.scopes) return
        fun epoch(name: String): Long = (run.nativeBindings[name] as? BackupCell.Integer)?.value?.takeIf { it >= 0L }
            ?: throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        val beforeHistory = epoch("beforeHistoryEpoch")
        val beforeStatistics = epoch("beforeStatisticsEpoch")
        val history = epoch("historyEpoch")
        val statistics = epoch("statisticsEpoch")
        val historyApplied = (run.nativeBindings["historyMergeApplied"] as? BackupCell.Flag)?.value ?: throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        val statisticsApplied = (run.nativeBindings["statisticsMergeApplied"] as? BackupCell.Flag)?.value ?: throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        val changed = historyApplied || statisticsApplied
        if (history !in beforeHistory..(if (beforeHistory == Long.MAX_VALUE) beforeHistory else beforeHistory + 1L) ||
            statistics !in beforeStatistics..(if (beforeStatistics == Long.MAX_VALUE) beforeStatistics else beforeStatistics + 1L)) {
            throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        }
        if ((history != beforeHistory) != changed || (statistics != beforeStatistics) != changed ||
            run.mode == BackupMergeMode.REPLACE_SELECTED && (!historyApplied || !statisticsApplied)) {
            throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        }
        transaction {
            carryDomain("history_merge_receipts", run, beforeHistory, history)
            carryDomain("statistics_merge_receipts", run, beforeStatistics, statistics)
        }
    }

    // Clearing one domain never revives a receipt belonging to its previous epoch.
    private fun carryDomain(table: String, run: BackupRestoreRun, before: Long, after: Long) {
        require(table in setOf("history_merge_receipts", "statistics_merge_receipts"))
        if (run.mode == BackupMergeMode.MERGE_KEEP_CURRENT) connection.prepare(
            "INSERT OR IGNORE INTO $table(archiveSha256,epoch) SELECT archiveSha256,? FROM $table WHERE epoch=?",
        ).use { it.bindLong(1, after); it.bindLong(2, before); it.step() }
        else connection.execute("DELETE FROM $table")
        connection.prepare("INSERT OR IGNORE INTO $table(archiveSha256,epoch) VALUES(?,?)").use {
            it.bindText(1, run.archiveSha256); it.bindLong(2, after); it.step()
        }
        connection.prepare("DELETE FROM $table WHERE epoch!=?").use { it.bindLong(1, after); it.step() }
    }

    fun planRelinks(runId: String, hints: List<BackupRelinkHint>) {
        val run = requireRun(runId, BackupRestorePhase.PREPARING)
        if (run.scopes.intersect(RoomBackupCapture.MAIN_SCOPES).isEmpty() || hints.size > 100) throw BackupException(BackupIssue.INVALID_INPUT)
        transaction { for (hint in hints) connection.prepare(
            "INSERT INTO pending_relinks(hintId,trackId,locationId,archiveSha256,payload) VALUES(?,?,?,?,?) ON CONFLICT(hintId) DO UPDATE SET payload=excluded.payload",
        ).use {
            if (hint.archiveSha256 != run.archiveSha256) throw BackupException(BackupIssue.INVALID_INPUT)
            bindHint(it, hint); it.step()
        } }
    }
    fun pendingRelinkPage(after: String?): List<BackupRelinkHint> = relinkPage("pending_relinks", after)
    fun relinkPage(after: String?): List<BackupRelinkHint> = relinkPage("relink_hints", after)
    fun relinkHint(id: String): BackupRelinkHint? = connection.prepare(
        "SELECT hintId,trackId,locationId,archiveSha256,payload FROM relink_hints WHERE hintId=?",
    ).use { it.bindText(1, id); if (it.step()) decodeHint(it) else null }
    fun beginRelinkProjection(runId: String) {
        val run = requireRun(runId, BackupRestorePhase.FINISHING_FORWARD)
        if (run.mode == BackupMergeMode.REPLACE_SELECTED && BackupScope.LIBRARY in run.scopes) transaction {
            connection.execute("DELETE FROM relink_hints")
        }
    }
    fun projectRelinks(runId: String, hints: List<BackupRelinkHint>) {
        requireRun(runId, BackupRestorePhase.FINISHING_FORWARD)
        if (hints.size > 100) throw BackupException(BackupIssue.INVALID_INPUT)
        transaction { for (hint in hints) connection.prepare(
            "INSERT INTO relink_hints(hintId,trackId,locationId,archiveSha256,payload) VALUES(?,?,?,?,?) ON CONFLICT(hintId) DO UPDATE SET payload=excluded.payload",
        ).use { bindHint(it, hint); it.step() } }
    }
    fun completeRelink(id: String) = transaction {
        connection.prepare("DELETE FROM relink_hints WHERE hintId=?").use { it.bindText(1, id); it.step() }
    }
    private fun relinkPage(table: String, after: String?): List<BackupRelinkHint> {
        require(table in setOf("pending_relinks", "relink_hints"))
        return connection.prepare("SELECT hintId,trackId,locationId,archiveSha256,payload FROM $table WHERE (? IS NULL OR hintId>?) ORDER BY hintId LIMIT 100").use {
            it.bindNullableText(1, after); it.bindNullableText(2, after)
            buildList { while (it.step()) add(decodeHint(it)) }
        }
    }
    private fun decodeHint(statement: SQLiteStatement): BackupRelinkHint {
        val (record, reference) = BackupRelinkHintCodec.decode(statement.getBlob(4))
        return BackupRelinkHint(statement.getText(0), statement.getText(1), statement.getText(2), statement.getText(3), record, reference)
    }
    private fun bindHint(statement: SQLiteStatement, hint: BackupRelinkHint) {
        statement.bindText(1, hint.id); statement.bindText(2, hint.trackId); statement.bindText(3, hint.locationId)
        statement.bindText(4, hint.archiveSha256);
        statement.bindBlob(5, BackupRelinkHintCodec.encode(hint.record, hint.cueReference))
    }

    fun clearFinished(runId: String) {
        val run = requireRun(runId)
        if (run.phase !in setOf(BackupRestorePhase.FINISHING_FORWARD, BackupRestorePhase.FINISHING_ROLLBACK)) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        val direction = if (run.phase == BackupRestorePhase.FINISHING_FORWARD) BackupConfigDirection.FORWARD else BackupConfigDirection.ROLLBACK
        if (configs().any { it.direction != direction || it.confirmedRevision == null }) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        transaction {
            for (id in setOfNotNull(run.incomingGeneration, run.beforeGeneration, run.targetGeneration)) connection.prepare(
                "DELETE FROM generation_owners WHERE generationId=?",
            ).use { it.bindText(1, id); it.step() }
            connection.execute("DELETE FROM config_steps"); connection.execute("DELETE FROM resource_plans"); connection.execute("DELETE FROM pending_relinks")
            connection.prepare("DELETE FROM restore_run WHERE id=1 AND runId=?").use { it.bindText(1, runId); it.step(); requireOneChanged() }
        }
    }

    private fun requireRun(id: String, phase: BackupRestorePhase? = null): BackupRestoreRun = (readRun()
        ?: throw BackupException(BackupIssue.RECOVERY_REQUIRED)).also {
        if (it.id != id || phase != null && it.phase != phase) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
    }
    private fun phase(runId: String, phase: BackupRestorePhase) = connection.prepare("UPDATE restore_run SET phase=? WHERE id=1 AND runId=?").use {
        it.bindLong(1, phase.ordinal.toLong()); it.bindText(2, runId); it.step(); requireOneChanged()
    }
    private fun requireOneChanged() { if (connection.prepare("SELECT changes()").use { it.step(); it.getLong(0) } != 1L) throw BackupException(BackupIssue.RECOVERY_REQUIRED) }
    private fun canonicalJournalRecord(bytes: ByteArray, key: String): BackupRecord {
        if (bytes.size > BackupLimits.RECORD_BYTES) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        val record = BackupJsonCodec.readRecord(bytes)
        val canonical = canonicalConfigPreference(record)
        if (record.key != key || !record.sameContent(canonical)) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        return record
    }

    /** A missing run with surviving steps is a damaged journal, never a fresh startup. */
    private fun verifyState() {
        for (table in listOf("history_merge_receipts", "statistics_merge_receipts")) {
            val badReceipts = connection.prepare("SELECT COUNT(*) FROM $table WHERE archiveSha256 GLOB '*[^0-9a-f]*' OR length(archiveSha256)!=64").use {
                if (!it.step()) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
                it.getLong(0)
            }
            if (badReceipts != 0L) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        }
        // Even a journal with no pending run must have valid owners and active relink projections.
        var afterOwner: String? = null
        while (true) {
            val page = generationPage(afterOwner)
            if (page.isEmpty()) break
            afterOwner = page.last().id
        }
        for (table in listOf("pending_relinks", "relink_hints")) {
            var after: String? = null
            while (true) {
                val page = relinkPage(table, after)
                if (page.isEmpty()) break
                after = page.last().id
            }
        }
        val run = readRun()
        if (run == null) {
            val orphaned = connection.prepare("SELECT (SELECT COUNT(*) FROM config_steps)+(SELECT COUNT(*) FROM resource_plans)+(SELECT COUNT(*) FROM pending_relinks)").use {
                if (!it.step()) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
                it.getLong(0)
            }
            if (orphaned != 0L) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
            return
        }
        val steps = configs()
        ThemeStorageRecords.decode(themeBaseline())
        for (id in setOfNotNull(run.incomingGeneration, run.beforeGeneration, run.targetGeneration)) {
            if (generationOwner(id) == null) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        }
        if (run.phase in setOf(BackupRestorePhase.PREPARING, BackupRestorePhase.CONFIGURING) && run.targetMainDigest != null ||
            run.phase in setOf(BackupRestorePhase.MAIN_PREPARED, BackupRestorePhase.FINISHING_FORWARD) && run.targetMainDigest == null) {
            throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        }
        if (run.phase != BackupRestorePhase.PREPARING && run.phase !in setOf(BackupRestorePhase.ROLLING_BACK, BackupRestorePhase.FINISHING_ROLLBACK) &&
            steps.any { it.forward == null }) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        val finalDirection = when (run.phase) {
            BackupRestorePhase.MAIN_PREPARED, BackupRestorePhase.FINISHING_FORWARD -> BackupConfigDirection.FORWARD
            BackupRestorePhase.FINISHING_ROLLBACK -> BackupConfigDirection.ROLLBACK
            else -> null
        }
        if (finalDirection != null && steps.any { it.direction != finalDirection || it.confirmedRevision == null }) {
            throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        }
        // Decode every bounded page now. Invalid owner/slot/metadata must block before admission.
        for (owner in BackupResourceOwner.entries) {
            var after: String? = null
            while (true) {
                val page = resourcePage(owner, after)
                if (page.isEmpty()) break
                after = page.last().slot
            }
        }
    }
    private fun <T> transaction(action: () -> T): T {
        connection.execute("BEGIN IMMEDIATE")
        var committed = false
        var failure: Throwable? = null
        try {
            val result = action()
            connection.execute("COMMIT")
            committed = true
            syncBackupDirectory(directory.root)
            return result
        } catch (error: Throwable) { failure = error; throw error }
        finally { if (!committed) try { connection.execute("ROLLBACK") }
        catch (closing: Throwable) { val original = failure; if (original == null) throw closing else if (original !== closing) original.addSuppressed(closing) } }
    }

    /** Only an already verified v1 table is replaced; receipt bytes and owner ordinals are retained. */
    private fun migrateLegacyResourceOwners() {
        requireBackupSpace(directory.root, Files.size(directory.child("restore.db").toPath()) + 64 * 1024L)
        transaction {
            connection.execute("ALTER TABLE resource_plans RENAME TO legacy_resource_plans")
            connection.execute(SCHEMA.single { it.startsWith("CREATE TABLE resource_plans(") })
            connection.execute("INSERT INTO resource_plans(owner,slot,resourceId,metadata,confirmed) " +
                "SELECT owner,slot,resourceId,metadata,confirmed FROM legacy_resource_plans")
            connection.execute("DROP TABLE legacy_resource_plans")
            connection.execute("PRAGMA user_version=2")
        }
    }

    override fun close() {
        var failure: Throwable? = null
        fun closeRemaining(action: () -> Unit) {
            try { action() }
            catch (closing: Throwable) {
                val original = failure
                if (original == null) failure = closing
                else if (original !== closing) original.addSuppressed(closing)
            }
        }
        if (!connectionClosed) closeRemaining { openedConnection?.close(); connectionClosed = true }
        // Channel close can release a lock even when the explicit release acknowledgement failed.
        ownerLock?.let { held -> if (held.isValid) closeRemaining { held.release() } }
        if (ownerChannel.isOpen) closeRemaining { ownerChannel.close() }
        failure?.let { throw it }
    }

    companion object {
        fun open(directory: BackupPrivateDirectory): BackupRestoreJournal = open(directory, onOpened = {})

        fun open(directory: BackupPrivateDirectory, onOpened: (BackupRestoreJournal) -> Unit): BackupRestoreJournal {
            val channel = FileChannel.open(directory.child("restore-owner.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            val journal = BackupRestoreJournal(directory, channel)
            try {
                onOpened(journal)
                journal.ownerLock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { throw BackupException(BackupIssue.BUSY) }
                    ?: throw BackupException(BackupIssue.BUSY)
                val file = directory.child("restore.db")
                val created = !Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
                if (created) BackupCreatedFile.create(file).use { it.channel.force(true) }
                val identity = BackupFileIdentity.capture(file)
                val opened = BundledSQLiteDriver().open(file.absolutePath)
                journal.openedConnection = opened
                opened.execute("PRAGMA journal_mode=DELETE"); opened.execute("PRAGMA synchronous=FULL")
                opened.execute("PRAGMA cache_size=-512"); opened.execute("PRAGMA temp_store=FILE")
                opened.execute("PRAGMA max_page_count=131072")
                val version = opened.prepare("PRAGMA user_version").use { it.step(); it.getLong(0) }
                if (created) {
                    if (version != 0L) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
                    opened.execute("BEGIN IMMEDIATE")
                    try {
                        for (sql in SCHEMA) opened.execute(sql)
                        opened.execute("PRAGMA user_version=2")
                        opened.execute("COMMIT")
                    } catch (failure: Throwable) {
                        try { opened.execute("ROLLBACK") } catch (closing: Throwable) { if (failure !== closing) failure.addSuppressed(closing) }
                        throw failure
                    }
                } else if (version == 1L) {
                    verifySchema(opened, LEGACY_SCHEMA)
                    journal.verifyState()
                    if (opened.prepare("SELECT COUNT(*) FROM resource_plans WHERE owner NOT BETWEEN 0 AND 3").use {
                            if (!it.step()) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
                            it.getLong(0)
                        } != 0L) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
                    journal.migrateLegacyResourceOwners()
                } else if (version != 2L) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
                verifySchema(opened)
                identity.requireSame(file)
                syncBackupDirectory(directory.root)
                journal.verifyState()
                return journal
            } catch (failure: Throwable) {
                try { journal.close() } catch (closing: Throwable) { if (closing !== failure) failure.addSuppressed(closing) }
                throw if (failure is BackupException && failure.issue == BackupIssue.BUSY) failure
                    else BackupException(BackupIssue.RECOVERY_REQUIRED, failure)
            }
        }
        private fun verifySchema(connection: SQLiteConnection, schema: List<String> = SCHEMA) {
            val actual = LinkedHashMap<String, String>()
            connection.prepare("SELECT name,sql FROM sqlite_schema WHERE name NOT LIKE 'sqlite_%' AND type IN ('table','view','trigger','index') ORDER BY name").use {
                while (it.step()) {
                    if (it.isNull(1)) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
                    actual[it.getText(0)] = normalizedSql(it.getText(1))
                }
            }
            val expected = schema.associate { sql ->
                val name = Regex("CREATE TABLE ([a-z_]+)").find(sql)?.groupValues?.get(1)
                    ?: throw BackupException(BackupIssue.RECOVERY_REQUIRED)
                name to normalizedSql(sql)
            }
            if (actual != expected) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
            connection.prepare("PRAGMA quick_check(1)").use {
                if (!it.step() || it.getText(0) != "ok" || it.step()) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
            }
        }
        private fun normalizedSql(sql: String): String = sql.trim().replace(Regex("\\s+"), " ").lowercase(java.util.Locale.ROOT)
        private val SCHEMA = listOf(
            "CREATE TABLE generation_owners(generationId TEXT NOT NULL PRIMARY KEY CHECK(length(generationId)=36),device INTEGER NOT NULL CHECK(device>=0),inode INTEGER NOT NULL CHECK(inode>=0)) WITHOUT ROWID, STRICT",
            "CREATE TABLE restore_run(id INTEGER NOT NULL PRIMARY KEY CHECK(id=1),runId TEXT NOT NULL,exportId TEXT NOT NULL,archiveSha256 TEXT NOT NULL,incomingGeneration TEXT NOT NULL,beforeGeneration TEXT NOT NULL,scopes BLOB NOT NULL CHECK(length(scopes) BETWEEN 1 AND 8),mode INTEGER NOT NULL CHECK(mode IN (0,1)),beforeMainDigest TEXT NOT NULL,phase INTEGER NOT NULL CHECK(phase BETWEEN 0 AND 5),targetGeneration TEXT,targetMainDigest TEXT,nativeBindings BLOB NOT NULL CHECK(length(nativeBindings)<=1048576),themeBaseline BLOB NOT NULL CHECK(length(themeBaseline)<=65536),CHECK((targetGeneration IS NULL)=(targetMainDigest IS NULL))) STRICT",
            "CREATE TABLE config_steps(configKey TEXT NOT NULL PRIMARY KEY,beforeRevision INTEGER NOT NULL CHECK(beforeRevision>=0),beforePayload BLOB NOT NULL CHECK(length(beforePayload)<=1048576),forwardPayload BLOB CHECK(length(forwardPayload)<=1048576),direction INTEGER CHECK(direction IN (0,1)),attemptRevision INTEGER CHECK(attemptRevision>=0),attemptBefore BLOB CHECK(length(attemptBefore)<=1048576),attemptTarget BLOB CHECK(length(attemptTarget)<=1048576),confirmedRevision INTEGER CHECK(confirmedRevision>=0)) WITHOUT ROWID, STRICT",
            "CREATE TABLE resource_plans(owner INTEGER NOT NULL CHECK(owner BETWEEN 0 AND 4),slot TEXT NOT NULL CHECK(length(slot) BETWEEN 1 AND 128),resourceId TEXT NOT NULL CHECK(length(resourceId)=64),metadata BLOB NOT NULL CHECK(length(metadata)<=32768),confirmed INTEGER NOT NULL CHECK(confirmed IN (0,1)),PRIMARY KEY(owner,slot)) WITHOUT ROWID, STRICT",
            "CREATE TABLE history_merge_receipts(archiveSha256 TEXT NOT NULL CHECK(length(archiveSha256)=64),epoch INTEGER NOT NULL CHECK(epoch>=0),PRIMARY KEY(archiveSha256,epoch)) WITHOUT ROWID, STRICT",
            "CREATE TABLE statistics_merge_receipts(archiveSha256 TEXT NOT NULL CHECK(length(archiveSha256)=64),epoch INTEGER NOT NULL CHECK(epoch>=0),PRIMARY KEY(archiveSha256,epoch)) WITHOUT ROWID, STRICT",
            "CREATE TABLE pending_relinks(hintId TEXT NOT NULL PRIMARY KEY CHECK(length(hintId)=36),trackId TEXT NOT NULL CHECK(length(trackId) BETWEEN 1 AND 128),locationId TEXT NOT NULL CHECK(length(locationId) BETWEEN 1 AND 128),archiveSha256 TEXT NOT NULL CHECK(length(archiveSha256)=64),payload BLOB NOT NULL CHECK(length(payload)<=1048576)) WITHOUT ROWID, STRICT",
            "CREATE TABLE relink_hints(hintId TEXT NOT NULL PRIMARY KEY CHECK(length(hintId)=36),trackId TEXT NOT NULL CHECK(length(trackId) BETWEEN 1 AND 128),locationId TEXT NOT NULL CHECK(length(locationId) BETWEEN 1 AND 128),archiveSha256 TEXT NOT NULL CHECK(length(archiveSha256)=64),payload BLOB NOT NULL CHECK(length(payload)<=1048576)) WITHOUT ROWID, STRICT",
        )
        private val LEGACY_SCHEMA = SCHEMA.map { it.replace("CHECK(owner BETWEEN 0 AND 4)", "CHECK(owner BETWEEN 0 AND 3)") }
    }
}

private fun encodeScopes(scopes: Set<BackupScope>): ByteArray = scopes.sortedBy { it.ordinal }.map { it.ordinal.toByte() }.toByteArray()
private fun validateRunBindings(phase: BackupRestorePhase, scopes: Set<BackupScope>, hasTarget: Boolean, values: Map<String, BackupCell>) {
    val main = setOf("mainMissingReferences", "resourceMissingReferences")
    val history = setOf("beforeHistoryEpoch", "beforeStatisticsEpoch", "historyEpoch", "statisticsEpoch", "historyMergeApplied", "statisticsMergeApplied")
    val cleanup = setOf("themeCleanupRevision", "themeCleanupAddedCount")
    val mainFields = if (hasTarget) main + (if (BackupScope.HISTORY in scopes) history else emptySet()) else emptySet()
    val expected = mainFields + (if (phase in setOf(BackupRestorePhase.FINISHING_FORWARD, BackupRestorePhase.FINISHING_ROLLBACK)) cleanup else emptySet())
    if (values.keys != expected || phase in setOf(BackupRestorePhase.PREPARING, BackupRestorePhase.CONFIGURING) && hasTarget) {
        throw BackupException(BackupIssue.RECOVERY_REQUIRED)
    }
    for ((key, cell) in values) {
        val valid = if (key in setOf("historyMergeApplied", "statisticsMergeApplied")) cell is BackupCell.Flag
            else cell is BackupCell.Integer && cell.value >= 0L && (key != "themeCleanupAddedCount" || cell.value <= ThemeLimits.INSTALLED_THEMES)
        if (!valid) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
    }
}
private fun decodeScopes(bytes: ByteArray): Set<BackupScope> {
    if (bytes.isEmpty() || bytes.size > BackupScope.entries.size || bytes.toSet().size != bytes.size) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
    return bytes.map { BackupScope.entries.getOrNull(it.toInt()) ?: throw BackupException(BackupIssue.RECOVERY_REQUIRED) }.toSet()
}
private inline fun <reified T : Enum<T>> enumAt(value: Long): T = enumValues<T>().getOrNull(value.takeIf { it in 0L..Int.MAX_VALUE.toLong() }?.toInt()
    ?: throw BackupException(BackupIssue.RECOVERY_REQUIRED)) ?: throw BackupException(BackupIssue.RECOVERY_REQUIRED)
private fun requireBackupUuid(value: String) { require(UUID.fromString(value).toString() == value) }
private fun requireBackupDigest(value: String) { require(value.matches(Regex("[0-9a-f]{64}"))) }
private fun SQLiteStatement.nullableText(index: Int): String? = if (isNull(index)) null else getText(index)
private fun SQLiteStatement.nullableLong(index: Int): Long? = if (isNull(index)) null else getLong(index)
private fun SQLiteStatement.nullableBlob(index: Int): ByteArray? = if (isNull(index)) null else getBlob(index)
