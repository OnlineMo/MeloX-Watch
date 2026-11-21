package io.github.onlinemo.meloxwatch.data.lyrics.online

import io.github.onlinemo.meloxwatch.data.auth.ControlledSessionStore
import io.github.onlinemo.meloxwatch.data.auth.SessionRepository
import io.github.onlinemo.meloxwatch.data.auth.fixtureSession
import io.github.onlinemo.meloxwatch.data.meting.ControlledMetingStore
import io.github.onlinemo.meloxwatch.data.meting.MetingRuntimeOwner
import io.github.onlinemo.meloxwatch.data.meting.metingConfiguration
import io.github.onlinemo.meloxwatch.data.netease.NeteaseEapi
import io.github.onlinemo.meloxwatch.data.netease.NeteaseLyricsApi
import io.github.onlinemo.meloxwatch.data.network.BoundedHttpGetClient
import io.github.onlinemo.meloxwatch.data.network.HttpExchange
import io.github.onlinemo.meloxwatch.data.network.HttpRequest
import io.github.onlinemo.meloxwatch.data.network.HttpResponse
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.TrackKey
import io.github.onlinemo.meloxwatch.playback.resources.MediaHttpTarget
import io.github.onlinemo.meloxwatch.playback.resources.PlaybackAssociatedResources
import io.github.onlinemo.meloxwatch.playback.resources.PlaybackResolutionBudget
import io.github.onlinemo.meloxwatch.playback.resources.PlaybackResourceStatus
import io.github.onlinemo.meloxwatch.playback.resources.ResourceCompleteness
import io.github.onlinemo.meloxwatch.playback.resources.ResourceMatch
import io.github.onlinemo.meloxwatch.playback.resources.RuntimePlaybackResource
import io.github.onlinemo.meloxwatch.playback.resources.RuntimePlaybackResources
import io.github.onlinemo.meloxwatch.playback.resources.StreamingQuality
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.robolectric.RuntimeEnvironment

/** Uses real session, EAPI, resource and disk owners with synthetic protocol/transport boundaries. */
internal class OnlineLyricsRepositoryFixture(directory: File, scope: CoroutineScope,
    serviceAddress: String = "https://service.invalid/api?token=fixture") : AutoCloseable {
    val resources = RuntimePlaybackResources()
    val store = ControlledSessionStore()
    val sessions = SessionRepository(store) { resources.invalidateStaleAccounts() }
    val requests = CopyOnWriteArrayList<HttpRequest>()
    val metingStoreCreations = AtomicInteger()
    val httpAcquisitions = AtomicInteger()
    val serviceRequests = AtomicInteger()
    val online = AtomicBoolean(true)
    var beforeNative: (suspend () -> Unit)? = null
    var nativeBody = "{\"code\":200,\"lrc\":{\"lyric\":\"[00:01.230]original\\n[00:03]second\"},\"tlyric\":{\"lyric\":\"[00:01.230]translation\"}}"
    var serviceText = "[00:01.230]service original\n[00:03]service second"
    private val http = BoundedHttpGetClient(RuntimeEnvironment.getApplication(),
        OkHttpClient.Builder().addInterceptor { chain ->
            serviceRequests.incrementAndGet()
            assertEquals("/lyrics", chain.request().url.encodedPath)
            assertNull(chain.request().header("Cookie"))
            assertEquals("Bearer service-fixture", chain.request().header("Authorization"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body(serviceText.toResponseBody()).build()
        }.build())
    val meting = MetingRuntimeOwner(RuntimeEnvironment.getApplication(), resources,
        { httpAcquisitions.incrementAndGet(); http },
        { metingStoreCreations.incrementAndGet(); ControlledMetingStore(metingConfiguration(address = serviceAddress, enabled = true)) }, scope)
    val cache = DiskOnlineLyricsCache(directory)
    val native = NeteaseLyricsApi(NeteaseEapi(HttpExchange { request ->
        requests += request
        beforeNative?.invoke()
        HttpResponse(200, emptyList(), nativeBody.toByteArray())
    }))
    val repository = OnlineLyricsRepository(sessions, native, resources, meting, cache, { online.get() })
    var opened: RuntimePlaybackResource? = null
        private set
    val entry = QueueEntry("lyrics-entry-one", TrackKey("netease", "201"), 0L, 0L, accountId = "101")

    suspend fun signIn() { sessions.commit(fixtureSession(), sessions.loginEpoch()) { accept -> accept() } }
    fun request(allowNetwork: Boolean = true, maxBytes: Long = 8_388_608L) = OnlineLyricsRequest(
        entry.trackKey, "lyrics-queue-one", 3L, entry.queueEntryId, 1L, checkNotNull(entry.accountId), maxBytes, allowNetwork)

    suspend fun openNative(resolver: String = "netease-native") {
        closeOpened()
        val token = sessions.authenticated { _, captured -> captured }
        opened = resources.register(entry, MediaHttpTarget.https("https://audio.invalid/fixture"),
            PlaybackResolutionBudget(), null, status(resolver), { sessions.isCurrent(token) })
        // Models transport acceptance only; no decoder, time advancement or sound is claimed.
        resources.notifyOpened(checkNotNull(opened?.online))
    }

    suspend fun openMeting() {
        closeOpened()
        val use = checkNotNull(meting.configuration.capture(requireEnabled = true))
        val context = use.contextFor(entry.trackKey.trackId)
        val response = use.configuration.endpoint.request(entry.trackKey.trackId)
        val audio = MediaHttpTarget.serviceReference("/audio", response, context)
        val lyrics = MediaHttpTarget.serviceReference("/lyrics", response, context)
        val token = sessions.authenticated { _, captured -> captured }
        opened = resources.register(entry, audio, PlaybackResolutionBudget(), null,
            status("meting-custom", use.generation), { sessions.isCurrent(token) }, use::isCurrent,
            PlaybackAssociatedResources(null, lyrics))
        resources.notifyOpened(checkNotNull(opened?.online))
    }

    fun closeOpened() {
        val old = opened ?: return
        resources.notifyClosed(checkNotNull(old.online))
        old.close()
        opened = null
    }
    private fun status(resolver: String, configuration: Long? = null) = PlaybackResourceStatus(resolver,
        if (configuration == null) ResourceMatch.PROVIDER_REPORTED else ResourceMatch.SERVICE_UNVERIFIED,
        ResourceCompleteness.UNKNOWN, StreamingQuality.STANDARD, null, null, configuration)
    override fun close() {
        try { repository.close(); closeOpened() }
        finally { try { sessions.close() } finally { try { meting.close() } finally { http.close() } } }
    }
}
