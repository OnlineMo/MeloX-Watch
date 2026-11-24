package io.github.onlinemo.meloxwatch.data.themes

import io.github.onlinemo.meloxwatch.data.themes.ThemeTestFixtures as Fixture
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 每次都断言故障确已触发，再核真实旧/新 bytes；Error 仅模拟不执行收尾的进程中断。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30], manifest = Config.NONE)
internal class ThemeDirectoryFaultsTest : ThemeStoreTestFixture() {
    @Test fun everyPrecommitIoFailureRestoresTheOldCatalogAndTheCompleteRetryPayload() {
        val points = listOf(ThemeStoragePoint.BEFORE_BACKUP_WRITE, ThemeStoragePoint.AFTER_BACKUP_WRITE,
            ThemeStoragePoint.BEFORE_PLAN_WRITE, ThemeStoragePoint.AFTER_PLAN_WRITE,
            ThemeStoragePoint.BEFORE_INSTALL_MOVE, ThemeStoragePoint.AFTER_INSTALL_MOVE,
            ThemeStoragePoint.BEFORE_CATALOG_MOVE, ThemeStoragePoint.AFTER_CATALOG_MOVE, ThemeStoragePoint.BEFORE_COMMIT_MARKER)
        for (point in points) {
            val fault = OneShotThemeFault(point)
            val store = store(faults = fault)
            store.exclusively {
                val proposal = prepareProposal(store)
                Fixture.expect(ThemeIssue.SAVE_FAILED) { commit(store, proposal) }
                assertEquals(point.name, 1, fault.hits.get())
                assertArrayEquals(proposal.before.bytes, catalogFile(store).readBytes())
                assertArrayEquals(proposal.value.manifest, File(store.stagePayload(proposal.stageId), "theme.json").readBytes())
                assertFalse(store.installationDirectory(proposal.stageId).exists())
                assertNull(store.pendingIssue())
                val retry = commit(store, proposal)
                assertFalse(retry.cleanupRequired)
                assertArrayEquals(ThemeStorageRecords.encode(proposal.replacement), catalogFile(store).readBytes())
                assertArrayEquals(proposal.value.manifest, File(store.installationDirectory(proposal.stageId), "theme.json").readBytes())
            }
        }
    }

    @Test fun failureAfterTheCommitMarkerKeepsTheNewSelectionAndOnlyRetriesCleanup() {
        val points = listOf(ThemeStoragePoint.AFTER_COMMIT_MARKER, ThemeStoragePoint.BEFORE_STAGE_DELETE,
            ThemeStoragePoint.AFTER_STAGE_DELETE, ThemeStoragePoint.BEFORE_JOURNAL_CLEANUP, ThemeStoragePoint.AFTER_JOURNAL_RETIRE)
        for (point in points) {
            val fault = OneShotThemeFault(point)
            val store = store(faults = fault)
            lateinit var accepted: ThemeStoredCatalog
            lateinit var proposal: Proposal
            store.exclusively {
                proposal = prepareProposal(store)
                val result = commit(store, proposal)
                assertEquals(point.name, 1, fault.hits.get())
                assertTrue(result.cleanupRequired)
                accepted = result.stored
                assertArrayEquals(accepted.bytes, catalogFile(store).readBytes())
                assertArrayEquals(proposal.value.manifest, File(store.installationDirectory(proposal.stageId), "theme.json").readBytes())
                assertEquals(ThemeIssue.CLEANUP_REQUIRED, store.pendingIssue())
            }
            val reopened = store(store.root)
            reopened.exclusively {
                assertNull(reopened.recover())
                assertArrayEquals(accepted.bytes, catalogFile(reopened).readBytes())
                assertEquals(accepted.catalog, reopened.loadCatalog().catalog)
                assertFalse(reopened.stageDirectory(proposal.stageId).exists())
                assertNull(reopened.pendingIssue())
            }
        }
    }

    @Test fun rollbackMoveFailuresRetainBeforeEvidenceAndRecoverFromTheActualPartialState() {
        for (rollbackPoint in listOf(ThemeStoragePoint.BEFORE_ROLLBACK_MOVE, ThemeStoragePoint.AFTER_ROLLBACK_MOVE)) {
            val primary = AtomicInteger()
            val secondary = AtomicInteger()
            val faults = ThemeStorageFaults { point ->
                if (point == ThemeStoragePoint.AFTER_CATALOG_MOVE && primary.getAndIncrement() == 0) throw IOException("primary")
                if (point == rollbackPoint && secondary.getAndIncrement() == 0) throw IOException("rollback")
            }
            val store = store(faults = faults)
            lateinit var proposal: Proposal
            store.exclusively {
                proposal = prepareProposal(store)
                val failure = Fixture.expect(ThemeIssue.RECOVERY_REQUIRED) { commit(store, proposal) }
                assertEquals(1, primary.get())
                assertEquals(1, secondary.get())
                assertTrue(failure.suppressed.isNotEmpty())
                assertTrue(journal(store).isDirectory)
                assertArrayEquals(proposal.before.bytes, checkNotNull(store.beforeCatalog()).bytes)
                val actualBytes = if (rollbackPoint == ThemeStoragePoint.BEFORE_ROLLBACK_MOVE) {
                    ThemeStorageRecords.encode(proposal.replacement)
                } else proposal.before.bytes
                assertArrayEquals(actualBytes, catalogFile(store).readBytes())
            }
            val reopened = store(store.root)
            reopened.exclusively {
                assertNull(reopened.recover())
                assertArrayEquals(proposal.before.bytes, catalogFile(reopened).readBytes())
                assertArrayEquals(proposal.value.manifest, File(reopened.stagePayload(proposal.stageId), "theme.json").readBytes())
                assertFalse(reopened.installationDirectory(proposal.stageId).exists())
                assertFalse(journal(reopened).exists())
            }
        }
    }

    @Test fun abruptExitBeforeTheMarkerRollsBackOnReopenAndExitAfterItKeepsTheNewCatalog() {
        val points = listOf(ThemeStoragePoint.AFTER_BACKUP_WRITE, ThemeStoragePoint.AFTER_PLAN_WRITE,
            ThemeStoragePoint.AFTER_INSTALL_MOVE, ThemeStoragePoint.AFTER_CATALOG_MOVE,
            ThemeStoragePoint.AFTER_COMMIT_MARKER, ThemeStoragePoint.AFTER_JOURNAL_RETIRE)
        for (point in points) {
            val fault = OneShotThemeFault(point, abrupt = true)
            val store = store(faults = fault)
            lateinit var proposal: Proposal
            store.exclusively {
                proposal = prepareProposal(store)
                try {
                    commit(store, proposal)
                    throw AssertionError("Expected interrupted process model")
                } catch (_: SimulatedThemeProcessExit) {
                    assertEquals(point.name, 1, fault.hits.get())
                }
            }
            val committed = point == ThemeStoragePoint.AFTER_COMMIT_MARKER || point == ThemeStoragePoint.AFTER_JOURNAL_RETIRE
            val reopened = store(store.root)
            reopened.exclusively {
                assertNull(reopened.recover())
                val expected = if (committed) ThemeStorageRecords.encode(proposal.replacement) else proposal.before.bytes
                assertArrayEquals(expected, catalogFile(reopened).readBytes())
                assertEquals(committed, reopened.installationDirectory(proposal.stageId).isDirectory)
                if (committed) assertFalse(reopened.stageDirectory(proposal.stageId).exists())
                else assertArrayEquals(proposal.value.manifest, File(reopened.stagePayload(proposal.stageId), "theme.json").readBytes())
                assertNull(reopened.pendingIssue())
            }
        }
    }

    @Test fun cancellationAndARollbackFailurePreserveTheOriginalCancellationIdentity() {
        val cancelled = CancellationException("Controlled cancellation before marker")
        val primary = AtomicInteger()
        val rollback = AtomicInteger()
        val store = store(faults = ThemeStorageFaults { point ->
            if (point == ThemeStoragePoint.AFTER_CATALOG_MOVE && primary.getAndIncrement() == 0) throw cancelled
            if (point == ThemeStoragePoint.BEFORE_ROLLBACK_MOVE && rollback.getAndIncrement() == 0) throw IOException("rollback")
        })
        lateinit var proposal: Proposal
        store.exclusively {
            proposal = prepareProposal(store)
            try {
                commit(store, proposal)
                throw AssertionError("Expected cancellation")
            } catch (actual: CancellationException) {
                assertSame(cancelled, actual)
                assertEquals(1, primary.get())
                assertEquals(1, rollback.get())
                assertTrue(actual.suppressed.any { it is ThemeException && it.issue == ThemeIssue.RECOVERY_REQUIRED })
            }
            assertArrayEquals(proposal.before.bytes, checkNotNull(store.beforeCatalog()).bytes)
            assertArrayEquals(ThemeStorageRecords.encode(proposal.replacement), catalogFile(store).readBytes())
        }
        val reopened = store(store.root)
        reopened.exclusively {
            assertNull(reopened.recover())
            assertArrayEquals(proposal.before.bytes, catalogFile(reopened).readBytes())
        }
    }

    @Test fun rollbackMemoryFailureKeepsTheOriginalCancellationAndTheExactRecoveryEvidence() {
        val cancelled = CancellationException("Controlled cancellation before marker")
        val memory = OutOfMemoryError("Controlled rollback memory failure")
        val primary = AtomicInteger()
        val rollback = AtomicInteger()
        val directory = store(faults = ThemeStorageFaults { point ->
            if (point == ThemeStoragePoint.AFTER_CATALOG_MOVE && primary.getAndIncrement() == 0) throw cancelled
            if (point == ThemeStoragePoint.BEFORE_ROLLBACK_MOVE && rollback.getAndIncrement() == 0) throw memory
        })
        lateinit var proposal: Proposal
        directory.exclusively {
            proposal = prepareProposal(directory)
            try {
                commit(directory, proposal)
                throw AssertionError("Expected cancellation")
            } catch (actual: CancellationException) {
                assertSame(cancelled, actual)
                assertEquals(1, primary.get())
                assertEquals(1, rollback.get())
                assertTrue(actual.suppressed.any { it is ThemeException && it.issue == ThemeIssue.RECOVERY_REQUIRED && it.cause === memory })
            }
            assertArrayEquals(proposal.before.bytes, checkNotNull(directory.beforeCatalog()).bytes)
            assertArrayEquals(ThemeStorageRecords.encode(proposal.replacement), catalogFile(directory).readBytes())
        }
        val reopened = store(directory.root)
        reopened.exclusively {
            assertNull(reopened.recover())
            assertArrayEquals(proposal.before.bytes, catalogFile(reopened).readBytes())
            assertArrayEquals(proposal.value.manifest, File(reopened.stagePayload(proposal.stageId), "theme.json").readBytes())
        }
    }

    @Test fun committedMarkerAgainstTheOldCatalogIsPreservedAsCorruptionWithoutRollback() {
        val fault = OneShotThemeFault(ThemeStoragePoint.AFTER_COMMIT_MARKER, abrupt = true)
        val directory = store(faults = fault)
        lateinit var proposal: Proposal
        lateinit var planBytes: ByteArray
        lateinit var markerBytes: ByteArray
        directory.exclusively {
            proposal = prepareProposal(directory)
            try {
                commit(directory, proposal)
                throw AssertionError("Expected interrupted committed transaction")
            } catch (_: SimulatedThemeProcessExit) {
                assertEquals(1, fault.hits.get())
            }
            planBytes = File(journal(directory), "plan.bin").readBytes()
            markerBytes = File(journal(directory), "committed").readBytes()
            catalogFile(directory).writeBytes(proposal.before.bytes)
        }
        val reopened = store(directory.root)
        reopened.exclusively {
            Fixture.expect(ThemeIssue.CORRUPT_STORAGE) { reopened.recover() }
            assertArrayEquals(proposal.before.bytes, catalogFile(reopened).readBytes())
            assertArrayEquals(planBytes, File(journal(reopened), "plan.bin").readBytes())
            assertArrayEquals(markerBytes, File(journal(reopened), "committed").readBytes())
            assertArrayEquals(proposal.value.manifest, File(reopened.installationDirectory(proposal.stageId), "theme.json").readBytes())
            assertTrue(reopened.stageArchive(proposal.stageId).isFile)
            catalogFile(reopened).writeBytes(ThemeStorageRecords.encode(proposal.replacement))
            assertNull(reopened.recover())
            assertEquals(proposal.replacement, reopened.loadCatalog().catalog)
        }
    }

    @Test fun committedPlanWithAnotherOperationCannotCleanItsStillReferencedPayload() {
        val fault = OneShotThemeFault(ThemeStoragePoint.AFTER_COMMIT_MARKER, abrupt = true)
        val directory = store(faults = fault)
        lateinit var proposal: Proposal
        lateinit var changedPlan: ByteArray
        directory.exclusively {
            proposal = prepareProposal(directory)
            try {
                commit(directory, proposal)
                throw AssertionError("Expected interrupted committed transaction")
            } catch (_: SimulatedThemeProcessExit) {
                assertEquals(1, fault.hits.get())
            }
            val file = File(journal(directory), "plan.bin")
            changedPlan = ThemeStorageRecords.encodePlan(ThemeStorageRecords.decodePlan(file.readBytes()).copy(operationId = Fixture.id()))
            file.writeBytes(changedPlan)
        }
        val reopened = store(directory.root)
        reopened.exclusively {
            Fixture.expect(ThemeIssue.CORRUPT_STORAGE) { reopened.recover() }
            assertArrayEquals(ThemeStorageRecords.encode(proposal.replacement), catalogFile(reopened).readBytes())
            assertArrayEquals(changedPlan, File(journal(reopened), "plan.bin").readBytes())
            assertArrayEquals(proposal.value.manifest, File(reopened.installationDirectory(proposal.stageId), "theme.json").readBytes())
            assertTrue(reopened.stageArchive(proposal.stageId).isFile)
        }
    }

    @Test fun substitutedCommittedDeleteIdCannotDeleteTheRemainingSelectedInstallation() {
        val original = store()
        lateinit var first: Proposal
        lateinit var second: Proposal
        lateinit var before: ThemeStoredCatalog
        original.exclusively {
            first = prepareProposal(original)
            commit(original, first)
            second = prepareProposal(original, Fixture.packageOf("fixture.second"))
            before = commit(original, second).stored
        }
        val operation = Fixture.id()
        val replacement = before.catalog.copy(revision = before.catalog.revision + 1,
            previous = ThemeBuiltins.defaultChoice,
            installed = before.catalog.installed.filter { it.ref.installationId != first.stageId },
            lastOperationId = operation, lastOperationDigest = ThemeStorageRecords.digest(operation.toByteArray()))
        val fault = OneShotThemeFault(ThemeStoragePoint.AFTER_COMMIT_MARKER, abrupt = true)
        val interrupted = store(original.root, fault)
        lateinit var changedPlan: ByteArray
        interrupted.exclusively {
            try {
                interrupted.commit(before, replacement, operation, null, null, first.stageId) {}
                throw AssertionError("Expected interrupted committed deletion")
            } catch (_: SimulatedThemeProcessExit) {
                assertEquals(1, fault.hits.get())
            }
            val file = File(journal(interrupted), "plan.bin")
            changedPlan = ThemeStorageRecords.encodePlan(ThemeStorageRecords.decodePlan(file.readBytes()).copy(deleteId = second.stageId))
            file.writeBytes(changedPlan)
        }
        val reopened = store(original.root)
        reopened.exclusively {
            Fixture.expect(ThemeIssue.CORRUPT_STORAGE) { reopened.recover() }
            assertArrayEquals(ThemeStorageRecords.encode(replacement), catalogFile(reopened).readBytes())
            assertArrayEquals(changedPlan, File(journal(reopened), "plan.bin").readBytes())
            assertArrayEquals(first.value.manifest, File(reopened.installationDirectory(first.stageId), "theme.json").readBytes())
            assertArrayEquals(second.value.manifest, File(reopened.installationDirectory(second.stageId), "theme.json").readBytes())
        }
    }

    @Test fun realInstallRenameFailurePreservesBothTheBlockedDestinationAndTheCandidate() {
        val hits = AtomicInteger()
        var prepared: Proposal? = null
        lateinit var directory: ThemeDirectoryStore
        directory = store(faults = ThemeStorageFaults { point ->
            if (point == ThemeStoragePoint.BEFORE_INSTALL_MOVE && hits.getAndIncrement() == 0) {
                val destination = directory.installationDirectory(checkNotNull(prepared).stageId)
                check(destination.mkdir())
                File(destination, "blocker").writeText("controlled unrelated destination")
            }
        })
        directory.exclusively {
            val proposal = prepareProposal(directory)
            prepared = proposal
            try {
                val failure = Fixture.expect(ThemeIssue.RECOVERY_REQUIRED) { commit(directory, proposal) }
                assertEquals(1, hits.get())
                assertTrue(failure.cause is IOException)
                assertArrayEquals(proposal.before.bytes, catalogFile(directory).readBytes())
                assertArrayEquals(proposal.value.manifest, File(directory.stagePayload(proposal.stageId), "theme.json").readBytes())
                val destination = directory.installationDirectory(proposal.stageId)
                val blocker = File(destination, "blocker")
                assertEquals("controlled unrelated destination", blocker.readText())
                assertTrue(blocker.delete())
                assertTrue(destination.delete())
                assertNull(directory.recover())
                assertArrayEquals(proposal.before.bytes, catalogFile(directory).readBytes())
            } finally {
                val destination = directory.installationDirectory(proposal.stageId)
                if (File(destination, "blocker").isFile) {
                    check(File(destination, "blocker").delete())
                    check(destination.delete())
                }
            }
        }
    }

    @Test fun realCatalogRenameFailureDoesNotDeleteThePreservedOldCatalogOrReportACommit() {
        val hits = AtomicInteger()
        lateinit var directory: ThemeDirectoryStore
        var obstruction: ThemeAtomicDestinationObstruction? = null
        directory = store(faults = ThemeStorageFaults { point ->
            if (point == ThemeStoragePoint.BEFORE_CATALOG_MOVE && hits.getAndIncrement() == 0) {
                obstruction = ThemeAtomicDestinationObstruction(catalogFile(directory)).also { it.obstruct() }
            }
        })
        directory.exclusively {
            val proposal = prepareProposal(directory)
            try {
                val failure = Fixture.expect(ThemeIssue.RECOVERY_REQUIRED) { commit(directory, proposal) }
                assertEquals(1, hits.get())
                assertTrue(failure.cause is IOException)
                checkNotNull(obstruction).assertPreserved(proposal.before.bytes)
                assertArrayEquals(proposal.before.bytes, checkNotNull(directory.beforeCatalog()).bytes)
                checkNotNull(obstruction).restore()
                assertNull(directory.recover())
                assertArrayEquals(proposal.before.bytes, catalogFile(directory).readBytes())
                assertArrayEquals(proposal.value.manifest, File(directory.stagePayload(proposal.stageId), "theme.json").readBytes())
            } finally {
                obstruction?.restore()
            }
        }
    }

    @Test fun actualDeleteFailureAndAlreadyCompletedDeleteHaveDistinctPersistentOutcomes() {
        for (point in listOf(ThemeStoragePoint.BEFORE_DELETE, ThemeStoragePoint.AFTER_DELETE)) {
            val fault = OneShotThemeFault(point)
            val store = store(faults = fault)
            lateinit var ref: ThemeRef
            lateinit var accepted: ThemeStoredCatalog
            store.exclusively {
                val installed = commit(store, prepareProposal(store)).stored
                ref = (installed.catalog.selected as ThemeChoice.Installed).ref
                val operation = Fixture.id()
                val replacement = installed.catalog.copy(revision = installed.catalog.revision + 1,
                    selected = ThemeBuiltins.defaultChoice, previous = ThemeBuiltins.defaultChoice, installed = emptyList(),
                    lastOperationId = operation, lastOperationDigest = ThemeStorageRecords.digest(operation.toByteArray()))
                val result = store.commit(installed, replacement, operation, null, null, ref.installationId) {}
                assertEquals(1, fault.hits.get())
                assertTrue(result.cleanupRequired)
                accepted = result.stored
                assertArrayEquals(accepted.bytes, catalogFile(store).readBytes())
                assertEquals(point == ThemeStoragePoint.BEFORE_DELETE, store.installationDirectory(ref.installationId).exists())
            }
            val reopened = store(store.root)
            reopened.exclusively {
                assertNull(reopened.recover())
                assertFalse(reopened.installationDirectory(ref.installationId).exists())
                assertArrayEquals(accepted.bytes, catalogFile(reopened).readBytes())
            }
        }
    }
}

internal class OneShotThemeFault(private val target: ThemeStoragePoint, private val abrupt: Boolean = false) : ThemeStorageFaults {
    val hits = AtomicInteger()
    override fun at(point: ThemeStoragePoint) {
        if (point != target || hits.getAndIncrement() != 0) return
        if (abrupt) throw SimulatedThemeProcessExit()
        throw IOException("Controlled theme transaction fault at $target")
    }
}

internal class SimulatedThemeProcessExit : Error("Simulated exit bypasses rollback and cleanup")

/** 把真实原文件保留在旁边，并使实际 ATOMIC_MOVE 的目标非空；恢复只操作本测试创建的对象。 */
private class ThemeAtomicDestinationObstruction(private val file: File) {
    private val preserved = File(checkNotNull(file.parentFile), "catalog-held")
    private val blocker = File(file, "blocker")
    private var displaced = false

    fun obstruct() {
        check(file.isFile && !preserved.exists())
        Files.move(file.toPath(), preserved.toPath())
        displaced = true
        check(file.mkdir())
        blocker.writeText("Keep the real rename destination nonempty")
    }

    fun assertPreserved(bytes: ByteArray) {
        assertTrue(file.isDirectory)
        assertTrue(blocker.isFile)
        assertArrayEquals(bytes, preserved.readBytes())
    }

    fun restore() {
        if (!displaced) return
        check(file.isDirectory)
        if (blocker.exists()) check(blocker.delete())
        check(file.list()?.isEmpty() == true)
        check(file.delete())
        Files.move(preserved.toPath(), file.toPath())
        displaced = false
    }
}
