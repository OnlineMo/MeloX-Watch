package io.github.onlinemo.meloxwatch.data.lyrics.online

import io.github.onlinemo.meloxwatch.data.auth.ControlledSessionStore
import io.github.onlinemo.meloxwatch.data.auth.SessionRepository
import io.github.onlinemo.meloxwatch.data.auth.SessionValidity
import io.github.onlinemo.meloxwatch.data.auth.fixtureSession
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsLimits
import io.github.onlinemo.meloxwatch.model.onlineOpaqueHash
import io.github.onlinemo.meloxwatch.playback.resources.RuntimePlaybackResources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Production session/cache/source transitions against synthetic data; every execution gate remains NOT_RUN here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class OnlineLyricsRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    @Before fun initializeSyntheticHttp() { OkHttp.initialize(RuntimeEnvironment.getApplication()) }

    @Test fun nativeLyricsUseOneCallAndTheSharedParserWithoutOpeningMetingOrChangingAudio() = runBlocking {
        OnlineLyricsRepositoryFixture(temporary.newFolder(), this).use { fixture ->
            fixture.signIn()
            fixture.openNative()
            val audio = fixture.resources.openedResource.value
            val result = fixture.repository.load(fixture.request()) { true } as OnlineLyricsResult.Loaded
            assertEquals(OnlineLyricsOrigin.NETEASE_LRC, result.fence.origin)
            assertEquals(1_230L, result.document.timeline.line(0).startsAtMs)
            assertEquals("original", result.document.timeline.line(0).text)
            assertEquals("translation", result.translation?.line(0, result.document.documentId)?.text)
            assertFalse(result.document.hasWordTimings)
            assertFalse(result.fromCache)
            assertTrue(result.accountedBytes <= LyricsLimits.ACCOUNTED_BYTES)
            assertEquals(1, fixture.requests.size)
            assertEquals(0, fixture.metingStoreCreations.get())
            assertEquals(0, fixture.httpAcquisitions.get())
            assertTrue(fixture.repository.validate(result))
            assertEquals(1, fixture.requests.size)
            assertSame(audio, fixture.resources.openedResource.value)
            fixture.opened?.checkCurrent()
        }
    }

    @Test fun nativeMissingAndAuxiliaryAccountDenialNeverFallBackOrExpireHealthyAudio() = runBlocking {
        OnlineLyricsRepositoryFixture(temporary.newFolder(), this).use { fixture ->
            fixture.signIn()
            fixture.openNative()
            val audio = fixture.resources.openedResource.value
            fixture.nativeBody = "{\"code\":200,\"lrc\":null}"
            assertEquals(OnlineLyricsIssue.NO_LYRICS, unavailable(fixture.repository.load(fixture.request()) { true }))
            fixture.nativeBody = "{\"code\":301}"
            assertEquals(OnlineLyricsIssue.LOGIN_REQUIRED, unavailable(fixture.repository.load(fixture.request()) { true }))
            assertEquals(2, fixture.requests.size)
            assertEquals(0, fixture.metingStoreCreations.get())
            assertEquals(0, fixture.httpAcquisitions.get())
            assertEquals(SessionValidity.VERIFIED, fixture.sessions.snapshot.value.validity)
            assertEquals(0, fixture.store.clears)
            assertSame(audio, fixture.resources.openedResource.value)
            fixture.opened?.checkCurrent()
        }
    }

    @Test fun theSameRestoredAccountReadsItsDiskCacheOfflineWithoutVerificationOrAPlaybackGate() = runBlocking {
        OnlineLyricsRepositoryFixture(temporary.newFolder(), this).use { fixture ->
            fixture.signIn()
            fixture.openNative()
            val original = fixture.repository.load(fixture.request()) { true } as OnlineLyricsResult.Loaded
            fixture.closeOpened()
            val restored = SessionRepository(ControlledSessionStore(fixtureSession().stored))
            val emptyResources = RuntimePlaybackResources()
            val reader = OnlineLyricsRepository(restored, fixture.native, emptyResources, fixture.meting, fixture.cache, { false })
            try {
                val saved = reader.load(fixture.request()) { true } as OnlineLyricsResult.Loaded
                assertTrue(saved.fromCache)
                assertEquals(original.document.documentId, saved.document.documentId)
                assertEquals(SessionValidity.RESTORED_UNVERIFIED, restored.snapshot.value.validity)
                assertEquals(1, fixture.requests.size)
                assertFalse(reader.validate(saved))
                assertEquals(1, fixture.requests.size)
                assertNull(emptyResources.openedResource.value)
            } finally { reader.close(); restored.close() }
        }
    }

    @Test fun aDifferentRestoredAccountCannotReadAnEarlierAccountsLyricsAndNeverRevalidatesToBrowse() = runBlocking {
        OnlineLyricsRepositoryFixture(temporary.newFolder(), this).use { fixture ->
            fixture.signIn()
            fixture.openNative()
            fixture.repository.load(fixture.request()) { true }
            val restored = SessionRepository(ControlledSessionStore(fixtureSession("102").stored))
            val reader = OnlineLyricsRepository(restored, fixture.native, RuntimePlaybackResources(), fixture.meting, fixture.cache, { false })
            try {
                val other = fixture.request(allowNetwork = false).copy(accountId = "102")
                assertEquals(OnlineLyricsIssue.NO_LYRICS, unavailable(reader.load(other) { true }))
                assertEquals(1, fixture.requests.size)
                assertEquals(SessionValidity.RESTORED_UNVERIFIED, restored.snapshot.value.validity)
            } finally { reader.close(); restored.close() }
        }
    }

    @Test fun unopenedOrUnknownActiveSourcesMakeNoSpeculativeLyricsRequest() = runBlocking {
        OnlineLyricsRepositoryFixture(temporary.newFolder(), this).use { fixture ->
            fixture.signIn()
            assertEquals(OnlineLyricsIssue.SOURCE_CHANGED, unavailable(fixture.repository.load(fixture.request()) { true }))
            fixture.openNative(resolver = "unknown-source")
            assertEquals(OnlineLyricsIssue.UNSUPPORTED, unavailable(fixture.repository.load(fixture.request()) { true }))
            assertEquals(0, fixture.requests.size)
            assertEquals(0, fixture.metingStoreCreations.get())
            assertEquals(0, fixture.httpAcquisitions.get())
        }
    }

    @Test fun onlyAnActuallyOpenedMetingResourceReadsItsLyricsTargetAndConfigurationChangesRetireIt() = runBlocking {
        OnlineLyricsRepositoryFixture(temporary.newFolder(), this).use { fixture ->
            fixture.signIn()
            fixture.openMeting()
            val audio = fixture.resources.openedResource.value
            val loaded = fixture.repository.load(fixture.request()) { true } as OnlineLyricsResult.Loaded
            assertEquals(OnlineLyricsOrigin.OPENED_METING_LRC, loaded.fence.origin)
            assertEquals("service original", loaded.document.timeline.line(0).text)
            assertFalse(loaded.document.hasWordTimings)
            assertEquals(0, fixture.requests.size)
            assertEquals(1, fixture.serviceRequests.get())
            val saved = fixture.repository.load(fixture.request()) { true } as OnlineLyricsResult.Loaded
            assertTrue(saved.fromCache)
            assertEquals(1, fixture.serviceRequests.get())
            fixture.meting.configuration.setEnabled(checkNotNull(loaded.fence.configurationGeneration), false)
            assertFalse(fixture.repository.validate(loaded))
            assertEquals(OnlineLyricsIssue.SOURCE_CHANGED, unavailable(fixture.repository.load(fixture.request()) { true }))
            assertEquals(1, fixture.serviceRequests.get())
            assertEquals(0, fixture.requests.size)
            assertSame(audio, fixture.resources.openedResource.value)
            fixture.opened?.checkCurrent()
        }
    }

    @Test(timeout = 5_000) fun accountChangeRejectsALateNativeBodyAndDoesNotPublishItToDisk() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val directory = temporary.newFolder()
        val unrelated = temporary.newFile("local-library-sentinel.txt").apply { writeText("preserved local data") }
        OnlineLyricsRepositoryFixture(directory, this).use { fixture ->
            fixture.signIn()
            fixture.openNative()
            fixture.beforeNative = { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
            val loading = async(Dispatchers.IO) { fixture.repository.load(fixture.request()) { true } }
            try {
                entered.await()
                fixture.sessions.commit(fixtureSession("102"), fixture.sessions.loginEpoch()) { accept -> accept() }
                release.complete(Unit)
                try { loading.await(); error("Late account body was accepted") }
                catch (_: CancellationException) { }
                assertEquals("102", fixture.sessions.snapshot.value.account?.accountId)
                assertTrue(directory.listFiles().orEmpty().isEmpty())
                assertEquals("preserved local data", unrelated.readText())
                assertEquals(1, fixture.requests.size)
            } finally { release.complete(Unit); loading.join() }
        }
    }

    @Test(timeout = 5_000) fun cancelledReadRetainsItsSlotUntilNonCooperativeCleanupEnds() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        OnlineLyricsRepositoryFixture(temporary.newFolder(), this).use { fixture ->
            fixture.signIn()
            fixture.openNative()
            fixture.beforeNative = { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
            val loading = async(Dispatchers.IO) { fixture.repository.load(fixture.request()) { true } }
            try {
                entered.await()
                loading.cancel()
                assertEquals(OnlineLyricsIssue.BUSY, unavailable(fixture.repository.load(fixture.request()) { true }))
                assertEquals(1, fixture.requests.size)
                release.complete(Unit)
                loading.join()
                fixture.beforeNative = null
                assertTrue(fixture.repository.load(fixture.request()) { true } is OnlineLyricsResult.Loaded)
                assertEquals(2, fixture.requests.size)
            } finally { release.complete(Unit); loading.join() }
        }
    }

    @Test fun badTranslationPreservesPrimaryAndCacheAllocationHonorsTheSingleRequestBudget() = runBlocking {
        OnlineLyricsRepositoryFixture(temporary.newFolder(), this).use { fixture ->
            fixture.signIn()
            fixture.openNative()
            val key = OnlineLyricsCacheKey.of(onlineOpaqueHash("netease:101"), "201", OnlineLyricsOrigin.NETEASE_LRC, null)
            assertTrue(fixture.cache.write(key, OnlineLyricsText("[00:01]primary".toByteArray(), byteArrayOf(0xff.toByte()))) { true })
            val loaded = fixture.repository.load(fixture.request()) { true } as OnlineLyricsResult.Loaded
            assertEquals("primary", loaded.document.timeline.line(0).text)
            assertNull(loaded.translation)
            assertEquals(OnlineLyricsIssue.INVALID_ENCODING, loaded.translationIssue)
            assertTrue(loaded.accountedBytes <= LyricsLimits.ACCOUNTED_BYTES)
            assertEquals(OnlineLyricsIssue.MEMORY_LIMIT,
                unavailable(fixture.repository.load(fixture.request(maxBytes = 66_000L)) { true }))
            assertEquals(0, fixture.requests.size)
        }
    }

    @Test fun repeatedServiceGenerationsAfterRestartReuseOnlyTheExactConfiguredSourcesCache() = runBlocking {
        val directory = temporary.newFolder()
        OnlineLyricsRepositoryFixture(directory, this).use { first ->
            first.signIn()
            first.openMeting()
            val loaded = first.repository.load(first.request()) { true } as OnlineLyricsResult.Loaded
            assertFalse(loaded.fromCache)
            assertEquals(1L, loaded.fence.configurationGeneration)
            assertEquals(1, first.serviceRequests.get())
        }
        OnlineLyricsRepositoryFixture(directory, this).use { same ->
            same.signIn()
            same.openMeting()
            val loaded = same.repository.load(same.request()) { true } as OnlineLyricsResult.Loaded
            assertTrue(loaded.fromCache)
            assertEquals(1L, loaded.fence.configurationGeneration)
            assertEquals(0, same.serviceRequests.get())
            assertEquals("service original", loaded.document.timeline.line(0).text)
        }
        OnlineLyricsRepositoryFixture(directory, this, serviceAddress = "https://other.invalid/api?token=new-fixture").use { changed ->
            changed.signIn()
            changed.openMeting()
            changed.serviceText = "[00:01.230]new configured source"
            val loaded = changed.repository.load(changed.request()) { true } as OnlineLyricsResult.Loaded
            assertFalse(loaded.fromCache)
            assertEquals(1L, loaded.fence.configurationGeneration)
            assertEquals(1, changed.serviceRequests.get())
            assertEquals("new configured source", loaded.document.timeline.line(0).text)
            assertEquals(0, changed.requests.size)
        }
    }

    private fun unavailable(result: OnlineLyricsResult): OnlineLyricsIssue = (result as OnlineLyricsResult.Unavailable).issue
}
