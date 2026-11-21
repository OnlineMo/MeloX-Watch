package io.github.onlinemo.meloxwatch.data.lyrics.active

import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsLoadRequest
import java.util.concurrent.atomic.AtomicReference

/**
 * The app has one acquisition owner and one physical provider channel. Identity comparison also
 * rejects a retired request whose fields happen to equal a later request after UI recreation.
 * The loader can call this fence from its parsing dispatcher without reading Compose/UI state.
 */
class LyricsLoadAuthority {
    private class Acquisition(val primary: LyricsLoadRequest, val translation: LyricsLoadRequest? = null)
    private val current = AtomicReference<Acquisition?>()

    fun install(request: LyricsLoadRequest) {
        current.set(Acquisition(request))
    }

    /** Translation is acquired serially by the same owner, with the same audio/source fences. */
    fun attachTranslation(primary: LyricsLoadRequest, translation: LyricsLoadRequest): Boolean {
        require(translation.binding.copy(documentIdentity = primary.binding.documentIdentity) == primary.binding)
        val owner = current.get() ?: return false
        return owner.primary === primary && owner.translation == null &&
            current.compareAndSet(owner, Acquisition(primary, translation))
    }

    fun isCurrent(request: LyricsLoadRequest): Boolean {
        val owner = current.get() ?: return false
        return owner.primary === request || owner.translation === request
    }

    fun retire(request: LyricsLoadRequest) {
        while (true) {
            val owner = current.get() ?: return
            val replacement = when {
                owner.primary === request -> null
                owner.translation === request -> Acquisition(owner.primary)
                else -> return
            }
            if (current.compareAndSet(owner, replacement)) return
        }
    }
}
