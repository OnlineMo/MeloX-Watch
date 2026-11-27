package io.github.onlinemo.meloxwatch.data.backup

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.onlinemo.meloxwatch.model.TrackKey
import io.github.onlinemo.meloxwatch.model.LocalCueReference
import io.github.onlinemo.meloxwatch.data.cue.CueReferenceCodec
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsEncodingChoice
import io.github.onlinemo.meloxwatch.data.metadata.LocalMetadataAssociationRow
import io.github.onlinemo.meloxwatch.data.metadata.MetadataAssociation
import io.github.onlinemo.meloxwatch.data.metadata.MetadataProvenance
import io.github.onlinemo.meloxwatch.data.metadata.MetadataPurpose
import io.github.onlinemo.meloxwatch.data.metadata.MetadataStoreException
import io.github.onlinemo.meloxwatch.data.metadata.decode
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** Native preimages retain the exact asset UUID and provenance bytes; content hashes are not identities. */
internal class BackupMetadataBeforeRow(val trackId: String, val purpose: MetadataPurpose,
    val row: LocalMetadataAssociationRow)

/** A separate private database indexes portable rows; no archive SQL or storage table names execute. */
internal class SqliteBackupStage private constructor(
    val directory: BackupPrivateDirectory,
    private val connection: SQLiteConnection,
    private val fileIdentity: BackupFileIdentity,
    private val checkCancelled: () -> Unit,
) : BackupArchiveStaging, BackupSnapshot {
    private var transaction = false
    private var closed = false
    private var phase = "EMPTY"
    private var logicalBytes = 0L
    private var resourceBytes = 0L
    private var capturedHeader: BackupHeader? = null
    private val derivedTables = LinkedHashMap<String, Long>()
    private var metadataBeforeSealed = false
    private var verifiedLease = false
    fun checkActive() = checkCancelled()
    fun clearActualIds() { requireVerifiedLease(); connection.execute("DELETE FROM actual_ids") }
    fun clearBindings() { checkCancelled(); connection.execute("DELETE FROM restore_bindings") }

    /** Derived tables live only in the existing rows.db lease and never extend sealed schema v1. */
    fun beginMetadataBefore() {
        requireVerifiedLease()
        if (METADATA_BEFORE in derivedTables) throw BackupException(BackupIssue.BUSY)
        createDerived(METADATA_BEFORE, "CREATE TABLE main.$METADATA_BEFORE(" +
            "trackId TEXT NOT NULL COLLATE BINARY,purpose TEXT NOT NULL,schemaVersion INTEGER NOT NULL," +
            "revision INTEGER NOT NULL,assetId TEXT,sha256 TEXT,byteCount INTEGER,provenance TEXT," +
            "PRIMARY KEY(purpose,trackId)) WITHOUT ROWID")
        metadataBeforeSealed = false
    }

    fun appendMetadataBefore(rows: List<BackupMetadataBeforeRow>) {
        if (METADATA_BEFORE !in derivedTables || metadataBeforeSealed || rows.size > BackupLimits.ROWS_PER_CHUNK) {
            throw BackupException(BackupIssue.INCOMPLETE_ARCHIVE)
        }
        var batchBytes = 0L
        for (entry in rows) {
            checkCancelled()
            val key = TrackKey(TrackKey.LOCAL_PROVIDER, entry.trackId)
            decodeMetadataBefore(entry.row, key, entry.purpose)
            val row = entry.row
            val bytes = 128L + entry.trackId.toByteArray(Charsets.UTF_8).size + entry.purpose.name.length +
                listOf(row.assetId, row.sha256, row.provenance).sumOf { it?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L }
            batchBytes = backupAdd(batchBytes, bytes, BackupLimits.CHUNK_BYTES.toLong())
        }
        reserveDerived(METADATA_BEFORE, batchBytes)
        for (entry in rows) {
            checkCancelled()
            val key = TrackKey(TrackKey.LOCAL_PROVIDER, entry.trackId)
            val row = entry.row
            connection.prepare("INSERT INTO main.$METADATA_BEFORE(trackId,purpose,schemaVersion,revision,assetId,sha256,byteCount,provenance) VALUES(?,?,?,?,?,?,?,?)").use {
                it.bindText(1, entry.trackId); it.bindText(2, entry.purpose.name)
                it.bindLong(3, row.schemaVersion); it.bindLong(4, row.revision)
                it.bindNullableText(5, row.assetId); it.bindNullableText(6, row.sha256)
                it.bindNullableLong(7, row.byteCount); it.bindNullableText(8, row.provenance); it.step()
            }
            val stored = readMetadataBefore(key, entry.purpose) ?: throw BackupException(BackupIssue.RECOVERY_REQUIRED)
            if (stored.schemaVersion != row.schemaVersion || stored.revision != row.revision ||
                stored.assetId != row.assetId || stored.sha256 != row.sha256 || stored.byteCount != row.byteCount ||
                stored.provenance != row.provenance) throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
        }
        requireStageFile()
    }

    fun sealMetadataBefore(expectedRows: Long) {
        checkCancelled()
        if (METADATA_BEFORE !in derivedTables || metadataBeforeSealed || expectedRows < 0L ||
            scalar("SELECT COUNT(*) FROM main.$METADATA_BEFORE") != expectedRows) {
            throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
        }
        requireStageFile()
        metadataBeforeSealed = true
    }

    fun metadataBefore(key: TrackKey, purpose: MetadataPurpose): MetadataAssociation? {
        requireMetadataBefore()
        return readMetadataBefore(key, purpose)?.let { decodeMetadataBefore(it, key, purpose) }
    }

    fun metadataBeforePage(purpose: MetadataPurpose, after: String?): List<MetadataAssociation> {
        requireMetadataBefore()
        val continuation = if (after == null) "" else " AND trackId>?"
        val ids = connection.prepare("SELECT trackId FROM main.$METADATA_BEFORE WHERE purpose=?$continuation ORDER BY trackId COLLATE BINARY LIMIT 100").use {
            it.bindText(1, purpose.name)
            if (after != null) it.bindText(2, after)
            buildList { while (it.step()) { checkCancelled(); add(it.getText(0)) } }
        }
        return ids.map { id -> metadataBefore(TrackKey(TrackKey.LOCAL_PROVIDER, id), purpose)
            ?: throw BackupException(BackupIssue.RECOVERY_REQUIRED) }
    }

    fun endMetadataBefore() {
        dropDerived(METADATA_BEFORE)
        metadataBeforeSealed = false
    }

    private fun requireMetadataBefore() {
        checkCancelled()
        if (!transaction || !metadataBeforeSealed || METADATA_BEFORE !in derivedTables) {
            throw BackupException(BackupIssue.INCOMPLETE_ARCHIVE)
        }
        requireStageFile()
    }

    private fun readMetadataBefore(key: TrackKey, purpose: MetadataPurpose): LocalMetadataAssociationRow? = connection.prepare(
        "SELECT CASE WHEN typeof(schemaVersion)='integer' AND typeof(revision)='integer' AND " +
            "typeof(assetId) IN ('text','null') AND typeof(sha256) IN ('text','null') AND " +
            "typeof(byteCount) IN ('integer','null') AND typeof(provenance) IN ('text','null') THEN 1 ELSE 0 END," +
            "schemaVersion,revision,substr(assetId,1,37),substr(sha256,1,65),byteCount," +
            "substr(provenance,1,${MetadataProvenance.PROVENANCE_BYTES + 1}) FROM main.$METADATA_BEFORE WHERE trackId=? AND purpose=? LIMIT 1",
    ).use {
        it.bindText(1, key.trackId); it.bindText(2, purpose.name)
        if (!it.step()) null else LocalMetadataAssociationRow(it.getLong(0) == 1L, it.getLong(1), it.getLong(2),
            if (it.isNull(3)) null else it.getText(3), if (it.isNull(4)) null else it.getText(4),
            if (it.isNull(5)) null else it.getLong(5), if (it.isNull(6)) null else it.getText(6))
    }

    private fun decodeMetadataBefore(row: LocalMetadataAssociationRow, key: TrackKey,
        purpose: MetadataPurpose): MetadataAssociation = try { row.decode(key, purpose) }
    catch (failure: MetadataStoreException) { throw BackupException(BackupIssue.RECOVERY_REQUIRED, failure) }

    fun beginResourceInventory() {
        resetDerived("preview_resources", "CREATE TABLE main.preview_resources(resourceId TEXT NOT NULL PRIMARY KEY) WITHOUT ROWID")
    }
    fun inventoryResource(kind: BackupResourceKind, sha256: String) {
        checkCancelled()
        if (!sha256.matches(Regex("[0-9a-f]{64}"))) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        reserveDerived("preview_resources", 128L)
        connection.prepare("INSERT OR IGNORE INTO main.preview_resources(resourceId) VALUES(?)").use {
            it.bindText(1, BackupLogicalSchema.resourceId(kind, sha256)); it.step()
        }
    }
    fun inventorySize(): Long = scalar("SELECT COUNT(*) FROM main.preview_resources")
    fun inResourceInventory(id: String): Boolean = connection.prepare("SELECT 1 FROM main.preview_resources WHERE resourceId=?").use {
        it.bindText(1, id); it.step()
    }
    fun hasTrackLocation(trackId: String, locationId: String): Boolean = connection.prepare(
        "SELECT 1 FROM logical_edges t JOIN logical_edges l ON l.fromKind=t.fromKind AND l.recordKey=t.recordKey WHERE t.fromKind=? AND t.field='trackId' AND t.targetKey=? AND l.field='locationId' AND l.targetKey=? LIMIT 1",
    ).use { it.bindLong(1, BackupRecordKind.TRACK_LOCATION.ordinal.toLong()); it.bindText(2, trackId); it.bindText(3, locationId); it.step() }
    fun hasAnyTrackLocation(trackId: String): Boolean = connection.prepare(
        "SELECT 1 FROM logical_edges WHERE fromKind=? AND field='trackId' AND targetKey=? LIMIT 1",
    ).use { it.bindLong(1, BackupRecordKind.TRACK_LOCATION.ordinal.toLong()); it.bindText(2, trackId); it.step() }
    fun beginRelinkReferences() {
        resetDerived("relink_reference_tracks", "CREATE TABLE main.relink_reference_tracks(trackId TEXT NOT NULL PRIMARY KEY,missingQueue INTEGER NOT NULL,cueReference TEXT) WITHOUT ROWID")
    }
    fun referenceTrack(trackId: String, missingQueue: Boolean, reference: LocalCueReference? = null) {
        checkCancelled()
        if (trackId.isBlank() || trackId.length > 128 || trackId.any(Char::isISOControl)) throw BackupException(BackupIssue.INVALID_REFERENCE)
        val encoded = reference?.portable()?.let(CueReferenceCodec::encode)
        reserveDerived("relink_reference_tracks", 128L + trackId.toByteArray(Charsets.UTF_8).size +
            (encoded?.toByteArray(Charsets.UTF_8)?.size ?: 0))
        val previous = connection.prepare("SELECT cueReference FROM main.relink_reference_tracks WHERE trackId=?").use {
            it.bindText(1, trackId);
            if (it.step() && !it.isNull(0)) it.getText(0) else null
        }
        if (previous != null && encoded != null && previous != encoded) throw BackupException(BackupIssue.INVALID_REFERENCE)
        connection.prepare("INSERT INTO main.relink_reference_tracks(trackId,missingQueue,cueReference) VALUES(?,?,?) ON CONFLICT(trackId) DO UPDATE SET missingQueue=MAX(missingQueue,excluded.missingQueue),cueReference=COALESCE(cueReference,excluded.cueReference)").use {
            it.bindText(1, trackId);
            it.bindLong(2, if (missingQueue) 1L else 0L);
            it.bindNullableText(3, encoded);
            it.step()
        }
    }
    fun referenceTrackPage(after: String?): List<BackupRelinkTrack> {
        val continuation = if (after == null) "" else " WHERE trackId>?"
        return connection.prepare(
            "SELECT trackId,missingQueue,cueReference FROM main.relink_reference_tracks$continuation ORDER BY trackId LIMIT 100",
        ).use {
            if (after != null) it.bindText(1, after)
            buildList {
                while (it.step()) {
                    checkCancelled();
                    add(BackupRelinkTrack(it.getText(0), it.getLong(1) == 1L,
                        if (it.isNull(2)) null else CueReferenceCodec.decode(it.getText(2), portableOnly = true)))
                }
            }
        }
    }

    fun bindingDigest(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(bytes: ByteArray) {
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
        }
        for (family in BackupBindingFamily.entries) {
            add(family.name.toByteArray(Charsets.US_ASCII))
            var after: String? = null
            while (true) {
                val page = bindingPage(family, after)
                if (page.isEmpty()) break
                for (binding in page) {
                    checkCancelled()
                    add(BackupJsonCodec.encodeValues(mapOf("incomingId" to BackupCell.Text(binding.incomingId),
                        "actualId" to BackupCell.Text(binding.actualId), "verified" to BackupCell.Flag(binding.verified)), checkCancelled))
                    add(BackupJsonCodec.encodeValues(binding.fences, checkCancelled))
                }
                after = page.last().incomingId
            }
        }
        return backupHex(digest.digest())
    }
    override val header: BackupHeader get() = capturedHeader ?: throw BackupException(BackupIssue.INCOMPLETE_ARCHIVE)

    override fun begin(header: BackupHeader) {
        if (phase != "EMPTY") throw BackupException(BackupIssue.BUSY)
        checkCancelled()
        capturedHeader = header
        connection.prepare("INSERT INTO stage_meta(id,header,phase) VALUES(1,?,'WRITING')").use {
            it.bindBlob(1, BackupJsonCodec.header(header, checkCancelled)); it.step()
        }
        connection.execute("BEGIN IMMEDIATE")
        transaction = true
        phase = "WRITING"
    }

    override fun record(record: BackupRecord) {
        requireWriting()
        if (record.kind.scope !in header.scopes) throw BackupException(BackupIssue.INVALID_INPUT)
        if (record.kind in BackupMetadataAssociations.KINDS && header.sourceDatabaseVersion < 9) throw BackupException(BackupIssue.UNSUPPORTED_VERSION)
        val payload = BackupJsonCodec.record(record, checkCancelled)
        requireBackupSpace(directory.root, payload.size.toLong() + 8192L)
        logicalBytes = backupAdd(logicalBytes, payload.size.toLong(), BackupLimits.EXPANDED_LOGICAL_BYTES)
        connection.prepare("INSERT INTO logical_records(kind,recordKey,scope,payload,bytes) VALUES(?,?,?,?,?)").use {
            it.bindLong(1, record.kind.ordinal.toLong()); it.bindText(2, record.key)
            it.bindLong(3, record.kind.scope.ordinal.toLong()); it.bindBlob(4, payload); it.bindLong(5, payload.size.toLong()); it.step()
        }
        addLogicalIndexes(record)
        if (record.kind == BackupRecordKind.RESOURCE) {
            val descriptor = BackupLogicalSchema.resourceDescriptor(record)
            resourceBytes = backupAdd(resourceBytes, descriptor.bytes, BackupLimits.RESOURCE_ARCHIVE_BYTES)
            insertResource(descriptor, sealed = false)
        }
    }

    /** The same deterministic projection builds captured rows and the verification-only tables. */
    private fun addLogicalIndexes(record: BackupRecord, prefix: String = "") {
        addReferences(record, prefix)
        addResourceUsage(record, prefix)
        when (record.kind) {
            BackupRecordKind.TRACK -> ordinal("track-added", "library", record.long("firstAddedSequence"), record.key, prefix)
            BackupRecordKind.NODE_HINT -> connection.prepare("INSERT INTO ${prefix}node_parents(child,parent) VALUES(?,?)").use {
                it.bindText(1, record.key); it.bindNullableText(2, record.optionalText("parentNodeId")); it.step()
            }
            BackupRecordKind.FAVORITES_MEMBER, BackupRecordKind.PLAYLIST_MEMBER -> {
                ordinal("playlist", record.text("playlistId"), record.long("ordinal"), record.key, prefix)
                val identity = if (record.optionalText("trackId") != null) backupStableId("track", record.text("providerId"), record.text("trackId"))
                    else backupStableId("placeholder", record.text("placeholderId"))
                uniqueIdentity("playlist", record.text("playlistId"), identity, record.key, prefix)
            }
            BackupRecordKind.QUEUE_ENTRY -> {
                ordinal("queue-original", "queue", record.long("originalOrder"), record.key, prefix)
                ordinal("queue-playback", "queue", record.long("playbackOrder"), record.key, prefix)
            }
            BackupRecordKind.HISTORY_ENTRY -> ordinal("history", "history", record.long("ordinal"), record.key, prefix)
            BackupRecordKind.TRACK_LOCATION -> uniqueIdentity("track-location", record.text("trackId"), record.text("locationId"), record.key, prefix)
            BackupRecordKind.TRACK_TOTAL -> uniqueIdentity("track-total", record.text("providerId"), record.text("trackId"), record.key, prefix)
            BackupRecordKind.DAY_TOTAL -> uniqueIdentity("day-total", record.text("localDate"), record.text("zoneId"), record.key, prefix)
            else -> Unit
        }
    }

    private fun insertResource(descriptor: BackupResource, sealed: Boolean, prefix: String = "") = connection.prepare(
        "INSERT INTO ${prefix}resources(resourceId,kind,bytes,sha256,licenseRequired,sealed) VALUES(?,?,?,?,?,?)",
    ).use {
        it.bindText(1, descriptor.id); it.bindLong(2, descriptor.kind.ordinal.toLong()); it.bindLong(3, descriptor.bytes)
        it.bindText(4, descriptor.sha256); it.bindLong(5, if (descriptor.requiresFontLicenseAcknowledgment) 1L else 0L)
        it.bindLong(6, if (sealed) 1L else 0L); it.step()
    }

    override fun resource(id: String): BackupResource {
        checkCancelled()
        return connection.prepare("SELECT kind,bytes,sha256,licenseRequired,sealed FROM resources WHERE resourceId=?").use { statement ->
            statement.bindText(1, id)
            if (!statement.step()) throw BackupException(BackupIssue.INVALID_REFERENCE)
            val rawKind = statement.getLong(0)
            val kind = BackupResourceKind.entries.getOrNull(rawKind.takeIf { it in 0L until BackupResourceKind.entries.size.toLong() }?.toInt()
                ?: throw BackupException(BackupIssue.INVALID_RESOURCE)) ?: throw BackupException(BackupIssue.INVALID_RESOURCE)
            if (phase == "VERIFIED" && statement.getLong(4) != 1L) throw BackupException(BackupIssue.INCOMPLETE_ARCHIVE)
            if (statement.getLong(3) !in 0L..1L) throw BackupException(BackupIssue.INVALID_RESOURCE)
            BackupResource(id, kind, statement.getLong(1), statement.getText(2), statement.getLong(3) == 1L).also { resource ->
                val logical = row(BackupRecordKind.RESOURCE, id) ?: throw BackupException(BackupIssue.INVALID_REFERENCE)
                if (!sameBackupResource(resource, BackupLogicalSchema.resourceDescriptor(logical))) throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
            }
        }
    }

    override fun writeResource(id: String): OutputStream {
        requireWriting()
        val descriptor = resource(id)
        val created = BackupCreatedFile.create(directory.child(id + ".bin"))
        return object : FilterOutputStream(Channels.newOutputStream(created.channel)) {
            private var closed = false
            private var written = 0L
            private val digest = MessageDigest.getInstance("SHA-256")
            override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
                checkCancelled()
                if (closed || length.toLong() > descriptor.bytes - written) throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
                requireBackupSpace(directory.root, length.toLong())
                out.write(bytes, offset, length)
                digest.update(bytes, offset, length)
                written += length
            }
            override fun close() {
                if (closed) return
                closed = true
                var failure: Throwable? = null
                try {
                    if (written != descriptor.bytes || backupHex(digest.digest()) != descriptor.sha256) {
                        throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
                    }
                    flush(); created.channel.force(true); created.requireSame()
                } catch (error: Throwable) { failure = error }
                try { super.close() } catch (error: Throwable) {
                    if (failure == null) failure = error else failure.addSuppressed(error)
                }
                failure?.let { throw it }
                syncBackupDirectory(directory.root)
                connection.prepare("UPDATE resources SET sealed=1 WHERE resourceId=? AND sealed=0").use {
                    it.bindText(1, id); it.step()
                }
            }
        }
    }

    /** A native file owner has closed and hashed its own bounded copy before registration. */
    fun adoptCapturedResource(captured: BackupCapturedResource): BackupResource {
        requireWriting()
        val descriptor = captured.descriptor
        val logical = BackupLogicalSchema.resourceRecord(descriptor)
        val existing = row(BackupRecordKind.RESOURCE, descriptor.id)
        if (existing != null) {
            if (existing.values != logical.values) throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
            captured.discardDuplicate()
            return resource(descriptor.id)
        }
        captured.install(directory)
        record(logical)
        connection.prepare("UPDATE resources SET sealed=1 WHERE resourceId=? AND sealed=0").use {
            it.bindText(1, descriptor.id); it.step()
        }
        return descriptor
    }

    override fun openResource(id: String): InputStream {
        if (phase != "VERIFIED") throw BackupException(BackupIssue.INCOMPLETE_ARCHIVE)
        val descriptor = resource(id)
        val file = directory.child(id + ".bin")
        BackupFileIdentity.capture(file)
        val channel = FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        if (channel.size() != descriptor.bytes) {
            channel.close()
            throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
        }
        return Channels.newInputStream(channel)
    }

    fun verifiedResourceFile(id: String): java.io.File {
        if (phase != "VERIFIED") throw BackupException(BackupIssue.INCOMPLETE_ARCHIVE)
        val descriptor = resource(id)
        val file = directory.child(id + ".bin")
        BackupFileIdentity.capture(file)
        if (java.nio.file.Files.size(file.toPath()) != descriptor.bytes) throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
        return file
    }

    override fun complete(header: BackupHeader, completion: BackupCompletion) {
        requireWriting()
        if (header != this.header || count(BackupRecordKind.RESOURCE) != completion.resources) {
            throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
        }
        for (kind in BackupRecordKind.entries) if (count(kind) != completion.records[kind]) {
            throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
        }
        seal()
    }

    /** Source capture is also sealed before the archive writer receives it. */
    fun sealSnapshot() { requireWriting(); seal() }

    private fun seal() {
        validateReferences()
        validateOrdering()
        validateRelationships()
        validateStatistics()
        if (scalar("SELECT COUNT(*) FROM resources WHERE sealed!=1") != 0L) throw BackupException(BackupIssue.INCOMPLETE_ARCHIVE)
        connection.prepare("UPDATE stage_meta SET phase='VERIFIED',logicalBytes=? WHERE id=1 AND phase='WRITING'").use {
            it.bindLong(1, logicalBytes); it.step()
        }
        clearDerived(derivedTables.keys.toList())
        connection.execute("COMMIT")
        transaction = false
        verifiedLease = false
        phase = "VERIFIED"
        syncBackupDirectory(directory.root)
    }

    override fun abort() {
        if (transaction) {
            connection.execute("ROLLBACK")
            transaction = false
            derivedTables.clear()
            metadataBeforeSealed = false
            verifiedLease = false
        }
        connection.execute("UPDATE stage_meta SET phase='FAILED' WHERE id=1 AND phase!='VERIFIED'")
        phase = "FAILED"
        syncBackupDirectory(directory.root)
    }

    override fun page(kind: BackupRecordKind, afterKey: String?): List<BackupRecord> = pageNow(kind, afterKey)

    fun pageNow(kind: BackupRecordKind, afterKey: String?): List<BackupRecord> {
        if (phase != "VERIFIED" && phase != "WRITING") throw BackupException(BackupIssue.INCOMPLETE_ARCHIVE)
        checkCancelled()
        val result = ArrayList<BackupRecord>()
        var bytes = 0
        // A direct key range lets subsequent chunks seek past already visited rows.
        val continuation = if (afterKey == null) "" else " AND recordKey>?"
        connection.prepare("SELECT recordKey,payload,scope,bytes FROM logical_records WHERE kind=?$continuation ORDER BY recordKey COLLATE BINARY LIMIT ?").use {
            it.bindLong(1, kind.ordinal.toLong())
            if (afterKey != null) it.bindText(2, afterKey)
            it.bindLong(if (afterKey == null) 2 else 3, BackupLimits.ROWS_PER_CHUNK.toLong())
            while (it.step()) {
                checkCancelled()
                val payload = it.getBlob(1)
                if (result.isNotEmpty() && payload.size > BackupLimits.CHUNK_BYTES - bytes) break
                if (payload.size > BackupLimits.RECORD_BYTES) throw BackupException(BackupIssue.TOO_LARGE)
                result += decodeRow(kind, it.getText(0), payload, it.getLong(2), it.getLong(3))
                bytes += payload.size
            }
        }
        return result
    }

    fun row(kind: BackupRecordKind, key: String): BackupRecord? = connection.prepare(
        "SELECT payload,scope,bytes FROM logical_records WHERE kind=? AND recordKey=?",
    ).use {
        it.bindLong(1, kind.ordinal.toLong()); it.bindText(2, key)
        if (it.step()) decodeRow(kind, key, it.getBlob(0), it.getLong(1), it.getLong(2)) else null
    }

    fun orderedPage(kind: BackupRecordKind, family: String, owner: String, after: Long?): List<BackupRecord> {
        val result = ArrayList<BackupRecord>()
        var bytes = 0
        val continuation = if (after == null) "" else " AND o.ordinal>?"
        connection.prepare("SELECT r.recordKey,r.payload,r.scope,r.bytes,o.ordinal FROM logical_ordinals o JOIN logical_records r ON r.kind=? AND r.recordKey=o.recordKey WHERE o.family=? AND o.owner=?$continuation ORDER BY o.ordinal LIMIT ?").use {
            it.bindLong(1, kind.ordinal.toLong()); it.bindText(2, family); it.bindText(3, owner)
            if (after != null) it.bindLong(4, after)
            it.bindLong(if (after == null) 4 else 5, BackupLimits.ROWS_PER_CHUNK.toLong())
            while (it.step()) {
                checkCancelled()
                val payload = it.getBlob(1)
                if (result.isNotEmpty() && payload.size > BackupLimits.CHUNK_BYTES - bytes) break
                val row = decodeRow(kind, it.getText(0), payload, it.getLong(2), it.getLong(3))
                val field = when (family) {
                    "track-added" -> "firstAddedSequence"
                    "playlist", "history" -> "ordinal"
                    "queue-original" -> "originalOrder"
                    "queue-playback" -> "playbackOrder"
                    else -> throw BackupException(BackupIssue.INVALID_INPUT)
                }
                if (row.long(field) != it.getLong(4)) throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
                result += row
                bytes += payload.size
            }
        }
        return result
    }
    private fun decodeRow(kind: BackupRecordKind, key: String, payload: ByteArray, scope: Long, bytes: Long): BackupRecord {
        if (kind in BackupMetadataAssociations.KINDS && header.sourceDatabaseVersion < 9) throw BackupException(BackupIssue.UNSUPPORTED_VERSION)
        if (payload.size > BackupLimits.RECORD_BYTES) throw BackupException(BackupIssue.TOO_LARGE)
        if (scope != kind.scope.ordinal.toLong() || bytes != payload.size.toLong()) throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
        return BackupJsonCodec.readRecord(payload, checkCancelled).also {
            // The pagination cursor is the indexed key, never an unchecked key hidden in its payload.
            if (it.kind != kind || it.key != key) throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
        }
    }

    fun count(kind: BackupRecordKind): Long = connection.prepare("SELECT COUNT(*) FROM logical_records WHERE kind=?").use {
        it.bindLong(1, kind.ordinal.toLong()); check(it.step()); it.getLong(0)
    }

    fun putBinding(binding: BackupStoredBinding) {
        checkCancelled()
        if (phase != "VERIFIED") throw BackupException(BackupIssue.INCOMPLETE_ARCHIVE)
        connection.prepare("INSERT INTO restore_bindings(family,incomingId,actualId,verified,fences) VALUES(?,?,?,?,?) ON CONFLICT(family,incomingId) DO UPDATE SET actualId=excluded.actualId,verified=excluded.verified,fences=excluded.fences").use {
            it.bindLong(1, binding.family.ordinal.toLong()); it.bindText(2, binding.incomingId); it.bindText(3, binding.actualId)
            it.bindLong(4, if (binding.verified) 1L else 0L); it.bindBlob(5, BackupJsonCodec.encodeValues(binding.fences, checkCancelled)); it.step()
        }
    }

    fun binding(family: BackupBindingFamily, incomingId: String): BackupStoredBinding? = connection.prepare(
        "SELECT incomingId,actualId,verified,fences FROM restore_bindings WHERE family=? AND incomingId=?",
    ).use {
        it.bindLong(1, family.ordinal.toLong()); it.bindText(2, incomingId)
        if (it.step()) decodeBinding(family, it) else null
    }

    fun setActualId(kind: BackupRecordKind, incoming: String, actual: String) {
        checkCancelled()
        if (phase != "VERIFIED" || actual.isBlank() || actual.length > 128 || actual.any(Char::isISOControl)) throw BackupException(BackupIssue.INVALID_INPUT)
        connection.prepare("INSERT INTO actual_ids(kind,incomingId,actualId) VALUES(?,?,?) ON CONFLICT(kind,incomingId) DO UPDATE SET actualId=excluded.actualId").use {
            it.bindLong(1, kind.ordinal.toLong()); it.bindText(2, incoming); it.bindText(3, actual); it.step()
        }
    }
    fun actualId(kind: BackupRecordKind, incoming: String): String = connection.prepare("SELECT actualId FROM actual_ids WHERE kind=? AND incomingId=?").use {
        it.bindLong(1, kind.ordinal.toLong()); it.bindText(2, incoming); if (it.step()) it.getText(0) else incoming
    }

    fun bindingPage(family: BackupBindingFamily, after: String?): List<BackupStoredBinding> {
        val continuation = if (after == null) "" else " AND incomingId>?"
        return connection.prepare(
            "SELECT incomingId,actualId,verified,fences FROM restore_bindings WHERE family=?$continuation ORDER BY incomingId LIMIT 100",
        ).use {
            it.bindLong(1, family.ordinal.toLong())
            if (after != null) it.bindText(2, after)
            buildList { while (it.step()) { checkCancelled(); add(decodeBinding(family, it)) } }
        }
    }

    private fun decodeBinding(family: BackupBindingFamily, statement: SQLiteStatement): BackupStoredBinding = BackupStoredBinding(
        family, statement.getText(0), statement.getText(1), statement.getLong(2) == 1L, BackupJsonCodec.decodeValues(statement.getBlob(3), checkCancelled),
    )

    fun missingEdges(current: SqliteBackupStage, scopes: Set<BackupScope>): Long {
        var count = 0L
        connection.prepare("SELECT e.toKind,e.targetKey FROM logical_edges e JOIN logical_records r ON r.kind=e.fromKind AND r.recordKey=e.recordKey WHERE e.hard=0 ORDER BY e.fromKind,e.recordKey,e.field").use {
            while (it.step()) {
                checkCancelled()
                val kind = BackupRecordKind.entries.getOrNull(it.getLong(0).toInt()) ?: throw BackupException(BackupIssue.INVALID_REFERENCE)
                val key = it.getText(1)
                if (row(kind, key)?.takeIf { kind.scope in scopes } == null && current.row(kind, key) == null) count++
            }
        }
        return count
    }

    fun firstResourceUse(resourceId: String, kind: BackupRecordKind): BackupResourceUse? = connection.prepare(
        "SELECT recordKey,field FROM resource_usage WHERE resourceId=? AND kind=? ORDER BY recordKey,field LIMIT 1",
    ).use {
        it.bindText(1, resourceId); it.bindLong(2, kind.ordinal.toLong())
        if (!it.step()) null else BackupResourceUse(row(kind, it.getText(0)) ?: throw BackupException(BackupIssue.INVALID_REFERENCE), it.getText(1))
    }

    fun lyricsEncodings(resourceId: String): Set<LyricsEncodingChoice> = connection.prepare(
        "SELECT DISTINCT encoding FROM resource_usage WHERE resourceId=? AND kind IN (?,?) ORDER BY encoding",
    ).use {
        it.bindText(1, resourceId); it.bindLong(2, BackupRecordKind.LYRICS_TRACK.ordinal.toLong())
        it.bindLong(3, BackupRecordKind.METADATA_LYRICS.ordinal.toLong())
        buildSet { while (it.step()) {
            val name = it.getText(0)
            add(LyricsEncodingChoice.entries.firstOrNull { encoding -> encoding.name == name } ?: throw BackupException(BackupIssue.INVALID_REFERENCE))
        } }
    }

    private fun addResourceUsage(record: BackupRecord, indexPrefix: String = "") {
        fun usage(resourceId: String, field: String, encoding: String? = null) = connection.prepare(
            "INSERT INTO ${indexPrefix}resource_usage(resourceId,kind,recordKey,field,encoding) VALUES(?,?,?,?,?)",
        ).use {
            it.bindText(1, resourceId); it.bindLong(2, record.kind.ordinal.toLong()); it.bindText(3, record.key)
            it.bindText(4, field); it.bindNullableText(5, encoding); it.step()
        }
        if (record.kind == BackupRecordKind.LYRICS_TRACK) for (prefix in listOf("primary", "translation")) {
            record.optionalText(prefix + "Sha256")?.let { hash ->
                usage(BackupLogicalSchema.resourceId(BackupResourceKind.LYRICS, hash), prefix, record.text(prefix + "Encoding"))
            }
        }
        if (record.kind == BackupRecordKind.PREFERENCE) for ((field, resource) in BackupPreferencesCodec.resourceReferences(record)) usage(resource, field)
        if (record.kind == BackupRecordKind.THEME_INSTALLATION) usage(record.text("packageResourceId"), "package")
        if (record.kind in BackupMetadataAssociations.KINDS) BackupMetadataAssociations.resourceId(record)?.let {
            usage(it, "resource", if (record.kind == BackupRecordKind.METADATA_LYRICS) LyricsEncodingChoice.UTF8.name else null)
        }
    }

    private fun addReferences(record: BackupRecord, indexPrefix: String = "") {
        fun edge(field: String, kind: BackupRecordKind, key: String?, hard: Boolean = true) {
            if (key == null) return
            connection.prepare("INSERT INTO ${indexPrefix}logical_edges(fromKind,recordKey,field,toKind,targetKey,hard) VALUES(?,?,?,?,?,?)").use {
                it.bindLong(1, record.kind.ordinal.toLong()); it.bindText(2, record.key); it.bindText(3, field)
                it.bindLong(4, kind.ordinal.toLong()); it.bindText(5, key); it.bindLong(6, if (hard) 1 else 0); it.step()
            }
        }
        when (record.kind) {
            BackupRecordKind.TRACK_LOCATION -> { edge("trackId", BackupRecordKind.TRACK, record.text("trackId")); edge("locationId", BackupRecordKind.LOCATION_HINT, record.text("locationId")) }
            BackupRecordKind.NODE_HINT -> { edge("sourceId", BackupRecordKind.SOURCE_HINT, record.text("sourceId")); edge("parent", BackupRecordKind.NODE_HINT, record.optionalText("parentNodeId")) }
            BackupRecordKind.ALIAS_HINT -> {
                edge("sourceId", BackupRecordKind.SOURCE_HINT, record.text("sourceId")); edge("locationId", BackupRecordKind.LOCATION_HINT, record.text("locationId"))
                edge("nodeId", BackupRecordKind.NODE_HINT, record.text("nodeId"))
            }
            BackupRecordKind.IMPORT_HINT -> { edge("sourceId", BackupRecordKind.SOURCE_HINT, record.text("sourceId")); edge("trackId", BackupRecordKind.TRACK, record.text("trackId")) }
            BackupRecordKind.RULE -> { edge("sourceId", BackupRecordKind.SOURCE_HINT, record.text("sourceId")); edge("nodeId", BackupRecordKind.NODE_HINT, record.text("nodeId")) }
            BackupRecordKind.DISPLAY_NAME -> edge("groupId", BackupRecordKind.DISPLAY_GROUP, record.key)
            BackupRecordKind.DISPLAY_ALBUM_LINK -> { edge("trackId", BackupRecordKind.TRACK, record.key); edge("groupId", BackupRecordKind.DISPLAY_GROUP, record.text("groupId")) }
            BackupRecordKind.DISPLAY_ARTIST_LINK -> { edge("trackId", BackupRecordKind.TRACK, record.text("trackId")); edge("groupId", BackupRecordKind.DISPLAY_GROUP, record.text("groupId")) }
            BackupRecordKind.FAVORITES_MEMBER, BackupRecordKind.PLAYLIST_MEMBER -> {
                edge("head", if (record.kind == BackupRecordKind.FAVORITES_MEMBER) BackupRecordKind.FAVORITES_HEAD else BackupRecordKind.PLAYLIST_HEAD, record.text("playlistId"))
                if (record.optionalText("providerId") == TrackKey.LOCAL_PROVIDER) edge("track", BackupRecordKind.TRACK, record.optionalText("trackId"), hard = false)
            }
            BackupRecordKind.QUEUE_ENTRY -> {
                edge("queue", BackupRecordKind.QUEUE_META, "queue")
                if (record.text("providerId") == TrackKey.LOCAL_PROVIDER) edge("track", BackupRecordKind.TRACK, record.text("trackId"), hard = false)
            }
            BackupRecordKind.LYRICS_TRACK -> {
                edge("track", BackupRecordKind.TRACK, record.key, hard = false)
                for (prefix in listOf("primary", "translation")) edge(prefix + "Resource", BackupRecordKind.RESOURCE, record.optionalText(prefix + "ResourceId"), hard = false)
            }
            BackupRecordKind.PREFERENCE -> for ((field, key) in BackupPreferencesCodec.resourceReferences(record)) edge(field, BackupRecordKind.RESOURCE, key, hard = false)
            BackupRecordKind.THEME_INSTALLATION -> edge("package", BackupRecordKind.RESOURCE, record.text("packageResourceId"))
            BackupRecordKind.METADATA_LYRICS, BackupRecordKind.METADATA_ARTWORK -> {
                edge("track", BackupRecordKind.TRACK, record.key, hard = false)
                edge("resource", BackupRecordKind.RESOURCE, record.optionalText("resourceId"), hard = false)
            }
            else -> Unit
        }
    }

    private fun validateReferences() {
        if (scalar("SELECT COUNT(*) FROM logical_edges e WHERE e.hard=1 AND NOT EXISTS(SELECT 1 FROM logical_records r WHERE r.kind=e.toKind AND r.recordKey=e.targetKey)") != 0L) {
            throw BackupException(BackupIssue.INVALID_REFERENCE)
        }
        connection.execute("DELETE FROM node_marks")
        connection.execute("INSERT INTO node_marks(nodeId) SELECT child FROM node_parents WHERE parent IS NULL")
        while (true) {
            checkCancelled()
            connection.execute("INSERT OR IGNORE INTO node_marks(nodeId) SELECT p.child FROM node_parents p JOIN node_marks m ON m.nodeId=p.parent WHERE NOT EXISTS(SELECT 1 FROM node_marks n WHERE n.nodeId=p.child) LIMIT 100")
            if (scalar("SELECT changes()") == 0L) break
        }
        if (scalar("SELECT COUNT(*) FROM node_parents p WHERE NOT EXISTS(SELECT 1 FROM node_marks m WHERE m.nodeId=p.child)") != 0L) {
            throw BackupException(BackupIssue.INVALID_REFERENCE)
        }
    }

    private fun validateOrdering() {
        for ((scope, kind, key) in listOf(Triple(BackupScope.LIBRARY, BackupRecordKind.LIBRARY_META, "library"),
            Triple(BackupScope.QUEUE, BackupRecordKind.QUEUE_META, "queue"), Triple(BackupScope.HISTORY, BackupRecordKind.HISTORY_META, "history"))) {
            if (scope in header.scopes && (count(kind) != 1L || row(kind, key) == null)) throw BackupException(BackupIssue.INVALID_REFERENCE)
        }
        if (BackupScope.PREFERENCES in header.scopes && count(BackupRecordKind.PREFERENCE) != BackupPreferencesCodec.KEYS.size.toLong()) {
            throw BackupException(BackupIssue.INCOMPLETE_ARCHIVE)
        }
        if (BackupScope.FAVORITES in header.scopes && count(BackupRecordKind.FAVORITES_HEAD) != 1L) {
            throw BackupException(BackupIssue.INCOMPLETE_ARCHIVE)
        }
        for (kind in listOf(BackupRecordKind.FAVORITES_HEAD, BackupRecordKind.PLAYLIST_HEAD)) {
            var after: String? = null
            while (true) {
                val page = pageNow(kind, after)
                if (page.isEmpty()) break
                for (head in page) validateContiguous("playlist", head.key, head.long("memberCount"))
                after = page.last().key
            }
        }
        row(BackupRecordKind.QUEUE_META, "queue")?.let { meta ->
            validateContiguous("queue-original", "queue", meta.long("totalCount"))
            validateContiguous("queue-playback", "queue", meta.long("totalCount"))
            meta.optionalText("currentEntryId")?.let { if (row(BackupRecordKind.QUEUE_ENTRY, it) == null) throw BackupException(BackupIssue.INVALID_REFERENCE) }
        }
    }

    private fun validateRelationships() {
        if (scalar("SELECT COUNT(*) FROM resources r WHERE NOT EXISTS(SELECT 1 FROM resource_usage u WHERE u.resourceId=r.resourceId)") != 0L) {
            throw BackupException(BackupIssue.INVALID_REFERENCE)
        }
        var after: String? = null
        while (true) {
            val page = pageNow(BackupRecordKind.NODE_HINT, after)
            if (page.isEmpty()) break
            for (node in page) node.optionalText("parentNodeId")?.let { parentId ->
                val parent = row(BackupRecordKind.NODE_HINT, parentId) ?: throw BackupException(BackupIssue.INVALID_REFERENCE)
                if (parent.text("sourceId") != node.text("sourceId") || !parent.flag("directory")) throw BackupException(BackupIssue.INVALID_REFERENCE)
            }
            after = page.last().key
        }
        after = null
        while (true) {
            val page = pageNow(BackupRecordKind.ALIAS_HINT, after)
            if (page.isEmpty()) break
            for (alias in page) {
                val node = row(BackupRecordKind.NODE_HINT, alias.text("nodeId")) ?: throw BackupException(BackupIssue.INVALID_REFERENCE)
                if (node.text("sourceId") != alias.text("sourceId") || node.flag("directory")) throw BackupException(BackupIssue.INVALID_REFERENCE)
            }
            after = page.last().key
        }
        row(BackupRecordKind.PREFERENCE, "lyrics-fonts")?.let { settings ->
            for (prefix in listOf("original", "translation")) settings.optionalText(prefix + "Sha256")?.let { hash ->
                val id = BackupLogicalSchema.resourceId(BackupResourceKind.FONT, hash)
                row(BackupRecordKind.RESOURCE, id)?.let { resource ->
                    if (resourceDescriptorBytes(resource, BackupResourceKind.FONT) != settings.long(prefix + "Bytes")) throw BackupException(BackupIssue.INVALID_REFERENCE)
                }
            }
        }
        row(BackupRecordKind.PREFERENCE, "background")?.let { settings -> settings.optionalText("imageSha256")?.let { hash ->
            row(BackupRecordKind.RESOURCE, BackupLogicalSchema.resourceId(BackupResourceKind.IMAGE, hash))?.let { resource ->
                if (resourceDescriptorBytes(resource, BackupResourceKind.IMAGE) != settings.long("imageBytes")) throw BackupException(BackupIssue.INVALID_REFERENCE)
            }
        } }
        row(BackupRecordKind.PREFERENCE, "theme")?.let { settings ->
            for (prefix in listOf("selected", "previous")) {
                val chosen = BackupPreferencesCodec.choice(settings, prefix)
                if (chosen !is io.github.onlinemo.meloxwatch.data.themes.ThemeChoice.Installed) continue
                val id = settings.optionalText(prefix + "PackageResourceId")
                val installation = row(BackupRecordKind.THEME_INSTALLATION, chosen.ref.installationId)
                if (installation != null && (installation.text("themeId") != chosen.ref.themeId || installation.text("manifestSha256") != chosen.ref.manifestSha256) ||
                    id != null && (installation == null || installation.text("packageResourceId") != id)) {
                    throw BackupException(BackupIssue.INVALID_REFERENCE)
                }
            }
        }
        after = null
        while (true) {
            val page = pageNow(BackupRecordKind.THEME_INSTALLATION, after)
            if (page.isEmpty()) break
            for (installation in page) {
                val resource = row(BackupRecordKind.RESOURCE, installation.text("packageResourceId")) ?: throw BackupException(BackupIssue.INVALID_REFERENCE)
                resourceDescriptorBytes(resource, BackupResourceKind.THEME_PACKAGE)
                if (resource.flag("licenseRequired") != (installation.long("fontBytes") > 0L)) throw BackupException(BackupIssue.INVALID_REFERENCE)
            }
            after = page.last().key
        }
        for (kind in BackupMetadataAssociations.KINDS) {
            after = null
            while (true) {
                val page = pageNow(kind, after)
                if (page.isEmpty()) break
                for (association in page) {
                    val cue = association.portableCueReference()
                    row(BackupRecordKind.TRACK, association.key)?.let { track ->
                        if (track.portableCueReference() != cue) throw BackupException(BackupIssue.INVALID_REFERENCE)
                    }
                    val otherKind = if (kind == BackupRecordKind.METADATA_LYRICS) BackupRecordKind.METADATA_ARTWORK else BackupRecordKind.METADATA_LYRICS
                    row(otherKind, association.key)?.let { other ->
                        if (other.portableCueReference() != cue) throw BackupException(BackupIssue.INVALID_REFERENCE)
                    }
                    if (kind == BackupRecordKind.METADATA_LYRICS && association.flag("primaryLinked")) {
                        val lyrics = row(BackupRecordKind.LYRICS_TRACK, association.key) ?: throw BackupException(BackupIssue.INVALID_REFERENCE)
                        if (lyrics.optionalText("primarySha256") != association.optionalText("sha256") ||
                            lyrics.optionalText("primaryEncoding") != LyricsEncodingChoice.UTF8.name ||
                            lyrics.optionalText("primaryResourceId") != association.optionalText("resourceId")) {
                            throw BackupException(BackupIssue.INVALID_REFERENCE)
                        }
                    }
                    val resourceId = BackupMetadataAssociations.resourceId(association) ?: continue
                    row(BackupRecordKind.RESOURCE, resourceId)?.let { resource ->
                        if (resourceDescriptorBytes(resource, BackupMetadataAssociations.resourceKind(kind)) != association.long("bytes") ||
                            resource.text("sha256") != association.text("sha256")) throw BackupException(BackupIssue.INVALID_REFERENCE)
                    }
                }
                after = page.last().key
            }
        }
    }
    private fun resourceDescriptorBytes(record: BackupRecord, kind: BackupResourceKind): Long = BackupLogicalSchema.resourceDescriptor(record).let {
        if (it.kind != kind) throw BackupException(BackupIssue.INVALID_REFERENCE)
        it.bytes
    }

    /** Stored projections must cover every logical row, including rows an inner join would hide. */
    private fun verifySealedState() {
        checkCancelled()
        if (logicalBytes !in 0L..BackupLimits.EXPANDED_LOGICAL_BYTES || scalar("SELECT COUNT(*) FROM stage_meta") != 1L) {
            throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
        }
        for (sql in SCHEMA) {
            val table = Regex("^CREATE TABLE ([a-z_]+)").find(sql)?.groupValues?.get(1) ?: continue
            if (table in DERIVED_COLUMNS) createDerived(VERIFY_PREFIX + table,
                sql.replaceFirst("CREATE TABLE $table", "CREATE TABLE main.$VERIFY_PREFIX$table"))
        }
        var failure: Throwable? = null
        try {
            var records = 0L
            var canonicalBytes = 0L
            var verifiedResourceBytes = 0L
            for (kind in BackupRecordKind.entries) {
                var after: String? = null
                while (true) {
                    val page = pageNow(kind, after)
                    if (page.isEmpty()) break
                    if (kind.scope !in header.scopes) throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
                    val canonicalSizes = page.map { BackupJsonCodec.record(it, checkCancelled).size.toLong() }
                    reserveDerived(VERIFY_PREFIX + "logical_edges", canonicalSizes.sum())
                    for (index in page.indices) {
                        val record = page[index]
                        checkCancelled()
                        records = backupAdd(records, 1L, Long.MAX_VALUE)
                        canonicalBytes = backupAdd(canonicalBytes, canonicalSizes[index], BackupLimits.EXPANDED_LOGICAL_BYTES)
                        addLogicalIndexes(record, "main.$VERIFY_PREFIX")
                        if (kind == BackupRecordKind.RESOURCE) {
                            val resource = BackupLogicalSchema.resourceDescriptor(record)
                            verifiedResourceBytes = backupAdd(verifiedResourceBytes, resource.bytes, BackupLimits.RESOURCE_ARCHIVE_BYTES)
                            insertResource(resource, sealed = true, prefix = "main.$VERIFY_PREFIX")
                        }
                    }
                    after = page.last().key
                }
            }
            if (records != scalar("SELECT COUNT(*) FROM logical_records") ||
                canonicalBytes != logicalBytes || scalar("SELECT COALESCE(SUM(bytes),0) FROM logical_records") != logicalBytes) {
                throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
            }
            if (scalar("SELECT COUNT(*) FROM resources WHERE sealed!=1") != 0L) throw BackupException(BackupIssue.INCOMPLETE_ARCHIVE)
            for ((table, columns) in DERIVED_COLUMNS) {
                checkCancelled()
                val expected = "main.$VERIFY_PREFIX$table"
                val matches = columns.split(',').joinToString(" AND ") { "actual.$it IS expected.$it" }
                val differs = scalar("SELECT EXISTS(SELECT 1 FROM main.$table actual WHERE NOT EXISTS(" +
                    "SELECT 1 FROM $expected expected WHERE $matches)) OR EXISTS(SELECT 1 FROM $expected expected WHERE NOT EXISTS(" +
                    "SELECT 1 FROM main.$table actual WHERE $matches))")
                if (differs != 0L) throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
            }
            resourceBytes = verifiedResourceBytes
            validateReferences()
            validateOrdering()
            validateRelationships()
            validateStatistics()
        } catch (caught: Throwable) { failure = caught; throw caught }
        finally {
            try { clearDerived(DERIVED_COLUMNS.keys.map { VERIFY_PREFIX + it }) }
            catch (closing: Throwable) {
                val original = failure
                if (original == null) throw closing else if (original !== closing) original.addSuppressed(closing)
            }
        }
    }

    private fun verifyColumnTypes() {
        for ((table, columns) in COLUMN_TYPES) {
            checkCancelled()
            val invalid = columns.entries.joinToString(" OR ") { (column, allowed) ->
                "typeof($column) NOT IN (" + allowed.split(',').joinToString(",") { "'$it'" } + ")"
            }
            if (scalar("SELECT COUNT(*) FROM $table WHERE $invalid") != 0L) throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
        }
    }
    private fun validateStatistics() {
        if (BackupScope.HISTORY !in header.scopes) return
        val meta = row(BackupRecordKind.HISTORY_META, "history") ?: throw BackupException(BackupIssue.INVALID_REFERENCE)
        for (kind in listOf(BackupRecordKind.TRACK_TOTAL, BackupRecordKind.DAY_TOTAL)) {
            var count = 0L
            var milliseconds = 0L
            var after: String? = null
            while (true) {
                val page = pageNow(kind, after)
                if (page.isEmpty()) break
                for (record in page) {
                    checkCancelled()
                    count = backupAdd(count, record.long("playCount"), Long.MAX_VALUE)
                    milliseconds = backupAdd(milliseconds, record.long("listeningMs"), Long.MAX_VALUE)
                }
                after = page.last().key
            }
            if (count != meta.long("totalPlayCount") || milliseconds != meta.long("totalListeningMs")) throw BackupException(BackupIssue.INVALID_REFERENCE)
        }
    }

    private fun validateContiguous(family: String, owner: String, expected: Long) {
        connection.prepare("SELECT COUNT(*),MIN(ordinal),MAX(ordinal) FROM logical_ordinals WHERE family=? AND owner=?").use {
            it.bindText(1, family); it.bindText(2, owner); check(it.step())
            if (it.getLong(0) != expected || expected > 0L && (it.getLong(1) != 0L || it.getLong(2) != expected - 1L)) {
                throw BackupException(BackupIssue.INVALID_REFERENCE)
            }
        }
    }
    private fun ordinal(family: String, owner: String, ordinal: Long, key: String, prefix: String = "") {
        connection.prepare("SELECT 1 FROM ${prefix}logical_ordinals WHERE family=? AND owner=? AND ordinal=?").use {
            it.bindText(1, family); it.bindText(2, owner); it.bindLong(3, ordinal)
            if (it.step()) throw BackupException(BackupIssue.DUPLICATE_RECORD)
        }
        connection.prepare("INSERT INTO ${prefix}logical_ordinals(family,owner,ordinal,recordKey) VALUES(?,?,?,?)").use {
            it.bindText(1, family); it.bindText(2, owner); it.bindLong(3, ordinal); it.bindText(4, key); it.step()
        }
    }
    private fun uniqueIdentity(family: String, owner: String, identity: String, key: String, prefix: String = "") {
        connection.prepare("SELECT 1 FROM ${prefix}logical_identities WHERE family=? AND owner=? AND identity=?").use {
            it.bindText(1, family); it.bindText(2, owner); it.bindText(3, identity)
            if (it.step()) throw BackupException(BackupIssue.DUPLICATE_RECORD)
        }
        connection.prepare("INSERT INTO ${prefix}logical_identities(family,owner,identity,recordKey) VALUES(?,?,?,?)").use {
            it.bindText(1, family); it.bindText(2, owner); it.bindText(3, identity); it.bindText(4, key); it.step()
        }
    }
    private fun createDerived(name: String, sql: String) {
        checkCancelled()
        require(name in DERIVED_NAMES)
        if (closed || phase !in setOf("WRITING", "VERIFIED")) throw BackupException(BackupIssue.INCOMPLETE_ARCHIVE)
        if (!transaction) {
            requireVerifiedLease()
        }
        requireStageFile()
        if (name in derivedTables || connection.prepare(
                "SELECT 1 FROM main.sqlite_schema WHERE name=? UNION ALL SELECT 1 FROM sqlite_temp_schema WHERE name=? LIMIT 1",
            ).use { it.bindText(1, name); it.bindText(2, name); it.step() }) {
            throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        }
        requireBackupSpace(directory.root, 64 * 1024L)
        connection.execute(sql)
        derivedTables[name] = 0L
    }

    /** Direct sealed-stage callers acquire the same verified lease as a newly opened restore caller. */
    private fun requireVerifiedLease() {
        checkCancelled()
        if (closed || phase != "VERIFIED") throw BackupException(BackupIssue.INCOMPLETE_ARCHIVE)
        if (!verifiedLease) {
            if (!transaction) {
                connection.execute("BEGIN IMMEDIATE")
                transaction = true
            }
            verifySchema(connection)
            verifyColumnTypes()
            connection.prepare("SELECT header,phase,logicalBytes FROM stage_meta WHERE id=1").use {
                if (!it.step() || it.getText(1) != "VERIFIED" || it.getLong(2) != logicalBytes ||
                    BackupJsonCodec.readHeader(it.getBlob(0), checkCancelled) != header) {
                    throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
                }
            }
            verifySealedState()
            verifiedLease = true
        }
        requireStageFile()
    }

    private fun resetDerived(name: String, sql: String) {
        if (name !in derivedTables) createDerived(name, sql) else {
            checkCancelled(); requireStageFile()
            connection.execute("DELETE FROM main.$name")
            derivedTables[name] = 0L
        }
    }

    private fun reserveDerived(name: String, bytes: Long) {
        checkCancelled()
        if (!transaction || name !in derivedTables || bytes < 0L) throw BackupException(BackupIssue.INCOMPLETE_ARCHIVE)
        val total = derivedTables.values.fold(0L) { sum, value -> backupAdd(sum, value, STAGE_DERIVED_BYTES) }
        backupAdd(total, bytes, STAGE_DERIVED_BYTES)
        requireBackupSpace(directory.root, backupAdd(bytes, 8192L, Long.MAX_VALUE))
        derivedTables[name] = backupAdd(checkNotNull(derivedTables[name]), bytes, STAGE_DERIVED_BYTES)
    }

    private fun dropDerived(name: String) {
        if (name !in derivedTables) return
        if (!transaction) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        requireStageFile()
        connection.execute("DROP TABLE main.$name")
        derivedTables.remove(name)
    }

    private fun clearDerived(names: Collection<String>) {
        var failure: Throwable? = null
        for (name in names) try { dropDerived(name) }
        catch (closing: Throwable) {
            val original = failure
            if (original == null) failure = closing else if (original !== closing) original.addSuppressed(closing)
        }
        failure?.let { throw it }
    }

    /** A FILE pragma cannot override TEMP_STORE=3; ordinary main tables use the measured private file. */
    private fun requireStageFile() {
        val file = directory.child("rows.db")
        fileIdentity.requireSame(file)
        var found = false
        connection.prepare("PRAGMA database_list").use {
            while (it.step()) {
                when (it.getText(1)) {
                    "main" -> {
                        if (found || it.getText(2) != file.absolutePath) throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
                        found = true
                    }
                    "temp" -> Unit
                    else -> throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
                }
            }
        }
        if (!found || scalar("PRAGMA main.cache_size") != -1024L || scalar("PRAGMA main.cache_spill") <= 0L ||
            scalar("PRAGMA main.max_page_count") != STAGE_MAX_PAGES) throw BackupException(BackupIssue.UNSUPPORTED_RESOURCE)
        connection.prepare("PRAGMA main.journal_mode").use {
            if (!it.step() || it.getText(0) != "delete") throw BackupException(BackupIssue.UNSUPPORTED_RESOURCE)
        }
    }

    private fun scalar(sql: String): Long = connection.prepare(sql).use { check(it.step()); it.getLong(0) }
    private fun requireWriting() { checkCancelled(); if (!transaction || phase != "WRITING") throw BackupException(BackupIssue.INCOMPLETE_ARCHIVE) }
    override fun close() {
        if (closed) return
        var failure: Throwable? = null
        if (transaction) try { connection.execute("ROLLBACK"); transaction = false }
        catch (rollback: Throwable) { failure = rollback }
        try { connection.close(); closed = true; transaction = false; derivedTables.clear(); metadataBeforeSealed = false; verifiedLease = false }
        catch (closing: Throwable) {
            val original = failure
            if (original == null) failure = closing
            else if (original !== closing) original.addSuppressed(closing)
        }
        failure?.let { throw it }
    }

    companion object {
        fun create(directory: BackupPrivateDirectory, checkCancelled: () -> Unit): SqliteBackupStage =
            create(directory, onOpened = {}, checkCancelled = checkCancelled)

        fun create(directory: BackupPrivateDirectory, onOpened: (SqliteBackupStage) -> Unit,
            checkCancelled: () -> Unit): SqliteBackupStage {
            checkCancelled(); requireBackupSpace(directory.root, 64 * 1024L)
            val file = directory.child("rows.db")
            BackupCreatedFile.create(file).use { it.channel.force(true) }
            val identity = BackupFileIdentity.capture(file)
            val connection = BundledSQLiteDriver().open(file.absolutePath)
            val result = SqliteBackupStage(directory, connection, identity, checkCancelled)
            try {
                onOpened(result)
                connection.execute("PRAGMA journal_mode=DELETE")
                connection.execute("PRAGMA synchronous=FULL")
                connection.execute("PRAGMA cache_size=-1024")
                connection.execute("PRAGMA cache_spill=1")
                connection.execute("PRAGMA temp_store=FILE")
                connection.execute("PRAGMA max_page_count=$STAGE_MAX_PAGES")
                connection.execute("PRAGMA user_version=1")
                for (sql in SCHEMA) connection.execute(sql)
                identity.requireSame(file)
                syncBackupDirectory(directory.root)
                return result
            } catch (failure: Throwable) {
                try { result.close() } catch (closing: Throwable) { if (closing !== failure) failure.addSuppressed(closing) }
                throw failure
            }
        }

        fun openVerified(directory: BackupPrivateDirectory, checkCancelled: () -> Unit): SqliteBackupStage =
            openVerified(directory, onOpened = {}, checkCancelled = checkCancelled)

        fun openVerified(directory: BackupPrivateDirectory, onOpened: (SqliteBackupStage) -> Unit,
            checkCancelled: () -> Unit): SqliteBackupStage {
            val file = directory.child("rows.db")
            val identity = BackupFileIdentity.capture(file)
            val connection = BundledSQLiteDriver().open(file.absolutePath)
            val result = SqliteBackupStage(directory, connection, identity, checkCancelled)
            try {
                onOpened(result)
                connection.execute("PRAGMA cache_size=-1024")
                connection.execute("PRAGMA cache_spill=1")
                connection.execute("PRAGMA temp_store=FILE")
                connection.execute("PRAGMA max_page_count=$STAGE_MAX_PAGES")
                // Only this private stage is reserved; main Room and archive readers remain separate.
                connection.execute("BEGIN IMMEDIATE")
                result.transaction = true
                if (connection.prepare("PRAGMA user_version").use { check(it.step()); it.getLong(0) } != 1L) throw BackupException(BackupIssue.UNSUPPORTED_VERSION)
                verifySchema(connection)
                result.verifyColumnTypes()
                connection.prepare("SELECT header,phase,logicalBytes FROM stage_meta WHERE id=1").use {
                    if (!it.step() || it.getText(1) != "VERIFIED") throw BackupException(BackupIssue.INCOMPLETE_ARCHIVE)
                    result.capturedHeader = BackupJsonCodec.readHeader(it.getBlob(0), checkCancelled)
                    result.phase = "VERIFIED"; result.logicalBytes = it.getLong(2)
                }
                result.verifySealedState()
                identity.requireSame(file)
                result.verifiedLease = true
                return result
            } catch (failure: Throwable) {
                try { result.close() } catch (closing: Throwable) { if (closing !== failure) failure.addSuppressed(closing) }
                throw failure
            }
        }
        private fun verifySchema(connection: SQLiteConnection) {
            val actual = LinkedHashMap<String, String>()
            connection.prepare("SELECT name,sql FROM sqlite_schema WHERE name NOT LIKE 'sqlite_%' AND type IN ('table','view','trigger','index') ORDER BY name").use {
                while (it.step()) {
                    if (it.isNull(1)) throw BackupException(BackupIssue.CORRUPT_ARCHIVE)
                    actual[it.getText(0)] = normalizedSql(it.getText(1))
                }
            }
            val expected = SCHEMA.associate { sql ->
                val name = Regex("CREATE (?:TABLE|INDEX) ([a-z_]+)").find(sql)?.groupValues?.get(1)
                    ?: throw BackupException(BackupIssue.CORRUPT_ARCHIVE)
                name to normalizedSql(sql)
            }
            if (actual != expected) throw BackupException(BackupIssue.CORRUPT_ARCHIVE)
            connection.prepare("PRAGMA quick_check(1)").use {
                if (!it.step() || it.getText(0) != "ok" || it.step()) throw BackupException(BackupIssue.CORRUPT_ARCHIVE)
            }
        }
        private fun normalizedSql(sql: String): String = sql.trim().replace(Regex("\\s+"), " ").lowercase(java.util.Locale.ROOT)
        private const val VERIFY_PREFIX = "backup_expected_"
        private const val METADATA_BEFORE = "backup_metadata_before"
        private const val STAGE_MAX_PAGES = 131072L
        // Incoming rows and the already bounded native before-image are separate logical domains.
        private const val STAGE_DERIVED_BYTES = 2L * BackupLimits.EXPANDED_LOGICAL_BYTES
        private val DERIVED_COLUMNS = mapOf(
            "logical_edges" to "fromKind,recordKey,field,toKind,targetKey,hard",
            "resource_usage" to "resourceId,kind,recordKey,field,encoding",
            "logical_ordinals" to "family,owner,ordinal,recordKey",
            "logical_identities" to "family,owner,identity,recordKey",
            "resources" to "resourceId,kind,bytes,sha256,licenseRequired,sealed",
            "node_parents" to "child,parent",
        )
        private val DERIVED_NAMES = DERIVED_COLUMNS.keys.map { VERIFY_PREFIX + it }.toSet() +
            setOf(METADATA_BEFORE, "preview_resources", "relink_reference_tracks")
        private val COLUMN_TYPES = mapOf(
            "stage_meta" to mapOf("id" to "integer", "header" to "blob", "phase" to "text", "logicalBytes" to "integer"),
            "logical_records" to mapOf("kind" to "integer", "recordKey" to "text", "scope" to "integer", "payload" to "blob", "bytes" to "integer"),
            "logical_edges" to mapOf("fromKind" to "integer", "recordKey" to "text", "field" to "text", "toKind" to "integer", "targetKey" to "text", "hard" to "integer"),
            "resource_usage" to mapOf("resourceId" to "text", "kind" to "integer", "recordKey" to "text", "field" to "text", "encoding" to "text,null"),
            "logical_ordinals" to mapOf("family" to "text", "owner" to "text", "ordinal" to "integer", "recordKey" to "text"),
            "logical_identities" to mapOf("family" to "text", "owner" to "text", "identity" to "text", "recordKey" to "text"),
            "resources" to mapOf("resourceId" to "text", "kind" to "integer", "bytes" to "integer", "sha256" to "text", "licenseRequired" to "integer", "sealed" to "integer"),
            "node_parents" to mapOf("child" to "text", "parent" to "text,null"),
            "node_marks" to mapOf("nodeId" to "text"),
            "restore_bindings" to mapOf("family" to "integer", "incomingId" to "text", "actualId" to "text", "verified" to "integer", "fences" to "blob"),
            "actual_ids" to mapOf("kind" to "integer", "incomingId" to "text", "actualId" to "text"),
        )
        private val SCHEMA = listOf(
            "CREATE TABLE stage_meta(id INTEGER NOT NULL PRIMARY KEY CHECK(id=1),header BLOB NOT NULL,phase TEXT NOT NULL,logicalBytes INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE logical_records(kind INTEGER NOT NULL,recordKey TEXT NOT NULL COLLATE BINARY,scope INTEGER NOT NULL,payload BLOB NOT NULL,bytes INTEGER NOT NULL,PRIMARY KEY(kind,recordKey)) WITHOUT ROWID",
            "CREATE TABLE logical_edges(fromKind INTEGER NOT NULL,recordKey TEXT NOT NULL,field TEXT NOT NULL,toKind INTEGER NOT NULL,targetKey TEXT NOT NULL,hard INTEGER NOT NULL,PRIMARY KEY(fromKind,recordKey,field)) WITHOUT ROWID",
            "CREATE INDEX logical_edges_target ON logical_edges(toKind,targetKey)",
            "CREATE TABLE resource_usage(resourceId TEXT NOT NULL,kind INTEGER NOT NULL,recordKey TEXT NOT NULL,field TEXT NOT NULL,encoding TEXT,PRIMARY KEY(resourceId,kind,recordKey,field)) WITHOUT ROWID",
            "CREATE TABLE logical_ordinals(family TEXT NOT NULL,owner TEXT NOT NULL,ordinal INTEGER NOT NULL,recordKey TEXT NOT NULL,PRIMARY KEY(family,owner,ordinal)) WITHOUT ROWID",
            "CREATE TABLE logical_identities(family TEXT NOT NULL,owner TEXT NOT NULL,identity TEXT NOT NULL,recordKey TEXT NOT NULL,PRIMARY KEY(family,owner,identity)) WITHOUT ROWID",
            "CREATE TABLE resources(resourceId TEXT NOT NULL PRIMARY KEY,kind INTEGER NOT NULL,bytes INTEGER NOT NULL,sha256 TEXT NOT NULL,licenseRequired INTEGER NOT NULL,sealed INTEGER NOT NULL) WITHOUT ROWID",
            "CREATE TABLE node_parents(child TEXT NOT NULL PRIMARY KEY,parent TEXT) WITHOUT ROWID",
            "CREATE INDEX node_parents_parent ON node_parents(parent)",
            "CREATE TABLE node_marks(nodeId TEXT NOT NULL PRIMARY KEY) WITHOUT ROWID",
            "CREATE TABLE restore_bindings(family INTEGER NOT NULL,incomingId TEXT NOT NULL,actualId TEXT NOT NULL,verified INTEGER NOT NULL,fences BLOB NOT NULL,PRIMARY KEY(family,incomingId)) WITHOUT ROWID",
            "CREATE TABLE actual_ids(kind INTEGER NOT NULL,incomingId TEXT NOT NULL,actualId TEXT NOT NULL,PRIMARY KEY(kind,incomingId)) WITHOUT ROWID",
        )
    }
}

internal fun SQLiteConnection.execute(sql: String) { prepare(sql).use { it.step() } }
internal fun SQLiteStatement.bindNullableText(index: Int, value: String?) { if (value == null) bindNull(index) else bindText(index, value) }
internal fun SQLiteStatement.bindNullableLong(index: Int, value: Long?) { if (value == null) bindNull(index) else bindLong(index, value) }
