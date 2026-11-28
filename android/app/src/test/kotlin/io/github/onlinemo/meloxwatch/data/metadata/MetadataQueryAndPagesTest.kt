package io.github.onlinemo.meloxwatch.data.metadata

import io.github.onlinemo.meloxwatch.data.lyrics.LocalLyricsParser
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsLimits
import io.github.onlinemo.meloxwatch.model.TrackKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MetadataQueryAndPagesTest {
    @Test fun onlyCheckedFieldsFormTheRequestAndAnUncheckedPrivateFieldIsIgnored() {
        val title = MetadataSearchQuery.reviewed("  Fixture song  ", "content://fixture.invalid/private", true, false)
        assertEquals("Fixture song", title.words)
        assertEquals("Fixture song", title.title)
        assertEquals(null, title.artist)
        val artist = MetadataSearchQuery.reviewed("Unused", "Fixture artist", false, true)
        assertEquals("Fixture artist", artist.words)
        assertEquals(null, artist.title)
        assertEquals("Fixture artist", artist.artist)
        assertFalse(title.toString().contains("Fixture song"))
    }

    @Test fun pathsAddressesCredentialsAndIdentifiersCannotBeSelectedForTransmission() {
        // Addresses and identifiers here are synthetic documentation fixtures, never local evidence.
        val unsafe = listOf("content://fixture.invalid/audio", "file:/fixture/audio", "Z:\\fixture\\song",
            "/fixture/audio.mp3", "root/folder/audio.mp3", "https://example.invalid/secret",
            "203.0.113.7", "2001:db8::7", "::1", "::", "::ffff:203.0.113.7", "fe80::7%fixture", "fixture@example.invalid",
            "00000000-0000-0000-0000-000000000007", "a".repeat(64), "Fixture\u0000song", "Fixture\u007fsong")
        for (value in unsafe) assertRejected { MetadataSearchQuery.reviewed(value, "", true, false) }
        assertRejected { MetadataSearchQuery.reviewed("Fixture", "", false, false) }
        assertRejected { MetadataSearchQuery.reviewed("a".repeat(256), "b".repeat(257), true, true) }
    }

    @Test fun aLongUnicodePreviewHasBoundedPagesWithEveryLineAndSurrogatePreserved(): Unit = runTest {
        val text = "[00:01]" + "A".repeat(2_047) + "\uD83C\uDFB5" + "B".repeat(2_049) +
            "\n[00:02]尾行\uD83C\uDFB5\n\nLast"
        val document = LocalLyricsParser(LyricsLimits(), StandardTestDispatcher(testScheduler)).parse(text.toByteArray())
        val pages = MetadataLyricsPages.create(document, 131_072L)
        val displayed = (0 until pages.count).map(pages::text)
        assertTrue(displayed.size > 2)
        assertTrue(displayed.all { it.length <= 2_048 })
        assertTrue(displayed.all { it.isEmpty() || !Character.isHighSurrogate(it.last()) })
        assertTrue(displayed.all { it.isEmpty() || !Character.isLowSurrogate(it.first()) })
        assertEquals(document.sourceLines.joinToString("\n") { it.text }, displayed.joinToString(""))
        assertTrue(pages.accountedBytes <= 131_072L)
    }

    @Test fun exactPageEdgesAndVirtualNewlinesDoNotCreateOversizedOrEmptyPages(): Unit = runTest {
        for (text in listOf("A".repeat(2_048) + "\nB", "A".repeat(2_047) + "\nB",
            "A".repeat(2_048) + "\n", "\n\nA", "A\n\n\nB")) {
            val document = LocalLyricsParser(LyricsLimits(), StandardTestDispatcher(testScheduler)).parse(text.toByteArray())
            val pages = MetadataLyricsPages.create(document, 131_072L)
            val displayed = (0 until pages.count).map(pages::text)
            assertTrue(displayed.all { it.isNotEmpty() && it.length <= 2_048 })
            assertEquals(document.sourceLines.joinToString("\n") { it.text }, displayed.joinToString(""))
        }
    }

    @Test fun emptyLyricsAndAnInsufficientIndexBudgetCannotBecomeAConfirmablePreview(): Unit = runTest {
        val parser = LocalLyricsParser(LyricsLimits(), StandardTestDispatcher(testScheduler))
        try {
            MetadataLyricsPages.create(parser.parse("[ar:Fixture]".toByteArray()), 131_072L)
            throw AssertionError("Metadata-only text became a lyric preview")
        } catch (failure: MetadataLookupException) { assertEquals(MetadataLookupIssue.NO_LYRICS, failure.issue) }
        try {
            MetadataLyricsPages.create(parser.parse("Fixture".toByteArray()), 1_024L)
            throw AssertionError("The page bypassed its reserved memory")
        } catch (failure: MetadataStoreException) { assertEquals(MetadataStoreIssue.LOW_MEMORY, failure.issue) }
    }

    @Test fun provenanceIsStrictAndClearedAndResourceMissingAssociationsHaveDifferentStates() {
        val source = MetadataProvenance("201", "Fixture", "Artist", "Album")
        val encoded = source.encode()
        assertEquals(source, MetadataProvenance.decode(encoded))
        assertTrue(encoded.contains(MetadataProvenance.RIGHTS_NOTICE))
        assertFalse(encoded.contains("http"))
        assertRejected { MetadataProvenance.decode(encoded.dropLast(1) + ",\"url\":\"https://example.invalid\"}") }
        assertRejected { MetadataProvenance("201", "Fixture\u007f", null, null) }
        val key = TrackKey("local", "metadata-fixture-track")
        val cleared = MetadataAssociation(key, MetadataPurpose.ARTWORK, revision = 2L)
        val pending = MetadataAssociation(key, MetadataPurpose.ARTWORK, revision = 3L,
            sha256 = "b".repeat(64), byteCount = 12L, provenance = source)
        assertEquals(null, cleared.sha256)
        assertEquals(null, pending.assetId)
        assertEquals(source, pending.provenance)
        assertRejected { MetadataAssociation(key, MetadataPurpose.ARTWORK, assetId = "../../fixture", sha256 = "b".repeat(64),
            byteCount = 12L, provenance = source) }
    }

    private fun assertRejected(action: () -> Unit) {
        try { action() } catch (_: MetadataStoreException) { return }
        catch (_: IllegalArgumentException) { return }
        throw AssertionError("Unsafe metadata input was accepted")
    }
}
