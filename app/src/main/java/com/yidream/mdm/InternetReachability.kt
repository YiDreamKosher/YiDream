package com.yidream.mdm

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import java.io.Closeable

internal object InternetReachability {
    fun isOnline(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Reports transitions on the main thread. Each observer belongs to a foreground activity. */
    fun observe(context: Context, changed: (Boolean) -> Unit): Closeable {
        val app = context.applicationContext
        val manager = app.getSystemService(ConnectivityManager::class.java) ?: return Closeable {}
        val main = Handler(Looper.getMainLooper())
        var previous = isOnline(app)
        var closed = false
        val callback = object : ConnectivityManager.NetworkCallback() {
            private fun update() {
                main.post {
                    if (closed) return@post
                    val online = isOnline(app)
                    if (online != previous) {
                        previous = online
                        changed(online)
                    }
                }
            }
            override fun onAvailable(network: Network) = update()
            override fun onLost(network: Network) = update()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = update()
        }
        return try {
            manager.registerDefaultNetworkCallback(callback)
            Closeable {
                closed = true
                runCatching { manager.unregisterNetworkCallback(callback) }
            }
        } catch (_: SecurityException) {
            Closeable {}
        }
    }
}
