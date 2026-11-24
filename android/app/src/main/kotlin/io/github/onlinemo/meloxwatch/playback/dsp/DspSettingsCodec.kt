package io.github.onlinemo.meloxwatch.playback.dsp

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Base64

/** 有界类型记录保留精确参数和完整名称，不依赖 JSON 数字推断或分隔符拆名。 */
internal object DspSettingsCodec {
    private val schemaKey = intPreferencesKey("schema")
    private val revisionKey = longPreferencesKey("revision")
    private val payloadKey = stringPreferencesKey("payload")
    private const val MAGIC = 0x4d445350
    private const val RECORD_BYTES = 24_576
    private const val ENCODED_CHARACTERS = 32_768
    private val keys = setOf("schema", "revision", "payload")

    fun decode(stored: Preferences): DspSnapshot {
        val values = try { bounded(stored.asMap()) }
        catch (error: DspStorageException) { return DspSnapshot(null, error.issue) }
        if (values.isEmpty()) return DspSnapshot(DspStored())
        val schema = values[schemaKey.name] as? Int
        if (schema != 1) return if (schema != null && schema > 1) DspSnapshot(null, DspSettingsIssue.NEWER_SCHEMA)
            else invalid(values)
        if (values.keys != keys) return invalid(values)
        val revision = values[revisionKey.name] as? Long ?: return invalid(values)
        val encoded = values[payloadKey.name] as? String ?: return invalid(values)
        if (revision < 0L || encoded.length > ENCODED_CHARACTERS) return invalid(values)
        return try {
            val bytes = Base64.getDecoder().decode(encoded)
            if (bytes.size > RECORD_BYTES) throw DspStorageException(DspSettingsIssue.SETTINGS_TOO_LARGE)
            val input = DataInputStream(ByteArrayInputStream(bytes))
            if (input.readInt() != MAGIC) return invalid(values)
            val enabled = input.strictBoolean()
            val bands = List(10) { input.readInt() }
            val selected = if (input.strictBoolean()) input.framedString(64) else null
            val mode = ReplayGainMode.entries.getOrNull(input.readUnsignedByte()) ?: return invalid(values)
            val settings = DspSettings(
                equalizerEnabled = enabled, bandGainTenthsDb = bands, selectedPresetId = selected,
                replayGainMode = mode, preampTenthsDb = input.readInt(), preventClipping = input.strictBoolean(),
                leftPercent = input.readInt(), rightPercent = input.readInt(),
                limiterEnabled = input.strictBoolean(), limiterThresholdTenthsDb = input.readInt(),
                limiterAttackMicros = input.readInt(), limiterReleaseMillis = input.readInt(),
                limiterLookaheadMillis = input.readInt(),
            )
            val count = input.readInt()
            if (count !in 0..DspLimits.MAX_PRESETS) return invalid(values)
            val presets = List(count) {
                DspPreset(input.framedString(64), input.framedString(DspLimits.MAX_NAME_CHARACTERS),
                    List(10) { input.readInt() })
            }
            if (input.available() != 0) return invalid(values)
            DspSnapshot(DspStored(revision, settings, presets))
        } catch (error: DspStorageException) {
            DspSnapshot(null, error.issue)
        } catch (_: Exception) {
            invalid(values)
        }
    }

    fun encode(value: DspStored): Preferences {
        val saved = value.immutableCopy()
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            val settings = saved.settings
            output.writeInt(MAGIC)
            output.writeBoolean(settings.equalizerEnabled)
            settings.bandGainTenthsDb.forEach(output::writeInt)
            output.writeBoolean(settings.selectedPresetId != null)
            settings.selectedPresetId?.let { output.framedString(it) }
            output.writeByte(settings.replayGainMode.ordinal)
            output.writeInt(settings.preampTenthsDb)
            output.writeBoolean(settings.preventClipping)
            output.writeInt(settings.leftPercent)
            output.writeInt(settings.rightPercent)
            output.writeBoolean(settings.limiterEnabled)
            output.writeInt(settings.limiterThresholdTenthsDb)
            output.writeInt(settings.limiterAttackMicros)
            output.writeInt(settings.limiterReleaseMillis)
            output.writeInt(settings.limiterLookaheadMillis)
            output.writeInt(saved.presets.size)
            for (preset in saved.presets) {
                output.framedString(preset.id)
                output.framedString(preset.name)
                preset.bandGainTenthsDb.forEach(output::writeInt)
            }
        }
        if (bytes.size() > RECORD_BYTES) throw DspStorageException(DspSettingsIssue.SETTINGS_TOO_LARGE)
        return mutablePreferencesOf(schemaKey to 1, revisionKey to saved.revision,
            payloadKey to Base64.getEncoder().encodeToString(bytes.toByteArray()))
    }

    private fun invalid(values: Map<String, Any>) =
        DspSnapshot(null, DspSettingsIssue.INVALID_SETTINGS, recoveryToken(values))

    private fun DataInputStream.strictBoolean(): Boolean = when (readUnsignedByte()) {
        0 -> false
        1 -> true
        else -> throw IllegalArgumentException("Invalid DSP boolean")
    }

    private fun DataInputStream.framedString(maxCharacters: Int): String {
        val size = readInt()
        require(size in 0..maxCharacters * 4 && size <= available())
        val bytes = ByteArray(size)
        readFully(bytes)
        val value = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        require(value.length <= maxCharacters)
        return value
    }

    private fun DataOutputStream.framedString(value: String) {
        val bytes = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value))
        writeInt(bytes.remaining())
        val buffer = ByteArray(bytes.remaining())
        bytes.get(buffer)
        write(buffer)
    }

    /** 排序和摘要前先检查所有损坏字段的边界；超限记录不给恢复 token。 */
    private fun bounded(stored: Map<Preferences.Key<*>, Any>): Map<String, Any> {
        if (stored.size > 16) throw DspStorageException(DspSettingsIssue.SETTINGS_TOO_LARGE)
        val result = LinkedHashMap<String, Any>()
        var units = 0
        fun add(count: Int) {
            if (count < 0 || count > 65_536 - units) throw DspStorageException(DspSettingsIssue.SETTINGS_TOO_LARGE)
            units += count
        }
        fun string(value: String, limit: Int = ENCODED_CHARACTERS) {
            if (value.length > limit) throw DspStorageException(DspSettingsIssue.SETTINGS_TOO_LARGE)
            add(value.length)
        }
        for ((key, value) in stored) {
            string(key.name, 128)
            if (result.put(key.name, value) != null) throw DspStorageException(DspSettingsIssue.UNRECOVERABLE_SETTINGS)
            when (value) {
                is Boolean -> add(1)
                is Int, is Float -> add(4)
                is Long, is Double -> add(8)
                is String -> string(value)
                is ByteArray -> add(value.size)
                is Set<*> -> {
                    if (value.size > 32) throw DspStorageException(DspSettingsIssue.SETTINGS_TOO_LARGE)
                    for (item in value) {
                        if (item !is String) throw DspStorageException(DspSettingsIssue.UNRECOVERABLE_SETTINGS)
                        string(item)
                    }
                }
                else -> throw DspStorageException(DspSettingsIssue.UNRECOVERABLE_SETTINGS)
            }
        }
        return result
    }

    private fun recoveryToken(values: Map<String, Any>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DataOutputStream(DigestOutputStream(DiscardOutput, digest)).use { output ->
            output.writeInt(1)
            output.writeInt(values.size)
            fun text(value: String) {
                output.writeInt(value.length)
                for (character in value) output.writeChar(character.code)
            }
            for ((key, value) in values.entries.sortedBy { it.key }) {
                text(key)
                when (value) {
                    is Boolean -> {
                        output.writeByte(1)
                        output.writeBoolean(value)
                    }
                    is Int -> {
                        output.writeByte(2)
                        output.writeInt(value)
                    }
                    is Long -> {
                        output.writeByte(3)
                        output.writeLong(value)
                    }
                    is Float -> {
                        output.writeByte(4)
                        output.writeInt(value.toRawBits())
                    }
                    is Double -> {
                        output.writeByte(5)
                        output.writeLong(value.toRawBits())
                    }
                    is String -> {
                        output.writeByte(6)
                        text(value)
                    }
                    is Set<*> -> {
                        output.writeByte(7)
                        output.writeInt(value.size)
                        value.map { it as String }.sorted().forEach(::text)
                    }
                    is ByteArray -> {
                        output.writeByte(8)
                        output.writeInt(value.size)
                        output.write(value)
                    }
                    else -> throw DspStorageException(DspSettingsIssue.UNRECOVERABLE_SETTINGS)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private object DiscardOutput : OutputStream() {
        override fun write(value: Int) = Unit
        override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
    }
}
