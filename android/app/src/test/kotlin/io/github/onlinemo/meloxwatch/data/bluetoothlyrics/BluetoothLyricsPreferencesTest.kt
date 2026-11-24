package io.github.onlinemo.meloxwatch.data.bluetoothlyrics

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesCodec
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesFailure
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesField
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesState
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesWriteResult
import io.github.onlinemo.meloxwatch.data.preferences.ResetConfirmation
import io.github.onlinemo.meloxwatch.data.preferences.UserPreferencesRepository
import io.github.onlinemo.meloxwatch.model.HelpPreferenceLimits
import io.github.onlinemo.meloxwatch.model.HelpPreferences
import io.github.onlinemo.meloxwatch.model.PrivacyNoticeChoice
import io.github.onlinemo.meloxwatch.model.ScreenShape
import io.github.onlinemo.meloxwatch.model.UserPreferences
import io.github.onlinemo.meloxwatch.model.UserPreferencesDraft
import io.github.onlinemo.meloxwatch.ui.bluetoothlyrics.BluetoothLyricsSettingsModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Definitions only: these fixtures do not attest Android grants, APKs or measured devices. */
@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothLyricsPreferencesTest {
    private val help = HelpPreferences(onboardingStep = 2, showTips = false,
        privacyNoticeVersion = HelpPreferenceLimits.PRIVACY_NOTICE_VERSION,
        privacyNoticeChoice = PrivacyNoticeChoice.LATER)

    @Test fun priorSchemasIgnoreExternalActivationAndPreserveTheirKnownFields() {
        for (schema in 1..5) {
            val original = PreferencesCodec.encode(UserPreferences(revision = 17L,
                restorePosition = false, screenShape = ScreenShape.ROUND, help = help,
                bluetoothLyricsEnabled = true)).toMutablePreferences().apply {
                this[intPreferencesKey("schema_version")] = schema
            }
            val before = original.asMap().toMap()
            val read = PreferencesCodec.decode(original)
            assertFalse(read.preferences.bluetoothLyricsEnabled)
            assertEquals(17L, read.preferences.revision)
            assertFalse(read.preferences.restorePosition)
            assertEquals(ScreenShape.ROUND, read.preferences.screenShape)
            assertEquals(if (schema == 5) help else HelpPreferenceLimits.Legacy, read.preferences.help)
            assertNull(read.recovery)
            assertFalse(read.isCanonical)
            assertEquals(before, original.asMap())
        }
    }

    @Test fun malformedNewBooleanDefaultsOffWithoutResettingHelp() {
        val record = PreferencesCodec.encode(UserPreferences(help = help)).toMutablePreferences().apply {
            remove(booleanPreferencesKey("bluetooth_lyrics_enabled"))
            this[stringPreferencesKey("bluetooth_lyrics_enabled")] = "true"
        }
        val decoded = PreferencesCodec.decode(record)
        assertFalse(decoded.preferences.bluetoothLyricsEnabled)
        assertEquals(help, decoded.preferences.help)
        assertEquals(setOf(PreferencesField.BLUETOOTH_LYRICS), decoded.recovery?.fields)
        assertFalse(decoded.isCanonical)
    }

    @Test fun currentCanonicalRecordPreservesAnExplicitOnSetting() {
        val original = UserPreferences(revision = 21L, help = help, bluetoothLyricsEnabled = true)
        val decoded = PreferencesCodec.decode(PreferencesCodec.encode(original))
        assertEquals(original, decoded.preferences)
        assertTrue(decoded.isCanonical)
    }

    @Test fun enableCancelDefaultAndDisableNeverWriteOrReconnectImplicitly(): Unit = runTest {
        val repo = FakePreferences(UserPreferences(revision = 5L, help = help, bluetoothLyricsEnabled = true))
        var stops = 0
        val model = BluetoothLyricsSettingsModel(repo, { stops++ }, backgroundScope)
        runCurrent()
        model.change(false)
        assertEquals(1, stops)
        assertFalse(model.state.value.draft)
        assertTrue(repo.current.bluetoothLyricsEnabled)
        model.reloadSaved()
        assertTrue(model.state.value.draft)
        model.defaultDraft()
        assertEquals(2, stops)
        assertEquals(0, repo.saves)
        assertTrue(repo.current.bluetoothLyricsEnabled)
    }

    @Test fun savingTheBooleanPreservesEveryOtherFieldAndCapturedRevision(): Unit = runTest {
        val before = UserPreferences(revision = 5L, restorePosition = false,
            screenShape = ScreenShape.ROUND, help = help)
        val repo = FakePreferences(before)
        val model = BluetoothLyricsSettingsModel(repo, {}, backgroundScope)
        runCurrent()
        model.change(true)
        assertFalse(model.state.value.canConnect)
        model.save()
        runCurrent()
        assertEquals(before.copy(revision = 6L, bluetoothLyricsEnabled = true), repo.current)
        assertTrue(model.state.value.canConnect)
        assertEquals(5L, repo.expectedRevision)
    }

    @Test fun saveFailureKeepsTheTrueSavedValueAndAllowsAnExplicitSameDraftRetry(): Unit = runTest {
        val repo = FakePreferences(UserPreferences(revision = 2L, help = help)).apply { failSave = true }
        val model = BluetoothLyricsSettingsModel(repo, {}, backgroundScope)
        runCurrent()
        model.change(true)
        model.save()
        runCurrent()
        assertFalse(repo.current.bluetoothLyricsEnabled)
        assertTrue(model.state.value.draft)
        assertEquals(PreferencesFailure.IO, model.state.value.failure)
        assertFalse(model.state.value.canConnect)
        repo.failSave = false
        model.save()
        runCurrent()
        assertTrue(repo.current.bluetoothLyricsEnabled)
        assertEquals(2, repo.saves)
    }

    @Test fun aChangedFullSnapshotCannotBeOverwrittenByThisOldBooleanDraft(): Unit = runTest {
        val repo = FakePreferences(UserPreferences(revision = 7L, help = help))
        val model = BluetoothLyricsSettingsModel(repo, {}, backgroundScope)
        runCurrent()
        model.change(true)
        repo.replace(repo.current.copy(revision = 8L, restorePosition = false))
        runCurrent()
        assertTrue(model.state.value.hasConflict)
        model.save()
        runCurrent()
        assertEquals(0, repo.saves)
        assertFalse(repo.current.bluetoothLyricsEnabled)
        assertFalse(repo.current.restorePosition)
        model.reloadSaved()
        assertFalse(model.state.value.hasConflict)
        assertFalse(model.state.value.draft)
    }

    private class FakePreferences(initial: UserPreferences) : UserPreferencesRepository {
        override val state = MutableStateFlow<PreferencesState>(PreferencesState.Ready(initial))
        val current: UserPreferences get() = (state.value as PreferencesState.Ready).preferences
        var saves = 0
        var failSave = false
        var expectedRevision: Long? = null
        fun replace(value: UserPreferences) { state.value = PreferencesState.Ready(value) }
        override suspend fun save(expectedRevision: Long, draft: UserPreferencesDraft): PreferencesWriteResult {
            saves++
            this.expectedRevision = expectedRevision
            if (current.revision != expectedRevision) return PreferencesWriteResult.Conflict(current)
            if (failSave) return PreferencesWriteResult.Failed(current, PreferencesFailure.IO)
            val next = current.copy(revision = current.revision + 1L,
                bluetoothLyricsEnabled = draft.bluetoothLyricsEnabled)
            if (draft.copy(bluetoothLyricsEnabled = current.bluetoothLyricsEnabled) != current.asDraft()) {
                throw AssertionError("Other saved fields were changed")
            }
            replace(next)
            return PreferencesWriteResult.Saved(next)
        }
        override suspend fun reset(expectedRevision: Long, confirmation: ResetConfirmation): PreferencesWriteResult =
            PreferencesWriteResult.Cancelled(current)
        override fun retryRead() = Unit
    }
}
