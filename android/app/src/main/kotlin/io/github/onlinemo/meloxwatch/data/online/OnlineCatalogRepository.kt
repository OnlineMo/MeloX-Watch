package io.github.onlinemo.meloxwatch.data.online

import io.github.onlinemo.meloxwatch.data.auth.AccountRequestToken
import io.github.onlinemo.meloxwatch.data.auth.AuthException
import io.github.onlinemo.meloxwatch.data.auth.AuthIssue
import io.github.onlinemo.meloxwatch.data.auth.SessionCredential
import io.github.onlinemo.meloxwatch.data.auth.SessionRepository
import io.github.onlinemo.meloxwatch.data.auth.SessionValidity
import io.github.onlinemo.meloxwatch.data.netease.NativeCatalogEntry
import io.github.onlinemo.meloxwatch.data.netease.NeteaseAuthApi
import io.github.onlinemo.meloxwatch.data.netease.NeteaseCatalogApi
import io.github.onlinemo.meloxwatch.data.online.cache.OnlineCatalogRowEntity
import io.github.onlinemo.meloxwatch.data.online.cache.OnlineCatalogViewEntity
import io.github.onlinemo.meloxwatch.data.online.cache.RoomOnlineCatalogCache
import io.github.onlinemo.meloxwatch.data.online.cache.entry
import io.github.onlinemo.meloxwatch.data.online.cache.snapshot
import io.github.onlinemo.meloxwatch.data.queue.QueueInputPage
import io.github.onlinemo.meloxwatch.data.queue.QueuePageSource
import io.github.onlinemo.meloxwatch.model.FrozenQueueView
import io.github.onlinemo.meloxwatch.model.OnlineCatalogEntry
import io.github.onlinemo.meloxwatch.model.OnlineCatalogEntryKind
import io.github.onlinemo.meloxwatch.model.OnlineCatalogException
import io.github.onlinemo.meloxwatch.model.OnlineCatalogIssue
import io.github.onlinemo.meloxwatch.model.OnlineCatalogKind
import io.github.onlinemo.meloxwatch.model.OnlineCatalogProgress
import io.github.onlinemo.meloxwatch.model.OnlineCatalogQuery
import io.github.onlinemo.meloxwatch.model.OnlineCatalogSnapshot
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.TrackKey
import io.github.onlinemo.meloxwatch.model.onlineOpaqueHash
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** One bounded catalog operation. Whole views live in rows; UI and queue readers use 100-row pages. */
internal class OnlineCatalogRepository(
    private val sessions: SessionRepository,
    private val login: NeteaseAuthApi,
    private val api: NeteaseCatalogApi,
    private val cache: RoomOnlineCatalogCache,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val registerArtwork: (NativeCatalogEntry, AccountRequestToken) -> String? = { _, _ -> null },
) {
    private val operations = Mutex()
    private val memory = Any()
    private val leasedViews = mutableSetOf<String>()

    suspend fun load(query: OnlineCatalogQuery, refresh: Boolean = false,
        progress: (OnlineCatalogProgress) -> Unit = {}): OnlineCatalogSnapshot = operation {
        if (!refresh) {
            val retained = cachedAccount { owner -> cache.dao.cached(owner, query.cacheKey) }
            if (retained != null) return@operation retained.snapshot()
        }
        account { credential, token ->
            val owner = owner(token)
            val previous = cache.dao.cached(owner, query.cacheKey)
            if (!refresh && previous != null) return@account previous.snapshot()
            var stage = cache.begin(owner, query.cacheKey, query.kind, nowEpochMs(), protectedViews() + listOfNotNull(previous?.id))
            var published = false
            try {
                collectWithinDeadline {
                    stage = initial(stage, query, credential, token, protectedViews() + listOfNotNull(previous?.id), progress)
                    // A refresh stages every page. Failure/cancellation preserves the old complete content and timestamp.
                    if (previous != null) {
                        while (!stage.complete) stage = step(stage, query, credential, token,
                            protectedViews() + previous.id, progress)
                    }
                    stage = publish(stage, token, protectedViews() + listOfNotNull(previous?.id))
                }
                currentCoroutineContext().ensureActive()
                published = true
                stage.snapshot()
            } finally {
                if (!published) withContext(NonCancellable) { cache.remove(stage.id) }
            }
        }
    }

    suspend fun next(snapshot: OnlineCatalogSnapshot, query: OnlineCatalogQuery,
        progress: (OnlineCatalogProgress) -> Unit = {}, first: Long? = null): OnlineCatalogSnapshot = operation {
        account { credential, token ->
            val view = requireView(snapshot, query, token)
            if (first != null && !view.indexed) throw OnlineCatalogException(OnlineCatalogIssue.STALE_VIEW)
            when {
                view.complete && first == null -> view
                view.indexed -> metadata(view, credential, token, protectedViews(), progress, first ?: view.metadataCursor)
                else -> appendPage(view, query, credential, token, protectedViews(), progress)
            }.snapshot()
        }
    }

    suspend fun finish(snapshot: OnlineCatalogSnapshot, query: OnlineCatalogQuery,
        progress: (OnlineCatalogProgress) -> Unit = {}): OnlineCatalogSnapshot = operation {
        account { credential, token ->
            var view = requireView(snapshot, query, token)
            // A published index already contains every ID. Queue preparation needs those rows, not every title.
            if (view.indexed) return@account view.snapshot()
            collectWithinDeadline {
                while (!view.complete) {
                    currentCoroutineContext().ensureActive()
                    view = step(view, query, credential, token, protectedViews(), progress)
                }
            }
            view.snapshot()
        }
    }

    suspend fun page(snapshot: OnlineCatalogSnapshot, first: Long): List<OnlineCatalogEntry> = cachedAccount { owner ->
        if (first < 0L || first > snapshot.count) throw OnlineCatalogException(OnlineCatalogIssue.STALE_VIEW)
        val view = cache.dao.view(snapshot.id) ?: throw OnlineCatalogException(OnlineCatalogIssue.STALE_VIEW)
        if (view.ownerHash != owner || view.revision != snapshot.revision || !view.published) {
            throw OnlineCatalogException(OnlineCatalogIssue.STALE_VIEW)
        }
        val result = cache.dao.rows(view.id, first, PAGE_SIZE).map { it.entry() }
        currentCoroutineContext().ensureActive()
        if (cache.dao.view(view.id) != view) throw OnlineCatalogException(OnlineCatalogIssue.STALE_VIEW)
        result
    }

    suspend fun freeze(snapshot: OnlineCatalogSnapshot): OnlineCatalogQueueLease {
        var pending: OnlineCatalogQueueLease? = null
        try {
            return operation {
                account { _, token ->
                    val view = cache.dao.view(snapshot.id) ?: throw OnlineCatalogException(OnlineCatalogIssue.STALE_VIEW)
                    if (view.ownerHash != owner(token) || view.revision != snapshot.revision || !view.published ||
                        view.count != snapshot.count || view.kind != snapshot.kind.name ||
                        (!view.complete && !view.indexed) || view.count <= 0L || OnlineCatalogKind.valueOf(view.kind) == OnlineCatalogKind.PLAYLISTS) {
                        throw OnlineCatalogException(OnlineCatalogIssue.STALE_VIEW)
                    }
                    synchronized(memory) {
                        if (leasedViews.isNotEmpty()) throw OnlineCatalogException(OnlineCatalogIssue.BUSY)
                        leasedViews.add(view.id)
                    }
                    OnlineCatalogQueueLease(FrozenQueueView(view.id, view.revision, view.count), token.account.accountId,
                        accountCurrent = { sessions.isCurrent(token) }, readRows = { first, limit ->
                            if (cache.dao.view(view.id) != view) throw OnlineCatalogException(OnlineCatalogIssue.STALE_VIEW)
                            cache.dao.rows(view.id, first, limit)
                        }, release = { synchronized(memory) { leasedViews.remove(view.id) } })
                        .also { pending = it }
                }
            }.also { pending = null }
        } finally { pending?.close() }
    }

    suspend fun current(snapshotId: String): OnlineCatalogSnapshot = cachedAccount { owner ->
        val view = cache.dao.view(snapshotId) ?: throw OnlineCatalogException(OnlineCatalogIssue.STALE_VIEW)
        if (view.ownerHash != owner || !view.published) throw OnlineCatalogException(OnlineCatalogIssue.STALE_VIEW)
        view.snapshot()
    }

    suspend fun presentation(accountId: String, trackId: String): OnlineCatalogEntry? {
        val snapshot = sessions.snapshot.value
        val account = snapshot.account ?: return null
        if (snapshot.validity != SessionValidity.VERIFIED || account.providerId != "netease" || account.accountId != accountId) return null
        val hash = onlineOpaqueHash(account.providerId + ":" + account.accountId)
        val row = cache.dao.track(hash, trackId)
        currentCoroutineContext().ensureActive()
        val current = sessions.snapshot.value
        return row?.takeIf { current.validity == SessionValidity.VERIFIED && current.generation == snapshot.generation && current.account == account }?.entry()
    }

    /** Visible artwork may rebind an evicted in-memory URL without editing a frozen catalog or its queue rows. */
    suspend fun artworkKey(trackId: String): String? = account { credential, token ->
        api.details(listOf(trackId), credential).singleOrNull()?.let { registerArtwork(it, token) }
    }

    private suspend fun initial(view: OnlineCatalogViewEntity, query: OnlineCatalogQuery, credential: SessionCredential,
        token: AccountRequestToken, preserve: Set<String>, progress: (OnlineCatalogProgress) -> Unit): OnlineCatalogViewEntity {
        if (query.kind == OnlineCatalogKind.SEARCH || query.kind == OnlineCatalogKind.PLAYLISTS) {
            return appendPage(view, query, credential, token, preserve, progress)
        }
        val index = api.index(query, token.account.accountId, credential)
        var current = view
        if (query.kind == OnlineCatalogKind.DAILY) {
            var first = 0
            while (first < index.seed.size) {
                currentCoroutineContext().ensureActive()
                val values = index.seed.subList(first, minOf(index.seed.size, first + PAGE_SIZE))
                val seen = cache.dao.existingIds(current.id, values.map { it.entry.id }).toMutableSet()
                val selected = values.filter { seen.add(it.entry.id) }
                val rows = selected.mapIndexed { position, value -> row(current.id, current.count + position, value, token) }
                current = update(current, current.copy(count = current.count + rows.size,
                    metadataCursor = current.count + rows.size, rawOffset = (first + values.size).toLong(),
                    reportedTotal = index.reportedTotal, indexed = true, complete = first + values.size >= index.seed.size,
                    usedBytes = current.usedBytes + rows.sumOf { it.estimatedBytes() }), rows, token, preserve)
                progress(OnlineCatalogProgress(current.rawOffset, index.reportedTotal, false))
                first += values.size
            }
            return if (index.seed.isEmpty()) update(current, current.copy(indexed = true, reportedTotal = 0L,
                complete = true), emptyList(), token, preserve) else current
        }
        val seeds = index.seed.take(PAGE_SIZE).associateBy { it.entry.id }
        var offset = 0
        while (offset < index.ids.size) {
            currentCoroutineContext().ensureActive()
            val ids = index.ids.subList(offset, minOf(index.ids.size, offset + PAGE_SIZE))
            val deduplicate = query.kind != OnlineCatalogKind.PLAYLIST
            val existing = if (deduplicate) cache.dao.existingIds(current.id, ids).toSet() else emptySet()
            val seen = existing.toMutableSet()
            val selected = ids.filter { !deduplicate || seen.add(it) }
            val rows = selected.mapIndexed { position, id ->
                seeds[id]?.let { row(current.id, current.count + position, it, token) }
                    ?: OnlineCatalogRowEntity(current.id, current.count + position, OnlineCatalogEntryKind.TRACK.name, id, metadataMissing = true)
            }
            current = update(current, current.copy(count = current.count + rows.size, rawOffset = (offset + ids.size).toLong(),
                reportedTotal = index.reportedTotal, indexed = true,
                usedBytes = current.usedBytes + rows.sumOf { it.estimatedBytes() }), rows, token, preserve)
            progress(OnlineCatalogProgress(current.rawOffset, index.reportedTotal, false))
            offset += ids.size
        }
        if (index.ids.isEmpty()) current = update(current, current.copy(indexed = true, reportedTotal = 0L, complete = true), emptyList(), token, preserve)
        return if (current.complete) current else metadata(current, credential, token, preserve, progress)
    }

    private suspend fun step(view: OnlineCatalogViewEntity, query: OnlineCatalogQuery, credential: SessionCredential,
        token: AccountRequestToken, preserve: Set<String>, progress: (OnlineCatalogProgress) -> Unit): OnlineCatalogViewEntity =
        if (view.indexed) metadata(view, credential, token, preserve, progress) else appendPage(view, query, credential, token, preserve, progress)

    private suspend fun appendPage(view: OnlineCatalogViewEntity, query: OnlineCatalogQuery, credential: SessionCredential,
        token: AccountRequestToken, preserve: Set<String>, progress: (OnlineCatalogProgress) -> Unit): OnlineCatalogViewEntity {
        val page = api.page(query, token.account.accountId, view.rawOffset, credential)
        if (view.reportedTotal != null && page.reportedTotal != null && page.reportedTotal != view.reportedTotal) {
            throw OnlineCatalogException(OnlineCatalogIssue.STALE_VIEW)
        }
        val seen = cache.dao.existingIds(view.id, page.entries.map { it.entry.id }).toMutableSet()
        val selected = page.entries.filter { seen.add(it.entry.id) }
        if (page.more && selected.isEmpty()) throw OnlineCatalogException(OnlineCatalogIssue.STALE_VIEW)
        val rows = selected.mapIndexed { position, value -> row(view.id, view.count + position, value, token) }
        val next = update(view, view.copy(count = view.count + rows.size, rawOffset = view.rawOffset + page.rawCount,
            reportedTotal = page.reportedTotal ?: view.reportedTotal, complete = !page.more,
            metadataCursor = view.count + rows.size, usedBytes = view.usedBytes + rows.sumOf { it.estimatedBytes() }), rows, token, preserve)
        progress(OnlineCatalogProgress(next.rawOffset, next.reportedTotal, false))
        return next
    }

    private suspend fun metadata(view: OnlineCatalogViewEntity, credential: SessionCredential, token: AccountRequestToken,
        preserve: Set<String>, progress: (OnlineCatalogProgress) -> Unit,
        first: Long = view.metadataCursor): OnlineCatalogViewEntity {
        if (first < 0L || first > view.count) throw OnlineCatalogException(OnlineCatalogIssue.STALE_VIEW)
        if (first == view.count) {
            return if (view.complete || view.metadataCursor < view.count) view
                else update(view, view.copy(complete = true), emptyList(), token, preserve)
        }
        requireMutable(view)
        val originals = cache.dao.rows(view.id, first, PAGE_SIZE)
        if (originals.size != minOf(PAGE_SIZE.toLong(), view.count - first).toInt() ||
            originals.withIndex().any { (index, row) -> row.position != first + index }) {
            throw OnlineCatalogException(OnlineCatalogIssue.INCOMPLETE)
        }
        val missing = originals.filter { it.metadataMissing }.map { it.itemId }.distinct()
        val details = if (missing.isEmpty()) emptyMap() else api.details(missing, credential).associateBy { it.entry.id }
        val rows = originals.map { original -> details[original.itemId]?.let { row(view.id, original.position, it, token) } ?: original }
        // An offline reader may jump ahead. Hydrating its visible page does not mark the skipped prefix complete.
        val cursor = if (first <= view.metadataCursor) maxOf(view.metadataCursor, first + rows.size) else view.metadataCursor
        val oldMissing = originals.count { it.position < view.metadataCursor && it.metadataMissing }
        val newMissing = rows.count { it.position < cursor && it.metadataMissing }
        val missingCount = view.missingMetadataCount + newMissing - oldMissing
        if (missingCount < 0L) throw OnlineCatalogException(OnlineCatalogIssue.INCOMPLETE)
        val next = update(view, view.copy(metadataCursor = cursor, complete = view.complete || cursor >= view.count,
            missingMetadataCount = missingCount,
            usedBytes = view.usedBytes + rows.sumOf { it.estimatedBytes() } - originals.sumOf { it.estimatedBytes() }), rows, token, preserve)
        progress(OnlineCatalogProgress(cursor, next.count, true))
        return next
    }

    private suspend fun publish(view: OnlineCatalogViewEntity, token: AccountRequestToken, preserve: Set<String>) =
        update(view, view.copy(published = true), emptyList(), token, preserve)

    private suspend fun update(view: OnlineCatalogViewEntity, next: OnlineCatalogViewEntity, rows: List<OnlineCatalogRowEntity>,
        token: AccountRequestToken, preserve: Set<String>): OnlineCatalogViewEntity {
        requireMutable(view)
        if (view.revision == Long.MAX_VALUE) throw OnlineCatalogException(OnlineCatalogIssue.CLOSED)
        return cache.apply(view, next.copy(revision = view.revision + 1L), rows, preserve + view.id) { sessions.isCurrent(token) }
    }

    private fun row(viewId: String, position: Long, native: NativeCatalogEntry, token: AccountRequestToken): OnlineCatalogRowEntity {
        val value = native.entry
        return OnlineCatalogRowEntity(viewId, position, value.kind.name, value.id, value.title, value.artist, value.album,
            value.durationMs, value.trackCount, registerArtwork(native, token), value.metadataMissing)
    }

    private suspend fun requireView(snapshot: OnlineCatalogSnapshot, query: OnlineCatalogQuery, token: AccountRequestToken): OnlineCatalogViewEntity {
        val view = cache.dao.view(snapshot.id) ?: throw OnlineCatalogException(OnlineCatalogIssue.STALE_VIEW)
        if (view.ownerHash != owner(token) || view.queryKey != query.cacheKey || view.revision != snapshot.revision ||
            view.count != snapshot.count || view.kind != snapshot.kind.name || !view.published) {
            throw OnlineCatalogException(OnlineCatalogIssue.STALE_VIEW)
        }
        return view
    }

    /** A queue reader keeps its exact published row revision until its physical preparation retires. */
    private fun requireMutable(view: OnlineCatalogViewEntity) {
        if (view.published && synchronized(memory) { view.id in leasedViews }) {
            throw OnlineCatalogException(OnlineCatalogIssue.BUSY)
        }
    }

    private fun protectedViews(): Set<String> = synchronized(memory) { leasedViews.toSet() }
    private fun owner(token: AccountRequestToken) = onlineOpaqueHash(token.account.providerId + ":" + token.account.accountId)

    /** Restored identity permits local cache reads only; fetches and playback still require authenticated(). */
    private suspend fun <T> cachedAccount(action: suspend (String) -> T): T {
        try {
            val restored = sessions.restore()
            if (restored.validity == SessionValidity.UNAVAILABLE) throw AuthException(restored.issue ?: AuthIssue.KEY_UNAVAILABLE)
            val account = restored.account ?: throw AuthException(AuthIssue.LOGIN_REQUIRED)
            if (account.providerId != "netease" || restored.validity !in setOf(SessionValidity.VERIFIED, SessionValidity.RESTORED_UNVERIFIED)) {
                throw AuthException(AuthIssue.LOGIN_REQUIRED)
            }
            val result = action(onlineOpaqueHash(account.providerId + ":" + account.accountId))
            currentCoroutineContext().ensureActive()
            val current = sessions.snapshot.value
            if (current.generation != restored.generation || current.account != account ||
                current.validity !in setOf(SessionValidity.VERIFIED, SessionValidity.RESTORED_UNVERIFIED)) {
                throw CancellationException("Online cached account changed")
            }
            return result
        } catch (error: AuthException) { throw OnlineCatalogException(authCatalogIssue(error.issue)) }
        catch (_: android.database.sqlite.SQLiteFullException) { throw OnlineCatalogException(OnlineCatalogIssue.STORAGE_FULL) }
        catch (_: android.database.sqlite.SQLiteException) { throw OnlineCatalogException(OnlineCatalogIssue.STORAGE_UNAVAILABLE) }
    }

    private suspend fun <T> account(action: suspend (SessionCredential, AccountRequestToken) -> T): T {
        try {
            val restored = sessions.restore()
            if (restored.account?.providerId != null && restored.account.providerId != "netease") throw AuthException(AuthIssue.LOGIN_REQUIRED)
            if (restored.validity == SessionValidity.RESTORED_UNVERIFIED) sessions.revalidate(login)
            if (restored.validity == SessionValidity.UNAVAILABLE) throw AuthException(restored.issue ?: AuthIssue.KEY_UNAVAILABLE)
            return sessions.authenticated(action)
        } catch (error: AuthException) { throw OnlineCatalogException(authCatalogIssue(error.issue)) }
        catch (_: android.database.sqlite.SQLiteFullException) { throw OnlineCatalogException(OnlineCatalogIssue.STORAGE_FULL) }
        catch (_: android.database.sqlite.SQLiteException) { throw OnlineCatalogException(OnlineCatalogIssue.STORAGE_UNAVAILABLE) }
    }

    private suspend fun <T> operation(action: suspend () -> T): T {
        if (!operations.tryLock()) throw OnlineCatalogException(OnlineCatalogIssue.BUSY)
        try { return action() } finally { operations.unlock() }
    }
    private suspend fun collectWithinDeadline(action: suspend () -> Unit) {
        val completed = withTimeoutOrNull(COLLECTION_MS) {
            action()
            true
        }
        if (completed != true) throw OnlineCatalogException(OnlineCatalogIssue.TIMEOUT)
    }
    companion object {
        const val PAGE_SIZE = 100
        const val COLLECTION_MS = 120_000L
    }
}

/** A frozen complete view, account token and at most one page; this reader cannot send an HTTP request. */
internal class OnlineCatalogQueueLease(
    val view: FrozenQueueView,
    private val accountId: String,
    private val accountCurrent: () -> Boolean,
    private val readRows: suspend (Long, Int) -> List<OnlineCatalogRowEntity>,
    private val release: () -> Unit,
) : QueuePageSource, AutoCloseable {
    private val closed = AtomicBoolean()
    private val cancelled = AtomicBoolean()
    fun checkCurrent() {
        if (cancelled.get()) throw CancellationException("Online queue preparation cancelled")
        if (closed.get()) throw OnlineCatalogException(OnlineCatalogIssue.STALE_VIEW)
        if (!accountCurrent()) throw OnlineCatalogException(OnlineCatalogIssue.LOGIN_REQUIRED)
    }
    fun cancel() { cancelled.set(true) }
    override suspend fun read(offset: Long, limit: Int): QueueInputPage = withContext(Dispatchers.IO) {
        require(offset >= 0L && offset <= view.totalCount && limit in 1..100)
        currentCoroutineContext().ensureActive()
        checkCurrent()
        val rows = readRows(offset, limit)
        if (rows.size != minOf(limit.toLong(), view.totalCount - offset).toInt() ||
            rows.withIndex().any { (index, row) -> row.position != offset + index }) {
            throw OnlineCatalogException(OnlineCatalogIssue.INCOMPLETE)
        }
        val entries = rows.map { row -> QueueEntry(UUID.nameUUIDFromBytes((view.id + ":" + row.position).toByteArray(Charsets.UTF_8)).toString(),
            TrackKey("netease", row.itemId), row.position, row.position, row.title, row.durationMs, accountId = accountId) }
        currentCoroutineContext().ensureActive()
        checkCurrent()
        QueueInputPage(view, offset, entries)
    }
    override fun close() { if (closed.compareAndSet(false, true)) release() }
}

internal fun authCatalogIssue(issue: AuthIssue): OnlineCatalogIssue = when (issue) {
    AuthIssue.NETWORK_UNAVAILABLE -> OnlineCatalogIssue.OFFLINE
    AuthIssue.TIMEOUT -> OnlineCatalogIssue.TIMEOUT
    AuthIssue.CERTIFICATE -> OnlineCatalogIssue.CERTIFICATE
    AuthIssue.RATE_LIMITED -> OnlineCatalogIssue.RATE_LIMITED
    AuthIssue.LOGIN_REQUIRED -> OnlineCatalogIssue.LOGIN_REQUIRED
    AuthIssue.BUSY -> OnlineCatalogIssue.BUSY
    AuthIssue.CLOSED -> OnlineCatalogIssue.CLOSED
    AuthIssue.INVALID_RESPONSE -> OnlineCatalogIssue.INVALID_RESPONSE
    AuthIssue.KEY_UNAVAILABLE, AuthIssue.SESSION_CORRUPT, AuthIssue.STORAGE_UNAVAILABLE -> OnlineCatalogIssue.SESSION_UNAVAILABLE
}
