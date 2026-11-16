package io.github.onlinemo.meloxwatch.data.cue

import io.github.onlinemo.meloxwatch.data.library.LibrarySourceState
import io.github.onlinemo.meloxwatch.data.local.LocalNodeKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CuePathMatcherTest {
    @Test fun nestedReferencesUseActualEnumerationEdgesAndOpaqueObjectUris() = runBlocking {
        val graph = CueGraph()
        val music = graph.node("music-object", "root", "Music", LocalNodeKind.DIRECTORY)
        val audio = graph.node("audio-object", music.objectId, "Audio", LocalNodeKind.DIRECTORY)
        graph.cue = graph.cue.copy(parentObjectId = music.objectId, displayName = "authorized:album.cue")
        val first = graph.node("opaque-first", audio.objectId, "first.flac")
        val second = graph.node("opaque-second", music.objectId, "second.flac")
        graph.children["root"] = listOf(music)
        graph.children[music.objectId] = listOf(graph.cue, audio, second)
        graph.children[audio.objectId] = listOf(first)
        val parsed = CueParser.parse((cueText("Audio/first.flac") +
            "\nFILE \"second.flac\" WAVE\nTRACK 02 AUDIO\nINDEX 01 00:00:00").toByteArray())
        val scope = graph.scope(listOf(graph.root, music))
        val matched = CuePathMatcher(graph).match(parsed, scope)
        assertEquals(listOf("opaque-first", "opaque-second"), matched.files.map { it.candidate.objectId })
        assertEquals(listOf(first.uri, second.uri), matched.files.map { it.candidate.uri })
        assertEquals(parsed.rawContentToken, matched.rawContentToken)
        val repeated = CuePathMatcher(graph).match(parsed, scope)
        assertEquals(matched.files, repeated.files)
        assertEquals(100, graph.largestRequestedBatch)
        assertEquals(1, graph.maximumActiveCursors)
        assertEquals(0, graph.activeCursors)
        assertEquals(graph.opened.size, graph.closedCursors)
    }

    @Test fun aLateNfcCollisionFailsEvenAfterAnEarlyExactSpellingMatch() = runBlocking {
        val graph = CueGraph()
        val composed = graph.node("composed", "root", "Café.flac")
        val decomposed = graph.node("decomposed", "root", "Cafe\u0301.flac")
        graph.children["root"] = listOf(graph.cue, composed) +
            (1..100).map { graph.node("other-$it", "root") } + decomposed
        val parsed = CueParser.parse(cueText("Café.flac").toByteArray())
        val error = expectCueIssue(CueIssue.AMBIGUOUS_PATH) { CuePathMatcher(graph).match(parsed, graph.scope()) }
        assertFalse(error.message.orEmpty().contains("Café"))
        assertEquals(0, graph.activeCursors)
        assertEquals(1, graph.maximumActiveCursors)
        assertEquals(graph.opened.size, graph.closedCursors)
    }

    @Test fun caseFoldingRequiresExplicitChoiceAndNeverPicksOneOfACollision() = runBlocking {
        val graph = CueGraph()
        val lower = graph.node("lower", "root", "mix.flac")
        graph.children["root"] = listOf(graph.cue, lower)
        val parsed = CueParser.parse(cueText("mix.FLAC").toByteArray())
        expectCueIssue(CueIssue.MISSING_AUDIO) { CuePathMatcher(graph).match(parsed, graph.scope()) }
        assertEquals("lower", CuePathMatcher(graph).match(parsed, graph.scope(), CueNamePolicy.FOLD_CASE).files.single().candidate.objectId)
        val upper = graph.node("upper", "root", "mix.FLAC")
        graph.children["root"] = listOf(graph.cue, lower, upper)
        assertEquals("upper", CuePathMatcher(graph).match(parsed, graph.scope()).files.single().candidate.objectId)
        expectCueIssue(CueIssue.AMBIGUOUS_PATH) { CuePathMatcher(graph).match(parsed, graph.scope(), CueNamePolicy.FOLD_CASE) }
        assertEquals(0, graph.activeCursors)
    }

    @Test fun differentPathsToTheSameProviderObjectCannotCreateDuplicateFileGroups() = runBlocking {
        val graph = CueGraph()
        val first = graph.node("folder-a", "root", "a", LocalNodeKind.DIRECTORY)
        val second = graph.node("folder-b", "root", "b", LocalNodeKind.DIRECTORY)
        graph.children["root"] = listOf(graph.cue, first, second)
        graph.children[first.objectId] = listOf(graph.node("shared-audio", first.objectId, "song.flac"))
        graph.children[second.objectId] = listOf(graph.node("shared-audio", second.objectId, "song.flac"))
        val parsed = CueParser.parse((cueText("a/song.flac") +
            "\nFILE \"b/song.flac\" WAVE\nTRACK 02 AUDIO\nINDEX 01 00:00:00").toByteArray())
        expectCueIssue(CueIssue.DUPLICATE_REFERENCE) { CuePathMatcher(graph).match(parsed, graph.scope()) }
        assertEquals(0, graph.activeCursors)
    }

    @Test fun missingSecondFileReturnsNoPartialMatchAndClosesEveryCursor() = runBlocking {
        val graph = CueGraph()
        graph.children["root"] = listOf(graph.cue, graph.node("first", "root", "first.flac"))
        val parsed = CueParser.parse((cueText("first.flac") +
            "\nFILE \"missing.flac\" WAVE\nTRACK 02 AUDIO\nINDEX 01 00:00:00").toByteArray())
        var returned = false
        expectCueIssue(CueIssue.MISSING_AUDIO) {
            CuePathMatcher(graph).match(parsed, graph.scope())
            returned = true
        }
        assertFalse(returned)
        assertEquals(graph.opened.size, graph.closedCursors)
        assertEquals(0, graph.activeCursors)
    }

    @Test fun directDocumentRevokedSourceAndFabricatedRootChainsCannotAuthorizeEnumeration() = runBlocking {
        val graph = CueGraph()
        val parsed = CueParser.parse(cueText().toByteArray())
        val scope = graph.scope()
        expectCueIssue(CueIssue.AUTHORIZATION_REQUIRED) {
            CuePathMatcher(graph).match(parsed, scope.copy(source = scope.source.copy(state = LibrarySourceState.NEEDS_AUTHORIZATION)))
        }
        expectCueIssue(CueIssue.AUTHORIZATION_REQUIRED) {
            CuePathMatcher(graph).match(parsed, scope.copy(source = scope.source.copy(spec = scope.source.spec.copy(kind = LocalSourceKind.DOCUMENT))))
        }
        val forged = graph.node("outside", "not-root", "Outside", LocalNodeKind.DIRECTORY)
        expectCueIssue(CueIssue.INVALID_PATH) {
            CuePathMatcher(graph).match(parsed, scope.copy(directories = listOf(graph.root, forged), cue = graph.cue.copy(parentObjectId = forged.objectId)))
        }
        expectCueIssue(CueIssue.INVALID_PATH) { CuePathMatcher(graph).match(parsed, scope.copy(directories = listOf(graph.root, graph.root))) }
        assertTrue(graph.opened.isEmpty())
    }

    @Test fun foreignSourceAuthorityOrParentEdgesFailInsteadOfOpeningGuessedUris() = runBlocking {
        for (mutation in 1..3) {
            val graph = CueGraph()
            val audio = graph.node("audio", "root", "album.flac")
            val foreign = when (mutation) {
                1 -> audio.copy(sourceId = "foreign-source")
                2 -> audio.copy(uri = "content://foreign-test/tree/root/document/audio")
                else -> audio.copy(parentObjectId = "outside-root")
            }
            graph.children["root"] = listOf(graph.cue, foreign)
            expectCueIssue(CueIssue.SOURCE_CHANGED) { CuePathMatcher(graph).match(CueParser.parse(cueText().toByteArray()), graph.scope()) }
            assertEquals(1, graph.opened.size)
            assertEquals(0, graph.activeCursors)
        }
    }

    @Test fun cancellationWaitsForAnIgnoringReadAndPhysicalCursorRetirement() = runBlocking {
        val graph = CueGraph()
        graph.children["root"] = listOf(graph.cue, graph.node("audio", "root", "album.flac"))
        val entered = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val closeEntered = CompletableDeferred<Unit>()
        val releaseClose = CompletableDeferred<Unit>()
        graph.onBatch = { _, _ ->
            entered.complete(Unit)
            withContext(NonCancellable) { releaseRead.await() }
        }
        graph.onClose = { _ -> closeEntered.complete(Unit); releaseClose.await() }
        var returned = false
        val task = launch { CuePathMatcher(graph).match(CueParser.parse(cueText().toByteArray()), graph.scope()); returned = true }
        try {
            withTimeout(5000L) { entered.await() }
            task.cancel()
            yield()
            assertTrue(task.isCancelled)
            assertFalse(task.isCompleted)
            assertEquals(1, graph.activeCursors)
            assertEquals(1, graph.opened.size)
            releaseRead.complete(Unit)
            withTimeout(5000L) { closeEntered.await() }
            assertFalse(task.isCompleted)
            assertEquals(1, graph.activeCursors)
            assertEquals(0, graph.closedCursors)
            releaseClose.complete(Unit)
            task.join()
            assertFalse(returned)
            assertEquals(0, graph.activeCursors)
            assertEquals(1, graph.closedCursors)
        } finally {
            task.cancel()
            releaseRead.complete(Unit)
            releaseClose.complete(Unit)
            task.join()
        }
    }

    @Test fun unfinishedInvalidAndFailedBatchesPreserveErrorsWithoutAnUnboundedRetry() = runBlocking {
        val parsed = CueParser.parse(cueText().toByteArray())
        val empty = CueGraph()
        empty.overrideBatch = { _, _ -> emptyList() }
        expectCueIssue(CueIssue.INCOMPLETE_ENUMERATION) { CuePathMatcher(empty).match(parsed, empty.scope()) }
        assertEquals(1, empty.opened.size)
        assertEquals(1, empty.closedCursors)
        val oversized = CueGraph()
        oversized.overrideBatch = { _, limit -> List(limit + 1) { oversized.cue } }
        expectCueIssue(CueIssue.LIMIT_EXCEEDED) { CuePathMatcher(oversized).match(parsed, oversized.scope()) }
        assertEquals(1, oversized.closedCursors)
        for (sameFailure in listOf(false, true)) {
            val failed = CueGraph()
            val primary = CueException(CueIssue.INCOMPLETE_ENUMERATION)
            val retirement = if (sameFailure) primary else IOException("controlled retirement failure")
            failed.onBatch = { _, _ -> throw primary }
            failed.onClose = { _ -> throw retirement }
            val caught = expectCueIssue(CueIssue.INCOMPLETE_ENUMERATION) {
                CuePathMatcher(failed).match(parsed, failed.scope())
            }
            assertSame(primary, caught)
            assertEquals(if (sameFailure) 0 else 1, caught.suppressed.size)
            if (!sameFailure) assertSame(retirement, caught.suppressed.single())
            assertEquals(1, failed.opened.size)
            assertEquals(1, failed.closedCursors)
            assertEquals(0, failed.activeCursors)
        }
    }

    @Test fun lookupWorkExhaustionRejectsTheWholeGroupUsingOnlyBoundedBatches() = runBlocking {
        val graph = CueGraph()
        graph.virtualRootCount = CueLimits.LOOKUP_CANDIDATES + 1
        expectCueIssue(CueIssue.LIMIT_EXCEEDED) {
            CuePathMatcher(graph).match(CueParser.parse(cueText().toByteArray()), graph.scope())
        }
        assertEquals(100, graph.largestRequestedBatch)
        assertEquals(1, graph.opened.size)
        assertEquals(1, graph.closedCursors)
        assertEquals(0, graph.activeCursors)
    }

    @Test fun originAndRelativeDirectoryDepthShareOneLimit() = runBlocking {
        val graph = CueGraph()
        val directories = mutableListOf(graph.root)
        for (index in 1 until CueLimits.DIRECTORY_DEPTH) {
            val parent = directories.last()
            val directory = graph.node("directory-$index", parent.objectId, "d$index", LocalNodeKind.DIRECTORY)
            graph.children[parent.objectId] = listOf(directory)
            directories += directory
        }
        graph.cue = graph.cue.copy(parentObjectId = directories.last().objectId)
        graph.children[directories.last().objectId] = listOf(graph.cue)
        val parsed = CueParser.parse(cueText("sub/song.flac").toByteArray())
        expectCueIssue(CueIssue.LIMIT_EXCEEDED) { CuePathMatcher(graph).match(parsed, graph.scope(directories)) }
        assertEquals(0, graph.activeCursors)
        assertEquals(graph.opened.size, graph.closedCursors)
    }

    @Test fun authorizationRevokedAfterLookupCannotProduceAnAcceptedMatch() = runBlocking {
        val graph = CueGraph()
        graph.children["root"] = listOf(graph.cue, graph.node("audio", "root", "album.flac"))
        graph.onObserve = { count -> if (count == 2) graph.currentGrant = graph.grant.copy(read = ObservedCapability.UNAVAILABLE) }
        expectCueIssue(CueIssue.AUTHORIZATION_REQUIRED) {
            CuePathMatcher(graph).match(CueParser.parse(cueText().toByteArray()), graph.scope())
        }
        assertEquals(2, graph.observations)
        assertEquals(0, graph.activeCursors)
    }

    @Test fun changedCueContentTokenInvalidatesTheOldOriginSnapshot() = runBlocking {
        val graph = CueGraph()
        val oldScope = graph.scope()
        graph.cue = graph.cue.copy(providerContentToken = "replacement-cue")
        graph.children["root"] = listOf(graph.cue, graph.node("audio", "root", "album.flac"))
        expectCueIssue(CueIssue.SOURCE_CHANGED) {
            CuePathMatcher(graph).match(CueParser.parse(cueText().toByteArray()), oldScope)
        }
        assertEquals(1, graph.closedCursors)
    }

    @Test fun providerFactsAreBoundedBeforeRetainingAnAudioReference() = runBlocking {
        val graph = CueGraph()
        val bounded = graph.node("audio", "root", "album.flac")
            .copy(providerContentToken = "x".repeat(CueLimits.FIELD_CHARACTERS))
        graph.children["root"] = listOf(graph.cue, bounded)
        val parsed = CueParser.parse(cueText().toByteArray())
        assertEquals(bounded, CuePathMatcher(graph).match(parsed, graph.scope()).files.single().candidate)
        graph.children["root"] = listOf(graph.cue, bounded.copy(providerContentToken = bounded.providerContentToken.orEmpty() + "x"))
        expectCueIssue(CueIssue.LIMIT_EXCEEDED) { CuePathMatcher(graph).match(parsed, graph.scope()) }
        assertEquals(0, graph.activeCursors)
    }
}
