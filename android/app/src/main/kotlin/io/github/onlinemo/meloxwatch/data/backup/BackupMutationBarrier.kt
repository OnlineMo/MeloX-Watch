package io.github.onlinemo.meloxwatch.data.backup

import android.database.sqlite.SQLiteDatabaseLockedException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

enum class BackupAccessState { READY, EXCLUSIVE_OPERATION, RECOVERY_REQUIRED }

/**
 * One application-owned admission barrier surrounds actual writes, including their durable ack.
 * The restore coroutine alone may write while it holds an exclusive generation. Read notifications
 * keep their old visible value until that generation has committed or recovered coherently.
 */
class BackupMutationBarrier {
    private val lock = Any()
    private var writers = 0
    private var exclusive: Any? = null
    // Even a fresh process must inspect the durable restore journal before admitting writers.
    private var recoveryRequired = true
    private var initialized = false
    private data class Publication(val action: () -> Unit, val confirmed: Boolean)
    private val deferred = LinkedHashMap<Any, Publication>()
    private val mutableState = MutableStateFlow(BackupAccessState.RECOVERY_REQUIRED)
    val state = mutableState.asStateFlow()

    /** Only the real startup owner checks/replays the journal and reads the native stores. */
    internal suspend fun initializeStorage(recoverPending: suspend () -> Unit) {
        if (synchronized(lock) { initialized && !recoveryRequired }) return
        exclusively(recovery = true) {
            recoverPending()
            recovered()
        }
        synchronized(lock) { initialized = true }
    }

    /** LP-10 holds its own shared original-mutation guard after this nonholding startup check. */
    fun requireReadyForFileMutation() = synchronized(lock) {
        if (recoveryRequired) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        if (exclusive != null) throw BackupException(BackupIssue.BUSY)
    }

    internal suspend fun requireExclusiveOwner() {
        val ticket = currentCoroutineContext()[Ticket]
        synchronized(lock) {
            if (exclusive == null || ticket == null || ticket.barrier !== this || ticket.owner !== exclusive) {
                throw BackupException(BackupIssue.BUSY)
            }
        }
    }

    /** Already admitted transfer IO still needs real worker admission before changing owned files. */
    internal fun <T> workerMutation(onBusy: () -> Throwable, action: () -> T): T {
        synchronized(lock) {
            if (exclusive != null || recoveryRequired) throw onBusy()
            writers++
        }
        try { return action() }
        finally { synchronized(lock) { check(writers > 0); writers-- } }
    }

    suspend fun <T> mutation(onBusy: () -> Throwable = { SQLiteDatabaseLockedException("Backup operation owns storage") },
        action: suspend () -> T): T {
        val ticket = currentCoroutineContext()[Ticket]
        val ordinary = synchronized(lock) {
            if (exclusive != null && ticket != null && ticket.barrier === this && ticket.owner === exclusive) false
            else {
                if (exclusive != null || recoveryRequired) throw onBusy()
                writers++
                true
            }
        }
        try { return action() }
        finally { if (ordinary) synchronized(lock) { check(writers > 0); writers-- } }
    }

    /** Room observers wait outside their writer/mutex, then read the current coherent projection. */
    internal suspend fun <T> readProjection(action: suspend () -> T): T {
        val ticket = currentCoroutineContext()[Ticket]
        if (synchronized(lock) { exclusive != null && ticket != null && ticket.barrier === this && ticket.owner === exclusive }) return action()
        while (true) {
            state.first { it == BackupAccessState.READY }
            try { return mutation(onBusy = { ProjectionWaiting() }, action = action) }
            catch (_: ProjectionWaiting) { /* Only a new backup generation changes admission. */ }
        }
    }

    internal suspend fun <T> exclusively(recovery: Boolean = false, action: suspend () -> T): T {
        val owner = Any()
        synchronized(lock) {
            if (exclusive != null || writers != 0 || recoveryRequired && !recovery) throw BackupException(BackupIssue.BUSY)
            exclusive = owner
            mutableState.value = BackupAccessState.EXCLUSIVE_OPERATION
        }
        var failure: Throwable? = null
        try { return withContext(Ticket(this, owner)) { action() } }
        catch (error: Throwable) { failure = error; throw error }
        finally {
            var publishingFailure: Throwable? = null
            synchronized(lock) {
                check(exclusive === owner)
                try {
                    if (!recoveryRequired) {
                        val publications = deferred.entries.iterator()
                        // Admission remains exclusive until the coherent generation is visible.
                        while (publications.hasNext()) {
                            publications.next().value.action()
                            publications.remove()
                        }
                    }
                } catch (error: Throwable) {
                    // Keep the unreported native values for a real recovery/read retry.
                    recoveryRequired = true
                    publishingFailure = BackupException(BackupIssue.RECOVERY_REQUIRED, error)
                } finally {
                    exclusive = null
                    mutableState.value = if (recoveryRequired) BackupAccessState.RECOVERY_REQUIRED else BackupAccessState.READY
                }
            }
            publishingFailure?.let { reporting ->
                if (failure == null) throw reporting
                val outgoing = checkNotNull(failure)
                if (outgoing !== reporting) outgoing.addSuppressed(reporting)
            }
        }
    }

    /** false means the bounded owner's latest publication is deferred. */
    internal fun publish(owner: Any, action: () -> Unit, confirmed: Boolean = true, accept: () -> Boolean = { true }): Boolean {
        return synchronized(lock) {
            if (!accept()) return@synchronized false
            if (exclusive != null || recoveryRequired) {
                if (owner !in deferred && deferred.size >= 16) throw IllegalStateException("Too many backup publication owners")
                // A late observer error must not discard this generation's verified read/CAS result.
                if (confirmed || deferred[owner]?.confirmed != true) deferred[owner] = Publication(action, confirmed)
                false
            } else {
                action()
                true
            }
        }
    }

    internal fun requireRecovery() = synchronized(lock) {
        recoveryRequired = true
        if (exclusive == null) mutableState.value = BackupAccessState.RECOVERY_REQUIRED
    }

    internal suspend fun recovered() {
        val ticket = currentCoroutineContext()[Ticket]
        synchronized(lock) {
            if (exclusive == null || ticket == null || ticket.barrier !== this || ticket.owner !== exclusive) {
                throw BackupException(BackupIssue.RECOVERY_REQUIRED)
            }
            recoveryRequired = false
        }
    }

    private class Ticket(val barrier: BackupMutationBarrier, val owner: Any) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Ticket>
    }
    private class ProjectionWaiting : Exception()
}

/** Optional constructor hooks leave independent fixtures unbound; production binds every owner once. */
internal suspend fun <T> BackupMutationBarrier?.withBackupMutation(onBusy: () -> Throwable = {
    SQLiteDatabaseLockedException("Backup operation owns storage")
}, action: suspend () -> T): T = if (this == null) action() else mutation(onBusy, action)
internal fun BackupMutationBarrier?.publishForBackup(owner: Any, action: () -> Unit): Boolean =
    if (this == null) { action(); true } else publish(owner, action)
internal fun BackupMutationBarrier?.publishFailureForBackup(owner: Any, action: () -> Unit): Boolean =
    if (this == null) { action(); true } else publish(owner, action, confirmed = false)
internal fun <T> BackupMutationBarrier?.withBackupWorkerMutation(onBusy: () -> Throwable, action: () -> T): T =
    if (this == null) action() else workerMutation(onBusy, action)
internal fun BackupMutationBarrier?.publishIfCurrentForBackup(owner: Any, accept: () -> Boolean, confirmed: Boolean = true, action: () -> Unit): Boolean =
    if (this == null) { if (accept()) { action(); true } else false } else publish(owner, action, confirmed, accept)
internal suspend fun <T> BackupMutationBarrier?.readBackupProjection(action: suspend () -> T): T =
    if (this == null) action() else readProjection(action)
