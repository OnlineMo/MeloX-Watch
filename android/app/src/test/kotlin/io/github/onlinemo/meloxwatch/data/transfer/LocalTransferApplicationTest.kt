package io.github.onlinemo.meloxwatch.data.transfer

import android.os.Looper
import io.github.onlinemo.meloxwatch.MeloXApplication
import io.github.onlinemo.meloxwatch.data.imports.ImportPhase
import io.github.onlinemo.meloxwatch.data.imports.OwnedAudioProvider
import io.github.onlinemo.meloxwatch.data.imports.TransferImportSelection
import io.github.onlinemo.meloxwatch.data.library.LocalAudioMetadata
import io.github.onlinemo.meloxwatch.data.library.RoomImportLedger
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

/** 实际 Application prepare 和同一 Room/配额/后端；只替换本测试的 TLS 身份。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], application = MeloXApplication::class, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LocalTransferApplicationTest {
    private val application: MeloXApplication get() = RuntimeEnvironment.getApplication() as MeloXApplication
    private var receiver: LocalTransferReceiver? = null

    @Before fun awaitActualStorageStartup() = runBlocking {
        withTimeout(5000L) { awaitWithMainPump { application.requireBackupStorageReady() } }
    }

    @After fun close() {
        runBlocking {
            try {
                withTimeout(5000L) {
                    awaitWithMainPump {
                        receiver?.retryClose()?.await()
                        application.backups.closeForOwner()
                        application.fileOperations.closeForOwner()
                    }
                }
            } finally { application.library.database.close() }
        }
    }

    @Test(timeout = 30_000L)
    fun actualApplicationPreparationAllowsFreshAuthenticationToRecoverOnlyTheOriginalInstalledOperation() = runBlocking {
        val watch = TransferTestFixtures.identity()
        val phone = TransferTestFixtures.identity()
        val owner = receiver(watch)
        val first = withTimeout(5000L) { awaitWithMainPump { owner.open(loopback()).await() } }
        val bytes = TransferTestFixtures.wav()
        val declaration = TransferTestFixtures.declaration(bytes)
        val operationId = TransferTestFixtures.id()
        val selection = withContext(Dispatchers.IO) {
            TransferTestFixtures.client(phone, first).use { client ->
                TransferTestFixtures.pair(client, phone, owner.server)
                TransferImportSelection(TransferProtocolCodec.operationBinding(phone.peerFingerprint,
                    TransferRequest.BeginUpload(operationId, client.scope(), declaration)), declaration)
            }
        }
        val ledger = RoomImportLedger(application.library, OwnedAudioProvider.authority(application))
        ledger.beginTransferred(selection)
        ledger.verifyTransferred(selection, bytes.size.toLong(), declaration.sha256.forLocalStorage(),
            LocalAudioMetadata(title = "Retained audio", mimeType = "audio/wav", durationMs = 100L))
        withContext(Dispatchers.IO) {
            val file = OwnedAudioProvider.finalFile(application, operationId)
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
            file.writeBytes(bytes)
        }
        ledger.installedTransferred(selection)
        ledger.unfinishedTransferred(selection, ImportPhase.INSTALLED, TransferIssue.RECOVERY_REQUIRED)
        withTimeout(5000L) { awaitWithMainPump { owner.close().await() } }
        assertEquals(0L, application.library.overview().totalTracks)

        // Production prepare validates and retains these bytes before starting this new authenticated session.
        val fresh = withTimeout(5000L) { awaitWithMainPump { owner.open(loopback()).await() } }
        assertNotEquals(first.opening.sessionId, fresh.opening.sessionId)
        assertEquals(0L, application.library.overview().totalTracks)
        val result = withContext(Dispatchers.IO) {
            TransferTestFixtures.client(phone, fresh).use { client ->
                TransferTestFixtures.pair(client, phone, owner.server)
                val completed = client.request(TransferRequest.BeginUpload(operationId, client.scope(), declaration))
                    as TransferResponse.Completed
                assertTrue(completed.replay)
                assertTrue(selection.binding.sameOperation(completed.receipt.binding))
                val duplicate = client.request(TransferRequest.BeginUpload(operationId, client.scope(), declaration))
                    as TransferResponse.Completed
                assertEquals(completed.receipt.resultId, duplicate.receipt.resultId)
                assertEquals(completed.receipt.revision, duplicate.receipt.revision)
                completed
            }
        }
        assertEquals(ImportPhase.COMMITTED, ledger.recordTransferred(operationId)!!.phase)
        assertEquals(1L, application.library.overview().totalTracks)
        assertEquals(bytes.size.toLong(), result.receipt.actualBytes)
        withContext(Dispatchers.IO) {
            assertArrayEquals(bytes, OwnedAudioProvider.finalFile(application, operationId).readBytes())
            assertFalse(OwnedAudioProvider.stagingFile(application, operationId).exists())
        }
        withTimeout(5000L) { awaitWithMainPump { owner.close().await() } }
        assertEquals(TransferServerPhase.OFF, owner.server.phase.value)
    }

    @Test(timeout = 30_000L)
    fun damagedInstalledBytesStillRejectActualApplicationPreparationWithoutReplacingOrPublishingThem() = runBlocking {
        rejectRetainedInput(damaged = true, missing = false, unexpectedPhase = false)
    }

    @Test(timeout = 30_000L)
    fun missingInstalledBytesStillRejectActualApplicationPreparationWithoutPublishingAnIndexEntry() = runBlocking {
        rejectRetainedInput(damaged = false, missing = true, unexpectedPhase = false)
    }

    @Test(timeout = 30_000L)
    fun unexpectedInstalledObjectStillRejectsActualApplicationPreparationAndRetainsItsOriginalBytes() = runBlocking {
        rejectRetainedInput(damaged = false, missing = false, unexpectedPhase = true)
    }

    private suspend fun rejectRetainedInput(damaged: Boolean, missing: Boolean, unexpectedPhase: Boolean) {
        application.library.initialize()
        val ledger = RoomImportLedger(application.library, OwnedAudioProvider.authority(application))
        val bytes = TransferTestFixtures.wav()
        val declaration = TransferTestFixtures.declaration(bytes)
        val binding = TransferTestFixtures.binding(TransferTestFixtures.authorized(), declaration)
        val selection = TransferImportSelection(binding, declaration)
        ledger.beginTransferred(selection)
        if (!unexpectedPhase) {
            ledger.verifyTransferred(selection, bytes.size.toLong(), declaration.sha256.forLocalStorage(),
                LocalAudioMetadata(title = "Retained audio", mimeType = "audio/wav", durationMs = 100L))
            ledger.installedTransferred(selection)
        }
        val retained = if (damaged) bytes.copyOf().also { it[50] = (it[50].toInt() xor 1).toByte() } else bytes
        withContext(Dispatchers.IO) {
            val file = OwnedAudioProvider.finalFile(application, binding.operationId)
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
            if (!missing) file.writeBytes(retained)
        }
        val owner = receiver(TransferTestFixtures.identity())
        try {
            withTimeout(5000L) { awaitWithMainPump { owner.open(loopback()).await() } }
            throw AssertionError("Expected recovery rejection")
        } catch (error: TransferException) { assertEquals(TransferIssue.RECOVERY_REQUIRED, error.issue) }
        withContext(NonCancellable) { withTimeout(5000L) { awaitWithMainPump { owner.close().await() } } }
        assertEquals(TransferServerPhase.OFF, owner.server.phase.value)
        assertFalse(owner.server.sessionState.value.active)
        assertEquals(0L, application.library.overview().totalTracks)
        assertEquals(if (unexpectedPhase) ImportPhase.COPYING else ImportPhase.INSTALLED,
            ledger.recordTransferred(binding.operationId)!!.phase)
        withContext(Dispatchers.IO) {
            val file = OwnedAudioProvider.finalFile(application, binding.operationId)
            if (missing) assertFalse(file.exists()) else assertArrayEquals(retained, file.readBytes())
        }
    }

    private fun receiver(identity: TransferTlsIdentity): LocalTransferReceiver = LocalTransferReceiver(
        application.transferReceiver.server, application.localProviderIo,
        prepare = application::prepareTransferReception,
        retireUploads = application::retireTransferUploads,
        identity = { identity },
    ).also { receiver = it }

    /** 等待真实 IO/资源票据时仍处理服务主线程回执；既有五秒预算围住整个等待。 */
    private suspend fun <T> awaitWithMainPump(action: suspend () -> T): T = coroutineScope {
        val pending = async(Dispatchers.IO) { runCatching { action() } }
        while (!pending.isCompleted) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(1L, TimeUnit.MILLISECONDS)
            delay(1L)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        pending.await().getOrThrow()
    }

    private fun loopback(): LocalTransferAddress = LocalTransferAddress(InetAddress.getLoopbackAddress())
}
