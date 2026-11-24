package io.github.onlinemo.meloxwatch.data.online

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.onlinemo.meloxwatch.data.auth.QrLoginController
import io.github.onlinemo.meloxwatch.data.auth.QrLoginPhase
import io.github.onlinemo.meloxwatch.data.auth.SessionRepository
import io.github.onlinemo.meloxwatch.data.auth.AuthIssue
import io.github.onlinemo.meloxwatch.data.netease.NeteaseLoginApi
import io.github.onlinemo.meloxwatch.data.lyrics.online.OnlineLyricsIssue
import io.github.onlinemo.meloxwatch.model.OnlineCatalogIssue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** QR payloads stay in the data layer. The page receives only bounded pixels and fixed status codes. */
internal class OnlineQrGrid(val edge: Int, private val modules: BooleanArray) {
    init { require(edge in 21..185 && modules.size == edge * edge) }
    operator fun get(x: Int, y: Int): Boolean = modules[y * edge + x]
    override fun toString(): String = "OnlineQrGrid(redacted)"
}

internal data class OnlineLoginSnapshot(
    val phase: QrLoginPhase = QrLoginPhase.HIDDEN,
    val generation: Long = 0L,
    val grid: OnlineQrGrid? = null,
    val issue: OnlineCatalogIssue? = null,
) {
    override fun toString(): String = "OnlineLoginSnapshot(phase=$phase, issue=$issue, redacted)"
}

internal class OnlineLoginPresenter(
    api: NeteaseLoginApi,
    private val sessions: SessionRepository,
    private val scope: CoroutineScope,
    private val clearLyricsAccount: suspend (String) -> OnlineLyricsIssue? = { null },
) : AutoCloseable {
    @Volatile var lyricsCleanupIssue: OnlineLyricsIssue? = null
        private set
    private val controller = QrLoginController(api, sessions, scope)
    // Register the initial account before construction can return to an account switch.
    private val accountCleanup = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        var previous = sessions.snapshot.value.account
        sessions.snapshot.collect { current ->
            val old = previous
            previous = current.account
            if (old != null && old != current.account && current.issue != AuthIssue.CLOSED) {
                withContext(NonCancellable) { lyricsCleanupIssue = cleanupAccount(old.accountId) }
            }
        }
    }
    val state: Flow<OnlineLoginSnapshot> = controller.state
        .distinctUntilChangedBy { Triple(it.phase, it.generation, it.issue) }.map { value ->
        currentCoroutineContext().ensureActive()
        val grid = if (value.phase in QR_VISIBLE) value.qr?.let { key ->
            try { OnlineQrEncoder.encode(key.content()) }
            catch (_: Exception) { null }
        } else null
        currentCoroutineContext().ensureActive()
        OnlineLoginSnapshot(value.phase, value.generation, grid,
            value.issue?.let(::authCatalogIssue) ?: if (value.phase in QR_VISIBLE && grid == null) OnlineCatalogIssue.INVALID_RESPONSE else null)
    }.flowOn(Dispatchers.Default)
    fun enter(online: Boolean) = controller.enter(online)
    fun leave() = controller.leave()
    fun refresh() = controller.refresh()
    fun networkChanged(online: Boolean) = controller.networkChanged(online)
    fun logout(): Job {
        val accountId = sessions.snapshot.value.account?.accountId
        lyricsCleanupIssue = null
        val logout = controller.logout()
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            // Navigation may cancel its waiter, but the explicit logout still completes account
            // persistence and only that account's bounded lyric cleanup before releasing this job.
            withContext(NonCancellable) {
                logout.join()
                if (accountId != null) lyricsCleanupIssue = cleanupAccount(accountId)
            }
        }
    }
    private suspend fun cleanupAccount(accountId: String): OnlineLyricsIssue? = try { clearLyricsAccount(accountId) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { OnlineLyricsIssue.READ_FAILED }
    catch (_: OutOfMemoryError) { OnlineLyricsIssue.MEMORY_LIMIT }

    override fun close() {
        try { controller.close() } finally { accountCleanup.cancel() }
    }

    companion object {
        private val QR_VISIBLE = setOf(QrLoginPhase.WAITING_SCAN, QrLoginPhase.WAITING_CONFIRMATION)
    }
}

/** ZXing core 3.5.3 is an explicit QR dependency; it has no network, Android service or storage integration. */
internal object OnlineQrEncoder {
    fun encode(value: String): OnlineQrGrid {
        require(value.length in 1..2048)
        val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 4))
        require(matrix.width == matrix.height && matrix.width in 21..185)
        return OnlineQrGrid(matrix.width, BooleanArray(matrix.width * matrix.height) { index ->
            matrix[index % matrix.width, index / matrix.width]
        })
    }
}
