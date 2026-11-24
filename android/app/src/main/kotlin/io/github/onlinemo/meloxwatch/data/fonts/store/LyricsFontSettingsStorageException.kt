package io.github.onlinemo.meloxwatch.data.fonts.store

import java.io.IOException

enum class LyricsFontSettingsStorageIssue {
    INVALID_REVISION,
    REVISION_EXHAUSTED,
    BUSY,
    INVALID_FIELDS,
    UNSUPPORTED_SCHEMA,
    CORRUPT_FILE,
    IO,
    LOW_MEMORY,
}

/** The editor reads actual storage after a failed CAS; this error never asserts rollback. */
class LyricsFontSettingsStorageException(
    val issue: LyricsFontSettingsStorageIssue,
    cause: Throwable? = null,
) : IOException(issue.name, cause)

internal fun fontSettingsFail(issue: LyricsFontSettingsStorageIssue): Nothing =
    throw LyricsFontSettingsStorageException(issue)
