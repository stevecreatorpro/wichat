package com.wichat.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Tracks whether any network currently reaches the internet, so the router and outbox can react. */
class NetworkMonitor(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val online = mutableSetOf<Network>()

    private val _hasInternet = MutableStateFlow(currentlyOnline())
    val hasInternet: StateFlow<Boolean> = _hasInternet.asStateFlow()

    init {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivity.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) add(network) else remove(network)
            }

            override fun onLost(network: Network) = update { remove(network) }
        })
    }

    private fun update(change: MutableSet<Network>.() -> Unit) {
        synchronized(online) {
            online.change()
            _hasInternet.value = online.isNotEmpty()
        }
    }

    private fun currentlyOnline(): Boolean {
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
