package io.github.onlinemo.meloxwatch.data.codec

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackIssue
import io.github.onlinemo.meloxwatch.playback.resources.MediaTransportException
import io.github.onlinemo.meloxwatch.playback.resources.PlaybackDataSources
import io.github.onlinemo.meloxwatch.playback.resources.RuntimePlaybackResources
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30], manifest = Config.NONE)
class RoutedCodecRetirementTest {
    @Test fun routedCloseFailureRetainsTheExactRawOwnerAndRetryConfirmsRetirementBeforeNewAcquire() {
        val uri = Uri.parse("content://retirement-fixture/item")
        val bytes = "ordinary raw fixture".toByteArray()
        var opened = 0
        var rejectClose = false
        val attempts = ArrayList<Int>()
        val raw = DataSource.Factory {
            val id = ++opened
            object : DataSource {
                private var position = 0
                override fun addTransferListener(transferListener: TransferListener) = Unit
                override fun open(dataSpec: DataSpec): Long {
                    position = dataSpec.position.toInt()
                    return bytes.size - dataSpec.position
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (position == bytes.size) return -1
                    val count = minOf(length, bytes.size - position)
                    bytes.copyInto(buffer, offset, position, position + count)
                    position += count
                    return count
                }
                override fun getUri(): Uri = uri
                override fun close() {
                    attempts += id
                    if (rejectClose) throw IOException("Fixture provider close failure")
                }
            }
        }
        val owners = PlaybackDataSources(
            RuntimeEnvironment.getApplication(),
            RuntimePlaybackResources(),
            controlledLocalRaw = raw,
        )
        val route = owners.createDataSource()
        val spec = DataSpec.Builder().setUri(uri).build()
        route.open(spec)
        val currentOwner = opened
        rejectClose = true
        try {
            route.close()
            fail("The failed physical close must remain retryable")
        } catch (_: IOException) {
        }
        assertEquals(currentOwner, attempts.last())
        val later = owners.createDataSource()
        try {
            later.open(spec)
            fail("Failed retirement blocks new raw opens")
        } catch (error: MediaTransportException) {
            assertEquals(QueuePlaybackIssue.SOURCE_CHANGED, error.issue)
        }
        assertEquals(currentOwner, opened)
        rejectClose = false
        route.close()
        assertEquals(listOf(currentOwner, currentOwner), attempts.takeLast(2))
        assertEquals(bytes.size.toLong(), later.open(spec))
        later.close()
        owners.close()
    }
}
