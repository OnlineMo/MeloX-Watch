package io.github.onlinemo.meloxwatch.companion.data.about

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.onlinemo.meloxwatch.companion.ui.about.CompanionLicensesViewModel
import io.github.onlinemo.meloxwatch.companion.ui.about.companionLicenseSections
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class CompanionLicenseRepositoryTest {
    @Test
    fun packagedCatalogProvidesAllFiftyThreeCompleteVerifiedNotices() = runBlocking {
        val repository = CompanionLicenseRepository(RuntimeEnvironment.getApplication().assets)
        val entries = repository.entries()
        assertEquals(53, entries.size)
        assertEquals(entries.size, entries.map { it.id }.toSet().size)
        assertTrue(entries.any { it.id == "melox-watch-project-license" && it.license == "GPL-3.0-only" })
        for (entry in entries) assertTrue(entry.id, repository.notice(entry).isNotBlank())
    }

    @Test
    fun largestCatalogIsAcceptedAndItsSuccessorIsRejected() = runBlocking {
        val entries = (0 until 256).map { entryJson(id = "entry-$it") }
        assertEquals(256, catalogRepository(catalog(entries.joinToString(","))).entries().size)
        rejects { catalogRepository(catalog((entries + entryJson(id = "entry-256")).joinToString(","))).entries() }
        rejects { catalogRepository(catalog("")).entries() }
    }

    @Test
    fun duplicateFieldsIdsUnknownFieldsAndNonCanonicalSchemasAreRejected() = runBlocking {
        val entry = entryJson()
        val invalid = listOf(
            "{\"schema_version\":1,\"schema_version\":1,\"entries\":[$entry]}",
            catalog(entry.replace("\"id\":\"example\"", "\"id\":\"example\",\"id\":\"again\"")),
            catalog("$entry,$entry"),
            catalog(entry.dropLast(1) + ",\"extra\":\"text\"}"),
            "{\"schema_version\":1,\"entries\":[$entry],\"extra\":{}}",
        ) + listOf("\"1\"", "1.0", "1e0", "-1", "01", "null", "true", "2").map {
            "{\"schema_version\":$it,\"entries\":[$entry]}"
        }
        for (text in invalid) rejects { catalogRepository(text).entries() }
    }

    @Test
    fun jsonStringsUtf8AndValueShapesAreStrict() = runBlocking {
        val original = entryJson()
        val invalidNames = listOf(
            "\"raw\nnewline\"", "\"raw\u0001control\"", "\"bad\\xescape\"", "\"bad\\uGG00\"",
            "\"\\ud800\"", "\"\\udc00\"", "[[]]", "{}", "17", "null",
        )
        for (name in invalidNames) {
            rejects { catalogRepository(catalog(original.replace("\"name\":\"Example\"", "\"name\":$name"))).entries() }
        }
        rejects { catalogRepository(catalog() + "{}").entries() }
        rejects { CompanionLicenseRepository { ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28)) }.entries() }
        assertEquals("Music 🎵", catalogRepository(catalog(entryJson(name = "Music 🎵"))).entries().single().name)
    }

    @Test
    fun longStringsTraversalAndNoncanonicalHashesAreRejected() = runBlocking {
        assertEquals(512, catalogRepository(catalog(entryJson(name = "a".repeat(512)))).entries().single().name.length)
        assertEquals(512, catalogRepository(catalog(entryJson(name = "🎵".repeat(256)))).entries().single().name.length)
        rejects { catalogRepository(catalog(entryJson(name = "a".repeat(513)))).entries() }
        rejects { catalogRepository(catalog(entryJson(name = "🎵".repeat(257)))).entries() }
        assertEquals(160, catalogRepository(catalog(entryJson(id = "a".repeat(160)))).entries().single().id.length)
        rejects { catalogRepository(catalog(entryJson(id = "a".repeat(161)))).entries() }
        for (path in listOf("licenses/../example.txt", "licenses/nested/example.txt", "https://example.test/a.txt", "licenses/a\\b.txt")) {
            rejects { catalogRepository(catalog(entryJson(path = path))).entries() }
        }
        rejects { catalogRepository(catalog(entryJson(id = "UPPER"))).entries() }
        rejects { catalogRepository(catalog(entryJson(sha = digest(NOTICE).uppercase()))).entries() }
    }

    @Test
    fun catalogByteOverflowClosesAfterReadingAtMostOneExcessByte() = runBlocking {
        val stream = ProbeStream((catalog() + " ".repeat(128 * 1024)).toByteArray())
        rejects { CompanionLicenseRepository { stream }.entries() }
        assertTrue(stream.closed)
        assertEquals(128 * 1024 + 1, stream.consumed)
    }

    @Test
    fun noticeLimitIsInclusiveAndOverflowClosesAtTheLimit() = runBlocking {
        val maximum = ByteArray(256 * 1024) { 'a'.code.toByte() }
        val accepted = ProbeStream(maximum)
        assertEquals(maximum.size, CompanionLicenseRepository { accepted }.notice(entry(maximum)).length)
        assertTrue(accepted.closed)
        val oversized = maximum + byteArrayOf('b'.code.toByte())
        val rejected = ProbeStream(oversized)
        rejects { CompanionLicenseRepository { rejected }.notice(entry(oversized)) }
        assertTrue(rejected.closed)
        assertEquals(256 * 1024 + 1, rejected.consumed)
    }

    @Test
    fun missingModifiedAndMalformedNoticesFailWithoutAcceptingOrRewritingText() = runBlocking {
        rejects { CompanionLicenseRepository { throw IOException("Missing packaged notice") }.notice(entry()) }
        rejects { CompanionLicenseRepository { ByteArrayInputStream("modified".toByteArray()) }.notice(entry()) }
        val invalidUtf8 = byteArrayOf(0xc3.toByte(), 0x28)
        rejects { CompanionLicenseRepository { ByteArrayInputStream(invalidUtf8) }.notice(entry(invalidUtf8)) }
        val opened = AtomicInteger()
        val repository = CompanionLicenseRepository { opened.incrementAndGet(); ByteArrayInputStream(NOTICE) }
        rejects { repository.notice(entry().copy(noticeAsset = "licenses/../example.txt")) }
        rejects { repository.notice(entry().copy(noticeSha256 = digest(NOTICE).uppercase())) }
        assertEquals(0, opened.get())
        assertEquals(NOTICE.toString(Charsets.UTF_8), repository.notice(entry()))
    }

    @Test
    fun cancellationPropagatesAndClosesTheOnlyOpenedNotice() = runBlocking {
        val owner = Job(coroutineContext[Job])
        val bytes = ByteArray(12 * 1024) { 'a'.code.toByte() }
        val stream = ProbeStream(bytes) { owner.cancel(CancellationException("Packaged notice stopped")) }
        val reading = async(owner) { CompanionLicenseRepository { stream }.notice(entry(bytes)) }
        try {
            reading.await()
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            owner.join()
        } finally {
            owner.cancel()
            owner.join()
        }
        assertTrue(stream.closed)
        assertEquals(4096, stream.consumed)
    }

    @Test
    fun hiddenOrBackgroundLifecycleCreatesNoAssetRead() {
        val opened = AtomicInteger()
        val repository = CompanionLicenseRepository { opened.incrementAndGet(); ByteArrayInputStream(NOTICE) }
        val store = ViewModelStore()
        val model = ViewModelProvider(store, viewModelFactory {
            initializer { CompanionLicensesViewModel(repository) }
        })[CompanionLicensesViewModel::class.java]
        try {
            model.show()
            model.hide()
            model.foreground()
            model.background()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(0, opened.get())
            assertFalse(model.state.value.loading)
            assertTrue(model.state.value.sections.isEmpty())
        } finally {
            store.clear()
        }
    }

    @Test
    fun lazySectionsKeepAllWhitespaceAndUnicodeWithBoundedLayout() {
        for (text in listOf("", "a".repeat(8193), "x".repeat(2047) + "🎵" + " tail\n".repeat(1800), "中文 原文\n".repeat(1800))) {
            val sections = companionLicenseSections(text)
            assertEquals(text, sections.joinToString(""))
            assertTrue(sections.all { it.length <= 2048 })
            assertTrue(sections.none { it.lastOrNull()?.isHighSurrogate() == true || it.firstOrNull()?.isLowSurrogate() == true })
        }
        var checks = 0
        try {
            companionLicenseSections("a".repeat(8192)) { if (++checks == 2) throw CancellationException("Layout stopped") }
            fail("Layout cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(2, checks)
        }
    }

    private suspend fun rejects(action: suspend () -> Unit) {
        try {
            action()
            fail("Malformed packaged license input must fail")
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The UI presents the same local unavailable state for all invalid packaged data.
        }
    }

    private class ProbeStream(bytes: ByteArray, private val afterRead: () -> Unit = {}) : ByteArrayInputStream(bytes) {
        var consumed = 0
            private set
        var closed = false
            private set
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, length).also {
            if (it > 0) { consumed += it; afterRead() }
        }
        override fun close() { closed = true; super.close() }
    }

    private companion object {
        val NOTICE = "Copyright Example contributors\nComplete fixture license text.\n".toByteArray()
        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        fun entry(bytes: ByteArray = NOTICE): CompanionLicenseEntry =
            CompanionLicenseEntry("example", "Example", "1", "MIT", "licenses/example.txt", digest(bytes))
        fun entryJson(id: String = "example", name: String = "Example", path: String = "licenses/example.txt", sha: String = digest(NOTICE)): String =
            JSONObject().put("id", id).put("name", name).put("version", "1").put("license", "MIT")
                .put("notice_asset", path).put("notice_sha256", sha).toString()
        fun catalog(entry: String = entryJson()): String = "{\"schema_version\":1,\"entries\":[$entry]}"
        fun catalogRepository(text: String): CompanionLicenseRepository = CompanionLicenseRepository { ByteArrayInputStream(text.toByteArray()) }
    }
}
