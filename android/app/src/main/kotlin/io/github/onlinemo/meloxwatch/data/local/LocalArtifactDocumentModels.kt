package io.github.onlinemo.meloxwatch.data.local

import android.net.Uri
import java.io.File
import java.io.IOException
import java.util.UUID

internal class LocalArtifactDocumentInput(val uri: Uri, val returnedFlags: Int) {
    override fun toString(): String = "LocalArtifactDocumentInput"
}

internal class LocalArtifactDocumentDestination(val uri: Uri, val returnedFlags: Int) {
    override fun toString(): String = "LocalArtifactDocumentDestination"
}

internal enum class LocalArtifactKind(val prefix: String, val suffix: String, val maximumBytes: Long) {
    BACKUP("MeloX-backup", "zip", 300L * 1024 * 1024),
    DIAGNOSTIC("MeloX-diagnostics", "json", 64L * 1024),
}

/** 只有已定义的两个来源域；内容校验属于实际生产者，不能把任意 filesDir 文件封成产物。 */
internal class LocalArtifactSource private constructor(
    val kind: LocalArtifactKind,
    val file: File,
    val artifactId: String,
    val expectedBytes: Long,
    val sha256: String,
    val verifyBackupFormat: ((File, () -> Unit) -> Unit)?,
) {
    val finalName: String get() = "${kind.prefix}-$artifactId.${kind.suffix}"

    init {
        val canonical = try { UUID.fromString(artifactId).toString() } catch (_: IllegalArgumentException) { null }
        require(canonical == artifactId)
        require(expectedBytes in 1..kind.maximumBytes && sha256.matches(Regex("[0-9a-f]{64}")))
        require((kind == LocalArtifactKind.BACKUP) == (verifyBackupFormat != null))
    }
    override fun toString(): String = "LocalArtifactSource(${kind.name})"

    companion object {
        fun backup(file: File, artifactId: String, expectedBytes: Long, sha256: String,
            verifyFormat: (File, () -> Unit) -> Unit): LocalArtifactSource =
            LocalArtifactSource(LocalArtifactKind.BACKUP, file, artifactId, expectedBytes, sha256, verifyFormat)

        fun diagnostic(file: File, artifactId: String, expectedBytes: Long, sha256: String): LocalArtifactSource =
            LocalArtifactSource(LocalArtifactKind.DIAGNOSTIC, file, artifactId, expectedBytes, sha256, null)
    }
}

internal data class LocalArtifactReceipt(val artifactId: String, val sha256: String, val bytes: Long)

internal enum class LocalArtifactIssue {
    INVALID_INPUT, LOCAL_PROVIDER_REQUIRED, TOO_LARGE, PERMISSION_DENIED, STORAGE_FULL, IO, BUSY,
    INTEGRITY_MISMATCH, CONFLICT, OUTPUT_CLEANUP_REQUIRED, UNSUPPORTED_RESOURCE, PATH_ESCAPE,
}

internal class LocalArtifactException(val issue: LocalArtifactIssue, cause: Throwable? = null) : IOException(issue.name, cause)
