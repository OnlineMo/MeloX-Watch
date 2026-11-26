package io.github.onlinemo.meloxwatch.data.backup

import android.content.Context
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontBackupAccess
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesWriteResult
import io.github.onlinemo.meloxwatch.data.themes.ThemeStorageRecords
import io.github.onlinemo.meloxwatch.model.UserPreferences
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class MetadataBackupNativeConfigTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var root: File
    private lateinit var fixture: NativeBackupFixture
    private var opened = false
    private val playerFile: File get() = File(root, "preferences/player.preferences_pb")

    @Before fun open() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        root = temporary.newFolder("app")
        openFixture()
    }

    @After fun close() {
        try { runBlocking { if (opened) fixture.close() } }
        finally { opened = false; Dispatchers.resetMain() }
    }

    @Test(timeout = 60_000L)
    fun equalPortablePlayerStillDisablesBothNativeDisclosuresAndPersistsOneRevision() = runBlocking {
        val enabled = seed(metadata = true, bluetooth = true)
        val expected = enabled.copy(revision = enabled.revision + 1, metadataLookupEnabled = false, bluetoothLyricsEnabled = false)
        withNative { fonts ->
            val before = fixture.configurations.read("player", fonts)
            assertFalse(before.disclosuresDisabled)
            val portable = BackupPreferencesCodec.player(expected)
            assertTrue(before.record.sameContent(portable))
            val actual = fixture.configurations.compareAndSet(before, portable, fonts)
            assertTrue(actual.disclosuresDisabled)
            assertEquals(expected, fixture.preferences.readForBackup())
            val bytes = playerFile.readBytes()
            assertEquals(actual.revision, fixture.configurations.compareAndSet(actual, portable, fonts).revision)
            assertArrayEquals(bytes, playerFile.readBytes())
        }
        fixture.close(); opened = false
        openFixture()
        withNative { assertEquals(expected, fixture.preferences.readForBackup()) }
    }

    @Test(timeout = 60_000L)
    fun failedNativeWriteCannotAcknowledgeThePortableRowAsDisabled() = runBlocking {
        val enabled = seed(metadata = true, bluetooth = false)
        val original = playerFile.readBytes()
        val scratch = File(playerFile.absolutePath + ".tmp")
        assertTrue(scratch.mkdir())
        val blocker = File(scratch, "write-blocker").apply { writeText("DataStore write fault") }
        try {
            withNative { fonts ->
                val before = fixture.configurations.read("player", fonts)
                val failure = try { fixture.configurations.compareAndSet(before, before.record, fonts); null }
                    catch (failure: BackupException) { failure }
                assertEquals(BackupIssue.IO, failure?.issue)
                assertEquals(enabled, fixture.preferences.readForBackup())
                assertFalse(fixture.configurations.read("player", fonts).disclosuresDisabled)
            }
            assertArrayEquals(original, playerFile.readBytes())
        } finally { assertTrue(blocker.delete()); assertTrue(scratch.delete()) }
        withNative { fonts ->
            val before = fixture.configurations.read("player", fonts)
            val actual = fixture.configurations.compareAndSet(before, before.record, fonts)
            assertTrue(actual.disclosuresDisabled)
            assertEquals(enabled.revision + 1, actual.revision)
            assertFalse(fixture.preferences.readForBackup().metadataLookupEnabled)
        }
    }

    @Test(timeout = 60_000L)
    fun journalRejectsAnEnabledReadbackAndResolvesALostAcknowledgmentFromTheActualOwner() = runBlocking {
        seed(metadata = true, bluetooth = false)
        val storage = BackupPrivateDirectory.open(temporary.newFolder("journal"))
        var journal = BackupRestoreJournal.open(storage)
        try {
            withNative { fonts ->
                val incoming = storage.newGeneration()
                val before = storage.newGeneration()
                journal.registerGeneration(incoming.ownership()); journal.registerGeneration(before.ownership())
                val versions = fixture.configurations.readAll(fonts)
                val run = BackupRestoreRun(stableUuid("metadata-config-run"), stableUuid("metadata-config-export"),
                    "3".repeat(64), incoming.root.name, before.root.name, setOf(BackupScope.PREFERENCES),
                    BackupMergeMode.REPLACE_SELECTED, "2".repeat(64))
                journal.begin(run, versions, ThemeStorageRecords.encode(fixture.themes.readCatalogForBackup()))
                journal.configureTargets(run.id, versions.mapValues { it.value.record })
                val player = versions.getValue("player")
                journal.planConfig(run.id, BackupConfigDirection.FORWARD, player, player.record)
                val failure = assertThrows(BackupException::class.java) { journal.confirmConfig(run.id, player) }
                assertEquals(BackupIssue.RECOVERY_REQUIRED, failure.issue)
                assertNull(journal.configs().single { it.key == "player" }.confirmedRevision)
                fixture.configurations.compareAndSet(player, player.record, fonts)
                journal.close()
                journal = BackupRestoreJournal.open(storage)
                assertNull(journal.configs().single { it.key == "player" }.confirmedRevision)
                val actual = fixture.configurations.read("player", fonts)
                assertTrue(actual.disclosuresDisabled)
                assertEquals(player.revision + 1, actual.revision)
                journal.confirmConfig(run.id, actual)
                assertEquals(actual.revision, journal.configs().single { it.key == "player" }.confirmedRevision)
                assertFalse(fixture.preferences.readForBackup().metadataLookupEnabled)
            }
        } finally { journal.close() }
    }

    private suspend fun seed(metadata: Boolean, bluetooth: Boolean): UserPreferences = withNative {
        val before = fixture.preferences.readForBackup()
        val saved = fixture.preferences.save(before.revision, before.asDraft().copy(
            metadataLookupEnabled = metadata, bluetoothLyricsEnabled = bluetooth, restorePosition = false)) as PreferencesWriteResult.Saved
        saved.preferences
    }

    private suspend fun openFixture() {
        fixture = NativeBackupFixture(RuntimeEnvironment.getApplication() as Context, root)
        opened = true
        check(withTimeout(20_000L) { fixture.coordinator.initializeStorage() } is BackupStartupState.Ready)
    }

    private suspend fun <T> withNative(action: suspend (LyricsFontBackupAccess) -> T): T =
        withTimeout(10_000L) { acquireNative(action) }

    private suspend fun <T> acquireNative(action: suspend (LyricsFontBackupAccess) -> T): T {
        while (true) {
            var entered = false
            try { return fixture.barrier.exclusively { entered = true; fixture.fonts.withSettings(action) } }
            catch (failure: BackupException) { if (entered || failure.issue != BackupIssue.BUSY) throw failure }
            delay(5)
        }
    }
}
