package io.github.onlinemo.meloxwatch.data.backup

import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontAsset
import io.github.onlinemo.meloxwatch.data.themes.BackgroundImageRef
import io.github.onlinemo.meloxwatch.data.themes.ThemeLimits
import io.github.onlinemo.meloxwatch.data.themes.ThemeRef
import java.net.URI
import java.util.UUID

/** Durable native plans must decode completely before a damaged journal can admit any writer. */
internal object BackupResourcePlanSchema {
    private val fontFields = setOf("id", "sha256", "bytes", "displayName", "license")
    private val imageFields = setOf("id", "sha256", "bytes", "mediaType", "width", "height", "orientation", "previewToken")
    private val themeFields = setOf("id", "themeId", "manifestSha256", "packageSha256", "resourceSha256", "displayName", "resourceBytes", "fontBytes", "added")
    private val themeAddedFields = setOf("expectedRevision", "previewToken", "operationId")
    private val lyricsFields = setOf("uri", "sourceId", "documentId", "sha256", "bytes", "added")

    fun validate(owner: BackupResourceOwner, slot: String, resourceId: String, values: Map<String, BackupCell>) {
        try {
            val row = BackupRecord(BackupRecordKind.PREFERENCE, "private-resource", values)
            when (owner) {
                BackupResourceOwner.FONT -> {
                    fields(values, fontFields)
                    uuid(slot)
                    val asset = LyricsFontAsset(row.text("id"), row.text("sha256"), row.long("bytes"),
                        row.text("displayName"), integer(row, "license"))
                    resource(resourceId, BackupResourceKind.FONT, asset.sha256)
                }
                BackupResourceOwner.BACKGROUND -> {
                    fields(values, imageFields)
                    uuid(slot); uuid(row.text("previewToken"))
                    val image = BackgroundImageRef(row.text("id"), row.text("sha256"), row.long("bytes"),
                        row.text("mediaType"), integer(row, "width"), integer(row, "height"), integer(row, "orientation"))
                    resource(resourceId, BackupResourceKind.IMAGE, image.sha256)
                }
                BackupResourceOwner.THEME -> {
                    val added = row.flag("added")
                    fields(values, themeFields + if (added) themeAddedFields else emptySet())
                    uuid(slot)
                    val ref = ThemeRef(row.text("id"), row.text("themeId"), row.text("manifestSha256"))
                    resource(resourceId, BackupResourceKind.THEME_PACKAGE, row.text("resourceSha256"))
                    require(row.text("packageSha256").matches(Regex("[0-9a-f]{64}")))
                    val name = row.text("displayName")
                    require(name.isNotBlank() && name.length <= ThemeLimits.NAME_CHARACTERS && name.none(Char::isISOControl))
                    require(row.long("resourceBytes") in 0L..ThemeLimits.UNPACKED_BYTES)
                    require(row.long("fontBytes") in 0L..row.long("resourceBytes"))
                    if (added) {
                        require(row.long("expectedRevision") >= 0L)
                        uuid(row.text("operationId")); uuid(row.text("previewToken"))
                        require(ref.installationId == row.text("previewToken"))
                    }
                }
                BackupResourceOwner.LYRICS -> {
                    fields(values, lyricsFields)
                    resource(resourceId, BackupResourceKind.LYRICS, row.text("sha256"))
                    require(slot == resourceId && row.long("bytes") in 1..BackupLimits.LYRICS_BYTES)
                    row.flag("added")
                    val raw = row.text("uri")
                    val uri = URI(raw)
                    require(uri.scheme == "content" && uri.isAbsolute && !uri.isOpaque && uri.rawUserInfo == null && uri.port == -1 &&
                        uri.rawAuthority?.endsWith(".owned-lyrics") == true && uri.rawQuery == null && uri.rawFragment == null)
                    val token = uri.rawPath?.removePrefix("/") ?: reject()
                    uuid(token)
                    require(uri.rawPath == "/$token" && raw.none(Char::isISOControl))
                    require(row.text("documentId") == "uri:$raw")
                    val source = row.text("sourceId")
                    require(source.startsWith("lyrics:")); uuid(source.removePrefix("lyrics:"))
                }
            }
        } catch (failure: BackupException) {
            if (failure.issue == BackupIssue.RECOVERY_REQUIRED) throw failure
            throw BackupException(BackupIssue.RECOVERY_REQUIRED, failure)
        } catch (failure: IllegalArgumentException) { throw BackupException(BackupIssue.RECOVERY_REQUIRED, failure) }
    }

    private fun fields(values: Map<String, BackupCell>, expected: Set<String>) {
        if (values.keys != expected || values.values.any { it == BackupCell.Null }) reject()
    }
    private fun integer(row: BackupRecord, name: String): Int = row.long(name).let {
        if (it !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) reject()
        it.toInt()
    }
    private fun resource(id: String, kind: BackupResourceKind, digest: String) {
        require(digest.matches(Regex("[0-9a-f]{64}")) && id == BackupLogicalSchema.resourceId(kind, digest))
    }
    private fun uuid(value: String) { require(value.length == 36 && UUID.fromString(value).toString() == value) }
    private fun reject(): Nothing = throw BackupException(BackupIssue.RECOVERY_REQUIRED)
}
