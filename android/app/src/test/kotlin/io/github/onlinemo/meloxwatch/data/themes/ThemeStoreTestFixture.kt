package io.github.onlinemo.meloxwatch.data.themes

import io.github.onlinemo.meloxwatch.data.themes.ThemeTestFixtures as Fixture
import java.io.File
import org.junit.Rule
import org.junit.rules.TemporaryFolder

internal abstract class ThemeStoreTestFixture {
    @get:Rule val temporary = TemporaryFolder()

    data class Proposal(
        val before: ThemeStoredCatalog,
        val replacement: ThemeCatalog,
        val operationId: String,
        val stageId: String,
        val value: Fixture.Package,
    )

    fun store(directory: File = File(temporary.root, Fixture.id()), faults: ThemeStorageFaults = ThemeStorageFaults {}): ThemeDirectoryStore =
        ThemeDirectoryStore(directory, { Fixture.ROOM }, faults)

    fun prepareProposal(store: ThemeDirectoryStore, value: Fixture.Package = Fixture.packageOf()): Proposal {
        val before = store.loadCatalog()
        val stage = Fixture.id()
        store.beginStage(stage)
        Fixture.payload(store.stagePayload(stage), value)
        val archive = value.zip()
        store.stageArchive(stage).writeBytes(archive)
        val manifest = ThemeManifestReader.read(value.manifest)
        val ref = ThemeRef(stage, manifest.id, ThemeStorageRecords.digest(value.manifest))
        val installation = ThemeInstallation(ref, manifest.displayName, ThemeStorageRecords.digest(archive),
            manifest.resources.sumOf { it.byteCount }, manifest.resources.filter { it.kind == ThemeResourceKind.FONT }.sumOf { it.byteCount })
        val operation = Fixture.id()
        val replacement = before.catalog.copy(revision = before.catalog.revision + 1, selected = ThemeChoice.Installed(ref),
            previous = before.catalog.selected, installed = before.catalog.installed + installation,
            lastOperationId = operation, lastOperationDigest = ThemeStorageRecords.digest(operation.toByteArray()))
        return Proposal(before, replacement, operation, stage, value)
    }

    fun commit(store: ThemeDirectoryStore, proposal: Proposal, cancellation: () -> Unit = {}): ThemeStoreCommit =
        store.commit(proposal.before, proposal.replacement, proposal.operationId, proposal.stageId, proposal.stageId, null, cancellation)

    fun catalogFile(store: ThemeDirectoryStore): File = File(store.root, "catalog.bin")
    fun journal(store: ThemeDirectoryStore): File = File(store.root, "transaction")
}
