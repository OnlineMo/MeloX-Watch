package io.github.onlinemo.meloxwatch.data.metadata

import io.github.onlinemo.meloxwatch.model.TrackKey
import kotlinx.coroutines.flow.Flow

/** The display cannot write without the source/selection/revision ticket captured for its preview. */
internal interface LocalMetadataStore {
    fun changes(trackKey: TrackKey, purpose: MetadataPurpose): Flow<Unit>
    suspend fun capture(purpose: MetadataPurpose): MetadataLookupTarget
    suspend fun captureClear(purpose: MetadataPurpose): MetadataClearTarget
    suspend fun read(trackKey: TrackKey, purpose: MetadataPurpose): MetadataAssociation
    suspend fun isCurrent(target: MetadataLookupTarget): Boolean
    suspend fun clearStillCurrent(target: MetadataClearTarget): Boolean
    suspend fun apply(target: MetadataLookupTarget, bytes: ByteArray, provenance: MetadataProvenance,
        stillCurrent: () -> Boolean): MetadataCommitReceipt
    suspend fun clear(target: MetadataClearTarget, stillCurrent: () -> Boolean): MetadataClearReceipt
}
