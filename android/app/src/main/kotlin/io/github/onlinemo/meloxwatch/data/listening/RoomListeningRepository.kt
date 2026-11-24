package io.github.onlinemo.meloxwatch.data.listening

import io.github.onlinemo.meloxwatch.data.library.LibraryException
import io.github.onlinemo.meloxwatch.data.library.LibraryIssue
import io.github.onlinemo.meloxwatch.data.library.LibraryMetaEntity
import io.github.onlinemo.meloxwatch.data.library.RoomLocalLibraryRepository
import io.github.onlinemo.meloxwatch.data.backup.readBackupProjection
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** Cumulative service acknowledgements, history and statistics share one local transaction owner. */
class RoomListeningRepository(private val library: RoomLocalLibraryRepository) : ListeningRepository {
    private val dao = library.database.listeningDao()
    private val epochObservation = Any()
    private var observationRevision = 0L
    private var pendingEpochChanges = 0
    private val mutableObservedEpochs = MutableStateFlow<ListeningEpochs?>(null)
    override val observedEpochs = mutableObservedEpochs.asStateFlow()

    override suspend fun state(): ListeningEpochs = access {
        val revision = synchronized(epochObservation) { observationRevision }
        val epochs = library.libraryTransaction { metadata().epochs() }
        synchronized(epochObservation) {
            if (revision == observationRevision && pendingEpochChanges == 0) mutableObservedEpochs.value = epochs
        }
        epochs
    }

    override suspend fun summary(): ListeningSummary = access {
        library.libraryTransaction {
            val current = metadata()
            ListeningSummary(current.epochs(), current.totalPlayCount, current.totalListeningMs,
                dao.historyCount(current.historyEpoch, current.historyFloorSequence))
        }
    }

    override suspend fun today(localDate: String, currentZoneId: String): ListeningTodaySummary = access {
        validateToday(localDate, currentZoneId)
        library.libraryTransaction {
            val current = metadata()
            val total = dao.today(current.statisticsEpoch, localDate)
            ListeningTodaySummary(localDate, currentZoneId, total.playCount, total.listeningMs, current.statisticsEpoch)
        }
    }

    override fun observeSummary(): Flow<ListeningSummary> = flow {
        library.database.backupMutationBarrier.readBackupProjection { Unit }
        emitAll(dao.observeMeta().map {
            library.database.backupMutationBarrier.readBackupProjection { summary() }
        })
    }.distinctUntilChanged()

    override suspend fun submit(update: ListeningRoundUpdate): ListeningCommit = access {
        validateEpochs(update.epochs)
        val commit = library.libraryTransaction(finalPublication = true) {
            val current = metadata()
            if (update.epochs.rulesVersion != current.rulesVersion || update.epochs.historyEpoch > current.historyEpoch ||
                update.epochs.statisticsEpoch > current.statisticsEpoch) fail(ListeningIssue.SOURCE_CHANGED)
            val stored = dao.round(update.roundId)
            stored?.let { validateRound(it, update) }
            if (stored != null && update.totalListeningMs < stored.totalListeningMs) return@libraryTransaction acknowledgement(current, stored, true)
            val before = stored?.totalListeningMs ?: 0L
            val delta = update.totalListeningMs - before
            val priorElapsed = stored?.sampledElapsedMs ?: update.firstProgressElapsedMs
            if (update.sampledElapsedMs < priorElapsed) {
                if (delta == 0L && stored != null) return@libraryTransaction acknowledgement(current, stored, true)
                fail(ListeningIssue.CONFLICT)
            }
            if (delta > update.sampledElapsedMs - priorElapsed) fail(ListeningIssue.INVALID_INPUT)
            val changes = segmentChanges(update, stored, delta)
            val duration = update.knownDurationMs ?: stored?.knownDurationMs
            val title = stored?.displayName ?: update.displayName
            if (stored != null && delta == 0L && duration == stored.knownDurationMs && title == stored.displayName) {
                return@libraryTransaction acknowledgement(current, stored, true)
            }
            val historyCurrent = update.epochs.historyEpoch == current.historyEpoch
            val statisticsCurrent = update.epochs.statisticsEpoch == current.statisticsEpoch
            var nextMeta = current
            val historyId = stored?.historyId ?: if (historyCurrent) current.nextHistorySequence else null
            if (stored == null && historyCurrent) {
                val nextSequence = increment(current.nextHistorySequence)
                nextMeta = nextMeta.copy(nextHistorySequence = nextSequence,
                    historyFloorSequence = maxOf(current.historyFloorSequence, nextSequence - ListeningLimits.HISTORY_ITEMS))
            }
            val threshold = threshold(duration)
            val countNow = statisticsCurrent && stored?.playCounted != true && update.totalListeningMs >= threshold
            val countedDay = if (countNow) countDay(update, changes, before, threshold) else null
            val round = ListeningRoundEntity(update.roundId, update.trackKey.providerId, update.trackKey.trackId, title,
                update.epochs.historyEpoch, update.epochs.statisticsEpoch, update.epochs.rulesVersion,
                update.firstProgressEpochMs, update.firstProgressElapsedMs, update.firstProgressZoneId,
                update.sampledEpochMs, update.sampledElapsedMs, update.sampledZoneId,
                if (delta > 0L) update.sampledEpochMs else stored?.lastProgressEpochMs ?: update.sampledEpochMs,
                update.totalListeningMs, duration, stored?.playCounted == true || countNow,
                countedDay?.first ?: stored?.countedLocalDate, countedDay?.second ?: stored?.countedZoneId, historyId)
            // Original epoch binding survives a clear before the first flush and cannot be rebound.
            dao.putRound(round)
            if (changes.values.isNotEmpty()) dao.putSegments(changes.values)
            if (historyCurrent && historyId != null && historyId >= nextMeta.historyFloorSequence) {
                dao.putHistory(ListeningHistoryEntity(round.historyEpoch, historyId, round.roundId, round.providerId, round.trackId,
                    round.displayName, round.firstProgressEpochMs, round.firstProgressZoneId, round.lastProgressEpochMs,
                    round.totalListeningMs, round.knownDurationMs))
            }
            if (statisticsCurrent && (delta > 0L || countNow)) {
                for ((segment, amount) in changes.deltas) {
                    if (amount > 0L) addDay(current.statisticsEpoch, segment.localDate, segment.zoneId, 0L, amount)
                }
                if (countedDay != null) addDay(current.statisticsEpoch, countedDay.first, countedDay.second, 1L, 0L)
                val previousTrack = dao.track(current.statisticsEpoch, round.providerId, round.trackId)
                val track = ListeningTrackEntity(current.statisticsEpoch, round.providerId, round.trackId,
                    add(previousTrack?.playCount ?: 0L, if (countNow) 1L else 0L),
                    add(previousTrack?.listeningMs ?: 0L, delta),
                    if (delta > 0L) round.lastProgressEpochMs else previousTrack?.lastProgressEpochMs,
                    round.displayName ?: previousTrack?.displayName)
                dao.putTrack(track)
                library.updateListeningProjectionUnlocked(update.trackKey, current.statisticsEpoch, track.playCount, track.listeningMs)
                nextMeta = nextMeta.copy(totalPlayCount = add(current.totalPlayCount, if (countNow) 1L else 0L),
                    totalListeningMs = add(current.totalListeningMs, delta))
            }
            dao.updateMeta(nextMeta)
            acknowledgement(nextMeta, round, false)
        }
        reclaim()
        commit
    }

    override suspend fun historyPage(after: ListeningHistoryCursor?, limit: Int): ListeningHistoryPage = access {
        pageLimit(limit)
        library.libraryTransaction {
            val current = metadata()
            if (after != null) {
                if (after.historyEpoch != current.historyEpoch || after.beforeHistoryId < current.historyFloorSequence) fail(ListeningIssue.SOURCE_CHANGED)
                if (dao.history(current.historyEpoch, after.beforeHistoryId, current.historyFloorSequence) == null) fail(ListeningIssue.INVALID_INPUT)
            }
            val rows = dao.historyPage(current.historyEpoch, current.historyFloorSequence, after?.beforeHistoryId, limit)
            val last = rows.lastOrNull()?.historyId
            val more = last != null && dao.historyPage(current.historyEpoch, current.historyFloorSequence, last, 1).isNotEmpty()
            ListeningHistoryPage(rows.map { it.model() }, last?.takeIf { more }?.let { ListeningHistoryCursor(current.historyEpoch, it) })
        }
    }

    override suspend fun trackSummary(trackKey: TrackKey): ListeningTrackSummary = access {
        library.libraryTransaction {
            val current = metadata()
            val value = dao.track(current.statisticsEpoch, trackKey.providerId, trackKey.trackId)
            if (value == null) ListeningTrackSummary(trackKey, 0L, 0L, null, localTitle(trackKey)) else trackModel(value)
        }
    }

    override suspend fun trackPage(after: ListeningTrackCursor?, limit: Int): ListeningTrackPage = access {
        pageLimit(limit)
        library.libraryTransaction {
            val current = metadata()
            if (after != null) {
                if (after.statisticsEpoch != current.statisticsEpoch) fail(ListeningIssue.SOURCE_CHANGED)
                val anchor = dao.track(current.statisticsEpoch, after.providerId, after.trackId) ?: fail(ListeningIssue.INVALID_INPUT)
                if (anchor.listeningMs != after.listeningMs) fail(ListeningIssue.SOURCE_CHANGED)
            }
            val rows = dao.trackPage(current.statisticsEpoch, after?.listeningMs, after?.providerId, after?.trackId, limit)
            val last = rows.lastOrNull()
            val more = last != null && dao.trackPage(current.statisticsEpoch, last.listeningMs, last.providerId, last.trackId, 1).isNotEmpty()
            ListeningTrackPage(rows.map { trackModel(it) }, last?.takeIf { more }?.let {
                ListeningTrackCursor(current.statisticsEpoch, it.listeningMs, it.providerId, it.trackId)
            })
        }
    }

    override suspend fun dayPage(after: ListeningDayCursor?, limit: Int): ListeningDayPage = access {
        pageLimit(limit)
        library.libraryTransaction {
            val current = metadata()
            if (after != null) {
                if (after.statisticsEpoch != current.statisticsEpoch) fail(ListeningIssue.SOURCE_CHANGED)
                if (dao.day(current.statisticsEpoch, after.localDate, after.zoneId) == null) fail(ListeningIssue.INVALID_INPUT)
            }
            val rows = dao.dayPage(current.statisticsEpoch, after?.localDate, after?.zoneId, limit)
            val last = rows.lastOrNull()
            val more = last != null && dao.dayPage(current.statisticsEpoch, last.localDate, last.zoneId, 1).isNotEmpty()
            ListeningDayPage(rows.map { it.model() }, last?.takeIf { more }?.let {
                ListeningDayCursor(current.statisticsEpoch, it.localDate, it.zoneId)
            })
        }
    }

    override suspend fun clearHistory(expectedHistoryEpoch: Long): ListeningEpochs = access {
        if (expectedHistoryEpoch < 0L) fail(ListeningIssue.INVALID_INPUT)
        val revision = beginEpochChange()
        var result: ListeningEpochs? = null
        try {
            result = library.libraryTransaction(finalPublication = true) {
                val current = metadata()
                if (current.historyEpoch != expectedHistoryEpoch) fail(ListeningIssue.CONFLICT)
                val next = current.copy(historyEpoch = increment(current.historyEpoch), historyFloorSequence = current.nextHistorySequence)
                dao.updateMeta(next)
                next.epochs()
            }
        } finally {
            finishEpochChange(revision, result)
        }
        reclaim()
        requireNotNull(result)
    }

    override suspend fun clearStatistics(expectedStatisticsEpoch: Long): ListeningEpochs = access {
        if (expectedStatisticsEpoch < 0L) fail(ListeningIssue.INVALID_INPUT)
        val revision = beginEpochChange()
        var result: ListeningEpochs? = null
        try {
            result = library.libraryTransaction(finalPublication = true) {
                val current = metadata()
                if (current.statisticsEpoch != expectedStatisticsEpoch) fail(ListeningIssue.CONFLICT)
                val next = current.copy(statisticsEpoch = increment(current.statisticsEpoch), totalPlayCount = 0L, totalListeningMs = 0L)
                library.clearListeningProjectionUnlocked(current.statisticsEpoch, next.statisticsEpoch)
                dao.updateMeta(next)
                next.epochs()
            }
        } finally {
            finishEpochChange(revision, result)
        }
        reclaim()
        requireNotNull(result)
    }

    /** 只锁短内存更新；IO/事务绝不持有这把锁。清除前先禁止用旧观察授权自动边界。 */
    private fun beginEpochChange(): Long = synchronized(epochObservation) {
        observationRevision += 1L
        pendingEpochChanges += 1
        mutableObservedEpochs.value = null
        observationRevision
    }

    private fun finishEpochChange(revision: Long, epochs: ListeningEpochs?) = synchronized(epochObservation) {
        pendingEpochChanges -= 1
        // 并发清除或不确定 COMMIT 后保持未知；下一次真实 state() 读取才能重新发布。
        if (pendingEpochChanges == 0 && revision == observationRevision) mutableObservedEpochs.value = epochs
    }

    private suspend fun metadata(): ListeningMetaEntity {
        dao.insertMeta(ListeningMetaEntity())
        library.dao.insertMeta(LibraryMetaEntity())
        val current = dao.meta() ?: fail(ListeningIssue.RECOVERY_REQUIRED)
        val libraryMeta = library.dao.meta() ?: fail(ListeningIssue.RECOVERY_REQUIRED)
        if (current.rulesVersion != ListeningLimits.RULES_VERSION || current.historyEpoch < 0L || current.statisticsEpoch < 0L ||
            current.statisticsEpoch != libraryMeta.statisticsEpoch || current.nextHistorySequence < 1L ||
            current.historyFloorSequence !in 1L..current.nextHistorySequence || current.totalPlayCount < 0L || current.totalListeningMs < 0L) {
            fail(ListeningIssue.RECOVERY_REQUIRED)
        }
        return current
    }

    private suspend fun localTitle(key: TrackKey): String? = if (key.providerId == TrackKey.LOCAL_PROVIDER) {
        library.dao.currentTrack(key.trackId)?.displayTitle
    } else null

    private suspend fun trackModel(value: ListeningTrackEntity): ListeningTrackSummary {
        val key = TrackKey(value.providerId, value.trackId)
        return ListeningTrackSummary(key, value.playCount, value.listeningMs, value.lastProgressEpochMs,
            localTitle(key) ?: value.displayName)
    }

    private suspend fun segmentChanges(update: ListeningRoundUpdate, stored: ListeningRoundEntity?, delta: Long): SegmentChanges {
        val previousLast = stored?.let { dao.lastSegment(it.roundId)?.ordinal } ?: -1L
        var last = previousLast
        var sum = 0L
        val values = ArrayList<ListeningSegmentEntity>(update.daySegments.size)
        val deltas = ArrayList<Pair<ListeningDayAccumulation, Long>>(update.daySegments.size)
        for (segment in update.daySegments) {
            val old = if (stored == null) null else dao.segment(stored.roundId, segment.ordinal)
            if (old != null && (old.localDate != segment.localDate || old.zoneId != segment.zoneId || segment.listeningMs < old.listeningMs)) {
                fail(ListeningIssue.CONFLICT)
            }
            if (old == null) {
                if (segment.ordinal != increment(last) || segment.listeningMs == 0L) fail(ListeningIssue.INVALID_INPUT)
                last = segment.ordinal
            }
            val amount = segment.listeningMs - (old?.listeningMs ?: 0L)
            if (segment.ordinal < previousLast && amount > 0L) fail(ListeningIssue.CONFLICT)
            sum = add(sum, amount)
            if (amount > 0L) values += ListeningSegmentEntity(update.roundId, segment.ordinal, segment.localDate, segment.zoneId, segment.listeningMs)
            deltas += segment to amount
        }
        if (sum != delta) fail(ListeningIssue.INVALID_INPUT)
        return SegmentChanges(values, deltas)
    }

    private fun validateRound(stored: ListeningRoundEntity, update: ListeningRoundUpdate) {
        if (stored.providerId != update.trackKey.providerId || stored.trackId != update.trackKey.trackId ||
            stored.historyEpoch != update.epochs.historyEpoch || stored.statisticsEpoch != update.epochs.statisticsEpoch ||
            stored.rulesVersion != update.epochs.rulesVersion || stored.firstProgressEpochMs != update.firstProgressEpochMs ||
            stored.firstProgressElapsedMs != update.firstProgressElapsedMs || stored.firstProgressZoneId != update.firstProgressZoneId) fail(ListeningIssue.CONFLICT)
    }

    private suspend fun addDay(epoch: Long, date: String, zone: String, count: Long, milliseconds: Long) {
        val old = dao.day(epoch, date, zone)
        dao.putDay(ListeningDayEntity(epoch, date, zone, add(old?.playCount ?: 0L, count), add(old?.listeningMs ?: 0L, milliseconds)))
    }

    private fun countDay(update: ListeningRoundUpdate, changes: SegmentChanges, before: Long, threshold: Long): Pair<String, String> {
        if (before < threshold) {
            var total = before
            for ((segment, amount) in changes.deltas) {
                total = add(total, amount)
                if (amount > 0L && total >= threshold) return segment.localDate to segment.zoneId
            }
        }
        val zone = ZoneId.of(update.sampledZoneId)
        return Instant.ofEpochMilli(update.sampledEpochMs).atZone(zone).toLocalDate().toString() to zone.id
    }

    private fun threshold(duration: Long?): Long = minOf(ListeningLimits.ROUND_THRESHOLD_MS,
        duration?.div(2L)?.coerceAtLeast(1L) ?: ListeningLimits.ROUND_THRESHOLD_MS)

    private suspend fun acknowledgement(current: ListeningMetaEntity, round: ListeningRoundEntity, replay: Boolean): ListeningCommit {
        val historyPresent = round.historyEpoch == current.historyEpoch && round.historyId?.let {
            dao.history(current.historyEpoch, it, current.historyFloorSequence) != null
        } == true
        return ListeningCommit(current.epochs(), round.totalListeningMs, historyPresent,
            round.statisticsEpoch == current.statisticsEpoch && round.playCounted, replay)
    }

    private suspend fun reclaim() {
        try {
            for (clean in listOf<suspend (ListeningMetaEntity) -> Int>(
                { dao.cleanHistory(it.historyEpoch, it.historyFloorSequence) },
                { dao.cleanTracks(it.statisticsEpoch) },
                { dao.cleanDays(it.statisticsEpoch) },
            )) {
                currentCoroutineContext().ensureActive()
                library.libraryTransaction { clean(metadata()) }
            }
            library.libraryTransaction(cleanAfter = true) { Unit }
        } catch (error: CancellationException) {
            throw error
        } catch (_: LibraryException) {
            // The committed acknowledgement remains valid; later submissions retry bounded reclamation.
        }
    }

    private fun validateEpochs(epochs: ListeningEpochs) {
        if (epochs.historyEpoch < 0L || epochs.statisticsEpoch < 0L || epochs.rulesVersion <= 0) fail(ListeningIssue.INVALID_INPUT)
    }
    private fun validateToday(localDate: String, zoneId: String) {
        if (localDate.length > 16 || zoneId.length > 256) fail(ListeningIssue.INVALID_INPUT)
        try {
            if (LocalDate.parse(localDate).toString() != localDate || ZoneId.of(zoneId).id != zoneId) fail(ListeningIssue.INVALID_INPUT)
        } catch (_: DateTimeException) { fail(ListeningIssue.INVALID_INPUT) }
    }
    private fun pageLimit(limit: Int) { if (limit !in 1..ListeningLimits.PAGE_SIZE) fail(ListeningIssue.INVALID_INPUT) }
    private fun increment(value: Long): Long = add(value, 1L)
    private fun add(a: Long, b: Long): Long = try { Math.addExact(a, b) } catch (_: ArithmeticException) { fail(ListeningIssue.STORAGE_FULL) }
    private fun fail(issue: ListeningIssue): Nothing = throw ListeningException(issue)
    private suspend fun <T> access(action: suspend () -> T): T = try { action() }
    catch (error: CancellationException) { throw error }
    catch (error: ListeningException) { throw error }
    catch (error: LibraryException) {
        val issue = when (error.issue) {
            LibraryIssue.INVALID_INPUT -> ListeningIssue.INVALID_INPUT
            LibraryIssue.CONFLICT -> ListeningIssue.CONFLICT
            LibraryIssue.SOURCE_CHANGED, LibraryIssue.VIEW_EXPIRED -> ListeningIssue.SOURCE_CHANGED
            LibraryIssue.STORAGE_FULL -> ListeningIssue.STORAGE_FULL
            LibraryIssue.STORAGE_UNAVAILABLE -> ListeningIssue.STORAGE_UNAVAILABLE
            else -> ListeningIssue.RECOVERY_REQUIRED
        }
        throw ListeningException(issue, error)
    }

    private data class SegmentChanges(
        val values: List<ListeningSegmentEntity>,
        val deltas: List<Pair<ListeningDayAccumulation, Long>>,
    )
}

private fun ListeningMetaEntity.epochs() = ListeningEpochs(historyEpoch, statisticsEpoch, rulesVersion)
private fun ListeningHistoryEntity.model() = ListeningHistoryItem(historyId, roundId, TrackKey(providerId, trackId), displayName,
    firstProgressEpochMs, firstProgressZoneId, lastProgressEpochMs, listeningMs, knownDurationMs)
private fun ListeningDayEntity.model() = ListeningDaySummary(localDate, zoneId, playCount, listeningMs)
