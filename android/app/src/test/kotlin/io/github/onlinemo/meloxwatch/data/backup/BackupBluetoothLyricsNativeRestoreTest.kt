package io.github.onlinemo.meloxwatch.data.backup

import android.content.Context
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontBackupAccess
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesWriteResult
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
class BackupBluetoothLyricsNativeRestoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var root: File
    private lateinit var fixture: NativeBackupFixture
    private var fixtureOpen = false
    private val playerFile: File get() = File(root, "preferences/player.preferences_pb")

    @Before fun open() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        root = temporary.newFolder("app")
        openFixture()
    }

    @After fun close() {
        try { runBlocking { if (fixtureOpen) fixture.close() } }
        finally { fixtureOpen = false; Dispatchers.resetMain() }
    }

    @Test(timeout = 60_000L)
    fun identicalPortablePlayerRestoresDisableNativeActivationAndPersistOneRevision() = runBlocking {
        val enabled = seed(true)
        val expected = enabled.copy(revision = enabled.revision + 1L, bluetoothLyricsEnabled = false)
        withNative { fonts ->
            val before = fixture.configurations.read("player", fonts)
            val target = BackupPreferencesCodec.player(expected)
            assertTrue(before.record.sameContent(target))
            val restored = fixture.configurations.compareAndSet(before, target, fonts)
            assertEquals(expected.revision, restored.revision)
            assertEquals(expected, fixture.preferences.readForBackup())
        }
        reopen()
        assertEquals(expected, readPlayer())
    }

    @Test(timeout = 60_000L)
    fun alreadyDisabledIdenticalPlayerRestoreKeepsRevisionAndOriginalBytes() = runBlocking {
        val disabled = seed(false)
        val originalBytes = playerFile.readBytes()
        withNative { fonts ->
            val before = fixture.configurations.read("player", fonts)
            val target = BackupPreferencesCodec.player(disabled)
            assertTrue(before.record.sameContent(target))
            repeat(2) {
                assertEquals(before.revision, fixture.configurations.compareAndSet(before, target, fonts).revision)
                assertEquals(disabled, fixture.preferences.readForBackup())
            }
        }
        assertArrayEquals(originalBytes, playerFile.readBytes())
        reopen()
        assertEquals(disabled, readPlayer())
    }

    @Test(timeout = 60_000L)
    fun realDataStoreWriteFailureKeepsActivationUntilExplicitNativeRetry() = runBlocking {
        val enabled = seed(true)
        val originalBytes = playerFile.readBytes()
        val scratch = File(playerFile.absolutePath + ".tmp")
        assertTrue(scratch.mkdir())
        val blocker = File(scratch, "write-blocker")
        blocker.writeText("owned DataStore temporary write fault")
        try {
            expectIssue(BackupIssue.IO) {
                withNative { fonts ->
                    val before = fixture.configurations.read("player", fonts)
                    fixture.configurations.compareAndSet(before, BackupPreferencesCodec.player(enabled), fonts)
                }
            }
            assertEquals(enabled, readPlayer())
            assertTrue(readPlayer().bluetoothLyricsEnabled)
            assertArrayEquals(originalBytes, playerFile.readBytes())
        } finally {
            assertTrue(blocker.delete())
            assertTrue(scratch.delete())
        }
        withNative { fonts ->
            val before = fixture.configurations.read("player", fonts)
            val retried = fixture.configurations.compareAndSet(before, BackupPreferencesCodec.player(enabled), fonts)
            assertEquals(enabled.revision + 1L, retried.revision)
            assertFalse(fixture.preferences.readForBackup().bluetoothLyricsEnabled)
        }
        reopen()
        assertEquals(enabled.copy(revision = enabled.revision + 1L, bluetoothLyricsEnabled = false), readPlayer())
    }

    @Test(timeout = 60_000L)
    fun staleNativeRevisionCannotReportDisabledOrOverwriteTheNewerPlayer() = runBlocking {
        val enabled = seed(true)
        withNative { fonts ->
            val before = fixture.configurations.read("player", fonts)
            val newer = fixture.preferences.save(enabled.revision,
                enabled.asDraft().copy(restorePosition = !enabled.restorePosition)) as PreferencesWriteResult.Saved
            val newerBytes = playerFile.readBytes()
            expectIssue(BackupIssue.STALE_PREVIEW) {
                fixture.configurations.compareAndSet(before, BackupPreferencesCodec.player(enabled), fonts)
            }
            assertEquals(newer.preferences, fixture.preferences.readForBackup())
            assertTrue(fixture.preferences.readForBackup().bluetoothLyricsEnabled)
            assertArrayEquals(newerBytes, playerFile.readBytes())
            val fresh = fixture.configurations.read("player", fonts)
            val restored = fixture.configurations.compareAndSet(fresh, fresh.record, fonts)
            assertEquals(newer.preferences.revision + 1L, restored.revision)
            assertEquals(newer.preferences.copy(revision = restored.revision, bluetoothLyricsEnabled = false),
                fixture.preferences.readForBackup())
        }
    }

    private suspend fun seed(enabled: Boolean): UserPreferences = withNative {
        val before = fixture.preferences.readForBackup()
        val saved = fixture.preferences.save(before.revision,
            before.asDraft().copy(restorePosition = false, bluetoothLyricsEnabled = enabled)) as PreferencesWriteResult.Saved
        assertEquals(saved.preferences, fixture.preferences.readForBackup())
        saved.preferences
    }

    private suspend fun readPlayer(): UserPreferences = withNative { fixture.preferences.readForBackup() }

    private suspend fun openFixture() {
        fixture = NativeBackupFixture(RuntimeEnvironment.getApplication() as Context, root)
        fixtureOpen = true
        check(withTimeout(20_000L) { fixture.coordinator.initializeStorage() } is BackupStartupState.Ready)
    }

    private suspend fun reopen() {
        fixture.close()
        fixtureOpen = false
        openFixture()
    }

    private suspend fun <T> withNative(action: suspend (LyricsFontBackupAccess) -> T): T =
        withTimeout(10_000L) { acquireNative(action) }

    private suspend fun <T> acquireNative(action: suspend (LyricsFontBackupAccess) -> T): T {
        while (true) {
            var entered = false
            try {
                return fixture.barrier.exclusively {
                    entered = true
                    fixture.fonts.withSettings(action)
                }
            } catch (error: BackupException) {
                // Retry admission only; an entered native operation is never replayed by this helper.
                if (entered || error.issue != BackupIssue.BUSY) throw error
            }
            delay(5L)
        }
    }

    private suspend fun expectIssue(issue: BackupIssue, action: suspend () -> Unit) {
        val failure = try { action(); null } catch (error: BackupException) { error }
        assertEquals(issue, failure?.issue)
    }
}
