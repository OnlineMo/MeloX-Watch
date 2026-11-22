package io.github.onlinemo.meloxwatch.data.fileoperations

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.content.pm.ProviderInfo
import android.os.CancellationSignal
import android.provider.DocumentsContract
import io.github.onlinemo.meloxwatch.data.fileoperations.fixture.FileOperationFixtureDocumentsProvider
import io.github.onlinemo.meloxwatch.data.fileoperations.fixture.FixtureDocumentTree
import io.github.onlinemo.meloxwatch.data.fileoperations.fixture.fixtureFileTarget
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], application = Application::class, shadows = [FileOperationFixtureOsShadow::class])
abstract class FileOperationPhysicalTestFixture {
    @get:Rule val temporaryFolder = TemporaryFolder()
    protected lateinit var context: Context
    protected lateinit var tree: FixtureDocumentTree
    protected lateinit var authority: String
    protected lateinit var adapter: SafOriginalFileAdapter
    private val trees = ArrayList<FixtureDocumentTree>()

    @Before fun openPhysicalTree() {
        context = RuntimeEnvironment.getApplication()
        authority = "${context.packageName}.lp10-primary"
        tree = newTree(authority, "primary")
        adapter = SafOriginalFileAdapter(context)
    }

    protected fun newTree(authority: String, directory: String): FixtureDocumentTree {
        val tree = FixtureDocumentTree(File(temporaryFolder.root, directory))
        tree.registerDescriptor = FileOperationFixtureOsShadow::register
        val info = ProviderInfo().apply {
            this.authority = authority
            packageName = context.packageName
            name = FileOperationFixtureDocumentsProvider::class.java.name
            applicationInfo = context.applicationInfo
            exported = false
            grantUriPermissions = true
        }
        val provider = Robolectric.buildContentProvider(FileOperationFixtureDocumentsProvider::class.java).create(info).get()
        provider.installedTree = tree
        val packageManager = Shadows.shadowOf(context.packageManager)
        packageManager.addOrUpdateProvider(info)
        packageManager.addIntentFilterForProvider(ComponentName(info.packageName, info.name), IntentFilter(DocumentsContract.PROVIDER_INTERFACE))
        ShadowContentResolver.registerProviderInternal(authority, provider)
        trees += tree
        return tree
    }

    protected fun provedTarget(id: String, kind: FileOperationKind): FileOperationTarget {
        val target = fixtureFileTarget(context, authority, tree, id)
        return target.copy(proof = adapter.inspect(target, kind, CancellationSignal()))
    }

    protected fun item(target: FileOperationTarget): FileOperationItem = FileOperationItem(UUID.randomUUID().toString(), 0L, target)

    protected fun expectIssue(expected: FileOperationIssue, action: () -> Unit) {
        try { action() } catch (failure: FileOperationException) {
            assertEquals(expected, failure.issue)
            return
        }
        throw AssertionError("Expected $expected")
    }

    @After fun closePhysicalTrees() {
        trees.forEach(FixtureDocumentTree::close)
    }
}
