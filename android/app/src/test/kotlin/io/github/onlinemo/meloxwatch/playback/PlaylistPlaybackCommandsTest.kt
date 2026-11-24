package io.github.onlinemo.meloxwatch.playback

import android.os.Bundle
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistFolderBinding
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistKind
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistQueueCatalogToken
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistSnapshot
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistViewOwner
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistViewToken
import io.github.onlinemo.meloxwatch.model.QueueMode
import io.github.onlinemo.meloxwatch.model.QueueRepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 真Bundle边界绑定完整冻结快照；原始引用或错误类型不能扩大队列输入。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class PlaylistPlaybackCommandsTest {
    @Test(timeout = 30_000L)
    fun allPlaylistKindsActionsAndExactMemberSelectionsRoundTripTheFullSnapshot() {
        for (kind in PlaylistKind.entries) {
            for (action in PlaylistQueueAction.entries) {
                for (selected in listOf(false, true)) {
                    val command = command(kind, action, selected)
                    val arguments = PlaybackCommands.playlistArguments(command)
                    assertEquals(command, PlaybackCommands.readPlaylist(arguments))
                    assertFalse(arguments.containsKey("entries"))
                    assertFalse(arguments.containsKey("uri"))
                    assertFalse(arguments.containsKey("track"))
                    assertEquals(20_005L, arguments.getLong("view_count"))
                    if (selected) assertEquals("exact-member", arguments.getString("member"))
                }
            }
        }
    }

    @Test(timeout = 30_000L)
    fun aLongCountAndUnstartedQueueStayExactWithoutAnIntOrProductListCap() {
        val original = command().copy(
            view = command().view.copy(snapshot = command().view.snapshot.copy(memberCount = Long.MAX_VALUE)),
            expectedGenerationId = null,
            expectedQueueRevision = 0L,
            expectedCurrentEntryId = null,
            selection = null,
        )
        val decoded = PlaybackCommands.readPlaylist(PlaybackCommands.playlistArguments(original))
        assertEquals(original, decoded)
        assertEquals(Long.MAX_VALUE, decoded.view.snapshot.memberCount)
        assertNull(decoded.expectedGenerationId)
        assertNull(decoded.selection)
        val last = original.copy(selection = PlaylistQueueSelection("last-member", Long.MAX_VALUE - 1L))
        assertEquals(last, PlaybackCommands.readPlaylist(PlaybackCommands.playlistArguments(last)))
    }

    @Test(timeout = 30_000L)
    fun missingWrongAndNegativePrimitiveFieldsCannotBecomeDefaultValues() {
        val mutations: List<Pair<String, Bundle.() -> Unit>> = listOf(
            "missing schema" to { remove("schema") },
            "String schema" to { putString("schema", "2") },
            "old schema" to { putInt("schema", 1) },
            "future schema" to { putInt("schema", 3) },
            "missing view revision" to { remove("view_revision") },
            "Int view revision" to { putInt("view_revision", 7) },
            "negative view revision" to { putLong("view_revision", -1L) },
            "missing list count" to { remove("view_count") },
            "Int list count" to { putInt("view_count", 20_005) },
            "empty list" to { putLong("view_count", 0L) },
            "negative list count" to { putLong("view_count", -1L) },
            "Int catalog revision" to { putInt("catalog_revision", 9) },
            "negative catalog revision" to { putLong("catalog_revision", -1L) },
            "missing rules revision" to { remove("rules_revision") },
            "negative rules revision" to { putLong("rules_revision", -1L) },
            "String queue revision" to { putString("revision", "0") },
            "negative queue revision" to { putLong("revision", -1L) },
            "missing creation epoch" to { remove("playlist_created") },
            "Int update epoch" to { putInt("playlist_updated", 2) },
            "negative creation epoch" to { putLong("playlist_created", -1L) },
            "missing mode" to { remove("shuffle") },
            "Int shuffle" to { putInt("shuffle", 1) },
            "unknown repeat" to { putString("repeat", "UNKNOWN") },
            "Long cycle limit" to { putLong("cycle_limit", 2L) },
            "zero cycle limit" to { putInt("cycle_limit", 0) },
            "oversized cycle limit" to { putInt("cycle_limit", 100) },
        )
        for ((label, mutation) in mutations) {
            rejects(label) { PlaybackCommands.readPlaylist(PlaybackCommands.playlistArguments(command()).apply(mutation)) }
        }
    }

    @Test(timeout = 30_000L)
    fun malformedIdentitiesOwnerAndMetadataCannotReplaceAnExactPlaylistView() {
        val mutations: List<Pair<String, Bundle.() -> Unit>> = listOf(
            "missing playlist" to { remove("playlist") },
            "blank playlist" to { putString("playlist", " ") },
            "oversized view" to { putString("view", "x".repeat(129)) },
            "array generation" to { putByteArray("playlist_generation", byteArrayOf(1)) },
            "missing owner" to { remove("playlist_owner") },
            "adopted owner from UI" to { putString("playlist_owner", "QUEUE") },
            "unknown kind" to { putString("playlist_kind", "ONLINE") },
            "missing full name" to { remove("playlist_name") },
            "Boolean name" to { putBoolean("playlist_name", true) },
            "oversized name" to { putString("playlist_name", "曲".repeat(4097)) },
            "blank catalog" to { putString("catalog", "") },
            "wrong operation" to { putLong("operation", 1L) },
            "blank queue generation" to { putString("generation", "") },
            "wrong current entry" to { putBoolean("current_entry", false) },
            "unsupported action" to { putString("action", "PLAY_CURRENT_PAGE") },
        )
        for ((label, mutation) in mutations) {
            rejects(label) { PlaybackCommands.readPlaylist(PlaybackCommands.playlistArguments(command()).apply(mutation)) }
        }
    }

    @Test(timeout = 30_000L)
    fun aFolderBindingIsCompleteAndNeverSmuggledIntoAnOrdinaryOrFavoritesSnapshot() {
        val mutations: List<Pair<String, Bundle.() -> Unit>> = listOf(
            "missing source" to { remove("folder_source") },
            "missing node" to { remove("folder_node") },
            "missing subtree" to { remove("folder_subtree") },
            "wrong source type" to { putInt("folder_source", 1) },
            "blank node" to { putString("folder_node", " ") },
            "wrong subtree type" to { putString("folder_subtree", "true") },
            "ordinary with binding" to { putString("playlist_kind", "ORDINARY") },
            "favorites with binding" to { putString("playlist_kind", "FAVORITES") },
        )
        for ((label, mutation) in mutations) {
            rejects(label) {
                PlaybackCommands.readPlaylist(PlaybackCommands.playlistArguments(command(PlaylistKind.FOLDER)).apply(mutation))
            }
        }
        val noBinding = PlaybackCommands.playlistArguments(command()).apply { putString("playlist_kind", "FOLDER") }
        rejects("folder without its actual binding") { PlaybackCommands.readPlaylist(noBinding) }
    }

    @Test(timeout = 30_000L)
    fun memberAndQueueFenceCombinationsCannotSilentlyChangeTheSelectionOrIntent() {
        val mutations: List<Pair<String, Bundle.() -> Unit>> = listOf(
            "missing member" to { remove("member") },
            "missing member ordinal" to { remove("selected_order") },
            "null member" to { putString("member", null) },
            "negative member ordinal" to { putLong("selected_order", -1L) },
            "past-last ordinal" to { putLong("selected_order", 20_005L) },
            "Int member ordinal" to { putInt("selected_order", 20_004) },
            "no generation with known revision" to { remove("generation") },
        )
        for ((label, mutation) in mutations) {
            rejects(label) { PlaybackCommands.readPlaylist(PlaybackCommands.playlistArguments(command()).apply(mutation)) }
        }
        val noGeneration = PlaybackCommands.playlistArguments(command()).apply {
            remove("generation")
            putLong("revision", 0L)
        }
        rejects("current entry without a queue generation") { PlaybackCommands.readPlaylist(noGeneration) }
    }

    @Test(timeout = 30_000L)
    fun rawResourcesAndMemberArraysAreRejectedByThePlaylistOnlyProtocol() {
        val mutations: List<Pair<String, Bundle.() -> Unit>> = listOf(
            "raw URI" to { putString("uri", "content://test/untrusted") },
            "grant URI" to { putString("grant_uri", "content://test/tree") },
            "member array" to { putStringArray("entries", arrayOf("one", "two")) },
            "guessed stable track" to { putString("track", "guessed") },
            "future instruction" to { putString("instruction", "replace latest") },
        )
        for ((label, mutation) in mutations) {
            rejects(label) { PlaybackCommands.readPlaylist(PlaybackCommands.playlistArguments(command()).apply(mutation)) }
        }
    }

    @Test(timeout = 30_000L)
    fun decodedSnapshotBindingSelectionAndModeStayFrozenAfterCallerBundleChanges() {
        val original = command(PlaylistKind.FOLDER)
        val arguments = PlaybackCommands.playlistArguments(original)
        val decoded = PlaybackCommands.readPlaylist(arguments)
        arguments.putString("playlist_name", "Later name")
        arguments.putString("playlist_generation", "Later generation")
        arguments.putString("folder_node", "Later root")
        arguments.putLong("playlist_updated", 999L)
        arguments.putLong("catalog_revision", 999L)
        arguments.putString("member", "Later member")
        arguments.putLong("selected_order", 1L)
        arguments.putBoolean("shuffle", false)
        arguments.putString("operation", "Later operation")
        assertEquals(original, decoded)
    }

    @Test(timeout = 30_000L)
    fun operationReceiptsRequireAnExactTypedIdentityAndCurrentSchema() {
        assertEquals("accepted-operation", PlaybackCommands.readReceiptOperation(
            PlaybackCommands.operationReceiptArguments("accepted-operation"),
        ))
        val mutations: List<Bundle.() -> Unit> = listOf(
            { remove("schema") }, { putInt("schema", 1) }, { putString("schema", "2") },
            { remove("operation") }, { putString("operation", "") },
            { putString("operation", "x".repeat(129)) }, { putBoolean("operation", true) },
        )
        for (mutation in mutations) {
            rejects("malformed operation receipt") {
                PlaybackCommands.readReceiptOperation(PlaybackCommands.operationReceiptArguments("accepted-operation").apply(mutation))
            }
        }
    }

    private fun command(
        kind: PlaylistKind = PlaylistKind.ORDINARY,
        action: PlaylistQueueAction = PlaylistQueueAction.REPLACE_COMPLETE_LIST,
        selected: Boolean = true,
    ): PlaylistQueueCommand = PlaylistQueueCommand(
        view = PlaylistViewToken("visible-playlist", PlaylistSnapshot(
            "playlist-id", "saved-members", 7L, "Saved playlist name", kind, 20_005L, 1L, 2L,
            if (kind == PlaylistKind.FOLDER) PlaylistFolderBinding("authorized-source", "indexed-node", subtree = false) else null,
        ), PlaylistViewOwner.UI),
        catalog = PlaylistQueueCatalogToken("unfiltered-catalog", 9L, 3L),
        operationId = "playlist-operation",
        expectedGenerationId = "queue-generation",
        expectedQueueRevision = 42L,
        expectedCurrentEntryId = "current-instance",
        selection = if (selected) PlaylistQueueSelection("exact-member", 20_004L) else null,
        mode = QueueMode(true, QueueRepeatMode.SINGLE, 2),
        action = action,
    )

    private fun rejects(label: String, operation: () -> Unit) {
        try {
            operation()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("Playlist protocol accepted " + label)
    }
}
