package io.github.onlinemo.meloxwatch.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.onlinemo.meloxwatch.data.preferences.DataStoreUserPreferencesRepository
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesFailure
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesField
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesRecoveryReason
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesState
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesWriteResult
import io.github.onlinemo.meloxwatch.data.preferences.ResetConfirmation
import io.github.onlinemo.meloxwatch.data.preferences.createUserPreferencesDataStore
import io.github.onlinemo.meloxwatch.model.ArtworkReadPath
import io.github.onlinemo.meloxwatch.model.HelpPreferenceLimits
import io.github.onlinemo.meloxwatch.model.HelpPreferences
import io.github.onlinemo.meloxwatch.model.PrivacyNoticeChoice
import io.github.onlinemo.meloxwatch.model.LibraryLayout
import io.github.onlinemo.meloxwatch.model.LibraryListContext
import io.github.onlinemo.meloxwatch.model.LibrarySort
import io.github.onlinemo.meloxwatch.model.LibrarySortField
import io.github.onlinemo.meloxwatch.model.PlaybackBackendKind
import io.github.onlinemo.meloxwatch.model.PlaybackControlsPreferences
import io.github.onlinemo.meloxwatch.model.ProgressGestureMode
import io.github.onlinemo.meloxwatch.model.RotaryAction
import io.github.onlinemo.meloxwatch.model.RotaryOrientation
import io.github.onlinemo.meloxwatch.model.ScreenShape
import io.github.onlinemo.meloxwatch.model.SongTapPolicy
import io.github.onlinemo.meloxwatch.model.UserPreferences
import io.github.onlinemo.meloxwatch.model.UserPreferencesDraft
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UserPreferencesRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()
    private val sessions = mutableListOf<StoreSession>()

    @Test(timeout = 30000L)
    fun schemaFivePreservesHelpAndDefaultsBluetoothOffUntilAnExplicitSave() = runBlocking {
        val file = preferencesFile()
        writeFixture(file, completeFixture(5, 11L).toMutablePreferences().apply {
            this[booleanPreferencesKey("help_show_tips")] = false
            this[intPreferencesKey("guide_step")] = 2
            this[intPreferencesKey("privacy_notice_version")] = HelpPreferenceLimits.PRIVACY_NOTICE_VERSION
            this[stringPreferencesKey("privacy_notice_choice")] = PrivacyNoticeChoice.LATER.name
        })
        val bytes = file.readBytes()
        val first = open(file)
        val before = first.ready().preferences
        assertFalse(before.bluetoothLyricsEnabled)
        assertEquals(2, before.help.onboardingStep)
        assertFalse(before.help.showTips)
        assertEquals(PrivacyNoticeChoice.LATER, before.help.privacyNoticeChoice)
        assertArrayEquals(bytes, file.readBytes())
        val saved = (first.repository.save(before.revision,
            before.asDraft().copy(bluetoothLyricsEnabled = true)) as PreferencesWriteResult.Saved).preferences
        first.close()
        val reopened = open(file)
        assertEquals(saved, reopened.ready().preferences)
        assertEquals(before.help, reopened.ready().preferences.help)
        assertTrue(reopened.ready().preferences.bluetoothLyricsEnabled)
        val untouched = file.readBytes()
        assertTrue(reopened.repository.save(before.revision, before.asDraft()) is PreferencesWriteResult.Conflict)
        assertArrayEquals(untouched, file.readBytes())
    }

    @After
    fun closeStores() = runBlocking {
        sessions.asReversed().forEach { session -> session.close() }
        sessions.clear()
    }

    @Test(timeout = 30000L)
    fun emptyStoreLoadsSafeDefaults() = runBlocking {
        val session = open(preferencesFile())
        val loaded = session.ready()
        assertEquals(UserPreferences(), loaded.preferences)
        assertNull(loaded.recovery)
        assertTrue(loaded.canWrite)
    }

    @Test(timeout = 30000L)
    fun noticeChoiceTipsAndGuideProgressSurviveRealFileReopenAndStaleWrites() = runBlocking {
        val file = preferencesFile()
        val first = open(file)
        first.ready()
        val help = HelpPreferences(onboardingStep = 2, showTips = false,
            privacyNoticeVersion = HelpPreferenceLimits.PRIVACY_NOTICE_VERSION, privacyNoticeChoice = PrivacyNoticeChoice.LATER)
        val saved = (first.repository.save(0L, UserPreferencesDraft(help = help)) as PreferencesWriteResult.Saved).preferences
        val bytes = file.readBytes()
        assertEquals(saved, (first.repository.save(0L, UserPreferencesDraft()) as PreferencesWriteResult.Conflict).current)
        assertArrayEquals(bytes, file.readBytes())
        first.close()
        val second = open(file)
        assertEquals(help, second.ready().preferences.help)
        val completed = help.copy(onboardingCompletedVersion = HelpPreferenceLimits.GUIDE_VERSION,
            onboardingStep = HelpPreferenceLimits.GUIDE_STEP_COUNT - 1)
        assertTrue(second.repository.save(saved.revision, saved.asDraft().copy(help = completed)) is PreferencesWriteResult.Saved)
        second.close()
        val reopened = open(file).ready()
        assertEquals(completed, reopened.preferences.help)
        assertFalse(reopened.preferences.help.offerInitialGuide)
    }

    @Test(timeout = 30000L)
    fun priorSchemasKeepTheirBytesAndDoNotOfferNewInstallGuidanceUntilExplicitSave() = runBlocking {
        for (schema in 1..4) {
            val file = preferencesFile()
            writeFixture(file, if (schema == 1) fixture(schema, 9L) else completeFixture(schema, 9L))
            val bytes = file.readBytes()
            val first = open(file)
            val loaded = first.ready()
            assertNull(loaded.recovery)
            assertEquals(HelpPreferenceLimits.Legacy, loaded.preferences.help)
            assertFalse(loaded.preferences.help.offerInitialGuide)
            assertArrayEquals(bytes, file.readBytes())
            val migrated = first.repository.save(9L, loaded.preferences.asDraft()) as PreferencesWriteResult.Saved
            first.close()
            assertEquals(migrated.preferences, open(file).ready().preferences)
        }
    }

    @Test(timeout = 30000L)
    fun invalidHelpFieldsExplainRecoveryWithoutRewritingTheStoredChoice() = runBlocking {
        val file = preferencesFile()
        val raw = completeFixture(UserPreferences.CURRENT_SCHEMA_VERSION, 2L).toMutablePreferences().apply {
            this[intPreferencesKey("guide_step")] = HelpPreferenceLimits.GUIDE_STEP_COUNT
            this[intPreferencesKey("privacy_notice_version")] = 1
            this[stringPreferencesKey("privacy_notice_choice")] = "UNANSWERED"
        }
        writeFixture(file, raw)
        val bytes = file.readBytes()
        val loaded = open(file).ready()
        assertEquals(setOf(PreferencesField.HELP), loaded.recovery?.fields)
        assertEquals(HelpPreferences(), loaded.preferences.help)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test(timeout = 30000L)
    fun committedValuesSurviveClosingAndReopeningTheRealFile() = runBlocking {
        val file = preferencesFile()
        val first = open(file)
        first.ready()
        val result = first.repository.save(0L, UserPreferencesDraft(false, ScreenShape.ROUND))
        assertTrue(result is PreferencesWriteResult.Saved)
        val saved = (result as PreferencesWriteResult.Saved).preferences
        assertEquals(1L, saved.revision)
        assertTrue(file.isFile)
        first.close()

        val reopened = open(file).ready()
        assertEquals(saved, reopened.preferences)
        assertNull(reopened.recovery)
    }

    @Test(timeout = 30000L)
    fun repeatedSaveDoesNotIncreaseRevisionOrRewriteTheFile() = runBlocking {
        val file = preferencesFile()
        val session = open(file)
        session.ready()
        val draft = UserPreferencesDraft(false, ScreenShape.RECTANGULAR)
        session.repository.save(0L, draft)
        val committedBytes = file.readBytes()

        val result = session.repository.save(1L, draft)
        assertTrue(result is PreferencesWriteResult.Unchanged)
        assertEquals(1L, (result as PreferencesWriteResult.Unchanged).preferences.revision)
        assertArrayEquals(committedBytes, file.readBytes())
    }

    @Test(timeout = 30000L)
    fun concurrentEditsAtTheSameRevisionCannotOverwriteEachOther() = runBlocking {
        val file = preferencesFile()
        val session = open(file)
        session.ready()
        val start = CompletableDeferred<Unit>()
        val edits = listOf(ScreenShape.ROUND, ScreenShape.RECTANGULAR).map { shape ->
            async(Dispatchers.Default) {
                start.await()
                session.repository.save(0L, UserPreferencesDraft(false, shape))
            }
        }
        start.complete(Unit)
        val outcomes = edits.awaitAll()
        assertEquals(1, outcomes.count { outcome -> outcome is PreferencesWriteResult.Saved })
        assertEquals(1, outcomes.count { outcome -> outcome is PreferencesWriteResult.Conflict })
        val saved = outcomes.filterIsInstance<PreferencesWriteResult.Saved>().single().preferences
        val conflict = outcomes.filterIsInstance<PreferencesWriteResult.Conflict>().single()
        assertEquals(saved, conflict.current)
        assertEquals(saved, session.ready().preferences)
        session.close()
        assertEquals(saved, open(file).ready().preferences)
    }

    @Test(timeout = 30000L)
    fun aDelayedOldObservationCannotReplaceAnAlreadyCommittedRevision() = runBlocking {
        val file = preferencesFile()
        writeFixture(file, fixture(schema = 1, revision = 3L))
        val observation = FirstObservationGate()
        val session = open(file) { store -> HeldFirstObservationDataStore(store, observation) }
        withTimeout(10000L) { observation.captured.await() }

        val result = session.repository.save(3L, UserPreferencesDraft(true, ScreenShape.RECTANGULAR))
        val committed = (result as PreferencesWriteResult.Saved).preferences
        assertEquals(4L, committed.revision)
        observation.release.complete(Unit)
        withTimeout(10000L) { observation.delivered.await() }
        assertEquals(committed, session.ready().preferences)
        observation.continueReads.complete(Unit)
        session.close()
        assertEquals(committed, open(file).ready().preferences)
    }

    @Test(timeout = 30000L)
    fun cancellingResetPreservesPreferencesAndConfirmedResetTouchesOnlyPreferences() = runBlocking {
        val file = preferencesFile()
        val library = temporaryFolder.newFile("library-sentinel")
        library.writeText("unrelated library and audio remain intact")
        val libraryBytes = library.readBytes()
        val session = open(file)
        session.ready()
        session.repository.save(0L, UserPreferencesDraft(false, ScreenShape.ROUND))
        val savedBytes = file.readBytes()

        val cancelled = session.repository.reset(1L, ResetConfirmation.CANCELLED)
        assertTrue(cancelled is PreferencesWriteResult.Cancelled)
        assertArrayEquals(savedBytes, file.readBytes())

        val confirmed = session.repository.reset(1L, ResetConfirmation.CONFIRMED)
        assertTrue(confirmed is PreferencesWriteResult.Saved)
        assertEquals(UserPreferences(revision = 2L), (confirmed as PreferencesWriteResult.Saved).preferences)
        assertArrayEquals(libraryBytes, library.readBytes())
        session.close()
        assertEquals(UserPreferences(revision = 2L), open(file).ready().preferences)
    }

    @Test(timeout = 30000L)
    fun binaryCorruptionIsRepairedWithADurableExplanation() = runBlocking {
        val file = preferencesFile()
        val corruptBytes = byteArrayOf(0x80.toByte())
        file.writeBytes(corruptBytes)
        val first = open(file)
        val recovered = first.ready()
        assertEquals(UserPreferences(), recovered.preferences)
        assertEquals(PreferencesRecoveryReason.CORRUPT_FILE, recovered.recovery?.reason)
        assertEquals(PreferencesField.entries.toSet(), recovered.recovery?.fields)
        assertFalse(corruptBytes.contentEquals(file.readBytes()))
        first.close()

        val second = open(file)
        assertEquals(PreferencesRecoveryReason.CORRUPT_FILE, second.ready().recovery?.reason)
        val repair = second.repository.save(0L, UserPreferencesDraft(false, ScreenShape.RECTANGULAR))
        assertTrue(repair is PreferencesWriteResult.Saved)
        second.close()

        val reopened = open(file).ready()
        assertNull(reopened.recovery)
        assertEquals(
            UserPreferences(revision = 1L, restorePosition = false, screenShape = ScreenShape.RECTANGULAR),
            reopened.preferences,
        )
    }

    @Test(timeout = 30000L)
    fun wrongTypedFieldsAndUnknownEnumsUseExplainedDefaults() = runBlocking {
        val file = preferencesFile()
        writeFixture(
            file,
            mutablePreferencesOf(
                intPreferencesKey("schema_version") to 1,
                longPreferencesKey("revision") to 8L,
                stringPreferencesKey("restore_position") to "not a boolean",
                stringPreferencesKey("screen_shape") to "OVAL",
            ),
        )
        val originalBytes = file.readBytes()
        val session = open(file)
        val recovered = session.ready()
        assertEquals(UserPreferences(revision = 8L), recovered.preferences)
        assertEquals(PreferencesRecoveryReason.INVALID_FIELDS, recovered.recovery?.reason)
        assertEquals(
            setOf(PreferencesField.RESTORE_POSITION, PreferencesField.SCREEN_SHAPE),
            recovered.recovery?.fields,
        )
        assertArrayEquals(originalBytes, file.readBytes())

        val saved = session.repository.save(8L, UserPreferencesDraft(false, ScreenShape.ROUND))
        assertTrue(saved is PreferencesWriteResult.Saved)
        session.close()
        assertEquals(
            UserPreferences(revision = 9L, restorePosition = false, screenShape = ScreenShape.ROUND),
            open(file).ready().preferences,
        )
    }

    @Test(timeout = 30000L)
    fun wrongTypedSchemaAndRevisionArePreservedUntilExplicitRepair() = runBlocking {
        val wrongSchemaFile = File(temporaryFolder.root, "wrong-schema.preferences_pb")
        writeFixture(
            wrongSchemaFile,
            mutablePreferencesOf(
                stringPreferencesKey("schema_version") to "1",
                longPreferencesKey("revision") to 7L,
            ),
        )
        val schemaBytes = wrongSchemaFile.readBytes()
        val wrongSchema = open(wrongSchemaFile).ready()
        assertEquals(UserPreferences(revision = 7L), wrongSchema.preferences)
        assertEquals(PreferencesRecoveryReason.UNRECOGNIZED_SCHEMA, wrongSchema.recovery?.reason)
        assertNull(wrongSchema.recovery?.originalSchemaVersion)
        assertArrayEquals(schemaBytes, wrongSchemaFile.readBytes())

        val wrongRevisionFile = File(temporaryFolder.root, "wrong-revision.preferences_pb")
        writeFixture(
            wrongRevisionFile,
            mutablePreferencesOf(
                intPreferencesKey("schema_version") to 1,
                stringPreferencesKey("revision") to "invalid",
                booleanPreferencesKey("restore_position") to false,
                stringPreferencesKey("screen_shape") to ScreenShape.ROUND.name,
            ),
        )
        val revisionBytes = wrongRevisionFile.readBytes()
        val session = open(wrongRevisionFile)
        val recovered = session.ready()
        assertEquals(UserPreferences(restorePosition = false, screenShape = ScreenShape.ROUND,
            help = HelpPreferenceLimits.Legacy), recovered.preferences)
        assertEquals(PreferencesRecoveryReason.INVALID_FIELDS, recovered.recovery?.reason)
        assertEquals(setOf(PreferencesField.REVISION), recovered.recovery?.fields)
        assertArrayEquals(revisionBytes, wrongRevisionFile.readBytes())

        val repair = session.repository.save(0L, recovered.preferences.asDraft())
        assertEquals(1L, (repair as PreferencesWriteResult.Saved).preferences.revision)
        session.close()
        assertNull(open(wrongRevisionFile).ready().recovery)
    }

    @Test(timeout = 30000L)
    fun anUnrecognizedOlderSchemaRequiresAnExplicitSaveToReplaceIt() = runBlocking {
        val file = preferencesFile()
        writeFixture(file, fixture(schema = 0, revision = 3L))
        val originalBytes = file.readBytes()
        val session = open(file)
        val recovered = session.ready()
        assertEquals(UserPreferences(revision = 3L), recovered.preferences)
        assertEquals(PreferencesRecoveryReason.UNRECOGNIZED_SCHEMA, recovered.recovery?.reason)
        assertArrayEquals(originalBytes, file.readBytes())

        val saved = session.repository.reset(3L, ResetConfirmation.CONFIRMED)
        assertTrue(saved is PreferencesWriteResult.Saved)
        session.close()
        val reopened = open(file).ready()
        assertEquals(UserPreferences(revision = 4L), reopened.preferences)
        assertNull(reopened.recovery)
    }

    @Test(timeout = 30000L)
    fun aNewerSchemaRemainsUntouchedEvenWhenSaveOrResetIsRequested() = runBlocking {
        val file = preferencesFile()
        writeFixture(file, fixture(schema = UserPreferences.CURRENT_SCHEMA_VERSION + 1, revision = 12L))
        val originalBytes = file.readBytes()
        val session = open(file)
        val loaded = session.ready()
        assertEquals(UserPreferences(revision = 12L), loaded.preferences)
        assertEquals(PreferencesRecoveryReason.NEWER_SCHEMA, loaded.recovery?.reason)
        assertFalse(loaded.canWrite)

        val saved = session.repository.save(12L, UserPreferencesDraft(false, ScreenShape.ROUND))
        val reset = session.repository.reset(12L, ResetConfirmation.CONFIRMED)
        assertEquals(PreferencesFailure.UNSUPPORTED_SCHEMA, (saved as PreferencesWriteResult.Failed).failure)
        assertEquals(PreferencesFailure.UNSUPPORTED_SCHEMA, (reset as PreferencesWriteResult.Failed).failure)
        assertArrayEquals(originalBytes, file.readBytes())
    }

    @Test(timeout = 30000L)
    fun legacyPlaybackSettingsMigrateOnlyOnExplicitSaveWithoutResettingTheirValues() = runBlocking {
        val file = preferencesFile()
        writeFixture(file, fixture(schema = 1, revision = 6L))
        val legacyBytes = file.readBytes()
        val first = open(file)
        val loaded = first.ready()
        assertEquals(false, loaded.preferences.restorePosition)
        assertEquals(ScreenShape.ROUND, loaded.preferences.screenShape)
        assertNull(loaded.recovery)
        assertArrayEquals(legacyBytes, file.readBytes())

        val result = first.repository.save(6L, loaded.preferences.asDraft()) as PreferencesWriteResult.Saved
        assertEquals(7L, result.preferences.revision)
        assertEquals(loaded.preferences.asDraft(), result.preferences.asDraft())
        first.close()
        val reopened = open(file)
        assertEquals(result.preferences, reopened.ready().preferences)
        assertTrue(reopened.repository.save(7L, result.preferences.asDraft()) is PreferencesWriteResult.Unchanged)
    }

    @Test(timeout = 30000L)
    fun libraryAppearanceAndPerListSortsSurviveReopenAndConflictCannotDiscardThem() = runBlocking {
        val file = preferencesFile()
        val first = open(file)
        first.ready()
        val sorts = UserPreferences().librarySorts.toMutableMap().apply {
            this[LibraryListContext.ALL] = LibrarySort(LibrarySortField.FILENAME_NATURAL, true)
            this[LibraryListContext.ALBUM] = LibrarySort(LibrarySortField.ALBUM_TRACK, false)
            this[LibraryListContext.ARTIST] = LibrarySort(LibrarySortField.PLAY_COUNT, true)
        }.toMap()
        val draft = UserPreferencesDraft(
            restorePosition = false,
            screenShape = ScreenShape.RECTANGULAR,
            libraryLayout = LibraryLayout.GRID,
            artworkReadPath = ArtworkReadPath.LOCAL_EXTRACT,
            librarySorts = sorts,
        )
        val saved = (first.repository.save(0L, draft) as PreferencesWriteResult.Saved).preferences
        val committedBytes = file.readBytes()
        val conflict = first.repository.save(0L, UserPreferencesDraft()) as PreferencesWriteResult.Conflict
        assertEquals(saved, conflict.current)
        assertArrayEquals(committedBytes, file.readBytes())
        first.close()
        assertEquals(draft, open(file).ready().preferences.asDraft())
    }

    @Test(timeout = 30000L)
    fun allPlaybackPreferencesSurviveReopenAndAStaleEditorCannotOverwriteThem() = runBlocking {
        val file = preferencesFile()
        val first = open(file)
        first.ready()
        val draft = UserPreferencesDraft(
            songTapPolicy = SongTapPolicy.INSERT_AND_PLAY,
            mediaButtonsEnabled = false,
            audioFocusManagement = false,
            playbackBackend = PlaybackBackendKind.SYSTEM_MEDIA_PLAYER,
            playbackSpeedTenths = 17,
            gaplessPlayback = false,
        )
        val saved = (first.repository.save(0L, draft) as PreferencesWriteResult.Saved).preferences
        val bytes = file.readBytes()
        assertEquals(draft, saved.asDraft())
        assertTrue(first.repository.save(1L, draft) is PreferencesWriteResult.Unchanged)
        assertEquals(saved, (first.repository.save(0L, UserPreferencesDraft()) as PreferencesWriteResult.Conflict).current)
        assertTrue(first.repository.reset(1L, ResetConfirmation.CANCELLED) is PreferencesWriteResult.Cancelled)
        assertArrayEquals(bytes, file.readBytes())
        first.close()
        val reopened = open(file)
        assertEquals(saved, reopened.ready().preferences)
        val reset = reopened.repository.reset(1L, ResetConfirmation.CONFIRMED) as PreferencesWriteResult.Saved
        assertEquals(UserPreferences(revision = 2L), reset.preferences)
    }

    @Test(timeout = 30000L)
    fun schemaTwoPreservesItsLibraryValuesAndBytesUntilExplicitPlaybackMigration() = runBlocking {
        val file = preferencesFile()
        val sorts = UserPreferences().librarySorts.toMutableMap().apply {
            this[LibraryListContext.ALL] = LibrarySort(LibrarySortField.FILENAME_NATURAL, true)
        }.toMap()
        val raw = completeFixture(schema = 2, revision = 9L, sorts = sorts).toMutablePreferences().apply {
            remove(stringPreferencesKey("song_tap_policy"))
            remove(booleanPreferencesKey("media_buttons_enabled"))
            remove(booleanPreferencesKey("audio_focus_management"))
            remove(stringPreferencesKey("playback_backend"))
            remove(intPreferencesKey("playback_speed_tenths"))
            remove(booleanPreferencesKey("gapless_playback"))
        }
        writeFixture(file, raw)
        val bytes = file.readBytes()
        val first = open(file)
        val loaded = first.ready()
        assertEquals(UserPreferences(
            revision = 9L, restorePosition = false, screenShape = ScreenShape.ROUND,
            libraryLayout = LibraryLayout.GRID, artworkReadPath = ArtworkReadPath.LOCAL_EXTRACT, librarySorts = sorts,
            help = HelpPreferenceLimits.Legacy,
        ), loaded.preferences)
        assertNull(loaded.recovery)
        assertArrayEquals(bytes, file.readBytes())
        val saved = first.repository.save(9L, loaded.preferences.asDraft()) as PreferencesWriteResult.Saved
        assertEquals(10L, saved.preferences.revision)
        assertEquals(loaded.preferences.asDraft(), saved.preferences.asDraft())
        first.close()
        val reopened = open(file)
        assertEquals(saved.preferences, reopened.ready().preferences)
        assertTrue(reopened.repository.save(10L, saved.preferences.asDraft()) is PreferencesWriteResult.Unchanged)
    }

    @Test(timeout = 30000L)
    fun invalidPlaybackTypesAndRangesOnlyRecoverTheAffectedFieldsWithoutWriting() = runBlocking {
        val file = preferencesFile()
        val raw = completeFixture(schema = UserPreferences.CURRENT_SCHEMA_VERSION, revision = 4L)
            .toMutablePreferences().apply {
                this[stringPreferencesKey("song_tap_policy")] = "UNKNOWN"
                this[stringPreferencesKey("playback_backend")] = "UNKNOWN"
                this[intPreferencesKey("playback_speed_tenths")] = 21
                this[stringPreferencesKey("media_buttons_enabled")] = "not boolean"
                this[stringPreferencesKey("audio_focus_management")] = "not boolean"
                this[stringPreferencesKey("gapless_playback")] = "not boolean"
            }
        writeFixture(file, raw)
        val bytes = file.readBytes()
        val loaded = open(file).ready()
        assertEquals(PreferencesRecoveryReason.INVALID_FIELDS, loaded.recovery?.reason)
        assertEquals(setOf(
            PreferencesField.SONG_TAP_POLICY, PreferencesField.PLAYBACK_BACKEND, PreferencesField.PLAYBACK_SPEED,
            PreferencesField.MEDIA_BUTTONS, PreferencesField.AUDIO_FOCUS, PreferencesField.GAPLESS,
        ), loaded.recovery?.fields)
        assertEquals(SongTapPolicy.ASK, loaded.preferences.songTapPolicy)
        assertEquals(PlaybackBackendKind.MEDIA3, loaded.preferences.playbackBackend)
        assertEquals(10, loaded.preferences.playbackSpeedTenths)
        assertTrue(loaded.preferences.mediaButtonsEnabled)
        assertTrue(loaded.preferences.audioFocusManagement)
        assertTrue(loaded.preferences.gaplessPlayback)
        assertEquals(LibraryLayout.GRID, loaded.preferences.libraryLayout)
        assertEquals(ArtworkReadPath.LOCAL_EXTRACT, loaded.preferences.artworkReadPath)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test(timeout = 30000L)
    fun schemaThreeKeepsNonDefaultPlaybackAndLibraryUntilExplicitControlsMigration() = runBlocking {
        val file = preferencesFile()
        val raw = completeFixture(schema = 3, revision = 19L).toMutablePreferences().apply {
            this[stringPreferencesKey("song_tap_policy")] = SongTapPolicy.INSERT_AND_PLAY.name
            this[stringPreferencesKey("playback_backend")] = PlaybackBackendKind.SYSTEM_MEDIA_PLAYER.name
            this[intPreferencesKey("playback_speed_tenths")] = 17
            this[booleanPreferencesKey("media_buttons_enabled")] = false
            this[booleanPreferencesKey("audio_focus_management")] = false
            this[booleanPreferencesKey("gapless_playback")] = false
        }
        writeFixture(file, raw)
        val bytes = file.readBytes()
        val first = open(file)
        val loaded = first.ready()
        assertEquals(UserPreferences(
            revision = 19L, restorePosition = false, screenShape = ScreenShape.ROUND,
            libraryLayout = LibraryLayout.GRID, artworkReadPath = ArtworkReadPath.LOCAL_EXTRACT,
            songTapPolicy = SongTapPolicy.INSERT_AND_PLAY, playbackBackend = PlaybackBackendKind.SYSTEM_MEDIA_PLAYER,
            playbackSpeedTenths = 17, mediaButtonsEnabled = false, audioFocusManagement = false, gaplessPlayback = false,
            controls = PlaybackControlsPreferences(),
            help = HelpPreferenceLimits.Legacy,
        ), loaded.preferences)
        assertNull(loaded.recovery)
        assertArrayEquals(bytes, file.readBytes())
        val saved = first.repository.save(19L, loaded.preferences.asDraft()) as PreferencesWriteResult.Saved
        assertEquals(20L, saved.preferences.revision)
        assertEquals(loaded.preferences.asDraft(), saved.preferences.asDraft())
        first.close()
        val reopened = open(file)
        assertEquals(saved.preferences, reopened.ready().preferences)
        assertTrue(reopened.repository.save(20L, saved.preferences.asDraft()) is PreferencesWriteResult.Unchanged)
    }

    @Test(timeout = 30000L)
    fun controlsSurviveReopenAndStaleOrCancelledEditsCannotDiscardThem() = runBlocking {
        val file = preferencesFile()
        val first = open(file)
        first.ready()
        val controls = PlaybackControlsPreferences(
            rotaryEnabled = true, rotaryAction = RotaryAction.SEEK, rotaryOrientation = RotaryOrientation.REVERSED,
            rotaryHaptics = true, showMainProgress = false, progressGesture = ProgressGestureMode.TAP_TO_SEEK,
            sleepTimerSummary = true,
        )
        val draft = UserPreferencesDraft(playbackSpeedTenths = 12, controls = controls)
        val saved = (first.repository.save(0L, draft) as PreferencesWriteResult.Saved).preferences
        val bytes = file.readBytes()
        assertEquals(draft, saved.asDraft())
        assertTrue(first.repository.save(1L, draft) is PreferencesWriteResult.Unchanged)
        assertEquals(saved, (first.repository.save(0L, UserPreferencesDraft()) as PreferencesWriteResult.Conflict).current)
        assertTrue(first.repository.reset(1L, ResetConfirmation.CANCELLED) is PreferencesWriteResult.Cancelled)
        assertArrayEquals(bytes, file.readBytes())
        first.close()
        val reopened = open(file)
        assertEquals(saved, reopened.ready().preferences)
        assertEquals(UserPreferences(revision = 2L),
            (reopened.repository.reset(1L, ResetConfirmation.CONFIRMED) as PreferencesWriteResult.Saved).preferences)
    }

    @Test(timeout = 30000L)
    fun damagedControlFieldsRecoverSafelyWithoutChangingOtherPreferencesOrBytes() = runBlocking {
        val file = preferencesFile()
        val raw = completeFixture(schema = UserPreferences.CURRENT_SCHEMA_VERSION, revision = 7L)
            .toMutablePreferences().apply {
                this[stringPreferencesKey("rotary_enabled")] = "true"
                this[stringPreferencesKey("rotary_action")] = "ANYTHING"
                this[intPreferencesKey("rotary_orientation")] = 1
                this[intPreferencesKey("rotary_haptics")] = 1
                this[stringPreferencesKey("show_main_progress")] = "false"
                this[stringPreferencesKey("progress_gesture")] = "IMMEDIATE_DRAG"
                this[stringPreferencesKey("sleep_timer_summary")] = "true"
                this[intPreferencesKey("playback_speed_tenths")] = 18
            }
        writeFixture(file, raw)
        val bytes = file.readBytes()
        val loaded = open(file).ready()
        assertEquals(PreferencesRecoveryReason.INVALID_FIELDS, loaded.recovery?.reason)
        assertEquals(setOf(PreferencesField.ROTARY_CONTROL, PreferencesField.ROTARY_TARGET, PreferencesField.ROTARY_DIRECTION,
            PreferencesField.ROTARY_HAPTICS, PreferencesField.MAIN_PROGRESS, PreferencesField.SEEK_GESTURE,
            PreferencesField.SLEEP_SUMMARY), loaded.recovery?.fields)
        assertEquals(PlaybackControlsPreferences(), loaded.preferences.controls)
        assertEquals(18, loaded.preferences.playbackSpeedTenths)
        assertEquals(LibraryLayout.GRID, loaded.preferences.libraryLayout)
        assertEquals(ArtworkReadPath.LOCAL_EXTRACT, loaded.preferences.artworkReadPath)
        assertFalse(loaded.preferences.restorePosition)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test(timeout = 30000L)
    fun revisionOverflowCannotCommitANegativeRevision() = runBlocking {
        val file = preferencesFile()
        writeFixture(file, fixture(schema = 1, revision = Long.MAX_VALUE))
        val originalBytes = file.readBytes()
        val session = open(file)
        session.ready()
        val result = session.repository.save(Long.MAX_VALUE, UserPreferencesDraft())
        assertEquals(PreferencesFailure.REVISION_EXHAUSTED, (result as PreferencesWriteResult.Failed).failure)
        assertArrayEquals(originalBytes, file.readBytes())
    }

    @Test(timeout = 30000L)
    fun writeFailureUsesTheAtomicCurrentValueAndPreservesCommittedBytes() = runBlocking {
        val file = preferencesFile()
        val observation = FirstObservationGate()
        lateinit var realStore: DataStore<Preferences>
        val session = open(file) { store ->
            realStore = store
            HeldFirstObservationDataStore(store, observation)
        }
        withTimeout(10000L) { observation.captured.await() }
        session.repository.save(0L, UserPreferencesDraft(false, ScreenShape.ROUND))
        // 同一个真实 DataStore 内的 fixture 前进到 revision 2；观察回调保持滞后以验证权威读取。
        realStore.updateData { fixture(schema = 1, revision = 2L) }
        assertEquals(1L, session.ready().preferences.revision)
        val committed = UserPreferences(revision = 2L, restorePosition = false, screenShape = ScreenShape.ROUND,
            help = HelpPreferenceLimits.Legacy)
        val committedBytes = file.readBytes()

        // DataStore 1.1.7 使用该暂存名；非空目录制造真实写盘故障，不替换存储为假实现。
        val scratch = File(file.absolutePath + ".tmp")
        assertTrue(scratch.mkdir())
        val blocker = File(scratch, "write-blocker")
        blocker.writeText("this fixture owns only the temporary directory")
        val result = session.repository.save(2L, UserPreferencesDraft(true, ScreenShape.RECTANGULAR))
        assertEquals(PreferencesFailure.IO, (result as PreferencesWriteResult.Failed).failure)
        assertEquals(committed, result.current)
        assertEquals(committed, session.ready().preferences)
        assertArrayEquals(committedBytes, file.readBytes())

        assertTrue(blocker.delete())
        assertTrue(scratch.delete())
        val retry = session.repository.save(2L, UserPreferencesDraft(true, ScreenShape.RECTANGULAR))
        assertTrue(retry is PreferencesWriteResult.Saved)
        session.close()
        assertEquals(
            UserPreferences(revision = 3L, screenShape = ScreenShape.RECTANGULAR),
            open(file).ready().preferences,
        )
    }

    @Test(timeout = 30000L)
    fun readFailureIsUnavailableUntilAnExplicitRetrySucceeds() = runBlocking {
        val file = preferencesFile()
        assertTrue(file.mkdir())
        val session = open(file)
        val failure = withTimeout(10000L) {
            session.repository.state.filterIsInstance<PreferencesState.Unavailable>().first()
        }
        assertEquals(PreferencesFailure.IO, failure.failure)
        assertNull(failure.lastKnown)

        assertTrue(file.delete())
        session.repository.retryRead()
        assertEquals(UserPreferences(), session.ready().preferences)
    }

    @Test(timeout = 30000L)
    fun aSuccessfulExplicitSaveAlsoResumesAnObserverWaitingAfterReadFailure() = runBlocking {
        val file = preferencesFile()
        assertTrue(file.mkdir())
        val failure = ReadFailureGate()
        failure.release.complete(Unit)
        val session = open(file) { store -> HeldReadFailureDataStore(store, failure) }
        withTimeout(10000L) {
            session.repository.state.filterIsInstance<PreferencesState.Unavailable>().first()
        }
        assertTrue(file.delete())

        val result = session.repository.save(0L, UserPreferencesDraft(false, ScreenShape.ROUND))
        val committed = (result as PreferencesWriteResult.Saved).preferences
        withTimeout(10000L) { failure.observed.await() }
        assertEquals(committed, session.ready().preferences)
    }

    @Test(timeout = 30000L)
    fun aDelayedReadIOExceptionCannotHideANewerConfirmedSave() = runBlocking {
        val file = preferencesFile()
        assertTrue(file.mkdir())
        val failure = ReadFailureGate()
        val session = open(file) { store -> HeldReadFailureDataStore(store, failure) }
        withTimeout(10000L) { failure.captured.await() }
        assertTrue(file.delete())

        val result = session.repository.save(0L, UserPreferencesDraft(false, ScreenShape.ROUND))
        val committed = (result as PreferencesWriteResult.Saved).preferences
        failure.release.complete(Unit)
        withTimeout(10000L) { failure.observed.await() }
        assertEquals(committed, session.ready().preferences)
    }

    @Test(timeout = 30000L)
    fun cancellationBeforeTheAtomicTransformDoesNotReturnAFakeFailureOrCommit() = runBlocking {
        val file = preferencesFile()
        writeFixture(file, fixture(schema = 1, revision = 3L))
        val committedBytes = file.readBytes()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        lateinit var gatedStore: GatedUpdateDataStore
        val session = open(file) { store ->
            GatedUpdateDataStore(store, entered, release).also { gatedStore = it }
        }
        session.ready()
        gatedStore.holdNextUpdate()
        var result: PreferencesWriteResult? = null
        val write = launch { result = session.repository.save(3L, UserPreferencesDraft(true, ScreenShape.RECTANGULAR)) }
        withTimeout(10000L) { entered.await() }
        write.cancelAndJoin()
        assertTrue(write.isCancelled)
        assertNull(result)
        assertArrayEquals(committedBytes, file.readBytes())

        release.complete(Unit)
        val retry = session.repository.save(3L, UserPreferencesDraft(true, ScreenShape.RECTANGULAR))
        assertTrue(retry is PreferencesWriteResult.Saved)
    }

    private fun preferencesFile(): File = File(temporaryFolder.root, "user.preferences_pb")

    private fun open(
        file: File,
        wrap: (DataStore<Preferences>) -> DataStore<Preferences> = { store -> store },
    ): StoreSession {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val store = createUserPreferencesDataStore(scope) { file }
        val observerJob = SupervisorJob(job)
        val observerScope = CoroutineScope(observerJob + Dispatchers.IO)
        return StoreSession(job, observerJob, DataStoreUserPreferencesRepository(wrap(store), observerScope)).also { session ->
            sessions.add(session)
        }
    }

    private suspend fun writeFixture(file: File, fixture: Preferences) {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        try {
            val store = createUserPreferencesDataStore(scope) { file }
            store.updateData { fixture }
        } finally {
            job.cancelAndJoin()
        }
    }

    private fun fixture(schema: Int, revision: Long): Preferences = mutablePreferencesOf(
        intPreferencesKey("schema_version") to schema,
        longPreferencesKey("revision") to revision,
        booleanPreferencesKey("restore_position") to false,
        stringPreferencesKey("screen_shape") to ScreenShape.ROUND.name,
    )

    private fun completeFixture(
        schema: Int,
        revision: Long,
        sorts: Map<LibraryListContext, LibrarySort> = UserPreferences().librarySorts,
    ): Preferences = mutablePreferencesOf(
        intPreferencesKey("schema_version") to schema,
        longPreferencesKey("revision") to revision,
        booleanPreferencesKey("restore_position") to false,
        stringPreferencesKey("screen_shape") to ScreenShape.ROUND.name,
        stringPreferencesKey("library_layout") to LibraryLayout.GRID.name,
        stringPreferencesKey("artwork_read_path") to ArtworkReadPath.LOCAL_EXTRACT.name,
        stringPreferencesKey("song_tap_policy") to SongTapPolicy.ASK.name,
        booleanPreferencesKey("media_buttons_enabled") to true,
        booleanPreferencesKey("audio_focus_management") to true,
        stringPreferencesKey("playback_backend") to PlaybackBackendKind.MEDIA3.name,
        intPreferencesKey("playback_speed_tenths") to 10,
        booleanPreferencesKey("gapless_playback") to true,
    ).apply {
        if (schema >= 4) {
            this[booleanPreferencesKey("rotary_enabled")] = false
            this[stringPreferencesKey("rotary_action")] = RotaryAction.MEDIA_VOLUME.name
            this[stringPreferencesKey("rotary_orientation")] = RotaryOrientation.NORMAL.name
            this[booleanPreferencesKey("rotary_haptics")] = false
            this[booleanPreferencesKey("show_main_progress")] = true
            this[stringPreferencesKey("progress_gesture")] = ProgressGestureMode.DRAG_TO_COMMIT.name
            this[booleanPreferencesKey("sleep_timer_summary")] = false
        }
        if (schema >= 5) {
            this[intPreferencesKey("guide_completed_version")] = 0
            this[intPreferencesKey("guide_dismissed_version")] = 0
            this[intPreferencesKey("guide_step")] = 0
            this[booleanPreferencesKey("help_show_tips")] = true
            this[intPreferencesKey("privacy_notice_version")] = 0
            this[stringPreferencesKey("privacy_notice_choice")] = "UNANSWERED"
        }
        if (schema >= 6) this[booleanPreferencesKey("bluetooth_lyrics_enabled")] = false
        sorts.forEach { (context, sort) ->
            val suffix = context.name.lowercase(java.util.Locale.ROOT)
            this[stringPreferencesKey("library_sort_" + suffix)] = sort.field.name
            this[booleanPreferencesKey("library_descending_" + suffix)] = sort.descending
        }
    }

    private class StoreSession(
        private val job: Job,
        private val observerJob: Job,
        val repository: DataStoreUserPreferencesRepository,
    ) {
        suspend fun close() {
            // 先让观察任务等待中的 identity read 收到 actor 回执，再取消 DataStore actor。
            observerJob.cancelAndJoin()
            job.cancelAndJoin()
        }

        suspend fun ready(): PreferencesState.Ready = withTimeout(10000L) {
            repository.state.filterIsInstance<PreferencesState.Ready>().first()
        }
    }

    private class FirstObservationGate {
        val captured = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val delivered = CompletableDeferred<Unit>()
        val continueReads = CompletableDeferred<Unit>()
    }

    private class HeldFirstObservationDataStore(
        private val delegate: DataStore<Preferences>,
        private val gate: FirstObservationGate,
    ) : DataStore<Preferences> by delegate {
        override val data: Flow<Preferences> = flow {
            var first = true
            delegate.data.collect { stored ->
                if (first) {
                    first = false
                    gate.captured.complete(Unit)
                    gate.release.await()
                    emit(stored)
                    gate.delivered.complete(Unit)
                    gate.continueReads.await()
                } else {
                    emit(stored)
                }
            }
        }
    }

    private class ReadFailureGate {
        val captured = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val observed = CompletableDeferred<Unit>()
    }

    private class HeldReadFailureDataStore(
        private val delegate: DataStore<Preferences>,
        private val gate: ReadFailureGate,
    ) : DataStore<Preferences> by delegate {
        override val data: Flow<Preferences> = flow {
            try {
                delegate.data.collect { stored ->
                    emit(stored)
                    gate.observed.complete(Unit)
                }
            } catch (error: IOException) {
                // 延迟的是真实文件读取异常，测试只控制回调交付次序。
                gate.captured.complete(Unit)
                gate.release.await()
                throw error
            }
        }
    }

    private class GatedUpdateDataStore(
        private val delegate: DataStore<Preferences>,
        private val entered: CompletableDeferred<Unit>,
        private val release: CompletableDeferred<Unit>,
    ) : DataStore<Preferences> by delegate {
        private val holdNext = AtomicBoolean(false)

        fun holdNextUpdate() { check(holdNext.compareAndSet(false, true)) }

        override suspend fun updateData(
            transform: suspend (Preferences) -> Preferences,
        ): Preferences {
            // Initial observer reads and the cancellation drain remain real, ungated calls.
            val held = holdNext.getAndSet(false)
            return delegate.updateData { stored ->
                if (held) {
                    entered.complete(Unit)
                    release.await()
                }
                transform(stored)
            }
        }
    }
}
