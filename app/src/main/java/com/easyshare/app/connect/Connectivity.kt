package com.easyshare.app.connect

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
    val lanFingerprint: String?
)

class ConnectivityDiagnoser {
    // Wired to ConnectivityManager in adaptive-connect phase.
    fun snapshot(): ConnectivitySnapshot = ConnectivitySnapshot(
        vpnActive = false,
        onWifi = true,
        onCellular = false,
        hasIpv6 = false,
        lanFingerprint = null
    )
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
