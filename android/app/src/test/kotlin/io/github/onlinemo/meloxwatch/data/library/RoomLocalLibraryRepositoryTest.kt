package io.github.onlinemo.meloxwatch.data.library

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.database.withTestReaderSql
import io.github.onlinemo.meloxwatch.data.database.withTestWriterSql
import io.github.onlinemo.meloxwatch.data.local.LocalGrantObservation
import io.github.onlinemo.meloxwatch.data.local.LocalNodeKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.queue.QueuePageSource
import io.github.onlinemo.meloxwatch.data.queue.QueueReplacementRequest
import io.github.onlinemo.meloxwatch.data.queue.QueueStoreException
import io.github.onlinemo.meloxwatch.data.queue.QueueStoreIssue
import io.github.onlinemo.meloxwatch.data.queue.RoomQueueStore
import io.github.onlinemo.meloxwatch.data.scan.MetadataOutcome
import io.github.onlinemo.meloxwatch.data.scan.ScanCounters
import io.github.onlinemo.meloxwatch.data.scan.ScanCoverage
import io.github.onlinemo.meloxwatch.data.scan.ScanMode
import io.github.onlinemo.meloxwatch.data.scan.ScanPhase
import io.github.onlinemo.meloxwatch.data.scan.ScanToken
import io.github.onlinemo.meloxwatch.model.FrozenQueueView
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Production Room queries over native SQLite; these fixtures do not claim provider/device acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RoomLocalLibraryRepositoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var file: File
    private lateinit var database: MeloXDatabase
    private lateinit var library: RoomLocalLibraryRepository
    private var elapsed = 1000L
    private var epoch = 1_790_000_000_000L

    @Before fun openDatabase() = runBlocking {
        file = File(temporaryFolder.root, "library.db")
        database = open()
        library = repository()
        assertEquals(0L, library.initialize().totalTracks)
    }

    @After fun closeDatabase() { database.close() }

    @Test(timeout = 30000L)
    fun providerProofSharesAliasesWhileSameNamesAndHashesKeepDistinctObjects() = runBlocking {
        val first = source("first")
        val second = source("second")
        val sharedA = audio(first, "a", "Same.mp3", proof = "document:shared", content = "native:shared:1")
        val sharedB = audio(second, "b", "Same.mp3", proof = "document:shared", content = "native:shared:1")
        append(first, listOf(sharedA, audio(first, "other", "Same.mp3", content = "native:shared:1")))
        val added = tracks().associateBy { it.location?.locationId }
        assertEquals(2, added.size)
        val original = tracks(LibraryQuery(sourceId = first.spec.sourceId)).first { it.aliasId == database.libraryDao().alias(first.spec.sourceId, "a")?.aliasId }
        val count = append(second, listOf(sharedB))
        assertEquals(0L, count)
        assertEquals(2L, library.overview().visibleTracks)
        val viaSecond = tracks(LibraryQuery(sourceId = second.spec.sourceId)).single()
        assertEquals(original.trackKey, viaSecond.trackKey)
        assertEquals(original.firstAddedSequence, viaSecond.firstAddedSequence)
        assertEquals(original.location?.locationId, viaSecond.location?.locationId)

        library.setSourceIssue(first.spec.sourceId, first.authEpoch, SourceReadIssue.PERMISSION_REVOKED)
        assertEquals(LibraryAvailability.AVAILABLE, tracks().first { it.trackKey == original.trackKey }.availability)
        assertEquals(LibraryAvailability.NEEDS_AUTHORIZATION, tracks().first { it.trackKey != original.trackKey }.availability)
        reopen()
        assertEquals(original.trackKey, tracks(LibraryQuery(sourceId = second.spec.sourceId)).single().trackKey)
    }

    @Test(timeout = 30000L)
    fun wrongReauthorizationAndProviderIncarnationPreserveTheOriginalMapping() = runBlocking {
        val source = source("reauthorize")
        val candidate = audio(source, "a", "Original.mp3", proof = "document:a")
        append(source, listOf(candidate))
        val before = tracks().single()
        val oldSource = library.source(source.spec.sourceId)
        expectIssue(LibraryIssue.SOURCE_CHANGED) {
            library.reauthorizeSource(source.spec.sourceId, source.authEpoch,
                source.spec.copy(uri = "content://library-fixture/tree/wrong", rootObjectId = "wrong"), grant())
        }
        expectIssue(LibraryIssue.SOURCE_CHANGED) {
            library.reauthorizeSource(source.spec.sourceId, source.authEpoch, source.spec.copy(providerIncarnation = "reset"), grant())
        }
        assertEquals(oldSource, library.source(source.spec.sourceId))
        assertEquals(before, tracks().single())
        val revoked = library.setSourceIssue(source.spec.sourceId, source.authEpoch, SourceReadIssue.PERMISSION_REVOKED)
        val refreshed = library.reauthorizeSource(source.spec.sourceId, revoked.authEpoch, source.spec, grant())
        assertTrue(refreshed.authEpoch > source.authEpoch)
        assertEquals(before.trackKey, tracks().single().trackKey)
        assertEquals(before.firstAddedEpochMs, tracks().single().firstAddedEpochMs)
        assertEquals("reauthorize", refreshed.displayName)
    }

    @Test(timeout = 30000L)
    fun existencePrecedesBadMetadataAndAbsenceBecomesVisibleOnlyAtThePointerCommit() = runBlocking {
        val source = source("absence")
        val one = audio(source, "one", "One.mp3")
        val two = audio(source, "two", "Two.mp3")
        append(source, listOf(one, two), mapOf("one" to LocalAudioMetadata(title = "Good title")))
        val before = tracks().associateBy { it.filename }
        val frozen = library.openView()
        val token = begin(source, ScanMode.FULL)
        library.commitSeen(token, listOf(root(source), one))
        library.commitMetadata(token, listOf(MetadataOutcome("one", issue = SourceReadIssue.METADATA_UNREADABLE)))
        finishDirectories(token)
        val coverage = coverage()
        library.setCoverage(token, coverage)
        val staged = library.stageAbsence(token)
        assertTrue(staged.complete)
        assertEquals(1L, staged.stagedCount)
        assertTrue(tracks().all { it.availability == LibraryAvailability.AVAILABLE })
        val final = library.finishScan(token, ScanCounters(seen = Long.MAX_VALUE), coverage)
        assertEquals(2L, final.counters.seen)
        assertEquals(1L, final.counters.failed)
        val current = tracks().associateBy { it.filename }
        assertEquals(LibraryAvailability.MISSING, current.getValue("Two.mp3").availability)
        assertEquals("Good title", current.getValue("One.mp3").title)
        assertEquals(SourceReadIssue.METADATA_UNREADABLE.name, current.getValue("One.mp3").metadataIssue)
        assertEquals(before.getValue("Two.mp3").trackKey, current.getValue("Two.mp3").trackKey)
        assertTrue(library.page(frozen).tracks.all { it.availability == LibraryAvailability.AVAILABLE })
        library.closeView(frozen.viewId)
    }

    @Test(timeout = 30000L)
    fun incrementalPartialCancelledAndRuleInvalidatedScansCannotDeclareUnseenAliasesMissing() = runBlocking {
        val source = source("fences")
        val one = audio(source, "one", "One.mp3")
        val two = audio(source, "two", "Two.mp3")
        append(source, listOf(one, two))
        append(source, listOf(one), mode = ScanMode.INCREMENTAL)
        assertTrue(tracks().all { it.availability == LibraryAvailability.AVAILABLE })

        val partial = begin(source, ScanMode.FULL)
        library.commitSeen(partial, listOf(root(source), one))
        finishDirectories(partial)
        val incomplete = coverage().copy(enumerationComplete = false)
        library.setCoverage(partial, incomplete)
        expectIssue(LibraryIssue.INCOMPLETE) { library.stageAbsence(partial) }
        library.finishScan(partial, ScanCounters(), incomplete)
        assertTrue(tracks().all { it.availability == LibraryAvailability.AVAILABLE })

        val cancelled = begin(source, ScanMode.FULL)
        library.commitSeen(cancelled, listOf(root(source), one))
        finishDirectories(cancelled)
        library.setCoverage(cancelled, coverage())
        library.stageAbsence(cancelled)
        library.setScanState(cancelled, ScanPhase.STOPPING)
        library.setScanState(cancelled, ScanPhase.CANCELLED)
        assertNull(library.source(source.spec.sourceId)?.activeScanId)
        expectIssue(LibraryIssue.SOURCE_CHANGED) { library.finishScan(cancelled, ScanCounters(), coverage()) }
        assertTrue(tracks().all { it.availability == LibraryAvailability.AVAILABLE })

        val raced = begin(source, ScanMode.FULL)
        library.commitSeen(raced, listOf(root(source), one))
        finishDirectories(raced)
        library.setCoverage(raced, coverage())
        library.stageAbsence(raced)
        library.setShortAudioFilter(library.rules().revision, true)
        expectIssue(LibraryIssue.SOURCE_CHANGED) { library.finishScan(raced, ScanCounters(), coverage()) }
        expectIssue(LibraryIssue.SOURCE_CHANGED) { begin(source, ScanMode.FULL) }
        assertEquals(ScanPhase.FAILED, library.setScanState(raced, ScanPhase.FAILED, SourceReadIssue.SOURCE_CHANGED).phase)
        assertEquals(ScanPhase.FAILED, library.setScanState(raced, ScanPhase.STOPPING).phase)
        assertTrue(tracks().all { it.availability == LibraryAvailability.AVAILABLE })
    }

    @Test(timeout = 30000L)
    fun frozenForwardReversePagesRejectForeignForgedAndExpiredCursors() = runBlocking {
        val source = source("pages")
        val candidates = (0 until 205).map { audio(source, "id-$it", "Song${it.toString().padStart(3, '0')}.mp3") }
        append(source, candidates)
        val view = library.openView()
        val first = library.page(view, limit = 5)
        val second = library.page(view, first.nextCursor, 5)
        assertEquals(first.tracks, library.previousPage(view, second.previousCursor ?: throw AssertionError("previous anchor"), 5).tracks)
        expectIssue(LibraryIssue.INVALID_INPUT) { library.page(view, first.nextCursor?.copy(sortKey = "forged"), 5) }
        val other = library.openView(LibraryQuery(search = "Song"))
        expectIssue(LibraryIssue.INVALID_INPUT) { library.page(other, first.nextCursor, 5) }

        append(source, listOf(candidates.first()), mapOf(candidates.first().objectId to LocalAudioMetadata(title = "ZZZ new title")))
        assertEquals("Song000.mp3", library.page(view).tracks.first().title)
        assertEquals(205L, view.totalCount)
        assertEquals(205L, library.page(view, library.page(view).nextCursor).view.totalCount)
        library.closeView(other.viewId)
        val fresh = library.openView()
        assertEquals("ZZZ new title", library.locate(fresh, first.tracks.first().trackKey)?.track?.title)
        library.closeView(fresh.viewId)
        elapsed += LibraryLimits.VIEW_LEASE_MS + 1L
        expectIssue(LibraryIssue.VIEW_EXPIRED) { library.page(view) }
        library.closeView(view.viewId)
    }

    @Test(timeout = 30000L)
    fun literalSearchNaturalNumbersRawArtistsAndUnknownDurationsUseTheDeclaredRules() = runBlocking {
        val source = source("keys")
        val literal = audio(source, "literal", "A%_\\take2.mp3")
        val hugeNine = "song${"9".repeat(80)}.mp3"
        val hugeTen = "song1${"0".repeat(80)}.mp3"
        val candidates = listOf(literal, audio(source, "decoy", "A12take2.mp3"), audio(source, "2", "song2.mp3"),
            audio(source, "10", "song10.mp3"), audio(source, "big9", hugeNine), audio(source, "big10", hugeTen))
        append(source, candidates, mapOf("literal" to LocalAudioMetadata(artist = "AC/DC", durationMs = 59_999L),
            "2" to LocalAudioMetadata(artists = listOf("Artist A", "Artist B"), durationMs = 60_000L)))
        for (query in listOf("%", "_", "\\")) assertEquals(listOf(literal.displayName), tracks(LibraryQuery(search = query)).map { it.filename })
        val sorted = tracks(LibraryQuery(search = "song"), LibrarySort(LibrarySortField.FILENAME_NATURAL))
        assertEquals(listOf("song2.mp3", "song10.mp3", hugeNine, hugeTen), sorted.map { it.filename })
        val view = library.openView()
        val artists = library.groups(view, LibraryGroupKind.ARTIST).groups.mapNotNull { it.title }
        assertTrue("AC/DC" in artists)
        assertFalse("AC" in artists || "DC" in artists)
        assertTrue("Artist A" in artists && "Artist B" in artists)
        assertEquals(emptyList<String>(), library.page(view).tracks.first { it.filename == literal.displayName }.metadata.artists)
        library.closeView(view.viewId)
        for (descending in listOf(false, true)) {
            val duration = tracks(sort = LibrarySort(LibrarySortField.DURATION, descending))
            assertTrue(duration.take(2).all { it.metadata.durationMs != null })
            assertTrue(duration.drop(2).all { it.metadata.durationMs == null })
        }
        val before = tracks().associate { it.trackKey to it.firstAddedSequence }
        library.setShortAudioFilter(library.rules().revision, true)
        assertEquals(5, tracks().size)
        assertTrue(tracks().any { it.metadata.durationMs == 60_000L })
        library.setShortAudioFilter(library.rules().revision, false)
        assertEquals(before, tracks().associate { it.trackKey to it.firstAddedSequence })
    }

    @Test(timeout = 30000L)
    fun exclusionsFollowProvenAncestryAndRemainBoundedWithoutHidingAnotherSource() = runBlocking {
        val source = source("folders")
        val nested = source("nested")
        val music = directory(source, "music", "Music")
        val backup = directory(source, "backup", "MusicBackup")
        val one = audio(source, "one", "One.mp3", parent = "music", proof = "document:one", content = "native:one")
        val two = audio(source, "two", "Two.mp3", parent = "backup")
        append(source, listOf(music, backup, one, two))
        append(nested, listOf(audio(nested, "nested-one", "One.mp3", proof = "document:one", content = "native:one")))
        val node = database.libraryDao().node(source.spec.sourceId, "music") ?: throw AssertionError("real directory")
        val rules = library.addExclusion(library.rules().revision, source.spec.sourceId, node.nodeId, true)
        assertEquals(listOf("Two.mp3"), tracks(LibraryQuery(sourceId = source.spec.sourceId)).map { it.filename })
        assertEquals(2, tracks().size)
        assertEquals(1, tracks(LibraryQuery(sourceId = nested.spec.sourceId)).size)
        assertEquals(rules.revision, library.addExclusion(rules.revision, source.spec.sourceId, node.nodeId, true).revision)
        assertEquals(1, library.exclusionPage().exclusions.size)
        assertEquals("Music", library.exclusionPage().exclusions.single().displayName)
        assertEquals("folders", library.exclusionPage().exclusions.single().sourceDisplayName)
        val hidden = tracks(LibraryQuery(sourceId = source.spec.sourceId, includeHidden = true))
        assertEquals(2, hidden.size)
        val extras = (0 until 105).map { audio(source, "rule-$it", "Rule$it.mp3") }
        append(source, extras)
        for (candidate in extras) {
            val item = database.libraryDao().node(source.spec.sourceId, candidate.objectId) ?: throw AssertionError("file node")
            library.addExclusion(library.rules().revision, source.spec.sourceId, item.nodeId, false)
        }
        val first = library.exclusionPage(limit = 100)
        val second = library.exclusionPage(first.nextRuleId, 100)
        assertEquals(100, first.exclusions.size)
        assertEquals(6, second.exclusions.size)
        assertNull(second.nextRuleId)
        assertEquals(106, (first.exclusions + second.exclusions).map { it.ruleId }.distinct().size)
        assertTrue((first.exclusions + second.exclusions).all { it.displayName != null && it.sourceDisplayName == "folders" })
        expectIssue(LibraryIssue.INVALID_INPUT) { library.exclusionPage(limit = 101) }
        val remove = first.exclusions.first()
        library.removeExclusion(library.rules().revision, remove.ruleId)
        assertNull(database.libraryDao().rule(remove.ruleId))
    }

    @Test(timeout = 30000L)
    fun exclusionNamesFollowRealObservationsAndSurviveUnknownNamesRevocationAndReopen() = runBlocking {
        val source = source("opaque-name-id")
        assertNull(source.displayName)
        val root = root(source).copy(displayName = "真实根目录")
        val folder = directory(source, "opaque-folder-object", "真实 Music")
        val unknown = audio(source, "opaque-audio-object", "")
        val initial = begin(source)
        library.commitSeen(initial, listOf(root, folder, unknown))
        finish(initial)
        val folderNode = database.libraryDao().node(source.spec.sourceId, folder.objectId) ?: throw AssertionError("folder")
        val unknownNode = database.libraryDao().node(source.spec.sourceId, unknown.objectId) ?: throw AssertionError("audio")
        library.addExclusion(library.rules().revision, source.spec.sourceId, folderNode.nodeId, true)
        library.addExclusion(library.rules().revision, source.spec.sourceId, unknownNode.nodeId, false)
        val originalRule = library.exclusionPage().exclusions.first { it.nodeId == folderNode.nodeId }
        assertEquals("真实 Music", originalRule.displayName)
        assertEquals("真实根目录", originalRule.sourceDisplayName)
        assertNull(library.exclusionPage().exclusions.first { it.nodeId == unknownNode.nodeId }.displayName)

        val missingNames = begin(source)
        library.commitSeen(missingNames, listOf(root.copy(displayName = ""), folder.copy(displayName = " "), unknown))
        finish(missingNames)
        assertEquals("", database.libraryDao().currentNode(rootNodeId(source))?.name)
        assertEquals(" ", database.libraryDao().currentNode(folderNode.nodeId)?.name)
        assertEquals("真实根目录", library.source(source.spec.sourceId)?.displayName)
        assertEquals(originalRule, library.exclusionPage().exclusions.first { it.nodeId == folderNode.nodeId })
        val view = library.openView()
        library.closeView(view.viewId)
        assertEquals(0L, database.withTestReaderSql {
            scalarLong("SELECT COUNT(*) FROM local_node_versions WHERE validTo IS NOT NULL AND name IS NOT NULL")
        })

        val rename = begin(source)
        library.commitSeen(rename, listOf(root.copy(displayName = "新根目录"), folder.copy(displayName = "已更名 Music")))
        finish(rename)
        library.setSourceIssue(source.spec.sourceId, source.authEpoch, SourceReadIssue.PERMISSION_REVOKED)
        reopen()
        val renamed = library.exclusionPage().exclusions.first { it.nodeId == folderNode.nodeId }
        assertEquals(originalRule.ruleId, renamed.ruleId)
        assertEquals("已更名 Music", renamed.displayName)
        assertEquals("新根目录", renamed.sourceDisplayName)
        assertNull(library.exclusionPage().exclusions.first { it.nodeId == unknownNode.nodeId }.displayName)
    }

    @Test(timeout = 30000L)
    fun sourcePagesUseTheirStoredKeysetWithoutAnImplicitHundredSourceLimit() = runBlocking {
        for (number in 0 until 105) source("source-${number.toString().padStart(3, '0')}")
        val first = library.sourcesPage(limit = 100)
        val second = library.sourcesPage(first.nextSourceId, 100)
        assertEquals(100, first.sources.size)
        assertEquals(5, second.sources.size)
        assertNull(second.nextSourceId)
        assertEquals(105, (first.sources + second.sources).map { it.spec.sourceId }.distinct().size)
        assertTrue((first.sources + second.sources).all { it.displayName == null })
        assertEquals("source-100", second.sources.first().spec.sourceId)
        expectIssue(LibraryIssue.INVALID_INPUT) { library.sourcesPage(limit = 101) }
    }

    @Test(timeout = 30000L)
    fun hundredMetadataOutcomesCommitOneRevisionAndRetryWithoutChangingTheFrozenView() = runBlocking {
        val source = source("metadata-batch")
        val candidates = (0 until 100).map { audio(source, "metadata-$it", "File$it.mp3") }
        val token = begin(source)
        library.commitSeen(token, listOf(root(source)))
        library.commitSeen(token, candidates)
        val frozen = library.openView()
        val beforeRevision = library.overview().revision
        val outcomes = candidates.mapIndexed { index, candidate ->
            MetadataOutcome(candidate.objectId, LocalAudioMetadata(title = "Title$index", durationMs = 90_000L))
        }
        val commit = library.commitMetadata(token, outcomes)
        assertEquals(beforeRevision + 1L, commit.revision)
        assertEquals(100L, commit.processed)
        assertEquals(0L, commit.failed)
        assertEquals(100L, library.latestScan(source.spec.sourceId)?.counters?.processed)
        assertTrue(library.page(frozen).tracks.all { it.title == it.filename && it.metadata.durationMs == null })
        val fresh = library.openView()
        assertTrue(library.page(fresh).tracks.all { it.title.startsWith("Title") && it.metadata.durationMs == 90_000L })
        library.closeView(fresh.viewId)
        val replay = library.commitMetadata(token, outcomes)
        assertEquals(commit.revision, replay.revision)
        assertEquals(0L, replay.processed)
        assertEquals(100L, library.latestScan(source.spec.sourceId)?.counters?.processed)
        expectIssue(LibraryIssue.CONFLICT) {
            library.commitMetadata(token, listOf(outcomes.first().copy(metadata = LocalAudioMetadata(title = "Conflict"))))
        }
        assertEquals(commit.revision, library.overview().revision)
        library.closeView(frozen.viewId)
        finish(token)
    }

    @Test(timeout = 30000L)
    fun metadataBatchFailureRollsBackTracksContentProofRevisionAndAcknowledgementsTogether() = runBlocking {
        val source = source("metadata-rollback")
        val one = audio(source, "one", "One.mp3", content = null)
        val two = audio(source, "two", "Two.mp3", content = null)
        val token = begin(source)
        library.commitSeen(token, listOf(root(source), one, two))
        val before = tracks().associateBy { it.filename }
        val beforeRevision = library.overview().revision
        database.withTestWriterSql {
            execute("""
                CREATE TRIGGER fail_metadata BEFORE INSERT ON local_track_versions
                WHEN NEW.displayTitle = 'refused'
                BEGIN SELECT RAISE(ABORT, 'injected metadata failure'); END
            """.trimIndent())
        }
        val hash = "sha256:${"a".repeat(64)}"
        val outcomes = listOf(MetadataOutcome("one", LocalAudioMetadata(title = "Updated"), verifiedContentToken = hash),
            MetadataOutcome("two", LocalAudioMetadata(title = "refused"), verifiedContentToken = hash))
        try {
            expectIssue(LibraryIssue.INVALID_INPUT) { library.commitMetadata(token, outcomes) }
            assertEquals(beforeRevision, library.overview().revision)
            assertEquals(before, tracks().associateBy { it.filename })
            assertEquals(0L, library.latestScan(source.spec.sourceId)?.counters?.processed)
            for (candidate in listOf(one, two)) {
                val seen = database.libraryDao().seen(token.scanId, candidate.objectId) ?: throw AssertionError("seen")
                assertFalse(seen.metadataProcessed)
                assertNull(seen.metadataPayload)
                val key = before.getValue(candidate.displayName).trackKey
                assertNull(library.resolveLocalRead(key).verifiedContentToken)
            }
        } finally { database.withTestWriterSql { execute("DROP TRIGGER fail_metadata") } }
        val retry = library.commitMetadata(token, outcomes)
        assertEquals(beforeRevision + 1L, retry.revision)
        assertEquals(2L, retry.processed)
        assertEquals(2L, library.latestScan(source.spec.sourceId)?.counters?.processed)
        assertEquals(hash, library.resolveLocalRead(before.getValue("One.mp3").trackKey).verifiedContentToken)
        finish(token)
    }

    @Test(timeout = 30000L)
    fun verifiedContentIsIndependentAndAnUnknownRescanNeverPretendsToBeVerified() = runBlocking {
        val source = source("content")
        val weak = audio(source, "weak", "Weak.mp3", content = null)
        val firstHash = "sha256:${"1".repeat(64)}"
        val nextHash = "sha256:${"2".repeat(64)}"
        val token = begin(source)
        library.commitSeen(token, listOf(root(source), weak))
        val key = tracks().single().trackKey
        val original = library.resolveLocalRead(key)
        assertNull(original.providerContentToken)
        assertNull(original.verifiedContentToken)
        library.commitMetadata(token, listOf(MetadataOutcome("weak", LocalAudioMetadata(title = "Original"), verifiedContentToken = firstHash)))
        val verified = library.resolveLocalRead(key)
        assertEquals(firstHash, verified.verifiedContentToken)
        assertNull(verified.providerContentToken)
        assertEquals(LocalContentValidation.UNKNOWN, verified.contentValidation)
        assertEquals(original.location.contentRevision, verified.location.contentRevision)
        finish(token)
        val second = begin(source)
        library.commitSeen(second, listOf(root(source), weak))
        val unknown = library.resolveLocalRead(key)
        assertNull(unknown.verifiedContentToken)
        assertEquals(verified.location.contentRevision, unknown.location.contentRevision)
        library.commitMetadata(second, listOf(MetadataOutcome("weak", LocalAudioMetadata(title = "Original"), verifiedContentToken = firstHash)))
        val restored = library.resolveLocalRead(key, verified.location.locationId, verified.location.contentRevision)
        assertEquals(firstHash, restored.verifiedContentToken)
        assertEquals(verified.location.contentRevision, restored.location.contentRevision)
        finish(second)

        val third = begin(source)
        library.commitSeen(third, listOf(root(source), weak))
        library.commitMetadata(third, listOf(MetadataOutcome("weak", LocalAudioMetadata(title = "Changed"), verifiedContentToken = nextHash)))
        val changed = library.resolveLocalRead(key)
        assertTrue(changed.location.contentRevision > unknown.location.contentRevision)
        assertEquals(key, changed.trackKey)
        assertEquals(nextHash, changed.verifiedContentToken)
        expectIssue(LibraryIssue.SOURCE_CHANGED) { library.resolveLocalRead(key, changed.location.locationId, verified.location.contentRevision) }
        finish(third)
        reopen()
        assertEquals(nextHash, library.resolveLocalRead(key).verifiedContentToken)
    }

    @Test(timeout = 30000L)
    fun directoryFrontierDeduplicatesCyclesAndCancelledOwnersReleaseTheirLease() = runBlocking {
        val source = source("frontier")
        val token = begin(source)
        val child = directory(source, "child", "Child")
        library.commitSeen(token, listOf(root(source), child, child))
        assertEquals(2L, library.latestScan(source.spec.sourceId)?.counters?.seen)
        val first = library.nextDirectory(token) ?: throw AssertionError("frontier")
        library.completeDirectory(token, first.objectId, true)
        val second = library.nextDirectory(token) ?: throw AssertionError("frontier")
        assertNotEquals(first.objectId, second.objectId)
        library.completeDirectory(token, second.objectId, true)
        assertNull(library.nextDirectory(token))
        library.setScanState(token, ScanPhase.CANCELLED)
        val cycle = begin(source)
        expectIssue(LibraryIssue.SOURCE_CHANGED) { library.commitSeen(cycle, listOf(root(source).copy(parentObjectId = "child"))) }
        library.setScanState(cycle, ScanPhase.CANCELLED)

        val views = (0 until LibraryLimits.UI_VIEWS).map { library.openView() }
        expectIssue(LibraryIssue.BUSY) { library.openView() }
        val owner = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            library.closeView(views.first().viewId)
        }
        owner.join()
        assertNull(database.libraryDao().view(views.first().viewId))
        val replacement = library.openView()
        library.closeView(replacement.viewId)
        for (view in views.drop(1)) library.closeView(view.viewId)
        assertEquals(0, database.libraryDao().views().size)
    }

    @Test(timeout = 180000L)
    fun productionLibraryProducerKeeps10000And10001RowsAndNeverPublishesAnExpiredReplacement() = runBlocking {
        val source = source("full")
        val queue = RoomQueueStore(database)
        measureFullLibraryStage("initialize-queue", 0L) { queue.initialize() }
        val initial = (0 until 10000).map { audio(source, "item-$it", "Track${it.toString().padStart(5, '0')}.mp3") }
        measureFullLibraryStage("seed-library", 10000L) { append(source, initial) }
        var lastSnapshot = queue.snapshot()
        for (count in listOf(10000L, 10001L)) {
            if (count == 10001L) measureFullLibraryStage("seed-extra-row", 1L) {
                append(source, listOf(audio(source, "item-10000", "Track10000.mp3")))
            }
            val producer = measureFullLibraryStage("freeze-library-view", count) {
                val view = library.openView(sort = LibrarySort(LibrarySortField.FILENAME_NATURAL))
                val producer = library.openQueueView(view.viewId)
                library.closeView(view.viewId)
                producer
            }
            val request = QueueReplacementRequest("library-$count", lastSnapshot?.generationId, lastSnapshot?.queueRevision ?: 0L,
                FrozenQueueView(producer.viewId, producer.revision, producer.totalCount))
            val produced = library.queueSource(producer)
            var largestPage = 0
            var sourcePageAttempts = 0L
            var sourcePageNanos = 0L
            val result = try {
                measureFullLibraryStage("replace-queue", count) {
                    queue.replace(request, QueuePageSource { offset, limit ->
                        val started = System.nanoTime()
                        try {
                            produced.read(offset, limit).also { largestPage = maxOf(largestPage, it.entries.size) }
                        } finally {
                            sourcePageAttempts += 1L
                            sourcePageNanos += System.nanoTime() - started
                        }
                    })
                }.snapshot
            } finally {
                println("LIBRARY_FULL_LIST_STAGE stage=source-pages state=summary count=$sourcePageAttempts elapsedMs=${sourcePageNanos / 1_000_000L}")
            }
            measureFullLibraryStage("verify-complete-queue", count) {
                assertEquals(count, result.entryCount)
                assertEquals(100, largestPage)
                val end = queue.page(result.generationId, count - 1L).single()
                assertEquals(count - 1L, end.originalOrder)
                assertEquals(count - 1L, end.playbackOrder)
                assertEquals("Track${(count - 1L).toString().padStart(5, '0')}.mp3", end.displayName)
                assertEquals(3, queue.window(result.generationId, queue.page(result.generationId, 5000L, 1).single().queueEntryId).entries.size)
            }
            lastSnapshot = result
            measureFullLibraryStage("close-producer-view", count) { library.closeView(producer.viewId) }
        }
        val producer = measureFullLibraryStage("freeze-expiring-view", 10001L) {
            val view = library.openView()
            val producer = library.openQueueView(view.viewId)
            library.closeView(view.viewId)
            producer
        }
        val sourcePages = library.queueSource(producer)
        val old = lastSnapshot ?: throw AssertionError("complete queue")
        measureFullLibraryStage("expired-replacement", 10001L) {
            try {
                queue.replace(QueueReplacementRequest("expired-library", old.generationId, old.queueRevision,
                    FrozenQueueView(producer.viewId, producer.revision, producer.totalCount)), QueuePageSource { offset, limit ->
                    if (offset > 0L) elapsed += LibraryLimits.VIEW_LEASE_MS + 1L
                    sourcePages.read(offset, limit)
                })
                throw AssertionError("expired source must abort")
            } catch (error: QueueStoreException) { assertEquals(QueueStoreIssue.SOURCE_CHANGED, error.issue) }
        }
        measureFullLibraryStage("verify-original-queue", 10001L) {
            assertEquals(old, queue.snapshot())
            assertEquals(10001L, database.queueDao().orderStats(old.generationId).entryCount)
        }
        measureFullLibraryStage("close-expired-view", 10001L) { library.closeView(producer.viewId) }
        measureFullLibraryStage("reopen-original-queue", 10001L) {
            reopen()
            assertEquals(old, RoomQueueStore(database).initialize())
        }
    }

    /** Monotonic fixture phases separate scanning, queue reads and reclamation without source data. */
    private suspend fun <T> measureFullLibraryStage(stage: String, count: Long, action: suspend () -> T): T {
        val started = System.nanoTime()
        var completed = false
        println("LIBRARY_FULL_LIST_STAGE stage=$stage state=started count=$count")
        try {
            return action().also { completed = true }
        } finally {
            val state = if (completed) "completed" else "failed"
            val elapsedMs = (System.nanoTime() - started) / 1_000_000L
            println("LIBRARY_FULL_LIST_STAGE stage=$stage state=$state count=$count elapsedMs=$elapsedMs")
        }
    }

    private fun open(): MeloXDatabase = Room.databaseBuilder(RuntimeEnvironment.getApplication() as Context,
        MeloXDatabase::class.java, file.absolutePath).setDriver(BundledSQLiteDriver())
        .addMigrations(MeloXDatabase.MIGRATION_1_2).build()
    private fun repository() = RoomLocalLibraryRepository(database, { elapsed }, { epoch })
    private suspend fun reopen() {
        database.close()
        database = open()
        library = repository()
        library.initialize()
    }
    private fun grant() = LocalGrantObservation(read = ObservedCapability.AVAILABLE, enumerate = ObservedCapability.AVAILABLE)
    private suspend fun source(id: String): LibrarySource = library.registerSource(LocalSourceSpec(id, LocalSourceKind.TREE,
        "content://library-fixture/tree/$id", authority = "library-fixture", rootObjectId = "root-$id", providerIncarnation = "fixture-instance"), grant())
    private fun root(source: LibrarySource) = ObservedCandidate(source.spec.sourceId, source.spec.rootObjectId ?: throw AssertionError("root"),
        source.spec.uri, null, source.spec.sourceId, "vnd.android.document/directory", LocalNodeKind.DIRECTORY)
    private fun directory(source: LibrarySource, id: String, name: String) = ObservedCandidate(source.spec.sourceId, id,
        "content://library-fixture/document/$id", source.spec.rootObjectId, name, "vnd.android.document/directory", LocalNodeKind.DIRECTORY)
    private fun audio(source: LibrarySource, id: String, name: String, parent: String? = source.spec.rootObjectId,
        proof: String? = null, content: String? = "native:$id:1") = ObservedCandidate(source.spec.sourceId, id,
        "content://library-fixture/document/${source.spec.sourceId}/$id", parent, name, "audio/mpeg", LocalNodeKind.AUDIO,
        sizeBytes = 128L, providerContentToken = content, entityProof = proof)
    private suspend fun rootNodeId(source: LibrarySource): String = database.libraryDao().node(source.spec.sourceId,
        source.spec.rootObjectId ?: throw AssertionError("root"))?.nodeId ?: throw AssertionError("root node")
    private suspend fun begin(source: LibrarySource, mode: ScanMode = ScanMode.INCREMENTAL): ScanToken =
        library.beginScan(source.spec.sourceId, library.source(source.spec.sourceId)?.authEpoch ?: throw AssertionError("source"), library.rules().revision, mode)
    private fun coverage() = ScanCoverage(true, true, true, true, false)
    private suspend fun finishDirectories(token: ScanToken) {
        while (true) {
            val next = library.nextDirectory(token) ?: break
            library.completeDirectory(token, next.objectId, true)
        }
    }
    private suspend fun finish(token: ScanToken) {
        finishDirectories(token)
        library.setCoverage(token, coverage())
        if (token.mode == ScanMode.FULL) {
            var after: String? = null
            while (true) {
                val page = library.stageAbsence(token, after)
                if (page.complete) break
                after = page.nextAliasId
            }
        }
        library.finishScan(token, ScanCounters(), coverage())
    }
    private suspend fun append(source: LibrarySource, candidates: List<ObservedCandidate>,
        metadata: Map<String, LocalAudioMetadata> = emptyMap(), mode: ScanMode = ScanMode.INCREMENTAL): Long {
        val token = begin(source, mode)
        var added = library.commitSeen(token, listOf(root(source))).added
        for (batch in candidates.chunked(LibraryLimits.PAGE_SIZE)) added += library.commitSeen(token, batch).added
        for (batch in metadata.entries.chunked(LibraryLimits.PAGE_SIZE)) library.commitMetadata(token, batch.map { MetadataOutcome(it.key, it.value) })
        finish(token)
        return added
    }
    private suspend fun tracks(query: LibraryQuery = LibraryQuery(), sort: LibrarySort = LibrarySort()): List<LibraryTrack> {
        val view = library.openView(query, sort)
        try {
            val result = ArrayList<LibraryTrack>()
            var cursor: LibraryCursor? = null
            do {
                val page = library.page(view, cursor)
                result += page.tracks
                cursor = page.nextCursor
            } while (cursor != null)
            return result
        } finally { library.closeView(view.viewId) }
    }
    private suspend fun expectIssue(issue: LibraryIssue, action: suspend () -> Unit) {
        try { action() } catch (error: LibraryException) { assertEquals(issue, error.issue); return }
        throw AssertionError("expected $issue")
    }
}
