package io.github.onlinemo.meloxwatch.data.fileoperations.fixture

import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** 只编入 Debug；文档操作落到独立测试目录，release 不包含提供方或测试入口。 */
class FileOperationFixtureDocumentsProvider : DocumentsProvider() {
    lateinit var authority: String
        private set
    var installedTree: FixtureDocumentTree? = null
    private fun tree(): FixtureDocumentTree = installedTree ?: FileOperationFixtureRegistry.tree(authority)

    override fun attachInfo(context: Context, info: ProviderInfo) {
        authority = info.authority
        super.attachInfo(context, info)
    }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: arrayOf(
        DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
        DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS,
    ))

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        tree().query(listOf(documentId), projection)

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        tree().query(tree().childIds(parentDocumentId), projection)

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = tree().isChild(parentDocumentId, documentId)

    override fun findDocumentPath(parentDocumentId: String?, childDocumentId: String): DocumentsContract.Path =
        tree().documentPath(parentDocumentId, childDocumentId)

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        tree().open(documentId, mode, signal)

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String =
        tree().create(parentDocumentId, mimeType, displayName)

    override fun renameDocument(documentId: String, displayName: String): String = tree().rename(documentId, displayName)

    override fun moveDocument(sourceDocumentId: String, sourceParentDocumentId: String, targetParentDocumentId: String): String =
        tree().move(sourceDocumentId, sourceParentDocumentId, targetParentDocumentId)

    override fun deleteDocument(documentId: String) {
        tree().delete(documentId)
    }
}

internal object FileOperationFixtureRegistry {
    private val trees = HashMap<String, FixtureDocumentTree>()
    fun install(authority: String, tree: FixtureDocumentTree) = synchronized(trees) {
        check(trees.putIfAbsent(authority, tree) == null)
    }
    fun tree(authority: String): FixtureDocumentTree = synchronized(trees) {
        trees[authority] ?: throw FileNotFoundException("Fixture scope is not installed")
    }
    fun remove(authority: String, tree: FixtureDocumentTree) = synchronized(trees) {
        check(trees[authority] === tree)
        trees.remove(authority)
    }
}

internal data class FixtureDocument(val id: String, val parentId: String?, val file: File, val mime: String)

/** 文件、游标和 FD 都是真实资源；故障 hook 只用于精确外部调用窗口，不构造成功证明。 */
class FixtureDocumentTree(val root: File) : AutoCloseable {
    private val lock = Any()
    private val documents = LinkedHashMap<String, FixtureDocument>()
    private val descriptors = ArrayList<ParcelFileDescriptor>()
    val creates = AtomicInteger()
    val renames = AtomicInteger()
    val moves = AtomicInteger()
    val deletes = AtomicInteger()
    val opened = AtomicInteger()
    val cursors = AtomicInteger()
    val cursorCloses = AtomicInteger()
    val modes = ArrayList<String>()
    var writable = true
    var readable = true
    var deletable = true
    var changeIdOnRename = true
    var beforeDelete: (() -> Unit)? = null
    var afterRename: (() -> Unit)? = null
    var afterMove: (() -> Unit)? = null
    var registerDescriptor: ((ParcelFileDescriptor, Boolean) -> Unit)? = null

    init {
        check(root.mkdirs() || root.isDirectory)
        documents[ROOT_ID] = FixtureDocument(ROOT_ID, null, root, DocumentsContract.Document.MIME_TYPE_DIR)
    }

    fun file(id: String): File = synchronized(lock) { requireDocument(id).file }
    fun name(id: String): String = file(id).name
    fun bytes(id: String): ByteArray = Files.readAllBytes(file(id).toPath())
    fun idNamed(name: String): String? = synchronized(lock) {
        documents.values.singleOrNull { it.file.name == name && it.file.exists() }?.id
    }

    fun putAudio(name: String, bytes: ByteArray, mime: String, parentId: String = ROOT_ID): String = synchronized(lock) {
        val parent = requireDocument(parentId)
        check(parent.file.isDirectory)
        val target = child(parent.file, name)
        check(!target.exists())
        Files.write(target.toPath(), bytes)
        UUID.randomUUID().toString().also { documents[it] = FixtureDocument(it, parentId, target, mime) }
    }

    fun childIds(parentId: String): List<String> = synchronized(lock) {
        requireReadable()
        requireDocument(parentId)
        documents.values.filter { it.parentId == parentId && it.file.exists() }.map { it.id }
    }

    fun isChild(parentId: String, id: String): Boolean = synchronized(lock) {
        requireReadable()
        requireDocument(parentId)
        var parent = documents[id]?.parentId
        while (parent != null) {
            if (parent == parentId) return@synchronized true
            parent = documents[parent]?.parentId
        }
        false
    }

    fun documentPath(parentId: String?, id: String): DocumentsContract.Path = synchronized(lock) {
        requireReadable()
        val path = ArrayList<String>()
        val visited = HashSet<String>()
        var current = requireDocument(id)
        while (true) {
            if (!visited.add(current.id)) throw FileNotFoundException("Fixture ancestry is cyclic")
            path += current.id
            if (current.id == parentId || current.parentId == null && parentId == null) break
            current = requireDocument(current.parentId ?: throw FileNotFoundException("Fixture is outside the selected tree"))
        }
        DocumentsContract.Path(null, path.asReversed())
    }

    fun query(ids: List<String>, projection: Array<out String>?): Cursor = synchronized(lock) {
        requireReadable()
        val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_FLAGS)
        cursors.incrementAndGet()
        object : MatrixCursor(columns, ids.size) {
            private var retired = false
            override fun close() {
                if (!retired) {
                    retired = true
                    cursorCloses.incrementAndGet()
                }
                super.close()
            }
        }.apply {
            for (id in ids) {
                val document = documents[id]?.takeIf { it.file.exists() } ?: continue
                val row: List<Any?> = columns.map { column ->
                    when (column) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> document.file.name
                        DocumentsContract.Document.COLUMN_MIME_TYPE -> document.mime
                        DocumentsContract.Document.COLUMN_SIZE -> if (document.file.isFile) document.file.length() else null
                        DocumentsContract.Document.COLUMN_FLAGS -> if (document.file.isDirectory)
                            DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                        else DocumentsContract.Document.FLAG_SUPPORTS_WRITE or DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                            DocumentsContract.Document.FLAG_SUPPORTS_RENAME or DocumentsContract.Document.FLAG_SUPPORTS_MOVE
                        else -> null
                    }
                }
                addRow(row)
            }
        }
    }

    fun open(id: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor = synchronized(lock) {
        requireReadable()
        signal?.throwIfCanceled()
        val file = requireDocument(id).file
        if (!file.isFile) throw FileNotFoundException("Fixture regular document missing")
        val flags = when (mode) {
            "r" -> ParcelFileDescriptor.MODE_READ_ONLY
            "rw" -> {
                requireWritable()
                ParcelFileDescriptor.MODE_READ_WRITE
            }
            else -> throw IllegalArgumentException("Fixture rejects truncating or unknown modes")
        }
        val descriptor = ParcelFileDescriptor.open(file, flags)
        try {
            registerDescriptor?.invoke(descriptor, mode == "rw")
            descriptors += descriptor
            modes += mode
            opened.incrementAndGet()
            descriptor
        } catch (failure: Throwable) {
            descriptor.close()
            throw failure
        }
    }

    fun create(parentId: String, mime: String, name: String): String = synchronized(lock) {
        requireWritable()
        val parent = requireDocument(parentId)
        check(parent.file.isDirectory)
        val file = child(parent.file, name)
        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) Files.createDirectory(file.toPath()) else Files.createFile(file.toPath())
        val id = UUID.randomUUID().toString()
        documents[id] = FixtureDocument(id, parentId, file, mime)
        creates.incrementAndGet()
        id
    }

    fun rename(id: String, name: String): String = synchronized(lock) {
        requireWritable()
        val old = requireDocument(id)
        val file = child(checkNotNull(old.file.parentFile), name)
        Files.move(old.file.toPath(), file.toPath())
        val actualId = if (changeIdOnRename) UUID.randomUUID().toString() else id
        documents.remove(id)
        documents[actualId] = old.copy(id = actualId, file = file)
        renames.incrementAndGet()
        afterRename?.invoke()
        actualId
    }

    fun move(id: String, sourceParentId: String, targetParentId: String): String = synchronized(lock) {
        requireWritable()
        val old = requireDocument(id)
        check(old.parentId == sourceParentId)
        val destination = requireDocument(targetParentId)
        check(destination.file.isDirectory)
        val file = child(destination.file, old.file.name)
        Files.move(old.file.toPath(), file.toPath())
        val actualId = UUID.randomUUID().toString()
        documents.remove(id)
        documents[actualId] = old.copy(id = actualId, parentId = targetParentId, file = file)
        moves.incrementAndGet()
        afterMove?.invoke()
        actualId
    }

    fun delete(id: String) = synchronized(lock) {
        requireWritable()
        if (!deletable) throw SecurityException("Fixture delete permission denied")
        beforeDelete?.invoke()
        val old = requireDocument(id)
        Files.delete(old.file.toPath())
        documents.remove(id)
        deletes.incrementAndGet()
    }

    fun assertClosed() = synchronized(lock) {
        check(cursors.get() == cursorCloses.get())
        check(descriptors.all { descriptor ->
            try { !descriptor.fileDescriptor.valid() } catch (_: IllegalStateException) { true }
        })
    }

    override fun close() {
        assertClosed()
        val targetRoot = root.canonicalFile
        for (file in targetRoot.walkBottomUp()) {
            check(file.canonicalFile.toPath().startsWith(targetRoot.toPath()))
            check(file.delete() || !file.exists())
        }
    }

    private fun child(parent: File, name: String): File {
        check(name.isNotBlank() && name !in setOf(".", "..") && '/' !in name && '\\' !in name)
        return File(parent, name).also { check(it.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) }
    }
    private fun requireDocument(id: String): FixtureDocument = documents[id]?.takeIf { it.file.exists() }
        ?: throw FileNotFoundException("Fixture document missing")
    private fun requireReadable() { if (!readable) throw SecurityException("Fixture read permission denied") }
    private fun requireWritable() { if (!writable) throw SecurityException("Fixture write permission denied") }

    companion object { const val ROOT_ID = "lp10-fixture-root" }
}
