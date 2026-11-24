package io.github.onlinemo.meloxwatch.data.themes

import io.github.onlinemo.meloxwatch.data.transfer.TransferFingerprint
import io.github.onlinemo.meloxwatch.data.transfer.TransferHash
import io.github.onlinemo.meloxwatch.data.transfer.TransferLimits
import io.github.onlinemo.meloxwatch.data.transfer.TransferOperationBinding
import io.github.onlinemo.meloxwatch.data.transfer.TransferOperationKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** 私有存储把已校验主题包绑定到与 Room 收据相同的操作。 */
internal data class TransferredThemeRecord(
    val protocolSchema: Int,
    val operationId: String,
    val targetFingerprint: String,
    val peerFingerprint: String,
    val payloadHash: String,
    val packageSha256: String,
    val packageBytes: Long,
    val manifestSha256: String,
    val themeId: String,
    val displayName: String,
    val receivedThemeRevision: Long,
) {
    init {
        if (protocolSchema !in 1..TransferLimits.SCHEMA || !isThemeOperationId(operationId) ||
            listOf(targetFingerprint, peerFingerprint, payloadHash, packageSha256, manifestSha256).any { !isThemeDigest(it) } ||
            packageBytes !in 1..ThemeLimits.PACKAGE_BYTES || !isThemeId(themeId) ||
            displayName.isBlank() || displayName.codePointCount(0, displayName.length) > ThemeLimits.NAME_CHARACTERS || receivedThemeRevision < 0L) {
            themeFail(ThemeIssue.CORRUPT_STORAGE)
        }
    }

    fun binding(): TransferOperationBinding = TransferOperationBinding(
        operationId, TransferOperationKind.THEME, TransferFingerprint.fromWire(hexBytes(targetFingerprint)),
        TransferFingerprint.fromWire(hexBytes(peerFingerprint)),
        TransferHash.fromWire(hexBytes(payloadHash)),
    )

    fun matches(binding: TransferOperationBinding): Boolean = binding().sameOperation(binding)
    override fun toString(): String = "<local-received-theme-binding>"
}

/** 文件事务提交后保留 guard，直到 Room 确认这次精确本机批准。 */
internal data class TransferredThemeApplyIntent(val candidate: TransferredThemeRecord, val request: ThemeApplyRequest) {
    init {
        if (!isThemeOperationId(request.operationId) || !isThemeOperationId(request.previewToken) || request.expectedRevision < 0L ||
            request.fontLicenseAcknowledgmentVersion != null && request.fontLicenseAcknowledgmentVersion < 0) {
            themeFail(ThemeIssue.CORRUPT_STORAGE)
        }
    }
    override fun toString(): String = "<local-theme-approval-intent>"
}

internal object TransferredThemeRecords {
    private const val RECORD_MAGIC = 0x4d575452
    private const val INTENT_MAGIC = 0x4d575441
    private const val STORAGE_SCHEMA = 1
    const val MAXIMUM_BYTES = 8 * 1024

    fun encode(record: TransferredThemeRecord): ByteArray = write { output ->
        output.writeInt(RECORD_MAGIC)
        output.writeInt(STORAGE_SCHEMA)
        output.writeRecord(record)
    }

    fun decode(bytes: ByteArray): TransferredThemeRecord = read(bytes) { input ->
        if (input.readInt() != RECORD_MAGIC || input.readInt() != STORAGE_SCHEMA) themeFail(ThemeIssue.CORRUPT_STORAGE)
        input.readRecord()
    }

    fun encode(intent: TransferredThemeApplyIntent): ByteArray = write { output ->
        output.writeInt(INTENT_MAGIC)
        output.writeInt(STORAGE_SCHEMA)
        output.writeRecord(intent.candidate)
        with(intent.request) {
            output.writeUTF(operationId)
            output.writeUTF(previewToken)
            output.writeLong(expectedRevision)
            output.writeBoolean(replaces != null)
            replaces?.let { output.writeUTF(it.installationId)
                output.writeUTF(it.themeId)
                output.writeUTF(it.manifestSha256) }
            output.writeBoolean(fontLicenseAcknowledgmentVersion != null)
            fontLicenseAcknowledgmentVersion?.let(output::writeInt)
        }
    }

    fun decodeIntent(bytes: ByteArray): TransferredThemeApplyIntent = read(bytes) { input ->
        if (input.readInt() != INTENT_MAGIC || input.readInt() != STORAGE_SCHEMA) themeFail(ThemeIssue.CORRUPT_STORAGE)
        val candidate = input.readRecord()
        val operationId = input.readUTF()
        val previewToken = input.readUTF()
        val revision = input.readLong()
        val replacement = if (input.readBoolean()) ThemeRef(input.readUTF(), input.readUTF(), input.readUTF()) else null
        val license = if (input.readBoolean()) input.readInt() else null
        TransferredThemeApplyIntent(candidate, ThemeApplyRequest(operationId, previewToken, revision, replacement, license))
    }

    private fun DataOutputStream.writeRecord(record: TransferredThemeRecord) {
        writeInt(record.protocolSchema)
        writeUTF(record.operationId)
        writeUTF(record.targetFingerprint)
        writeUTF(record.peerFingerprint)
        writeUTF(record.payloadHash)
        writeUTF(record.packageSha256)
        writeLong(record.packageBytes)
        writeUTF(record.manifestSha256)
        writeUTF(record.themeId)
        writeUTF(record.displayName)
        writeLong(record.receivedThemeRevision)
    }

    private fun DataInputStream.readRecord(): TransferredThemeRecord = TransferredThemeRecord(
        readInt(), readUTF(), readUTF(), readUTF(), readUTF(), readUTF(), readLong(), readUTF(), readUTF(), readUTF(), readLong(),
    )

    private fun write(action: (DataOutputStream) -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use(action)
        return bytes.toByteArray().also { if (it.size > MAXIMUM_BYTES) themeFail(ThemeIssue.CORRUPT_STORAGE) }
    }

    private fun <T> read(bytes: ByteArray, action: (DataInputStream) -> T): T {
        if (bytes.isEmpty() || bytes.size > MAXIMUM_BYTES) themeFail(ThemeIssue.CORRUPT_STORAGE)
        return try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                action(input).also { if (input.read() != -1) themeFail(ThemeIssue.CORRUPT_STORAGE) }
            }
        } catch (error: IOException) {
            throw ThemeException(ThemeIssue.CORRUPT_STORAGE, error)
        } catch (error: IllegalArgumentException) {
            throw ThemeException(ThemeIssue.CORRUPT_STORAGE, error)
        }
    }
}

private fun hexBytes(value: String): ByteArray = ByteArray(32) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
