package io.github.onlinemo.meloxwatch.data.meting

import android.security.keystore.KeyPermanentlyInvalidatedException
import java.io.File
import java.nio.ByteBuffer
import java.security.ProviderException
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30], manifest = Config.NONE)
class MetingConfigurationStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    @Before fun initializeControlledUrlParsing() { OkHttp.initialize(RuntimeEnvironment.getApplication()) }

    @Test fun freshNonceRoundTripsAddressAndIndependentCredentialWithoutPlaintext() {
        val cipher = MetingConfigurationCipher(FixtureMetingKeys(), "fixture.app")
        val configuration = metingConfiguration()
        val first = cipher.seal(configuration)
        val second = cipher.seal(configuration)
        assertNotEquals(first.copyOfRange(16, 28).toList(), second.copyOfRange(16, 28).toList())
        val restored = cipher.open(first)
        assertEquals(configuration.endpoint.storedAddress, restored.endpoint.storedAddress)
        assertEquals(configuration.generation, restored.generation)
        assertEquals(configuration.credential?.headers(), restored.credential?.headers())
        assertFalse(first.toString(Charsets.ISO_8859_1).contains("service-fixture"))
        assertFalse(first.toString(Charsets.ISO_8859_1).contains("service.invalid"))
    }

    @Test fun authenticatedApplicationIdentityTamperAndEnvelopeLimitsRejectWithoutRawDetails() {
        val keys = FixtureMetingKeys()
        val cipher = MetingConfigurationCipher(keys, "fixture.app")
        val original = cipher.seal(metingConfiguration())
        expect(MetingIssue.CONFIGURATION_CORRUPT) { MetingConfigurationCipher(keys, "other.app").open(original) }
        for ((offset, value) in listOf(0 to 0, 4 to 2, 8 to 13, 12 to 1)) {
            expect(MetingIssue.CONFIGURATION_CORRUPT) { cipher.open(original.copyOf().also { ByteBuffer.wrap(it).putInt(offset, value) }) }
        }
        expect(MetingIssue.CONFIGURATION_CORRUPT) { cipher.open(original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }) }
        expect(MetingIssue.CONFIGURATION_CORRUPT) { cipher.open(ByteArray(MetingLimits.ENCRYPTED_BYTES + 1)) }
    }

    @Test fun readNeverReplacesAnInvalidatedKeyAndOnlyExplicitNewSaveCanReplaceIt() {
        val keys = FixtureMetingKeys()
        val cipher = MetingConfigurationCipher(keys, "fixture.app")
        val original = cipher.seal(metingConfiguration())
        keys.invalidated = true
        expect(MetingIssue.KEY_UNAVAILABLE) { cipher.open(original) }
        assertEquals(0, keys.replacements)
        val newer = cipher.seal(metingConfiguration(generation = 2L))
        assertEquals(1, keys.replacements)
        assertEquals(2L, cipher.open(newer).generation)
        expect(MetingIssue.CONFIGURATION_CORRUPT) { cipher.open(original) }
    }

    @Test fun atomicFilesRemainEncryptedAndFailedKeyUsePreservesTheLastAcceptedBytes() = runBlocking {
        val file = File(temporary.root, "configuration.enc")
        val keys = FixtureMetingKeys()
        val store = AtomicMetingConfigurationStore.controlled(file, MetingConfigurationCipher(keys, "fixture.app"))
        assertNull(store.load())
        store.save(metingConfiguration())
        val original = file.readBytes()
        keys.failure = ProviderException("private provider detail")
        val error = expect(MetingIssue.KEY_UNAVAILABLE) { runBlocking { store.save(metingConfiguration(generation = 2L)) } }
        assertNull(error.cause)
        assertArrayEquals(original, file.readBytes())
        assertEquals(0, keys.replacements)
        keys.failure = null
        assertEquals(1L, store.load()?.generation)
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("Bearer service-fixture"))
    }

    @Test fun corruptedOrOversizedRecordsAreNotDeletedAndClearOnlyRemovesOwnedAtomicMembers() = runBlocking {
        val file = File(temporary.root, "configuration.enc")
        val marker = File(temporary.root, "music-library-marker").apply { writeText("keep") }
        val store = AtomicMetingConfigurationStore.controlled(file, MetingConfigurationCipher(FixtureMetingKeys(), "fixture.app"))
        for (bytes in listOf(byteArrayOf(1, 2, 3), ByteArray(MetingLimits.ENCRYPTED_BYTES + 1))) {
            file.writeBytes(bytes)
            expect(MetingIssue.CONFIGURATION_CORRUPT) { runBlocking { store.load() } }
            assertArrayEquals(bytes, file.readBytes())
        }
        File(file.path + ".new").writeBytes(byteArrayOf(4, 5, 6))
        store.clear()
        assertNull(store.load())
        assertFalse(file.exists())
        assertFalse(File(file.path + ".bak").exists())
        assertFalse(File(file.path + ".new").exists())
        assertEquals("keep", marker.readText())
    }

    private fun expect(issue: MetingIssue, action: () -> Unit): MetingException =
        assertThrows(MetingException::class.java) { action() }.also { assertEquals(issue, it.issue) }
}

internal class FixtureMetingKeys : MetingKeyProvider {
    private var key: SecretKey = SecretKeySpec(ByteArray(32) { 17 }, "AES")
    var invalidated = false
    var failure: RuntimeException? = null
    var replacements = 0
        private set
    override fun encryptionKey(): SecretKey {
        failure?.let { throw it }
        if (invalidated) throw KeyPermanentlyInvalidatedException()
        return key
    }
    override fun existingKey(): SecretKey? = encryptionKey()
    override fun replaceInvalidatedKey(): SecretKey {
        replacements++
        invalidated = false
        key = SecretKeySpec(ByteArray(32) { 23 }, "AES")
        return key
    }
}
