package io.github.onlinemo.meloxwatch.data.cue

import io.github.onlinemo.meloxwatch.data.library.LocalContentValidation
import io.github.onlinemo.meloxwatch.data.local.LocalNodeKind
import io.github.onlinemo.meloxwatch.model.TrackKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CueGroupValidatorTest {
    @Test fun fullGroupUsesNextIndex01AndActualLastFileEndWithExplicitMetadataInheritance() = runBlocking {
        val fixture = fixture("""
            TITLE "Global Album"
            PERFORMER "Global Artist"
            FILE "one.flac" WAVE
              TITLE "File Album"
              PERFORMER "File Artist"
              TRACK 01 AUDIO
                TITLE "One"
                PERFORMER "Solo"
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                TITLE "Two"
                INDEX 00 00:59:00
                INDEX 01 01:00:01
            FILE "two.aiff" AIFF
              TRACK 03 AUDIO
                INDEX 01 00:00:10
        """.trimIndent())
        val facts = fixture.facts.map { if (it.fileIndex == 1) it.copy(durationUs = 45_000_000L) else it }
        val group = CueGroupValidator.prepare(fixture.parsed, fixture.document, fixture.matched, facts)
        assertEquals(3, group.segments.size)
        val first = group.segments[0]
        val second = group.segments[1]
        val third = group.segments[2]
        assertEquals(0L, first.bounds.start.frames)
        assertEquals(4501L, first.bounds.endFrameExclusive?.frames)
        assertEquals(60_013_333L, first.bounds.endUsExclusive)
        assertEquals(first.bounds.endUsExclusive, second.bounds.startUs)
        assertEquals(4425L, second.index00?.frames)
        assertEquals(90_000_000L, second.bounds.endUsExclusive)
        assertNull(second.bounds.endFrameExclusive)
        assertEquals(10L, third.bounds.start.frames)
        assertEquals(133_333L, third.bounds.startUs)
        assertEquals(45_000_000L, third.bounds.endUsExclusive)
        assertEquals("One", first.title)
        assertEquals("Solo", first.performer)
        assertEquals("File Album", first.album)
        assertEquals("File Artist", first.albumPerformer)
        assertEquals("File Artist", second.performer)
        assertNull(third.title)
        assertEquals("Global Album", third.album)
        assertEquals("Global Artist", third.performer)
        assertEquals(facts.map { it.descriptor.trackKey }, group.audioFiles.map { it.descriptor.trackKey })
        assertEquals(facts[0].descriptor, first.audio)
        assertNotEquals(first.audio.trackKey, first.identity.trackKey)
        assertEquals(1, group.boundaryVersion)
    }

    @Test fun completeGroupKeepsTheExactPhysicalEndRatherThanRoundingToACueFrame() = runBlocking {
        val fixture = fixture()
        val group = CueGroupValidator.prepare(fixture.parsed, fixture.document, fixture.matched,
            fixture.facts.map { it.copy(durationUs = 1_234_567L) })
        assertEquals(1_234_567L, group.segments.single().bounds.endUsExclusive)
        assertEquals(1_234_567L, group.segments.single().bounds.durationUs)
        assertNull(group.segments.single().bounds.endFrameExclusive)
    }

    @Test fun repeatedScanAndCueRelocationKeepKeysAcrossTitleAndContentRevisionChanges() = runBlocking {
        val original = fixture("TITLE \"Album\"\n" + cueText() + "\nTITLE \"Original\"")
        val changed = fixture("TITLE \"Album\"\n" + cueText() + "\nTITLE \"Renamed\"",
            cueObjectId = "relocated-cue", contentRevision = 8L)
        val first = CueGroupValidator.prepare(original.parsed, original.document, original.matched, original.facts)
        val second = CueGroupValidator.prepare(changed.parsed, changed.document, changed.matched, changed.facts)
        assertNotEquals(first.document.rawContentToken, second.document.rawContentToken)
        assertNotEquals(first.document.objectId, second.document.objectId)
        assertNotEquals(first.document.location.uri, second.document.location.uri)
        assertNotEquals(first.document.location.contentRevision, second.document.location.contentRevision)
        assertEquals(first.segments.single().identity.trackKey, second.segments.single().identity.trackKey)
        assertEquals("Original", first.segments.single().title)
        assertEquals("Renamed", second.segments.single().title)
    }

    @Test fun aliasesWithTheSameDurablePhysicalTrackOrEntityProofAreRejected() = runBlocking {
        val fixture = twoFileFixture()
        val aliased = fixture.facts.mapIndexed { index, facts ->
            if (index == 1) facts.copy(descriptor = facts.descriptor.copy(trackKey = fixture.facts[0].descriptor.trackKey)) else facts
        }
        expectCueIssue(CueIssue.DUPLICATE_REFERENCE) {
            CueGroupValidator.prepare(fixture.parsed, fixture.document, fixture.matched, aliased)
        }
        val matchingProof = fixture.matched.copy(files = fixture.matched.files.map {
            it.copy(candidate = it.candidate.copy(entityProof = "provider-entity:shared"))
        })
        expectCueIssue(CueIssue.DUPLICATE_REFERENCE) {
            CueGroupValidator.prepare(fixture.parsed, fixture.document, matchingProof, fixture.facts)
        }
    }

    @Test fun missingExtraOrRepeatedFileFactsCannotBecomeAPartialGroup() = runBlocking {
        val fixture = twoFileFixture()
        expectCueIssue(CueIssue.MISSING_AUDIO) {
            CueGroupValidator.prepare(fixture.parsed, fixture.document, fixture.matched, fixture.facts.take(1))
        }
        expectCueIssue(CueIssue.MISSING_AUDIO) {
            CueGroupValidator.prepare(fixture.parsed, fixture.document, fixture.matched,
                listOf(fixture.facts[0], fixture.facts[1].copy(fileIndex = 2)))
        }
        expectCueIssue(CueIssue.DUPLICATE_REFERENCE) {
            CueGroupValidator.prepare(fixture.parsed, fixture.document, fixture.matched, List(2) { fixture.facts[0] })
        }
        val duplicateMatches = fixture.matched.copy(files = List(2) { fixture.matched.files[0] })
        expectCueIssue(CueIssue.DUPLICATE_REFERENCE) {
            CueGroupValidator.prepare(fixture.parsed, fixture.document, duplicateMatches, fixture.facts)
        }
    }

    @Test fun individuallyUnverifiedAudioCannotBeAuthorizedByTheCueTextOrFileType() = runBlocking {
        val fixture = fixture()
        val unknown = fixture.facts.map { it.copy(descriptor = it.descriptor.copy(contentValidation = LocalContentValidation.UNKNOWN)) }
        expectCueIssue(CueIssue.UNVERIFIED_CONTENT) {
            CueGroupValidator.prepare(fixture.parsed, fixture.document, fixture.matched, unknown)
        }
        val online = fixture.facts.map { it.copy(descriptor = it.descriptor.copy(trackKey = TrackKey("online", "remote"))) }
        expectCueIssue(CueIssue.INVALID_AUDIO) {
            CueGroupValidator.prepare(fixture.parsed, fixture.document, fixture.matched, online)
        }
    }

    @Test fun changedCueHashMatchedTextAuthorizationEpochOrAudioTokenInvalidatesPreparation() = runBlocking {
        val fixture = fixture()
        expectCueIssue(CueIssue.SOURCE_CHANGED) {
            CueGroupValidator.prepare(fixture.parsed, fixture.document.copy(rawContentToken = "sha256:" + "0".repeat(64)),
                fixture.matched, fixture.facts)
        }
        expectCueIssue(CueIssue.SOURCE_CHANGED) {
            CueGroupValidator.prepare(fixture.parsed, fixture.document,
                fixture.matched.copy(rawContentToken = "sha256:" + "1".repeat(64)), fixture.facts)
        }
        expectCueIssue(CueIssue.SOURCE_CHANGED) {
            CueGroupValidator.prepare(fixture.parsed,
                fixture.document.copy(source = fixture.document.source.copy(authEpoch = fixture.document.source.authEpoch + 1)),
                fixture.matched, fixture.facts)
        }
        for (facts in listOf(
            fixture.facts.map { it.copy(descriptor = it.descriptor.copy(providerContentToken = "changed-audio")) },
            fixture.facts.map { it.copy(descriptor = it.descriptor.copy(source = it.descriptor.source.copy(authEpoch = 99L))) },
        )) {
            expectCueIssue(CueIssue.SOURCE_CHANGED) { CueGroupValidator.prepare(fixture.parsed, fixture.document, fixture.matched, facts) }
        }
    }

    @Test fun changedAudioObjectUriOrGrantCannotReuseAnEarlierVerifiedDescriptor() = runBlocking {
        val fixture = fixture()
        val first = fixture.facts.single()
        for (descriptor in listOf(
            first.descriptor.copy(objectId = "another-audio"),
            first.descriptor.copy(location = first.descriptor.location.copy(uri = "content://cue-test/tree/root/document/another-audio")),
            first.descriptor.copy(location = first.descriptor.location.copy(grantUri = "content://cue-test/tree/outside")),
        )) {
            expectCueIssue(CueIssue.SOURCE_CHANGED) {
                CueGroupValidator.prepare(fixture.parsed, fixture.document, fixture.matched, listOf(first.copy(descriptor = descriptor)))
            }
        }
    }

    @Test fun nonPositiveUnknownOrTooShortAudioEndCannotProduceEmptyOrOverlappingSegments() = runBlocking {
        val fixture = fixture(cueText().replace("00:00:00", "00:00:01"))
        for (duration in listOf(0L, -1L)) {
            expectCueIssue(CueIssue.INVALID_AUDIO) {
                CueGroupValidator.prepare(fixture.parsed, fixture.document, fixture.matched,
                    fixture.facts.map { it.copy(durationUs = duration) })
            }
        }
        for (duration in listOf(13_332L, 13_333L)) {
            expectCueIssue(CueIssue.INVALID_BOUNDARIES) {
                CueGroupValidator.prepare(fixture.parsed, fixture.document, fixture.matched,
                    fixture.facts.map { it.copy(durationUs = duration) })
            }
        }
        val beyond = this@CueGroupValidatorTest.fixture(cueText() + "\nTRACK 02 AUDIO\nINDEX 01 00:01:00")
        expectCueIssue(CueIssue.INVALID_BOUNDARIES) {
            CueGroupValidator.prepare(beyond.parsed, beyond.document, beyond.matched,
                beyond.facts.map { it.copy(durationUs = 999_999L) })
        }
    }

    @Test fun largeValidDurationUsesCheckedFrameConversionWithoutMultiplicationOverflow() = runBlocking {
        val fixture = fixture(cueText().replace("00:00:00", "00:01:01"))
        val group = CueGroupValidator.prepare(fixture.parsed, fixture.document, fixture.matched,
            fixture.facts.map { it.copy(durationUs = Long.MAX_VALUE) })
        assertEquals(76L, group.segments.single().bounds.start.frames)
        assertEquals(1_013_333L, group.segments.single().bounds.startUs)
        assertEquals(Long.MAX_VALUE - 1_013_333L, group.segments.single().bounds.durationUs)
    }

    @Test fun aLateCancellationDoesNotReturnAlreadyPreparedSegmentsOrMutateInputs() = runBlocking {
        val fixture = twoFileFixture()
        var healthyCheckpoints = 0
        val healthy = CueGroupValidator.prepare(fixture.parsed, fixture.document, fixture.matched, fixture.facts,
            checkCancelled = { healthyCheckpoints++ })
        assertEquals(2, healthy.segments.size)
        val cancellation = CancellationException("cancelled")
        var currentCheckpoint = 0
        var returned = false
        try {
            CueGroupValidator.prepare(fixture.parsed, fixture.document, fixture.matched, fixture.facts, checkCancelled = {
                currentCheckpoint++
                if (currentCheckpoint == healthyCheckpoints) throw cancellation
            })
            returned = true
        } catch (error: CancellationException) { assertTrue(error === cancellation) }
        assertFalse(returned)
        assertEquals(healthyCheckpoints, currentCheckpoint)
        assertEquals(2, fixture.facts.size)
        assertEquals(2, fixture.parsed.tracks.size)
    }

    @Test fun groupBoundaryGuardRejectsAlteredParsedTimesBeforeCreatingLogicalTracks() = runBlocking {
        val fixture = fixture(cueText().replace("00:00:00", "00:10:00") +
            "\nTRACK 02 AUDIO\nINDEX 00 00:19:00\nINDEX 01 00:20:00")
        val altered = fixture.parsed.copy(tracks = listOf(fixture.parsed.tracks[0],
            fixture.parsed.tracks[1].copy(index00 = CueFrameTime(700L))))
        expectCueIssue(CueIssue.INVALID_BOUNDARIES) {
            CueGroupValidator.prepare(altered, fixture.document, fixture.matched, fixture.facts)
        }
    }

    private suspend fun twoFileFixture(): Fixture = fixture(cueText("one.flac") +
        "\nFILE \"two.flac\" WAVE\nTRACK 02 AUDIO\nINDEX 01 00:00:00")

    private suspend fun fixture(text: String = cueText(), cueObjectId: String = "cue-object", contentRevision: Long = 4L): Fixture {
        val graph = CueGraph()
        graph.cue = graph.node(cueObjectId, "root", "album.cue", LocalNodeKind.OTHER)
        val parsed = CueParser.parse(text.toByteArray())
        val audio = parsed.files.map { graph.node("audio-${it.index}", "root", it.path.components.single()) }
        graph.children["root"] = listOf(graph.cue) + audio
        val matched = CuePathMatcher(graph).match(parsed, graph.scope())
        return Fixture(parsed, matched, cueDocument(parsed, matched, contentRevision = contentRevision), cueFacts(matched))
    }

    private data class Fixture(val parsed: ParsedCue, val matched: CueMatchedFiles,
        val document: CueDocumentSnapshot, val facts: List<CueAudioFacts>)
}
