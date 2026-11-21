package io.github.onlinemo.meloxwatch.data.meting

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class MetingConfigurationRepositoryTest {
    @Before fun initializeControlledUrlParsing() { OkHttp.initialize(RuntimeEnvironment.getApplication()) }

    @Test fun absentAndDisabledConfigurationDoNotProduceAnEnabledUse() = runBlocking {
        for (stored in listOf(null, metingConfiguration(enabled = false))) {
            val store = ControlledMetingStore(stored)
            val repository = MetingConfigurationRepository { store }
            try {
                assertNull(repository.capture(true))
                assertFalse(repository.snapshot.value.enabled)
                assertEquals(1, store.loads)
            } finally { repository.close() }
        }
    }

    @Test fun changingConfigurationCancelsPendingUsesButRetiresAnAlreadyAcceptedOpening() = runBlocking {
        val repository = MetingConfigurationRepository { ControlledMetingStore(metingConfiguration(enabled = true)) }
        try {
            val use = checkNotNull(repository.capture(true))
            val pendingCancellations = AtomicInteger()
            val openedCancellations = AtomicInteger()
            val pending = use.context.ownPending { pendingCancellations.incrementAndGet() }
            val opened = use.context.ownPending { openedCancellations.incrementAndGet() }
            var accepted = false
            opened.commitOpened { accepted = true }
            assertTrue(accepted)
            val saved = repository.setEnabled(use.generation, false)
            assertEquals(use.generation + 1L, saved.generation)
            assertEquals(1, pendingCancellations.get())
            assertEquals(0, openedCancellations.get())
            assertFalse(use.isCurrent())
            assertThrows(CancellationException::class.java) { pending.commitOpened { throw AssertionError("No stale publication") } }
            pending.close()
            opened.close()
        } finally { repository.close() }
    }

    @Test fun aChangedOriginCannotKeepTheOldCredentialAndAnExplicitClearNeverCarriesOldQueries() = runBlocking {
        val repository = MetingConfigurationRepository { ControlledMetingStore(metingConfiguration()) }
        try {
            val old = repository.restore()
            val blank = repository.edit(old.generation)
            assertEquals("", blank.endpoint)
            val edit = blank.changed(endpoint = "https://new-service.invalid/new-api")
            val error = assertThrows(MetingException::class.java) { runBlocking { repository.save(old.generation, edit) } }
            assertEquals(MetingIssue.CREDENTIAL_ORIGIN_CHANGED, error.issue)
            assertEquals(old.generation, repository.snapshot.value.generation)
            val saved = repository.save(old.generation, edit.changed(credentialEdit = MetingCredentialEdit.CLEAR))
            val use = checkNotNull(repository.capture(false))
            assertFalse(saved.hasCredential)
            assertEquals("new-service.invalid", saved.hostLabel)
            assertNull(use.configuration.endpoint.url.query)
            assertEquals(emptyMap<String, String>(), use.context.headersFor(use.configuration.endpoint.url))
        } finally { repository.close() }
    }

    @Test fun failedCommitFencesOldConfigurationWithoutDestroyingThePreviousCiphertextRecord() = runBlocking {
        val original = metingConfiguration(enabled = true)
        val store = ControlledMetingStore(original).apply { writeFailure = MetingIssue.STORAGE_UNAVAILABLE }
        val repository = MetingConfigurationRepository { store }
        try {
            val use = checkNotNull(repository.capture(true))
            assertThrows(MetingException::class.java) { runBlocking { repository.setEnabled(use.generation, false) } }
            assertEquals(original, store.persistent)
            assertFalse(use.isCurrent())
            assertFalse(repository.snapshot.value.changing)
            assertFalse(repository.snapshot.value.enabled)
            assertEquals(MetingIssue.STORAGE_UNAVAILABLE, repository.snapshot.value.issue)
            store.writeFailure = null
            val restored = repository.reload()
            assertTrue(restored.enabled)
            assertTrue(restored.generation > original.generation)
        } finally { repository.close() }
    }

    @Test(timeout = 5_000) fun aCancelledReloadDoesNotLeaveTheRepositoryPermanentlyBusy() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = ControlledMetingStore(metingConfiguration())
        val repository = MetingConfigurationRepository { store }
        repository.restore()
        store.beforeLoad = { entered.complete(Unit); release.await() }
        val work = async(start = CoroutineStart.UNDISPATCHED) { repository.reload() }
        try {
            entered.await()
            work.cancel()
            assertThrows(CancellationException::class.java) { runBlocking { work.await() } }
            assertFalse(repository.snapshot.value.changing)
            assertEquals(MetingIssue.CONFIGURATION_CHANGED, repository.snapshot.value.issue)
            store.beforeLoad = {}
            assertTrue(repository.reload().configured)
        } finally { release.complete(Unit); work.join(); repository.close() }
    }
}
