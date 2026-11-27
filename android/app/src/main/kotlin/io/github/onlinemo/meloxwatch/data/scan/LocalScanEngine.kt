package io.github.onlinemo.meloxwatch.data.scan

import io.github.onlinemo.meloxwatch.data.cue.CueEncoding
import io.github.onlinemo.meloxwatch.data.cue.CueException
import io.github.onlinemo.meloxwatch.data.cue.CueGroupCommit
import io.github.onlinemo.meloxwatch.data.cue.CueIssue
import io.github.onlinemo.meloxwatch.data.cue.CueScanStore
import io.github.onlinemo.meloxwatch.data.library.LibraryException
import io.github.onlinemo.meloxwatch.data.library.LibraryMutationStore
import io.github.onlinemo.meloxwatch.data.library.LibraryIssue
import io.github.onlinemo.meloxwatch.data.library.LibrarySource
import io.github.onlinemo.meloxwatch.data.library.LocalAudioMetadata
import io.github.onlinemo.meloxwatch.data.local.AndroidLocalSources
import io.github.onlinemo.meloxwatch.data.local.LocalInputLimits
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.LocalSourceAccess
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** One scan job; the database owns the bounded, durable directory frontier and generation fences. */
class LocalScanEngine(
    private val sources: LocalSourceAccess,
    private val metadata: suspend (ObservedCandidate) -> LocalAudioMetadata,
    private val store: LibraryMutationStore,
    private val io: LocalProviderIo,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val readContentToken: suspend (ObservedCandidate) -> String? = { null },
    private val verifyMetadataCandidate: suspend (LocalSourceSpec, ObservedCandidate) -> Unit = { _, _ ->
        throw SourceReadException(SourceReadIssue.CONTENT_UNKNOWN)
    },
    private val scanCue: suspend (ScanToken, ObservedCandidate, CueEncoding) -> CueGroupCommit = { _, _, _ ->
        throw CueException(CueIssue.INVALID_AUDIO)
    },
) {
    private val startLock = Mutex()
    private val activeLock = Any()
    private var active: Active? = null
    @Volatile private var backgrounded = false
    private val mutableState = MutableStateFlow<ScanState?>(null)
    val state: StateFlow<ScanState?> = mutableState.asStateFlow()

    suspend fun start(
        source: LibrarySource,
        rulesRevision: Long,
        mode: ScanMode = ScanMode.FULL,
        sinceMediaGeneration: Long? = null,
        cueEncoding: CueEncoding = CueEncoding.UTF8,
    ): ScanToken = begin(source, rulesRevision, mode, sinceMediaGeneration, cueEncoding)

    /** Updates are explicit. Only a current committed MediaStore checkpoint permits a generation query. */
    suspend fun update(source: LibrarySource, rulesRevision: Long): ScanToken =
        begin(source, rulesRevision, null, null, CueEncoding.UTF8)

    private suspend fun begin(source: LibrarySource, rulesRevision: Long, requestedMode: ScanMode?,
        requestedGeneration: Long?, cueEncoding: CueEncoding): ScanToken {
        synchronized(activeLock) {
            active?.let {
                return reusableToken(it, source, rulesRevision, requestedMode ?: it.token.mode, cueEncoding,
                    if (requestedMode == null) it.sinceGeneration else requestedGeneration)
            }
        }
        if (!startLock.tryLock()) throw SourceReadException(SourceReadIssue.BUSY)
        try {
            synchronized(activeLock) {
                active?.let {
                    return reusableToken(it, source, rulesRevision, requestedMode ?: it.token.mode, cueEncoding,
                        if (requestedMode == null) it.sinceGeneration else requestedGeneration)
                }
            }
            var mode = requestedMode ?: ScanMode.FULL
            var sinceGeneration = requestedGeneration
            var checkpoint: MediaScanCheckpoint? = null
            if (requestedMode == null || requestedMode == ScanMode.INCREMENTAL) {
                val observed = provider { sources.observe(source.spec) }
                if (observed.identity != AndroidLocalSources.sourceIdentity(source.spec)) throw SourceReadException(SourceReadIssue.SOURCE_CHANGED)
                val saved = store.scanCheckpoint(source.spec.sourceId, source.authEpoch, rulesRevision)?.takeIf { saved ->
                    source.spec.kind == LocalSourceKind.MEDIA_STORE && source.spec.mediaVolume != "external" &&
                        saved.sourceRevision == source.revision && saved.providerVersion == observed.providerVersion &&
                        observed.mediaGeneration?.let { it >= saved.generation } == true
                }
                if (saved != null && (requestedMode == null || requestedGeneration == saved.generation)) {
                    checkpoint = saved
                    mode = ScanMode.INCREMENTAL
                    sinceGeneration = saved.generation
                } else if (requestedMode == ScanMode.INCREMENTAL) {
                    throw SourceReadException(SourceReadIssue.SOURCE_CHANGED)
                } else sinceGeneration = null
            }
            val token = store.beginScan(source.spec.sourceId, source.authEpoch, rulesRevision, mode)
            val task = Active(token, cueEncoding, sinceGeneration, checkpoint)
            task.job = scope.launch(start = CoroutineStart.LAZY) { task.entered.set(true); run(task, source, sinceGeneration) }
            synchronized(activeLock) { active = task }
            publish(task, ScanState(token, ScanPhase.PREPARING))
            task.job.invokeOnCompletion {
                if (!task.entered.get()) scope.launch(NonCancellable) {
                    try {
                        io.awaitIdle()
                        val terminal = if (backgrounded) ScanPhase.PAUSED_BACKGROUND else task.terminal
                        val state = try { store.setScanState(task.token, terminal) }
                            catch (_: LibraryException) { ScanState(task.token, terminal) }
                        publish(task, state)
                    } finally {
                        synchronized(activeLock) { if (active === task) active = null }
                        task.settled.complete(Unit)
                    }
                }
            }
            task.job.start()
            return token
        } finally { startLock.unlock() }
    }

    suspend fun cancel(scanId: String) { stop(scanId, ScanPhase.CANCELLED) }

    /** 原件操作须等待已接受的提供方、游标和终态收尾，包括从未启动的扫描 job。 */
    suspend fun cancelAndAwait(scanId: String) = withContext(NonCancellable) {
        val task = synchronized(activeLock) { active?.takeIf { it.token.scanId == scanId } } ?: return@withContext
        stop(scanId, ScanPhase.CANCELLED)
        if (withTimeoutOrNull(5_000L) {
                task.settled.await()
                true
            } != true) {
            throw SourceReadException(SourceReadIssue.BUSY)
        }
    }

    private fun reusableToken(task: Active, source: LibrarySource, rulesRevision: Long, mode: ScanMode, encoding: CueEncoding,
        sinceGeneration: Long?): ScanToken {
        val token = task.token
        if (task.stopping.get() || token.sourceId != source.spec.sourceId || token.mode != mode || task.cueEncoding != encoding ||
            task.sinceGeneration != sinceGeneration) {
            throw SourceReadException(SourceReadIssue.BUSY)
        }
        if (token.sourceRevision != source.revision || token.authEpoch != source.authEpoch || token.rulesRevision != rulesRevision) {
            throw SourceReadException(SourceReadIssue.SOURCE_CHANGED)
        }
        return token
    }

    fun onForeground() { backgrounded = false }

    fun onBackground() {
        backgrounded = true
        val task = synchronized(activeLock) { active } ?: return
        if (!task.stopping.compareAndSet(false, true)) return
        task.terminal = ScanPhase.PAUSED_BACKGROUND
        scope.launch { stopAccepted(task) }
    }

    private suspend fun stop(scanId: String, terminal: ScanPhase) {
        val task = synchronized(activeLock) { active?.takeIf { it.token.scanId == scanId } } ?: return
        if (!task.stopping.compareAndSet(false, true)) return
        task.terminal = terminal
        stopAccepted(task)
    }

    private suspend fun stopAccepted(task: Active) {
        publish(task, (mutableState.value ?: ScanState(task.token, ScanPhase.RUNNING)).copy(phase = ScanPhase.STOPPING))
        try { store.setScanState(task.token, ScanPhase.STOPPING) }
        catch (error: LibraryException) {
            // A stale rules/source fence blocks writes, but cannot prevent this owned job from stopping.
            if (error.issue != LibraryIssue.SOURCE_CHANGED) throw error
        }
        finally { task.job.cancel(CancellationException("Scan stop requested")) }
    }

    private suspend fun run(task: Active, source: LibrarySource, sinceGeneration: Long?) {
        var counters = ScanCounters()
        var excluded = false
        var complete = true
        var authorizationValid = true
        var mediaFactsValid = true
        try {
            if (backgrounded || task.stopping.get()) {
                task.terminal = if (backgrounded) ScanPhase.PAUSED_BACKGROUND else task.terminal
                throw CancellationException("Scan starts while hidden")
            }
            val first = provider { sources.observe(source.spec) }
            if (first.identity != AndroidLocalSources.sourceIdentity(source.spec)) throw SourceReadException(SourceReadIssue.SOURCE_CHANGED)
            val checkpoint = task.checkpoint
            if (task.token.mode == ScanMode.INCREMENTAL &&
                (source.spec.kind != LocalSourceKind.MEDIA_STORE || sinceGeneration == null ||
                    checkpoint == null || !checkpoint.matches(task.token) || checkpoint.providerVersion != first.providerVersion ||
                    first.mediaGeneration?.let { it >= sinceGeneration } != true)
            ) throw SourceReadException(SourceReadIssue.SOURCE_CHANGED)
            counters = store.setScanState(task.token, ScanPhase.RUNNING).counters

            suspend fun consume(candidates: List<ObservedCandidate>) {
                currentCoroutineContext().ensureActive()
                if (backgrounded || task.stopping.get()) throw CancellationException("Scan paused at batch boundary")
                if (first.mediaGeneration != null) {
                    for (candidate in candidates) if (!candidate.directory && candidate.mayContainAudio) {
                        val generation = candidate.generationModified
                        if (candidate.providerVersion != first.providerVersion || generation == null ||
                            generation !in 0L..first.mediaGeneration) mediaFactsValid = false
                    }
                }
                val seen = store.commitSeen(task.token, candidates)
                val outcomes = ArrayList<MetadataOutcome>(candidates.size)
                for (candidate in candidates.distinctBy { it.objectId }) {
                    currentCoroutineContext().ensureActive()
                    if (candidate.directory) continue
                    val isExcluded = store.isExcluded(task.token, candidate.objectId, candidate.parentObjectId)
                    if (isExcluded || candidate.mayContainCue || !candidate.mayContainAudio) {
                        continue
                    }
                    if (seen.metadataObjectIds != null && candidate.objectId !in seen.metadataObjectIds) continue
                    try {
                        val hasProviderProof = candidate.providerContentToken?.let { !it.startsWith("sha256:") } == true
                        if (hasProviderProof) provider { verifyMetadataCandidate(first.source, candidate) }
                        val before = if (candidate.providerContentToken == null) provider { readContentToken(candidate) } else null
                        val values = provider { metadata(candidate) }
                        if (hasProviderProof) provider { verifyMetadataCandidate(first.source, candidate) }
                        if (before != null && before != provider { readContentToken(candidate) }) throw SourceReadException(SourceReadIssue.SOURCE_CHANGED)
                        outcomes += MetadataOutcome(candidate.objectId, values, verifiedContentToken = before)
                    } catch (error: SourceReadException) {
                        if (error.issue == SourceReadIssue.BUSY) throw error
                        if (error.issue == SourceReadIssue.PERMISSION_DENIED || error.issue == SourceReadIssue.PERMISSION_REVOKED) {
                            authorizationValid = false
                        }
                        outcomes += MetadataOutcome(candidate.objectId, issue = error.issue)
                    }
                }
                if (outcomes.isNotEmpty()) {
                    store.commitMetadata(task.token, outcomes)
                }
                val acknowledged = store.latestScan(task.token.sourceId)
                    ?.takeIf { it.token == task.token } ?: throw SourceReadException(SourceReadIssue.SOURCE_CHANGED)
                counters = acknowledged.counters
                publish(task, ScanState(task.token, ScanPhase.RUNNING, counters))
            }

            consume(listOf(first.root))
            while (true) {
                currentCoroutineContext().ensureActive()
                if (backgrounded || task.stopping.get()) throw CancellationException("Scan paused at batch boundary")
                val directory = store.nextDirectory(task.token) ?: break
                if (store.isExcluded(task.token, directory.objectId, directory.parentObjectId)) {
                    excluded = true
                    counters = store.completeDirectory(task.token, directory.objectId, true).counters
                    continue
                }
                try {
                    val cursor = provider { sources.open(first.source, directory.objectId,
                        sinceGeneration.takeIf { task.token.mode == ScanMode.INCREMENTAL }) }
                    try {
                        while (!cursor.exhausted) {
                            val batch = provider { cursor.nextBatch(LocalInputLimits.BATCH_SIZE) }
                            if (batch.isEmpty() && !cursor.exhausted) throw SourceReadException(SourceReadIssue.SOURCE_UNAVAILABLE)
                            if (batch.isNotEmpty()) consume(batch)
                        }
                    } finally { withContext(NonCancellable) { cursor.closeAndAwait() } }
                    counters = store.completeDirectory(task.token, directory.objectId, true).counters
                } catch (error: SourceReadException) {
                    complete = false
                    if (error.issue == SourceReadIssue.PERMISSION_DENIED || error.issue == SourceReadIssue.PERMISSION_REVOKED) authorizationValid = false
                    counters = store.completeDirectory(task.token, directory.objectId, false).counters
                }
            }
            if (task.token.mode == ScanMode.INCREMENTAL && complete) {
                // MediaStore has no deletion tombstones. A second bounded cursor proves the complete
                // existence set; commitSeen reuses only metadata bound to the successful checkpoint.
                try {
                    val cursor = provider { sources.open(first.source, first.root.objectId, null) }
                    try {
                        while (!cursor.exhausted) {
                            val batch = provider { cursor.nextBatch(LocalInputLimits.BATCH_SIZE) }
                            if (batch.isEmpty() && !cursor.exhausted) throw SourceReadException(SourceReadIssue.SOURCE_UNAVAILABLE)
                            if (batch.isNotEmpty()) consume(batch)
                        }
                    } finally { withContext(NonCancellable) { cursor.closeAndAwait() } }
                    counters = store.completeDirectory(task.token, first.root.objectId, true, fullEnumeration = true).counters
                } catch (error: SourceReadException) {
                    complete = false
                    if (error.issue == SourceReadIssue.PERMISSION_DENIED || error.issue == SourceReadIssue.PERMISSION_REVOKED) authorizationValid = false
                    counters = store.completeDirectory(task.token, first.root.objectId, false, fullEnumeration = true).counters
                }
            }
            // 全部枚举游标释放后，CUE 查询才开启自己的游标。
            val cues = store as? CueScanStore
            if (cues != null) while (true) {
                currentCoroutineContext().ensureActive()
                if (backgrounded || task.stopping.get()) throw CancellationException("CUE scan paused at group boundary")
                val candidate = cues.nextCueDocument(task.token) ?: break
                try {
                    provider { scanCue(task.token, candidate, task.cueEncoding) }
                    counters = cues.completeCueDocument(task.token, candidate.objectId).counters
                } catch (failure: CueException) {
                    complete = false
                    if (failure.issue == CueIssue.AUTHORIZATION_REQUIRED) authorizationValid = false
                    counters = cues.completeCueDocument(task.token, candidate.objectId, failure.issue).counters
                } catch (failure: SourceReadException) {
                    if (failure.issue == SourceReadIssue.BUSY || failure.issue == SourceReadIssue.SOURCE_CHANGED) throw failure
                    complete = false
                    val issue = when (failure.issue) {
                        SourceReadIssue.PERMISSION_DENIED, SourceReadIssue.PERMISSION_REVOKED -> {
                            authorizationValid = false;
                            CueIssue.AUTHORIZATION_REQUIRED
                        }
                        SourceReadIssue.FILE_MISSING, SourceReadIssue.SOURCE_UNAVAILABLE -> CueIssue.MISSING_AUDIO
                        SourceReadIssue.CONTENT_UNKNOWN -> CueIssue.UNVERIFIED_CONTENT
                        else -> CueIssue.INVALID_AUDIO
                    }
                    counters = cues.completeCueDocument(task.token, candidate.objectId, issue).counters
                }
                publish(task, ScanState(task.token, ScanPhase.RUNNING, counters))
            }
            val last = provider { sources.observe(first.source) }
            val same = first.identity == last.identity && first.providerVersion == last.providerVersion &&
                first.mediaGeneration == last.mediaGeneration && mediaFactsValid
            val accessible = last.access.read == ObservedCapability.AVAILABLE || last.access.enumerate == ObservedCapability.AVAILABLE
            val acknowledged = store.setCoverage(task.token, ScanCoverage(complete, authorizationValid && accessible, true, same, excluded))
            val coverage = acknowledged.coverage
            counters = acknowledged.counters
            if (coverage.permitsAbsence) {
                publish(task, ScanState(task.token, ScanPhase.STAGING_ABSENCE, counters, coverage))
                var after: String? = null
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val page = store.stageAbsence(task.token, after)
                    if (page.complete) break
                    if (page.nextAliasId == null || page.nextAliasId == after) throw SourceReadException(SourceReadIssue.SOURCE_UNAVAILABLE)
                    after = page.nextAliasId
                }
            }
            if (source.spec.kind == LocalSourceKind.MEDIA_STORE && source.spec.mediaVolume != "external" &&
                coverage.permitsAbsence && counters.failed == 0L && first.providerVersion?.isNotBlank() == true && first.mediaGeneration != null) {
                val checkpoint = MediaScanCheckpoint(task.token.sourceId, task.token.scanId, task.token.sourceRevision,
                    task.token.authEpoch, task.token.rulesRevision, first.providerVersion, first.mediaGeneration, task.token.mode)
                store.setWatermark(task.token, checkpoint.encode())
            }
            val final = store.finishScan(task.token, counters, coverage)
            publish(task, final)
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                // The channel stays occupied if a provider is still physically stopping.
                io.awaitIdle()
                val terminal = if (backgrounded) ScanPhase.PAUSED_BACKGROUND else task.terminal
                val state = try { store.setScanState(task.token, terminal) }
                    catch (_: LibraryException) { ScanState(task.token, terminal, counters) }
                publish(task, state)
            }
        } catch (error: Exception) {
            val issue = when (error) {
                is SourceReadException -> error.issue
                is LibraryException -> when (error.issue) {
                    LibraryIssue.BUSY -> SourceReadIssue.BUSY
                    LibraryIssue.CONFLICT, LibraryIssue.SOURCE_CHANGED -> SourceReadIssue.SOURCE_CHANGED
                    LibraryIssue.PERMISSION_REVOKED -> SourceReadIssue.PERMISSION_REVOKED
                    LibraryIssue.STORAGE_FULL -> SourceReadIssue.STORAGE_FULL
                    LibraryIssue.INVALID_INPUT -> SourceReadIssue.INVALID_SOURCE
                    LibraryIssue.FILE_MISSING -> SourceReadIssue.FILE_MISSING
                    else -> SourceReadIssue.SOURCE_UNAVAILABLE
                }
                else -> SourceReadIssue.SOURCE_UNAVAILABLE
            }
            withContext(NonCancellable) {
                val state = try { store.setScanState(task.token, ScanPhase.FAILED, issue) }
                    catch (_: LibraryException) { ScanState(task.token, ScanPhase.FAILED, counters, issue = issue) }
                publish(task, state)
            }
        } finally {
            synchronized(activeLock) { if (active === task) active = null }
            task.settled.complete(Unit)
        }
    }

    private suspend fun <T> provider(action: suspend () -> T): T {
        repeat(3) {
            currentCoroutineContext().ensureActive()
            try { return action() } catch (error: SourceReadException) {
                if (error.issue != SourceReadIssue.BUSY || it == 2) throw error
                io.awaitIdle()
            }
        }
        throw SourceReadException(SourceReadIssue.BUSY)
    }

    private fun publish(task: Active, value: ScanState) {
        synchronized(activeLock) { if (active === task && (!task.stopping.get() || value.phase != ScanPhase.RUNNING)) mutableState.value = value }
    }

    private class Active(val token: ScanToken, val cueEncoding: CueEncoding, val sinceGeneration: Long?, val checkpoint: MediaScanCheckpoint?) {
        lateinit var job: Job
        val stopping = AtomicBoolean(false)
        val entered = AtomicBoolean(false)
        val settled = CompletableDeferred<Unit>()
        @Volatile var terminal = ScanPhase.CANCELLED
    }
}
