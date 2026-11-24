package io.github.onlinemo.meloxwatch.data.netease

import io.github.onlinemo.meloxwatch.data.auth.fixtureSession
import io.github.onlinemo.meloxwatch.data.network.HttpExchange
import io.github.onlinemo.meloxwatch.data.network.HttpRequest
import io.github.onlinemo.meloxwatch.data.network.HttpResponse
import io.github.onlinemo.meloxwatch.data.network.JsonObject
import io.github.onlinemo.meloxwatch.data.network.JsonText
import io.github.onlinemo.meloxwatch.data.network.StrictJson
import io.github.onlinemo.meloxwatch.data.network.jsonLong
import io.github.onlinemo.meloxwatch.data.network.jsonString
import io.github.onlinemo.meloxwatch.data.online.catalogFields
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic EAPI only. Defined for cloud execution; no account, Android or device acceptance is asserted. */
class NeteaseLyricsApiTest {
    @Test fun oneRequestKeepsNativeLrcAndTranslationBytesAndDoesNotInventYrcWordTiming() = runBlocking {
        val calls = CopyOnWriteArrayList<HttpRequest>()
        val original = "[offset:100]\n[00:01.230]原文\n[00:03]second"
        val translated = "[offset:100]\n[00:01.230]translation"
        val json = JsonObject(mapOf("code" to io.github.onlinemo.meloxwatch.data.network.JsonNumber("200"),
            "lrc" to JsonObject(mapOf("lyric" to JsonText(original))),
            "tlyric" to JsonObject(mapOf("lyric" to JsonText(translated))),
            "yrc" to JsonObject(mapOf("lyric" to JsonText("[1230,1770](1230,300,0)not LRC")))))
        val api = NeteaseLyricsApi(NeteaseEapi(HttpExchange { request ->
            calls += request
            HttpResponse(200, emptyList(), StrictJson.encode(json))
        }))
        val result = api.fetch("201", fixtureSession().stored.credential)
        assertEquals(original, result.primary?.toString(Charsets.UTF_8))
        assertEquals(translated, result.translation?.toString(Charsets.UTF_8))
        assertEquals(1, calls.size)
        assertEquals(NeteaseEapi.ORIGIN + "/eapi/song/lyric", calls.single().url)
        val fields = catalogFields(calls.single())
        assertEquals("201", fields["id"].jsonString())
        for (name in listOf("lv", "kv", "tv", "rv")) assertEquals(-1L, fields[name].jsonLong())
        assertNull(fields["yv"])
        assertEquals("NativeLyricsText(redacted)", result.toString())
    }

    @Test fun aNativeMissIsOneResultAndMalformedSectionsAreBoundedFailures() = runBlocking {
        var calls = 0
        var body = "{\"code\":200,\"lrc\":null,\"tlyric\":{\"lyric\":\"\"}}"
        val api = NeteaseLyricsApi(NeteaseEapi(HttpExchange {
            calls++
            HttpResponse(200, emptyList(), body.toByteArray())
        }))
        val missing = api.fetch("201", fixtureSession().stored.credential)
        assertNull(missing.primary)
        assertNull(missing.translation)
        assertEquals(1, calls)
        body = "{\"code\":200,\"lrc\":{\"lyric\":123}}"
        val issue = lyricsFailure { api.fetch("201", fixtureSession().stored.credential) }
        assertEquals(NeteaseLyricsIssue.INVALID_RESPONSE, issue)
        assertEquals(2, calls)
    }

    @Test fun aTightMemoryLimitNarrowsTheRealTransportAndRejectsBeforeCreatingAnImpossibleRequest() = runBlocking {
        val calls = CopyOnWriteArrayList<HttpRequest>()
        val api = NeteaseLyricsApi(NeteaseEapi(HttpExchange { request ->
            calls += request
            HttpResponse(200, emptyList(), "{\"code\":200,\"lrc\":{\"lyric\":\"[00:01]bounded\"}}".toByteArray())
        }))
        assertEquals("[00:01]bounded", api.fetch("201", fixtureSession().stored.credential, 300_000L).primary?.toString(Charsets.UTF_8))
        assertTrue(calls.single().budget.responseBytes in 1..17_232)
        assertTrue(calls.single().budget.callTimeoutMs <= 8_000L)
        assertEquals(NeteaseLyricsIssue.MEMORY_LIMIT, lyricsFailure {
            api.fetch("201", fixtureSession().stored.credential, 100_000L)
        })
        assertEquals(1, calls.size)
    }

    @Test fun malformedTranslationRetainsTheValidPrimaryAndItsBoundedIssueFromTheSameNativeCall() = runBlocking {
        var calls = 0
        val api = NeteaseLyricsApi(NeteaseEapi(HttpExchange {
            calls++
            HttpResponse(200, emptyList(), "{\"code\":200,\"lrc\":{\"lyric\":\"[00:01]primary\"},\"tlyric\":{\"lyric\":123}}".toByteArray())
        }))
        val loaded = api.fetch("201", fixtureSession().stored.credential)
        assertEquals("[00:01]primary", loaded.primary?.toString(Charsets.UTF_8))
        assertNull(loaded.translation)
        assertEquals(NeteaseLyricsIssue.INVALID_RESPONSE, loaded.translationIssue)
        assertEquals(1, calls)
    }

    @Test fun cancellationOfTheCallerRetiresTheSingleAuxiliaryRequest() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        var calls = 0
        val api = NeteaseLyricsApi(NeteaseEapi(HttpExchange {
            calls++
            entered.complete(Unit)
            try { CompletableDeferred<Unit>().await() }
            finally { cancelled.complete(Unit) }
            error("Retired synthetic request cannot return lyrics")
        }))
        val loading = async(Dispatchers.IO) { api.fetch("201", fixtureSession().stored.credential) }
        entered.await()
        loading.cancelAndJoin()
        assertTrue(cancelled.isCompleted)
        assertEquals(1, calls)
    }

    private suspend fun lyricsFailure(action: suspend () -> Unit): NeteaseLyricsIssue {
        try { action() }
        catch (error: NeteaseLyricsException) {
            assertFalse(error.message.orEmpty().contains("MUSIC_U"))
            return error.issue
        }
        error("Expected a bounded lyrics issue")
    }
}
