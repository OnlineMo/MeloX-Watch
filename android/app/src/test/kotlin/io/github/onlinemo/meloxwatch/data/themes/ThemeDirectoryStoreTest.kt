package io.github.onlinemo.meloxwatch.data.themes

import io.github.onlinemo.meloxwatch.data.themes.ThemeTestFixtures as Fixture
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 真实 NIO 目录、锁、rename、fsync 及重开；没有用内存目录替代持久状态。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30], manifest = Config.NONE)
internal class ThemeDirectoryStoreTest : ThemeStoreTestFixture() {
    @Test fun installWritesOneRevisionAndReopenReturnsTheExactAcceptedManifest() {
        val store = store()
        val accepted = store.exclusively {
            val proposal = prepareProposal(store)
            val result = commit(store, proposal)
            assertFalse(result.cleanupRequired)
            assertEquals(proposal.replacement, result.stored.catalog)
            assertArrayEquals(result.stored.bytes, catalogFile(store).readBytes())
            assertArrayEquals(proposal.value.manifest, File(store.installationDirectory(proposal.stageId), "theme.json").readBytes())
            assertFalse(store.stageDirectory(proposal.stageId).exists())
            assertNull(store.pendingIssue())
            result.stored
        }
        val reopened = store(store.root)
        reopened.exclusively {
            assertNull(reopened.recover())
            val actual = reopened.loadCatalog()
            assertArrayEquals(accepted.bytes, actual.bytes)
            assertEquals(accepted.catalog, actual.catalog)
            val ref = (actual.catalog.selected as ThemeChoice.Installed).ref
            assertEquals(ref.themeId, reopened.manifest(ref, actual.catalog).id)
        }
    }

    @Test fun aDifferentInstanceCannotEnterTheAlreadyLockedPhysicalDirectory() {
        val store = store()
        val other = store(store.root)
        var entered = false
        store.exclusively {
            Fixture.expect(ThemeIssue.BUSY) { other.exclusively { entered = true } }
            assertFalse(entered)
            store.loadCatalog()
        }
        other.exclusively { entered = true }
        assertTrue(entered)
    }

    @Test fun mutationAgainstStaleCatalogBytesCannotInstallItsStage() {
        val store = store()
        store.exclusively {
            val first = prepareProposal(store)
            val stale = prepareProposal(store, Fixture.packageOf("fixture.second"))
            val actual = commit(store, first).stored
            Fixture.expect(ThemeIssue.REVISION_CONFLICT) { commit(store, stale) }
            assertArrayEquals(actual.bytes, catalogFile(store).readBytes())
            assertTrue(store.stagePayload(stale.stageId).isDirectory)
            assertFalse(store.installationDirectory(stale.stageId).exists())
        }
    }

    @Test fun invalidOperationOrRevisionIsRejectedBeforeCreatingTheTransactionOrMovingPayload() {
        val directory = store()
        directory.exclusively {
            val proposal = prepareProposal(directory)
            val wrong = listOf(proposal.replacement.copy(lastOperationId = Fixture.id()),
                proposal.replacement.copy(revision = proposal.replacement.revision + 1))
            for (replacement in wrong) {
                Fixture.expect(ThemeIssue.CORRUPT_STORAGE) {
                    directory.commit(proposal.before, replacement, proposal.operationId, proposal.stageId, proposal.stageId, null) {}
                }
                assertArrayEquals(proposal.before.bytes, catalogFile(directory).readBytes())
                assertFalse(journal(directory).exists())
                assertFalse(directory.installationDirectory(proposal.stageId).exists())
                assertArrayEquals(proposal.value.manifest, File(directory.stagePayload(proposal.stageId), "theme.json").readBytes())
            }
        }
    }

    @Test fun stageManifestReadRejectsOversizedAndLinkedFilesWithoutReplacingTheCatalog() {
        val store = store()
        store.exclusively {
            val proposal = prepareProposal(store)
            val manifestFile = File(store.stagePayload(proposal.stageId), "theme.json")
            RandomAccessFile(manifestFile, "rw").use { it.setLength(ThemeLimits.MANIFEST_BYTES.toLong() + 1) }
            Fixture.expect(ThemeIssue.INTEGRITY_FAILED) { store.stageManifestBytes(proposal.stageId) {} }
            assertArrayEquals(proposal.before.bytes, catalogFile(store).readBytes())
            assertTrue(manifestFile.delete())
            val outside = temporary.newFile().apply { writeBytes(proposal.value.manifest) }
            Files.createSymbolicLink(manifestFile.toPath(), outside.toPath())
            Fixture.expect(ThemeIssue.LINK_OR_SPECIAL_FILE) { store.stageManifestBytes(proposal.stageId) {} }
            assertArrayEquals(proposal.value.manifest, outside.readBytes())
            assertArrayEquals(proposal.before.bytes, catalogFile(store).readBytes())
        }
    }

    @Test fun unindexedInstalledDirectoriesAndMissingCatalogAreCorruptionInsteadOfAnEmptyReset() {
        val store = store()
        store.exclusively {
            val proposal = prepareProposal(store)
            commit(store, proposal)
            val original = catalogFile(store).readBytes()
            val orphan = store.installationDirectory(Fixture.id())
            assertTrue(orphan.mkdir())
            Fixture.expect(ThemeIssue.CORRUPT_STORAGE) { store.loadCatalog() }
            assertArrayEquals(original, catalogFile(store).readBytes())
            assertTrue(orphan.delete())
            assertTrue(catalogFile(store).delete())
            Fixture.expect(ThemeIssue.CORRUPT_STORAGE) { store.loadCatalog() }
            assertArrayEquals(proposal.value.manifest, File(store.installationDirectory(proposal.stageId), "theme.json").readBytes())
        }
    }

    @Test fun orphanStageCleanupRetainsOnlyTheExactLivePreview() {
        val store = store()
        store.exclusively {
            store.loadCatalog()
            val live = Fixture.id()
            val old = Fixture.id()
            store.beginStage(live)
            store.beginStage(old)
            store.stageArchive(live).writeText("live preview")
            store.stageArchive(old).writeText("abandoned preview")
            store.cleanupOrphanStages(setOf(live))
            assertTrue(store.stageArchive(live).isFile)
            assertFalse(store.stageDirectory(old).exists())
            store.cleanupOrphanStages(emptySet())
            assertFalse(store.stageDirectory(live).exists())
        }
    }

    @Test fun invalidOrHalfWrittenPublishedJournalIsRetainedForExplicitRecovery() {
        val store = store()
        store.exclusively {
            val before = store.loadCatalog().bytes
            assertTrue(journal(store).mkdir())
            val plan = File(journal(store), "plan.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            Fixture.expect(ThemeIssue.CORRUPT_STORAGE) { store.recover() }
            assertArrayEquals(before, catalogFile(store).readBytes())
            assertArrayEquals(byteArrayOf(1, 2, 3), plan.readBytes())
        }
    }

    @Test fun unpublishedPartialPlanCanBeRemovedWithoutReadingItAsATransaction() {
        val store = store()
        store.exclusively {
            val before = store.loadCatalog().bytes
            assertTrue(journal(store).mkdir())
            File(journal(store), "before.bin").writeBytes(before.copyOf(3))
            File(journal(store), "plan-next.bin").writeBytes(byteArrayOf(1, 2, 3))
            assertNull(store.recover())
            assertFalse(journal(store).exists())
            assertArrayEquals(before, catalogFile(store).readBytes())
        }
    }

    @Test fun spaceChecksKeepTheFreeReserveAndCountAllOwnedCopies() {
        val store = store()
        store.exclusively {
            store.loadCatalog()
            val measured = store.measuredOwnedBytes()
            assertEquals(catalogFile(store).length(), measured)
            Fixture.expect(ThemeIssue.STORAGE_QUOTA_EXCEEDED) { store.requireRoom(ThemeLimits.STORED_BYTES, 0, 1) }
            Fixture.expect(ThemeIssue.STORAGE_QUOTA_EXCEEDED) { store.requireRoom(measured, ThemeLimits.STORED_BYTES, 1) }
        }
        val full = ThemeDirectoryStore(File(temporary.root, Fixture.id()), { ThemeLimits.FREE_RESERVE_BYTES })
        full.exclusively {
            Fixture.expect(ThemeIssue.STORAGE_FULL) { full.loadCatalog() }
            assertFalse(catalogFile(full).exists())
        }
    }
}
