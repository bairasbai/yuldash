package com.yuldash.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Один процессный подписчик; сеть исчезла — OFF сразу, вернулась — живое подтверждение. */
internal object FeatureConnectivity {
    private var registered = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Synchronized fun init(context: Context) {
        if (registered) return
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                if (FeatureFlags.automaticRefreshEnabled) FeatureFlags.markOffline(context)
            }
            override fun onAvailable(network: Network) {
                if (FeatureFlags.automaticRefreshEnabled) scope.launch { FeatureFlags.refresh(context) }
            }
        }
        registered = runCatching { manager.registerDefaultNetworkCallback(callback); true }.getOrDefault(false)
    }
}
