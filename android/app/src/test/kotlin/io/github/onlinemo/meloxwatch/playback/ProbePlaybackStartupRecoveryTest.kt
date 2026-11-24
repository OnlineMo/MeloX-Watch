package io.github.onlinemo.meloxwatch.playback

import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.ListenableFuture
import io.github.onlinemo.meloxwatch.MeloXApplication
import io.github.onlinemo.meloxwatch.data.backup.BackupStartupState
import io.github.onlinemo.meloxwatch.playback.dsp.ServiceDspSettingsOwner
import io.github.onlinemo.meloxwatch.playback.sleep.ServiceSleepTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.util.ReflectionHelpers

/** 真实 App startup、Service callback 与 host 围栏；空队列不构成解码或设备播放证据。 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE, application = MeloXApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ProbePlaybackStartupRecoveryTest {
    private lateinit var application: MeloXApplication
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val controllers = mutableListOf<MediaController>()
    private var serviceController: ServiceController<ProbePlaybackService>? = null

    @Before fun awaitActualApplicationStartup() = runBlocking {
        application = RuntimeEnvironment.getApplication() as MeloXApplication
        Shadows.shadowOf(application).grantPermissions(
            application.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
        )
        pumpUntil("application.backups.ready") { application.backups.startup.value !is BackupStartupState.Loading }
        assertTrue(application.backups.startup.value is BackupStartupState.Ready)
    }

    @After fun closeActualOwners() = runBlocking {
        try {
            controllers.forEach(MediaController::release)
            serviceController?.let { controller ->
                val service = controller.get()
                controller.destroy()
                val closing = field<Deferred<Unit>>(service, "closing")
                pumpUntil("cleanup.service") { closing.isCompleted }
                closing.await()
            }
            fromIo("cleanup.backups") { application.backups.closeForOwner() }
            fromIo("cleanup.fileJournal") { application.fileOperations.closeForOwner() }
        } finally {
            application.onTerminate()
            ioScope.cancel()
        }
    }

    @Test
    fun anUnavailableBackupGateCanRetryAllMissingServiceStagesOnTheSamePausedSession() = runBlocking {
        // 不伪造首个 Deferred：真实 startup 已完成，当前恢复状态独立失效。
        application.backupMutationBarrier.requireRecovery()
        assertTrue(application.filePlaybackHost.isReadBlocked())
        val service = createService()
        awaitInitialization(service)
        val session = field<MediaSession>(service, "mediaSession")
        val owner = field<ServicePlaybackOwner>(service, "playbackOwner")
        val timer = field<ServiceSleepTimer>(service, "sleepTimer")
        val dsp = field<ServiceDspSettingsOwner>(service, "dspSettingsOwner")
        val controller = connect(session)
        assertFalse(field<Boolean>(service, "initializationComplete"))
        assertFalse(field<Boolean>(owner, "preferencesInitialized"))
        assertFalse(timer.state.value.initialized)
        assertFalse(controller.availableCommands.contains(Player.COMMAND_PLAY_PAUSE))
        assertFalse(session.player.playWhenReady)

        // 无 pending run 的显式恢复不关闭或重建既有 MediaSession。
        val recovered = fromIo("backup.retry") { application.backups.initializeStorage() }
        assertTrue(recovered is BackupStartupState.Ready && !recovered.recoveredPendingRun)
        assertSame(session, field<MediaSession>(service, "mediaSession"))
        assertTrue("file journal 尚未 loaded，恢复命令仍须能进入自己的读围栏",
            application.filePlaybackHost.isReadBlocked())
        val result = awaitFuture("retryRestore.first", controller.sendCustomCommand(PlaybackCommands.retryRestore, Bundle.EMPTY))
        assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
        assertTrue(field<Boolean>(service, "initializationComplete"))
        assertTrue(field<Boolean>(owner, "preferencesInitialized"))
        assertTrue(timer.state.value.initialized)
        assertFalse(application.filePlaybackHost.isReadBlocked())
        pumpUntil("controller.playCommands") { controller.availableCommands.contains(Player.COMMAND_PLAY_PAUSE) }
        assertSame(owner, field<ServicePlaybackOwner>(service, "playbackOwner"))
        assertSame(session, field<MediaSession>(service, "mediaSession"))
        assertFalse(session.player.playWhenReady)

        val timerLease = field<Any?>(timer, "lease")
        assertNotNull(timerLease)
        val dspObserver = field<Job>(dsp, "observer")
        val preferencesObserver = field<Job>(owner, "preferenceObserver")
        val startup = field<Any>(service, "playbackStartup")
        val bootId = field<String?>(startup, "bootId")
        val generation = owner.state.value.generation
        assertTrue(field<Boolean>(startup, "bootIdentityRead"))
        assertEquals(SessionResult.RESULT_SUCCESS,
            awaitFuture("retryRestore.idempotent", controller.sendCustomCommand(PlaybackCommands.retryRestore, Bundle.EMPTY)).resultCode)
        assertSame(timerLease, field<Any?>(timer, "lease"))
        assertSame(dspObserver, field<Job>(dsp, "observer"))
        assertSame(preferencesObserver, field<Job>(owner, "preferenceObserver"))
        assertEquals(bootId, field<String?>(startup, "bootId"))
        assertEquals(generation, owner.state.value.generation)
        assertSame(session, field<MediaSession>(service, "mediaSession"))
        assertFalse(session.player.playWhenReady)
        assertNull(field<Job?>(service, "activeCommand"))
    }

    @Test
    fun aReservedStartupAllocatesNothingAndTheSameServiceRebuildsOnlyAfterTheHostAllowsReads() = runBlocking {
        val controllerInfo = connectedControllerInfo()
        fromIo("fileJournal.prime") { application.fileOperations.assertPlaybackReadable() }
        val host = application.filePlaybackHost
        host.reserve()
        try {
            val service = createService()
            assertNull(field<MediaSession?>(service, "mediaSession"))
            assertNull(field<ServicePlaybackOwner?>(service, "playbackOwner"))
            assertNull(field<ServiceDspSettingsOwner?>(service, "dspSettingsOwner"))
            assertNull(field<ServiceSleepTimer?>(service, "sleepTimer"))
            assertTrue(field<Boolean>(service, "resourcesClosed"))
            assertNull(service.onGetSession(controllerInfo))
            assertTrue(host.isReadBlocked())
        } finally {
            host.leave()
        }
        val service = checkNotNull(serviceController).get()
        val session = service.onGetSession(controllerInfo)
        assertNotNull(session)
        awaitInitialization(service)
        val owner = field<ServicePlaybackOwner>(service, "playbackOwner")
        assertTrue(field<Boolean>(service, "initializationComplete"))
        assertFalse(field<Boolean>(service, "resourcesClosed"))
        assertTrue(host.allowsOwner(service))
        assertSame(session, service.onGetSession(controllerInfo))
        assertSame(owner, field<ServicePlaybackOwner>(service, "playbackOwner"))
        assertFalse(checkNotNull(session).player.playWhenReady)
    }

    private fun createService(): ProbePlaybackService {
        check(serviceController == null)
        return Robolectric.buildService(ProbePlaybackService::class.java).create().also {
            serviceController = it
        }.get()
    }

    private suspend fun awaitInitialization(service: ProbePlaybackService) {
        val initialization = field<Job>(service, "initialization")
        pumpUntil("service.initialization") { initialization.isCompleted }
        initialization.join()
    }

    private suspend fun connect(session: MediaSession): MediaController =
        awaitFuture("controller.connect", MediaController.Builder(application, session.token).buildAsync()).also(controllers::add)

    /** 获取实际连接身份使用无原生资源的暂停门面，随后退出该 fixture 会话。 */
    private suspend fun connectedControllerInfo(): MediaSession.ControllerInfo {
        val player = QuarantinedPlaybackBackend(PlaybackEngineReleaseGuard())
        val session = MediaSession.Builder(application, player).build()
        try {
            val controller = connect(session)
            pumpUntil("fixture.controllerIdentity") { session.connectedControllers.isNotEmpty() }
            return session.connectedControllers.single().also { controller.release() }
        } finally {
            session.release()
            player.release()
        }
    }

    private suspend fun <T> fromIo(stage: String, action: suspend () -> T): T {
        val work = ioScope.async { action() }
        pumpUntil(stage) { work.isCompleted }
        return work.await()
    }

    private suspend fun <T> awaitFuture(stage: String, future: ListenableFuture<T>): T {
        pumpUntil(stage) { future.isDone }
        return future.get()
    }

    /** 原等待驱动和期限保持；完整输出标记区分控制器回执与实际初始化停点。 */
    private suspend fun pumpUntil(stage: String, complete: () -> Boolean) {
        reportWait(stage, "start")
        var observedInitialization = initializationStage()
        try {
            withTimeout(20_000L) {
                while (!complete()) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    val currentInitialization = initializationStage()
                    if (currentInitialization != observedInitialization) {
                        observedInitialization = currentInitialization
                        reportWait(stage, "initialization")
                    }
                    delay(1L)
                }
                Shadows.shadowOf(Looper.getMainLooper()).idle()
            }
            reportWait(stage, "end")
        } catch (timeout: TimeoutCancellationException) {
            val diagnostic = "MELOX_PROBE_TIMEOUT wait=$stage " + waitSnapshot()
            println(diagnostic)
            timeout.addSuppressed(IllegalStateException(diagnostic))
            throw timeout
        }
    }

    private fun initializationStage(): String {
        val service = serviceController?.get() ?: return "NO_SERVICE"
        val startup = field<Any?>(service, "playbackStartup") ?: return "NO_OWNER"
        return field(startup, "stage")
    }

    private fun jobState(job: Job?): String = if (job == null) "none" else
        "active:${job.isActive},completed:${job.isCompleted},cancelled:${job.isCancelled}"

    /** 只记录固定阶段、布尔状态与调度时间；诊断读取失败继续保留原等待异常。 */
    private fun waitSnapshot(): String = runCatching {
        val service = serviceController?.get()
        val initialization = service?.let { field<Job?>(it, "initialization") }
        val command = service?.let { field<Job?>(it, "activeCommand") }
        val completed = service?.let { field<Boolean>(it, "initializationComplete") }
        val preferences = application.preferences
        val preferencesBarrierMatches = field<Any?>(preferences, "backupBarrier") === application.backupMutationBarrier
        val nextMainTaskMs = Shadows.shadowOf(Looper.getMainLooper()).nextScheduledTaskTime.toMillis()
        "stage=${initializationStage()} initialization=${jobState(initialization)} command=${jobState(command)} " +
            "complete=$completed preferences=${preferences.state.value.javaClass.simpleName} " +
            "preferencesBarrierMatches=$preferencesBarrierMatches " +
            "backup=${application.backups.startup.value.javaClass.simpleName} " +
            "barrier=${application.backupMutationBarrier.state.value.name} " +
            "files=${application.fileOperations.state.value.javaClass.simpleName} " +
            "readBlocked=${application.filePlaybackHost.isReadBlocked()} " +
            "uptimeMs=${SystemClock.uptimeMillis()} nextMainTaskMs=$nextMainTaskMs"
    }.getOrElse { "snapshotUnavailable=${it.javaClass.simpleName}" }

    private fun reportWait(stage: String, event: String) {
        println("MELOX_PROBE_STAGE wait=$stage event=$event " + waitSnapshot())
    }

    private fun <T> field(owner: Any, name: String): T = ReflectionHelpers.getField(owner, name)
}
