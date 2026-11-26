package io.github.onlinemo.meloxwatch.data.local

/** 只有这些固定阶段枚举可离开内存中的物理操作回执。 */
enum class LocalProviderFailureStage { READ, RETIREMENT }

/** 原错误只在本次应用生命周期的内存中保留；快照不能用于诊断导出。 */
internal class LocalProviderFailureSnapshot internal constructor(
    val receipt: LocalProviderFailureReceipt,
    val readFailure: Throwable?,
    val retirementFailure: Throwable?,
    val physicallyRetired: Boolean,
) {
    override fun toString(): String = "LocalProviderFailureSnapshot(redacted)"
}

/** 每份回执绑定一个精确ticket；两个阶段各保留一个原错、一个附加错和一个报告错。 */
internal class LocalProviderFailureReceipt internal constructor(
    internal val ticket: Any,
    internal val sequence: Long,
) {
    private val lock = Any()
    private val failures = arrayOfNulls<Throwable>(LocalProviderFailureStage.entries.size)
    private val additionalFailures = arrayOfNulls<Throwable>(LocalProviderFailureStage.entries.size)
    private val reportingFailures = arrayOfNulls<Throwable>(LocalProviderFailureStage.entries.size)
    private var retired = false
    private var discarded = false
    private var reported = 0
    private var reporting = 0

    internal fun record(stage: LocalProviderFailureStage, failure: Throwable): Boolean = synchronized(lock) {
        if (discarded) return@synchronized false
        val first = failures[stage.ordinal]
        if (first == null) {
            failures[stage.ordinal] = failure
            true
        } else if (first !== failure && additionalFailures[stage.ordinal] == null) {
            // dispatcher可能执行worker后再拒绝派发，两次原始结果都要保留。
            // 重复清理或报告只复用固定槽，不扩展错误集合。
            additionalFailures[stage.ordinal] = failure
            attach(first, failure)
            true
        } else false
    }

    internal fun recordReportingFailure(stage: LocalProviderFailureStage, failure: Throwable): Boolean = synchronized(lock) {
        if (discarded || reportingFailures[stage.ordinal] != null) return@synchronized false
        val original = failures[stage.ordinal] ?: return@synchronized false
        reportingFailures[stage.ordinal] = failure
        attach(original, failure)
        true
    }

    internal fun retire() = synchronized(lock) { retired = true }
    internal fun isRetired(): Boolean = synchronized(lock) { retired }
    internal fun canConsume(): Boolean = synchronized(lock) {
        retired && !discarded && reporting == 0 && LocalProviderFailureStage.entries.all { stage ->
            failures[stage.ordinal] == null || (reported and (1 shl stage.ordinal)) != 0
        }
    }

    internal fun pendingReports(): List<LocalProviderFailureStage> = synchronized(lock) {
        if (!retired || discarded) return@synchronized emptyList()
        LocalProviderFailureStage.entries.filter { stage ->
            val bit = 1 shl stage.ordinal
            if (failures[stage.ordinal] == null || (reported and bit) != 0) false
            else {
                reported = reported or bit
                reporting = reporting or bit
                true
            }
        }
    }

    internal fun reportCompleted(stage: LocalProviderFailureStage) = synchronized(lock) {
        reporting = reporting and (1 shl stage.ordinal).inv()
    }

    internal fun snapshot(): LocalProviderFailureSnapshot = synchronized(lock) {
        LocalProviderFailureSnapshot(this, failures[LocalProviderFailureStage.READ.ordinal],
            failures[LocalProviderFailureStage.RETIREMENT.ordinal], retired)
    }

    internal fun discard() = synchronized(lock) {
        discarded = true
        failures.fill(null)
        additionalFailures.fill(null)
        reportingFailures.fill(null)
    }

    private fun attach(original: Throwable, additional: Throwable) {
        if (original !== additional && original.cause !== additional && original.suppressed.none { it === additional }) {
            original.addSuppressed(additional)
        }
    }

    override fun toString(): String = "LocalProviderFailureReceipt(redacted)"
}
