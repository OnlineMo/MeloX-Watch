package io.github.onlinemo.meloxwatch.data.about

data class AppVersion(val name: String, val code: Long) {
    init {
        require(code in 0..2_100_000_000L)
        require(ProjectVersion.parse(name) != null)
    }
}

internal data class ProjectVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ProjectVersion> {
    override fun compareTo(other: ProjectVersion): Int =
        compareValuesBy(this, other, ProjectVersion::major, ProjectVersion::minor, ProjectVersion::patch)

    companion object {
        private val pattern = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")

        fun parse(value: String): ProjectVersion? {
            if (value.length > 32) return null
            val matched = pattern.matchEntire(value) ?: return null
            val parts = matched.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
            return ProjectVersion(parts[0], parts[1], parts[2])
        }
    }
}

enum class ProjectUpdateIssue {
    UNAVAILABLE,
    TIMEOUT,
    BUSY,
    NO_RELEASE,
    INVALID_RELEASE,
    UNTRUSTED_SOURCE,
    CERTIFICATE_MISMATCH,
    DOWNGRADE,
    TOO_LARGE,
}

/** 仅保存已核验的公开发布摘要；账号、设备及曲库不会进入更新请求。 */
data class ProjectReleaseSummary(
    val version: AppVersion,
    val releasePage: String,
    val notes: String,
    val prerelease: Boolean,
    val sourceSha: String,
    val snapshotSha: String,
    val watchApkSha256: String,
    val phoneApkSha256: String,
)

sealed interface ProjectUpdateState {
    data object Idle : ProjectUpdateState
    data object Checking : ProjectUpdateState
    data object Cancelled : ProjectUpdateState
    data class Checked(val release: ProjectReleaseSummary, val hasUpdate: Boolean, val checkedAtEpochMs: Long) : ProjectUpdateState
    data class Failed(val issue: ProjectUpdateIssue, val checkedAtEpochMs: Long) : ProjectUpdateState
}

internal class ProjectUpdateException(val issue: ProjectUpdateIssue) : Exception(issue.name)

object ProjectChannels {
    const val REPOSITORY = "OnlineMo/MeloX-Watch"
    const val SOURCE_PAGE = "https://github.com/OnlineMo/MeloX-Watch"
    const val RELEASES_PAGE = "$SOURCE_PAGE/releases"
    const val WATCH_PACKAGE = "io.github.onlinemo.meloxwatch"
    const val PHONE_PACKAGE = "io.github.onlinemo.meloxwatch.companion"
    const val RELEASE_CERTIFICATE_SHA256 = "71efe629047abc6a3149a22ddb305d0dce0d2d1012b9e8a054921a121981d188"
}
