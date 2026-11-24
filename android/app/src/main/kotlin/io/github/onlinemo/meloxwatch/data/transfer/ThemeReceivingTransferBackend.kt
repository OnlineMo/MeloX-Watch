package io.github.onlinemo.meloxwatch.data.transfer

import io.github.onlinemo.meloxwatch.data.themes.ThemeException
import io.github.onlinemo.meloxwatch.data.themes.TransferredThemeCoordinator
import io.github.onlinemo.meloxwatch.data.themes.transferThemeFailure

/** 路由保留音频导入所有者；主题上传只创建待本机确认的候选。 */
class ThemeReceivingTransferBackend(private val audio: TransferBackend, private val themes: TransferredThemeCoordinator) : TransferBackend by audio {
    override suspend fun committed(binding: TransferOperationBinding, permit: TransferPermit): TransferReceipt? =
        if (binding.kind == TransferOperationKind.THEME) translate { themes.committed(binding, permit) } else audio.committed(binding, permit)

    override suspend fun commitUpload(binding: TransferOperationBinding, upload: TransferVerifiedUpload, permit: TransferPermit): TransferReceipt =
        if (binding.kind == TransferOperationKind.THEME) translate { themes.receive(binding, upload, permit) } else audio.commitUpload(binding, upload, permit)

    private suspend fun <T> translate(action: suspend () -> T): T = try { action() }
    catch (error: ThemeException) { throw transferThemeFailure(error) }
}
