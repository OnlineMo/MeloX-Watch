package io.github.onlinemo.meloxwatch.data.online

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A visible online page owns this callback. Connectivity never authorizes playback or automatic catalog refresh. */
internal class OnlineNetworkMonitor(context: Context) : AutoCloseable {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val mutableOnline = MutableStateFlow(false)
    val online = mutableOnline.asStateFlow()
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()
        override fun onLost(network: Network) = refresh()
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = refresh()
    }
    @Volatile private var registered = false
    fun start() {
        if (registered) return
        try {
            manager?.registerDefaultNetworkCallback(callback)
            registered = manager != null
            refresh()
        } catch (_: SecurityException) { mutableOnline.value = false }
    }
    private fun refresh() {
        if (!registered) return
        val connectivity = manager ?: run {
            mutableOnline.value = false
            return
        }
        val capabilities = try { connectivity.getNetworkCapabilities(connectivity.activeNetwork) }
            catch (_: SecurityException) { null }
        val available = capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (registered) mutableOnline.value = available
    }
    override fun close() {
        if (registered) {
            registered = false
            try { manager?.unregisterNetworkCallback(callback) }
            catch (_: IllegalArgumentException) { }
            catch (_: SecurityException) { }
        }
        mutableOnline.value = false
    }
}
