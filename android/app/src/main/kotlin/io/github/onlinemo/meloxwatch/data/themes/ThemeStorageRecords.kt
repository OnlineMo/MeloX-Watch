package io.github.onlinemo.meloxwatch.data.themes

import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontLimits
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest

internal data class ThemeInstallation(
    val ref: ThemeRef,
    val displayName: String,
    val packageSha256: String,
    val resourceBytes: Long,
    val fontBytes: Long,
)

internal data class ThemeCatalog(
    val revision: Long = 0L,
    val selected: ThemeChoice = ThemeBuiltins.defaultChoice,
    val previous: ThemeChoice = ThemeBuiltins.defaultChoice,
    val installed: List<ThemeInstallation> = emptyList(),
    val lastOperationId: String? = null,
    val lastOperationDigest: String? = null,
)

internal data class ThemeTransactionPlan(
    val operationId: String,
    val oldCatalogSha256: String,
    val newCatalogSha256: String,
    val installId: String?,
    val stageId: String?,
    val deleteId: String?,
)

/** 私有目录事务记录有独立版本和上限；它不是 LP-11 备份，也不恢复任何文件授权。 */
internal object ThemeStorageRecords {
    fun encode(catalog: ThemeCatalog): ByteArray {
        validateCatalog(catalog)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(CATALOG_MAGIC)
            output.writeInt(VERSION)
            output.writeLong(catalog.revision)
            output.choice(catalog.selected)
            output.choice(catalog.previous)
            output.writeInt(catalog.installed.size)
            for (theme in catalog.installed) {
                output.ref(theme.ref)
                output.writeUTF(theme.displayName)
                output.writeUTF(theme.packageSha256)
                output.writeLong(theme.resourceBytes)
                output.writeLong(theme.fontBytes)
            }
            output.optional(catalog.lastOperationId)
            output.optional(catalog.lastOperationDigest)
        }
        return bounded(bytes.toByteArray())
    }

    fun decode(bytes: ByteArray): ThemeCatalog {
        bounded(bytes)
        return decodeRecord(bytes) { input ->
            if (input.readInt() != CATALOG_MAGIC || input.readInt() != VERSION) themeFail(ThemeIssue.CORRUPT_STORAGE)
            val revision = input.readLong()
            val selected = input.choice()
            val previous = input.choice()
            val count = input.readInt()
            if (count !in 0..ThemeLimits.INSTALLED_THEMES) themeFail(ThemeIssue.CORRUPT_STORAGE)
            val installed = ArrayList<ThemeInstallation>(count)
            repeat(count) {
                installed += ThemeInstallation(input.ref(), input.readUTF(), input.readUTF(), input.readLong(), input.readLong())
            }
            val catalog = ThemeCatalog(revision, selected, previous, installed, input.optional(), input.optional())
            validateCatalog(catalog)
            catalog
        }
    }

    fun encodePlan(plan: ThemeTransactionPlan): ByteArray {
        validatePlan(plan)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(PLAN_MAGIC)
            output.writeInt(VERSION)
            output.writeUTF(plan.operationId)
            output.writeUTF(plan.oldCatalogSha256)
            output.writeUTF(plan.newCatalogSha256)
            output.optional(plan.installId)
            output.optional(plan.stageId)
            output.optional(plan.deleteId)
        }
        return bounded(bytes.toByteArray())
    }

    fun decodePlan(bytes: ByteArray): ThemeTransactionPlan = decodeRecord(bounded(bytes)) { input ->
        if (input.readInt() != PLAN_MAGIC || input.readInt() != VERSION) themeFail(ThemeIssue.CORRUPT_STORAGE)
        val plan = ThemeTransactionPlan(
            input.readUTF(), input.readUTF(), input.readUTF(), input.optional(), input.optional(), input.optional(),
        )
        validatePlan(plan)
        plan
    }

    fun digest(bytes: ByteArray): String = themeHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun validateCatalog(catalog: ThemeCatalog) {
        if (catalog.revision < 0 || catalog.installed.size > ThemeLimits.INSTALLED_THEMES ||
            (catalog.lastOperationId == null) != (catalog.lastOperationDigest == null)) themeFail(ThemeIssue.CORRUPT_STORAGE)
        if (catalog.lastOperationId != null && !isThemeOperationId(catalog.lastOperationId)) themeFail(ThemeIssue.CORRUPT_STORAGE)
        if (catalog.lastOperationDigest != null && !isThemeDigest(catalog.lastOperationDigest)) themeFail(ThemeIssue.CORRUPT_STORAGE)
        val ids = HashSet<String>()
        var fonts = 0L
        var resources = 0L
        for (theme in catalog.installed) {
            if (!ids.add(theme.ref.installationId) || theme.displayName.isBlank() ||
                theme.displayName.length > ThemeLimits.NAME_CHARACTERS || theme.displayName.any(Char::isISOControl) ||
                !isThemeDigest(theme.packageSha256) || theme.resourceBytes !in 0..ThemeLimits.UNPACKED_BYTES ||
                theme.fontBytes !in 0..theme.resourceBytes) themeFail(ThemeIssue.CORRUPT_STORAGE)
            if (theme.fontBytes > LyricsFontLimits.OWNED_BYTES - fonts || theme.resourceBytes > ThemeLimits.STORED_BYTES - resources) {
                themeFail(ThemeIssue.CORRUPT_STORAGE)
            }
            fonts += theme.fontBytes
            resources += theme.resourceBytes
        }
        for (choice in listOf(catalog.selected, catalog.previous)) {
            if (choice is ThemeChoice.Installed && catalog.installed.none { it.ref == choice.ref }) {
                themeFail(ThemeIssue.CORRUPT_STORAGE)
            }
        }
    }

    private fun validatePlan(plan: ThemeTransactionPlan) {
        if (!isThemeOperationId(plan.operationId) || !isThemeDigest(plan.oldCatalogSha256) ||
            !isThemeDigest(plan.newCatalogSha256) ||
            listOfNotNull(plan.installId, plan.stageId, plan.deleteId).any { !isThemeOperationId(it) } ||
            plan.installId != null && plan.installId != plan.stageId ||
            plan.deleteId != null && (plan.installId != null || plan.stageId != null)) themeFail(ThemeIssue.CORRUPT_STORAGE)
    }

    private fun DataOutputStream.choice(choice: ThemeChoice) {
        when (choice) {
            is ThemeChoice.Builtin -> {
                writeByte(0)
                writeUTF(choice.theme.name)
            }
            is ThemeChoice.Installed -> {
                writeByte(1)
                ref(choice.ref)
            }
        }
    }

    private fun DataInputStream.choice(): ThemeChoice = when (readUnsignedByte()) {
        0 -> ThemeChoice.Builtin(BuiltinTheme.valueOf(readUTF()))
        1 -> ThemeChoice.Installed(ref())
        else -> themeFail(ThemeIssue.CORRUPT_STORAGE)
    }

    private fun DataOutputStream.ref(ref: ThemeRef) {
        writeUTF(ref.installationId)
        writeUTF(ref.themeId)
        writeUTF(ref.manifestSha256)
    }

    private fun DataInputStream.ref(): ThemeRef = ThemeRef(readUTF(), readUTF(), readUTF())
    private fun DataOutputStream.optional(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeUTF(value)
    }

    private fun DataInputStream.optional(): String? = if (readBoolean()) readUTF() else null
    private fun bounded(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty() || bytes.size > ThemeLimits.CATALOG_BYTES) themeFail(ThemeIssue.CORRUPT_STORAGE)
        return bytes
    }

    private fun <T> decodeRecord(bytes: ByteArray, decode: (DataInputStream) -> T): T {
        try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val result = decode(input)
                if (input.read() != -1) themeFail(ThemeIssue.CORRUPT_STORAGE)
                return result
            }
        } catch (error: ThemeException) {
            throw error
        } catch (error: IOException) {
            throw ThemeException(ThemeIssue.CORRUPT_STORAGE, error)
        } catch (error: IllegalArgumentException) {
            throw ThemeException(ThemeIssue.CORRUPT_STORAGE, error)
        }
    }

    private const val VERSION = 1
    private const val CATALOG_MAGIC = 0x4d585443
    private const val PLAN_MAGIC = 0x4d585450
}
