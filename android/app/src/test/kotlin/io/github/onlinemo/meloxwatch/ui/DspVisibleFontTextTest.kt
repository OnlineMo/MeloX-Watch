package io.github.onlinemo.meloxwatch.ui

import android.app.Application
import android.content.res.Configuration
import io.github.onlinemo.meloxwatch.R
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontException
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontIssue
import io.github.onlinemo.meloxwatch.playback.dsp.DspLimits
import io.github.onlinemo.meloxwatch.ui.fonts.ThemeUiFontText
import io.github.onlinemo.meloxwatch.ui.fonts.themeUiFontCoverage
import io.github.onlinemo.meloxwatch.ui.library.LibraryUiState
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistEntryUiState
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistUiState
import io.github.onlinemo.meloxwatch.ui.queue.QueueViewerState
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Page ownership and real localized corpus tests; displayed flags do not prove device clipping. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], application = Application::class)
class DspVisibleFontTextTest {
    @Test fun onlyDisplayedPresetControlsAndTheCurrentInputContributeText() {
        val corpus = DspVisibleFontText { true }
        control(corpus, "first", DspNamedTextRole.CHOICE, "Visible 永")
        control(corpus, "second", DspNamedTextRole.RENAME, "Hidden 音", displayed = false)
        val input = corpus.input().also { it.attach(); it.present("Draft 乐", true) }
        assertEquals(listOf("Draft 乐", "Visible 永"), corpus.snapshot.parts)
        input.present("Draft 乐", false)
        assertEquals(listOf("Visible 永"), corpus.snapshot.parts)
        assertNull(corpus.snapshot.issue)
    }

    @Test fun inputEditsReplaceTheVisibleCorpusAndOldSnapshotsStayFrozen() {
        val corpus = DspVisibleFontText { true }
        val input = corpus.input().also { it.attach(); it.present("Before", true) }
        val before = corpus.snapshot
        input.present("After 音", true)
        assertEquals(listOf("After 音"), corpus.snapshot.parts)
        assertEquals(listOf("Before"), before.parts)
        input.present("", true)
        assertEquals(emptyList<String>(), corpus.snapshot.parts)
        input.close()
        assertNull(corpus.snapshot.issue)
    }

    @Test fun duplicateRolesAndTheirRetirementKeepTheSameFontIdentity() {
        val corpus = DspVisibleFontText { true }
        control(corpus, "first", DspNamedTextRole.CHOICE, "Curve")
        val snapshot = corpus.snapshot
        val rename = control(corpus, "first", DspNamedTextRole.RENAME, "Curve")
        val delete = control(corpus, "first", DspNamedTextRole.DELETE, "Curve")
        assertSame(snapshot, corpus.snapshot)
        rename.present("Curve", true)
        rename.close()
        delete.close()
        assertSame(snapshot, corpus.snapshot)
    }

    @Test fun equalVisibleSetsUseStableOrderAcrossRoleRegistrationAndReplacement() {
        val first = DspVisibleFontText { true }
        control(first, "b", DspNamedTextRole.DELETE, "Zeta")
        control(first, "a", DspNamedTextRole.CHOICE, "Alpha")
        val before = first.snapshot
        val duplicate = control(first, "b", DspNamedTextRole.RENAME, "Zeta")
        val second = DspVisibleFontText { true }
        control(second, "a", DspNamedTextRole.REPLACE, "Alpha")
        control(second, "b", DspNamedTextRole.CHOICE, "Zeta")
        assertEquals(second.snapshot, first.snapshot)
        duplicate.close()
        assertSame(before, first.snapshot)
    }

    @Test fun replacedControlIgnoresLateUpdatesCloseAndReattachmentFromTheOldToken() {
        val corpus = DspVisibleFontText { true }
        val old = control(corpus, "curve", DspNamedTextRole.DELETE, "Old")
        val current = control(corpus, "curve", DspNamedTextRole.DELETE, "Current")
        val snapshot = corpus.snapshot
        old.present("Late", true)
        old.close()
        old.attach()
        old.present("Resurrected", true)
        assertSame(snapshot, corpus.snapshot)
        current.present("Edited", true)
        assertEquals(listOf("Edited"), corpus.snapshot.parts)
    }

    @Test fun retiredPageCannotRetainOrReviveTextAndCannotClearTheNewOwner() {
        val old = DspVisibleFontText { true }
        val registration = control(old, "curve", DspNamedTextRole.CHOICE, "Old")
        val current = DspVisibleFontText { true }
        control(current, "curve", DspNamedTextRole.CHOICE, "Current")
        val snapshot = current.snapshot
        old.close()
        registration.present("Late", true)
        registration.close()
        registration.attach()
        old.input().also { it.attach(); it.present("Late input", true) }
        old.close()
        assertSame(DspFontTextSnapshot.EMPTY, old.snapshot)
        assertSame(snapshot, current.snapshot)
    }

    @Test fun hiddenPageRejectsLateLayoutCallbacksEvenAfterVisibilityReturns() {
        var visible = true
        val corpus = DspVisibleFontText { visible }
        val registration = control(corpus, "curve", DspNamedTextRole.CHOICE, "Visible")
        visible = false
        assertSame(DspFontTextSnapshot.EMPTY, corpus.snapshot)
        registration.present("Late", true)
        visible = true
        registration.attach()
        registration.present("Resurrected", true)
        assertSame(DspFontTextSnapshot.EMPTY, corpus.snapshot)
    }

    @Test fun allThirtyTwoPresetsFourRolesAndInputFitWithoutDuplicatedNames() {
        val corpus = DspVisibleFontText { true }
        repeat(DspLimits.MAX_PRESETS) { index ->
            val text = index.toString().padStart(2, '0') + "永".repeat(DspLimits.MAX_NAME_CHARACTERS - 2)
            for (role in presetRoles) control(corpus, "curve-$index", role, text)
        }
        corpus.input().also { it.attach(); it.present("音".repeat(DspLimits.MAX_NAME_CHARACTERS), true) }
        assertEquals(DspLimits.MAX_PRESETS + 1, corpus.snapshot.parts.size)
        assertTrue(corpus.snapshot.parts.all { it.length == DspLimits.MAX_NAME_CHARACTERS })
        assertNull(corpus.snapshot.issue)
    }

    @Test fun extraPresetRejectsTheWholeCorpusInsteadOfReturningPartialTrustedText() {
        val corpus = DspVisibleFontText { true }
        repeat(DspLimits.MAX_PRESETS) { control(corpus, "curve-$it", DspNamedTextRole.CHOICE, "Curve $it") }
        control(corpus, "extra", DspNamedTextRole.DELETE, "Extra")
        assertEquals(emptyList<String>(), corpus.snapshot.parts)
        assertEquals(LyricsFontIssue.INVALID_INPUT, corpus.snapshot.issue)
        val captured = ThemeUiFontText.capture(corpus.snapshot.parts, corpus.snapshot.issue)
        assertEquals(LyricsFontIssue.INVALID_INPUT, captured.issue)
        assertTrue(captured.parts.isEmpty())
    }

    @Test fun oversizedHiddenNameDoesNotFailUntilItActuallyBecomesVisible() {
        val corpus = DspVisibleFontText { true }
        val text = "永".repeat(DspLimits.MAX_NAME_CHARACTERS + 1)
        val registration = control(corpus, "curve", DspNamedTextRole.CHOICE, text, displayed = false)
        assertNull(corpus.snapshot.issue)
        registration.present(text, true)
        assertEquals(LyricsFontIssue.INVALID_INPUT, corpus.snapshot.issue)
        assertTrue(corpus.snapshot.parts.isEmpty())
    }

    @Test fun inputAcceptsSixtyFourUtf16UnitsAndRejectsAnExtraUnitWithoutTruncation() {
        val corpus = DspVisibleFontText { true }
        val input = corpus.input().also { it.attach() }
        val text = "乐".repeat(DspLimits.MAX_NAME_CHARACTERS)
        input.present(text, true)
        assertEquals(listOf(text), corpus.snapshot.parts)
        input.present(text + "!", true)
        assertEquals(LyricsFontIssue.INVALID_INPUT, corpus.snapshot.issue)
        assertTrue(corpus.snapshot.parts.isEmpty())
    }

    @Test fun closingAllRolesFreesAPresetSlotForADifferentControl() {
        val corpus = DspVisibleFontText { true }
        val retired = presetRoles.map { control(corpus, "curve-0", it, "Curve 0") }
        repeat(DspLimits.MAX_PRESETS - 1) { control(corpus, "curve-${it + 1}", DspNamedTextRole.CHOICE, "Curve ${it + 1}") }
        retired.forEach { it.close() }
        control(corpus, "replacement", DspNamedTextRole.CHOICE, "Replacement")
        assertNull(corpus.snapshot.issue)
        assertEquals(DspLimits.MAX_PRESETS, corpus.snapshot.parts.size)
        assertFalse("Curve 0" in corpus.snapshot.parts)
    }

    @Test fun dispatcherIncludesDspTextOnlyForTheVisibleDspSettingsItem() {
        val snapshot = DspFontTextSnapshot(listOf("Private curve", "Private draft"), null)
        assertEquals(snapshot.parts, dispatched(ProbePage.DSP, listOf("dsp:settings"), snapshot))
        assertTrue(dispatched(ProbePage.DSP, emptyList(), snapshot).isEmpty())
        assertTrue(dispatched(ProbePage.HOME, listOf("dsp:settings"), snapshot).isEmpty())
        assertTrue(dispatched(ProbePage.DSP, listOf("dsp:settings"), null).isEmpty())
    }

    @Test fun englishResourcesAndActualDspNamesReachBothUiFontRoles(): Unit = runBlocking {
        assertLocalizedCoverage(Locale.US)
    }

    @Test fun chineseResourcesAndActualDspNamesReachBothUiFontRoles(): Unit = runBlocking {
        assertLocalizedCoverage(Locale.SIMPLIFIED_CHINESE)
    }

    private suspend fun assertLocalizedCoverage(locale: Locale) {
        val app = RuntimeEnvironment.getApplication()
        val configuration = Configuration(app.resources.configuration).apply { setLocale(locale) }
        val resources = app.createConfigurationContext(configuration).resources
        val corpus = DspVisibleFontText { true }
        control(corpus, "curve", DspNamedTextRole.CHOICE, "Private 乐 curve")
        corpus.input().also { it.attach(); it.present("Current 永 draft", true) }
        val captured = ThemeUiFontText.capture(corpus.snapshot.parts, corpus.snapshot.issue)
        val text = themeUiFontCoverage(resources, captured) { true }
        assertEquals(text.original, text.translation)
        for (id in intArrayOf(R.string.dsp_presets, R.string.dsp_preset_name, R.string.dsp_rename_preset,
            R.string.dsp_delete_confirm, R.string.dsp_actual_eq, R.string.dsp_tag_ape,
            R.string.dsp_output_unsupported, R.string.dsp_name_conflict, R.string.dsp_save)) {
            assertTrue(text.original.contains(resources.getString(id)))
        }
        assertTrue(text.original.contains("Private 乐 curve"))
        assertTrue(text.original.contains("Current 永 draft"))
        val rejected = ThemeUiFontText.capture(listOf("Private rejected"), LyricsFontIssue.INVALID_INPUT)
        try {
            themeUiFontCoverage(resources, rejected) { true }
            fail("Rejected source text must not produce a partial trusted font corpus")
        } catch (error: LyricsFontException) { assertEquals(LyricsFontIssue.INVALID_INPUT, error.issue) }
    }

    private fun dispatched(page: ProbePage, keys: List<Any>, snapshot: DspFontTextSnapshot?): List<String> =
        probePageVisibleText(page, ProbeUiState(), LibraryUiState(), QueueViewerState(), PlaylistUiState(),
            PlaylistEntryUiState(), keys, dspText = snapshot)

    private fun control(corpus: DspVisibleFontText, id: String, role: DspNamedTextRole, text: String,
        displayed: Boolean = true): DspVisibleFontText.Registration =
        corpus.preset(id, role).also { it.attach(); it.present(text, displayed) }

    private val presetRoles = listOf(DspNamedTextRole.CHOICE, DspNamedTextRole.RENAME,
        DspNamedTextRole.REPLACE, DspNamedTextRole.DELETE)
}
