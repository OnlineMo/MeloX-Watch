package io.github.onlinemo.meloxwatch.data.transfer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
class TransferRequestProcessorTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun excessControlTrafficStopsBeforeBackendAccessAndResetsOnlyAtTheTimeBoundary() = runBlocking(Dispatchers.Default) {
        val clock = TransferTestFixtures.Clock()
        val fixture = TransferTestFixtures.StoreFixture(temporary.newFolder(), clock = clock)
        val auth = TransferTestFixtures.authorized(clock = clock)
        val processor = TransferRequestProcessor(auth.peer, auth.authority, fixture.store, fixture.backend, clock)
        fun request() = TransferRequest.Authenticate(TransferTestFixtures.id(), auth.opening.target, auth.opening.sessionId)
        repeat(TransferLimits.CONTROLS_PER_SECOND) { processor.process(request()) }
        expect(TransferIssue.RATE_LIMITED) { processor.process(request()) }
        clock.now = 999L
        expect(TransferIssue.RATE_LIMITED) { processor.process(request()) }
        clock.now = 1000L
        assertTrue(processor.process(request()).response is TransferResponse.Authenticated)
        assertTrue(fixture.backend.lookupCalls.get() == 0 && fixture.backend.readCalls.get() == 0 && fixture.partials.listFiles().orEmpty().isEmpty())
        processor.close()
    }

    @Test fun excessChunkTrafficCannotReachStorageEvenWithAnApprovedAudioPermission() = runBlocking(Dispatchers.Default) {
        val clock = TransferTestFixtures.Clock()
        val fixture = TransferTestFixtures.StoreFixture(temporary.newFolder(), clock = clock)
        val auth = TransferTestFixtures.authorized(clock = clock)
        val processor = TransferRequestProcessor(auth.peer, auth.authority, fixture.store, fixture.backend, clock)
        fun request() = TransferRequest.UploadChunk(TransferTestFixtures.id(), auth.scope, TransferTestFixtures.id(), 0L, byteArrayOf(1))
        repeat(TransferLimits.CHUNKS_PER_SECOND) { expect(TransferIssue.NOT_FOUND) { processor.process(request()) } }
        expect(TransferIssue.RATE_LIMITED) { processor.process(request()) }
        clock.now = 1000L
        expect(TransferIssue.NOT_FOUND) { processor.process(request()) }
        assertTrue(fixture.accounting.reservationCount == 0 && fixture.backend.lookupCalls.get() == 0 && fixture.partials.listFiles().orEmpty().isEmpty())
        processor.close()
    }

    private suspend fun expect(issue: TransferIssue, action: suspend () -> Unit) {
        try { action()
            fail("Expected a transfer rejection") }
        catch (error: TransferException) { assertTrue(error.issue == issue) }
    }
}
