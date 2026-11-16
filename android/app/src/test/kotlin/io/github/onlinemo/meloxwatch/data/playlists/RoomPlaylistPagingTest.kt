package io.github.onlinemo.meloxwatch.data.playlists

import io.github.onlinemo.meloxwatch.data.database.withPreservedTransaction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RoomPlaylistPagingTest : PlaylistRoomFixture() {
    @Test(timeout = 120000L)
    fun theComplete10005MemberListUsesBoundedInputAndOutputPagesAndPersistsItsTailAcrossReopen() = runBlocking {
        val input = FrozenPlaylistInput("full-10005", 7L, 10_005L)
        val reads = ArrayList<Pair<Long, Int>>()
        val normal = generatedSource(input)
        val created = checkNotNull(repository.apply(PlaylistMutationRequest("create-full-10005", "large", 0L,
            PlaylistMutation.Create("Complete list", input)), PlaylistPageSource { offset, limit ->
                reads.add(offset to limit)
                normal.read(offset, limit)
            }).snapshot)
        assertEquals(10_005L, created.memberCount)
        assertEquals(101, reads.size)
        assertTrue(reads.all { it.second in 1..100 })
        assertEquals(10_000L to 5, reads.last())
        val view = repository.openView(created.playlistId)
        val memberIds = HashSet<String>()
        var first = 0L
        var count = 0L
        do {
            val page = repository.page(view, first)
            assertTrue(page.members.size <= 100)
            for (member in page.members) {
                assertEquals(count, member.ordinal)
                assertEquals("track-$count", member.reference.trackKey?.trackId)
                assertTrue(memberIds.add(member.memberId))
                count += 1L
            }
            first = page.nextOrdinal ?: break
        } while (true)
        assertEquals(10_005L, count)
        assertEquals(10_005, memberIds.size)
        val tail = repository.page(view, 10_000L)
        assertEquals(5, tail.members.size)
        assertEquals("track-10004", tail.members.last().reference.trackKey?.trackId)
        assertNull(tail.nextOrdinal)
        repository.closeView(view.viewId)
        reopen()
        assertEquals(created, repository.snapshot(created.playlistId))
        val restored = repository.openView(created.playlistId)
        assertEquals(tail.members, repository.page(restored, 10_000L).members)
        repository.closeView(restored.viewId)
        assertEquals(10_005L, scalar("SELECT COUNT(*) FROM playlist_members"))
        integrity()
    }

    @Test(timeout = 120000L)
    fun catalogKeysetsKeepAll105OriginalListsDespiteConcurrentRenameDeleteAndCreate() = runBlocking {
        for (index in 0 until 105) create("list-" + index.toString().padStart(3, '0'), "Original $index")
        val view = repository.openCatalogView()
        assertEquals(105L, view.totalCount)
        val first = repository.catalogPage(view)
        assertEquals(100, first.playlists.size)
        val cursor = checkNotNull(first.nextCursor)
        val originalRename = checkNotNull(repository.snapshot("list-020"))
        repository.apply(request("rename-during-catalog", originalRename, PlaylistMutation.Rename("Changed later")))
        val originalDelete = checkNotNull(repository.snapshot("list-100"))
        repository.apply(request("delete-during-catalog", originalDelete, PlaylistMutation.Delete(true)))
        create("list-000-new", "Created later")
        val second = repository.catalogPage(view, cursor)
        assertEquals(5, second.playlists.size)
        assertNull(second.nextCursor)
        val frozen = first.playlists + second.playlists
        assertEquals(105, frozen.map { it.playlistId }.distinct().size)
        assertEquals("Original 20", frozen.single { it.playlistId == "list-020" }.name)
        assertEquals(originalDelete, frozen.single { it.playlistId == "list-100" })
        assertFalse(frozen.any { it.playlistId == "list-000-new" || it.kind == PlaylistKind.FAVORITES })
        val deletedMembers = repository.openView("list-100", fromCatalog = view)
        assertEquals(originalDelete, deletedMembers.snapshot)
        assertTrue(repository.page(deletedMembers).members.isEmpty())
        repository.closeView(deletedMembers.viewId)
        expectIssue(PlaylistIssue.INVALID_INPUT) { repository.catalogPage(view, cursor.copy(revision = cursor.revision + 1L)) }
        val currentView = repository.openCatalogView()
        val currentFirst = repository.catalogPage(currentView)
        val currentSecond = repository.catalogPage(currentView, currentFirst.nextCursor)
        val current = currentFirst.playlists + currentSecond.playlists
        assertEquals(105, current.size)
        assertFalse(current.any { it.playlistId == "list-100" })
        assertTrue(current.any { it.playlistId == "list-000-new" })
        assertEquals("Changed later", current.single { it.playlistId == "list-020" }.name)
        repository.closeView(currentView.viewId)
        repository.closeView(view.viewId)
        integrity()
    }

    @Test(timeout = 30000L)
    fun adoptedQueueViewPinsDeletedMembersIndependentlyOfUiAndReclamationDeletesAtMost100PerCall() = runBlocking {
        val old = create("leased", references = List(305) { reference(it.toLong()) })
        val ui = repository.openView(old.playlistId)
        val original = allMembers(ui)
        val queue = repository.adoptView(ui.viewId)
        assertEquals(PlaylistViewOwner.QUEUE, queue.owner)
        assertNotEquals(ui.viewId, queue.viewId)
        repository.apply(request("delete-leased", old, PlaylistMutation.Delete(true)))
        repository.closeView(ui.viewId)
        assertFalse(repository.maintain())
        assertEquals(original, allMembers(queue))
        assertEquals(305L, scalar("SELECT COUNT(*) FROM playlist_members"))
        repository.closeView(queue.viewId)
        var iterations = 0
        do {
            val beforeMembers = scalar("SELECT COUNT(*) FROM playlist_members")
            val beforeGenerations = scalar("SELECT COUNT(*) FROM playlist_generations")
            val changed = repository.maintain()
            val afterMembers = scalar("SELECT COUNT(*) FROM playlist_members")
            val afterGenerations = scalar("SELECT COUNT(*) FROM playlist_generations")
            assertTrue(beforeMembers - afterMembers in 0L..100L)
            assertTrue(beforeGenerations - afterGenerations in 0L..1L)
            iterations += 1
            if (!changed) break
        } while (iterations < 10)
        assertTrue(iterations < 10)
        assertEquals(0L, scalar("SELECT COUNT(*) FROM playlist_members"))
        assertNull(database.playlistDao().generation(old.generationId))
        assertEquals(2L, scalar("SELECT COUNT(*) FROM playlist_receipts"))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM playlist_generations"))
        integrity()
    }

    @Test(timeout = 30000L)
    fun repeatedMetadataRenamesShareTheOriginal300RowsAndHistoricalViewsRemainStableThroughDeletion() = runBlocking {
        val old = create("shared-data", "Original", List(300) { reference(it.toLong()) })
        val view = repository.openView(old.playlistId)
        val original = allMembers(view)
        var current = old
        for (index in 0 until 20) {
            current = checkNotNull(repository.apply(request("metadata-rename-$index", current,
                PlaylistMutation.Rename("Name $index"))).snapshot)
            assertEquals(300L, scalar("SELECT COUNT(*) FROM playlist_members"))
            assertEquals(0L, scalar("SELECT COUNT(*) FROM playlist_members WHERE generationId != ?", old.generationId))
            assertEquals(old.generationId, database.playlistDao().generation(current.generationId)?.dataGenerationId)
        }
        assertEquals(old, view.snapshot)
        assertEquals(original, allMembers(view))
        assertEquals(original, allMembers(current))
        repository.apply(request("delete-shared-data", current, PlaylistMutation.Delete(true)))
        while (repository.maintain()) { /* 历史view仍固定原成员，其他无引用header可以回收。 */ }
        assertEquals(300L, scalar("SELECT COUNT(*) FROM playlist_members"))
        assertEquals(original, allMembers(view))
        repository.closeView(view.viewId)
        while (repository.maintain()) { /* 释放最后引用后按有界批次清理。 */ }
        assertEquals(0L, scalar("SELECT COUNT(*) FROM playlist_members"))
        assertEquals(22L, scalar("SELECT COUNT(*) FROM playlist_receipts"))
        integrity()
    }

    @Test(timeout = 30000L)
    fun viewOwnerLimitsTokenValidationExpiryAndProcessRestartNeverSilentlyChangeASelection() = runBlocking {
        val old = create("view-limits", references = listOf(reference(1), reference(2)))
        val catalog = repository.openCatalogView()
        val first = repository.openView(old.playlistId)
        val second = repository.openView(old.playlistId)
        expectIssue(PlaylistIssue.BUSY) { repository.openView(old.playlistId) }
        val queue = repository.openView(old.playlistId, PlaylistViewOwner.QUEUE)
        expectIssue(PlaylistIssue.BUSY) { repository.adoptView(first.viewId) }
        expectIssue(PlaylistIssue.INVALID_INPUT) { repository.page(first.copy(owner = PlaylistViewOwner.QUEUE)) }
        expectIssue(PlaylistIssue.INVALID_INPUT) { repository.page(first.copy(snapshot = first.snapshot.copy(name = "Forged"))) }
        expectIssue(PlaylistIssue.INVALID_INPUT) { repository.page(first, limit = 101) }
        expectIssue(PlaylistIssue.INVALID_INPUT) { repository.page(first, firstOrdinal = 3L) }
        repository.closeView(queue.viewId)
        val adopted = repository.adoptView(first.viewId)
        repository.closeView(first.viewId)
        assertEquals(old, adopted.snapshot)
        assertEquals(2, repository.page(adopted).members.size)
        elapsed += PlaylistLimits.VIEW_LEASE_MS
        expectIssue(PlaylistIssue.VIEW_EXPIRED) { repository.page(second) }
        expectIssue(PlaylistIssue.VIEW_EXPIRED) { repository.page(adopted) }
        expectIssue(PlaylistIssue.VIEW_EXPIRED) { repository.catalogPage(catalog) }
        val fresh = repository.openView(old.playlistId)
        val freshCatalog = repository.openCatalogView()
        assertEquals(2L, scalar("SELECT COUNT(*) FROM playlist_views"))
        reopen("different-process")
        expectIssue(PlaylistIssue.VIEW_EXPIRED) { repository.page(fresh) }
        expectIssue(PlaylistIssue.VIEW_EXPIRED) { repository.catalogPage(freshCatalog) }
        assertEquals(0L, scalar("SELECT COUNT(*) FROM playlist_views"))
        assertEquals(old, repository.snapshot(old.playlistId))
        integrity()
    }

    @Test(timeout = 30000L)
    fun folderBindingsPersistAndManualAddOrRemoveIsRejectedWithoutChangingTheSeededCompleteMembership() = runBlocking {
        // 这个fixture只核自动成员边界；真实FULL扫描同步由曲库集成测试核验。
        val binding = PlaylistFolderBinding("folder-source", "folder-node", true)
        database.withPreservedTransaction {
            val dao = database.playlistDao()
            val meta = checkNotNull(dao.meta())
            val global = meta.revision + 1L
            dao.insertGeneration(PlaylistGenerationEntity("folder-generation", "folder", "folder-generation", "seed-folder",
                "fixture", "PUBLISHED", 1L, "Folder", "folder", PlaylistKind.FOLDER.name,
                epoch, epoch, 2L, global, null, process, binding.sourceId, binding.nodeId, binding.subtree))
            dao.insertMembers(listOf(reference(1).record("folder-generation", "folder-member-1", 0L),
                reference(2).record("folder-generation", "folder-member-2", 1L)))
            dao.insertHead(PlaylistHeadEntity("folder", "folder-generation", 1L, meta.nextPlaylistSequence))
            dao.updateMeta(meta.copy(revision = global, nextPlaylistSequence = meta.nextPlaylistSequence + 1L))
        }
        val folder = checkNotNull(repository.snapshot("folder"))
        val members = allMembers(folder)
        val input = FrozenPlaylistInput("manual-folder-input", 0L, 1L)
        expectIssue(PlaylistIssue.AUTOMATIC_MEMBERS) {
            repository.apply(request("manual-folder-add", folder, PlaylistMutation.Add(input)), generatedSource(input))
        }
        expectIssue(PlaylistIssue.AUTOMATIC_MEMBERS) {
            repository.apply(request("manual-folder-remove", folder, PlaylistMutation.Remove(members.first().memberId, true)))
        }
        val renamed = checkNotNull(repository.apply(request("folder-rename", folder, PlaylistMutation.Rename("User folder name"))).snapshot)
        assertEquals(binding, renamed.folderBinding)
        assertEquals(members, allMembers(renamed))
        reopen()
        assertEquals(binding, repository.snapshot(folder.playlistId)?.folderBinding)
        assertEquals(members, allMembers(renamed))
        integrity()
    }
}
