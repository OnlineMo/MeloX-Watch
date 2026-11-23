package io.github.onlinemo.meloxwatch.data.network

import io.github.onlinemo.meloxwatch.data.meting.ControlledMetingStore
import io.github.onlinemo.meloxwatch.data.meting.MetingConfigurationRepository
import io.github.onlinemo.meloxwatch.data.meting.MetingEndpointMode
import io.github.onlinemo.meloxwatch.data.meting.metingConfiguration
import kotlinx.coroutines.runBlocking
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
class HttpTransportGateTest {
    @Before fun initializeControlledUrlParsing() { OkHttp.initialize(RuntimeEnvironment.getApplication()) }

    @Test fun defaultClientsRejectHttpAndInvalidUriFieldsBeforeAnyConnection() {
        for (address in listOf("http://service.invalid/api", "https://name:secret@service.invalid/api",
            "https://service.invalid/api#fragment", " https://service.invalid/api", "file:///audio", "https://service.invalid/a\n")) {
            val error = assertThrows(NetworkException::class.java) { HttpTransportGate.parseHttps(address) }
            assertEquals(NetworkIssue.INVALID_REQUEST, error.issue)
            assertFalse(error.toString().contains("secret"))
        }
    }

    @Test fun explicitHttpPermissionOnlyAppliesToTheExactConfiguredOriginAndCannotDowngradeHttps(): Unit = runBlocking {
        val repository = MetingConfigurationRepository { ControlledMetingStore(metingConfiguration(
            "http://service.invalid:8080/api", allowCleartext = true)) }
        try {
            val context = checkNotNull(repository.capture(false)).context
            val target = HttpTransportGate.parse("http://service.invalid:8080/audio")
            assertEquals(target, HttpTransportGate.check(target, context))
            for (address in listOf("http://service.invalid/audio", "http://other.invalid:8080/audio")) {
                assertThrows(NetworkException::class.java) { HttpTransportGate.check(HttpTransportGate.parse(address), context) }
            }
            val secure = HttpTransportGate.parseHttps("https://service.invalid/audio")
            assertThrows(NetworkException::class.java) { HttpTransportGate.redirect(secure, target.toString(), context) }
        } finally { repository.close() }
    }

    @Test fun crossOriginRemovesOnlyConfiguredPrivatePairsAndPreservesOtherSignatureEncodingAndOrder() = runBlocking {
        val repository = MetingConfigurationRepository { ControlledMetingStore(metingConfiguration()) }
        try {
            val context = checkNotNull(repository.capture(false)).context
            val origin = HttpTransportGate.parseHttps("https://service.invalid/api?token=fixture")
            val same = HttpTransportGate.redirect(origin, "/audio?token=fixture&sig=ab%2Fcd+ef&empty&sig=z%2bz", context)
            assertEquals("Bearer service-fixture", context.headersFor(same)["Authorization"])
            assertEquals("token=fixture&sig=ab%2Fcd+ef&empty&sig=z%2bz", same.encodedQuery)
            val other = HttpTransportGate.redirect(origin, "https://cdn.invalid/audio?token=fixture&sig=ab%2Fcd+ef&empty&sig=z%2bz", context)
            assertEquals(emptyMap<String, String>(), context.headersFor(other))
            assertEquals("sig=ab%2Fcd+ef&empty&sig=z%2bz", other.encodedQuery)
        } finally { repository.close() }
    }

    @Test fun templateQueriesWithPrivatePrefixesAreIsolatedAfterSubstitution() = runBlocking {
        val repository = MetingConfigurationRepository { ControlledMetingStore(metingConfiguration(
            "https://service.invalid/api?id=:id&token=private-:id", mode = MetingEndpointMode.TEMPLATE)) }
        try {
            val context = checkNotNull(repository.capture(false)).contextFor("201")
            val origin = HttpTransportGate.parseHttps("https://service.invalid/api?id=201&token=private-201")
            val other = HttpTransportGate.redirect(origin, "https://cdn.invalid/audio?token=private-201&sig=unchanged%2Fsignature", context)
            assertEquals("sig=unchanged%2Fsignature", other.encodedQuery)
            assertEquals(emptyMap<String, String>(), context.headersFor(other))
        } finally { repository.close() }
    }
}
