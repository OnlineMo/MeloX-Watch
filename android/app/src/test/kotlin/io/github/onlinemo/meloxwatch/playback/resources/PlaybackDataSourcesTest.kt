package io.github.onlinemo.meloxwatch.playback.resources

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackIssue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class PlaybackDataSourcesTest {
    @Before fun initializeControlledFixtures() { OkHttp.initialize(RuntimeEnvironment.getApplication()) }

    @Test fun theMedia3AndPlatformPortsUseTheSameControlledRangeAndCredentialPolicy() = runBlocking(Dispatchers.IO) {
        val context = RuntimeEnvironment.getApplication()
        val resources = RuntimePlaybackResources()
        val resource = onlineResource(resources)
        val ranges = mutableListOf<String>()
        val bytes = "abcdef".toByteArray()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val value = requireNotNull(chain.request().header("Range"))
            ranges.add(value)
            assertNull(chain.request().header("Cookie"))
            assertNull(chain.request().header("Authorization"))
            val parsed = Regex("bytes=([0-9]+)-([0-9]*)").matchEntire(value)!!
            val first = parsed.groupValues[1].toInt()
            val last = parsed.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: bytes.lastIndex
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(206).message("Fixture")
                .header("Content-Range", "bytes $first-$last/${bytes.size}")
                .header("Content-Type", "audio/mpeg").body(bytes.copyOfRange(first, last + 1).toResponseBody()).build()
        }.build()
        val factory = PlaybackDataSources(context, resources, HttpsMediaStreams(context, resources, client))
        try {
            val media3 = factory.createDataSource()
            try {
                assertEquals(2L, media3.open(DataSpec.Builder().setUri(resource.mediaUri).setPosition(1L).setLength(2L).build()))
                val buffer = ByteArray(2)
                assertEquals(2, media3.read(buffer, 0, 2))
                assertEquals("bc", buffer.toString(Charsets.UTF_8))
                assertEquals(resource.mediaUri, media3.uri)
            } finally { media3.close() }
            val failures = mutableListOf<QueuePlaybackIssue>()
            val platform = factory.platform(resource.mediaUri) { failures.add(it) }
            try {
                assertEquals(-1L, platform.getSize())
                assertEquals(1, ranges.size)
                val buffer = ByteArray(2)
                assertEquals(2, platform.readAt(3L, buffer, 0, 2))
                assertEquals("de", buffer.toString(Charsets.UTF_8))
                assertEquals(6L, platform.getSize())
                assertTrue(platform.supportsSeek)
                assertEquals(2, platform.readAt(0L, buffer, 0, 2))
                assertEquals("ab", buffer.toString(Charsets.UTF_8))
                assertTrue(failures.isEmpty())
                assertEquals(listOf("bytes=1-2", "bytes=3-", "bytes=0-"), ranges)
            } finally { platform.close() }
        } finally {
            resource.close()
            factory.close()
        }
    }

    @Test fun aRawRemoteUriCannotBypassTheRuntimeResourceRegistryThroughEitherBackendPort() = runBlocking(Dispatchers.IO) {
        val context = RuntimeEnvironment.getApplication()
        val resources = RuntimePlaybackResources()
        var dispatched = 0
        val client = OkHttpClient.Builder().addInterceptor {
            dispatched++
            throw AssertionError("A raw URL cannot dispatch")
        }.build()
        val factory = PlaybackDataSources(context, resources, HttpsMediaStreams(context, resources, client))
        val raw = Uri.parse("https://fixture.invalid/audio?signature=fixture")
        try {
            val media3 = factory.createDataSource()
            try {
                assertEquals(QueuePlaybackIssue.RESOURCE_INVALID, assertThrows(MediaTransportException::class.java) {
                    media3.open(DataSpec.Builder().setUri(raw).build())
                }.issue)
            } finally { media3.close() }
            val platform = factory.platform(raw) {}
            try {
                assertEquals(QueuePlaybackIssue.RESOURCE_INVALID, assertThrows(MediaTransportException::class.java) {
                    platform.readAt(0L, ByteArray(4), 0, 4)
                }.issue)
                assertEquals(0, dispatched)
            } finally { platform.close() }
        } finally { factory.close() }
    }

    @Test fun bothPortsRejectAShortNonTail206AndTheFailedPlatformPortCannotOpenAContinuation() = runBlocking(Dispatchers.IO) {
        val context = RuntimeEnvironment.getApplication()
        val resources = RuntimePlaybackResources()
        val resource = onlineResource(resources)
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(206).message("Fixture")
                .header("Content-Range", "bytes 0-9/1000").body("0123456789".toResponseBody()).build()
        }.build()
        val factory = PlaybackDataSources(context, resources, HttpsMediaStreams(context, resources, client))
        try {
            val media3 = factory.createDataSource()
            try {
                assertEquals(QueuePlaybackIssue.RESOURCE_INVALID, assertThrows(MediaTransportException::class.java) {
                    media3.open(DataSpec.Builder().setUri(resource.mediaUri).setLength(100L).build())
                }.issue)
            } finally { media3.close() }
            val platform = factory.platform(resource.mediaUri) {}
            try {
                assertEquals(QueuePlaybackIssue.RESOURCE_INVALID, assertThrows(MediaTransportException::class.java) {
                    platform.readAt(0L, ByteArray(10), 0, 10)
                }.issue)
                assertEquals(QueuePlaybackIssue.SOURCE_CHANGED, assertThrows(MediaTransportException::class.java) {
                    platform.readAt(10L, ByteArray(10), 0, 10)
                }.issue)
                assertEquals(2, requests)
            } finally { platform.close() }
        } finally {
            resource.close()
            factory.close()
        }
    }

    @Test fun theActualTailLengthAndSequentialPlatformEofMatchTheCompleteObject() = runBlocking(Dispatchers.IO) {
        val context = RuntimeEnvironment.getApplication()
        val resources = RuntimePlaybackResources()
        val resource = onlineResource(resources)
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(206).message("Fixture")
                .header("Content-Range", "bytes 0-9/10").body("0123456789".toResponseBody()).build()
        }.build()
        val factory = PlaybackDataSources(context, resources, HttpsMediaStreams(context, resources, client))
        try {
            val media3 = factory.createDataSource()
            try {
                assertEquals(10L, media3.open(DataSpec.Builder().setUri(resource.mediaUri).setLength(100L).build()))
                assertEquals(10, media3.read(ByteArray(100), 0, 100))
                assertEquals(-1, media3.read(ByteArray(10), 0, 10))
            } finally { media3.close() }
            val platform = factory.platform(resource.mediaUri) {}
            try {
                assertEquals(6, platform.readAt(0L, ByteArray(6), 0, 6))
                assertEquals(4, platform.readAt(6L, ByteArray(6), 0, 6))
                assertEquals(-1, platform.readAt(10L, ByteArray(6), 0, 6))
                assertEquals(10L, platform.getSize())
                assertEquals(2, requests)
            } finally { platform.close() }
        } finally {
            resource.close()
            factory.close()
        }
    }
}
