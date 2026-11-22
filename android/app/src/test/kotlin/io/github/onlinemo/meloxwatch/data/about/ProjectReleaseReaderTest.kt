package io.github.onlinemo.meloxwatch.data.about

import io.github.onlinemo.meloxwatch.data.network.JsonArray
import io.github.onlinemo.meloxwatch.data.network.JsonBounds
import io.github.onlinemo.meloxwatch.data.network.JsonNumber
import io.github.onlinemo.meloxwatch.data.network.JsonObject
import io.github.onlinemo.meloxwatch.data.network.JsonText
import io.github.onlinemo.meloxwatch.data.network.JsonValue
import io.github.onlinemo.meloxwatch.data.network.StrictJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 固定 Python CI 格式的合成记录，验证独立 Kotlin reader；不证明真实发布或签名。 */
class ProjectReleaseReaderTest {
    @Test(timeout = 5_000L)
    fun pythonPublicationBindingIsAcceptedForNewAndCurrentVersions() {
        val entry = ProjectReleaseReader.catalog(fixture("catalog.json"))
        val next = ProjectReleaseReader.published(entry, fixture("candidate.json"), fixture("publication.json"), AppVersion("0.0.1", 1))
        assertEquals(AppVersion("0.1.0", 7), next.version)
        assertEquals("a".repeat(64), next.watchApkSha256)
        assertEquals("b".repeat(64), next.phoneApkSha256)
        assertEquals("${ProjectChannels.RELEASES_PAGE}/tag/v0.1.0", next.releasePage)
        assertTrue(next.prerelease)
        assertEquals(next, ProjectReleaseReader.published(entry, fixture("candidate.json"), fixture("publication.json"), next.version))
    }

    @Test(timeout = 5_000L)
    fun unknownChannelAndMissingOrDuplicatedAnnouncementAssetsAreRejected() {
        val release = (decode(fixture("catalog.json")) as JsonArray).values.single() as JsonObject
        val assets = (release["assets"] as JsonArray).values
        listOf(
            changed(release, "html_url", JsonText("https://untrusted.invalid/releases/tag/v0.1.0")),
            changed(release, "assets", JsonArray(assets.take(1))),
            changed(release, "assets", JsonArray(assets + assets.first())),
        ).forEach { record ->
            rejected(ProjectUpdateIssue.INVALID_RELEASE) { ProjectReleaseReader.catalog(encode(JsonArray(listOf(record)))) }
        }
        rejected(ProjectUpdateIssue.NO_RELEASE) { ProjectReleaseReader.catalog("[]".toByteArray()) }
    }

    @Test(timeout = 5_000L)
    fun aSecondPackageCannotBeMissingOrHaveAChangedIdentityHashOrCertificate() {
        val original = decode(fixture("candidate.json")) as JsonObject
        val apks = (original["apks"] as JsonArray).values
        rejected(ProjectUpdateIssue.INVALID_RELEASE) {
            published(encode(changed(original, "apks", JsonArray(apks.take(1)))))
        }
        for ((field, value, issue) in listOf(
            Triple("package", JsonText("example.invalid.companion"), ProjectUpdateIssue.INVALID_RELEASE),
            Triple("sha256", JsonText("c".repeat(64)), ProjectUpdateIssue.INVALID_RELEASE),
            Triple("certificate_sha256", JsonText("0".repeat(64)), ProjectUpdateIssue.CERTIFICATE_MISMATCH),
        )) {
            val phone = changed(apks[1] as JsonObject, field, value)
            rejected(issue) { published(encode(changed(original, "apks", JsonArray(listOf(apks[0], phone))))) }
        }
    }

    @Test(timeout = 5_000L)
    fun announcementMustBindTheSameSourceSnapshotVersionAndPackageSet() {
        val publication = decode(fixture("publication.json")) as JsonObject
        val identity = publication["candidate"] as JsonObject
        for (field in listOf("source_sha", "snapshot_sha", "package_set_sha256")) {
            val value = JsonText("f".repeat(if (field == "package_set_sha256") 64 else 40))
            rejected(ProjectUpdateIssue.INVALID_RELEASE) {
                published(publication = encode(changed(publication, "candidate", changed(identity, field, value))))
            }
        }
        rejected(ProjectUpdateIssue.INVALID_RELEASE) {
            published(publication = encode(changed(publication, "tag", JsonText("v0.2.0"))))
        }
    }

    @Test(timeout = 5_000L)
    fun downgradeOrDifferentCodeForTheSameVersionNeverBecomesAnUpdate() {
        rejected(ProjectUpdateIssue.DOWNGRADE) { published(current = AppVersion("0.2.0", 8)) }
        rejected(ProjectUpdateIssue.INVALID_RELEASE) { published(current = AppVersion("0.1.0", 6)) }
        val candidate = decode(fixture("candidate.json")) as JsonObject
        val version = candidate["version"] as JsonObject
        rejected(ProjectUpdateIssue.INVALID_RELEASE) {
            published(encode(changed(candidate, "version", changed(version, "versionCode", JsonNumber("7")))))
        }
    }

    @Test(timeout = 5_000L)
    fun repeatedKeysAndCancellationArePreservedAsFailures() {
        val bytes = fixture("candidate.json").toString(Charsets.UTF_8)
            .replace("\"schema_version\": 2,", "\"schema_version\": 2, \"schema_version\": 2,").toByteArray()
        rejected(ProjectUpdateIssue.INVALID_RELEASE) { published(bytes) }
        val cancelled = kotlinx.coroutines.CancellationException("Fixture cancellation")
        try {
            ProjectReleaseReader.catalog(fixture("catalog.json")) { throw cancelled }
            throw AssertionError("Cancellation was swallowed")
        } catch (failure: kotlinx.coroutines.CancellationException) {
            assertTrue(failure === cancelled)
        }
    }

    private fun published(
        candidate: ByteArray = fixture("candidate.json"),
        publication: ByteArray = fixture("publication.json"),
        current: AppVersion = AppVersion("0.0.1", 1),
    ): ProjectReleaseSummary = ProjectReleaseReader.published(ProjectReleaseReader.catalog(fixture("catalog.json")), candidate, publication, current)

    private fun rejected(issue: ProjectUpdateIssue, action: () -> Any?) {
        try {
            action()
            throw AssertionError("Invalid release was accepted: $issue")
        } catch (failure: ProjectUpdateException) { assertEquals(issue, failure.issue) }
    }

    private fun fixture(name: String): ByteArray = checkNotNull(javaClass.getResourceAsStream("/fixtures/about/$name")).use { it.readBytes() }
    private fun decode(bytes: ByteArray): JsonValue = StrictJson.decode(bytes, JsonBounds(ProjectReleaseReader.RESPONSE_BYTES))
    private fun encode(value: JsonValue): ByteArray = StrictJson.encode(value, ProjectReleaseReader.RESPONSE_BYTES)
    private fun changed(value: JsonObject, key: String, field: JsonValue): JsonObject = JsonObject(value.fields + (key to field))
}
