package io.github.onlinemo.meloxwatch.data.about

import io.github.onlinemo.meloxwatch.data.network.BoundedHttpGetClient
import io.github.onlinemo.meloxwatch.data.network.HttpGetKind
import io.github.onlinemo.meloxwatch.data.network.HttpGetLimits
import io.github.onlinemo.meloxwatch.data.network.NetworkException
import io.github.onlinemo.meloxwatch.data.network.NetworkIssue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 显式检查只获取发布摘要；无后台任务、下载APK、安装Intent、Cookie或账户凭据。 */
internal class ProjectUpdateSource(private val http: BoundedHttpGetClient) {
    suspend fun check(current: AppVersion): ProjectReleaseSummary = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val context = currentCoroutineContext()
        fun cancelled() {
            context.ensureActive()
            if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= OPERATION_TIMEOUT_MS) {
                throw ProjectUpdateException(ProjectUpdateIssue.TIMEOUT)
            }
        }
        suspend fun fetch(target: String, asset: Boolean): ByteArray {
            val initial = target.toHttpUrl()
            val trusted = if (asset) initial.host == "github.com" &&
                initial.encodedPath.startsWith("/${ProjectChannels.REPOSITORY}/releases/download/v")
            else target == CATALOG_URL
            if (!trusted || initial.scheme != "https" || initial.port != 443 ||
                initial.username.isNotEmpty() || initial.password.isNotEmpty() ||
                initial.query != (if (asset) null else "per_page=5") || initial.fragment != null
            ) throw ProjectUpdateException(ProjectUpdateIssue.UNTRUSTED_SOURCE)
            var next = initial
            for (redirect in 0..1) {
                cancelled()
                val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started).coerceAtLeast(0)
                val remaining = OPERATION_TIMEOUT_MS - elapsed
                if (remaining <= 0) throw ProjectUpdateException(ProjectUpdateIssue.TIMEOUT)
                val response = http.get(next, HttpGetLimits(
                    callTimeoutMs = minOf(8_000, remaining),
                    connectTimeoutMs = minOf(3_000, remaining),
                    responseBytes = ProjectReleaseReader.RESPONSE_BYTES,
                    kind = HttpGetKind.JSON,
                ))
                if (response.location == null) return response.bytes
                if (!asset || redirect != 0 || response.code != 302) {
                    throw ProjectUpdateException(ProjectUpdateIssue.UNTRUSTED_SOURCE)
                }
                val redirected = response.location.toHttpUrlOrNull()
                    ?: throw ProjectUpdateException(ProjectUpdateIssue.UNTRUSTED_SOURCE)
                if (redirected.scheme != "https" || redirected.port != 443 ||
                    redirected.host !in ASSET_HOSTS || redirected.username.isNotEmpty() ||
                    redirected.password.isNotEmpty() || redirected.fragment != null
                ) throw ProjectUpdateException(ProjectUpdateIssue.UNTRUSTED_SOURCE)
                next = redirected
            }
            throw ProjectUpdateException(ProjectUpdateIssue.UNTRUSTED_SOURCE)
        }
        try {
            val entry = ProjectReleaseReader.catalog(fetch(CATALOG_URL, false), ::cancelled)
            val candidate = fetch(entry.candidateUrl, true)
            val publication = fetch(entry.publicationUrl, true)
            cancelled()
            ProjectReleaseReader.published(entry, candidate, publication, current, ::cancelled)
        } catch (failure: NetworkException) {
            val issue = when (failure.issue) {
                NetworkIssue.BUSY -> ProjectUpdateIssue.BUSY
                NetworkIssue.TIMEOUT -> ProjectUpdateIssue.TIMEOUT
                NetworkIssue.RESPONSE_TOO_LARGE -> ProjectUpdateIssue.TOO_LARGE
                NetworkIssue.CERTIFICATE, NetworkIssue.REDIRECT -> ProjectUpdateIssue.UNTRUSTED_SOURCE
                else -> ProjectUpdateIssue.UNAVAILABLE
            }
            throw ProjectUpdateException(issue)
        }
    }

    private companion object {
        const val CATALOG_URL = "https://api.github.com/repos/OnlineMo/MeloX-Watch/releases?per_page=5"
        const val OPERATION_TIMEOUT_MS = 16_000L
        val ASSET_HOSTS = setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com")
    }
}
