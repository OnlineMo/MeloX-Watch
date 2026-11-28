package io.github.onlinemo.meloxwatch.data.online

import android.util.AtomicFile
import io.github.onlinemo.meloxwatch.data.auth.SessionRepository
import io.github.onlinemo.meloxwatch.data.auth.SessionSnapshot
import io.github.onlinemo.meloxwatch.data.auth.SessionValidity
import io.github.onlinemo.meloxwatch.model.OnlineCatalogKind
import io.github.onlinemo.meloxwatch.model.OnlineCatalogQuery
import io.github.onlinemo.meloxwatch.model.onlineOpaqueHash
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal enum class OnlineSearchHistoryIssue { READ_FAILED, WRITE_FAILED, CLEAR_FAILED, CORRUPT, BUSY, STALE_ACCOUNT }
internal class OnlineSearchHistoryException(val issue: OnlineSearchHistoryIssue,
    val recoverableView: OnlineSearchHistoryView? = null) : Exception(issue.name)

internal class OnlineSearchHistoryScope private constructor(val accountHash: String?, val generation: Long, val lineageId: String?) {
    fun matches(other: OnlineSearchHistoryScope): Boolean = sameLineage(other) && generation == other.generation
    fun sameLineage(other: OnlineSearchHistoryScope): Boolean = accountHash == other.accountHash && lineageId == other.lineageId
    override fun toString(): String = "OnlineSearchHistoryScope(redacted)"
    companion object {
        fun of(snapshot: SessionSnapshot): OnlineSearchHistoryScope {
            val account = snapshot.account?.takeIf {
                snapshot.validity == SessionValidity.VERIFIED || snapshot.validity == SessionValidity.RESTORED_UNVERIFIED
            }
            return OnlineSearchHistoryScope(account?.let { onlineOpaqueHash(it.providerId + ":" + it.accountId) },
                snapshot.generation, snapshot.lineageId.takeIf { account != null })
        }
    }
}

/** A view carries its account generation and store identity; equal words never authorize an old callback. */
internal class OnlineSearchHistoryView internal constructor(
    private val owner: Any,
    internal val scope: OnlineSearchHistoryScope,
    internal val revision: String?,
    queries: List<String>,
    val issue: OnlineSearchHistoryIssue? = null,
    internal val clearToken: Any? = null,
) {
    val queries: List<String> = Collections.unmodifiableList(queries.toList())
    internal fun belongsTo(expected: Any): Boolean = owner === expected
    override fun toString(): String = "OnlineSearchHistoryView(count=${queries.size}, issue=$issue, redacted)"
}
internal class OnlineSearchHistoryCleanup internal constructor(internal val snapshot: SessionSnapshot, val issue: OnlineSearchHistoryIssue) {
    override fun toString(): String = "OnlineSearchHistoryCleanup(issue=$issue, redacted)"
}

/** One private AtomicFile. The auth repository owns persisted lineage; history never reads credentials. */
internal class OnlineSearchHistory private constructor(
    private val file: File,
    private val sessions: SessionRepository,
    private val beforeAccountCleanup: () -> Unit,
    private val afterStartWrite: () -> Unit,
) {
    private val atomic = AtomicFile(file)
    private val viewOwner = Any()
    private val mutex = Mutex()
    private val callers = Semaphore(3)
    private val cleanupMemory = Any()
    private var cleanup: CleanupOwner? = null
    private val retirementMemory = Any()
    private var retiredLineage: String? = null
    // Only the physical file owner changes this epoch or its one retained failed clear.
    private var mutationEpoch = 0L
    private var retainedClear: ClearOwner? = null

    /** The explicit logout retires its current lineage before credential or lyric IO can suspend. */
    fun retireAccount(snapshot: SessionSnapshot) {
        val target = OnlineSearchHistoryScope.of(snapshot)
        if (target.lineageId != null && target.sameLineage(OnlineSearchHistoryScope.of(sessions.snapshot.value))) {
            synchronized(retirementMemory) { retiredLineage = target.lineageId }
        }
    }
    suspend fun read(snapshot: SessionSnapshot): OnlineSearchHistoryView {
        val scope = OnlineSearchHistoryScope.of(snapshot)
        return io(OnlineSearchHistoryIssue.READ_FAILED) {
            requireCurrent(scope)
            retainedClear?.takeIf { it.scope.sameLineage(scope) && it.epoch == mutationEpoch && matchesResiduals(it) }?.let {
                currentCoroutineContext().ensureActive()
                requireCurrent(scope)
                return@io retryView(scope, it)
            }
            val loaded = try { readDocument() } catch (error: OnlineSearchHistoryException) {
                if (error.issue != OnlineSearchHistoryIssue.CORRUPT) throw error
                requireCurrent(scope)
                return@io OnlineSearchHistoryView(viewOwner, scope, null, emptyList(), error.issue)
            }
            currentCoroutineContext().ensureActive()
            requireCurrent(scope)
            view(scope, loaded)
        }
    }
    /** Explicit search only; page entry, refresh and history selection never record a query. */
    suspend fun record(snapshot: SessionSnapshot, query: String): OnlineSearchHistoryView {
        OnlineCatalogQuery(OnlineCatalogKind.SEARCH, query)
        val scope = OnlineSearchHistoryScope.of(snapshot)
        if (scope.accountHash == null || scope.lineageId == null) throw OnlineSearchHistoryException(OnlineSearchHistoryIssue.STALE_ACCOUNT)
        return io(OnlineSearchHistoryIssue.WRITE_FAILED) {
            requireCurrent(scope)
            val previous = readDocument()?.document
            val words = listOf(query) + previous?.takeIf { activeDocument(scope, it) }?.queries.orEmpty().filter { it != query }
            val document = Document(scope.accountHash, scope.lineageId, words.take(MAX_QUERIES))
            val bytes = encode(document)
            val caller = currentCoroutineContext()
            caller.ensureActive()
            requireCurrent(scope)
            synchronized(retirementMemory) {
                if (scope.lineageId == retiredLineage) throw OnlineSearchHistoryException(OnlineSearchHistoryIssue.STALE_ACCOUNT)
            }
            // Any new write retires an old partial-clear capability, even when bytes repeat or writing later fails.
            nextMutation()
            withContext(NonCancellable) { write(bytes) }
            caller.ensureActive()
            requireCurrent(scope)
            view(scope, Loaded(document, fingerprint(bytes)))
        }
    }
    suspend fun clear(expected: OnlineSearchHistoryView): OnlineSearchHistoryView = io(OnlineSearchHistoryIssue.CLEAR_FAILED) {
        if (!expected.belongsTo(viewOwner)) throw OnlineSearchHistoryException(OnlineSearchHistoryIssue.STALE_ACCOUNT)
        requireCurrent(expected.scope)
        val retry = expected.clearToken?.let { token ->
            val owner = token as? ClearOwner ?: throw OnlineSearchHistoryException(OnlineSearchHistoryIssue.STALE_ACCOUNT)
            if (retainedClear !== owner || !owner.scope.sameLineage(expected.scope) || owner.epoch != mutationEpoch || !matchesResiduals(owner)) {
                throw OnlineSearchHistoryException(OnlineSearchHistoryIssue.STALE_ACCOUNT)
            }
            owner
        }
        if (retry == null) {
            if (expected.issue == OnlineSearchHistoryIssue.CORRUPT) {
                try { readDocument(); throw OnlineSearchHistoryException(OnlineSearchHistoryIssue.STALE_ACCOUNT) }
                catch (error: OnlineSearchHistoryException) { if (error.issue != OnlineSearchHistoryIssue.CORRUPT) throw error }
            } else {
                val loaded = readDocument()
                if (loaded?.revision != expected.revision || loaded?.document?.accountHash != expected.scope.accountHash ||
                    loaded?.document?.lineageId != expected.scope.lineageId) throw OnlineSearchHistoryException(OnlineSearchHistoryIssue.STALE_ACCOUNT)
            }
        }
        val caller = currentCoroutineContext()
        caller.ensureActive()
        requireCurrent(expected.scope)
        withContext(NonCancellable) { deleteOwned(expected.scope, expected.revision, expected.queries, retry) }
        caller.ensureActive()
        requireCurrent(expected.scope)
        OnlineSearchHistoryView(viewOwner, expected.scope, null, emptyList())
    }
    /** Coalescing is by persisted lineage; a later verification generation still belongs to the same old logout. */
    suspend fun clearAccount(snapshot: SessionSnapshot): OnlineSearchHistoryIssue? {
        val target = OnlineSearchHistoryScope.of(snapshot)
        if (target.accountHash == null || target.lineageId == null) return null
        retireAccount(snapshot)
        val (owner, leader) = synchronized(cleanupMemory) {
            val old = cleanup
            if (old != null) {
                if (!old.scope.sameLineage(target) || old.waiters == 2) return OnlineSearchHistoryIssue.BUSY
                old.waiters += 1
                old to false
            } else CleanupOwner(target).also { cleanup = it } to true
        }
        if (!leader) {
            try {
                val joined = withTimeoutOrNull(ADMISSION_MS) { CleanupResult(owner.finished.await()) }
                return if (joined == null) OnlineSearchHistoryIssue.BUSY else joined.issue
            } finally { synchronized(cleanupMemory) { owner.waiters -= 1 } }
        }
        return withContext(NonCancellable) {
            val issue = try {
                io(OnlineSearchHistoryIssue.CLEAR_FAILED) {
                    beforeAccountCleanup()
                    val retry = retainedClear?.takeIf { it.scope.sameLineage(target) && it.epoch == mutationEpoch && matchesResiduals(it) }
                    if (retry != null) deleteOwned(target, retry.revision, retry.queries, retry)
                    else {
                        val loaded = readDocument()
                        if (loaded != null && loaded.document.accountHash == target.accountHash && loaded.document.lineageId == target.lineageId) {
                            deleteOwned(target, loaded.revision, loaded.document.queries, null)
                        } else if (reservedFiles().drop(1).any(File::exists)) {
                            throw OnlineSearchHistoryException(OnlineSearchHistoryIssue.CLEAR_FAILED)
                        }
                    }
                }
                null
            } catch (error: OnlineSearchHistoryException) { error.issue }
            finally { synchronized(cleanupMemory) { if (cleanup === owner) cleanup = null } }
            owner.finished.complete(issue)
            issue
        }
    }
    private fun requireCurrent(scope: OnlineSearchHistoryScope) {
        if (!scope.matches(OnlineSearchHistoryScope.of(sessions.snapshot.value))) throw OnlineSearchHistoryException(OnlineSearchHistoryIssue.STALE_ACCOUNT)
    }
    private fun activeDocument(scope: OnlineSearchHistoryScope, document: Document): Boolean =
        scope.accountHash != null && scope.accountHash == document.accountHash && scope.lineageId == document.lineageId &&
            synchronized(retirementMemory) { scope.lineageId != retiredLineage }
    private fun view(scope: OnlineSearchHistoryScope, loaded: Loaded?): OnlineSearchHistoryView =
        OnlineSearchHistoryView(viewOwner, scope, loaded?.revision, loaded?.document?.takeIf { activeDocument(scope, it) }?.queries.orEmpty())
    private fun retryView(scope: OnlineSearchHistoryScope, owner: ClearOwner): OnlineSearchHistoryView =
        OnlineSearchHistoryView(viewOwner, scope, owner.revision, owner.queries, OnlineSearchHistoryIssue.CLEAR_FAILED, owner)
    private fun nextMutation() {
        if (mutationEpoch == Long.MAX_VALUE) throw OnlineSearchHistoryException(OnlineSearchHistoryIssue.BUSY)
        mutationEpoch += 1L
    }
    private fun deleteOwned(scope: OnlineSearchHistoryScope, revision: String?, queries: List<String>, retry: ClearOwner?) {
        val owner = retry ?: run {
            val facts = reservedFacts()
            nextMutation()
            ClearOwner(scope, revision, queries.toList(), mutationEpoch, facts).also { retainedClear = it }
        }
        try {
            deleteExactFiles()
            if (retainedClear === owner) retainedClear = null
        } catch (error: Exception) {
            // Remember the exact surviving names and identities; absent files must stay absent on retry.
            try { owner.residuals = reservedFacts() } catch (_: Exception) { }
            throw OnlineSearchHistoryException(OnlineSearchHistoryIssue.CLEAR_FAILED, retryView(scope, owner))
        }
    }
    private fun reservedFacts(): Map<File, FileFact> = reservedFiles().mapNotNull { own ->
        if (!Files.exists(own.toPath(), LinkOption.NOFOLLOW_LINKS)) null else {
            val attributes = Files.readAttributes(own.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (attributes.isSymbolicLink || !attributes.isDirectory && !attributes.isRegularFile) {
                throw OnlineSearchHistoryException(OnlineSearchHistoryIssue.CLEAR_FAILED)
            }
            val hash = if (attributes.isRegularFile && attributes.size() <= MAX_BYTES) own.inputStream().use { input ->
                fingerprint(boundedBytes(input))
            } else null
            own to FileFact(attributes.fileKey(), attributes.isDirectory, attributes.size(), attributes.lastModifiedTime().toMillis(), hash)
        }
    }.toMap()
    private fun matchesResiduals(owner: ClearOwner): Boolean {
        val current = reservedFacts()
        return current.all { (path, now) ->
            val old = owner.residuals[path] ?: return@all false
            old.key != null && old.key == now.key && old.directory == now.directory &&
                (old.directory || old.size == now.size && old.modifiedMs == now.modifiedMs && old.hash == now.hash)
        }
    }
    private suspend fun <T> io(failure: OnlineSearchHistoryIssue, action: suspend () -> T): T {
        if (!callers.tryAcquire()) throw OnlineSearchHistoryException(OnlineSearchHistoryIssue.BUSY)
        var locked = false
        try {
            if (withTimeoutOrNull(ADMISSION_MS) { mutex.lock(); locked = true; true } != true) throw OnlineSearchHistoryException(OnlineSearchHistoryIssue.BUSY)
            return withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                try { action() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: OnlineSearchHistoryException) { throw error }
                catch (_: OutOfMemoryError) { throw OnlineSearchHistoryException(failure) }
                catch (_: Exception) { throw OnlineSearchHistoryException(failure) }
            }
        } finally { if (locked) mutex.unlock(); callers.release() }
    }
    private fun readDocument(): Loaded? {
        val bytes = try { atomic.openRead().use(::boundedBytes) } catch (_: FileNotFoundException) {
            if (reservedFiles().any(File::exists)) throw OnlineSearchHistoryException(OnlineSearchHistoryIssue.CORRUPT)
            return null
        }
        val document = try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == MAGIC && input.readInt() == 1)
                val account = input.readUTF().also { require(it.matches(Regex("[0-9a-f]{64}"))) }
                val lineage = input.readUTF().also { require(it.length == 36 && UUID.fromString(it).toString() == it) }
                val count = input.readInt().also { require(it in 1..MAX_QUERIES) }
                val words = List(count) { input.readUTF().also { OnlineCatalogQuery(OnlineCatalogKind.SEARCH, it) } }
                require(words.distinct().size == words.size && input.read() == -1)
                Document(account, lineage, words)
            }
        } catch (_: IOException) { throw OnlineSearchHistoryException(OnlineSearchHistoryIssue.CORRUPT) }
        catch (_: IllegalArgumentException) { throw OnlineSearchHistoryException(OnlineSearchHistoryIssue.CORRUPT) }
        return Loaded(document, fingerprint(bytes))
    }
    private fun boundedBytes(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream(4096)
        val buffer = ByteArray(4096)
        while (true) {
            val read = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES - output.size() + 1))
            if (read < 0) break
            if (read == 0 || output.size() + read > MAX_BYTES) throw OnlineSearchHistoryException(OnlineSearchHistoryIssue.CORRUPT)
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
    private fun encode(document: Document): ByteArray = ByteArrayOutputStream().also { output ->
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC); data.writeInt(1)
            data.writeUTF(document.accountHash); data.writeUTF(document.lineageId)
            data.writeInt(document.queries.size); document.queries.forEach(data::writeUTF)
        }
    }.toByteArray().also { check(it.size <= MAX_BYTES) }
    private fun write(bytes: ByteArray) {
        val output = atomic.startWrite()
        var finishing = false
        try {
            afterStartWrite(); output.write(bytes); output.flush(); output.fd.sync()
            finishing = true
            atomic.finishWrite(output)
            if (readDocument()?.revision != fingerprint(bytes)) throw OnlineSearchHistoryException(OnlineSearchHistoryIssue.WRITE_FAILED)
        } catch (error: Throwable) { if (!finishing) atomic.failWrite(output); throw error }
        finally { output.close() }
    }
    private fun deleteExactFiles() {
        atomic.delete()
        for (own in reservedFiles()) if (own.exists() && !own.delete()) throw OnlineSearchHistoryException(OnlineSearchHistoryIssue.CLEAR_FAILED)
        if (reservedFiles().any(File::exists)) throw OnlineSearchHistoryException(OnlineSearchHistoryIssue.CLEAR_FAILED)
    }
    private fun reservedFiles(): List<File> = listOf(file, File(file.path + ".bak"), File(file.path + ".new"))
    private fun fingerprint(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private class Document(val accountHash: String, val lineageId: String, val queries: List<String>)
    private class Loaded(val document: Document, val revision: String)
    private class FileFact(val key: Any?, val directory: Boolean, val size: Long, val modifiedMs: Long, val hash: String?)
    private class ClearOwner(val scope: OnlineSearchHistoryScope, val revision: String?, val queries: List<String>, val epoch: Long,
        var residuals: Map<File, FileFact>)
    private class CleanupOwner(val scope: OnlineSearchHistoryScope) {
        val finished = CompletableDeferred<OnlineSearchHistoryIssue?>()
        var waiters = 0
    }
    private class CleanupResult(val issue: OnlineSearchHistoryIssue?)
    companion object {
        internal const val MAX_QUERIES = 8
        internal const val MAX_BYTES = 16 * 1024
        private const val ADMISSION_MS = 2_000L
        private const val MAGIC = 0x4d585348
        fun create(noBackupDirectory: File, sessions: SessionRepository): OnlineSearchHistory =
            OnlineSearchHistory(File(noBackupDirectory, "online-search-history.v1"), sessions, {}, {})
        internal fun controlled(file: File, sessions: SessionRepository, beforeAccountCleanup: () -> Unit = {},
            afterStartWrite: () -> Unit = {}): OnlineSearchHistory = OnlineSearchHistory(file, sessions, beforeAccountCleanup, afterStartWrite)
    }
}
