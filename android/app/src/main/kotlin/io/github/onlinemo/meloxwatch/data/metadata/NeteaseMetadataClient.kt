package io.github.onlinemo.meloxwatch.data.metadata

import android.content.Context
import android.os.SystemClock
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsLimits
import io.github.onlinemo.meloxwatch.data.netease.NeteaseCatalogApi
import io.github.onlinemo.meloxwatch.data.netease.NeteaseEapi
import io.github.onlinemo.meloxwatch.data.netease.NeteaseLyricsApi
import io.github.onlinemo.meloxwatch.data.network.BoundedHttpGetClient
import io.github.onlinemo.meloxwatch.data.network.HttpGetKind
import io.github.onlinemo.meloxwatch.data.network.HttpGetLimits
import io.github.onlinemo.meloxwatch.data.network.HttpTransportGate
import io.github.onlinemo.meloxwatch.data.network.NetworkException
import io.github.onlinemo.meloxwatch.data.network.NetworkIssue
import io.github.onlinemo.meloxwatch.data.network.OkHttpExchange
import io.github.onlinemo.meloxwatch.model.OnlineCatalogException
import io.github.onlinemo.meloxwatch.model.OnlineCatalogIssue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl

internal class NeteaseMetadataClient(
    private val catalog: NeteaseCatalogApi,
    private val lyricApi: NeteaseLyricsApi,
    private val http: suspend (HttpUrl, HttpGetLimits) -> io.github.onlinemo.meloxwatch.data.network.HttpGetResponse,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) : LocalMetadataClient {
    override suspend fun search(query: MetadataSearchQuery, offset: Long): MetadataSearchPage {
        val page = catalog.searchMetadata(query.words, offset, 20)
        currentCoroutineContext().ensureActive()
        return MetadataSearchPage(page.entries.map { native -> native.entry.let {
            MetadataProvenance(it.id, it.title, it.artist, it.album)
        } }, page.rawCount, page.more)
    }

    override suspend fun lyrics(candidate: MetadataProvenance, maxAccountedBytes: Long): MetadataArtifact {
        require(maxAccountedBytes in 1..LyricsLimits.ACCOUNTED_BYTES)
        val bytes = lyricApi.fetchMetadata(candidate.songId, maxAccountedBytes).primary
            ?: throw MetadataLookupException(MetadataLookupIssue.NO_LYRICS)
        currentCoroutineContext().ensureActive()
        return MetadataArtifact(bytes, candidate)
    }

    override suspend fun artwork(candidate: MetadataProvenance): MetadataArtifact {
        val started = nowMs()
        val details = catalog.metadataDetails(candidate.songId)
            ?: throw MetadataLookupException(MetadataLookupIssue.NO_ARTWORK)
        if (details.entry.id != candidate.songId) throw MetadataLookupException(MetadataLookupIssue.INVALID_RESPONSE)
        val provenance = details.entry.let { MetadataProvenance(it.id, it.title, it.artist, it.album) }
        var target = artworkTarget(details.artworkUrl ?: throw MetadataLookupException(MetadataLookupIssue.NO_ARTWORK))
        var redirects = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val elapsed = nowMs() - started
            if (elapsed < 0L || elapsed >= TOTAL_MS) throw OnlineCatalogException(OnlineCatalogIssue.TIMEOUT)
            val remaining = TOTAL_MS - elapsed
            val response = http(target, HttpGetLimits(remaining, minOf(remaining, 3_000L), ARTWORK_RESPONSE_BYTES, HttpGetKind.IMAGE))
            if (response.url != target) throw MetadataLookupException(MetadataLookupIssue.INVALID_RESPONSE)
            if (response.code in REDIRECTS) {
                if (++redirects > 3) throw MetadataLookupException(MetadataLookupIssue.INVALID_RESPONSE)
                target = artworkTarget(HttpTransportGate.redirect(target,
                    response.location ?: throw MetadataLookupException(MetadataLookupIssue.INVALID_RESPONSE)).toString())
                continue
            }
            if (response.code !in 200..299 || response.bytes.isEmpty() || response.bytes.size > ARTWORK_RESPONSE_BYTES) {
                throw MetadataLookupException(MetadataLookupIssue.INVALID_RESPONSE)
            }
            currentCoroutineContext().ensureActive()
            return MetadataArtifact(response.bytes, provenance)
        }
    }

    companion object {
        const val ARTWORK_RESPONSE_BYTES = 1_048_576
        private const val TOTAL_MS = 8_000L
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)

        /** A server-supplied URL is not authority to contact arbitrary origins or a local host. */
        internal fun artworkTarget(value: String): HttpUrl {
            val parsed = HttpTransportGate.parseHttps(value)
            if (parsed.port != 443 || !parsed.host.endsWith(".music.126.net") ||
                parsed.host == "music.126.net" || parsed.querySize > 16) {
                throw NetworkException(NetworkIssue.INVALID_REQUEST)
            }
            return parsed
        }
    }
}

internal enum class MetadataLookupIssue { NO_LYRICS, NO_ARTWORK, INVALID_RESPONSE }
internal class MetadataLookupException(val issue: MetadataLookupIssue) : Exception(issue.name)

/** Lazy creation is reached only by an explicit search/preview, never by opening the local core. */
internal class NeteaseMetadataClientOwner(context: Context, private val images: () -> BoundedHttpGetClient) : AutoCloseable {
    private val application = context.applicationContext
    private val gate = Mutex()
    private val lifecycle = Any()
    private var exchange: OkHttpExchange? = null
    private var instance: NeteaseMetadataClient? = null
    private var closed = false

    suspend fun get(): LocalMetadataClient = gate.withLock {
        synchronized(lifecycle) {
            if (closed) throw NetworkException(NetworkIssue.CLOSED)
            instance?.let { return@withLock it }
        }
        val created = OkHttpExchange.create(application, NeteaseEapi.ORIGIN)
        try {
            val eapi = withContext(Dispatchers.IO) { NeteaseEapi(created) }
            currentCoroutineContext().ensureActive()
            synchronized(lifecycle) {
                if (closed) throw CancellationException("Metadata owner closed")
                NeteaseMetadataClient(NeteaseCatalogApi(eapi), NeteaseLyricsApi(eapi),
                    http = { target, limits -> images().get(target, limits, context = null) }).also {
                    exchange = created
                    instance = it
                }
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { created.close() }
            throw failure
        }
    }

    override fun close() {
        val retired = synchronized(lifecycle) {
            if (closed) return
            closed = true
            instance = null
            exchange.also { exchange = null }
        }
        retired?.close()
    }
}
