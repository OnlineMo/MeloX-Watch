package io.github.onlinemo.meloxwatch.data.lyrics.load

import io.github.onlinemo.meloxwatch.data.library.LibraryException
import io.github.onlinemo.meloxwatch.data.library.LibraryIssue
import io.github.onlinemo.meloxwatch.data.library.LibrarySource
import io.github.onlinemo.meloxwatch.data.library.LibrarySourceState
import io.github.onlinemo.meloxwatch.data.library.LocalContentValidation
import io.github.onlinemo.meloxwatch.data.library.LocalReadDescriptor
import io.github.onlinemo.meloxwatch.data.local.LocalCandidateCursor
import io.github.onlinemo.meloxwatch.data.local.LocalGrantObservation
import io.github.onlinemo.meloxwatch.data.local.LocalNodeKind
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.LocalSourceAccess
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceObservation
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.lyrics.EmbeddedLyricsBinaryFixture
import io.github.onlinemo.meloxwatch.model.LocalLocationReference
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred

/** IO fixtures use the real shared channel, including its asynchronous cursor retirement. */
internal class LocalLyricsLoadFixture(val io: LocalProviderIo = LocalProviderIo()) {
    val grant = LocalGrantObservation(read = ObservedCapability.AVAILABLE, enumerate = ObservedCapability.AVAILABLE)
    val source = LocalSourceSpec("audio-source", LocalSourceKind.TREE, "content://lyrics.fixture/tree/root", authority = "lyrics.fixture", rootObjectId = "root")
    val audioRow = ObservedCandidate(source.sourceId, "audio-object", "content://lyrics.fixture/tree/root/document/audio-object", "folder", "Track.mp3", "audio/mpeg", LocalNodeKind.AUDIO)
    @Volatile var audio = LocalReadDescriptor(
        TrackKey("local", "stable-track"), LibrarySource(source, grant, "grant", 4L, 6L, LibrarySourceState.ACTIVE, null, null),
        "alias", LocalLocationReference("location", audioRow.uri, source.uri, contentRevision = 7L), audioRow.objectId, "native-audio-v1", null,
    )
    @Volatile var current = true
    @Volatile var audioIssue: SourceReadIssue? = null
    @Volatile var validationHook: (() -> Unit)? = null
    @Volatile var verifyHook: (() -> Unit)? = null
    @Volatile var cursorCloseHook: (() -> Unit)? = null
    @Volatile var eofOnlyOnEmptyBatch = false
    @Volatile var emptyNonExhaustedBatch = false
    @Volatile var tooManyBatchRows = false
    var directory: List<ObservedCandidate> = listOf(audioRow)
    val observations = ConcurrentHashMap<String, LocalSourceObservation>()
    val documents = ConcurrentHashMap<String, ObservedCandidate>()
    val streams = ConcurrentHashMap<String, () -> InputStream>()
    val openedUris = CopyOnWriteArrayList<String>()
    val batchLimits = CopyOnWriteArrayList<Int>()
    val validationCalls = AtomicInteger()
    val documentVerifications = AtomicInteger()
    val closedStreams = AtomicInteger()
    val cursorOpens = AtomicInteger()
    val cursorCloses = AtomicInteger()
    private val nextDocument = AtomicInteger()

    init {
        observations[source.sourceId] = LocalSourceObservation(source, "tree-incarnation-1", grant,
            ObservedCandidate(source.sourceId, "root", source.uri, null, "root", null, LocalNodeKind.DIRECTORY))
        setAudio(EmbeddedLyricsBinaryFixture.id3(EmbeddedLyricsBinaryFixture.frame("USLT", EmbeddedLyricsBinaryFixture.uslt("fallback"))))
    }

    fun setAudio(bytes: ByteArray) {
        audio = audio.copy(sizeBytes = bytes.size.toLong())
        streams[audio.location.uri] = { ByteArrayInputStream(bytes) }
    }

    fun document(text: String, name: String = "Selected.lrc", nativeToken: String? = null): LyricsDocumentInput {
        val id = "text-${nextDocument.incrementAndGet()}"
        val uri = "content://lyrics.fixture/document/$id"
        val documentSource = LocalSourceSpec(id, LocalSourceKind.DOCUMENT, uri, authority = "lyrics.fixture", rootObjectId = id)
        val bytes = text.toByteArray(Charsets.UTF_8)
        val candidate = ObservedCandidate(id, id, uri, null, name, "text/plain", LocalNodeKind.OTHER, bytes.size.toLong(), providerContentToken = nativeToken)
        observations[id] = LocalSourceObservation(documentSource, "document-$id", grant, candidate)
        documents[uri] = candidate
        streams[uri] = { ByteArrayInputStream(bytes) }
        return LyricsDocumentInput("picker-$id", 12L, documentSource, candidate)
    }

    fun sibling(text: String, name: String = "Track.lrc", objectId: String = "sibling-lrc"): ObservedCandidate {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val candidate = ObservedCandidate(source.sourceId, objectId, "content://lyrics.fixture/tree/root/document/$objectId", "folder", name,
            "text/plain", LocalNodeKind.OTHER, bytes.size.toLong())
        documents[candidate.uri] = candidate
        streams[candidate.uri] = { ByteArrayInputStream(bytes) }
        return candidate
    }

    fun request(selection: LyricsLoadSelection = LyricsLoadSelection.Automatic(), documentIdentity: String? = null): LyricsLoadRequest = LyricsLoadRequest(
        LyricsLoadBinding(audio.trackKey, audio.location.locationId, audio.location.contentRevision, audio.source.spec.sourceId,
            audio.source.revision, audio.source.authEpoch, 9L, 12L, documentIdentity), selection,
    )

    fun loader(limits: LocalLyricsLoadLimits = LocalLyricsLoadLimits()): LocalLyricsLoader = LocalLyricsLoader(
        io = io,
        resolveAudio = { key, location, revision ->
            val latest = audio
            if (latest.trackKey != key || latest.location.locationId != location || latest.location.contentRevision != revision) {
                throw LibraryException(LibraryIssue.SOURCE_CHANGED)
            }
            latest
        },
        validateAudio = { descriptor -> io.read {
            validationCalls.incrementAndGet()
            audioIssue?.let { throw SourceReadException(it) }
            validationHook?.invoke()
            descriptor.copy(contentValidation = LocalContentValidation.VERIFIED)
        } },
        sources = access(),
        verifyDocument = { _, candidate -> io.read {
            documentVerifications.incrementAndGet()
            verifyHook?.invoke()
            if (documents[candidate.uri] != candidate) throw SourceReadException(SourceReadIssue.SOURCE_CHANGED)
        } },
        opener = LocalLyricsStreamOpener { uri, _ ->
            openedUris += uri
            val stream = streams[uri]?.invoke() ?: throw SourceReadException(SourceReadIssue.FILE_MISSING)
            LocalLyricsOpenedStream(stream, closeDescriptor = { closedStreams.incrementAndGet() })
        },
        isCurrent = { current },
        limits = limits,
    )

    fun access(): LocalSourceAccess = object : LocalSourceAccess {
        override suspend fun observe(source: LocalSourceSpec): LocalSourceObservation = io.read {
            observations[source.sourceId] ?: throw SourceReadException(SourceReadIssue.PERMISSION_REVOKED)
        }

        override suspend fun open(source: LocalSourceSpec, parentObjectId: String?, sinceMediaGeneration: Long?): LocalCandidateCursor = io.read {
            check(source == this@LocalLyricsLoadFixture.source && parentObjectId == "folder" && sinceMediaGeneration == null)
            cursorOpens.incrementAndGet()
            object : LocalCandidateCursor {
                private var index = 0
                private var eof = false
                private val closed = AtomicBoolean(false)
                private val receipt = CompletableDeferred<Unit>()
                override val exhausted: Boolean get() = eof

                override suspend fun nextBatch(limit: Int): List<ObservedCandidate> = io.read {
                    batchLimits += limit
                    if (emptyNonExhaustedBatch) emptyList()
                    else {
                        val count = if (tooManyBatchRows) limit + 1 else limit
                        val rows = directory.subList(index, minOf(directory.size, index + count)).toList()
                        index += rows.size
                        eof = if (eofOnlyOnEmptyBatch) rows.isEmpty() else index == directory.size
                        rows
                    }
                }

                override fun close() {
                    if (closed.compareAndSet(false, true)) io.retireCursor(receipt) {
                        cursorCloseHook?.invoke()
                        cursorCloses.incrementAndGet()
                    }
                }

                override suspend fun closeAndAwait() {
                    close()
                    receipt.await()
                }
            }
        }
    }
}

internal fun loaded(result: LocalLyricsLoadResult): LocalLyricsLoadResult.Loaded =
    result as? LocalLyricsLoadResult.Loaded ?: throw AssertionError("Expected loaded lyrics: ${(result as LocalLyricsLoadResult.Unavailable).failure}")

internal fun failure(result: LocalLyricsLoadResult): LyricsLoadFailure =
    (result as? LocalLyricsLoadResult.Unavailable ?: throw AssertionError("Expected unavailable lyrics")).failure
