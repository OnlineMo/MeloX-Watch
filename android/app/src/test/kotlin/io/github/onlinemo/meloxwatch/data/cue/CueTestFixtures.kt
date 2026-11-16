package io.github.onlinemo.meloxwatch.data.cue

import io.github.onlinemo.meloxwatch.data.library.LibrarySource
import io.github.onlinemo.meloxwatch.data.library.LibrarySourceState
import io.github.onlinemo.meloxwatch.data.library.LocalContentValidation
import io.github.onlinemo.meloxwatch.data.library.LocalReadDescriptor
import io.github.onlinemo.meloxwatch.data.local.LocalCandidateCursor
import io.github.onlinemo.meloxwatch.data.local.LocalGrantObservation
import io.github.onlinemo.meloxwatch.data.local.LocalNodeKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceAccess
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceObservation
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import io.github.onlinemo.meloxwatch.model.LocalAccess
import io.github.onlinemo.meloxwatch.model.LocalLocationReference
import io.github.onlinemo.meloxwatch.model.TrackKey
import org.junit.Assert.assertEquals

internal inline fun expectCueIssue(expected: CueIssue, action: () -> Unit): CueException {
    try { action() } catch (error: CueException) {
        assertEquals(expected, error.issue)
        return error
    }
    throw AssertionError("Expected $expected")
}

internal fun cueText(path: String = "album.flac"): String = """
    FILE "$path" WAVE
      TRACK 01 AUDIO
        INDEX 01 00:00:00
""".trimIndent()

/** In-memory contract fixture; it makes no claim about Android permission, formats or device audio. */
internal class CueGraph : LocalSourceAccess {
    val grant = LocalGrantObservation(read = ObservedCapability.AVAILABLE,
        enumerate = ObservedCapability.AVAILABLE, persistedRead = ObservedCapability.AVAILABLE)
    val spec = LocalSourceSpec("cue-source", LocalSourceKind.TREE, "content://cue-test/tree/root",
        authority = "cue-test", rootObjectId = "root")
    val source = LibrarySource(spec, grant, "cue-grant", 5L, 3L, LibrarySourceState.ACTIVE, null, null)
    val root = node("root", null, "Root", LocalNodeKind.DIRECTORY)
    var cue = node("cue-object", "root", "album.cue", LocalNodeKind.OTHER)
    val children = mutableMapOf("root" to listOf(cue))
    var currentGrant = grant
    var onObserve: ((Int) -> Unit)? = null
    var onBatch: (suspend (String, Int) -> Unit)? = null
    var onClose: (suspend (String) -> Unit)? = null
    var overrideBatch: ((String, Int) -> List<ObservedCandidate>?)? = null
    var virtualRootCount: Int? = null
    var observations = 0
    val opened = ArrayList<String>()
    var activeCursors = 0
        private set
    var maximumActiveCursors = 0
        private set
    var largestRequestedBatch = 0
        private set
    var closedCursors = 0
        private set

    fun node(
        id: String,
        parent: String?,
        name: String = "$id.flac",
        kind: LocalNodeKind = LocalNodeKind.AUDIO,
    ): ObservedCandidate = ObservedCandidate(spec.sourceId, id,
        "content://cue-test/tree/root/document/$id", parent, name,
        if (kind == LocalNodeKind.AUDIO) "audio/flac" else null, kind,
        providerContentToken = "native-$id")

    fun scope(directories: List<ObservedCandidate> = listOf(root)): CueDirectoryScope = CueDirectoryScope(source, directories, cue)

    override suspend fun observe(source: LocalSourceSpec): LocalSourceObservation {
        require(source == spec)
        observations++
        onObserve?.invoke(observations)
        return LocalSourceObservation(spec, "cue-identity", currentGrant, root)
    }

    override suspend fun open(source: LocalSourceSpec, parentObjectId: String?, sinceMediaGeneration: Long?): LocalCandidateCursor {
        require(source == spec)
        val parent = requireNotNull(parentObjectId)
        opened += parent
        activeCursors++
        maximumActiveCursors = maxOf(maximumActiveCursors, activeCursors)
        return Cursor(parent, children[parent].orEmpty())
    }

    private inner class Cursor(private val parent: String, private val rows: List<ObservedCandidate>) : LocalCandidateCursor {
        private var offset = 0
        private var closed = false
        private val virtualCount = virtualRootCount?.takeIf { parent == "root" }
        private val total = virtualCount ?: rows.size
        private val junk = node("virtual-junk", parent, "other.flac")
        override val exhausted: Boolean get() = offset >= total

        override suspend fun nextBatch(limit: Int): List<ObservedCandidate> {
            check(!closed)
            largestRequestedBatch = maxOf(largestRequestedBatch, limit)
            onBatch?.invoke(parent, offset)
            overrideBatch?.invoke(parent, limit)?.let { return it }
            val end = minOf(total, offset + limit)
            val result = if (virtualCount == null) rows.subList(offset, end).toList()
                else List(end - offset) { index -> if (offset + index == 0) cue else junk }
            offset = end
            return result
        }

        override fun close() {
            if (!closed) {
                closed = true
                activeCursors--
                closedCursors++
            }
        }

        override suspend fun closeAndAwait() {
            try { onClose?.invoke(parent) } finally { close() }
        }
    }
}

internal fun cueDocument(
    parsed: ParsedCue,
    matched: CueMatchedFiles,
    cueId: String = "durable-cue-id",
    contentRevision: Long = 4L,
): CueDocumentSnapshot = CueDocumentSnapshot(
    CueDocumentIdentity(matched.scope.source.spec.sourceId, cueId), matched.scope.source,
    matched.scope.cue.objectId,
    LocalLocationReference("cue-location", matched.scope.cue.uri, matched.scope.source.spec.uri,
        access = LocalAccess.PERSISTED, contentRevision = contentRevision), parsed.rawContentToken,
)

internal fun cueFacts(matched: CueMatchedFiles, durationUs: Long = 90_000_000L): List<CueAudioFacts> = matched.files.map {
    val candidate = it.candidate
    val location = LocalLocationReference("location-${candidate.objectId}", candidate.uri,
        matched.scope.source.spec.uri, access = LocalAccess.PERSISTED, contentRevision = 4L)
    val descriptor = LocalReadDescriptor(TrackKey(TrackKey.LOCAL_PROVIDER, "physical-${candidate.objectId}"),
        matched.scope.source, "alias-${candidate.objectId}", location, candidate.objectId,
        candidate.providerContentToken, candidate.sizeBytes, contentValidation = LocalContentValidation.VERIFIED)
    CueAudioFacts(it.fileIndex, descriptor, durationUs)
}
