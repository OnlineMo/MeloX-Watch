package io.github.onlinemo.meloxwatch.data.about

import io.github.onlinemo.meloxwatch.data.network.JsonArray
import io.github.onlinemo.meloxwatch.data.network.JsonBoolean
import io.github.onlinemo.meloxwatch.data.network.JsonBounds
import io.github.onlinemo.meloxwatch.data.network.JsonNull
import io.github.onlinemo.meloxwatch.data.network.JsonNumber
import io.github.onlinemo.meloxwatch.data.network.JsonObject
import io.github.onlinemo.meloxwatch.data.network.JsonText
import io.github.onlinemo.meloxwatch.data.network.JsonValue
import io.github.onlinemo.meloxwatch.data.network.NetworkException
import io.github.onlinemo.meloxwatch.data.network.StrictJson
import java.security.MessageDigest
import java.util.TreeMap

internal data class ProjectReleaseEntry(
    val versionName: String,
    val page: String,
    val notes: String,
    val prerelease: Boolean,
    val candidateUrl: String,
    val publicationUrl: String,
)

/** 复用真实候选/发布记录格式；不会仅凭tag名称或服务器自报的版本显示可用更新。 */
internal object ProjectReleaseReader {
    const val RESPONSE_BYTES = 256 * 1024
    private val bounds = JsonBounds(RESPONSE_BYTES, depth = 16, nodes = 12_000, stringChars = 16_384, members = 2_000)
    private val sha256Pattern = Regex("[0-9a-f]{64}")
    private val sha1Pattern = Regex("[0-9a-f]{40}")

    fun catalog(bytes: ByteArray, checkCancelled: () -> Unit = {}): ProjectReleaseEntry = checked {
        val releases = (StrictJson.decode(bytes, bounds, checkCancelled) as? JsonArray)?.values ?: invalid()
        if (releases.isEmpty()) throw ProjectUpdateException(ProjectUpdateIssue.NO_RELEASE)
        require(releases.size <= 5)
        val accepted = releases.map { raw ->
            checkCancelled()
            val release = objectValue(raw)
            require(!boolean(release["draft"]))
            val tag = text(release["tag_name"], 33)
            require(tag.startsWith("v"))
            val version = tag.substring(1)
            require(ProjectVersion.parse(version) != null)
            val page = text(release["html_url"], 256)
            require(page == "${ProjectChannels.RELEASES_PAGE}/tag/$tag")
            val assets = (release["assets"] as? JsonArray)?.values ?: invalid()
            require(assets.size <= 32)
            val named = LinkedHashMap<String, String>()
            for (asset in assets) {
                val record = objectValue(asset)
                val name = text(record["name"], 128)
                if (name in setOf("candidate-manifest.json", "publication-record.json")) {
                    require(name !in named)
                    require(long(record["size"]) in 1..RESPONSE_BYTES.toLong())
                    val url = text(record["browser_download_url"], 512)
                    require(url == "${ProjectChannels.RELEASES_PAGE}/download/$tag/$name")
                    named[name] = url
                }
            }
            ProjectReleaseEntry(
                version,
                page,
                optionalText(release["body"], 16_384).orEmpty(),
                boolean(release["prerelease"]),
                named["candidate-manifest.json"] ?: invalid(),
                named["publication-record.json"] ?: invalid(),
            )
        }
        accepted.maxBy { checkNotNull(ProjectVersion.parse(it.versionName)) }
    }

    fun published(
        release: ProjectReleaseEntry,
        candidateBytes: ByteArray,
        publicationBytes: ByteArray,
        current: AppVersion,
        checkCancelled: () -> Unit = {},
    ): ProjectReleaseSummary = checked {
        val candidate = objectValue(StrictJson.decode(candidateBytes, bounds, checkCancelled))
        val publication = objectValue(StrictJson.decode(publicationBytes, bounds, checkCancelled))
        require(long(candidate["schema_version"]) == 2L && text(candidate["kind"]) == "release-candidate")
        require(text(candidate["repository"]) == ProjectChannels.REPOSITORY)
        require(long(publication["schema_version"]) == 1L)
        val versionRecord = objectValue(candidate["version"])
        val code = text(versionRecord["versionCode"], 16)
        require(code.matches(Regex("[1-9][0-9]*")))
        val version = AppVersion(text(versionRecord["versionName"], 32), code.toLongOrNull() ?: invalid())
        require(version.name == release.versionName && version.code > 0)
        require(boolean(candidate["prerelease"]) == release.prerelease)
        require(text(publication["tag"]) == "v${version.name}")
        require(boolean(publication["prerelease"]) == release.prerelease)
        val source = sha1(candidate["source_sha"])
        val snapshot = sha1(candidate["snapshot_sha"])
        val published = objectValue(publication["candidate"])
        require(sha1(published["source_sha"]) == source && sha1(published["snapshot_sha"]) == snapshot)
        require(sameJson(published["version"], versionRecord))
        require(text(publication["approved_at"], 128).isNotBlank())
        sha1(publication["approval_sha"])

        val files = objectValue(candidate["files"])
        val apks = (candidate["apks"] as? JsonArray)?.values ?: invalid()
        require(apks.size == 2)
        val packages = apks.map { raw ->
            checkCancelled()
            val apk = objectValue(raw)
            val role = text(apk["role"], 16)
            require(role == "watch" || role == "phone")
            require(text(apk["kind"]) == "application" && text(apk["signing"]) == "stable")
            require(text(apk["file"]) == "$role-release.apk")
            require(text(apk["package"]) == if (role == "watch") ProjectChannels.WATCH_PACKAGE else ProjectChannels.PHONE_PACKAGE)
            require(text(apk["version_name"], 32) == version.name)
            require(text(apk["version_code"], 16) == version.code.toString())
            require(!boolean(apk["debuggable"]) && !boolean(apk["profileable"]))
            val certificate = sha256(apk["certificate_sha256"])
            if (certificate != ProjectChannels.RELEASE_CERTIFICATE_SHA256) {
                throw ProjectUpdateException(ProjectUpdateIssue.CERTIFICATE_MISMATCH)
            }
            require(sha256(apk["sha256"]) == sha256(files["$role-release.apk"]))
            val abis = (apk["abis"] as? JsonArray)?.values ?: invalid()
            require(abis.size <= 8 && abis.all { text(it, 64).matches(Regex("[A-Za-z0-9_-]+")) })
            val fields = listOf("role", "file", "package", "version_name", "version_code", "abis", "sha256", "certificate_sha256")
            JsonObject(fields.associateWith { apk[it] ?: invalid() })
        }
        require(packages.map { text(it["role"]) } == listOf("watch", "phone"))
        val protocol = objectValue(candidate["protocol"])
        require(sameJson(published["protocol"], protocol))
        require(sameJson(published["packages"], JsonArray(packages)))
        val identity = JsonObject(mapOf("packages" to JsonArray(packages), "protocol" to protocol))
        val canonical = StrictJson.encode(sorted(identity), RESPONSE_BYTES, checkCancelled)
        require(hash(canonical) == sha256(published["package_set_sha256"]))

        val next = checkNotNull(ProjectVersion.parse(version.name))
        val previous = checkNotNull(ProjectVersion.parse(current.name))
        if (next < previous || version.code < current.code) throw ProjectUpdateException(ProjectUpdateIssue.DOWNGRADE)
        if ((next == previous) != (version.code == current.code)) {
            throw ProjectUpdateException(ProjectUpdateIssue.INVALID_RELEASE)
        }
        val byRole = packages.associateBy { text(it["role"]) }
        ProjectReleaseSummary(
            version,
            release.page,
            release.notes,
            release.prerelease,
            source,
            snapshot,
            sha256(byRole.getValue("watch")["sha256"]),
            sha256(byRole.getValue("phone")["sha256"]),
        )
    }

    private fun sorted(value: JsonValue): JsonValue = when (value) {
        is JsonObject -> JsonObject(TreeMap(value.fields.mapValues { sorted(it.value) }))
        is JsonArray -> JsonArray(value.values.map(::sorted))
        else -> value
    }

    private fun sameJson(first: JsonValue?, second: JsonValue?): Boolean =
        first != null && second != null && StrictJson.encode(sorted(first), RESPONSE_BYTES)
            .contentEquals(StrictJson.encode(sorted(second), RESPONSE_BYTES))

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }

    private fun objectValue(value: JsonValue?): JsonObject = value as? JsonObject ?: invalid()
    private fun text(value: JsonValue?, maximum: Int = 256): String = (value as? JsonText)?.value
        ?.takeIf { it.length <= maximum } ?: invalid()
    private fun optionalText(value: JsonValue?, maximum: Int): String? = when (value) {
        null, JsonNull -> null
        else -> text(value, maximum)
    }
    private fun long(value: JsonValue?): Long = (value as? JsonNumber)?.value?.toLongOrNull() ?: invalid()
    private fun boolean(value: JsonValue?): Boolean = (value as? JsonBoolean)?.value ?: invalid()
    private fun sha256(value: JsonValue?): String = text(value, 64).takeIf(sha256Pattern::matches) ?: invalid()
    private fun sha1(value: JsonValue?): String = text(value, 40).takeIf(sha1Pattern::matches) ?: invalid()
    private fun invalid(): Nothing = throw ProjectUpdateException(ProjectUpdateIssue.INVALID_RELEASE)

    private inline fun <T> checked(action: () -> T): T = try {
        action()
    } catch (failure: ProjectUpdateException) {
        throw failure
    } catch (_: NetworkException) {
        invalid()
    } catch (_: IllegalArgumentException) {
        invalid()
    } catch (_: OutOfMemoryError) {
        throw ProjectUpdateException(ProjectUpdateIssue.TOO_LARGE)
    }
}
