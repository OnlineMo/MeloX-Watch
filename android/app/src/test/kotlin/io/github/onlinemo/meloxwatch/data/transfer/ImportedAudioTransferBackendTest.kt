package io.github.onlinemo.meloxwatch.data.transfer

import android.content.Context
import android.content.ContextWrapper
import android.os.CancellationSignal
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.imports.AudioCopyQuota
import io.github.onlinemo.meloxwatch.data.imports.ImportLimits
import io.github.onlinemo.meloxwatch.data.imports.ImportLedger
import io.github.onlinemo.meloxwatch.data.imports.ImportPhase
import io.github.onlinemo.meloxwatch.data.imports.LocalImportManager
import io.github.onlinemo.meloxwatch.data.imports.OwnedAudioProvider
import io.github.onlinemo.meloxwatch.data.imports.TransferImportLedger
import io.github.onlinemo.meloxwatch.data.imports.TransferImportRecord
import io.github.onlinemo.meloxwatch.data.imports.TransferImportSelection
import io.github.onlinemo.meloxwatch.data.library.LocalAudioMetadata
import io.github.onlinemo.meloxwatch.data.library.RoomImportLedger
import io.github.onlinemo.meloxwatch.data.library.RoomLocalLibraryRepository
import io.github.onlinemo.meloxwatch.data.local.LocalCandidateCursor
import io.github.onlinemo.meloxwatch.data.local.LocalGrantObservation
import io.github.onlinemo.meloxwatch.data.local.LocalMetadataReader
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.LocalSourceAccess
import io.github.onlinemo.meloxwatch.data.local.LocalSourceObservation
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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

/** Real files, Room and authorization. Parser/codec output and fsync durability need separate evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ImportedAudioTransferBackendTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var database: MeloXDatabase
    private lateinit var library: RoomLocalLibraryRepository
    private lateinit var ledger: RoomImportLedger
    private lateinit var io: LocalProviderIo
    private lateinit var quota: AudioCopyQuota
    private lateinit var store: TransferUploadStore
    private lateinit var manager: LocalImportManager
    private lateinit var backend: ImportedAudioTransferBackend
    private lateinit var file: File
    private val metadata = LocalAudioMetadata(title = "Uploaded audio", mimeType = "audio/wav", durationMs = 100)
    private val roomBytes = ImportLimits.FREE_RESERVE_BYTES + 2 * ImportLimits.QUOTA_BYTES
    private var readMetadata: (File, CancellationSignal) -> LocalAudioMetadata = { _, signal -> signal.throwIfCanceled()
        metadata }
    private var sync: (File) -> Unit = {}

    @Before fun setup() = runBlocking {
        val privateRoot = temporary.newFolder("private")
        context = object : ContextWrapper(RuntimeEnvironment.getApplication() as Context) {
            override fun getNoBackupFilesDir(): File = privateRoot
        }
        io = LocalProviderIo()
        quota = AudioCopyQuota(context, io) { roomBytes }
        file = File(temporary.root, "transfer-import.db")
        open()
        store = TransferUploadStore(quota.transferDirectory, io, quota, PcmWavTransferValidator,
            TransferDiskSpace { roomBytes })
    }

    @After fun close() { database.close() }

    @Test(timeout = 30_000L) fun unknownLengthCreatesExactOwnedAudioAndDurableReceiptWithoutASourceGrant() = runBlocking {
        val auth = TransferTestFixtures.authorized()
        val bytes = TransferTestFixtures.wav(frames = 40_000)
        val declaration = TransferTestFixtures.declaration(bytes, declared = null)
        val binding = TransferTestFixtures.binding(auth, declaration)
        val receipt = upload(auth, bytes, declaration, binding)
        assertEquals(bytes.size.toLong(), receipt.actualBytes)
        assertEquals(library.overview().revision, receipt.revision)
        val record = ledger.recordTransferred(binding.operationId)!!
        assertEquals(ImportPhase.COMMITTED, record.phase)
        assertArrayEquals(bytes, OwnedAudioProvider.finalFile(context, binding.operationId).readBytes())
        assertFalse(OwnedAudioProvider.stagingFile(context, binding.operationId).exists())
        assertTrue(quota.transferDirectory.listFiles().orEmpty().isEmpty())
        val read = library.resolveLocalRead(record.trackKey!!)
        assertEquals("sha256:${declaration.sha256.forLocalStorage()}", read.verifiedContentToken)
        assertEquals(1L, library.overview().totalTracks)
        assertReceipt(receipt, backend.committed(binding, auth.permit())!!)
        reopen()
        assertReceipt(receipt, backend.committed(binding, auth.permit())!!)
        assertEquals(1L, library.overview().totalTracks)
    }

    @Test(timeout = 30_000L) fun identicalNamesAndHashesStillCreateIndependentImmutableObjects() = runBlocking {
        val auth = TransferTestFixtures.authorized()
        val bytes = TransferTestFixtures.wav()
        val declaration = TransferTestFixtures.declaration(bytes, name = "同名.wav")
        val first = TransferTestFixtures.binding(auth, declaration)
        val second = TransferTestFixtures.binding(auth, declaration)
        val a = upload(auth, bytes, declaration, first)
        val b = upload(auth, bytes, declaration, second)
        assertNotEquals(a.resultId, b.resultId)
        assertNotEquals(a.revision, b.revision)
        assertArrayEquals(bytes, OwnedAudioProvider.finalFile(context, first.operationId).readBytes())
        assertArrayEquals(bytes, OwnedAudioProvider.finalFile(context, second.operationId).readBytes())
        assertEquals(2L, library.overview().totalTracks)
    }

    @Test(timeout = 30_000L) fun changedPayloadPeerOrTargetCannotReuseAnOperationReceipt() = runBlocking {
        val auth = TransferTestFixtures.authorized()
        val bytes = TransferTestFixtures.wav()
        val declaration = TransferTestFixtures.declaration(bytes)
        val binding = TransferTestFixtures.binding(auth, declaration)
        upload(auth, bytes, declaration, binding)
        val changed = TransferTestFixtures.binding(auth, TransferTestFixtures.declaration(bytes, name = "Other.wav"), binding.operationId)
        expect(TransferIssue.CONFLICT) { backend.committed(changed, auth.permit()) }
        val another = TransferTestFixtures.authorized()
        val otherPeer = TransferTestFixtures.binding(another, declaration, binding.operationId)
        expect(TransferIssue.CONFLICT) { backend.committed(otherPeer, another.permit()) }
        assertEquals(1L, library.overview().totalTracks)
        assertArrayEquals(bytes, OwnedAudioProvider.finalFile(context, binding.operationId).readBytes())
    }

    @Test(timeout = 30_000L) fun wrongOffsetAndDigestNeverReachTheImportLedger() = runBlocking {
        val auth = TransferTestFixtures.authorized()
        val bytes = TransferTestFixtures.wav()
        val declaration = TransferTestFixtures.declaration(bytes)
        val binding = TransferTestFixtures.binding(auth, declaration)
        val stage = store.begin(binding, declaration, auth.permit())
        try {
            expect(TransferIssue.INVALID_OFFSET) { stage.append(auth.permit(), 1L, bytes) }
            stage.append(auth.permit(), 0L, bytes.copyOf().also { it[50] = (it[50].toInt() xor 1).toByte() })
            expect(TransferIssue.INTEGRITY_FAILED) { stage.verify(auth.permit()) }
            assertNull(ledger.recordTransferred(binding.operationId))
            assertEquals(0L, library.overview().totalTracks)
        } finally { stage.discard() }
        assertTrue(quota.transferDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test(timeout = 30_000L) fun actualMetadataFailureRetiresInputWithoutPublishingAnOwnedObject() = runBlocking {
        readMetadata = { _, _ -> throw SourceReadException(SourceReadIssue.UNSUPPORTED_FORMAT) }
        makeManager(ledger)
        val auth = TransferTestFixtures.authorized()
        val bytes = TransferTestFixtures.wav()
        val declaration = TransferTestFixtures.declaration(bytes)
        val binding = TransferTestFixtures.binding(auth, declaration)
        expect(TransferIssue.UNSUPPORTED_TYPE) { upload(auth, bytes, declaration, binding) }
        assertEquals(ImportPhase.FAILED, ledger.recordTransferred(binding.operationId)!!.phase)
        assertFalse(OwnedAudioProvider.finalFile(context, binding.operationId).exists())
        assertEquals(0L, library.overview().totalTracks)
    }

    @Test(timeout = 30_000L) fun installedBytesSurviveRevocationAndStartupNeedsFreshExactAuthorization() = runBlocking {
        val auth = TransferTestFixtures.authorized()
        val bytes = TransferTestFixtures.wav()
        val declaration = TransferTestFixtures.declaration(bytes)
        val binding = TransferTestFixtures.binding(auth, declaration)
        var revoked = false
        sync = {
            if (!revoked && OwnedAudioProvider.finalFile(context, binding.operationId).exists()) {
                revoked = true
                auth.authority.revokeForLocalUi(auth.peer)
            }
        }
        makeManager(ledger)
        expect(TransferIssue.NOT_APPROVED) { upload(auth, bytes, declaration, binding) }
        assertEquals(ImportPhase.INSTALLED, ledger.recordTransferred(binding.operationId)!!.phase)
        assertEquals(0L, library.overview().totalTracks)
        assertArrayEquals(bytes, OwnedAudioProvider.finalFile(context, binding.operationId).readBytes())
        reopen()
        assertEquals(1L, manager.recoverTransferred().pending)
        assertEquals(0L, library.overview().totalTracks)
        val fresh = freshAuthorization(auth)
        val receipt = backend.committed(binding, fresh.permit())!!
        assertEquals(ImportPhase.COMMITTED, ledger.recordTransferred(binding.operationId)!!.phase)
        assertEquals(1L, library.overview().totalTracks)
        assertReceipt(receipt, backend.committed(binding, fresh.permit())!!)
    }

    @Test(timeout = 30_000L) fun wrongInstalledBytesRemainPendingAndAreNeverReplacedOnFreshRetry() = runBlocking {
        val auth = TransferTestFixtures.authorized()
        val bytes = TransferTestFixtures.wav()
        val declaration = TransferTestFixtures.declaration(bytes)
        val binding = TransferTestFixtures.binding(auth, declaration)
        sync = { if (OwnedAudioProvider.finalFile(context, binding.operationId).exists()) auth.authority.revokeForLocalUi(auth.peer) }
        makeManager(ledger)
        expect(TransferIssue.NOT_APPROVED) { upload(auth, bytes, declaration, binding) }
        val altered = bytes.copyOf().also { it[50] = (it[50].toInt() xor 1).toByte() }
        OwnedAudioProvider.finalFile(context, binding.operationId).writeBytes(altered)
        val fresh = freshAuthorization(auth)
        expect(TransferIssue.INTEGRITY_FAILED) { backend.committed(binding, fresh.permit()) }
        assertArrayEquals(altered, OwnedAudioProvider.finalFile(context, binding.operationId).readBytes())
        assertEquals(ImportPhase.INSTALLED, ledger.recordTransferred(binding.operationId)!!.phase)
        assertEquals(0L, library.overview().totalTracks)
    }

    @Test(timeout = 30_000L) fun cancelledValidatorWaiterDoesNotReleaseThePhysicalChannelOrUploadOwner() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        readMetadata = { _, _ -> entered.countDown()
            check(release.await(10L, TimeUnit.SECONDS))
            metadata }
        makeManager(ledger)
        val auth = TransferTestFixtures.authorized()
        val bytes = TransferTestFixtures.wav()
        val declaration = TransferTestFixtures.declaration(bytes)
        val binding = TransferTestFixtures.binding(auth, declaration)
        val stage = prepared(auth, bytes, declaration, binding)
        val verified = stage.verify(auth.permit())
        stage.markCommitting()
        val caller = async(Dispatchers.Default) { backend.commitUpload(binding, verified, auth.permit()) }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5L, TimeUnit.SECONDS) })
            caller.cancel()
            val retirement = async(Dispatchers.Default) { stage.discard() }
            delay(100L)
            assertFalse(retirement.isCompleted)
            assertTrue(stage.file.isFile)
            release.countDown()
            try { caller.await()
                throw AssertionError("Expected cancellation") } catch (_: CancellationException) {}
            withTimeout(5000L) { retirement.await()
                store.awaitIdle() }
            assertEquals(ImportPhase.CANCELLED, ledger.recordTransferred(binding.operationId)!!.phase)
            assertFalse(OwnedAudioProvider.finalFile(context, binding.operationId).exists())
            assertEquals(0L, library.overview().totalTracks)
        } finally { release.countDown()
            caller.cancel()
            caller.join()
            stage.discard() }
    }

    @Test(timeout = 30_000L) fun stageCancellationBeforeRoomClaimBlocksPublicationAndJoinsTheBackend() = runBlocking {
        exerciseRoomBoundary(cancelAfterClaim = false)
    }

    @Test(timeout = 30_000L) fun stageCancellationAndRevocationAfterRoomClaimRetainReceiptAndJoinRoomBeforeRetirement() = runBlocking {
        exerciseRoomBoundary(cancelAfterClaim = true)
    }

    private suspend fun exerciseRoomBoundary(cancelAfterClaim: Boolean) = kotlinx.coroutines.coroutineScope {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val auth = TransferTestFixtures.authorized()
        val bytes = TransferTestFixtures.wav()
        val declaration = TransferTestFixtures.declaration(bytes)
        val binding = TransferTestFixtures.binding(auth, declaration)
        val hooked = object : ImportLedger by ledger, TransferImportLedger by ledger {
            override suspend fun commitTransferred(selection: TransferImportSelection, permit: TransferPermit,
                ownedSpec: LocalSourceSpec, grant: LocalGrantObservation, ownedCandidate: ObservedCandidate,
                commitBoundary: () -> TransferCommitClaim): TransferImportRecord = ledger.commitTransferred(
                    selection, permit, ownedSpec, grant, ownedCandidate,
                ) {
                    val accepted = if (cancelAfterClaim) commitBoundary() else null
                    entered.complete(Unit)
                    check(release.await(10L, TimeUnit.SECONDS))
                    accepted ?: commitBoundary()
                }
        }
        makeManager(hooked)
        val stage = prepared(auth, bytes, declaration, binding)
        val verified = stage.verify(auth.permit())
        stage.markCommitting()
        val caller = async(Dispatchers.Default) { runCatching { backend.commitUpload(binding, verified, auth.permit()) } }
        try {
            withTimeout(5000L) { entered.await() }
            io.awaitIdle()
            val retirement = async(Dispatchers.Default) { stage.discard() }
            delay(100L)
            assertFalse(retirement.isCompleted)
            if (cancelAfterClaim) auth.authority.revokeForLocalUi(auth.peer)
            release.countDown()
            val outcome = caller.await()
            withTimeout(5000L) { retirement.await()
                store.awaitIdle() }
            if (cancelAfterClaim) {
                val receipt = outcome.getOrThrow()
                assertReceipt(receipt, ledger.recordTransferred(binding.operationId)!!.receipt()!!)
                assertEquals(TransferUploadPhase.COMPLETED, store.progress.value!!.phase)
                assertEquals(1L, library.overview().totalTracks)
            } else {
                assertEquals(TransferIssue.CLOSED, (outcome.exceptionOrNull() as TransferException).issue)
                assertEquals(ImportPhase.INSTALLED, ledger.recordTransferred(binding.operationId)!!.phase)
                assertEquals(0L, library.overview().totalTracks)
            }
            assertArrayEquals(bytes, OwnedAudioProvider.finalFile(context, binding.operationId).readBytes())
        } finally { release.countDown()
            caller.join()
            stage.discard() }
    }

    private suspend fun prepared(auth: TransferTestFixtures.Authorized, bytes: ByteArray,
        declaration: TransferUploadDeclaration, binding: TransferOperationBinding): TransferUploadStage {
        val stage = store.begin(binding, declaration, auth.permit())
        var offset = 0
        while (offset < bytes.size) {
            val next = minOf(offset + TransferLimits.CHUNK_BYTES, bytes.size)
            stage.append(auth.permit(), offset.toLong(), bytes.copyOfRange(offset, next))
            offset = next
        }
        return stage
    }
    private suspend fun upload(auth: TransferTestFixtures.Authorized, bytes: ByteArray,
        declaration: TransferUploadDeclaration, binding: TransferOperationBinding): TransferReceipt {
        val stage = prepared(auth, bytes, declaration, binding)
        try {
            val verified = stage.verify(auth.permit())
            stage.markCommitting()
            return backend.commitUpload(binding, verified, auth.permit())
        } finally { stage.discard() }
    }
    private suspend fun open() {
        database = Room.databaseBuilder(context, MeloXDatabase::class.java, file.absolutePath)
            .setDriver(BundledSQLiteDriver()).build()
        library = RoomLocalLibraryRepository(database)
        library.initialize()
        ledger = RoomImportLedger(library, OwnedAudioProvider.authority(context))
        makeManager(ledger)
    }
    private suspend fun reopen() { database.close()
        open() }
    private fun makeManager(owner: ImportLedger) {
        manager = LocalImportManager(context, noExternalSource, LocalMetadataReader(context, io), owner, io,
            readMetadata = readMetadata, freeBytes = { roomBytes }, syncDirectory = { sync(it) }, copyQuota = quota)
        backend = ImportedAudioTransferBackend(manager)
    }
    private val noExternalSource = object : LocalSourceAccess {
        override suspend fun observe(source: LocalSourceSpec): LocalSourceObservation = throw AssertionError("Transfer cannot fabricate a source grant")
        override suspend fun open(source: LocalSourceSpec, parentObjectId: String?, sinceMediaGeneration: Long?): LocalCandidateCursor =
            throw AssertionError("Transfer cannot open an external source")
    }
    private fun freshAuthorization(old: TransferTestFixtures.Authorized): TransferTestFixtures.Authorized {
        val authority = TransferSessionAuthority()
        val opening = authority.begin(old.opening.target)
        val capabilities = setOf(TransferCapability.UPLOAD_AUDIO)
        authority.pair(old.peer, TransferRequest.Pair(TransferTestFixtures.id(), opening.target, opening.sessionId,
            checkNotNull(authority.pairingCodeForLocalUi()), capabilities))
        authority.approveForLocalUi(old.peer, capabilities)
        val token = authority.authenticate(old.peer, opening.target, opening.sessionId)
        return TransferTestFixtures.Authorized(authority, old.peer, opening, TransferScope(opening.target, opening.sessionId, token.bearer))
    }
    private fun assertReceipt(expected: TransferReceipt, actual: TransferReceipt) {
        assertTrue(expected.binding.sameOperation(actual.binding))
        assertEquals(expected.result, actual.result)
        assertEquals(expected.resultId, actual.resultId)
        assertEquals(expected.revision, actual.revision)
        assertEquals(expected.actualBytes, actual.actualBytes)
    }
    private suspend fun expect(issue: TransferIssue, action: suspend () -> Any?) {
        try { action()
            throw AssertionError("Expected $issue") }
        catch (error: TransferException) { assertEquals(issue, error.issue) }
    }
}
