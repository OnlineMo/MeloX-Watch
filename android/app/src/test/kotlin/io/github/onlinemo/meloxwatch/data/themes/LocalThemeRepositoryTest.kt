package io.github.onlinemo.meloxwatch.data.themes

import android.os.CancellationSignal
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontLimits
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontRole
import io.github.onlinemo.meloxwatch.data.themes.ThemeTestFixtures as Fixture
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalThemeRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun importedPreviewAndCancelLeaveTheOriginalSelectionAndSourceUnchanged() = runBlocking {
        val value = Fixture.packageOf()
        val source = BytesThemeSource(value.zip())
        val directory = temporary.newFolder()
        val repository = Fixture.repository(directory, source)
        val before = repository.read()
        val bytes = File(directory, "catalog.bin").readBytes()
        val original = source.bytes.copyOf()
        val preview = repository.prepareImport(Fixture.request())
        assertEquals(before.selected, repository.state.value.selected)
        assertEquals(before.revision, repository.state.value.revision)
        assertEquals(1, source.opens.get())
        assertEquals(1, source.inputCloses.get())
        assertEquals(1, source.descriptorCloses.get())
        assertEquals(ThemeStorageRecords.digest(original), preview.packageSha256)
        repository.discardPreview(preview.token)
        assertNull(repository.preview.value)
        assertFalse(File(directory, "staging/${preview.token}").exists())
        assertArrayEquals(bytes, File(directory, "catalog.bin").readBytes())
        assertArrayEquals(original, source.bytes)
        assertEquals(before.selected, repository.read().selected)
    }

    @Test fun explicitApplyPersistsTheExactChoiceAndKeepsThePreviousBuiltinOnRestart() = runBlocking {
        val directory = temporary.newFolder()
        val source = BytesThemeSource(Fixture.packageOf().zip())
        val repository = Fixture.repository(directory, source)
        val preview = repository.prepareImport(Fixture.request())
        val accepted = repository.apply(Fixture.apply(preview))
        val ref = checkNotNull(accepted.installedRef)
        assertFalse(accepted.cleanupRequired)
        assertEquals(1L, accepted.snapshot.revision)
        assertEquals(ThemeChoice.Installed(ref), accepted.snapshot.selected)
        assertEquals(ThemeBuiltins.defaultChoice, accepted.snapshot.previous)
        assertEquals(preview.manifest.id, accepted.snapshot.effectiveManifest.id)
        assertNull(repository.preview.value)
        assertFalse(File(directory, "staging/${preview.token}").exists())
        val bytes = File(directory, "catalog.bin").readBytes()
        val reopened = Fixture.repository(directory, source)
        val actual = reopened.read()
        assertEquals(accepted.snapshot.selected, actual.selected)
        assertEquals(accepted.snapshot.previous, actual.previous)
        assertEquals(accepted.snapshot.effective, actual.effective)
        assertArrayEquals(bytes, File(directory, "catalog.bin").readBytes())
        assertEquals(1, source.opens.get())
    }

    @Test fun builtinPreviewCanBeCancelledAndAppliedWithoutOpeningAnyExternalInput() = runBlocking {
        val source = BytesThemeSource(Fixture.packageOf().zip())
        val repository = Fixture.repository(temporary.newFolder(), source)
        val light = ThemeChoice.Builtin(BuiltinTheme.LIGHT)
        val draft = repository.previewChoice(light)
        assertEquals(ThemeBuiltins.defaultChoice, repository.state.value.selected)
        repository.discardPreview(draft.token)
        assertEquals(ThemeBuiltins.defaultChoice, repository.read().selected)
        val next = repository.previewChoice(light)
        val accepted = repository.apply(Fixture.apply(next))
        assertEquals(light, accepted.snapshot.selected)
        assertEquals(light, accepted.snapshot.effective)
        assertEquals("builtin.light", accepted.snapshot.effectiveManifest.id)
        assertEquals(0, source.opens.get())
    }

    @Test fun sameIdChangedPackageNeedsAnExactExplicitReplacementAndRetainsTheOldInstallation() = runBlocking {
        val directory = temporary.newFolder()
        val first = Fixture.repository(directory, BytesThemeSource(Fixture.packageOf().zip()))
        val old = checkNotNull(first.apply(Fixture.apply(first.prepareImport(Fixture.request()))).installedRef)
        val changed = Fixture.manifest()
        changed.getJSONObject("colors").put("accent", "#80FF80")
        val replacementPackage = Fixture.Package(Fixture.jsonBytes(changed), emptyList())
        val second = Fixture.repository(directory, BytesThemeSource(replacementPackage.zip()))
        val preview = second.prepareImport(Fixture.request())
        val request = Fixture.apply(preview)
        Fixture.expectSuspending(ThemeIssue.THEME_ID_CONFLICT) { second.apply(request) }
        assertEquals(ThemeChoice.Installed(old), second.state.value.selected)
        assertSame(preview, second.preview.value)
        val accepted = second.apply(request.copy(replaces = old))
        assertNotEquals(old, accepted.installedRef)
        assertEquals(ThemeChoice.Installed(old), accepted.snapshot.previous)
        assertEquals(2, accepted.snapshot.installed.size)
        assertTrue(File(directory, "installed/${old.installationId}/theme.json").isFile)
    }

    @Test fun exactRepeatedPackageReusesItsInstallationButConflictingOperationPayloadDoesNotReplay() = runBlocking {
        val source = BytesThemeSource(Fixture.packageOf().zip())
        val repository = Fixture.repository(temporary.newFolder(), source)
        val original = repository.apply(Fixture.apply(repository.prepareImport(Fixture.request())))
        val preview = repository.prepareImport(Fixture.request())
        val request = Fixture.apply(preview)
        val reused = repository.apply(request)
        assertEquals(original.installedRef, reused.installedRef)
        assertEquals(1, reused.snapshot.installed.size)
        assertEquals(2L, reused.snapshot.revision)
        val replay = repository.apply(request)
        assertEquals(reused.snapshot.revision, replay.snapshot.revision)
        assertEquals(reused.installedRef, replay.installedRef)
        Fixture.expectSuspending(ThemeIssue.OPERATION_CONFLICT) { repository.apply(request.copy(expectedRevision = 9)) }
        assertEquals(2, source.opens.get())
    }

    @Test fun aLivePreviewRejectsAnotherImportWithoutOpeningOrQueuingTheSecondSource() = runBlocking {
        val source = BytesThemeSource(Fixture.packageOf().zip())
        val repository = Fixture.repository(temporary.newFolder(), source)
        val preview = repository.prepareImport(Fixture.request())
        Fixture.expectSuspending(ThemeIssue.BUSY) { repository.prepareImport(Fixture.request()) }
        assertEquals(1, source.opens.get())
        assertSame(preview, repository.preview.value)
        repository.discardPreview(preview.token)
        val fresh = repository.prepareImport(Fixture.request())
        assertNotEquals(preview.token, fresh.token)
        repository.discardPreview(fresh.token)
    }

    @Test fun precommitFailurePublishesTheActualOldStateAndKeepsOneExactRetryPreview() = runBlocking {
        val directory = temporary.newFolder()
        val source = BytesThemeSource(Fixture.packageOf().zip())
        val fault = OneShotThemeFault(ThemeStoragePoint.AFTER_CATALOG_MOVE)
        val repository = Fixture.repository(directory, source, faults = fault)
        val preview = repository.prepareImport(Fixture.request())
        val beforeBytes = File(directory, "catalog.bin").readBytes()
        val request = Fixture.apply(preview)
        Fixture.expectSuspending(ThemeIssue.SAVE_FAILED) { repository.apply(request) }
        assertEquals(1, fault.hits.get())
        assertEquals(ThemeBuiltins.defaultChoice, repository.state.value.selected)
        assertEquals(0L, repository.state.value.revision)
        assertEquals(ThemeIssue.SAVE_FAILED, repository.state.value.issue)
        assertSame(preview, repository.preview.value)
        assertArrayEquals(beforeBytes, File(directory, "catalog.bin").readBytes())
        assertTrue(File(directory, "staging/${preview.token}/payload/theme.json").isFile)
        val accepted = repository.apply(request)
        assertEquals(1L, accepted.snapshot.revision)
        assertEquals(1, source.opens.get())
    }

    @Test fun rollbackFailureReportsRecoveryAndOnlyAnActualRecoveryRestoresTheRetryPayload() = runBlocking {
        val directory = temporary.newFolder()
        val primary = AtomicInteger()
        val rollback = AtomicInteger()
        var obstructRecovery = true
        val repository = Fixture.repository(directory, BytesThemeSource(Fixture.packageOf().zip()), faults = ThemeStorageFaults { point ->
            if (point == ThemeStoragePoint.AFTER_CATALOG_MOVE && primary.getAndIncrement() == 0) throw IOException("primary")
            if (point == ThemeStoragePoint.BEFORE_ROLLBACK_MOVE && obstructRecovery) {
                rollback.incrementAndGet()
                throw IOException("rollback")
            }
        })
        val preview = repository.prepareImport(Fixture.request())
        val request = Fixture.apply(preview)
        Fixture.expectSuspending(ThemeIssue.RECOVERY_REQUIRED) { repository.apply(request) }
        assertEquals(1, primary.get())
        assertEquals(1, rollback.get())
        assertEquals(ThemeIssue.RECOVERY_REQUIRED, repository.state.value.issue)
        assertEquals(ThemeBuiltins.defaultChoice, repository.state.value.selected)
        assertEquals(1L, ThemeStorageRecords.decode(File(directory, "catalog.bin").readBytes()).revision)
        assertEquals(ThemeIssue.RECOVERY_REQUIRED, repository.read().issue)
        assertEquals(2, rollback.get())
        obstructRecovery = false
        assertNull(repository.read().issue)
        assertEquals(0L, ThemeStorageRecords.decode(File(directory, "catalog.bin").readBytes()).revision)
        assertTrue(File(directory, "staging/${preview.token}/payload/theme.json").isFile)
        assertEquals(1L, repository.apply(request).snapshot.revision)
    }

    @Test fun failedInputAndFailedStageCleanupPreserveTheOriginalIssueAndReportActualCleanup() = runBlocking {
        val directory = temporary.newFolder()
        val source = BytesThemeSource(Fixture.packageOf().zip()).apply { openFailure = IOException("selected source unavailable") }
        val fault = OneShotThemeFault(ThemeStoragePoint.BEFORE_STAGE_DELETE)
        val repository = Fixture.repository(directory, source, faults = fault)
        val original = Fixture.expectSuspending(ThemeIssue.SOURCE_UNAVAILABLE) { repository.prepareImport(Fixture.request()) }
        assertEquals(1, fault.hits.get())
        assertTrue(original.suppressed.any { it is IOException })
        assertEquals(ThemeIssue.CLEANUP_REQUIRED, repository.state.value.issue)
        assertEquals(ThemeBuiltins.defaultChoice, repository.state.value.selected)
        assertNull(repository.preview.value)
        assertEquals(1, checkNotNull(File(directory, "staging").list()).size)
        assertNull(repository.read().issue)
        assertTrue(checkNotNull(File(directory, "staging").list()).isEmpty())
        source.openFailure = null
        val fresh = repository.prepareImport(Fixture.request())
        repository.discardPreview(fresh.token)
    }

    @Test fun unknownAndChangedGlobalFontBudgetsAreNotAssumedToBeZero() = runBlocking {
        val font = Fixture.font()
        val value = Fixture.packageOf(assets = listOf(font), fonts = mapOf("original" to font.id))
        var other: Long? = null
        val repository = Fixture.repository(temporary.newFolder(), BytesThemeSource(value.zip()), otherFontBytes = { other })
        Fixture.expectSuspending(ThemeIssue.FONT_BUDGET_UNAVAILABLE) { repository.prepareImport(Fixture.request()) }
        assertEquals(ThemeBuiltins.defaultChoice, repository.state.value.selected)
        other = 0L
        val preview = repository.prepareImport(Fixture.request())
        assertEquals(font.bytes.size.toLong() + value.zip().size, repository.ownedFontBytes())
        other = LyricsFontLimits.OWNED_BYTES - 1
        Fixture.expectSuspending(ThemeIssue.FONT_QUOTA_EXCEEDED) {
            repository.apply(Fixture.apply(preview, acknowledgment = LyricsFontLimits.LICENSE_ACKNOWLEDGMENT_VERSION))
        }
        assertEquals(ThemeBuiltins.defaultChoice, repository.state.value.selected)
        assertSame(preview, repository.preview.value)
        repository.discardPreview(preview.token)
        assertEquals(0L, repository.ownedFontBytes())
    }

    @Test fun fontLicenseAcknowledgmentPrecedesApplicationAndConsumptionCannotKeepAStaleToken() = runBlocking {
        val font = Fixture.font()
        val value = Fixture.packageOf(assets = listOf(font), fonts = mapOf("original" to font.id))
        val repository = Fixture.repository(temporary.newFolder(), BytesThemeSource(value.zip()))
        val preview = repository.prepareImport(Fixture.request())
        val request = Fixture.apply(preview)
        Fixture.expectSuspending(ThemeIssue.FONT_LICENSE_ACKNOWLEDGMENT_REQUIRED) { repository.apply(request) }
        val consumed = repository.consumePreviewFont(preview.token, LyricsFontRole.ORIGINAL) {
            file, resource, _, signal ->
            signal.throwIfCanceled()
            assertArrayEquals(font.bytes, file.readBytes())
            resource.sha256
        }
        assertEquals(ThemeStorageRecords.digest(font.bytes), consumed)
        val accepted = repository.apply(request.copy(fontLicenseAcknowledgmentVersion = LyricsFontLimits.LICENSE_ACKNOWLEDGMENT_VERSION))
        assertEquals(1L, accepted.snapshot.revision)
        Fixture.expectSuspending(ThemeIssue.PREVIEW_NOT_FOUND) {
            repository.consumePreviewFont(preview.token, LyricsFontRole.ORIGINAL) { _, _, _, _ -> Unit }
        }
    }

    @Test fun inputCloseFailureAndLowSpaceDoNotPublishAPreviewOrChangeTheSavedTheme() = runBlocking {
        val directory = temporary.newFolder()
        val source = BytesThemeSource(Fixture.packageOf().zip()).apply { closeFailure = IOException("controlled close failure") }
        var free = Fixture.ROOM
        val repository = Fixture.repository(directory, source, available = { free })
        repository.read()
        val before = File(directory, "catalog.bin").readBytes()
        Fixture.expectSuspending(ThemeIssue.INPUT_CLOSE_FAILED) { repository.prepareImport(Fixture.request()) }
        assertEquals(1, source.inputCloses.get())
        assertEquals(1, source.descriptorCloses.get())
        assertNull(repository.preview.value)
        assertArrayEquals(before, File(directory, "catalog.bin").readBytes())
        source.closeFailure = null
        free = ThemeLimits.FREE_RESERVE_BYTES
        Fixture.expectSuspending(ThemeIssue.STORAGE_FULL) { repository.prepareImport(Fixture.request()) }
        assertArrayEquals(before, File(directory, "catalog.bin").readBytes())
        assertNull(repository.preview.value)
    }

    @Test fun payloadChangedAfterPreviewCannotInstallItsUndeclaredBytesOrChangeTheSavedChoice() = runBlocking {
        val directory = temporary.newFolder()
        val repository = Fixture.repository(directory, BytesThemeSource(Fixture.packageOf().zip()))
        val preview = repository.prepareImport(Fixture.request())
        val before = File(directory, "catalog.bin").readBytes()
        val extra = File(directory, "staging/${preview.token}/payload/extra.bin").apply { writeBytes(byteArrayOf(1, 3, 5)) }
        Fixture.expectSuspending(ThemeIssue.UNDECLARED_RESOURCE) { repository.apply(Fixture.apply(preview)) }
        assertEquals(ThemeBuiltins.defaultChoice, repository.state.value.selected)
        assertSame(preview, repository.preview.value)
        assertArrayEquals(before, File(directory, "catalog.bin").readBytes())
        assertArrayEquals(byteArrayOf(1, 3, 5), extra.readBytes())
        assertFalse(File(directory, "installed/${preview.token}").exists())
        repository.discardPreview(preview.token)
    }

    @Test fun sourceReadFailureKeepsItsIdentityWhenFinalInputCloseRunsOutOfMemory() = runBlocking {
        val directory = temporary.newFolder()
        val original = IOException("Controlled selected-source read failure")
        val closing = OutOfMemoryError("Controlled final close memory failure")
        val inputCloses = AtomicInteger()
        val descriptors = AtomicInteger()
        val source = object : ThemePackageOpener {
            override fun open(uri: String, signal: CancellationSignal): OpenedThemePackage = OpenedThemePackage(
                object : ByteArrayInputStream(byteArrayOf(1)) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw original
                    override fun close() {
                        inputCloses.incrementAndGet()
                        throw closing
                    }
                }, 1,
            ) { descriptors.incrementAndGet() }
        }
        val repository = Fixture.repository(directory, source)
        repository.read()
        val before = File(directory, "catalog.bin").readBytes()
        val actual = Fixture.expectSuspending(ThemeIssue.SOURCE_UNAVAILABLE) { repository.prepareImport(Fixture.request()) }
        assertSame(original, actual.cause)
        assertTrue(actual.suppressed.any { it === closing })
        assertEquals(1, inputCloses.get())
        assertEquals(1, descriptors.get())
        assertNull(repository.preview.value)
        assertArrayEquals(before, File(directory, "catalog.bin").readBytes())
        assertTrue(checkNotNull(File(directory, "staging").list()).isEmpty())
    }

    @Test fun aFinalCloseMemoryFailureRemainsLowMemoryAndRetiresItsDescriptorAndStage() = runBlocking {
        val directory = temporary.newFolder()
        val closing = OutOfMemoryError("Controlled final close memory failure")
        val source = BytesThemeSource(Fixture.packageOf().zip()).apply { closeFailure = closing }
        val repository = Fixture.repository(directory, source)
        repository.read()
        val before = File(directory, "catalog.bin").readBytes()
        val actual = Fixture.expectSuspending(ThemeIssue.LOW_MEMORY) { repository.prepareImport(Fixture.request()) }
        assertSame(closing, actual.cause)
        assertEquals(1, source.inputCloses.get())
        assertEquals(1, source.descriptorCloses.get())
        assertNull(repository.preview.value)
        assertArrayEquals(before, File(directory, "catalog.bin").readBytes())
        assertTrue(checkNotNull(File(directory, "staging").list()).isEmpty())
    }

    @Test fun fontAccountingRejectsUndeclaredStagedBytesAndAChangedInputArchiveLength() = runBlocking {
        val directory = temporary.newFolder()
        val value = Fixture.packageOf()
        val archiveBytes = value.zip()
        val source = BytesThemeSource(archiveBytes)
        val repository = Fixture.repository(directory, source)
        val preview = repository.prepareImport(Fixture.request())
        val extra = File(directory, "staging/${preview.token}/payload/unlisted.ttf").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        Fixture.expectSuspending(ThemeIssue.UNDECLARED_RESOURCE) { repository.ownedFontBytes() }
        assertArrayEquals(byteArrayOf(1, 2, 3), extra.readBytes())
        assertTrue(extra.delete())
        val stagedArchive = File(directory, "staging/${preview.token}/input.zip")
        stagedArchive.writeBytes(archiveBytes + byteArrayOf(1))
        Fixture.expectSuspending(ThemeIssue.INTEGRITY_FAILED) { repository.ownedFontBytes() }
        assertSame(preview, repository.preview.value)
        assertEquals(ThemeBuiltins.defaultChoice, repository.state.value.selected)
        stagedArchive.writeBytes(archiveBytes)
        assertEquals(0L, repository.ownedFontBytes())
        assertArrayEquals(archiveBytes, source.bytes)
        repository.discardPreview(preview.token)
    }
}
