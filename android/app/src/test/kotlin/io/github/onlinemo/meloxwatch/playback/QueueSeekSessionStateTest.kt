package io.github.onlinemo.meloxwatch.playback

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class QueueSeekSessionStateTest {
    private val owner = "541b75e7-bba6-41df-91cf-88c781c16d5a"
    private val preview = "cd60c267-c5e1-40cd-8d70-99cd3b9e76d9"
    private val prefix = "io.github.onlinemo.meloxwatch.seek."

    @Test
    fun unavailableObservationHasNoOwnerAndCannotInventAPreview() {
        val state = QueueSeekSessionState()
        assertEquals(state, QueueSeekSessionState.fromSessionExtras(state.toSessionExtras()))
        assertNull(state.ownerId)
        assertNull(state.previewId)
    }

    @Test
    fun aMintedPreviewSharesTheOwnerWithoutExportingSourceOrQueueIdentity() {
        val state = QueueSeekSessionState(owner, preview)
        val extras = state.toSessionExtras()
        assertEquals(state, QueueSeekSessionState.fromSessionExtras(extras))
        assertEquals(setOf(prefix + "schema", prefix + "owner", prefix + "preview"), extras.keySet())
        extras.putString(prefix + "preview", null)
        assertEquals(preview, state.previewId)
        assertEquals(QueueSeekSessionState(owner), QueueSeekSessionState.fromSessionExtras(extras))
    }

    @Test
    fun unrelatedSessionExtrasDoNotInvalidateTheSeekObservation() {
        val extras = QueueSeekSessionState(owner, preview).toSessionExtras().apply {
            putString("backend", "SYSTEM")
            putLong("queue_revision", 99L)
        }
        assertEquals(QueueSeekSessionState(owner, preview), QueueSeekSessionState.fromSessionExtras(extras))
    }

    @Test
    fun missingUnknownAndWrongPrimitiveSchemasFailClosed() {
        val malformed = listOf(
            Bundle(),
            QueueSeekSessionState(owner).toSessionExtras().apply { remove(prefix + "schema") },
            QueueSeekSessionState(owner).toSessionExtras().apply { putLong(prefix + "schema", 1L) },
            QueueSeekSessionState(owner).toSessionExtras().apply { putInt(prefix + "schema", 2) },
        )
        malformed.forEach { assertNull(QueueSeekSessionState.fromSessionExtras(it)) }
    }

    @Test
    fun aMissingIdentityFieldCannotBecomeAnExplicitUnavailableObservation() {
        for (field in listOf("owner", "preview")) {
            val extras = QueueSeekSessionState().toSessionExtras().apply { remove(prefix + field) }
            assertNull(QueueSeekSessionState.fromSessionExtras(extras))
        }
    }

    @Test
    fun wrongIdentityTypesAndUnknownFieldsFailClosed() {
        val malformed = listOf(
            QueueSeekSessionState(owner, preview).toSessionExtras().apply { putLong(prefix + "owner", 1L) },
            QueueSeekSessionState(owner, preview).toSessionExtras().apply { putBoolean(prefix + "preview", true) },
            QueueSeekSessionState(owner, preview).toSessionExtras().apply { putString(prefix + "source", "private") },
        )
        malformed.forEach { assertNull(QueueSeekSessionState.fromSessionExtras(it)) }
    }

    @Test
    fun aPreviewWithoutItsOwnerAndNoncanonicalIdentitiesAreRejected() {
        val malformed = listOf(
            QueueSeekSessionState(owner, preview).toSessionExtras().apply { putString(prefix + "owner", null) },
            QueueSeekSessionState(owner, preview).toSessionExtras().apply { putString(prefix + "owner", "1-1-1-1-1") },
            QueueSeekSessionState(owner, preview).toSessionExtras().apply { putString(prefix + "preview", preview.uppercase()) },
            QueueSeekSessionState(owner, preview).toSessionExtras().apply { putString(prefix + "preview", "") },
        )
        malformed.forEach { assertNull(QueueSeekSessionState.fromSessionExtras(it)) }
    }
}
