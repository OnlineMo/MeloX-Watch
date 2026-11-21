package io.github.onlinemo.meloxwatch.data.themes

import android.graphics.Bitmap
import android.graphics.Color
import android.os.CancellationSignal
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontTestFixtures
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.Deflater
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals

/** 原字体夹具与许可只读复用；ZIP 夹具显式写真实 local/central/descriptor，不 mock 解包结果。 */
internal object ThemeTestFixtures {
    const val ROOM = ThemeLimits.FREE_RESERVE_BYTES + ThemeLimits.STORED_BYTES + 1024 * 1024

    data class Asset(
        val id: String,
        val path: String,
        val bytes: ByteArray,
        val kind: String = "image",
        val mediaType: String = "image/png",
        val licenseName: String = "Original test fixture",
        val licenseText: String = "Original bitmap generated in the test; no external image source.",
    )

    data class Package(val manifest: ByteArray, val assets: List<Asset>) {
        fun records(method: Int = 0, descriptor: Boolean = false): List<ZipRecord> =
            listOf(ZipRecord("theme.json", manifest, method, descriptor)) +
                assets.map { ZipRecord(it.path, it.bytes, method, descriptor) }

        fun zip(method: Int = 0, descriptor: Boolean = false): ByteArray = archive(records(method, descriptor))
    }

    data class ZipRecord(
        val name: String,
        val bytes: ByteArray,
        val method: Int = 0,
        val descriptor: Boolean = false,
        val externalAttributes: Long = 0,
        val localName: String = name,
        val flags: Int = 0x800 or if (descriptor) 8 else 0,
        val localFlags: Int = flags,
        val expandedBytes: Long = bytes.size.toLong(),
        val crc32: Long = crc(bytes),
        val compressed: ByteArray? = null,
        val compressedCount: Long? = null,
        val descriptorSignature: Long = 0x08074b50,
        val descriptorCrc: Long = crc32,
        val extra: ByteArray = byteArrayOf(),
    )

    fun id(): String = UUID.randomUUID().toString()
    fun request(): ThemeImportRequest = ThemeImportRequest(id(), "content://theme-fixture/selected")
    fun apply(preview: ThemePreview, replaces: ThemeRef? = null, acknowledgment: Int? = null): ThemeApplyRequest =
        ThemeApplyRequest(id(), preview.token, preview.expectedRevision, replaces, acknowledgment)

    fun packageOf(
        themeId: String = "fixture.dark",
        assets: List<Asset> = emptyList(),
        icons: Map<String, String> = emptyMap(),
        fonts: Map<String, String> = emptyMap(),
        background: JSONObject = JSONObject().put("mode", "default"),
    ): Package = Package(manifest(themeId, assets, icons, fonts, background).toString().toByteArray(Charsets.UTF_8), assets)

    fun manifest(
        themeId: String = "fixture.dark",
        assets: List<Asset> = emptyList(),
        icons: Map<String, String> = emptyMap(),
        fonts: Map<String, String> = emptyMap(),
        background: JSONObject = JSONObject().put("mode", "default"),
    ): JSONObject {
        val colors = JSONObject()
        for (name in listOf("background", "surface", "surfaceRaised", "surfacePressed")) colors.put(name, "#000000")
        for (name in listOf("textPrimary", "textSecondary", "error", "disabledText")) colors.put(name, "#FFFFFF")
        colors.put("accent", "#FF8080")
        colors.put("focus", "#FFFFFF")
        val resources = JSONArray()
        for (asset in assets) {
            val resource = JSONObject()
            resource.put("id", asset.id)
            resource.put("path", asset.path)
            resource.put("kind", asset.kind)
            resource.put("mediaType", asset.mediaType)
            resource.put("byteCount", asset.bytes.size)
            resource.put("sha256", ThemeStorageRecords.digest(asset.bytes))
            resource.put("license", notice(asset.licenseName, asset.licenseText))
            resources.put(resource)
        }
        val result = JSONObject()
        result.put("schemaVersion", 1)
        result.put("id", themeId)
        result.put("displayName", "Fixture dark")
        result.put("author", "MeloX Watch test fixture")
        result.put("license", notice("Original fixture", "Static theme tokens constructed in the test."))
        result.put("colors", colors)
        result.put("background", background)
        result.put("resources", resources)
        if (icons.isNotEmpty()) result.put("icons", JSONObject(icons))
        if (fonts.isNotEmpty()) result.put("fonts", JSONObject(fonts))
        return result
    }

    fun jsonBytes(value: JSONObject): ByteArray = value.toString().toByteArray(Charsets.UTF_8)

    fun font(name: String = LyricsFontTestFixtures.VARIABLE, resourceId: String = "font.original"): Asset {
        val extension = name.substringAfterLast('.')
        val licenseFile = if (extension == "ttf") "RobotoMono-OFL.txt" else "SourceCodePro-LICENSE.md"
        val license = checkNotNull(javaClass.getResourceAsStream("/fixtures/lyrics-fonts/$licenseFile")).use {
            it.readBytes().toString(Charsets.UTF_8)
        }
        return Asset(resourceId, "assets/$name", LyricsFontTestFixtures.bytes(name), "font", "font/$extension",
            "SIL Open Font License 1.1", license)
    }

    fun png(icon: Boolean = false, edge: Int = 32): ByteArray {
        val bitmap = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(if (icon) Color.TRANSPARENT else Color.rgb(40, 90, 140))
            if (icon) for (y in edge / 4 until edge * 3 / 4) {
                for (x in edge / 4 until edge * 3 / 4) bitmap.setPixel(x, y, Color.RED)
            }
            val bytes = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes))
            return bytes.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    fun raster(format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(30, 70, 120))
            val bytes = ByteArrayOutputStream()
            check(bitmap.compress(format, 85, bytes))
            return bytes.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    fun payload(directory: File, value: Package): File {
        check(directory.mkdirs() || directory.isDirectory)
        File(directory, "theme.json").writeBytes(value.manifest)
        for (asset in value.assets) {
            val file = File(directory, asset.path)
            check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
            file.writeBytes(asset.bytes)
        }
        return directory
    }

    fun repository(
        directory: File,
        source: ThemePackageOpener,
        io: LocalProviderIo = LocalProviderIo(),
        otherFontBytes: () -> Long? = { 0L },
        available: (File) -> Long = { ROOM },
        faults: ThemeStorageFaults = ThemeStorageFaults {},
        validator: ThemeAssetValidator = ThemeAssetValidator(),
    ): LocalThemeRepository = LocalThemeRepository(directory, io, source, ThemeFontStorageBudget(otherFontBytes),
        validator, available, faults)

    fun expect(issue: ThemeIssue, action: () -> Unit): ThemeException {
        try {
            action()
            throw AssertionError("Expected $issue")
        } catch (error: ThemeException) {
            assertEquals(issue, error.issue)
            return error
        }
    }

    suspend fun expectSuspending(issue: ThemeIssue, action: suspend () -> Unit): ThemeException {
        try {
            action()
            throw AssertionError("Expected $issue")
        } catch (error: ThemeException) {
            assertEquals(issue, error.issue)
            return error
        }
    }

    fun archive(records: List<ZipRecord>): ByteArray {
        val bytes = ByteArrayOutputStream()
        val output = DataOutputStream(bytes)
        data class Central(val record: ZipRecord, val compressedBytes: ByteArray, val localOffset: Int)
        val central = ArrayList<Central>()
        for (record in records) {
            val compressed = record.compressed ?: if (record.method == 8) deflate(record.bytes) else record.bytes
            central += Central(record, compressed, bytes.size())
            output.le32(0x04034b50)
            output.le16(if (record.method == 8) 20 else 10)
            output.le16(record.localFlags)
            output.le16(record.method)
            output.le16(0)
            output.le16(0)
            output.le32(if (record.descriptor) 0 else record.crc32)
            output.le32(if (record.descriptor) 0 else record.compressedCount ?: compressed.size.toLong())
            output.le32(if (record.descriptor) 0 else record.expandedBytes)
            val name = record.localName.toByteArray(Charsets.UTF_8)
            output.le16(name.size)
            output.le16(record.extra.size)
            output.write(name)
            output.write(record.extra)
            output.write(compressed)
            if (record.descriptor) {
                output.le32(record.descriptorSignature)
                output.le32(record.descriptorCrc)
                output.le32(record.compressedCount ?: compressed.size.toLong())
                output.le32(record.expandedBytes)
            }
        }
        val centralOffset = bytes.size()
        for (entry in central) {
            val record = entry.record
            output.le32(0x02014b50)
            output.le16(0x0314)
            output.le16(if (record.method == 8) 20 else 10)
            output.le16(record.flags)
            output.le16(record.method)
            output.le16(0)
            output.le16(0)
            output.le32(record.crc32)
            output.le32(record.compressedCount ?: entry.compressedBytes.size.toLong())
            output.le32(record.expandedBytes)
            val name = record.name.toByteArray(Charsets.UTF_8)
            output.le16(name.size)
            output.le16(0)
            output.le16(0)
            output.le16(0)
            output.le16(0)
            output.le32(record.externalAttributes)
            output.le32(entry.localOffset.toLong())
            output.write(name)
        }
        val centralSize = bytes.size() - centralOffset
        output.le32(0x06054b50)
        output.le16(0)
        output.le16(0)
        output.le16(records.size)
        output.le16(records.size)
        output.le32(centralSize.toLong())
        output.le32(centralOffset.toLong())
        output.le16(0)
        output.flush()
        return bytes.toByteArray()
    }

    fun crc(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

    private fun notice(name: String, text: String): JSONObject = JSONObject().put("name", name).put("text", text)

    private fun deflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        try {
            deflater.setInput(bytes)
            deflater.finish()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                check(count > 0)
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun DataOutputStream.le16(value: Int) {
        writeByte(value)
        writeByte(value ushr 8)
    }

    private fun DataOutputStream.le32(value: Long) {
        repeat(4) { shift -> writeByte((value ushr (shift * 8)).toInt()) }
    }
}

internal class BytesThemeSource(var bytes: ByteArray, var declared: Long? = bytes.size.toLong()) : ThemePackageOpener {
    val opens = AtomicInteger()
    val inputCloses = AtomicInteger()
    val descriptorCloses = AtomicInteger()
    var openFailure: Throwable? = null
    var closeFailure: Throwable? = null

    override fun open(uri: String, signal: CancellationSignal): OpenedThemePackage {
        signal.throwIfCanceled()
        opens.incrementAndGet()
        openFailure?.let { throw it }
        val input = object : ByteArrayInputStream(bytes) {
            override fun close() {
                inputCloses.incrementAndGet()
                super.close()
                closeFailure?.let { throw it }
            }
        }
        return OpenedThemePackage(input, declared) { descriptorCloses.incrementAndGet() }
    }
}
