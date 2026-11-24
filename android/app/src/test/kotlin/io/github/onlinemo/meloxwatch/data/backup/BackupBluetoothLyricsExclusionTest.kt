package io.github.onlinemo.meloxwatch.data.backup

import io.github.onlinemo.meloxwatch.model.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupBluetoothLyricsExclusionTest {
    @Test fun portablePlayerBackupNeverCarriesExternalActivation() {
        val before = UserPreferences(bluetoothLyricsEnabled = true)
        val record = BackupPreferencesCodec.player(before)
        assertEquals(BackupPreferencesCodec.player(before.copy(bluetoothLyricsEnabled = false)).values, record.values)
        BackupPreferencesCodec.validate(record)
        val restored = BackupPreferencesCodec.player(record)
        assertFalse(restored.bluetoothLyricsEnabled)
        assertEquals(before.copy(bluetoothLyricsEnabled = false), restored)
    }

    @Test fun anAddedActivationFieldIsRejectedRatherThanRestoringAPermission() {
        val valid = BackupPreferencesCodec.player(UserPreferences())
        val invalid = BackupRecord(valid.kind, valid.key,
            valid.values + ("bluetoothLyricsEnabled" to BackupCell.Flag(true)))
        var rejected = false
        try { BackupPreferencesCodec.validate(invalid) }
        catch (error: BackupException) {
            assertEquals(BackupIssue.INVALID_INPUT, error.issue)
            rejected = true
        }
        assertTrue(rejected)
    }
}
