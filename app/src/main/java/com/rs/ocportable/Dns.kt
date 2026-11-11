package com.rs.ocportable

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities

/**
 * DNS for the guest.
 *
 * musl reads `/etc/resolv.conf`; Android's own resolver asks netd. The servers netd is using are
 * reachable by construction, so we copy `LinkProperties.dnsServers` into the rootfs and refresh on
 * every network change. This matters mid-conversation when Wi-Fi hands over to cellular.
 */
object Dns {
    fun servers(context: Context): List<String> {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val network = manager.activeNetwork ?: return emptyList()
        val properties = manager.getLinkProperties(network) ?: return emptyList()
        return properties.dnsServers.mapNotNull { it.hostAddress }.filter { it.isNotBlank() }
    }

    fun register(context: Context, onChange: (List<String>) -> Unit): ConnectivityManager.NetworkCallback? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onChange(servers(context))
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                val addresses = linkProperties.dnsServers.mapNotNull { it.hostAddress }.filter { it.isNotBlank() }
                if (addresses.isNotEmpty()) onChange(addresses)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                // DNS is refreshed by onLinkPropertiesChanged; this callback intentionally does
                // nothing so a capabilities-only change does not rewrite the file.
            }
        }
        return try {
            manager.registerDefaultNetworkCallback(callback)
            callback
        } catch (_: RuntimeException) {
            null
        }
    }

    fun unregister(context: Context, callback: ConnectivityManager.NetworkCallback?) {
        if (callback == null) return
        try {
            context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        } catch (_: RuntimeException) {
            // Already unregistered, or the process is going away.
        }
    }
}
