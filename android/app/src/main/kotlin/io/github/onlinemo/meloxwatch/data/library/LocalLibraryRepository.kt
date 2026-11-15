package io.github.onlinemo.meloxwatch.data.library

import io.github.onlinemo.meloxwatch.data.queue.QueuePageSource
import io.github.onlinemo.meloxwatch.model.TrackKey
import kotlinx.coroutines.flow.Flow

/** Local database queries only. This interface never logs in, scans or fetches network metadata. */
interface LocalLibraryRepository {
    suspend fun initialize(): LibraryOverview
    suspend fun overview(): LibraryOverview
    fun observeOverview(): Flow<LibraryOverview>
    suspend fun source(sourceId: String): LibrarySource?
    suspend fun sourcesPage(afterSourceId: String? = null, limit: Int = LibraryLimits.PAGE_SIZE): LibrarySourcePage
    suspend fun openView(
        query: LibraryQuery = LibraryQuery(),
        sort: LibrarySort = LibrarySort(),
        owner: LibraryViewOwner = LibraryViewOwner.UI,
    ): LibraryViewToken
    /** Adopts the exact visible revision into a separate producer lease; never opens latest data. */
    suspend fun openQueueView(fromViewId: String): LibraryViewToken
    suspend fun page(view: LibraryViewToken, after: LibraryCursor? = null, limit: Int = LibraryLimits.PAGE_SIZE): LibraryPage
    suspend fun previousPage(view: LibraryViewToken, before: LibraryCursor, limit: Int = LibraryLimits.PAGE_SIZE): LibraryPage
    suspend fun groups(view: LibraryViewToken, kind: LibraryGroupKind, afterKey: String? = null, limit: Int = LibraryLimits.PAGE_SIZE): LibraryGroupPage
    suspend fun folders(view: LibraryViewToken, parentNodeId: String?, afterNodeId: String? = null, limit: Int = LibraryLimits.PAGE_SIZE): LibraryFolderPage
    suspend fun folder(view: LibraryViewToken, nodeId: String): LibraryFolder?
    suspend fun sections(view: LibraryViewToken): List<LibrarySection>
    suspend fun locate(view: LibraryViewToken, trackKey: TrackKey): LibraryLocation?
    suspend fun closeView(viewId: String)
    fun queueSource(view: LibraryViewToken): QueuePageSource
    suspend fun resolveLocalRead(trackKey: TrackKey, expectedLocationId: String? = null, expectedContentRevision: Long? = null): LocalReadDescriptor
    suspend fun rules(): LibraryRules
    suspend fun exclusionPage(afterRuleId: String? = null, limit: Int = LibraryLimits.PAGE_SIZE): LibraryExclusionPage
    suspend fun setShortAudioFilter(expectedRulesRevision: Long, enabled: Boolean): LibraryRules
    suspend fun addExclusion(expectedRulesRevision: Long, sourceId: String, nodeId: String, subtree: Boolean): LibraryRules
    suspend fun removeExclusion(expectedRulesRevision: Long, ruleId: String): LibraryRules
}
