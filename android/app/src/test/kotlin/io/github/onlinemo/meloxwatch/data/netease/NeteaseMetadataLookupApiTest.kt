package io.github.onlinemo.meloxwatch.data.netease

import io.github.onlinemo.meloxwatch.data.auth.AuthException
import io.github.onlinemo.meloxwatch.data.auth.AuthIssue
import io.github.onlinemo.meloxwatch.data.auth.SessionCredential
import io.github.onlinemo.meloxwatch.data.network.HttpExchange
import io.github.onlinemo.meloxwatch.data.network.HttpRequest
import io.github.onlinemo.meloxwatch.data.network.HttpResponse
import io.github.onlinemo.meloxwatch.data.network.JsonArray
import io.github.onlinemo.meloxwatch.data.network.JsonBoolean
import io.github.onlinemo.meloxwatch.data.network.JsonObject
import io.github.onlinemo.meloxwatch.data.network.NetworkException
import io.github.onlinemo.meloxwatch.data.network.NetworkIssue
import io.github.onlinemo.meloxwatch.data.network.StrictJson
import io.github.onlinemo.meloxwatch.data.network.jsonLong
import io.github.onlinemo.meloxwatch.data.network.jsonObject
import io.github.onlinemo.meloxwatch.data.network.jsonString
import io.github.onlinemo.meloxwatch.data.online.catalogFields
import io.github.onlinemo.meloxwatch.data.online.catalogJson
import io.github.onlinemo.meloxwatch.data.online.catalogSearch
import io.github.onlinemo.meloxwatch.data.online.catalogSong
import io.github.onlinemo.meloxwatch.model.OnlineCatalogException
import io.github.onlinemo.meloxwatch.model.OnlineCatalogIssue
import io.github.onlinemo.meloxwatch.model.OnlineCatalogKind
import io.github.onlinemo.meloxwatch.model.OnlineCatalogQuery
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Synthetic protocol regression for cloud execution; controlled exchanges never open a connection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class NeteaseMetadataLookupApiTest {
    @Before fun initialize() { OkHttp.initialize(RuntimeEnvironment.getApplication()) }

    @Test fun anonymousCallsAfterAnAuthenticatedCallDoNotReplayItsCredentials() = runBlocking {
        val requests = CopyOnWriteArrayList<HttpRequest>()
        val eapi = syntheticEapi(HttpExchange { request ->
            requests += request
            when (request.url) {
                SEARCH_URL -> catalogSearch(listOf(201L), 1L)
                DETAILS_URL -> catalogJson("{\"code\":200,\"songs\":[${catalogSong(201)}]}")
                LYRICS_URL -> catalogJson("{\"code\":200,\"lrc\":{\"lyric\":\"[00:01]Fixture lyric\"}}")
                else -> error("Unexpected synthetic metadata route")
            }
        })
        val catalog = NeteaseCatalogApi(eapi)
        val credential = SessionCredential.fromValidatedHeader(
            "MUSIC_U=fixture-u; MUSIC_A=fixture-a; __csrf=fixture-csrf")
        catalog.page(OnlineCatalogQuery(OnlineCatalogKind.SEARCH, "Fixture authenticated query"),
            "101", 0L, credential)
        val authenticated = catalogFields(requests.single())["header"].jsonObject()
        assertTrue("MUSIC_U" in authenticated.fields)
        assertTrue("MUSIC_A" in authenticated.fields)
        requests.clear()

        val page = catalog.searchMetadata("Fixture song Fixture artist", 0L)
        assertEquals("201", page.entries.single().entry.id)
        assertEquals("201", catalog.metadataDetails("201")?.entry?.id)
        assertEquals("[00:01]Fixture lyric", NeteaseLyricsApi(eapi).fetchMetadata("201")
            .primary?.toString(Charsets.UTF_8))
        assertEquals(3, requests.size)
        requests.forEach { anonymousFields(it) }
        val search = catalogFields(requests[0])
        assertEquals("Fixture song Fixture artist", search["s"].jsonString())
        assertEquals(0L, search["offset"].jsonLong())
        assertEquals(20L, search["limit"].jsonLong())
        val details = StrictJson.decode(catalogFields(requests[1])["c"].jsonString()
            .toByteArray(Charsets.UTF_8)) as JsonArray
        assertEquals(1, details.values.size)
        assertEquals(setOf("id"), details.values.single().jsonObject().fields.keys)
        assertEquals(201L, details.values.single().jsonObject()["id"].jsonLong())
        assertEquals("201", catalogFields(requests[2])["id"].jsonString())
    }

    @Test fun theDefaultSearchPageAndTrueLastPageUseTheirActualOffsets() = runBlocking {
        val requests = CopyOnWriteArrayList<HttpRequest>()
        val firstIds = (201L..220L).toList()
        val lastIds = listOf(221L, 222L)
        val api = NeteaseCatalogApi(syntheticEapi(HttpExchange { request ->
            requests += request
            val fields = anonymousFields(request)
            assertEquals("Fixture search", fields["s"].jsonString())
            assertEquals(20L, fields["limit"].jsonLong())
            when (fields["offset"].jsonLong()) {
                0L -> catalogSearch(firstIds, 22L)
                20L -> catalogSearch(lastIds, 22L)
                else -> error("Unexpected synthetic page")
            }
        }))
        val first = api.searchMetadata("Fixture search", 0L)
        assertEquals(firstIds.map { it.toString() }, first.entries.map { it.entry.id })
        assertEquals(20, first.rawCount)
        assertEquals(22L, first.reportedTotal)
        assertTrue(first.more)
        val last = api.searchMetadata("Fixture search", 20L)
        assertEquals(lastIds.map { it.toString() }, last.entries.map { it.entry.id })
        assertEquals(2, last.rawCount)
        assertEquals(22L, last.reportedTotal)
        assertFalse(last.more)
        assertEquals(2, requests.size)
    }

    @Test fun aReportedZeroSearchAndMissingDetailsRemainSuccessfulEmptyResults() = runBlocking {
        val calls = AtomicInteger()
        val api = NeteaseCatalogApi(syntheticEapi(HttpExchange { request ->
            calls.incrementAndGet()
            anonymousFields(request)
            when (request.url) {
                SEARCH_URL -> catalogSearch(emptyList(), 0L)
                DETAILS_URL -> catalogJson("{\"code\":200,\"songs\":[]}")
                else -> error("Unexpected empty-result route")
            }
        }))
        val page = api.searchMetadata("Fixture missing", 0L)
        assertTrue(page.entries.isEmpty())
        assertEquals(0, page.rawCount)
        assertEquals(0L, page.reportedTotal)
        assertFalse(page.more)
        assertNull(api.metadataDetails("201"))
        assertEquals(2, calls.get())
    }

    @Test fun duplicateIdsContradictoryTotalsAndOversizedPagesCannotBecomeMatches() = runBlocking {
        val cases = listOf(
            Triple(catalogSearch(listOf(201L, 201L), 2L), 0L, OnlineCatalogIssue.INVALID_RESPONSE),
            Triple(catalogSearch(listOf(201L, 202L), 1L), 0L, OnlineCatalogIssue.INVALID_RESPONSE),
            Triple(catalogSearch(listOf(201L), -1L), 0L, OnlineCatalogIssue.INVALID_RESPONSE),
            Triple(catalogSearch(listOf(201L), 2L), 2L, OnlineCatalogIssue.INVALID_RESPONSE),
            Triple(catalogSearch((201L..221L).toList(), 21L), 0L, OnlineCatalogIssue.INVALID_RESPONSE),
            Triple(catalogSearch(emptyList(), 5L), 0L, OnlineCatalogIssue.INCOMPLETE),
        )
        for ((response, offset, expected) in cases) {
            val calls = AtomicInteger()
            val api = NeteaseCatalogApi(syntheticEapi(HttpExchange { request ->
                calls.incrementAndGet()
                anonymousFields(request)
                response
            }))
            assertEquals(expected, catalogFailure { api.searchMetadata("Fixture search", offset) })
            assertEquals(1, calls.get())
        }
    }

    @Test fun anonymousDetailsRejectAnUnrequestedIdentityOrDuplicateRecord() = runBlocking {
        for (ids in listOf(listOf(299L), listOf(201L, 299L), listOf(201L, 201L))) {
            val calls = AtomicInteger()
            val api = NeteaseCatalogApi(syntheticEapi(HttpExchange { request ->
                calls.incrementAndGet()
                anonymousFields(request)
                catalogJson("{\"code\":200,\"songs\":[${ids.joinToString(",") { catalogSong(it) }}]}")
            }))
            assertEquals(OnlineCatalogIssue.INVALID_RESPONSE, catalogFailure { api.metadataDetails("201") })
            assertEquals(1, calls.get())
        }
    }

    @Test fun invalidExplicitQueriesAndNoncanonicalIdsNeverReachTheExchange() = runBlocking {
        val calls = AtomicInteger()
        val eapi = syntheticEapi(HttpExchange {
            calls.incrementAndGet()
            error("Invalid synthetic input must not issue a request")
        })
        val catalog = NeteaseCatalogApi(eapi)
        val queries = listOf(
            Triple("", 0L, 20), Triple("Fixture\nsearch", 0L, 20),
            Triple("x".repeat(513), 0L, 20), Triple("Fixture", -1L, 20),
            Triple("Fixture", Long.MAX_VALUE, 20), Triple("Fixture", 0L, 0),
            Triple("Fixture", 0L, 101),
        )
        for ((words, offset, limit) in queries) assertEquals(OnlineCatalogIssue.INVALID_RESPONSE,
            catalogFailure { catalog.searchMetadata(words, offset, limit) })
        for (id in listOf("0", "000201", "fixture-local-identity")) {
            assertEquals(OnlineCatalogIssue.INVALID_RESPONSE, catalogFailure { catalog.metadataDetails(id) })
            assertEquals(NeteaseLyricsIssue.INVALID_RESPONSE, lyricsFailure { NeteaseLyricsApi(eapi).fetchMetadata(id) })
        }
        assertEquals(0, calls.get())
    }

    @Test fun service301And429KeepTheirClassificationsWithoutCredentialRetry() = runBlocking {
        for ((code, expected) in listOf(301 to AuthIssue.LOGIN_REQUIRED, 429 to AuthIssue.RATE_LIMITED)) {
            val calls = AtomicInteger()
            val eapi = syntheticEapi(HttpExchange { request ->
                calls.incrementAndGet()
                anonymousFields(request)
                catalogJson("{\"code\":$code}")
            })
            assertEquals(expected, authFailure { NeteaseCatalogApi(eapi).searchMetadata("Fixture", 0L) })
            assertEquals(expected, authFailure { NeteaseCatalogApi(eapi).metadataDetails("201") })
            assertEquals(expected, authFailure { NeteaseLyricsApi(eapi).fetchMetadata("201") })
            assertEquals(3, calls.get())
        }
    }

    @Test fun transport429DoesNotStartAnAuthenticatedFallback() = runBlocking {
        val calls = AtomicInteger()
        val eapi = syntheticEapi(HttpExchange { request ->
            calls.incrementAndGet()
            anonymousFields(request)
            throw NetworkException(NetworkIssue.HTTP, 429)
        })
        assertEquals(AuthIssue.RATE_LIMITED, authFailure { NeteaseCatalogApi(eapi).searchMetadata("Fixture", 0L) })
        assertEquals(AuthIssue.RATE_LIMITED, authFailure { NeteaseCatalogApi(eapi).metadataDetails("201") })
        assertEquals(AuthIssue.RATE_LIMITED, authFailure { NeteaseLyricsApi(eapi).fetchMetadata("201") })
        assertEquals(3, calls.get())
    }

    @Test fun anonymousLyricsPreserveRealTextAndReportABadTranslationSeparately() = runBlocking {
        val calls = AtomicInteger()
        var body = "{\"code\":200,\"lrc\":{\"lyric\":\"[00:01.200]合成歌词\\n[00:03]Fixture second line\"}," +
            "\"tlyric\":{\"lyric\":\"[00:01.200]Fixture translation\"},\"yrc\":{\"lyric\":\"Not LRC word timing\"}}"
        val api = NeteaseLyricsApi(syntheticEapi(HttpExchange { request ->
            calls.incrementAndGet()
            anonymousFields(request)
            catalogJson(body)
        }))
        val loaded = api.fetchMetadata("201")
        assertEquals("[00:01.200]合成歌词\n[00:03]Fixture second line", loaded.primary?.toString(Charsets.UTF_8))
        assertEquals("[00:01.200]Fixture translation", loaded.translation?.toString(Charsets.UTF_8))
        assertNull(loaded.translationIssue)
        assertEquals("NativeLyricsText(redacted)", loaded.toString())
        body = "{\"code\":200,\"lrc\":{\"lyric\":\"[00:01]Fixture primary\"},\"tlyric\":{\"lyric\":123}}"
        val partial = api.fetchMetadata("201")
        assertEquals("[00:01]Fixture primary", partial.primary?.toString(Charsets.UTF_8))
        assertNull(partial.translation)
        assertEquals(NeteaseLyricsIssue.INVALID_RESPONSE, partial.translationIssue)
        assertEquals(2, calls.get())
    }

    @Test fun malformedUtf8SectionsAndOversizedLyricResponsesAreRejected() = runBlocking {
        val oversized = "{\"code\":200,\"lrc\":{\"lyric\":\"" + "x".repeat(1024 * 1024 + 1) + "\"}}"
        val responses = listOf(
            catalogJson("{\"code\":200,\"lrc\":{\"lyric\":123}}"),
            catalogJson("{\"code\":200,\"lrc\":{\"lyric\":\"\\uD800\"}}"),
            HttpResponse(200, emptyList(), byteArrayOf(0xff.toByte())),
            catalogJson("{\"code\":200,\"lrc\":"),
            catalogJson(oversized),
        )
        for (response in responses) {
            val calls = AtomicInteger()
            val api = NeteaseLyricsApi(syntheticEapi(HttpExchange { request ->
                calls.incrementAndGet()
                anonymousFields(request)
                assertTrue(request.budget.responseBytes in 1..1024 * 1024)
                response
            }))
            assertEquals(NeteaseLyricsIssue.INVALID_RESPONSE, lyricsFailure { api.fetchMetadata("201") })
            assertEquals(1, calls.get())
        }
    }

    @Test fun anonymousLyricsHonorTheCallerMemoryBudgetBeforeAndDuringTheRequest() = runBlocking {
        val requests = CopyOnWriteArrayList<HttpRequest>()
        val api = NeteaseLyricsApi(syntheticEapi(HttpExchange { request ->
            requests += request
            anonymousFields(request)
            catalogJson("{\"code\":200,\"lrc\":{\"lyric\":\"[00:01]Fixture bounded\"}}")
        }))
        assertEquals("[00:01]Fixture bounded", api.fetchMetadata("201", 300_000L)
            .primary?.toString(Charsets.UTF_8))
        assertTrue(requests.single().budget.responseBytes in 1 until 1024 * 1024)
        assertTrue(requests.single().budget.callTimeoutMs <= 8_000L)
        assertEquals(NeteaseLyricsIssue.MEMORY_LIMIT, lyricsFailure { api.fetchMetadata("201", 100_000L) })
        assertEquals(1, requests.size)
    }

    @Test fun cancellingEachAnonymousOperationPropagatesCancellationAndRetiresItsExchange() = runBlocking {
        val operations = listOf<suspend (NeteaseEapi) -> Unit>(
            { NeteaseCatalogApi(it).searchMetadata("Fixture", 0L) },
            { NeteaseCatalogApi(it).metadataDetails("201") },
            { NeteaseLyricsApi(it).fetchMetadata("201") },
        )
        for (operation in operations) withTimeout(10_000L) {
            supervisorScope {
                val entered = CompletableDeferred<Unit>()
                val retired = CompletableDeferred<Unit>()
                val failure = CompletableDeferred<Throwable>()
                val calls = AtomicInteger()
                val eapi = syntheticEapi(HttpExchange { request ->
                    calls.incrementAndGet()
                    anonymousFields(request)
                    entered.complete(Unit)
                    try { awaitCancellation() } finally { retired.complete(Unit) }
                })
                val loading = async(Dispatchers.IO) {
                    try { operation(eapi) }
                    catch (error: Throwable) { failure.complete(error); throw error }
                }
                entered.await()
                loading.cancelAndJoin()
                assertTrue(failure.await() is CancellationException)
                assertTrue(loading.isCancelled)
                assertTrue(retired.isCompleted)
                assertEquals(1, calls.get())
            }
        }
    }

    private fun anonymousFields(request: HttpRequest): JsonObject {
        val fields = catalogFields(request)
        val expected = when (request.url) {
            SEARCH_URL -> setOf("s", "type", "limit", "offset", "e_r", "header")
            DETAILS_URL -> setOf("c", "e_r", "header")
            LYRICS_URL -> setOf("id", "lv", "kv", "tv", "rv", "e_r", "header")
            else -> error("Unexpected synthetic metadata route")
        }
        assertEquals(expected, fields.fields.keys)
        assertFalse((fields["e_r"] as JsonBoolean).value)
        if (request.url == SEARCH_URL) assertEquals(1L, fields["type"].jsonLong())
        if (request.url == LYRICS_URL) for (name in listOf("lv", "kv", "tv", "rv")) {
            assertEquals(-1L, fields[name].jsonLong())
        }
        val header = fields["header"].jsonObject()
        assertEquals(PROTOCOL_HEADERS, header.fields.keys)
        assertEquals("android", header["os"].jsonString())
        assertEquals("9.0.90", header["appver"].jsonString())
        assertEquals("140", header["versioncode"].jsonString())
        assertEquals("distribution", header["channel"].jsonString())
        assertEquals("2A".repeat(16), header["deviceId"].jsonString())
        assertEquals("1700000000", header["buildver"].jsonString())
        assertEquals("1700000000000_0007", header["requestId"].jsonString())
        for (name in listOf("osver", "mobilename", "resolution", "__csrf")) assertEquals("", header[name].jsonString())
        val rawCookie = requireNotNull(request.headers["Cookie"])
        val cookiePairs = rawCookie.split("; ")
        assertEquals(PROTOCOL_HEADERS.size, cookiePairs.size)
        val cookie = cookiePairs.associate { pair ->
            val split = pair.indexOf('=')
            assertTrue(split > 0)
            pair.substring(0, split) to pair.substring(split + 1)
        }
        assertEquals(PROTOCOL_HEADERS, cookie.keys)
        header.fields.forEach { (name, value) -> assertEquals(value.jsonString(), cookie[name]) }
        assertEquals(setOf("Content-Type", "User-Agent", "Cookie"), request.headers.keys)
        assertEquals("application/x-www-form-urlencoded", request.headers["Content-Type"])
        assertEquals("NeteaseMusic/9.0.90 (Android)", request.headers["User-Agent"])
        assertNull(header["MUSIC_U"])
        assertNull(header["MUSIC_A"])
        val plaintext = StrictJson.encode(fields).toString(Charsets.UTF_8)
        for (excluded in listOf("MUSIC_U", "MUSIC_A", "fixture-u", "fixture-a", "fixture-csrf",
                "content:", "file:", "fixture-local-identity")) {
            assertFalse(plaintext.contains(excluded))
            assertFalse(rawCookie.contains(excluded))
        }
        return fields
    }

    private fun syntheticEapi(exchange: HttpExchange): NeteaseEapi = NeteaseEapi(exchange,
        EapiEnvironment(nowEpochMs = { 1_700_000_000_000L }, random = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) { bytes.fill(0x2a.toByte()) }
            override fun nextInt(bound: Int): Int { require(bound > 0); return 7 % bound }
        }))

    private suspend fun catalogFailure(action: suspend () -> Unit): OnlineCatalogIssue {
        try { action() }
        catch (error: OnlineCatalogException) { return error.issue }
        error("Expected a rejected metadata catalog result")
    }

    private suspend fun lyricsFailure(action: suspend () -> Unit): NeteaseLyricsIssue {
        try { action() }
        catch (error: NeteaseLyricsException) { return error.issue }
        error("Expected a bounded metadata lyrics failure")
    }

    private suspend fun authFailure(action: suspend () -> Unit): AuthIssue {
        try { action() }
        catch (error: AuthException) { return error.issue }
        error("Expected a classified metadata service failure")
    }

    private companion object {
        const val SEARCH_URL = NeteaseEapi.ORIGIN + "/eapi/search/get"
        const val DETAILS_URL = NeteaseEapi.ORIGIN + "/eapi/v3/song/detail"
        const val LYRICS_URL = NeteaseEapi.ORIGIN + "/eapi/song/lyric"
        val PROTOCOL_HEADERS = setOf("os", "osver", "appver", "versioncode", "deviceId", "mobilename",
            "resolution", "channel", "buildver", "requestId", "__csrf")
    }
}
