package io.github.onlinemo.meloxwatch.data.metadata

import io.github.onlinemo.meloxwatch.data.netease.EapiEnvironment
import io.github.onlinemo.meloxwatch.data.netease.NeteaseCatalogApi
import io.github.onlinemo.meloxwatch.data.netease.NeteaseEapi
import io.github.onlinemo.meloxwatch.data.netease.NeteaseLyricsApi
import io.github.onlinemo.meloxwatch.data.network.HttpExchange
import io.github.onlinemo.meloxwatch.data.network.HttpGetKind
import io.github.onlinemo.meloxwatch.data.network.HttpGetLimits
import io.github.onlinemo.meloxwatch.data.network.HttpGetResponse
import io.github.onlinemo.meloxwatch.data.network.NetworkException
import io.github.onlinemo.meloxwatch.data.online.catalogFields
import io.github.onlinemo.meloxwatch.data.online.catalogJson
import io.github.onlinemo.meloxwatch.data.online.catalogSong
import io.github.onlinemo.meloxwatch.data.network.jsonObject
import io.github.onlinemo.meloxwatch.model.OnlineCatalogException
import io.github.onlinemo.meloxwatch.model.OnlineCatalogIssue
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.OkHttp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Returned CDN addresses are synthetic. The GET boundary records calls and never opens a connection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class NeteaseMetadataClientTest {
    @Before fun initialize() { OkHttp.initialize(RuntimeEnvironment.getApplication()) }

    @Test fun onlyHttpsNativeCdnOriginsAreAcceptedBeforeAnyGet() {
        val accepted = NeteaseMetadataClient.artworkTarget("https://p1.music.126.net/fixture.png")
        assertEquals("p1.music.126.net", accepted.host)
        val rejected = listOf("https://example.invalid/fixture.png", "https://p1.music.126.net.example.invalid/fixture.png",
            "http://p1.music.126.net/fixture.png", "https://p1.music.126.net:444/fixture.png",
            "https://fixture@p1.music.126.net/fixture.png", "https://p1.music.126.net/fixture.png#fragment")
        for (url in rejected) {
            try { NeteaseMetadataClient.artworkTarget(url); throw AssertionError("Unexpected artwork origin accepted") }
            catch (_: NetworkException) { }
        }
    }

    @Test fun refreshedDetailsAndTheValidatedBodySupplyProvenanceWithoutPersistingTheUrl() = runBlocking {
        val gets = ArrayList<Pair<HttpUrl, HttpGetLimits>>()
        val bytes = byteArrayOf(1, 2, 3)
        val client = client("https://p1.music.126.net/fixture.png", get = { target, limits ->
            gets += target to limits
            HttpGetResponse(200, target, null, bytes)
        })
        val artifact = client.artwork(MetadataProvenance("201", "Old fixture title", null, null))
        assertArrayEquals(bytes, artifact.bytes)
        assertEquals("Refreshed fixture title", artifact.provenance.title)
        assertEquals("201", artifact.provenance.songId)
        assertEquals(1, gets.size)
        assertEquals(HttpGetKind.IMAGE, gets.single().second.kind)
        assertEquals(1_048_576, gets.single().second.responseBytes)
        assertTrue(gets.single().second.callTimeoutMs in 1..8_000L)
        assertFalse(artifact.provenance.encode().contains("http"))
        assertFalse(artifact.toString().contains("Refreshed"))
    }

    @Test fun aRedirectToAnotherOriginIsRejectedWithoutContactingIt() = runBlocking {
        var calls = 0
        val client = client("https://p1.music.126.net/fixture.png", get = { target, _ ->
            calls++
            HttpGetResponse(302, target, "https://example.invalid/fixture.png", byteArrayOf())
        })
        try { client.artwork(MetadataProvenance("201", null, null, null)); throw AssertionError("Unsafe redirect was followed") }
        catch (_: NetworkException) { }
        assertEquals(1, calls)
    }

    @Test fun redirectsCannotResetTheOperationBudgetOrExceedThreeHops() = runBlocking {
        var now = 1_000L
        val limits = ArrayList<Long>()
        val timed = client("https://p1.music.126.net/fixture.png", now = { now }, get = { target, budget ->
            limits += budget.callTimeoutMs
            now += 3_000L
            HttpGetResponse(302, target, "/fixture-next.png", byteArrayOf())
        })
        try { timed.artwork(MetadataProvenance("201", null, null, null)); throw AssertionError("Artwork exceeded its total time budget") }
        catch (failure: OnlineCatalogException) { assertEquals(OnlineCatalogIssue.TIMEOUT, failure.issue) }
        assertEquals(listOf(8_000L, 5_000L, 2_000L), limits)
        var calls = 0
        val repeated = client("https://p1.music.126.net/fixture.png", get = { target, _ ->
            calls++
            HttpGetResponse(302, target, "/fixture-next.png", byteArrayOf())
        })
        try { repeated.artwork(MetadataProvenance("201", null, null, null)); throw AssertionError("Too many redirects were accepted") }
        catch (failure: MetadataLookupException) { assertEquals(MetadataLookupIssue.INVALID_RESPONSE, failure.issue) }
        assertEquals(4, calls)
    }

    @Test fun anUnrequestedDetailOrChangedResponseUrlCannotBecomeAnArtifact() = runBlocking {
        var calls = 0
        val differentSong = client("https://p1.music.126.net/fixture.png", returnedId = 299,
            get = { _, _ -> calls++; throw AssertionError("A foreign detail cannot start GET") })
        try { differentSong.artwork(MetadataProvenance("201", null, null, null)); throw AssertionError("A different song was accepted") }
        catch (_: OnlineCatalogException) { }
        assertEquals(0, calls)
        val wrongUrl = client("https://p1.music.126.net/fixture.png", get = { _, _ ->
            calls++
            HttpGetResponse(200, NeteaseMetadataClient.artworkTarget("https://p2.music.126.net/other.png"), null, byteArrayOf(1))
        })
        try { wrongUrl.artwork(MetadataProvenance("201", null, null, null)); throw AssertionError("A mismatched response origin was accepted") }
        catch (failure: MetadataLookupException) { assertEquals(MetadataLookupIssue.INVALID_RESPONSE, failure.issue) }
        assertEquals(1, calls)
    }

    private fun client(url: String, returnedId: Long = 201L, now: () -> Long = { 1_000L },
        get: suspend (HttpUrl, HttpGetLimits) -> HttpGetResponse): NeteaseMetadataClient {
        val song = catalogSong(returnedId, "Refreshed fixture title")
            .replace("https://artwork.invalid/$returnedId.jpg", url)
        val eapi = NeteaseEapi(HttpExchange { request ->
            val header = catalogFields(request)["header"].jsonObject()
            assertFalse("MUSIC_U" in header.fields)
            assertFalse("MUSIC_A" in header.fields)
            catalogJson("{\"code\":200,\"songs\":[$song]}")
        }, EapiEnvironment(nowEpochMs = { 1_794_787_200_000L }))
        return NeteaseMetadataClient(NeteaseCatalogApi(eapi), NeteaseLyricsApi(eapi), get, now)
    }
}
