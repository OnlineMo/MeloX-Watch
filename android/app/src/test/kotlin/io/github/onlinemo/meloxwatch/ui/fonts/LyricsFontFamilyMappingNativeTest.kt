package io.github.onlinemo.meloxwatch.ui.fonts

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.font.resolveAsTypeface
import io.github.onlinemo.meloxwatch.data.fonts.AndroidLyricsFontDecoder
import io.github.onlinemo.meloxwatch.data.fonts.AndroidLyricsFontFiles
import io.github.onlinemo.meloxwatch.data.fonts.BytesFontStream
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontIssue
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSelection
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontTestFixtures as Fixture
import io.github.onlinemo.meloxwatch.data.fonts.initializeNativeLyricsFonts
import io.github.onlinemo.meloxwatch.data.fonts.nativeFontPixels
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LyricsFontFamilyMappingNativeTest {
    @get:Rule val temporary = TemporaryFolder()

    @Before fun initializeNativeFonts() = initializeNativeLyricsFonts()

    @Test fun composePreservesBothRealAxisFacesWithoutSynthesisAndFallbackKeepsTheOtherRole() = runBlocking {
        val files = AndroidLyricsFontFiles(temporary.newFolder("compose-fonts"), LocalProviderIo(),
            BytesFontStream(Fixture.bytes()), AndroidLyricsFontDecoder(), { Fixture.ROOM })
        val first = files.importFont(Fixture.request(), emptySet())
        val second = files.importFont(Fixture.request(), setOf(first.asset.id))
        val original = files.loadFont(LyricsFontSelection(first.asset, 200f), "MeloX")
        val translation = files.loadFont(LyricsFontSelection(second.asset, 650f), "Watch")
        val pair = lyricsFontFamilies(original, translation)
        val resolver = createFontFamilyResolver(RuntimeEnvironment.getApplication())
        // Loaded families must preserve the supplied native axis even with a different style hint.
        val composedOriginal = resolver.resolveAsTypeface(pair.originalFamily, FontWeight.Black,
            FontStyle.Normal, FontSynthesis.None).value
        val composedTranslation = resolver.resolveAsTypeface(pair.translationFamily, FontWeight.Thin,
            FontStyle.Normal, FontSynthesis.None).value
        val originalPixels = nativeFontPixels(checkNotNull(original.typeface))
        val translationPixels = nativeFontPixels(checkNotNull(translation.typeface))
        assertTrue(originalPixels.contentEquals(nativeFontPixels(composedOriginal)))
        assertTrue(translationPixels.contentEquals(nativeFontPixels(composedTranslation)))
        assertFalse(originalPixels.contentEquals(translationPixels))
        val missing = files.loadFont(LyricsFontSelection(first.asset, 200f), "永")
        assertEquals(LyricsFontIssue.MISSING_GLYPHS, missing.issue)
        val fallbackPair = lyricsFontFamilies(missing, translation)
        val fallback = resolver.resolveAsTypeface(fallbackPair.originalFamily, fallbackPair.originalWeight,
            FontStyle.Normal, FontSynthesis.None).value
        val keptTranslation = resolver.resolveAsTypeface(fallbackPair.translationFamily, fallbackPair.translationWeight,
            FontStyle.Normal, FontSynthesis.None).value
        assertTrue(nativeFontPixels(fallback).any { android.graphics.Color.alpha(it) > 0 })
        assertTrue(translationPixels.contentEquals(nativeFontPixels(keptTranslation)))
    }
}
