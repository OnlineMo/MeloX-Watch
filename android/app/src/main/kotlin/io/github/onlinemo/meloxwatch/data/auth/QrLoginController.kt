package io.github.onlinemo.meloxwatch.data.auth

import android.os.SystemClock
import io.github.onlinemo.meloxwatch.data.netease.LoginQrKey
import io.github.onlinemo.meloxwatch.data.netease.NeteaseLoginApi
import io.github.onlinemo.meloxwatch.data.netease.QrCheckPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal object QrLoginLimits {
    const val POLL_INTERVAL_MS = 2_500L
    const val KEY_LIFETIME_MS = 120_000L
    const val FLOW_LIFETIME_MS = 240_000L
    const val POLLS_PER_KEY = 48
    const val TOTAL_POLLS = 96
    const val KEY_REQUESTS = 2
}

enum class QrLoginPhase { HIDDEN, OFFLINE, CREATING, WAITING_SCAN, WAITING_CONFIRMATION, VERIFYING, SAVING, SIGNED_IN, EXPIRED, ERROR }

internal class QrLoginState(
    val phase: QrLoginPhase,
    val generation: Long,
    val qr: LoginQrKey? = null,
    val issue: AuthIssue? = null,
) {
    override fun toString(): String = "QrLoginState(phase=$phase, generation=$generation, issue=$issue, redacted)"
}

internal interface LoginClock {
    fun nowElapsedMs(): Long
    suspend fun wait(milliseconds: Long)
}

private object AndroidLoginClock : LoginClock {
    override fun nowElapsedMs(): Long = SystemClock.elapsedRealtime()
    override suspend fun wait(milliseconds: Long) = delay(milliseconds)
}

/** A visible page owns one bounded run. Offline pauses preserve its deadline and consumed budgets. */
internal class QrLoginController(
    private val api: NeteaseLoginApi,
    private val sessions: SessionRepository,
    private val scope: CoroutineScope,
    private val clock: LoginClock = AndroidLoginClock,
    private val autoRefreshExpiredOnce: Boolean = true,
) : AutoCloseable {
    private val memory = Any()
    private var generation = 0L
    private var visible = false
    private var online = false
    private var closed = false
    private var run: PageRun? = null
    private var intent: Intent? = null
    private var job: Job? = null
    private val mutableState = MutableStateFlow(QrLoginState(QrLoginPhase.HIDDEN, 0))
    val state: StateFlow<QrLoginState> = mutableState.asStateFlow()

    fun enter(networkAvailable: Boolean) {
        val work = synchronized(memory) {
            if (closed || visible) return
            visible = true
            online = networkAvailable
            run = newRun()
            restartLocked()
        }
        work.dispatch()
    }

    fun refresh() {
        val work = synchronized(memory) {
            if (closed || !visible) return
            run = newRun()
            restartLocked()
        }
        work.dispatch()
    }

    fun networkChanged(networkAvailable: Boolean) {
        val work = synchronized(memory) {
            if (closed || !visible || online == networkAvailable) return
            online = networkAvailable
            if (run?.terminal == true) return
            restartLocked()
        }
        work.dispatch()
    }

    fun leave() {
        val old = synchronized(memory) {
            if (!visible && intent == null) return
            visible = false
            nextGeneration()
            intent = null
            run = null
            mutableState.value = QrLoginState(QrLoginPhase.HIDDEN, generation)
            job.also { job = null }
        }
        old?.cancel()
    }

    fun logout(): Job {
        leave()
        val operation = sessions.beginLogout()
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { sessions.finishLogout(operation) }
            catch (_: AuthException) { /* SessionSnapshot retains the explicit persistence failure. */ }
        }
    }

    private fun newRun(): PageRun = PageRun(clock.nowElapsedMs(), sessions.loginEpoch())

    private fun restartLocked(): Work {
        val captured = run ?: return Work(job, null)
        nextGeneration()
        val nextIntent = Intent(generation, captured)
        intent = nextIntent
        mutableState.value = QrLoginState(if (online) captured.waitingPhase.takeIf { captured.key != null }
            ?: QrLoginPhase.CREATING else QrLoginPhase.OFFLINE, generation, captured.key)
        val previous = job
        val next = if (online) scope.launch(start = CoroutineStart.LAZY) { execute(nextIntent) } else null
        job = next
        return Work(previous, next)
    }

    private suspend fun execute(expected: Intent) {
        try {
            while (current(expected)) {
                val page = expected.page
                if (expired(page)) {
                    publishFinal(expected, QrLoginPhase.EXPIRED)
                    return
                }
                if (synchronized(memory) {
                    if (!currentLocked(expected)) return
                    page.key == null
                }) {
                    // A cancelled key request has an unknown outcome. Connectivity alone never starts another.
                    val requested = synchronized(memory) {
                        if (!currentLocked(expected)) return
                        if (page.keyRequests != 0 && !page.allowNextKey) false
                        else if (page.keyRequests >= QrLoginLimits.KEY_REQUESTS) false
                        else {
                            page.keyRequests++
                            page.allowNextKey = false
                            true
                        }
                    }
                    if (!requested) throw AuthException(AuthIssue.NETWORK_UNAVAILABLE)
                    publish(expected, QrLoginPhase.CREATING)
                    val key = withinBudget(page) { api.createKey() }
                    currentCoroutineContext().ensureActive()
                    synchronized(memory) {
                        if (!currentLocked(expected)) return
                        page.key = key
                        page.keyDeadline = minOf(page.deadline, deadlineAfter(clock.nowElapsedMs(), QrLoginLimits.KEY_LIFETIME_MS))
                        page.keyPolls = 0
                        page.waitingPhase = QrLoginPhase.WAITING_SCAN
                    }
                }
                val window = synchronized(memory) {
                    if (!currentLocked(expected)) return
                    val key = page.key ?: return
                    PollWindow(key, page.keyDeadline, page.keyPolls, page.waitingPhase)
                }
                publish(expected, window.phase)
                val remaining = window.deadline - clock.nowElapsedMs()
                if (remaining <= 0 || window.polls >= QrLoginLimits.POLLS_PER_KEY) {
                    refreshExpired(expected)
                    return
                }
                clock.wait(minOf(QrLoginLimits.POLL_INTERVAL_MS, remaining))
                currentCoroutineContext().ensureActive()
                if (!current(expected)) return
                if (clock.nowElapsedMs() >= window.deadline) {
                    refreshExpired(expected)
                    return
                }
                synchronized(memory) {
                    if (!currentLocked(expected)) return
                    if (page.totalPolls >= QrLoginLimits.TOTAL_POLLS) {
                        publishLocked(expected, QrLoginPhase.EXPIRED)
                        return
                    }
                    page.totalPolls++
                    page.keyPolls++
                }
                val result = try { withinBudget(page, window.deadline) { api.checkKey(window.key) } }
                catch (_: QrDeadlineException) {
                    refreshExpired(expected)
                    return
                }
                currentCoroutineContext().ensureActive()
                if (!current(expected)) return
                when (result.phase) {
                    QrCheckPhase.EXPIRED -> {
                        refreshExpired(expected)
                        return
                    }
                    QrCheckPhase.WAITING_SCAN -> synchronized(memory) {
                        if (currentLocked(expected)) publishLocked(expected, page.waitingPhase)
                    }
                    QrCheckPhase.WAITING_CONFIRMATION -> synchronized(memory) {
                        if (!currentLocked(expected)) return
                        page.waitingPhase = QrLoginPhase.WAITING_CONFIRMATION
                        publishLocked(expected, page.waitingPhase)
                    }
                    QrCheckPhase.AUTHORIZED -> {
                        val credential = result.credential ?: throw AuthException(AuthIssue.INVALID_RESPONSE)
                        publish(expected, QrLoginPhase.VERIFYING)
                        val verified = withinBudget(page) { api.verify(credential) }
                        currentCoroutineContext().ensureActive()
                        if (!current(expected)) return
                        publish(expected, QrLoginPhase.SAVING)
                        val saved = sessions.commit(verified, page.logoutEpoch, page.deadline - clock.nowElapsedMs()) { accept ->
                            synchronized(memory) {
                                if (!currentLocked(expected) || expired(page) || !accept()) false
                                else {
                                    // Accepted persistence may finish after disconnect/leave. This QR can never poll again.
                                    page.terminal = true
                                    page.key = null
                                    true
                                }
                            }
                        }
                        currentCoroutineContext().ensureActive()
                        if (saved) publishFinal(expected, QrLoginPhase.SIGNED_IN)
                        else publishFinal(expected, QrLoginPhase.ERROR, AuthIssue.LOGIN_REQUIRED)
                        return
                    }
                }
            }
        } catch (_: QrDeadlineException) {
            publishFinal(expected, QrLoginPhase.EXPIRED)
        } catch (error: AuthException) {
            publishFinal(expected, QrLoginPhase.ERROR, error.issue)
        } catch (error: CancellationException) {
            throw error
        }
    }

    private fun refreshExpired(expected: Intent) {
        val work = synchronized(memory) {
            if (!currentLocked(expected)) return
            val page = expected.page
            if (!autoRefreshExpiredOnce || page.autoRefreshed || expired(page) || page.keyRequests >= QrLoginLimits.KEY_REQUESTS) {
                publishLocked(expected, QrLoginPhase.EXPIRED)
                return
            }
            page.autoRefreshed = true
            page.allowNextKey = true
            page.key = null
            page.keyDeadline = 0
            page.waitingPhase = QrLoginPhase.WAITING_SCAN
            // A new intent invalidates every callback for the old key while preserving the total run budget.
            restartLocked()
        }
        work.dispatch()
    }

    private suspend fun <T : Any> withinBudget(page: PageRun, deadline: Long = page.deadline, action: suspend () -> T): T {
        val remaining = minOf(page.deadline, deadline) - clock.nowElapsedMs()
        if (remaining <= 0) throw QrDeadlineException()
        return withTimeoutOrNull(remaining) { action() } ?: throw QrDeadlineException()
    }
    private fun expired(page: PageRun): Boolean = clock.nowElapsedMs() >= page.deadline
    private fun current(expected: Intent): Boolean = synchronized(memory) { currentLocked(expected) }
    private fun currentIntentLocked(expected: Intent): Boolean = !closed && visible && intent === expected
    private fun currentLocked(expected: Intent): Boolean = currentIntentLocked(expected) && online && !expected.page.terminal
    private fun publish(expected: Intent, phase: QrLoginPhase, issue: AuthIssue? = null) = synchronized(memory) {
        if (currentLocked(expected)) publishLocked(expected, phase, issue)
    }
    private fun publishFinal(expected: Intent, phase: QrLoginPhase, issue: AuthIssue? = null) = synchronized(memory) {
        if (currentIntentLocked(expected)) publishLocked(expected, phase, issue)
    }
    private fun publishLocked(expected: Intent, phase: QrLoginPhase, issue: AuthIssue? = null) {
        if (phase in TERMINAL_PHASES) {
            expected.page.terminal = true
            expected.page.key = null
        }
        mutableState.value = QrLoginState(phase, expected.generation,
            if (phase in QR_PHASES) expected.page.key else null, issue)
    }
    private fun nextGeneration() {
        if (generation == Long.MAX_VALUE) throw AuthException(AuthIssue.CLOSED)
        generation++
    }

    override fun close() {
        val old = synchronized(memory) {
            if (closed) return
            closed = true
            visible = false
            nextGeneration()
            intent = null
            run = null
            mutableState.value = QrLoginState(QrLoginPhase.HIDDEN, generation)
            job.also { job = null }
        }
        old?.cancel()
    }

    private class PageRun(start: Long, val logoutEpoch: Long) {
        val deadline = deadlineAfter(start, QrLoginLimits.FLOW_LIFETIME_MS)
        var key: LoginQrKey? = null
        var keyDeadline = 0L
        var keyPolls = 0
        var totalPolls = 0
        var keyRequests = 0
        var allowNextKey = false
        var autoRefreshed = false
        var terminal = false
        var waitingPhase = QrLoginPhase.WAITING_SCAN
    }
    private class Intent(val generation: Long, val page: PageRun)
    private class PollWindow(val key: LoginQrKey, val deadline: Long, val polls: Int, val phase: QrLoginPhase)
    private class QrDeadlineException : Exception()
    private class Work(private val previous: Job?, private val next: Job?) {
        fun dispatch() {
            previous?.cancel()
            next?.start()
        }
    }
    companion object {
        private val QR_PHASES = setOf(QrLoginPhase.WAITING_SCAN, QrLoginPhase.WAITING_CONFIRMATION, QrLoginPhase.OFFLINE)
        private val TERMINAL_PHASES = setOf(QrLoginPhase.SIGNED_IN, QrLoginPhase.EXPIRED, QrLoginPhase.ERROR)
        private fun deadlineAfter(now: Long, duration: Long): Long = if (now > Long.MAX_VALUE - duration) Long.MAX_VALUE else now + duration
    }
}
