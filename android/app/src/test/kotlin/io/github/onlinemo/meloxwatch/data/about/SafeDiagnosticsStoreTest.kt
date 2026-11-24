package io.github.onlinemo.meloxwatch.data.about

import android.content.Context
import android.content.ContextWrapper
import io.github.onlinemo.meloxwatch.data.network.JsonArray
import io.github.onlinemo.meloxwatch.data.network.JsonBounds
import io.github.onlinemo.meloxwatch.data.network.JsonObject
import io.github.onlinemo.meloxwatch.data.network.StrictJson
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 真实 AtomicFile 与独立私有目录；不授予外部文件或网络访问。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30], manifest = Config.NONE)
class SafeDiagnosticsStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test(timeout = 15_000L)
    fun boundedRecordsSurviveReopenAndExportOnlyWhitelistedFields() = runBlocking {
        val context = isolatedContext()
        val store = SafeDiagnosticsStore(context)
        repeat(80) { index ->
            store.record(DiagnosticArea.PLAYBACK, DiagnosticCode.entries[index % DiagnosticCode.entries.size],
                (100L + index) * 60_000L)
        }
        val records = SafeDiagnosticsStore(context).events()
        assertEquals(32, records.size)
        assertEquals(148L, records.first().epochMinute)
        val export = store.prepareExport(AppVersion("0.1.0", 7))
        try {
            val bytes = export.file.readBytes()
            assertEquals(bytes.size.toLong(), export.bytes)
            assertEquals(hash(bytes), export.sha256)
            val root = StrictJson.decode(bytes, JsonBounds(SafeDiagnosticsStore.MAX_BYTES)) as JsonObject
            assertEquals(setOf("schema_version", "data", "data_sha256", "complete"), root.fields.keys)
            val data = root["data"] as JsonObject
            assertEquals(setOf("app_version", "app_version_code", "events"), data.fields.keys)
            val events = (data["events"] as JsonArray).values
            assertEquals(records.size, events.size)
            events.forEach { assertEquals(setOf("area", "code", "epoch_minute"), (it as JsonObject).fields.keys) }
            assertTrue(export.toString().contains("redacted"))
            assertFalse(export.toString().contains(export.file.path))
        } finally { store.discard(export) }
        assertFalse(export.file.exists())
        store.clear()
        assertTrue(SafeDiagnosticsStore(context).events().isEmpty())
    }

    @Test(timeout = 15_000L)
    fun failedRetirementRetainsTheExactOwnerAndAnExplicitRetryUnblocksTheNextExport() = runBlocking {
        val store = SafeDiagnosticsStore(isolatedContext())
        val original = store.prepareExport(AppVersion("0.1.0", 7))
        val originalBytes = original.file.readBytes()
        assertTrue(original.file.delete())
        assertTrue(original.file.mkdir())
        val blocker = File(original.file, "owned-test-child").apply { writeText("blocked") }
        retirementRequired { store.discard(original) }
        retirementRequired { store.prepareExport(AppVersion("0.1.0", 7)) }
        retirementRequired { store.recoverRetiredExport() }
        assertTrue(blocker.delete())
        assertTrue(original.file.delete())
        original.file.writeBytes(originalBytes)
        store.recoverRetiredExport()
        assertFalse(original.file.exists())
        val next = store.prepareExport(AppVersion("0.1.0", 7))
        try {
            assertNotEquals(original.artifactId, next.artifactId)
            // 旧对象的迟到退役不能删除新对象借用的同名私有源。
            store.discard(original)
            assertTrue(next.file.isFile)
            assertArrayEquals(originalBytes, next.file.readBytes())
        } finally { store.discard(next) }
        assertFalse(next.file.exists())
    }

    @Test(timeout = 15_000L)
    fun recoveryCannotRetireAFileStillBorrowedByTheActiveExport() = runBlocking {
        val store = SafeDiagnosticsStore(isolatedContext())
        val active = store.prepareExport(AppVersion("0.1.0", 7))
        try {
            try {
                store.recoverRetiredExport()
                throw AssertionError("Active export was retired by recovery")
            } catch (_: java.io.IOException) { }
            assertTrue(active.file.isFile)
            assertEquals(active.sha256, hash(active.file.readBytes()))
        } finally { store.discard(active) }
    }

    @Test(timeout = 15_000L)
    fun invalidRecordsRemainUntouchedUntilAnExplicitClear() = runBlocking {
        val context = isolatedContext()
        val file = File(context.filesDir, "diagnostics-v1.json")
        val bytes = "{\"schema_version\":1,\"events\":[{\"area\":\"PLAYBACK\",\"code\":\"INTERNAL\",\"epoch_minute\":1,\"unexpected\":\"excluded\"}]}"
            .toByteArray()
        file.writeBytes(bytes)
        val store = SafeDiagnosticsStore(context)
        try {
            store.events()
            throw AssertionError("Unexpected diagnostic fields were accepted")
        } catch (_: IllegalArgumentException) { }
        assertArrayEquals(bytes, file.readBytes())
        store.clear()
        assertTrue(store.events().isEmpty())
    }

    private fun isolatedContext(): Context {
        val files = temporary.newFolder()
        val cache = temporary.newFolder()
        return object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getCacheDir(): File = cache
        }
    }

    private suspend fun retirementRequired(action: suspend () -> Any?) {
        try {
            action()
            throw AssertionError("Retirement failure lost its recovery classification")
        } catch (_: DiagnosticSourceRetirementException) { }
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }
}
