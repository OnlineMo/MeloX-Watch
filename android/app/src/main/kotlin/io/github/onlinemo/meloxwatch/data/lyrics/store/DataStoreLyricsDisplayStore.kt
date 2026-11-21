package io.github.onlinemo.meloxwatch.data.lyrics.store

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One file owner, one observer and one admitted mutation; disk revision is the CAS authority. */
class DataStoreLyricsDisplayStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
) : LyricsDisplayStore {
    private val mutableState = MutableStateFlow<LyricsDisplayState>(LyricsDisplayState.Loading)
    override val state = mutableState.asStateFlow()
    private val occupied = AtomicBoolean(false)
    private val publicationLock = Any()
    private var readGeneration = Any()
    private var pendingReadRetry: CompletableDeferred<Unit>? = null

    init {
        scope.launch {
            while (isActive) {
                val observedGeneration = synchronized(publicationLock) { readGeneration }
                try {
                    dataStore.data.collect {
                        // DataStore 1.1.7 publishes its cache before atomic move can fail. Treat
                        // emissions as notifications; only a locked file read confirms settings.
                        val generation = synchronized(publicationLock) { readGeneration }
                        publishVerifiedRead(LyricsDisplayCodec.decode(readActualPreferences()), generation)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: CorruptionException) {
                    awaitReadRetry(observedGeneration, LyricsDisplayIssue.CORRUPT_FILE)
                } catch (_: IOException) {
                    awaitReadRetry(observedGeneration, LyricsDisplayIssue.IO)
                } catch (_: OutOfMemoryError) {
                    awaitReadRetry(observedGeneration, LyricsDisplayIssue.LOW_MEMORY)
                }
            }
        }
    }

    override suspend fun save(expectedRevision: Long, next: LyricsDisplayPreferences): LyricsDisplayWriteResult {
        if (expectedRevision < 0L || next.revision != expectedRevision) {
            return LyricsDisplayWriteResult.Failed(lastKnown(), LyricsDisplayIssue.INVALID_REVISION)
        }
        if (!occupied.compareAndSet(false, true)) {
            return LyricsDisplayWriteResult.Failed(lastKnown(), LyricsDisplayIssue.BUSY)
        }
        var readAtWrite: DecodedLyricsDisplay? = null
        var intended: LyricsDisplayPreferences? = null
        var changed = false
        var writeFailure: Throwable? = null
        val caller = currentCoroutineContext()
        try {
            caller.ensureActive()
            // updateData's caller can stop awaiting while its writer still runs. Hold admission
            // through the actual attempt, acknowledgment and any required durable read-back.
            val attempt = withContext(NonCancellable) {
                runCatching {
                    try {
                        val committed = dataStore.updateData { stored ->
                            caller.ensureActive()
                            val decoded = LyricsDisplayCodec.decode(stored)
                            readAtWrite = decoded
                            val current = decoded.preferences ?: throw RejectedWrite(checkNotNull(decoded.issue))
                            if (current.revision != expectedRevision) throw ConflictingWrite(current)
                            changed = current != next || !decoded.persisted
                            if (!changed) {
                                caller.ensureActive()
                                stored
                            } else {
                                if (current.revision == Long.MAX_VALUE) throw RejectedWrite(LyricsDisplayIssue.REVISION_EXHAUSTED)
                                val replacement = next.copy(revision = current.revision + 1L)
                                intended = replacement
                                LyricsDisplayCodec.encode(replacement).also { caller.ensureActive() }
                            }
                        }
                        val decoded = LyricsDisplayCodec.decode(committed)
                        publishAuthoritative(decoded)
                        val saved = decoded.preferences
                        when {
                            saved == null -> LyricsDisplayWriteResult.Failed(lastKnown(), checkNotNull(decoded.issue))
                            changed -> LyricsDisplayWriteResult.Saved(saved)
                            else -> LyricsDisplayWriteResult.Unchanged(saved)
                        }
                    } catch (conflict: ConflictingWrite) {
                        writeFailure = conflict
                        readAtWrite?.let(::publishAuthoritative)
                        LyricsDisplayWriteResult.Conflict(conflict.current)
                    } catch (rejected: RejectedWrite) {
                        writeFailure = rejected
                        readAtWrite?.let(::publishAuthoritative)
                        LyricsDisplayWriteResult.Failed(lastKnown(), rejected.issue)
                    } catch (corrupt: CorruptionException) {
                        writeFailure = corrupt
                        publishUnavailable(LyricsDisplayIssue.CORRUPT_FILE)
                        LyricsDisplayWriteResult.Failed(lastKnown(), LyricsDisplayIssue.CORRUPT_FILE)
                    } catch (io: IOException) {
                        writeFailure = io
                        // A failed acknowledgment can follow a commit or an uncommitted cache update.
                        readAfterWriteFailure(intended)
                    } catch (memory: OutOfMemoryError) {
                        writeFailure = memory
                        publishUnavailable(LyricsDisplayIssue.LOW_MEMORY)
                        LyricsDisplayWriteResult.Failed(lastKnown(), LyricsDisplayIssue.LOW_MEMORY)
                    }
                }
            }
            try { caller.ensureActive() }
            catch (cancelled: CancellationException) {
                val failure = attempt.exceptionOrNull()?.takeUnless { it is CancellationException }
                    ?: writeFailure?.takeUnless { it is CancellationException }
                if (failure != null && failure !== cancelled) cancelled.addSuppressed(failure)
                throw cancelled
            }
            return attempt.getOrThrow()
        } finally {
            occupied.set(false)
        }
    }

    override suspend fun reset(
        expectedRevision: Long,
        confirmation: LyricsDisplayResetConfirmation,
    ): LyricsDisplayWriteResult {
        if (confirmation == LyricsDisplayResetConfirmation.CANCELLED) {
            return LyricsDisplayWriteResult.Cancelled(lastKnown())
        }
        if (expectedRevision < 0L) return LyricsDisplayWriteResult.Failed(lastKnown(), LyricsDisplayIssue.INVALID_REVISION)
        return save(expectedRevision, LyricsDisplayPreferences(revision = expectedRevision))
    }

    override fun retryRead() {
        synchronized(publicationLock) { pendingReadRetry?.complete(Unit) }
    }

    private suspend fun readActualPreferences(): Preferences {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        // An identity transform rereads the file under DataStore's coordinator lock. It writes
        // nothing and does not repair a poisoned cache, so every confirmation uses this path.
        val attempt = withContext(NonCancellable) {
            runCatching {
                dataStore.updateData { stored ->
                    caller.ensureActive()
                    stored
                }
            }
        }
        try { caller.ensureActive() }
        catch (cancelled: CancellationException) {
            attempt.exceptionOrNull()?.takeUnless { it is CancellationException }?.let { failure ->
                if (failure !== cancelled) cancelled.addSuppressed(failure)
            }
            throw cancelled
        }
        return attempt.getOrThrow()
    }

    private suspend fun readAfterWriteFailure(intended: LyricsDisplayPreferences?): LyricsDisplayWriteResult = try {
        val actual = LyricsDisplayCodec.decode(readActualPreferences())
        publishAuthoritative(actual)
        val current = actual.preferences
        when {
            current == null -> LyricsDisplayWriteResult.Failed(lastKnown(), checkNotNull(actual.issue))
            intended != null && actual.persisted && current == intended -> LyricsDisplayWriteResult.Saved(current)
            else -> LyricsDisplayWriteResult.Failed(current, LyricsDisplayIssue.IO)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: CorruptionException) {
        publishUnavailable(LyricsDisplayIssue.CORRUPT_FILE)
        LyricsDisplayWriteResult.Failed(lastKnown(), LyricsDisplayIssue.CORRUPT_FILE)
    } catch (_: IOException) {
        publishUnavailable(LyricsDisplayIssue.IO)
        LyricsDisplayWriteResult.Failed(lastKnown(), LyricsDisplayIssue.IO)
    } catch (_: OutOfMemoryError) {
        publishUnavailable(LyricsDisplayIssue.LOW_MEMORY)
        LyricsDisplayWriteResult.Failed(lastKnown(), LyricsDisplayIssue.LOW_MEMORY)
    }

    private suspend fun awaitReadRetry(generation: Any, issue: LyricsDisplayIssue) {
        val retry = synchronized(publicationLock) {
            // An older collector error cannot replace a newer verified read or atomic save result.
            if (generation !== readGeneration) null else CompletableDeferred<Unit>().also {
                pendingReadRetry = it
                mutableState.value = LyricsDisplayState.Unavailable(lastKnown(), issue)
            }
        } ?: return
        try {
            retry.await()
        } finally {
            synchronized(publicationLock) {
                if (pendingReadRetry === retry) pendingReadRetry = null
            }
        }
    }

    private fun publishVerifiedRead(decoded: DecodedLyricsDisplay, generation: Any) {
        synchronized(publicationLock) {
            if (generation === readGeneration) publishDecodedLocked(decoded)
        }
    }

    private fun publishAuthoritative(decoded: DecodedLyricsDisplay) {
        synchronized(publicationLock) { publishAuthoritativeLocked(decoded) }
    }

    private fun publishAuthoritativeLocked(decoded: DecodedLyricsDisplay) {
        if (!publishDecodedLocked(decoded)) return
        readGeneration = Any()
        // A verified explicit operation also resolves the old failed read, without banking a retry credit.
        pendingReadRetry?.complete(Unit)
    }

    private fun publishDecodedLocked(decoded: DecodedLyricsDisplay): Boolean {
        val current = decoded.preferences
        val known = lastKnown()
        if (current != null && known != null && current.revision < known.revision) return false
        mutableState.value = if (current == null) {
            LyricsDisplayState.Unavailable(known, checkNotNull(decoded.issue))
        } else {
            LyricsDisplayState.Ready(current, decoded.persisted)
        }
        return true
    }

    private fun publishUnavailable(issue: LyricsDisplayIssue) {
        synchronized(publicationLock) { mutableState.value = LyricsDisplayState.Unavailable(lastKnown(), issue) }
    }

    private fun lastKnown(): LyricsDisplayPreferences? = when (val current = mutableState.value) {
        is LyricsDisplayState.Ready -> current.preferences
        is LyricsDisplayState.Unavailable -> current.lastKnown
        LyricsDisplayState.Loading -> null
    }

    private class ConflictingWrite(val current: LyricsDisplayPreferences) : Exception()
    private class RejectedWrite(val issue: LyricsDisplayIssue) : Exception()
}
