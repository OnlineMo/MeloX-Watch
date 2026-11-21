package io.github.onlinemo.meloxwatch.data.transfer

import android.content.Context
import android.content.ContextWrapper
import io.github.onlinemo.meloxwatch.data.database.withTestWriterSql
import io.github.onlinemo.meloxwatch.data.imports.AudioCopyQuota
import io.github.onlinemo.meloxwatch.data.imports.ImportLedger
import io.github.onlinemo.meloxwatch.data.imports.ImportLimits
import io.github.onlinemo.meloxwatch.data.imports.ImportPhase
import io.github.onlinemo.meloxwatch.data.imports.LocalImportManager
import io.github.onlinemo.meloxwatch.data.imports.OwnedAudioProvider
import io.github.onlinemo.meloxwatch.data.imports.TransferImportLedger
import io.github.onlinemo.meloxwatch.data.imports.TransferImportRecord
import io.github.onlinemo.meloxwatch.data.imports.TransferImportSelection
import io.github.onlinemo.meloxwatch.data.library.LocalAudioMetadata
import io.github.onlinemo.meloxwatch.data.library.RoomImportLedger
import io.github.onlinemo.meloxwatch.data.local.LocalCandidateCursor
import io.github.onlinemo.meloxwatch.data.local.LocalGrantObservation
import io.github.onlinemo.meloxwatch.data.local.LocalMetadataReader
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.LocalSourceAccess
import io.github.onlinemo.meloxwatch.data.local.LocalSourceObservation
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Real private files, import owner and Room receipt. Metadata decoding and fsync durability are separate gates. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TransferAudioRoomReceiptTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var fixture: CompanionRoomFixture
    private lateinit var io: LocalProviderIo
    private lateinit var quota: AudioCopyQuota
    private lateinit var store: TransferUploadStore
    private lateinit var ledger: RoomImportLedger
    private lateinit var manager: LocalImportManager
    private lateinit var backend: RoomCompanionTransferBackend
    private val metadata = LocalAudioMetadata(title = "Actual transferred file", mimeType = "audio/wav", durationMs = 100L)
    private val roomBytes = ImportLimits.FREE_RESERVE_BYTES + 2 * ImportLimits.QUOTA_BYTES
    private var metadataReads = 0

    @Before fun open() = runBlocking {
        val privateRoot = temporary.newFolder("private-audio")
        context = object : ContextWrapper(RuntimeEnvironment.getApplication() as Context) {
            override fun getNoBackupFilesDir(): File = privateRoot
        }
        fixture = CompanionRoomFixture(context, File(temporary.root, "audio-room.db"))
        fixture.initialize()
        fixture.newSession(setOf(TransferCapability.UPLOAD_AUDIO))
        io = LocalProviderIo()
        quota = AudioCopyQuota(context, io) { roomBytes }
        store = TransferUploadStore(quota.transferDirectory, io, quota, PcmWavTransferValidator,
            TransferDiskSpace { roomBytes }, fixture.clock)
        connectOwners()
    }
    @After fun close() { fixture.close() }

    @Test(timeout = 30_000L) fun actualAudioPublicationAndOriginalCanonicalReceiptShareTheSameRoomRevisionAndSurviveRestart() = runBlocking {
        val bytes = TransferTestFixtures.wav(frames = 40_000)
        val declaration = TransferTestFixtures.declaration(bytes, declared = null)
        val binding = binding(declaration)
        val receipt = upload(bytes, declaration, binding)
        assertStored(receipt)
        assertArrayEquals(bytes, OwnedAudioProvider.finalFile(context, binding.operationId).readBytes())
        assertFalse(OwnedAudioProvider.stagingFile(context, binding.operationId).exists())
        assertTrue(quota.transferDirectory.listFiles().orEmpty().isEmpty())
        assertEquals(1, metadataReads)
        assertEquals(1L, fixture.library.overview().totalTracks)
        assertReceipt(receipt, checkNotNull(backend.committed(binding, permit())))
        reopenOwners()
        assertReceipt(receipt, checkNotNull(backend.committed(binding, permit())))
        assertStored(receipt)
        assertEquals(1L, fixture.library.overview().totalTracks)
        assertArrayEquals(bytes, OwnedAudioProvider.finalFile(context, binding.operationId).readBytes())
    }

    @Test(timeout = 30_000L) fun revocationBeforeTheActualRoomClaimLeavesInstalledBytesAndFreshExactAuthorizationCanResume() = runBlocking {
        exerciseBoundary(afterClaim = false)
    }

    @Test(timeout = 30_000L) fun revocationAndStageRetirementAfterTheActualRoomClaimWaitForBothOriginalReceiptAndAudio() = runBlocking {
        exerciseBoundary(afterClaim = true)
    }

    @Test(timeout = 30_000L) fun failedCanonicalReceiptWriteRollsBackTheActualAudioRowsButRetainsInstalledFileForExactRecovery() = runBlocking {
        val before = fixture.library.overview()
        val bytes = TransferTestFixtures.wav()
        val declaration = TransferTestFixtures.declaration(bytes)
        val binding = binding(declaration)
        fixture.database.withTestWriterSql { execute("""
            CREATE TRIGGER controlled_audio_receipt BEFORE UPDATE ON transfer_room_operations
            WHEN NEW.resultKind IS NOT NULL BEGIN SELECT RAISE(ABORT, 'controlled audio receipt failure'); END
        """.trimIndent()) }
        try {
            expectTransferIssue(TransferIssue.RECOVERY_REQUIRED) { upload(bytes, declaration, binding) }
            assertEquals(before, fixture.library.overview())
            assertEquals(ImportPhase.INSTALLED, checkNotNull(ledger.recordTransferred(binding.operationId)).phase)
            assertArrayEquals(bytes, OwnedAudioProvider.finalFile(context, binding.operationId).readBytes())
            fixture.database.withTestWriterSql {
                assertEquals(0L, scalarLong("SELECT COUNT(*) FROM local_sources WHERE sourceId = ?", "transfer:${binding.operationId}"))
                assertEquals(0L, scalarLong("SELECT COUNT(*) FROM local_tracks"))
                assertNull(scalarText("SELECT resultKind FROM transfer_room_operations WHERE operationId = ?", binding.operationId))
                execute("DROP TRIGGER controlled_audio_receipt")
            }
            reopenOwners()
            assertEquals(1L, manager.recoverTransferred().pending)
            assertEquals(0L, fixture.library.overview().totalTracks)
            val accepted = checkNotNull(backend.committed(binding, permit()))
            assertStored(accepted)
            assertEquals(1, metadataReads)
            assertEquals(1L, fixture.library.overview().totalTracks)
            assertArrayEquals(bytes, OwnedAudioProvider.finalFile(context, binding.operationId).readBytes())
        } finally { fixture.database.withTestWriterSql { execute("DROP TRIGGER IF EXISTS controlled_audio_receipt") } }
    }

    @Test(timeout = 30_000L) fun committedAudioWithoutItsCanonicalReceiptIsRejectedAndNeitherReplayPathManufacturesACompletion() = runBlocking {
        val bytes = TransferTestFixtures.wav()
        val declaration = TransferTestFixtures.declaration(bytes)
        val binding = binding(declaration)
        val original = upload(bytes, declaration, binding)
        val before = fixture.library.overview()
        fixture.database.withTestWriterSql { execute("DELETE FROM transfer_room_operations WHERE operationId = ?", binding.operationId) }
        expectTransferIssue(TransferIssue.RECOVERY_REQUIRED) { backend.committed(binding, permit()) }
        assertNull(fixture.database.transferRoomDao().operation(binding.operationId))
        val stage = prepared(bytes, declaration, binding)
        try {
            val verified = stage.verify(permit())
            stage.markCommitting()
            expectTransferIssue(TransferIssue.RECOVERY_REQUIRED) { backend.commitUpload(binding, verified, permit()) }
            assertFalse(store.progress.value?.phase == TransferUploadPhase.COMPLETED)
            assertNull(checkNotNull(fixture.database.transferRoomDao().operation(binding.operationId)).resultKind)
        } finally { stage.discard(); withTimeout(5_000L) { store.awaitIdle() } }
        assertEquals(before, fixture.library.overview())
        assertEquals(ImportPhase.COMMITTED, checkNotNull(ledger.recordTransferred(binding.operationId)).phase)
        assertReceipt(original, checkNotNull(ledger.recordTransferred(binding.operationId)?.receipt()))
        assertArrayEquals(bytes, OwnedAudioProvider.finalFile(context, binding.operationId).readBytes())
    }

    @Test(timeout = 30_000L) fun anUnknownAudioOperationCannotCreateAnOfflineReceiptOrAnOwnedObject() = runBlocking {
        val declaration = TransferTestFixtures.declaration(TransferTestFixtures.wav())
        val binding = binding(declaration)
        assertNull(backend.committed(binding, permit()))
        assertNull(fixture.database.transferRoomDao().operation(binding.operationId))
        assertNull(ledger.recordTransferred(binding.operationId))
        assertFalse(OwnedAudioProvider.finalFile(context, binding.operationId).exists())
        assertEquals(0L, fixture.library.overview().totalTracks)
        assertEquals(0, metadataReads)
    }

    private suspend fun exerciseBoundary(afterClaim: Boolean) = coroutineScope {
        val bytes = TransferTestFixtures.wav()
        val declaration = TransferTestFixtures.declaration(bytes)
        val binding = binding(declaration)
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val actual = ledger
        val hooked = object : ImportLedger by actual, TransferImportLedger by actual {
            override suspend fun commitTransferred(selection: TransferImportSelection, permit: TransferPermit,
                ownedSpec: LocalSourceSpec, grant: LocalGrantObservation, ownedCandidate: ObservedCandidate,
                commitBoundary: () -> TransferCommitClaim): TransferImportRecord = actual.commitTransferred(
                selection, permit, ownedSpec, grant, ownedCandidate,
            ) {
                val claimed = if (afterClaim) commitBoundary() else null
                entered.complete(Unit)
                check(release.await(10L, TimeUnit.SECONDS))
                claimed ?: commitBoundary()
            }
        }
        connectOwners(hooked)
        val stage = prepared(bytes, declaration, binding)
        val verified = stage.verify(permit())
        stage.markCommitting()
        val granted = permit()
        val caller = async(Dispatchers.Default) { runCatching { backend.commitUpload(binding, verified, granted) } }
        var retirement: kotlinx.coroutines.Deferred<Unit>? = null
        try {
            withTimeout(5_000L) { entered.await(); io.awaitIdle() }
            fixture.authority.revokeForLocalUi(fixture.peer)
            if (afterClaim) {
                retirement = async(start = CoroutineStart.UNDISPATCHED) { stage.discard() }
                assertFalse(checkNotNull(retirement).isCompleted)
            }
            assertFalse(caller.isCompleted)
            assertArrayEquals(bytes, OwnedAudioProvider.finalFile(context, binding.operationId).readBytes())
            release.countDown()
            val outcome = withTimeout(10_000L) { caller.await() }
            withTimeout(5_000L) { retirement?.await(); stage.discard(); store.awaitIdle() }
            if (afterClaim) {
                val receipt = outcome.getOrThrow()
                assertStored(receipt)
                assertEquals(TransferUploadPhase.COMPLETED, store.progress.value?.phase)
            } else {
                assertEquals(TransferIssue.NOT_APPROVED, (outcome.exceptionOrNull() as TransferException).issue)
                assertEquals(ImportPhase.INSTALLED, checkNotNull(ledger.recordTransferred(binding.operationId)).phase)
                assertEquals(0L, fixture.library.overview().totalTracks)
                assertNull(checkNotNull(fixture.database.transferRoomDao().operation(binding.operationId)).resultKind)
            }
            fixture.newSession(setOf(TransferCapability.UPLOAD_AUDIO))
            connectOwners()
            val accepted = checkNotNull(backend.committed(binding, permit()))
            assertStored(accepted)
            assertEquals(1L, fixture.library.overview().totalTracks)
            assertArrayEquals(bytes, OwnedAudioProvider.finalFile(context, binding.operationId).readBytes())
        } finally {
            release.countDown()
            withTimeout(10_000L) { caller.join(); retirement?.await(); stage.discard(); store.awaitIdle() }
        }
    }

    private fun connectOwners(owner: ImportLedger? = null) {
        ledger = RoomImportLedger(fixture.library, OwnedAudioProvider.authority(context))
        manager = LocalImportManager(context, noExternalSource, LocalMetadataReader(context, io), owner ?: ledger, io,
            readMetadata = { _, signal -> signal.throwIfCanceled(); metadataReads++; metadata },
            freeBytes = { roomBytes }, syncDirectory = {}, copyQuota = quota)
        backend = fixture.backendWithUploadOwner(ImportedAudioTransferBackend(manager))
    }
    private suspend fun reopenOwners() {
        fixture.reopen()
        fixture.newSession(setOf(TransferCapability.UPLOAD_AUDIO))
        connectOwners()
    }
    private fun permit() = fixture.permit(TransferCapability.UPLOAD_AUDIO)
    private fun binding(declaration: TransferUploadDeclaration) = TransferProtocolCodec.operationBinding(fixture.peer,
        TransferRequest.BeginUpload(CompanionRoomFixture.uuid(), fixture.scope, declaration))
    private suspend fun prepared(bytes: ByteArray, declaration: TransferUploadDeclaration,
        binding: TransferOperationBinding): TransferUploadStage {
        val stage = store.begin(binding, declaration, permit())
        try {
            var offset = 0
            while (offset < bytes.size) {
                val next = minOf(offset + TransferLimits.CHUNK_BYTES, bytes.size)
                stage.append(permit(), offset.toLong(), bytes.copyOfRange(offset, next))
                offset = next
            }
            return stage
        } catch (error: Throwable) { stage.discard(); throw error }
    }
    private suspend fun upload(bytes: ByteArray, declaration: TransferUploadDeclaration,
        binding: TransferOperationBinding): TransferReceipt {
        val stage = prepared(bytes, declaration, binding)
        try {
            val verified = stage.verify(permit())
            stage.markCommitting()
            return backend.commitUpload(binding, verified, permit())
        } finally { stage.discard(); withTimeout(5_000L) { store.awaitIdle() } }
    }
    private suspend fun assertStored(receipt: TransferReceipt) {
        val audio = checkNotNull(ledger.recordTransferred(receipt.binding.operationId))
        assertEquals(ImportPhase.COMMITTED, audio.phase)
        assertReceipt(receipt, checkNotNull(audio.receipt()))
        val canonical = checkNotNull(fixture.database.transferRoomDao().operation(receipt.binding.operationId))
        assertEquals(receipt.binding.payloadHash.forLocalStorage(), canonical.payloadHash)
        assertEquals(receipt.result.wire, canonical.resultKind)
        assertEquals(receipt.revision, canonical.appliedRevision)
        assertEquals(receipt.resultId, canonical.resultId)
        assertEquals(receipt.actualBytes, canonical.actualBytes)
        assertEquals(receipt.revision, fixture.library.overview().revision)
        assertNotNull(fixture.library.resolveLocalRead(checkNotNull(audio.trackKey)))
    }
    private fun assertReceipt(expected: TransferReceipt, actual: TransferReceipt) {
        assertTrue(expected.binding.sameOperation(actual.binding)); assertEquals(expected.result, actual.result)
        assertEquals(expected.revision, actual.revision); assertEquals(expected.resultId, actual.resultId)
        assertEquals(expected.actualBytes, actual.actualBytes)
    }
    private val noExternalSource = object : LocalSourceAccess {
        override suspend fun observe(source: LocalSourceSpec): LocalSourceObservation = throw AssertionError("Audio transfer cannot acquire an external source grant")
        override suspend fun open(source: LocalSourceSpec, parentObjectId: String?, sinceMediaGeneration: Long?): LocalCandidateCursor =
            throw AssertionError("Audio transfer cannot enumerate an external source")
    }
}
