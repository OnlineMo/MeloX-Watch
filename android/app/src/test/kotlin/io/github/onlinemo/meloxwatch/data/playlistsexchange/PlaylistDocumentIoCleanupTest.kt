package io.github.onlinemo.meloxwatch.data.playlistsexchange

import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PlaylistDocumentIoCleanupTest : RoomPlaylistExchangeFixture() {
    @Test fun cleanupRecognizesOnlyItsOwnPartialPrefixOnTheSameDescriptorIdentity() = runBlocking {
        val files = PlaylistDocumentIo(context, providerIo)
        val source = files.newPrivateFile("output")
        val bytes = ByteArray(96 * 1024) { (it % 251).toByte() }
        source.writeBytes(bytes)
        val proof = PlaylistFileProof(bytes.size.toLong(), exchangeTestSha256(bytes))
        val owned = PlaylistOwnedOutput()
        files.write(documentUri, source, proof, owned)
        // A controlled partial-file fixture verifies cleanup, independently of the writer's success path.
        RandomAccessFile(documentFile, "rw").use { it.setLength(33_333L) }
        assertEquals(PlaylistOutputCleanup.CLEARED, files.clearUncommitted(documentUri, source, owned))
        assertEquals(0L, documentFile.length())
        assertTrue(source.readBytes().contentEquals(bytes))
        assertEquals(0, provider.deleteCount.get())
        files.deletePrivate(source)
        assertProviderClosed()
        assertNoPrivateStages()
    }

    @Test fun aForeignInodeWithTheSamePrefixIsProtectedEvenWhenAllComparedBytesMatch() = runBlocking {
        val files = PlaylistDocumentIo(context, providerIo)
        val source = files.newPrivateFile("output")
        val bytes = ByteArray(4_096) { (it % 127).toByte() }
        source.writeBytes(bytes)
        val owned = PlaylistOwnedOutput()
        files.write(documentUri, source, PlaylistFileProof(bytes.size.toLong(), exchangeTestSha256(bytes)), owned)
        val foreign = temporaryFolder.newFile("identical-foreign-prefix.txt").apply { writeBytes(bytes.take(100).toByteArray()) }
        val foreignBytes = foreign.readBytes()
        provider.document = foreign
        assertEquals(PlaylistOutputCleanup.NOT_OWNED, files.clearUncommitted(documentUri, source, owned))
        assertTrue(foreign.readBytes().contentEquals(foreignBytes))
        assertTrue(documentFile.readBytes().contentEquals(bytes))
        assertEquals(0, provider.deleteCount.get())
        files.deletePrivate(source)
        assertProviderClosed()
        assertNoPrivateStages()
    }

    @Test fun corruptPrivateSerializationCannotAuthorizeDestructiveCleanupAndAValidProofCanBeRetried() = runBlocking {
        val files = PlaylistDocumentIo(context, providerIo)
        val source = files.newPrivateFile("output")
        val bytes = ByteArray(4_096) { (it % 127).toByte() }
        source.writeBytes(bytes)
        val owned = PlaylistOwnedOutput()
        files.write(documentUri, source, PlaylistFileProof(bytes.size.toLong(), exchangeTestSha256(bytes)), owned)
        source.writeBytes(bytes.copyOf().also { it[0] = (it[0] + 1).toByte() })
        assertEquals(PlaylistOutputCleanup.UNAVAILABLE, files.clearUncommitted(documentUri, source, owned))
        assertTrue(documentFile.readBytes().contentEquals(bytes))
        source.writeBytes(bytes)
        assertEquals(PlaylistOutputCleanup.CLEARED, files.clearUncommitted(documentUri, source, owned))
        assertEquals(0L, documentFile.length())
        files.deletePrivate(source)
        assertProviderClosed()
        assertNoPrivateStages()
    }
}
