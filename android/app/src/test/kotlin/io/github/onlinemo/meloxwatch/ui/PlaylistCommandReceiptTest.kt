package io.github.onlinemo.meloxwatch.ui

import android.os.Bundle
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import io.github.onlinemo.meloxwatch.playback.PlaybackCommands
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistUiIssue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class PlaylistCommandReceiptTest {
    @Test(timeout = 30_000L)
    fun onlyTheTypedReceiptForThisOperationAcknowledgesSuccess() {
        assertNull(playlistCommandResultIssue(result(), OPERATION))
        assertEquals(PlaylistUiIssue.SOURCE_CHANGED, playlistCommandResultIssue(result("older-operation"), OPERATION))
    }

    @Test(timeout = 30_000L)
    fun successWithoutAReceiptCannotClearTheCurrentIntent() {
        assertEquals(PlaylistUiIssue.SOURCE_CHANGED,
            playlistCommandResultIssue(SessionResult(SessionResult.RESULT_SUCCESS, Bundle.EMPTY), OPERATION))
    }

    @Test(timeout = 30_000L)
    fun wrongSchemaAndOperationTypesCannotBecomeAnAcceptedReceipt() {
        val mutations: List<Bundle.() -> Unit> = listOf(
            { putString("schema", "2") }, { putInt("schema", 1) },
            { putLong("operation", 1L) }, { putString("operation", " ") },
        )
        for (mutation in mutations) {
            val extras = PlaybackCommands.operationReceiptArguments(OPERATION).apply(mutation)
            assertEquals(PlaylistUiIssue.SOURCE_CHANGED,
                playlistCommandResultIssue(SessionResult(SessionResult.RESULT_SUCCESS, extras), OPERATION))
        }
    }

    @Test(timeout = 30_000L)
    fun aMatchingFailureKeepsTheServiceIssueButAnOlderFailureCannotReplaceIt() {
        val extras = PlaybackCommands.operationReceiptArguments(OPERATION).apply { putString(PlaybackCommands.FAILURE, "CONFLICT") }
        assertEquals(PlaylistUiIssue.CONFLICT,
            playlistCommandResultIssue(SessionResult(SessionError.ERROR_INVALID_STATE, extras), OPERATION))
        assertEquals(PlaylistUiIssue.SOURCE_CHANGED,
            playlistCommandResultIssue(SessionResult(SessionError.ERROR_INVALID_STATE, extras), "newer-operation"))
    }

    @Test(timeout = 30_000L)
    fun aMatchingBadValueReportsAnInvalidIntent() {
        val extras = PlaybackCommands.operationReceiptArguments(OPERATION)
        assertEquals(PlaylistUiIssue.INVALID_INPUT,
            playlistCommandResultIssue(SessionResult(SessionError.ERROR_BAD_VALUE, extras), OPERATION))
    }

    @Test(timeout = 30_000L)
    fun busyCancellationAndClosedReceiptsDoNotImplyThatTheOperationNeverCommitted() {
        for ((code, issue) in listOf("BUSY" to PlaylistUiIssue.BUSY, "CANCELLED" to PlaylistUiIssue.BUSY,
            "CLOSED" to PlaylistUiIssue.SOURCE_UNAVAILABLE)) {
            val extras = PlaybackCommands.operationReceiptArguments(OPERATION).apply { putString(PlaybackCommands.FAILURE, code) }
            assertEquals(issue, playlistCommandResultIssue(SessionResult(SessionError.ERROR_INVALID_STATE, extras), OPERATION))
        }
    }

    @Test(timeout = 30_000L)
    fun missingWrongAndOversizedFailureCodesUseTheStableUnavailableIssue() {
        val mutations: List<Bundle.() -> Unit> = listOf(
            {}, { putInt(PlaybackCommands.FAILURE, 1) }, { putString(PlaybackCommands.FAILURE, "x".repeat(129)) },
            { putString(PlaybackCommands.FAILURE, "UNKNOWN_ERROR") },
        )
        for (mutation in mutations) {
            val extras = PlaybackCommands.operationReceiptArguments(OPERATION).apply(mutation)
            assertEquals(PlaylistUiIssue.SOURCE_UNAVAILABLE,
                playlistCommandResultIssue(SessionResult(SessionError.ERROR_INVALID_STATE, extras), OPERATION))
        }
    }

    private fun result(operationId: String = OPERATION): SessionResult =
        SessionResult(SessionResult.RESULT_SUCCESS, PlaybackCommands.operationReceiptArguments(operationId))

    companion object { private const val OPERATION = "playlist-operation" }
}
