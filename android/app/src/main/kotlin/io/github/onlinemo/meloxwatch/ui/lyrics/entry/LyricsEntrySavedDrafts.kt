package io.github.onlinemo.meloxwatch.ui.lyrics.entry

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.onlinemo.meloxwatch.data.local.LocalInputLimits
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsUserOffset
import java.security.MessageDigest

/** Main and recreation tests bind only the draft holder to the Activity registry. */
internal fun lyricsEntrySavedStateFactory(): ViewModelProvider.Factory =
    viewModelFactory { initializer { LyricsEntrySavedStateHolder(createSavedStateHandle()) } }

/** Activity registry ownership remains separate from the application-owned lyrics business model. */
internal class LyricsEntrySavedStateHolder(val handle: SavedStateHandle) : ViewModel()

/** A proposal, never read permission or commit authority. All identities are fixed-size local digests. */
internal data class LyricsSavedOffsetDraft(
    val value: Int,
    val queueRevision: Long,
    val preferencesRevision: Long,
    val queueFingerprint: String,
    val audioFingerprint: String,
    val readFingerprint: String,
) {
    fun sameTarget(other: LyricsSavedOffsetDraft): Boolean =
        queueFingerprint == other.queueFingerprint && audioFingerprint == other.audioFingerprint
}

internal class LyricsEntrySavedDrafts(private val state: SavedStateHandle) {
    fun readOffset(): LyricsSavedOffsetDraft? {
        val fields = state.get<Any>(KEY_OFFSET) as? List<*> ?: return null
        if (fields.size != 7 || fields.any { it !is String || it.length > 64 }) return null
        val values = fields.map { it as String }
        if (values[0] != "1") return null
        val offset = values[1].toIntOrNull()?.takeIf { it in -LyricsUserOffset.MAX_DELAY_MS..LyricsUserOffset.MAX_DELAY_MS }
            ?: return null
        val queueRevision = values[2].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val preferencesRevision = values[3].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        if (values.subList(4, 7).any { !it.matches(DIGEST) }) return null
        return LyricsSavedOffsetDraft(offset, queueRevision, preferencesRevision, values[4], values[5], values[6])
    }

    fun writeOffset(draft: LyricsSavedOffsetDraft) {
        // Assign a new list at every edit. Clearing a later owner must not mutate a Registry snapshot.
        state[KEY_OFFSET] = arrayListOf("1", draft.value.toString(), draft.queueRevision.toString(),
            draft.preferencesRevision.toString(), draft.queueFingerprint, draft.audioFingerprint, draft.readFingerprint)
    }

    fun updateOffset(value: Int) { readOffset()?.let { writeOffset(it.copy(value = value)) } }
    fun clearOffset() { state.remove<Any>(KEY_OFFSET) }

    companion object {
        internal const val KEY_OFFSET = "lyrics.entry.offset-draft.v1"
        private val DIGEST = Regex("[0-9a-f]{64}")
    }
}

/** Length-prefix each bounded field; neither raw provider metadata nor a URI enters SavedState. */
internal fun lyricsDraftFingerprint(vararg values: String?): String {
    require(values.size <= 24 && values.all { it == null || it.length <= LocalInputLimits.URI_CHARACTERS })
    val digest = MessageDigest.getInstance("SHA-256")
    for (value in values) {
        val bytes = value?.toByteArray(Charsets.UTF_8)
        val size = bytes?.size ?: -1
        for (shift in intArrayOf(24, 16, 8, 0)) digest.update((size ushr shift).toByte())
        if (bytes != null) digest.update(bytes)
    }
    val bytes = digest.digest()
    val alphabet = "0123456789abcdef"
    return String(CharArray(bytes.size * 2) { index ->
        val value = bytes[index / 2].toInt() and 0xff
        alphabet[if (index % 2 == 0) value ushr 4 else value and 0xf]
    })
}
