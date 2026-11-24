package io.github.onlinemo.meloxwatch.data.themes

import io.github.onlinemo.meloxwatch.data.fonts.FontWeightSupport
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontLimits
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontRole
import io.github.onlinemo.meloxwatch.data.imports.ImportPhase
import io.github.onlinemo.meloxwatch.data.imports.OwnedAudioProvider
import io.github.onlinemo.meloxwatch.data.transfer.TransferException
import io.github.onlinemo.meloxwatch.data.transfer.TransferHash
import io.github.onlinemo.meloxwatch.data.transfer.TransferIssue
import io.github.onlinemo.meloxwatch.data.transfer.TransferOperationBinding
import io.github.onlinemo.meloxwatch.data.transfer.TransferReceipt
import io.github.onlinemo.meloxwatch.data.transfer.TransferReceiptKind
import io.github.onlinemo.meloxwatch.data.transfer.TransferUploadPhase
import io.github.onlinemo.meloxwatch.data.themes.ThemeTestFixtures as Packages
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

/** 原许可字体、实际 ZIP 和真实 SQL claim 时序；不借伪 URI 或合成收据通过正常路径。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TransferredThemeInputIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var fixture: TransferredThemeIntegrationFixture

    @Before fun open() = runBlocking {
        fixture = TransferredThemeIntegrationFixture(temporary.newFolder("theme-input"))
        withTimeout(10_000L) { fixture.initialize() }
    }

    @After fun close() = runBlocking {
        if (::fixture.isInitialized) fixture.closeAwaitingWork()
    }

    @Test(timeout = 30_000L)
    fun originalLicensedTtfAndOtfReachNativeValidationAndAnAcknowledgedLocalApply() = runBlocking {
        val original = Packages.font()
        val translation = Packages.font("SourceCodePro-Regular.otf", "font.translation")
        val value = Packages.packageOf("fixture.real.fonts", assets = listOf(original, translation),
            fonts = mapOf("original" to original.id, "translation" to translation.id))
        val received = fixture.receivePackage(value)
        val preview = fixture.coordinator.preview(received.binding.operationId)
        assertEquals(FontWeightSupport.NO_AXIS, preview.fontCapabilities.getValue(LyricsFontRole.TRANSLATION).support)
        assertNotNull(preview.fontCapabilities[LyricsFontRole.ORIGINAL])
        assertEquals(original.licenseText, preview.manifest.resource(original.id)?.license?.text)
        assertEquals(translation.licenseText, preview.manifest.resource(translation.id)?.license?.text)
        assertArrayEquals(original.bytes, File(fixture.receivedPayload(received.binding.operationId), original.path).readBytes())
        assertArrayEquals(translation.bytes, File(fixture.receivedPayload(received.binding.operationId), translation.path).readBytes())

        val request = Packages.apply(preview, acknowledgment = LyricsFontLimits.LICENSE_ACKNOWLEDGMENT_VERSION)
        val accepted = fixture.coordinator.apply(request)
        val installation = fixture.installedDirectory(checkNotNull(accepted.installedRef))
        assertArrayEquals(original.bytes, File(installation, original.path).readBytes())
        assertArrayEquals(translation.bytes, File(installation, translation.path).readBytes())
        assertEquals(LyricsFontLimits.LICENSE_ACKNOWLEDGMENT_VERSION,
            fixture.candidate(received.binding.operationId)?.fontLicenseAcknowledgmentVersion)
        fixture.assertCanonical(received)
        fixture.reopen()
        assertNull(fixture.coordinator.load())
        assertEquals(accepted.snapshot.selected, fixture.coordinator.read().selected)
        assertArrayEquals(original.bytes, File(installation, original.path).readBytes())
        assertArrayEquals(translation.bytes, File(installation, translation.path).readBytes())
    }

    @Test(timeout = 30_000L)
    fun aHashCorrectButMalformedFontIsRejectedWithoutChangingTheOldThemeOrCompletingAnOperation() = runBlocking {
        val before = fixture.installLocal(Packages.packageOf("fixture.previous"))
        val savedCatalog = fixture.catalogFile.readBytes()
        val real = Packages.font()
        val malformed = real.copy(bytes = ByteArray(real.bytes.size))
        val value = Packages.packageOf("fixture.invalid.font", assets = listOf(malformed),
            fonts = mapOf("original" to malformed.id))
        val bytes = value.zip()
        val declaration = fixture.themeDeclaration(bytes)
        val binding = fixture.binding(declaration)

        expectThemeTransferIssue(TransferIssue.INVALID_INPUT) { fixture.upload(bytes, declaration, binding) }
        fixture.assertNoCompletion(binding.operationId)
        assertFalse(fixture.receivedDirectory(binding.operationId).exists())
        assertEquals(before.snapshot.selected, fixture.coordinator.read().selected)
        assertArrayEquals(savedCatalog, fixture.catalogFile.readBytes())
        assertEquals(1, fixture.commitAttempts.get())
        fixture.assertOriginalSourcesUnchangedAndClosed()
    }

    @Test(timeout = 30_000L)
    fun traversalAndActiveImageResourcesFailActualArchiveOrManifestValidationAndPreserveOutsideBytes() = runBlocking {
        val before = fixture.installLocal(Packages.packageOf("fixture.previous"))
        val savedCatalog = fixture.catalogFile.readBytes()
        val outside = temporary.newFile("outside.audio")
        val originalOutside = transferredThemeTestWav()
        outside.writeBytes(originalOutside)
        val base = Packages.packageOf("fixture.unsafe")
        val traversal = Packages.archive(base.records() + Packages.ZipRecord("../outside.audio", byteArrayOf(1)))
        val svg = Packages.Asset("active", "assets/active.svg",
            "<svg xmlns='http://www.w3.org/2000/svg'><script>invalid</script></svg>".toByteArray(),
            mediaType = "image/svg+xml")
        val activeImage = Packages.packageOf("fixture.active", assets = listOf(svg)).zip()
        val undeclared = Packages.archive(base.records() + Packages.ZipRecord("assets/unlisted.js", byteArrayOf(1, 2)))

        for (bytes in listOf(traversal, activeImage, undeclared)) {
            val declaration = fixture.themeDeclaration(bytes)
            val binding = fixture.binding(declaration)
            expectThemeTransferIssue(TransferIssue.INVALID_INPUT) { fixture.upload(bytes, declaration, binding) }
            fixture.assertNoCompletion(binding.operationId)
            assertFalse(fixture.receivedDirectory(binding.operationId).exists())
            assertArrayEquals(originalOutside, outside.readBytes())
            assertArrayEquals(savedCatalog, fixture.catalogFile.readBytes())
            assertEquals(before.snapshot.selected, fixture.coordinator.read().selected)
        }
        fixture.assertOriginalSourcesUnchangedAndClosed()
    }

    @Test(timeout = 30_000L)
    fun callerCancellationAtTheFinalRoomBoundaryBeforeClaimCannotPublishACandidateOrReceipt() = runBlocking {
        exerciseClaimBoundary(afterClaim = false, cancelCaller = true)
    }

    @Test(timeout = 30_000L)
    fun revocationAtTheFinalRoomBoundaryBeforeClaimCannotPublishACandidateOrReceipt() = runBlocking {
        exerciseClaimBoundary(afterClaim = false, cancelCaller = false)
    }

    @Test(timeout = 30_000L)
    fun callerCancellationAfterClaimKeepsTheRealCanonicalCommitAndStageRetirementWaitsForIt() = runBlocking {
        exerciseClaimBoundary(afterClaim = true, cancelCaller = true)
    }

    @Test(timeout = 30_000L)
    fun revocationAfterClaimKeepsTheRealCanonicalCommitAndStageRetirementWaitsForIt() = runBlocking {
        exerciseClaimBoundary(afterClaim = true, cancelCaller = false)
    }

    @Test(timeout = 30_000L)
    fun discardingAPendingThemeKeepsInstalledThemesAndTheRealIndexedAudioFile() = runBlocking {
        val before = fixture.installLocal(Packages.packageOf("fixture.previous"))
        val ref = checkNotNull(before.installedRef)
        val savedInstalled = fixture.installedManifest(ref.installationId).readBytes()
        val audioBytes = transferredThemeTestWav(frames = 40_000)
        val audio = fixture.uploadAudio(audioBytes)
        assertEquals(TransferReceiptKind.AUDIO_COMMITTED, audio.result)
        val library = fixture.library.overview()
        val audioRecord = checkNotNull(fixture.audioLedger.recordTransferred(audio.binding.operationId))
        assertEquals(ImportPhase.COMMITTED, audioRecord.phase)
        assertNotNull(fixture.library.resolveLocalRead(checkNotNull(audioRecord.trackKey)))
        val savedCatalog = fixture.catalogFile.readBytes()
        val received = fixture.receivePackage(Packages.packageOf("fixture.discard"))
        val preview = fixture.coordinator.preview(received.binding.operationId)
        fixture.coordinator.cancelPreview(preview.token)
        fixture.coordinator.discard(received.binding.operationId)
        fixture.coordinator.discard(received.binding.operationId)

        assertEquals(TransferredThemeState.DISCARDED.name, fixture.candidate(received.binding.operationId)?.state)
        assertFalse(fixture.receivedDirectory(received.binding.operationId).exists())
        assertArrayEquals(savedCatalog, fixture.catalogFile.readBytes())
        assertArrayEquals(savedInstalled, fixture.installedManifest(ref.installationId).readBytes())
        assertArrayEquals(audioBytes, OwnedAudioProvider.finalFile(fixture.context, audio.binding.operationId).readBytes())
        assertEquals(library, fixture.library.overview())
        fixture.assertCanonical(audio)
        fixture.assertCanonical(received)
        assertEquals(before.snapshot.selected, fixture.coordinator.read().selected)

        val applied = fixture.receivePackage(Packages.packageOf("fixture.applied"))
        val accepted = fixture.coordinator.apply(Packages.apply(fixture.coordinator.preview(applied.binding.operationId)))
        val appliedManifest = fixture.installedManifest(checkNotNull(accepted.installedRef).installationId).readBytes()
        expectLocalThemeIssue(ThemeIssue.IN_USE) { fixture.coordinator.discard(applied.binding.operationId) }
        assertArrayEquals(appliedManifest, fixture.installedManifest(checkNotNull(accepted.installedRef).installationId).readBytes())
        assertArrayEquals(savedInstalled, fixture.installedManifest(ref.installationId).readBytes())
        assertArrayEquals(audioBytes, OwnedAudioProvider.finalFile(fixture.context, audio.binding.operationId).readBytes())
        assertEquals(library, fixture.library.overview())
        assertNotNull(fixture.library.resolveLocalRead(checkNotNull(audioRecord.trackKey)))
        fixture.assertCanonical(audio)
    }

    @Test(timeout = 30_000L)
    fun ordinaryPreviewCleanupRemovesOnlyItsStageWhileReceivedCopiesSurvivePreviewRetirementAndCancellation() = runBlocking {
        val received = fixture.receivePackage(Packages.packageOf("fixture.retained"))
        val savedSeal = fixture.receivedSeal(received.binding.operationId).readBytes()
        val savedInput = fixture.receivedInput(received.binding.operationId).readBytes()
        val savedCatalog = fixture.catalogFile.readBytes()
        val retired = fixture.coordinator.preview(received.binding.operationId)
        fixture.coordinator.retirePreview(retired.token)
        fixture.coordinator.read()
        assertArrayEquals(savedSeal, fixture.receivedSeal(received.binding.operationId).readBytes())
        assertArrayEquals(savedInput, fixture.receivedInput(received.binding.operationId).readBytes())
        expectLocalThemeIssue(ThemeIssue.PREVIEW_NOT_FOUND) { fixture.coordinator.apply(Packages.apply(retired)) }
        val cancelled = fixture.coordinator.preview(received.binding.operationId)
        fixture.coordinator.cancelPreview(cancelled.token)
        assertArrayEquals(savedSeal, fixture.receivedSeal(received.binding.operationId).readBytes())

        val ordinary = fixture.prepareLocal(Packages.packageOf("fixture.ordinary"))
        assertTrue(fixture.stagingDirectory(ordinary.preview.token).isDirectory)
        fixture.coordinator.retirePreview(ordinary.preview.token)
        fixture.coordinator.read()
        assertFalse(fixture.stagingDirectory(ordinary.preview.token).exists())
        assertArrayEquals(savedSeal, fixture.receivedSeal(received.binding.operationId).readBytes())
        assertArrayEquals(savedInput, fixture.receivedInput(received.binding.operationId).readBytes())
        assertArrayEquals(savedCatalog, fixture.catalogFile.readBytes())
        assertEquals(TransferredThemeState.RECEIVED.name, fixture.candidate(received.binding.operationId)?.state)
        fixture.assertCanonical(received)
        assertNotNull(fixture.coordinator.preview(received.binding.operationId))
        assertEquals(0, fixture.commitAttempts.get())
        fixture.assertOriginalSourcesUnchangedAndClosed()
    }

    @Test(timeout = 30_000L)
    fun anUploadOwnerCannotBeCommittedUnderAnotherOperationOrPayloadBinding() = runBlocking {
        val savedCatalog = fixture.catalogFile.readBytes()
        val bytes = Packages.packageOf("fixture.binding").zip()
        val declaration = fixture.themeDeclaration(bytes)
        for (wrongOperation in listOf(true, false)) {
            val binding = fixture.binding(declaration)
            val supplied = TransferOperationBinding(
                if (wrongOperation) Packages.id() else binding.operationId,
                binding.kind, binding.target, binding.peer,
                if (wrongOperation) binding.payloadHash else TransferHash.digest("other declaration".toByteArray()),
            )
            val stage = fixture.prepareStage(bytes, declaration, binding)
            try {
                val verified = stage.verify(fixture.themePermit())
                stage.markCommitting()
                expectThemeTransferIssue(TransferIssue.INVALID_INPUT) {
                    fixture.backend.commitUpload(supplied, verified, fixture.themePermit())
                }
            } finally {
                fixture.retireStage(stage)
                withTimeout(5_000L) { fixture.awaitPhysicalIdle() }
            }
            fixture.assertNoCompletion(binding.operationId)
            fixture.assertNoCompletion(supplied.operationId)
            assertArrayEquals(savedCatalog, fixture.catalogFile.readBytes())
            assertFalse(fixture.receivedDirectory(binding.operationId).exists())
            assertFalse(fixture.receivedDirectory(supplied.operationId).exists())
        }
        assertEquals(0, fixture.commitAttempts.get())
    }

    @Test(timeout = 30_000L)
    fun anotherPayloadCannotReplayAnExistingCanonicalThemeAndAnotherOperationCannotApproveItsPreview() = runBlocking {
        val received = fixture.receivePackage(Packages.packageOf("fixture.exact.replay"))
        val binding = received.binding
        val changed = TransferOperationBinding(binding.operationId, binding.kind, binding.target, binding.peer,
            TransferHash.digest("changed payload".toByteArray()))
        val savedCatalog = fixture.catalogFile.readBytes()
        val savedSeal = fixture.receivedSeal(binding.operationId).readBytes()
        expectThemeTransferIssue(TransferIssue.CONFLICT) { fixture.backend.committed(changed, fixture.themePermit()) }
        val preview = fixture.coordinator.preview(binding.operationId)
        expectLocalThemeIssue(ThemeIssue.THEME_NOT_FOUND) { fixture.coordinator.approve(Packages.id(), Packages.apply(preview)) }
        fixture.assertCanonical(received)
        assertArrayEquals(savedCatalog, fixture.catalogFile.readBytes())
        assertArrayEquals(savedSeal, fixture.receivedSeal(binding.operationId).readBytes())
        assertEquals(0, fixture.commitAttempts.get())
    }

    private suspend fun exerciseClaimBoundary(afterClaim: Boolean, cancelCaller: Boolean) = supervisorScope {
        val before = fixture.installLocal(Packages.packageOf("fixture.previous"))
        val savedCatalog = fixture.catalogFile.readBytes()
        val bytes = Packages.packageOf("fixture.claim").zip()
        val declaration = fixture.themeDeclaration(bytes)
        val binding = fixture.binding(declaration)
        val stage = fixture.prepareStage(bytes, declaration, binding)
        val verified = stage.verify(fixture.themePermit())
        stage.markCommitting()
        val permit = fixture.themePermit()
        val gate = ThemeRoomStepGate { sql ->
            val finalBoundary = if (afterClaim) {
                sql.contains("insert") && sql.contains("into transferred_theme_candidates")
            } else {
                sql.startsWith("select") && sql.contains("state in ('received', 'applying')")
            }
            fixture.receivedSeal(binding.operationId).isFile && finalBoundary
        }
        fixture.sqlProbe.beforeStep = gate::beforeStep
        val observedFailure = AtomicReference<Exception?>()
        val caller = async(Dispatchers.Default) {
            try {
                fixture.backend.commitUpload(binding, verified, permit)
            } catch (failure: Exception) {
                observedFailure.set(failure)
                throw failure
            }
        }
        var retirement: Deferred<Unit>? = null
        var delivered: TransferReceipt? = null
        try {
            withTimeout(5_000L) { gate.entered.await() }
            assertEquals(1, gate.hits.get())
            withTimeout(5_000L) { fixture.io.awaitIdle() }
            assertArrayEquals(bytes, fixture.receivedInput(binding.operationId).readBytes())
            assertFalse(stage.file.exists())
            assertFalse(caller.isCompleted)
            if (cancelCaller) caller.cancel(CancellationException("controlled receive caller cancellation"))
            else fixture.authority.revokeForLocalUi(fixture.peer)
            if (afterClaim) {
                retirement = async(start = CoroutineStart.UNDISPATCHED) { fixture.retireStage(stage) }
                assertFalse(checkNotNull(retirement).isCompleted)
                assertEquals(TransferUploadPhase.RETIRING, fixture.store.progress.value?.phase)
            }
            gate.release()
            if (cancelCaller) {
                expectThemeCancellation { withTimeout(10_000L) { caller.await() } }
                assertTrue(caller.isCancelled)
            } else if (afterClaim) {
                delivered = withTimeout(10_000L) { caller.await() }
            } else {
                expectThemeTransferIssue(TransferIssue.NOT_APPROVED) { withTimeout(10_000L) { caller.await() } }
                assertTrue(observedFailure.get() is TransferException)
            }
            withContext(NonCancellable) {
                withTimeout(10_000L) {
                    caller.join()
                    retirement?.await()
                    fixture.retireStage(stage)
                    fixture.awaitPhysicalIdle()
                }
            }
            assertArrayEquals(savedCatalog, fixture.catalogFile.readBytes())
            assertEquals(before.snapshot.selected, fixture.themes.state.value.selected)
            fixture.sqlProbe.beforeStep = {}
            if (afterClaim) {
                val row = checkNotNull(fixture.candidate(binding.operationId))
                assertEquals(TransferredThemeState.RECEIVED.name, row.state)
                assertEquals(TransferUploadPhase.COMPLETED, fixture.store.progress.value?.phase)
                fixture.newSession()
                val durable = checkNotNull(fixture.backend.committed(binding, fixture.themePermit()))
                fixture.assertCanonical(durable)
                delivered?.let { fixture.assertSameReceipt(it, durable) }
                assertArrayEquals(bytes, fixture.receivedInput(binding.operationId).readBytes())
            } else {
                fixture.assertNoCompletion(binding.operationId)
                assertFalse(fixture.store.progress.value?.phase == TransferUploadPhase.COMPLETED)
                assertTrue(fixture.receivedSeal(binding.operationId).isFile)
                fixture.newSession()
                val resumed = fixture.upload(bytes, declaration, binding)
                fixture.assertCanonical(resumed)
                assertEquals(TransferredThemeState.RECEIVED.name, fixture.candidate(binding.operationId)?.state)
                assertArrayEquals(bytes, fixture.receivedInput(binding.operationId).readBytes())
            }
            assertEquals(1, fixture.commitAttempts.get())
            fixture.assertOriginalSourcesUnchangedAndClosed()
        } finally {
            gate.release()
            fixture.sqlProbe.beforeStep = {}
            withContext(NonCancellable) {
                withTimeout(10_000L) {
                    caller.join()
                    retirement?.await()
                    fixture.retireStage(stage)
                    fixture.awaitPhysicalIdle()
                }
            }
        }
    }
}
