package io.github.onlinemo.meloxwatch.data.lyrics.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A real file-backed identity update is admitted after cancellation; its actor must still exist. */
internal class LyricsDisplayCloseLifecycleTest : LyricsDisplayFileFixture() {
    @Test(timeout = 30000L)
    fun closingDrainsTheCancelledObserverReadBeforeStoppingTheRealDataStoreFileOwner(): Unit = runBlocking {
        val file = preferencesFile()
        val entered = CompletableDeferred<Unit>()
        val submit = CompletableDeferred<Unit>()
        val session = openDisplay(file) { HeldClosingIdentityRead(it, entered, submit) }
        withTimeout(10000L) { entered.await() }
        val closing = async { session.close() }
        try {
            withTimeout(10000L) { while (!session.observerCancelled) yield() }
            assertTrue(session.fileOwnerActive)
            assertFalse(closing.isCompleted)
            submit.complete(Unit)
            withTimeout(10000L) { closing.await() }
            assertFalse(session.fileOwnerActive)
            assertFalse(file.exists())
            assertEquals(LyricsDisplayState.Ready(LyricsDisplayPreferences(), persisted = false), openDisplay(file).ready())
        } finally {
            submit.complete(Unit)
            withTimeout(10000L) { closing.await() }
        }
    }
}

/** Only scheduling is controlled: the queued transform still goes through the real DataStore. */
private class HeldClosingIdentityRead(
    delegate: DataStore<Preferences>,
    private val entered: CompletableDeferred<Unit>,
    private val submit: CompletableDeferred<Unit>,
) : ForwardingDisplayDataStore(delegate) {
    private val first = AtomicBoolean(true)
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        if (first.compareAndSet(true, false)) {
            entered.complete(Unit)
            withContext(NonCancellable) { submit.await() }
        }
        return delegate.updateData(transform)
    }
}
