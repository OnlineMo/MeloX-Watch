package io.github.onlinemo.meloxwatch.data.online

import io.github.onlinemo.meloxwatch.data.network.HttpRequest
import io.github.onlinemo.meloxwatch.data.network.HttpResponse
import io.github.onlinemo.meloxwatch.data.network.JsonObject
import io.github.onlinemo.meloxwatch.data.network.StrictJson
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** Public, synthetic protocol fixtures only; decrypts the request to assert its real paging fields. */
internal fun catalogFields(request: HttpRequest): JsonObject {
    val text = request.body.toString(StandardCharsets.US_ASCII).removePrefix("params=")
    val encoded = ByteArray(text.length / 2) { index -> text.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec("e82ckenh8dichen8".toByteArray(StandardCharsets.US_ASCII), "AES"))
    val plain = cipher.doFinal(encoded).toString(Charsets.UTF_8)
    val pieces = plain.split("-36cd479b6b5-")
    check(pieces.size == 3)
    return StrictJson.parse(pieces[1].toByteArray(Charsets.UTF_8)) as JsonObject
}

internal fun catalogJson(value: String): HttpResponse = HttpResponse(200, emptyList(), value.toByteArray(Charsets.UTF_8))
internal fun catalogSong(id: Long, title: String = "Fixture song $id"): String =
    "{\"id\":$id,\"name\":\"$title\",\"dt\":10000,\"ar\":[{\"name\":\"Fixture artist\"}],\"al\":{\"name\":\"Fixture album\",\"picUrl\":\"https://artwork.invalid/$id.jpg\"}}"
internal fun catalogSearch(ids: List<Long>, total: Long): HttpResponse = catalogJson(
    "{\"code\":200,\"result\":{\"songCount\":$total,\"songs\":[${ids.joinToString(",") { catalogSong(it) }}]}}")
