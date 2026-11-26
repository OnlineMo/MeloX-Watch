package io.github.onlinemo.meloxwatch.data.backup

import android.content.Context
import android.os.CancellationSignal
import androidx.room.deferredTransaction
import androidx.room.useReaderConnection
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontAsset
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontImportRequest
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontLimits
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSelection
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSettings
import io.github.onlinemo.meloxwatch.data.local.AndroidLocalSources
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.local.requirePlatformLocalProvider
import io.github.onlinemo.meloxwatch.data.local.LocalArtifactException
import io.github.onlinemo.meloxwatch.data.local.LocalArtifactIssue
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import io.github.onlinemo.meloxwatch.data.lyrics.LocalLyricsParser
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsEncodingChoice
import io.github.onlinemo.meloxwatch.data.lyrics.active.LyricsDocumentCopies
import io.github.onlinemo.meloxwatch.data.lyrics.active.OwnedLyricsProvider
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsAssociation
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsTrackLimits
import io.github.onlinemo.meloxwatch.data.lyrics.store.decode
import io.github.onlinemo.meloxwatch.data.metadata.LocalMetadataAssociationRow
import io.github.onlinemo.meloxwatch.data.metadata.LocalMetadataArtworkFiles
import io.github.onlinemo.meloxwatch.data.metadata.MetadataAssociation
import io.github.onlinemo.meloxwatch.data.metadata.MetadataProvenance
import io.github.onlinemo.meloxwatch.data.metadata.MetadataPurpose
import io.github.onlinemo.meloxwatch.data.metadata.MetadataStoreException
import io.github.onlinemo.meloxwatch.data.metadata.MetadataStoreIssue
import io.github.onlinemo.meloxwatch.data.metadata.decode
import io.github.onlinemo.meloxwatch.data.themes.BackgroundImageRef
import io.github.onlinemo.meloxwatch.data.themes.CustomBackgroundImportRequest
import io.github.onlinemo.meloxwatch.data.themes.LocalBackgroundImages
import io.github.onlinemo.meloxwatch.data.themes.LocalThemeRepository
import io.github.onlinemo.meloxwatch.data.themes.ThemeApplyRequest
import io.github.onlinemo.meloxwatch.data.themes.ThemeCatalog
import io.github.onlinemo.meloxwatch.data.themes.ThemeChoice
import io.github.onlinemo.meloxwatch.data.themes.ThemeException
import io.github.onlinemo.meloxwatch.data.themes.ThemeImportRequest
import io.github.onlinemo.meloxwatch.data.themes.ThemeIssue
import io.github.onlinemo.meloxwatch.data.themes.ThemeRef
import io.github.onlinemo.meloxwatch.data.themes.ThemeStorageRecords
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundException
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundIssue
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontBackupAccess
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class BackupResourceUse(val record: BackupRecord, val field: String)
internal class PreparedBackupResources(val targets: Map<String, BackupRecord>, val missingReferences: Long) {
    override fun toString(): String = "PreparedBackupResources"
}
internal data class BackupResourceInventory(val existing: Long, val conflicts: Long)

/** Imports use the existing validators, quota owners and fresh font consent; originals stay read-only. */
internal class BackupNativeResourceBridge(
    context: Context,
    private val barrier: BackupMutationBarrier,
    private val database: MeloXDatabase,
    private val io: LocalProviderIo,
    private val sources: AndroidLocalSources,
    private val copies: LyricsDocumentCopies,
    private val background: LocalBackgroundImages,
    private val themes: LocalThemeRepository,
    private val parser: LocalLyricsParser = LocalLyricsParser(),
    private val metadataArtwork: LocalMetadataArtworkFiles? = null,
) {
    private val context = context.applicationContext

    suspend fun missingPreferenceReferences(stage: SqliteBackupStage, scopes: Set<BackupScope>, mode: BackupMergeMode,
        configurations: Map<String, BackupConfigVersion>): Long {
        barrier.requireExclusiveOwner()
        if (BackupScope.PREFERENCES !in scopes || mode != BackupMergeMode.REPLACE_SELECTED) return 0L
        val resourcesSelected = BackupScope.RESOURCES in scopes
        val beforeFonts = BackupPreferencesCodec.fonts(configurations.getValue("lyrics-fonts").record)
        val wantedFonts = BackupPreferencesCodec.fonts(stage.row(BackupRecordKind.PREFERENCE, "lyrics-fonts")
            ?: throw BackupException(BackupIssue.INVALID_REFERENCE))
        val existingFonts = listOfNotNull(beforeFonts.original.asset, beforeFonts.translation.asset)
        var missing = 0L
        for (asset in listOfNotNull(wantedFonts.original.asset, wantedFonts.translation.asset)) {
            val retained = existingFonts.any { it.sha256 == asset.sha256 && it.byteCount == asset.byteCount && it.displayName == asset.displayName }
            if (!retained && (!resourcesSelected || stage.row(BackupRecordKind.RESOURCE,
                    BackupLogicalSchema.resourceId(BackupResourceKind.FONT, asset.sha256)) == null)) missing++
        }
        val wantedImage = BackupPreferencesCodec.background(stage.row(BackupRecordKind.PREFERENCE, "background")
            ?: throw BackupException(BackupIssue.INVALID_REFERENCE)).customImage
        val existingImage = BackupPreferencesCodec.background(configurations.getValue("background").record).customImage
        if (wantedImage != null && existingImage?.copy(id = wantedImage.id) != wantedImage &&
            (!resourcesSelected || stage.row(BackupRecordKind.RESOURCE, BackupLogicalSchema.resourceId(BackupResourceKind.IMAGE, wantedImage.sha256)) == null)) missing++
        val catalog = themes.readCatalogForBackup()
        val wantedTheme = stage.row(BackupRecordKind.PREFERENCE, "theme") ?: throw BackupException(BackupIssue.INVALID_REFERENCE)
        for (prefix in listOf("selected", "previous")) {
            val requested = BackupPreferencesCodec.choice(wantedTheme, prefix)
            if (requested !is ThemeChoice.Installed) continue
            val retained = catalog.installed.any { it.ref.themeId == requested.ref.themeId && it.ref.manifestSha256 == requested.ref.manifestSha256 }
            if (!retained && (!resourcesSelected || stage.row(BackupRecordKind.THEME_INSTALLATION, requested.ref.installationId) == null)) missing++
        }
        return missing
    }

    /** Count actual registered owners with a disk-backed identity set; repeated lyric uses count once. */
    suspend fun inventory(stage: SqliteBackupStage, scopes: Set<BackupScope>,
        configurations: Map<String, BackupConfigVersion>): BackupResourceInventory {
        barrier.requireExclusiveOwner()
        stage.beginResourceInventory()
        val catalog = if (BackupScope.PREFERENCES in scopes) themes.readCatalogForBackup() else null
        if (BackupScope.PREFERENCES in scopes) {
            val fonts = BackupPreferencesCodec.fonts(configurations.getValue("lyrics-fonts").record)
            for (asset in listOfNotNull(fonts.original.asset, fonts.translation.asset)) stage.inventoryResource(BackupResourceKind.FONT, asset.sha256)
            BackupPreferencesCodec.background(configurations.getValue("background").record).customImage?.let {
                stage.inventoryResource(BackupResourceKind.IMAGE, it.sha256)
            }
            for (installation in checkNotNull(catalog).installed) stage.inventoryResource(BackupResourceKind.THEME_PACKAGE, installation.packageSha256)
        }
        if (BackupScope.LYRICS in scopes) database.useReaderConnection { reader -> reader.deferredTransaction {
            var after: String? = null
            while (true) {
                val page = reader.usePrepared("SELECT trackId,primaryUri,primaryConfirmedContentSha256,translationUri,translationConfirmedContentSha256 FROM lyrics_track_preferences WHERE (? IS NULL OR trackId>?) ORDER BY trackId LIMIT 100") { statement ->
                    statement.bindNullableText(1, after); statement.bindNullableText(2, after)
                    buildList { while (statement.step()) {
                        stage.checkActive()
                        add(statement.getText(0) to listOfNotNull(
                            if (statement.isNull(1) || statement.isNull(2)) null else statement.getText(1) to statement.getText(2),
                            if (statement.isNull(3) || statement.isNull(4)) null else statement.getText(3) to statement.getText(4),
                        ))
                    } }
                }
                if (page.isEmpty()) break
                for ((_, documents) in page) for ((uri, hash) in documents) {
                    if (OwnedLyricsProvider.token(context, AndroidLocalSources.contentUri(uri)) != null) stage.inventoryResource(BackupResourceKind.LYRICS, hash)
                }
                after = page.last().first
            }
        } }
        eachMetadata(scopes, stage::checkActive) { association ->
            if (association.assetId != null) stage.inventoryResource(
                BackupMetadataAssociations.resourceKind(BackupMetadataAssociations.kind(association.purpose)),
                checkNotNull(association.sha256))
        }
        var conflicts = 0L
        each(stage, BackupRecordKind.RESOURCE) { row ->
            val resource = BackupLogicalSchema.resourceDescriptor(row)
            val alreadyPresent = if (resource.kind != BackupResourceKind.THEME_PACKAGE) stage.inResourceInventory(resource.id)
                else stage.firstResourceUse(resource.id, BackupRecordKind.THEME_INSTALLATION)?.record?.let { installation ->
                    catalog?.installed?.any { it.ref.themeId == installation.text("themeId") && it.ref.manifestSha256 == installation.text("manifestSha256") } == true
                } == true
            if (alreadyPresent) conflicts = Math.addExact(conflicts, 1L)
        }
        return BackupResourceInventory(stage.inventorySize(), conflicts)
    }

    suspend fun capture(stage: SqliteBackupStage, configurations: Map<String, BackupConfigVersion>, fonts: LyricsFontBackupAccess?): Map<String, String> {
        barrier.requireExclusiveOwner()
        if (BackupScope.RESOURCES !in stage.header.scopes) return emptyMap()
        val packages = LinkedHashMap<String, String>()
        if (BackupScope.PREFERENCES in stage.header.scopes) {
            val fontAccess = fonts ?: throw BackupException(BackupIssue.BUSY)
            val selectedFonts = BackupPreferencesCodec.fonts(configurations.getValue("lyrics-fonts").record)
            for (asset in listOfNotNull(selectedFonts.original.asset, selectedFonts.translation.asset).distinctBy { it.id }) captureResource(stage,
                BackupResourceKind.FONT, true) { output -> fontAccess.exportForBackup(asset, output) }
            BackupPreferencesCodec.background(configurations.getValue("background").record).customImage?.let { image ->
                captureResource(stage, BackupResourceKind.IMAGE, false) { output -> background.exportForBackup(image, output) }
            }
            val catalog = themes.readCatalogForBackup()
            for (installation in catalog.installed) {
                val resource = captureResource(stage, BackupResourceKind.THEME_PACKAGE, installation.fontBytes > 0L) { output -> themes.exportForBackup(installation.ref, output) }
                packages[installation.ref.installationId] = resource.id
                stage.record(BackupRecord(BackupRecordKind.THEME_INSTALLATION, installation.ref.installationId, mapOf(
                    "themeId" to BackupCell.Text(installation.ref.themeId), "manifestSha256" to BackupCell.Text(installation.ref.manifestSha256),
                    "displayName" to BackupCell.Text(installation.displayName), "packageResourceId" to BackupCell.Text(resource.id),
                    "resourceBytes" to BackupCell.Integer(installation.resourceBytes), "fontBytes" to BackupCell.Integer(installation.fontBytes),
                )))
            }
        }
        if (BackupScope.LYRICS in stage.header.scopes) each(stage, BackupRecordKind.LYRICS_TRACK) { row ->
            val current = database.lyricsDao().row(row.key)?.decode(TrackKey(TrackKey.LOCAL_PROVIDER, row.key))
                ?: throw BackupException(BackupIssue.STALE_PREVIEW)
            for (prefix in listOf("primary", "translation")) {
                val id = row.optionalText(prefix + "ResourceId") ?: continue
                if (stage.row(BackupRecordKind.RESOURCE, id) != null) continue
                val association = if (prefix == "primary") current.primary else current.translation
                if (association == null || association.confirmedContentSha256 != row.text(prefix + "Sha256") ||
                    association.encoding.name != row.text(prefix + "Encoding")) throw BackupException(BackupIssue.STALE_PREVIEW)
                val uri = association.uri
                val owned = OwnedLyricsProvider.token(context, AndroidLocalSources.contentUri(uri)) != null
                val external = if (owned) null else {
                    // Check locality before any provider query/open can cause a remote read.
                    physical { signal ->
                        signal.throwIfCanceled()
                        try { requirePlatformLocalProvider(context, AndroidLocalSources.contentUri(uri)) }
                        catch (failure: LocalArtifactException) {
                            throw BackupException(if (failure.issue == LocalArtifactIssue.LOCAL_PROVIDER_REQUIRED)
                                BackupIssue.UNSUPPORTED_RESOURCE else BackupIssue.INVALID_RESOURCE, failure)
                        }
                    }
                    if (association.readFlags != LyricsTrackLimits.READ_PERMISSION_FLAG) throw BackupException(BackupIssue.PERMISSION_DENIED)
                    val source = LocalSourceSpec(association.sourceId, LocalSourceKind.DOCUMENT, uri, association.readFlags)
                    val observed = sources.observe(source)
                    if (observed.access.persistedRead != ObservedCapability.AVAILABLE) throw BackupException(BackupIssue.PERMISSION_DENIED)
                    if (observed.root.objectId != association.documentId || observed.root.uri != uri || observed.root.directory) {
                        throw BackupException(BackupIssue.STALE_PREVIEW)
                    }
                    sources.verifyObservedCandidate(source, observed.root)
                    observed
                }
                val resource = captureResource(stage, BackupResourceKind.LYRICS, false) { output ->
                    if (owned) copies.exportForBackup(uri, row.text(prefix + "Sha256"), output)
                    else copies.exportExternalForBackup(checkNotNull(external).root, row.text(prefix + "Sha256"), output)
                }
                if (external != null) {
                    sources.verifyObservedCandidate(external.source, external.root)
                    if (sources.observe(external.source).access.persistedRead != ObservedCapability.AVAILABLE) throw BackupException(BackupIssue.PERMISSION_DENIED)
                }
                if (resource.id != id) throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
            }
        }
        for (kind in BackupMetadataAssociations.KINDS) {
            if (kind.scope !in stage.header.scopes) continue
            each(stage, kind) { row ->
                val id = row.optionalText("resourceId") ?: return@each
                val current = currentMetadata(row) ?: throw BackupException(BackupIssue.STALE_PREVIEW)
                if (current.assetId == null || !BackupMetadataAssociations.record(current,
                        row.values.getValue("cueReference"), true,
                        row.kind == BackupRecordKind.METADATA_LYRICS && row.flag("primaryLinked")).sameContent(row)) {
                    throw BackupException(BackupIssue.STALE_PREVIEW)
                }
                if (stage.row(BackupRecordKind.RESOURCE, id) != null) return@each
                val resource = captureResource(stage, BackupMetadataAssociations.resourceKind(kind), false) { output ->
                    metadataBoundary {
                        when (current.purpose) {
                            MetadataPurpose.LYRICS -> copies.exportForBackup(ownedLyricsUri(checkNotNull(current.assetId)),
                                checkNotNull(current.sha256), output)
                            MetadataPurpose.ARTWORK -> artworkOwner().exportForBackup(checkNotNull(current.assetId),
                                checkNotNull(current.byteCount), checkNotNull(current.sha256), output)
                        }
                    }
                }
                if (resource.id != id || resource.bytes != row.long("bytes")) {
                    throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
                }
            }
        }
        return packages
    }

    suspend fun validate(stage: SqliteBackupStage, fonts: LyricsFontBackupAccess) {
        barrier.requireExclusiveOwner()
        each(stage, BackupRecordKind.RESOURCE) { row ->
            val resource = BackupLogicalSchema.resourceDescriptor(row)
            val file = stage.verifiedResourceFile(resource.id)
            when (resource.kind) {
                BackupResourceKind.FONT -> {
                    val use = stage.firstResourceUse(resource.id, BackupRecordKind.PREFERENCE) ?: throw BackupException(BackupIssue.INVALID_REFERENCE)
                    if (use.record.key != "lyrics-fonts") throw BackupException(BackupIssue.INVALID_REFERENCE)
                    val value = BackupPreferencesCodec.fonts(use.record)
                    val asset = if (use.field == "original") value.original.asset else if (use.field == "translation") value.translation.asset else null
                    if (asset == null || asset.byteCount != resource.bytes || asset.sha256 != resource.sha256) throw BackupException(BackupIssue.INVALID_REFERENCE)
                    fonts.validateForBackup(file, asset)
                }
                BackupResourceKind.IMAGE -> {
                    val preference = stage.firstResourceUse(resource.id, BackupRecordKind.PREFERENCE)
                    val metadata = stage.firstResourceUse(resource.id, BackupRecordKind.METADATA_ARTWORK)
                    if (preference == null && metadata == null) throw BackupException(BackupIssue.INVALID_REFERENCE)
                    if (preference != null) {
                        if (preference.record.key != "background") throw BackupException(BackupIssue.INVALID_REFERENCE)
                        val image = BackupPreferencesCodec.background(preference.record).customImage ?: throw BackupException(BackupIssue.INVALID_REFERENCE)
                        if (image.byteCount != resource.bytes || image.sha256 != resource.sha256) throw BackupException(BackupIssue.INVALID_REFERENCE)
                        background.validateForBackup(file, image)
                    }
                    if (metadata != null) {
                        if (metadata.record.long("bytes") != resource.bytes || metadata.record.text("sha256") != resource.sha256) {
                            throw BackupException(BackupIssue.INVALID_REFERENCE)
                        }
                        metadataBoundary { artworkOwner().validateForBackup(file, resource.bytes, resource.sha256) }
                    }
                }
                BackupResourceKind.THEME_PACKAGE -> {
                    val use = stage.firstResourceUse(resource.id, BackupRecordKind.THEME_INSTALLATION) ?: throw BackupException(BackupIssue.INVALID_REFERENCE)
                    val payload = stage.directory.newGeneration()
                    var failure: Throwable? = null
                    try {
                        val actual = themes.validateForBackup(file, payload)
                        val expected = use.record
                        if (actual.manifest.id != expected.text("themeId") || actual.manifest.displayName != expected.text("displayName") ||
                            actual.manifestSha256 != expected.text("manifestSha256") || actual.manifest.resources.sumOf { it.byteCount } != expected.long("resourceBytes") ||
                            actual.manifest.resources.filter { it.kind.name == "FONT" }.sumOf { it.byteCount } != expected.long("fontBytes")) {
                            throw BackupException(BackupIssue.INVALID_RESOURCE)
                        }
                    } catch (error: Throwable) { failure = error; throw error }
                    finally { try { stage.directory.retireGeneration(payload) }
                    catch (closing: Throwable) { val outgoing = failure; if (outgoing == null) throw closing else if (outgoing !== closing) outgoing.addSuppressed(closing) } }
                }
                BackupResourceKind.LYRICS -> {
                    val encodings = stage.lyricsEncodings(resource.id)
                    if (encodings.isEmpty()) throw BackupException(BackupIssue.INVALID_REFERENCE)
                    val bytes = physical { signal ->
                        signal.throwIfCanceled()
                        stage.openResource(resource.id).use { input ->
                            val result = ByteArray(resource.bytes.toInt())
                            var offset = 0
                            while (offset < result.size) {
                                signal.throwIfCanceled()
                                val count = input.read(result, offset, minOf(64 * 1024, result.size - offset))
                                if (count <= 0) throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
                                offset += count
                            }
                            if (input.read() != -1) throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
                            result
                        }
                    }
                    if (backupSha256(bytes) != resource.sha256) throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
                    for (encoding in encodings) { parser.parse(bytes, encoding); currentCoroutineContext().ensureActive() }
                }
            }
        }
    }

    suspend fun retainBefore(before: SqliteBackupStage, configurations: Map<String, BackupConfigVersion>, fonts: LyricsFontBackupAccess) {
        barrier.requireExclusiveOwner()
        val selectedFonts = BackupPreferencesCodec.fonts(configurations.getValue("lyrics-fonts").record)
        fonts.retainForBackup(listOfNotNull(selectedFonts.original.asset, selectedFonts.translation.asset).distinctBy { it.id })
        background.retainForBackup(listOfNotNull(BackupPreferencesCodec.background(configurations.getValue("background").record).customImage))
        copies.beginBackupRetention()
        each(before, BackupRecordKind.LYRICS_TRACK) { row ->
            for (prefix in listOf("primary", "translation")) row.optionalText(prefix + "UriHint")?.let { uri ->
                if (OwnedLyricsProvider.token(context, AndroidLocalSources.contentUri(uri)) != null) copies.retainForBackup(uri)
            }
        }
        metadataBoundary {
            metadataArtwork?.beginBackupRetention()
            eachMetadata(before.header.scopes, before::checkActive) { association ->
                association.assetId?.let { assetId ->
                    when (association.purpose) {
                        MetadataPurpose.LYRICS -> copies.retainForBackup(ownedLyricsUri(assetId))
                        MetadataPurpose.ARTWORK -> artworkOwner().retainForBackup(assetId)
                    }
                }
            }
        }
    }

    suspend fun prepare(run: BackupRestoreRun, incoming: SqliteBackupStage, before: SqliteBackupStage,
        configurations: Map<String, BackupConfigVersion>, fonts: LyricsFontBackupAccess, session: BackupResourceSession,
        journal: BackupRestoreJournal, fontLicenseAcknowledged: Boolean): PreparedBackupResources {
        barrier.requireExclusiveOwner()
        val resourcesSelected = BackupScope.RESOURCES in run.scopes
        if (resourcesSelected && !fontLicenseAcknowledged) each(incoming, BackupRecordKind.RESOURCE) { resource ->
            if (resource.flag("licenseRequired")) throw BackupException(BackupIssue.FONT_LICENSE_REQUIRED)
        }
        val targets = configurations.mapValuesTo(LinkedHashMap()) { it.value.record }
        val replacingPreferences = BackupScope.PREFERENCES in run.scopes && run.mode == BackupMergeMode.REPLACE_SELECTED
        if (replacingPreferences) for (key in BackupPreferencesCodec.KEYS) targets[key] = canonicalConfigPreference(
            incoming.row(BackupRecordKind.PREFERENCE, key) ?: throw BackupException(BackupIssue.INVALID_REFERENCE),
        )
        var missing = 0L
        val beforeFonts = BackupPreferencesCodec.fonts(configurations.getValue("lyrics-fonts").record)
        val retainedFonts = listOfNotNull(beforeFonts.original.asset, beforeFonts.translation.asset).distinctBy { it.id }.toMutableList()
        if (replacingPreferences) {
            val wanted = BackupPreferencesCodec.fonts(targets.getValue("lyrics-fonts"))
            suspend fun selection(requested: LyricsFontSelection, retained: LyricsFontSelection): LyricsFontSelection {
                val asset = requested.asset ?: return requested
                val existing = retainedFonts.firstOrNull { it.sha256 == asset.sha256 && it.byteCount == asset.byteCount && it.displayName == asset.displayName }
                if (existing != null) return requested.copy(asset = existing)
                val id = BackupLogicalSchema.resourceId(BackupResourceKind.FONT, asset.sha256)
                if (!resourcesSelected || incoming.row(BackupRecordKind.RESOURCE, id) == null) { missing++; return retained }
                if (!fontLicenseAcknowledged) throw BackupException(BackupIssue.FONT_LICENSE_REQUIRED)
                val imported = fonts.importFont(LyricsFontImportRequest(session.uri(context, id), asset.displayName,
                    LyricsFontLimits.LICENSE_ACKNOWLEDGMENT_VERSION), retainedFonts.map { it.id }.toSet())
                if (imported.asset.sha256 != asset.sha256 || imported.asset.byteCount != asset.byteCount) {
                    fonts.discard(imported.asset, retainedFonts.map { it.id }.toSet())
                    throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
                }
                journal.planResource(run.id, BackupResourcePlan(BackupResourceOwner.FONT, asset.id, id, fontMetadata(imported.asset)))
                retainedFonts += imported.asset
                fonts.retainForBackup(retainedFonts.distinctBy { it.id })
                journal.confirmResource(run.id, BackupResourceOwner.FONT, asset.id)
                return requested.copy(asset = imported.asset)
            }
            targets["lyrics-fonts"] = BackupPreferencesCodec.fonts(LyricsFontSettings(original = selection(wanted.original, beforeFonts.original),
                translation = selection(wanted.translation, beforeFonts.translation)))
            val originalBackground = BackupPreferencesCodec.background(configurations.getValue("background").record)
            val wantedBackground = BackupPreferencesCodec.background(targets.getValue("background"))
            val image = wantedBackground.customImage
            if (image != null) {
                val existing = originalBackground.customImage?.takeIf { it.sha256 == image.sha256 && it.byteCount == image.byteCount &&
                    it.mediaType == image.mediaType && it.width == image.width && it.height == image.height && it.orientation == image.orientation }
                val id = BackupLogicalSchema.resourceId(BackupResourceKind.IMAGE, image.sha256)
                targets["background"] = when {
                    existing != null -> BackupPreferencesCodec.background(wantedBackground.copy(customImage = existing))
                    !resourcesSelected || incoming.row(BackupRecordKind.RESOURCE, id) == null -> {
                        missing++; configurations.getValue("background").record
                    }
                    else -> {
                        val preview = background.prepareImage(CustomBackgroundImportRequest(UUID.randomUUID().toString(), session.uri(context, id), "Background"))
                        if (preview.image.sha256 != image.sha256 || preview.image.byteCount != image.byteCount || preview.image.mediaType != image.mediaType ||
                            preview.image.width != image.width || preview.image.height != image.height || preview.image.orientation != image.orientation) {
                            background.discardPreview(preview.token)
                            throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
                        }
                        try {
                            journal.planResource(run.id, BackupResourcePlan(BackupResourceOwner.BACKGROUND, image.id, id,
                                imageMetadata(preview.image) + ("previewToken" to BackupCell.Text(preview.token))))
                            background.retainForBackup(listOfNotNull(originalBackground.customImage, preview.image).distinctBy { it.id })
                            journal.confirmResource(run.id, BackupResourceOwner.BACKGROUND, image.id)
                        } catch (failure: Throwable) {
                            // A rejected/lost journal acknowledgement must not retain UI admission.
                            background.retirePreview(preview.token)
                            throw failure
                        }
                        BackupPreferencesCodec.background(wantedBackground.copy(customImage = preview.image))
                    }
                }
            }
        }
        val themeMapping = LinkedHashMap<String, ThemeRef>()
        if (resourcesSelected && BackupScope.PREFERENCES in run.scopes) each(incoming, BackupRecordKind.THEME_INSTALLATION) { installation ->
            val id = installation.text("packageResourceId")
            val current = themes.readCatalogForBackup()
            val existing = current.installed.firstOrNull { it.ref.themeId == installation.text("themeId") && it.ref.manifestSha256 == installation.text("manifestSha256") }
            if (existing != null) {
                themeMapping[installation.key] = existing.ref
                journal.planResource(run.id, BackupResourcePlan(BackupResourceOwner.THEME, installation.key, id,
                    themeMetadata(existing.ref, existing.packageSha256, existing.displayName, existing.resourceBytes, existing.fontBytes) +
                        mapOf("added" to BackupCell.Flag(false), "resourceSha256" to BackupCell.Text(incoming.resource(id).sha256))))
                journal.confirmResource(run.id, BackupResourceOwner.THEME, installation.key)
            } else {
                val preview = themes.prepareImport(ThemeImportRequest(UUID.randomUUID().toString(), session.uri(context, id)))
                val expected = incoming.resource(id)
                if (preview.packageSha256 != expected.sha256 || preview.manifest.id != installation.text("themeId") ||
                    preview.manifest.displayName != installation.text("displayName")) {
                    themes.discardPreview(preview.token)
                    throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
                }
                val ref = ThemeRef(preview.token, installation.text("themeId"), installation.text("manifestSha256"))
                val operation = UUID.randomUUID().toString()
                val applied = try {
                    journal.planResource(run.id, BackupResourcePlan(BackupResourceOwner.THEME, installation.key, id,
                        themeMetadata(ref, expected.sha256, installation.text("displayName"), installation.long("resourceBytes"), installation.long("fontBytes")) + mapOf(
                            "added" to BackupCell.Flag(true), "expectedRevision" to BackupCell.Integer(preview.expectedRevision),
                            "resourceSha256" to BackupCell.Text(expected.sha256),
                            "previewToken" to BackupCell.Text(preview.token), "operationId" to BackupCell.Text(operation),
                        )))
                    themes.apply(ThemeApplyRequest(operation, preview.token, preview.expectedRevision,
                        replaces = preview.sameIdInstallations.firstOrNull(), fontLicenseAcknowledgmentVersion =
                            if (fontLicenseAcknowledged) LyricsFontLimits.LICENSE_ACKNOWLEDGMENT_VERSION else null))
                } catch (failure: Throwable) {
                    themes.retirePreview(preview.token)
                    throw failure
                }
                if (applied.installedRef != ref || applied.cleanupRequired) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
                themeMapping[installation.key] = ref
                journal.confirmResource(run.id, BackupResourceOwner.THEME, installation.key)
            }
        }
        if (replacingPreferences) {
            val wanted = targets.getValue("theme")
            val original = configurations.getValue("theme").record
            val current = themes.readCatalogForBackup()
            fun choice(prefix: String): ThemeChoice {
                val requested = BackupPreferencesCodec.choice(wanted, prefix)
                if (requested !is ThemeChoice.Installed) return requested
                val mapped = themeMapping[requested.ref.installationId] ?: current.installed.firstOrNull {
                    it.ref.themeId == requested.ref.themeId && it.ref.manifestSha256 == requested.ref.manifestSha256
                }?.ref
                if (mapped != null) return ThemeChoice.Installed(mapped)
                missing++
                return BackupPreferencesCodec.choice(original, prefix)
            }
            targets["theme"] = BackupPreferencesCodec.theme(choice("selected"), choice("previous"))
        }
        if (resourcesSelected && BackupScope.LYRICS in run.scopes) each(incoming, BackupRecordKind.RESOURCE) { row ->
            val resource = BackupLogicalSchema.resourceDescriptor(row)
            if (resource.kind != BackupResourceKind.LYRICS) return@each
            prepareLyricsResource(run, incoming, before, session, journal, resource.id, resource.bytes, resource.sha256,
                allowImport = true)
        }
        for (kind in BackupMetadataAssociations.KINDS) {
            if (kind.scope !in run.scopes) continue
            each(incoming, kind) { row ->
                val hash = row.optionalText("sha256") ?: return@each
                val current = currentMetadata(row)
                if (run.mode == BackupMergeMode.MERGE_KEEP_CURRENT && current != null &&
                    (current.revision != 0L || current.sha256 != null)) return@each
                val id = checkNotNull(BackupMetadataAssociations.resourceId(row))
                val allowImport = resourcesSelected && incoming.row(BackupRecordKind.RESOURCE, id) != null
                metadataBoundary {
                    when (BackupMetadataAssociations.purpose(kind)) {
                        MetadataPurpose.LYRICS -> prepareLyricsResource(run, incoming, before, session, journal, id,
                            row.long("bytes"), hash, allowImport, current)
                        MetadataPurpose.ARTWORK -> prepareArtworkResource(run, incoming, before, session, journal, id,
                            row.long("bytes"), hash, allowImport, current)
                    }
                }
            }
        }
        return PreparedBackupResources(targets, missing)
    }

    /** Reuse is admitted only after reading the actual immutable file; a portable hint supplies no access. */
    private suspend fun prepareLyricsResource(run: BackupRestoreRun, incoming: SqliteBackupStage, before: SqliteBackupStage,
        session: BackupResourceSession, journal: BackupRestoreJournal, id: String, bytes: Long, sha256: String,
        allowImport: Boolean, current: MetadataAssociation? = null) {
        journal.resource(BackupResourceOwner.LYRICS, id)?.let { plan ->
            val value = privateRecord(plan)
            if (!plan.confirmed || value.long("bytes") != bytes || value.text("sha256") != sha256) {
                throw BackupException(BackupIssue.RECOVERY_REQUIRED)
            }
            return
        }
        val candidates = LinkedHashSet<String>()
        current?.takeIf { it.sha256 == sha256 && it.byteCount == bytes }?.assetId?.let { candidates += ownedLyricsUri(it) }
        before.firstResourceUse(id, BackupRecordKind.LYRICS_TRACK)?.let { use ->
            use.record.optionalText(use.field + "UriHint")?.takeIf {
                OwnedLyricsProvider.token(context, AndroidLocalSources.contentUri(it)) != null
            }?.let(candidates::add)
        }
        before.firstResourceUse(id, BackupRecordKind.METADATA_LYRICS)?.record?.let { row ->
            currentMetadata(row)?.takeIf { it.sha256 == sha256 && it.byteCount == bytes }?.assetId?.let { candidates += ownedLyricsUri(it) }
        }
        var reused: String? = null
        for (candidate in candidates) {
            if (existingFileIsValid {
                    val output = BackupResourceWriter.create(incoming.directory, BackupResourceKind.LYRICS, incoming::checkActive)
                    try {
                        copies.exportForBackup(candidate, sha256, output)
                        physical { signal -> signal.throwIfCanceled(); output.verifyWrittenFile {
                            if (it.length() != bytes) throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
                        } }
                    } finally { output.close() }
                }) { reused = candidate; break }
        }
        if (reused == null && !allowImport) return
        val copy = if (reused == null) copies.createForBackup(session.uri(context, id), bytes, sha256) else null
        val uri = copy?.uri ?: checkNotNull(reused)
        try {
            val source = LocalSourceSpec("lyrics:" + UUID.randomUUID().toString(), LocalSourceKind.DOCUMENT, uri)
            val observed = sources.observe(source)
            if (observed.root.directory || observed.root.uri != uri || observed.root.sizeBytes?.let { it != bytes } == true ||
                OwnedLyricsProvider.token(context, AndroidLocalSources.contentUri(uri)) == null) {
                throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
            }
            journal.planResource(run.id, BackupResourcePlan(BackupResourceOwner.LYRICS, id, id, mapOf(
                "uri" to BackupCell.Text(uri), "sourceId" to BackupCell.Text(source.sourceId), "documentId" to BackupCell.Text(observed.root.objectId),
                "sha256" to BackupCell.Text(sha256), "bytes" to BackupCell.Integer(bytes), "added" to BackupCell.Flag(copy != null),
            )))
            copies.retainForBackup(uri)
            journal.confirmResource(run.id, BackupResourceOwner.LYRICS, id)
        } finally { copy?.release() }
    }

    private suspend fun prepareArtworkResource(run: BackupRestoreRun, incoming: SqliteBackupStage, before: SqliteBackupStage,
        session: BackupResourceSession, journal: BackupRestoreJournal, id: String, bytes: Long, sha256: String,
        allowImport: Boolean, current: MetadataAssociation?) {
        journal.resource(BackupResourceOwner.METADATA_ARTWORK, id)?.let { plan ->
            val value = privateRecord(plan)
            if (!plan.confirmed || value.long("bytes") != bytes || value.text("sha256") != sha256) {
                throw BackupException(BackupIssue.RECOVERY_REQUIRED)
            }
            return
        }
        val candidates = LinkedHashSet<String>()
        current?.takeIf { it.sha256 == sha256 && it.byteCount == bytes }?.assetId?.let(candidates::add)
        before.firstResourceUse(id, BackupRecordKind.METADATA_ARTWORK)?.record?.let { row ->
            currentMetadata(row)?.takeIf { it.sha256 == sha256 && it.byteCount == bytes }?.assetId?.let(candidates::add)
        }
        var reused: String? = null
        for (candidate in candidates) {
            if (existingFileIsValid { artworkOwner().verifyForBackup(candidate, bytes, sha256) }) { reused = candidate; break }
        }
        if (reused == null && !allowImport) return
        val asset = if (reused == null) artworkOwner().createForBackup(session.uri(context, id), bytes, sha256) else null
        val assetId = asset?.assetId ?: checkNotNull(reused)
        try {
            journal.planResource(run.id, BackupResourcePlan(BackupResourceOwner.METADATA_ARTWORK, id, id, mapOf(
                "id" to BackupCell.Text(assetId), "sha256" to BackupCell.Text(sha256), "bytes" to BackupCell.Integer(bytes),
                "added" to BackupCell.Flag(asset != null),
            )))
            artworkOwner().retainForBackup(assetId)
            journal.confirmResource(run.id, BackupResourceOwner.METADATA_ARTWORK, id)
        } finally { asset?.release() }
    }

    fun restoredLyrics(journal: BackupRestoreJournal, resourceId: String, encoding: String): LyricsAssociation? {
        val plan = journal.resource(BackupResourceOwner.LYRICS, resourceId)?.takeIf { it.confirmed } ?: return null
        val value = privateRecord(plan)
        return LyricsAssociation(value.text("documentId"), value.text("sourceId"), value.text("uri"), 0,
            LyricsEncodingChoice.entries.firstOrNull { it.name == encoding }
                ?: throw BackupException(BackupIssue.INVALID_REFERENCE), value.text("sha256"))
    }

    fun restoredMetadataAsset(journal: BackupRestoreJournal, resourceId: String, purpose: MetadataPurpose, bytes: Long): String? {
        val owner = if (purpose == MetadataPurpose.LYRICS) BackupResourceOwner.LYRICS else BackupResourceOwner.METADATA_ARTWORK
        val plan = journal.resource(owner, resourceId)?.takeIf { it.confirmed } ?: return null
        val value = privateRecord(plan)
        val kind = if (purpose == MetadataPurpose.LYRICS) BackupResourceKind.LYRICS else BackupResourceKind.IMAGE
        if (value.long("bytes") != bytes || BackupLogicalSchema.resourceId(kind, value.text("sha256")) != resourceId) {
            throw BackupException(BackupIssue.INVALID_REFERENCE)
        }
        return if (purpose == MetadataPurpose.ARTWORK) value.text("id") else
            OwnedLyricsProvider.token(context, AndroidLocalSources.contentUri(value.text("uri")))
                ?: throw BackupException(BackupIssue.INVALID_REFERENCE)
    }

    suspend fun recoverRetention(journal: BackupRestoreJournal, fonts: LyricsFontBackupAccess) {
        barrier.requireExclusiveOwner()
        val run = journal.readRun() ?: throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        val steps = journal.configs()
        val forward = run.phase == BackupRestorePhase.FINISHING_FORWARD
        val rollback = run.phase == BackupRestorePhase.FINISHING_ROLLBACK
        val fontStep = steps.single { it.key == "lyrics-fonts" }
        val baselineFonts = BackupPreferencesCodec.fonts(fontStep.before.record)
        val targetFonts = BackupPreferencesCodec.fonts(fontStep.forward ?: fontStep.before.record)
        val selected = when { forward -> targetFonts; rollback -> baselineFonts; else -> baselineFonts }
        val retainedFonts = listOfNotNull(selected.original.asset, selected.translation.asset).toMutableList()
        if (!forward && !rollback) eachPlan(journal, BackupResourceOwner.FONT) { plan ->
            val value = privateRecord(plan)
            retainedFonts += LyricsFontAsset(value.text("id"), value.text("sha256"), value.long("bytes"), value.text("displayName"), value.long("license").toInt())
        }
        fonts.retainForBackup(retainedFonts.distinctBy { it.id })
        val backgroundStep = steps.single { it.key == "background" }
        val chosenBackground = if (forward) backgroundStep.forward ?: throw BackupException(BackupIssue.RECOVERY_REQUIRED) else backgroundStep.before.record
        val images = listOfNotNull(BackupPreferencesCodec.background(chosenBackground).customImage).toMutableList()
        if (!forward && !rollback) eachPlan(journal, BackupResourceOwner.BACKGROUND) { plan -> images += imageFromPlan(plan) }
        background.retainForBackup(images.distinctBy { it.id })
        copies.beginBackupRetention()
        if (!rollback) eachPlan(journal, BackupResourceOwner.LYRICS) { plan -> copies.retainForBackup(privateRecord(plan).text("uri")) }
        metadataBoundary {
            metadataArtwork?.beginBackupRetention()
            if (!rollback) eachPlan(journal, BackupResourceOwner.METADATA_ARTWORK) { plan ->
                artworkOwner().retainForBackup(privateRecord(plan).text("id"))
            }
            eachMetadata(setOf(BackupScope.LIBRARY, BackupScope.LYRICS)) { association ->
                association.assetId?.let { assetId ->
                    when (association.purpose) {
                        MetadataPurpose.LYRICS -> copies.retainForBackup(ownedLyricsUri(assetId))
                        MetadataPurpose.ARTWORK -> artworkOwner().retainForBackup(assetId)
                    }
                }
            }
        }
    }

    suspend fun retirePreparedPreviews(journal: BackupRestoreJournal) {
        barrier.requireExclusiveOwner()
        eachPlan(journal, BackupResourceOwner.BACKGROUND) { plan ->
            privateRecord(plan).optionalText("previewToken")?.let { token ->
                background.retirePreview(token)
                try { background.discardPreview(token) }
                catch (failure: PlaybackBackgroundException) { if (failure.issue != PlaybackBackgroundIssue.PREVIEW_NOT_FOUND) throw failure }
            }
        }
        eachPlan(journal, BackupResourceOwner.THEME) { plan ->
            if (plan.metadata["added"] == BackupCell.Flag(true)) {
                val token = privateRecord(plan).text("previewToken")
                themes.retirePreview(token)
                try { themes.discardPreview(token) }
                catch (failure: ThemeException) { if (failure.issue != ThemeIssue.PREVIEW_NOT_FOUND) throw failure }
            }
        }
    }

    suspend fun verifyThemeEvolution(journal: BackupRestoreJournal): ThemeCatalog {
        barrier.requireExclusiveOwner()
        val run = journal.readRun() ?: throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        val before = ThemeStorageRecords.decode(journal.themeBaseline())
        val actual = themes.readCatalogForBackup()
        val originals = before.installed.associateBy { it.ref.installationId }
        if (originals.any { (id, value) -> actual.installed.singleOrNull { it.ref.installationId == id } != value }) {
            throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        }
        val additions = ArrayList<BackupResourcePlan>()
        eachPlan(journal, BackupResourceOwner.THEME) { if (it.metadata["added"] == BackupCell.Flag(true)) additions += it }
        if (additions.size > 8) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        additions.sortBy { privateRecord(it).long("expectedRevision") }
        for ((index, plan) in additions.withIndex()) {
            if (before.revision > Long.MAX_VALUE - index || privateRecord(plan).long("expectedRevision") != before.revision + index) {
                throw BackupException(BackupIssue.RECOVERY_REQUIRED)
            }
        }
        val planned = additions.associateBy { privateRecord(it).text("id") }
        for (installation in actual.installed) {
            if (installation.ref.installationId in originals) continue
            val plan = planned[installation.ref.installationId] ?: throw BackupException(BackupIssue.RECOVERY_REQUIRED)
            val value = privateRecord(plan)
            if (installation.ref != themeRef(plan) || installation.packageSha256 != value.text("packageSha256") ||
                installation.displayName != value.text("displayName") || installation.resourceBytes != value.long("resourceBytes") ||
                installation.fontBytes != value.long("fontBytes")) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        }
        val present = actual.installed.count { it.ref.installationId !in originals }.toLong()
        val step = journal.configs().single { it.key == "theme" }
        val currentPreference = BackupPreferencesCodec.theme(actual.selected, actual.previous)
        if (run.phase == BackupRestorePhase.FINISHING_ROLLBACK || run.phase == BackupRestorePhase.FINISHING_FORWARD) {
            val baseRevision = (run.nativeBindings["themeCleanupRevision"] as? BackupCell.Integer)?.value ?: throw BackupException(BackupIssue.RECOVERY_REQUIRED)
            val added = (run.nativeBindings["themeCleanupAddedCount"] as? BackupCell.Integer)?.value ?: throw BackupException(BackupIssue.RECOVERY_REQUIRED)
            if (present > added || added > additions.size.toLong()) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
            val removed = if (run.phase == BackupRestorePhase.FINISHING_ROLLBACK) added - present else 0L
            if (baseRevision > Long.MAX_VALUE - removed || actual.revision != baseRevision + removed ||
                run.phase == BackupRestorePhase.FINISHING_FORWARD && present != added) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
            val expected = if (run.phase == BackupRestorePhase.FINISHING_ROLLBACK) step.before.record else step.forward
            if (expected == null || !currentPreference.sameContent(expected)) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        } else if (step.attemptBefore != null) {
            val prior = step.attemptBefore
            val target = step.attemptTarget ?: throw BackupException(BackupIssue.RECOVERY_REQUIRED)
            val old = actual.revision == prior.revision && currentPreference.sameContent(prior.record)
            val next = prior.revision < Long.MAX_VALUE && actual.revision == prior.revision + 1L && currentPreference.sameContent(target)
            if (!old && !next) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        } else {
            val count = present.toInt()
            if (additions.take(count).any { themeRef(it).installationId !in actual.installed.map { value -> value.ref.installationId } } ||
                before.revision > Long.MAX_VALUE - present || actual.revision != before.revision + present) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
            val selected = if (count == 0) before.selected else ThemeChoice.Installed(themeRef(additions[count - 1]))
            val previous = if (count == 0) before.previous else if (count == 1) before.selected else ThemeChoice.Installed(themeRef(additions[count - 2]))
            if (actual.selected != selected || actual.previous != previous) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        }
        return actual
    }

    suspend fun cleanupBindings(journal: BackupRestoreJournal): Map<String, BackupCell> {
        val actual = verifyThemeEvolution(journal)
        val baseline = ThemeStorageRecords.decode(journal.themeBaseline()).installed.map { it.ref.installationId }.toSet()
        return mapOf("themeCleanupRevision" to BackupCell.Integer(actual.revision),
            "themeCleanupAddedCount" to BackupCell.Integer(actual.installed.count { it.ref.installationId !in baseline }.toLong()))
    }

    suspend fun cleanup(journal: BackupRestoreJournal, direction: BackupConfigDirection, fonts: LyricsFontBackupAccess) {
        barrier.requireExclusiveOwner()
        verifyThemeEvolution(journal)
        val metadata = database.localMetadataDao()
        if (MetadataPurpose.entries.any { metadata.recoveryBlocked(it.name) }) {
            throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        }
        if (direction == BackupConfigDirection.ROLLBACK) {
            val baseline = ThemeStorageRecords.decode(journal.themeBaseline())
            val retained = baseline.installed.map { it.ref }.toSet()
            eachPlan(journal, BackupResourceOwner.THEME) { plan ->
                if (plan.metadata["added"] != BackupCell.Flag(true)) return@eachPlan
                val current = verifyThemeEvolution(journal)
                val ref = themeRef(plan)
                if (current.installed.none { it.ref == ref }) return@eachPlan
                val result = themes.remove(ref, current.revision, UUID.randomUUID().toString(), retained)
                if (!result.filesDeleted || result.cleanupRequired) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
            }
        }
        fonts.releaseBackupRetention(fonts.read().assetIds())
        background.releaseBackupRetention()
        copies.releaseBackupRetention()
        copies.recover { uri ->
            database.lyricsDao().isDocumentUriReferenced(uri) ||
                OwnedLyricsProvider.token(context, AndroidLocalSources.contentUri(uri))?.let {
                    metadata.isAnyAssetReferenced(it)
                } == true
        }
        metadataBoundary {
            metadataArtwork?.releaseBackupRetention()
            metadataArtwork?.recover { assetId -> metadata.isAnyAssetReferenced(assetId) }
        }
        verifyThemeEvolution(journal)
    }

    private fun imageFromPlan(plan: BackupResourcePlan): BackgroundImageRef = privateRecord(plan).let {
        BackgroundImageRef(it.text("id"), it.text("sha256"), it.long("bytes"), it.text("mediaType"), it.long("width").toInt(),
            it.long("height").toInt(), it.long("orientation").toInt())
    }
    private fun themeRef(plan: BackupResourcePlan): ThemeRef = privateRecord(plan).let { ThemeRef(it.text("id"), it.text("themeId"), it.text("manifestSha256")) }
    private suspend fun eachPlan(journal: BackupRestoreJournal, owner: BackupResourceOwner, action: suspend (BackupResourcePlan) -> Unit) {
        var after: String? = null
        while (true) {
            val page = journal.resourcePage(owner, after)
            if (page.isEmpty()) break
            for (plan in page) { currentCoroutineContext().ensureActive(); action(plan) }
            after = page.last().slot
        }
    }

    private fun fontMetadata(asset: LyricsFontAsset): Map<String, BackupCell> = mapOf("id" to BackupCell.Text(asset.id),
        "sha256" to BackupCell.Text(asset.sha256), "bytes" to BackupCell.Integer(asset.byteCount), "displayName" to BackupCell.Text(asset.displayName),
        "license" to BackupCell.Integer(asset.licenseAcknowledgmentVersion.toLong()))
    private fun imageMetadata(image: BackgroundImageRef): Map<String, BackupCell> = mapOf("id" to BackupCell.Text(image.id), "sha256" to BackupCell.Text(image.sha256),
        "bytes" to BackupCell.Integer(image.byteCount), "mediaType" to BackupCell.Text(image.mediaType), "width" to BackupCell.Integer(image.width.toLong()),
        "height" to BackupCell.Integer(image.height.toLong()), "orientation" to BackupCell.Integer(image.orientation.toLong()))
    private fun themeMetadata(ref: ThemeRef, packageSha256: String, name: String, resourceBytes: Long, fontBytes: Long): Map<String, BackupCell> = mapOf(
        "id" to BackupCell.Text(ref.installationId), "themeId" to BackupCell.Text(ref.themeId), "manifestSha256" to BackupCell.Text(ref.manifestSha256),
        "packageSha256" to BackupCell.Text(packageSha256), "displayName" to BackupCell.Text(name), "resourceBytes" to BackupCell.Integer(resourceBytes), "fontBytes" to BackupCell.Integer(fontBytes))
    private fun privateRecord(plan: BackupResourcePlan): BackupRecord = BackupRecord(BackupRecordKind.PREFERENCE, "private-resource", plan.metadata)

    private fun artworkOwner(): LocalMetadataArtworkFiles = metadataArtwork ?: throw BackupException(BackupIssue.UNSUPPORTED_RESOURCE)
    private fun ownedLyricsUri(assetId: String): String = OwnedLyricsProvider.uri(context, assetId).toString()

    private suspend fun currentMetadata(row: BackupRecord): MetadataAssociation? = metadataBoundary {
        val purpose = BackupMetadataAssociations.purpose(row.kind)
        val key = TrackKey(TrackKey.LOCAL_PROVIDER, row.key)
        database.localMetadataDao().row(row.key, purpose.name)?.decode(key, purpose)
    }

    /** Only one bounded SQL page is retained, including native UUIDs kept out of portable rows. */
    private suspend fun eachMetadata(scopes: Set<BackupScope>, checkCancelled: () -> Unit = {},
        action: suspend (MetadataAssociation) -> Unit) {
        for (purpose in MetadataPurpose.entries) {
            if (BackupMetadataAssociations.kind(purpose).scope !in scopes) continue
            var after: String? = null
            while (true) {
                currentCoroutineContext().ensureActive(); checkCancelled()
                val page = database.useReaderConnection { reader -> reader.usePrepared(
                    "SELECT substr(trackId,1,129),typeof(trackId)," +
                        "CASE WHEN typeof(schemaVersion)='integer' AND typeof(revision)='integer' AND " +
                        "typeof(assetId) IN ('text','null') AND typeof(sha256) IN ('text','null') AND " +
                        "typeof(byteCount) IN ('integer','null') AND typeof(provenance) IN ('text','null') THEN 1 ELSE 0 END," +
                        "schemaVersion,revision,substr(assetId,1,37),substr(sha256,1,65),byteCount," +
                        "substr(provenance,1,${MetadataProvenance.PROVENANCE_BYTES + 1}) FROM local_metadata_associations " +
                        "WHERE purpose=? AND (? IS NULL OR trackId>?) ORDER BY trackId COLLATE BINARY LIMIT 100") { statement ->
                    statement.bindText(1, purpose.name); statement.bindNullableText(2, after); statement.bindNullableText(3, after)
                    buildList {
                        while (statement.step()) {
                            checkCancelled()
                            if (statement.getText(1) != "text" || statement.getLong(2) != 1L) throw BackupException(BackupIssue.INVALID_RESOURCE)
                            val id = statement.getText(0)
                            val row = LocalMetadataAssociationRow(true, statement.getLong(3), statement.getLong(4),
                                if (statement.isNull(5)) null else statement.getText(5),
                                if (statement.isNull(6)) null else statement.getText(6),
                                if (statement.isNull(7)) null else statement.getLong(7),
                                if (statement.isNull(8)) null else statement.getText(8))
                            add(try { row.decode(TrackKey(TrackKey.LOCAL_PROVIDER, id), purpose) }
                                catch (failure: MetadataStoreException) { throw metadataFailure(failure) }
                                catch (failure: IllegalArgumentException) { throw BackupException(BackupIssue.INVALID_RESOURCE, failure) })
                        }
                    }
                } }
                if (page.isEmpty()) break
                for (association in page) { currentCoroutineContext().ensureActive(); checkCancelled(); action(association) }
                after = page.last().trackKey.trackId
            }
        }
    }

    private suspend fun existingFileIsValid(action: suspend () -> Unit): Boolean = try { action(); true }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: BackupException) {
        if (failure.issue in setOf(BackupIssue.INVALID_RESOURCE, BackupIssue.INTEGRITY_MISMATCH, BackupIssue.STALE_PREVIEW)) false else throw failure
    } catch (failure: MetadataStoreException) {
        if (failure.issue in setOf(MetadataStoreIssue.SOURCE_CHANGED, MetadataStoreIssue.INVALID_RECORD,
                MetadataStoreIssue.INVALID_INPUT, MetadataStoreIssue.INPUT_LIMIT)) false else throw failure
    } catch (failure: SourceReadException) {
        when (val cause = failure.cause) {
            is BackupException -> if (cause.issue in setOf(BackupIssue.INVALID_RESOURCE, BackupIssue.INTEGRITY_MISMATCH)) false else throw cause
            is MetadataStoreException -> if (cause.issue in setOf(MetadataStoreIssue.SOURCE_CHANGED, MetadataStoreIssue.INVALID_RECORD,
                    MetadataStoreIssue.INVALID_INPUT, MetadataStoreIssue.INPUT_LIMIT)) false else throw metadataFailure(cause)
            else -> if (failure.issue in setOf(SourceReadIssue.SOURCE_UNAVAILABLE, SourceReadIssue.FILE_MISSING,
                    SourceReadIssue.SOURCE_CHANGED)) false else throw failure
        }
    }

    private suspend fun <T> metadataBoundary(action: suspend () -> T): T = try { action() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: MetadataStoreException) { throw metadataFailure(failure) }
    catch (failure: SourceReadException) {
        throw when (val cause = failure.cause) {
            is MetadataStoreException -> metadataFailure(cause)
            is BackupException -> cause
            else -> failure
        }
    }

    private fun metadataFailure(failure: MetadataStoreException): BackupException = BackupException(when (failure.issue) {
        MetadataStoreIssue.INVALID_INPUT, MetadataStoreIssue.INVALID_RECORD -> BackupIssue.INVALID_RESOURCE
        MetadataStoreIssue.TRACK_MISSING, MetadataStoreIssue.SOURCE_CHANGED -> BackupIssue.STALE_PREVIEW
        MetadataStoreIssue.BUSY -> BackupIssue.BUSY
        MetadataStoreIssue.CONFLICT -> BackupIssue.CONFLICT
        MetadataStoreIssue.INPUT_LIMIT -> BackupIssue.TOO_LARGE
        MetadataStoreIssue.LOW_SPACE -> BackupIssue.STORAGE_FULL
        MetadataStoreIssue.LOW_MEMORY, MetadataStoreIssue.IO -> BackupIssue.IO
    }, failure)

    private suspend fun captureResource(stage: SqliteBackupStage, kind: BackupResourceKind, license: Boolean,
        action: suspend (BackupResourceWriter) -> Unit): BackupResource {
        val writer = BackupResourceWriter.create(stage.directory, kind, stage::checkActive)
        var failure: Throwable? = null
        try { action(writer); return stage.adoptCapturedResource(writer.seal(kind, license)) }
        catch (error: Throwable) { failure = error; throw error }
        finally { try { writer.close() }
        catch (closing: Throwable) { val outgoing = failure; if (outgoing == null) throw closing else if (outgoing !== closing) outgoing.addSuppressed(closing) } }
    }

    private suspend fun <T> physical(action: (CancellationSignal) -> T): T {
        val admitted = AtomicBoolean(false)
        try { return io.read(admitted = { admitted.set(true) }, action = action) }
        catch (failure: SourceReadException) { throw (failure.cause as? BackupException) ?: failure }
        finally { if (admitted.get()) withContext(NonCancellable) { io.awaitIdle() } }
    }

    private suspend fun each(stage: SqliteBackupStage, kind: BackupRecordKind, action: suspend (BackupRecord) -> Unit) {
        var after: String? = null
        while (true) {
            val page = stage.pageNow(kind, after)
            if (page.isEmpty()) break
            for (row in page) { stage.checkActive(); action(row) }
            after = page.last().key
        }
    }
}
