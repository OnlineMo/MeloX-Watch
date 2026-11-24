package io.github.onlinemo.meloxwatch.data.transfer

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class TransferUploadStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun actualMultiBlockAudioIsFullyHashedValidatedCopiedAndDurablyReceipted() = runBlocking(Dispatchers.Default) {
        val fixture = TransferTestFixtures.StoreFixture(temporary.newFolder())
        val auth = TransferTestFixtures.authorized()
        val bytes = TransferTestFixtures.wav(frames = 70_000)
        val declaration = TransferTestFixtures.declaration(bytes)
        val binding = TransferTestFixtures.binding(auth, declaration)
        val stage = fixture.store.begin(binding, declaration, auth.permit())
        appendAll(stage, auth, bytes)
        val verified = stage.verify(auth.permit())
        val receipt = fixture.backend.commitUpload(binding, verified, auth.permit())
        stage.discard()
        assertTrue(fixture.owned.listFiles().orEmpty().single().readBytes().contentEquals(bytes))
        assertTrue(receipt.binding.sameOperation(binding) && fixture.backend.receiptExists(binding.operationId))
        assertTrue(fixture.backend.committed(binding, auth.permit())?.binding?.sameOperation(binding) == true)
        assertTrue(fixture.partials.listFiles().orEmpty().isEmpty() && fixture.accounting.reservationCount == 0)
    }

    @Test fun unknownLengthGrowsByActualBytesAndStillNeedsACompleteHash() = runBlocking(Dispatchers.Default) {
        val fixture = TransferTestFixtures.StoreFixture(temporary.newFolder())
        val auth = TransferTestFixtures.authorized()
        val bytes = TransferTestFixtures.wav(frames = 40_000)
        val declaration = TransferTestFixtures.declaration(bytes, declared = null)
        val stage = fixture.store.begin(TransferTestFixtures.binding(auth, declaration), declaration, auth.permit())
        appendAll(stage, auth, bytes)
        assertTrue(stage.actualBytes == bytes.size.toLong())
        val verified = stage.verify(auth.permit())
        assertTrue(verified.actualBytes == bytes.size.toLong() && verified.sha256 == TransferTestFixtures.hash(bytes))
        stage.discard()
        assertTrue(fixture.accounting.reservationCount == 0)
    }

    @Test fun declaredLengthMismatchAndWrongFullDigestCannotCommit() = runBlocking(Dispatchers.Default) {
        val fixture = TransferTestFixtures.StoreFixture(temporary.newFolder())
        val auth = TransferTestFixtures.authorized()
        val bytes = TransferTestFixtures.wav()
        val tooLong = TransferTestFixtures.declaration(bytes, declared = bytes.size.toLong() + 2)
        val stage = fixture.store.begin(TransferTestFixtures.binding(auth, tooLong), tooLong, auth.permit())
        stage.append(auth.permit(), 0L, bytes)
        expect(TransferIssue.INTEGRITY_FAILED) { stage.verify(auth.permit()) }
        stage.discard()
        val wrongHash = TransferUploadDeclaration(TransferUploadKind.AUDIO, "Selected.wav", "audio/wav", bytes.size.toLong(), TransferTestFixtures.hash(TransferTestFixtures.wav(frequency = 220)), 0L)
        val second = fixture.store.begin(TransferTestFixtures.binding(auth, wrongHash), wrongHash, auth.permit())
        second.append(auth.permit(), 0L, bytes)
        expect(TransferIssue.INTEGRITY_FAILED) { second.verify(auth.permit()) }
        second.discard()
        assertTrue(fixture.backend.commitCalls.get() == 0 && fixture.owned.listFiles().orEmpty().isEmpty())
    }

    @Test fun matchingHashAndMimeDoNotMakeAMalformedWavAudio() = runBlocking(Dispatchers.Default) {
        val fixture = TransferTestFixtures.StoreFixture(temporary.newFolder())
        val auth = TransferTestFixtures.authorized()
        val bytes = TransferTestFixtures.wav().also { it[0] = 'X'.code.toByte() }
        val declaration = TransferTestFixtures.declaration(bytes)
        val stage = fixture.store.begin(TransferTestFixtures.binding(auth, declaration), declaration, auth.permit())
        stage.append(auth.permit(), 0L, bytes)
        expect(TransferIssue.NOT_AUDIO) { stage.verify(auth.permit()) }
        stage.discard()
        assertTrue(fixture.backend.commitCalls.get() == 0)
    }

    @Test fun nameIsDisplayOnlyAndCannotEscapeThePrivateDirectory() = runBlocking(Dispatchers.Default) {
        val fixture = TransferTestFixtures.StoreFixture(temporary.newFolder())
        val auth = TransferTestFixtures.authorized()
        val bytes = TransferTestFixtures.wav()
        try { TransferTestFixtures.declaration(bytes, name = "../escape.wav"); fail("Expected a path rejection") }
        catch (_: IllegalArgumentException) { }
        val declaration = TransferTestFixtures.declaration(bytes, name = "同名音乐.wav")
        val stage = fixture.store.begin(TransferTestFixtures.binding(auth, declaration), declaration, auth.permit())
        assertTrue(stage.file.name.endsWith(".partial") && stage.file.name != declaration.displayName)
        assertTrue(checkNotNull(stage.file.parentFile).canonicalFile == fixture.partials.canonicalFile)
        stage.discard()
        assertTrue(fixture.owned.listFiles().orEmpty().isEmpty())
    }

    @Test fun actualOwnedAudioAndAnotherImportReservationParticipateInTheSameQuota() = runBlocking(Dispatchers.Default) {
        val fixture = TransferTestFixtures.StoreFixture(temporary.newFolder())
        val bytes = TransferTestFixtures.wav()
        val original = File(fixture.owned, "original.wav").also { it.writeBytes(bytes) }
        fixture.accounting.externalAudioReservation = TransferLimits.AUDIO_BYTES - bytes.size - 1L
        val auth = TransferTestFixtures.authorized()
        val declaration = TransferTestFixtures.declaration(bytes)
        expect(TransferIssue.QUOTA_EXCEEDED) { fixture.store.begin(TransferTestFixtures.binding(auth, declaration), declaration, auth.permit()) }
        assertTrue(original.readBytes().contentEquals(bytes) && fixture.accounting.reservationCount == 0)
    }

    @Test fun unknownLengthChecksActualFreeSpaceBeforeEveryBlock() = runBlocking(Dispatchers.Default) {
        val available = AtomicLong(TransferLimits.FREE_RESERVE_BYTES + 1_000_000L)
        val fixture = TransferTestFixtures.StoreFixture(temporary.newFolder(), space = TransferDiskSpace { available.get() })
        val auth = TransferTestFixtures.authorized()
        val bytes = TransferTestFixtures.wav()
        val declaration = TransferTestFixtures.declaration(bytes, declared = null)
        val stage = fixture.store.begin(TransferTestFixtures.binding(auth, declaration), declaration, auth.permit())
        stage.append(auth.permit(), 0L, bytes.copyOfRange(0, 1024))
        available.set(TransferLimits.FREE_RESERVE_BYTES)
        expect(TransferIssue.STORAGE_FULL) { stage.append(auth.permit(), 1024L, bytes.copyOfRange(1024, bytes.size)) }
        assertTrue(stage.actualBytes == 1024L && stage.file.length() == 1024L)
        stage.discard()
        assertTrue(fixture.accounting.reservationCount == 0)
    }

    @Test fun cancellationRetainsTheSlotUntilThePhysicalValidatorReturnsAndTheFileIsRemoved() = runBlocking(Dispatchers.Default) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val validator = TransferContentValidator { file, declaration, signal ->
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            PcmWavTransferValidator.validate(file, declaration, signal)
        }
        val fixture = TransferTestFixtures.StoreFixture(temporary.newFolder(), validator)
        val originalBytes = TransferTestFixtures.wav(frequency = 220)
        val original = File(fixture.owned, "original.wav").also { it.writeBytes(originalBytes) }
        val auth = TransferTestFixtures.authorized()
        val bytes = TransferTestFixtures.wav()
        val declaration = TransferTestFixtures.declaration(bytes)
        val stage = fixture.store.begin(TransferTestFixtures.binding(auth, declaration), declaration, auth.permit())
        stage.append(auth.permit(), 0L, bytes)
        val verification = async { stage.verify(auth.permit()) }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            verification.cancel()
            val retirement = async { stage.discard() }
            delay(100L)
            assertFalse(retirement.isCompleted)
            assertTrue(stage.file.isFile)
            expect(TransferIssue.BUSY) { fixture.store.begin(TransferTestFixtures.binding(auth, declaration), declaration, auth.permit()) }
            release.countDown()
            verification.join()
            withTimeout(5000L) { retirement.await(); fixture.store.awaitIdle() }
            assertTrue(original.readBytes().contentEquals(originalBytes) && fixture.partials.listFiles().orEmpty().isEmpty())
            assertTrue(fixture.accounting.reservationCount == 0)
        } finally { release.countDown(); verification.cancel(); stage.discard(); fixture.store.retireAbandoned() }
    }

    @Test fun recoveryIsBoundedAndRefusesToStartBesideAnAbandonedPartial() = runBlocking(Dispatchers.Default) {
        val fixture = TransferTestFixtures.StoreFixture(temporary.newFolder())
        fixture.partials.mkdirs()
        repeat(3) { File(fixture.partials, TransferTestFixtures.id() + ".partial").writeBytes(byteArrayOf(1)) }
        assertTrue(fixture.store.recoverAbandoned(2) == 2)
        val auth = TransferTestFixtures.authorized()
        val declaration = TransferTestFixtures.declaration(TransferTestFixtures.wav())
        expect(TransferIssue.RECOVERY_REQUIRED) { fixture.store.begin(TransferTestFixtures.binding(auth, declaration), declaration, auth.permit()) }
        assertTrue(fixture.partials.listFiles().orEmpty().size == 1)
        assertTrue(fixture.store.recoverAbandoned(2) == 1)
        val stage = fixture.store.begin(TransferTestFixtures.binding(auth, declaration), declaration, auth.permit())
        stage.discard()
        assertTrue(fixture.partials.listFiles().orEmpty().isEmpty())
    }

    @Test fun aSymlinkedTransferRootCannotTouchAnExternalOriginal() = runBlocking(Dispatchers.Default) {
        val root = temporary.newFolder()
        val fixture = TransferTestFixtures.StoreFixture(root)
        val external = File(root, "outside").also { it.mkdirs() }
        val bytes = TransferTestFixtures.wav()
        val original = File(external, "original.wav").also { it.writeBytes(bytes) }
        Files.createSymbolicLink(fixture.partials.toPath(), external.toPath())
        val auth = TransferTestFixtures.authorized()
        val declaration = TransferTestFixtures.declaration(bytes)
        expect(TransferIssue.RECOVERY_REQUIRED) { fixture.store.begin(TransferTestFixtures.binding(auth, declaration), declaration, auth.permit()) }
        assertTrue(original.readBytes().contentEquals(bytes) && external.listFiles().orEmpty().size == 1)
    }

    @Test fun renewedBearerContinuesTheSamePhysicalUploadAndRevocationStopsFurtherWrites() = runBlocking(Dispatchers.Default) {
        val clock = TransferTestFixtures.Clock()
        val fixture = TransferTestFixtures.StoreFixture(temporary.newFolder(), clock = clock)
        val auth = TransferTestFixtures.authorized(clock = clock)
        val bytes = TransferTestFixtures.wav(frames = 40_000)
        val declaration = TransferTestFixtures.declaration(bytes)
        val stage = fixture.store.begin(TransferTestFixtures.binding(auth, declaration), declaration, auth.permit())
        try {
            val firstBlock = bytes.copyOfRange(0, TransferLimits.CHUNK_BYTES)
            stage.append(auth.permit(), 0L, firstBlock)
            clock.now = TransferLimits.TOKEN_MS
            expect(TransferIssue.EXPIRED) { stage.append(auth.permit(), firstBlock.size.toLong(), bytes.copyOfRange(firstBlock.size, bytes.size)) }
            val renewal = auth.authority.authenticate(auth.peer, auth.opening.target, auth.opening.sessionId)
            val renewedScope = TransferScope(auth.opening.target, auth.opening.sessionId, renewal.bearer)
            val permit = auth.authority.authorize(auth.peer, renewedScope, TransferCapability.UPLOAD_AUDIO)
            stage.append(permit, firstBlock.size.toLong(), bytes.copyOfRange(firstBlock.size, bytes.size))
            assertTrue(stage.file.readBytes().contentEquals(bytes))
            stage.verify(permit)
            auth.authority.revokeForLocalUi(auth.peer)
            expect(TransferIssue.NOT_APPROVED) { stage.append(permit, bytes.size.toLong(), byteArrayOf(1)) }
            assertTrue(stage.file.readBytes().contentEquals(bytes))
        } finally { stage.discard() }
        assertTrue(fixture.partials.listFiles().orEmpty().isEmpty() && fixture.accounting.reservationCount == 0)
    }

    @Test fun receivingUpdatesAreSampledButRealPhaseAndReceiptChangesAreImmediate() = runBlocking(Dispatchers.Default) {
        val clock = TransferTestFixtures.Clock()
        val fixture = TransferTestFixtures.StoreFixture(temporary.newFolder(), clock = clock)
        val auth = TransferTestFixtures.authorized(clock = clock)
        val bytes = TransferTestFixtures.wav()
        val declaration = TransferTestFixtures.declaration(bytes)
        val binding = TransferTestFixtures.binding(auth, declaration)
        assertTrue(fixture.store.progress.value == null)
        val stage = fixture.store.begin(binding, declaration, auth.permit())
        try {
            assertTrue(fixture.store.progress.value?.phase == TransferUploadPhase.RECEIVING && fixture.store.progress.value?.receivedBytes == 0L)
            stage.append(auth.permit(), 0L, bytes.copyOfRange(0, 512))
            assertTrue(stage.file.length() == 512L && fixture.store.progress.value?.receivedBytes == 0L)
            clock.now = 999L
            stage.append(auth.permit(), 512L, bytes.copyOfRange(512, 1024))
            assertTrue(stage.file.length() == 1024L && fixture.store.progress.value?.receivedBytes == 0L)
            clock.now = 1000L
            stage.append(auth.permit(), 1024L, bytes.copyOfRange(1024, bytes.size))
            assertTrue(fixture.store.progress.value?.receivedBytes == bytes.size.toLong())
            val verified = stage.verify(auth.permit())
            assertTrue(fixture.store.progress.value?.phase == TransferUploadPhase.VERIFYING)
            stage.markCommitting()
            assertTrue(fixture.store.progress.value?.phase == TransferUploadPhase.COMMITTING)
            val receipt = fixture.backend.commitUpload(binding, verified, auth.permit())
            assertTrue(fixture.backend.receiptExists(binding.operationId) && stage.file.exists())
            assertTrue(fixture.store.progress.value?.receipt === receipt && fixture.store.progress.value?.phase == TransferUploadPhase.COMMITTING)
            stage.discard()
            assertTrue(fixture.store.progress.value?.phase == TransferUploadPhase.COMPLETED && fixture.store.progress.value?.receivedBytes == bytes.size.toLong())
            assertTrue(fixture.store.progress.value?.toString() == "<local-upload-status>")
        } finally { stage.discard() }
    }

    private suspend fun appendAll(stage: TransferUploadStage, auth: TransferTestFixtures.Authorized, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + TransferLimits.CHUNK_BYTES, bytes.size)
            stage.append(auth.permit(), offset.toLong(), bytes.copyOfRange(offset, end))
            offset = end
        }
    }

    @Test fun concurrentDiscardJoinsOnePhysicalRetirementAndCannotReinstallAnOldStage() = runBlocking(Dispatchers.Default) {
        val fixture = TransferTestFixtures.StoreFixture(temporary.newFolder())
        val entered = CompletableDeferred<Unit>()
        val allowed = CompletableDeferred<Unit>()
        val accounting = object : TransferQuotaAccounting by fixture.accounting {
            override suspend fun release(ticket: String) {
                entered.complete(Unit)
                allowed.await()
                fixture.accounting.release(ticket)
            }
        }
        val store = TransferUploadStore(fixture.partials, fixture.providerIo, accounting, PcmWavTransferValidator,
            TransferDiskSpace { TransferLimits.FREE_RESERVE_BYTES + TransferLimits.AUDIO_BYTES })
        val auth = TransferTestFixtures.authorized()
        val declaration = TransferTestFixtures.declaration(TransferTestFixtures.wav())
        val stage = store.begin(TransferTestFixtures.binding(auth, declaration), declaration, auth.permit())
        val first = async { stage.discard() }
        try {
            withTimeout(5000L) { entered.await() }
            val second = async { stage.discard() }
            delay(100L)
            assertFalse(second.isCompleted)
            allowed.complete(Unit)
            withTimeout(5000L) { first.await(); second.await(); store.awaitIdle() }
            assertTrue(store.progress.value?.phase == TransferUploadPhase.CANCELLED)
            val nextBinding = TransferTestFixtures.binding(auth, declaration)
            val next = store.begin(nextBinding, declaration, auth.permit())
            try {
                stage.discard()
                assertTrue(store.progress.value?.binding?.sameOperation(nextBinding) == true)
                assertTrue(store.progress.value?.phase == TransferUploadPhase.RECEIVING)
            } finally { next.discard() }
        } finally { allowed.complete(Unit); first.join(); stage.discard() }
    }
    private suspend fun expect(issue: TransferIssue, action: suspend () -> Unit) {
        try { action(); fail("Expected a transfer rejection") }
        catch (error: TransferException) { assertTrue(error.issue == issue) }
    }
}
