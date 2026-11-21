package io.github.onlinemo.meloxwatch.data.themes

internal data class TransferredThemeStorage(val receivedIds: List<String>, val intent: TransferredThemeApplyIntent?) {
    override fun toString(): String = "<local-theme-storage-state>"
}

internal data class TransferredThemeApplyOutcome(val applied: ThemeApplyResult?)
