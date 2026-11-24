package io.github.onlinemo.meloxwatch.data.backup

import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.library.LibraryCodec
import io.github.onlinemo.meloxwatch.data.library.LocalContentValidation
import io.github.onlinemo.meloxwatch.data.library.LocalReadDescriptor
import io.github.onlinemo.meloxwatch.data.library.RoomLocalLibraryRepository
import io.github.onlinemo.meloxwatch.data.library.LibraryException
import io.github.onlinemo.meloxwatch.data.library.LibraryIssue
import io.github.onlinemo.meloxwatch.data.local.AndroidLocalSources
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.model.TrackKey
import kotlinx.coroutines.CancellationException

internal enum class BackupBindingFamily { SOURCE, LOCATION, NODE, ALIAS, QUEUE }

/** A missing logical reference receives no URI, source grant, fingerprint or audio ownership. */
internal fun syntheticBackupRelinkHint(archiveSha256: String, trackId: String): BackupRelinkHint {
    val key = stableUuid("backup-missing-reference", archiveSha256, trackId)
    val location = "restored-location-" + backupStableId(archiveSha256, BackupBindingFamily.LOCATION.name, key)
    val record = BackupRecord(BackupRecordKind.LOCATION_HINT, key, mapOf("sizeBytes" to BackupCell.Null,
        "modifiedEpochMs" to BackupCell.Null, "contentSha256" to BackupCell.Null, "providerContentTokenHint" to BackupCell.Null))
    return BackupRelinkHint(stableUuid(archiveSha256, trackId, location), trackId, location, archiveSha256, record)
}

/** Fences live only in the private stage. They are never among exportable logical records. */
internal class BackupStoredBinding(val family: BackupBindingFamily, val incomingId: String, val actualId: String,
    val verified: Boolean, val fences: Map<String, BackupCell> = emptyMap()) {
    override fun toString(): String = "BackupStoredBinding(${family.name})"
    fun record(): BackupRecord = BackupRecord(BackupRecordKind.PREFERENCE, "private-binding", fences)
}

/** Permission and complete content/provider proofs are observed again at confirmation. */
internal class BackupPermissionVerifier(
    private val database: MeloXDatabase,
    private val library: RoomLocalLibraryRepository,
    private val sources: AndroidLocalSources,
    private val retainedRead: suspend (TrackKey, String, Long) -> LocalReadDescriptor?,
) {
    suspend fun prepare(stage: SqliteBackupStage, archiveSha256: String, scopes: Set<BackupScope> = stage.header.scopes,
        mode: BackupMergeMode = BackupMergeMode.REPLACE_SELECTED) {
        if (!archiveSha256.matches(DIGEST)) throw BackupException(BackupIssue.INVALID_INPUT)
        stage.clearBindings()
        val dao = database.libraryDao()
        for (family in BackupBindingFamily.entries) {
            if (family == BackupBindingFamily.QUEUE || BackupScope.LIBRARY !in scopes) continue
            val kind = family.recordKind()
            var after: String? = null
            while (true) {
                val page = stage.pageNow(kind, after)
                if (page.isEmpty()) break
                for (row in page) {
                    stage.putBinding(BackupStoredBinding(family, row.key,
                        unresolvedId(family, archiveSha256, row.key), false))
                }
                after = page.last().key
            }
        }
        var after: String? = null
        while (true) {
            if (BackupScope.LIBRARY !in scopes) break
            val page = stage.pageNow(BackupRecordKind.TRACK_LOCATION, after)
            if (page.isEmpty()) break
            for (relation in page) {
                stage.checkActive()
                val key = TrackKey(TrackKey.LOCAL_PROVIDER, relation.text("trackId"))
                val locationId = relation.text("locationId")
                val incoming = stage.row(BackupRecordKind.LOCATION_HINT, locationId) ?: throw BackupException(BackupIssue.INVALID_REFERENCE)
                val existing = dao.location(locationId) ?: continue
                val descriptor = currentDescriptor(key, locationId, existing.contentRevision) ?: continue
                val verified = try { sources.validateRead(descriptor) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: SourceReadException) { if (failure.issue == SourceReadIssue.BUSY) throw BackupException(BackupIssue.BUSY, failure) else continue }
                catch (_: Exception) { continue }
                if (verified.contentValidation != LocalContentValidation.VERIFIED ||
                    verified.copy(contentValidation = descriptor.contentValidation) != descriptor) continue
                val completeHash = descriptor.verifiedContentToken?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.takeIf { it.matches(DIGEST) }
                    ?: descriptor.providerContentToken?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.takeIf { it.matches(DIGEST) }
                val expectedHash = incoming.optionalText("contentSha256")
                val expectedProvider = incoming.optionalText("providerContentTokenHint")
                if (expectedHash == null && (expectedProvider == null || expectedProvider != descriptor.providerContentToken)) continue
                if (incoming.optionalText("contentSha256")?.let { it != completeHash } == true ||
                    incoming.optionalLong("sizeBytes")?.let { it != descriptor.sizeBytes } == true) continue
                val sourceHint = stage.row(BackupRecordKind.SOURCE_HINT, descriptor.source.spec.sourceId) ?: continue
                if (sourceHint.text("kind") != descriptor.source.spec.kind.name ||
                    sourceHint.optionalText("uriHint")?.let { it != descriptor.source.spec.uri } == true) continue
                val actualSource = dao.source(descriptor.source.spec.sourceId) ?: continue
                val actualAlias = dao.currentAlias(descriptor.aliasId) ?: continue
                val actualNode = dao.currentNode(actualAlias.nodeId) ?: continue
                if (actualSource.revision != descriptor.source.revision || actualSource.authEpoch != descriptor.source.authEpoch ||
                    actualSource.grantId != descriptor.source.grantId || actualAlias.locationId != locationId ||
                    actualAlias.uri != descriptor.location.uri || actualAlias.contentRevision != descriptor.location.contentRevision) continue
                // Re-read the same registered route after provider I/O; a version change makes this proof stale.
                val again = currentDescriptor(key, locationId, existing.contentRevision) ?: continue
                if (again != descriptor) continue
                stage.putBinding(BackupStoredBinding(BackupBindingFamily.SOURCE, sourceHint.key, actualSource.sourceId, true, mapOf(
                    "revision" to BackupCell.Integer(actualSource.revision), "authEpoch" to BackupCell.Integer(actualSource.authEpoch),
                    "grantId" to BackupCell.Text(actualSource.grantId), "specSha256" to BackupCell.Text(backupSha256(actualSource.spec.toByteArray(Charsets.UTF_8))),
                )))
                stage.putBinding(BackupStoredBinding(BackupBindingFamily.LOCATION, locationId, locationId, true, mapOf(
                    "contentRevision" to BackupCell.Integer(existing.contentRevision), "sourceId" to BackupCell.Text(actualSource.sourceId),
                    "aliasId" to BackupCell.Text(descriptor.aliasId), "uri" to BackupCell.Text(descriptor.location.uri),
                    "grantUri" to BackupCell.Text(descriptor.location.grantUri), "grantFlags" to BackupCell.Integer(descriptor.location.grantFlags.toLong()),
                    "access" to BackupCell.Text(descriptor.location.access.name), "filename" to BackupCell.Text(actualAlias.filename),
                    "contentSha256" to backupText(completeHash),
                )))
                stage.row(BackupRecordKind.ALIAS_HINT, descriptor.aliasId)?.let { aliasHint ->
                    if (aliasHint.text("sourceId") == sourceHint.key && aliasHint.text("locationId") == locationId &&
                        aliasHint.optionalText("uriHint")?.let { it == descriptor.location.uri } != false) {
                        stage.putBinding(BackupStoredBinding(BackupBindingFamily.ALIAS, aliasHint.key, descriptor.aliasId, true,
                            mapOf("versionId" to BackupCell.Text(actualAlias.versionId))))
                    }
                }
                stage.row(BackupRecordKind.NODE_HINT, actualNode.nodeId)?.let { nodeHint ->
                    if (nodeHint.text("sourceId") == sourceHint.key && nodeHint.optionalText("uriHint")?.let { it == actualNode.uri } != false) {
                        stage.putBinding(BackupStoredBinding(BackupBindingFamily.NODE, nodeHint.key, actualNode.nodeId, true,
                            mapOf("versionId" to BackupCell.Text(actualNode.versionId))))
                    }
                }
            }
            after = page.last().key
        }
        if (BackupScope.QUEUE in scopes) prepareQueue(stage, archiveSha256, scopes, mode)
    }

    /** Standalone queue restore resolves only already registered routes, never the archived URI. */
    private suspend fun prepareQueue(stage: SqliteBackupStage, archiveSha256: String, scopes: Set<BackupScope>, mode: BackupMergeMode) {
        val dao = database.libraryDao()
        var after: String? = null
        while (true) {
            val page = stage.pageNow(BackupRecordKind.QUEUE_ENTRY, after)
            if (page.isEmpty()) break
            for (row in page) {
                stage.checkActive()
                if (row.text("providerId") != TrackKey.LOCAL_PROVIDER) continue
                stage.putBinding(BackupStoredBinding(BackupBindingFamily.QUEUE, row.key,
                    unresolvedId(BackupBindingFamily.QUEUE, archiveSha256, row.key), false))
                val key = TrackKey(TrackKey.LOCAL_PROVIDER, row.text("trackId"))
                val hint = row.optionalText("locationIdHint")
                val descriptor = if (hint == null) try { library.resolveLocalRead(key) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: LibraryException) { if (failure.issue == LibraryIssue.BUSY) throw BackupException(BackupIssue.BUSY, failure) else null }
                    else dao.location(hint)?.let { currentDescriptor(key, hint, it.contentRevision) }
                if (descriptor == null || row.optionalText("uriHint")?.let { it != descriptor.location.uri } == true) continue
                if (BackupScope.LIBRARY in scopes && (mode == BackupMergeMode.REPLACE_SELECTED || dao.currentTrack(key.trackId) == null) &&
                    (stage.binding(BackupBindingFamily.LOCATION, descriptor.location.locationId)?.verified != true ||
                        !stage.hasTrackLocation(key.trackId, descriptor.location.locationId))) continue
                val observed = try { sources.validateRead(descriptor) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: SourceReadException) { if (failure.issue == SourceReadIssue.BUSY) throw BackupException(BackupIssue.BUSY, failure) else null }
                if (observed == null || observed.contentValidation != LocalContentValidation.VERIFIED ||
                    observed.copy(contentValidation = descriptor.contentValidation) != descriptor) continue
                val locationHint = hint?.let { stage.row(BackupRecordKind.LOCATION_HINT, it) }
                if (locationHint != null) {
                    val expectedHash = locationHint.optionalText("contentSha256")
                    val expectedProvider = locationHint.optionalText("providerContentTokenHint")
                    val hash = listOfNotNull(descriptor.verifiedContentToken, descriptor.providerContentToken).firstNotNullOfOrNull {
                        it.takeIf { token -> token.matches(Regex("sha256:[0-9a-f]{64}")) }?.removePrefix("sha256:")
                    }
                    if (expectedHash == null && (expectedProvider == null || expectedProvider != descriptor.providerContentToken) ||
                        expectedHash?.let { it != hash } == true || locationHint.optionalLong("sizeBytes")?.let { it != descriptor.sizeBytes } == true) continue
                }
                val source = dao.source(descriptor.source.spec.sourceId) ?: continue
                val alias = dao.currentAlias(descriptor.aliasId) ?: continue
                if (source.revision != descriptor.source.revision || source.authEpoch != descriptor.source.authEpoch ||
                    source.grantId != descriptor.source.grantId || alias.uri != descriptor.location.uri ||
                    alias.locationId != descriptor.location.locationId || alias.contentRevision != descriptor.location.contentRevision) continue
                if (currentDescriptor(key, descriptor.location.locationId, descriptor.location.contentRevision) != descriptor) continue
                stage.putBinding(BackupStoredBinding(BackupBindingFamily.QUEUE, row.key, descriptor.location.locationId, true, mapOf(
                    "trackId" to BackupCell.Text(key.trackId), "sourceId" to BackupCell.Text(source.sourceId),
                    "sourceRevision" to BackupCell.Integer(source.revision), "sourceAuthEpoch" to BackupCell.Integer(source.authEpoch),
                    "sourceGrantId" to BackupCell.Text(source.grantId), "sourceSpecSha256" to BackupCell.Text(backupSha256(source.spec.toByteArray(Charsets.UTF_8))),
                    "aliasId" to BackupCell.Text(alias.aliasId), "aliasVersionId" to BackupCell.Text(alias.versionId),
                    "uri" to BackupCell.Text(descriptor.location.uri), "grantUri" to BackupCell.Text(descriptor.location.grantUri),
                    "grantFlags" to BackupCell.Integer(descriptor.location.grantFlags.toLong()), "access" to BackupCell.Text(descriptor.location.access.name),
                    "contentRevision" to BackupCell.Integer(descriptor.location.contentRevision),
                )))
            }
            after = page.last().key
        }
    }

    private suspend fun currentDescriptor(key: TrackKey, locationId: String, revision: Long): LocalReadDescriptor? {
        try { return library.resolveLocalRead(key, locationId, revision) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: LibraryException) { if (failure.issue == LibraryIssue.BUSY) throw BackupException(BackupIssue.BUSY, failure) }
        catch (_: Exception) { }
        return try { retainedRead(key, locationId, revision) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: LibraryException) { if (failure.issue == LibraryIssue.BUSY) throw BackupException(BackupIssue.BUSY, failure) else null }
        catch (failure: SourceReadException) { if (failure.issue == SourceReadIssue.BUSY) throw BackupException(BackupIssue.BUSY, failure) else null }
        catch (_: Exception) { null }
    }

    suspend fun checkFences(stage: SqliteBackupStage) {
        val dao = database.libraryDao()
        for (family in BackupBindingFamily.entries) {
            var after: String? = null
            while (true) {
                val page = stage.bindingPage(family, after)
                if (page.isEmpty()) break
                for (binding in page) {
                    if (!binding.verified) continue
                    val fence = binding.record()
                    val valid = when (family) {
                        BackupBindingFamily.SOURCE -> dao.source(binding.actualId)?.let {
                            it.revision == fence.long("revision") && it.authEpoch == fence.long("authEpoch") && it.grantId == fence.text("grantId") &&
                                backupSha256(it.spec.toByteArray(Charsets.UTF_8)) == fence.text("specSha256")
                        } == true
                        BackupBindingFamily.LOCATION -> dao.location(binding.actualId)?.contentRevision == fence.long("contentRevision")
                        BackupBindingFamily.NODE -> dao.currentNode(binding.actualId)?.versionId == fence.text("versionId")
                        BackupBindingFamily.ALIAS -> dao.currentAlias(binding.actualId)?.versionId == fence.text("versionId")
                        BackupBindingFamily.QUEUE -> {
                            val source = dao.source(fence.text("sourceId"))
                            val alias = dao.currentAlias(fence.text("aliasId"))
                            source != null && alias != null && source.revision == fence.long("sourceRevision") &&
                                source.authEpoch == fence.long("sourceAuthEpoch") && source.grantId == fence.text("sourceGrantId") &&
                                backupSha256(source.spec.toByteArray(Charsets.UTF_8)) == fence.text("sourceSpecSha256") &&
                                alias.versionId == fence.text("aliasVersionId") && alias.locationId == binding.actualId &&
                                dao.location(binding.actualId)?.contentRevision == fence.long("contentRevision")
                        }
                    }
                    if (!valid) throw BackupException(BackupIssue.STALE_PREVIEW)
                }
                after = page.last().incomingId
            }
        }
    }
    private fun unresolvedId(family: BackupBindingFamily, archiveSha256: String, key: String): String =
        "restored-" + family.name.lowercase(java.util.Locale.ROOT) + "-" +
            backupStableId(archiveSha256, family.name, key)
    private fun BackupBindingFamily.recordKind() = when (this) {
        BackupBindingFamily.SOURCE -> BackupRecordKind.SOURCE_HINT
        BackupBindingFamily.LOCATION -> BackupRecordKind.LOCATION_HINT
        BackupBindingFamily.NODE -> BackupRecordKind.NODE_HINT
        BackupBindingFamily.ALIAS -> BackupRecordKind.ALIAS_HINT
        BackupBindingFamily.QUEUE -> BackupRecordKind.QUEUE_ENTRY
    }
    private val DIGEST = Regex("[0-9a-f]{64}")
}
