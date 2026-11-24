package io.github.onlinemo.meloxwatch.data.themes

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicBoolean

/** 调用方持有到不再显示为止；无内部图片缓存，关闭即释放 bitmap 与有界临时显示名额。 */
class ThemeImageLease internal constructor(
    val bitmap: Bitmap,
    /** PNG 图标按 alpha 作遮罩；控件使用此已验证前景色，不能照搬包内任意像素颜色。 */
    val tintColor: Int?,
    private val release: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            bitmap.recycle()
        } finally {
            release()
        }
    }
}
