package com.example.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

object RetryBackoffPolicy {
    const val MAX_SEGMENT_RETRIES = 6
    const val STALL_WATCHDOG_MS = 14_000L

    private val BACKOFF_STEPS_MS = longArrayOf(
        1_200L,
        2_400L,
        4_000L,
        6_000L,
        8_000L
    )

    fun computeRetryDelayMs(attempt: Int): Long {
        if (attempt <= 0) return BACKOFF_STEPS_MS[0]
        val index = (attempt - 1).coerceIn(0, BACKOFF_STEPS_MS.lastIndex)
        return BACKOFF_STEPS_MS[index]
    }
}

object NetworkConnectivityObserver {
    fun isCurrentlyConnected(context: Context): Boolean {
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val active = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(active) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(true)
    }

    fun observeNetworkAvailable(context: Context): Flow<Boolean> = callbackFlow {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            trySend(true)
            close()
            return@callbackFlow
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(true)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    trySend(true)
                }
            }

            override fun onLost(network: Network) {
                trySend(isCurrentlyConnected(context))
            }
        }

        val registered = runCatching {
            cm.registerDefaultNetworkCallback(callback)
            true
        }.getOrDefault(false)

        if (!registered) {
            trySend(true)
        }

        awaitClose {
            if (registered) {
                runCatching { cm.unregisterNetworkCallback(callback) }
            }
        }
    }.distinctUntilChanged()
}
