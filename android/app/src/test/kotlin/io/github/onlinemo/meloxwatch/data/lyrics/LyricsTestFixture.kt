package io.github.onlinemo.meloxwatch.data.lyrics

import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Assert.assertEquals

internal suspend fun parseLyrics(text: String, limits: LyricsLimits = LyricsLimits()): LyricsDocument =
    LocalLyricsParser(limits).parse(text.toByteArray(Charsets.UTF_8))

internal suspend fun expectLyricsIssue(
    issue: LyricsParseIssue,
    sourceLine: Int? = null,
    block: suspend () -> Unit,
): LyricsParseException {
    try {
        block()
    } catch (error: LyricsParseException) {
        assertEquals(issue, error.issue)
        assertEquals(sourceLine, error.sourceLineNumber)
        return error
    }
    throw AssertionError("Expected $issue")
}

internal fun expectLyricsArgumentError(block: () -> Unit) {
    try {
        block()
    } catch (_: IllegalArgumentException) {
        return
    }
    throw AssertionError("Expected an invalid lyric setting to be rejected")
}

internal suspend fun expectLyricsCancellation(block: suspend () -> Unit) {
    try {
        block()
    } catch (_: CancellationException) {
        return
    }
    throw AssertionError("Expected cancellation to propagate")
}

/** Drives the real coroutine through a yield, then lets the test cancel before its next segment. */
internal class LyricsQueuedDispatcher : CoroutineDispatcher() {
    private val pending = ArrayDeque<Runnable>()
    val pendingTaskCount: Int get() = pending.size

    override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }

    fun runNext(): Boolean {
        val task = pending.pollFirst() ?: return false
        task.run()
        return true
    }

    fun drain() {
        repeat(4_096) { if (!runNext()) return }
        throw AssertionError("The bounded fixture dispatcher did not drain")
    }
}

internal fun numberedLyrics(count: Int, prefix: String): String = buildString {
    repeat(count) { index ->
        if (index != 0) append('\n')
        append('[').append(index / 60).append(':')
        if (index % 60 < 10) append('0')
        append(index % 60).append(']').append(prefix).append(index)
    }
}
