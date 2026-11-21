package io.github.onlinemo.meloxwatch.data.fonts

import io.github.onlinemo.meloxwatch.data.themes.ThemeBitmapOwner
import io.github.onlinemo.meloxwatch.data.themes.ThemeException
import io.github.onlinemo.meloxwatch.data.themes.ThemeIssue
import java.io.File

/** The existing decoder's two native rasters and two pixel arrays share the image owner. */
internal class BudgetedLyricsFontDecoder(
    private val bitmaps: ThemeBitmapOwner,
    private val decoder: LyricsFontDecoder = AndroidLyricsFontDecoder(),
) : LyricsFontDecoder {
    override fun validate(file: File, info: SfntFontInfo, checkCancellation: () -> Unit): FontWeightCapability {
        checkCancellation()
        val reservation = try { bitmaps.reserve(PROBE_BYTES) }
        catch (error: ThemeException) {
            throw LyricsFontException(if (error.issue == ThemeIssue.BUSY) LyricsFontIssue.BUSY else LyricsFontIssue.LOW_MEMORY, error)
        }
        try { return decoder.validate(file, info, checkCancellation) }
        finally { reservation.close() }
    }

    override fun decode(file: File, info: SfntFontInfo, capability: FontWeightCapability, requested: Float?): DecodedLyricsFont =
        decoder.decode(file, info, capability, requested)

    companion object { const val PROBE_BYTES = 4L * 512 * 96 * 4 }
}
