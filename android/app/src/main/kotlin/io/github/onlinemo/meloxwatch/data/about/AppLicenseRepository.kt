package io.github.onlinemo.meloxwatch.data.about

import android.content.res.AssetManager
import io.github.onlinemo.meloxwatch.data.network.JsonArray
import io.github.onlinemo.meloxwatch.data.network.JsonBounds
import io.github.onlinemo.meloxwatch.data.network.JsonNumber
import io.github.onlinemo.meloxwatch.data.network.JsonObject
import io.github.onlinemo.meloxwatch.data.network.JsonText
import io.github.onlinemo.meloxwatch.data.network.StrictJson
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class AppLicenseEntry(
    val id: String,
    val name: String,
    val version: String,
    val license: String,
    val noticeAsset: String,
    val noticeSha256: String,
)

internal class AppLicenseRepository(private val assets: AssetManager) {
    suspend fun entries(): List<AppLicenseEntry> = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        val root = StrictJson.decode(read("licenses/index.json", 128 * 1024) { context.ensureActive() },
            JsonBounds(128 * 1024, depth = 3, nodes = 4096, stringChars = 512, members = 512)) {
            context.ensureActive()
        } as? JsonObject ?: invalid()
        require((root["schema_version"] as? JsonNumber)?.value == "1")
        val raw = (root["entries"] as? JsonArray)?.values ?: invalid()
        require(raw.size in 1..256)
        val entries = raw.map { value ->
            context.ensureActive()
            val entry = value as? JsonObject ?: invalid()
            fun text(key: String): String = (entry[key] as? JsonText)?.value ?: invalid()
            val id = text("id")
            val path = text("notice_asset")
            val sha = text("notice_sha256")
            require(id.matches(Regex("[a-z0-9._+-]{1,160}")))
            require(path.matches(Regex("licenses/[A-Za-z0-9._+-]{1,160}\\.txt")))
            require(sha.matches(Regex("[0-9a-f]{64}")))
            AppLicenseEntry(id, text("name"), text("version"), text("license"), path, sha)
        }
        require(entries.map { it.id }.toSet().size == entries.size)
        entries
    }

    suspend fun notice(entry: AppLicenseEntry): String = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        require(entry.noticeAsset.matches(Regex("licenses/[A-Za-z0-9._+-]{1,160}\\.txt")))
        val bytes = read(entry.noticeAsset, 256 * 1024) { context.ensureActive() }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }
        require(sha == entry.noticeSha256)
        context.ensureActive()
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }

    private fun read(path: String, maximum: Int, cancelled: () -> Unit): ByteArray = assets.open(path).use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            cancelled()
            val count = input.read(buffer, 0, minOf(buffer.size, maximum - output.size() + 1))
            if (count < 0) break
            require(count > 0 && output.size() + count <= maximum)
            output.write(buffer, 0, count)
        }
        cancelled()
        output.toByteArray()
    }

    private fun invalid(): Nothing = throw IllegalArgumentException("Invalid packaged license catalog")
}
