package io.github.onlinemo.meloxwatch.playback.resources

import android.content.Context
import io.github.onlinemo.meloxwatch.data.cue.AndroidCueOwnedInput
import io.github.onlinemo.meloxwatch.data.cue.CueOwnedInput
import io.github.onlinemo.meloxwatch.model.CueFileBinding

/** 分配不执行IO；DataSource先注册此对象，initialize再打开FD。 */
internal fun interface CuePcmInputFactory {
    fun create(binding: CueFileBinding): CueSeekableInput
}

internal typealias CueSeekableInput = CueOwnedInput

internal class AndroidCuePcmInputFactory(context: Context) : CuePcmInputFactory {
    private val application = context.applicationContext
    override fun create(binding: CueFileBinding): CueSeekableInput = AndroidCueOwnedInput(application, binding)
}
