package io.github.onlinemo.meloxwatch.data.cue

import io.github.onlinemo.meloxwatch.data.library.LocalAudioMetadata
import io.github.onlinemo.meloxwatch.data.library.LocalReadDescriptor
import io.github.onlinemo.meloxwatch.model.CueBoundDocumentReference
import io.github.onlinemo.meloxwatch.model.LocalCueReference
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.util.Collections

data class CueAdoptionTargetMember(
    val trackKey: TrackKey,
    val reference: LocalCueReference,
    val title: String,
    val member: Boolean,
) {
    init { require(trackKey == reference.trackKey && title.length <= CueLimits.FIELD_CHARACTERS) }
    override fun toString(): String = "CueAdoptionTargetMember(redacted)"
}

data class CueAdoptionSourceMember(
    val trackKey: TrackKey,
    val reference: LocalCueReference,
    val metadata: LocalAudioMetadata,
    val cueRead: LocalReadDescriptor,
    val audioRead: LocalReadDescriptor,
) {
    init { require(trackKey == reference.trackKey && reference.isBound) }
    override fun toString(): String = "CueAdoptionSourceMember(redacted)"
}

data class CueAdoptionDifferences(
    val identityChanged: Boolean,
    val cueContentChanged: Boolean,
    val unknownAudioContent: Int,
    val audioContentChanged: Int,
    val boundsChanged: Int,
    val extraNativeTracks: Int,
) {
    init { require(listOf(unknownAudioContent, audioContentChanged, boundsChanged, extraNativeTracks).all { it in 0..CueLimits.TRACKS }) }
    val differentOrUnknownContent: Boolean get() = cueContentChanged || unknownAudioContent > 0 || audioContentChanged > 0
    val requiresExplicitConfirmation: Boolean get() = identityChanged || differentOrUnknownContent || boundsChanged > 0 || extraNativeTracks > 0
}

/** 私有且不可变的预览围栏；界面只显示名称、数量和差异摘要。 */
class CueAdoptionPlan internal constructor(
    val id: String,
    val libraryRevision: Long,
    val logicalSourceId: String,
    val logicalCueId: String,
    val aliasId: String,
    val expectedAliasRevision: Long?,
    val logicalGroupRevision: Long,
    val nativeDocument: CueBoundDocumentReference,
    targets: List<CueAdoptionTargetMember>,
    sources: List<CueAdoptionSourceMember>,
    val queueGenerationId: String?,
    val queueRevision: Long,
    val checkpointRevision: Long,
    val affectedQueueEntries: Long,
    val differences: CueAdoptionDifferences,
    val affectedPlaylistEntries: Long = 0L,
) {
    val targets: List<CueAdoptionTargetMember> = Collections.unmodifiableList(targets.toList())
    val sources: List<CueAdoptionSourceMember> = Collections.unmodifiableList(sources.toList())
    init {
        require(id.isNotBlank() && aliasId.isNotBlank() && libraryRevision >= 0L && logicalGroupRevision > 0L)
        require(this.targets.size in 1..CueLimits.TRACKS && this.sources.size in 1..CueLimits.TRACKS)
        require(this.targets.map { it.reference.identity.trackNumber }.distinct().size == this.targets.size)
        require(this.sources.map { it.reference.identity.trackNumber }.distinct().size == this.sources.size)
        require(this.targets.all { it.reference.identity.sourceId == logicalSourceId && it.reference.identity.cueId == logicalCueId })
        require(this.sources.all {
            val actual = it.reference.actualDocument
            actual.sourceId == nativeDocument.sourceId && actual.cueId == nativeDocument.cueId &&
                actual.groupRevision == nativeDocument.groupRevision
        })
        require(queueRevision >= 0L && checkpointRevision >= 0L && affectedQueueEntries >= 0L && affectedPlaylistEntries >= 0L)
    }
    override fun toString(): String = "CueAdoptionPlan(redacted)"
}

data class CueAdoptionCommit(
    val libraryRevision: Long,
    val logicalGroupRevision: Long,
    val updatedTracks: Int,
    val repairedQueueEntries: Long,
)
