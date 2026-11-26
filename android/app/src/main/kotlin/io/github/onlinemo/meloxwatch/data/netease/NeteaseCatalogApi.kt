/* Protocol reference: youshen2/MeloX, GPL-3.0, 09713edb847a0f0a3515a34f779cc630d4fe5985.
 * WatchNeteaseAPI.swift and MeloX/Core/Networking/NeteaseAPI.swift; Android paging/parsing is independent. */
package io.github.onlinemo.meloxwatch.data.netease

import io.github.onlinemo.meloxwatch.data.auth.AuthException
import io.github.onlinemo.meloxwatch.data.auth.AuthIssue
import io.github.onlinemo.meloxwatch.data.auth.SessionCredential
import io.github.onlinemo.meloxwatch.data.network.JsonArray
import io.github.onlinemo.meloxwatch.data.network.JsonBoolean
import io.github.onlinemo.meloxwatch.data.network.JsonBounds
import io.github.onlinemo.meloxwatch.data.network.JsonNull
import io.github.onlinemo.meloxwatch.data.network.JsonNumber
import io.github.onlinemo.meloxwatch.data.network.JsonObject
import io.github.onlinemo.meloxwatch.data.network.JsonText
import io.github.onlinemo.meloxwatch.data.network.JsonValue
import io.github.onlinemo.meloxwatch.data.network.MetadataCallBudget
import io.github.onlinemo.meloxwatch.data.network.NetworkException
import io.github.onlinemo.meloxwatch.data.network.NetworkLimits
import io.github.onlinemo.meloxwatch.data.network.StrictJson
import io.github.onlinemo.meloxwatch.data.network.jsonLong
import io.github.onlinemo.meloxwatch.data.network.jsonObject
import io.github.onlinemo.meloxwatch.data.online.authCatalogIssue
import io.github.onlinemo.meloxwatch.model.OnlineCatalogEntry
import io.github.onlinemo.meloxwatch.model.OnlineCatalogEntryKind
import io.github.onlinemo.meloxwatch.model.OnlineCatalogException
import io.github.onlinemo.meloxwatch.model.OnlineCatalogIssue
import io.github.onlinemo.meloxwatch.model.OnlineCatalogKind
import io.github.onlinemo.meloxwatch.model.OnlineCatalogQuery
import io.github.onlinemo.meloxwatch.model.onlineCanonicalId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class NativeCatalogEntry(val entry: OnlineCatalogEntry, val artworkUrl: String?) {
    override fun toString(): String = "NativeCatalogEntry(redacted)"
}
internal data class NativeCatalogPage(val entries: List<NativeCatalogEntry>, val rawCount: Int,
    val reportedTotal: Long?, val more: Boolean)
internal data class NativeCatalogIndex(val ids: List<String>, val seed: List<NativeCatalogEntry>, val reportedTotal: Long)

/** One bounded call per page; pagination/account/ordered snapshots belong to the repository. */
internal class NeteaseCatalogApi(private val eapi: NeteaseEapi) {
    /** Explicit local metadata lookup has no account scope and never sends a saved credential. */
    suspend fun searchMetadata(words: String, offset: Long, limit: Int = 20): NativeCatalogPage = checked {
        if (words.isBlank() || words.length > 512 || words.any { it.code < 32 } ||
            limit !in 1..PAGE_SIZE || offset < 0L || offset > Long.MAX_VALUE - limit) invalid()
        val response = post("/api/search/get", mapOf("s" to JsonText(words), "type" to JsonNumber("1"),
            "limit" to JsonNumber(limit.toString()), "offset" to JsonNumber(offset.toString())), null)
        checkCode(response)
        val result = response["result"].jsonObject()
        val songs = array(result["songs"], emptyAllowed = true)
        val total = result["songCount"].jsonLong()
        if (songs.size > limit || total < 0L || offset + songs.size > total) invalid()
        val more = offset + songs.size < total
        if (more && songs.isEmpty()) throw OnlineCatalogException(OnlineCatalogIssue.INCOMPLETE)
        val records = songs.mapIndexed { index, value ->
            currentCoroutineContext().ensureActive()
            track(value.jsonObject(), offset + index)
        }
        if (records.map { it.entry.id }.distinct().size != records.size) invalid()
        NativeCatalogPage(records, songs.size, total, more)
    }

    suspend fun metadataDetails(id: String): NativeCatalogEntry? =
        readDetails(listOf(id), null).singleOrNull()

    suspend fun page(query: OnlineCatalogQuery, accountId: String, offset: Long, credential: SessionCredential): NativeCatalogPage = checked {
        if (offset < 0L || offset > Long.MAX_VALUE - PAGE_SIZE) invalid()
        if (!onlineCanonicalId(accountId)) invalid()
        val response = when (query.kind) {
            OnlineCatalogKind.PLAYLISTS -> post("/api/user/playlist", mapOf("uid" to JsonText(accountId),
                "limit" to JsonNumber(PAGE_SIZE.toString()), "offset" to JsonNumber(offset.toString()),
                "includeVideo" to JsonBoolean(true)), credential)
            OnlineCatalogKind.SEARCH -> post("/api/search/get", mapOf("s" to JsonText(query.argument),
                "type" to JsonNumber("1"), "limit" to JsonNumber(PAGE_SIZE.toString()), "offset" to JsonNumber(offset.toString())), credential)
            else -> invalid()
        }
        checkCode(response)
        val payload = if (query.kind == OnlineCatalogKind.SEARCH) response["result"].jsonObject() else response
        val values = array(payload[if (query.kind == OnlineCatalogKind.SEARCH) "songs" else "playlist"], emptyAllowed = true)
        if (values.size > PAGE_SIZE) invalid()
        val total = optionalLong(payload[if (query.kind == OnlineCatalogKind.SEARCH) "songCount" else "playlistCount"])
        if (total != null && total < 0L) invalid()
        val next = offset + values.size
        val more = if (query.kind == OnlineCatalogKind.SEARCH) {
            val expected = total ?: invalid()
            if (next > expected) invalid()
            next < expected
        } else when (val indicator = payload["more"]) {
            is JsonBoolean -> indicator.value
            null, JsonNull -> total?.let { next < it } ?: invalid()
            else -> invalid()
        }
        if (more && values.isEmpty()) throw OnlineCatalogException(OnlineCatalogIssue.INCOMPLETE)
        if (total != null && (next > total || !more && next != total || more && next >= total)) {
            throw OnlineCatalogException(OnlineCatalogIssue.INCOMPLETE)
        }
        val records = values.mapIndexed { index, value ->
            currentCoroutineContext().ensureActive()
            if (query.kind == OnlineCatalogKind.PLAYLISTS) playlist(value.jsonObject(), index.toLong())
            else track(value.jsonObject(), index.toLong())
        }
        NativeCatalogPage(records, values.size, total, more)
    }

    suspend fun index(query: OnlineCatalogQuery, accountId: String, credential: SessionCredential): NativeCatalogIndex = checked {
        if (!onlineCanonicalId(accountId)) invalid()
        val response = when (query.kind) {
            OnlineCatalogKind.PLAYLIST -> post("/api/v6/playlist/detail",
                mapOf("id" to JsonText(query.argument), "n" to JsonNumber("100"), "s" to JsonNumber("8")), credential)
            OnlineCatalogKind.LIKED -> post("/api/song/like/get", mapOf("uid" to JsonText(accountId)), credential)
            OnlineCatalogKind.DAILY -> post("/api/v3/discovery/recommend/songs", emptyMap(), credential)
            else -> invalid()
        }
        checkCode(response)
        when (query.kind) {
            OnlineCatalogKind.PLAYLIST -> {
                val fields = response["playlist"].jsonObject()
                if (id(fields["id"]) != query.argument) invalid()
                val ids = array(fields["trackIds"], emptyAllowed = true).map {
                    currentCoroutineContext().ensureActive()
                    id(it.jsonObject()["id"])
                }
                val total = fields["trackCount"].jsonLong()
                if (total != ids.size.toLong()) throw OnlineCatalogException(OnlineCatalogIssue.INCOMPLETE)
                val seed = array(fields["tracks"], emptyAllowed = true).also { if (it.size > PAGE_SIZE) invalid() }
                    .mapIndexed { order, value ->
                        currentCoroutineContext().ensureActive()
                        track(value.jsonObject(), order.toLong())
                    }
                if (seed.any { it.entry.id !in ids }) invalid()
                NativeCatalogIndex(ids, seed, total)
            }
            OnlineCatalogKind.LIKED -> {
                val ids = array(response["ids"], emptyAllowed = true).map {
                    currentCoroutineContext().ensureActive()
                    id(it)
                }
                NativeCatalogIndex(ids, emptyList(), ids.size.toLong())
            }
            OnlineCatalogKind.DAILY -> {
                val songs = array(response["data"].jsonObject()["dailySongs"], emptyAllowed = true)
                    .mapIndexed { order, value -> track(value.jsonObject(), order.toLong()) }
                NativeCatalogIndex(songs.map { it.entry.id }, songs, songs.size.toLong())
            }
            else -> invalid()
        }
    }

    suspend fun details(ids: List<String>, credential: SessionCredential): List<NativeCatalogEntry> =
        readDetails(ids, credential)

    private suspend fun readDetails(ids: List<String>, credential: SessionCredential?): List<NativeCatalogEntry> = checked {
        if (ids.isEmpty() || ids.size > PAGE_SIZE || ids.any { !onlineCanonicalId(it) }) invalid()
        val requested = ids.distinct()
        val body = StrictJson.encode(JsonArray(requested.map { JsonObject(mapOf("id" to JsonNumber(it))) }))
        val response = post("/api/v3/song/detail", mapOf("c" to JsonText(body.toString(Charsets.UTF_8))), credential)
        checkCode(response)
        val songs = array(response["songs"], emptyAllowed = true)
            .mapIndexed { order, value -> track(value.jsonObject(), order.toLong()) }
        if (songs.size > requested.size || songs.map { it.entry.id }.distinct().size != songs.size ||
            songs.any { it.entry.id !in requested }) invalid()
        songs
    }

    private suspend fun post(path: String, fields: Map<String, JsonValue>, credential: SessionCredential?): JsonObject =
        eapi.post(path, fields, credential?.let { NeteaseCookie.fromCredential(it) }, MetadataCallBudget(),
            // The byte bound controls large ID indexes; a 10,000-element parser cap would become a product cap.
            JsonBounds(bytes = NetworkLimits.RESPONSE_BYTES, nodes = NetworkLimits.RESPONSE_BYTES / 2 + 1,
                members = NetworkLimits.RESPONSE_BYTES / 2 + 1, stringChars = 4096)).json

    private fun track(fields: JsonObject, order: Long): NativeCatalogEntry {
        val id = id(fields["id"])
        val artists = fields["ar"] ?: fields["artists"]
        val artist = if (artists == null || artists === JsonNull) null else array(artists, emptyAllowed = true).also {
            if (it.size > 32) invalid()
        }.mapNotNull { text(it.jsonObject()["name"], 128) }.joinToString(" / ").takeIf { it.isNotEmpty() }?.also {
            if (it.length > 512) invalid()
        }
        val album = (fields["al"] ?: fields["album"]).takeUnless { it == null || it === JsonNull }?.jsonObject()
        val duration = optionalLong(fields["dt"] ?: fields["duration"])?.also { if (it < 0L || it > 7L * 24 * 60 * 60 * 1000) invalid() }?.takeIf { it > 0L }
        return NativeCatalogEntry(OnlineCatalogEntry(order, OnlineCatalogEntryKind.TRACK, id,
            text(fields["name"], 256), artist, album?.let { text(it["name"], 256) }, duration),
            album?.let { artwork(it["picUrl"]) })
    }

    private fun playlist(fields: JsonObject, order: Long): NativeCatalogEntry {
        val count = optionalLong(fields["trackCount"])?.also { if (it < 0L) invalid() }
        val creator = fields["creator"].takeUnless { it == null || it === JsonNull }?.jsonObject()
        return NativeCatalogEntry(OnlineCatalogEntry(order, OnlineCatalogEntryKind.PLAYLIST, id(fields["id"]),
            text(fields["name"], 256), creator?.let { text(it["nickname"], 128) }, trackCount = count), artwork(fields["coverImgUrl"]))
    }

    private fun artwork(value: JsonValue?): String? {
        val raw = text(value, 2048) ?: return null
        val parsed = raw.toHttpUrlOrNull() ?: return null
        val secure = if (parsed.scheme == "http" && parsed.port == 80) parsed.newBuilder().scheme("https").port(443).build() else parsed
        return secure.takeIf { it.scheme == "https" && it.username.isEmpty() && it.password.isEmpty() && it.fragment == null }?.toString()
    }
    private fun text(value: JsonValue?, limit: Int): String? = when (value) {
        null, JsonNull -> null
        is JsonText -> value.value.also { if (it.length > limit || it.any { character -> character.code < 32 }) invalid() }.takeIf { it.isNotBlank() }
        else -> invalid()
    }
    private fun optionalLong(value: JsonValue?): Long? = if (value == null || value === JsonNull) null else value.jsonLong()
    private fun id(value: JsonValue?): String {
        val result = when (value) {
            is JsonNumber -> value.value
            is JsonText -> value.value
            else -> invalid()
        }
        if (!onlineCanonicalId(result)) invalid()
        return result
    }
    private fun array(value: JsonValue?, emptyAllowed: Boolean): List<JsonValue> = when (value) {
        is JsonArray -> value.values
        null, JsonNull -> if (emptyAllowed) emptyList() else invalid()
        else -> invalid()
    }
    private fun checkCode(value: JsonObject) {
        when (value["code"].jsonLong()) {
            200L -> Unit
            301L, 401L, 403L -> throw AuthException(AuthIssue.LOGIN_REQUIRED)
            429L -> throw AuthException(AuthIssue.RATE_LIMITED)
            else -> invalid()
        }
    }
    private suspend fun <T> checked(action: suspend () -> T): T = withContext(Dispatchers.Default) {
        try { action() } catch (error: AuthException) {
            // EAPI已转换传输错误；仅账号授权与限流继续由会话所有者处理。
            if (error.issue == AuthIssue.LOGIN_REQUIRED || error.issue == AuthIssue.RATE_LIMITED) throw error
            throw OnlineCatalogException(authCatalogIssue(error.issue))
        } catch (error: NetworkException) {
            if (error.statusCode in setOf(401, 403)) throw AuthException(AuthIssue.LOGIN_REQUIRED)
            if (error.statusCode == 429) throw AuthException(AuthIssue.RATE_LIMITED)
            throw OnlineCatalogException(when (error.issue) {
                io.github.onlinemo.meloxwatch.data.network.NetworkIssue.UNAVAILABLE -> OnlineCatalogIssue.OFFLINE
                io.github.onlinemo.meloxwatch.data.network.NetworkIssue.TIMEOUT -> OnlineCatalogIssue.TIMEOUT
                io.github.onlinemo.meloxwatch.data.network.NetworkIssue.CERTIFICATE -> OnlineCatalogIssue.CERTIFICATE
                io.github.onlinemo.meloxwatch.data.network.NetworkIssue.BUSY -> OnlineCatalogIssue.BUSY
                io.github.onlinemo.meloxwatch.data.network.NetworkIssue.CLOSED -> OnlineCatalogIssue.CLOSED
                else -> OnlineCatalogIssue.INVALID_RESPONSE
            })
        }
    }
    private fun invalid(): Nothing = throw OnlineCatalogException(OnlineCatalogIssue.INVALID_RESPONSE)
    companion object { const val PAGE_SIZE = 100 }
}
