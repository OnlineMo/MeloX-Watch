package io.github.onlinemo.meloxwatch.data.themes

import io.github.onlinemo.meloxwatch.data.themes.ThemeTestFixtures as Fixture
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
class ThemeRemovalAndRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun activePreviousPreviewAndExternalReferencesEachProtectTheirInstallation() = runBlocking {
        val directory = temporary.newFolder()
        val source = BytesThemeSource(Fixture.packageOf().zip())
        val repository = Fixture.repository(directory, source)
        val first = install(repository, source, Fixture.packageOf("fixture.first"))
        val second = install(repository, source, Fixture.packageOf("fixture.second"))
        Fixture.expectSuspending(ThemeIssue.IN_USE) { repository.remove(second, 2, Fixture.id(), emptySet()) }
        Fixture.expectSuspending(ThemeIssue.IN_USE) { repository.remove(first, 2, Fixture.id(), emptySet()) }
        val builtin = repository.previewChoice(ThemeBuiltins.defaultChoice)
        repository.apply(Fixture.apply(builtin))
        Fixture.expectSuspending(ThemeIssue.IN_USE) { repository.remove(first, 3, Fixture.id(), setOf(first)) }
        val preview = repository.previewChoice(ThemeChoice.Installed(first))
        Fixture.expectSuspending(ThemeIssue.IN_USE) { repository.remove(first, 3, Fixture.id(), emptySet()) }
        repository.discardPreview(preview.token)
        val originalAudio = temporary.newFile().apply { writeBytes(byteArrayOf(1, 3, 5, 7)) }
        val removed = repository.remove(first, 3, Fixture.id(), emptySet())
        assertTrue(removed.filesDeleted)
        assertFalse(removed.cleanupRequired)
        assertEquals(4L, removed.snapshot.revision)
        assertEquals(ThemeBuiltins.defaultChoice, removed.snapshot.selected)
        assertEquals(ThemeChoice.Installed(second), removed.snapshot.previous)
        assertFalse(File(directory, "installed/${first.installationId}").exists())
        assertTrue(File(directory, "installed/${second.installationId}/theme.json").isFile)
        assertArrayEquals(byteArrayOf(1, 3, 5, 7), originalAudio.readBytes())
    }

    @Test fun anotherDurableMutationInvalidatesAQueuedPreviewWithoutTruncatingOrApplyingIt() = runBlocking {
        val directory = temporary.newFolder()
        val source = BytesThemeSource(Fixture.packageOf().zip())
        val repository = Fixture.repository(directory, source)
        val first = install(repository, source, Fixture.packageOf("fixture.first"))
        install(repository, source, Fixture.packageOf("fixture.second"))
        repository.apply(Fixture.apply(repository.previewChoice(ThemeBuiltins.defaultChoice)))
        replace(source, Fixture.packageOf("fixture.third"))
        val preview = repository.prepareImport(Fixture.request())
        val staged = File(directory, "staging/${preview.token}/payload/theme.json").readBytes()
        repository.remove(first, 3, Fixture.id(), emptySet())
        Fixture.expectSuspending(ThemeIssue.REVISION_CONFLICT) { repository.apply(Fixture.apply(preview)) }
        assertEquals(4L, repository.state.value.revision)
        assertEquals(ThemeBuiltins.defaultChoice, repository.state.value.selected)
        assertArrayEquals(staged, File(directory, "staging/${preview.token}/payload/theme.json").readBytes())
        assertFalse(File(directory, "installed/${preview.token}").exists())
        repository.discardPreview(preview.token)
        assertFalse(File(directory, "staging/${preview.token}").exists())
    }

    @Test fun corruptSelectedResourceFallsBackToVerifiedPreviousAndRepairIsActuallyRevalidated() = runBlocking {
        val directory = temporary.newFolder()
        val source = BytesThemeSource(Fixture.packageOf().zip())
        val repository = Fixture.repository(directory, source)
        val image = Fixture.Asset("cover", "assets/cover.png", Fixture.png())
        val first = install(repository, source, Fixture.packageOf("fixture.first", listOf(image)))
        val second = install(repository, source, Fixture.packageOf("fixture.second", listOf(image)))
        val before = File(directory, "catalog.bin").readBytes()
        val file = File(directory, "installed/${second.installationId}/${image.path}")
        val changed = image.bytes.copyOf()
        changed[changed.lastIndex] = (changed.last().toInt() xor 1).toByte()
        file.writeBytes(changed)
        val fallback = repository.read()
        assertEquals(ThemeIssue.INTEGRITY_FAILED, fallback.issue)
        assertEquals(ThemeChoice.Installed(second), fallback.selected)
        assertEquals(ThemeChoice.Installed(first), fallback.effective)
        assertEquals(first.themeId, fallback.effectiveManifest.id)
        assertArrayEquals(before, File(directory, "catalog.bin").readBytes())
        file.writeBytes(image.bytes)
        val repaired = repository.read()
        assertNull(repaired.issue)
        assertEquals(ThemeChoice.Installed(second), repaired.effective)
        assertArrayEquals(before, File(directory, "catalog.bin").readBytes())
    }

    @Test fun corruptBothSelectionsKeepsTheirSavedReferencesAndUsesAnAccessibleBuiltin() = runBlocking {
        val directory = temporary.newFolder()
        val source = BytesThemeSource(Fixture.packageOf().zip())
        val repository = Fixture.repository(directory, source)
        val first = install(repository, source, Fixture.packageOf("fixture.first"))
        val second = install(repository, source, Fixture.packageOf("fixture.second"))
        val before = File(directory, "catalog.bin").readBytes()
        for (ref in listOf(first, second)) {
            val file = File(directory, "installed/${ref.installationId}/theme.json")
            val changed = file.readBytes()
            changed[0] = '['.code.toByte()
            file.writeBytes(changed)
        }
        val actual = repository.read()
        assertEquals(ThemeIssue.INTEGRITY_FAILED, actual.issue)
        assertEquals(ThemeChoice.Installed(second), actual.selected)
        assertEquals(ThemeChoice.Installed(first), actual.previous)
        assertEquals(ThemeBuiltins.defaultChoice, actual.effective)
        assertEquals("builtin.dark", actual.effectiveManifest.id)
        assertEquals(2, actual.installed.size)
        assertArrayEquals(before, File(directory, "catalog.bin").readBytes())
    }

    @Test fun installedUndeclaredFontBytesCauseFallbackAndRemainPreservedUntilExplicitRepair() = runBlocking {
        val directory = temporary.newFolder()
        val source = BytesThemeSource(Fixture.packageOf().zip())
        val repository = Fixture.repository(directory, source)
        val first = install(repository, source, Fixture.packageOf("fixture.first"))
        val second = install(repository, source, Fixture.packageOf("fixture.second"))
        val before = File(directory, "catalog.bin").readBytes()
        val font = Fixture.font()
        val unlisted = File(directory, "installed/${second.installationId}/unlisted.ttf").apply { writeBytes(font.bytes) }
        val fallback = repository.read()
        assertEquals(ThemeIssue.UNDECLARED_RESOURCE, fallback.issue)
        assertEquals(ThemeChoice.Installed(second), fallback.selected)
        assertEquals(ThemeChoice.Installed(first), fallback.effective)
        Fixture.expectSuspending(ThemeIssue.UNDECLARED_RESOURCE) { repository.ownedFontBytes() }
        assertArrayEquals(font.bytes, unlisted.readBytes())
        assertArrayEquals(before, File(directory, "catalog.bin").readBytes())
        assertTrue(unlisted.delete())
        val repaired = repository.read()
        assertNull(repaired.issue)
        assertEquals(ThemeChoice.Installed(second), repaired.effective)
        assertArrayEquals(before, File(directory, "catalog.bin").readBytes())
    }

    @Test fun persistentDeleteFailureReportsTheNewCatalogAndRetryUsesTheSameCommittedOperation() = runBlocking {
        val directory = temporary.newFolder()
        val source = BytesThemeSource(Fixture.packageOf().zip())
        var refuseDelete = false
        val faults = AtomicInteger()
        val repository = Fixture.repository(directory, source, faults = ThemeStorageFaults { point ->
            if (point == ThemeStoragePoint.BEFORE_DELETE && refuseDelete) {
                faults.incrementAndGet()
                throw IOException("Controlled persistent delete failure")
            }
        })
        val first = install(repository, source, Fixture.packageOf("fixture.first"))
        install(repository, source, Fixture.packageOf("fixture.second"))
        repository.apply(Fixture.apply(repository.previewChoice(ThemeBuiltins.defaultChoice)))
        val operation = Fixture.id()
        refuseDelete = true
        val result = repository.remove(first, 3, operation, emptySet())
        assertTrue(result.cleanupRequired)
        assertFalse(result.filesDeleted)
        assertEquals(ThemeIssue.CLEANUP_REQUIRED, result.snapshot.issue)
        assertEquals(4L, result.snapshot.revision)
        assertEquals(1, result.snapshot.installed.size)
        val accepted = File(directory, "catalog.bin").readBytes()
        val read = repository.read()
        assertEquals(ThemeIssue.CLEANUP_REQUIRED, read.issue)
        assertEquals(result.snapshot.selected, read.selected)
        assertEquals(4L, read.revision)
        assertEquals(2, faults.get())
        assertArrayEquals(accepted, File(directory, "catalog.bin").readBytes())
        refuseDelete = false
        val retry = repository.remove(first, 3, operation, emptySet())
        assertTrue(retry.filesDeleted)
        assertFalse(retry.cleanupRequired)
        assertEquals(4L, retry.snapshot.revision)
        assertFalse(File(directory, "installed/${first.installationId}").exists())
        assertArrayEquals(accepted, File(directory, "catalog.bin").readBytes())
    }

    @Test fun restartingWithAnUnappliedPreviewCleansOnlyTheAbandonedStageAndCorruptCatalogIsNotReset() = runBlocking {
        val directory = temporary.newFolder()
        val source = BytesThemeSource(Fixture.packageOf().zip())
        val oldProcess = Fixture.repository(directory, source)
        val preview = oldProcess.prepareImport(Fixture.request())
        val original = File(directory, "catalog.bin").readBytes()
        val restarted = Fixture.repository(directory, source)
        assertEquals(ThemeBuiltins.defaultChoice, restarted.read().selected)
        assertFalse(File(directory, "staging/${preview.token}").exists())
        assertArrayEquals(original, File(directory, "catalog.bin").readBytes())
        val corrupted = original.copyOf()
        corrupted[0] = (corrupted[0].toInt() xor 1).toByte()
        File(directory, "catalog.bin").writeBytes(corrupted)
        val actual = restarted.read()
        assertEquals(ThemeIssue.CORRUPT_STORAGE, actual.issue)
        assertNull(actual.revision)
        assertEquals(ThemeBuiltins.defaultChoice, actual.effective)
        assertArrayEquals(corrupted, File(directory, "catalog.bin").readBytes())
    }

    private suspend fun install(repository: LocalThemeRepository, source: BytesThemeSource, value: Fixture.Package): ThemeRef {
        replace(source, value)
        return checkNotNull(repository.apply(Fixture.apply(repository.prepareImport(Fixture.request()))).installedRef)
    }

    private fun replace(source: BytesThemeSource, value: Fixture.Package) {
        source.bytes = value.zip()
        source.declared = source.bytes.size.toLong()
    }
}
