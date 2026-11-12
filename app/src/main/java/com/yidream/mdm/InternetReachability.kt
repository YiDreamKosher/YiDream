package com.yidream.mdm

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import java.io.Closeable

internal object InternetReachability {
    private data class Route(val network: Network, val validated: Boolean)

    private fun currentRoute(context: Context): Route? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = manager.activeNetwork ?: return null
        val capabilities = manager.getNetworkCapabilities(network) ?: return null
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return null
        return Route(network, capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
    }

    // Android's probe can fail on a filtered connection that still reaches YiDream.
    fun hasInternetRoute(context: Context): Boolean = currentRoute(context) != null

    /** Reports route and validation changes on the main thread. */
    fun observe(context: Context, changed: () -> Unit): Closeable {
        val app = context.applicationContext
        val manager = app.getSystemService(ConnectivityManager::class.java) ?: return Closeable {}
        val main = Handler(Looper.getMainLooper())
        var previous = currentRoute(app)
        var closed = false
        val callback = object : ConnectivityManager.NetworkCallback() {
            private fun update() {
                main.post {
                    if (closed) return@post
                    val route = currentRoute(app)
                    if (route != previous) {
                        previous = route
                        changed()
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
