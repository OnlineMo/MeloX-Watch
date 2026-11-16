package io.github.onlinemo.meloxwatch.data.playlistsexchange

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import io.github.onlinemo.meloxwatch.data.library.PlaylistRecoveryRoomFixture
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.playlists.FrozenPlaylistInput
import io.github.onlinemo.meloxwatch.data.playlists.LocalPlaylistRepository
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistFolderBinding
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistInputPage
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutation
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutationRequest
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistPageSource
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistReference
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistSnapshot
import io.github.onlinemo.meloxwatch.ui.playlists.AuthorizedPlaylistDocument
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.robolectric.RuntimeEnvironment

/** Production Room/index/core plus a real file-backed DocumentsProvider; system grants remain a device gate. */
abstract class RoomPlaylistExchangeFixture : PlaylistRecoveryRoomFixture() {
    protected lateinit var context: Context
    protected lateinit var documentFile: File
    protected lateinit var provider: FixturePlaylistDocumentProvider
    protected lateinit var documentUri: Uri
    protected lateinit var providerIo: LocalProviderIo
    protected lateinit var registry: PlaylistDocumentRegistry
    protected lateinit var exchange: RoomPlaylistExchangeController

    @Before fun openExchangeProvider() {
        context = RuntimeEnvironment.getApplication()
        documentFile = temporaryFolder.newFile("exchange-document.txt")
        val authority = "${context.packageName}.playlist-exchange-fixture"
        provider = fixturePlaylistDocumentProvider(context, authority, documentFile)
        documentUri = DocumentsContract.buildDocumentUri(authority, FixturePlaylistDocumentProvider.DOCUMENT_ID)
        providerIo = LocalProviderIo()
        registry = PlaylistDocumentRegistry(context, providerIo)
        exchange = exchangeController()
    }

    protected fun exchangeController(port: LocalPlaylistRepository = playlists, cleanupTimeoutMs: Long = 5_000L) = RoomPlaylistExchangeController(
        context, database, port, library, registry, providerIo, { elapsed }, { epoch }, cleanupTimeoutMs,
    )

    protected suspend fun input(text: String, binding: PlaylistFolderBinding? = null): AuthorizedPlaylistDocument {
        documentFile.writeText(text, Charsets.UTF_8)
        return registry.registerImport(documentUri, READ, binding)
    }

    protected suspend fun output(operationId: String): AuthorizedPlaylistDocument =
        registry.registerExport(documentUri, READ_WRITE, operationId)

    protected suspend fun rootBinding() = PlaylistFolderBinding(source.spec.sourceId,
        checkNotNull(database.libraryDao().node(source.spec.sourceId, "root-object")).nodeId)

    protected fun knownM3u(name: String = "Imported playlist"): String = """
        #EXTM3U
        #PLAYLIST:$name
        #MELOX-TRACK-ID:${replacement.trackId}
        melox-unresolved:${exchangeTestId(1L)}
        #MELOX-TRACK-ID:${sameNamedDecoy.trackId}
        melox-unresolved:${exchangeTestId(2L)}
    """.trimIndent() + "\n"

    protected suspend fun createOrdinary(
        id: String = "export-playlist",
        references: List<PlaylistReference> = listOf(PlaylistReference(sameNamedDecoy), PlaylistReference(replacement)),
    ): PlaylistSnapshot {
        val frozen = FrozenPlaylistInput("input-$id", 0L, references.size.toLong())
        return checkNotNull(playlists.apply(PlaylistMutationRequest("create-$id", id, 0L,
            PlaylistMutation.Create("Manual playlist", frozen)), PlaylistPageSource { offset, limit ->
                assertFalse(database.inTransaction())
                val end = minOf(references.size.toLong(), offset + limit).toInt()
                PlaylistInputPage(frozen, offset, references.subList(offset.toInt(), end).toList(), end.toLong() == frozen.totalCount)
            }).snapshot)
    }

    protected fun assertProviderClosed() {
        assertEquals(provider.cursorCount.get(), provider.cursorCloseCount.get())
        assertTrue(provider.allDescriptorsClosed)
        assertEquals(0, provider.childQueryCount.get())
        assertEquals(0, provider.deleteCount.get())
    }

    protected fun assertNoPrivateStages() {
        val directory = File(context.noBackupFilesDir, "playlist-exchange")
        assertEquals(0, directory.listFiles()?.size ?: 0)
    }

    protected companion object {
        const val READ = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val READ_WRITE = READ or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
