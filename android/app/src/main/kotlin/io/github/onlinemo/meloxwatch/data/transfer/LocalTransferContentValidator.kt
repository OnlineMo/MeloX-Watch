package io.github.onlinemo.meloxwatch.data.transfer

import android.os.CancellationSignal
import io.github.onlinemo.meloxwatch.data.themes.ThemeException
import io.github.onlinemo.meloxwatch.data.themes.ThemeManifestReader
import io.github.onlinemo.meloxwatch.data.themes.ThemeTransferQuota
import io.github.onlinemo.meloxwatch.data.themes.ThemeZipArchive
import io.github.onlinemo.meloxwatch.data.themes.transferThemeFailure
import java.io.File

/** 传输层识别真实包结构；收到主题后的资源/字体解码仍由 LP-12 文件所有者完整验证。 */
class LocalTransferContentValidator(
    private val audio: TransferContentValidator,
    private val themes: ThemeTransferQuota,
) : TransferContentValidator {
    override fun validate(file: File, declaration: TransferUploadDeclaration, signal: CancellationSignal) {
        if (declaration.kind == TransferUploadKind.AUDIO) {
            audio.validate(file, declaration, signal)
            return
        }
        try {
            signal.throwIfCanceled()
            val archive = ThemeZipArchive(file)
            val index = archive.inspect(signal::throwIfCanceled)
            val manifest = ThemeManifestReader.read(archive.readManifest(index, signal::throwIfCanceled), signal::throwIfCanceled)
            archive.validateDeclarations(index, manifest)
            themes.classifyOnPhysicalWorker(file, manifest)
            signal.throwIfCanceled()
        } catch (error: ThemeException) {
            throw transferThemeFailure(error)
        }
    }
}
