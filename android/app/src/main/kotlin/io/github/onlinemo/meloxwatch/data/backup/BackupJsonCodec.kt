package io.github.onlinemo.meloxwatch.data.backup

import java.io.ByteArrayOutputStream
import java.util.Collections
import io.github.onlinemo.meloxwatch.data.network.JsonArray
import io.github.onlinemo.meloxwatch.data.network.JsonBoolean
import io.github.onlinemo.meloxwatch.data.network.JsonBounds
import io.github.onlinemo.meloxwatch.data.network.JsonNull
import io.github.onlinemo.meloxwatch.data.network.JsonNumber
import io.github.onlinemo.meloxwatch.data.network.JsonObject
import io.github.onlinemo.meloxwatch.data.network.JsonText
import io.github.onlinemo.meloxwatch.data.network.JsonValue
import io.github.onlinemo.meloxwatch.data.network.NetworkException
import io.github.onlinemo.meloxwatch.data.network.StrictJson

/** Each chunk uses the pure JSON parser with independent backup input bounds. */
internal object BackupJsonCodec {
    private const val MAGIC = "MeloX Watch logical backup"
    private val HEADER_FIELDS = setOf("format", "version", "logicalVersion", "databaseVersion", "exportId", "createdEpochMs", "scopes", "encryption", "audioIncluded")
    private val RECORD_FIELDS = setOf("kind", "key", "values")
    private val COMPLETE_FIELDS = setOf("complete", "exportId", "records", "chunks", "resources", "logicalBytes", "resourceBytes", "chainSha256")

    fun header(value: BackupHeader, checkCancelled: () -> Unit = {}): ByteArray = encode(JsonObject(mapOf(
        "format" to JsonText(MAGIC), "version" to number(value.formatVersion.toLong()),
        "logicalVersion" to number(value.logicalVersion.toLong()), "databaseVersion" to number(value.sourceDatabaseVersion.toLong()),
        "exportId" to JsonText(value.exportId), "createdEpochMs" to number(value.createdEpochMs),
        "scopes" to JsonArray(value.scopes.sortedBy { it.ordinal }.map { JsonText(it.name) }),
        "encryption" to JsonText("none"), "audioIncluded" to JsonBoolean(false),
    )), BackupLimits.MANIFEST_BYTES, checkCancelled)

    fun readHeader(bytes: ByteArray, checkCancelled: () -> Unit = {}): BackupHeader = readChecked {
        val objectValue = objectValue(decode(bytes, BackupLimits.MANIFEST_BYTES, checkCancelled))
        exactFields(objectValue, HEADER_FIELDS)
        if (text(objectValue["format"]) != MAGIC || integer(objectValue["version"]) != BackupLimits.FORMAT_VERSION.toLong() ||
            integer(objectValue["logicalVersion"]) != BackupLimits.LOGICAL_VERSION.toLong() ||
            text(objectValue["encryption"]) != "none" || flag(objectValue["audioIncluded"])) {
            throw BackupException(BackupIssue.UNSUPPORTED_VERSION)
        }
        val sourceVersion = integer(objectValue["databaseVersion"])
        if (sourceVersion !in 1L..BackupLimits.DATABASE_VERSION.toLong()) throw BackupException(BackupIssue.UNSUPPORTED_VERSION)
        val rawScopes = array(objectValue["scopes"]).values.map { scope(text(it)) }
        if (rawScopes.isEmpty() || rawScopes.distinct().size != rawScopes.size) corrupt()
        BackupHeader(exportId = text(objectValue["exportId"]), sourceDatabaseVersion = sourceVersion.toInt(),
            scopes = Collections.unmodifiableSet(rawScopes.toSet()), createdEpochMs = integer(objectValue["createdEpochMs"]))
    }

    fun records(values: List<BackupRecord>, checkCancelled: () -> Unit = {}): ByteArray {
        if (values.isEmpty() || values.size > BackupLimits.ROWS_PER_CHUNK) throw BackupException(BackupIssue.INVALID_INPUT)
        val output = ByteArrayOutputStream(8192)
        output.write('['.code)
        for ((index, value) in values.withIndex()) {
            val bytes = record(value, checkCancelled)
            val separator = if (index == 0) 0 else 1
            if (bytes.size > BackupLimits.CHUNK_BYTES - output.size() - separator - 1) {
                throw BackupException(BackupIssue.TOO_LARGE)
            }
            if (separator != 0) output.write(','.code)
            output.write(bytes)
        }
        output.write(']'.code)
        return output.toByteArray()
    }

    fun record(row: BackupRecord, checkCancelled: () -> Unit = {}): ByteArray {
        checkCancelled()
        BackupLogicalSchema.validate(row)
        return encode(JsonObject(mapOf("kind" to JsonText(row.kind.name), "key" to JsonText(row.key),
            "values" to JsonObject(row.values.mapValues { (_, value) -> cell(value) }))), BackupLimits.RECORD_BYTES, checkCancelled)
    }

    fun readRecord(bytes: ByteArray, checkCancelled: () -> Unit = {}): BackupRecord = readChecked {
        readRecordNode(decode(bytes, BackupLimits.RECORD_BYTES, checkCancelled), checkCancelled)
    }

    fun readRecords(bytes: ByteArray, checkCancelled: () -> Unit = {}): List<BackupRecord> = readChecked {
        val rows = array(decode(bytes, BackupLimits.CHUNK_BYTES, checkCancelled)).values
        if (rows.isEmpty() || rows.size > BackupLimits.ROWS_PER_CHUNK) corrupt()
        rows.map { readRecordNode(it, checkCancelled) }
    }

    private fun readRecordNode(node: JsonValue, checkCancelled: () -> Unit): BackupRecord {
        checkCancelled()
        val row = objectValue(node)
        exactFields(row, RECORD_FIELDS)
        val kind = BackupRecordKind.entries.firstOrNull { it.name == text(row["kind"]) } ?: corrupt()
        val values = objectValue(row["values"]).fields
        if (values.size > BackupLimits.FIELDS_PER_RECORD) corrupt()
        val decodedRecord = BackupRecord(kind, text(row["key"]), values.mapValues { (_, value) -> readCell(value) })
        // A small chunk may still contain one oversized record; enforce its independent limit.
        record(decodedRecord, checkCancelled)
        return decodedRecord
    }

    fun completion(value: BackupCompletion, checkCancelled: () -> Unit = {}): ByteArray = encode(JsonObject(mapOf(
        "complete" to JsonBoolean(true), "exportId" to JsonText(value.exportId),
        "records" to counts(value.records), "chunks" to counts(value.chunks),
        "resources" to number(value.resources), "logicalBytes" to number(value.logicalBytes),
        "resourceBytes" to number(value.resourceBytes), "chainSha256" to JsonText(value.chainSha256),
    )), BackupLimits.MANIFEST_BYTES, checkCancelled)

    fun readCompletion(bytes: ByteArray, checkCancelled: () -> Unit = {}): BackupCompletion = readChecked {
        val value = objectValue(decode(bytes, BackupLimits.MANIFEST_BYTES, checkCancelled))
        exactFields(value, COMPLETE_FIELDS)
        if (!flag(value["complete"])) throw BackupException(BackupIssue.INCOMPLETE_ARCHIVE)
        BackupCompletion(text(value["exportId"]), readCounts(value["records"]), readCounts(value["chunks"]),
            nonnegative(value["resources"]), nonnegative(value["logicalBytes"]), nonnegative(value["resourceBytes"]),
            text(value["chainSha256"]))
    }

    /** Settings reuse the same typed cell envelope without exposing their storage filename. */
    fun encodeValues(values: Map<String, BackupCell>, checkCancelled: () -> Unit = {}): ByteArray =
        encode(JsonObject(values.mapValues { (_, value) -> cell(value) }), BackupLimits.RECORD_BYTES, checkCancelled)

    fun decodeValues(bytes: ByteArray, checkCancelled: () -> Unit = {}): Map<String, BackupCell> = readChecked {
        objectValue(decode(bytes, BackupLimits.RECORD_BYTES, checkCancelled)).fields.mapValues { (_, value) -> readCell(value) }
    }

    private fun readCell(value: JsonValue): BackupCell = when (value) {
        JsonNull -> BackupCell.Null
        is JsonText -> BackupCell.Text(value.value)
        is JsonBoolean -> BackupCell.Flag(value.value)
        is JsonNumber -> {
            if (value.value.none { it in ".eE" }) BackupCell.Integer(value.value.toLongOrNull() ?: corrupt())
            else BackupCell.Decimal(value.value.toDoubleOrNull()?.takeIf { it.isFinite() } ?: corrupt())
        }
        else -> corrupt()
    }

    private fun cell(value: BackupCell): JsonValue = when (value) {
        BackupCell.Null -> JsonNull
        is BackupCell.Text -> JsonText(value.value)
        is BackupCell.Integer -> number(value.value)
        is BackupCell.Decimal -> JsonNumber(value.value.toString())
        is BackupCell.Flag -> JsonBoolean(value.value)
    }

    private fun counts(values: Map<BackupRecordKind, Long>): JsonObject = JsonObject(
        BackupRecordKind.entries.associate { it.name to number(values[it] ?: 0L) },
    )

    private fun readCounts(value: JsonValue?): Map<BackupRecordKind, Long> {
        val objectValue = objectValue(value)
        if (objectValue.fields.keys != BackupRecordKind.entries.map { it.name }.toSet()) corrupt()
        return BackupRecordKind.entries.associateWith { nonnegative(objectValue[it.name]) }
    }

    private fun scope(value: String): BackupScope = BackupScope.entries.firstOrNull { it.name == value } ?: corrupt()
    private fun number(value: Long): JsonNumber = JsonNumber(value.toString())
    private fun objectValue(value: JsonValue?): JsonObject = value as? JsonObject ?: corrupt()
    private fun array(value: JsonValue?): JsonArray = value as? JsonArray ?: corrupt()
    private fun text(value: JsonValue?): String = (value as? JsonText)?.value ?: corrupt()
    private fun integer(value: JsonValue?): Long = (value as? JsonNumber)?.value?.toLongOrNull() ?: corrupt()
    private fun nonnegative(value: JsonValue?): Long = integer(value).takeIf { it >= 0L } ?: corrupt()
    private fun flag(value: JsonValue?): Boolean = (value as? JsonBoolean)?.value ?: corrupt()
    private fun exactFields(value: JsonObject, expected: Set<String>) { if (value.fields.keys != expected) corrupt() }
    private fun corrupt(): Nothing = throw BackupException(BackupIssue.CORRUPT_ARCHIVE)

    private fun decode(bytes: ByteArray, limit: Int, checkCancelled: () -> Unit): JsonValue {
        if (bytes.size > limit) throw BackupException(BackupIssue.TOO_LARGE)
        return StrictJson.decode(bytes, JsonBounds(bytes = limit, depth = BackupLimits.JSON_DEPTH,
            nodes = 40_000, stringChars = BackupLimits.RECORD_BYTES, members = BackupLimits.FIELDS_PER_RECORD), checkCancelled)
    }

    private fun encode(value: JsonValue, limit: Int, checkCancelled: () -> Unit): ByteArray = try {
        StrictJson.encode(value, limit, checkCancelled)
    } catch (error: NetworkException) {
        throw BackupException(BackupIssue.TOO_LARGE, error)
    }

    private inline fun <T> readChecked(block: () -> T): T = try {
        block()
    } catch (error: NetworkException) {
        throw BackupException(BackupIssue.CORRUPT_ARCHIVE, error)
    } catch (error: IllegalArgumentException) {
        throw BackupException(BackupIssue.CORRUPT_ARCHIVE, error)
    }
}

internal data class BackupCompletion(
    val exportId: String,
    val records: Map<BackupRecordKind, Long>,
    val chunks: Map<BackupRecordKind, Long>,
    val resources: Long,
    val logicalBytes: Long,
    val resourceBytes: Long,
    val chainSha256: String,
) {
    init {
        require(records.values.all { it >= 0L } && chunks.values.all { it >= 0L })
        require(resources >= 0L && logicalBytes >= 0L && resourceBytes >= 0L)
        require(chainSha256.matches(Regex("[0-9a-f]{64}")))
    }
}
