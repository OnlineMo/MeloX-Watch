package io.github.onlinemo.meloxwatch.playback.route

import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.SessionCommand

/** 同 UID 控制器使用的有界协议；绝对请求只传 opaque owner 和原实读值。 */
@androidx.annotation.OptIn(UnstableApi::class)
object MediaVolumeCommands {
    private const val PREFIX = "io.github.onlinemo.meloxwatch.volume."
    val read = SessionCommand(PREFIX + "READ", Bundle.EMPTY)
    val set = SessionCommand(PREFIX + "SET", Bundle.EMPTY)
    val adjust = SessionCommand(PREFIX + "ADJUST", Bundle.EMPTY)
    private const val SCHEMA = "schema"
    private const val VERSION = 1
    private const val OWNER = "owner"
    private const val KNOWN = "known"
    private const val CURRENT = "current"
    private const val MINIMUM = "minimum"
    private const val MAXIMUM = "maximum"
    private const val FIXED = "fixed"
    private const val MUTED = "muted"
    private const val REMOTE = "remote"
    private const val TARGET = "target"
    private const val DELTA = "delta"
    private const val AVAILABLE = "available"
    private const val ISSUE = "issue"
    private const val ACCEPTED = "accepted"
    private const val CHANGED = "changed"
    private const val WRITE_ATTEMPTED = "write_attempted"
    private val SNAPSHOT_FIELDS = setOf(OWNER, KNOWN, CURRENT, MINIMUM, MAXIMUM, FIXED, MUTED, REMOTE)
    private val RESULT_FIELDS = setOf(SCHEMA, AVAILABLE, ISSUE, ACCEPTED, CHANGED, WRITE_ATTEMPTED)

    fun readArguments(): Bundle = Bundle().apply { putInt(SCHEMA, VERSION) }

    fun readRead(arguments: Bundle) {
        arguments.checkFields(setOf(SCHEMA))
    }

    fun setArguments(request: MediaVolumeSetRequest): Bundle = readArguments().apply {
        writeSnapshot(request.expected)
        putInt(TARGET, request.target)
    }

    fun readSet(arguments: Bundle): MediaVolumeSetRequest {
        arguments.checkFields(SNAPSHOT_FIELDS + setOf(SCHEMA, TARGET))
        return MediaVolumeSetRequest(arguments.readSnapshot(), arguments.value(TARGET))
    }

    fun adjustArguments(delta: Int): Bundle = readArguments().apply {
        require(delta == -1 || delta == 1)
        putInt(DELTA, delta)
    }

    fun readAdjust(arguments: Bundle): Int {
        arguments.checkFields(setOf(SCHEMA, DELTA))
        return arguments.value<Int>(DELTA).also { require(it == -1 || it == 1) }
    }

    fun resultArguments(result: MediaVolumeResult): Bundle = readArguments().apply {
        putBoolean(AVAILABLE, result.snapshot != null)
        result.snapshot?.let { writeSnapshot(it) }
        putString(ISSUE, result.issue?.name)
        putBoolean(ACCEPTED, result.accepted)
        putBoolean(CHANGED, result.changed)
        putBoolean(WRITE_ATTEMPTED, result.writeAttempted)
    }

    fun readResult(arguments: Bundle): MediaVolumeResult {
        val available = arguments.value<Boolean>(AVAILABLE)
        arguments.checkFields(if (available) RESULT_FIELDS + SNAPSHOT_FIELDS else RESULT_FIELDS)
        val issue = arguments.rawValue(ISSUE).let {
            require(it == null || it is String)
            (it as String?)?.let(ServiceMediaVolumeIssue::valueOf)
        }
        return MediaVolumeResult(
            snapshot = if (available) arguments.readSnapshot() else null,
            issue = issue,
            accepted = arguments.value(ACCEPTED),
            changed = arguments.value(CHANGED),
            writeAttempted = arguments.value(WRITE_ATTEMPTED),
        )
    }

    private fun Bundle.writeSnapshot(snapshot: MediaVolumeSnapshot) {
        putString(OWNER, snapshot.route.ownerId)
        putBoolean(KNOWN, snapshot.route.known)
        putInt(CURRENT, snapshot.values.current)
        putInt(MINIMUM, snapshot.values.minimum)
        putInt(MAXIMUM, snapshot.values.maximum)
        putBoolean(FIXED, snapshot.values.fixed)
        putBoolean(MUTED, snapshot.values.muted)
        putBoolean(REMOTE, snapshot.values.remote)
    }

    private fun Bundle.readSnapshot(): MediaVolumeSnapshot {
        val owner = rawValue(OWNER)
        require(owner == null || owner is String)
        return MediaVolumeSnapshot(
            MusicVolumeValues(value(CURRENT), value(MINIMUM), value(MAXIMUM), value(FIXED), value(MUTED), value(REMOTE)),
            PlaybackRouteSessionState(owner as String?, value(KNOWN)),
        )
    }

    private fun Bundle.checkFields(fields: Set<String>) {
        require(keySet() == fields)
        require(value<Int>(SCHEMA) == VERSION)
    }

    private inline fun <reified T> Bundle.value(key: String): T =
        rawValue(key) as? T ?: throw IllegalArgumentException("Invalid media volume field")

    @Suppress("DEPRECATION")
    private fun Bundle.rawValue(key: String): Any? = get(key)
}
