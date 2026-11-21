package io.github.onlinemo.meloxwatch.data.lyrics.active

import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.database.withPreservedTransaction
import io.github.onlinemo.meloxwatch.data.library.LibraryException
import io.github.onlinemo.meloxwatch.data.library.LibraryIssue
import io.github.onlinemo.meloxwatch.data.library.LibrarySql
import io.github.onlinemo.meloxwatch.data.library.LocalLibraryRepository
import io.github.onlinemo.meloxwatch.data.library.LocalReadDescriptor
import io.github.onlinemo.meloxwatch.data.local.LocalInputLimits
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsSiblingScope
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.TrackKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LocalLyricsContext(
    val audio: LocalReadDescriptor,
    val siblingScope: LyricsSiblingScope?,
)

/** Indexed parent edges grant no access; the loader independently observes and opens each input. */
class RoomLyricsContextResolver(
    private val database: MeloXDatabase,
    private val library: LocalLibraryRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) {
    suspend fun resolve(entry: QueueEntry): LocalLyricsContext = withContext(dispatcher) {
        if (entry.trackKey.providerId != TrackKey.LOCAL_PROVIDER) fail(LibraryIssue.INVALID_INPUT)
        val location = entry.location ?: fail(LibraryIssue.SOURCE_UNAVAILABLE)
        val audio = library.resolveLocalRead(entry.trackKey, location.locationId, location.contentRevision)
        database.withPreservedTransaction {
            val dao = database.libraryDao()
            val source = dao.source(audio.source.spec.sourceId) ?: fail(LibraryIssue.SOURCE_CHANGED)
            if (source.revision != audio.source.revision || source.authEpoch != audio.source.authEpoch) {
                fail(LibraryIssue.SOURCE_CHANGED)
            }
            val alias = dao.currentAlias(audio.aliasId) ?: fail(LibraryIssue.SOURCE_CHANGED)
            if (alias.sourceId != source.sourceId || alias.locationId != location.locationId ||
                alias.contentRevision != location.contentRevision || alias.uri != audio.location.uri) {
                fail(LibraryIssue.SOURCE_CHANGED)
            }
            val physicalNode = dao.nodeById(alias.nodeId) ?: fail(LibraryIssue.SOURCE_CHANGED)
            if (physicalNode.sourceId != source.sourceId || physicalNode.objectId != audio.objectId) {
                fail(LibraryIssue.SOURCE_CHANGED)
            }
            val scope = if (audio.source.spec.kind == LocalSourceKind.TREE) {
                val node = dao.currentNode(alias.nodeId)
                val parentId = node?.parentNodeId
                if (node == null || node.sourceId != source.sourceId || node.directory ||
                    node.uri != alias.uri || parentId == null || alias.filename.isBlank() ||
                    alias.filename.length >= LocalInputLimits.NAME_CHARACTERS) null
                else {
                    val parent = dao.currentNode(parentId)
                    val parentIdentity = dao.nodeById(parentId)
                    val revision = dao.meta()?.revision ?: fail(LibraryIssue.RECOVERY_REQUIRED)
                    // This root-bounded query uses saved physical edges, never URI/name splitting.
                    val insideRoot = dao.text(LibrarySql.relativePath(revision, source.sourceId, parentId)) != null
                    if (parent == null || parent.sourceId != source.sourceId || !parent.directory ||
                        parentIdentity == null || parentIdentity.sourceId != source.sourceId || !insideRoot) null
                    else LyricsSiblingScope(parentIdentity.objectId, alias.filename)
                }
            } else null
            LocalLyricsContext(audio, scope)
        }
    }

    /** Re-resolve the registered source fence; physical access/content still needs Android validation. */
    suspend fun isCurrent(context: LocalLyricsContext): Boolean {
        val expected = context.audio
        val observed = try {
            library.resolveLocalRead(expected.trackKey, expected.location.locationId, expected.location.contentRevision)
        } catch (_: LibraryException) {
            return false
        }
        return observed.source == expected.source && observed.aliasId == expected.aliasId &&
            observed.location == expected.location && observed.objectId == expected.objectId &&
            observed.providerContentToken == expected.providerContentToken &&
            observed.verifiedContentToken == expected.verifiedContentToken
    }

    private fun fail(issue: LibraryIssue): Nothing = throw LibraryException(issue)
}
