package io.github.onlinemo.meloxwatch.data.meting

import okhttp3.OkHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class MetingEndpointTest {
    @Before fun initializeControlledUrlParsing() { OkHttp.initialize(RuntimeEnvironment.getApplication()) }

    @Test fun endpointKeepsUnrelatedRawQueryPairsAndAddsTheOriginalStableIdentity() {
        val endpoint = MetingEndpoint.checked("https://selfhost.invalid/custom/path?signature=a%2fb+c&empty&type=song",
            MetingEndpointMode.ENDPOINT, false)
        val target = endpoint.request("201")
        assertEquals("/custom/path", target.encodedPath)
        assertEquals("signature=a%2fb+c&empty&type=song&server=netease&id=201", target.encodedQuery)
        assertEquals("selfhost.invalid", endpoint.hostLabel)
        assertFalse(endpoint.toString().contains("signature"))
    }

    @Test fun templateReplacesOnlyAllowedQueryValuesAndDoesNotRewriteUnrelatedSignatures() {
        val endpoint = MetingEndpoint.checked("https://selfhost.invalid/service?server=:server&type=:type&id=:id&sig=a%2fb+c",
            MetingEndpointMode.TEMPLATE, false)
        assertEquals("server=netease&type=song&id=201&sig=a%2fb+c", endpoint.request("201").encodedQuery)
    }

    @Test fun conflictingOrRepeatedReservedParametersAndUnknownOrMisplacedPlaceholdersRejectAtSave() {
        for ((address, mode) in listOf(
            "https://selfhost.invalid/api?server=other" to MetingEndpointMode.ENDPOINT,
            "https://selfhost.invalid/api?type=song&type=song" to MetingEndpointMode.ENDPOINT,
            "https://selfhost.invalid/api?id=201" to MetingEndpointMode.ENDPOINT,
            "https://selfhost.invalid/api?id=:id&x=:unknown" to MetingEndpointMode.TEMPLATE,
            "https://selfhost.invalid/:id?id=:id" to MetingEndpointMode.TEMPLATE,
            "https://selfhost.invalid/api?:id=201" to MetingEndpointMode.TEMPLATE,
            "https://selfhost.invalid/api?type=:type" to MetingEndpointMode.TEMPLATE,
            "https://selfhost.invalid/api?id=:id&server=:id" to MetingEndpointMode.TEMPLATE,
        )) assertThrows(MetingException::class.java) { MetingEndpoint.checked(address, mode, false) }
    }

    @Test fun httpNeedsExplicitConsentAndDisplayNeverIncludesAQueryOrPath() {
        val address = "http://selfhost.invalid:8080/private-api?token=private-fixture"
        assertThrows(MetingException::class.java) { MetingEndpoint.checked(address, MetingEndpointMode.ENDPOINT, false) }
        val endpoint = MetingEndpoint.checked(address, MetingEndpointMode.ENDPOINT, true)
        assertEquals("selfhost.invalid:8080", endpoint.hostLabel)
        assertFalse(endpoint.toString().contains("private-fixture"))
    }

    @Test fun canonicalEncodingCannotTurnAnAcceptedShortInputIntoAnOversizedStoredAddress() {
        assertThrows(MetingException::class.java) {
            MetingEndpoint.checked("https://selfhost.invalid/api?name=" + "歌".repeat(512), MetingEndpointMode.ENDPOINT, false)
        }
    }
}
