package io.github.onlinemo.meloxwatch.data.backup

import java.io.IOException
import java.util.UUID
import java.util.Collections
import io.github.onlinemo.meloxwatch.model.TrackKey
import io.github.onlinemo.meloxwatch.data.imports.ImportLimits

/** These are archive input limits, never a library or queue item limit. */
object BackupLimits {
    const val FORMAT_VERSION = 1
    const val LOGICAL_VERSION = 1
    const val DATABASE_VERSION = 9
    const val LOGICAL_ARCHIVE_BYTES = 100L * 1024 * 1024
    const val EXPANDED_LOGICAL_BYTES = 200L * 1024 * 1024
    const val CHUNK_BYTES = 2 * 1024 * 1024
    const val RECORD_BYTES = 1024 * 1024
    const val MANIFEST_BYTES = 64 * 1024
    // The container parser bounds directory memory; rows still use complete disk-backed pages.
    const val ZIP_ENTRIES = 32_768
    const val ROWS_PER_CHUNK = 100
    const val JSON_DEPTH = 16
    const val FIELDS_PER_RECORD = 128
    const val IDENTIFIER_CHARACTERS = 4096
    const val RESOURCE_ARCHIVE_BYTES = 200L * 1024 * 1024
    const val FONT_BYTES = 8L * 1024 * 1024
    const val IMAGE_BYTES = 16L * 1024 * 1024
    const val THEME_PACKAGE_BYTES = 16L * 1024 * 1024
    const val EXPANDED_THEME_BYTES = 32L * 1024 * 1024
    const val LYRICS_BYTES = 1024L * 1024
    const val FREE_RESERVE_BYTES = ImportLimits.FREE_RESERVE_BYTES
}

enum class BackupScope { LIBRARY, FAVORITES, PLAYLISTS, QUEUE, HISTORY, PREFERENCES, LYRICS, RESOURCES }
enum class BackupMergeMode { MERGE_KEEP_CURRENT, REPLACE_SELECTED }

enum class BackupIssue {
    INVALID_INPUT, UNSUPPORTED_VERSION, TOO_LARGE, CORRUPT_ARCHIVE, INCOMPLETE_ARCHIVE,
    PATH_ESCAPE, DUPLICATE_RECORD, INVALID_REFERENCE, INTEGRITY_MISMATCH, PERMISSION_DENIED,
    LOCAL_DESTINATION_REQUIRED, STORAGE_FULL, IO, BUSY, STALE_PREVIEW, PENDING_FILE_OPERATION,
    RECOVERY_REQUIRED, OUTPUT_CLEANUP_REQUIRED,
    FONT_LICENSE_REQUIRED, INVALID_RESOURCE, UNSUPPORTED_RESOURCE, CONFLICT,
}

class BackupException(val issue: BackupIssue, cause: Throwable? = null) : IOException(issue.name, cause)

class BackupSelection(scopes: Set<BackupScope>) {
    val scopes: Set<BackupScope> = Collections.unmodifiableSet(scopes.toSet())
    init {
        require(this.scopes.isNotEmpty())
        require(BackupScope.RESOURCES !in this.scopes || BackupScope.PREFERENCES in this.scopes ||
            BackupScope.LYRICS in this.scopes || BackupScope.LIBRARY in this.scopes)
    }
    override fun toString(): String = "BackupSelection"
}

/** Source database version is context; portable rows have their own logical schema. */
data class BackupHeader(
    val exportId: String = UUID.randomUUID().toString(),
    val formatVersion: Int = BackupLimits.FORMAT_VERSION,
    val logicalVersion: Int = BackupLimits.LOGICAL_VERSION,
    val sourceDatabaseVersion: Int = BackupLimits.DATABASE_VERSION,
    val scopes: Set<BackupScope>,
    val createdEpochMs: Long,
) {
    init {
        require(UUID.fromString(exportId).toString() == exportId)
        require(formatVersion == BackupLimits.FORMAT_VERSION)
        require(logicalVersion == BackupLimits.LOGICAL_VERSION)
        require(sourceDatabaseVersion in 1..BackupLimits.DATABASE_VERSION)
        require(scopes.isNotEmpty() && createdEpochMs >= 0L)
        require(BackupScope.RESOURCES !in scopes || BackupScope.PREFERENCES in scopes ||
            BackupScope.LYRICS in scopes || BackupScope.LIBRARY in scopes)
    }
}

/** Record types describe committed logical data; operational or authorization rows have no type. */
enum class BackupRecordKind(val scope: BackupScope) {
    LIBRARY_META(BackupScope.LIBRARY), TRACK(BackupScope.LIBRARY), TRACK_LOCATION(BackupScope.LIBRARY),
    SOURCE_HINT(BackupScope.LIBRARY), LOCATION_HINT(BackupScope.LIBRARY), NODE_HINT(BackupScope.LIBRARY),
    ALIAS_HINT(BackupScope.LIBRARY), IMPORT_HINT(BackupScope.LIBRARY), RULE(BackupScope.LIBRARY),
    DISPLAY_GROUP(BackupScope.LIBRARY), DISPLAY_NAME(BackupScope.LIBRARY),
    DISPLAY_ALBUM_LINK(BackupScope.LIBRARY), DISPLAY_ARTIST_LINK(BackupScope.LIBRARY),
    FAVORITES_HEAD(BackupScope.FAVORITES), FAVORITES_MEMBER(BackupScope.FAVORITES),
    PLAYLIST_HEAD(BackupScope.PLAYLISTS), PLAYLIST_MEMBER(BackupScope.PLAYLISTS),
    QUEUE_META(BackupScope.QUEUE), QUEUE_ENTRY(BackupScope.QUEUE),
    HISTORY_META(BackupScope.HISTORY), HISTORY_ENTRY(BackupScope.HISTORY),
    TRACK_TOTAL(BackupScope.HISTORY), DAY_TOTAL(BackupScope.HISTORY),
    PREFERENCE(BackupScope.PREFERENCES), LYRICS_TRACK(BackupScope.LYRICS),
    RESOURCE(BackupScope.RESOURCES), THEME_INSTALLATION(BackupScope.RESOURCES),
    METADATA_LYRICS(BackupScope.LYRICS), METADATA_ARTWORK(BackupScope.LIBRARY),
}

sealed interface BackupCell {
    data object Null : BackupCell
    data class Text(val value: String) : BackupCell {
        override fun toString(): String = "Text"
    }
    data class Integer(val value: Long) : BackupCell
    data class Decimal(val value: Double) : BackupCell {
        init { require(value.isFinite()) }
    }
    data class Flag(val value: Boolean) : BackupCell
}

/** A row can contain private labels/reference hints, so logs must never stringify its values. */
class BackupRecord(val kind: BackupRecordKind, val key: String, values: Map<String, BackupCell>) {
    val values: Map<String, BackupCell> = Collections.unmodifiableMap(values.toMap())
    init {
        require(key.isNotBlank() && key.length <= BackupLimits.IDENTIFIER_CHARACTERS && !key.any(Char::isISOControl))
        require(this.values.size <= BackupLimits.FIELDS_PER_RECORD)
        require(this.values.keys.all { it.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,63}")) })
    }
    override fun toString(): String = "BackupRecord(${kind.name})"
}

enum class BackupResourceKind(val limitBytes: Long) {
    LYRICS(BackupLimits.LYRICS_BYTES), FONT(BackupLimits.FONT_BYTES),
    IMAGE(BackupLimits.IMAGE_BYTES), THEME_PACKAGE(BackupLimits.THEME_PACKAGE_BYTES),
}

class BackupResource(
    val id: String,
    val kind: BackupResourceKind,
    val bytes: Long,
    val sha256: String,
    val requiresFontLicenseAcknowledgment: Boolean,
) {
    init {
        require(id.matches(Regex("[0-9a-f]{64}")))
        require(bytes in 1..kind.limitBytes)
        require(sha256.matches(Regex("[0-9a-f]{64}")))
    }
    override fun toString(): String = "BackupResource(${kind.name})"
}

/** Counters only: private rows, URI hints, names and files never enter progress state. */
data class BackupProgress(val stage: BackupStage, val records: Long = 0L, val bytes: Long = 0L) {
    init { require(records >= 0L && bytes >= 0L) }
}
enum class BackupStage { CAPTURING, WRITING, VERIFYING, PREVIEW, APPLYING, RECOVERING, COMPLETE, FAILED }

data class BackupScopeImpact(
    val scope: BackupScope,
    val incoming: Long,
    val existing: Long,
    val conflicts: Long,
    val missingReferences: Long,
) {
    init { require(incoming >= 0L && existing >= 0L && conflicts >= 0L && missingReferences >= 0L) }
}

/** The opaque preview token binds the verified archive, selected scopes and real current revisions. */
class BackupRestorePreview internal constructor(
    val token: String,
    val archiveSha256: String,
    val header: BackupHeader,
    val mode: BackupMergeMode,
    scopes: Set<BackupScope>,
    impacts: List<BackupScopeImpact>,
    val readableWithoutPassword: Boolean = true,
    val needsFontLicenseAcknowledgment: Boolean,
) {
    val scopes: Set<BackupScope> = Collections.unmodifiableSet(scopes.toSet())
    val impacts: List<BackupScopeImpact> = Collections.unmodifiableList(impacts.toList())
    override fun toString(): String = "BackupRestorePreview"
}

data class BackupExportReceipt(val exportId: String, val sha256: String, val bytes: Long)
data class BackupRestoreReceipt(val exportId: String, val restoredScopes: Set<BackupScope>, val missingReferences: Long)

sealed interface BackupStartupState {
    data object Loading : BackupStartupState
    data class Ready(val recoveredPendingRun: Boolean) : BackupStartupState
    data class Unavailable(val issue: BackupIssue) : BackupStartupState
}

class BackupMissingFile internal constructor(val hintId: String, val trackId: String, val title: String, val isCue: Boolean = false) {
    override fun toString(): String = "BackupMissingFile"
}
class BackupRelinkPage internal constructor(val items: List<BackupMissingFile>, val next: String?) {
    override fun toString(): String = "BackupRelinkPage"
}
enum class BackupRelinkMatch { VERIFIED_MATCH, DIFFERENT_CONTENT, UNKNOWN_CONTENT }
class BackupRelinkPreview internal constructor(val token: String, val missingTrackId: String, val target: TrackKey,
    val originalTitle: String, val targetTitle: String, val match: BackupRelinkMatch,
    val cueDifferences: io.github.onlinemo.meloxwatch.data.cue.CueAdoptionDifferences? = null,
    val cueOriginalMembers: Int = 0, val cueTargetMembers: Int = 0, val affectedQueueEntries: Long = 0L,
    val affectedPlaylistEntries: Long = 0L, cueMembers: List<CueRelinkMemberPreview> = emptyList()) {
    val cueMembers: List<CueRelinkMemberPreview> = Collections.unmodifiableList(cueMembers.toList())
    override fun toString(): String = "BackupRelinkPreview"
}
data class BackupRelinkReceipt(val trackId: String, val repairedQueueEntries: Long)
