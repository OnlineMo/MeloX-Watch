package io.github.onlinemo.meloxwatch.data.cue

import io.github.onlinemo.meloxwatch.data.library.LibrarySourceState
import io.github.onlinemo.meloxwatch.data.local.LocalInputLimits
import io.github.onlinemo.meloxwatch.data.local.LocalNodeKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceAccess
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceObservation
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import java.net.URI
import java.net.URISyntaxException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * V1 normal path is an explicitly authorized TREE containing the CUE and every reference.
 * A standalone DOCUMENT grant cannot authorize sibling reads; it needs an authorized directory.
 * A complete directory pass detects NFC/case collisions without retaining the whole directory.
 */
class CuePathMatcher(private val access: LocalSourceAccess) {
    suspend fun match(
        parsed: ParsedCue,
        scope: CueDirectoryScope,
        namePolicy: CueNamePolicy = CueNamePolicy.EXACT,
    ): CueMatchedFiles {
        currentCoroutineContext().ensureActive()
        val frozen = scope.copy(directories = scope.directories.toList())
        validateCueScope(frozen)
        if (parsed.files.isEmpty()) throw CueException(CueIssue.INVALID_INPUT)
        if (parsed.files.size > CueLimits.FILES) throw CueException(CueIssue.LIMIT_EXCEEDED)
        val root = RequestedPath()
        for ((index, file) in parsed.files.withIndex()) {
            if (file.index != index || file.path.components.isEmpty()) throw CueException(CueIssue.INVALID_INPUT)
            if (frozen.directories.size + file.path.components.size - 1 > CueLimits.DIRECTORY_DEPTH) {
                throw CueException(CueIssue.LIMIT_EXCEEDED)
            }
            var path = root
            for (component in file.path.components) {
                if (path.fileIndex != null) throw CueException(CueIssue.INVALID_PATH)
                val key = cueNameKey(component, namePolicy)
                path = path.children.getOrPut(key) { RequestedPath() }
            }
            if (path.fileIndex != null) throw CueException(CueIssue.DUPLICATE_REFERENCE)
            if (path.children.isNotEmpty()) throw CueException(CueIssue.INVALID_PATH)
            path.fileIndex = file.index
        }
        val budget = LookupBudget()
        validateObservation(frozen, access.observe(frozen.source.spec))
        for (index in 1 until frozen.directories.size) {
            val expected = frozen.directories[index]
            val actual = findChildren(frozen, frozen.directories[index - 1].objectId, emptySet(), namePolicy, budget,
                expected.objectId).origin ?: throw CueException(CueIssue.SOURCE_CHANGED)
            requireSameEdge(actual, expected)
        }
        val files = HashMap<Int, ObservedCandidate>()
        val objects = HashSet<String>()
        var actualCue: ObservedCandidate? = null
        suspend fun resolve(path: RequestedPath, parentId: String, ancestors: Set<String>, originId: String? = null) {
            currentCoroutineContext().ensureActive()
            val found = findChildren(frozen, parentId, path.children.keys, namePolicy, budget, originId)
            if (originId != null) {
                val cue = found.origin ?: throw CueException(CueIssue.SOURCE_CHANGED)
                requireSameEdge(cue, frozen.cue)
                if (cue.providerContentToken != frozen.cue.providerContentToken ||
                    cue.sizeBytes != null && frozen.cue.sizeBytes != null && cue.sizeBytes != frozen.cue.sizeBytes) {
                    throw CueException(CueIssue.SOURCE_CHANGED)
                }
                actualCue = cue
            }
            for ((key, child) in path.children) {
                currentCoroutineContext().ensureActive()
                val candidate = found.matches[key] ?: throw CueException(CueIssue.MISSING_AUDIO)
                if (candidate.objectId in ancestors || candidate.objectId == frozen.cue.objectId) {
                    throw CueException(CueIssue.SOURCE_CHANGED)
                }
                val fileIndex = child.fileIndex
                if (fileIndex != null) {
                    if (candidate.directory) throw CueException(CueIssue.INVALID_AUDIO)
                    if (!objects.add(candidate.objectId)) throw CueException(CueIssue.DUPLICATE_REFERENCE)
                    files[fileIndex] = candidate
                } else {
                    if (!candidate.directory) throw CueException(CueIssue.INVALID_PATH)
                    resolve(child, candidate.objectId, ancestors + candidate.objectId)
                }
            }
        }
        // 请求的名称组成有界前缀树；每个共用目录只做一次完整扫描。
        resolve(root, frozen.directories.last().objectId, frozen.directories.mapTo(HashSet()) { it.objectId }, frozen.cue.objectId)
        validateObservation(frozen, access.observe(frozen.source.spec))
        currentCoroutineContext().ensureActive()
        val result = parsed.files.map { file -> CueMatchedFile(file.index, files[file.index] ?: throw CueException(CueIssue.MISSING_AUDIO)) }
        return CueMatchedFiles(parsed.rawContentToken, frozen.copy(cue = actualCue ?: throw CueException(CueIssue.SOURCE_CHANGED)), result)
    }

    private class RequestedPath {
        val children = LinkedHashMap<String, RequestedPath>()
        var fileIndex: Int? = null
    }
    private data class DirectoryMatches(val matches: Map<String, ObservedCandidate>, val origin: ObservedCandidate?)

    private suspend fun findChildren(
        scope: CueDirectoryScope,
        parentObjectId: String,
        names: Set<String>,
        policy: CueNamePolicy,
        budget: LookupBudget,
        expectedObjectId: String? = null,
    ): DirectoryMatches {
        currentCoroutineContext().ensureActive()
        val authority = cueContentAuthority(scope.source.spec.uri)
        val cursor = access.open(scope.source.spec, parentObjectId)
        var primaryFailure: Throwable? = null
        try {
            val matches = LinkedHashMap<String, ObservedCandidate>()
            var origin: ObservedCandidate? = null
            while (!cursor.exhausted) {
                currentCoroutineContext().ensureActive()
                val batch = cursor.nextBatch(LocalInputLimits.BATCH_SIZE)
                currentCoroutineContext().ensureActive()
                if (batch.size > LocalInputLimits.BATCH_SIZE) throw CueException(CueIssue.LIMIT_EXCEEDED)
                if (batch.isEmpty() && !cursor.exhausted) throw CueException(CueIssue.INCOMPLETE_ENUMERATION)
                for (candidate in batch) {
                    currentCoroutineContext().ensureActive()
                    budget.visit()
                    validateCueNode(scope, candidate, authority)
                    if (candidate.parentObjectId != parentObjectId || candidate.objectId == parentObjectId) {
                        throw CueException(CueIssue.SOURCE_CHANGED)
                    }
                    if (candidate.objectId == expectedObjectId) {
                        if (origin != null && origin != candidate) throw CueException(CueIssue.SOURCE_CHANGED)
                        origin = candidate
                    }
                    val key = try { cueNameKey(candidate.displayName, policy) }
                    catch (failure: CueException) {
                        if (failure.issue == CueIssue.INVALID_PATH) continue else throw failure
                    }
                    if (key !in names) continue
                    val previous = matches[key]
                    if (previous != null && previous.objectId != candidate.objectId) throw CueException(CueIssue.AMBIGUOUS_PATH)
                    if (previous != null && previous != candidate) throw CueException(CueIssue.SOURCE_CHANGED)
                    matches[key] = candidate
                }
            }
            currentCoroutineContext().ensureActive()
            return DirectoryMatches(matches.toMap(), origin)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try { withContext(NonCancellable) { cursor.closeAndAwait() } }
            catch (failure: Throwable) {
                val primary = primaryFailure
                if (primary == null) throw failure else if (primary !== failure) primary.addSuppressed(failure)
            }
        }
    }

    private fun requireSameEdge(actual: ObservedCandidate, expected: ObservedCandidate) {
        if (actual.sourceId != expected.sourceId || actual.objectId != expected.objectId || actual.uri != expected.uri ||
            actual.parentObjectId != expected.parentObjectId || actual.kind != expected.kind) {
            throw CueException(CueIssue.SOURCE_CHANGED)
        }
    }

    private fun validateObservation(scope: CueDirectoryScope, observation: LocalSourceObservation) {
        validateCueNode(scope, observation.root)
        if (observation.source != scope.source.spec || observation.root.objectId != scope.directories.first().objectId ||
            observation.root.uri != scope.directories.first().uri || !observation.root.directory) {
            throw CueException(CueIssue.SOURCE_CHANGED)
        }
        if (observation.access.read != ObservedCapability.AVAILABLE || observation.access.enumerate != ObservedCapability.AVAILABLE ||
            scope.source.grant.persistedRead == ObservedCapability.AVAILABLE && observation.access.persistedRead != ObservedCapability.AVAILABLE) {
            throw CueException(CueIssue.AUTHORIZATION_REQUIRED)
        }
    }

    private class LookupBudget {
        private var visited = 0
        fun visit() {
            if (++visited > CueLimits.LOOKUP_CANDIDATES) throw CueException(CueIssue.LIMIT_EXCEEDED)
        }
    }
}

internal fun validateCueScope(scope: CueDirectoryScope) {
    val source = scope.source
    if (source.state != LibrarySourceState.ACTIVE || source.spec.kind != LocalSourceKind.TREE ||
        source.grant.read != ObservedCapability.AVAILABLE || source.grant.enumerate != ObservedCapability.AVAILABLE) {
        throw CueException(CueIssue.AUTHORIZATION_REQUIRED)
    }
    if (source.authEpoch < 0L || source.revision < 0L || scope.directories.isEmpty()) throw CueException(CueIssue.INVALID_INPUT)
    if (scope.directories.size > CueLimits.DIRECTORY_DEPTH) throw CueException(CueIssue.LIMIT_EXCEEDED)
    if (source.spec.rootObjectId != scope.directories.first().objectId) throw CueException(CueIssue.INVALID_PATH)
    val authority = cueContentAuthority(source.spec.uri)
    val seen = HashSet<String>()
    for ((index, directory) in scope.directories.withIndex()) {
        validateCueNode(scope, directory, authority)
        if (directory.kind != LocalNodeKind.DIRECTORY || !seen.add(directory.objectId)) throw CueException(CueIssue.INVALID_PATH)
        if (index > 0 && directory.parentObjectId != scope.directories[index - 1].objectId) throw CueException(CueIssue.INVALID_PATH)
    }
    validateCueNode(scope, scope.cue, authority)
    if (scope.cue.directory || scope.cue.objectId in seen || scope.cue.parentObjectId != scope.directories.last().objectId) {
        throw CueException(CueIssue.INVALID_PATH)
    }
}

private fun validateCueNode(
    scope: CueDirectoryScope,
    node: ObservedCandidate,
    authority: String = cueContentAuthority(scope.source.spec.uri),
) {
    if (node.displayName.length > LocalInputLimits.NAME_CHARACTERS ||
        (node.parentObjectId?.length ?: 0) > LocalInputLimits.URI_CHARACTERS ||
        listOf(node.mimeType, node.providerVersion, node.providerContentToken, node.entityProof)
            .any { it != null && it.length > CueLimits.FIELD_CHARACTERS }) throw CueException(CueIssue.LIMIT_EXCEEDED)
    if (node.sourceId != scope.source.spec.sourceId || cueContentAuthority(node.uri) != authority ||
        (scope.source.spec.authority != null && scope.source.spec.authority != authority)) {
        throw CueException(CueIssue.SOURCE_CHANGED)
    }
}

private fun cueContentAuthority(value: String): String {
    // URISyntaxException embeds the original URI in its message; the caller needs only a stable code.
    val uri = try { URI(value) } catch (_: URISyntaxException) { throw CueException(CueIssue.INVALID_PATH) }
    val authority = uri.rawAuthority
    if (uri.scheme != "content" || authority.isNullOrBlank()) throw CueException(CueIssue.INVALID_PATH)
    return authority
}
