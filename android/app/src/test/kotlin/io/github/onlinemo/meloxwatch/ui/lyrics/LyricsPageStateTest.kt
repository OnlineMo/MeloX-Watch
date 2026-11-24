package io.github.onlinemo.meloxwatch.ui.lyrics

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import io.github.onlinemo.meloxwatch.model.TrackKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class LyricsPageStateTest {
    @Test
    fun manualBrowsingRemainsUntilAnExplicitReturnWithoutChangingTheSavedListPosition() {
        val state = state()
        state.beginBrowsing()
        val token = state.beginAnchorScroll()
        state.endAnchorScroll(token)
        assertFalse(state.followsCurrent)
        assertEquals(17, state.listState.firstVisibleItemIndex)
        assertEquals(93, state.listState.firstVisibleItemScrollOffset)
        state.returnToCurrent()
        assertTrue(state.followsCurrent)
        assertEquals(17, state.listState.firstVisibleItemIndex)
        assertEquals(93, state.listState.firstVisibleItemScrollOffset)
    }

    @Test
    fun anOlderCancelledAnchorFinallyCannotClearTheNewScrollOwner() {
        val state = state()
        val old = state.beginAnchorScroll()
        val current = state.beginAnchorScroll()
        state.endAnchorScroll(old)
        assertTrue(state.automaticScrollInProgress)
        state.endAnchorScroll(current)
        assertFalse(state.automaticScrollInProgress)
    }

    @Test
    fun browsingStateCannotBeBorrowedByAnotherQueueInstanceSourceGenerationOrDocument() {
        val identity = identity()
        val state = state()
        state.beginBrowsing()
        assertTrue(state.belongsTo(identity, "document"))
        assertFalse(state.belongsTo(identity.copy(queueGenerationId = "replacement-queue"), "document"))
        assertFalse(state.belongsTo(identity.copy(queueEntryId = "duplicate-track-entry"), "document"))
        assertFalse(state.belongsTo(identity.copy(sourceGeneration = 2L), "document"))
        assertFalse(state.belongsTo(identity.copy(trackKey = TrackKey("local", "other-track")), "document"))
        assertFalse(state.belongsTo(identity, "replacement-document"))
        assertFalse(state.belongsTo(null, "document"))
        assertFalse(state.followsCurrent)
    }

    private fun state(): LyricsPageState = LyricsPageState(
        identity(), "document", LazyListState(firstVisibleItemIndex = 17, firstVisibleItemScrollOffset = 93),
        mutableStateOf(true),
    )

    private fun identity(): LyricsPageIdentity = LyricsPageIdentity(TrackKey("local", "fixture-track"), "queue", "entry", 1L)
}
