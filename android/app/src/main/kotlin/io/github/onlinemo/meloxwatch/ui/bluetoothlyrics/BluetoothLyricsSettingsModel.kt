package io.github.onlinemo.meloxwatch.ui.bluetoothlyrics

import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesFailure
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesState
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesWriteResult
import io.github.onlinemo.meloxwatch.data.preferences.UserPreferencesRepository
import io.github.onlinemo.meloxwatch.model.UserPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

enum class BluetoothLyricsSaveFeedback { SAVED, UNCHANGED }

data class BluetoothLyricsSettingsState(
    val storage: PreferencesState = PreferencesState.Loading,
    val base: UserPreferences? = null,
    val draft: Boolean = false,
    val saving: Boolean = false,
    val failure: PreferencesFailure? = null,
    val conflict: Boolean = false,
    val feedback: BluetoothLyricsSaveFeedback? = null,
) {
    val hasConflict: Boolean get() = conflict || (!saving && base != null &&
        storage is PreferencesState.Ready && storage.preferences.revision != base.revision)
    val canEdit: Boolean get() = !saving && !hasConflict && base != null &&
        storage is PreferencesState.Ready && storage.canWrite
    val canConnect: Boolean get() = canEdit && draft && base?.bluetoothLyricsEnabled == true &&
        failure == null
}

/** A Boolean draft never writes until explicit save with the captured full-snapshot revision. */
@MainThread
class BluetoothLyricsSettingsModel(
    private val repository: UserPreferencesRepository,
    private val stopOutput: () -> Unit,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope
    private val mutableState = MutableStateFlow(BluetoothLyricsSettingsState(storage = repository.state.value))
    val state = mutableState.asStateFlow()
    private var saving: Job? = null

    init {
        scope.launch {
            repository.state.collect { storage ->
                val current = mutableState.value.copy(storage = storage)
                mutableState.value = if (current.base == null && storage is PreferencesState.Ready) {
                    current.copy(base = storage.preferences, draft = storage.preferences.bluetoothLyricsEnabled)
                } else current
            }
        }
    }

    fun change(value: Boolean) {
        val current = mutableState.value
        if (!current.canEdit) return
        if (!value) stopOutput()
        mutableState.value = current.copy(draft = value, failure = null, feedback = null)
    }

    fun defaultDraft() = change(false)

    /** Cancel discards only this draft. A stopped connection never reconnects as a side effect. */
    fun reloadSaved() {
        if (saving != null) return
        val storage = repository.state.value as? PreferencesState.Ready ?: return
        mutableState.value = BluetoothLyricsSettingsState(storage = storage, base = storage.preferences,
            draft = storage.preferences.bluetoothLyricsEnabled)
    }

    fun retryRead() { if (saving == null) repository.retryRead() }

    fun save() {
        val current = mutableState.value
        val base = current.base ?: return
        if (!current.canEdit || saving != null) return
        val replacement = base.asDraft().copy(bluetoothLyricsEnabled = current.draft)
        mutableState.value = current.copy(saving = true, failure = null, feedback = null)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                when (val result = repository.save(base.revision, replacement)) {
                    is PreferencesWriteResult.Saved -> confirmed(result.preferences, BluetoothLyricsSaveFeedback.SAVED)
                    is PreferencesWriteResult.Unchanged -> confirmed(result.preferences, BluetoothLyricsSaveFeedback.UNCHANGED)
                    is PreferencesWriteResult.Conflict -> mutableState.value = mutableState.value.copy(
                        storage = repository.state.value, saving = false, conflict = true)
                    is PreferencesWriteResult.Cancelled -> mutableState.value = mutableState.value.copy(
                        storage = repository.state.value, saving = false)
                    is PreferencesWriteResult.Failed -> mutableState.value = mutableState.value.copy(
                        storage = repository.state.value, saving = false, failure = result.failure)
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                mutableState.value = mutableState.value.copy(storage = repository.state.value,
                    saving = false, failure = PreferencesFailure.IO)
            } finally {
                mutableState.value = mutableState.value.copy(saving = false)
            }
        }
        saving = job
        job.invokeOnCompletion { if (saving === job) saving = null }
        job.start()
    }

    private fun confirmed(value: UserPreferences, feedback: BluetoothLyricsSaveFeedback) {
        mutableState.value = BluetoothLyricsSettingsState(storage = repository.state.value, base = value,
            draft = value.bluetoothLyricsEnabled, feedback = feedback)
    }
}
