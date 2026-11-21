package io.github.onlinemo.meloxwatch.data.themes

import android.os.CancellationSignal
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontLimits
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** Counts physical font payloads and retained inputs; it never deletes a draft to make room. */
internal object ThemeFontStorageMeasurement {
    fun measure(directory: File, signal: CancellationSignal): Long {
        val store = ThemeDirectoryStore(directory)
        return store.exclusively { measureLocked(store, signal) }
    }

    /** Caller already owns the theme lock. An in-progress candidate is reserved separately. */
    fun measureLocked(store: ThemeDirectoryStore, signal: CancellationSignal, excludedStage: String? = null): Long {
        signal.throwIfCanceled()
        store.pendingIssue()?.let(::themeFail)
        requireNames(store.root, setOf("owner.lock", "catalog.bin", "installed", "staging", "received", "transfer-apply.bin"), signal)
        val catalog = store.loadCatalog().catalog
        var total = 0L
        for (installation in catalog.installed) {
            signal.throwIfCanceled()
            val manifest = store.manifest(installation.ref, catalog)
            val payload = store.installationDirectory(installation.ref.installationId)
            verifyThemePayloadTree(payload, manifest, signal::throwIfCanceled)
            total = add(total, payloadFonts(payload, manifest, signal))
        }
        val ordinaryStages = children(File(store.root, "staging"), signal)
        val received = children(File(store.root, "received"), signal)
        if (ordinaryStages.size > 1 || received.size > 1) themeFail(ThemeIssue.CORRUPT_STORAGE)
        val stages = ordinaryStages + received
        for (stage in stages) {
            if (!isThemeOperationId(stage.name)) themeFail(ThemeIssue.CORRUPT_STORAGE)
            requireDirectory(stage)
            val isReceived = stage in received
            requireNames(stage, if (isReceived) setOf("payload", "input.zip", "candidate.bin") else setOf("payload", "input.zip"), signal)
            requireDirectory(store.stagePayload(stage.name))
            if (stage.name == excludedStage) continue
            val archive = store.stageArchive(stage.name)
            requireThemeRegularFile(archive)
            val archiveBytes = Files.size(archive.toPath())
            if (archiveBytes !in 1..ThemeLimits.PACKAGE_BYTES) themeFail(ThemeIssue.INTEGRITY_FAILED)
            val manifestBytes = store.stageManifestBytes(stage.name, signal::throwIfCanceled)
            val manifest = ThemeManifestReader.read(manifestBytes, signal::throwIfCanceled)
            if (isReceived) {
                val record = store.receivedRecord(stage.name)
                if (record.packageBytes != archiveBytes || record.manifestSha256 != ThemeStorageRecords.digest(manifestBytes) ||
                    record.themeId != manifest.id || record.displayName != manifest.displayName ||
                    record.packageSha256 != digestBoundedThemeFile(archive, archiveBytes, ThemeLimits.PACKAGE_BYTES, signal::throwIfCanceled)) {
                    themeFail(ThemeIssue.INTEGRITY_FAILED)
                }
            }
            val zip = ThemeZipArchive(archive)
            val index = zip.inspect(signal::throwIfCanceled)
            if (!zip.readManifest(index, signal::throwIfCanceled).contentEquals(manifestBytes)) {
                themeFail(ThemeIssue.INTEGRITY_FAILED)
            }
            zip.validateDeclarations(index, manifest)
            val payload = store.stagePayload(stage.name)
            verifyThemePayloadTree(payload, manifest, signal::throwIfCanceled)
            // Font-bearing archives retain another copy. Image-only archives use theme storage.
            if (manifest.fonts.isNotEmpty()) {
                total = add(total, archiveBytes)
                total = add(total, payloadFonts(payload, manifest, signal))
            }
        }
        signal.throwIfCanceled()
        return total
    }

    private fun payloadFonts(payload: File, manifest: ThemeManifest, signal: CancellationSignal): Long {
        var bytes = 0L
        for (resource in manifest.resources) {
            signal.throwIfCanceled()
            if (resource.kind != ThemeResourceKind.FONT) continue
            val file = themeContainedFile(payload, resource.path)
            requireThemeRegularFile(file)
            val actual = Files.size(file.toPath())
            if (actual != resource.byteCount) themeFail(ThemeIssue.INTEGRITY_FAILED)
            bytes = add(bytes, actual)
        }
        return bytes
    }

    private fun add(current: Long, next: Long): Long {
        if (next < 0L || next > LyricsFontLimits.OWNED_BYTES - current) themeFail(ThemeIssue.FONT_QUOTA_EXCEEDED)
        return current + next
    }

    private fun requireNames(directory: File, allowed: Set<String>, signal: CancellationSignal) {
        if (children(directory, signal).any { it.name !in allowed }) themeFail(ThemeIssue.CORRUPT_STORAGE)
    }

    private fun children(directory: File, signal: CancellationSignal): List<File> {
        requireDirectory(directory)
        val result = ArrayList<File>()
        Files.newDirectoryStream(directory.toPath()).use { entries ->
            for (path in entries) {
                signal.throwIfCanceled()
                if (result.size == ThemeLimits.OWNED_NODES) themeFail(ThemeIssue.STORAGE_QUOTA_EXCEEDED)
                if (Files.isSymbolicLink(path) || path.toFile().canonicalFile.parentFile != directory.canonicalFile) {
                    themeFail(ThemeIssue.LINK_OR_SPECIAL_FILE)
                }
                if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) requireThemeRegularFile(path.toFile())
                result += path.toFile()
            }
        }
        return result
    }

    private fun requireDirectory(directory: File) {
        if (Files.isSymbolicLink(directory.toPath()) || !Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            themeFail(ThemeIssue.LINK_OR_SPECIAL_FILE)
        }
    }
}
