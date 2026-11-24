package io.github.onlinemo.meloxwatch.data.meting

import io.github.onlinemo.meloxwatch.playback.resources.FixturePlaybackClock
import io.github.onlinemo.meloxwatch.playback.resources.MediaHttpTarget
import io.github.onlinemo.meloxwatch.playback.resources.PlaybackResolutionBudget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class MetingRequestFlightsTest {
    @Before fun initializeControlledUrlParsing() { OkHttp.initialize(RuntimeEnvironment.getApplication()) }

    @Test(timeout = 5_000) fun sameOperationHasFourBoundedWaitersAndANewOperationNeverReusesCompletedUnknownTtlUrls() = runBlocking {
        val flights = MetingRequestFlights(this)
        val budget = PlaybackResolutionBudget(FixturePlaybackClock())
        val key = MetingRequestKey("201", "101", 1L, 1L, budget.operationId)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val action: suspend () -> MetingResolvedSong = {
            calls++
            entered.complete(Unit)
            release.await()
            song()
        }
        val waiting = List(4) { async(start = CoroutineStart.UNDISPATCHED) { flights.resolve(key, budget, action) } }
        try {
            entered.await()
            assertEquals(MetingIssue.BUSY, assertThrows(MetingException::class.java) { runBlocking { flights.resolve(key, budget, action) } }.issue)
            val newer = PlaybackResolutionBudget(FixturePlaybackClock())
            assertEquals(MetingIssue.BUSY, assertThrows(MetingException::class.java) { runBlocking {
                flights.resolve(key.copy(operationId = newer.operationId), newer, action)
            } }.issue)
            waiting.first().cancel()
            waiting.first().join()
            assertEquals(1, calls)
            release.complete(Unit)
            waiting.drop(1).forEach { it.await() }
            flights.resolve(key.copy(operationId = newer.operationId), newer) { calls++; song() }
            assertEquals(2, calls)
        } finally {
            release.complete(Unit)
            waiting.forEach { it.cancel() }
            waiting.forEach { it.join() }
            flights.close()
        }
    }

    private fun song() = MetingResolvedSong(MediaHttpTarget.https("https://fixture.invalid/audio?signature=fixture"),
        null, null, MetingTrackMatch.SINGLE_COMPATIBLE_UNVERIFIED, null, null)
}
