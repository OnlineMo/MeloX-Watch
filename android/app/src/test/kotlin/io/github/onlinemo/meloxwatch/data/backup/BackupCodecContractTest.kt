package io.github.onlinemo.meloxwatch.data.backup

import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontAsset
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontLimits
import io.github.onlinemo.meloxwatch.model.HelpPreferences
import io.github.onlinemo.meloxwatch.model.PrivacyNoticeChoice
import io.github.onlinemo.meloxwatch.model.UserPreferences
import io.github.onlinemo.meloxwatch.playback.dsp.DspPreset
import io.github.onlinemo.meloxwatch.playback.dsp.DspSettings
import io.github.onlinemo.meloxwatch.playback.dsp.DspStored
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCodecContractTest {
    @Test fun allSevenNativePreferencesHavePortableCanonicalRoundTrips() {
        val player = UserPreferences(help = HelpPreferences(onboardingCompletedVersion = 7, onboardingDismissedVersion = 3,
            showTips = false, privacyNoticeVersion = 5, privacyNoticeChoice = PrivacyNoticeChoice.READ, onboardingStep = 2))
        val preset = DspPreset("fixture_curve", "Fixture curve", listOf(10, 20, 30, 40, 50, 0, -10, -20, -30, -40))
        val dsp = DspStored(settings = DspSettings(equalizerEnabled = true, bandGainTenthsDb = preset.bandGainTenthsDb,
            selectedPresetId = preset.id, leftPercent = 75, rightPercent = 90), presets = listOf(preset))
        val rows = BackupTestFixtures.preferences().mapValues { it.value.record }.toMutableMap()
        rows["player"] = BackupPreferencesCodec.player(player)
        rows["dsp"] = BackupPreferencesCodec.dsp(dsp)
        assertEquals(BackupPreferencesCodec.KEYS, rows.keys)
        for ((key, row) in rows) {
            val decoded = BackupJsonCodec.readRecord(BackupJsonCodec.record(row))
            assertTrue(row.sameContent(decoded))
            assertTrue(decoded.sameContent(canonicalConfigPreference(decoded)))
            assertEquals(key, decoded.key)
        }
        assertEquals(player.help, BackupPreferencesCodec.player(rows.getValue("player")).help)
        assertEquals(dsp, BackupPreferencesCodec.dsp(rows.getValue("dsp")))
    }

    @Test fun helpReadRecordIsPreservedWithoutAddingPermissionOrNetworkState() {
        val record = BackupPreferencesCodec.player(UserPreferences(help = HelpPreferences(onboardingCompletedVersion = 1,
            onboardingDismissedVersion = 0, showTips = true, privacyNoticeVersion = 1, privacyNoticeChoice = PrivacyNoticeChoice.LATER)))
        assertEquals(PrivacyNoticeChoice.LATER, BackupPreferencesCodec.player(record).help.privacyNoticeChoice)
        assertFalse(record.values.keys.any { it in setOf("accounts", "token", "pairingKey", "metingEndpoint", "networkEnabled") })
        val invalid = BackupRecord(record.kind, record.key, record.values + ("privacyNoticeVersion" to BackupCell.Integer(0)))
        expectBackupIssue(BackupIssue.INVALID_INPUT) { BackupPreferencesCodec.validate(invalid) }
        val oversized = BackupRecord(record.kind, record.key, record.values + ("onboardingCompletedVersion" to BackupCell.Integer(1_000_001)))
        expectBackupIssue(BackupIssue.INVALID_INPUT) { BackupPreferencesCodec.validate(oversized) }
        expectBackupIssue(BackupIssue.INVALID_INPUT) {
            BackupPreferencesCodec.validate(BackupRecord(record.kind, record.key, record.values + ("onboardingStep" to BackupCell.Integer(4))))
        }
    }

    @Test fun archiveHeaderRejectsUnknownVersionsEncryptionAudioAndRepeatedScopes() {
        val header = BackupJsonCodec.header(BackupTestFixtures.header(BackupScope.HISTORY)).toString(Charsets.UTF_8)
        for (changed in listOf(header.replace("\"version\":1", "\"version\":2"),
                header.replace("\"encryption\":\"none\"", "\"encryption\":\"unknown\""),
                header.replace("\"audioIncluded\":false", "\"audioIncluded\":true"))) {
            expectBackupIssue(BackupIssue.UNSUPPORTED_VERSION) { BackupJsonCodec.readHeader(changed.toByteArray()) }
        }
        expectBackupIssue(BackupIssue.CORRUPT_ARCHIVE) {
            BackupJsonCodec.readHeader(header.replace("[\"HISTORY\"]", "[\"HISTORY\",\"HISTORY\"]").toByteArray())
        }
    }

    @Test fun credentialsUnknownFieldsDuplicateJsonKeysAndTrailingBytesAreRejected() {
        val row = BackupTestFixtures.preferences().getValue("player").record
        expectBackupIssue(BackupIssue.INVALID_INPUT) {
            BackupPreferencesCodec.validate(BackupRecord(row.kind, row.key, row.values + ("token" to BackupCell.Text("synthetic-token"))))
        }
        val encoded = BackupJsonCodec.record(row).toString(Charsets.UTF_8)
        expectBackupIssue(BackupIssue.CORRUPT_ARCHIVE) {
            BackupJsonCodec.readRecord(encoded.replaceFirst("\"kind\":", "\"kind\":\"PREFERENCE\",\"kind\":").toByteArray())
        }
        expectBackupIssue(BackupIssue.CORRUPT_ARCHIVE) { BackupJsonCodec.readRecord((encoded + "{}").toByteArray()) }
    }

    @Test fun lyricContentSurvivesMissingNonportableHintsAndUsesAnIndependentResourceReference() {
        val sha256 = "b".repeat(64)
        val resource = BackupLogicalSchema.resourceId(BackupResourceKind.LYRICS, sha256)
        val row = BackupTestFixtures.lyrics("fixture-track-a", sha256, resource)
        val stripped = BackupRecord(row.kind, row.key, row.values + mapOf("primaryUriHint" to BackupCell.Null,
            "primaryDocumentIdHint" to BackupCell.Null, "primarySourceIdHint" to BackupCell.Null))
        val restored = BackupJsonCodec.readRecord(BackupJsonCodec.record(stripped))
        assertEquals(sha256, restored.text("primarySha256"))
        assertEquals("UTF8", restored.text("primaryEncoding"))
        assertEquals(resource, restored.text("primaryResourceId"))
        assertNull(restored.optionalText("primaryUriHint"))
        expectBackupIssue(BackupIssue.INVALID_INPUT) {
            BackupLogicalSchema.validate(BackupRecord(row.kind, row.key, row.values + ("primaryEncoding" to BackupCell.Null)))
        }
        assertNull(BackupLogicalSchema.portableHint("uri:content://fixture.owned-lyrics/opaque-object"))
    }

    @Test fun absolutePathsNetworkUrisAndQueryCredentialsCannotBecomeArchiveHints() {
        for (raw in listOf("/storage/emulated/0/private", "C:\\private\\song.mp3", "https://example.invalid/song")) {
            assertNull(BackupLogicalSchema.portableHint(raw))
            assertNull(BackupLogicalSchema.portableUri(raw))
        }
        assertNull(BackupLogicalSchema.portableUri("content://fixture/document/track?credential=synthetic"))
        assertNull(BackupLogicalSchema.portableRelative("music/../private.mp3"))
        assertEquals("music/track.mp3", BackupLogicalSchema.portableRelative("music/track.mp3"))
    }

    @Test fun nativeResourceJournalRejectsWrongTypesOverflowUnknownKeysAndCrossOwnerPlans() {
        val asset = LyricsFontAsset(stableUuid("fixture-font"), "c".repeat(64), 1024L, "Fixture font", LyricsFontLimits.LICENSE_ACKNOWLEDGMENT_VERSION)
        val values = mapOf("id" to BackupCell.Text(asset.id), "sha256" to BackupCell.Text(asset.sha256),
            "bytes" to BackupCell.Integer(asset.byteCount), "displayName" to BackupCell.Text(asset.displayName),
            "license" to BackupCell.Integer(asset.licenseAcknowledgmentVersion.toLong()))
        val id = BackupLogicalSchema.resourceId(BackupResourceKind.FONT, asset.sha256)
        BackupResourcePlan(BackupResourceOwner.FONT, stableUuid("fixture-original-font"), id, values)
        for (invalid in listOf(values + ("bytes" to BackupCell.Text("1024")),
                values + ("license" to BackupCell.Integer(Long.MAX_VALUE)), values + ("path" to BackupCell.Text("synthetic/path")))) {
            expectBackupIssue(BackupIssue.RECOVERY_REQUIRED) { BackupResourcePlan(BackupResourceOwner.FONT, asset.id, id, invalid) }
        }
        expectBackupIssue(BackupIssue.RECOVERY_REQUIRED) { BackupResourcePlan(BackupResourceOwner.BACKGROUND, asset.id, id, values) }
    }

    @Test fun nativeLyricsRecoveryPlanRequiresAnOwnedOpaqueUriAndExactDocumentIdentity() {
        val uri = "content://fixture.owned-lyrics/${stableUuid("fixture-lyric-object")}"
        val hash = "d".repeat(64)
        val id = BackupLogicalSchema.resourceId(BackupResourceKind.LYRICS, hash)
        val values = mapOf("uri" to BackupCell.Text(uri), "sourceId" to BackupCell.Text("lyrics:${stableUuid("fixture-source")}"),
            "documentId" to BackupCell.Text("uri:$uri"), "sha256" to BackupCell.Text(hash),
            "bytes" to BackupCell.Integer(64L), "added" to BackupCell.Flag(true))
        BackupResourcePlan(BackupResourceOwner.LYRICS, id, id, values)
        expectBackupIssue(BackupIssue.RECOVERY_REQUIRED) {
            BackupResourcePlan(BackupResourceOwner.LYRICS, id, id, values + ("uri" to BackupCell.Text("content://external/document/file")))
        }
        expectBackupIssue(BackupIssue.RECOVERY_REQUIRED) {
            BackupResourcePlan(BackupResourceOwner.LYRICS, id, id, values + ("documentId" to BackupCell.Text("uri:wrong")))
        }
    }
}

internal fun expectBackupIssue(issue: BackupIssue, action: () -> Unit): BackupException {
    try { action() } catch (failure: BackupException) { assertEquals(issue, failure.issue); return failure }
    throw AssertionError("Expected ${issue.name}")
}
