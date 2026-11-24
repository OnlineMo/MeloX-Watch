package io.github.onlinemo.meloxwatch.data.meting

import io.github.onlinemo.meloxwatch.data.network.BoundedHttpGetClient
import io.github.onlinemo.meloxwatch.playback.resources.FixturePlaybackClock
import io.github.onlinemo.meloxwatch.playback.resources.PlaybackResolutionBudget
import io.github.onlinemo.meloxwatch.playback.resources.ResourceCompleteness
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class MetingProtocolApiTest {
    @Before fun initializeControlledUrlParsing() { OkHttp.initialize(RuntimeEnvironment.getApplication()) }

    @Test fun singleClassicArrayKeepsResourceSignaturesAndReportsUnverifiedUnknownFields() = runBlocking {
        fixture { chain ->
            assertEquals("201", chain.request().url.queryParameter("id"))
            assertEquals("netease", chain.request().url.queryParameter("server"))
            assertEquals("Bearer service-fixture", chain.request().header("Authorization"))
            assertNull(chain.request().header("Cookie"))
            response(chain, 200, "[{\"name\":\"Fixture\",\"artist\":\"Artist\",\"url\":\"https://cdn.invalid/audio?sig=a%2fb+c\",\"pic\":\"/cover\",\"lrc\":\"/lyrics\"}]")
        }.use { fixture ->
            val song = fixture.api.resolve("201", fixture.use, PlaybackResolutionBudget(FixturePlaybackClock()))
            assertEquals(MetingTrackMatch.SINGLE_COMPATIBLE_UNVERIFIED, song.match)
            assertEquals(ResourceCompleteness.UNKNOWN, song.completeness)
            assertEquals("sig=a%2fb+c", song.target.url.encodedQuery)
            assertEquals(emptyMap<String, String>(), song.target.headers())
            assertEquals("/cover", song.artwork?.url?.encodedPath)
            assertEquals("/lyrics", song.lyrics?.url?.encodedPath)
            assertFalse(song.toString().contains("sig="))
        }
    }

    @Test fun conflictsAndAmbiguousMultiArraysAreRejectedWhileOneExactIdentityCanBeSelected() = runBlocking {
        for ((json, expected) in listOf(
            "[{\"id\":202,\"server\":\"netease\",\"url\":\"/audio\"}]" to MetingIssue.CONFLICTING_TRACK,
            "[{\"id\":201,\"server\":\"other\",\"url\":\"/audio\"}]" to MetingIssue.CONFLICTING_TRACK,
            "[{\"url\":\"/first\"},{\"url\":\"/second\"}]" to MetingIssue.MULTIPLE_RESULTS,
            "[{\"id\":201,\"server\":\"netease\",\"url\":\"/first\"},{\"id\":201,\"server\":\"netease\",\"url\":\"/second\"}]" to MetingIssue.MULTIPLE_RESULTS,
        )) fixture { chain -> response(chain, 200, json) }.use { fixture ->
            val error = assertThrows(MetingException::class.java) { runBlocking {
                fixture.api.resolve("201", fixture.use, PlaybackResolutionBudget(FixturePlaybackClock()))
            } }
            assertEquals(expected, error.issue)
        }
        fixture { chain -> response(chain, 200,
            "[{\"id\":202,\"server\":\"netease\",\"url\":\"/other\"},{\"id\":201,\"server\":\"netease\",\"url\":\"/exact\"}]") }.use { fixture ->
            val song = fixture.api.resolve("201", fixture.use, PlaybackResolutionBudget(FixturePlaybackClock()))
            assertEquals("/exact", song.target.url.encodedPath)
            assertEquals(MetingTrackMatch.EXACT_SERVICE_DECLARATION, song.match)
        }
    }

    @Test fun previewFalseNeverClaimsFullAndBadOptionalAssetsDoNotFailAudio() = runBlocking {
        for (preview in listOf("true", "false", "null")) fixture { chain -> response(chain, 200,
            "[{\"url\":\"/audio\",\"preview\":$preview,\"pic\":123,\"lrc\":\"http://other.invalid/lyrics\"}]") }.use { fixture ->
            val song = fixture.api.resolve("201", fixture.use, PlaybackResolutionBudget(FixturePlaybackClock()))
            assertEquals(if (preview == "true") ResourceCompleteness.PREVIEW_REPORTED else ResourceCompleteness.UNKNOWN, song.completeness)
            assertNull(song.artwork)
            assertNull(song.lyrics)
            assertEquals(MetingIssue.PROTOCOL_INVALID, song.artworkIssue)
            assertEquals(MetingIssue.RESOURCE_INVALID, song.lyricsIssue)
        }
    }

    @Test fun protocolCompatibilityAndResourceResolutionRemainDistinctForEmptyArraysAndInvalidAudioUrls() = runBlocking {
        for ((json, compatible) in listOf("[]" to true, "[{\"url\":null}]" to true,
            "{\"error\":\"unsupported\"}" to false, "[{\"url\":123}]" to false)) {
            fixture { chain -> response(chain, 200, json) }.use { fixture ->
                val error = assertThrows(MetingException::class.java) { runBlocking {
                    fixture.api.resolve("201", fixture.use, PlaybackResolutionBudget(FixturePlaybackClock()))
                } }
                assertEquals(compatible, error.protocolCompatible)
            }
        }
    }

    @Test fun cumulativeMetadataRedirectsAndRemainingNativeBudgetAreNotResetByTheService() = runBlocking {
        val clock = FixturePlaybackClock()
        val budget = PlaybackResolutionBudget(clock)
        budget.claimNativeRequest()
        budget.claimMetadataRedirect()
        budget.claimMetadataRedirect()
        clock.now = 5_000L
        val calls = AtomicInteger()
        fixture { chain ->
            calls.incrementAndGet()
            response(chain, 302, "").newBuilder().header("Location", "/again").build()
        }.use { fixture ->
            val error = assertThrows(MetingException::class.java) { runBlocking { fixture.api.resolve("201", fixture.use, budget) } }
            assertEquals(MetingIssue.REDIRECT_LIMIT, error.issue)
            assertEquals(2, calls.get())
            assertEquals(3_000L, budget.remainingMs())
        }
    }

    @Test fun authRateLimitsAndHtmlAreOneStoppedCallWithNoResourceSuccess() = runBlocking {
        for ((code, body, expected) in listOf(Triple(401, "no", MetingIssue.AUTH_REQUIRED),
            Triple(429, "slow", MetingIssue.RATE_LIMITED), Triple(200, "<html>error</html>", MetingIssue.PROTOCOL_INVALID))) {
            val calls = AtomicInteger()
            fixture { chain -> calls.incrementAndGet(); response(chain, code, body) }.use { fixture ->
                val error = assertThrows(MetingException::class.java) { runBlocking {
                    fixture.api.resolve("201", fixture.use, PlaybackResolutionBudget(FixturePlaybackClock()))
                } }
                assertEquals(expected, error.issue)
                assertFalse(error.protocolCompatible)
                assertEquals(1, calls.get())
                assertNull(error.cause)
            }
        }
    }

    private class Fixture(val repository: MetingConfigurationRepository, val use: MetingConfigurationUse,
        val http: BoundedHttpGetClient, val api: MetingProtocolApi) : AutoCloseable {
        override fun close() { repository.close(); http.close() }
    }
    private suspend fun fixture(interceptor: Interceptor): Fixture {
        val repository = MetingConfigurationRepository { ControlledMetingStore(metingConfiguration(enabled = true)) }
        val use = checkNotNull(repository.capture(true))
        val http = BoundedHttpGetClient(RuntimeEnvironment.getApplication(), OkHttpClient.Builder().addInterceptor(interceptor).build())
        return Fixture(repository, use, http, MetingProtocolApi(http))
    }
    private fun response(chain: Interceptor.Chain, code: Int, body: String) = Response.Builder()
        .request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Fixture").body(body.toResponseBody()).build()
}
