package io.github.onlinemo.meloxwatch.data.themes

import io.github.onlinemo.meloxwatch.data.database.withTestWriterSql
import io.github.onlinemo.meloxwatch.data.transfer.TransferIssue
import io.github.onlinemo.meloxwatch.data.transfer.TransferReceiptKind
import io.github.onlinemo.meloxwatch.data.themes.ThemeTestFixtures as Packages
import java.io.File
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

/** 真实上传所有者、Room V6 和主题文件事务；用例定义不构成云端执行或设备验收证据。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TransferredThemeRoomIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var fixture: TransferredThemeIntegrationFixture

    @Before fun open() = runBlocking {
        fixture = TransferredThemeIntegrationFixture(temporary.newFolder("theme-room"))
        withTimeout(10_000L) { fixture.initialize() }
    }

    @After fun close() = runBlocking {
        if (::fixture.isInitialized) fixture.closeAwaitingWork()
    }

    @Test(timeout = 30_000L)
    fun receivingAndPreviewingProduceOnlyAPendingReceiptAndKeepTheOldSavedSelection() = runBlocking {
        val before = fixture.installLocal(Packages.packageOf("fixture.previous"))
        val catalog = fixture.catalogFile.readBytes()
        val value = Packages.packageOf("fixture.received")
        val bytes = value.zip()
        val declaration = fixture.themeDeclaration(bytes)
        val binding = fixture.binding(declaration)
        val receipt = fixture.upload(bytes, declaration, binding)

        assertEquals(TransferReceiptKind.THEME_PENDING_CONFIRMATION, receipt.result)
        assertEquals(before.snapshot.revision, receipt.revision)
        assertEquals(binding.operationId, receipt.resultId)
        assertEquals(bytes.size.toLong(), receipt.actualBytes)
        fixture.assertCanonical(receipt)
        val row = checkNotNull(fixture.candidate(binding.operationId))
        assertEquals(TransferredThemeState.RECEIVED.name, row.state)
        assertNull(row.localApplyOperationId)
        assertNull(row.appliedInstallationId)
        assertArrayEquals(bytes, fixture.receivedInput(binding.operationId).readBytes())
        assertArrayEquals(value.manifest, fixture.receivedManifest(binding.operationId).readBytes())
        assertEquals(before.snapshot.selected, fixture.coordinator.read().selected)
        assertArrayEquals(catalog, fixture.catalogFile.readBytes())

        val preview = fixture.coordinator.preview(binding.operationId)
        assertEquals(value.manifest.toString(Charsets.UTF_8),
            fixture.receivedManifest(binding.operationId).readText(Charsets.UTF_8))
        assertEquals(row.themeId, preview.manifest.id)
        assertEquals(before.snapshot.revision, preview.expectedRevision)
        assertEquals(before.snapshot.selected, fixture.coordinator.read().selected)
        assertArrayEquals(catalog, fixture.catalogFile.readBytes())
        assertEquals(binding.operationId, fixture.coordinator.pending.value?.operationId)
        assertFalse(fixture.guardFile.exists())
        assertEquals(1, fixture.commitAttempts.get())
        fixture.assertOriginalSourcesUnchangedAndClosed()
    }

    @Test(timeout = 30_000L)
    fun theAppearanceConfirmationRouteAppliesTheExactReceivedPreviewAndSurvivesRestart() = runBlocking {
        val before = fixture.installLocal(Packages.packageOf("fixture.previous"))
        val value = Packages.packageOf("fixture.confirmed")
        val bytes = value.zip()
        val declaration = fixture.themeDeclaration(bytes)
        val binding = fixture.binding(declaration)
        val receipt = fixture.upload(bytes, declaration, binding)
        val preview = fixture.coordinator.preview(binding.operationId)
        val request = Packages.apply(preview)

        val accepted = fixture.coordinator.apply(request)
        val ref = checkNotNull(accepted.installedRef)
        assertEquals(binding.operationId, ref.installationId)
        assertEquals(preview.expectedRevision + 1, accepted.snapshot.revision)
        assertEquals(ThemeChoice.Installed(ref), accepted.snapshot.selected)
        assertEquals(before.snapshot.selected, accepted.snapshot.previous)
        assertEquals(2, accepted.snapshot.installed.size)
        assertArrayEquals(value.manifest, File(fixture.installedDirectory(ref), "theme.json").readBytes())
        assertFalse(fixture.receivedDirectory(binding.operationId).exists())
        assertFalse(fixture.guardFile.exists())
        assertNull(fixture.coordinator.pending.value)
        val candidate = checkNotNull(fixture.candidate(binding.operationId))
        assertEquals(TransferredThemeState.APPLIED.name, candidate.state)
        assertEquals(request.operationId, candidate.localApplyOperationId)
        assertEquals(ref.installationId, candidate.appliedInstallationId)
        assertEquals(accepted.snapshot.revision, candidate.appliedRevision)
        fixture.assertCanonical(receipt)
        val savedCatalog = fixture.catalogFile.readBytes()
        val savedManifest = File(fixture.installedDirectory(ref), "theme.json").readBytes()
        val attempts = fixture.commitAttempts.get()

        fixture.reopen()
        assertNull(fixture.coordinator.load())
        val restored = fixture.coordinator.read()
        assertEquals(accepted.snapshot.revision, restored.revision)
        assertEquals(accepted.snapshot.selected, restored.selected)
        assertEquals(before.snapshot.selected, restored.previous)
        assertArrayEquals(savedCatalog, fixture.catalogFile.readBytes())
        assertArrayEquals(savedManifest, File(fixture.installedDirectory(ref), "theme.json").readBytes())
        assertEquals(attempts, fixture.commitAttempts.get())
        fixture.assertCanonical(receipt)
        fixture.assertSameReceipt(receipt, checkNotNull(fixture.backend.committed(binding, fixture.themePermit())))
        fixture.assertOriginalSourcesUnchangedAndClosed()
    }

    @Test(timeout = 30_000L)
    fun anIdenticalActualReuploadReturnsTheOriginalCanonicalReceiptAndKeepsOneReceivedCopy() = runBlocking {
        val value = Packages.packageOf("fixture.idempotent")
        val bytes = value.zip()
        val declaration = fixture.themeDeclaration(bytes)
        val binding = fixture.binding(declaration)
        val first = fixture.upload(bytes, declaration, binding)
        val savedCandidate = fixture.receivedSeal(binding.operationId).readBytes()
        val savedCatalog = fixture.catalogFile.readBytes()

        val second = fixture.upload(bytes, declaration, binding)
        fixture.assertSameReceipt(first, second)
        fixture.assertCanonical(first)
        assertEquals(1, fixture.database.transferredThemeCandidateDao().page(null, 100).size)
        assertEquals(TransferredThemeState.RECEIVED.name, fixture.candidate(binding.operationId)?.state)
        assertArrayEquals(savedCandidate, fixture.receivedSeal(binding.operationId).readBytes())
        assertArrayEquals(savedCatalog, fixture.catalogFile.readBytes())
        assertArrayEquals(bytes, fixture.receivedInput(binding.operationId).readBytes())
        assertEquals(0, fixture.commitAttempts.get())
        assertEquals(0, fixture.selectedProvider.opens.get())
        fixture.reopen()
        assertNotNull(fixture.coordinator.load())
        fixture.assertSameReceipt(first, checkNotNull(fixture.backend.committed(binding, fixture.themePermit())))
        assertArrayEquals(savedCandidate, fixture.receivedSeal(binding.operationId).readBytes())
        assertEquals(1, fixture.database.transferredThemeCandidateDao().page(null, 100).size)
    }

    @Test(timeout = 30_000L)
    fun anAppliedReceivedThemeCanReturnToTheBuiltinDefaultAndKeepItsInstalledPayloadAndOriginalReceipt() = runBlocking {
        val received = fixture.receivePackage(Packages.packageOf("fixture.default.restore"))
        val preview = fixture.coordinator.preview(received.binding.operationId)
        val installed = fixture.coordinator.apply(Packages.apply(preview))
        val ref = checkNotNull(installed.installedRef)
        val savedPayload = fixture.installedManifest(ref.installationId).readBytes()

        val defaultPreview = fixture.coordinator.previewChoice(ThemeBuiltins.defaultChoice)
        val restored = fixture.coordinator.apply(Packages.apply(defaultPreview))
        assertEquals(ThemeBuiltins.defaultChoice, restored.snapshot.selected)
        assertEquals(installed.snapshot.selected, restored.snapshot.previous)
        assertEquals(defaultPreview.expectedRevision + 1, restored.snapshot.revision)
        assertArrayEquals(savedPayload, fixture.installedManifest(ref.installationId).readBytes())
        fixture.assertCanonical(received)
        fixture.reopen()
        assertNull(fixture.coordinator.load())
        assertEquals(ThemeBuiltins.defaultChoice, fixture.coordinator.read().selected)
        assertArrayEquals(savedPayload, fixture.installedManifest(ref.installationId).readBytes())
        assertEquals(TransferredThemeState.APPLIED.name, fixture.candidate(received.binding.operationId)?.state)
        fixture.assertCanonical(received)
    }

    @Test(timeout = 30_000L)
    fun aRealCandidateInsertTriggerRollsBackBothTheCandidateAndCanonicalCompletion() = runBlocking {
        exerciseReceiveRollback(candidateInsert = true)
    }

    @Test(timeout = 30_000L)
    fun aRealCanonicalUpdateTriggerRollsBackTheCandidateInTheSameTransaction() = runBlocking {
        exerciseReceiveRollback(candidateInsert = false)
    }

    private suspend fun exerciseReceiveRollback(candidateInsert: Boolean) {
        val before = fixture.installLocal(Packages.packageOf("fixture.previous"))
        val savedCatalog = fixture.catalogFile.readBytes()
        val bytes = Packages.packageOf("fixture.rollback").zip()
        val declaration = fixture.themeDeclaration(bytes)
        val binding = fixture.binding(declaration)
        val insertSteps = AtomicInteger()
        fixture.sqlProbe.beforeStep = { sql ->
            if (sql.contains("insert") && sql.contains("into transferred_theme_candidates")) insertSteps.incrementAndGet()
        }
        val trigger = if (candidateInsert) {
            """
                CREATE TRIGGER controlled_receive_failure BEFORE INSERT ON transferred_theme_candidates
                BEGIN SELECT RAISE(ABORT, 'controlled candidate insert failure'); END
            """.trimIndent()
        } else {
            """
                CREATE TRIGGER controlled_receive_failure BEFORE UPDATE ON transfer_room_operations
                WHEN NEW.resultKind IS NOT NULL
                BEGIN SELECT RAISE(ABORT, 'controlled canonical receipt failure'); END
            """.trimIndent()
        }
        fixture.database.withTestWriterSql { execute(trigger) }
        try {
            expectThemeTransferIssue(TransferIssue.RECOVERY_REQUIRED) { fixture.upload(bytes, declaration, binding) }
            assertEquals(1, insertSteps.get())
            assertNull(fixture.candidate(binding.operationId))
            val operation = checkNotNull(fixture.database.transferRoomDao().operation(binding.operationId))
            assertNull(operation.resultKind)
            assertNull(operation.appliedRevision)
            assertNull(operation.resultId)
            assertNull(operation.actualBytes)
            assertTrue(fixture.receivedSeal(binding.operationId).isFile)
            assertArrayEquals(bytes, fixture.receivedInput(binding.operationId).readBytes())
            assertArrayEquals(savedCatalog, fixture.catalogFile.readBytes())
            assertEquals(before.snapshot.selected, fixture.themes.state.value.selected)
        } finally {
            fixture.sqlProbe.beforeStep = {}
            fixture.database.withTestWriterSql { execute("DROP TRIGGER IF EXISTS controlled_receive_failure") }
        }

        fixture.reopen()
        assertNull(fixture.coordinator.load())
        assertFalse(fixture.receivedDirectory(binding.operationId).exists())
        assertEquals(before.snapshot.selected, fixture.coordinator.read().selected)
        assertArrayEquals(savedCatalog, fixture.catalogFile.readBytes())
        val accepted = fixture.upload(bytes, declaration, binding)
        fixture.assertCanonical(accepted)
        assertEquals(TransferredThemeState.RECEIVED.name, fixture.candidate(binding.operationId)?.state)
        assertArrayEquals(bytes, fixture.receivedInput(binding.operationId).readBytes())
        assertEquals(before.snapshot.revision, accepted.revision)
        fixture.assertOriginalSourcesUnchangedAndClosed()
    }
}
