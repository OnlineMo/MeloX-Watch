package io.github.onlinemo.meloxwatch.playback

import android.app.Application
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesState
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesWriteResult
import io.github.onlinemo.meloxwatch.data.preferences.ResetConfirmation
import io.github.onlinemo.meloxwatch.data.preferences.UserPreferencesRepository
import io.github.onlinemo.meloxwatch.model.PlaybackBackendKind
import io.github.onlinemo.meloxwatch.model.UserPreferences
import io.github.onlinemo.meloxwatch.model.UserPreferencesDraft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Empty owners exercise real bootstrap/teardown; a quarantined owner never allocates an engine. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class QuarantinedPlaybackOwnerTest {
    @Test fun unknownReleaseSurvivesOwnerCloseRecreationPreferenceChangesAndExplicitRetry() = runBlocking {
        val guard = PlaybackEngineReleaseGuard()
        guard.quarantine(guard.acquire())
        repeat(2) {
            val fixture = OwnerFixture(guard)
            try {
                val owner = fixture.owner
                owner.initializePreferences()
                owner.observePreferences()
                assertBlocked(owner)
                assertTrue(owner.retryBackendIfNeeded())
                assertBlocked(owner)
                for (kind in listOf(PlaybackBackendKind.SYSTEM_MEDIA_PLAYER, PlaybackBackendKind.MEDIA3)) {
                    fixture.preferences.publish(kind)
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    assertEquals(kind, owner.state.value.requestedBackend)
                    assertBlocked(owner)
                    assertFalse(owner.requestPlay())
                }
                assertTrue(fixture.issues.all { issue -> issue == PlaybackBackendIssue.SWITCH_FAILED })
            } finally { fixture.close() }
            assertTrue(guard.blocksCreation())
            assertTrue(guard.hasUnconfirmedRelease())
        }
    }

    @Test fun confirmedNormalOwnerCloseAllowsANewOwnerToAllocateItsSingleEngine() {
        val guard = PlaybackEngineReleaseGuard()
        val first = OwnerFixture(guard)
        try {
            assertTrue(first.owner.initialPlayer is RoutingPlaybackPlayer)
            assertEquals(PlaybackBackendKind.MEDIA3, first.owner.state.value.actualBackend)
            assertTrue(guard.blocksCreation())
        } finally { first.close() }
        assertFalse(guard.blocksCreation())

        val next = OwnerFixture(guard)
        try {
            assertTrue(next.owner.initialPlayer is RoutingPlaybackPlayer)
            assertEquals(PlaybackBackendKind.MEDIA3, next.owner.state.value.actualBackend)
            assertNull(next.owner.state.value.issue)
            assertTrue(guard.blocksCreation())
        } finally { next.close() }
        assertFalse(guard.blocksCreation())
    }

    @Test fun anotherLiveOwnerCannotAllocateAnEngineOrClearTheFirstOwnersLease() {
        val guard = PlaybackEngineReleaseGuard()
        val first = OwnerFixture(guard)
        try {
            assertTrue(first.owner.initialPlayer is RoutingPlaybackPlayer)
            val second = OwnerFixture(guard)
            try {
                assertTrue(second.owner.initialPlayer is QuarantinedPlaybackBackend)
                assertNull(second.owner.state.value.actualBackend)
                assertFalse(second.owner.hasUsableBackend)
                assertFalse(second.owner.requestPlay())
            } finally { second.close() }
            assertTrue(guard.blocksCreation())
            assertFalse(guard.hasUnconfirmedRelease())
            assertEquals(PlaybackBackendKind.MEDIA3, first.owner.state.value.actualBackend)
        } finally { first.close() }
        assertFalse(guard.blocksCreation())
    }

    private fun assertBlocked(owner: ServicePlaybackOwner) {
        assertTrue(owner.initialPlayer is QuarantinedPlaybackBackend)
        assertNull(owner.state.value.actualBackend)
        assertEquals(PlaybackBackendIssue.SWITCH_FAILED, owner.state.value.issue)
        assertFalse(owner.hasUsableBackend)
        assertFalse(owner.initialPlayer.playWhenReady)
        assertFalse(owner.initialPlayer.isPlaying)
        assertEquals(Player.STATE_IDLE, owner.initialPlayer.playbackState)
        assertEquals("RELEASE_UNCONFIRMED", owner.initialPlayer.playerError?.message)
    }

    private class OwnerFixture(guard: PlaybackEngineReleaseGuard) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val preferences = MutablePreferences()
        val issues = mutableListOf<PlaybackBackendIssue>()
        val owner: ServicePlaybackOwner

        init {
            val application: Application = RuntimeEnvironment.getApplication()
            Shadows.shadowOf(application).grantPermissions(application.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
            owner = ServicePlaybackOwner(application, scope, preferences, LocalProviderIo(),
                ensureForeground = { throw AssertionError("An empty owner cannot request playback foreground") },
                onFailure = issues::add,
                releaseGuard = guard)
        }

        fun close() {
            try {
                owner.close()
                Shadows.shadowOf(Looper.getMainLooper()).idle()
            }
            finally { scope.cancel() }
        }
    }

    private class MutablePreferences : UserPreferencesRepository {
        private val values = MutableStateFlow<PreferencesState>(PreferencesState.Ready(UserPreferences()))
        override val state = values.asStateFlow()
        fun publish(kind: PlaybackBackendKind) {
            val old = (values.value as PreferencesState.Ready).preferences
            values.value = PreferencesState.Ready(old.copy(revision = old.revision + 1L, playbackBackend = kind))
        }
        override suspend fun save(expectedRevision: Long, draft: UserPreferencesDraft): PreferencesWriteResult =
            throw AssertionError("Playback owner cannot write preferences")
        override suspend fun reset(expectedRevision: Long, confirmation: ResetConfirmation): PreferencesWriteResult =
            throw AssertionError("Playback owner cannot reset preferences")
        override fun retryRead() { throw AssertionError("Playback owner cannot retry preference I/O implicitly") }
    }
}
