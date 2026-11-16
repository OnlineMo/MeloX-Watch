package io.github.onlinemo.meloxwatch.playback

import io.github.onlinemo.meloxwatch.data.library.LibraryException
import io.github.onlinemo.meloxwatch.data.library.LibraryIssue
import io.github.onlinemo.meloxwatch.data.library.LibraryQuery
import io.github.onlinemo.meloxwatch.data.library.LibraryViewOwner
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistException
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistIssue
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMember
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistQueueAvailability
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistReference
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistReferenceIssue
import io.github.onlinemo.meloxwatch.model.QueueMode
import io.github.onlinemo.meloxwatch.model.TrackKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 独立端口用例；需要云端运行，不能据此宣称Room、签名APK或实机已通过。 */
class PlaylistQueueProducerTest {
    @Test(timeout = 30_000L)
    fun everyMemberOfAListLargerThanTenThousandIsProducedInBoundedPages() = runBlocking {
        val fixture = PlaylistProducerFixture(10_005L)
        val producer = fixture.producer()
        assertEquals(0, fixture.playlists.adoptionCount)
        assertEquals(0, fixture.library.queueOpens)
        var offset = 0L
        var lastId: String? = null
        try {
            while (offset < 10_005L) {
                val limit = minOf(100L, 10_005L - offset).toInt()
                val page = producer.source.read(offset, limit)
                assertEquals(producer.frozenView, page.view)
                assertEquals(offset, page.offset)
                assertEquals(limit, page.entries.size)
                assertEquals((offset until offset + limit).toList(), page.entries.map { it.originalOrder })
                assertEquals(page.entries.map { it.originalOrder }, page.entries.map { it.playbackOrder })
                assertEquals(limit, page.entries.map { it.queueEntryId }.toSet().size)
                assertTrue(page.entries.none { it.queueEntryId == "mapper-id" || it.queueEntryId == lastId })
                lastId = page.entries.last().queueEntryId
                offset += page.entries.size
                if (offset == 100L) {
                    fixture.playlists.closeView(fixture.view.viewId)
                    fixture.library.closeView(fixture.library.view.viewId)
                }
            }
            assertEquals(10_005L, offset)
            assertEquals(1, fixture.playlists.adoptionCount)
            assertEquals(1, fixture.library.queueOpens)
            assertEquals(101, fixture.mappedSizes.size)
            assertTrue(fixture.mappedSizes.all { it in 1..100 })
            assertEquals(5, fixture.mappedSizes.last())
            assertEquals(1, fixture.mappedContexts.distinct().size)
            assertNotEquals(fixture.command().catalog.id, fixture.mappedContexts.first().id)
        } finally {
            producer.close()
        }
    }

    @Test(timeout = 30_000L)
    fun constructingAndClosingAnUnreadProducerNeedsNoExpiredOriginalLease() = runBlocking {
        val fixture = PlaylistProducerFixture()
        fixture.playlists.expired = true
        fixture.library.expired = true
        val producer = fixture.producer()
        assertEquals(205L, producer.frozenView.totalCount)
        producer.close()
        assertEquals(0, fixture.playlists.adoptionCount)
        assertEquals(0, fixture.library.queueOpens)
        assertTrue(fixture.mappedSizes.isEmpty())
        expectClosed { producer.source.read(0L, 100) }
    }

    @Test(timeout = 30_000L)
    fun nonforwardOrOversizedRequestsCannotAdoptOrRepeatQueueRows() = runBlocking {
        val fixture = PlaylistProducerFixture(101L)
        val producer = fixture.producer()
        try {
            expectInvalid { producer.source.read(1L, 100) }
            expectInvalid { producer.source.read(0L, 101) }
            assertEquals(0, fixture.playlists.adoptionCount)
            producer.source.read(0L, 100)
            expectInvalid { producer.source.read(0L, 100) }
            expectInvalid { producer.source.read(99L, 1) }
            assertEquals(1, producer.source.read(100L, 100).entries.size)
            expectInvalid { producer.source.read(101L, 1) }
        } finally {
            producer.close()
        }
    }

    @Test(timeout = 30_000L)
    fun selectedInsertionUsesTheExactMemberAndResetsOnlyQueueInstanceIdentityAndOrder() = runBlocking {
        val fixture = PlaylistProducerFixture()
        val command = fixture.command().copy(selection = PlaylistQueueSelection("member-204", 204L), action = PlaylistQueueAction.INSERT_NEXT)
        val producer = fixture.producer(command)
        try {
            val page = producer.source.read(0L, 100)
            val entry = page.entries.single()
            assertEquals(1L, page.view.totalCount)
            assertEquals(TrackKey(TrackKey.LOCAL_PROVIDER, "track-204"), entry.trackKey)
            assertEquals(0L, entry.originalOrder)
            assertEquals(0L, entry.playbackOrder)
            assertEquals("location-204", entry.location?.locationId)
            assertNotEquals("mapper-id", entry.queueEntryId)
            assertEquals(listOf(204L), fixture.playlists.pages)
            assertEquals(listOf(1), fixture.mappedSizes)
        } finally {
            producer.close()
        }
    }

    @Test(timeout = 30_000L)
    fun selectedReplacementKeepsTheCompleteListRatherThanTheSelectedPage() = runBlocking {
        val fixture = PlaylistProducerFixture()
        val producer = fixture.producer(fixture.command().copy(selection = PlaylistQueueSelection("member-150", 150L)))
        try {
            assertEquals(205L, producer.frozenView.totalCount)
            assertEquals(100, producer.source.read(0L, 100).entries.size)
            assertEquals(100, producer.source.read(100L, 100).entries.size)
            assertEquals(5, producer.source.read(200L, 5).entries.size)
            assertEquals(listOf(150L, 0L, 100L, 200L), fixture.playlists.pages)
        } finally {
            producer.close()
        }
    }

    @Test(timeout = 30_000L)
    fun changingTheSelectedIdentityOrPlaylistSnapshotRejectsBeforeMappingRows() = runBlocking {
        for (changeSnapshot in listOf(false, true)) {
            val fixture = PlaylistProducerFixture(3L)
            val command = fixture.command().copy(selection = PlaylistQueueSelection("member-1", 1L))
            if (changeSnapshot) {
                fixture.playlists.adoptionTransform = { it.copy(snapshot = it.snapshot.copy(revision = it.snapshot.revision + 1L)) }
            } else {
                val original = fixture.playlists.memberAt
                fixture.playlists.memberAt = { ordinal -> original(ordinal).copy(memberId = "other-$ordinal") }
            }
            val producer = fixture.producer(command)
            try {
                expectPlaylist(PlaylistIssue.SOURCE_CHANGED) { producer.source.read(0L, 3) }
                assertTrue(fixture.mappedSizes.isEmpty())
            } finally {
                producer.close()
            }
        }
    }

    @Test(timeout = 30_000L)
    fun aDifferentCatalogRevisionRuleOrFilteredViewCannotTrimPlaylistMembership() = runBlocking {
        for (change in 0..4) {
            val fixture = PlaylistProducerFixture(3L)
            fixture.library.adoptionTransform = { lease ->
                when (change) {
                    0 -> lease.copy(revision = lease.revision + 1L)
                    1 -> lease.copy(rulesRevision = lease.rulesRevision + 1L)
                    2 -> lease.copy(query = LibraryQuery(includeHidden = false))
                    3 -> lease.copy(owner = LibraryViewOwner.UI)
                    else -> lease.copy(query = LibraryQuery(search = "other track", includeHidden = true))
                }
            }
            val producer = fixture.producer()
            try {
                expectLibrary(LibraryIssue.SOURCE_CHANGED) { producer.source.read(0L, 3) }
                assertTrue(fixture.mappedSizes.isEmpty())
            } finally {
                producer.close()
            }
            assertEquals(1, fixture.playlists.closed.size)
            assertEquals(1, fixture.library.closed.size)
        }
    }

    @Test(timeout = 30_000L)
    fun missingAndUnresolvedMembersKeepTheirOriginalPositionsWithoutImportedResources() = runBlocking {
        val fixture = PlaylistProducerFixture(3L)
        val originalMember = fixture.playlists.memberAt
        fixture.playlists.memberAt = { ordinal ->
            if (ordinal == 1L) PlaylistMember("placeholder", ordinal, PlaylistReference(
                placeholderId = "imported", issue = PlaylistReferenceIssue.NETWORK_REFERENCE,
                titleHint = "Unavailable", descriptionHint = "https://invalid.test/private",
            )) else originalMember(ordinal)
        }
        val originalMap = fixture.mapRows
        fixture.mapRows = { members ->
            originalMap(members).mapIndexed { index, resolution ->
                if (index == 0) resolution.copy(availability = PlaylistQueueAvailability.MISSING, entry = resolution.entry.copy(location = null))
                else resolution
            }
        }
        val producer = fixture.producer()
        try {
            val entries = producer.source.read(0L, 3).entries
            assertEquals(3, entries.size)
            assertEquals(listOf(0L, 1L, 2L), entries.map { it.originalOrder })
            assertNull(entries[0].location)
            assertNull(entries[1].location)
            assertEquals(PlaylistQueueKeys.unresolved("imported"), entries[1].trackKey)
            assertEquals(TrackKey(TrackKey.LOCAL_PROVIDER, "track-2"), entries[2].trackKey)
        } finally {
            producer.close()
        }
    }

    @Test(timeout = 30_000L)
    fun mapperCannotDropReorderOrSubstituteKnownMembers() = runBlocking {
        for (invalidMapping in 0..2) {
            val fixture = PlaylistProducerFixture(3L)
            val originalMap = fixture.mapRows
            fixture.mapRows = { members ->
                val results = originalMap(members)
                when (invalidMapping) {
                    0 -> results.dropLast(1)
                    1 -> results.reversed()
                    else -> results.map { it.copy(entry = it.entry.copy(trackKey = TrackKey(TrackKey.LOCAL_PROVIDER, "different"))) }
                }
            }
            val producer = fixture.producer()
            try {
                expectPlaylist(if (invalidMapping == 0) PlaylistIssue.INCOMPLETE else PlaylistIssue.SOURCE_CHANGED) {
                    producer.source.read(0L, 3)
                }
            } finally {
                producer.close()
            }
        }
    }

    @Test(timeout = 30_000L)
    fun placeholderCannotBeGuessedAsAKnownTrackOrGivenAnImportedLocation() = runBlocking {
        for (invalidMapping in 0..2) {
            val fixture = PlaylistProducerFixture(1L)
            fixture.playlists.memberAt = { ordinal -> PlaylistMember("placeholder", ordinal,
                PlaylistReference(placeholderId = "imported", issue = PlaylistReferenceIssue.UNMATCHED)) }
            val originalMap = fixture.mapRows
            fixture.mapRows = { members ->
                originalMap(members).map { result ->
                    when (invalidMapping) {
                        0 -> result.copy(availability = PlaylistQueueAvailability.AVAILABLE)
                        1 -> result.copy(entry = result.entry.copy(trackKey = TrackKey(TrackKey.LOCAL_PROVIDER, "real-track")))
                        else -> result.copy(entry = result.entry.copy(location = io.github.onlinemo.meloxwatch.model.LocalLocationReference("imported", "content://test/imported")))
                    }
                }
            }
            val producer = fixture.producer()
            try {
                expectPlaylist(PlaylistIssue.SOURCE_CHANGED) { producer.source.read(0L, 1) }
            } finally {
                producer.close()
            }
        }
    }

    @Test(timeout = 30_000L)
    fun aPartialOrIncorrectlyNumberedSourceNeverReturnsAPartialQueuePage() = runBlocking {
        for (invalidPage in 0..2) {
            val fixture = PlaylistProducerFixture(3L)
            fixture.playlists.pageTransform = { page ->
                when (invalidPage) {
                    0 -> page.copy(members = page.members.dropLast(1))
                    1 -> page.copy(members = page.members.map { it.copy(ordinal = it.ordinal + 1L) })
                    else -> page.copy(nextOrdinal = 3L)
                }
            }
            val producer = fixture.producer()
            try {
                expectPlaylist(if (invalidPage == 1) PlaylistIssue.SOURCE_CHANGED else PlaylistIssue.INCOMPLETE) {
                    producer.source.read(0L, 3)
                }
                assertTrue(fixture.mappedSizes.isEmpty())
            } finally {
                producer.close()
            }
        }
    }

    @Test(timeout = 30_000L)
    fun expiryBetweenPagesStopsReadingTheFrozenListAndDoesNotOpenLatestData() = runBlocking {
        val fixture = PlaylistProducerFixture(101L)
        val producer = fixture.producer()
        try {
            producer.source.read(0L, 100)
            fixture.playlists.expired = true
            expectPlaylist(PlaylistIssue.VIEW_EXPIRED) { producer.source.read(100L, 1) }
            assertEquals(1, fixture.playlists.adoptionCount)
            assertEquals(1, fixture.library.queueOpens)
            assertEquals(listOf(100), fixture.mappedSizes)
        } finally {
            producer.close()
        }
    }

    @Test(timeout = 30_000L)
    fun stableInputIdentityIgnoresTemporaryLeasesButChangesWithTheActualIntent() = runBlocking {
        val fixture = PlaylistProducerFixture(3L)
        val base = fixture.command().copy(selection = PlaylistQueueSelection("member-1", 1L), action = PlaylistQueueAction.INSERT_NEXT)
        val first = fixture.producer(base)
        val second = fixture.producer(base.copy(operationId = "another-operation"))
        try {
            val firstPage = first.source.read(0L, 1)
            first.close()
            val secondPage = second.source.read(0L, 1)
            assertEquals(firstPage.view, secondPage.view)
            assertNotEquals(firstPage.entries.single().queueEntryId, secondPage.entries.single().queueEntryId)
            assertTrue(firstPage.view.id.length <= 128)
            val changes = listOf(
                base.copy(selection = PlaylistQueueSelection("member-2", 2L)),
                base.copy(mode = QueueMode(shuffle = true)),
                base.copy(action = PlaylistQueueAction.INSERT_AND_PLAY),
                base.copy(catalog = base.catalog.copy(rulesRevision = base.catalog.rulesRevision + 1L)),
                base.copy(view = base.view.copy(viewId = "another-original-view")),
            )
            for (changed in changes) assertNotEquals(firstPage.view, fixture.producer(changed).frozenView)
        } finally {
            first.close()
            second.close()
        }
    }

    @Test(timeout = 30_000L)
    fun closeReleasesTheOtherLeaseAfterOneFailureAndRetriesOnlyTheFailedLease() = runBlocking {
        for (relationFailure in listOf(false, true)) {
            val fixture = PlaylistProducerFixture(3L)
            val producer = fixture.producer()
            producer.source.read(0L, 3)
            if (relationFailure) fixture.playlists.closeFailures = 1 else fixture.library.closeFailures = 1
            try {
                if (relationFailure) expectPlaylist(PlaylistIssue.STORAGE_UNAVAILABLE) { producer.close() }
                else expectLibrary(LibraryIssue.STORAGE_UNAVAILABLE) { producer.close() }
                assertEquals(if (relationFailure) 0 else 1, fixture.playlists.closed.size)
                assertEquals(if (relationFailure) 1 else 0, fixture.library.closed.size)
                expectClosed { producer.source.read(0L, 1) }
            } finally {
                producer.close()
            }
            assertEquals(1, fixture.playlists.closed.size)
            assertEquals(1, fixture.library.closed.size)
        }
    }

    @Test(timeout = 30_000L)
    fun bothLeaseClosuresCanThrowTheSameExceptionObjectAndStillBeRetried() = runBlocking {
        val fixture = PlaylistProducerFixture(3L)
        val producer = fixture.producer()
        producer.source.read(0L, 3)
        val failure = PlaylistException(PlaylistIssue.STORAGE_UNAVAILABLE)
        fixture.playlists.closeFailures = 1
        fixture.library.closeFailures = 1
        fixture.playlists.closeError = failure
        fixture.library.closeError = failure
        try {
            try {
                producer.close()
                throw AssertionError("Both close failures were ignored")
            } catch (error: PlaylistException) {
                assertSame(failure, error)
            }
            assertTrue(fixture.playlists.closed.isEmpty())
            assertTrue(fixture.library.closed.isEmpty())
        } finally {
            producer.close()
        }
        assertEquals(1, fixture.playlists.closed.size)
        assertEquals(1, fixture.library.closed.size)
    }

    @Test(timeout = 30_000L)
    fun cancellationStopsTheMapperAndStillAllowsOwnedLeaseCleanup() = runBlocking {
        val fixture = PlaylistProducerFixture(3L)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val producer = fixture.producer()
        fixture.mapRows = {
            entered.complete(Unit)
            release.await()
            error("Cancelled mapper resumed")
        }
        val read = launch { producer.source.read(0L, 3) }
        try {
            withTimeout(5_000L) { entered.await() }
            withTimeout(5_000L) { read.cancelAndJoin() }
            assertTrue(read.isCancelled)
            assertFalse(release.isCompleted)
        } finally {
            release.complete(Unit)
            withTimeout(5_000L) { read.cancelAndJoin() }
            producer.close()
        }
        assertEquals(1, fixture.playlists.closed.size)
        assertEquals(1, fixture.library.closed.size)
    }

    private suspend fun expectPlaylist(issue: PlaylistIssue, action: suspend () -> Unit) {
        try {
            action()
        } catch (error: PlaylistException) {
            assertEquals(issue, error.issue)
            return
        }
        throw AssertionError("Expected $issue")
    }

    private suspend fun expectLibrary(issue: LibraryIssue, action: suspend () -> Unit) {
        try {
            action()
        } catch (error: LibraryException) {
            assertEquals(issue, error.issue)
            return
        }
        throw AssertionError("Expected $issue")
    }

    private suspend fun expectClosed(action: suspend () -> Unit) {
        try {
            action()
        } catch (_: IllegalStateException) {
            return
        }
        throw AssertionError("Closed producer returned a page")
    }

    private suspend fun expectInvalid(action: suspend () -> Unit) {
        try {
            action()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("Producer accepted an invalid cursor or page budget")
    }
}
