package io.github.onlinemo.meloxwatch.data.lyrics.load

import android.content.Context
import android.os.CancellationSignal
import android.provider.DocumentsContract
import io.github.onlinemo.meloxwatch.data.library.LibrarySource
import io.github.onlinemo.meloxwatch.data.library.LibrarySourceState
import io.github.onlinemo.meloxwatch.data.library.LocalContentValidation
import io.github.onlinemo.meloxwatch.data.library.LocalReadDescriptor
import io.github.onlinemo.meloxwatch.data.local.AndroidLocalSources
import io.github.onlinemo.meloxwatch.data.local.ContentFingerprint
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceObservation
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.local.fixtureAudioProvider
import io.github.onlinemo.meloxwatch.data.local.fixtureDescriptor
import io.github.onlinemo.meloxwatch.data.lyrics.EmbeddedLyricsBinaryFixture as Binary
import io.github.onlinemo.meloxwatch.model.LocalLocationReference
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Actual Android TREE observations, cursor decoding and file opens; no AVAILABLE capability is injected. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class LocalLyricsTreeAccessTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun realTreeWithUnknownReadLoadsTheUniqueSiblingAfterExactAudioValidation() = runBlocking {
        val tree = tree("lyrics_tree_sibling", "[00:01]sibling")
        assertEquals(ObservedCapability.UNKNOWN, tree.observation.access.read)
        assertEquals(ObservedCapability.AVAILABLE, tree.observation.access.enumerate)
        val result = loaded(loader(tree, tree.audio).load(request(tree.audio,
            LyricsLoadSelection.Automatic(siblingScope = LyricsSiblingScope(LocalLyricsTreeProviderFixture.FOLDER, "Track.mp3")))))
        assertEquals(LyricsSiblingStatus.MATCHED, result.siblingStatus)
        assertEquals(LyricsSourceOrigin.SAME_NAME_LRC, result.source.origin)
        assertEquals(LocalLyricsTreeProviderFixture.LYRICS, result.source.objectId)
        assertEquals("sibling", result.document.timeline.line(0).text)
        assertEquals(LocalContentValidation.VERIFIED, result.audio.contentValidation)
        assertEquals(ObservedCapability.UNKNOWN, result.audio.source.grant.read)
        assertEquals(ObservedCapability.UNKNOWN, tree.sources.observe(tree.observation.source).access.read)
        assertEquals(2, tree.provider.openedDocumentIds.count { it == LocalLyricsTreeProviderFixture.AUDIO })
        assertEquals(2, tree.provider.openedDocumentIds.count { it == LocalLyricsTreeProviderFixture.LYRICS })
        assertClosed(tree)
    }

    @Test fun separatelySelectedTreeTextUsesItsActualDocumentDescriptor() = runBlocking {
        val tree = tree("lyrics_tree_selected", "[00:02]selected")
        val audio = separateAudio("lyrics_tree_selected_audio")
        val input = input(tree)
        val result = loaded(loader(tree, audio).load(request(audio, LyricsLoadSelection.TextDocument(input))))
        assertEquals(LyricsSourceOrigin.EXPLICIT_DOCUMENT, result.source.origin)
        assertEquals(input.documentId, result.source.documentId)
        assertEquals("selected", result.document.timeline.line(0).text)
        assertEquals(2_000L, result.document.timeline.line(0).startsAtMs)
        assertEquals(Binary.sha256(tree.lyrics.readBytes()), result.source.contentSha256)
        assertEquals(0, tree.provider.openedDocumentIds.count { it == LocalLyricsTreeProviderFixture.AUDIO })
        assertEquals(2, tree.provider.openedDocumentIds.count { it == LocalLyricsTreeProviderFixture.LYRICS })
        assertClosed(tree)
    }

    @Test fun associatedTreeTextCannotFallBackWhenItsActualOpenIsDenied() = runBlocking {
        val tree = tree("lyrics_tree_denied", "[00:01]kept")
        val input = input(tree)
        tree.provider.deniedDocumentId = LocalLyricsTreeProviderFixture.LYRICS
        val result = loader(tree, tree.audio).load(request(tree.audio, LyricsLoadSelection.Automatic(associatedDocument = input)))
        assertEquals(LyricsLoadIssue.PERMISSION_DENIED, failure(result).issue)
        assertEquals(SourceReadIssue.PERMISSION_DENIED, failure(result).sourceIssue)
        assertEquals(1, tree.provider.openedDocumentIds.count { it == LocalLyricsTreeProviderFixture.LYRICS })
        assertEquals(1, tree.provider.openedDocumentIds.count { it == LocalLyricsTreeProviderFixture.AUDIO })
        assertTrue(tree.lyrics.isFile)
        assertClosed(tree)
    }

    @Test fun treeEnumerationNeverSubstitutesForDeniedExactAudioRead() = runBlocking {
        val tree = tree("lyrics_tree_audio_denied", "[00:01]kept")
        val beforeRootQueries = tree.provider.rootChildQueries.get()
        val beforeFolderQueries = tree.provider.folderChildQueries.get()
        tree.provider.deniedDocumentId = LocalLyricsTreeProviderFixture.AUDIO
        val result = loader(tree, tree.audio).load(request(tree.audio,
            LyricsLoadSelection.Automatic(siblingScope = LyricsSiblingScope(LocalLyricsTreeProviderFixture.FOLDER, "Track.mp3"))))
        assertEquals(LyricsLoadIssue.PERMISSION_DENIED, failure(result).issue)
        assertEquals(SourceReadIssue.PERMISSION_DENIED, failure(result).sourceIssue)
        assertEquals(beforeRootQueries, tree.provider.rootChildQueries.get())
        assertEquals(beforeFolderQueries, tree.provider.folderChildQueries.get())
        assertEquals(listOf(LocalLyricsTreeProviderFixture.AUDIO), tree.provider.openedDocumentIds.toList())
        assertTrue(tree.lyrics.isFile)
        assertClosed(tree)
    }

    @Test fun weakTreeTextContentChangeIsRejectedByTheSecondCompleteHash() = runBlocking {
        val tree = tree("lyrics_tree_hash_change", "[00:01]a")
        val reads = AtomicInteger()
        tree.provider.beforeOpen = { id ->
            if (id == LocalLyricsTreeProviderFixture.LYRICS && reads.incrementAndGet() == 2) {
                tree.lyrics.writeText("[00:01]b", Charsets.UTF_8)
            }
        }
        val result = loader(tree, tree.audio).load(request(tree.audio, LyricsLoadSelection.TextDocument(input(tree))))
        assertEquals(LyricsLoadIssue.SOURCE_CHANGED, failure(result).issue)
        assertEquals(2, reads.get())
        assertEquals("[00:01]b", tree.lyrics.readText(Charsets.UTF_8))
        assertClosed(tree)
    }

    @Test fun unknownTreeWithoutPinnedAudioProofNeverReadsLyrics() = runBlocking {
        val tree = tree("lyrics_tree_audio_unknown", "[00:01]kept")
        val audio = tree.audio.copy(verifiedContentToken = null)
        val beforeFolderQueries = tree.provider.folderChildQueries.get()
        val result = loader(tree, audio).load(request(audio,
            LyricsLoadSelection.Automatic(siblingScope = LyricsSiblingScope(LocalLyricsTreeProviderFixture.FOLDER, "Track.mp3"))))
        assertEquals(LyricsLoadIssue.CONTENT_UNKNOWN, failure(result).issue)
        assertEquals(SourceReadIssue.CONTENT_UNKNOWN, failure(result).sourceIssue)
        assertEquals(beforeFolderQueries, tree.provider.folderChildQueries.get())
        assertEquals(listOf(LocalLyricsTreeProviderFixture.AUDIO), tree.provider.openedDocumentIds.toList())
        assertClosed(tree)
    }

    @Test fun selectedTreeTextCannotBypassAnExplicitPersistedReadRequirement() = runBlocking {
        val tree = tree("lyrics_tree_persistence", "[00:01]kept")
        val input = input(tree).copy(requirePersistedRead = true)
        assertEquals(ObservedCapability.UNAVAILABLE, tree.observation.access.persistedRead)
        val result = loader(tree, tree.audio).load(request(tree.audio, LyricsLoadSelection.TextDocument(input)))
        assertEquals(LyricsLoadIssue.PERMISSION_REVOKED, failure(result).issue)
        assertEquals(0, tree.provider.openedDocumentIds.count { it == LocalLyricsTreeProviderFixture.LYRICS })
        assertTrue(tree.lyrics.isFile)
        assertClosed(tree)
    }

    private suspend fun tree(name: String, text: String): Tree {
        val directory = temporary.newFolder(name)
        val audioFile = File(directory, "Track.mp3").apply { writeBytes(tag("fallback")) }
        val lyricsFile = File(directory, "Track.lrc").apply { writeText(text, Charsets.UTF_8) }
        val authority = "${context().packageName}.$name"
        val provider = fixtureLyricsTreeProvider(context(), authority, audioFile, lyricsFile)
        val io = LocalProviderIo()
        val sources = AndroidLocalSources(context(), io)
        val uri = DocumentsContract.buildTreeDocumentUri(authority, LocalLyricsTreeProviderFixture.ROOT)
        val observed = sources.observe(LocalSourceSpec(name, LocalSourceKind.TREE, uri.toString()))
        val cursor = sources.open(observed.source, LocalLyricsTreeProviderFixture.FOLDER)
        val rows = try { cursor.nextBatch() } finally { cursor.closeAndAwait() }
        val audioRow = rows.single { it.objectId == LocalLyricsTreeProviderFixture.AUDIO }
        val lyricsRow = rows.single { it.objectId == LocalLyricsTreeProviderFixture.LYRICS }
        val fingerprint = audioFile.inputStream().use { ContentFingerprint.read(it, CancellationSignal(), audioFile.length()) }
        val descriptor = LocalReadDescriptor(
            TrackKey("local", name), LibrarySource(observed.source, observed.access, "fixture-grant", 1L, 1L, LibrarySourceState.ACTIVE, null, null),
            "fixture-alias", LocalLocationReference("location:$name", audioRow.uri, uri.toString(), contentRevision = 1L),
            audioRow.objectId, audioRow.providerContentToken, audioRow.sizeBytes, verifiedContentToken = checkNotNull(fingerprint),
        )
        return Tree(io, sources, provider, observed, descriptor, lyricsRow, lyricsFile)
    }

    private fun input(tree: Tree): LyricsDocumentInput = LyricsDocumentInput("selected-tree-lyrics", 3L, tree.observation.source, tree.lyricsRow)

    private fun separateAudio(authority: String): LocalReadDescriptor {
        val file = temporary.newFile("$authority.mp3").apply { writeBytes(tag("other audio")) }
        fixtureAudioProvider(authority, file)
        return fixtureDescriptor(authority, file)
    }

    private fun loader(tree: Tree, audio: LocalReadDescriptor): LocalLyricsLoader = LocalLyricsLoader(
        tree.io, { _, _, _ -> audio }, tree.sources::validateRead, tree.sources, tree.sources::verifyObservedCandidate,
        AndroidLocalLyricsStream(context().contentResolver), { true },
    )

    private fun request(audio: LocalReadDescriptor, selection: LyricsLoadSelection): LyricsLoadRequest = LyricsLoadRequest(
        LyricsLoadBinding(audio.trackKey, audio.location.locationId, audio.location.contentRevision, audio.source.spec.sourceId,
            audio.source.revision, audio.source.authEpoch, 2L, 3L), selection,
    )

    private fun assertClosed(tree: Tree) {
        assertEquals(tree.provider.cursorCount.get(), tree.provider.cursorCloseCount.get())
        assertTrue(tree.provider.allDescriptorsClosed)
        assertTrue(tree.provider.openModes.all { it == "r" })
    }

    private fun context(): Context = RuntimeEnvironment.getApplication()
    private fun tag(text: String): ByteArray = Binary.id3(Binary.frame("USLT", Binary.uslt(text)))

    private data class Tree(
        val io: LocalProviderIo,
        val sources: AndroidLocalSources,
        val provider: LocalLyricsTreeProviderFixture,
        val observation: LocalSourceObservation,
        val audio: LocalReadDescriptor,
        val lyricsRow: ObservedCandidate,
        val lyrics: File,
    )
}
