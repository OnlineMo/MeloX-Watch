package io.github.onlinemo.meloxwatch.data.backup

import io.github.onlinemo.meloxwatch.data.cue.CueReferenceCodec
import io.github.onlinemo.meloxwatch.data.metadata.MetadataAssociation
import io.github.onlinemo.meloxwatch.data.metadata.MetadataProvenance
import io.github.onlinemo.meloxwatch.data.metadata.MetadataPurpose
import io.github.onlinemo.meloxwatch.data.network.JsonBounds
import io.github.onlinemo.meloxwatch.data.network.JsonObject
import io.github.onlinemo.meloxwatch.data.network.JsonText
import io.github.onlinemo.meloxwatch.data.network.StrictJson
import io.github.onlinemo.meloxwatch.model.CueTrackReference
import io.github.onlinemo.meloxwatch.model.LocalCueReference
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MetadataBackupCodecTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = TrackKey("local", "fixture-metadata-track")
    private val hash = "4".repeat(64)
    private val source = MetadataProvenance("1024", "Fixture song", "Fixture artist", "Fixture album")

    private fun row(purpose: MetadataPurpose = MetadataPurpose.LYRICS, linked: Boolean = false) = BackupMetadataAssociations.record(
        MetadataAssociation(key, purpose, 3, stableUuid("metadata-original-asset"), hash, 25, source), BackupCell.Null, true, linked)

    @Test fun portableRowsCarryExactFieldsAndSourceWhileOmittingNativeAssetIdentity() {
        for (purpose in MetadataPurpose.entries) {
            val row = row(purpose)
            val encoded = BackupJsonCodec.record(row).toString(Charsets.UTF_8)
            assertEquals(BackupMetadataAssociations.fields(row.kind), row.values.keys)
            assertFalse(encoded.contains(stableUuid("metadata-original-asset")))
            assertFalse(encoded.contains("content://"))
            assertFalse(encoded.contains("assetId"))
            val decoded = BackupMetadataAssociations.decode(BackupJsonCodec.readRecord(encoded.toByteArray()))
            assertEquals(source, decoded.provenance)
            assertEquals(hash, decoded.sha256)
            assertEquals(25L, decoded.byteCount)
            assertNull(decoded.assetId)
            assertEquals(3L, decoded.revision)
            assertEquals(purpose, decoded.purpose)
        }
    }

    @Test fun pendingRowsAndClearTombstonesRemainDistinctWithoutAnyResourceTransport() {
        val pending = MetadataAssociation(key, MetadataPurpose.LYRICS, 7, null, hash, 25, source)
        val pendingRow = BackupMetadataAssociations.record(pending, BackupCell.Null, false)
        assertNull(pendingRow.optionalText("resourceId"))
        assertEquals(pending, BackupMetadataAssociations.decode(pendingRow))
        val clear = MetadataAssociation(key, MetadataPurpose.LYRICS, 9)
        val clearRow = BackupMetadataAssociations.record(clear, BackupCell.Null, true)
        assertEquals(clear, BackupMetadataAssociations.decode(clearRow))
        assertNull(clearRow.optionalText("sha256"))
        assertNull(clearRow.optionalText("provenance"))
        assertThrows(BackupException::class.java) {
            BackupJsonCodec.record(BackupRecord(clearRow.kind, clearRow.key,
                clearRow.values + ("resourceId" to row().values.getValue("resourceId"))))
        }
        assertThrows(BackupException::class.java) { BackupJsonCodec.record(BackupRecord(clearRow.kind, clearRow.key,
            clearRow.values + ("primaryLinked" to BackupCell.Flag(true)))) }
    }

    @Test fun strictSourceDecoderRejectsPrivateExtraFieldsWrongProviderAndChangedRights() {
        val provenance = json(source.encode().toByteArray())
        val invalid = listOf(
            JsonObject(provenance.fields + ("uri" to JsonText("fixture-private-reference"))),
            JsonObject(provenance.fields + ("provider" to JsonText("another-provider"))),
            JsonObject(provenance.fields + ("rightsNotice" to JsonText("redistribution-granted"))),
            JsonObject(provenance.fields - "album"),
        )
        for (sourceJson in invalid) assertThrows(BackupException::class.java) {
            val current = row()
            BackupJsonCodec.record(BackupRecord(current.kind, current.key, current.values +
                ("provenance" to BackupCell.Text(StrictJson.encode(sourceJson, 8192).toString(Charsets.UTF_8)))))
        }
        val current = row()
        assertThrows(BackupException::class.java) { BackupJsonCodec.record(BackupRecord(current.kind, current.key,
            current.values + ("assetId" to BackupCell.Text(stableUuid("private-extra"))))) }
    }

    @Test fun purposeLimitsTypesDigestAndResourceKindAreIndependentlyValidated() {
        val current = row()
        val invalid = listOf(
            "bytes" to BackupCell.Integer(BackupLimits.LYRICS_BYTES + 1),
            "bytes" to BackupCell.Integer(0),
            "revision" to BackupCell.Integer(-1),
            "revision" to BackupCell.Text("3"),
            "sha256" to BackupCell.Text("a".repeat(63)),
            "resourceId" to BackupCell.Text(BackupLogicalSchema.resourceId(BackupResourceKind.IMAGE, hash)),
            "provenance" to BackupCell.Null,
            "primaryLinked" to BackupCell.Text("true"),
        )
        for ((field, value) in invalid) assertThrows(BackupException::class.java) {
            BackupJsonCodec.record(BackupRecord(current.kind, current.key, current.values + (field to value)))
        }
        assertThrows(BackupException::class.java) { BackupJsonCodec.record(BackupRecord(current.kind,
            "relative/path", current.values)) }
        val image = row(MetadataPurpose.ARTWORK)
        val accepted = BackupRecord(image.kind, image.key, image.values + ("bytes" to BackupCell.Integer(BackupLimits.IMAGE_BYTES)))
        assertEquals(BackupLimits.IMAGE_BYTES, BackupMetadataAssociations.decode(accepted).byteCount)
    }

    @Test fun anExplicitPrimaryLinkRequiresTheMatchingArchivedLyricsSelection() {
        for (lyrics in listOf(null, BackupTestFixtures.lyrics(key.trackId, "2".repeat(64),
                BackupLogicalSchema.resourceId(BackupResourceKind.LYRICS, "2".repeat(64))))) {
            val directory = BackupPrivateDirectory.open(temporary.newFolder())
            SqliteBackupStage.create(directory) {}.use { stage ->
                stage.begin(BackupTestFixtures.header(BackupScope.LYRICS))
                stage.record(row(linked = true))
                lyrics?.let(stage::record)
                assertEquals(BackupIssue.INVALID_REFERENCE, assertThrows(BackupException::class.java) { stage.sealSnapshot() }.issue)
            }
        }
        val directory = BackupPrivateDirectory.open(temporary.newFolder())
        SqliteBackupStage.create(directory) {}.use { stage ->
            stage.begin(BackupTestFixtures.header(BackupScope.LYRICS))
            val linked = row(linked = true)
            stage.record(linked)
            stage.record(BackupTestFixtures.lyrics(key.trackId, hash, linked.text("resourceId")))
            stage.sealSnapshot()
            assertTrue(stage.row(BackupRecordKind.METADATA_LYRICS, key.trackId)!!.flag("primaryLinked"))
        }
    }

    @Test fun cueAssociationsRequireTheExactPortableSegmentIdentity() {
        val cue = LocalCueReference(CueTrackReference("fixture-cue-source", "fixture-disc", 1, 2,
            0, 75, 1_000_000, null, "sha256:" + "1".repeat(64), audioContentTokenHint = "sha256:" + "2".repeat(64)))
        val value = MetadataAssociation(cue.trackKey, MetadataPurpose.LYRICS, 4, null, hash, 25, source)
        val encoded = BackupMetadataAssociations.record(value, BackupCell.Text(CueReferenceCodec.encode(cue)), false)
        assertEquals(cue, encoded.portableCueReference())
        assertThrows(BackupException::class.java) { BackupJsonCodec.record(BackupRecord(encoded.kind, key.trackId, encoded.values)) }
        assertThrows(BackupException::class.java) { BackupJsonCodec.record(BackupRecord(encoded.kind, encoded.key,
            encoded.values + ("cueReference" to BackupCell.Null))) }
    }

    @Test fun conflictingCueProjectionsAreRejectedEvenForClearRowsWithoutAssets() {
        val cue = LocalCueReference(CueTrackReference("fixture-cue-source", "fixture-disc", 1, 2,
            0, 75, 1_000_000, null, "sha256:" + "1".repeat(64), audioContentTokenHint = "sha256:" + "2".repeat(64)))
        val changed = LocalCueReference(cue.identity.copy(endFrameExclusive = 150, endUsExclusive = 2_000_000))
        val directory = BackupPrivateDirectory.open(temporary.newFolder("cue-projections"))
        SqliteBackupStage.create(directory) {}.use { stage ->
            stage.begin(BackupTestFixtures.header(BackupScope.LIBRARY, BackupScope.LYRICS))
            stage.record(BackupRecord(BackupRecordKind.LIBRARY_META, "library", mapOf("shortAudioFilter" to BackupCell.Flag(false))))
            val track = BackupTestFixtures.track(cue.trackKey.trackId, 1)
            stage.record(BackupRecord(track.kind, track.key, track.values + ("cueReference" to BackupCell.Text(CueReferenceCodec.encode(cue)))))
            stage.record(BackupMetadataAssociations.record(MetadataAssociation(cue.trackKey, MetadataPurpose.LYRICS, 3),
                BackupCell.Text(CueReferenceCodec.encode(changed)), false))
            assertEquals(BackupIssue.INVALID_REFERENCE, assertThrows(BackupException::class.java) { stage.sealSnapshot() }.issue)
        }
    }

    @Test fun legacyCompletionCountsArePreciselyExpandedOnlyForSourceVersionsOneThroughEight() {
        val counts = BackupRecordKind.entries.associateWith { 0L }
        val completion = BackupCompletion(stableUuid("fixture-completion"), counts, counts, 0, 128, 0, hash)
        val bytes = withoutMetadataCounts(BackupJsonCodec.completion(completion))
        for (version in 1..8) {
            val decoded = BackupJsonCodec.readCompletion(bytes, sourceDatabaseVersion = version)
            assertEquals(counts, decoded.records)
            assertEquals(counts, decoded.chunks)
        }
        assertThrows(BackupException::class.java) { BackupJsonCodec.readCompletion(bytes, sourceDatabaseVersion = 9) }
        val current = json(BackupJsonCodec.completion(completion))
        val legacyRecords = (json(bytes)["records"] as JsonObject)
        val mixed = JsonObject(current.fields + ("records" to legacyRecords))
        assertThrows(BackupException::class.java) {
            BackupJsonCodec.readCompletion(StrictJson.encode(mixed, BackupLimits.MANIFEST_BYTES), sourceDatabaseVersion = 8)
        }
    }

    @Test fun aRealLegacyZipRetainsItsHeaderChainAndQueueWhenNewCountsAreAbsent() {
        val root = BackupPrivateDirectory.open(temporary.newFolder("archive"))
        val modernCounts = root.child("counts.zip")
        SqliteBackupStage.create(root) {}.use { stage ->
            stage.begin(BackupHeader(sourceDatabaseVersion = 8, scopes = setOf(BackupScope.QUEUE), createdEpochMs = BackupTestFixtures.EPOCH_MS))
            stage.record(BackupTestFixtures.queueMeta(1))
            stage.record(BackupTestFixtures.queueEntry(0, 1))
            stage.sealSnapshot()
            BackupArchiveWriter().write(stage, modernCounts) {}
        }
        val legacy = root.child("legacy.zip")
        ZipFile(modernCounts).use { input -> ZipOutputStream(legacy.outputStream()).use { output ->
            val entries = input.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val bytes = input.getInputStream(entry).use { it.readBytes() }
                output.putNextEntry(ZipEntry(entry.name))
                output.write(if (entry.name == BACKUP_COMPLETE_NAME) withoutMetadataCounts(bytes) else bytes)
                output.closeEntry()
            }
        } }
        val target = BackupPrivateDirectory.open(temporary.newFolder("restored"))
        SqliteBackupStage.create(target) {}.use { stage ->
            assertEquals(8, BackupArchiveReader().stage(legacy, stage) {}.sourceDatabaseVersion)
            assertEquals(1L, stage.count(BackupRecordKind.QUEUE_ENTRY))
            assertEquals(0L, stage.count(BackupRecordKind.METADATA_LYRICS))
            assertEquals(0L, stage.count(BackupRecordKind.METADATA_ARTWORK))
        }
    }

    @Test fun oldSourceVersionsCannotCarryMetadataRowsAndLibraryResourcesAreAValidSelection() {
        assertTrue(BackupSelection(setOf(BackupScope.LIBRARY, BackupScope.RESOURCES)).scopes.contains(BackupScope.LIBRARY))
        assertThrows(IllegalArgumentException::class.java) { BackupSelection(setOf(BackupScope.RESOURCES)) }
        val root = BackupPrivateDirectory.open(temporary.newFolder("old-source"))
        SqliteBackupStage.create(root) {}.use { stage ->
            stage.begin(BackupHeader(sourceDatabaseVersion = 8, scopes = setOf(BackupScope.LYRICS), createdEpochMs = 1))
            val failure = assertThrows(BackupException::class.java) { stage.record(row()) }
            assertEquals(BackupIssue.UNSUPPORTED_VERSION, failure.issue)
        }
    }

    private fun json(bytes: ByteArray): JsonObject = StrictJson.decode(bytes, JsonBounds(
        bytes = BackupLimits.MANIFEST_BYTES, depth = 16, nodes = 1024, stringChars = 8192, members = 128)) as JsonObject

    private fun withoutMetadataCounts(bytes: ByteArray): ByteArray {
        val value = json(bytes)
        val names = setOf("METADATA_LYRICS", "METADATA_ARTWORK")
        return StrictJson.encode(JsonObject(value.fields + listOf("records", "chunks").associateWith { field ->
            JsonObject((value[field] as JsonObject).fields - names)
        }), BackupLimits.MANIFEST_BYTES)
    }
}
