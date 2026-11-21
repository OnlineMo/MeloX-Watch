package io.github.onlinemo.meloxwatch.data.themes

import kotlinx.coroutines.flow.StateFlow

/** The editor uses the existing repository owner; this boundary introduces no storage or queue. */
interface ThemeSelectionStore {
    val state: StateFlow<ThemeSnapshot>
    suspend fun read(): ThemeSnapshot
    suspend fun prepareImport(request: ThemeImportRequest): ThemePreview
    suspend fun previewChoice(choice: ThemeChoice): ThemePreview
    fun retirePreview(token: String)
    suspend fun apply(request: ThemeApplyRequest): ThemeApplyResult
}
