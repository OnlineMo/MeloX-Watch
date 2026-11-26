package io.github.onlinemo.meloxwatch.data.playlistsexchange

import android.content.Context
import io.github.onlinemo.meloxwatch.data.cue.CueByteFixture
import io.github.onlinemo.meloxwatch.data.cue.CueReferenceCodec
import io.github.onlinemo.meloxwatch.data.cue.RoomCueLibraryFixture
import io.github.onlinemo.meloxwatch.data.library.LocalAudioMetadata
import io.github.onlinemo.meloxwatch.data.library.RoomPlaylistFences
import io.github.onlinemo.meloxwatch.data.playlists.FrozenPlaylistInput
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistInputPage
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistLimits
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMember
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutation
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutationRequest
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistPageSource
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistReference
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistSnapshot
import io.github.onlinemo.meloxwatch.data.playlists.RoomPlaylistRepository
import io.github.onlinemo.meloxwatch.model.LocalCueReference
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

/** 原生Room登记与真实关系提交共同验回滚；合成输入不提供设备或provider验收信用。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CuePlaylistRegistrationBoundaryTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var fixture: RoomCueLibraryFixture
    private lateinit var playlists: RoomPlaylistRepository
    private val registrations = AtomicInteger()
    private var registrationCheckpoint: (suspend () -> Unit)? = null

    @Before fun open(): Unit = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        fixture = RoomCueLibraryFixture(context, File(temporary.root, "cue-registration-boundary.db"))
        fixture.open()
        val fences = RoomPlaylistFences(fixture.database)
        playlists = RoomPlaylistRepository(fixture.database, fixture.database.playlistDao(),
            { fixture.elapsed }, { EPOCH_MS }, "cue-registration-boundary", fences, fences,
            cueInputRegistrar = ::registerPortableInputs)
        playlists.initialize()
    }

    @After fun close(): Unit = runBlocking {
        if (this@CuePlaylistRegistrationBoundaryTest::fixture.isInitialized) fixture.close()
    }

    @Test(timeout = 30_000L)
    fun cancellationDuringRealPendingRegistrationRollsBackBothDomainsBeforeClaim(): Unit = runBlocking {
        withTimeout(10_000L) {
            val references = portableReferences()
            val beforeLibrary = fixture.library.overview()
            val beforePlaylists = playlists.overview()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            // 挂起点位于第一份真实pending写入之后，覆盖原提交内登记的取消盲区。
            registrationCheckpoint = {
                entered.complete(Unit)
                release.await()
            }
            val action = async(Dispatchers.IO) { create(references) }
            try {
                entered.await()
                action.cancelAndJoin()
                assertTrue(action.isCancelled)
                assertEquals(1, registrations.get())
                assertNull(playlists.snapshot(PLAYLIST_ID))
                assertNull(fixture.database.playlistDao().receipt(CREATE_OPERATION))
                for (reference in references) {
                    assertNull(fixture.database.libraryDao().track(reference.trackKey.trackId))
                    assertNull(fixture.database.libraryDao().currentTrack(reference.trackKey.trackId))
                }
                assertEquals(beforeLibrary, fixture.library.overview())
                assertEquals(beforePlaylists, playlists.overview())
            } finally {
                // 失败也释放实际登记任务，收束后才关闭数据库夹具。
                release.complete(Unit)
                withContext(NonCancellable) { action.cancelAndJoin() }
                registrationCheckpoint = null
            }
        }
    }

    @Test(timeout = 30_000L)
    fun renamingConfirmedCueMembersPreservesIdentitiesAndNeverRepeatsRegistration(): Unit = runBlocking {
        withTimeout(10_000L) {
            val references = portableReferences()
            val created = create(references)
            val originalMembers = members(created)
            val beforeLibrary = fixture.library.overview()
            val count = registrations.get()
            assertEquals(references.size, count)
            val request = PlaylistMutationRequest("cue-registration-rename", PLAYLIST_ID, created.revision,
                PlaylistMutation.Rename("Renamed CUE order"))
            val renamed = playlists.apply(request)
            val snapshot = checkNotNull(renamed.snapshot)
            assertEquals("Renamed CUE order", snapshot.name)
            assertEquals(originalMembers, members(snapshot))
            assertEquals(count, registrations.get())
            assertEquals(beforeLibrary, fixture.library.overview())
            assertTrue(playlists.apply(request).wasReplay)
            assertEquals(count, registrations.get())
            for (reference in references) {
                val saved = checkNotNull(fixture.database.libraryDao().currentTrack(reference.trackKey.trackId))
                assertEquals(reference, CueReferenceCodec.decode(checkNotNull(saved.cueReference)))
            }
        }
    }

    private fun portableReferences(): List<LocalCueReference> {
        val source = CueByteFixture()
        return listOf(source.reference(1).portable(), source.reference(2).portable())
    }

    private suspend fun create(references: List<LocalCueReference>): PlaylistSnapshot {
        val input = FrozenPlaylistInput("cue-registration-input", 1L, references.size.toLong())
        val request = PlaylistMutationRequest(CREATE_OPERATION, PLAYLIST_ID, 0L,
            PlaylistMutation.Create("Confirmed CUE order", input))
        return checkNotNull(playlists.apply(request, PlaylistPageSource { offset, limit ->
            val end = minOf(references.size.toLong(), offset + limit).toInt()
            val page = references.subList(offset.toInt(), end).map { reference ->
                PlaylistReference(reference.trackKey, titleHint = "CUE ${reference.identity.trackNumber}",
                    durationMs = reference.identity.durationUs / 1000L, cueReference = reference)
            }
            PlaylistInputPage(input, offset, page, end.toLong() == input.totalCount)
        }).snapshot)
    }

    private suspend fun registerPortableInputs(generation: String) {
        var first = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = fixture.database.playlistDao().membersPage(generation, first, PlaylistLimits.PAGE_SIZE)
            if (page.isEmpty()) break
            for (member in page) {
                currentCoroutineContext().ensureActive()
                val reference = member.cueReference?.let { CueReferenceCodec.decode(it, portableOnly = true) } ?: continue
                fixture.library.registerPortableCueHintUnlocked(reference,
                    LocalAudioMetadata(title = member.titleHint, artist = member.artistHint, album = member.albumHint,
                        durationMs = member.durationMs ?: reference.identity.durationUs / 1000L,
                        trackNumber = reference.identity.trackNumber), "CUE ${reference.identity.trackNumber}")
                registrations.incrementAndGet()
                registrationCheckpoint?.invoke()
            }
            first = Math.addExact(page.last().ordinal, 1L)
        }
    }

    private suspend fun members(snapshot: PlaylistSnapshot): List<PlaylistMember> {
        val view = playlists.openView(snapshot.playlistId)
        return try {
            val all = mutableListOf<PlaylistMember>()
            var first = 0L
            while (true) {
                val page = playlists.page(view, first)
                all += page.members
                first = page.nextOrdinal ?: break
            }
            all
        } finally { playlists.closeView(view.viewId) }
    }

    private companion object {
        const val EPOCH_MS = 1_790_000_000_000L
        const val PLAYLIST_ID = "cue-registration-list"
        const val CREATE_OPERATION = "cue-registration-create"
    }
}
