package io.github.onlinemo.meloxwatch.data.lyrics.load

import android.content.Context
import io.github.onlinemo.meloxwatch.data.library.LibraryException
import io.github.onlinemo.meloxwatch.data.library.LibraryIssue
import io.github.onlinemo.meloxwatch.data.library.LocalContentValidation
import io.github.onlinemo.meloxwatch.data.library.LocalLibraryRepository
import io.github.onlinemo.meloxwatch.data.library.LocalReadDescriptor
import io.github.onlinemo.meloxwatch.data.local.AndroidLocalSources
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.LocalSourceAccess
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceObservation
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.lyrics.EmbeddedLyricsCandidate
import io.github.onlinemo.meloxwatch.data.lyrics.EmbeddedLyricsDiagnostic
import io.github.onlinemo.meloxwatch.data.lyrics.EmbeddedLyricsReader
import io.github.onlinemo.meloxwatch.data.lyrics.LocalLyricsParser
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsDocument
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsKind
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsParseException
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsParseIssue
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * One app-scoped, offline acquisition owner. It does not cache, persist associations/grants,
 * mutate offsets, drive a clock or control playback. The caller checks binding again when it
 * publishes a preview or confirms an association, and supplies the unique media clock to the UI.
 * Binding checks can run on the caller or work dispatcher, so isCurrent uses a thread-safe snapshot.
 */
class LocalLyricsLoader internal constructor(
    private val io: LocalProviderIo,
    private val resolveAudio: suspend (TrackKey, String?, Long?) -> LocalReadDescriptor,
    private val validateAudio: suspend (LocalReadDescriptor) -> LocalReadDescriptor,
    private val sources: LocalSourceAccess,
    private val verifyDocument: suspend (LocalSourceSpec, ObservedCandidate) -> Unit,
    private val opener: LocalLyricsStreamOpener,
    private val isCurrent: (LyricsLoadRequest) -> Boolean,
    private val limits: LocalLyricsLoadLimits = LocalLyricsLoadLimits(),
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) {
    private val occupied = AtomicBoolean(false)
    private val textRead = LocalLyricsTextRead(limits)
    private val siblings = LocalLyricsSourceResolver(sources, limits, workDispatcher)

    suspend fun load(request: LyricsLoadRequest): LocalLyricsLoadResult {
        if (!occupied.compareAndSet(false, true)) return unavailable(request, LyricsLoadFailure(LyricsLoadIssue.BUSY))
        var siblingStatus = LyricsSiblingStatus.NOT_REQUESTED
        var embeddedDiagnostics: List<EmbeddedLyricsDiagnostic> = emptyList()
        var cancelledPhysicalWork = false
        try {
            checkCurrent(request)
            val audio = verifyAudioUnchanged(resolveBound(request))
            val acquired = when (val selection = request.selection) {
                is LyricsLoadSelection.TextDocument -> readText(request, selection.input, LyricsSourceOrigin.EXPLICIT_DOCUMENT)
                is LyricsLoadSelection.Embedded -> readEmbedded(request, audio, selection.candidateId)
                is LyricsLoadSelection.Automatic -> {
                    val associated = selection.associatedDocument
                    if (associated != null) readText(request, associated, LyricsSourceOrigin.EXPLICIT_DOCUMENT)
                    else {
                        val resolution = selection.siblingScope?.let { scope ->
                            siblings.resolve(audio, scope) { checkCurrent(request) }
                        }
                        siblingStatus = resolution?.status ?: LyricsSiblingStatus.NOT_REQUESTED
                        if (siblingStatus == LyricsSiblingStatus.AUDIO_SCOPE_MISMATCH) lyricsLoadFail(LyricsLoadIssue.SOURCE_CHANGED)
                        val match = resolution?.candidate
                        if (match != null) {
                            readText(request, LyricsDocumentInput(
                                documentId = match.objectId,
                                sourceGeneration = request.binding.sourceGeneration,
                                source = audio.source.spec,
                                candidate = match,
                                requirePersistedRead = audio.source.grant.persistedRead == ObservedCapability.AVAILABLE,
                            ), LyricsSourceOrigin.SAME_NAME_LRC)
                        } else readEmbedded(request, audio, null)
                    }
                }
            }
            embeddedDiagnostics = acquired.diagnostics
            checkCurrent(request)
            if (request.binding.documentIdentity != null && request.binding.documentIdentity != acquired.source.documentId) {
                lyricsLoadFail(LyricsLoadIssue.DOCUMENT_MISMATCH)
            }
            val latest = resolveBound(request)
            if (!sameAudio(audio, latest)) lyricsLoadFail(LyricsLoadIssue.SOURCE_CHANGED)
            val verified = verifyAudioUnchanged(latest)
            checkCurrent(request)
            return LocalLyricsLoadResult.Loaded(
                request.binding, verified, acquired.source, acquired.document, acquired.candidates,
                acquired.diagnostics, siblingStatus, acquired.accountedBytes,
            )
        } catch (error: CancellationException) {
            cancelledPhysicalWork = true
            throw error
        } catch (error: LyricsLoadException) {
            return unavailable(request, error.failure, siblingStatus, embeddedDiagnostics)
        } catch (error: EmbeddedLoadException) {
            return unavailable(request, error.failure, siblingStatus, error.diagnostics)
        } catch (error: SourceReadException) {
            return unavailable(request, LyricsLoadFailure(error.issue.toLoadIssue(), sourceIssue = error.issue), siblingStatus, embeddedDiagnostics)
        } catch (error: LibraryException) {
            return unavailable(request, LyricsLoadFailure(error.issue.toLoadIssue(), libraryIssue = error.issue), siblingStatus, embeddedDiagnostics)
        } catch (error: LyricsParseException) {
            val issue = when (error.issue) {
                LyricsParseIssue.MEMORY_LIMIT -> LyricsLoadIssue.MEMORY_LIMIT
                LyricsParseIssue.INPUT_TOO_LARGE -> LyricsLoadIssue.INPUT_TOO_LARGE
                else -> LyricsLoadIssue.PARSE_FAILED
            }
            return unavailable(request, LyricsLoadFailure(issue, parseIssue = error.issue), siblingStatus, embeddedDiagnostics)
        } catch (_: IOException) {
            return unavailable(request, LyricsLoadFailure(LyricsLoadIssue.IO_FAILURE), siblingStatus, embeddedDiagnostics)
        } catch (_: SecurityException) {
            return unavailable(request, LyricsLoadFailure(LyricsLoadIssue.PERMISSION_DENIED), siblingStatus, embeddedDiagnostics)
        } catch (_: IllegalArgumentException) {
            return unavailable(request, LyricsLoadFailure(LyricsLoadIssue.INVALID_INPUT), siblingStatus, embeddedDiagnostics)
        } catch (_: OutOfMemoryError) {
            return unavailable(request, LyricsLoadFailure(LyricsLoadIssue.MEMORY_LIMIT), siblingStatus, embeddedDiagnostics)
        } finally {
            // Ordinary completed/BUSY calls never wait for another request. Cancellation can leave
            // this call's non-cooperative IO/close running, so retain ownership until physical idle.
            try { if (cancelledPhysicalWork) withContext(NonCancellable) { io.awaitIdle() } }
            finally { occupied.set(false) }
        }
    }

    /**
     * 显式播放确认只复核已展示的字节和音频，不再解析一份歌词或创建新时间线。
     * 同名来源重新走授权父边与消歧；内嵌来源由原音频的真实内容验证保护。
     */
    suspend fun validate(request: LyricsLoadRequest, loaded: LocalLyricsLoadResult.Loaded): Boolean {
        if (!occupied.compareAndSet(false, true)) return false
        var cancelledPhysicalWork = false
        try {
            checkCurrent(request)
            if (loaded.binding != request.binding) return false
            val audio = verifyAudioUnchanged(resolveBound(request))
            if (!sameAudio(audio, loaded.audio)) return false
            val input = when (val selection = request.selection) {
                is LyricsLoadSelection.TextDocument -> selection.input
                is LyricsLoadSelection.Automatic -> selection.associatedDocument ?: if (
                    loaded.source.origin == LyricsSourceOrigin.SAME_NAME_LRC && selection.siblingScope != null
                ) {
                    val match = siblings.resolve(audio, selection.siblingScope) { checkCurrent(request) }
                    val candidate = match.candidate ?: return false
                    if (match.status != LyricsSiblingStatus.MATCHED) return false
                    LyricsDocumentInput(candidate.objectId, request.binding.sourceGeneration, audio.source.spec,
                        candidate, requirePersistedRead = audio.source.grant.persistedRead == ObservedCapability.AVAILABLE)
                } else null
                is LyricsLoadSelection.Embedded -> null
            }
            if (input != null) {
                if (loaded.source.origin !in setOf(LyricsSourceOrigin.EXPLICIT_DOCUMENT, LyricsSourceOrigin.SAME_NAME_LRC) ||
                    input.documentId != loaded.source.documentId || input.candidate.objectId != loaded.source.objectId ||
                    input.source.sourceId != loaded.source.sourceId || input.sourceGeneration != loaded.source.sourceGeneration) return false
                val before = observeDocument(input)
                verifyDocument(before.source, input.candidate)
                checkCurrent(request)
                val caller = currentCoroutineContext()
                val actual = io.read { signal ->
                    opener.open(input.candidate.uri, signal).use { opened ->
                        textRead.fingerprint(opened.input, declaredSize(input.candidate.sizeBytes, opened.declaredSizeBytes), signal, caller)
                    }
                }
                if (actual.sha256 != loaded.source.contentSha256 ||
                    input.expectedContentSha256?.let { it != actual.sha256 } == true) return false
                verifyDocument(before.source, input.candidate)
                val after = observeDocument(input)
                if (before.identity != after.identity || before.source != after.source || before.root.objectId != after.root.objectId ||
                    before.root.uri != after.root.uri) return false
            } else if (loaded.source.origin !in setOf(LyricsSourceOrigin.EMBEDDED_SYNCHRONIZED, LyricsSourceOrigin.EMBEDDED_PLAIN_TEXT) ||
                loaded.source.sourceId != audio.source.spec.sourceId || loaded.source.objectId != audio.objectId ||
                loaded.source.embeddedCandidateId == null) return false
            checkCurrent(request)
            val latest = verifyAudioUnchanged(resolveBound(request))
            checkCurrent(request)
            return sameAudio(audio, latest)
        } catch (error: CancellationException) {
            cancelledPhysicalWork = true
            throw error
        } catch (_: Exception) {
            return false
        } catch (_: OutOfMemoryError) {
            return false
        } finally {
            try { if (cancelledPhysicalWork) withContext(NonCancellable) { io.awaitIdle() } }
            finally { occupied.set(false) }
        }
    }

    private suspend fun readText(request: LyricsLoadRequest, input: LyricsDocumentInput, origin: LyricsSourceOrigin): Acquisition {
        val limits = requestLimits(request)
        val textRead = LocalLyricsTextRead(limits)
        checkCurrent(request)
        if (request.binding.documentIdentity != null && request.binding.documentIdentity != input.documentId) {
            lyricsLoadFail(LyricsLoadIssue.DOCUMENT_MISMATCH)
        }
        if (input.source.kind == LocalSourceKind.MEDIA_STORE) lyricsLoadFail(LyricsLoadIssue.INVALID_INPUT)
        val before = observeDocument(input)
        verifyDocument(before.source, input.candidate)
        checkCurrent(request)
        val context = currentCoroutineContext()
        val bytes = io.read { signal ->
            opener.open(input.candidate.uri, signal).use { opened ->
                textRead.read(opened.input, declaredSize(input.candidate.sizeBytes, opened.declaredSizeBytes), signal, context)
            }
        }
        val shaBaseline = input.candidate.providerContentToken?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
        if (shaBaseline != null && !shaBaseline.matches(Regex("[0-9a-f]{64}"))) lyricsLoadFail(LyricsLoadIssue.CONTENT_UNKNOWN)
        if (input.expectedContentSha256?.let { it != bytes.sha256 } == true || shaBaseline?.let { it != bytes.sha256 } == true) {
            lyricsLoadFail(LyricsLoadIssue.SOURCE_CHANGED)
        }
        checkCurrent(request)
        val nativeToken = input.candidate.providerContentToken?.takeUnless { it.startsWith("sha256:") }
        val proofOverhead = if (nativeToken == null) limits.ioChunkBytes + 256L else 0L
        if (bytes.acquisitionOverheadBytes + bytes.bytes.size + proofOverhead > limits.lyrics.maxAccountedBytes) {
            lyricsLoadFail(LyricsLoadIssue.MEMORY_LIMIT)
        }
        if (nativeToken == null) {
            val proof = io.read { signal ->
                opener.open(input.candidate.uri, signal).use { opened ->
                    textRead.fingerprint(opened.input, declaredSize(input.candidate.sizeBytes, opened.declaredSizeBytes), signal, context)
                }
            }
            if (proof.sha256 != bytes.sha256 || proof.sizeBytes != bytes.bytes.size) lyricsLoadFail(LyricsLoadIssue.SOURCE_CHANGED)
        }
        verifyDocument(before.source, input.candidate)
        val after = observeDocument(input)
        if (before.identity != after.identity || before.source != after.source ||
            before.root.objectId != after.root.objectId || before.root.uri != after.root.uri) {
            lyricsLoadFail(LyricsLoadIssue.SOURCE_CHANGED)
        }
        checkCurrent(request)
        val overhead = bytes.acquisitionOverheadBytes + proofOverhead
        val available = limits.lyrics.maxAccountedBytes - overhead
        if (available <= 0L) lyricsLoadFail(LyricsLoadIssue.MEMORY_LIMIT)
        val document = LocalLyricsParser(limits.lyrics.copy(maxAccountedBytes = available), workDispatcher).parse(bytes.bytes, input.encoding)
        val accounted = overhead + bytes.bytes.size.toLong() * 6L + 256L + document.accountedResidentBytes
        if (accounted > limits.lyrics.maxAccountedBytes) lyricsLoadFail(LyricsLoadIssue.MEMORY_LIMIT)
        return Acquisition(LyricsLoadedSource(
            origin, before.source.sourceId, input.candidate.objectId, input.documentId, input.sourceGeneration, bytes.sha256,
        ), document, emptyList(), emptyList(), accounted)
    }

    private suspend fun readEmbedded(request: LyricsLoadRequest, audio: LocalReadDescriptor, candidateId: String?): Acquisition {
        val limits = requestLimits(request)
        checkCurrent(request)
        if (candidateId != null && request.binding.documentIdentity != null && request.binding.documentIdentity != candidateId) {
            lyricsLoadFail(LyricsLoadIssue.DOCUMENT_MISMATCH)
        }
        val available = limits.lyrics.maxAccountedBytes - LYRICS_LOAD_ENVELOPE_BYTES
        if (available <= 0L) lyricsLoadFail(LyricsLoadIssue.MEMORY_LIMIT)
        val embeddedLimits = limits.embedded.copy(lyrics = limits.lyrics.copy(maxAccountedBytes = available))
        val context = currentCoroutineContext()
        val result = io.read { signal ->
            opener.open(audio.location.uri, signal).use { opened ->
                declaredSize(audio.sizeBytes, opened.declaredSizeBytes)
                // This bridge blocks only the accepted physical worker. Its event loop keeps all
                // stream reads on that worker; the frozen parser can suspend onto bounded CPU work.
                runBlocking(context.minusKey(ContinuationInterceptor)) {
                    val physicalDispatcher = checkNotNull(coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher)
                    EmbeddedLyricsReader(embeddedLimits, physicalDispatcher, workDispatcher).read(opened.input)
                }
            }
        }
        checkCurrent(request)
        if (!result.metadataComplete || result.accountedBytes == null) {
            throw EmbeddedLoadException(result.diagnostics)
        }
        val candidate = if (candidateId == null) result.defaultCandidate else result.candidates.firstOrNull { it.source.candidateId == candidateId }
        if (candidate == null) {
            throw EmbeddedLoadException(result.diagnostics, if (candidateId == null) LyricsLoadIssue.NO_LYRICS else LyricsLoadIssue.DOCUMENT_MISMATCH)
        }
        val origin = if (candidate.document.kind == LyricsKind.SYNCHRONIZED) LyricsSourceOrigin.EMBEDDED_SYNCHRONIZED
            else LyricsSourceOrigin.EMBEDDED_PLAIN_TEXT
        return Acquisition(LyricsLoadedSource(
            origin, audio.source.spec.sourceId, audio.objectId, candidate.source.candidateId,
            request.binding.sourceGeneration, candidate.source.contentSha256, candidate.source.candidateId,
        ), candidate.document, result.candidates, result.diagnostics, LYRICS_LOAD_ENVELOPE_BYTES + result.accountedBytes)
    }

    private suspend fun observeDocument(input: LyricsDocumentInput): LocalSourceObservation {
        val observed = sources.observe(input.source)
        val actual = observed.source
        if (actual.copy(authority = input.source.authority, rootObjectId = input.source.rootObjectId) != input.source ||
            input.source.authority != null && input.source.authority != actual.authority ||
            input.source.rootObjectId != null && input.source.rootObjectId != actual.rootObjectId) lyricsLoadFail(LyricsLoadIssue.SOURCE_CHANGED)
        when (observed.access.read) {
            ObservedCapability.UNAVAILABLE -> lyricsLoadFail(LyricsLoadIssue.PERMISSION_DENIED)
            ObservedCapability.UNKNOWN -> {
                // A TREE observation proves its directory query, not the selected file's bytes.
                // Candidate rechecks and the actual bounded open/read prove file access below.
                if (actual.kind != LocalSourceKind.TREE) lyricsLoadFail(LyricsLoadIssue.PERMISSION_DENIED)
            }
            ObservedCapability.AVAILABLE -> Unit
        }
        if (input.requirePersistedRead && observed.access.persistedRead != ObservedCapability.AVAILABLE) {
            lyricsLoadFail(LyricsLoadIssue.PERMISSION_REVOKED)
        }
        if (actual.kind != LocalSourceKind.TREE && (observed.root.objectId != input.candidate.objectId || observed.root.uri != input.candidate.uri)) {
            lyricsLoadFail(LyricsLoadIssue.SOURCE_CHANGED)
        }
        return observed
    }

    private fun requestLimits(request: LyricsLoadRequest): LocalLyricsLoadLimits {
        val lyrics = limits.lyrics.copy(maxAccountedBytes = minOf(limits.lyrics.maxAccountedBytes, request.maxAccountedBytes))
        return limits.copy(lyrics = lyrics, embedded = limits.embedded.copy(lyrics = lyrics))
    }

    private suspend fun resolveBound(request: LyricsLoadRequest): LocalReadDescriptor {
        val binding = request.binding
        val descriptor = resolveAudio(binding.trackKey, binding.locationId, binding.contentRevision)
        if (descriptor.trackKey != binding.trackKey || descriptor.location.locationId != binding.locationId ||
            descriptor.location.contentRevision != binding.contentRevision || descriptor.source.spec.sourceId != binding.sourceId ||
            descriptor.source.revision != binding.sourceRevision || descriptor.source.authEpoch != binding.authorizationEpoch) {
            lyricsLoadFail(LyricsLoadIssue.SOURCE_CHANGED)
        }
        return descriptor
    }

    private suspend fun verifyAudioUnchanged(descriptor: LocalReadDescriptor): LocalReadDescriptor {
        val verified = validateAudio(descriptor)
        if (verified.contentValidation != LocalContentValidation.VERIFIED || !sameAudio(descriptor, verified)) {
            lyricsLoadFail(LyricsLoadIssue.CONTENT_UNKNOWN)
        }
        return verified
    }

    private suspend fun checkCurrent(request: LyricsLoadRequest) {
        currentCoroutineContext().ensureActive()
        if (!isCurrent(request)) lyricsLoadFail(LyricsLoadIssue.STALE_REQUEST)
    }

    private fun declaredSize(expected: Long?, actual: Long?): Long? {
        if (expected != null && actual != null && expected != actual) lyricsLoadFail(LyricsLoadIssue.SOURCE_CHANGED)
        return expected ?: actual
    }

    private fun sameAudio(left: LocalReadDescriptor, right: LocalReadDescriptor): Boolean =
        left.copy(contentValidation = LocalContentValidation.UNKNOWN) == right.copy(contentValidation = LocalContentValidation.UNKNOWN)

    private data class Acquisition(
        val source: LyricsLoadedSource,
        val document: LyricsDocument,
        val candidates: List<EmbeddedLyricsCandidate>,
        val diagnostics: List<EmbeddedLyricsDiagnostic>,
        val accountedBytes: Long,
    )

    private fun unavailable(
        request: LyricsLoadRequest,
        failure: LyricsLoadFailure,
        siblingStatus: LyricsSiblingStatus = LyricsSiblingStatus.NOT_REQUESTED,
        diagnostics: List<EmbeddedLyricsDiagnostic> = emptyList(),
    ): LocalLyricsLoadResult.Unavailable = LocalLyricsLoadResult.Unavailable(request.binding, failure, siblingStatus, diagnostics)

    private class EmbeddedLoadException(val diagnostics: List<EmbeddedLyricsDiagnostic>, issue: LyricsLoadIssue = LyricsLoadIssue.EMBEDDED_FAILED) :
        Exception(issue.name) { val failure = LyricsLoadFailure(issue) }

    companion object {
        /** Reuse the app's provider IO/sources instance; do not create a second acquisition channel. */
        fun android(
            context: Context,
            io: LocalProviderIo,
            library: LocalLibraryRepository,
            sources: AndroidLocalSources,
            isCurrent: (LyricsLoadRequest) -> Boolean,
            limits: LocalLyricsLoadLimits = LocalLyricsLoadLimits(),
        ): LocalLyricsLoader = LocalLyricsLoader(
            io, library::resolveLocalRead, sources::validateRead, sources, sources::verifyObservedCandidate,
            AndroidLocalLyricsStream(context.applicationContext.contentResolver), isCurrent, limits,
        )
    }
}

private fun SourceReadIssue.toLoadIssue(): LyricsLoadIssue = when (this) {
    SourceReadIssue.BUSY -> LyricsLoadIssue.BUSY
    SourceReadIssue.PERMISSION_DENIED -> LyricsLoadIssue.PERMISSION_DENIED
    SourceReadIssue.PERMISSION_REVOKED -> LyricsLoadIssue.PERMISSION_REVOKED
    SourceReadIssue.SOURCE_CHANGED -> LyricsLoadIssue.SOURCE_CHANGED
    SourceReadIssue.CONTENT_UNKNOWN -> LyricsLoadIssue.CONTENT_UNKNOWN
    SourceReadIssue.FILE_MISSING -> LyricsLoadIssue.FILE_MISSING
    SourceReadIssue.SOURCE_UNAVAILABLE -> LyricsLoadIssue.SOURCE_UNAVAILABLE
    SourceReadIssue.INVALID_SOURCE, SourceReadIssue.NOT_AUDIO -> LyricsLoadIssue.INVALID_INPUT
    SourceReadIssue.UNSUPPORTED_FORMAT -> LyricsLoadIssue.EMBEDDED_FAILED
    SourceReadIssue.METADATA_UNREADABLE, SourceReadIssue.STORAGE_FULL -> LyricsLoadIssue.IO_FAILURE
}

private fun LibraryIssue.toLoadIssue(): LyricsLoadIssue = when (this) {
    LibraryIssue.BUSY -> LyricsLoadIssue.BUSY
    LibraryIssue.CONFLICT, LibraryIssue.SOURCE_CHANGED, LibraryIssue.VIEW_EXPIRED -> LyricsLoadIssue.SOURCE_CHANGED
    LibraryIssue.PERMISSION_REVOKED -> LyricsLoadIssue.PERMISSION_REVOKED
    LibraryIssue.FILE_MISSING -> LyricsLoadIssue.FILE_MISSING
    LibraryIssue.INVALID_INPUT -> LyricsLoadIssue.INVALID_INPUT
    LibraryIssue.INCOMPLETE -> LyricsLoadIssue.CONTENT_UNKNOWN
    LibraryIssue.SOURCE_UNAVAILABLE, LibraryIssue.STORAGE_FULL, LibraryIssue.STORAGE_UNAVAILABLE, LibraryIssue.RECOVERY_REQUIRED -> LyricsLoadIssue.SOURCE_UNAVAILABLE
}
