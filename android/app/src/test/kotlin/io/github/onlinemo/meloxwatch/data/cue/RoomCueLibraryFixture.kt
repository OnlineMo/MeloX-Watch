package io.github.onlinemo.meloxwatch.data.cue

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.library.LibraryQuery
import io.github.onlinemo.meloxwatch.data.library.LibrarySource
import io.github.onlinemo.meloxwatch.data.library.LibraryTrack
import io.github.onlinemo.meloxwatch.data.library.LocalAudioMetadata
import io.github.onlinemo.meloxwatch.data.library.LocalContentValidation
import io.github.onlinemo.meloxwatch.data.library.RoomLocalLibraryRepository
import io.github.onlinemo.meloxwatch.data.local.LocalGrantObservation
import io.github.onlinemo.meloxwatch.data.local.LocalNodeKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import io.github.onlinemo.meloxwatch.data.scan.MetadataOutcome
import io.github.onlinemo.meloxwatch.data.scan.ScanCoverage
import io.github.onlinemo.meloxwatch.data.scan.ScanMode
import io.github.onlinemo.meloxwatch.data.scan.ScanToken
import io.github.onlinemo.meloxwatch.model.CuePcmWaveFacts
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** 使用真实 Room 与 RIFF/CUE 字节；合成授权不代表 Android 提供方或设备验证。 */
internal class RoomCueLibraryFixture(private val context: Context, private val file: File) : AutoCloseable {
    lateinit var database: MeloXDatabase
        private set
    lateinit var library: RoomLocalLibraryRepository
        private set
    val wave = waveBytes()
    var elapsed = 1000L

    suspend fun open() {
        database = Room.databaseBuilder(context, MeloXDatabase::class.java, file.absolutePath)
            .setDriver(BundledSQLiteDriver()).addMigrations(MeloXDatabase.MIGRATION_1_2, MeloXDatabase.MIGRATION_2_3,
                MeloXDatabase.MIGRATION_3_4, MeloXDatabase.MIGRATION_4_5, MeloXDatabase.MIGRATION_5_6,
                MeloXDatabase.MIGRATION_6_7, MeloXDatabase.MIGRATION_7_8).build()
        library = RoomLocalLibraryRepository(database, { elapsed }, { 1_790_000_000_000L })
        library.initialize()
    }

    suspend fun reopen() {
        close();
        open()
    }
    override fun close() { if (this::database.isInitialized) database.close() }

    suspend fun stage(text: String = CUE, audio: ByteArray = wave, wholeTitle: String = "Whole file", retireDirectories: Boolean = true,
        sourceId: String = SOURCE_ID): StagedCue {
        require(sourceId.matches(Regex("[a-z0-9-]+")))
        val rootName = if (sourceId == SOURCE_ID) "root" else "root-$sourceId"
        val source = library.source(sourceId) ?: library.registerSource(LocalSourceSpec(sourceId, LocalSourceKind.TREE,
            "content://cue-room/tree/$rootName", authority = "cue-room", rootObjectId = "root"),
            LocalGrantObservation(read = ObservedCapability.AVAILABLE, enumerate = ObservedCapability.AVAILABLE))
        val token = library.beginScan(sourceId, source.authEpoch, library.rules().revision, ScanMode.FULL)
        val root = ObservedCandidate(sourceId, "root", source.spec.uri, null, "Fixture root", "vnd.android.document/directory", LocalNodeKind.DIRECTORY)
        val bytes = text.toByteArray(Charsets.UTF_8)
        val cue = ObservedCandidate(sourceId, "sheet", "${source.spec.uri}/document/sheet", "root",
            "disc.cue", "application/x-cue", LocalNodeKind.OTHER, bytes.size.toLong())
        val candidate = ObservedCandidate(sourceId, "wave", "${source.spec.uri}/document/wave", "root",
            "disc.wav", "audio/wav", LocalNodeKind.AUDIO, audio.size.toLong())
        val facts = CuePcmWaveParser.inspect(ByteArrayInputStream(audio), audio.size.toLong())
        library.commitSeen(token, listOf(root, cue, candidate))
        library.commitMetadata(token, listOf(MetadataOutcome(candidate.objectId,
            LocalAudioMetadata(title = wholeTitle, durationMs = facts.durationUs / 1000L, mimeType = "audio/wav"),
            verifiedContentToken = sha(audio))))
        if (retireDirectories) this.retireDirectories(token)
        return StagedCue(token, cue, candidate, bytes, facts)
    }

    suspend fun retireDirectories(token: ScanToken) {
        while (true) {
            val directory = library.nextDirectory(token) ?: break
            library.completeDirectory(token, directory.objectId, true)
        }
    }

    suspend fun prepare(staged: StagedCue): PreparedCueGroup {
        val selected = library.nextCueDocument(staged.token) ?: throw AssertionError("CUE frontier")
        check(selected == staged.cue)
        val context = library.cueScanContext(staged.token, selected)
        val parsed = CueParser.parse(staged.bytes)
        val audio = library.cueAudioDescriptor(staged.token, staged.audio).copy(contentValidation = LocalContentValidation.VERIFIED)
        return CueGroupValidator.prepare(parsed,
            CueDocumentSnapshot(context.document.identity, context.document.source, context.document.objectId,
                context.document.location, parsed.rawContentToken, parsed.encoding),
            CueMatchedFiles(parsed.rawContentToken, context.scope, listOf(CueMatchedFile(0, staged.audio))),
            listOf(CueAudioFacts(0, audio, staged.facts.durationUs)))
    }

    suspend fun accept(staged: StagedCue): Pair<PreparedCueGroup, CueGroupCommit> {
        val prepared = prepare(staged)
        val committed = library.commitCueGroup(staged.token, prepared, mapOf(0 to staged.facts))
        library.completeCueDocument(staged.token, staged.cue.objectId)
        return prepared to committed
    }

    suspend fun finish(staged: StagedCue) {
        val coverage = ScanCoverage(true, true, true, true, false)
        library.setCoverage(staged.token, coverage)
        var after: String? = null
        while (true) {
            val page = library.stageAbsence(staged.token, after)
            if (page.complete) break
            after = page.nextAliasId ?: throw AssertionError("absence cursor")
        }
        val counters = checkNotNull(library.latestScan(staged.token.sourceId)).counters
        library.finishScan(staged.token, counters, coverage)
    }

    suspend fun tracks(query: LibraryQuery = LibraryQuery()): List<LibraryTrack> {
        val view = library.openView(query)
        return try { library.page(view).tracks } finally { library.closeView(view.viewId) }
    }
    suspend fun segments(logicalSourceId: String = SOURCE_ID): List<LibraryTrack> = tracks().filter {
        it.cueReference?.identity?.sourceId == logicalSourceId }
        .sortedBy { checkNotNull(it.cueReference).identity.trackNumber }

    data class StagedCue(val token: ScanToken, val cue: ObservedCandidate, val audio: ObservedCandidate,
        val bytes: ByteArray, val facts: CuePcmWaveFacts)

    companion object {
        const val SOURCE_ID = "cue-room"
        val CUE = """TITLE "Album"
            |PERFORMER "Artist"
            |FILE "disc.wav" WAVE
            |TRACK 01 AUDIO
            |TITLE "First segment"
            |INDEX 01 00:00:00
            |TRACK 02 AUDIO
            |TITLE "Second segment"
            |INDEX 01 00:01:01
            |""".trimMargin()

        fun sha(bytes: ByteArray): String = "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

        private fun waveBytes(): ByteArray {
            val samples = 88_200
            val payload = samples * 4
            return ByteArray(44 + payload).also { bytes ->
                val out = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                fun id(value: String) { out.put(value.toByteArray(Charsets.US_ASCII)) }
                id("RIFF");
                out.putInt(payload + 36);
                id("WAVE");
                id("fmt ");
                out.putInt(16)
                out.putShort(1);
                out.putShort(2);
                out.putInt(44_100);
                out.putInt(176_400);
                out.putShort(4);
                out.putShort(16)
                id("data");
                out.putInt(payload)
                repeat(samples) { frame ->
                    val left = if (frame < 44_688) 7000 else -11000
                    out.putShort(left.toShort());
                    out.putShort((-left).toShort())
                }
            }
        }
    }
}
