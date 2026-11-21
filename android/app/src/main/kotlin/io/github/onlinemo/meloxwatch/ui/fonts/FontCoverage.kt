package io.github.onlinemo.meloxwatch.ui.fonts

import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontException
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontIssue
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

/** One caller-owned text accumulator; overflow never returns a partially validated document. */
internal class FontCoverageBuilder(private val limit: Int = LyricsFontLimits.TEXT_CHARACTERS) {
    init { require(limit in 1..LyricsFontLimits.TEXT_CHARACTERS) }
    private val text = StringBuilder()

    fun append(actual: String) {
        val separator = if (text.isEmpty()) 0 else 1
        if (separator > limit - text.length || actual.length > limit - text.length - separator) {
            throw LyricsFontException(LyricsFontIssue.INVALID_INPUT)
        }
        if (separator != 0) text.append('\n')
        text.append(actual)
    }

    fun finish(): String = text.toString()
}

/** Only reads the exact page/Window getter; this must never obtain a VM or font-owner lock. */
internal suspend fun fontCoverageCheckpoint(visibleNow: () -> Boolean) {
    currentCoroutineContext().ensureActive()
    if (!visibleNow()) throw CancellationException("Font coverage page retired")
    yield()
    currentCoroutineContext().ensureActive()
    if (!visibleNow()) throw CancellationException("Font coverage page retired")
}
