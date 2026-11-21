package io.github.onlinemo.meloxwatch.data.lyrics.online

import io.github.onlinemo.meloxwatch.data.meting.MetingRuntimeOwner
import io.github.onlinemo.meloxwatch.data.netease.OnlineAccountChanges
import io.github.onlinemo.meloxwatch.model.onlineOpaqueHash
import io.github.onlinemo.meloxwatch.playback.resources.RuntimePlaybackResources
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Constructing/observing and explicit cache cleanup do not create account or HTTP clients. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class OnlineLyricsFeatureOwnerTest {
    @Test fun constructionAndObservationRemainMemoryOnlyWithNoAccountClientOrLyricFetch() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val resources = RuntimePlaybackResources()
        val accounts = OnlineAccountChanges()
        val native = AtomicInteger()
        val http = AtomicInteger()
        val meting = MetingRuntimeOwner(context, resources, { http.incrementAndGet(); error("No hidden GET client") })
        val owner = OnlineLyricsFeatureOwner(context, { native.incrementAndGet(); error("No hidden account client") }, resources, accounts, meting)
        var revisions = 0
        val observation = backgroundScope.launch { owner.changes.collect { revisions++ } }
        try {
            runCurrent()
            assertEquals(0, revisions)
            accounts.changed()
            runCurrent()
            assertEquals(1, revisions)
            assertEquals(0, native.get())
            assertEquals(0, http.get())
        } finally { observation.cancel(); owner.close(); meting.close(); runCurrent() }
    }

    @Test fun explicitAccountCleanupUsesOnlyTheOwnedDiskCacheAndDoesNotCreateAnAccountRuntime() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val resources = RuntimePlaybackResources()
        val native = AtomicInteger()
        val meting = MetingRuntimeOwner(context, resources, { error("Cache cleanup must not acquire HTTP") })
        val owner = OnlineLyricsFeatureOwner(context, { native.incrementAndGet(); error("Cleanup must not create an account") },
            resources, OnlineAccountChanges(), meting)
        val cache = DiskOnlineLyricsCache(context.noBackupFilesDir.resolve("online-lyrics-cache"))
        val hash = onlineOpaqueHash("netease:101")
        val key = OnlineLyricsCacheKey.of(hash, "201", OnlineLyricsOrigin.NETEASE_LRC, null)
        try {
            assertTrue(cache.write(key, OnlineLyricsText("saved account lyrics".toByteArray(), null)) { true })
            assertNull(owner.clearAccount("101"))
            assertNull(cache.read(key))
            assertEquals(0, native.get())
        } finally { cache.clearOwner(hash); owner.close(); meting.close(); runCurrent() }
    }
}
