package io.github.onlinemo.meloxwatch.data.themes

import io.github.onlinemo.meloxwatch.data.database.withTestWriterSql
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontLimits
import io.github.onlinemo.meloxwatch.data.transfer.TransferIssue
import io.github.onlinemo.meloxwatch.data.themes.ThemeTestFixtures as Packages
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
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

/** 故障来自真实 SQL 触发器和文件事务，不补造 candidate、批准 intent 或 canonical 收据。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TransferredThemeRecoveryIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var fixture: TransferredThemeIntegrationFixture

    @Before fun open() = runBlocking {
        fixture = TransferredThemeIntegrationFixture(temporary.newFolder("theme-recovery"))
        withTimeout(10_000L) { fixture.initialize() }
    }

    @After fun close() = runBlocking {
        if (::fixture.isInitialized) fixture.closeAwaitingWork()
    }

    @Test(timeout = 30_000L)
    fun aDurableGuardWithoutARoomApprovalCommitIsReleasedOnRestartWithoutApplyingThePackage() = runBlocking {
        val before = fixture.installLocal(Packages.packageOf("fixture.previous"))
        val savedCatalog = fixture.catalogFile.readBytes()
        val received = fixture.receivePackage(Packages.packageOf("fixture.guard"))
        val preview = fixture.coordinator.preview(received.binding.operationId)
        val request = Packages.apply(preview)
        val applyingSteps = AtomicInteger()
        fixture.sqlProbe.beforeStep = { sql ->
            if (sql.startsWith("update") && sql.contains("transferred_theme_candidates")) applyingSteps.incrementAndGet()
        }
        fixture.database.withTestWriterSql {
            execute("""
                CREATE TRIGGER controlled_apply_intent BEFORE UPDATE ON transferred_theme_candidates
                WHEN NEW.state = 'APPLYING'
                BEGIN SELECT RAISE(ABORT, 'controlled approval transaction failure'); END
            """.trimIndent())
        }
        try {
            expectLocalThemeIssue(ThemeIssue.RECOVERY_REQUIRED) { fixture.coordinator.apply(request) }
            assertEquals(1, applyingSteps.get())
            assertTrue(fixture.guardFile.isFile)
            assertEquals(TransferredThemeState.RECEIVED.name, fixture.candidate(received.binding.operationId)?.state)
            assertArrayEquals(savedCatalog, fixture.catalogFile.readBytes())
            assertEquals(1, fixture.commitAttempts.get())
            fixture.assertCanonical(received)
        } finally {
            fixture.sqlProbe.beforeStep = {}
            fixture.database.withTestWriterSql { execute("DROP TRIGGER IF EXISTS controlled_apply_intent") }
        }

        fixture.reopen()
        assertNotNull(fixture.coordinator.load())
        assertFalse(fixture.guardFile.exists())
        assertArrayEquals(savedCatalog, fixture.catalogFile.readBytes())
        assertEquals(before.snapshot.selected, fixture.coordinator.read().selected)
        assertEquals(1, fixture.commitAttempts.get())
        expectLocalThemeIssue(ThemeIssue.PREVIEW_NOT_FOUND) { fixture.coordinator.approve(received.binding.operationId, request) }
        assertArrayEquals(savedCatalog, fixture.catalogFile.readBytes())
        val newPreview = fixture.coordinator.preview(received.binding.operationId)
        val accepted = fixture.coordinator.apply(Packages.apply(newPreview))
        assertEquals(preview.expectedRevision + 1, accepted.snapshot.revision)
        assertEquals(received.binding.operationId, accepted.installedRef?.installationId)
        fixture.assertCanonical(received)
    }

    @Test(timeout = 30_000L)
    fun applyingWithAnAlreadyCommittedFilesystemRecoversOnlyTheResultAndNeverRepeatsThePhysicalApply() = runBlocking {
        val before = fixture.installLocal(Packages.packageOf("fixture.previous"))
        val value = Packages.packageOf("fixture.settlement")
        val received = fixture.receivePackage(value)
        val preview = fixture.coordinator.preview(received.binding.operationId)
        val request = Packages.apply(preview)
        val updateSteps = AtomicInteger()
        fixture.sqlProbe.beforeStep = { sql ->
            if (sql.startsWith("update") && sql.contains("transferred_theme_candidates")) updateSteps.incrementAndGet()
        }
        fixture.database.withTestWriterSql {
            execute("""
                CREATE TRIGGER controlled_settlement BEFORE UPDATE ON transferred_theme_candidates
                WHEN NEW.state = 'APPLIED'
                BEGIN SELECT RAISE(ABORT, 'controlled settlement transaction failure'); END
            """.trimIndent())
        }
        try {
            expectLocalThemeIssue(ThemeIssue.RECOVERY_REQUIRED) { fixture.coordinator.apply(request) }
            assertEquals(2, updateSteps.get())
            assertEquals(TransferredThemeState.APPLYING.name, fixture.candidate(received.binding.operationId)?.state)
            assertTrue(fixture.guardFile.isFile)
            assertEquals(preview.expectedRevision + 1, fixture.themes.state.value.revision)
            val installed = fixture.themes.state.value.installed.single { it.ref.installationId == received.binding.operationId }
            assertEquals(ThemeChoice.Installed(installed.ref), fixture.themes.state.value.selected)
            assertEquals(before.snapshot.selected, fixture.themes.state.value.previous)
            assertArrayEquals(value.manifest, fixture.installedManifest(received.binding.operationId).readBytes())
            assertEquals(2, fixture.commitAttempts.get())
            fixture.assertCanonical(received)
        } finally {
            fixture.sqlProbe.beforeStep = {}
            fixture.database.withTestWriterSql { execute("DROP TRIGGER IF EXISTS controlled_settlement") }
        }
        val committedCatalog = fixture.catalogFile.readBytes()
        val installedManifest = fixture.installedManifest(received.binding.operationId).readBytes()

        fixture.reopen()
        assertNull(fixture.coordinator.load())
        val restored = fixture.coordinator.read()
        assertEquals(preview.expectedRevision + 1, restored.revision)
        assertEquals(before.snapshot.selected, restored.previous)
        assertEquals(TransferredThemeState.APPLIED.name, fixture.candidate(received.binding.operationId)?.state)
        assertEquals(request.operationId, fixture.candidate(received.binding.operationId)?.localApplyOperationId)
        assertArrayEquals(committedCatalog, fixture.catalogFile.readBytes())
        assertArrayEquals(installedManifest, fixture.installedManifest(received.binding.operationId).readBytes())
        assertFalse(fixture.guardFile.exists())
        assertFalse(fixture.receivedDirectory(received.binding.operationId).exists())
        assertEquals(2, fixture.commitAttempts.get())
        fixture.assertCanonical(received)
        assertNull(fixture.coordinator.load())
        assertEquals(2, fixture.commitAttempts.get())
    }

    @Test(timeout = 30_000L)
    fun aRealPrecommitFileRollbackRequiresANewExplicitPreviewBeforeAnyRetryCanApply() = runBlocking {
        val before = fixture.installLocal(Packages.packageOf("fixture.previous"))
        val savedCatalog = fixture.catalogFile.readBytes()
        val value = Packages.packageOf("fixture.file.rollback")
        val received = fixture.receivePackage(value)
        val preview = fixture.coordinator.preview(received.binding.operationId)
        val request = Packages.apply(preview)
        val failures = AtomicInteger()
        fixture.storageFault = { point ->
            if (point == ThemeStoragePoint.BEFORE_COMMIT_MARKER && failures.getAndIncrement() == 0) {
                throw IOException("controlled theme precommit failure")
            }
        }
        try {
            expectLocalThemeIssue(ThemeIssue.SAVE_FAILED) { fixture.coordinator.apply(request) }
            assertEquals(1, failures.get())
            assertEquals(TransferredThemeState.APPLYING.name, fixture.candidate(received.binding.operationId)?.state)
            assertTrue(fixture.guardFile.isFile)
            assertArrayEquals(savedCatalog, fixture.catalogFile.readBytes())
            assertArrayEquals(value.manifest, fixture.receivedManifest(received.binding.operationId).readBytes())
            assertFalse(fixture.installedManifest(received.binding.operationId).exists())
        } finally {
            fixture.storageFault = null
        }

        fixture.reopen()
        assertNotNull(fixture.coordinator.load())
        assertEquals(TransferredThemeState.RECEIVED.name, fixture.candidate(received.binding.operationId)?.state)
        assertNull(fixture.candidate(received.binding.operationId)?.localApplyOperationId)
        assertFalse(fixture.guardFile.exists())
        assertArrayEquals(savedCatalog, fixture.catalogFile.readBytes())
        assertEquals(before.snapshot.selected, fixture.coordinator.read().selected)
        expectLocalThemeIssue(ThemeIssue.PREVIEW_NOT_FOUND) { fixture.coordinator.approve(received.binding.operationId, request) }
        assertEquals(2, fixture.commitAttempts.get())
        assertArrayEquals(savedCatalog, fixture.catalogFile.readBytes())
        val fresh = fixture.coordinator.preview(received.binding.operationId)
        assertTrue(fresh.token != preview.token)
        val accepted = fixture.coordinator.apply(Packages.apply(fresh))
        assertEquals(preview.expectedRevision + 1, accepted.snapshot.revision)
        assertArrayEquals(value.manifest, File(fixture.installedDirectory(checkNotNull(accepted.installedRef)), "theme.json").readBytes())
        assertEquals(3, fixture.commitAttempts.get())
        fixture.assertCanonical(received)
    }

    @Test(timeout = 30_000L)
    fun aFontPackageWithoutTheLocalLicenseAcknowledgmentRemainsReceivedAndNeedsAFreshAcknowledgedPreview() = runBlocking {
        val font = Packages.font()
        val value = Packages.packageOf("fixture.license", assets = listOf(font), fonts = mapOf("original" to font.id))
        val before = fixture.coordinator.read()
        val savedCatalog = fixture.catalogFile.readBytes()
        val received = fixture.receivePackage(value)
        val preview = fixture.coordinator.preview(received.binding.operationId)
        assertTrue(preview.fontCapabilities.isNotEmpty())
        expectLocalThemeIssue(ThemeIssue.FONT_LICENSE_ACKNOWLEDGMENT_REQUIRED) {
            fixture.coordinator.apply(Packages.apply(preview))
        }
        assertEquals(TransferredThemeState.APPLYING.name, fixture.candidate(received.binding.operationId)?.state)
        assertTrue(fixture.guardFile.isFile)
        assertArrayEquals(savedCatalog, fixture.catalogFile.readBytes())
        assertEquals(0, fixture.commitAttempts.get())
        fixture.reopen()
        assertNotNull(fixture.coordinator.load())
        assertEquals(before.selected, fixture.coordinator.read().selected)
        assertEquals(TransferredThemeState.RECEIVED.name, fixture.candidate(received.binding.operationId)?.state)
        assertFalse(fixture.guardFile.exists())
        val acknowledged = fixture.coordinator.preview(received.binding.operationId)
        val accepted = fixture.coordinator.apply(Packages.apply(acknowledged,
            acknowledgment = LyricsFontLimits.LICENSE_ACKNOWLEDGMENT_VERSION))
        assertArrayEquals(font.bytes, File(fixture.installedDirectory(checkNotNull(accepted.installedRef)), font.path).readBytes())
        assertEquals(TransferredThemeState.APPLIED.name, fixture.candidate(received.binding.operationId)?.state)
        fixture.assertCanonical(received)
    }

    @Test(timeout = 30_000L)
    fun missingCanonicalEvidenceRejectsPreviewLookupAndReuploadWithoutManufacturingACompletion() = runBlocking {
        val value = Packages.packageOf("fixture.missing.receipt")
        val bytes = value.zip()
        val declaration = fixture.themeDeclaration(bytes)
        val received = fixture.upload(bytes, declaration, fixture.binding(declaration))
        val savedSeal = fixture.receivedSeal(received.binding.operationId).readBytes()
        val savedCatalog = fixture.catalogFile.readBytes()
        fixture.database.withTestWriterSql {
            execute("DELETE FROM transfer_room_operations WHERE operationId = ?", received.binding.operationId)
        }
        expectLocalThemeIssue(ThemeIssue.RECOVERY_REQUIRED) { fixture.coordinator.load() }
        expectLocalThemeIssue(ThemeIssue.RECOVERY_REQUIRED) { fixture.coordinator.preview(received.binding.operationId) }
        expectThemeTransferIssue(TransferIssue.RECOVERY_REQUIRED) {
            fixture.backend.committed(received.binding, fixture.themePermit())
        }
        expectThemeTransferIssue(TransferIssue.RECOVERY_REQUIRED) { fixture.upload(bytes, declaration, received.binding) }
        val canonical = fixture.database.transferRoomDao().operation(received.binding.operationId)
        assertTrue(canonical == null || canonical.resultKind == null)
        assertEquals(TransferredThemeState.RECEIVED.name, fixture.candidate(received.binding.operationId)?.state)
        assertArrayEquals(savedSeal, fixture.receivedSeal(received.binding.operationId).readBytes())
        assertArrayEquals(savedCatalog, fixture.catalogFile.readBytes())
        assertEquals(0, fixture.commitAttempts.get())
    }
}
