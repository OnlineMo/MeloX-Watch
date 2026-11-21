package io.github.onlinemo.meloxwatch.companion

import android.app.Application
import android.net.ConnectivityManager
import io.github.onlinemo.meloxwatch.companion.data.CompanionDocuments
import io.github.onlinemo.meloxwatch.companion.data.CompanionLocalStore
import io.github.onlinemo.meloxwatch.companion.transfer.CompanionTlsIdentity
import io.github.onlinemo.meloxwatch.companion.transfer.CompanionTransferClient
import io.github.onlinemo.meloxwatch.companion.transfer.TlsCompanionWireFactory
import java.io.File

/** Wiring alone creates no socket, identity, provider read or implicit connection. */
class MeloXCompanionApplication : Application() {
    internal val documents: CompanionDocuments by lazy {
        CompanionDocuments(contentResolver, CompanionLocalStore(File(noBackupFilesDir, "companion-v1")))
    }
    internal val transfer: CompanionTransferClient by lazy {
        val factory = TlsCompanionWireFactory(checkNotNull(getSystemService(ConnectivityManager::class.java)),
            identity = { CompanionTlsIdentity.load(documents) })
        CompanionTransferClient(factory)
    }
}
