package io.github.onlinemo.meloxwatch.data.netease

import io.github.onlinemo.meloxwatch.data.auth.AuthException
import io.github.onlinemo.meloxwatch.data.auth.AuthIssue
import io.github.onlinemo.meloxwatch.data.auth.fixtureSession
import io.github.onlinemo.meloxwatch.data.network.HttpExchange
import io.github.onlinemo.meloxwatch.data.network.NetworkException
import io.github.onlinemo.meloxwatch.data.network.NetworkIssue
import io.github.onlinemo.meloxwatch.data.network.jsonLong
import io.github.onlinemo.meloxwatch.data.network.jsonString
import io.github.onlinemo.meloxwatch.data.online.catalogFields
import io.github.onlinemo.meloxwatch.data.online.catalogJson
import io.github.onlinemo.meloxwatch.data.online.catalogSearch
import io.github.onlinemo.meloxwatch.data.online.catalogSong
import io.github.onlinemo.meloxwatch.model.OnlineCatalogException
import io.github.onlinemo.meloxwatch.model.OnlineCatalogIssue
import io.github.onlinemo.meloxwatch.model.OnlineCatalogKind
import io.github.onlinemo.meloxwatch.model.OnlineCatalogQuery
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class NeteaseCatalogApiTest {
    private val credential = fixtureSession().stored.credential
    @Before fun initialize() { OkHttp.initialize(RuntimeEnvironment.getApplication()) }

    @Test fun searchUsesTheActualOffsetLimitAndQueryWithATrueLastPage() = runBlocking {
        var fields: io.github.onlinemo.meloxwatch.data.network.JsonObject? = null
        val api = NeteaseCatalogApi(NeteaseEapi(HttpExchange { request ->
            assertEquals(NeteaseEapi.ORIGIN + "/eapi/search/get", request.url)
            fields = catalogFields(request)
            catalogSearch(listOf(201L, 202L), 102L)
        }))
        val page = api.page(OnlineCatalogQuery(OnlineCatalogKind.SEARCH, "Fixture query"), "101", 100L, credential)
        assertEquals(100L, requireNotNull(fields)["offset"].jsonLong())
        assertEquals(100L, requireNotNull(fields)["limit"].jsonLong())
        assertEquals("Fixture query", requireNotNull(fields)["s"].jsonString())
        assertEquals(2, page.rawCount)
        assertEquals(102L, page.reportedTotal)
        assertFalse(page.more)
    }

    @Test fun anEmptyIntermediateSearchPageIsIncompleteRatherThanSuccessfulEmpty() = runBlocking {
        val api = NeteaseCatalogApi(NeteaseEapi(HttpExchange { catalogSearch(emptyList(), 10L) }))
        assertEquals(OnlineCatalogIssue.INCOMPLETE, catalogFailure { api.page(OnlineCatalogQuery(OnlineCatalogKind.SEARCH, "Fixture"), "101", 0L, credential) })
    }

    @Test fun playlistMoreFalseCannotContradictItsKnownTotal() = runBlocking {
        val api = NeteaseCatalogApi(NeteaseEapi(HttpExchange {
            catalogJson("{\"code\":200,\"more\":false,\"playlistCount\":2,\"playlist\":[{\"id\":991,\"name\":\"Fixture list\"}]}")
        }))
        assertEquals(OnlineCatalogIssue.INCOMPLETE, catalogFailure { api.page(OnlineCatalogQuery(OnlineCatalogKind.PLAYLISTS), "101", 0L, credential) })
    }

    @Test fun aPlaylistIndexPreservesDuplicateSongInstancesAndRequiresTheFullDeclaredCount() = runBlocking {
        val body = "{\"code\":200,\"playlist\":{\"id\":991,\"trackCount\":3,\"trackIds\":[{\"id\":201},{\"id\":202},{\"id\":201}],\"tracks\":[${catalogSong(201)}]}}"
        val api = NeteaseCatalogApi(NeteaseEapi(HttpExchange { catalogJson(body) }))
        assertEquals(listOf("201", "202", "201"), api.index(OnlineCatalogQuery(OnlineCatalogKind.PLAYLIST, "991"), "101", credential).ids)
        val incomplete = NeteaseCatalogApi(NeteaseEapi(HttpExchange { catalogJson(body.replace("\"trackCount\":3", "\"trackCount\":4")) }))
        assertEquals(OnlineCatalogIssue.INCOMPLETE, catalogFailure { incomplete.index(OnlineCatalogQuery(OnlineCatalogKind.PLAYLIST, "991"), "101", credential) })
    }

    @Test fun songDetailsRejectAnUnrequestedIdentity() = runBlocking {
        val api = NeteaseCatalogApi(NeteaseEapi(HttpExchange { catalogJson("{\"code\":200,\"songs\":[${catalogSong(299)}]}") }))
        assertEquals(OnlineCatalogIssue.INVALID_RESPONSE, catalogFailure { api.details(listOf("201"), credential) })
    }

    @Test fun networkErrorsKeepTheirOfflineTimeoutCertificateAndBusyClassifications() = runBlocking {
        for ((network, issue) in listOf(NetworkIssue.UNAVAILABLE to OnlineCatalogIssue.OFFLINE, NetworkIssue.TIMEOUT to OnlineCatalogIssue.TIMEOUT,
            NetworkIssue.CERTIFICATE to OnlineCatalogIssue.CERTIFICATE, NetworkIssue.BUSY to OnlineCatalogIssue.BUSY)) {
            val api = NeteaseCatalogApi(NeteaseEapi(HttpExchange { throw NetworkException(network) }))
            assertEquals(issue, catalogFailure { api.page(OnlineCatalogQuery(OnlineCatalogKind.SEARCH, "Fixture"), "101", 0L, credential) })
        }
    }

    @Test fun authorizationAndRateLimitFailuresRemainAccountErrors() = runBlocking {
        for ((status, expected) in listOf(401 to AuthIssue.LOGIN_REQUIRED, 429 to AuthIssue.RATE_LIMITED)) {
            val api = NeteaseCatalogApi(NeteaseEapi(HttpExchange { throw NetworkException(NetworkIssue.HTTP, status) }))
            var failure: AuthIssue? = null
            try { api.page(OnlineCatalogQuery(OnlineCatalogKind.SEARCH, "Fixture"), "101", 0L, credential) }
            catch (error: AuthException) { failure = error.issue }
            assertEquals(expected, failure)
        }
    }

    @Test fun metadataUsesStableStringsAndOnlySecureArtworkTargets() = runBlocking {
        val api = NeteaseCatalogApi(NeteaseEapi(HttpExchange { catalogSearch(listOf(201), 1L) }))
        val item = api.page(OnlineCatalogQuery(OnlineCatalogKind.SEARCH, "Fixture"), "101", 0L, credential).entries.single()
        assertEquals("Fixture artist", item.entry.artist)
        assertEquals(10_000L, item.entry.durationMs)
        assertTrue(requireNotNull(item.artworkUrl).startsWith("https://"))
        assertFalse(item.toString().contains("201.jpg"))
    }

    private suspend fun catalogFailure(action: suspend () -> Unit): OnlineCatalogIssue? = try {
        action()
        null
    } catch (error: OnlineCatalogException) { error.issue }
}
