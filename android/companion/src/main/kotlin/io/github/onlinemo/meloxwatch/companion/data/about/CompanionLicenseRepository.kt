package io.github.onlinemo.meloxwatch.companion.data.about

import android.content.res.AssetManager
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class CompanionLicenseEntry(
    val id: String,
    val name: String,
    val version: String,
    val license: String,
    val noticeAsset: String,
    val noticeSha256: String,
)

/** Packaged text remains independent of pairing, document storage and network recovery. */
internal class CompanionLicenseRepository(private val openAsset: (String) -> InputStream) {
    constructor(assets: AssetManager) : this({ path -> assets.open(path, AssetManager.ACCESS_STREAMING) })

    suspend fun entries(): List<CompanionLicenseEntry> = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        val cancelled = { context.ensureActive() }
        val text = utf8(read("licenses/index.json", INDEX_BYTES, cancelled))
        CatalogReader(text, cancelled).read()
    }

    suspend fun notice(entry: CompanionLicenseEntry): String = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        context.ensureActive()
        require(NOTICE_PATH.matches(entry.noticeAsset) && SHA256.matches(entry.noticeSha256))
        val bytes = read(entry.noticeAsset, NOTICE_BYTES) { context.ensureActive() }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        require(digest == entry.noticeSha256)
        context.ensureActive()
        utf8(bytes).also { context.ensureActive() }
    }

    private fun read(path: String, maximum: Int, cancelled: () -> Unit): ByteArray {
        cancelled()
        return openAsset(path).use { input ->
            val output = ByteArrayOutputStream(4096)
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
    }

    /** The schema has four value levels and admits no recursive or unknown value shapes. */
    private class CatalogReader(private val text: String, private val cancelled: () -> Unit) {
        private var position = 0
        private var nodes = 0

        fun read(): List<CompanionLicenseEntry> {
            node(0)
            expect('{')
            val fields = mutableSetOf<String>()
            var catalog: List<CompanionLicenseEntry>? = null
            do {
                val name = member(fields)
                expect(':')
                when (name) {
                    "schema_version" -> {
                        node(1)
                        expect('1')
                    }
                    "entries" -> catalog = entries()
                    else -> invalid()
                }
            } while (consume(','))
            expect('}')
            require(fields == setOf("schema_version", "entries"))
            whitespace()
            require(position == text.length)
            cancelled()
            return checkNotNull(catalog)
        }

        private fun entries(): List<CompanionLicenseEntry> {
            node(1)
            expect('[')
            val entries = mutableListOf<CompanionLicenseEntry>()
            val ids = mutableSetOf<String>()
            if (consume(']')) invalid()
            do {
                require(entries.size < 256)
                val entry = entry()
                require(ids.add(entry.id))
                entries.add(entry)
            } while (consume(','))
            expect(']')
            return entries
        }

        private fun entry(): CompanionLicenseEntry {
            node(2)
            expect('{')
            val fields = linkedMapOf<String, String>()
            val names = mutableSetOf<String>()
            do {
                val name = member(names)
                require(name in ENTRY_FIELDS)
                expect(':')
                node(3)
                fields[name] = string()
            } while (consume(','))
            expect('}')
            require(names == ENTRY_FIELDS)
            val id = fields.getValue("id")
            val path = fields.getValue("notice_asset")
            val sha = fields.getValue("notice_sha256")
            require(ID.matches(id) && NOTICE_PATH.matches(path) && SHA256.matches(sha))
            return CompanionLicenseEntry(id, fields.getValue("name"), fields.getValue("version"),
                fields.getValue("license"), path, sha)
        }

        private fun member(names: MutableSet<String>): String {
            cancelled()
            require(names.size < 512)
            return string().also { require(names.add(it)) }
        }

        private fun node(depth: Int) {
            cancelled()
            require(depth <= 3 && ++nodes <= 4096)
        }

        private fun string(): String {
            expect('"')
            val output = StringBuilder()
            while (position < text.length) {
                if (position % 1024 == 0) cancelled()
                val char = text[position++]
                when {
                    char == '"' -> return output.toString().also(::validSurrogates)
                    char == '\\' -> output.append(when (val escaped = text.getOrNull(position++) ?: invalid()) {
                        '"', '\\', '/' -> escaped
                        'b' -> '\b'
                        'f' -> '\u000c'
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        'u' -> hexChar()
                        else -> invalid()
                    })
                    char.code < 32 -> invalid()
                    else -> output.append(char)
                }
                require(output.length <= 512)
            }
            invalid()
        }

        private fun hexChar(): Char {
            var value = 0
            repeat(4) {
                val char = text.getOrNull(position++) ?: invalid()
                require(char in "0123456789abcdefABCDEF")
                value = value * 16 + char.digitToInt(16)
            }
            return value.toChar()
        }

        private fun validSurrogates(value: String) {
            var index = 0
            while (index < value.length) {
                val char = value[index++]
                if (char.isHighSurrogate()) require(index < value.length && value[index++].isLowSurrogate())
                else require(!char.isLowSurrogate())
            }
        }

        private fun whitespace() {
            while (true) {
                when (text.getOrNull(position)) {
                    ' ', '\r', '\n', '\t' -> if (++position % 1024 == 0) cancelled()
                    else -> return
                }
            }
        }

        private fun consume(char: Char): Boolean {
            whitespace()
            return (text.getOrNull(position) == char).also { if (it) position++ }
        }

        private fun expect(char: Char) { require(consume(char)) }
        private fun invalid(): Nothing = throw IllegalArgumentException("Invalid packaged license catalog")
    }

    private companion object {
        const val INDEX_BYTES = 128 * 1024
        const val NOTICE_BYTES = 256 * 1024
        val ID = Regex("[a-z0-9._+-]{1,160}")
        val NOTICE_PATH = Regex("licenses/[A-Za-z0-9._+-]{1,160}\\.txt")
        val SHA256 = Regex("[0-9a-f]{64}")
        val ENTRY_FIELDS = setOf("id", "name", "version", "license", "notice_asset", "notice_sha256")

        fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    }
}
