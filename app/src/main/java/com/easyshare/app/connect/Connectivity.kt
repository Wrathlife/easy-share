package com.easyshare.app.connect

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

enum class ConnectStrategy {
    LanDirect,
    WanDefault,
    Ipv6Prefer,
    TcpIce,
    IceRestart,
    VpnAwareRetry
}

data class ConnectivitySnapshot(
    val vpnActive: Boolean,
    val onWifi: Boolean,
    val onCellular: Boolean,
    val hasIpv6: Boolean,
    val hasInternet: Boolean,
    val lanFingerprint: String?,
    /** True when the system active network is Wi‑Fi. */
    val activeWifi: Boolean = false,
    /** True when the system active network is cellular. */
    val activeCellular: Boolean = false,
    val activeValidated: Boolean = false
) {
    /**
     * True when transfers would use mobile data:
     * prefer the active network; fall back to interface presence.
     */
    val cellularOnly: Boolean
        get() = when {
            activeWifi -> false
            activeCellular -> true
            else -> onCellular && !onWifi
        }
}

class ConnectivityDiagnoser(context: Context) {
    private val appContext = context.applicationContext
    private val connectivity =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun snapshot(): ConnectivitySnapshot {
        val networks = connectivity.allNetworks
        var onWifi = false
        var onCellular = false
        var vpnActive = false
        var hasValidated = false
        var hasIpv6 = false
        var wifiCount = 0
        var cellularCount = 0

        for (network in networks) {
            val caps = connectivity.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) vpnActive = true
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                onWifi = true
                wifiCount++
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                onCellular = true
                cellularCount++
            }
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                hasValidated = true
            }
            if (hasGlobalIpv6(network)) hasIpv6 = true
        }

        var activeWifi = false
        var activeCellular = false
        var activeValidated = false
        connectivity.activeNetwork?.let { active ->
            val caps = connectivity.getNetworkCapabilities(active)
            activeWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            activeCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            activeValidated =
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        }

        val result = ConnectivitySnapshot(
            vpnActive = vpnActive,
            onWifi = onWifi,
            onCellular = onCellular,
            hasIpv6 = hasIpv6,
            hasInternet = hasValidated || activeValidated,
            lanFingerprint = if (activeWifi || (onWifi && !activeCellular)) "wifi" else null,
            activeWifi = activeWifi,
            activeCellular = activeCellular,
            activeValidated = activeValidated
        )

        // #region agent log
        com.easyshare.app.debug.AgentDebugLog.log(
            hypothesisId = "B1",
            location = "ConnectivityDiagnoser.snapshot",
            message = "connectivity classification",
            data = mapOf(
                "wifiCount" to wifiCount,
                "cellularCount" to cellularCount,
                "onWifi" to result.onWifi,
                "onCellular" to result.onCellular,
                "cellularOnly" to result.cellularOnly,
                "activeWifi" to activeWifi,
                "activeCellular" to activeCellular,
                "activeValidated" to activeValidated,
                "hasInternet" to result.hasInternet
            ),
            runId = "fix-review2"
        )
        // #endregion

        return result
    }

    fun observe(): Flow<ConnectivitySnapshot> = callbackFlow {
        trySend(snapshot())
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(snapshot())
            }

            override fun onLost(network: Network) {
                trySend(snapshot())
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                trySend(snapshot())
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivity.registerNetworkCallback(request, callback)
        awaitClose {
            runCatching { connectivity.unregisterNetworkCallback(callback) }
        }
    }.distinctUntilChanged()

    private fun hasGlobalIpv6(network: Network?): Boolean {
        if (network == null) return false
        val link = connectivity.getLinkProperties(network) ?: return false
        return link.linkAddresses.any { addr ->
            val inet = addr.address
            !inet.isLoopbackAddress && !inet.isLinkLocalAddress && inet.hostAddress?.contains(":") == true
        }
    }
}

class StrategyLadder {
    fun first(snapshot: ConnectivitySnapshot): ConnectStrategy =
        if (snapshot.lanFingerprint != null) ConnectStrategy.LanDirect
        else ConnectStrategy.WanDefault

    fun next(current: ConnectStrategy, snapshot: ConnectivitySnapshot): ConnectStrategy? {
        val order = buildList {
            if (snapshot.lanFingerprint != null) add(ConnectStrategy.LanDirect)
            add(ConnectStrategy.WanDefault)
            if (snapshot.hasIpv6) add(ConnectStrategy.Ipv6Prefer)
            add(ConnectStrategy.TcpIce)
            add(ConnectStrategy.IceRestart)
            if (snapshot.vpnActive) add(ConnectStrategy.VpnAwareRetry)
        }
        val idx = order.indexOf(current)
        return order.getOrNull(idx + 1)
    }
}

sealed interface FailureKind {
    data object VpnSuspected : FailureKind
    data object NoStun : FailureKind
    data object IceFailed : FailureKind
    data object Exhausted : FailureKind
}

class FailureClassifier {
    fun classify(
        vpnActive: Boolean,
        gotSrflx: Boolean,
        strategiesLeft: Boolean
    ): FailureKind = when {
        vpnActive -> FailureKind.VpnSuspected
        !gotSrflx -> FailureKind.NoStun
        !strategiesLeft -> FailureKind.Exhausted
        else -> FailureKind.IceFailed
    }
}

/** Whether a transfer is allowed given prefs + live network. */
fun canUseCurrentNetwork(snapshot: ConnectivitySnapshot, useMobileData: Boolean): Boolean {
    if (!snapshot.hasInternet && !snapshot.onWifi && !snapshot.onCellular) return false
    if (snapshot.cellularOnly && !useMobileData) return false
    return true
}
