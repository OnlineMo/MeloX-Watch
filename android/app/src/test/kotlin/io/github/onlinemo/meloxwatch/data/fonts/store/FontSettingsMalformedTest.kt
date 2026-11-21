package io.github.onlinemo.meloxwatch.data.fonts.store

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSelection
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSettings
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontTestFixtures as Fixture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

internal class FontSettingsMalformedTest : FontSettingsFileFixture() {
    @Test(timeout = 30000L)
    fun anUnknownEnvelopeSchemaStaysReadOnlyAcrossCasAndReopen() = runBlocking {
        val file = settingsFile()
        val stored = LyricsFontSettingsCodec.encode(bothRoles()).toMutablePreferences().apply {
            this[intPreferencesKey("schema_version")] = 2
        }
        writeSettingsFixture(file, stored)
        val bytes = file.readBytes()
        val session = openSettings(file)
        expectFontSettingsIssue(LyricsFontSettingsStorageIssue.UNSUPPORTED_SCHEMA) { session.store.read() }
        expectFontSettingsIssue(LyricsFontSettingsStorageIssue.UNSUPPORTED_SCHEMA) {
            session.store.compareAndSet(0L, LyricsFontSettings())
        }
        assertArrayEquals(bytes, file.readBytes())
        session.close()
        expectFontSettingsIssue(LyricsFontSettingsStorageIssue.UNSUPPORTED_SCHEMA) { openSettings(file).store.read() }
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test(timeout = 30000L)
    fun everyPartialAssetRoleIsRejectedWithoutDeletingEitherSavedRecord() = runBlocking {
        for (prefix in listOf("original", "translation")) {
            for (field in listOf("id", "sha256", "byte_count", "display_name", "license_acknowledgment_version")) {
                val damaged = LyricsFontSettingsCodec.encode(bothRoles()).toMutablePreferences().apply {
                    when (field) {
                        "byte_count" -> remove(longPreferencesKey("${prefix}_asset_$field"))
                        "license_acknowledgment_version" -> remove(intPreferencesKey("${prefix}_asset_$field"))
                        else -> remove(stringPreferencesKey("${prefix}_asset_$field"))
                    }
                }
                rejectAndPreserve(damaged)
            }
        }
    }

    @Test(timeout = 30000L)
    fun malformedTypesFieldsNamesHashesLicenseCountsAndNonfiniteRequestsNeverBecomeDefaults() = runBlocking {
        val faults: List<Pair<String, MutablePreferences.() -> Unit>> = listOf(
            "missing-schema" to { remove(intPreferencesKey("schema_version")) },
            "missing-revision" to { remove(longPreferencesKey("revision")) },
            "wrong-schema-type" to { this[longPreferencesKey("schema_version")] = 1L },
            "wrong-revision-type" to { this[intPreferencesKey("revision")] = 4 },
            "negative-revision" to { this[longPreferencesKey("revision")] = -1L },
            "unknown-field" to { this[stringPreferencesKey("external_uri")] = "content://fixture/unused" },
            "wrong-weight-type" to { this[intPreferencesKey("original_requested_weight")] = 400 },
            "nan-request" to { this[floatPreferencesKey("original_requested_weight")] = Float.NaN },
            "infinite-request" to { this[floatPreferencesKey("translation_requested_weight")] = Float.POSITIVE_INFINITY },
            "negative-infinite-request" to { this[floatPreferencesKey("translation_requested_weight")] = Float.NEGATIVE_INFINITY },
            "wrong-asset-id-type" to { this[longPreferencesKey("original_asset_id")] = 1L },
            "noncanonical-id" to { this[stringPreferencesKey("original_asset_id")] = "1-1-1-1-1" },
            "uppercase-id" to { this[stringPreferencesKey("original_asset_id")] = "abcdefab-1234-5678-9abc-abcdefabcdef".uppercase() },
            "giant-id" to { this[stringPreferencesKey("original_asset_id")] = "a".repeat(8193) },
            "uppercase-hash" to { this[stringPreferencesKey("translation_asset_sha256")] = "A".repeat(64) },
            "short-hash" to { this[stringPreferencesKey("translation_asset_sha256")] = "a".repeat(63) },
            "wrong-bytes-type" to { this[intPreferencesKey("original_asset_byte_count")] = 1024 },
            "empty-bytes" to { this[longPreferencesKey("original_asset_byte_count")] = 0L },
            "oversize-bytes" to { this[longPreferencesKey("original_asset_byte_count")] = 8L * 1024 * 1024 + 1L },
            "empty-name" to { this[stringPreferencesKey("translation_asset_display_name")] = "  " },
            "control-name" to { this[stringPreferencesKey("translation_asset_display_name")] = "font\nfile" },
            "giant-name" to { this[stringPreferencesKey("translation_asset_display_name")] = "F".repeat(8193) },
            "wrong-license-type" to { this[longPreferencesKey("original_asset_license_acknowledgment_version")] = 1L },
            "unknown-license" to { this[intPreferencesKey("original_asset_license_acknowledgment_version")] = 2 },
        )
        for ((name, damage) in faults) {
            val damaged = LyricsFontSettingsCodec.encode(bothRoles()).toMutablePreferences().apply(damage)
            rejectAndPreserve(damaged, name)
        }
    }

    @Test(timeout = 30000L)
    fun truncatedProtobufKeepsItsOriginalBytesAndOnlyExplicitReadsAreAttempted() = runBlocking {
        val file = settingsFile()
        val original = byteArrayOf(0x80.toByte())
        file.writeBytes(original)
        lateinit var controls: ControlledFontSettingsDataStore
        val session = openSettings(file) { ControlledFontSettingsDataStore(it).also { controls = it } }
        assertEquals(0, controls.readSubscriptions.get())
        expectFontSettingsIssue(LyricsFontSettingsStorageIssue.CORRUPT_FILE) { session.store.read() }
        assertEquals(1, controls.readSubscriptions.get())
        expectFontSettingsIssue(LyricsFontSettingsStorageIssue.CORRUPT_FILE) {
            session.store.compareAndSet(0L, LyricsFontSettings())
        }
        assertEquals(1, controls.readSubscriptions.get())
        assertArrayEquals(original, file.readBytes())
        session.close()
        expectFontSettingsIssue(LyricsFontSettingsStorageIssue.CORRUPT_FILE) { openSettings(file).store.read() }
        assertArrayEquals(original, file.readBytes())
    }

    private fun bothRoles() = LyricsFontSettings(revision = 4L,
        original = LyricsFontSelection(Fixture.asset(), 400f), translation = LyricsFontSelection(Fixture.asset(), 600f))

    private suspend fun rejectAndPreserve(stored: MutablePreferences, name: String = "partial-asset") {
        val file = settingsFile()
        writeSettingsFixture(file, stored)
        val bytes = file.readBytes()
        val session = openSettings(file)
        expectFontSettingsIssue(LyricsFontSettingsStorageIssue.INVALID_FIELDS) { session.store.read() }
        expectFontSettingsIssue(LyricsFontSettingsStorageIssue.INVALID_FIELDS) {
            session.store.compareAndSet(0L, LyricsFontSettings())
        }
        assertArrayEquals(name, bytes, file.readBytes())
        session.close()
    }
}
