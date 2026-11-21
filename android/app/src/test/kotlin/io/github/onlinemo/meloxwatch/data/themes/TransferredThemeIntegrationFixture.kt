package io.github.onlinemo.meloxwatch.data.themes

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.imports.AudioCopyQuota
import io.github.onlinemo.meloxwatch.data.imports.ImportLimits
import io.github.onlinemo.meloxwatch.data.imports.LocalImportManager
import io.github.onlinemo.meloxwatch.data.imports.OwnedAudioProvider
import io.github.onlinemo.meloxwatch.data.library.LocalAudioMetadata
import io.github.onlinemo.meloxwatch.data.library.RoomImportLedger
import io.github.onlinemo.meloxwatch.data.library.RoomLocalLibraryRepository
import io.github.onlinemo.meloxwatch.data.library.RoomPlaylistFences
import io.github.onlinemo.meloxwatch.data.local.LocalCandidateCursor
import io.github.onlinemo.meloxwatch.data.local.LocalMetadataReader
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.LocalSourceAccess
import io.github.onlinemo.meloxwatch.data.local.LocalSourceObservation
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.playlists.RoomPlaylistRepository
import io.github.onlinemo.meloxwatch.data.transfer.ImportedAudioTransferBackend
import io.github.onlinemo.meloxwatch.data.transfer.LocalTransferContentValidator
import io.github.onlinemo.meloxwatch.data.transfer.LocalTransferQuotaAccounting
import io.github.onlinemo.meloxwatch.data.transfer.PlatformAudioTransferValidator
import io.github.onlinemo.meloxwatch.data.transfer.RoomCompanionTransferBackend
import io.github.onlinemo.meloxwatch.data.transfer.ThemeReceivingTransferBackend
import io.github.onlinemo.meloxwatch.data.transfer.TransferBackend
import io.github.onlinemo.meloxwatch.data.transfer.TransferCapability
import io.github.onlinemo.meloxwatch.data.transfer.TransferClock
import io.github.onlinemo.meloxwatch.data.transfer.TransferDiskSpace
import io.github.onlinemo.meloxwatch.data.transfer.TransferException
import io.github.onlinemo.meloxwatch.data.transfer.TransferFingerprint
import io.github.onlinemo.meloxwatch.data.transfer.TransferHash
import io.github.onlinemo.meloxwatch.data.transfer.TransferIssue
import io.github.onlinemo.meloxwatch.data.transfer.TransferLimits
import io.github.onlinemo.meloxwatch.data.transfer.TransferOperationBinding
import io.github.onlinemo.meloxwatch.data.transfer.TransferPermit
import io.github.onlinemo.meloxwatch.data.transfer.TransferProtocolCodec
import io.github.onlinemo.meloxwatch.data.transfer.TransferReceipt
import io.github.onlinemo.meloxwatch.data.transfer.TransferRequest
import io.github.onlinemo.meloxwatch.data.transfer.TransferScope
import io.github.onlinemo.meloxwatch.data.transfer.TransferSessionAuthority
import io.github.onlinemo.meloxwatch.data.transfer.TransferUploadDeclaration
import io.github.onlinemo.meloxwatch.data.transfer.TransferUploadKind
import io.github.onlinemo.meloxwatch.data.transfer.TransferUploadStage
import io.github.onlinemo.meloxwatch.data.transfer.TransferUploadStore
import io.github.onlinemo.meloxwatch.data.themes.ThemeTestFixtures as Packages
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowContentResolver

/**
 * 每个用例独占真实私有文件、Room V6、上传 stage 和物理 worker。
 * 上传始终由实际 store 校验；本机导入通过真实 ContentProvider 的只读文件描述符。
 */
internal class TransferredThemeIntegrationFixture(root: File) {
    private val privateRoot = File(root, "private").also { check(it.mkdirs()) }
    val context: Context = object : ContextWrapper(RuntimeEnvironment.getApplication() as Context) {
        override fun getNoBackupFilesDir(): File = privateRoot
    }
    val themeDirectory = File(privateRoot, "local-themes")
    private val fontDirectory = File(privateRoot, "lyrics-fonts").also { check(it.mkdirs()) }
    private val databaseFile = File(root, "theme-integration.db")
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "theme-integration-physical").apply { isDaemon = true }
    }
    private val dispatcher = worker.asCoroutineDispatcher()
    val io = LocalProviderIo(dispatcher)
    val sqlProbe = ThemeSqlStepProbe()
    var database = openDatabase()
        private set
    var elapsed = 1_000L
    private val clock = TransferClock { elapsed }
    val target = TransferFingerprint.digest("synthetic-theme-watch-key".toByteArray())
    val peer = TransferFingerprint.digest("synthetic-theme-phone-key".toByteArray())
    val authority = TransferSessionAuthority(clock)
    private lateinit var scope: TransferScope
    lateinit var library: RoomLocalLibraryRepository
        private set
    private lateinit var playlists: RoomPlaylistRepository
    lateinit var audioLedger: RoomImportLedger
        private set
    private lateinit var audioManager: LocalImportManager
    private lateinit var audioQuota: AudioCopyQuota
    private lateinit var themeQuota: ThemeTransferQuota
    private lateinit var sharedBudget: SharedFontThemeStorageBudget
    lateinit var themes: LocalThemeRepository
        private set
    lateinit var coordinator: TransferredThemeCoordinator
        private set
    lateinit var backend: TransferBackend
        private set
    lateinit var store: TransferUploadStore
        private set
    val selectedProvider = ThemeSelectedFileProvider()
    private val originals = mutableListOf<ThemeOriginalFile>()
    private val stages = ConcurrentHashMap<String, TransferUploadStage>()
    val commitAttempts = AtomicInteger()
    @Volatile var storageFault: ((ThemeStoragePoint) -> Unit)? = null
    val catalogFile: File get() = File(themeDirectory, "catalog.bin")
    val guardFile: File get() = File(themeDirectory, "transfer-apply.bin")
    private var closed = false

    suspend fun initialize() {
        val info = ProviderInfo().apply { authority = ThemeSelectedFileProvider.AUTHORITY }
        selectedProvider.attachInfo(context, info)
        ShadowContentResolver.registerProviderInternal(ThemeSelectedFileProvider.AUTHORITY, selectedProvider)
        connectQuotas()
        connectRepositories()
        connectUploadStore()
        newSession()
        coordinator.load()
    }

    fun newSession() {
        authority.stop()
        val capabilities = setOf(TransferCapability.UPLOAD_THEME, TransferCapability.UPLOAD_AUDIO)
        val opening = authority.begin(target)
        authority.pair(peer, TransferRequest.Pair(Packages.id(), target, opening.sessionId,
            checkNotNull(authority.pairingCodeForLocalUi()), capabilities))
        authority.approveForLocalUi(peer, capabilities)
        val authentication = authority.authenticate(peer, target, opening.sessionId)
        scope = TransferScope(target, opening.sessionId, authentication.bearer)
    }

    fun themePermit(): TransferPermit = permit(TransferUploadKind.THEME)

    fun themeDeclaration(bytes: ByteArray): TransferUploadDeclaration = TransferUploadDeclaration(
        TransferUploadKind.THEME, "Selected theme.zip", "application/vnd.melox.theme+zip",
        bytes.size.toLong(), TransferHash.digest(bytes), 0L,
    )

    fun binding(declaration: TransferUploadDeclaration): TransferOperationBinding = TransferProtocolCodec.operationBinding(
        peer, TransferRequest.BeginUpload(Packages.id(), scope, declaration),
    )

    suspend fun receivePackage(value: Packages.Package): TransferReceipt {
        val bytes = value.zip()
        val declaration = themeDeclaration(bytes)
        return upload(bytes, declaration, binding(declaration))
    }

    suspend fun uploadAudio(bytes: ByteArray): TransferReceipt {
        val declaration = TransferUploadDeclaration(TransferUploadKind.AUDIO, "Selected audio.wav", "audio/wav",
            bytes.size.toLong(), TransferHash.digest(bytes), 0L)
        return upload(bytes, declaration, binding(declaration))
    }

    suspend fun prepareStage(bytes: ByteArray, declaration: TransferUploadDeclaration,
        binding: TransferOperationBinding): TransferUploadStage {
        val stage = store.begin(binding, declaration, permit(declaration.kind))
        stages[stage.ticket] = stage
        try {
            var offset = 0
            while (offset < bytes.size) {
                val next = minOf(offset + TransferLimits.CHUNK_BYTES, bytes.size)
                stage.append(permit(declaration.kind), offset.toLong(), bytes.copyOfRange(offset, next))
                offset = next
            }
            return stage
        } catch (failure: Exception) {
            withContext(NonCancellable) { retireStage(stage) }
            throw failure
        }
    }

    suspend fun upload(bytes: ByteArray, declaration: TransferUploadDeclaration,
        binding: TransferOperationBinding): TransferReceipt {
        val stage = prepareStage(bytes, declaration, binding)
        try {
            val granted = permit(declaration.kind)
            val verified = stage.verify(granted)
            stage.markCommitting()
            val receipt = backend.commitUpload(binding, verified, granted)
            // 只把真实 owner 返回且已存在于 Room 的结果交给 stage，绝不在夹具中创建收据。
            assertCanonical(receipt)
            stage.recordCommitted(receipt)
            return receipt
        } finally {
            withContext(NonCancellable) {
                retireStage(stage)
                withTimeout(5_000L) { awaitPhysicalIdle() }
            }
        }
    }

    suspend fun prepareLocal(value: Packages.Package): ThemeSelectedImport {
        val requestId = Packages.id()
        val bytes = value.zip()
        val source = File(privateRoot, "selected-$requestId.zip")
        source.writeBytes(bytes)
        val uri = selectedProvider.register(source)
        originals.add(ThemeOriginalFile(source, bytes))
        val request = ThemeImportRequest(requestId, uri.toString())
        return ThemeSelectedImport(request, coordinator.prepareImport(request))
    }

    suspend fun installLocal(value: Packages.Package): ThemeApplyResult {
        val selected = prepareLocal(value)
        return coordinator.apply(Packages.apply(selected.preview))
    }

    suspend fun candidate(operationId: String): TransferredThemeCandidateEntity? =
        database.transferredThemeCandidateDao().candidate(operationId)

    fun receivedDirectory(operationId: String): File = File(File(themeDirectory, "received"), operationId)
    fun receivedInput(operationId: String): File = File(receivedDirectory(operationId), "input.zip")
    fun receivedSeal(operationId: String): File = File(receivedDirectory(operationId), "candidate.bin")
    fun receivedPayload(operationId: String): File = File(receivedDirectory(operationId), "payload")
    fun receivedManifest(operationId: String): File = File(receivedPayload(operationId), "theme.json")
    fun stagingDirectory(previewToken: String): File = File(File(themeDirectory, "staging"), previewToken)
    fun installedDirectory(ref: ThemeRef): File = File(File(themeDirectory, "installed"), ref.installationId)
    fun installedManifest(operationId: String): File = File(File(File(themeDirectory, "installed"), operationId), "theme.json")

    suspend fun assertCanonical(receipt: TransferReceipt) {
        val row = checkNotNull(database.transferRoomDao().operation(receipt.binding.operationId))
        assertEquals(receipt.binding.kind.wire, row.operationKind)
        assertEquals(receipt.binding.target.forLocalDisplay(), row.targetFingerprint)
        assertEquals(receipt.binding.peer.forLocalDisplay(), row.peerFingerprint)
        assertEquals(receipt.binding.payloadHash.forLocalStorage(), row.payloadHash)
        assertEquals(receipt.result.wire, row.resultKind)
        assertEquals(receipt.revision, row.appliedRevision)
        assertEquals(receipt.resultId, row.resultId)
        assertEquals(receipt.actualBytes, row.actualBytes)
    }

    fun assertSameReceipt(expected: TransferReceipt, actual: TransferReceipt) {
        assertTrue(expected.binding.sameOperation(actual.binding))
        assertEquals(expected.result, actual.result)
        assertEquals(expected.revision, actual.revision)
        assertEquals(expected.resultId, actual.resultId)
        assertEquals(expected.actualBytes, actual.actualBytes)
    }

    suspend fun assertNoCompletion(operationId: String) {
        assertNull(candidate(operationId))
        val operation = database.transferRoomDao().operation(operationId)
        assertNull(operation?.resultKind)
        assertNull(operation?.appliedRevision)
        assertNull(operation?.resultId)
        assertNull(operation?.actualBytes)
    }

    fun assertOriginalSourcesUnchangedAndClosed() {
        for (original in originals) assertArrayEquals(original.bytes, original.file.readBytes())
        assertEquals(0, selectedProvider.writes.get())
        assertEquals(0, selectedProvider.queries.get())
        selectedProvider.assertDescriptorsClosed()
    }

    suspend fun retireStage(stage: TransferUploadStage) = withContext(NonCancellable) {
        stage.discard()
        stages.remove(stage.ticket, stage)
        Unit
    }

    suspend fun awaitPhysicalIdle() {
        store.awaitIdle()
        io.awaitIdle()
    }

    suspend fun reopen() {
        withTimeout(10_000L) {
            check(stages.isEmpty()) { "Reopen cannot abandon an upload owner" }
            awaitPhysicalIdle()
        }
        database.close()
        database = openDatabase()
        connectRepositories()
        newSession()
    }

    suspend fun closeAwaitingWork() = withContext(NonCancellable) {
        if (closed) return@withContext
        authority.stop()
        try {
            for (stage in stages.values.toList()) {
                withTimeout(10_000L) {
                    stage.backendRetirement?.await()
                    io.awaitIdle()
                }
                retireStage(stage)
            }
            if (::store.isInitialized) withTimeout(10_000L) { awaitPhysicalIdle() }
        } finally {
            // 物理 idle 不代表 Room 已结束；先等待真实 backend acknowledgment，再关闭自有资源。
            withTimeout(10_000L) {
                for (stage in stages.values) stage.backendRetirement?.await()
                io.awaitIdle()
            }
            selectedProvider.closeRemainingDescriptors()
            database.close()
            dispatcher.close()
            check(worker.awaitTermination(5L, TimeUnit.SECONDS)) { "Physical worker did not terminate" }
            closed = true
        }
    }

    private fun permit(kind: TransferUploadKind): TransferPermit = authority.authorize(peer, scope, kind.capability)

    private fun connectQuotas() {
        check(!::audioQuota.isInitialized && !::themeQuota.isInitialized)
        // 两个回调只读同一生命周期的真实租约；重开 Room 不创建另一组存储所有者。
        themeQuota = ThemeTransferQuota(
            themeDirectory,
            File(privateRoot, AudioCopyQuota.TRANSFER_DIRECTORY),
            io,
            audioPendingBytes = { audioQuota.pendingBytesOnPhysicalWorker() },
            audioTickets = { audioQuota.transferTicketsOnPhysicalWorker() },
            freeBytes = { ROOM_BYTES },
        )
        audioQuota = AudioCopyQuota(
            context,
            io,
            otherPendingBytes = { themeQuota.pendingBytesOnPhysicalWorker() },
            otherTransferTickets = { themeQuota.ticketsOnPhysicalWorker() },
            freeBytes = { ROOM_BYTES },
        )
        sharedBudget = SharedFontThemeStorageBudget(
            fontDirectory,
            themeDirectory,
            io,
            incomingThemes = themeQuota,
            audioPendingBytes = { audioQuota.pendingBytesOnPhysicalWorker() },
        )
    }

    private fun connectUploadStore() {
        check(!::store.isInitialized)
        store = TransferUploadStore(
            File(privateRoot, AudioCopyQuota.TRANSFER_DIRECTORY),
            io,
            LocalTransferQuotaAccounting(audioQuota, themeQuota),
            LocalTransferContentValidator(PlatformAudioTransferValidator(), themeQuota),
            diskSpace = TransferDiskSpace { ROOM_BYTES },
            clock = clock,
        )
    }

    private suspend fun connectRepositories() {
        library = RoomLocalLibraryRepository(database, { elapsed }, { 1_790_000_000_000L })
        val fences = RoomPlaylistFences(database)
        playlists = RoomPlaylistRepository(database, database.playlistDao(), { elapsed }, { 1_790_000_000_000L },
            processId = "theme-integration-playlists", folderFenceVerifier = fences, libraryFenceVerifier = fences)
        library.initialize()
        playlists.initialize()
        themes = LocalThemeRepository(themeDirectory, io, AndroidThemePackageSource(context.contentResolver),
            sharedBudget.themeBudget, availableBytes = sharedBudget::availableBytesForThemesOnWorker,
            sharedBudget = sharedBudget,
            faults = ThemeStorageFaults { point ->
                if (point == ThemeStoragePoint.BEFORE_COMMIT_MARKER) commitAttempts.incrementAndGet()
                storageFault?.invoke(point)
            })
        coordinator = TransferredThemeCoordinator(database, themes, themeQuota)
        audioLedger = RoomImportLedger(library, OwnedAudioProvider.authority(context))
        audioManager = LocalImportManager(context, noExternalSource, LocalMetadataReader(context, io), audioLedger, io,
            readMetadata = { file, signal ->
                // 元数据观测仅针对测试 WAV；格式仍由实际验证器核验，不声称通过 Android 解码验收。
                signal.throwIfCanceled()
                check(file.isFile && file.length() > 44L)
                LocalAudioMetadata(title = "Transferred fixture audio", mimeType = "audio/wav",
                    durationMs = (file.length() - 44L) * 1000L / 16_000L)
            }, freeBytes = { ROOM_BYTES }, syncDirectory = ::syncAudioDirectory, copyQuota = audioQuota)
        val room = RoomCompanionTransferBackend(database, library, playlists, ImportedAudioTransferBackend(audioManager),
            clock, processId = "theme-integration-backend")
        backend = ThemeReceivingTransferBackend(room, coordinator)
    }

    private fun openDatabase(): MeloXDatabase = Room.databaseBuilder(context, MeloXDatabase::class.java, databaseFile.absolutePath)
        .setDriver(sqlProbe)
        .addMigrations(MeloXDatabase.MIGRATION_1_2, MeloXDatabase.MIGRATION_2_3, MeloXDatabase.MIGRATION_3_4,
            MeloXDatabase.MIGRATION_4_5, MeloXDatabase.MIGRATION_5_6)
        .build()

    private fun syncAudioDirectory(directory: File) {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            channel.force(true)
        }
    }

    private val noExternalSource = object : LocalSourceAccess {
        override suspend fun observe(source: LocalSourceSpec): LocalSourceObservation =
            throw AssertionError("Transferred audio must not acquire an external source")

        override suspend fun open(source: LocalSourceSpec, parentObjectId: String?,
            sinceMediaGeneration: Long?): LocalCandidateCursor =
            throw AssertionError("Transferred audio must not enumerate an external source")
    }

    companion object {
        private const val ROOM_BYTES = ImportLimits.FREE_RESERVE_BYTES + 2 * ImportLimits.QUOTA_BYTES + ThemeLimits.STORED_BYTES
    }
}

internal data class ThemeSelectedImport(val request: ThemeImportRequest, val preview: ThemePreview)
private data class ThemeOriginalFile(val file: File, val bytes: ByteArray)

/** 只包裹公共 step 边界，所有 bind/read/write/事务仍委托真实 bundled SQLite。 */
internal class ThemeSqlStepProbe(private val actual: SQLiteDriver = BundledSQLiteDriver()) : SQLiteDriver by actual {
    @Volatile var beforeStep: (String) -> Unit = {}

    override fun open(fileName: String): SQLiteConnection {
        val connection = actual.open(fileName)
        return object : SQLiteConnection by connection {
            override fun prepare(sql: String): SQLiteStatement {
                val statement = connection.prepare(sql)
                val normalized = sql.trim().lowercase(Locale.ROOT).replace("`", "").replace("\"", "")
                return object : SQLiteStatement by statement {
                    override fun step(): Boolean {
                        beforeStep(normalized)
                        return statement.step()
                    }
                }
            }
        }
    }
}

/** 闩锁只暂停真实 SQL worker；finally 释放，内部等待也有明确上限，不靠 sleep 碰时序。 */
internal class ThemeRoomStepGate(private val matches: (String) -> Boolean) {
    private val claimed = AtomicBoolean(false)
    private val released = CountDownLatch(1)
    val entered = CompletableDeferred<Unit>()
    val hits = AtomicInteger()

    fun beforeStep(sql: String) {
        if (!matches(sql) || !claimed.compareAndSet(false, true)) return
        hits.incrementAndGet()
        entered.complete(Unit)
        check(released.await(10L, TimeUnit.SECONDS)) { "Actual SQL boundary was not released" }
    }

    fun release() = released.countDown()
}

/** URI 注册到真实临时文件；只读描述符关闭情况在断言前可观察，不返回字节流假冒 provider。 */
internal class ThemeSelectedFileProvider : ContentProvider() {
    private val files = ConcurrentHashMap<Uri, File>()
    private val descriptors = mutableListOf<ParcelFileDescriptor>()
    val opens = AtomicInteger()
    val queries = AtomicInteger()
    val writes = AtomicInteger()

    fun register(file: File): Uri {
        val uri = Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(Packages.id()).build()
        files[uri] = file
        return uri
    }

    override fun onCreate(): Boolean = true
    override fun getType(uri: Uri): String = "application/vnd.melox.theme+zip"

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        check(mode == "r") { "Selected theme must be opened read-only" }
        val file = checkNotNull(files[uri])
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        synchronized(descriptors) { descriptors.add(descriptor) }
        opens.incrementAndGet()
        return AssetFileDescriptor(descriptor, 0, file.length())
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        queries.incrementAndGet()
        return null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = rejectWrite()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = rejectWrite()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = rejectWrite()

    fun assertDescriptorsClosed() = synchronized(descriptors) {
        for (descriptor in descriptors) assertFalse(descriptor.fileDescriptor.valid())
    }

    fun closeRemainingDescriptors() = synchronized(descriptors) {
        for (descriptor in descriptors) if (descriptor.fileDescriptor.valid()) descriptor.close()
    }

    private fun rejectWrite(): Nothing {
        writes.incrementAndGet()
        throw AssertionError("Selected theme original cannot be modified")
    }

    companion object {
        const val AUTHORITY = "selected-theme-integration-fixture"
    }
}

internal suspend fun <T> expectLocalThemeIssue(issue: ThemeIssue, action: suspend () -> T): ThemeException {
    try {
        action()
    } catch (failure: ThemeException) {
        assertEquals(issue, failure.issue)
        return failure
    }
    throw AssertionError("Expected local theme issue $issue")
}

internal suspend fun <T> expectThemeTransferIssue(issue: TransferIssue, action: suspend () -> T): TransferException {
    try {
        action()
    } catch (failure: TransferException) {
        assertEquals(issue, failure.issue)
        return failure
    }
    throw AssertionError("Expected transfer issue $issue")
}

internal suspend fun <T> expectThemeCancellation(action: suspend () -> T): CancellationException {
    try {
        action()
    } catch (cancelled: CancellationException) {
        if (cancelled is TimeoutCancellationException) throw cancelled
        return cancelled
    }
    throw AssertionError("Expected original caller cancellation")
}

/** 有界真实 PCM WAV 样本，只供内容验证和原字节保留断言，不代替媒体解码验收。 */
internal fun transferredThemeTestWav(frames: Int = 800): ByteArray {
    require(frames in 1..40_000)
    val buffer = ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
    buffer.putInt(buffer.capacity() - 8)
    buffer.put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
    buffer.putInt(16)
    buffer.putShort(1)
    buffer.putShort(1)
    buffer.putInt(8_000)
    buffer.putInt(16_000)
    buffer.putShort(2)
    buffer.putShort(16)
    buffer.put("data".toByteArray(Charsets.US_ASCII))
    buffer.putInt(frames * 2)
    repeat(frames) { frame ->
        val sample = sin(2.0 * Math.PI * 440 * frame / 8_000) * 8_192
        buffer.putShort(sample.toInt().toShort())
    }
    return buffer.array()
}
