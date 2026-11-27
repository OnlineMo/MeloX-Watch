package io.github.onlinemo.meloxwatch.data.cue

import io.github.onlinemo.meloxwatch.data.network.JsonBounds
import io.github.onlinemo.meloxwatch.data.network.JsonNull
import io.github.onlinemo.meloxwatch.data.network.JsonNumber
import io.github.onlinemo.meloxwatch.data.network.JsonObject
import io.github.onlinemo.meloxwatch.data.network.JsonText
import io.github.onlinemo.meloxwatch.data.network.JsonValue
import io.github.onlinemo.meloxwatch.data.network.StrictJson
import io.github.onlinemo.meloxwatch.model.CueBoundDocumentReference
import io.github.onlinemo.meloxwatch.model.CueFileBinding
import io.github.onlinemo.meloxwatch.model.CuePcmWaveFacts
import io.github.onlinemo.meloxwatch.model.CueSegmentBinding
import io.github.onlinemo.meloxwatch.model.CueTrackReference
import io.github.onlinemo.meloxwatch.model.LocalAccess
import io.github.onlinemo.meloxwatch.model.LocalCueReference
import io.github.onlinemo.meloxwatch.model.LocalLocationReference
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** 原生行与可移植提示共用严格封装；只有原生行携带绑定围栏。 */
object CueReferenceCodec {
    const val MAX_BYTES = 64 * 1024
    private const val MEMO_BYTES = 128 * 1024
    private const val MEMO_ENTRIES = 8
    private val decoded = ReferenceMemo<String, LocalCueReference>()
    private val encoded = ReferenceMemo<LocalCueReference, String>()
    private val nonNegativeInteger = Regex("0|[1-9][0-9]*")
    private val rootFields = setOf("schema", "state", "identity", "binding")
    private val identityFields = setOf("sourceId", "cueId", "trackNumber", "groupRevision", "startFrame",
        "endFrameExclusive", "endUsExclusive", "index00Frame", "cueContentToken", "boundaryVersion", "audioContentTokenHint")
    private val fileFields = setOf("sourceId", "sourceRevision", "authEpoch", "grantId", "aliasId", "objectId",
        "location", "providerContentToken", "verifiedContentToken", "sizeBytes")
    private val locationFields = setOf("locationId", "uri", "grantUri", "grantFlags", "access", "contentRevision")
    private val pcmFields = setOf("fileBytes", "dataOffset", "dataBytes", "sampleRateHz", "channels", "bitsPerSample")

    fun encode(reference: LocalCueReference): String {
        encoded[reference]?.let { return it }
        return encodeUncached(reference).also { encoded.remember(reference, it, it.length) }
    }

    private fun encodeUncached(reference: LocalCueReference): String {
        val identity = reference.identity
        val node = JsonObject(linkedMapOf(
            "schema" to number(1), "state" to JsonText(if (reference.isBound) "BOUND" else "PENDING"),
            "identity" to JsonObject(linkedMapOf(
                "sourceId" to JsonText(identity.sourceId), "cueId" to JsonText(identity.cueId),
                "trackNumber" to number(identity.trackNumber), "groupRevision" to number(identity.groupRevision),
                "startFrame" to number(identity.startFrame), "endFrameExclusive" to nullableNumber(identity.endFrameExclusive),
                "endUsExclusive" to number(identity.endUsExclusive), "index00Frame" to nullableNumber(identity.index00Frame),
                "cueContentToken" to JsonText(identity.cueContentToken), "boundaryVersion" to number(identity.boundaryVersion),
                "audioContentTokenHint" to nullableText(identity.audioContentTokenHint),
            )),
            "binding" to (reference.binding?.let { binding -> JsonObject(linkedMapOf(
                "cue" to file(binding.cue), "audio" to file(binding.audio), "pcm" to pcm(binding.pcm),
                "actualDocument" to (binding.actualDocument?.let(::document) ?: JsonNull),
                "adoptionAliasId" to nullableText(binding.adoptionAliasId),
            )) } ?: JsonNull),
        ))
        return String(StrictJson.encode(node, MAX_BYTES), Charsets.UTF_8)
    }

    fun decode(value: String, portableOnly: Boolean = false): LocalCueReference {
        require(value.length <= MAX_BYTES)
        decoded[value]?.let {
            // Parsed values are immutable hints. A hit never grants portable access to a native binding.
            require(!portableOnly || !it.isBound)
            return it
        }
        return decodeUncached(value, portableOnly).also { decoded.remember(value, it, value.length) }
    }

    private fun decodeUncached(value: String, portableOnly: Boolean): LocalCueReference {
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
        require(encoded.remaining() <= MAX_BYTES)
        val bytes = ByteArray(encoded.remaining()).also { encoded.get(it) }
        val root = objectNode(StrictJson.decode(bytes, JsonBounds(MAX_BYTES, depth = 4,
            nodes = 128, stringChars = 8192, members = 16)), rootFields)
        require(long(root, "schema") == 1L)
        val identity = objectNode(root["identity"], identityFields)
        val track = CueTrackReference(text(identity, "sourceId"), text(identity, "cueId"), integer(identity, "trackNumber"),
            long(identity, "groupRevision"), long(identity, "startFrame"), nullableLong(identity, "endFrameExclusive"),
            long(identity, "endUsExclusive"), nullableLong(identity, "index00Frame"), text(identity, "cueContentToken"),
            integer(identity, "boundaryVersion"), nullableText(identity, "audioContentTokenHint"))
        val binding = when (text(root, "state")) {
            "PENDING" -> {
                require(root["binding"] == JsonNull);
                null
            }
            "BOUND" -> {
                require(!portableOnly)
                val native = objectNode(root["binding"], setOf("cue", "audio", "pcm", "actualDocument", "adoptionAliasId"))
                val wave = objectNode(native["pcm"], pcmFields)
                CueSegmentBinding(readFile(native["cue"]), readFile(native["audio"]),
                    CuePcmWaveFacts(long(wave, "fileBytes"), long(wave, "dataOffset"), long(wave, "dataBytes"),
                        integer(wave, "sampleRateHz"), integer(wave, "channels"), integer(wave, "bitsPerSample")),
                    readDocument(native["actualDocument"]), nullableText(native, "adoptionAliasId"))
            }
            else -> throw IllegalArgumentException("Invalid CUE reference state")
        }
        return LocalCueReference(track, binding)
    }

    private fun document(value: CueBoundDocumentReference) = JsonObject(linkedMapOf(
        "sourceId" to JsonText(value.sourceId), "cueId" to JsonText(value.cueId),
        "groupRevision" to number(value.groupRevision), "trackNumber" to number(value.trackNumber),
    ))
    private fun readDocument(node: JsonValue?): CueBoundDocumentReference? {
        if (node == JsonNull) return null
        val value = objectNode(node, setOf("sourceId", "cueId", "groupRevision", "trackNumber"))
        return CueBoundDocumentReference(text(value, "sourceId"), text(value, "cueId"), long(value, "groupRevision"), integer(value, "trackNumber"))
    }

    private fun file(value: CueFileBinding) = JsonObject(linkedMapOf(
        "sourceId" to JsonText(value.sourceId), "sourceRevision" to number(value.sourceRevision),
        "authEpoch" to number(value.authEpoch), "grantId" to JsonText(value.grantId), "aliasId" to JsonText(value.aliasId),
        "objectId" to JsonText(value.objectId), "location" to location(value.location),
        "providerContentToken" to nullableText(value.providerContentToken),
        "verifiedContentToken" to nullableText(value.verifiedContentToken), "sizeBytes" to number(value.sizeBytes),
    ))

    private fun location(value: LocalLocationReference) = JsonObject(linkedMapOf(
        "locationId" to JsonText(value.locationId), "uri" to JsonText(value.uri), "grantUri" to JsonText(value.grantUri),
        "grantFlags" to number(value.grantFlags), "access" to JsonText(value.access.name), "contentRevision" to number(value.contentRevision),
    ))

    private fun pcm(value: CuePcmWaveFacts) = JsonObject(linkedMapOf(
        "fileBytes" to number(value.fileBytes), "dataOffset" to number(value.dataOffset), "dataBytes" to number(value.dataBytes),
        "sampleRateHz" to number(value.sampleRateHz), "channels" to number(value.channels), "bitsPerSample" to number(value.bitsPerSample),
    ))

    private fun readFile(node: JsonValue?): CueFileBinding {
        val file = objectNode(node, fileFields)
        val route = objectNode(file["location"], locationFields)
        return CueFileBinding(text(file, "sourceId"), long(file, "sourceRevision"), long(file, "authEpoch"),
            text(file, "grantId"), text(file, "aliasId"), text(file, "objectId"),
            LocalLocationReference(text(route, "locationId"), text(route, "uri"), text(route, "grantUri"),
                integer(route, "grantFlags"), LocalAccess.valueOf(text(route, "access")), long(route, "contentRevision")),
            nullableText(file, "providerContentToken"), nullableText(file, "verifiedContentToken"), long(file, "sizeBytes"))
    }

    private fun objectNode(node: JsonValue?, fields: Set<String>): JsonObject {
        val result = node as? JsonObject ?: throw IllegalArgumentException("Invalid CUE reference object")
        require(result.fields.keys == fields)
        return result
    }
    private fun long(node: JsonObject, name: String): Long {
        val raw = (node[name] as? JsonNumber)?.value ?: throw IllegalArgumentException("Invalid CUE reference number")
        require(nonNegativeInteger.matches(raw))
        return raw.toLongOrNull() ?: throw IllegalArgumentException("Invalid CUE reference extent")
    }
    private fun integer(node: JsonObject, name: String): Int = long(node, name).also { require(it <= Int.MAX_VALUE) }.toInt()
    private fun nullableLong(node: JsonObject, name: String): Long? = if (node[name] == JsonNull) null else long(node, name)
    private fun text(node: JsonObject, name: String): String =
        (node[name] as? JsonText)?.value ?: throw IllegalArgumentException("Invalid CUE reference text")
    private fun nullableText(node: JsonObject, name: String): String? = if (node[name] == JsonNull) null else text(node, name)
    private fun number(value: Number): JsonNumber = JsonNumber(value.toString())
    private fun nullableNumber(value: Long?): JsonValue = value?.let(::number) ?: JsonNull
    private fun nullableText(value: String?): JsonValue = value?.let(::JsonText) ?: JsonNull

    /** Exact values only; current source, permission and content fences remain the caller's responsibility. */
    private class ReferenceMemo<K, V> {
        private data class Entry<V>(val value: V, val bytes: Int)
        private val values = LinkedHashMap<K, Entry<V>>(MEMO_ENTRIES, 0.75f, true)
        private var retainedBytes = 0

        @Synchronized operator fun get(key: K): V? = values[key]?.value

        @Synchronized fun remember(key: K, value: V, textChars: Int) {
            // Both UTF-16 representations plus a fixed allowance for this bounded immutable model.
            val bytes = textChars.toLong() * 4L + 8192L
            if (bytes > MEMO_BYTES) return
            values.remove(key)?.let { retainedBytes -= it.bytes }
            while (values.size >= MEMO_ENTRIES || retainedBytes + bytes > MEMO_BYTES) {
                val iterator = values.entries.iterator()
                retainedBytes -= iterator.next().value.bytes
                iterator.remove()
            }
            values[key] = Entry(value, bytes.toInt())
            retainedBytes += bytes.toInt()
        }
    }
}
