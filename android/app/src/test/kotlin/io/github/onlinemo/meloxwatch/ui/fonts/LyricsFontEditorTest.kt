package io.github.onlinemo.meloxwatch.ui.fonts

import android.os.CancellationSignal
import io.github.onlinemo.meloxwatch.data.fonts.AndroidLyricsFontFiles
import io.github.onlinemo.meloxwatch.data.fonts.FilesOnlyFontDecoder
import io.github.onlinemo.meloxwatch.data.fonts.FontWeightSupport
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontImportRequest
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontIssue
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontLimits
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontRole
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSaveResult
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSettings
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSettingsStore
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontStreamOpener
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontTestFixtures as Fixture
import io.github.onlinemo.meloxwatch.data.fonts.OpenedLyricsFontStream
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Owner persistence/preview contracts use real files; native glyph proof lives in data/fonts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class LyricsFontEditorTest {
    @get:Rule val temporary = TemporaryFolder()
    private val text = LyricsFontPreviewText("MeloX Watch", "Music")

    @Test fun twoRolesKeepIndependentFontsAndWeightsAcrossOwnerRecreation() = runBlocking {
        val fixture = owner()
        choose(fixture, LyricsFontRole.ORIGINAL, Fixture.VARIABLE, 600f)
        choose(fixture, LyricsFontRole.TRANSLATION, Fixture.STATIC, null)
        val saved = fixture.settings.actual
        assertEquals(Fixture.VARIABLE, saved.original.asset?.displayName)
        assertEquals(Fixture.STATIC, saved.translation.asset?.displayName)
        assertEquals(600f, saved.original.requestedWeight)
        assertNull(saved.translation.requestedWeight)
        val restored = LyricsFontEditor(fixture.settings, fixture.files())
        restored.restore(text)
        assertEquals(saved, restored.state.value.saved)
        assertEquals(600f, restored.state.value.original.configuredAxisWeight)
        assertNull(restored.state.value.translation.configuredAxisWeight)
        assertEquals(FontWeightSupport.NO_AXIS, restored.state.value.translation.capability.support)
        assertTrue(fixture.originals.values.all(File::isFile))
    }

    @Test fun previewCancellationRestoresTrueSavedWeightAndRetiresOnlyTheUnsavedPrivateCopy() = runBlocking {
        val fixture = owner()
        choose(fixture, LyricsFontRole.ORIGINAL, Fixture.VARIABLE, 400f)
        fixture.editor.begin(LyricsFontRole.ORIGINAL, text)
        fixture.editor.previewWeight(650f, text)
        assertEquals(650f, fixture.editor.state.value.original.configuredAxisWeight)
        assertEquals(400f, fixture.settings.actual.original.requestedWeight)
        fixture.editor.importFont(request(Fixture.STATIC), text)
        val candidate = checkNotNull(fixture.editor.state.value.draft?.selection?.asset)
        fixture.editor.cancel(text)
        assertEquals(400f, fixture.editor.state.value.original.configuredAxisWeight)
        assertEquals(400f, fixture.editor.state.value.saved?.original?.requestedWeight)
        assertNull(fixture.editor.state.value.draft)
        assertFalse(File(fixture.directory, candidate.id + ".font").exists())
        assertTrue(fixture.originals.values.all(File::isFile))
    }

    @Test fun failedSaveReReadsActualValuesAndExplicitRetrySavesTheRetainedProposal() = runBlocking {
        val fixture = owner()
        choose(fixture, LyricsFontRole.ORIGINAL, Fixture.VARIABLE, 400f)
        fixture.editor.begin(LyricsFontRole.ORIGINAL, text)
        fixture.editor.previewWeight(650f, text)
        fixture.settings.failBeforeWrite = true
        fixture.editor.confirm(text)
        val failure = fixture.editor.state.value
        assertEquals(LyricsFontIssue.SAVE_FAILED, failure.issue)
        assertEquals(400f, failure.saved?.original?.requestedWeight)
        assertEquals(400f, failure.original.configuredAxisWeight)
        assertEquals(650f, failure.retry?.selection?.requestedWeight)
        assertTrue(failure.canRetrySave)
        assertFalse(failure.canConfirm)
        fixture.editor.retrySave(text)
        assertEquals(650f, fixture.settings.actual.original.requestedWeight)
        assertEquals(650f, fixture.editor.state.value.original.configuredAxisWeight)
        assertNull(fixture.editor.state.value.retry)
        assertNull(fixture.editor.state.value.issue)
    }

    @Test fun aWriteThatCommittedBeforeItsErrorIsReadBackWithoutASecondSave() = runBlocking {
        val fixture = owner()
        choose(fixture, LyricsFontRole.ORIGINAL, Fixture.VARIABLE, 400f)
        fixture.editor.begin(LyricsFontRole.ORIGINAL, text)
        fixture.editor.previewWeight(600f, text)
        val revision = fixture.settings.actual.revision
        fixture.settings.failAfterWrite = true
        fixture.editor.confirm(text)
        assertEquals(revision + 1L, fixture.settings.actual.revision)
        assertEquals(fixture.settings.actual, fixture.editor.state.value.saved)
        assertEquals(600f, fixture.editor.state.value.original.configuredAxisWeight)
        assertNull(fixture.editor.state.value.retry)
        assertNull(fixture.editor.state.value.issue)
    }

    @Test fun revisionConflictRestoresTheOtherWriterAndNeedsAnExplicitProposalRetry() = runBlocking {
        val fixture = owner()
        choose(fixture, LyricsFontRole.ORIGINAL, Fixture.VARIABLE, 400f)
        choose(fixture, LyricsFontRole.TRANSLATION, Fixture.STATIC, null)
        val translation = fixture.settings.actual.translation
        fixture.editor.begin(LyricsFontRole.ORIGINAL, text)
        fixture.editor.previewWeight(650f, text)
        val current = fixture.settings.actual
        fixture.settings.actual = current.copy(revision = current.revision + 1L,
            original = current.original.copy(requestedWeight = 300f))
        fixture.editor.confirm(text)
        assertEquals(LyricsFontIssue.SETTINGS_CONFLICT, fixture.editor.state.value.issue)
        assertEquals(300f, fixture.editor.state.value.original.configuredAxisWeight)
        assertEquals(650f, fixture.editor.state.value.retry?.selection?.requestedWeight)
        assertEquals(translation, fixture.editor.state.value.saved?.translation)
        assertEquals(translation, fixture.settings.actual.translation)
        fixture.editor.retrySave(text)
        assertEquals(650f, fixture.settings.actual.original.requestedWeight)
        assertEquals(translation, fixture.settings.actual.translation)
    }

    @Test fun unreadableStorageAfterFailedWriteNeverClaimsADefaultOrDeletesTheProposal() = runBlocking {
        val fixture = owner()
        choose(fixture, LyricsFontRole.ORIGINAL, Fixture.VARIABLE, 400f)
        fixture.editor.begin(LyricsFontRole.ORIGINAL, text)
        fixture.editor.importFont(request(Fixture.STATIC), text)
        val proposal = checkNotNull(fixture.editor.state.value.draft?.selection?.asset)
        fixture.settings.failBeforeWrite = true
        fixture.settings.failReads = true
        fixture.editor.confirm(text)
        assertNull(fixture.editor.state.value.saved)
        assertTrue(fixture.editor.state.value.original.usesSystemFont)
        assertEquals(LyricsFontIssue.SETTINGS_UNAVAILABLE, fixture.editor.state.value.issue)
        assertEquals(proposal, fixture.editor.state.value.retry?.selection?.asset)
        assertTrue(File(fixture.directory, proposal.id + ".font").isFile)
        fixture.settings.failReads = false
        fixture.editor.retrySave(text)
        assertEquals(proposal, fixture.settings.actual.original.asset)
        assertNotNull(fixture.editor.state.value.original.typeface)
    }

    @Test fun disablingCustomFontAffectsOnlyItsRoleAndPreservesBothExternalOriginals() = runBlocking {
        val fixture = owner()
        choose(fixture, LyricsFontRole.ORIGINAL, Fixture.VARIABLE, 600f)
        choose(fixture, LyricsFontRole.TRANSLATION, Fixture.STATIC, null)
        val original = checkNotNull(fixture.settings.actual.original.asset)
        val translation = fixture.settings.actual.translation
        fixture.editor.begin(LyricsFontRole.ORIGINAL, text)
        fixture.editor.previewSystemFont()
        assertTrue(fixture.editor.state.value.original.usesSystemFont)
        assertNotNull(fixture.settings.actual.original.asset)
        fixture.editor.confirm(text)
        assertNull(fixture.settings.actual.original.asset)
        assertNull(fixture.settings.actual.original.requestedWeight)
        assertEquals(translation, fixture.settings.actual.translation)
        assertFalse(File(fixture.directory, original.id + ".font").exists())
        assertTrue(File(fixture.directory, checkNotNull(translation.asset).id + ".font").isFile)
        fixture.originals.forEach { (name, file) -> assertEquals(Fixture.sha256(Fixture.bytes(name)), Fixture.sha256(file.readBytes())) }
    }

    @Test fun switchingToAStaticFontDisablesWeightAndNeverReportsTheOldRequestAsConfigured() = runBlocking {
        val fixture = owner()
        choose(fixture, LyricsFontRole.ORIGINAL, Fixture.VARIABLE, 650f)
        fixture.editor.begin(LyricsFontRole.ORIGINAL, text)
        fixture.editor.importFont(request(Fixture.STATIC), text)
        assertFalse(fixture.editor.state.value.canAdjustWeight)
        assertEquals(FontWeightSupport.NO_AXIS, fixture.editor.state.value.original.capability.support)
        assertEquals(650f, fixture.editor.state.value.original.requestedWeight)
        assertNull(fixture.editor.state.value.original.configuredAxisWeight)
        fixture.editor.previewWeight(700f, text)
        assertEquals(LyricsFontIssue.INVALID_INPUT, fixture.editor.state.value.issue)
        assertNull(fixture.editor.state.value.original.configuredAxisWeight)
        assertEquals(650f, fixture.editor.state.value.draft?.selection?.requestedWeight)
        fixture.editor.previewWeight(null, text)
        assertNull(fixture.editor.state.value.draft?.selection?.requestedWeight)
        fixture.editor.confirm(text)
        assertEquals(Fixture.STATIC, fixture.settings.actual.original.asset?.displayName)
        assertNull(fixture.settings.actual.original.requestedWeight)
    }

    @Test fun missingGlyphPreviewIsAVisibleSystemFallbackAndCannotBeConfirmedAsAValidRender() = runBlocking {
        val fixture = owner()
        fixture.editor.begin(LyricsFontRole.ORIGINAL, text)
        fixture.editor.importFont(request(Fixture.VARIABLE), LyricsFontPreviewText("永", "Music"))
        assertEquals(LyricsFontIssue.MISSING_GLYPHS, fixture.editor.state.value.original.issue)
        assertTrue(fixture.editor.state.value.original.usesSystemFont)
        assertFalse(fixture.editor.state.value.canConfirm)
        fixture.editor.confirm(text)
        assertNull(fixture.settings.actual.original.asset)
        fixture.editor.cancel(text)
        assertTrue(checkNotNull(fixture.directory.list()).isEmpty())
    }

    @Test fun failedReadDuringCancelDoesNotTurnTheCancelledDraftIntoASaveRetry() = runBlocking {
        val fixture = owner()
        choose(fixture, LyricsFontRole.ORIGINAL, Fixture.VARIABLE, 400f)
        fixture.editor.begin(LyricsFontRole.ORIGINAL, text)
        fixture.editor.importFont(request(Fixture.STATIC), text)
        val unconfirmed = checkNotNull(fixture.editor.state.value.draft?.selection?.asset)
        fixture.settings.failReads = true
        fixture.editor.cancel(text)
        assertNull(fixture.editor.state.value.saved)
        assertNull(fixture.editor.state.value.retry)
        assertFalse(fixture.editor.state.value.canRetrySave)
        assertTrue(File(fixture.directory, unconfirmed.id + ".font").isFile)
        fixture.settings.failReads = false
        fixture.editor.restore(text)
        assertEquals(400f, fixture.editor.state.value.saved?.original?.requestedWeight)
        assertFalse(File(fixture.directory, unconfirmed.id + ".font").exists())
    }

    @Test fun malformedFontKeepsTheExistingDraftPreviewAndSavedSettings() = runBlocking {
        val fixture = owner()
        choose(fixture, LyricsFontRole.ORIGINAL, Fixture.VARIABLE, 400f)
        val saved = fixture.settings.actual
        fixture.editor.begin(LyricsFontRole.ORIGINAL, text)
        fixture.originals.getValue(Fixture.STATIC).writeBytes("invalid font contents".toByteArray())
        fixture.editor.importFont(request(Fixture.STATIC), text)
        assertEquals(LyricsFontIssue.UNSUPPORTED_FORMAT, fixture.editor.state.value.issue)
        assertEquals(saved, fixture.settings.actual)
        assertEquals(saved.original, fixture.editor.state.value.draft?.selection)
        assertEquals(400f, fixture.editor.state.value.original.configuredAxisWeight)
    }

    private fun owner(): FontOwnerFixture {
        val directory = temporary.newFolder("owned")
        val originalsDirectory = temporary.newFolder("selected")
        val originals = listOf(Fixture.VARIABLE, Fixture.STATIC).associateWith { Fixture.file(originalsDirectory, it) }
        return FontOwnerFixture(directory, originals)
    }

    private suspend fun choose(fixture: FontOwnerFixture, role: LyricsFontRole, name: String, weight: Float?) {
        fixture.editor.begin(role, text)
        fixture.editor.importFont(request(name), text)
        if (weight != null) fixture.editor.previewWeight(weight, text)
        assertTrue(fixture.editor.state.value.canConfirm)
        fixture.editor.confirm(text)
        assertNull(fixture.editor.state.value.issue)
    }

    private fun request(name: String): LyricsFontImportRequest = LyricsFontImportRequest(
        "content://owner-font/$name", name, LyricsFontLimits.LICENSE_ACKNOWLEDGMENT_VERSION,
    )
}

private class FontOwnerFixture(val directory: File, val originals: Map<String, File>) {
    val settings = MemoryFontSettings()
    val editor = LyricsFontEditor(settings, files())
    fun files(): AndroidLyricsFontFiles {
        val source = LyricsFontStreamOpener { uri: String, signal: CancellationSignal ->
            signal.throwIfCanceled()
            val original = originals.getValue(uri.substringAfterLast('/'))
            OpenedLyricsFontStream(FileInputStream(original), original.length())
        }
        return AndroidLyricsFontFiles(directory, LocalProviderIo(), source, FilesOnlyFontDecoder(), { Fixture.ROOM })
    }
}

private class MemoryFontSettings : LyricsFontSettingsStore {
    var actual = LyricsFontSettings()
    var failBeforeWrite = false
    var failAfterWrite = false
    var failReads = false
    override suspend fun read(): LyricsFontSettings {
        if (failReads) throw IOException("Controlled preference read failure")
        return actual
    }
    override suspend fun compareAndSet(expectedRevision: Long, replacement: LyricsFontSettings): LyricsFontSaveResult {
        if (actual.revision != expectedRevision) return LyricsFontSaveResult.Conflict(actual)
        if (failBeforeWrite) {
            failBeforeWrite = false
            throw IOException("Controlled preference failure before write")
        }
        actual = replacement.copy(revision = actual.revision + 1L)
        if (failAfterWrite) {
            failAfterWrite = false
            throw IOException("Controlled preference failure after write")
        }
        return LyricsFontSaveResult.Saved(actual)
    }
}
