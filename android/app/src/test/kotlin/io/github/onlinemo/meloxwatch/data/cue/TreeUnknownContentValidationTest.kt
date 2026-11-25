package io.github.onlinemo.meloxwatch.data.cue

import android.content.Context
import android.content.Intent
import io.github.onlinemo.meloxwatch.MeloXApplication
import io.github.onlinemo.meloxwatch.data.library.LibraryTrack
import io.github.onlinemo.meloxwatch.data.library.LibraryFolder
import io.github.onlinemo.meloxwatch.data.library.RoomFolderPlaylistMaintainer
import io.github.onlinemo.meloxwatch.data.playlists.FrozenPlaylistInput
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistFolderBinding
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistInputPage
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistLimits
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMember
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutation
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutationRequest
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistOperationResult
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistPageSource
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistQueueAvailability
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistQueueCatalogToken
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistReference
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistReferenceIssue
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistSnapshot
import io.github.onlinemo.meloxwatch.data.playlistsexchange.RoomPlaylistExportSource
import io.github.onlinemo.meloxwatch.data.scan.ScanState
import io.github.onlinemo.meloxwatch.model.CuePcmWaveFacts
import io.github.onlinemo.meloxwatch.playback.LocalPlaybackResolver
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackException
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackIssue
import io.github.onlinemo.meloxwatch.ui.playlists.FolderPlaylistCreateRequest
import io.github.onlinemo.meloxwatch.ui.playlists.FolderPlaylistUpdateRequest
import io.github.onlinemo.meloxwatch.ui.playlists.KeywordPlaylistCreateRequest
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistFolderChoice
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistRecoveryAction
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistRecoveryTarget
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelAndJoin
import io.github.onlinemo.meloxwatch.data.library.LibraryAvailability
import io.github.onlinemo.meloxwatch.data.library.LibraryException
import io.github.onlinemo.meloxwatch.data.library.LibraryIssue
import io.github.onlinemo.meloxwatch.data.library.LibraryLimits
import io.github.onlinemo.meloxwatch.data.library.LibraryQuery
import io.github.onlinemo.meloxwatch.data.library.LibrarySourceState
import io.github.onlinemo.meloxwatch.data.library.LocalAudioMetadata
import io.github.onlinemo.meloxwatch.data.library.LocalContentValidation
import io.github.onlinemo.meloxwatch.data.library.RoomPlaylistFences
import io.github.onlinemo.meloxwatch.data.local.AndroidLocalSources
import io.github.onlinemo.meloxwatch.data.local.LocalInputLimits
import io.github.onlinemo.meloxwatch.data.local.LocalMetadataReader
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistCatalogFence
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistException
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistIssue
import io.github.onlinemo.meloxwatch.data.scan.LocalScanEngine
import io.github.onlinemo.meloxwatch.data.scan.ScanMode
import io.github.onlinemo.meloxwatch.data.scan.ScanPhase
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** 真实TREE路由、V8 Room和FD内容证明；模拟返回grant不计入实机持久授权验收。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TreeUnknownContentValidationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test(timeout = 60_000L)
    fun anEnumeratedWeakTreeKeepsUnknownReadThroughScanFileProofAndRoomReopen() {
        runBlocking {
            withWeakTree { fixture ->
                fixture.scan()
                val library = fixture.room.library
                val track = fixture.room.tracks().single()
                val saved = checkNotNull(library.source(fixture.spec.sourceId))
                assertEquals(LibrarySourceState.ACTIVE, saved.state)
                assertEquals(ObservedCapability.UNKNOWN, saved.grant.read)
                assertEquals(ObservedCapability.AVAILABLE, saved.grant.enumerate)
                assertEquals(LibraryAvailability.UNKNOWN, track.availability)
                val read = library.resolveLocalRead(track.trackKey)
                val expected = RoomCueLibraryFixture.sha(fixture.room.wave)
                assertNull(read.providerContentToken)
                assertEquals(expected, read.verifiedContentToken)
                assertEquals(ObservedCapability.UNKNOWN, read.source.grant.read)
                val verified = fixture.sources.validateRead(read)
                assertEquals(LocalContentValidation.VERIFIED, verified.contentValidation)
                assertEquals(expected, verified.verifiedContentToken)
                assertEquals(ObservedCapability.UNKNOWN, verified.source.grant.read)

                val view = library.openView(LibraryQuery(sourceId = saved.spec.sourceId, relativePathKeyword = "disc.wav"))
                try {
                    assertEquals(listOf(track.trackKey), library.page(view).tracks.map { it.trackKey })
                    val folder = library.folders(view, null, null, LibraryLimits.PAGE_SIZE).folders.single()
                    assertEquals(saved.spec.sourceId, folder.sourceId)
                    val fence = catalogFence(fixture.room, saved.spec.sourceId)
                    val verifier = RoomPlaylistFences(fixture.room.database)
                    library.libraryTransaction { verifier.verify(fence) }
                } finally { library.closeView(view.viewId) }
                assertRetired(fixture.provider)

                fixture.room.reopen()
                val reopened = checkNotNull(fixture.room.library.source(saved.spec.sourceId))
                assertEquals(saved.spec, reopened.spec)
                assertEquals(saved.grant, reopened.grant)
                assertEquals(saved.grantId, reopened.grantId)
                assertEquals(saved.authEpoch, reopened.authEpoch)
                assertEquals(LibraryAvailability.UNKNOWN, fixture.room.tracks().single().availability)
                val restored = fixture.room.library.resolveLocalRead(track.trackKey)
                assertEquals(expected, restored.verifiedContentToken)
                assertEquals(LocalContentValidation.VERIFIED, fixture.sources.validateRead(restored).contentValidation)
                assertEquals(ObservedCapability.UNKNOWN, checkNotNull(fixture.room.library.source(saved.spec.sourceId)).grant.read)
                assertRetired(fixture.provider)
            }
        }
    }

    @Test(timeout = 60_000L)
    fun unknownReadNeverBypassesExplicitDenialMissingProofOrSameLengthReplacement() {
        runBlocking {
            withWeakTree { fixture ->
                fixture.scan()
                val library = fixture.room.library
                val track = fixture.room.tracks().single()
                val read = library.resolveLocalRead(track.trackKey)
                val opens = fixture.provider.opened.size
                expectRead(SourceReadIssue.PERMISSION_REVOKED) {
                    fixture.sources.validateRead(read.copy(source = read.source.copy(
                        grant = read.source.grant.copy(read = ObservedCapability.UNAVAILABLE))))
                }
                expectRead(SourceReadIssue.SOURCE_UNAVAILABLE) {
                    fixture.sources.validateRead(read.copy(source = read.source.copy(
                        grant = read.source.grant.copy(enumerate = ObservedCapability.UNKNOWN))))
                }
                for (kind in listOf(LocalSourceKind.DOCUMENT, LocalSourceKind.MEDIA_STORE, LocalSourceKind.APP_OWNED)) {
                    expectRead(SourceReadIssue.SOURCE_UNAVAILABLE) {
                        fixture.sources.validateRead(read.copy(source = read.source.copy(spec = read.source.spec.copy(kind = kind))))
                    }
                }
                assertEquals(opens, fixture.provider.opened.size)
                expectRead(SourceReadIssue.CONTENT_UNKNOWN) {
                    fixture.sources.validateRead(read.copy(verifiedContentToken = null))
                }
                assertEquals(opens + 1, fixture.provider.opened.size)

                fixture.provider.denied = fixture.objectId
                val stillEnumerated = fixture.sources.observe(read.source.spec)
                assertEquals(ObservedCapability.UNKNOWN, stillEnumerated.access.read)
                assertEquals(ObservedCapability.AVAILABLE, stillEnumerated.access.enumerate)
                expectRead(SourceReadIssue.PERMISSION_DENIED) { fixture.sources.validateRead(read) }
                fixture.provider.denied = null

                // 这里只制造“要求的持久权不存在”，不把合成期望记成平台撤权测试。
                val beforePersistenceCheck = fixture.provider.opened.size
                expectRead(SourceReadIssue.PERMISSION_REVOKED) {
                    fixture.sources.validateRead(read.copy(source = read.source.copy(
                        grant = read.source.grant.copy(persistedRead = ObservedCapability.AVAILABLE))))
                }
                assertEquals(beforePersistenceCheck, fixture.provider.opened.size)
                val replacement = fixture.room.wave.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
                fixture.file.writeBytes(replacement)
                assertEquals(checkNotNull(read.sizeBytes), fixture.file.length())
                assertNotEquals(read.verifiedContentToken, RoomCueLibraryFixture.sha(replacement))
                expectRead(SourceReadIssue.SOURCE_CHANGED) { fixture.sources.validateRead(read) }
                assertEquals(read.verifiedContentToken, library.resolveLocalRead(track.trackKey).verifiedContentToken)
                assertEquals(ObservedCapability.UNKNOWN, checkNotNull(library.source(fixture.spec.sourceId)).grant.read)
                assertEquals(LibraryAvailability.UNKNOWN, fixture.room.tracks().single().availability)
                assertRetired(fixture.provider)
            }
        }
    }

    @Test(timeout = 60_000L)
    fun onlyAnEnumeratedUnknownTreeCanBecomeActiveAndRecoverBySameIdentityReauthorization() {
        runBlocking {
            withWeakTree { fixture ->
                val observation = fixture.sources.observe(fixture.spec)
                val library = fixture.room.library
                val denied = library.registerSource(observation.source.copy(sourceId = "explicit-read-denied"),
                    observation.access.copy(read = ObservedCapability.UNAVAILABLE))
                assertEquals(LibrarySourceState.NEEDS_AUTHORIZATION, denied.state)
                val unprobed = library.registerSource(observation.source.copy(sourceId = "tree-enumeration-unknown"),
                    observation.access.copy(enumerate = ObservedCapability.UNKNOWN))
                assertEquals(LibrarySourceState.UNAVAILABLE, unprobed.state)
                for (kind in listOf(LocalSourceKind.DOCUMENT, LocalSourceKind.MEDIA_STORE)) {
                    val blocked = library.registerSource(observation.source.copy(sourceId = "unknown-${kind.name.lowercase()}", kind = kind),
                        observation.access)
                    assertEquals(LibrarySourceState.UNAVAILABLE, blocked.state)
                    expectLibrary(LibraryIssue.SOURCE_UNAVAILABLE) {
                        library.beginScan(blocked.spec.sourceId, blocked.authEpoch, library.rules().revision, ScanMode.FULL)
                    }
                }
                assertTrue(fixture.provider.opened.isEmpty())
                fixture.scan()
                val track = fixture.room.tracks().single()
                val original = checkNotNull(library.source(fixture.spec.sourceId))
                val originalRead = library.resolveLocalRead(track.trackKey)
                val revoked = library.setSourceIssue(original.spec.sourceId, original.authEpoch, SourceReadIssue.PERMISSION_REVOKED)
                assertEquals(LibrarySourceState.NEEDS_AUTHORIZATION, revoked.state)
                expectLibrary(LibraryIssue.PERMISSION_REVOKED) { library.resolveLocalRead(track.trackKey) }
                val verified = fixture.sources.verifyReauthorization(original.spec, original.spec.copy(sourceId = "selected-tree"))
                assertEquals(original.spec.sourceId, verified.source.sourceId)
                assertEquals(ObservedCapability.UNKNOWN, verified.access.read)
                assertEquals(ObservedCapability.AVAILABLE, verified.access.enumerate)
                for (badGrant in listOf(verified.access.copy(read = ObservedCapability.UNAVAILABLE),
                    verified.access.copy(enumerate = ObservedCapability.UNKNOWN))) {
                    expectLibrary(LibraryIssue.SOURCE_CHANGED) {
                        library.reauthorizeSource(original.spec.sourceId, revoked.authEpoch, verified.source, badGrant)
                    }
                }
                assertEquals(revoked, library.source(original.spec.sourceId))
                val restored = library.reauthorizeSource(original.spec.sourceId, revoked.authEpoch, verified.source, verified.access)
                assertEquals(LibrarySourceState.ACTIVE, restored.state)
                assertEquals(ObservedCapability.UNKNOWN, restored.grant.read)
                assertEquals(revoked.authEpoch + 1L, restored.authEpoch)
                assertEquals(revoked.revision + 1L, restored.revision)
                assertNotEquals(original.grantId, restored.grantId)
                expectLibrary(LibraryIssue.CONFLICT) {
                    library.beginScan(restored.spec.sourceId, original.authEpoch, library.rules().revision, ScanMode.FULL)
                }
                val currentRead = library.resolveLocalRead(track.trackKey)
                assertEquals(restored.authEpoch, currentRead.source.authEpoch)
                assertEquals(restored.grantId, currentRead.source.grantId)
                assertEquals(originalRead.verifiedContentToken, currentRead.verifiedContentToken)
                assertEquals(LocalContentValidation.VERIFIED, fixture.sources.validateRead(currentRead).contentValidation)
                assertEquals(LibraryAvailability.UNKNOWN, fixture.room.tracks().single().availability)
                assertRetired(fixture.provider)
            }
        }
    }

    @Test(timeout = 120_000L)
    fun anUnknownTreeCueNeedsTheCompleteWaveProofAndKeepsScopedGroupAndFolderFences() {
        runBlocking {
            val fixture = CueLargeTreeFixture(context(), temporary.newFolder("large-tree"))
            try {
                fixture.open()
                val tracks = fixture.scan("unknown-cue")
                val library = fixture.room.library
                val source = checkNotNull(library.source("source-unknown-cue"))
                assertEquals(ObservedCapability.UNKNOWN, source.grant.read)
                assertEquals(ObservedCapability.AVAILABLE, source.grant.enumerate)
                assertTrue(fixture.facts.fileBytes > LocalInputLimits.READ_PROOF_BYTES)
                assertEquals(8L * 1024L * 1024L + 44L, fixture.facts.fileBytes)
                assertTrue(tracks.all { it.availability == LibraryAvailability.UNKNOWN })
                val track = tracks.first()
                val reference = checkNotNull(track.cueReference)
                val read = library.resolveCueRead(track.trackKey, reference)
                assertEquals(fixture.facts, checkNotNull(read.reference.binding).pcm)
                assertEquals(CueLargeTreeFixture.fileToken(fixture.file("unknown-cue")), read.audio.verifiedContentToken)
                assertEquals(LocalContentValidation.VERIFIED, fixture.sources.validateCueRead(read.audio).contentValidation)
                expectRead(SourceReadIssue.CONTENT_UNKNOWN) { fixture.sources.validateRead(read.audio) }
                expectCue(CueIssue.UNVERIFIED_CONTENT) {
                    fixture.sources.validateCueRead(read.audio.copy(verifiedContentToken = null))
                }
                fixture.provider.denied = "unknown-cue-wave"
                expectRead(SourceReadIssue.PERMISSION_DENIED) { fixture.sources.validateCueRead(read.audio) }
                fixture.provider.denied = null

                val view = library.openView(LibraryQuery(sourceId = source.spec.sourceId))
                try {
                    val root = library.folders(view, null, null, LibraryLimits.PAGE_SIZE).folders.single()
                    assertEquals("disc.cue", library.cueRelativePath(view, track))
                    assertEquals(listOf(track.trackKey), library.cueRelativeMatches(view, root.nodeId, listOf("disc.cue"), reference.identity.trackNumber))
                    val fence = catalogFence(fixture.room, source.spec.sourceId)
                    val verifier = RoomPlaylistFences(fixture.room.database)
                    library.libraryTransaction { verifier.verify(fence) }
                    for (wrong in listOf(fence.copy(catalogRevision = fence.catalogRevision + 1L),
                        fence.copy(rulesRevision = fence.rulesRevision + 1L),
                        fence.copy(sourceRevision = fence.sourceRevision + 1L),
                        fence.copy(authEpoch = fence.authEpoch + 1L), fence.copy(scanId = "different-complete-scan"))) {
                        expectPlaylist(PlaylistIssue.SOURCE_CHANGED) { library.libraryTransaction { verifier.verify(wrong) } }
                    }
                    val dao = fixture.room.database.libraryDao()
                    val scan = checkNotNull(dao.scan(fence.scanId))
                    try {
                        for (incomplete in listOf(scan.copy(enumerationComplete = false), scan.copy(authorizationValid = false),
                            scan.copy(excludedSubtrees = true), scan.copy(absenceDone = false), scan.copy(failed = 1L))) {
                            library.libraryTransaction { dao.updateScan(incomplete) }
                            expectPlaylist(PlaylistIssue.INCOMPLETE) { library.libraryTransaction { verifier.verify(fence) } }
                        }
                    } finally { library.libraryTransaction { dao.updateScan(scan) } }
                    library.libraryTransaction { verifier.verify(fence) }

                    val revoked = library.setSourceIssue(source.spec.sourceId, source.authEpoch, SourceReadIssue.PERMISSION_REVOKED)
                    expectLibrary(LibraryIssue.PERMISSION_REVOKED) { library.resolveCueRead(track.trackKey, reference) }
                    expectLibrary(LibraryIssue.PERMISSION_REVOKED) {
                        library.cueRelativeMatches(view, root.nodeId, listOf("disc.cue"), reference.identity.trackNumber)
                    }
                    val observed = fixture.sources.verifyReauthorization(source.spec, source.spec.copy(sourceId = "new-selection"))
                    val restored = library.reauthorizeSource(source.spec.sourceId, revoked.authEpoch, observed.source, observed.access)
                    assertEquals(ObservedCapability.UNKNOWN, restored.grant.read)
                    assertNotEquals(source.grantId, restored.grantId)
                    assertTrue(restored.authEpoch > source.authEpoch)
                    expectLibrary(LibraryIssue.SOURCE_CHANGED) { library.resolveCueRead(track.trackKey, reference) }
                    expectLibrary(LibraryIssue.SOURCE_CHANGED) {
                        library.cueRelativeMatches(view, root.nodeId, listOf("disc.cue"), reference.identity.trackNumber)
                    }
                    expectPlaylist(PlaylistIssue.SOURCE_CHANGED) { library.libraryTransaction { verifier.verify(fence) } }
                } finally { library.closeView(view.viewId) }
                assertRetired(fixture.provider)
                fixture.room.reopen()
                assertEquals(ObservedCapability.UNKNOWN, checkNotNull(fixture.room.library.source(source.spec.sourceId)).grant.read)
                assertRetired(fixture.provider)
            } finally { fixture.close() }
        }
    }

    @Config(application = MeloXApplication::class)
    @Test(timeout = 120_000L)
    fun actualPlaylistMappingAndExplicitPlaybackKeepUnknownTreeRoutesAndStoredCueHints() {
        runBlocking {
            withApplicationTree { fixture ->
                fixture.scan()
                val app = fixture.app
                val tracks = fixture.tracks()
                assertEquals(3, tracks.size)
                assertTrue(tracks.all { it.availability == LibraryAvailability.UNKNOWN })
                val original = tracks.map { track -> PlaylistReference(trackKey = track.trackKey, titleHint = track.title,
                    durationMs = track.metadata.durationMs, cueReference = track.cueReference?.portable()) }
                val playlist = fixture.create("unknown-ordinary", original)
                val playlistView = app.playlists.openView(playlist.playlistId)
                val libraryView = app.library.openView(LibraryQuery(includeHidden = true))
                try {
                    val page = app.playlists.page(playlistView)
                    assertEquals(original, page.members.map { it.reference })
                    assertTrue(page.members.filter { it.reference.cueReference != null }.all { !checkNotNull(it.reference.cueReference).isBound })
                    val beforeMap = fixture.provider.opened.size
                    val mapped = app.playlistQueueMapper.map(PlaylistQueueCatalogToken(libraryView.viewId,
                        libraryView.revision, libraryView.rulesRevision), page.members)
                    assertEquals(beforeMap, fixture.provider.opened.size)
                    assertEquals(page.members.map { it.memberId }, mapped.map { it.memberId })
                    assertEquals(page.members.map { it.ordinal }, mapped.map { it.entry.originalOrder })
                    assertTrue(mapped.all { it.availability == PlaylistQueueAvailability.UNAVAILABLE })
                    assertTrue(mapped.all { it.entry.location != null })
                    assertEquals(2, mapped.count { it.entry.cueReference?.isBound == true })
                    val ordinary = mapped.single { it.entry.cueReference == null }.entry
                    val resolver = LocalPlaybackResolver(app)
                    for (value in mapped) {
                        val beforeRead = fixture.provider.opened.size
                        val resolved = resolver.resolve(value.entry)
                        assertEquals(value.entry.trackKey, resolved.trackKey)
                        assertEquals(value.entry.location, resolved.location)
                        assertTrue(fixture.provider.opened.size > beforeRead)
                    }
                    val read = app.library.resolveLocalRead(ordinary.trackKey)
                    assertEquals(CueLargeTreeFixture.fileToken(fixture.wave), read.verifiedContentToken)
                    assertEquals(ObservedCapability.UNKNOWN, read.source.grant.read)
                    assertEquals(page.members, app.playlists.page(playlistView).members)

                    val beforeExport = fixture.provider.opened.size
                    val exporter = RoomPlaylistExportSource(app.library.database, app.playlists, app.library,
                        playlistView, libraryView, "unknown-relative-export", singleLineTitles = true)
                    exporter.prepareRelativeRoot()
                    val exported = exporter.page(0L, PlaylistLimits.PAGE_SIZE)
                    assertEquals(page.members.size, exported.size)
                    assertTrue(exported.all { !it.unresolved && it.relativePath != null })
                    assertEquals(setOf(listOf("disc.wav"), listOf("disc.cue")), exported.map { checkNotNull(it.relativePath).components }.toSet())
                    assertEquals(beforeExport, fixture.provider.opened.size)
                    assertEquals(page.members, app.playlists.page(playlistView).members)

                    // 同长度替换必须由显式播放器FD/hash拒绝，索引和grant仍保原证明及UNKNOWN。
                    val replacement = fixture.wave.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
                    fixture.wave.writeBytes(replacement)
                    expectPlayback(QueuePlaybackIssue.SOURCE_CHANGED) { resolver.resolve(ordinary) }
                    expectPlayback(QueuePlaybackIssue.SOURCE_CHANGED) { resolver.resolve(mapped.first { it.entry.cueReference != null }.entry) }
                    fixture.wave.writeBytes(fixture.bytes)
                } finally {
                    app.library.closeView(libraryView.viewId)
                    app.playlists.closeView(playlistView.viewId)
                }

                val replacements = listOf(tracks.single { it.cueReference == null }, tracks.first { it.cueReference != null })
                for ((index, selected) in replacements.withIndex()) {
                    val pending = fixture.create("unknown-recovery-$index", listOf(PlaylistReference(placeholderId = "needs-reassociation-$index",
                        issue = PlaylistReferenceIssue.UNMATCHED, titleHint = "Missing logical reference")))
                    val recoveryView = app.playlists.openView(pending.playlistId)
                    try {
                        val member = app.playlists.page(recoveryView).members.single()
                        val target = PlaylistRecoveryTarget(recoveryView, member, PlaylistQueueAvailability.UNRESOLVED,
                            PlaylistRecoveryAction.REASSOCIATE)
                        val beforeRecovery = fixture.provider.opened.size
                        val preview = app.playlistRecovery.preview(target, selected.trackKey)
                        val commit = app.playlistRecovery.confirm("unknown-reassociate-$index", preview.previewId, confirmed = true)
                        assertEquals(PlaylistOperationResult.REFERENCE_REPLACED, commit.result)
                        assertEquals(1L, checkNotNull(commit.snapshot).memberCount)
                        assertEquals(selected.trackKey, fixture.members(checkNotNull(commit.snapshot)).single().reference.trackKey)
                        assertEquals(beforeRecovery, fixture.provider.opened.size)
                    } finally { app.playlists.closeView(recoveryView.viewId) }
                }
                assertEquals(ObservedCapability.UNKNOWN, checkNotNull(app.library.source(fixture.spec.sourceId)).grant.read)
                assertRetired(fixture.provider)
            }
        }
    }

    @Config(application = MeloXApplication::class)
    @Test(timeout = 120_000L)
    fun folderAndKeywordInputsRetainUnknownTreeMembersAcrossFullMaintenanceAndFailedReads() {
        runBlocking {
            withApplicationTree { fixture ->
                fixture.scan()
                val app = fixture.app
                val root = fixture.rootFolder()
                val choice = PlaylistFolderChoice(PlaylistFolderBinding(fixture.spec.sourceId, root.nodeId), root.name)
                val beforePreview = fixture.provider.opened.size
                val preview = app.playlistFolders.preview(choice)
                assertEquals(3L, preview.memberCount)
                val created = checkNotNull(app.playlistFolders.create(FolderPlaylistCreateRequest("unknown-folder-create",
                    "unknown-folder", "Unknown tree folder", preview.previewId)).snapshot)
                assertEquals(beforePreview, fixture.provider.opened.size)
                val original = fixture.members(created)
                assertEquals(fixture.tracks().map { it.trackKey }.toSet(), original.map { checkNotNull(it.reference.trackKey) }.toSet())
                val moved = checkNotNull(app.playlists.apply(PlaylistMutationRequest("unknown-folder-move", created.playlistId,
                    created.revision, PlaylistMutation.Move(original.last().memberId, 0L))).snapshot)
                val manualOrder = fixture.members(moved)
                val second = fixture.scan()
                val maintainer = RoomFolderPlaylistMaintainer(app.library.database, app.playlists, app.library)
                try {
                    val beforeMaintenance = fixture.provider.opened.size
                    val result = maintainer.afterScan(second)
                    assertEquals(1L, result.synchronized)
                    assertNull(result.issue)
                    val synchronized = checkNotNull(app.playlists.snapshot(created.playlistId))
                    assertEquals(3L, synchronized.memberCount)
                    assertEquals(manualOrder.map { it.memberId }, fixture.members(synchronized).map { it.memberId })
                    assertEquals(manualOrder.map { it.reference.trackKey }, fixture.members(synchronized).map { it.reference.trackKey })
                    val update = app.playlistFolders.preview(choice, synchronized)
                    assertEquals(3L, update.memberCount)
                    assertEquals(0L, update.addedCount)
                    assertEquals(0L, update.removedCount)
                    val unchanged = checkNotNull(app.playlistFolders.synchronize(FolderPlaylistUpdateRequest("unknown-folder-explicit-sync",
                        synchronized.playlistId, synchronized.revision, update.previewId)).snapshot)
                    assertEquals(manualOrder.map { it.memberId }, fixture.members(unchanged).map { it.memberId })
                    val keyword = app.playlistKeywords.preview("disc.")
                    assertEquals(3L, keyword.memberCount)
                    val keywordSnapshot = checkNotNull(app.playlistKeywords.create(KeywordPlaylistCreateRequest("unknown-keyword-create",
                        "unknown-keyword", "Unknown path snapshot", keyword.previewId, true, false)).snapshot)
                    val keywordMembers = fixture.members(keywordSnapshot)
                    assertEquals(3, keywordMembers.size)
                    assertEquals(manualOrder.map { it.reference.trackKey }.toSet(), keywordMembers.map { it.reference.trackKey }.toSet())
                    assertEquals(beforeMaintenance, fixture.provider.opened.size)

                    fixture.provider.denied = "app-root-wave-0"
                    val failed = fixture.scan(expectComplete = false)
                    assertFalse(failed.coverage.permitsAbsence)
                    assertTrue(failed.counters.failed > 0L)
                    assertEquals(0L, maintainer.afterScan(failed).synchronized)
                    val latest = checkNotNull(app.library.source(fixture.spec.sourceId))
                    app.library.setSourceIssue(latest.spec.sourceId, latest.authEpoch, SourceReadIssue.PERMISSION_REVOKED)
                    assertEquals(0L, maintainer.resume().synchronized)
                    assertEquals(manualOrder.map { it.memberId }, fixture.members(checkNotNull(app.playlists.snapshot(created.playlistId))).map { it.memberId })
                    assertEquals(keywordMembers, fixture.members(keywordSnapshot))
                } finally { maintainer.close() }
                assertRetired(fixture.provider)
            }
        }
    }

    @Config(application = MeloXApplication::class)
    @Test(timeout = 120_000L)
    fun keywordBroadConfirmationCountsTheSameOneHundredUnknownTreeCandidatesAsItsInput() {
        runBlocking {
            withApplicationTree(waveCount = 100, includeCue = false) { fixture ->
                fixture.scan()
                val app = fixture.app
                val strict = app.library.openView(LibraryQuery(readableOnly = true))
                try { assertEquals(0L, strict.totalCount) } finally { app.library.closeView(strict.viewId) }
                assertEquals(100, fixture.tracks().size)
                val beforePreview = fixture.provider.opened.size
                val preview = app.playlistKeywords.preview("Unknown")
                assertEquals(100L, preview.memberCount)
                assertTrue(preview.requiresBroadConfirmation)
                expectPlaylist(PlaylistIssue.INVALID_INPUT) {
                    app.playlistKeywords.create(KeywordPlaylistCreateRequest("unknown-broad-rejected", "unknown-broad-rejected",
                        "Requires confirmation", preview.previewId, false, false))
                }
                val accepted = checkNotNull(app.playlistKeywords.create(KeywordPlaylistCreateRequest("unknown-broad-create",
                    "unknown-broad", "Complete unknown input", preview.previewId, true, false)).snapshot)
                assertEquals(100L, accepted.memberCount)
                assertEquals(100, fixture.members(accepted).size)
                assertEquals(fixture.tracks().map { it.trackKey }.toSet(), fixture.members(accepted).map { it.reference.trackKey }.toSet())
                assertEquals(beforePreview, fixture.provider.opened.size)
                assertTrue(fixture.tracks().all { it.availability == LibraryAvailability.UNKNOWN })
                assertEquals(ObservedCapability.UNKNOWN, checkNotNull(app.library.source(fixture.spec.sourceId)).grant.read)
                assertRetired(fixture.provider)
            }
        }
    }

    private suspend fun withWeakTree(action: suspend (WeakTreeFixture) -> Unit) {
        val fixture = WeakTreeFixture(context(), temporary.newFolder("weak-tree"))
        try {
            fixture.room.open()
            action(fixture)
        } finally { fixture.close() }
    }

    private suspend fun catalogFence(room: RoomCueLibraryFixture, sourceId: String): PlaylistCatalogFence {
        val dao = room.database.libraryDao()
        val meta = checkNotNull(dao.meta())
        val source = checkNotNull(dao.source(sourceId))
        return PlaylistCatalogFence(meta.revision, meta.rulesRevision, sourceId, source.revision, source.authEpoch,
            checkNotNull(source.latestScanId))
    }

    private fun assertRetired(provider: CueDocumentsProviderFixture) {
        assertEquals(0, provider.activeCursors.get())
        assertTrue(provider.descriptors.isNotEmpty())
        assertTrue(provider.descriptors.all { !it.fileDescriptor.valid() })
    }
    private fun context(): Context = RuntimeEnvironment.getApplication()
    private suspend fun expectRead(issue: SourceReadIssue, action: suspend () -> Any?) {
        try { action(); throw AssertionError("Expected $issue") }
        catch (error: SourceReadException) { assertEquals(issue, error.issue) }
    }
    private suspend fun expectLibrary(issue: LibraryIssue, action: suspend () -> Any?) {
        try { action(); throw AssertionError("Expected $issue") }
        catch (error: LibraryException) { assertEquals(issue, error.issue) }
    }
    private suspend fun expectPlaylist(issue: PlaylistIssue, action: suspend () -> Any?) {
        try { action(); throw AssertionError("Expected $issue") }
        catch (error: PlaylistException) { assertEquals(issue, error.issue) }
    }
    private suspend fun expectCue(issue: CueIssue, action: suspend () -> Any?) {
        try { action(); throw AssertionError("Expected $issue") }
        catch (error: CueException) { assertEquals(issue, error.issue) }
    }

    private suspend fun withApplicationTree(waveCount: Int = 1, includeCue: Boolean = true,
        action: suspend (ApplicationTreeFixture) -> Unit) {
        val app = RuntimeEnvironment.getApplication() as MeloXApplication
        val fixture = ApplicationTreeFixture(app, temporary.newFolder("application-tree"), waveCount, includeCue)
        var failure: Throwable? = null
        try {
            fixture.open()
            action(fixture)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try { fixture.close() } catch (cleanup: Throwable) {
                if (failure == null) throw cleanup
                if (failure !== cleanup) failure.addSuppressed(cleanup)
            }
        }
    }

    private suspend fun expectPlayback(issue: QueuePlaybackIssue, action: suspend () -> Any?) {
        try { action(); throw AssertionError("Expected $issue") }
        catch (error: QueuePlaybackException) { assertEquals(issue, error.failure.issue) }
    }

    private class ApplicationTreeFixture(val app: MeloXApplication, directory: File, waveCount: Int, includeCue: Boolean) {
        val provider = fixtureCueDocuments(app, "tree-unknown-application")
        val spec = LocalSourceSpec("application-tree-source", LocalSourceKind.TREE,
            "content://tree-unknown-application/tree/app-root", Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val facts = CuePcmWaveFacts(44L + 88_200L * 4L, 44L, 88_200L * 4L, 44_100, 2)
        val bytes = CuePcmWaveParser.transportHeader(facts, facts.dataBytes) + ByteArray(facts.dataBytes.toInt())
        val wave: File
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        init {
            require(waveCount in 1..100 && (!includeCue || waveCount == 1))
            provider.roots.add("app-root")
            repeat(waveCount) { index ->
                val file = File(directory, if (waveCount == 1) "disc.wav" else "Unknown-$index.wav").apply { writeBytes(bytes) }
                provider.documents["app-root-wave-$index"] = file
            }
            wave = checkNotNull(provider.documents["app-root-wave-0"])
            if (includeCue) provider.documents["app-root-cue"] = File(directory, "disc.cue").apply { writeText(RoomCueLibraryFixture.CUE) }
        }
        suspend fun open() {
            app.requireBackupStorageReady()
            app.fileOperations.initialize()
            app.library.initialize()
            app.playlists.initialize()
            withTimeout(5_000L) { app.localProviderIo.awaitIdle() }
        }
        suspend fun scan(expectComplete: Boolean = true): ScanState {
            val observed = app.localSources.observe(spec)
            assertEquals(ObservedCapability.UNKNOWN, observed.access.read)
            assertEquals(ObservedCapability.AVAILABLE, observed.access.enumerate)
            val source = app.library.registerSource(observed.source, observed.access)
            val reader = CueScanReader(app.fileOperations.cueMutations, CuePathMatcher(app.localSources), AndroidCueLocalDocuments(app.localSources))
            val engine = LocalScanEngine(app.localSources, { LocalAudioMetadata(title = "Whole file", durationMs = facts.durationUs / 1000L,
                mimeType = "audio/wav") }, app.fileOperations.mutations, app.localProviderIo, scope, app.localMetadata::contentToken,
                app.localSources::verifyObservedCandidate, reader::read)
            val token = engine.start(source, app.library.rules().revision)
            val terminal = withTimeout(30_000L) { engine.state.filterNotNull().first { it.token == token &&
                it.phase in setOf(ScanPhase.COMPLETED, ScanPhase.FAILED, ScanPhase.CANCELLED) } }
            if (expectComplete) {
                assertEquals(ScanPhase.COMPLETED, terminal.phase)
                assertTrue(terminal.coverage.permitsAbsence)
                assertEquals(0L, terminal.counters.failed)
            }
            withTimeout(5_000L) { app.localProviderIo.awaitIdle() }
            return terminal
        }
        suspend fun rootFolder(): LibraryFolder {
            val view = app.library.openView(LibraryQuery(sourceId = spec.sourceId))
            val result = try { app.library.folders(view, null, null, LibraryLimits.PAGE_SIZE).folders.single() }
                finally { app.library.closeView(view.viewId) }
            return result
        }
        suspend fun tracks(): List<LibraryTrack> {
            val view = app.library.openView(LibraryQuery(sourceId = spec.sourceId))
            val result = try { app.library.page(view).tracks } finally { app.library.closeView(view.viewId) }
            return result
        }
        suspend fun create(id: String, references: List<PlaylistReference>): PlaylistSnapshot {
            val input = FrozenPlaylistInput("$id-input", 1L, references.size.toLong())
            val commit = app.playlists.apply(PlaylistMutationRequest("$id-create", id, 0L, PlaylistMutation.Create(id, input)),
                PlaylistPageSource { offset, limit ->
                    val page = references.drop(offset.toInt()).take(limit)
                    PlaylistInputPage(input, offset, page, offset + page.size == input.totalCount)
                })
            return checkNotNull(commit.snapshot)
        }
        suspend fun members(snapshot: PlaylistSnapshot): List<PlaylistMember> {
            val view = app.playlists.openView(snapshot.playlistId)
            val result = try {
                assertEquals(snapshot, view.snapshot)
                val values = ArrayList<PlaylistMember>()
                var first = 0L
                while (true) {
                    val page = app.playlists.page(view, first, PlaylistLimits.PAGE_SIZE)
                    assertEquals(view, page.view)
                    assertTrue(page.members.size <= PlaylistLimits.PAGE_SIZE)
                    values += page.members
                    first = page.nextOrdinal ?: break
                }
                assertEquals(snapshot.memberCount, values.size.toLong())
                values.toList()
            } finally { app.playlists.closeView(view.viewId) }
            return result
        }
        suspend fun close() = withContext(NonCancellable) {
            scope.coroutineContext[Job]?.cancelAndJoin()
            withTimeout(5_000L) { app.localProviderIo.awaitIdle() }
            var failure: Throwable? = null
            try { app.onTerminate() }
            catch (error: Throwable) { failure = error; throw error }
            finally {
                try { app.library.database.close() } catch (cleanup: Throwable) {
                    if (failure == null) throw cleanup
                    if (failure !== cleanup) failure.addSuppressed(cleanup)
                }
            }
        }
    }

    private class WeakTreeFixture(context: Context, directory: File) : AutoCloseable {
        val room = RoomCueLibraryFixture(context, File(directory, "weak-tree.db"))
        val io = LocalProviderIo()
        val sources = AndroidLocalSources(context, io)
        val provider = fixtureCueDocuments(context, "tree-unknown-proof")
        val metadata = LocalMetadataReader(context, io)
        val objectId = "unknown-root-wave"
        val file = File(directory, "disc.wav").apply { writeBytes(room.wave) }
        val spec = LocalSourceSpec("weak-tree-source", LocalSourceKind.TREE,
            "content://tree-unknown-proof/tree/unknown-root", Intent.FLAG_GRANT_READ_URI_PERMISSION)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        init {
            provider.roots.add("unknown-root")
            provider.documents[objectId] = file
        }
        suspend fun scan() {
            val observation = sources.observe(spec)
            assertEquals(ObservedCapability.UNKNOWN, observation.access.read)
            assertEquals(ObservedCapability.AVAILABLE, observation.access.enumerate)
            assertTrue(provider.opened.isEmpty())
            val source = room.library.registerSource(observation.source, observation.access)
            assertEquals(ObservedCapability.UNKNOWN, checkNotNull(room.library.source(spec.sourceId)).grant.read)
            assertEquals(LibrarySourceState.ACTIVE, source.state)
            val candidates = enumerate(source.spec)
            assertEquals(listOf(objectId), candidates.map { it.objectId })
            assertNull(candidates.single().providerContentToken)
            assertTrue(provider.opened.isEmpty())
            assertEquals(0, provider.activeCursors.get())
            val engine = LocalScanEngine(sources, { LocalAudioMetadata(title = "Weak tree whole file", durationMs = 2_000L,
                mimeType = "audio/wav") }, room.library, io, scope, metadata::contentToken, sources::verifyObservedCandidate)
            val token = engine.start(source, room.library.rules().revision)
            val terminal = withTimeout(30_000L) { engine.state.filterNotNull().first { it.token.scanId == token.scanId &&
                it.phase in setOf(ScanPhase.COMPLETED, ScanPhase.FAILED, ScanPhase.CANCELLED) } }
            assertEquals(ScanPhase.COMPLETED, terminal.phase)
            assertTrue(terminal.coverage.permitsAbsence)
            assertEquals(0L, terminal.counters.failed)
            io.awaitIdle()
        }
        private suspend fun enumerate(spec: LocalSourceSpec): List<ObservedCandidate> {
            val cursor = sources.open(spec, "unknown-root")
            val result = try {
                val candidates = cursor.nextBatch()
                assertTrue(cursor.exhausted)
                candidates
            } finally { cursor.closeAndAwait() }
            return result
        }
        override fun close() { scope.cancel(); room.close() }
    }
}
