package io.github.onlinemo.meloxwatch.data.library

import io.github.onlinemo.meloxwatch.data.scan.MetadataOutcome
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class LibraryMetadataJsonTest {
    private val metadata = LocalAudioMetadata(title = "Title", artist = "Artist", artists = listOf("Artist", "Other"),
        durationMs = 61_000L, discNumber = 0, trackNumber = 1, parserVersion = 2,
        codecParameters = LocalCodecParameters("APE", "Monkey's Audio", 44_100, 16, 2, 44_100, 16, 3990, 1000))

    @Test fun validOldNullableFieldsAndTypedCodecRoundtripThroughActualMetadataAndOutcomeEntrances() {
        val raw = LibraryCodec.metadata(metadata)
        assertEquals(metadata, LibraryCodec.metadata(raw))
        val legacy = JSONObject(raw).apply { remove("codecParameters") }.toString()
        assertEquals(metadata.copy(codecParameters = null), LibraryCodec.metadata(legacy))
        val outcome = MetadataOutcome("audio", metadata, verifiedContentToken = "sha256:" + "a".repeat(64))
        assertEquals(outcome, LibraryCodec.outcome(LibraryCodec.outcome(outcome)))
        val failure = MetadataOutcome("audio", issue = io.github.onlinemo.meloxwatch.data.local.SourceReadIssue.METADATA_UNREADABLE)
        assertEquals(failure, LibraryCodec.outcome(LibraryCodec.outcome(failure)))
    }

    @Test fun rawNestedCodecDuplicateKeysAndNumberCoercionsAreRejectedBeforeJSONObjectCanNormalizeThem() {
        val raw = LibraryCodec.metadata(metadata)
        for (replacement in listOf("\"2\"", "2.0", "2e0", "true", "null", "2,\"channels\":1")) {
            val malformed = raw.replace("\"channels\":2", "\"channels\":" + replacement)
            assertTrue(malformed != raw)
            rejected { LibraryCodec.metadata(malformed) }
            val envelope = "{\"schema\":1,\"objectId\":\"audio\",\"metadata\":" + JSONObject.quote(malformed) + "}"
            rejected { LibraryCodec.outcome(envelope) }
        }
    }

    @Test fun outcomeRejectsObjectMetadataDuplicateIdsAndNonIntegralSchemaWithoutTypeConversion() {
        val raw = LibraryCodec.metadata(metadata)
        rejected { LibraryCodec.outcome("{\"schema\":1,\"objectId\":\"audio\",\"metadata\":" + raw + "}") }
        rejected { LibraryCodec.outcome("{\"schema\":1,\"objectId\":\"audio\",\"objectId\":\"other\"}") }
        rejected { LibraryCodec.outcome("{\"schema\":1.0,\"objectId\":\"audio\"}") }
        rejected { LibraryCodec.outcome("{\"schema\":1,\"objectId\":2}") }
        rejected { LibraryCodec.outcome("{\"schema\":1,\"objectId\":\"audio\",\"verifiedContentToken\":true}") }
    }

    @Test fun rawInputChecksArtistCountSizeAndInvalidUtf16BeforeCreatingTheMetadataModel() {
        val raw = LibraryCodec.metadata(metadata)
        val manyArtists = JSONObject(raw).apply { put("artists", JSONArray(List(129) { "Artist" })) }.toString()
        rejected { LibraryCodec.metadata(manyArtists) }
        rejected { LibraryCodec.metadata(raw.replace("Title", "\ud800")) }
        rejected { LibraryCodec.metadata(raw + " ".repeat(LibraryMetadataJson.METADATA_BYTES)) }
        rejected { LibraryCodec.metadata(raw.replace("\"durationMs\":61000", "\"durationMs\":61000.0")) }
    }

    @Test fun outcomeKeepsTheExistingProviderIdentityLimitSeparateFromTagLengths() {
        for (length in listOf(4097, io.github.onlinemo.meloxwatch.data.local.LocalInputLimits.URI_CHARACTERS)) {
            val expected = MetadataOutcome("i".repeat(length), metadata)
            assertEquals(expected, LibraryCodec.outcome(LibraryCodec.outcome(expected)))
        }
        val excessive = MetadataOutcome("i".repeat(io.github.onlinemo.meloxwatch.data.local.LocalInputLimits.URI_CHARACTERS + 1), metadata)
        rejected { LibraryCodec.outcome(LibraryCodec.outcome(excessive)) }
    }

    private fun rejected(action: () -> Any?) {
        try {
            action()
            throw AssertionError("Invalid raw library JSON was accepted")
        } catch (_: JSONException) {
        } catch (failure: LibraryException) {
            assertEquals(LibraryIssue.RECOVERY_REQUIRED, failure.issue)
        }
    }
}
