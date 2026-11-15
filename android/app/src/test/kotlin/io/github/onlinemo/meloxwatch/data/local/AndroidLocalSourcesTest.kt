package io.github.onlinemo.meloxwatch.data.local

import android.Manifest
import android.app.Application
import android.content.Context
import android.provider.MediaStore
import io.github.onlinemo.meloxwatch.data.library.LocalContentValidation
import io.github.onlinemo.meloxwatch.model.LocalAccess
import io.github.onlinemo.meloxwatch.model.LocalLocationReference
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class AndroidLocalSourcesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun transientReadActuallyOpensTheSelectedUriWithoutClaimingDurableContentProof() = runBlocking {
        val audio = File(temporary.root, "audio.mp3").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val provider = fixtureAudioProvider("transient-audio", audio)
        val sources = AndroidLocalSources(context(), LocalProviderIo())
        sources.verifyTransientRead(LocalLocationReference("preview", "content://transient-audio/audio"))
        assertEquals(1, provider.queryReads)
        assertEquals(1, provider.audioReads)
        expect(SourceReadIssue.CONTENT_UNKNOWN) {
            sources.validateRead(fixtureDescriptor("transient-audio", audio.length()))
        }
        audio.writeBytes(byteArrayOf())
        expect(SourceReadIssue.NOT_AUDIO) {
            sources.verifyTransientRead(LocalLocationReference("preview", "content://transient-audio/audio"))
        }
    }

    @Test fun transientPreparationRejectsQueryOpenAndLostPersistedReadAccess() = runBlocking {
        val audio = File(temporary.root, "audio.mp3").apply { writeBytes(byteArrayOf(1)) }
        val provider = fixtureAudioProvider("transient-access", audio)
        val sources = AndroidLocalSources(context(), LocalProviderIo())
        val location = LocalLocationReference("preview", "content://transient-access/audio")
        provider.denied = true
        expect(SourceReadIssue.PERMISSION_DENIED) { sources.verifyTransientRead(location) }
        provider.denied = false
        provider.openDenied = true
        expect(SourceReadIssue.PERMISSION_DENIED) { sources.verifyTransientRead(location) }
        provider.openDenied = false
        expect(SourceReadIssue.PERMISSION_REVOKED) {
            sources.verifyTransientRead(location.copy(access = LocalAccess.PERSISTED))
        }
        assertEquals(0, provider.audioReads)
    }

    @Test fun transientReadCannotWidenASingleDocumentOrSubstituteADifferentTreeGrant() = runBlocking {
        val audio = File(temporary.root, "audio.mp3").apply { writeBytes(byteArrayOf(1)) }
        val provider = fixtureAudioProvider("transient-scope", audio)
        val sources = AndroidLocalSources(context(), LocalProviderIo())
        val location = LocalLocationReference("preview", "content://transient-scope/audio")
        expect(SourceReadIssue.SOURCE_CHANGED) {
            sources.verifyTransientRead(location.copy(grantUri = "content://transient-scope/other"))
        }
        expect(SourceReadIssue.SOURCE_CHANGED) {
            sources.verifyTransientRead(location.copy(uri = "content://transient-scope/tree/replacement/document/audio",
                grantUri = "content://transient-scope/tree/original"))
        }
        expect(SourceReadIssue.INVALID_SOURCE) {
            sources.verifyTransientRead(location.copy(access = LocalAccess.MEDIA_PERMISSION))
        }
        expect(SourceReadIssue.INVALID_SOURCE) {
            sources.verifyTransientRead(location.copy(access = LocalAccess.APP_OWNED))
        }
        assertEquals(0, provider.queryReads)
        assertEquals(0, provider.audioReads)
    }

    @Test fun weakProviderUsesACompleteHashAndRejectsSameSizeReplacement() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val audio = File(temporary.root, "audio.mp3").apply { writeBytes(bytes) }
        fixtureAudioProvider("weak-audio", audio)
        val sources = AndroidLocalSources(context(), LocalProviderIo())
        val descriptor = fixtureDescriptor("weak-audio", bytes.size.toLong(), token(bytes))
        assertEquals(LocalContentValidation.VERIFIED, sources.validateRead(descriptor).contentValidation)
        audio.writeBytes(byteArrayOf(5, 4, 3, 2, 1))
        expect(SourceReadIssue.SOURCE_CHANGED) { sources.validateRead(descriptor) }
    }

    @Test fun anUnprovedOldLibraryAccessCannotSilentlyPlayTheCurrentUriContent() = runBlocking {
        val audio = File(temporary.root, "audio.mp3").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        fixtureAudioProvider("unknown-audio", audio)
        val sources = AndroidLocalSources(context(), LocalProviderIo())
        expect(SourceReadIssue.CONTENT_UNKNOWN) { sources.validateRead(fixtureDescriptor("unknown-audio", 3)) }
    }

    @Test fun aWeakLargeSourceRemainsUnknownInsteadOfUsingAPrefixHash() = runBlocking {
        val bytes = ByteArray(LocalInputLimits.READ_PROOF_BYTES + 1) { 7 }
        val audio = File(temporary.root, "audio.mp3").apply { writeBytes(bytes) }
        fixtureAudioProvider("large-audio", audio)
        val sources = AndroidLocalSources(context(), LocalProviderIo())
        expect(SourceReadIssue.CONTENT_UNKNOWN) {
            sources.validateRead(fixtureDescriptor("large-audio", bytes.size.toLong(), token(bytes)))
        }
    }

    @Test fun actualPermissionDenialAndAnUnexpectedAuthorityRemainSeparateFailures() = runBlocking {
        val audio = File(temporary.root, "audio.mp3").apply { writeBytes(byteArrayOf(1)) }
        val provider = fixtureAudioProvider("denied-audio", audio)
        val sources = AndroidLocalSources(context(), LocalProviderIo())
        val descriptor = fixtureDescriptor("denied-audio", 1)
        provider.denied = true
        expect(SourceReadIssue.PERMISSION_DENIED) { sources.validateRead(descriptor) }
        provider.denied = false
        val changed = descriptor.copy(location = descriptor.location.copy(uri = "content://different/audio"))
        expect(SourceReadIssue.SOURCE_CHANGED) { sources.validateRead(changed) }
    }

    @Test fun reauthorizationRetainsTheOriginalSourceIdInAllPublishedFacts() = runBlocking {
        val audio = File(temporary.root, "audio.mp3").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        fixtureAudioProvider("reauth-audio", audio)
        val sources = AndroidLocalSources(context(), LocalProviderIo())
        val original = sources.observe(LocalSourceSpec("original", LocalSourceKind.DOCUMENT,
            "content://reauth-audio/audio"))
        val replacement = original.source.copy(sourceId = "replacement")
        val verified = sources.verifyReauthorization(original.source, replacement)
        assertEquals(original.identity, verified.identity)
        assertEquals("original", verified.source.sourceId)
        assertEquals("original", verified.root.sourceId)
        assertEquals(original.root.objectId, verified.root.objectId)
    }

    @Test fun aValidWeakObjectCanBeRecheckedWithoutClaimingAContentProof() = runBlocking {
        val audio = File(temporary.root, "audio.mp3").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val provider = fixtureAudioProvider("preview-audio", audio)
        val sources = AndroidLocalSources(context(), LocalProviderIo())
        val observed = sources.observe(LocalSourceSpec("preview", LocalSourceKind.DOCUMENT, "content://preview-audio/audio"))
        sources.verifyObservedCandidate(observed.source, observed.root)
        assertEquals(1, provider.audioReads)
        assertEquals(null, observed.root.providerContentToken)
        expect(SourceReadIssue.CONTENT_UNKNOWN) { sources.verifyMetadataCandidate(observed.source, observed.root) }
        expect(SourceReadIssue.CONTENT_UNKNOWN) { sources.validateRead(fixtureDescriptor("preview-audio", audio.length())) }
    }

    @Test fun changedPreviewNameMimeAndSizeAreReadFromTheActualProvider() = runBlocking {
        val original = File(temporary.root, "original.mp3").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val provider = fixtureAudioProvider("preview-changes", original)
        val sources = AndroidLocalSources(context(), LocalProviderIo())
        val observed = sources.observe(LocalSourceSpec("preview", LocalSourceKind.DOCUMENT, "content://preview-changes/audio"))
        provider.audio = File(temporary.root, "replacement.mp3").apply { writeBytes(byteArrayOf(3, 2, 1)) }
        expect(SourceReadIssue.SOURCE_CHANGED) { sources.verifyObservedCandidate(observed.source, observed.root) }
        provider.audio = original
        provider.mimeType = "audio/flac"
        expect(SourceReadIssue.SOURCE_CHANGED) { sources.verifyObservedCandidate(observed.source, observed.root) }
        provider.mimeType = "audio/mpeg"
        original.writeBytes(byteArrayOf(1, 2, 3, 4))
        expect(SourceReadIssue.SOURCE_CHANGED) { sources.verifyObservedCandidate(observed.source, observed.root) }
    }

    @Test fun previewRecheckEnforcesSourceScopeAndActualQueryPermission() = runBlocking {
        val audio = File(temporary.root, "audio.mp3").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val provider = fixtureAudioProvider("preview-scope", audio)
        val sources = AndroidLocalSources(context(), LocalProviderIo())
        val observed = sources.observe(LocalSourceSpec("preview", LocalSourceKind.DOCUMENT, "content://preview-scope/audio"))
        expect(SourceReadIssue.SOURCE_CHANGED) {
            sources.verifyObservedCandidate(observed.source, observed.root.copy(sourceId = "another-source"))
        }
        expect(SourceReadIssue.SOURCE_CHANGED) {
            sources.verifyObservedCandidate(observed.source.copy(authority = "another-authority"), observed.root)
        }
        val tree = LocalSourceSpec("preview", LocalSourceKind.TREE, "content://preview-scope/tree/original",
            authority = "preview-scope", rootObjectId = "original")
        expect(SourceReadIssue.SOURCE_CHANGED) {
            sources.verifyObservedCandidate(tree, observed.root.copy(uri = "content://preview-scope/tree/replacement/document/audio"))
        }
        provider.denied = true
        expect(SourceReadIssue.PERMISSION_DENIED) { sources.verifyObservedCandidate(observed.source, observed.root) }
    }

    @Test
    @Config(sdk = [30])
    fun nativeMediaTokenChangesAndLossAreObservedThroughTheAndroidProviderApi() = runBlocking {
        val audio = File(temporary.root, "audio.mp3").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val provider = fixtureMediaProvider(audio)
        val sources = AndroidLocalSources(context(), LocalProviderIo())
        val (source, candidate) = mediaCandidate(sources)
        assertEquals("fixture-version:17", candidate.providerContentToken)
        sources.verifyObservedCandidate(source, candidate)
        sources.verifyMetadataCandidate(source, candidate)
        provider.generationModified = 18L
        expect(SourceReadIssue.SOURCE_CHANGED) { sources.verifyObservedCandidate(source, candidate) }
        expect(SourceReadIssue.SOURCE_CHANGED) { sources.verifyMetadataCandidate(source, candidate) }
        provider.generationModified = null
        expect(SourceReadIssue.CONTENT_UNKNOWN) { sources.verifyObservedCandidate(source, candidate) }
        expect(SourceReadIssue.CONTENT_UNKNOWN) { sources.verifyMetadataCandidate(source, candidate) }
    }

    @Test
    @Config(sdk = [30])
    fun matchingNamesAndSizesDoNotAuthorizeAReplacementMediaObject() = runBlocking {
        val audio = File(temporary.root, "audio.mp3").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val provider = fixtureMediaProvider(audio)
        val sources = AndroidLocalSources(context(), LocalProviderIo())
        val (source, candidate) = mediaCandidate(sources)
        provider.identifier = 8L
        expect(SourceReadIssue.SOURCE_CHANGED) { sources.verifyObservedCandidate(source, candidate) }
        expect(SourceReadIssue.SOURCE_CHANGED) { sources.verifyMetadataCandidate(source, candidate) }
    }

    private suspend fun mediaCandidate(sources: AndroidLocalSources): Pair<LocalSourceSpec, ObservedCandidate> {
        val application: Application = RuntimeEnvironment.getApplication()
        shadowOf(application).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        val observed = sources.observe(LocalSourceSpec("media", LocalSourceKind.MEDIA_STORE,
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY).toString(),
            mediaVolume = MediaStore.VOLUME_EXTERNAL_PRIMARY))
        val cursor = sources.open(observed.source)
        val candidate = try { cursor.nextBatch().single() } finally { cursor.closeAndAwait() }
        return observed.source to candidate
    }

    private fun context(): Context = RuntimeEnvironment.getApplication()
    private fun token(bytes: ByteArray): String = "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private suspend fun expect(issue: SourceReadIssue, action: suspend () -> Any?) {
        try { action(); throw AssertionError("Expected $issue") }
        catch (error: SourceReadException) { assertEquals(issue, error.issue) }
    }
}
