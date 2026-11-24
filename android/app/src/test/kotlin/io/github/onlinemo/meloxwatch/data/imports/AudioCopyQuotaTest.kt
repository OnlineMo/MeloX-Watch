package io.github.onlinemo.meloxwatch.data.imports

import android.content.Context
import android.content.ContextWrapper
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.transfer.TransferException
import io.github.onlinemo.meloxwatch.data.transfer.TransferIssue
import io.github.onlinemo.meloxwatch.data.transfer.TransferUploadKind
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class AudioCopyQuotaTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var io: LocalProviderIo
    private lateinit var quota: AudioCopyQuota
    private var available = ImportLimits.FREE_RESERVE_BYTES + 2 * ImportLimits.QUOTA_BYTES

    @Before fun setup() {
        val root = temporary.newFolder()
        context = object : ContextWrapper(RuntimeEnvironment.getApplication() as Context) {
            override fun getNoBackupFilesDir(): File = root
        }
        io = LocalProviderIo()
        quota = AudioCopyQuota(context, io) { available }
    }

    @Test fun knownSafAndTransferReservationsShareTheOneGiBTotal() = runBlocking {
        val normal = id()
        val transfer = id()
        quota.reserveImport(normal, 600L * 1024 * 1024)
        expectTransfer(TransferIssue.QUOTA_EXCEEDED) { quota.reserve(transfer, TransferUploadKind.AUDIO, 500L * 1024 * 1024) }
        quota.releaseImport(normal)
        val usage = quota.reserve(transfer, TransferUploadKind.AUDIO, 500L * 1024 * 1024)
        assertEquals(0L, usage.ownedAudioBytes)
        assertEquals(0L, usage.otherReservedAudioBytes)
        quota.release(transfer)
    }

    @Test fun alreadyWrittenActiveFileIsNotChargedAgainBesideItsFullReservation() = runBlocking {
        val normal = id()
        val transfer = id()
        quota.reserveImport(normal, 4096L)
        io.read {
            OwnedAudioProvider.stagingFile(context, normal).writeBytes(ByteArray(3072))
            quota.recordImportBytesOnPhysicalWorker(normal, 3072L)
        }
        val usage = quota.reserve(transfer, TransferUploadKind.AUDIO, ImportLimits.QUOTA_BYTES - 4096L)
        assertEquals(0L, usage.ownedAudioBytes)
        assertEquals(4096L, usage.otherReservedAudioBytes)
        io.read { Files.delete(OwnedAudioProvider.stagingFile(context, normal).toPath()) }
        quota.releaseImport(normal)
        quota.release(transfer)
    }

    @Test fun realFreeSpaceMustCoverOtherLeasesUnwrittenBytesAndThe512MiBReserve() = runBlocking {
        available = ImportLimits.FREE_RESERVE_BYTES + 400L * 1024 * 1024
        val normal = id()
        quota.reserveImport(normal, 300L * 1024 * 1024)
        expectTransfer(TransferIssue.STORAGE_FULL) { quota.reserve(id(), TransferUploadKind.AUDIO, 200L * 1024 * 1024) }
        quota.releaseImport(normal)
    }

    @Test fun failedPhysicalCleanupKeepsTheReservationUntilThatExactFileIsRemoved() = runBlocking {
        val normal = id()
        quota.reserveImport(normal, 4096L)
        io.read { OwnedAudioProvider.stagingFile(context, normal).writeBytes(ByteArray(4096)); quota.recordImportBytesOnPhysicalWorker(normal, 4096L) }
        try { quota.releaseImport(normal); throw AssertionError("Expected retained cleanup") }
        catch (error: ImportException) { assertEquals(ImportIssue.RECOVERY_REQUIRED, error.issue) }
        expectTransfer(TransferIssue.QUOTA_EXCEEDED) { quota.reserve(id(), TransferUploadKind.AUDIO, ImportLimits.QUOTA_BYTES) }
        io.read { Files.delete(OwnedAudioProvider.stagingFile(context, normal).toPath()) }
        quota.releaseImport(normal)
        val next = id()
        quota.reserve(next, TransferUploadKind.AUDIO, ImportLimits.QUOTA_BYTES)
        quota.release(next)
    }

    @Test fun sameVolumeAdoptionAndInstallationRemainOneReservationThenBecomeActualOwnedBytes() = runBlocking {
        val ticket = id()
        val operation = id()
        val bytes = ByteArray(4096) { (it % 251).toByte() }
        quota.reserve(ticket, TransferUploadKind.AUDIO, bytes.size.toLong())
        io.read {
            quota.transferDirectory.mkdirs()
            val source = File(quota.transferDirectory.canonicalFile, "$ticket.partial").also { it.writeBytes(bytes) }
            quota.recordPhysicalBytes(ticket, bytes.size.toLong())
            quota.adoptTransferOnPhysicalWorker(ticket, operation, source, bytes.size.toLong())
            val staging = OwnedAudioProvider.stagingFile(context, operation)
            Files.move(source.toPath(), staging.toPath(), StandardCopyOption.ATOMIC_MOVE)
            Files.move(staging.toPath(), OwnedAudioProvider.finalFile(context, operation).toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
        quota.release(ticket)
        val next = id()
        val usage = quota.reserve(next, TransferUploadKind.AUDIO, 1L)
        assertEquals(bytes.size.toLong(), usage.ownedAudioBytes)
        assertEquals(0L, usage.otherReservedAudioBytes)
        assertTrue(OwnedAudioProvider.finalFile(context, operation).readBytes().contentEquals(bytes))
        quota.release(next)
    }

    private fun id(): String = UUID.randomUUID().toString()
    private suspend fun expectTransfer(issue: TransferIssue, action: suspend () -> Any?) {
        try { action(); throw AssertionError("Expected $issue") }
        catch (error: TransferException) { assertEquals(issue, error.issue) }
    }
}
