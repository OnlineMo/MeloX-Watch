package io.github.onlinemo.meloxwatch.ui.lyrics

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

internal class LyricsAnchorScrollToken
internal data class LyricsScrollObservation(val scrolling: Boolean, val automatic: Boolean)

/**
 * 页面拥有浏览位置；这里不保存媒体时间、播放状态或延时任务。
 * 调用层可在分页外持有此状态，隐藏/返回时保留浏览；换队列实例或歌词内容得到新状态。
 */
@Stable
class LyricsPageState internal constructor(
    val identity: LyricsPageIdentity?,
    val documentId: String?,
    val listState: LazyListState,
    private val following: MutableState<Boolean>,
) {
    private var anchorScroll: LyricsAnchorScrollToken? by mutableStateOf(null)
    val followsCurrent: Boolean get() = following.value
    internal val automaticScrollInProgress: Boolean get() = anchorScroll != null

    fun beginBrowsing() { following.value = false }
    fun returnToCurrent() { following.value = true }

    internal fun belongsTo(currentIdentity: LyricsPageIdentity?, currentDocumentId: String?): Boolean =
        identity == currentIdentity && documentId == currentDocumentId

    internal fun beginAnchorScroll(): LyricsAnchorScrollToken {
        val token = LyricsAnchorScrollToken()
        anchorScroll = token
        return token
    }

    internal fun endAnchorScroll(token: LyricsAnchorScrollToken) {
        // 旧效果取消后的 finally 不能清掉新定位的标记；列表本身仍由其 MutatorMutex 串行处理。
        if (anchorScroll === token) anchorScroll = null
    }
}

/** 仅恢复本页显示状态；重建不重放播放预览、确认或音频命令。 */
@Composable
fun rememberLyricsPageState(identity: LyricsPageIdentity?, documentId: String?): LyricsPageState =
    key(identity, documentId) {
        val list = rememberLazyListState()
        val following = rememberSaveable { mutableStateOf(true) }
        remember(identity, documentId, list, following) {
            LyricsPageState(identity, documentId, list, following)
        }
    }
