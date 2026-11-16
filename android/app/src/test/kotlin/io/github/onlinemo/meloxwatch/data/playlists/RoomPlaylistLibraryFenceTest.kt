package io.github.onlinemo.meloxwatch.data.playlists

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** 测试定义待云端编译/执行；不以关键词或交换UI的预检查代替最终数据库围栏。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RoomPlaylistLibraryFenceTest : PlaylistRoomFixture() {
    @Test(timeout = 30000L)
    fun anExplicitLibraryFenceWithoutAVerifierIsRejectedWhileOrdinaryOfflineCrudNeedsNoLibrary() = runBlocking {
        val old = create("independent-offline", references = listOf(reference(99)))
        val members = allMembers(old)
        val revision = PlaylistLibraryRevision(7L, 3L)
        val input = FrozenPlaylistInput("required-library-input", 2L, 2L)
        val operations = listOf(
            PlaylistMutationRequest("fenced-no-verifier-create", "fenced-no-verifier-create", 0L,
                PlaylistMutation.Create("Fenced", input), revision),
            request("fenced-no-verifier-add", old, PlaylistMutation.Add(input)).copy(expectedLibraryRevision = revision),
            request("fenced-no-verifier-rename", old, PlaylistMutation.Rename("Fenced name")).copy(expectedLibraryRevision = revision),
        )
        var reads = 0
        for (operation in operations) {
            expectIssue(PlaylistIssue.SOURCE_UNAVAILABLE) {
                repository.apply(operation, if (operation.mutation is PlaylistMutation.Rename) null else PlaylistPageSource { _, _ ->
                    reads += 1
                    throw AssertionError("missing verifier must reject before input reading")
                })
            }
            assertNull(database.playlistDao().receipt(operation.operationId))
        }
        assertEquals(0, reads)
        assertEquals(old, repository.snapshot(old.playlistId))
        assertEquals(members, allMembers(old))
        val renamed = checkNotNull(repository.apply(request("plain-offline-rename", old, PlaylistMutation.Rename("Offline works"))).snapshot)
        assertEquals("Offline works", renamed.name)
        assertEquals(members, allMembers(renamed))
        assertNull(database.fenceDao().library())
        integrity()
    }

    @Test(timeout = 30000L)
    fun lateCatalogOrRuleChangesRejectCompleteCreationAdditionAndAllDuplicateAdditionIncludingItsReceiptPath() = runBlocking {
        val verifier = useFenceVerifier()
        val old = create("library-fence-kept", references = listOf(reference(99)))
        val original = allMembers(old)
        val overview = repository.overview()
        var checks = 0
        for (mode in 0..2) {
            for (field in listOf("catalogRevision", "rulesRevision")) {
                val fence = prepareFence()
                val revision = PlaylistLibraryRevision(fence.catalogRevision, fence.rulesRevision)
                val suffix = "$mode-$field"
                val input = FrozenPlaylistInput("library-changed-input-$suffix", 2L, 205L)
                val operation = if (mode == 0) PlaylistMutationRequest("library-changed-create-$suffix", "library-changed-create-$suffix", 0L,
                    PlaylistMutation.Create("Changed preview", input), revision)
                else request("library-changed-add-$suffix", old, PlaylistMutation.Add(input)).copy(expectedLibraryRevision = revision)
                var reads = 0
                expectIssue(PlaylistIssue.SOURCE_CHANGED) {
                    repository.apply(operation, PlaylistPageSource { offset, limit ->
                        assertFalse(database.inTransaction())
                        reads += 1
                        val count = minOf(limit.toLong(), input.totalCount - offset).toInt()
                        val references = if (mode == 2) List(count) { reference(99) } else List(count) { reference(offset + it) }
                        val page = PlaylistInputPage(input, offset, references, offset + count == input.totalCount)
                        if (page.endReached) execute("UPDATE playlist_fixture_library SET $field = $field + 1")
                        page
                    })
                }
                checks += 1
                assertEquals(checks, verifier.libraryChecks)
                assertEquals(3, reads)
                assertEquals(old, repository.snapshot(old.playlistId))
                assertEquals(original, allMembers(old))
                assertEquals(overview, repository.overview())
                assertNull(database.playlistDao().receipt(operation.operationId))
                if (mode == 0) assertNull(database.playlistDao().head(operation.playlistId))
                while (repository.maintain()) { /* 已验证输入也只能在最终版本一致时发布。 */ }
                assertEquals(1L, scalar("SELECT COUNT(*) FROM playlist_members"))
            }
        }
        reopen()
        assertEquals(original, allMembers(old))
        integrity()
    }

    @Test(timeout = 30000L)
    fun aMatchingLibraryFenceCommitsAnImportedSnapshotThenReplaysWithoutRecheckingLaterLibraryChanges() = runBlocking {
        val verifier = useFenceVerifier()
        val fence = prepareFence()
        val revision = PlaylistLibraryRevision(fence.catalogRevision, fence.rulesRevision)
        val missing = PlaylistReference(placeholderId = "import-still-missing", issue = PlaylistReferenceIssue.UNMATCHED,
            relativePathHint = "music/missing.flac")
        val references = listOf(reference(1), missing)
        val input = FrozenPlaylistInput("matching-import-input", 4L, references.size.toLong())
        val operation = PlaylistMutationRequest("matching-import-create", "matching-import", 0L,
            PlaylistMutation.Create("Imported snapshot", input), revision)
        val first = repository.apply(operation, listSource(input, references))
        val old = checkNotNull(first.snapshot)
        val members = allMembers(old)
        assertEquals(1, verifier.libraryChecks)
        assertEquals(references, members.map { it.reference })
        execute("UPDATE playlist_fixture_library SET catalogRevision = catalogRevision + 1, rulesRevision = rulesRevision + 1")
        val renamed = checkNotNull(repository.apply(request("matching-import-later-name", old, PlaylistMutation.Rename("Later import name"))).snapshot)
        val replay = repository.apply(operation, PlaylistPageSource { _, _ -> throw AssertionError("receipt replay must not read") })
        assertTrue(replay.wasReplay)
        assertEquals(PlaylistOperationResult.CREATED, replay.result)
        assertEquals(first.appliedRevision, replay.appliedRevision)
        assertEquals(renamed, replay.snapshot)
        assertEquals(1, verifier.libraryChecks)
        reopen()
        assertTrue(repository.apply(operation).wasReplay)
        assertEquals(members, allMembers(renamed))
        integrity()
    }

    @Test(timeout = 30000L)
    fun aPersistedLibraryChangeAfterMetadataStagingRejectsRenameAtTheSameFinalHeadCas() = runBlocking {
        val fence = prepareFence()
        val revision = PlaylistLibraryRevision(fence.catalogRevision, fence.rulesRevision)
        val old = create("metadata-library-fence", references = listOf(reference(1), reference(2)))
        val members = allMembers(old)
        val overview = repository.overview()
        val real = database.playlistDao()
        val operationId = "metadata-fence-late-change"
        val changingDao = object : PlaylistDao by real {
            override suspend fun insertGeneration(generation: PlaylistGenerationEntity) {
                real.insertGeneration(generation)
                if (generation.operationId == operationId) {
                    assertTrue(database.inTransaction())
                    database.fenceDao().putLibrary(PlaylistFenceLibraryEntity(
                        catalogRevision = revision.catalogRevision + 1L, rulesRevision = revision.rulesRevision))
                }
            }
        }
        val verifier = useFenceVerifier(changingDao)
        val operation = request(operationId, old, PlaylistMutation.Rename("Must repreview")).copy(expectedLibraryRevision = revision)
        expectIssue(PlaylistIssue.SOURCE_CHANGED) { repository.apply(operation) }
        assertEquals(1, verifier.libraryChecks)
        assertEquals(old, repository.snapshot(old.playlistId))
        assertEquals(members, allMembers(old))
        assertEquals(overview, repository.overview())
        assertNull(real.receipt(operationId))
        assertEquals(revision.catalogRevision + 1L, database.fenceDao().library()?.catalogRevision)
        integrity()
    }

    @Test(timeout = 30000L)
    fun folderRequestsWithBothFencesRequireTheSameCatalogAndRulesAndVerifyBothAtCommit() = runBlocking {
        val verifier = useFenceVerifier()
        val fence = prepareFence()
        val revision = PlaylistLibraryRevision(fence.catalogRevision, fence.rulesRevision)
        val input = FrozenPlaylistInput("both-fence-input", 1L, 2L)
        val synchronization = PlaylistFolderSynchronization(PlaylistFolderBinding(fence.sourceId, "both-fence-node"), fence, input)
        val operation = PlaylistMutationRequest("both-fence-create", "both-fence-folder", 0L,
            PlaylistMutation.CreateFolder("Both fences", synchronization), revision, fence)
        for ((index, changed) in listOf(revision.copy(catalogRevision = revision.catalogRevision + 1L),
            revision.copy(rulesRevision = revision.rulesRevision + 1L)).withIndex()) {
            expectIssue(PlaylistIssue.INVALID_INPUT) {
                repository.apply(operation.copy(operationId = "inconsistent-fence-$index", expectedLibraryRevision = changed),
                    PlaylistPageSource { _, _ -> throw AssertionError("inconsistent fences must not read") })
            }
        }
        assertEquals(0, verifier.libraryChecks)
        assertEquals(0, verifier.folderChecks)
        val created = repository.apply(operation, generatedSource(input))
        assertEquals(PlaylistOperationResult.CREATED, created.result)
        assertEquals(1, verifier.libraryChecks)
        assertEquals(1, verifier.folderChecks)
        assertEquals(2L, created.snapshot?.memberCount)
        assertEquals(1L, scalar("SELECT COUNT(*) FROM playlist_receipts"))
        integrity()
    }

    @Test(timeout = 30000L)
    fun missingPersistedLibraryVersionRejectsACompleteInputAndSavedReceiptsBindBothRevisionFields() = runBlocking {
        val verifier = useFenceVerifier()
        val revision = PlaylistLibraryRevision(7L, 3L)
        val input = FrozenPlaylistInput("version-required-import", 1L, 2L)
        val operation = PlaylistMutationRequest("version-required-create", "version-required", 0L,
            PlaylistMutation.Create("Versioned import", input), revision)
        expectIssue(PlaylistIssue.SOURCE_UNAVAILABLE) { repository.apply(operation, generatedSource(input)) }
        assertEquals(1, verifier.libraryChecks)
        assertNull(database.playlistDao().head(operation.playlistId))
        assertNull(database.playlistDao().receipt(operation.operationId))
        prepareFence()
        val accepted = repository.apply(operation, generatedSource(input))
        assertEquals(2, verifier.libraryChecks)
        for (changed in listOf(revision.copy(catalogRevision = 8L), revision.copy(rulesRevision = 4L))) {
            expectIssue(PlaylistIssue.INVALID_INPUT) {
                repository.apply(operation.copy(expectedLibraryRevision = changed),
                    PlaylistPageSource { _, _ -> throw AssertionError("changed receipt fence must reject before reading") })
            }
        }
        expectIssue(PlaylistIssue.INVALID_INPUT) { repository.apply(operation.copy(expectedLibraryRevision = null)) }
        assertEquals(accepted.snapshot, repository.snapshot(operation.playlistId))
        assertEquals(2, verifier.libraryChecks)
        assertTrue(repository.apply(operation).wasReplay)
        assertEquals(2, verifier.libraryChecks)
        integrity()
    }

    @Test(timeout = 90000L)
    fun explicitSourceFenceRejectsLateSourceAuthorizationOrScanChangesOnCreateAddAndAllDuplicateReceiptPaths() = runBlocking {
        val verifier = useFenceVerifier()
        val old = create("explicit-source-kept", references = listOf(reference(99)))
        val original = allMembers(old)
        val overview = repository.overview()
        val changes = listOf(
            "UPDATE playlist_fixture_sources SET sourceRevision = sourceRevision + 1" to PlaylistIssue.SOURCE_CHANGED,
            "UPDATE playlist_fixture_sources SET authEpoch = authEpoch + 1" to PlaylistIssue.SOURCE_CHANGED,
            "UPDATE playlist_fixture_sources SET authorization = 'REVOKED'" to PlaylistIssue.SOURCE_UNAVAILABLE,
            "UPDATE playlist_fixture_scans SET status = 'FAILED'" to PlaylistIssue.INCOMPLETE,
            "UPDATE playlist_fixture_scans SET full = 0" to PlaylistIssue.INCOMPLETE,
            "DELETE FROM playlist_fixture_scans" to PlaylistIssue.SOURCE_CHANGED,
        )
        var checks = 0
        for (mode in 0..2) {
            for ((index, change) in changes.withIndex()) {
                val fence = prepareFence()
                val suffix = "$mode-$index"
                val input = FrozenPlaylistInput("source-fenced-input-$suffix", 2L, 101L)
                val operation = if (mode == 0) PlaylistMutationRequest("source-fenced-create-$suffix", "source-fenced-create-$suffix", 0L,
                    PlaylistMutation.Create("Source-matched import", input), expectedSourceFence = fence)
                else request("source-fenced-add-$suffix", old, PlaylistMutation.Add(input)).copy(expectedSourceFence = fence)
                var reads = 0
                expectIssue(change.second) {
                    repository.apply(operation, PlaylistPageSource { offset, limit ->
                        assertFalse(database.inTransaction())
                        reads += 1
                        val count = minOf(limit.toLong(), input.totalCount - offset).toInt()
                        val references = if (mode == 2) List(count) { reference(99) } else List(count) { reference(offset + it) }
                        val page = PlaylistInputPage(input, offset, references, offset + count == input.totalCount)
                        if (page.endReached) execute(change.first)
                        page
                    })
                }
                checks += 1
                assertEquals(checks, verifier.folderChecks)
                assertEquals(0, verifier.libraryChecks)
                assertEquals(2, reads)
                assertEquals(old, repository.snapshot(old.playlistId))
                assertEquals(original, allMembers(old))
                assertEquals(overview, repository.overview())
                assertNull(database.playlistDao().receipt(operation.operationId))
                if (mode == 0) assertNull(database.playlistDao().head(operation.playlistId))
                while (repository.maintain()) { /* 完整读到输入末页也不能省略来源/扫描提交围栏。 */ }
                assertEquals(1L, scalar("SELECT COUNT(*) FROM playlist_members"))
            }
        }
        integrity()
    }

    @Test(timeout = 30000L)
    fun explicitSourceFenceNeedsTheExistingVerifierAndRejectsInconsistentLibraryOrFolderFencesBeforeReading() = runBlocking {
        val fence = prepareFence()
        val revision = PlaylistLibraryRevision(fence.catalogRevision, fence.rulesRevision)
        val input = FrozenPlaylistInput("explicit-fence-required", 1L, 2L)
        val operation = PlaylistMutationRequest("explicit-fence-create", "explicit-fence-create", 0L,
            PlaylistMutation.Create("Source-fenced ordinary", input), expectedSourceFence = fence)
        val forbiddenSource = PlaylistPageSource { _, _ -> throw AssertionError("invalid or unavailable fence must reject before reading") }
        expectIssue(PlaylistIssue.SOURCE_UNAVAILABLE) { repository.apply(operation, forbiddenSource) }
        val onlyLibraryVerifier = PlaylistRoomFenceVerifier(database)
        repository = newRepository(libraryFenceVerifier = onlyLibraryVerifier)
        expectIssue(PlaylistIssue.SOURCE_UNAVAILABLE) {
            repository.apply(operation.copy(expectedLibraryRevision = revision), forbiddenSource)
        }
        assertEquals(0, onlyLibraryVerifier.libraryChecks)
        val verifier = useFenceVerifier()
        for ((index, inconsistent) in listOf(revision.copy(catalogRevision = revision.catalogRevision + 1L),
            revision.copy(rulesRevision = revision.rulesRevision + 1L)).withIndex()) {
            expectIssue(PlaylistIssue.INVALID_INPUT) {
                repository.apply(operation.copy(operationId = "explicit-inconsistent-library-$index",
                    expectedLibraryRevision = inconsistent), forbiddenSource)
            }
        }
        val synchronization = PlaylistFolderSynchronization(PlaylistFolderBinding(fence.sourceId, "explicit-folder-node"), fence, input)
        expectIssue(PlaylistIssue.INVALID_INPUT) {
            repository.apply(operation.copy(operationId = "explicit-inconsistent-folder", playlistId = "explicit-inconsistent-folder",
                mutation = PlaylistMutation.CreateFolder("Folder", synchronization),
                expectedSourceFence = fence.copy(sourceRevision = fence.sourceRevision + 1L)), forbiddenSource)
        }
        assertEquals(0, verifier.folderChecks)
        assertEquals(0, verifier.libraryChecks)
        assertEquals(PlaylistOverview(0L, 0L, 0L), repository.overview())
        assertEquals(0L, scalar("SELECT COUNT(*) FROM playlist_receipts"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM playlist_generations WHERE state != 'PUBLISHED'"))
        integrity()
    }

    @Test(timeout = 30000L)
    fun acceptedSourceFencedImportStaysOrdinaryAndAnAllDuplicateReceiptReplaysAfterRevocationRenameAndDeletion() = runBlocking {
        val verifier = useFenceVerifier()
        val fence = prepareFence()
        val revision = PlaylistLibraryRevision(fence.catalogRevision, fence.rulesRevision)
        val missing = PlaylistReference(placeholderId = "source-import-missing", issue = PlaylistReferenceIssue.UNMATCHED,
            relativePathHint = "music/missing.flac")
        val references = listOf(reference(1), missing)
        val input = FrozenPlaylistInput("source-import-complete", 1L, references.size.toLong())
        val create = PlaylistMutationRequest("source-import-create", "source-import", 0L,
            PlaylistMutation.Create("Source-matched ordinary", input), revision, fence)
        val old = checkNotNull(repository.apply(create, listSource(input, references)).snapshot)
        assertEquals(PlaylistKind.ORDINARY, old.kind)
        assertNull(old.folderBinding)
        val members = allMembers(old)
        val before = repository.overview()
        val duplicates = FrozenPlaylistInput("source-import-duplicates", 2L, 101L)
        val operation = request("source-import-no-change", old, PlaylistMutation.Add(duplicates))
            .copy(expectedLibraryRevision = revision, expectedSourceFence = fence)
        val commit = repository.apply(operation, listSource(duplicates, List(101) { reference(1) }))
        assertEquals(PlaylistOperationResult.UNCHANGED, commit.result)
        assertEquals(101L, commit.duplicateCount)
        assertEquals(old, commit.snapshot)
        assertEquals(before, repository.overview())
        assertEquals(members, allMembers(old))
        assertEquals(2, verifier.libraryChecks)
        assertEquals(2, verifier.folderChecks)
        execute("UPDATE playlist_fixture_sources SET authorization = 'REVOKED'")
        val renamed = checkNotNull(repository.apply(request("source-import-later-name", old, PlaylistMutation.Rename("Later"))).snapshot)
        val replay = repository.apply(operation)
        assertTrue(replay.wasReplay)
        assertEquals(PlaylistOperationResult.UNCHANGED, replay.result)
        assertEquals(101L, replay.duplicateCount)
        assertEquals(renamed, replay.snapshot)
        assertEquals(2, verifier.libraryChecks)
        assertEquals(2, verifier.folderChecks)
        repository.apply(request("source-import-later-delete", renamed, PlaylistMutation.Delete(true)))
        reopen()
        val deletedReplay = repository.apply(operation)
        assertTrue(deletedReplay.wasReplay)
        assertEquals(commit.appliedRevision, deletedReplay.appliedRevision)
        assertEquals(PlaylistOperationResult.UNCHANGED, deletedReplay.result)
        assertEquals(101L, deletedReplay.duplicateCount)
        assertNull(deletedReplay.snapshot)
        assertTrue(repository.apply(create).wasReplay)
        integrity()
    }

    @Test(timeout = 30000L)
    fun ordinaryImportReceiptsBindAllSixExplicitSourceFenceFieldsAndCannotDropThatFenceOnReplay() = runBlocking {
        val verifier = useFenceVerifier()
        val fence = prepareFence()
        val input = FrozenPlaylistInput("source-fingerprint-input", 1L, 2L)
        val operation = PlaylistMutationRequest("source-fingerprint-create", "source-fingerprint", 0L,
            PlaylistMutation.Create("Source fingerprint", input), expectedSourceFence = fence)
        val accepted = repository.apply(operation, generatedSource(input))
        val variants = listOf(
            fence.copy(catalogRevision = fence.catalogRevision + 1L),
            fence.copy(rulesRevision = fence.rulesRevision + 1L),
            fence.copy(sourceId = "another-source"),
            fence.copy(sourceRevision = fence.sourceRevision + 1L),
            fence.copy(authEpoch = fence.authEpoch + 1L),
            fence.copy(scanId = "another-scan"),
        )
        for (variant in variants) expectIssue(PlaylistIssue.INVALID_INPUT) {
            repository.apply(operation.copy(expectedSourceFence = variant),
                PlaylistPageSource { _, _ -> throw AssertionError("changed receipt fence must reject before reading") })
        }
        expectIssue(PlaylistIssue.INVALID_INPUT) { repository.apply(operation.copy(expectedSourceFence = null)) }
        assertEquals(1, verifier.folderChecks)
        assertEquals(accepted.snapshot, repository.snapshot(operation.playlistId))
        execute("DELETE FROM playlist_fixture_scans")
        repository = newRepository()
        assertTrue(repository.apply(operation).wasReplay)
        assertEquals(accepted.snapshot, repository.snapshot(operation.playlistId))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM playlist_receipts"))
        integrity()
    }
}
