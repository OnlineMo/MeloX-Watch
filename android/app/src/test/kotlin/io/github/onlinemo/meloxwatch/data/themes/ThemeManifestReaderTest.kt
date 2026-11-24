package io.github.onlinemo.meloxwatch.data.themes

import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontRole
import io.github.onlinemo.meloxwatch.data.themes.ThemeTestFixtures as Fixture
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30], manifest = Config.NONE)
class ThemeManifestReaderTest {
    @Test fun staticThemeHasIndependentOriginalAndTranslationResourcesAndLiteralLicenses() {
        val original = Fixture.font()
        val translation = Fixture.font("SourceCodePro-Regular.otf", "font.translation")
        val value = Fixture.packageOf(assets = listOf(original, translation),
            fonts = mapOf("original" to original.id, "translation" to translation.id))
        val manifest = ThemeManifestReader.read(value.manifest)
        assertEquals(1, manifest.schemaVersion)
        assertEquals(original.id, manifest.fonts[LyricsFontRole.ORIGINAL])
        assertEquals(translation.id, manifest.fonts[LyricsFontRole.TRANSLATION])
        assertEquals(original.licenseText, manifest.resource(original.id)?.license?.text)
        assertEquals(translation.licenseText, manifest.resource(translation.id)?.license?.text)
        assertFalse(manifest.license.text.isBlank())
    }

    @Test fun customImageMustResolveToADeclaredImageAndOtherModesCannotSmuggleAnImageReference() {
        val image = Fixture.Asset("cover", "assets/cover.png", byteArrayOf(1))
        val custom = JSONObject().put("mode", "customImage").put("image", image.id)
        assertEquals(image.id, ThemeManifestReader.read(Fixture.packageOf(assets = listOf(image), background = custom)
            .manifest).background.imageResourceId)
        Fixture.expect(ThemeIssue.MISSING_RESOURCE) {
            ThemeManifestReader.read(Fixture.packageOf(background = custom).manifest)
        }
        Fixture.expect(ThemeIssue.MALFORMED_MANIFEST) {
            ThemeManifestReader.read(Fixture.packageOf(assets = listOf(image),
                background = JSONObject().put("mode", "black").put("image", image.id)).manifest)
        }
    }

    @Test fun unknownTopLevelAndNestedExecutableOrRemoteFieldsAreRejected() {
        for (field in listOf("script", "layout", "remoteEndpoint", "html")) {
            val value = Fixture.manifest().put(field, "https://example.invalid/payload")
            Fixture.expect(ThemeIssue.UNSUPPORTED_FIELD) { ThemeManifestReader.read(Fixture.jsonBytes(value)) }
        }
        val nested = Fixture.manifest()
        nested.getJSONObject("background").put("url", "https://example.invalid/image.png")
        Fixture.expect(ThemeIssue.UNSUPPORTED_FIELD) { ThemeManifestReader.read(Fixture.jsonBytes(nested)) }
    }

    @Test fun duplicateJsonKeysAtBothLevelsFailBeforeAThemeCanBeApplied() {
        val text = Fixture.packageOf().manifest.toString(Charsets.UTF_8)
        Fixture.expect(ThemeIssue.MALFORMED_MANIFEST) {
            ThemeManifestReader.read(("{\"id\":\"other\"," + text.drop(1)).toByteArray())
        }
        val nested = text.replace("\"background\":\"#000000\"", "\"background\":\"#000000\",\"background\":\"#FFFFFF\"")
        assertFalse(text == nested)
        Fixture.expect(ThemeIssue.MALFORMED_MANIFEST) { ThemeManifestReader.read(nested.toByteArray()) }
    }

    @Test fun unsupportedSchemaAndNoncanonicalNumericFormsHaveDistinctRejections() {
        Fixture.expect(ThemeIssue.UNSUPPORTED_SCHEMA) {
            ThemeManifestReader.read(Fixture.jsonBytes(Fixture.manifest().put("schemaVersion", 2)))
        }
        val text = Fixture.packageOf().manifest.toString(Charsets.UTF_8)
        assertTrue(text.contains("\"schemaVersion\":1"))
        for (number in listOf("1.0", "1e0", "-1", "\"1\"")) {
            Fixture.expect(ThemeIssue.MALFORMED_MANIFEST) {
                ThemeManifestReader.read(text.replace("\"schemaVersion\":1", "\"schemaVersion\":$number").toByteArray())
            }
        }
    }

    @Test fun malformedUtf8BomTrailingDataAndUnpairedSurrogatesAreNotRepairedSilently() {
        val valid = Fixture.packageOf().manifest
        for (bytes in listOf(byteArrayOf(0xc3.toByte(), 0x28), byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + valid,
            valid + byteArrayOf(123, 125))) {
            Fixture.expect(ThemeIssue.MALFORMED_MANIFEST) { ThemeManifestReader.read(bytes) }
        }
        val text = valid.toString(Charsets.UTF_8)
        assertTrue(text.contains("Fixture dark"))
        Fixture.expect(ThemeIssue.MALFORMED_MANIFEST) {
            ThemeManifestReader.read(text.replace("Fixture dark", "\\ud800").toByteArray())
        }
    }

    @Test fun requiredFieldsAndCompleteResourceLicenseNoticesCannotBeOmitted() {
        val value = Fixture.manifest()
        value.remove("license")
        Fixture.expect(ThemeIssue.MALFORMED_MANIFEST) { ThemeManifestReader.read(Fixture.jsonBytes(value)) }
        val image = Fixture.Asset("cover", "assets/cover.png", byteArrayOf(1))
        val withoutNotice = Fixture.manifest(assets = listOf(image))
        withoutNotice.getJSONArray("resources").getJSONObject(0).getJSONObject("license").remove("text")
        Fixture.expect(ThemeIssue.MALFORMED_MANIFEST) { ThemeManifestReader.read(Fixture.jsonBytes(withoutNotice)) }
    }

    @Test fun pathTraversalCrossPlatformAliasesAndDepthOverflowAreRefused() {
        for (path in listOf("../cover.png", "/assets/cover.png", "assets\\cover.png", "assets/%2e%2e/cover.png",
            "assets/C:/cover.png", "assets/CON.png", "assets/x./cover.png", "assets/a/b/c/d/cover.png")) {
            Fixture.expect(ThemeIssue.INVALID_PATH) {
                ThemeManifestReader.read(Fixture.packageOf(assets = listOf(Fixture.Asset("cover", path, byteArrayOf(1)))).manifest)
            }
        }
        assertEquals("assets/a/b/c/cover.png", themeAssetPath("assets/a/b/c/cover.png"))
    }

    @Test fun duplicateCaseFoldedPathsAndDuplicateResourceIdsAreRejected() {
        val first = Fixture.Asset("one", "assets/Cover.png", byteArrayOf(1))
        val second = Fixture.Asset("two", "assets/cover.png", byteArrayOf(2))
        Fixture.expect(ThemeIssue.DUPLICATE_PATH) { ThemeManifestReader.read(Fixture.packageOf(assets = listOf(first, second)).manifest) }
        Fixture.expect(ThemeIssue.MALFORMED_MANIFEST) {
            ThemeManifestReader.read(Fixture.packageOf(assets = listOf(first, second.copy(id = first.id))).manifest)
        }
    }

    @Test fun fontsCannotRemainUnreferencedOrUseAnImageAsEitherRole() {
        val font = Fixture.font()
        Fixture.expect(ThemeIssue.UNSUPPORTED_RESOURCE) { ThemeManifestReader.read(Fixture.packageOf(assets = listOf(font)).manifest) }
        val image = Fixture.Asset("cover", "assets/cover.png", byteArrayOf(1))
        Fixture.expect(ThemeIssue.UNSUPPORTED_RESOURCE) {
            ThemeManifestReader.read(Fixture.packageOf(assets = listOf(image), fonts = mapOf("original" to image.id)).manifest)
        }
    }

    @Test fun textContrastUsesTheHardReadabilityThresholdOnEveryOpaqueSurface() {
        val value = Fixture.manifest()
        val colors = value.getJSONObject("colors")
        for (field in listOf("background", "surface", "surfaceRaised", "surfacePressed")) colors.put(field, "#FFFFFF")
        for (field in listOf("textPrimary", "textSecondary", "error", "disabledText")) colors.put(field, "#757575")
        colors.put("accent", "#757575")
        colors.put("focus", "#757575")
        ThemeManifestReader.read(Fixture.jsonBytes(value))
        colors.put("textSecondary", "#777777")
        Fixture.expect(ThemeIssue.UNREADABLE_PALETTE) { ThemeManifestReader.read(Fixture.jsonBytes(value)) }
        colors.put("textSecondary", "#757575")
        colors.put("surfacePressed", "#757575")
        Fixture.expect(ThemeIssue.UNREADABLE_PALETTE) { ThemeManifestReader.read(Fixture.jsonBytes(value)) }
    }

    @Test fun transparentColorsAndLargeResourceListsDoNotReachNativeDecoders() {
        val value = Fixture.manifest()
        value.getJSONObject("colors").put("textPrimary", "#00FFFFFF")
        Fixture.expect(ThemeIssue.MALFORMED_MANIFEST) { ThemeManifestReader.read(Fixture.jsonBytes(value)) }
        val assets = (0..ThemeLimits.RESOURCES).map { Fixture.Asset("r$it", "assets/r$it.png", byteArrayOf(1)) }
        Fixture.expect(ThemeIssue.TOO_MANY_RESOURCES) { ThemeManifestReader.read(Fixture.packageOf(assets = assets).manifest) }
    }

    @Test fun cancellationPropagatesWhileParsingAndDoesNotBecomeMalformedInput() {
        var checkpoints = 0
        try {
            ThemeManifestReader.read(Fixture.packageOf().manifest) {
                if (++checkpoints == 7) throw CancellationException("Controlled manifest cancellation")
            }
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(7, checkpoints)
        }
    }

    @Test fun parsedCollectionsStayUnmodifiableAndBothBuiltinsRemainReadable() {
        val parsed = ThemeManifestReader.read(Fixture.packageOf().manifest)
        try {
            (parsed.resources as MutableList<ThemeResource>).clear()
            throw AssertionError("Expected immutable resources")
        } catch (_: UnsupportedOperationException) {
            assertTrue(parsed.resources.isEmpty())
        }
        assertEquals(21.0, themeContrast(0xff000000.toInt(), 0xffffffff.toInt()), 0.000001)
        BuiltinTheme.entries.forEach { ThemeBuiltins.manifest(it) }
    }
}
