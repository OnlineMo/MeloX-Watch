package io.github.onlinemo.meloxwatch.data.fileoperations

import io.github.onlinemo.meloxwatch.data.library.LibraryCodec
import io.github.onlinemo.meloxwatch.data.library.LibrarySource
import io.github.onlinemo.meloxwatch.data.library.LibrarySourceState
import io.github.onlinemo.meloxwatch.data.library.LocalContentValidation
import io.github.onlinemo.meloxwatch.data.library.LocalReadDescriptor
import io.github.onlinemo.meloxwatch.model.LocalAccess
import io.github.onlinemo.meloxwatch.model.LocalLocationReference
import io.github.onlinemo.meloxwatch.model.TrackKey
import org.json.JSONArray
import org.json.JSONObject

/** 私有操作日志 schema；记录不进入诊断或显式备份，不能作为恢复 URI 权限的能力。 */
internal object FileOperationCodec {
    fun command(command: FileOperationCommand): String = JSONObject().apply {
        put("schema", 1)
        put("kind", command.kind.name)
        put("tags", command.tags?.values?.let(::tags))
        put("name", command.displayName)
        put("removedSource", command.removedSourceId)
        put("removedSourceAuth", command.removedSourceAuthEpoch)
        put("destination", command.destination?.let { destination -> JSONObject().apply {
            put("source", destination.sourceId)
            put("tree", destination.treeUri)
            put("parent", destination.parentObjectId)
            put("auth", destination.expectedAuthEpoch)
            put("name", destination.displayName)
        } })
    }.toString()

    fun command(value: String): FileOperationCommand = JSONObject(value).let { json ->
        require(json.getInt("schema") == 1)
        when (FileOperationKind.valueOf(json.getString("kind"))) {
            FileOperationKind.REMOVE_FROM_INDEX -> FileOperationCommand.removeFromIndex()
            FileOperationKind.REMOVE_SOURCE -> FileOperationCommand.removeSource(json.getString("removedSource"), json.getLong("removedSourceAuth"))
            FileOperationKind.DELETE_ORIGINAL -> FileOperationCommand.deleteOriginal()
            FileOperationKind.DELETE_APP_COPY -> FileOperationCommand.deleteAppCopy()
            FileOperationKind.WRITE_TAGS -> FileOperationCommand.writeTags(AudioTagPatch(tags(json.getJSONObject("tags"))))
            FileOperationKind.RENAME -> FileOperationCommand.rename(json.getString("name"))
            FileOperationKind.MOVE -> json.getJSONObject("destination").let { destination -> FileOperationCommand.move(
                FileOperationDestination(destination.getString("source"), destination.getString("tree"), destination.getString("parent"),
                    destination.getLong("auth"), destination.optionalString("name"))) }
            FileOperationKind.DELETE_COMPLETED_MOVE_SOURCE -> throw FileOperationException(FileOperationIssue.INVALID_INPUT)
        }
    }

    fun commandBinding(selection: FileSelectionToken, value: FileOperationCommand): String = fileOperationDigest(
        JSONObject().apply {
            put("schema", 1)
            put("view", selection.viewId)
            put("revision", selection.revision)
            put("count", selection.totalCount)
            put("command", JSONObject(command(value)))
        }.toString().toByteArray(Charsets.UTF_8))

    fun target(value: FileOperationTarget): String = JSONObject().apply {
        put("schema", 1)
        put("unit", value.unitKey)
        put("track", track(value.trackKey))
        put("trackVersion", value.trackVersionId)
        put("descriptor", value.descriptor?.let(::descriptor))
        put("parent", value.parentObjectId)
        put("ownership", value.ownership?.name)
        put("proof", value.proof?.let(::proof))
        put("removedSource", value.removedSourceId)
        put("displayName", value.displayName)
    }.toString()

    fun target(value: String): FileOperationTarget = JSONObject(value).let { json ->
        require(json.getInt("schema") == 1)
        FileOperationTarget(json.getString("unit"), track(json.getJSONObject("track")), json.getString("trackVersion"),
            json.optionalObject("descriptor")?.let(::descriptor), json.optionalString("parent"),
            json.optionalString("ownership")?.let(FileResourceOwnership::valueOf), json.optionalObject("proof")?.let(::proof), json.optionalString("removedSource"),
            json.optionalString("displayName"))
    }

    fun impact(value: FileOperationImpact): String = JSONObject().apply {
        put("schema", 1)
        put("key", value.impactKey)
        put("kind", value.kind.name)
        put("track", value.trackKey?.let(::track))
        put("source", value.sourceId)
        put("alias", value.aliasId)
        put("node", value.nodeId)
        put("object", value.objectId)
        put("version", value.versionId)
        put("revision", value.contentRevision)
        put("sourceSnapshot", value.source?.let(::source))
    }.toString()

    fun impact(value: String): FileOperationImpact = JSONObject(value).let { json ->
        require(json.getInt("schema") == 1)
        FileOperationImpact(json.getString("key"), FileImpactKind.valueOf(json.getString("kind")),
            json.optionalObject("track")?.let(::track), json.optionalString("source"), json.optionalString("alias"),
            json.optionalString("node"), json.optionalString("object"), json.optionalString("version"), json.optionalLong("revision"),
            json.optionalObject("sourceSnapshot")?.let(::source))
    }

    fun route(value: FilePublishedRoute): String = JSONObject().apply {
        put("schema", 1)
        put("source", value.sourceId)
        put("auth", value.authEpoch)
        put("grant", value.grantId)
        put("proof", value.proof?.let(::proof))
        put("issue", value.issue?.name)
    }.toString()

    fun route(value: String): FilePublishedRoute = JSONObject(value).let {
        require(it.getInt("schema") == 1)
        FilePublishedRoute(it.getString("source"), it.getLong("auth"), it.getString("grant"), it.optionalObject("proof")?.let(::proof),
            it.optionalString("issue")?.let(FileOperationIssue::valueOf))
    }

    fun artifacts(value: FileOperationArtifacts): String = JSONObject().apply {
        put("schema", 1)
        put("stagingName", value.stagingName)
        put("staged", value.staged?.let(::document))
        put("backupName", value.backupName)
        put("backup", value.originalBackup?.let(::document))
        put("published", value.published?.let(::document))
        put("tags", value.tagValues?.let(::tags))
        put("audioHash", value.audioSha256)
        put("deleted", value.originalDeleted)
        put("differentName", value.actualNameDiffers)
        put("restoredOriginal", value.restoredOriginal)
        put("sourceDeletionStarted", value.sourceDeletionStarted)
    }.toString()

    fun artifacts(value: String): FileOperationArtifacts = JSONObject(value).let { json ->
        require(json.getInt("schema") == 1)
        FileOperationArtifacts(json.optionalString("stagingName"), json.optionalObject("staged")?.let(::document),
            json.optionalString("backupName"), json.optionalObject("backup")?.let(::document),
            json.optionalObject("published")?.let(::document), json.optionalObject("tags")?.let(::tags),
            json.optionalString("audioHash"), json.getBoolean("deleted"), json.getBoolean("differentName"), json.getBoolean("restoredOriginal"),
            json.getBoolean("sourceDeletionStarted"))
    }

    fun selected(value: FileSelectedTrack): String = JSONObject().apply {
        put("schema", 1)
        put("track", track(value.trackKey))
        put("location", value.locationId)
        put("revision", value.contentRevision)
    }.toString()
    fun selected(value: String): FileSelectedTrack = JSONObject(value).let {
        require(it.getInt("schema") == 1)
        FileSelectedTrack(track(it.getJSONObject("track")), it.optionalString("location"), it.optionalLong("revision"))
    }

    fun proof(value: PhysicalFileProof): JSONObject = JSONObject().apply {
        put("uri", value.uri)
        put("object", value.objectId)
        put("name", value.displayName)
        put("mime", value.mimeType)
        put("device", value.device)
        put("inode", value.inode)
        put("changedTime", value.changedTime)
        put("bytes", value.bytes)
        put("sha256", value.sha256)
    }

    private fun proof(json: JSONObject) = PhysicalFileProof(json.getString("uri"), json.getString("object"), json.getString("name"),
        json.optionalString("mime"), json.getLong("device"), json.getLong("inode"), json.getLong("changedTime"), json.getLong("bytes"), json.getString("sha256"))

    private fun document(value: WrittenDocument): JSONObject = JSONObject().apply {
        put("source", value.sourceId)
        put("tree", value.treeUri)
        put("parent", value.parentObjectId)
        put("proof", proof(value.proof))
    }
    private fun document(json: JSONObject) = WrittenDocument(json.getString("source"), json.getString("tree"),
        json.getString("parent"), proof(json.getJSONObject("proof")))

    private fun descriptor(value: LocalReadDescriptor): JSONObject = JSONObject().apply {
        put("track", track(value.trackKey))
        put("sourceSnapshot", source(value.source))
        put("alias", value.aliasId)
        put("object", value.objectId)
        put("providerToken", value.providerContentToken)
        put("bytes", value.sizeBytes)
        put("verifiedToken", value.verifiedContentToken)
        put("validation", value.contentValidation.name)
        put("location", JSONObject().apply {
            put("id", value.location.locationId)
            put("uri", value.location.uri)
            put("grantUri", value.location.grantUri)
            put("flags", value.location.grantFlags)
            put("access", value.location.access.name)
            put("revision", value.location.contentRevision)
        })
    }

    private fun descriptor(json: JSONObject): LocalReadDescriptor {
        val source = source(json.getJSONObject("sourceSnapshot"))
        val location = json.getJSONObject("location").let { LocalLocationReference(it.getString("id"), it.getString("uri"),
            it.getString("grantUri"), it.getInt("flags"), LocalAccess.valueOf(it.getString("access")), it.getLong("revision")) }
        return LocalReadDescriptor(track(json.getJSONObject("track")), source, json.getString("alias"), location, json.getString("object"),
            json.optionalString("providerToken"), json.optionalLong("bytes"), json.optionalString("verifiedToken"),
            LocalContentValidation.valueOf(json.getString("validation")))
    }

    private fun source(value: LibrarySource): JSONObject = JSONObject().apply {
        put("source", LibraryCodec.source(value.spec))
        put("grant", LibraryCodec.grant(value.grant))
        put("grantId", value.grantId)
        put("revision", value.revision)
        put("auth", value.authEpoch)
        put("state", value.state.name)
        put("activeScan", value.activeScanId)
        put("latestScan", value.latestScanId)
    }
    private fun source(json: JSONObject) = LibrarySource(LibraryCodec.source(json.getString("source")), LibraryCodec.grant(json.getString("grant")),
        json.getString("grantId"), json.getLong("revision"), json.getLong("auth"), LibrarySourceState.valueOf(json.getString("state")),
        json.optionalString("activeScan"), json.optionalString("latestScan"))

    private fun track(value: TrackKey) = JSONObject().apply { put("provider", value.providerId)
        put("id", value.trackId) }
    private fun track(json: JSONObject) = TrackKey(json.getString("provider"), json.getString("id"))
    private fun tags(value: Map<AudioTagField, List<String>?>): JSONObject = JSONObject().apply {
        for (field in AudioTagField.entries) if (field in value) put(field.name, value[field]?.let(::JSONArray) ?: JSONObject.NULL)
    }
    private fun tags(json: JSONObject): Map<AudioTagField, List<String>?> = AudioTagField.entries.filter { json.has(it.name) }.associateWith {
        if (json.isNull(it.name)) null else json.getJSONArray(it.name).let { array -> List(array.length()) { index -> array.getString(index) } }
    }
    private fun JSONObject.optionalObject(key: String): JSONObject? = if (!has(key) || isNull(key)) null else getJSONObject(key)
    private fun JSONObject.optionalString(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)
    private fun JSONObject.optionalLong(key: String): Long? = if (!has(key) || isNull(key)) null else getLong(key)
}
