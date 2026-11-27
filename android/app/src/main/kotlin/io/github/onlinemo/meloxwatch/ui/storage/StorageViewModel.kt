package io.github.onlinemo.meloxwatch.ui.storage

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.onlinemo.meloxwatch.MeloXApplication
import io.github.onlinemo.meloxwatch.data.storage.StorageClearReport
import io.github.onlinemo.meloxwatch.data.storage.StorageException
import io.github.onlinemo.meloxwatch.data.storage.StorageIssue
import io.github.onlinemo.meloxwatch.data.storage.StorageReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StorageUiState(
    val report: StorageReport? = null,
    val clear: StorageClearReport? = null,
    val confirmClear: Boolean = false,
    val working: Boolean = false,
    val stopping: Boolean = false,
    val issue: StorageIssue? = null,
)

/** 统计由可见页面的明确按钮触发；隐藏页面安全取消已接受工作并等待收尾。 */
class StorageViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MeloXApplication
    private val mutableState = MutableStateFlow(StorageUiState())
    val state = mutableState.asStateFlow()
    private var command: Job? = null

    fun refresh() = run {
        val report = app.fileStorage.measure()
        mutableState.update { it.copy(report = report) }
    }
    fun requestClear() { if (command?.isCompleted != false) mutableState.update { it.copy(confirmClear = true) } }
    fun cancelClear() { mutableState.update { it.copy(confirmClear = false) } }
    fun confirmClear() {
        if (!mutableState.value.confirmClear) return
        mutableState.update { it.copy(confirmClear = false) }
        run {
            val result = app.fileStorage.clearCaches()
            mutableState.update { it.copy(clear = result, report = null, issue = result.issue) }
        }
    }
    fun cancel() { if (command?.isCompleted == false) { mutableState.update { it.copy(stopping = true) }
            command?.cancel() } }
    fun onHidden() { cancel()
        cancelClear() }

    private fun run(action: suspend () -> Unit) {
        if (command?.isCompleted == false) { mutableState.update { it.copy(issue = StorageIssue.BUSY) }
            return }
        mutableState.update { it.copy(working = true, stopping = false, issue = null) }
        val priorClear = app.fileStorage.clearState.value
        command = viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { mutableState.update { it.copy(issue = StorageIssue.CANCELLED) }
                throw cancelled }
            catch (failure: Exception) { mutableState.update { it.copy(issue = (failure as? StorageException)?.issue ?: StorageIssue.UNAVAILABLE) } }
            finally {
                val observed = app.fileStorage.clearState.value
                mutableState.update { it.copy(clear = observed ?: it.clear, report = if (observed != null && observed !== priorClear) null else it.report,
                    working = false, stopping = false) }
            }
        }
    }
}
