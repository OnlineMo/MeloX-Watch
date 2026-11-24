package io.github.onlinemo.meloxwatch.data.lyrics.load

import io.github.onlinemo.meloxwatch.data.library.LocalContentValidation
import io.github.onlinemo.meloxwatch.data.library.LocalReadDescriptor
import io.github.onlinemo.meloxwatch.data.local.LocalInputLimits
import io.github.onlinemo.meloxwatch.data.local.LocalSourceAccess
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceObservation
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal data class LyricsSiblingResolution(val status: LyricsSiblingStatus, val candidate: ObservedCandidate? = null)

/** Only a complete, explicitly authorized TREE enumeration can establish a unique sibling. */
internal class LocalLyricsSourceResolver(
    private val access: LocalSourceAccess,
    private val limits: LocalLyricsLoadLimits,
    private val workDispatcher: CoroutineDispatcher,
) {
    suspend fun resolve(
        audio: LocalReadDescriptor,
        scope: LyricsSiblingScope,
        checkCurrent: suspend () -> Unit,
    ): LyricsSiblingResolution = withContext(workDispatcher) {
        val source = audio.source.spec
        if (source.kind != LocalSourceKind.TREE) return@withContext LyricsSiblingResolution(LyricsSiblingStatus.SCOPE_UNAVAILABLE)
        checkCurrent()
        val before = access.observe(source)
        checkSource(audio, before)
        if (before.access.enumerate != ObservedCapability.AVAILABLE || !before.root.directory) {
            return@withContext LyricsSiblingResolution(LyricsSiblingStatus.SCOPE_UNAVAILABLE)
        }
        val targetName = canonical(scope.audioFileName.substringBeforeLast('.', scope.audioFileName) + ".lrc")
        val cursor = access.open(source, scope.parentObjectId)
        var scanned = 0
        var audioSeen = false
        var match: ObservedCandidate? = null
        var ambiguous = false
        var status: LyricsSiblingStatus? = null
        try {
            while (!cursor.exhausted && scanned < limits.maxSiblingEntries) {
                checkCurrent()
                val requested = minOf(LocalInputLimits.BATCH_SIZE, limits.maxSiblingEntries - scanned)
                val batch = cursor.nextBatch(requested)
                if (batch.size > requested || batch.isEmpty() && !cursor.exhausted) {
                    status = LyricsSiblingStatus.INCOMPLETE_PROVIDER
                    break
                }
                for (candidate in batch) {
                    checkCurrent()
                    scanned++
                    if (candidate.sourceId != source.sourceId || candidate.parentObjectId != scope.parentObjectId ||
                        candidate.displayName.length >= LocalInputLimits.NAME_CHARACTERS) {
                        // A truncated name or unproven parent row could hide another same-name object.
                        status = LyricsSiblingStatus.INCOMPLETE_PROVIDER
                        break
                    }
                    if (candidate.objectId == audio.objectId && candidate.uri == audio.location.uri) {
                        if (!candidate.mayContainAudio || candidate.directory || candidate.displayName != scope.audioFileName) {
                            status = LyricsSiblingStatus.AUDIO_SCOPE_MISMATCH
                            break
                        }
                        audioSeen = true
                    }
                    if (!candidate.directory && canonical(candidate.displayName) == targetName) {
                        val previous = match
                        if (previous == null) match = candidate
                        else if (previous.objectId != candidate.objectId || previous.uri != candidate.uri) ambiguous = true
                        else if (previous != candidate) {
                            status = LyricsSiblingStatus.INCOMPLETE_PROVIDER
                            break
                        }
                    }
                }
                if (status != null) break
            }
            if (status == null && !cursor.exhausted) {
                // The exact ceiling still needs an EOF probe; the first excess row is never selected.
                checkCurrent()
                val probe = cursor.nextBatch(1)
                status = when {
                    probe.size > 1 -> LyricsSiblingStatus.INCOMPLETE_PROVIDER
                    probe.isNotEmpty() -> LyricsSiblingStatus.DIRECTORY_TRUNCATED
                    !cursor.exhausted -> LyricsSiblingStatus.INCOMPLETE_PROVIDER
                    else -> null
                }
            }
        } finally {
            // Provider cursor retirement, including a non-cooperative close, finishes before reuse.
            withContext(NonCancellable) { cursor.closeAndAwait() }
        }
        checkCurrent()
        val after = access.observe(source)
        checkSource(audio, after)
        if (before.identity != after.identity || before.source != after.source ||
            before.root.objectId != after.root.objectId || before.root.uri != after.root.uri) {
            lyricsLoadFail(LyricsLoadIssue.SOURCE_CHANGED)
        }
        if (after.access.enumerate != ObservedCapability.AVAILABLE) {
            return@withContext LyricsSiblingResolution(LyricsSiblingStatus.SCOPE_UNAVAILABLE)
        }
        status?.let { return@withContext LyricsSiblingResolution(it) }
        if (!audioSeen) return@withContext LyricsSiblingResolution(LyricsSiblingStatus.AUDIO_SCOPE_MISMATCH)
        when {
            ambiguous -> LyricsSiblingResolution(LyricsSiblingStatus.AMBIGUOUS)
            match == null -> LyricsSiblingResolution(LyricsSiblingStatus.NO_MATCH)
            else -> LyricsSiblingResolution(LyricsSiblingStatus.MATCHED, match)
        }
    }

    private fun checkSource(audio: LocalReadDescriptor, observed: LocalSourceObservation) {
        if (observed.source != audio.source.spec) lyricsLoadFail(LyricsLoadIssue.SOURCE_CHANGED)
        when (observed.access.read) {
            ObservedCapability.UNAVAILABLE -> lyricsLoadFail(LyricsLoadIssue.PERMISSION_DENIED)
            ObservedCapability.UNKNOWN -> {
                // TREE enumeration does not prove file access. The loader must independently
                // validate this exact audio descriptor before asking for its sibling scope.
                if (audio.contentValidation != LocalContentValidation.VERIFIED) lyricsLoadFail(LyricsLoadIssue.CONTENT_UNKNOWN)
            }
            ObservedCapability.AVAILABLE -> Unit
        }
        if (audio.source.grant.persistedRead == ObservedCapability.AVAILABLE &&
            observed.access.persistedRead != ObservedCapability.AVAILABLE) lyricsLoadFail(LyricsLoadIssue.PERMISSION_REVOKED)
    }

    private fun canonical(name: String): String = Normalizer.normalize(
        Normalizer.normalize(name, Normalizer.Form.NFC).lowercase(Locale.ROOT), Normalizer.Form.NFC,
    )
}
