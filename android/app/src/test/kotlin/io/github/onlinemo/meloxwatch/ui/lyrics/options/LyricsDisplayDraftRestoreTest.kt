package io.github.onlinemo.meloxwatch.ui.lyrics.options

import android.app.Application
import android.os.Bundle
import android.os.Parcel
import androidx.activity.ComponentActivity
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.onlinemo.meloxwatch.data.lyrics.store.DisplayMutationGate
import io.github.onlinemo.meloxwatch.data.lyrics.store.ForwardingDisplayDataStore
import io.github.onlinemo.meloxwatch.data.lyrics.store.HeldDisplayMutationDataStore
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDisplayAlignment
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDisplayCodec
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDisplayFileFixture
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDisplayIssue
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDisplayPreferences
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDisplayResetConfirmation
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDisplayStore
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDisplayWriteResult
import io.github.onlinemo.meloxwatch.ui.ProbeLyricsDisplayViewModel
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Fresh registry/parcel owners and the real file-backed store distinguish drafts from commands. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
internal class LyricsDisplayDraftRestoreTest : LyricsDisplayFileFixture() {
    private val hosts = ArrayList<ActivityController<LyricsDisplayDraftRestoreActivity>>()

    @After
    fun closeHosts() {
        hosts.asReversed().forEach { host ->
            if (host.get().lifecycle.currentState != Lifecycle.State.DESTROYED) host.pause().stop().destroy()
        }
        hosts.clear()
    }

    @Test(timeout = 30_000L)
    fun aParcelledRegistryRestoresEveryUnsavedFieldIntoAFreshOwnerWithoutAWrite() = runBlocking {
        val file = preferencesFile()
        val original = LyricsDisplayPreferences(revision = 7L, textSizeSp = 18f, legacyKeepScreenOn = true)
        writeDisplayFixture(file, LyricsDisplayCodec.encode(original))
        val before = file.readBytes()
        val firstSession = openDisplay(file)
        assertEquals(original, firstSession.ready().preferences)
        val firstCalls = Calls(firstSession.store)
        val firstHost = host()
        val first = model(firstHost, firstCalls)
        assertTrue(first.editor.begin())
        val proposal = original.copy(textSizeSp = 31.75f, blockSpacingDp = 23.5f,
            alignment = LyricsDisplayAlignment.START, anchorFraction = 0.73f,
            horizontalMarginDp = 23.75f, showTranslation = false, emphasizeWords = true)
        assertTrue(first.editor.preview(proposal))
        val restoredState = checkpoint(firstHost)
        firstSession.close()

        val secondSession = openDisplay(file)
        assertEquals(original, secondSession.ready().preferences)
        val secondCalls = Calls(secondSession.store)
        val second = model(host(restoredState), secondCalls)
        assertNotSame(first, second)
        assertNotSame(first.editor, second.editor)
        assertEquals(proposal, second.editor.state.value.retry)
        assertNull(second.editor.state.value.draft)
        assertFalse(second.editor.state.value.working)
        second.editor.save()
        assertTrue(secondCalls.revisions.isEmpty())
        assertEquals(original, secondSession.ready().preferences)
        assertArrayEquals(before, file.readBytes())
        assertTrue(second.editor.reviewRetry())
        assertEquals(proposal, second.editor.state.value.draft)
        assertNull(second.editor.state.value.retry)
        assertTrue(firstCalls.revisions.isEmpty())
        assertEquals(0, secondCalls.resets)
        assertEquals(0, secondCalls.readRetries)
        assertArrayEquals(before, file.readBytes())
    }

    @Test(timeout = 30_000L)
    fun defaultPreviewSurvivesButAnExplicitCancellationDoesNotResurrectIt() = runBlocking {
        val file = preferencesFile()
        val original = LyricsDisplayPreferences(revision = 3L, textSizeSp = 32f,
            horizontalMarginDp = 24f, legacyKeepScreenOn = true)
        writeDisplayFixture(file, LyricsDisplayCodec.encode(original))
        val before = file.readBytes()
        val session = openDisplay(file)
        session.ready()
        val calls = Calls(session.store)
        val firstHost = host()
        val first = model(firstHost, calls)
        assertTrue(first.editor.begin())
        assertTrue(first.editor.previewDefaults())
        val defaults = LyricsDisplayPreferences(revision = original.revision)
        val secondHost = host(checkpoint(firstHost))
        val second = model(secondHost, calls)
        assertEquals(defaults, second.editor.state.value.retry)
        assertTrue(second.editor.reviewRetry())
        assertEquals(defaults, second.editor.state.value.draft)
        assertTrue(second.editor.cancel())
        val third = model(host(checkpoint(secondHost)), calls)
        assertNull(third.editor.state.value.draft)
        assertNull(third.editor.state.value.retry)
        assertTrue(third.editor.begin())
        assertEquals(original, third.editor.state.value.draft)
        assertTrue(calls.revisions.isEmpty())
        assertEquals(0, calls.resets)
        assertArrayEquals(before, file.readBytes())
    }

    @Test(timeout = 30_000L)
    fun anExplicitSaveClearsTheRetainedProposalAndKeepsTheOriginalCasRevision() = runBlocking {
        val file = preferencesFile()
        val original = LyricsDisplayPreferences(revision = 4L)
        writeDisplayFixture(file, LyricsDisplayCodec.encode(original))
        val session = openDisplay(file)
        session.ready()
        val calls = Calls(session.store)
        val firstHost = host()
        val first = model(firstHost, calls)
        assertTrue(first.editor.begin())
        val proposal = original.copy(textSizeSp = 29f, emphasizeWords = true)
        assertTrue(first.editor.preview(proposal))
        first.editor.save()
        assertEquals(listOf(4L), calls.revisions)
        assertEquals(proposal.copy(revision = 5L), session.ready().preferences)
        val before = file.readBytes()
        val second = model(host(checkpoint(firstHost)), calls)
        assertNull(second.editor.state.value.draft)
        assertNull(second.editor.state.value.retry)
        second.editor.save()
        assertEquals(listOf(4L), calls.revisions)
        assertArrayEquals(before, file.readBytes())
    }

    @Test(timeout = 30_000L)
    fun anUncommittedIoFailureRetainsTheProposalForExplicitReviewAfterRecreation() = runBlocking {
        val file = preferencesFile()
        val original = LyricsDisplayPreferences(revision = 6L)
        writeDisplayFixture(file, LyricsDisplayCodec.encode(original))
        val before = file.readBytes()
        lateinit var failure: FailNextMutation
        val session = openDisplay(file) { backing -> FailNextMutation(backing).also { failure = it } }
        session.ready()
        val calls = Calls(session.store)
        val firstHost = host()
        val first = model(firstHost, calls)
        assertTrue(first.editor.begin())
        val proposal = original.copy(blockSpacingDp = 24f, showTranslation = false)
        assertTrue(first.editor.preview(proposal))
        failure.arm()
        first.editor.save()
        assertEquals(LyricsDisplayIssue.IO, first.editor.state.value.issue)
        assertEquals(proposal, first.editor.state.value.retry)
        assertArrayEquals(before, file.readBytes())
        val second = model(host(checkpoint(firstHost)), calls)
        assertEquals(proposal, second.editor.state.value.retry)
        second.editor.save()
        assertEquals(listOf(6L), calls.revisions)
        assertTrue(second.editor.reviewRetry())
        assertEquals(proposal, second.editor.state.value.draft)
        second.editor.save()
        assertEquals(listOf(6L, 6L), calls.revisions)
        assertEquals(proposal.copy(revision = 7L), session.ready().preferences)
    }

    @Test(timeout = 30_000L)
    fun cancellationBeforeTheRealCommitRetainsAReviewableProposalAfterRegistryRecreation() = runBlocking {
        val file = preferencesFile()
        val original = LyricsDisplayPreferences(revision = 12L)
        writeDisplayFixture(file, LyricsDisplayCodec.encode(original))
        val before = file.readBytes()
        val commit = DisplayMutationGate()
        val session = openDisplay(file) { HeldDisplayMutationDataStore(it, commit) }
        session.ready()
        val calls = Calls(session.store)
        val firstHost = host()
        val first = model(firstHost, calls)
        assertTrue(first.editor.begin())
        val proposal = original.copy(textSizeSp = 30f, blockSpacingDp = 20f, emphasizeWords = true)
        assertTrue(first.editor.preview(proposal))
        commit.arm()
        val write = launch(start = CoroutineStart.UNDISPATCHED) { first.editor.save() }
        try {
            commit.entered.await()
            write.cancel()
            assertTrue(first.editor.state.value.working)
            assertFalse(first.editor.cancel())
            commit.release.complete(Unit)
            write.join()
            assertFalse(first.editor.state.value.working)
            assertNull(first.editor.state.value.draft)
            assertEquals(proposal, first.editor.state.value.retry)
            assertEquals(LyricsDisplayNotice.READ_REQUIRED, first.editor.state.value.notice)
            assertEquals(original, session.ready().preferences)
            assertArrayEquals(before, file.readBytes())
            val restored = checkpoint(firstHost)
            session.close()

            val reopened = openDisplay(file)
            assertEquals(original, reopened.ready().preferences)
            val freshCalls = Calls(reopened.store)
            val second = model(host(restored), freshCalls)
            assertEquals(proposal, second.editor.state.value.retry)
            assertNull(second.editor.state.value.draft)
            second.editor.save()
            assertTrue(freshCalls.revisions.isEmpty())
            assertArrayEquals(before, file.readBytes())
            assertTrue(second.editor.reviewRetry())
            assertEquals(proposal, second.editor.state.value.draft)
            assertTrue(freshCalls.revisions.isEmpty())
            second.editor.save()
            assertEquals(listOf(12L), freshCalls.revisions)
            assertEquals(proposal.copy(revision = 13L), reopened.ready().preferences)
            assertEquals(listOf(12L), calls.revisions)
            assertEquals(0, freshCalls.resets)
        } finally {
            commit.release.complete(Unit)
            withContext(NonCancellable) { write.cancelAndJoin() }
        }
    }

    @Test(timeout = 30_000L)
    fun aRealCommittedWriteIsNotReplayedFromInFlightOrSettledCancellationSnapshots() = runBlocking {
        for (snapshotBeforeCancellation in listOf(true, false)) {
            val file = preferencesFile()
            val original = LyricsDisplayPreferences(revision = 9L)
            writeDisplayFixture(file, LyricsDisplayCodec.encode(original))
            val ack = DisplayMutationGate()
            val session = openDisplay(file) { HeldDisplayMutationDataStore(it, ack, afterCommit = true) }
            session.ready()
            val calls = Calls(session.store)
            val firstHost = host()
            val first = model(firstHost, calls)
            assertTrue(first.editor.begin())
            val proposal = original.copy(horizontalMarginDp = 22f, showTranslation = false)
            assertTrue(first.editor.preview(proposal))
            ack.arm()
            val write = launch(start = CoroutineStart.UNDISPATCHED) { first.editor.save() }
            try {
                ack.entered.await()
                assertTrue(first.editor.state.value.working)
                val inFlightState = if (snapshotBeforeCancellation) checkpoint(firstHost) else null
                write.cancel()
                assertTrue(first.editor.state.value.working)
                assertFalse(first.editor.cancel())
                ack.release.complete(Unit)
                write.join()
                assertFalse(first.editor.state.value.working)
                assertNull(first.editor.state.value.draft)
                assertEquals(proposal, first.editor.state.value.retry)
                assertEquals(LyricsDisplayNotice.READ_REQUIRED, first.editor.state.value.notice)
                assertEquals(proposal.copy(revision = 10L), session.ready().preferences)
                val restored = inFlightState ?: checkpoint(firstHost)
                val before = file.readBytes()
                session.close()
                val reopened = openDisplay(file)
                reopened.ready()
                val freshCalls = Calls(reopened.store)
                val second = model(host(restored), freshCalls)
                assertEquals(proposal, second.editor.state.value.retry)
                assertFalse(second.editor.state.value.working)
                second.editor.save()
                assertTrue(freshCalls.revisions.isEmpty())
                assertTrue(second.editor.reviewRetry())
                assertEquals(LyricsDisplayNotice.SAVED, second.editor.state.value.notice)
                assertNull(second.editor.state.value.draft)
                assertNull(second.editor.state.value.retry)
                second.editor.save()
                assertTrue(freshCalls.revisions.isEmpty())
                assertEquals(listOf(9L), calls.revisions)
                assertArrayEquals(before, file.readBytes())
            } finally {
                ack.release.complete(Unit)
                withContext(NonCancellable) { write.cancelAndJoin() }
            }
        }
    }

    @Test(timeout = 30_000L)
    fun aNewCommittedRevisionRejectsTheRestoredProposalWithoutRebasingIt() = runBlocking {
        val file = preferencesFile()
        val original = LyricsDisplayPreferences(revision = 8L)
        writeDisplayFixture(file, LyricsDisplayCodec.encode(original))
        val session = openDisplay(file)
        session.ready()
        val calls = Calls(session.store)
        val firstHost = host()
        val first = model(firstHost, calls)
        assertTrue(first.editor.begin())
        val proposal = original.copy(textSizeSp = 32f)
        assertTrue(first.editor.preview(proposal))
        val state = checkpoint(firstHost)
        val other = original.copy(anchorFraction = 0.25f)
        assertTrue(session.store.save(original.revision, other) is LyricsDisplayWriteResult.Saved)
        val before = file.readBytes()
        val second = model(host(state), calls)
        assertEquals(proposal, second.editor.state.value.retry)
        assertTrue(second.editor.reviewRetry())
        assertEquals(LyricsDisplayNotice.CONFLICT, second.editor.state.value.notice)
        assertNull(second.editor.state.value.retry)
        assertNull(second.editor.state.value.draft)
        second.editor.save()
        assertTrue(calls.revisions.isEmpty())
        assertArrayEquals(before, file.readBytes())
        assertTrue(second.editor.begin())
        assertEquals(other.copy(revision = 9L), second.editor.state.value.draft)
    }

    @Test(timeout = 30_000L)
    fun invalidSavedDraftTypesRangesAndFutureFormatsNeverChangeTheRealStore() = runBlocking {
        val file = preferencesFile()
        val original = LyricsDisplayPreferences(revision = 2L)
        writeDisplayFixture(file, LyricsDisplayCodec.encode(original))
        val session = openDisplay(file)
        session.ready()
        val calls = Calls(session.store)
        val validHandle = SavedStateHandle()
        val encoder = LyricsDisplayEditor(calls, validHandle)
        assertTrue(encoder.begin())
        assertTrue(encoder.preview(original.copy(textSizeSp = 28f)))
        val fields = checkNotNull(validHandle.get<List<*>>(LyricsDisplaySavedDrafts.KEY_PROPOSAL))
        assertEquals(10, fields.size)
        assertTrue(fields.all { it is Int || it is Long || it is Float || it is Boolean })
        val faults = ArrayList<Any?>().apply {
            add(null)
            add("not a proposal")
            add(fields.dropLast(1))
            add(fields + 1)
            for ((index, value) in listOf(0 to 2, 1 to -1L, 2 to 28, 2 to Float.NaN,
                2 to 33f, 3 to Float.POSITIVE_INFINITY, 3 to 3f, 4 to 2,
                5 to 0.24f, 6 to 25f, 7 to "false", 8 to 1, 9 to null)) {
                add(fields.toMutableList().apply { this[index] = value })
            }
        }
        val before = file.readBytes()
        for (fault in faults) {
            val handle = SavedStateHandle(mapOf(LyricsDisplaySavedDrafts.KEY_PROPOSAL to fault, "other_owner" to 17L))
            val editor = LyricsDisplayEditor(calls, handle)
            assertNull(editor.state.value.draft)
            assertNull(editor.state.value.retry)
            assertEquals(LyricsDisplayIssue.INVALID_FIELDS, editor.state.value.issue)
            assertEquals(LyricsDisplayNotice.READ_REQUIRED, editor.state.value.notice)
            assertFalse(handle.contains(LyricsDisplaySavedDrafts.KEY_PROPOSAL))
            assertEquals(17L, handle.get<Long>("other_owner"))
            editor.save()
            assertArrayEquals(before, file.readBytes())
        }
        assertTrue(calls.revisions.isEmpty())
        assertEquals(0, calls.resets)
        assertEquals(0, calls.readRetries)
    }

    @Test(timeout = 30_000L)
    fun unsupportedStoredConfigurationKeepsTheRetainedProposalReadOnlyUntilExplicitRecovery() = runBlocking {
        val proposal = LyricsDisplayPreferences(revision = 5L, textSizeSp = 30f)
        val file = preferencesFile()
        writeDisplayFixture(file, LyricsDisplayCodec.encode(proposal))
        val firstSession = openDisplay(file)
        firstSession.ready()
        val firstHost = host()
        assertTrue(model(firstHost, firstSession.store).editor.begin())
        val restored = checkpoint(firstHost)
        firstSession.close()
        writeDisplayFixture(file, LyricsDisplayCodec.encode(proposal).toMutablePreferences().apply {
            this[intPreferencesKey("schema_version")] = 2
        })
        val before = file.readBytes()
        val session = openDisplay(file)
        assertEquals(LyricsDisplayIssue.UNSUPPORTED_SCHEMA, session.unavailable().issue)
        val calls = Calls(session.store)
        val editor = model(host(restored), calls).editor
        assertEquals(proposal, editor.state.value.retry)
        assertFalse(editor.reviewRetry())
        assertEquals(proposal, editor.state.value.retry)
        assertNull(editor.state.value.draft)
        editor.save()
        assertFalse(editor.begin())
        assertTrue(calls.revisions.isEmpty())
        assertEquals(0, calls.resets)
        assertEquals(0, calls.readRetries)
        assertArrayEquals(before, file.readBytes())
    }

    private fun host(state: Bundle? = null): ActivityController<LyricsDisplayDraftRestoreActivity> =
        Robolectric.buildActivity(LyricsDisplayDraftRestoreActivity::class.java).create(state).start().resume()
            .also { hosts.add(it) }

    private fun model(host: ActivityController<LyricsDisplayDraftRestoreActivity>, store: LyricsDisplayStore): ProbeLyricsDisplayViewModel =
        ViewModelProvider(host.get(), viewModelFactory {
            initializer {
                ProbeLyricsDisplayViewModel(RuntimeEnvironment.getApplication(), LyricsDisplayEditor(store, createSavedStateHandle()))
            }
        })[ProbeLyricsDisplayViewModel::class.java]

    private fun checkpoint(host: ActivityController<LyricsDisplayDraftRestoreActivity>): Bundle {
        val saved = Bundle()
        host.pause().saveInstanceState(saved).stop().destroy()
        return parcelledDisplayState(saved)
    }

    private class Calls(private val delegate: LyricsDisplayStore) : LyricsDisplayStore by delegate {
        val revisions = ArrayList<Long>()
        var resets = 0
        var readRetries = 0
        override suspend fun save(expectedRevision: Long, next: LyricsDisplayPreferences): LyricsDisplayWriteResult {
            revisions += expectedRevision
            return delegate.save(expectedRevision, next)
        }
        override suspend fun reset(expectedRevision: Long, confirmation: LyricsDisplayResetConfirmation): LyricsDisplayWriteResult {
            resets++
            return delegate.reset(expectedRevision, confirmation)
        }
        override fun retryRead() { readRetries++; delegate.retryRead() }
    }

    private class FailNextMutation(delegate: DataStore<Preferences>) : ForwardingDisplayDataStore(delegate) {
        private val armed = AtomicBoolean(false)
        fun arm() { check(armed.compareAndSet(false, true)) }
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = delegate.updateData { before ->
            val after = transform(before)
            if (after != before && armed.compareAndSet(true, false)) throw IOException("controlled uncommitted display write")
            after
        }
    }

}

class LyricsDisplayDraftRestoreActivity : ComponentActivity()

internal fun parcelledDisplayState(saved: Bundle): Bundle {
    val encoded = Parcel.obtain()
    val decoded = Parcel.obtain()
    try {
        encoded.writeBundle(saved)
        val bytes = encoded.marshall()
        decoded.unmarshall(bytes, 0, bytes.size)
        decoded.setDataPosition(0)
        return checkNotNull(decoded.readBundle(LyricsDisplayDraftRestoreActivity::class.java.classLoader))
    } finally { encoded.recycle(); decoded.recycle() }
}
