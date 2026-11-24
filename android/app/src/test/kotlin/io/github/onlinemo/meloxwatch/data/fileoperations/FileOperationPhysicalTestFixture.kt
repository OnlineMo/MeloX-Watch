package io.github.onlinemo.meloxwatch.data.fileoperations

import android.app.Application
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
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
        // 两个真实提供方实例需要独立组件名；第二个authority不能覆盖第一个的发现记录。
        val providerType: Class<out HostFileOperationFixtureDocumentsProvider> = if (trees.isEmpty())
            HostFileOperationFixtureDocumentsProvider::class.java else SecondaryHostFileOperationFixtureDocumentsProvider::class.java
        val info = ProviderInfo().apply {
            this.authority = authority
            packageName = context.packageName
            name = providerType.name
            applicationInfo = context.applicationInfo
            exported = true
            grantUriPermissions = true
            readPermission = android.Manifest.permission.MANAGE_DOCUMENTS
            writePermission = android.Manifest.permission.MANAGE_DOCUMENTS
        }
        val provider = Robolectric.buildContentProvider(providerType).create(info).get()
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


/** 主机resolver直接调用旧重载；仍由平台最终TREE dispatcher核验并打开真实fixture资源。 */
internal open class HostFileOperationFixtureDocumentsProvider : FileOperationFixtureDocumentsProvider() {
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
        cancellationSignal: CancellationSignal?,
    ): Cursor? {
        cancellationSignal?.throwIfCanceled()
        val queryArgs = if (selection == null && selectionArgs == null && sortOrder == null) {
            null
        } else {
            Bundle().apply {
                selection?.let { putString(ContentResolver.QUERY_ARG_SQL_SELECTION, it) }
                selectionArgs?.let { putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, it) }
                sortOrder?.let { putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, it) }
            }
        }
        return super.query(uri, projection, queryArgs, cancellationSignal)
    }
}

internal class SecondaryHostFileOperationFixtureDocumentsProvider : HostFileOperationFixtureDocumentsProvider()
