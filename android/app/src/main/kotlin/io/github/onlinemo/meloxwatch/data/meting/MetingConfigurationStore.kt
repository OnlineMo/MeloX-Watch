package io.github.onlinemo.meloxwatch.data.meting

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.ProviderException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal interface MetingConfigurationStore {
    suspend fun load(): MetingStoredConfiguration?
    suspend fun save(configuration: MetingStoredConfiguration)
    suspend fun clear()
}

internal interface MetingKeyProvider {
    fun encryptionKey(): SecretKey
    fun existingKey(): SecretKey?
    fun replaceInvalidatedKey(): SecretKey
}

/** 独立密钥、AAD 和 no-backup 文件；任何失败都不降级为明文或删除本地音乐。 */
internal class MetingConfigurationCipher(private val keys: MetingKeyProvider, applicationId: String) {
    private val aad = "MeloXWatch/meting/configuration/v1/$applicationId".toByteArray(Charsets.UTF_8)

    fun seal(configuration: MetingStoredConfiguration): ByteArray {
        val plain = MetingConfigurationCodec.encode(configuration)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            try { cipher.init(Cipher.ENCRYPT_MODE, keys.encryptionKey()) }
            catch (_: KeyPermanentlyInvalidatedException) {
                // 只有本次明确保存才可替换已确认永久失效的服务密钥，读取和暂时失败不替换。
                cipher.init(Cipher.ENCRYPT_MODE, keys.replaceInvalidatedKey())
            }
            cipher.updateAAD(aad)
            val encrypted = cipher.doFinal(plain)
            val iv = cipher.iv
            if (iv.size != IV_BYTES || encrypted.size + HEADER_BYTES + iv.size > MetingLimits.ENCRYPTED_BYTES) keyUnavailable()
            return ByteBuffer.allocate(HEADER_BYTES + iv.size + encrypted.size)
                .putInt(MAGIC).putInt(VERSION).putInt(iv.size).putInt(encrypted.size).put(iv).put(encrypted).array()
        } catch (_: GeneralSecurityException) { keyUnavailable() }
        catch (_: ProviderException) { keyUnavailable() }
        catch (_: SecurityException) { keyUnavailable() }
        finally { plain.fill(0) }
    }

    fun open(bytes: ByteArray): MetingStoredConfiguration {
        if (bytes.size !in (HEADER_BYTES + IV_BYTES + 16)..MetingLimits.ENCRYPTED_BYTES) corrupt()
        val input = ByteBuffer.wrap(bytes)
        if (input.int != MAGIC || input.int != VERSION || input.int != IV_BYTES) corrupt()
        val length = input.int
        if (length < 16 || length != input.remaining() - IV_BYTES) corrupt()
        val iv = ByteArray(IV_BYTES).also(input::get)
        val encrypted = ByteArray(length).also(input::get)
        val plain = try {
            val key = keys.existingKey() ?: keyUnavailable()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            cipher.doFinal(encrypted)
        } catch (_: BadPaddingException) { corrupt() }
        catch (_: GeneralSecurityException) { keyUnavailable() }
        catch (_: ProviderException) { keyUnavailable() }
        catch (_: SecurityException) { keyUnavailable() }
        return try { MetingConfigurationCodec.decode(plain) } finally { plain.fill(0) }
    }

    companion object {
        private const val MAGIC = 0x4d584d43
        private const val VERSION = 1
        private const val HEADER_BYTES = 16
        private const val IV_BYTES = 12
        private fun corrupt(): Nothing = throw MetingException(MetingIssue.CONFIGURATION_CORRUPT)
        private fun keyUnavailable(): Nothing = throw MetingException(MetingIssue.KEY_UNAVAILABLE)
    }
}

private object MetingConfigurationCodec {
    private const val MAGIC = 0x4d584d52
    private const val VERSION = 1

    fun encode(configuration: MetingStoredConfiguration): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            output.writeLong(configuration.generation)
            output.writeByte(if (configuration.enabled) 1 else 0)
            output.writeByte(if (configuration.allowCleartext) 1 else 0)
            text(output, configuration.endpoint.mode.name, 16)
            text(output, configuration.endpoint.storedAddress, MetingLimits.URL_CHARACTERS * 4)
            val credential = configuration.credential
            output.writeByte(if (credential == null) 0 else 1)
            if (credential != null) {
                text(output, credential.name, MetingLimits.HEADER_NAME_CHARACTERS)
                text(output, credential.storedValue(), MetingLimits.CREDENTIAL_CHARACTERS)
            }
        }
        return bytes.toByteArray().also {
            if (it.size > MetingLimits.CONFIGURATION_BYTES) throw MetingException(MetingIssue.CONFIGURATION_CORRUPT)
        }
    }

    fun decode(bytes: ByteArray): MetingStoredConfiguration = try {
        if (bytes.size > MetingLimits.CONFIGURATION_BYTES) corrupt()
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            if (input.readInt() != MAGIC || input.readInt() != VERSION) corrupt()
            val generation = input.readLong()
            if (generation < 0L) corrupt()
            val enabled = flag(input)
            val cleartext = flag(input)
            val modeName = text(input, 16)
            val mode = MetingEndpointMode.entries.firstOrNull { it.name == modeName } ?: corrupt()
            val endpoint = MetingEndpoint.checked(text(input, MetingLimits.URL_CHARACTERS * 4), mode, cleartext)
            val credential = if (flag(input)) MetingServiceCredential.checked(
                text(input, MetingLimits.HEADER_NAME_CHARACTERS), text(input, MetingLimits.CREDENTIAL_CHARACTERS)) else null
            if (input.available() != 0) corrupt()
            MetingStoredConfiguration(generation, endpoint, enabled, cleartext, credential)
        }
    } catch (_: IOException) { corrupt() }
    catch (_: IllegalArgumentException) { corrupt() }
    catch (_: MetingException) { corrupt() }

    private fun flag(input: DataInputStream): Boolean = when (input.readUnsignedByte()) {
        0 -> false
        1 -> true
        else -> corrupt()
    }

    private fun text(output: DataOutputStream, value: String, maximumBytes: Int) {
        val encoded = try {
            Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
        } catch (_: CharacterCodingException) { corrupt() }
        val bytes = ByteArray(encoded.remaining()).also(encoded::get)
        try {
            if (bytes.size !in 1..maximumBytes) corrupt()
            output.writeInt(bytes.size)
            output.write(bytes)
        } finally { bytes.fill(0) }
    }

    private fun text(input: DataInputStream, maximumBytes: Int): String {
        val size = input.readInt()
        if (size !in 1..maximumBytes || size > input.available()) corrupt()
        val bytes = ByteArray(size).also(input::readFully)
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) { corrupt() }
        finally { bytes.fill(0) }
    }

    private fun corrupt(): Nothing = throw MetingException(MetingIssue.CONFIGURATION_CORRUPT)
}

private class AndroidMetingKeys : MetingKeyProvider {
    override fun encryptionKey(): SecretKey = access {
        val store = keyStore()
        when (val key = store.getKey(ALIAS, null)) {
            is SecretKey -> key
            null -> if (store.containsAlias(ALIAS)) unavailable() else create()
            else -> unavailable()
        }
    }
    override fun existingKey(): SecretKey? = access { keyStore().getKey(ALIAS, null) as? SecretKey }
    override fun replaceInvalidatedKey(): SecretKey = access {
        keyStore().deleteEntry(ALIAS)
        create()
    }
    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun create(): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
        init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).setUserAuthenticationRequired(false).build())
        generateKey()
    }
    private inline fun <T> access(action: () -> T): T = try { action() }
    catch (error: KeyPermanentlyInvalidatedException) { throw error }
    catch (_: GeneralSecurityException) { unavailable() }
    catch (_: IOException) { unavailable() }
    catch (_: ProviderException) { unavailable() }
    catch (_: SecurityException) { unavailable() }
    companion object {
        private const val ALIAS = "meloxwatch.meting.configuration.aes-gcm.v1"
        private fun unavailable(): Nothing = throw MetingException(MetingIssue.KEY_UNAVAILABLE)
    }
}

/** AtomicFile 及其两个保留旁文件只接收密文，写入后读回核对，未知提交结果不伪造回滚。 */
internal class AtomicMetingConfigurationStore private constructor(
    private val file: File,
    private val cipher: MetingConfigurationCipher,
) : MetingConfigurationStore {
    private val atomic = AtomicFile(file)
    private val writes = Mutex()

    override suspend fun load(): MetingStoredConfiguration? = io {
        val encrypted = try { readEncrypted() } catch (_: FileNotFoundException) {
            if (ownFiles().any(File::exists)) throw MetingException(MetingIssue.STORAGE_UNAVAILABLE)
            return@io null
        }
        cipher.open(encrypted)
    }

    override suspend fun save(configuration: MetingStoredConfiguration): Unit = io {
        val encrypted = cipher.seal(configuration)
        try {
            val output = atomic.startWrite()
            var finishAttempted = false
            try {
                output.write(encrypted)
                output.flush()
                output.fd.sync()
                finishAttempted = true
                atomic.finishWrite(output)
                if (!MessageDigest.isEqual(encrypted, readEncrypted())) throw MetingException(MetingIssue.STORAGE_UNAVAILABLE)
            } catch (error: Throwable) {
                if (!finishAttempted) atomic.failWrite(output)
                throw error
            } finally { output.close() }
        } finally { encrypted.fill(0) }
    }

    override suspend fun clear(): Unit = io {
        atomic.delete()
        for (owned in ownFiles()) if (owned.exists() && !owned.delete()) throw MetingException(MetingIssue.STORAGE_UNAVAILABLE)
        if (ownFiles().any(File::exists)) throw MetingException(MetingIssue.STORAGE_UNAVAILABLE)
    }

    private fun ownFiles() = listOf(file, File(file.path + ".bak"), File(file.path + ".new"))
    private fun readEncrypted(): ByteArray = atomic.openRead().use { input ->
        val output = ByteArrayOutputStream(4096)
        val buffer = ByteArray(4096)
        while (true) {
            val read = input.read(buffer, 0, minOf(buffer.size, MetingLimits.ENCRYPTED_BYTES - output.size() + 1))
            if (read < 0) break
            if (read == 0 || output.size() + read > MetingLimits.ENCRYPTED_BYTES) throw MetingException(MetingIssue.CONFIGURATION_CORRUPT)
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    }

    private suspend fun <T> io(action: suspend () -> T): T = withContext(Dispatchers.IO) {
        writes.withLock {
            currentCoroutineContext().ensureActive()
            try { action() }
            catch (_: IOException) { throw MetingException(MetingIssue.STORAGE_UNAVAILABLE) }
            catch (_: SecurityException) { throw MetingException(MetingIssue.STORAGE_UNAVAILABLE) }
        }
    }

    companion object {
        suspend fun create(context: Context): AtomicMetingConfigurationStore = withContext(Dispatchers.IO) {
            val application = context.applicationContext
            try {
                val parent = application.noBackupFilesDir.canonicalFile
                val directory = File(parent, "meting-configuration-v1")
                if (!directory.isDirectory && !directory.mkdirs()) throw MetingException(MetingIssue.STORAGE_UNAVAILABLE)
                if (Files.isSymbolicLink(directory.toPath()) || !Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                    directory.canonicalFile.parentFile != parent
                ) throw MetingException(MetingIssue.STORAGE_UNAVAILABLE)
                AtomicMetingConfigurationStore(File(directory, "configuration.enc"),
                    MetingConfigurationCipher(AndroidMetingKeys(), application.packageName))
            } catch (_: IOException) { throw MetingException(MetingIssue.STORAGE_UNAVAILABLE) }
            catch (_: SecurityException) { throw MetingException(MetingIssue.STORAGE_UNAVAILABLE) }
        }
        internal fun controlled(file: File, cipher: MetingConfigurationCipher) = AtomicMetingConfigurationStore(file, cipher)
    }
}
