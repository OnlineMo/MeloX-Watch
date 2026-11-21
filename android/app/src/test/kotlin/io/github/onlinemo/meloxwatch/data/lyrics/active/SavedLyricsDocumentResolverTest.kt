package io.github.onlinemo.meloxwatch.data.lyrics.active

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.Process
import android.provider.OpenableColumns
import io.github.onlinemo.meloxwatch.data.local.LocalCandidateCursor
import io.github.onlinemo.meloxwatch.data.local.LocalGrantObservation
import io.github.onlinemo.meloxwatch.data.local.LocalNodeKind
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.LocalSourceAccess
import io.github.onlinemo.meloxwatch.data.local.LocalSourceObservation
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsEncodingChoice
import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsLoadIssue
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsAssociation
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class SavedLyricsDocumentResolverTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var access: DocumentAccess
    private lateinit var copies: LyricsDocumentCopies
    private lateinit var resolver: SavedLyricsDocumentResolver
    private val bytes = "[00:01]selected lyrics".toByteArray()
    private var verifications = 0
    private var failVerificationAt = 0

    @Before fun setup() {
        val root = temporary.newFolder("private-lyrics-resolver")
        context = object : ContextWrapper(RuntimeEnvironment.getApplication() as Context) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        access = DocumentAccess()
        copies = LyricsDocumentCopies(context, LocalProviderIo(), openInput = { _, _ -> ByteArrayInputStream(bytes) },
            freeBytes = { 1_073_741_824L }, syncDirectory = {})
        resolver = newResolver()
    }

    @Test fun previewRequiresTheActualReadFlagAndRejectsInvalidRoutesBeforeAccess(): Unit = runBlocking {
        expect(LyricsLoadIssue.PERMISSION_DENIED) {
            resolver.preview(URI, 0, 1L, LyricsEncodingChoice.AUTO_BOM_OR_UTF8)
        }
        for (uri in listOf("https://example.invalid/lyrics", "content://lyrics.resolver.fixture/file?path=one",
            "content://lyrics.resolver.fixture/file#one", "content://lyrics.resolver.fixture/one\n")) {
            expect(LyricsLoadIssue.INVALID_INPUT) {
                resolver.preview(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION, 1L, LyricsEncodingChoice.AUTO_BOM_OR_UTF8)
            }
        }
        assertEquals(0, access.observations)
        val preview = resolver.preview(URI, Intent.FLAG_GRANT_READ_URI_PERMISSION, 1L, LyricsEncodingChoice.GB18030)
        assertEquals("external-one", preview.documentId)
        assertEquals(1L, preview.sourceGeneration)
        assertEquals(LyricsEncodingChoice.GB18030, preview.encoding)
        assertFalse(preview.requirePersistedRead)
        assertEquals(1, access.observations)
        assertTrue(checkNotNull(OwnedLyricsProvider.root(context).list()).isEmpty())
    }

    @Test fun aSavedExternalAssociationRechecksPersistedReadAndDocumentIdentity(): Unit = runBlocking {
        val association = LyricsAssociation("external-one", "retained-source", URI, 1,
            LyricsEncodingChoice.AUTO_BOM_OR_UTF8, sha256(bytes))
        access.persisted = true
        val input = resolver.resolve(association, 7L)
        assertTrue(input.requirePersistedRead)
        assertEquals(association.confirmedContentSha256, input.expectedContentSha256)
        assertEquals(7L, input.sourceGeneration)
        access.persisted = false
        expect(LyricsLoadIssue.PERMISSION_REVOKED) { resolver.resolve(association, 8L) }
        access.persisted = true
        expect(LyricsLoadIssue.SOURCE_CHANGED) { resolver.resolve(association.copy(documentId = "other-object"), 8L) }
        assertTrue(checkNotNull(OwnedLyricsProvider.root(context).list()).isEmpty())
    }

    @Test fun confirmedCopySurvivesOriginalRevocationAndANewResolverWithoutPersistedReadPermission(): Unit = runBlocking {
        val input = resolver.preview(URI, 1, 3L, LyricsEncodingChoice.AUTO_BOM_OR_UTF8)
        val prepared = resolver.prepareAssociation(input, sha256(bytes))
        assertEquals(2, verifications)
        assertEquals(0, prepared.association.readFlags)
        assertFalse(prepared.input.requirePersistedRead)
        copies.recover { false }
        assertEquals(1, checkNotNull(OwnedLyricsProvider.root(context).list()).size)
        prepared.release()
        prepared.release()
        access.revoked = true
        val restored = newResolver().resolve(prepared.association, 4L)
        assertEquals(prepared.association.documentId, restored.documentId)
        assertEquals(sha256(bytes), restored.expectedContentSha256)
        assertFalse(restored.requirePersistedRead)
        val token = checkNotNull(OwnedLyricsProvider.token(context, Uri.parse(restored.candidate.uri)))
        assertEquals(sha256(bytes), sha256(OwnedLyricsProvider.finalFile(context, token).readBytes()))
        copies.recover { it == prepared.association.uri }
        assertTrue(OwnedLyricsProvider.finalFile(context, token).isFile)
    }

    @Test fun postCopySourceChangeReturnsNoAssociationAndLeavesAnExplicitlyRecoverableOrphan(): Unit = runBlocking {
        val input = resolver.preview(URI, 1, 3L, LyricsEncodingChoice.AUTO_BOM_OR_UTF8)
        failVerificationAt = 2
        try {
            resolver.prepareAssociation(input, sha256(bytes))
            throw AssertionError("Expected controlled source change")
        } catch (error: SourceReadException) {
            assertEquals(SourceReadIssue.SOURCE_CHANGED, error.issue)
        }
        assertEquals(2, verifications)
        assertEquals(1, checkNotNull(OwnedLyricsProvider.root(context).list()).size)
        copies.recover { false }
        assertTrue(checkNotNull(OwnedLyricsProvider.root(context).list()).isEmpty())
    }

    @Test fun privateProviderQueryExposesOnlyACompletedLocalTokenAndForeignCallersAreRejected() {
        val providerContext = context
        val provider = OwnedLyricsProvider().apply {
            attachInfo(providerContext, ProviderInfo().apply {
                authority = OwnedLyricsProvider.authority(providerContext)
                exported = false
            })
        }
        val token = UUID.randomUUID().toString()
        val uri = OwnedLyricsProvider.uri(context, token)
        OwnedLyricsProvider.stagingFile(context, token).writeBytes(bytes)
        try {
            provider.query(uri, null, null, null, null)
            throw AssertionError("Staging bytes cannot be queried")
        } catch (_: FileNotFoundException) { }
        OwnedLyricsProvider.finalFile(context, token).writeBytes(bytes)
        provider.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null).use {
            assertTrue(it.moveToFirst())
            assertEquals("lyrics.lrc", it.getString(0))
            assertEquals(bytes.size.toLong(), it.getLong(1))
        }
        assertEquals("text/plain", provider.getType(uri))
        ShadowBinder.setCallingUid(Process.myUid() + 1)
        try {
            provider.query(uri, null, null, null, null)
            throw AssertionError("Foreign caller cannot read private lyrics")
        } catch (_: SecurityException) { }
        finally { ShadowBinder.setCallingUid(Process.myUid()) }
    }

    private fun newResolver() = SavedLyricsDocumentResolver(context, access, copies) { source, candidate ->
        check(source.sourceId == candidate.sourceId)
        verifications++
        if (verifications == failVerificationAt) throw SourceReadException(SourceReadIssue.SOURCE_CHANGED)
    }

    private inner class DocumentAccess : LocalSourceAccess {
        var observations = 0
        var persisted = false
        var revoked = false
        override suspend fun observe(source: LocalSourceSpec): LocalSourceObservation {
            observations++
            val token = OwnedLyricsProvider.token(context, Uri.parse(source.uri))
            val owned = token != null
            if (!owned && revoked) throw SourceReadException(SourceReadIssue.PERMISSION_REVOKED)
            val objectId = if (owned) "uri:${source.uri}" else "external-one"
            val count = if (token != null) OwnedLyricsProvider.finalFile(context, token).length() else bytes.size.toLong()
            val candidate = ObservedCandidate(source.sourceId, objectId, source.uri, null, "selected.lrc", "text/plain",
                LocalNodeKind.OTHER, count)
            val actual = source.copy(authority = Uri.parse(source.uri).authority, rootObjectId = objectId)
            return LocalSourceObservation(actual, "observed:$objectId", LocalGrantObservation(
                read = ObservedCapability.AVAILABLE,
                persistedRead = if (!owned && persisted) ObservedCapability.AVAILABLE else ObservedCapability.UNAVAILABLE,
            ), candidate)
        }
        override suspend fun open(source: LocalSourceSpec, parentObjectId: String?,
            sinceMediaGeneration: Long?): LocalCandidateCursor = error("A selected document cannot enumerate its parent")
    }

    private suspend fun expect(issue: LyricsLoadIssue, action: suspend () -> Unit) {
        try {
            action()
            throw AssertionError("Expected rejected lyric input")
        } catch (error: LyricsDocumentResolutionException) {
            assertEquals(issue, error.issue)
        }
    }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object { private const val URI = "content://lyrics.resolver.fixture/document/one" }
}
