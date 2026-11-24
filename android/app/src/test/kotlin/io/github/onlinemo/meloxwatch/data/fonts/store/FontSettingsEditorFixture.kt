package io.github.onlinemo.meloxwatch.data.fonts.store

import io.github.onlinemo.meloxwatch.data.fonts.AndroidLyricsFontFiles
import io.github.onlinemo.meloxwatch.data.fonts.FilesOnlyFontDecoder
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontAsset
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontDecoder
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontImportRequest
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontLimits
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontRole
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSettingsStore
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontStreamOpener
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontTestFixtures as Fixture
import io.github.onlinemo.meloxwatch.data.fonts.OpenedLyricsFontStream
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.ui.fonts.LyricsFontEditor
import io.github.onlinemo.meloxwatch.ui.fonts.LyricsFontPreviewText
import java.io.File
import java.io.FileInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/** Real selected and private font bytes plus the real store; the default decoder isolates native proof. */
internal class FontSettingsEditorFixture(
    root: File,
    store: LyricsFontSettingsStore,
    decoder: LyricsFontDecoder = FilesOnlyFontDecoder(),
) {
    val text = LyricsFontPreviewText("MeloX Watch", "Music")
    val directory = File(root, "private-fonts").apply { check(mkdir()) }
    private val originalsDirectory = File(root, "selected-fonts").apply { check(mkdir()) }
    private val originals = listOf(Fixture.VARIABLE, Fixture.STATIC).associateWith { Fixture.file(originalsDirectory, it) }
    private val originalBytes = originals.mapValues { (_, file) -> file.readBytes() }
    val files = AndroidLyricsFontFiles(directory, LocalProviderIo(), LyricsFontStreamOpener { uri, signal ->
        signal.throwIfCanceled()
        val original = originals.getValue(uri.substringAfterLast('/'))
        OpenedLyricsFontStream(FileInputStream(original), original.length())
    }, decoder, { Fixture.ROOM })
    val editor = LyricsFontEditor(store, files)

    suspend fun choose(role: LyricsFontRole, name: String, requested: Float?) {
        editor.begin(role, text)
        editor.importFont(request(name), text)
        if (requested != null) editor.previewWeight(requested, text)
        assertTrue(editor.state.value.canConfirm)
        editor.confirm(text)
        assertNull(editor.state.value.issue)
    }

    fun newEditor(store: LyricsFontSettingsStore): LyricsFontEditor = LyricsFontEditor(store, files)
    fun assetFile(asset: LyricsFontAsset): File = File(directory, asset.id + ".font")

    fun assertOriginalsUnchanged() {
        originals.forEach { (name, file) ->
            assertTrue(file.isFile)
            assertArrayEquals(originalBytes.getValue(name), file.readBytes())
        }
    }

    fun request(name: String): LyricsFontImportRequest = LyricsFontImportRequest(
        "content://persistent-font-fixture/$name", name, LyricsFontLimits.LICENSE_ACKNOWLEDGMENT_VERSION,
    )
}
