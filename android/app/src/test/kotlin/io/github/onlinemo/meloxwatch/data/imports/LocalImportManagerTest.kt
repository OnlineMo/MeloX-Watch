package io.github.onlinemo.meloxwatch.data.imports

import android.content.Context
import android.content.ContextWrapper
import android.os.CancellationSignal
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.database.withTestWriterSql
import io.github.onlinemo.meloxwatch.data.library.LocalAudioMetadata
import io.github.onlinemo.meloxwatch.data.library.LocalContentValidation
import io.github.onlinemo.meloxwatch.data.library.RoomImportLedger
import io.github.onlinemo.meloxwatch.data.library.RoomLocalLibraryRepository
import io.github.onlinemo.meloxwatch.data.local.AndroidLocalSources
import io.github.onlinemo.meloxwatch.data.local.LocalCandidateCursor
import io.github.onlinemo.meloxwatch.data.local.LocalGrantObservation
import io.github.onlinemo.meloxwatch.data.local.LocalInputLimits
import io.github.onlinemo.meloxwatch.data.local.LocalMetadataReader
import io.github.onlinemo.meloxwatch.data.local.LocalNodeKind
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.LocalSourceAccess
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceObservation
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.scan.LocalScanEngine
import io.github.onlinemo.meloxwatch.data.scan.ScanPhase
import io.github.onlinemo.meloxwatch.data.transfer.TransferUploadKind
import io.github.onlinemo.meloxwatch.model.LocalAccess
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Real private files and Room transactions; audio parsing has its separate format tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LocalImportManagerTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var databaseFile: File
    private lateinit var database: MeloXDatabase
    private lateinit var library: RoomLocalLibraryRepository
    private lateinit var ledger: RoomImportLedger
    private lateinit var io: LocalProviderIo
    private lateinit var original: File
    private var declaredBytes: Long? = null
    private var openings = 0
    private var availableBytes = ImportLimits.FREE_RESERVE_BYTES + ImportLimits.QUOTA_BYTES
    private var sourceToken: String? = null
    private val audio = LocalAudioMetadata(title = "Selected audio", artist = "AC/DC", artists = listOf("AC/DC"), mimeType = "audio/mpeg")
    private val source = LocalSourceSpec("selected", LocalSourceKind.DOCUMENT, "content://test-audio/item",
        authority = "test-audio", rootObjectId = "document:1")

    @Before fun setup() = runBlocking {
        val privateRoot = temporary.newFolder("private")
        context = object : ContextWrapper(RuntimeEnvironment.getApplication() as Context) {
            override fun getNoBackupFilesDir(): File = privateRoot
        }
        original = File(temporary.root, "original.mp3").apply { writeBytes(ByteArray(150_000) { (it % 251).toByte() }) }
        io = LocalProviderIo()
        databaseFile = File(temporary.root, "imports.db")
        openDatabase()
    }

    @After fun closeDatabase() { database.close() }

    @Test fun unknownLengthCopyCommitsExactBytesOnceAndKeepsTheOriginal() = runBlocking {
        val before = original.readBytes()
        val manager = manager()
        val preview = manager.prepare(source)
        assertNull(preview.declaredBytes)
        val result = manager.confirmCopy(preview)
        assertEquals(ImportPhase.COMMITTED, result.phase)
        assertEquals(before.size.toLong(), result.actualBytes)
        assertEquals(digest(before), result.sha256)
        assertEquals(2, openings)
        assertArrayEquals(before, original.readBytes())
        assertArrayEquals(before, OwnedAudioProvider.finalFile(context, result.intent.fileToken).readBytes())
        assertFalse(OwnedAudioProvider.stagingFile(context, result.intent.fileToken).exists())
        assertEquals(1L, library.overview().totalTracks)
        val descriptor = library.resolveLocalRead(result.trackKey!!)
        assertEquals(LocalAccess.APP_OWNED, descriptor.location.access)
        assertEquals("sha256:${result.sha256}", descriptor.verifiedContentToken)
        assertEquals(LocalContentValidation.UNKNOWN, descriptor.contentValidation)
        assertEquals(result, manager.confirmCopy(preview))
        assertEquals(2, openings)

        reopenDatabase()
        assertEquals(result, ledger.record(result.intent.operationId))
        assertEquals(ImportRecoverySummary(0, 0, 0), manager().recover())
        assertEquals(1L, library.overview().totalTracks)
    }

    @Test fun newConfirmedCopiesUseIndependentObjectsEvenWithIdenticalNamesAndHashes() = runBlocking {
        val manager = manager()
        val first = manager.confirmCopy(manager.prepare(source))
        val second = manager.confirmCopy(manager.prepare(source))
        assertNotEquals(first.intent.fileToken, second.intent.fileToken)
        assertNotEquals(first.trackKey, second.trackKey)
        assertEquals(first.sha256, second.sha256)
        assertEquals(2L, library.overview().totalTracks)
        assertTrue(OwnedAudioProvider.finalFile(context, first.intent.fileToken).isFile)
        assertTrue(OwnedAudioProvider.finalFile(context, second.intent.fileToken).isFile)
    }

    @Test fun rescanningALargeImmutableCopyRetainsTheOriginalInstallationProof() = runBlocking {
        original.writeBytes(ByteArray(LocalInputLimits.READ_PROOF_BYTES + 1) { (it % 251).toByte() })
        val manager = manager()
        val imported = manager.confirmCopy(manager.prepare(source))
        val owned = library.source("import:${imported.intent.operationId}")!!
        val access = object : LocalSourceAccess {
            override suspend fun observe(source: LocalSourceSpec): LocalSourceObservation = LocalSourceObservation(source,
                AndroidLocalSources.sourceIdentity(source), owned.grant,
                ObservedCandidate(source.sourceId, imported.intent.fileToken, source.uri, null, "internal.audio", "audio/*",
                    LocalNodeKind.AUDIO, imported.actualBytes, entityProof = "owned:${source.authority}:${imported.intent.fileToken}"))
            override suspend fun open(source: LocalSourceSpec, parentObjectId: String?, sinceMediaGeneration: Long?): LocalCandidateCursor =
                throw AssertionError("Private single-copy scan must not enumerate")
        }
        val scanScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val engine = LocalScanEngine(access, { audio }, library, io, scanScope)
            val token = engine.start(owned, library.rules().revision)
            val state = withTimeout(20_000) {
                engine.state.filterNotNull().first { it.token == token && it.phase in setOf(ScanPhase.COMPLETED, ScanPhase.FAILED) }
            }
            scanScope.coroutineContext[Job]!!.children.toList().joinAll()
            assertEquals(ScanPhase.COMPLETED, state.phase)
            val descriptor = library.resolveLocalRead(imported.trackKey!!)
            assertEquals("sha256:${imported.sha256}", descriptor.verifiedContentToken)
            assertEquals("sha256:${imported.sha256}", descriptor.providerContentToken)
            assertEquals(1L, library.overview().totalTracks)
        } finally { scanScope.cancel() }
    }

    @Test fun installationSurvivesDatabasePublicationRollbackAndRecoversWithoutRecopy() = runBlocking {
        database.withTestWriterSql {
            execute(
                "CREATE TRIGGER reject_import_publication BEFORE UPDATE OF phase ON import_operations " +
                    "WHEN NEW.phase = 'COMMITTED' BEGIN SELECT RAISE(ABORT, 'controlled commit failure'); END",
            )
        }
        val manager = manager()
        val preview = manager.prepare(source)
        val installed = manager.confirmCopy(preview)
        assertEquals(ImportPhase.INSTALLED, installed.phase)
        assertEquals(ImportIssue.RECOVERY_REQUIRED, installed.issue)
        assertEquals(0L, library.overview().totalTracks)
        assertEquals(0L, library.overview().sourceCount)
        assertNull(installed.trackKey)
        val installedBytes = OwnedAudioProvider.finalFile(context, installed.intent.fileToken).readBytes()
        val copyOpenings = openings
        database.withTestWriterSql { execute("DROP TRIGGER reject_import_publication") }
        reopenDatabase()
        assertEquals(ImportPhase.INSTALLED, ledger.record(installed.intent.operationId)?.phase)
        assertEquals(ImportRecoverySummary(1, 0, 0), manager().recover())
        val recovered = ledger.record(installed.intent.operationId)!!
        assertEquals(ImportPhase.COMMITTED, recovered.phase)
        assertEquals(copyOpenings, openings)
        assertArrayEquals(installedBytes, OwnedAudioProvider.finalFile(context, recovered.intent.fileToken).readBytes())
        assertEquals(1L, library.overview().totalTracks)
        assertEquals(ImportRecoverySummary(0, 0, 0), manager().recover())
    }

    @Test fun verifiedStagingRecoversButInterruptedCopyIsNeverPublished() = runBlocking {
        val bytes = original.readBytes()
        val manager = manager()
        val verifiedIntent = manager.prepare(source).intent
        ledger.begin(verifiedIntent)
        OwnedAudioProvider.stagingFile(context, verifiedIntent.fileToken).writeBytes(bytes)
        ledger.recordVerified(verifiedIntent.operationId, bytes.size.toLong(), digest(bytes), audio)
        val interruptedIntent = manager.prepare(source).intent
        ledger.begin(interruptedIntent)
        OwnedAudioProvider.stagingFile(context, interruptedIntent.fileToken).writeBytes(bytes.copyOf(4096))
        reopenDatabase()
        assertEquals(ImportRecoverySummary(1, 1, 0), manager().recover())
        assertEquals(ImportPhase.COMMITTED, ledger.record(verifiedIntent.operationId)?.phase)
        assertEquals(ImportIssue.INTERRUPTED, ledger.record(interruptedIntent.operationId)?.issue)
        assertFalse(OwnedAudioProvider.stagingFile(context, interruptedIntent.fileToken).exists())
        assertFalse(OwnedAudioProvider.finalFile(context, interruptedIntent.fileToken).exists())
        assertArrayEquals(bytes, original.readBytes())
        assertEquals(1L, library.overview().totalTracks)
    }

    @Test(timeout = 30_000L) fun interruptedCopyRetryRetiresItsSharedReservationWithoutRecopyingTheOriginal() = runBlocking {
        val manager = manager()
        val preview = manager.prepare(source)
        ledger.begin(preview.intent)
        manager.copyQuota.reserveImport(preview.intent.operationId, original.length())
        io.read {
            OwnedAudioProvider.stagingFile(context, preview.intent.fileToken).writeBytes(ByteArray(4096))
            manager.copyQuota.recordImportBytesOnPhysicalWorker(preview.intent.operationId, 4096L)
        }
        val originalBytes = original.readBytes()
        val interrupted = manager.confirmCopy(preview)
        assertEquals(ImportPhase.FAILED, interrupted.phase)
        assertEquals(ImportIssue.INTERRUPTED, interrupted.issue)
        assertEquals(interrupted, manager.confirmCopy(preview))
        assertEquals(0, openings)
        assertFalse(OwnedAudioProvider.stagingFile(context, preview.intent.fileToken).exists())
        assertFalse(OwnedAudioProvider.finalFile(context, preview.intent.fileToken).exists())
        assertArrayEquals(originalBytes, original.readBytes())
        assertEquals(0L, library.overview().totalTracks)
        val ticket = UUID.randomUUID().toString()
        try {
            val available = manager.copyQuota.reserve(ticket, TransferUploadKind.AUDIO, ImportLimits.QUOTA_BYTES)
            assertEquals(0L, available.ownedAudioBytes)
            assertEquals(0L, available.otherReservedAudioBytes)
        } finally { manager.copyQuota.release(ticket) }
    }

    @Test fun missingOrDifferentInstalledBytesStayPendingAndAreNeverReplaced() = runBlocking {
        val intent = manager().prepare(source).intent
        val bytes = original.readBytes()
        ledger.begin(intent)
        ledger.recordVerified(intent.operationId, bytes.size.toLong(), digest(bytes), audio)
        ledger.markInstalled(intent.operationId)
        assertEquals(ImportRecoverySummary(0, 0, 1), manager().recover())
        assertEquals(ImportPhase.INSTALLED, ledger.record(intent.operationId)?.phase)
        assertEquals(ImportIssue.RECOVERY_REQUIRED, ledger.record(intent.operationId)?.issue)
        val wrong = bytes.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
        OwnedAudioProvider.finalFile(context, intent.fileToken).writeBytes(wrong)
        assertEquals(ImportRecoverySummary(0, 0, 1), manager().recover())
        assertArrayEquals(wrong, OwnedAudioProvider.finalFile(context, intent.fileToken).readBytes())
        assertEquals(0L, library.overview().totalTracks)
    }

    @Test fun sameSizeUnstampedReplacementFailsBeforeInstallationAndPreservesOldCopies() = runBlocking {
        val stableManager = manager()
        val first = stableManager.confirmCopy(stableManager.prepare(source))
        val before = original.readBytes()
        var newOpenings = 0
        val changedManager = manager(open = { _, _ ->
            newOpenings++
            ByteArrayInputStream(if (newOpenings == 1) before else before.copyOf().apply { this[0] = 99 })
        })
        val result = changedManager.confirmCopy(changedManager.prepare(source))
        assertEquals(ImportPhase.FAILED, result.phase)
        assertEquals(ImportIssue.SOURCE_CHANGED, result.issue)
        assertFalse(OwnedAudioProvider.finalFile(context, result.intent.fileToken).exists())
        assertFalse(OwnedAudioProvider.stagingFile(context, result.intent.fileToken).exists())
        assertArrayEquals(before, original.readBytes())
        assertArrayEquals(before, OwnedAudioProvider.finalFile(context, first.intent.fileToken).readBytes())
        assertEquals(1L, library.overview().totalTracks)
    }

    @Test fun reserveIsRecheckedForStreamedBytesAfterPreview() = runBlocking {
        sourceToken = "provider:1"
        val manager = manager(free = { root -> availableBytes - (root.listFiles()?.sumOf { it.length() } ?: 0L) })
        val preview = manager.prepare(source)
        availableBytes = ImportLimits.FREE_RESERVE_BYTES + ImportLimits.BUFFER_BYTES
        val result = manager.confirmCopy(preview)
        assertEquals(ImportPhase.FAILED, result.phase)
        assertEquals(ImportIssue.STORAGE_FULL, result.issue)
        assertFalse(OwnedAudioProvider.stagingFile(context, result.intent.fileToken).exists())
        assertFalse(OwnedAudioProvider.finalFile(context, result.intent.fileToken).exists())
        assertEquals(0L, library.overview().totalTracks)
        assertEquals(150_000L, original.length())
    }

    @Test fun audioValidationFailureAndKnownLengthMismatchRemainTypedFailures() = runBlocking {
        val invalidManager = manager(read = { _, _ -> throw SourceReadException(SourceReadIssue.UNSUPPORTED_FORMAT) })
        val invalid = invalidManager.confirmCopy(invalidManager.prepare(source))
        assertEquals(ImportIssue.UNSUPPORTED_FORMAT, invalid.issue)
        assertEquals(ImportPhase.FAILED, invalid.phase)
        declaredBytes = original.length() + 1
        val manager = manager()
        val mismatch = manager.confirmCopy(manager.prepare(source))
        assertEquals(ImportIssue.SOURCE_CHANGED, mismatch.issue)
        assertEquals(ImportPhase.FAILED, mismatch.phase)
        assertEquals(0L, library.overview().totalTracks)
        assertFalse(OwnedAudioProvider.finalFile(context, mismatch.intent.fileToken).exists())
    }

    @Test fun confirmationMustMatchTheLatestPreviewAndOperationRetriesKeepTheirIntent() = runBlocking {
        val manager = manager()
        val discarded = manager.prepare(source)
        val current = manager.prepare(source)
        expect(ImportIssue.INVALID_INPUT) { manager.confirmCopy(discarded) }
        val committed = manager.confirmCopy(current)
        val altered = current.copy(intent = current.intent.copy(candidate = current.intent.candidate.copy(displayName = "different.mp3")))
        expect(ImportIssue.CONFLICT) { manager.confirmCopy(altered) }
        assertEquals(committed, manager.confirmCopy(current))
        assertEquals(1L, library.overview().totalTracks)
    }

    @Test fun cancellationRetainsThePhysicalChannelUntilItStopsAndDeletesOnlyItsStaging() = runBlocking {
        withTimeout(15_000) {
            sourceToken = "provider:1"
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val before = original.readBytes()
            val manager = manager(open = { _, _ -> object : ByteArrayInputStream(before) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    return super.read(buffer, offset, length)
                }
            } })
            val preview = manager.prepare(source)
            val caller = async(Dispatchers.Default) { manager.confirmCopy(preview) }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                caller.cancel()
                expect(ImportIssue.BUSY) { manager.prepare(source) }
                try { io.read { Unit }; throw AssertionError("Expected occupied physical channel") }
                catch (error: SourceReadException) { assertEquals(SourceReadIssue.BUSY, error.issue) }
                release.countDown()
                try { caller.await(); throw AssertionError("Expected caller cancellation") }
                catch (_: CancellationException) { }
                io.awaitIdle()
                assertEquals(ImportPhase.CANCELLED, ledger.record(preview.intent.operationId)?.phase)
                assertFalse(OwnedAudioProvider.stagingFile(context, preview.intent.fileToken).exists())
                assertFalse(OwnedAudioProvider.finalFile(context, preview.intent.fileToken).exists())
                assertArrayEquals(before, original.readBytes())
                assertEquals(0L, library.overview().totalTracks)
            } finally { release.countDown(); caller.cancel() }
        }
    }

    @Test fun ledgerPagesPendingRowsAndRejectsInstallingOrVerifyingATerminalOperation() = runBlocking {
        val intents = (0 until 103).map { index ->
            val candidate = candidate().copy(displayName = "audio-$index.mp3")
            ImportIntent(UUID.randomUUID().toString(), source, candidate, AndroidLocalSources.sourceIdentity(source))
        }
        intents.forEach { ledger.begin(it) }
        val first = ledger.pending()
        assertEquals(100, first.records.size)
        val second = ledger.pending(first.nextOperationId)
        assertEquals(3, second.records.size)
        assertNull(second.nextOperationId)
        assertEquals(103, (first.records + second.records).map { it.intent.operationId }.distinct().size)
        val cancelled = ledger.markUnfinished(intents.first().operationId, ImportPhase.CANCELLED, ImportIssue.CANCELLED)
        assertEquals(cancelled, ledger.begin(intents.first()))
        expect(ImportIssue.CONFLICT) { ledger.markInstalled(cancelled.intent.operationId) }
        expect(ImportIssue.CONFLICT) { ledger.recordVerified(cancelled.intent.operationId, 1, "0".repeat(64), audio) }
    }

    private fun candidate() = ObservedCandidate(source.sourceId, source.rootObjectId!!, source.uri, null,
        original.name, "audio/mpeg", LocalNodeKind.AUDIO, declaredBytes, providerContentToken = sourceToken,
        entityProof = "document:test-audio:document:1")

    private val sources = object : LocalSourceAccess {
        override suspend fun observe(source: LocalSourceSpec): LocalSourceObservation = LocalSourceObservation(
            source, AndroidLocalSources.sourceIdentity(source), LocalGrantObservation(read = ObservedCapability.AVAILABLE), candidate(),
        )
        override suspend fun open(source: LocalSourceSpec, parentObjectId: String?, sinceMediaGeneration: Long?): LocalCandidateCursor =
            throw AssertionError("Import must not enumerate the source")
    }

    private fun manager(
        open: (ObservedCandidate, CancellationSignal) -> InputStream = { _, _ -> openings++; original.inputStream() },
        read: (File, CancellationSignal) -> LocalAudioMetadata = { _, signal -> signal.throwIfCanceled(); audio },
        free: (File) -> Long = { availableBytes },
    ) = LocalImportManager(context, sources, LocalMetadataReader(context, io), ledger, io,
        readMetadata = read, openInput = open, freeBytes = free, syncDirectory = { })

    private suspend fun openDatabase() {
        database = Room.databaseBuilder(context, MeloXDatabase::class.java, databaseFile.absolutePath)
            .setDriver(BundledSQLiteDriver()).build()
        library = RoomLocalLibraryRepository(database)
        library.initialize()
        ledger = RoomImportLedger(library, OwnedAudioProvider.authority(context))
    }
    private suspend fun reopenDatabase() { database.close(); openDatabase() }
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private suspend fun expect(issue: ImportIssue, action: suspend () -> Any?) {
        try { action(); throw AssertionError("Expected $issue") }
        catch (error: ImportException) { assertEquals(issue, error.issue) }
    }
}
