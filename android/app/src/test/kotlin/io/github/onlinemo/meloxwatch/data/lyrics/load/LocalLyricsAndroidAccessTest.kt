package io.github.onlinemo.meloxwatch.data.lyrics.load

import android.content.Context
import io.github.onlinemo.meloxwatch.data.library.LocalReadDescriptor
import io.github.onlinemo.meloxwatch.data.local.AndroidLocalSources
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.local.fixtureAudioProvider
import io.github.onlinemo.meloxwatch.data.local.fixtureDescriptor
import io.github.onlinemo.meloxwatch.data.lyrics.EmbeddedLyricsBinaryFixture as Binary
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
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

/** Real resolver/descriptor/authorization paths; binary fixtures prove metadata, not audio decoding. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class LocalLyricsAndroidAccessTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun androidContentAdapterLoadsEmbeddedTextWithThePinnedFullAudioProof() = runBlocking {
        val audio = audioFile("local")
        val provider = fixtureAudioProvider("android-lyrics-audio", audio)
        val descriptor = fixtureDescriptor("android-lyrics-audio", audio)
        val io = LocalProviderIo()
        val sources = AndroidLocalSources(context(), io)
        val result = loaded(loader(io, sources, descriptor).load(request(descriptor,
            LyricsLoadSelection.Automatic(siblingScope = LyricsSiblingScope("ungranted-parent", "audio.mp3")))))
        assertEquals("local", result.document.sourceLines.single().text)
        assertEquals(LyricsSourceOrigin.EMBEDDED_PLAIN_TEXT, result.source.origin)
        assertEquals(LyricsSiblingStatus.SCOPE_UNAVAILABLE, result.siblingStatus)
        assertEquals(3, provider.audioReads)
        assertEquals(descriptor.verifiedContentToken, result.audio.verifiedContentToken)
    }

    @Test fun separatelyAuthorizedRealTextDocumentLoadsWithoutReadingItsParent() = runBlocking {
        val audio = audioFile("embedded")
        fixtureAudioProvider("android-lyrics-manual-audio", audio)
        val descriptor = fixtureDescriptor("android-lyrics-manual-audio", audio)
        val text = File(temporary.root, "selected.lrc").apply { writeText("[00:01]selected") }
        val provider = fixtureAudioProvider("android-lyrics-manual-text", text).apply { mimeType = "text/plain" }
        val io = LocalProviderIo()
        val sources = AndroidLocalSources(context(), io)
        val observed = sources.observe(LocalSourceSpec("manual-text", LocalSourceKind.DOCUMENT, "content://android-lyrics-manual-text/audio"))
        val input = LyricsDocumentInput("picker-text", 3L, observed.source, observed.root)
        val result = loaded(loader(io, sources, descriptor).load(request(descriptor, LyricsLoadSelection.TextDocument(input))))
        assertEquals(LyricsSourceOrigin.EXPLICIT_DOCUMENT, result.source.origin)
        assertEquals("selected", result.document.timeline.line(0).text)
        assertEquals(LyricsSiblingStatus.NOT_REQUESTED, result.siblingStatus)
        assertTrue(provider.audioReads >= 3)
        assertTrue(text.isFile)
    }

    @Test fun realWeakAudioHashChangeDuringTextAcquisitionRejectsTheResult() = runBlocking {
        val audio = audioFile("before")
        fixtureAudioProvider("android-lyrics-changing-audio", audio)
        val descriptor = fixtureDescriptor("android-lyrics-changing-audio", audio)
        val text = File(temporary.root, "changing.lrc").apply { writeText("[00:01]text") }
        val provider = fixtureAudioProvider("android-lyrics-changing-text", text).apply { mimeType = "text/plain" }
        val io = LocalProviderIo()
        val sources = AndroidLocalSources(context(), io)
        val observed = sources.observe(LocalSourceSpec("changing-text", LocalSourceKind.DOCUMENT, "content://android-lyrics-changing-text/audio"))
        val once = AtomicBoolean(false)
        provider.beforeQuery = { if (once.compareAndSet(false, true)) audio.writeBytes(tag("after!")) }
        val result = loader(io, sources, descriptor).load(request(descriptor,
            LyricsLoadSelection.TextDocument(LyricsDocumentInput("picker-change", 3L, observed.source, observed.root))))
        assertEquals(LyricsLoadIssue.SOURCE_CHANGED, failure(result).issue)
        assertEquals(SourceReadIssue.SOURCE_CHANGED, failure(result).sourceIssue)
        assertEquals(descriptor.sizeBytes, audio.length())
        assertTrue(text.isFile)
    }

    @Test fun realTextPermissionDenialIsVisibleAndDoesNotDeleteTheOriginal() = runBlocking {
        val audio = audioFile("local")
        fixtureAudioProvider("android-lyrics-denied-audio", audio)
        val descriptor = fixtureDescriptor("android-lyrics-denied-audio", audio)
        val text = File(temporary.root, "denied.lrc").apply { writeText("text") }
        val provider = fixtureAudioProvider("android-lyrics-denied-text", text).apply { mimeType = "text/plain" }
        val io = LocalProviderIo()
        val sources = AndroidLocalSources(context(), io)
        val observed = sources.observe(LocalSourceSpec("denied-text", LocalSourceKind.DOCUMENT, "content://android-lyrics-denied-text/audio"))
        provider.denied = true
        val result = loader(io, sources, descriptor).load(request(descriptor,
            LyricsLoadSelection.Automatic(LyricsDocumentInput("picker-denied", 3L, observed.source, observed.root))))
        assertEquals(LyricsLoadIssue.PERMISSION_DENIED, failure(result).issue)
        assertEquals(SourceReadIssue.PERMISSION_DENIED, failure(result).sourceIssue)
        assertTrue(text.isFile)
    }

    @Test fun adapterRejectsNetworkAndAbsoluteFileRoutesBeforeResolverAccess() = runBlocking {
        val io = LocalProviderIo()
        val opener = AndroidLocalLyricsStream(context().contentResolver)
        for (uri in listOf("https://example.invalid/lyrics.lrc", "file:///ignored.lrc")) {
            try {
                io.read { signal -> opener.open(uri, signal).use { } }
                throw AssertionError("Expected content URI only")
            } catch (error: LyricsLoadException) { assertEquals(LyricsLoadIssue.INVALID_INPUT, error.failure.issue) }
        }
    }

    private fun context(): Context = RuntimeEnvironment.getApplication()
    private fun audioFile(text: String): File = File(temporary.root, "audio.mp3").apply { writeBytes(tag(text)) }
    private fun tag(text: String): ByteArray = Binary.id3(Binary.frame("USLT", Binary.uslt(text)))
    private fun request(descriptor: LocalReadDescriptor, selection: LyricsLoadSelection = LyricsLoadSelection.Embedded()): LyricsLoadRequest = LyricsLoadRequest(
        LyricsLoadBinding(descriptor.trackKey, descriptor.location.locationId, descriptor.location.contentRevision, descriptor.source.spec.sourceId,
            descriptor.source.revision, descriptor.source.authEpoch, 2L, 3L), selection,
    )

    private fun loader(io: LocalProviderIo, sources: AndroidLocalSources, descriptor: LocalReadDescriptor): LocalLyricsLoader = LocalLyricsLoader(
        io, { _, _, _ -> descriptor }, sources::validateRead, sources, sources::verifyObservedCandidate,
        AndroidLocalLyricsStream(context().contentResolver), { true },
    )
}
