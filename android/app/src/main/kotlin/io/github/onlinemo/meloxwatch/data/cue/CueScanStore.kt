package io.github.onlinemo.meloxwatch.data.cue

import io.github.onlinemo.meloxwatch.data.library.LocalAudioMetadata
import io.github.onlinemo.meloxwatch.data.library.LibrarySource
import io.github.onlinemo.meloxwatch.data.library.LibraryTrack
import io.github.onlinemo.meloxwatch.data.library.LibraryViewToken
import io.github.onlinemo.meloxwatch.data.library.LocalReadDescriptor
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.data.scan.ScanState
import io.github.onlinemo.meloxwatch.data.scan.ScanToken
import io.github.onlinemo.meloxwatch.model.CuePcmWaveFacts
import io.github.onlinemo.meloxwatch.model.LocalCueReference
import io.github.onlinemo.meloxwatch.model.LocalLocationReference
import io.github.onlinemo.meloxwatch.model.TrackKey

/** 文档读取路径不包含可播放的队列条目或物理音轨标识。 */
data class CueDocumentAccess(
    val identity: CueDocumentIdentity,
    val source: LibrarySource,
    val aliasId: String,
    val objectId: String,
    val location: LocalLocationReference,
    val providerContentToken: String?,
    val sizeBytes: Long,
) {
    fun readDescriptor(rawContentToken: String? = null): LocalReadDescriptor = LocalReadDescriptor(
        TrackKey(TrackKey.LOCAL_PROVIDER, "cue-document:${identity.cueId}"), source, aliasId, location,
        objectId, providerContentToken, sizeBytes, verifiedContentToken = rawContentToken,
    )
}

data class CueScanContext(val document: CueDocumentAccess, val scope: CueDirectoryScope)
data class CueGroupCommit(val revision: Long, val groupRevision: Long, val addedTracks: Long, val alreadyCommitted: Boolean)
data class CuePlaybackRead(val reference: LocalCueReference, val cue: LocalReadDescriptor, val audio: LocalReadDescriptor,
    val metadata: LocalAudioMetadata? = null)

/** 提供方 I/O 在这些短 Room 操作之外执行；每次修改都重核扫描围栏。 */
interface CueScanStore {
    suspend fun nextCueDocument(token: ScanToken): ObservedCandidate?
    suspend fun cueScanContext(token: ScanToken, candidate: ObservedCandidate): CueScanContext
    suspend fun cueAudioDescriptor(token: ScanToken, candidate: ObservedCandidate): LocalReadDescriptor
    suspend fun commitCueGroup(token: ScanToken, prepared: PreparedCueGroup, pcmFacts: Map<Int, CuePcmWaveFacts>): CueGroupCommit
    /**
     * 成功只结清本代次已验证的完整组；失败只结清本代次已经接纳的 CUE_RUNNING。
     * 实现须在同一恢复 barrier/原生事务中重核 source/authEpoch/rules 和 frontier，
     * 重复失败只回读相同结果；失败结清不执行提供方 IO，也不激活或删除段组。
     */
    suspend fun completeCueDocument(token: ScanToken, objectId: String, issue: CueIssue? = null): ScanState
    suspend fun resolveCueRead(trackKey: TrackKey, expected: LocalCueReference? = null): CuePlaybackRead
    suspend fun cueRelativeMatches(view: LibraryViewToken, rootNodeId: String, components: List<String>, trackNumber: Int): List<TrackKey>
    suspend fun cueRelativePath(view: LibraryViewToken, track: LibraryTrack): String?
}
