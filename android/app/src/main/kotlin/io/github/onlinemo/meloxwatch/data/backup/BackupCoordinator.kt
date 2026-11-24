package io.github.onlinemo.meloxwatch.data.backup

import android.content.Context
import android.os.CancellationSignal
import android.os.SystemClock
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationsRuntime
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationException
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationIssue
import io.github.onlinemo.meloxwatch.data.fonts.BackupFontOwner
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontBackupAccess
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontException
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontIssue
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.library.LibraryException
import io.github.onlinemo.meloxwatch.data.library.LibraryIssue
import io.github.onlinemo.meloxwatch.data.library.RoomLocalLibraryRepository
import io.github.onlinemo.meloxwatch.data.listening.RoomListeningRepository
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsParseException
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsParseIssue
import io.github.onlinemo.meloxwatch.data.playlists.RoomPlaylistRepository
import io.github.onlinemo.meloxwatch.data.queue.RoomQueueStore
import io.github.onlinemo.meloxwatch.data.themes.LocalThemeRepository
import io.github.onlinemo.meloxwatch.data.themes.ThemeStorageRecords
import io.github.onlinemo.meloxwatch.data.themes.ThemeException
import io.github.onlinemo.meloxwatch.data.themes.ThemeIssue
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundException
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundIssue
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * One application owner, one caller-owned operation and one immutable preview. Startup inspects
 * the real journal before any database writer can run; restoration never starts a player or network.
 */
class BackupCoordinator internal constructor(
    context: Context,
    private val barrier: BackupMutationBarrier,
    private val database: MeloXDatabase,
    private val library: RoomLocalLibraryRepository,
    private val queues: RoomQueueStore,
    private val playlists: RoomPlaylistRepository,
    private val listening: RoomListeningRepository,
    private val fileOperations: FileOperationsRuntime,
    private val configurations: BackupNativeConfigBridge,
    private val resources: BackupNativeResourceBridge,
    private val fontOwner: BackupFontOwner,
    private val themes: LocalThemeRepository,
    private val io: LocalProviderIo,
    private val documents: BackupDocuments,
    private val permissions: BackupPermissionVerifier,
    private val relinker: RoomBackupRelinker,
    private val epochMs: () -> Long = System::currentTimeMillis,
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
    // The production default is the sole platform document owner; core fixtures keep real disk/Room.
    private val copyInput: suspend (BackupDocumentInput, BackupPrivateDirectory) -> File = documents::copyInput,
) {
    private val context = context.applicationContext
    private val actions = Mutex()
    private val mutableStartup = MutableStateFlow<BackupStartupState>(BackupStartupState.Loading)
    val startup: StateFlow<BackupStartupState> = mutableStartup.asStateFlow()
    private val mutableProgress = MutableStateFlow<BackupProgress?>(null)
    val progress: StateFlow<BackupProgress?> = mutableProgress.asStateFlow()
    private val capture = RoomBackupCapture(database)
    private val materializer = RoomBackupMaterializer(context.packageName, barrier, database, library, permissions, resources::restoredLyrics)
    private val reader = BackupArchiveReader()
    private val writer = BackupArchiveWriter()
    private var directory: BackupPrivateDirectory? = null
    private var journal: BackupRestoreJournal? = null
    private var heldPreview: HeldPreview? = null
    private val closeRetentions = BackupCloseRetentions(io) {
        barrier.requireRecovery()
        mutableStartup.value = BackupStartupState.Unavailable(BackupIssue.RECOVERY_REQUIRED)
    }

    private class HeldPreview(val model: BackupRestorePreview, val generation: BackupPrivateDirectory, val file: File,
        val mainDigest: String, val logicalDigest: String, val bindingDigest: String,
        val configurations: Map<String, BackupConfigVersion>, val expiresElapsedMs: Long)

    suspend fun initializeStorage(): BackupStartupState {
        if (mutableStartup.value is BackupStartupState.Ready && barrier.state.value == BackupAccessState.READY && !closeRetentions.hasPending) return mutableStartup.value
        if (!actions.tryLock()) return mutableStartup.value
        try {
            mutableStartup.value = BackupStartupState.Loading
            return withContext(Dispatchers.IO) {
                try {
                    retryRetainedCloses()
                    val storage = physical { signal ->
                        signal.throwIfCanceled()
                        BackupPrivateDirectory.createBelow(context.noBackupFilesDir, "logical-backups-v1")
                    }
                    directory = storage
                    val saved = journal ?: physicalOwner { signal, register -> signal.throwIfCanceled(); BackupRestoreJournal.open(storage, onOpened = register) }.also { journal = it }
                    val pending = physical { signal -> signal.throwIfCanceled(); saved.readRun() }
                    if (pending == null) barrier.initializeStorage {
                        initializeDatabaseOwners()
                        retireUnusedGenerations(saved)
                    } else withClosedStorage(initializing = true) {
                        fontOwner.withSettings { fonts -> recoverRun(saved, fonts); finalizeClosedRun(saved) }
                        library.initialize()
                        playlists.initialize()
                        listening.state()
                        retireUnusedGenerations(saved)
                    }
                    BackupStartupState.Ready(recoveredPendingRun = pending != null).also { mutableStartup.value = it }
                } catch (cancelled: CancellationException) {
                    barrier.requireRecovery()
                    mutableStartup.value = BackupStartupState.Unavailable(BackupIssue.RECOVERY_REQUIRED)
                    throw cancelled
                } catch (failure: Exception) {
                    barrier.requireRecovery()
                    BackupStartupState.Unavailable(issue(failure)).also { mutableStartup.value = it }
                }
            }
        } finally { actions.unlock() }
    }

    suspend fun awaitReady(): Boolean = startup.first { it !is BackupStartupState.Loading } is BackupStartupState.Ready

    fun requireReady() {
        if (mutableStartup.value !is BackupStartupState.Ready || barrier.state.value == BackupAccessState.RECOVERY_REQUIRED || closeRetentions.hasPending) {
            throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        }
        if (barrier.state.value != BackupAccessState.READY) throw BackupException(BackupIssue.BUSY)
    }

    suspend fun export(selection: BackupSelection, destination: BackupDocumentDestination): BackupExportReceipt = action {
        val generation = newGeneration()
        val operationContext = currentCoroutineContext()
        var stage: SqliteBackupStage? = null
        var failure: Throwable? = null
        try {
            val created = physicalOwner { signal, register -> signal.throwIfCanceled(); SqliteBackupStage.create(generation, onOpened = register) { operationContext.ensureActive() } }
                .also { stage = it }
            update(BackupStage.CAPTURING)
            barrier.exclusively {
                val header = BackupHeader(scopes = selection.scopes, createdEpochMs = epochMs(), sourceDatabaseVersion = capture.sourceDatabaseVersion())
                created.begin(header)
                if (BackupScope.PREFERENCES in selection.scopes) fontOwner.withSettings { fonts -> captureSnapshot(created, fonts) }
                else captureSnapshot(created, null)
            }
            update(BackupStage.WRITING)
            val archive = generation.child("export.zip")
            val receipt = physical { signal -> writer.write(created, archive, signal::throwIfCanceled) }
            update(BackupStage.VERIFYING, bytes = receipt.bytes)
            physical { signal ->
                if (reader.verify(archive, signal::throwIfCanceled).exportId != receipt.exportId ||
                    backupFileSha256(archive, signal::throwIfCanceled) != receipt.sha256) throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
            }
            documents.writeVerified(destination, archive, receipt).also { update(BackupStage.COMPLETE, bytes = it.bytes) }
        } catch (error: Throwable) { failure = error; throw error }
        finally { closeAndRetire(stage, generation, failure) }
    }

    suspend fun previewRestore(input: BackupDocumentInput, mode: BackupMergeMode,
        scopes: Set<BackupScope>? = null): BackupRestorePreview = action {
        discardHeldPreview()
        val generation = newGeneration()
        val operationContext = currentCoroutineContext()
        var stage: SqliteBackupStage? = null
        var retained = false
        var failure: Throwable? = null
        try {
            update(BackupStage.VERIFYING)
            val file = copyInput(input, generation)
            val created = physicalOwner { signal, register -> signal.throwIfCanceled(); SqliteBackupStage.create(generation, onOpened = register) { operationContext.ensureActive() } }
            stage = created
            val header = physical { signal -> reader.stage(file, created, signal::throwIfCanceled) }
            val sha256 = physical { signal -> backupFileSha256(file, signal::throwIfCanceled) }
            val selected = (scopes ?: header.scopes).toSet()
            if (selected.isEmpty() || !header.scopes.containsAll(selected)) throw BackupException(BackupIssue.INVALID_INPUT)
            BackupSelection(selected)
            val bound = barrier.exclusively {
                fontOwner.withSettings { fonts ->
                    if (BackupScope.RESOURCES in selected) resources.validate(created, fonts)
                    permissions.prepare(created, sha256, selected, mode)
                    val versions = configurations.readAll(fonts)
                    val digest = capture.mainDigest(checkCancelled = { operationContext.ensureActive() })
                    val inventory = if (BackupScope.RESOURCES in selected) resources.inventory(created, selected, versions) else null
                    val preferencesMissing = resources.missingPreferenceReferences(created, selected, mode, versions)
                    val impacts = impacts(created, selected, mode, inventory, preferencesMissing)
                    val fontConsent = BackupScope.RESOURCES in selected && hasLicensedResource(created)
                    val model = BackupRestorePreview(UUID.randomUUID().toString(), sha256, header, mode, selected, impacts,
                        needsFontLicenseAcknowledgment = fontConsent)
                    val logical = created.logicalDigest(header.scopes)
                    val bindings = created.bindingDigest()
                    // A preview's caller job ends here. Confirmation opens with its own job.
                    closeOwner(created)
                    HeldPreview(model, generation, file, digest, logical, bindings, versions, Math.addExact(elapsedMs(), PREVIEW_MS))
                }
            }
            stage = null
            heldPreview = bound
            retained = true
            update(BackupStage.PREVIEW)
            bound.model
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            if (!retained) closeAndRetire(stage, generation, failure)
        }
    }

    suspend fun confirmRestore(token: String, fontLicenseAcknowledged: Boolean = false): BackupRestoreReceipt = action {
        val preview = heldPreview?.takeIf { it.model.token == token } ?: throw BackupException(BackupIssue.STALE_PREVIEW)
        if (elapsedMs() > preview.expiresElapsedMs) throw BackupException(BackupIssue.STALE_PREVIEW)
        if (preview.model.needsFontLicenseAcknowledgment && !fontLicenseAcknowledged) throw BackupException(BackupIssue.FONT_LICENSE_REQUIRED)
        val saved = storageJournal()
        withClosedStorage {
            fontOwner.withSettings { fonts ->
                val operationContext = currentCoroutineContext()
                var incoming: SqliteBackupStage? = null
                var beforeGeneration: BackupPrivateDirectory? = null
                var before: SqliteBackupStage? = null
                var began = false
                var requestedRun: BackupRestoreRun? = null
                var outgoing: Throwable? = null
                try {
                    val stage = physicalOwner { signal, register ->
                        signal.throwIfCanceled()
                        SqliteBackupStage.openVerified(preview.generation, onOpened = register) { operationContext.ensureActive() }
                    }.also { incoming = it }
                    physical { signal ->
                        if (backupFileSha256(preview.file, signal::throwIfCanceled) != preview.model.archiveSha256 ||
                            stage.header != preview.model.header || stage.logicalDigest(stage.header.scopes) != preview.logicalDigest) {
                            throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
                        }
                    }
                    permissions.prepare(stage, preview.model.archiveSha256, preview.model.scopes, preview.model.mode)
                    if (stage.bindingDigest() != preview.bindingDigest) throw BackupException(BackupIssue.STALE_PREVIEW)
                    val current = configurations.readAll(fonts)
                    if (current.any { (key, actual) -> preview.configurations[key]?.let {
                            it.revision != actual.revision || !it.record.sameContent(actual.record)
                        } != false }) throw BackupException(BackupIssue.STALE_PREVIEW)
                    val beforeDigest = capture.mainDigest(checkCancelled = { operationContext.ensureActive() })
                    if (beforeDigest != preview.mainDigest) throw BackupException(BackupIssue.STALE_PREVIEW)
                    val originalGeneration = newGeneration().also { beforeGeneration = it }
                    val original = physicalOwner { signal, register ->
                        signal.throwIfCanceled()
                        SqliteBackupStage.create(originalGeneration, onOpened = register) { operationContext.ensureActive() }
                    }.also { before = it }
                    original.begin(BackupHeader(scopes = setOf(BackupScope.PREFERENCES, BackupScope.RESOURCES) +
                        if (BackupScope.LYRICS in preview.model.scopes) setOf(BackupScope.LYRICS) else emptySet(),
                        createdEpochMs = epochMs(), sourceDatabaseVersion = capture.sourceDatabaseVersion()))
                    captureSnapshot(original, fonts, includeLyricsResourceReferences = false)
                    val baseline = themes.readCatalogForBackup()
                    val run = BackupRestoreRun(UUID.randomUUID().toString(), preview.model.header.exportId, preview.model.archiveSha256,
                        stage.directory.root.name, originalGeneration.root.name, preview.model.scopes, preview.model.mode, beforeDigest)
                    requestedRun = run
                    // A failed fsync acknowledgement can still have durably created this run.
                    began = true
                    heldPreview = null
                    physical { signal -> signal.throwIfCanceled(); saved.begin(run, current, ThemeStorageRecords.encode(baseline)) }
                    update(BackupStage.APPLYING)
                    resources.retainBefore(original, current, fonts)
                    planRelinks(run, stage, saved)
                    BackupResourceSession.open(stage.directory).use { session ->
                        val prepared = resources.prepare(run, stage, original, current, fonts, session, saved, fontLicenseAcknowledged)
                        physical { signal -> signal.throwIfCanceled(); saved.configureTargets(run.id, prepared.targets) }
                        forwardConfigurations(run, saved, fonts)
                        val target = newGeneration()
                        val applied = materializer.apply(run, stage, saved) { bindings ->
                            val digest = capture.mainDigest({ operationContext.ensureActive() }, insideWriter = true)
                            physical { signal ->
                                signal.throwIfCanceled()
                                BackupCreatedFile.create(target.child("main-state.sha256")).use { file ->
                                    val data = ByteBuffer.wrap(digest.toByteArray(Charsets.US_ASCII))
                                    while (data.hasRemaining()) { signal.throwIfCanceled(); file.channel.write(data) }
                                    file.channel.force(true); file.requireSame()
                                }
                                syncBackupDirectory(target.root)
                                saved.prepareMain(run.id, target.root.name, digest, bindings +
                                    ("resourceMissingReferences" to BackupCell.Integer(prepared.missingReferences)))
                            }
                        }
                        withContext(NonCancellable) {
                            finishForward(saved, fonts)
                            val totalMissing = Math.addExact(prepared.missingReferences, applied.missingReferences)
                            update(BackupStage.COMPLETE)
                            BackupRestoreReceipt(run.exportId, run.scopes, totalMissing)
                        }
                    }
                } catch (failure: Throwable) {
                    outgoing = failure
                    if (began) withContext(NonCancellable) {
                        try {
                            val pending = physical { _ -> saved.readRun() }
                            if (pending != null && pending.id != requestedRun?.id) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
                            recoverRun(saved, fonts)
                        }
                        catch (recovery: Throwable) {
                            barrier.requireRecovery()
                            mutableStartup.value = BackupStartupState.Unavailable(BackupIssue.RECOVERY_REQUIRED)
                            if (recovery !== failure) failure.addSuppressed(recovery)
                        }
                    }
                    throw failure
                } finally {
                    withContext(NonCancellable) {
                        var cleanupFailure: Throwable? = null
                        suspend fun cleanup(action: suspend () -> Unit) {
                            try { action() } catch (failure: Throwable) {
                                if (cleanupFailure == null) cleanupFailure = failure
                                else if (failure !== cleanupFailure) checkNotNull(cleanupFailure).addSuppressed(failure)
                            }
                        }
                        beforeGeneration?.let { generation -> cleanup { closeAndRetire(before, generation, null) } }
                        // Before begin, this generation still backs the reusable immutable preview.
                        cleanup {
                            if (began) closeAndRetire(incoming, preview.generation, null)
                            else incoming?.let { closeOwner(it) }
                        }
                        if (began && cleanupFailure == null) cleanup { finalizeClosedRun(saved) }
                        cleanupFailure?.let { failure ->
                            if (began) {
                                barrier.requireRecovery()
                                mutableStartup.value = BackupStartupState.Unavailable(BackupIssue.RECOVERY_REQUIRED)
                            }
                            if (outgoing == null) throw failure
                            else if (failure !== outgoing) checkNotNull(outgoing).addSuppressed(failure)
                        }
                    }
                }
            }
        }
    }

    suspend fun discardRestorePreview(token: String) = action {
        val held = heldPreview
        if (held != null && held.model.token == token) discardHeldPreview()
    }

    suspend fun missingRelinks(after: String? = null, limit: Int = 100): BackupRelinkPage = action {
        relinker.missing(storageJournal(), after, limit)
    }
    suspend fun previewRelink(missingTrackId: String, target: TrackKey): BackupRelinkPreview = action {
        relinker.preview(storageJournal(), missingTrackId, target)
    }
    suspend fun confirmRelink(token: String, allowDifferentContent: Boolean = false): BackupRelinkReceipt = action {
        withClosedStorage { relinker.confirm(storageJournal(), token, allowDifferentContent) }
    }
    suspend fun discardRelinkPreview(token: String) = action { relinker.discard(token) }

    /** The application owner first stops new callers; an active restore cannot lose its journal. */
    internal suspend fun closeForOwner() {
        if (!actions.tryLock()) throw BackupException(BackupIssue.BUSY)
        try { withContext(NonCancellable + Dispatchers.IO) {
            barrier.requireRecovery()
            mutableStartup.value = BackupStartupState.Unavailable(BackupIssue.RECOVERY_REQUIRED)
            retryRetainedCloses()
            discardHeldPreview(); relinker.discardAll()
            journal?.let { closeOwner(it) }
            journal = null; directory = null
        } } finally { actions.unlock() }
    }

    private suspend fun initializeDatabaseOwners() {
        library.initialize()
        queues.initialize()
        playlists.initialize()
        listening.state()
    }

    /** Preserve the LP-10 guard -> real service close -> queue lease -> backup barrier order. */
    private suspend fun <T> withClosedStorage(initializing: Boolean = false, action: suspend () -> T): T =
        fileOperations.withExclusiveLibraryMutation(requireNoPending = true) {
            fileOperations.withClosedPlayback { reservation ->
                queues.withFileLibraryRestore(reservation) {
                    if (initializing) {
                        var result: ResultBox<T>? = null
                        barrier.initializeStorage { result = ResultBox(action()) }
                        (result ?: throw BackupException(BackupIssue.RECOVERY_REQUIRED)).value
                    } else barrier.exclusively(action = action)
                }
            }
        }
    private class ResultBox<T>(val value: T)

    private suspend fun captureSnapshot(stage: SqliteBackupStage, fonts: LyricsFontBackupAccess?,
        includeLyricsResourceReferences: Boolean = BackupScope.RESOURCES in stage.header.scopes) {
        capture.captureMain(stage, includeLyricsResourceReferences)
        val versions = if (BackupScope.PREFERENCES in stage.header.scopes) configurations.readAll(fonts ?: throw BackupException(BackupIssue.BUSY)) else emptyMap()
        val packages = resources.capture(stage, versions, fonts)
        for ((key, version) in versions) stage.record(if (key == "theme") BackupPreferencesCodec.theme(
            BackupPreferencesCodec.choice(version.record, "selected"), BackupPreferencesCodec.choice(version.record, "previous"), packages) else version.record)
        stage.sealSnapshot()
    }

    private suspend fun forwardConfigurations(run: BackupRestoreRun, saved: BackupRestoreJournal, fonts: LyricsFontBackupAccess) {
        for (key in BackupPreferencesCodec.KEYS) {
            currentCoroutineContext().ensureActive()
            val step = physical { signal -> signal.throwIfCanceled(); saved.configs().single { it.key == key } }
            if (key == "theme") resources.verifyThemeEvolution(saved)
            val current = configurations.read(key, fonts)
            if (key != "theme" && (current.revision != step.before.revision || !current.record.sameContent(step.before.record))) {
                throw BackupException(BackupIssue.RECOVERY_REQUIRED)
            }
            val target = step.forward ?: throw BackupException(BackupIssue.RECOVERY_REQUIRED)
            physical { signal -> signal.throwIfCanceled(); saved.planConfig(run.id, BackupConfigDirection.FORWARD, current, target) }
            val actual = configurations.compareAndSet(current, target, fonts)
            physical { signal -> signal.throwIfCanceled(); saved.confirmConfig(run.id, actual) }
        }
    }

    private suspend fun recoverRun(saved: BackupRestoreJournal, fonts: LyricsFontBackupAccess) {
        barrier.requireExclusiveOwner()
        update(BackupStage.RECOVERING)
        val run = physical { signal -> signal.throwIfCanceled(); saved.readRun() } ?: return
        resources.recoverRetention(saved, fonts)
        resources.verifyThemeEvolution(saved)
        val actual = committedDigest()
        val committed = run.targetMainDigest != null && actual == run.targetMainDigest &&
            (actual != run.beforeMainDigest || run.phase == BackupRestorePhase.FINISHING_FORWARD)
        if (run.phase == BackupRestorePhase.FINISHING_FORWARD || committed) {
            if (actual != run.targetMainDigest) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
            finishForward(saved, fonts)
            return
        }
        if (actual != run.beforeMainDigest || run.phase == BackupRestorePhase.FINISHING_FORWARD) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        if (run.phase != BackupRestorePhase.FINISHING_ROLLBACK) {
            physical { signal -> signal.throwIfCanceled(); saved.beginRollback(run.id) }
            for (key in BackupPreferencesCodec.KEYS) {
                val step = physical { signal -> signal.throwIfCanceled(); saved.configs().single { it.key == key } }
                if (key == "theme") resources.verifyThemeEvolution(saved)
                val current = configurations.read(key, fonts)
                if (key != "theme" && !knownCurrent(step, current)) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
                physical { signal -> signal.throwIfCanceled(); saved.planConfig(run.id, BackupConfigDirection.ROLLBACK, current, step.before.record) }
                val actualConfig = configurations.compareAndSet(current, step.before.record, fonts)
                physical { signal -> signal.throwIfCanceled(); saved.confirmConfig(run.id, actualConfig) }
            }
            val cleanup = resources.cleanupBindings(saved)
            physical { signal -> signal.throwIfCanceled(); saved.finishDirection(run.id, BackupConfigDirection.ROLLBACK, cleanup) }
        } else verifyConfirmedConfigurations(saved, fonts, BackupConfigDirection.ROLLBACK)
        resources.retirePreparedPreviews(saved)
        resources.cleanup(saved, BackupConfigDirection.ROLLBACK, fonts)
        if (committedDigest() != run.beforeMainDigest) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
    }

    private suspend fun finishForward(saved: BackupRestoreJournal, fonts: LyricsFontBackupAccess) {
        val run = physical { signal -> signal.throwIfCanceled(); saved.readRun() } ?: throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        if (run.targetMainDigest == null || committedDigest() != run.targetMainDigest) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        verifyConfirmedConfigurations(saved, fonts, BackupConfigDirection.FORWARD)
        if (run.phase != BackupRestorePhase.FINISHING_FORWARD) {
            val cleanup = resources.cleanupBindings(saved)
            physical { signal -> signal.throwIfCanceled(); saved.finishDirection(run.id, BackupConfigDirection.FORWARD, cleanup) }
        }
        resources.retirePreparedPreviews(saved)
        resources.cleanup(saved, BackupConfigDirection.FORWARD, fonts)
        projectRelinks(saved)
        physical { signal -> signal.throwIfCanceled(); saved.finishHistoryMerge(run.id) }
        if (BackupScope.LIBRARY in run.scopes && run.mode == BackupMergeMode.REPLACE_SELECTED) fileOperations.clearIndexUndoAfterLibraryReplacement()
    }

    /** Both stage databases and the read-only resource session have exited before directory removal. */
    private suspend fun finalizeClosedRun(saved: BackupRestoreJournal) {
        if (closeRetentions.hasPending) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        val run = physical { signal -> signal.throwIfCanceled(); saved.readRun() } ?: return
        val expected = when (run.phase) {
            BackupRestorePhase.FINISHING_FORWARD -> run.targetMainDigest
            BackupRestorePhase.FINISHING_ROLLBACK -> run.beforeMainDigest
            else -> return
        }
        if (expected == null || committedDigest() != expected) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        val root = directory ?: throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        physical { signal ->
            signal.throwIfCanceled()
            for (id in setOfNotNull(run.incomingGeneration, run.beforeGeneration, run.targetGeneration)) {
                val owner = saved.generationOwner(id) ?: throw BackupException(BackupIssue.RECOVERY_REQUIRED)
                root.openOwnedGeneration(owner)?.let(root::retireGeneration)
            }
            saved.clearFinished(run.id)
        }
        library.invalidateAfterBackupRestore()
        barrier.recovered()
    }

    private suspend fun verifyConfirmedConfigurations(saved: BackupRestoreJournal, fonts: LyricsFontBackupAccess, direction: BackupConfigDirection) {
        resources.verifyThemeEvolution(saved)
        val steps = physical { signal -> signal.throwIfCanceled(); saved.configs() }
        for (step in steps) {
            val expected = if (direction == BackupConfigDirection.FORWARD) step.forward else step.before.record
            val actual = configurations.read(step.key, fonts)
            if (expected == null || !actual.record.sameContent(expected) || step.direction != direction || step.confirmedRevision == null ||
                step.key != "theme" && actual.revision != step.confirmedRevision) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        }
    }
    private fun knownCurrent(step: BackupConfigStep, current: BackupConfigVersion): Boolean {
        val before = step.attemptBefore ?: step.before
        val target = step.attemptTarget
        return current.revision == before.revision && current.record.sameContent(before.record) ||
            before.revision < Long.MAX_VALUE && current.revision == before.revision + 1L && target != null && current.record.sameContent(target)
    }

    private suspend fun planRelinks(run: BackupRestoreRun, stage: SqliteBackupStage, saved: BackupRestoreJournal) {
        var after: String? = null
        while (BackupScope.LIBRARY in run.scopes) {
            val page = stage.pageNow(BackupRecordKind.TRACK_LOCATION, after)
            if (page.isEmpty()) break
            val pending = page.mapNotNull { row ->
                if (run.mode == BackupMergeMode.MERGE_KEEP_CURRENT && database.libraryDao().currentTrack(row.text("trackId")) != null) return@mapNotNull null
                val binding = stage.binding(BackupBindingFamily.LOCATION, row.text("locationId")) ?: throw BackupException(BackupIssue.INVALID_REFERENCE)
                if (binding.verified) null else BackupRelinkHint(stableUuid(run.archiveSha256, row.text("trackId"), binding.actualId),
                    row.text("trackId"), binding.actualId, run.archiveSha256,
                    stage.row(BackupRecordKind.LOCATION_HINT, row.text("locationId")) ?: throw BackupException(BackupIssue.INVALID_REFERENCE))
            }
            physical { signal -> signal.throwIfCanceled(); saved.planRelinks(run.id, pending) }
            after = page.last().key
        }
        stage.beginRelinkReferences()
        for (kind in BackupRecordKind.entries) {
            if (kind.scope !in run.scopes) continue
            var cursor: String? = null
            while (true) {
                val page = stage.pageNow(kind, cursor)
                if (page.isEmpty()) break
                for (row in page) {
                    val track = when (kind) {
                        BackupRecordKind.TRACK, BackupRecordKind.LYRICS_TRACK -> row.key
                        BackupRecordKind.FAVORITES_MEMBER, BackupRecordKind.PLAYLIST_MEMBER ->
                            row.optionalText("trackId")?.takeIf { row.optionalText("providerId") == TrackKey.LOCAL_PROVIDER }
                        BackupRecordKind.QUEUE_ENTRY, BackupRecordKind.HISTORY_ENTRY, BackupRecordKind.TRACK_TOTAL ->
                            row.text("trackId").takeIf { row.text("providerId") == TrackKey.LOCAL_PROVIDER }
                        else -> null
                    } ?: continue
                    val queueMissing = kind == BackupRecordKind.QUEUE_ENTRY && stage.binding(BackupBindingFamily.QUEUE, row.key)?.verified != true
                    stage.referenceTrack(track, queueMissing)
                }
                cursor = page.last().key
            }
        }
        after = null
        while (true) {
            val page = stage.referenceTrackPage(after)
            if (page.isEmpty()) break
            val hints = ArrayList<BackupRelinkHint>()
            for ((trackId, missingQueue) in page) {
                val current = database.libraryDao().currentTrack(trackId)
                val restoringLibraryTrack = BackupScope.LIBRARY in run.scopes && stage.row(BackupRecordKind.TRACK, trackId) != null
                val needs = if (restoringLibraryTrack) !stage.hasAnyTrackLocation(trackId) &&
                    (run.mode == BackupMergeMode.REPLACE_SELECTED || current == null) else current == null || missingQueue
                if (needs) hints += syntheticBackupRelinkHint(run.archiveSha256, trackId)
            }
            physical { signal -> signal.throwIfCanceled(); saved.planRelinks(run.id, hints) }
            after = page.last().first
        }
    }
    private suspend fun projectRelinks(saved: BackupRestoreJournal) {
        val run = physical { signal -> signal.throwIfCanceled(); saved.readRun() } ?: throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        physical { signal -> signal.throwIfCanceled(); saved.beginRelinkProjection(run.id) }
        var after: String? = null
        while (true) {
            val page = physical { signal -> signal.throwIfCanceled(); saved.pendingRelinkPage(after) }
            if (page.isEmpty()) break
            val live = ArrayList<BackupRelinkHint>()
            for (hint in page) if (relinker.isMissing(hint)) live += hint
            physical { signal -> signal.throwIfCanceled(); saved.projectRelinks(run.id, live) }
            after = page.last().id
        }
    }

    private suspend fun impacts(stage: SqliteBackupStage, scopes: Set<BackupScope>, mode: BackupMergeMode,
        inventory: BackupResourceInventory?, preferencesMissing: Long): List<BackupScopeImpact> =
        relinker.restoreImpacts(stage, scopes, mode, inventory, preferencesMissing)
    private fun hasLicensedResource(stage: SqliteBackupStage): Boolean {
        var after: String? = null
        while (true) {
            val page = stage.pageNow(BackupRecordKind.RESOURCE, after)
            if (page.isEmpty()) return false
            if (page.any { it.flag("licenseRequired") }) return true
            after = page.last().key
        }
    }

    private suspend fun newGeneration(): BackupPrivateDirectory {
        val root = directory ?: throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        val saved = storageJournal()
        val allocated = AtomicReference<BackupPrivateDirectory?>(null)
        var outgoing: Throwable? = null
        try {
            val generation = physical { signal ->
                signal.throwIfCanceled()
                val created = root.newGeneration(onCreated = allocated::set)
                saved.registerGeneration(created.ownership())
                created
            }
            currentCoroutineContext().ensureActive()
            check(allocated.compareAndSet(generation, null))
            return generation
        } catch (failure: Throwable) { outgoing = failure; throw failure }
        finally {
            allocated.get()?.let { generation -> withContext(NonCancellable) {
                try {
                    physical { signal ->
                        signal.throwIfCanceled()
                        if (closeRetentions.hasPending) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
                        val run = saved.readRun()
                        if (run != null && generation.root.name in setOfNotNull(run.incomingGeneration, run.beforeGeneration, run.targetGeneration)) {
                            throw BackupException(BackupIssue.RECOVERY_REQUIRED)
                        }
                        val owner = saved.generationOwner(generation.root.name)
                        if (owner == null) root.retireGeneration(generation)
                        else {
                            val actual = generation.ownership()
                            if (actual.device != owner.device || actual.inode != owner.inode) throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
                            root.openOwnedGeneration(owner)?.let(root::retireGeneration)
                            saved.retireGenerationOwner(owner.id)
                        }
                    }
                    allocated.compareAndSet(generation, null)
                }
                catch (cleanup: Throwable) {
                    barrier.requireRecovery()
                    mutableStartup.value = BackupStartupState.Unavailable(BackupIssue.RECOVERY_REQUIRED)
                    val original = outgoing
                    if (original == null) throw cleanup
                    if (original !== cleanup) original.addSuppressed(cleanup)
                }
            } }
        }
    }
    private suspend fun discardHeldPreview() {
        val held = heldPreview ?: return
        heldPreview = null
        closeAndRetire(null, held.generation, null)
    }
    private suspend fun closeAndRetire(stage: SqliteBackupStage?, generation: BackupPrivateDirectory, outgoing: Throwable?) {
        withContext(NonCancellable) {
            var failure = outgoing
            var closed = stage == null
            if (stage != null) try { closeOwner(stage); closed = true }
            catch (cleanup: Throwable) { if (failure == null) failure = cleanup else if (cleanup !== failure) failure.addSuppressed(cleanup) }
            val saved = journal
            val run = try { if (saved == null) null else physical { _ -> saved.readRun() } }
            catch (cleanup: Throwable) {
                barrier.requireRecovery()
                if (failure == null) throw cleanup else {
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                    return@withContext
                }
            }
            if (closed && !closeRetentions.hasPending && (run == null || generation.root.name !in setOfNotNull(run.incomingGeneration, run.beforeGeneration, run.targetGeneration))) {
                try { retireGeneration(generation.root.name) }
                catch (cleanup: Throwable) { if (failure == null) failure = cleanup else if (cleanup !== failure) failure.addSuppressed(cleanup) }
            }
            if (outgoing == null) failure?.let { throw it }
        }
    }
    private suspend fun retireGeneration(id: String) {
        if (closeRetentions.hasPending) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        val saved = storageJournal()
        val root = directory ?: throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        physical { signal ->
            signal.throwIfCanceled()
            val run = saved.readRun()
            if (run != null && id in setOfNotNull(run.incomingGeneration, run.beforeGeneration, run.targetGeneration)) {
                throw BackupException(BackupIssue.RECOVERY_REQUIRED)
            }
            val owner = saved.generationOwner(id) ?: return@physical
            root.openOwnedGeneration(owner)?.let(root::retireGeneration)
            saved.retireGenerationOwner(id)
        }
    }
    private suspend fun retireUnusedGenerations(saved: BackupRestoreJournal) {
        val run = physical { signal -> signal.throwIfCanceled(); saved.readRun() }
        val protected = setOfNotNull(run?.incomingGeneration, run?.beforeGeneration, run?.targetGeneration, heldPreview?.generation?.root?.name)
        var after: String? = null
        while (true) {
            val page = physical { signal -> signal.throwIfCanceled(); saved.generationPage(after) }
            if (page.isEmpty()) break
            for (owner in page) if (owner.id !in protected) retireGeneration(owner.id)
            after = page.last().id
        }
    }
    private fun storageJournal(): BackupRestoreJournal = journal ?: throw BackupException(BackupIssue.RECOVERY_REQUIRED)
    private suspend fun <T> action(operation: suspend () -> T): T {
        requireReady()
        if (!actions.tryLock()) throw BackupException(BackupIssue.BUSY)
        try { return withContext(Dispatchers.IO) { operation() } }
        catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                try { discardHeldPreview(); relinker.discardAll() }
                catch (cleanup: Throwable) { if (cleanup !== cancelled) cancelled.addSuppressed(cleanup) }
            }
            throw cancelled
        }
        catch (failure: Exception) { update(BackupStage.FAILED); throw if (failure is BackupException) failure else BackupException(issue(failure), failure) }
        finally { actions.unlock() }
    }
    private suspend fun <T> physical(operation: (CancellationSignal) -> T): T {
        val admitted = AtomicBoolean(false)
        try { return io.read(admitted = { admitted.set(true) }, action = operation) }
        catch (failure: SourceReadException) { throw (failure.cause as? BackupException) ?: failure }
        finally { if (admitted.get()) withContext(NonCancellable) { io.awaitIdle() } }
    }
    private suspend fun <T : AutoCloseable> physicalOwner(operation: (CancellationSignal, (T) -> Unit) -> T): T =
        openBackupPhysicalOwner(io, onCloseFailure = { owner, _ -> closeRetentions.retain(owner) }, action = operation)

    private suspend fun closeOwner(owner: AutoCloseable) {
        closeRetentions.close(owner)
        if (owner === journal) journal = null
    }

    /** Clear a delivered journal pointer only after retrying that same actual object's close. */
    private suspend fun retryRetainedCloses() {
        val priorJournal = journal?.takeIf(closeRetentions::contains)
        try { closeRetentions.retryAll() }
        finally { if (priorJournal != null && !closeRetentions.contains(priorJournal)) journal = null }
    }
    private fun update(stage: BackupStage, records: Long = 0L, bytes: Long = 0L) { mutableProgress.value = BackupProgress(stage, records, bytes) }
    private suspend fun committedDigest(): String {
        val caller = currentCoroutineContext()
        return capture.mainDigest(checkCancelled = { caller.ensureActive() })
    }
    private fun issue(failure: Exception): BackupIssue {
        var cause: Throwable? = failure
        repeat(16) {
            val current = cause ?: return@repeat
            if (current is BackupException) return current.issue
            cause = current.cause
        }
        return when (failure) {
        is BackupException -> failure.issue
        is SourceReadException -> when (failure.issue) {
            SourceReadIssue.BUSY -> BackupIssue.BUSY
            SourceReadIssue.PERMISSION_DENIED, SourceReadIssue.PERMISSION_REVOKED -> BackupIssue.PERMISSION_DENIED
            SourceReadIssue.STORAGE_FULL -> BackupIssue.STORAGE_FULL
            else -> BackupIssue.IO
        }
        is LibraryException -> when (failure.issue) {
            LibraryIssue.BUSY -> BackupIssue.BUSY
            LibraryIssue.CONFLICT, LibraryIssue.SOURCE_CHANGED -> BackupIssue.STALE_PREVIEW
            LibraryIssue.STORAGE_FULL -> BackupIssue.STORAGE_FULL
            else -> BackupIssue.RECOVERY_REQUIRED
        }
        is LyricsFontException -> when (failure.issue) {
            LyricsFontIssue.BUSY -> BackupIssue.BUSY
            LyricsFontIssue.LICENSE_ACKNOWLEDGMENT_REQUIRED -> BackupIssue.FONT_LICENSE_REQUIRED
            LyricsFontIssue.PERMISSION_DENIED -> BackupIssue.PERMISSION_DENIED
            LyricsFontIssue.TOO_LARGE, LyricsFontIssue.QUOTA_EXCEEDED, LyricsFontIssue.LOW_MEMORY -> BackupIssue.TOO_LARGE
            LyricsFontIssue.STORAGE_FULL -> BackupIssue.STORAGE_FULL
            LyricsFontIssue.INTEGRITY_FAILED -> BackupIssue.INTEGRITY_MISMATCH
            LyricsFontIssue.UNSUPPORTED_FORMAT, LyricsFontIssue.MISSING_GLYPHS -> BackupIssue.UNSUPPORTED_RESOURCE
            LyricsFontIssue.SETTINGS_CONFLICT -> BackupIssue.STALE_PREVIEW
            LyricsFontIssue.SETTINGS_UNAVAILABLE, LyricsFontIssue.FONT_BUDGET_UNAVAILABLE -> BackupIssue.RECOVERY_REQUIRED
            LyricsFontIssue.INVALID_INPUT, LyricsFontIssue.INVALID_FONT, LyricsFontIssue.NATIVE_DECODE_FAILED -> BackupIssue.INVALID_RESOURCE
            else -> BackupIssue.IO
        }
        is ThemeException -> when (failure.issue) {
            ThemeIssue.BUSY -> BackupIssue.BUSY
            ThemeIssue.PERMISSION_DENIED -> BackupIssue.PERMISSION_DENIED
            ThemeIssue.PACKAGE_TOO_LARGE, ThemeIssue.EXPANDED_TOO_LARGE, ThemeIssue.EXPANSION_RATIO,
            ThemeIssue.TOO_MANY_RESOURCES, ThemeIssue.IMAGE_TOO_LARGE, ThemeIssue.FONT_QUOTA_EXCEEDED,
            ThemeIssue.STORAGE_QUOTA_EXCEEDED, ThemeIssue.LOW_MEMORY -> BackupIssue.TOO_LARGE
            ThemeIssue.STORAGE_FULL -> BackupIssue.STORAGE_FULL
            ThemeIssue.INVALID_PATH, ThemeIssue.LINK_OR_SPECIAL_FILE -> BackupIssue.PATH_ESCAPE
            ThemeIssue.DUPLICATE_PATH -> BackupIssue.DUPLICATE_RECORD
            ThemeIssue.FONT_LICENSE_ACKNOWLEDGMENT_REQUIRED -> BackupIssue.FONT_LICENSE_REQUIRED
            ThemeIssue.INTEGRITY_FAILED -> BackupIssue.INTEGRITY_MISMATCH
            ThemeIssue.UNSUPPORTED_ARCHIVE, ThemeIssue.UNSUPPORTED_SCHEMA, ThemeIssue.UNSUPPORTED_FIELD,
            ThemeIssue.UNSUPPORTED_RESOURCE -> BackupIssue.UNSUPPORTED_RESOURCE
            ThemeIssue.REVISION_CONFLICT, ThemeIssue.OPERATION_CONFLICT, ThemeIssue.PREVIEW_NOT_FOUND -> BackupIssue.STALE_PREVIEW
            ThemeIssue.THEME_ID_CONFLICT, ThemeIssue.IN_USE -> BackupIssue.CONFLICT
            ThemeIssue.RECOVERY_REQUIRED, ThemeIssue.CLEANUP_REQUIRED, ThemeIssue.CORRUPT_STORAGE,
            ThemeIssue.FONT_BUDGET_UNAVAILABLE -> BackupIssue.RECOVERY_REQUIRED
            ThemeIssue.STORAGE_UNAVAILABLE, ThemeIssue.SAVE_FAILED, ThemeIssue.INPUT_CLOSE_FAILED,
            ThemeIssue.SOURCE_UNAVAILABLE -> BackupIssue.IO
            else -> BackupIssue.INVALID_RESOURCE
        }
        is PlaybackBackgroundException -> when (failure.issue) {
            PlaybackBackgroundIssue.BUSY -> BackupIssue.BUSY
            PlaybackBackgroundIssue.PERMISSION_DENIED -> BackupIssue.PERMISSION_DENIED
            PlaybackBackgroundIssue.IMAGE_TOO_LARGE, PlaybackBackgroundIssue.LOW_MEMORY -> BackupIssue.TOO_LARGE
            PlaybackBackgroundIssue.STORAGE_FULL -> BackupIssue.STORAGE_FULL
            PlaybackBackgroundIssue.INTEGRITY_FAILED -> BackupIssue.INTEGRITY_MISMATCH
            PlaybackBackgroundIssue.SETTINGS_CONFLICT, PlaybackBackgroundIssue.OPERATION_CONFLICT,
            PlaybackBackgroundIssue.PREVIEW_NOT_FOUND -> BackupIssue.STALE_PREVIEW
            PlaybackBackgroundIssue.RECOVERY_REQUIRED, PlaybackBackgroundIssue.CLEANUP_REQUIRED -> BackupIssue.RECOVERY_REQUIRED
            PlaybackBackgroundIssue.NO_IMAGE, PlaybackBackgroundIssue.INVALID_IMAGE, PlaybackBackgroundIssue.INVALID_INPUT -> BackupIssue.INVALID_RESOURCE
            else -> BackupIssue.IO
        }
        is LyricsParseException -> when (failure.issue) {
            LyricsParseIssue.INPUT_TOO_LARGE, LyricsParseIssue.TOO_MANY_LINES, LyricsParseIssue.TOO_MANY_TIMED_LINES,
            LyricsParseIssue.TOO_MANY_WORD_EVENTS, LyricsParseIssue.LINE_TOO_LONG, LyricsParseIssue.MEMORY_LIMIT -> BackupIssue.TOO_LARGE
            LyricsParseIssue.UNSUPPORTED_ENCODING -> BackupIssue.UNSUPPORTED_RESOURCE
            else -> BackupIssue.INVALID_RESOURCE
        }
        is FileOperationException -> when (failure.issue) {
            FileOperationIssue.BUSY -> BackupIssue.BUSY
            FileOperationIssue.RECOVERY_REQUIRED -> BackupIssue.PENDING_FILE_OPERATION
            FileOperationIssue.CONFLICT, FileOperationIssue.SOURCE_CHANGED -> BackupIssue.STALE_PREVIEW
            FileOperationIssue.PERMISSION_DENIED, FileOperationIssue.PERMISSION_REVOKED -> BackupIssue.PERMISSION_DENIED
            FileOperationIssue.STORAGE_FULL -> BackupIssue.STORAGE_FULL
            FileOperationIssue.PLAYBACK_RELEASE_UNCONFIRMED -> BackupIssue.RECOVERY_REQUIRED
            else -> BackupIssue.IO
        }
        else -> BackupIssue.IO
        }
    }
    companion object { private const val PREVIEW_MS = 10L * 60 * 1000 }
}
