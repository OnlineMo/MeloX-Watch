package io.github.onlinemo.meloxwatch.data.metadata

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesCodec
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesField
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesRecoveryReason
import io.github.onlinemo.meloxwatch.model.HelpPreferences
import io.github.onlinemo.meloxwatch.model.ScreenShape
import io.github.onlinemo.meloxwatch.model.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataLookupPreferencesTest {
    @Test fun everyPriorSchemaIgnoresInjectedActivationWithoutMutatingTheStoredRecord() {
        for (schema in 1..6) {
            val original = PreferencesCodec.encode(UserPreferences(revision = 17L, restorePosition = false,
                screenShape = ScreenShape.ROUND, bluetoothLyricsEnabled = true, metadataLookupEnabled = true))
                .toMutablePreferences().apply { this[intPreferencesKey("schema_version")] = schema }
            val before = original.asMap().toMap()
            val read = PreferencesCodec.decode(original)
            assertFalse(read.preferences.metadataLookupEnabled)
            assertEquals(schema == 6, read.preferences.bluetoothLyricsEnabled)
            assertEquals(17L, read.preferences.revision)
            assertFalse(read.preferences.restorePosition)
            assertEquals(ScreenShape.ROUND, read.preferences.screenShape)
            assertNull(read.recovery)
            assertFalse(read.isCanonical)
            assertEquals(before, original.asMap())
        }
    }

    @Test fun currentExplicitActivationRoundTripsAndIsPreservedInAnUnrelatedDraft() {
        val original = UserPreferences(revision = 23L, metadataLookupEnabled = true, bluetoothLyricsEnabled = true,
            help = HelpPreferences(showTips = false))
        val decoded = PreferencesCodec.decode(PreferencesCodec.encode(original))
        assertEquals(original, decoded.preferences)
        assertTrue(decoded.isCanonical)
        assertTrue(decoded.preferences.asDraft().copy(restorePosition = false).metadataLookupEnabled)
        assertFalse(UserPreferences().metadataLookupEnabled)
    }

    @Test fun missingOrWrongTypedActivationDefaultsOffWithoutResettingOtherPreferences() {
        for (wrongType in listOf(false, true)) {
            val original = UserPreferences(revision = 13L, bluetoothLyricsEnabled = true, help = HelpPreferences(showTips = false))
            val record = PreferencesCodec.encode(original).toMutablePreferences().apply {
                remove(booleanPreferencesKey("metadata_lookup_enabled"))
                if (wrongType) this[stringPreferencesKey("metadata_lookup_enabled")] = "true"
            }
            val decoded = PreferencesCodec.decode(record)
            assertEquals(original, decoded.preferences)
            assertEquals(setOf(PreferencesField.METADATA_LOOKUP), decoded.recovery?.fields)
            assertFalse(decoded.isCanonical)
        }
    }

    @Test fun aFutureSchemaCannotSilentlyActivateRequestsOrAcceptWrites() {
        val record = PreferencesCodec.encode(UserPreferences(metadataLookupEnabled = true)).toMutablePreferences().apply {
            this[intPreferencesKey("schema_version")] = UserPreferences.CURRENT_SCHEMA_VERSION + 1
        }
        val decoded = PreferencesCodec.decode(record)
        assertFalse(decoded.preferences.metadataLookupEnabled)
        assertFalse(decoded.canWrite)
        assertEquals(PreferencesRecoveryReason.NEWER_SCHEMA, decoded.recovery?.reason)
    }
}
