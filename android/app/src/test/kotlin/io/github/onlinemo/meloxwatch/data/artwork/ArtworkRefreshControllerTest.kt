package io.github.onlinemo.meloxwatch.data.artwork

import android.content.Context
import io.github.onlinemo.meloxwatch.data.local.AndroidLocalSources
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.local.fixtureAudioProvider
import io.github.onlinemo.meloxwatch.data.local.fixtureDescriptor
import io.github.onlinemo.meloxwatch.model.ArtworkReadPath
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class ArtworkRefreshControllerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun confirmationFreezesVisibleTargetsAndPublishesRealBoundedProgress() = runBlocking {
        val audio = File(temporary.root, "audio.mp3").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val thumbnail = File(temporary.root, "cover.png").apply { writeBytes(png()) }
        fixtureAudioProvider("refresh-covers", audio).apply { this.thumbnail = thumbnail }
        val io = LocalProviderIo()
        val repository = repository(io)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = ArtworkRefreshController(repository, io, scope)
            val descriptor = fixtureDescriptor("refresh-covers", audio)
            val targets = mutableListOf(descriptor, descriptor, descriptor.copy(aliasId = "second"))
            val preview = controller.prepareVisible(targets, ArtworkReadPath.SYSTEM_THUMBNAIL)
            targets.clear()
            assertEquals(2, preview.targets.size)
            expect(ArtworkIssue.BUSY) { controller.start(preview.copy(targetPx = 192)) }
            val refreshId = controller.start(preview)
            val state = terminal(controller, refreshId, scope)
            assertEquals(ArtworkRefreshPhase.COMPLETED, state.phase)
            assertEquals(2, state.total)
            assertEquals(2, state.processed)
            assertEquals(2, state.updated)
            assertEquals(0, state.failed)
            expect(ArtworkIssue.INVALID_IMAGE) {
                controller.prepareVisible(List(ArtworkLimits.VISIBLE_REFRESH_ITEMS + 1) { descriptor }, ArtworkReadPath.LOCAL_EXTRACT)
            }
            expect(ArtworkIssue.INVALID_IMAGE) { controller.prepareVisible(emptyList(), ArtworkReadPath.LOCAL_EXTRACT) }
        } finally { scope.cancel() }
    }

    @Test fun cancellationWaitsForPhysicalIoAndKeepsThePreviouslyDisplayedLeaseUsable() = runBlocking {
        withTimeout(20_000) {
            val audio = File(temporary.root, "audio.mp3").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val thumbnail = File(temporary.root, "cover.png").apply { writeBytes(png()) }
            val provider = fixtureAudioProvider("cancel-covers", audio).apply { this.thumbnail = thumbnail }
            val io = LocalProviderIo()
            val repository = repository(io)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val descriptor = fixtureDescriptor("cancel-covers", audio)
            val display = repository.acquire(descriptor, ArtworkReadPath.SYSTEM_THUMBNAIL)!!
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            provider.beforeThumbnail = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
            try {
                val controller = ArtworkRefreshController(repository, io, scope)
                val preview = controller.prepareVisible(listOf(descriptor), ArtworkReadPath.SYSTEM_THUMBNAIL)
                val id = controller.start(preview)
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                controller.cancel(id)
                assertEquals(ArtworkRefreshPhase.STOPPING, controller.state.value?.phase)
                try { io.read { Unit }; throw AssertionError("Expected physical channel ownership") }
                catch (error: SourceReadException) { assertEquals(SourceReadIssue.BUSY, error.issue) }
                release.countDown()
                val state = terminal(controller, id, scope)
                assertEquals(ArtworkRefreshPhase.CANCELLED, state.phase)
                assertEquals(0, state.processed)
                assertFalse(display.bitmap.isRecycled)
                repository.acquire(descriptor, ArtworkReadPath.SYSTEM_THUMBNAIL)!!.use { assertSame(display.bitmap, it.bitmap) }
            } finally { release.countDown(); display.close(); scope.cancel() }
        }
    }

    @Test fun aBackgroundedControllerNeedsAForegroundBeforeItCanStartTheConfirmedScope() = runBlocking {
        val audio = File(temporary.root, "audio.mp3").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        fixtureAudioProvider("background-covers", audio)
        val io = LocalProviderIo()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = ArtworkRefreshController(repository(io), io, scope)
            val preview = controller.prepareVisible(listOf(fixtureDescriptor("background-covers", audio)), ArtworkReadPath.LOCAL_EXTRACT)
            controller.onBackground()
            expect(ArtworkIssue.BUSY) { controller.start(preview) }
            controller.onForeground()
            val state = terminal(controller, controller.start(preview), scope)
            assertEquals(ArtworkRefreshPhase.COMPLETED, state.phase)
            assertEquals(1, state.missing)
        } finally { scope.cancel() }
    }

    private suspend fun terminal(controller: ArtworkRefreshController, id: String, scope: CoroutineScope): ArtworkRefreshState = withTimeout(20_000) {
        val state = controller.state.filterNotNull().first { it.refreshId == id && it.phase in
            setOf(ArtworkRefreshPhase.COMPLETED, ArtworkRefreshPhase.CANCELLED, ArtworkRefreshPhase.PAUSED_BACKGROUND, ArtworkRefreshPhase.FAILED) }
        scope.coroutineContext[Job]!!.children.toList().joinAll()
        state
    }
    private suspend fun expect(issue: ArtworkIssue, action: suspend () -> Any?) {
        try { action(); throw AssertionError("Expected $issue") }
        catch (error: ArtworkException) { assertEquals(issue, error.issue) }
    }
    private fun context(): Context = RuntimeEnvironment.getApplication()
    private fun repository(io: LocalProviderIo): LocalArtworkRepository =
        LocalArtworkRepository(context(), io, AndroidLocalSources(context(), io)::validateRead)
}
