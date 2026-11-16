package io.github.onlinemo.meloxwatch.data.queue

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

internal object QueuePhase {
    const val STAGING = 0
    const val SEALED = 1
    const val PUBLISHED = 2
    const val ABORTED = 3
}

/** 非活动代次只保留这个小型操作记录；其曲目行由 Store 分批回收。 */
@Entity(tableName = "queue_generations", indices = [Index(value = ["operationId"], unique = true)])
data class QueueGenerationEntity(
    @PrimaryKey val generationId: String,
    val operationId: String,
    val expectedGenerationId: String?,
    val expectedQueueRevision: Long,
    val viewId: String,
    val viewRevision: Long,
    val totalCount: Long,
    val shuffle: Boolean,
    val repeatMode: String,
    val cycleLimit: Int?,
    val startOriginalOrder: Long,
    val initialPositionMs: Long,
    val allowEmpty: Boolean,
    val phase: Int = QueuePhase.STAGING,
    val writtenCount: Long = 0L,
    val committedQueueRevision: Long? = null,
    @ColumnInfo(defaultValue = "'REPLACE'")
    val operationKind: String = "REPLACE",
    @ColumnInfo(defaultValue = "''")
    val operationFingerprint: String = "",
    @ColumnInfo(defaultValue = "NULL")
    val expectedCurrentEntryId: String? = null,
    @ColumnInfo(defaultValue = "'PRESERVE_SUPPLIED'")
    val orderPolicy: String = "PRESERVE_SUPPLIED",
)

@Entity(
    tableName = "queue_entries",
    primaryKeys = ["generationId", "queueEntryId"],
    foreignKeys = [
        ForeignKey(
            entity = QueueGenerationEntity::class,
            parentColumns = ["generationId"],
            childColumns = ["generationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["generationId", "originalOrder"], unique = true),
        Index(value = ["generationId", "playbackOrder"], unique = true),
    ],
)
data class QueueEntryEntity(
    val generationId: String,
    val queueEntryId: String,
    val providerId: String,
    val trackId: String,
    val originalOrder: Long,
    val playbackOrder: Long,
    val displayName: String?,
    val durationMs: Long?,
    val accountId: String?,
    val locationId: String?,
    val locationUri: String?,
    val grantUri: String?,
    val grantFlags: Int?,
    val access: String?,
    val contentRevision: Long?,
)

/** 两个 FK 也约束清理：不能删除活动代次或当前实例。 */
@Entity(
    tableName = "queue_head",
    foreignKeys = [
        ForeignKey(
            entity = QueueGenerationEntity::class,
            parentColumns = ["generationId"],
            childColumns = ["activeGenerationId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = QueueEntryEntity::class,
            parentColumns = ["generationId", "queueEntryId"],
            childColumns = ["activeGenerationId", "currentEntryId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["activeGenerationId"]),
        Index(value = ["activeGenerationId", "currentEntryId"]),
    ],
)
data class QueueHeadEntity(
    @PrimaryKey val id: Int = 1,
    val activeGenerationId: String? = null,
    val queueRevision: Long = 0L,
    val checkpointRevision: Long = 0L,
    val currentEntryId: String? = null,
    val positionMs: Long = 0L,
    val completedCycles: Int = 0,
)

data class QueueOrderStats(
    val entryCount: Long,
    val firstOriginalOrder: Long?,
    val lastOriginalOrder: Long?,
    val firstPlaybackOrder: Long?,
    val lastPlaybackOrder: Long?,
)
