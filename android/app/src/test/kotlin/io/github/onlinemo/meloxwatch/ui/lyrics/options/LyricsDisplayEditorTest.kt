package io.github.onlinemo.meloxwatch.ui.lyrics.options

import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDisplayIssue
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDisplayPreferences
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDisplayResetConfirmation
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDisplayState
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDisplayStore
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDisplayWriteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner oracles at the store boundary; real disk CAS tests remain in data/lyrics/store. */
class LyricsDisplayEditorTest {
    @Test(timeout = 30_000)
    fun previewsDefaultsAndCancelLeaveTheConfirmedStoreRecordUntouched() {
        val store = Store(LyricsDisplayPreferences(revision = 7L, textSizeSp = 18f))
        val original = store.actual()
        val editor = LyricsDisplayEditor(store)
        assertTrue(editor.begin())
        assertTrue(editor.preview(original.copy(textSizeSp = 32f, legacyKeepScreenOn = true)))
        assertTrue(editor.previewDefaults())
        assertEquals(LyricsDisplayPreferences(revision = 7L), editor.state.value.draft)
        assertTrue(editor.cancel())
        assertNull(editor.state.value.draft)
        assertNull(editor.state.value.retry)
        assertEquals(original, store.actual())
        assertEquals(0, store.saves)
        assertEquals(0, store.resets)
    }

    @Test(timeout = 30_000)
    fun savingUsesTheOriginalRevisionAndNewOwnersReadOnlyConfirmedValues() = runBlocking {
        val store = Store(LyricsDisplayPreferences(revision = 4L))
        val editor = LyricsDisplayEditor(store)
        assertTrue(editor.begin())
        val requested = store.actual().copy(textSizeSp = 31f, showTranslation = false)
        assertTrue(editor.preview(requested))
        assertEquals(20f, store.actual().textSizeSp, 0f)
        editor.save()
        assertEquals(listOf(4L), store.expectedRevisions)
        assertEquals(requested.copy(revision = 5L), store.actual())
        assertNull(editor.state.value.draft)
        assertEquals(LyricsDisplayNotice.SAVED, editor.state.value.notice)
        val recreated = LyricsDisplayEditor(store)
        assertTrue(recreated.begin())
        assertEquals(store.actual(), recreated.state.value.draft)
        assertEquals(1, store.saves)
    }

    @Test(timeout = 30_000)
    fun aChangedRevisionDiscardsTheOldDraftWithoutRebasingOrOverwriting() = runBlocking {
        val store = Store(LyricsDisplayPreferences(revision = 2L))
        val editor = LyricsDisplayEditor(store)
        assertTrue(editor.begin())
        assertTrue(editor.preview(store.actual().copy(textSizeSp = 30f)))
        val other = store.actual().copy(revision = 3L, anchorFraction = 0.75f)
        store.state.value = LyricsDisplayState.Ready(other, true)
        editor.save()
        assertEquals(other, store.actual())
        assertEquals(listOf(2L), store.expectedRevisions)
        assertNull(editor.state.value.draft)
        assertNull(editor.state.value.retry)
        assertEquals(LyricsDisplayNotice.CONFLICT, editor.state.value.notice)
        assertTrue(editor.begin())
        assertEquals(other, editor.state.value.draft)
        assertEquals(1, store.saves)
    }

    @Test(timeout = 30_000)
    fun aFailedWriteReturnsToConfirmedValuesAndRequiresExplicitReviewBeforeRetry() = runBlocking {
        val store = Store(LyricsDisplayPreferences(revision = 4L))
        val actual = store.actual()
        val editor = LyricsDisplayEditor(store)
        assertTrue(editor.begin())
        val proposal = actual.copy(blockSpacingDp = 22f)
        assertTrue(editor.preview(proposal))
        store.attempt = { _, _ -> LyricsDisplayWriteResult.Failed(actual, LyricsDisplayIssue.IO) }
        editor.save()
        assertEquals(actual, store.actual())
        assertNull(editor.state.value.draft)
        assertEquals(proposal, editor.state.value.retry)
        assertFalse(editor.begin())
        editor.save()
        assertEquals(1, store.saves)
        assertTrue(editor.reviewRetry())
        assertEquals(proposal, editor.state.value.draft)
        assertNull(editor.state.value.retry)
        store.attempt = null
        editor.save()
        assertEquals(listOf(4L, 4L), store.expectedRevisions)
        assertEquals(proposal.copy(revision = 5L), store.actual())
    }

    @Test(timeout = 30_000)
    fun aVerifiedCommittedValueAfterLostAcknowledgmentIsNeverSavedTwice() = runBlocking {
        val store = Store(LyricsDisplayPreferences(revision = 1L))
        val editor = LyricsDisplayEditor(store)
        assertTrue(editor.begin())
        val proposal = store.actual().copy(horizontalMarginDp = 18f)
        assertTrue(editor.preview(proposal))
        store.attempt = { expected, next ->
            store.commit(expected, next)
            LyricsDisplayWriteResult.Failed(store.actual(), LyricsDisplayIssue.IO)
        }
        editor.save()
        assertEquals(proposal, editor.state.value.retry)
        assertTrue(editor.reviewRetry())
        assertEquals(LyricsDisplayNotice.SAVED, editor.state.value.notice)
        assertNull(editor.state.value.retry)
        assertNull(editor.state.value.draft)
        editor.save()
        assertEquals(1, store.saves)
        assertEquals(proposal.copy(revision = 2L), store.actual())
    }

    @Test(timeout = 30_000)
    fun anUnavailableReadDoesNotAuthorizeRetryFromFallbackOrAutomaticallyWakeStorage() = runBlocking {
        val store = Store(LyricsDisplayPreferences(revision = 5L))
        val confirmed = store.actual()
        val editor = LyricsDisplayEditor(store)
        assertTrue(editor.begin())
        val proposal = confirmed.copy(textSizeSp = 28f)
        assertTrue(editor.preview(proposal))
        store.attempt = { _, _ ->
            store.state.value = LyricsDisplayState.Unavailable(confirmed, LyricsDisplayIssue.IO)
            LyricsDisplayWriteResult.Failed(confirmed, LyricsDisplayIssue.IO)
        }
        editor.save()
        assertFalse(editor.reviewRetry())
        assertNull(editor.state.value.draft)
        assertEquals(proposal, editor.state.value.retry)
        assertEquals(0, store.readRetries)
        editor.retryRead()
        assertEquals(1, store.readRetries)
        assertTrue(store.state.value is LyricsDisplayState.Unavailable)
        assertEquals(1, store.saves)
        store.state.value = LyricsDisplayState.Ready(confirmed, true)
        assertTrue(editor.reviewRetry())
        assertEquals(proposal, editor.state.value.draft)
        assertEquals(1, store.saves)
    }

    @Test(timeout = 30_000)
    fun cancellationKeepsAdmissionUntilTheRealStoreAttemptFinishesAndDoesNotReplayIt() = runBlocking {
        val store = Store()
        val editor = LyricsDisplayEditor(store)
        assertTrue(editor.begin())
        assertTrue(editor.preview(store.actual().copy(legacyKeepScreenOn = true)))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        store.attempt = { expected, next ->
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                store.commit(expected, next)
            }
        }
        val write = launch(start = CoroutineStart.UNDISPATCHED) { editor.save() }
        try {
            entered.await()
            write.cancel(CancellationException("Settings page hidden"))
            assertTrue(editor.state.value.working)
            assertFalse(editor.cancel())
            assertFalse(editor.begin())
            assertFalse(editor.previewDefaults())
            editor.save()
            assertEquals(1, store.saves)
            assertEquals(0L, store.actual().revision)
            release.complete(Unit)
            write.join()
            assertFalse(editor.state.value.working)
            assertNull(editor.state.value.draft)
            assertNull(editor.state.value.retry)
            assertEquals(LyricsDisplayNotice.READ_REQUIRED, editor.state.value.notice)
            assertEquals(1L, store.actual().revision)
            assertTrue(store.actual().legacyKeepScreenOn)
            editor.save()
            assertEquals(1, store.saves)
            assertTrue(editor.begin())
            assertEquals(store.actual(), editor.state.value.draft)
        } finally {
            release.complete(Unit)
            write.cancel()
            withContext(NonCancellable) { write.join() }
        }
    }

    @Test(timeout = 30_000)
    fun aDifferentRevisionDuringFailedPreviewReviewDiscardsItsSavedRetryIntent() = runBlocking {
        val store = Store(LyricsDisplayPreferences(revision = 8L))
        val editor = LyricsDisplayEditor(store)
        assertTrue(editor.begin())
        assertTrue(editor.preview(store.actual().copy(textSizeSp = 32f)))
        store.attempt = { _, _ -> LyricsDisplayWriteResult.Failed(store.actual(), LyricsDisplayIssue.IO) }
        editor.save()
        val other = store.actual().copy(revision = 9L, textSizeSp = 14f)
        store.state.value = LyricsDisplayState.Ready(other, true)
        assertTrue(editor.reviewRetry())
        assertEquals(LyricsDisplayNotice.CONFLICT, editor.state.value.notice)
        assertNull(editor.state.value.draft)
        assertNull(editor.state.value.retry)
        assertEquals(other, store.actual())
        assertEquals(1, store.saves)
    }

    private class Store(initial: LyricsDisplayPreferences = LyricsDisplayPreferences()) : LyricsDisplayStore {
        override val state = MutableStateFlow<LyricsDisplayState>(LyricsDisplayState.Ready(initial, true))
        var saves = 0
        var resets = 0
        var readRetries = 0
        val expectedRevisions = mutableListOf<Long>()
        var attempt: (suspend (Long, LyricsDisplayPreferences) -> LyricsDisplayWriteResult)? = null

        fun actual(): LyricsDisplayPreferences = (state.value as LyricsDisplayState.Ready).preferences

        override suspend fun save(expectedRevision: Long, next: LyricsDisplayPreferences): LyricsDisplayWriteResult {
            saves++
            expectedRevisions += expectedRevision
            return attempt?.invoke(expectedRevision, next) ?: commit(expectedRevision, next)
        }

        fun commit(expectedRevision: Long, next: LyricsDisplayPreferences): LyricsDisplayWriteResult {
            val current = actual()
            if (current.revision != expectedRevision) return LyricsDisplayWriteResult.Conflict(current)
            val saved = next.copy(revision = current.revision + 1L)
            state.value = LyricsDisplayState.Ready(saved, true)
            return LyricsDisplayWriteResult.Saved(saved)
        }

        override suspend fun reset(
            expectedRevision: Long,
            confirmation: LyricsDisplayResetConfirmation,
        ): LyricsDisplayWriteResult {
            resets++
            return LyricsDisplayWriteResult.Cancelled((state.value as? LyricsDisplayState.Ready)?.preferences)
        }

        override fun retryRead() { readRetries++ }
    }
}
