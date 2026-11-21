package io.github.onlinemo.meloxwatch.data.lyrics.store

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class LyricsDisplayMalformedTest : LyricsDisplayFileFixture() {
    @Test(timeout = 30000L)
    fun unknownSchemaRemainsReadOnlyAcrossSaveResetAndReopen() = runBlocking {
        val file = preferencesFile()
        val original = LyricsDisplayCodec.encode(LyricsDisplayPreferences(revision = 7L)).toMutablePreferences().apply {
            this[intPreferencesKey("schema_version")] = 2
        }
        writeDisplayFixture(file, original)
        val bytes = file.readBytes()
        val session = openDisplay(file)
        assertEquals(LyricsDisplayState.Unavailable(null, LyricsDisplayIssue.UNSUPPORTED_SCHEMA), session.unavailable())
        assertEquals(LyricsDisplayWriteResult.Failed(null, LyricsDisplayIssue.UNSUPPORTED_SCHEMA),
            session.store.save(0L, LyricsDisplayPreferences()))
        assertEquals(LyricsDisplayWriteResult.Failed(null, LyricsDisplayIssue.UNSUPPORTED_SCHEMA),
            session.store.reset(0L, LyricsDisplayResetConfirmation.CONFIRMED))
        assertArrayEquals(bytes, file.readBytes())
        session.close()
        assertEquals(LyricsDisplayState.Unavailable(null, LyricsDisplayIssue.UNSUPPORTED_SCHEMA), openDisplay(file).unavailable())
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test(timeout = 30000L)
    fun wrongTypesMissingFieldsAndNonfiniteOrOutOfRangeChoicesNeverBecomeSavedDefaults() = runBlocking {
        val faults: List<Pair<String, MutablePreferences.() -> Unit>> = listOf(
            "wrong-schema-type" to { this[longPreferencesKey("schema_version")] = 1L },
            "wrong-text-type" to { this[intPreferencesKey("text_size_sp")] = 20 },
            "wrong-bool-type" to { this[stringPreferencesKey("keep_screen_on")] = "false" },
            "missing-field" to { remove(booleanPreferencesKey("show_translation")) },
            "unexpected-field" to { this[stringPreferencesKey("external_uri")] = "content://fixture/unused" },
            "unknown-alignment" to { this[stringPreferencesKey("alignment")] = "RIGHT" },
            "giant-alignment" to { this[stringPreferencesKey("alignment")] = "X".repeat(8193) },
            "negative-revision" to { this[longPreferencesKey("revision")] = -1L },
            "nan-text" to { this[floatPreferencesKey("text_size_sp")] = Float.NaN },
            "infinite-spacing" to { this[floatPreferencesKey("block_spacing_dp")] = Float.POSITIVE_INFINITY },
            "negative-infinite-anchor" to { this[floatPreferencesKey("anchor_fraction")] = Float.NEGATIVE_INFINITY },
            "small-text" to { this[floatPreferencesKey("text_size_sp")] = 13f },
            "large-text" to { this[floatPreferencesKey("text_size_sp")] = 33f },
            "small-spacing" to { this[floatPreferencesKey("block_spacing_dp")] = 3f },
            "large-spacing" to { this[floatPreferencesKey("block_spacing_dp")] = 25f },
            "small-anchor" to { this[floatPreferencesKey("anchor_fraction")] = 0.24f },
            "large-anchor" to { this[floatPreferencesKey("anchor_fraction")] = 0.76f },
            "negative-margin" to { this[floatPreferencesKey("horizontal_margin_dp")] = -1f },
            "large-margin" to { this[floatPreferencesKey("horizontal_margin_dp")] = 25f },
        )
        for ((name, damage) in faults) {
            val file = preferencesFile()
            val damaged = LyricsDisplayCodec.encode(LyricsDisplayPreferences(revision = 4L)).toMutablePreferences().apply(damage)
            writeDisplayFixture(file, damaged)
            val bytes = file.readBytes()
            val session = openDisplay(file)
            assertEquals(name, LyricsDisplayState.Unavailable(null, LyricsDisplayIssue.INVALID_FIELDS), session.unavailable())
            assertEquals(name, LyricsDisplayWriteResult.Failed(null, LyricsDisplayIssue.INVALID_FIELDS),
                session.store.save(0L, LyricsDisplayPreferences()))
            assertEquals(name, LyricsDisplayWriteResult.Failed(null, LyricsDisplayIssue.INVALID_FIELDS),
                session.store.reset(0L, LyricsDisplayResetConfirmation.CONFIRMED))
            assertArrayEquals(name, bytes, file.readBytes())
            session.close()
        }
    }

    @Test(timeout = 30000L)
    fun truncatedProtobufIsPreservedAndReadRetryIsExplicit() = runBlocking {
        val file = preferencesFile()
        val bytes = byteArrayOf(0x80.toByte())
        file.writeBytes(bytes)
        lateinit var reads: CountingDisplayReadsDataStore
        val session = openDisplay(file) { delegate -> CountingDisplayReadsDataStore(delegate).also { reads = it } }
        assertEquals(LyricsDisplayState.Unavailable(null, LyricsDisplayIssue.CORRUPT_FILE), session.unavailable())
        assertNull(withTimeoutOrNull(100L) { reads.subscriptions.first { it > 1 } })
        assertEquals(LyricsDisplayWriteResult.Failed(null, LyricsDisplayIssue.CORRUPT_FILE),
            session.store.save(0L, LyricsDisplayPreferences()))
        assertEquals(LyricsDisplayWriteResult.Failed(null, LyricsDisplayIssue.CORRUPT_FILE),
            session.store.reset(0L, LyricsDisplayResetConfirmation.CONFIRMED))
        assertArrayEquals(bytes, file.readBytes())
        session.store.retryRead()
        withTimeout(10000L) { reads.subscriptions.first { it >= 2 } }
        assertArrayEquals(bytes, file.readBytes())
        assertTrue(session.store.state.value is LyricsDisplayState.Unavailable)
        session.close()
        assertEquals(LyricsDisplayIssue.CORRUPT_FILE, openDisplay(file).unavailable().issue)
        assertArrayEquals(bytes, file.readBytes())
    }
}
