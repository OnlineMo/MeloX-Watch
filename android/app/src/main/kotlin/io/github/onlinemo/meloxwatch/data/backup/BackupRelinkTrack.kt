package io.github.onlinemo.meloxwatch.data.backup

import io.github.onlinemo.meloxwatch.model.LocalCueReference

internal data class BackupRelinkTrack(val trackId: String, val missingQueue: Boolean, val cueReference: LocalCueReference?) {
    override fun toString(): String = "BackupRelinkTrack(redacted)"
}
