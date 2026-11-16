package io.github.onlinemo.meloxwatch.playback.sleep

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** 两个主线程任务分别替换，不为暂停中的定时新建wake lock或精确闹钟。 */
internal class AndroidSleepTimerScheduler : SleepTimerScheduler {
    private val handler = Handler(Looper.getMainLooper())
    private var deadlineTask: Runnable? = null
    private var summaryTask: Runnable? = null
    private var closed = false

    override fun deadline(token: SleepTimerToken, atElapsedMs: Long, task: () -> Unit) {
        checkMain()
        clearDeadline()
        if (closed) return
        val runnable = Runnable {
            deadlineTask = null
            if (!closed) task()
        }
        deadlineTask = runnable
        handler.postDelayed(runnable, delayUntil(atElapsedMs))
    }

    override fun summary(token: SleepTimerToken, atElapsedMs: Long, task: () -> Unit) {
        checkMain()
        clearSummary()
        if (closed) return
        val runnable = Runnable {
            summaryTask = null
            if (!closed) task()
        }
        summaryTask = runnable
        handler.postDelayed(runnable, delayUntil(atElapsedMs))
    }

    override fun clearDeadline() {
        checkMain()
        deadlineTask?.let(handler::removeCallbacks)
        deadlineTask = null
    }

    override fun clearSummary() {
        checkMain()
        summaryTask?.let(handler::removeCallbacks)
        summaryTask = null
    }

    override fun close() {
        checkMain()
        closed = true
        clearDeadline()
        clearSummary()
    }

    // Handler基于uptime；播放时使用已有音频wake lock。深睡延迟仍在发声/自然前进前重核elapsed。
    private fun delayUntil(atElapsedMs: Long): Long = (atElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper())
}
