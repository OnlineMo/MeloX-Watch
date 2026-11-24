package io.github.onlinemo.meloxwatch.data.fonts.store

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.font.resolveAsTypeface
import io.github.onlinemo.meloxwatch.data.fonts.AndroidLyricsFontDecoder
import io.github.onlinemo.meloxwatch.data.fonts.FontWeightSupport
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontRole
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontTestFixtures as Fixture
import io.github.onlinemo.meloxwatch.data.fonts.initializeNativeLyricsFonts
import io.github.onlinemo.meloxwatch.data.fonts.nativeFontPixels
import io.github.onlinemo.meloxwatch.ui.fonts.lyricsFontFamilies
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real file CAS, original TTF bytes, native faces and Compose mapping; device evidence remains separate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class FontSettingsNativeIntegrationTest : FontSettingsFileFixture() {
    @Before fun initializeNativeFonts() = initializeNativeLyricsFonts()

    @Test(timeout = 30000L)
    fun reopenedRoleWeightsKeepTheirDistinctNativeGlyphsAndDisablingOnePreservesTheOther() = runBlocking {
        val file = settingsFile()
        val session = openSettings(file)
        val fixture = FontSettingsEditorFixture(temporary.newFolder("native-persistence"), session.store, AndroidLyricsFontDecoder())
        fixture.choose(LyricsFontRole.ORIGINAL, Fixture.VARIABLE, 200f)
        fixture.choose(LyricsFontRole.TRANSLATION, Fixture.VARIABLE, 650f)
        val actual = session.store.read()
        val originalPixels = nativeFontPixels(checkNotNull(fixture.editor.state.value.original.typeface))
        val translationPixels = nativeFontPixels(checkNotNull(fixture.editor.state.value.translation.typeface))
        assertFalse(originalPixels.contentEquals(translationPixels))
        session.close()

        val reopened = openSettings(file)
        val restored = fixture.newEditor(reopened.store)
        restored.restore(fixture.text)
        val state = restored.state.value
        assertEquals(actual, state.saved)
        assertEquals(FontWeightSupport.AVAILABLE, state.original.capability.support)
        assertEquals(FontWeightSupport.AVAILABLE, state.translation.capability.support)
        assertEquals(200f, state.original.configuredAxisWeight)
        assertEquals(650f, state.translation.configuredAxisWeight)
        val pair = lyricsFontFamilies(state.original, state.translation)
        val resolver = createFontFamilyResolver(RuntimeEnvironment.getApplication())
        val originalFace = resolver.resolveAsTypeface(pair.originalFamily, pair.originalWeight, FontStyle.Normal, FontSynthesis.None).value
        val translationFace = resolver.resolveAsTypeface(pair.translationFamily, pair.translationWeight, FontStyle.Normal, FontSynthesis.None).value
        assertTrue(originalPixels.contentEquals(nativeFontPixels(originalFace)))
        assertTrue(translationPixels.contentEquals(nativeFontPixels(translationFace)))

        restored.begin(LyricsFontRole.ORIGINAL, fixture.text)
        restored.previewSystemFont()
        restored.confirm(fixture.text)
        val cleared = reopened.store.read()
        assertEquals(actual.revision + 1L, cleared.revision)
        assertNull(cleared.original.asset)
        assertNull(cleared.original.requestedWeight)
        assertEquals(actual.translation, cleared.translation)
        assertTrue(translationPixels.contentEquals(nativeFontPixels(checkNotNull(restored.state.value.translation.typeface))))
        assertTrue(fixture.assetFile(checkNotNull(actual.translation.asset)).isFile)
        assertFalse(fixture.assetFile(checkNotNull(actual.original.asset)).exists())
        fixture.assertOriginalsUnchanged()
    }
}
