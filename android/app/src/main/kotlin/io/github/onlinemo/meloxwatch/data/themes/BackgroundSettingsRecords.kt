package io.github.onlinemo.meloxwatch.data.themes

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Small checksummed records contain settings and managed-copy identities, never authority. */
internal object BackgroundSettingsRecords {
    private const val MAGIC = 0x4d424731
    private const val VERSION = 1
    private const val DIGEST_BYTES = 32

    fun encode(settings: PlaybackBackgroundSettings): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            output.writeLong(settings.revision)
            val options = settings.options
            output.writeUTF(options.mode.name)
            output.writeInt(options.blurLevel)
            output.writeInt(options.darkMaskPercent)
            output.writeFloat(options.crop.zoom)
            output.writeFloat(options.crop.horizontal)
            output.writeFloat(options.crop.vertical)
            output.writeBoolean(options.customImage != null)
            options.customImage?.let { image ->
                output.writeUTF(image.id)
                output.writeUTF(image.sha256)
                output.writeLong(image.byteCount)
                output.writeUTF(image.mediaType)
                output.writeInt(image.width)
                output.writeInt(image.height)
                output.writeInt(image.orientation)
            }
        }
        val payload = bytes.toByteArray()
        check(payload.size + DIGEST_BYTES <= PlaybackBackgroundLimits.SETTINGS_BYTES)
        return payload + MessageDigest.getInstance("SHA-256").digest(payload)
    }

    fun decode(bytes: ByteArray): PlaybackBackgroundSettings {
        if (bytes.size !in DIGEST_BYTES + 16..PlaybackBackgroundLimits.SETTINGS_BYTES) {
            backgroundFail(PlaybackBackgroundIssue.RECOVERY_REQUIRED)
        }
        val payload = bytes.copyOfRange(0, bytes.size - DIGEST_BYTES)
        val expected = bytes.copyOfRange(bytes.size - DIGEST_BYTES, bytes.size)
        if (!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(payload), expected)) {
            backgroundFail(PlaybackBackgroundIssue.RECOVERY_REQUIRED)
        }
        try {
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) backgroundFail(PlaybackBackgroundIssue.RECOVERY_REQUIRED)
                val revision = input.readLong()
                val mode = ThemeBackgroundMode.valueOf(boundedString(input, 32))
                val blur = input.readInt()
                val mask = input.readInt()
                val crop = BackgroundCrop(input.readFloat(), input.readFloat(), input.readFloat())
                val present = input.readUnsignedByte()
                if (present !in 0..1) backgroundFail(PlaybackBackgroundIssue.RECOVERY_REQUIRED)
                val image = if (present == 1) BackgroundImageRef(
                    boundedString(input, 36), boundedString(input, 64), input.readLong(), boundedString(input, 32),
                    input.readInt(), input.readInt(), input.readInt(),
                ) else null
                if (input.read() != -1) backgroundFail(PlaybackBackgroundIssue.RECOVERY_REQUIRED)
                return PlaybackBackgroundSettings(revision, PlaybackBackgroundOptions(mode, blur, mask, crop, image))
            }
        } catch (error: PlaybackBackgroundException) { throw error }
        catch (error: Exception) { throw PlaybackBackgroundException(PlaybackBackgroundIssue.RECOVERY_REQUIRED, error) }
    }

    private fun boundedString(input: DataInputStream, limit: Int): String {
        val value = input.readUTF()
        if (value.length > limit || value.any(Char::isISOControl)) backgroundFail(PlaybackBackgroundIssue.RECOVERY_REQUIRED)
        return value
    }
}
