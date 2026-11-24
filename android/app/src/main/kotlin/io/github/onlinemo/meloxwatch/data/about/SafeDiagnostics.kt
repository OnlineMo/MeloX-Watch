package io.github.onlinemo.meloxwatch.data.about

import android.content.Context
import android.util.AtomicFile
import io.github.onlinemo.meloxwatch.data.network.JsonArray
import io.github.onlinemo.meloxwatch.data.network.JsonBoolean
import io.github.onlinemo.meloxwatch.data.network.JsonBounds
import io.github.onlinemo.meloxwatch.data.network.JsonNumber
import io.github.onlinemo.meloxwatch.data.network.JsonObject
import io.github.onlinemo.meloxwatch.data.network.JsonText
import io.github.onlinemo.meloxwatch.data.network.StrictJson
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class DiagnosticArea { APP, LIBRARY, PLAYBACK, LYRICS, APPEARANCE, FILES, BACKUP, TRANSFER, UPDATE }
enum class DiagnosticCode {
    PERMISSION, STORAGE, BUSY, SOURCE_CHANGED, SOURCE_MISSING, SOURCE_DAMAGED,
    UNSUPPORTED, TIMEOUT, CONNECTION, INVALID_DATA, RECOVERY_REQUIRED, INTERNAL,
}

/** 接口只接收枚举和分钟时间，无法传入路径、URI、曲名、账号或Throwable消息。 */
data class SafeDiagnosticEvent(val area: DiagnosticArea, val code: DiagnosticCode, val epochMinute: Long) {
    init { require(epochMinute in 0..MAX_MINUTE) }

    companion object { const val MAX_MINUTE = 16_666_666_666L }
}

internal class PrivateDiagnosticExport internal constructor(
    val file: File,
    val sha256: String,
    val bytes: Long,
    val artifactId: String = UUID.randomUUID().toString(),
) {
    override fun toString(): String = "PrivateDiagnosticExport(redacted)"
}

internal class DiagnosticSourceRetirementException : IOException("Diagnostic source cleanup is required")

internal class SafeDiagnosticsStore(context: Context) {
    private val application = context.applicationContext
    // 目录获取可能创建私有目录；仅在下方IO操作内访问，构造不进行文件工作。
    private val base: File get() = File(checkNotNull(application.filesDir) { "Diagnostic storage unavailable" }, "diagnostics-v1.json")
    private val exports: File get() = File(checkNotNull(application.cacheDir) { "Diagnostic export storage unavailable" }, "diagnostic-exports")

    private val operations = Mutex()
    private var activeExport: PrivateDiagnosticExport? = null
    private var retirementReadyForRetry = false

    suspend fun events(): List<SafeDiagnosticEvent> = withContext(Dispatchers.IO) {
        operations.withLock { read() }
    }

    suspend fun record(area: DiagnosticArea, code: DiagnosticCode, epochMs: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) {
            operations.withLock {
                val current = read()
                val event = SafeDiagnosticEvent(area, code, (epochMs / 60_000).coerceIn(0, SafeDiagnosticEvent.MAX_MINUTE))
                if (current.lastOrNull() != event) write((current + event).takeLast(MAX_EVENTS))
            }
        }

    suspend fun clear() = withContext(Dispatchers.IO) { operations.withLock { write(emptyList()) } }

    suspend fun prepareExport(version: AppVersion): PrivateDiagnosticExport {
        var owned: PrivateDiagnosticExport? = null
        return try {
            withContext(Dispatchers.IO) {
                operations.withLock {
                    if (activeExport != null) {
                        if (retirementReadyForRetry) throw DiagnosticSourceRetirementException()
                        throw IOException("Diagnostic export already active")
                    }
                    val context = currentCoroutineContext()
                    val data = JsonObject(mapOf(
                        "app_version" to JsonText(version.name),
                        "app_version_code" to JsonNumber(version.code.toString()),
                        "events" to JsonArray(read().map(::eventJson)),
                    ))
                    val body = StrictJson.encode(data, MAX_BYTES) { context.ensureActive() }
                    val bytes = StrictJson.encode(JsonObject(mapOf(
                        "schema_version" to JsonNumber("1"),
                        "data" to data,
                        "data_sha256" to JsonText(hash(body)),
                        "complete" to JsonBoolean(true),
                    )), MAX_BYTES) { context.ensureActive() }
                    if (!exports.isDirectory && !exports.mkdirs()) {
                        throw IOException("Diagnostic export storage unavailable")
                    }
                    val file = File(exports, "active.json")
                    val atomic = AtomicFile(file)
                    val checksum = hash(bytes)
                    val export = PrivateDiagnosticExport(file, checksum, bytes.size.toLong())
                    activeExport = export
                    owned = export
                    retirementReadyForRetry = false
                    // 一个已退役进程最多留此单一私有缓存；不累积取消后的未知代次文件。
                    try { deleteExportFile(file) }
                    catch (failure: Exception) {
                        retirementReadyForRetry = true
                        throw DiagnosticSourceRetirementException().also { it.addSuppressed(failure) }
                    }
                    var output: java.io.FileOutputStream? = null
                    try {
                        val acquired = atomic.startWrite()
                        output = acquired
                        context.ensureActive()
                        acquired.write(bytes)
                        context.ensureActive()
                        atomic.finishWrite(acquired)
                        if (!file.isFile || file.length() != bytes.size.toLong() || hash(file.readBytes()) != checksum) {
                            throw IOException("Diagnostic export verification failed")
                        }
                        export
                    } catch (failure: Throwable) {
                        try { if (output != null) atomic.failWrite(output) }
                        catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
                        retirementReadyForRetry = true
                        try { retireExport(export) }
                        catch (cleanup: Exception) {
                            if (cleanup !== failure) failure.addSuppressed(cleanup)
                            if (failure !is CancellationException) throw cleanup
                        }
                        throw failure
                    }
                }
            }
        } catch (failure: CancellationException) {
            // IO已提交而结果尚未交给UI时也回收同一对象，不能因取消丢失清理所有权。
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    operations.withLock {
                        owned?.takeIf { activeExport === it && !retirementReadyForRetry }?.let(::retireExport)
                    }
                }
            } catch (cleanup: Exception) {
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    suspend fun discard(export: PrivateDiagnosticExport) = withContext(Dispatchers.IO) {
        operations.withLock {
            if (activeExport !== export) return@withLock
            retireExport(export)
        }
    }

    /** 只重试上一位真实writer退出后已请求退役的对象，不清理正在借用的源文件。 */
    suspend fun recoverRetiredExport() = withContext(Dispatchers.IO) {
        operations.withLock {
            val export = activeExport ?: return@withLock
            if (!retirementReadyForRetry) throw IOException("Diagnostic export is still owned")
            retireExport(export)
        }
    }

    private fun retireExport(export: PrivateDiagnosticExport) {
        if (activeExport !== export) return
        if (export.file.parentFile?.canonicalFile != exports.canonicalFile || export.file.isDirectory) {
            retirementReadyForRetry = true
            throw DiagnosticSourceRetirementException()
        }
        try { deleteExportFile(export.file) }
        catch (failure: Exception) {
            retirementReadyForRetry = true
            throw DiagnosticSourceRetirementException().also { it.addSuppressed(failure) }
        }
        activeExport = null
        retirementReadyForRetry = false
    }

    private suspend fun read(): List<SafeDiagnosticEvent> {
        val context = currentCoroutineContext()
        if (!base.exists() && !File(base.path + ".bak").exists()) return emptyList()
        val bytes = AtomicFile(base).openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                context.ensureActive()
                val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES - output.size() + 1))
                if (count < 0) break
                if (count == 0 || output.size() + count > MAX_BYTES) throw IOException("Invalid diagnostic record")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val root = StrictJson.decode(bytes, JsonBounds(MAX_BYTES, depth = 3, nodes = 512, stringChars = 64, members = 64)) {
            context.ensureActive()
        } as? JsonObject ?: invalid()
        require(root.fields.keys == setOf("schema_version", "events"))
        require((root["schema_version"] as? JsonNumber)?.value == "1")
        val events = (root["events"] as? JsonArray)?.values ?: invalid()
        require(events.size <= MAX_EVENTS)
        return events.map { value ->
            val event = value as? JsonObject ?: invalid()
            require(event.fields.keys == setOf("area", "code", "epoch_minute"))
            val area = DiagnosticArea.entries.firstOrNull { it.name == (event["area"] as? JsonText)?.value } ?: invalid()
            val code = DiagnosticCode.entries.firstOrNull { it.name == (event["code"] as? JsonText)?.value } ?: invalid()
            val minute = (event["epoch_minute"] as? JsonNumber)?.value?.toLongOrNull() ?: invalid()
            SafeDiagnosticEvent(area, code, minute)
        }
    }

    private suspend fun write(events: List<SafeDiagnosticEvent>) {
        require(events.size <= MAX_EVENTS)
        val context = currentCoroutineContext()
        val bytes = StrictJson.encode(JsonObject(mapOf(
            "schema_version" to JsonNumber("1"),
            "events" to JsonArray(events.map(::eventJson)),
        )), MAX_BYTES) { context.ensureActive() }
        val atomic = AtomicFile(base)
        val output = atomic.startWrite()
        try {
            context.ensureActive()
            output.write(bytes)
            context.ensureActive()
            atomic.finishWrite(output)
            if (read() != events) throw IOException("Diagnostic record verification failed")
        } catch (failure: Throwable) {
            atomic.failWrite(output)
            throw failure
        }
    }

    private fun eventJson(event: SafeDiagnosticEvent): JsonObject = JsonObject(mapOf(
        "area" to JsonText(event.area.name),
        "code" to JsonText(event.code.name),
        "epoch_minute" to JsonNumber(event.epochMinute.toString()),
    ))

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }
    private fun invalid(): Nothing = throw IOException("Invalid diagnostic record")

    private fun deleteExportFile(file: File) {
        val paths = listOf(file, File(file.path + ".bak"), File(file.path + ".new"))
        if (paths.any { it.isDirectory }) throw IOException("Unexpected diagnostic cache object")
        AtomicFile(file).delete()
        if (paths.any { it.exists() }) throw IOException("Diagnostic source retirement is not confirmed")
    }

    companion object {
        const val MAX_EVENTS = 32
        const val MAX_BYTES = 32 * 1024
    }
}
