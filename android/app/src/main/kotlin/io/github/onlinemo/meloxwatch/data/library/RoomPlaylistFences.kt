package io.github.onlinemo.meloxwatch.data.library

import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.database.hasCurrentWriterTransaction
import io.github.onlinemo.meloxwatch.data.local.allowsFileReadValidation
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistCatalogFence
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistException
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistIssue
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistLibraryRevision
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistLibraryTransactionFenceVerifier
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistTransactionFenceVerifier
import io.github.onlinemo.meloxwatch.data.scan.ScanMode
import io.github.onlinemo.meloxwatch.data.scan.ScanPhase

/** These checks run on the playlist writer's existing Room transaction, without provider I/O. */
class RoomPlaylistFences(private val database: MeloXDatabase) :
    PlaylistTransactionFenceVerifier, PlaylistLibraryTransactionFenceVerifier {
    private val dao = database.libraryDao()

    override suspend fun verify(revision: PlaylistLibraryRevision) {
        if (!database.hasCurrentWriterTransaction()) fail(PlaylistIssue.RECOVERY_REQUIRED)
        val actual = dao.meta() ?: fail(PlaylistIssue.SOURCE_UNAVAILABLE)
        if (actual.revision != revision.catalogRevision || actual.rulesRevision != revision.rulesRevision) {
            fail(PlaylistIssue.SOURCE_CHANGED)
        }
    }

    override suspend fun verify(fence: PlaylistCatalogFence) {
        verify(PlaylistLibraryRevision(fence.catalogRevision, fence.rulesRevision))
        val source = dao.source(fence.sourceId) ?: fail(PlaylistIssue.SOURCE_UNAVAILABLE)
        val grant = dao.grant(source.grantId) ?: fail(PlaylistIssue.SOURCE_UNAVAILABLE)
        val observed = source.model(grant)
        if (observed.state != LibrarySourceState.ACTIVE || !observed.grant.allowsFileReadValidation(observed.spec.kind)) {
            fail(PlaylistIssue.SOURCE_UNAVAILABLE)
        }
        if (source.revision != fence.sourceRevision || source.authEpoch != fence.authEpoch ||
            source.activeScanId != null || source.latestScanId != fence.scanId || source.absenceScanId != fence.scanId) {
            fail(PlaylistIssue.SOURCE_CHANGED)
        }
        val scan = dao.scan(fence.scanId) ?: fail(PlaylistIssue.INCOMPLETE)
        if (scan.sourceId != fence.sourceId || scan.sourceRevision != fence.sourceRevision ||
            scan.authEpoch != fence.authEpoch || scan.rulesRevision != fence.rulesRevision) {
            fail(PlaylistIssue.SOURCE_CHANGED)
        }
        if (scan.phase != ScanPhase.COMPLETED.name || scan.mode != ScanMode.FULL.name ||
            !scan.enumerationComplete || !scan.authorizationValid || !scan.rulesUnchanged || !scan.sourceUnchanged ||
            scan.excludedSubtrees || !scan.absenceDone || scan.failed != 0L || scan.issue != null ||
            source.absenceScanSequence != scan.sequence || dao.incompleteFrontier(scan.scanId) != 0L) {
            fail(PlaylistIssue.INCOMPLETE)
        }
    }

    private fun fail(issue: PlaylistIssue): Nothing = throw PlaylistException(issue)
}
