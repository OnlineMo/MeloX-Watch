package io.github.onlinemo.meloxwatch.data.backup

import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSettings
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDisplayPreferences
import io.github.onlinemo.meloxwatch.data.themes.AppearanceOptions
import io.github.onlinemo.meloxwatch.data.themes.BuiltinTheme
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundOptions
import io.github.onlinemo.meloxwatch.data.themes.ThemeChoice
import io.github.onlinemo.meloxwatch.model.UserPreferences
import io.github.onlinemo.meloxwatch.playback.dsp.DspStored

/** Synthetic portable rows only; no test fixture is captured from a user's device or library. */
internal object BackupTestFixtures {
    const val EPOCH_MS = 1_800_000_000_000L
    val ARCHIVE_SHA256 = "a".repeat(64)

    fun preferences(): Map<String, BackupConfigVersion> = listOf(
        BackupPreferencesCodec.player(UserPreferences()),
        BackupPreferencesCodec.appearance(AppearanceOptions()),
        BackupPreferencesCodec.display(LyricsDisplayPreferences()),
        BackupPreferencesCodec.fonts(LyricsFontSettings()),
        BackupPreferencesCodec.background(PlaybackBackgroundOptions()),
        BackupPreferencesCodec.theme(ThemeChoice.Builtin(BuiltinTheme.DARK), ThemeChoice.Builtin(BuiltinTheme.DARK)),
        BackupPreferencesCodec.dsp(DspStored()),
    ).associate { it.key to BackupConfigVersion(0L, it) }

    fun header(vararg scopes: BackupScope) = BackupHeader(scopes = scopes.toSet(), createdEpochMs = EPOCH_MS)
    fun queueId(ordinal: Long): String = stableUuid("fixture-queue-entry", ordinal.toString())
    fun queueMeta(count: Long, current: Long = 0L): BackupRecord = BackupRecord(BackupRecordKind.QUEUE_META, "queue", mapOf(
        "totalCount" to BackupCell.Integer(count), "shuffle" to BackupCell.Flag(true),
        "repeatMode" to BackupCell.Text("LIST"), "cycleLimit" to BackupCell.Integer(7L),
        "currentEntryId" to if (count == 0L) BackupCell.Null else BackupCell.Text(queueId(current)),
        "positionMs" to BackupCell.Integer(if (count == 0L) 0L else 1500L),
        "completedCycles" to BackupCell.Integer(if (count == 0L) 0L else 3L),
    ))
    fun queueEntry(ordinal: Long, total: Long, trackId: String = "fixture-track-${ordinal % 17L}") =
        BackupRecord(BackupRecordKind.QUEUE_ENTRY, queueId(ordinal), mapOf(
            "providerId" to BackupCell.Text("local"), "trackId" to BackupCell.Text(trackId),
            "originalOrder" to BackupCell.Integer(ordinal), "playbackOrder" to BackupCell.Integer(total - ordinal - 1L),
            "displayName" to BackupCell.Text("Fixture ${ordinal % 17L}"), "durationMs" to BackupCell.Integer(180_000L),
            "locationIdHint" to BackupCell.Null, "uriHint" to BackupCell.Null,
        ))
    fun historyMeta(playCount: Long = 0L, listeningMs: Long = 0L) = BackupRecord(BackupRecordKind.HISTORY_META, "history", mapOf(
        "totalPlayCount" to BackupCell.Integer(playCount), "totalListeningMs" to BackupCell.Integer(listeningMs),
    ))
    fun historyEntry(ordinal: Long, trackId: String = "fixture-track-a") = BackupRecord(BackupRecordKind.HISTORY_ENTRY,
        backupStableId("fixture-history", ordinal.toString()), mapOf(
            "ordinal" to BackupCell.Integer(ordinal), "providerId" to BackupCell.Text("local"), "trackId" to BackupCell.Text(trackId),
            "displayName" to BackupCell.Text("Fixture history"), "firstProgressEpochMs" to BackupCell.Integer(EPOCH_MS),
            "firstProgressZoneId" to BackupCell.Text("UTC"), "lastProgressEpochMs" to BackupCell.Integer(EPOCH_MS + 45_000L),
            "listeningMs" to BackupCell.Integer(45_000L), "knownDurationMs" to BackupCell.Integer(180_000L),
        ))
    fun trackTotal(trackId: String = "fixture-track-a", count: Long = 2L, ms: Long = 45_000L) = BackupRecord(
        BackupRecordKind.TRACK_TOTAL, backupStableId("local", trackId), mapOf(
            "providerId" to BackupCell.Text("local"), "trackId" to BackupCell.Text(trackId), "playCount" to BackupCell.Integer(count),
            "listeningMs" to BackupCell.Integer(ms), "lastProgressEpochMs" to BackupCell.Integer(EPOCH_MS + ms),
            "displayName" to BackupCell.Text("Fixture total"),
        ))
    fun dayTotal(count: Long = 2L, ms: Long = 45_000L) = BackupRecord(BackupRecordKind.DAY_TOTAL,
        backupStableId("2027-01-15", "UTC"), mapOf("localDate" to BackupCell.Text("2027-01-15"), "zoneId" to BackupCell.Text("UTC"),
            "playCount" to BackupCell.Integer(count), "listeningMs" to BackupCell.Integer(ms)))
    fun track(id: String, sequence: Long) = BackupRecord(BackupRecordKind.TRACK, id, mapOf(
        "providerId" to BackupCell.Text("local"), "member" to BackupCell.Flag(true),
        "firstAddedEpochMs" to BackupCell.Integer(EPOCH_MS), "firstAddedSequence" to BackupCell.Integer(sequence),
        "filename" to BackupCell.Text("$id.mp3"), "title" to BackupCell.Text("Fixture $id"),
        "artist" to BackupCell.Text("Fixture artist"), "artists" to BackupCell.Text("[\"Fixture artist\"]"),
        "album" to BackupCell.Text("Fixture album"), "albumArtist" to BackupCell.Null,
        "durationMs" to BackupCell.Integer(180_000L), "discNumber" to BackupCell.Integer(1L), "trackNumber" to BackupCell.Integer(sequence),
        "compilation" to BackupCell.Flag(false), "mimeType" to BackupCell.Text("audio/mpeg"), "parserVersion" to BackupCell.Integer(1L),
        "metadataIssue" to BackupCell.Null, "codecParameters" to BackupCell.Null,
    ))
    fun playlistHead(id: String, count: Long, favorites: Boolean = false) = BackupRecord(
        if (favorites) BackupRecordKind.FAVORITES_HEAD else BackupRecordKind.PLAYLIST_HEAD, id, mapOf(
            "name" to BackupCell.Text(if (favorites) "Favorites" else "Fixture playlist"),
            "kind" to BackupCell.Text(if (favorites) "FAVORITES" else "ORDINARY"),
            "memberCount" to BackupCell.Integer(count), "createdEpochMs" to BackupCell.Integer(EPOCH_MS),
            "updatedEpochMs" to BackupCell.Integer(EPOCH_MS), "bindingSourceId" to BackupCell.Null,
            "bindingNodeId" to BackupCell.Null, "bindingSubtree" to BackupCell.Null,
        ))
    fun playlistMember(id: String, ordinal: Long, trackId: String, favorites: Boolean = false) = BackupRecord(
        if (favorites) BackupRecordKind.FAVORITES_MEMBER else BackupRecordKind.PLAYLIST_MEMBER,
        backupStableId(id, ordinal.toString()), mapOf(
            "playlistId" to BackupCell.Text(id), "memberId" to BackupCell.Text(stableUuid(id, ordinal.toString())),
            "ordinal" to BackupCell.Integer(ordinal), "providerId" to BackupCell.Text("local"), "trackId" to BackupCell.Text(trackId),
            "placeholderId" to BackupCell.Null, "issue" to BackupCell.Null, "titleHint" to BackupCell.Text("Fixture $trackId"),
            "artistHint" to BackupCell.Null, "albumHint" to BackupCell.Null, "relativePathHint" to BackupCell.Null,
            "descriptionHint" to BackupCell.Null, "durationMs" to BackupCell.Integer(180_000L),
        ))
    fun lyrics(id: String, sha256: String, resourceId: String?) = BackupRecord(BackupRecordKind.LYRICS_TRACK, id,
        mapOf("userDelayMs" to BackupCell.Integer(100L)) + listOf("primary", "translation").flatMap { prefix ->
            val primary = prefix == "primary"
            listOf(
                prefix + "DocumentIdHint" to BackupCell.Null,
                prefix + "SourceIdHint" to if (primary) BackupCell.Text("lyrics:${stableUuid(id, "source")}") else BackupCell.Null,
                prefix + "UriHint" to if (primary) BackupCell.Text("content://fixture.owned-lyrics/${stableUuid(id)}") else BackupCell.Null,
                prefix + "Encoding" to if (primary) BackupCell.Text("UTF8") else BackupCell.Null,
                prefix + "Sha256" to if (primary) BackupCell.Text(sha256) else BackupCell.Null,
                prefix + "ResourceId" to if (primary && resourceId != null) BackupCell.Text(resourceId) else BackupCell.Null,
            )
        })
}
