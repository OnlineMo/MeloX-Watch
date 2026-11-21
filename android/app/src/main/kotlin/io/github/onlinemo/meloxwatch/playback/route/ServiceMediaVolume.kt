package io.github.onlinemo.meloxwatch.playback.route

/** STREAM_MUSIC 的实读值与公开限制；不携带设备或 MediaRouter 对象。 */
data class MusicVolumeValues(
    val current: Int,
    val minimum: Int,
    val maximum: Int,
    val fixed: Boolean,
    val muted: Boolean,
    val remote: Boolean,
) {
    init {
        require(minimum >= 0 && maximum >= minimum)
        require(current in 0..maximum && (current >= minimum || muted && current == 0))
    }

    val adjustable: Boolean get() = !fixed && !remote && maximum > minimum
}

data class MediaVolumeSnapshot(val values: MusicVolumeValues, val route: PlaybackRouteSessionState) {
    val absoluteAdjustable: Boolean get() = values.adjustable && route.known
}

data class MediaVolumeSetRequest(val expected: MediaVolumeSnapshot, val target: Int) {
    init {
        require(expected.route.known)
        require(target in expected.values.minimum..expected.values.maximum)
    }
}

enum class ServiceMediaVolumeIssue { UNAVAILABLE, FIXED, REMOTE_OUTPUT, OUTPUT_CHANGED, SET_REJECTED, AT_LIMIT }

data class MediaVolumeResult(
    val snapshot: MediaVolumeSnapshot?,
    val issue: ServiceMediaVolumeIssue? = null,
    val accepted: Boolean = false,
    val changed: Boolean = false,
    val writeAttempted: Boolean = false,
) {
    init {
        require(!accepted || snapshot != null && issue == null)
        require(!changed || accepted && writeAttempted)
    }
}

internal interface MusicVolumePort {
    fun read(): MusicVolumeValues
    fun set(index: Int)
}

/** 由服务在同一同步提交点读、核验、写、回读；UI 只持草稿。 */
internal class ServiceMediaVolume(
    private val platform: MusicVolumePort,
    private val currentRoute: () -> PlaybackRouteSessionState,
) {
    fun read(): MediaVolumeResult {
        val snapshot = capture()
        return MediaVolumeResult(snapshot, snapshot?.values?.let(::constraintIssue) ?: if (snapshot == null) {
            ServiceMediaVolumeIssue.UNAVAILABLE
        } else null)
    }

    fun setAbsolute(request: MediaVolumeSetRequest): MediaVolumeResult {
        val actual = capture() ?: return MediaVolumeResult(null, ServiceMediaVolumeIssue.UNAVAILABLE)
        if (!actual.route.known || actual.route != request.expected.route) {
            return MediaVolumeResult(actual, ServiceMediaVolumeIssue.OUTPUT_CHANGED)
        }
        constraintIssue(actual.values)?.let { return MediaVolumeResult(actual, it) }
        if (actual.values != request.expected.values) {
            return MediaVolumeResult(actual, ServiceMediaVolumeIssue.OUTPUT_CHANGED)
        }
        if (request.target == actual.values.current) return MediaVolumeResult(actual, accepted = true)
        return write(actual, request.target)
    }

    /** 暂停、无轨道或不可观察输出仍只用这次鲜读值；相对命令没有旧输出的绝对草稿。 */
    fun adjust(delta: Int): MediaVolumeResult {
        require(delta == -1 || delta == 1)
        val actual = capture() ?: return MediaVolumeResult(null, ServiceMediaVolumeIssue.UNAVAILABLE)
        constraintIssue(actual.values)?.let { return MediaVolumeResult(actual, it) }
        if (delta < 0 && actual.values.current <= actual.values.minimum ||
            delta > 0 && actual.values.current >= actual.values.maximum) {
            return MediaVolumeResult(actual, ServiceMediaVolumeIssue.AT_LIMIT)
        }
        val target = (actual.values.current.toLong() + delta).coerceIn(
            actual.values.minimum.toLong(), actual.values.maximum.toLong(),
        ).toInt()
        if (target == actual.values.current) return MediaVolumeResult(actual, ServiceMediaVolumeIssue.AT_LIMIT)
        return write(actual, target)
    }

    private fun capture(): MediaVolumeSnapshot? = try {
        val before = currentRoute()
        val values = platform.read()
        val after = currentRoute()
        MediaVolumeSnapshot(values, if (before == after) after else PlaybackRouteSessionState())
    } catch (_: RuntimeException) {
        null
    }

    private fun write(before: MediaVolumeSnapshot, target: Int): MediaVolumeResult {
        try { platform.set(target) }
        catch (_: RuntimeException) {
            return MediaVolumeResult(capture(), ServiceMediaVolumeIssue.SET_REJECTED, writeAttempted = true)
        }
        val readback = capture()
            ?: return MediaVolumeResult(null, ServiceMediaVolumeIssue.UNAVAILABLE, writeAttempted = true)
        // setStreamVolume 没有 route CAS。IPC 期间可能已写；换输出只能拒绝 ACK，不能宣称零误写或回滚。
        if (before.route != readback.route || !sameConstraints(before.values, readback.values)) {
            return MediaVolumeResult(readback, ServiceMediaVolumeIssue.OUTPUT_CHANGED, writeAttempted = true)
        }
        if (!readback.values.adjustable || readback.values.current != target) {
            return MediaVolumeResult(readback, ServiceMediaVolumeIssue.SET_REJECTED, writeAttempted = true)
        }
        return MediaVolumeResult(readback, accepted = true, changed = true, writeAttempted = true)
    }

    private fun sameConstraints(before: MusicVolumeValues, after: MusicVolumeValues): Boolean =
        before.minimum == after.minimum && before.maximum == after.maximum &&
            before.fixed == after.fixed && before.remote == after.remote

    private fun constraintIssue(values: MusicVolumeValues): ServiceMediaVolumeIssue? = when {
        values.remote -> ServiceMediaVolumeIssue.REMOTE_OUTPUT
        values.fixed || values.maximum == values.minimum -> ServiceMediaVolumeIssue.FIXED
        else -> null
    }
}
