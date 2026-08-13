package com.netshare.app.connect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPolicyTest {
    private fun snap(
        wifi: Boolean = false,
        cellular: Boolean = false,
        internet: Boolean = true,
        activeWifi: Boolean = false,
        activeCellular: Boolean = false
    ) = ConnectivitySnapshot(
        vpnActive = false,
        onWifi = wifi,
        onCellular = cellular,
        hasIpv6 = false,
        hasInternet = internet,
        lanFingerprint = if (wifi || activeWifi) "wifi" else null,
        activeWifi = activeWifi,
        activeCellular = activeCellular,
        activeValidated = internet
    )

    @Test
    fun wifiAlwaysAllowed() {
        assertTrue(canUseCurrentNetwork(snap(wifi = true, activeWifi = true), useMobileData = false))
    }

    @Test
    fun cellularOnlyBlockedWhenToggleOff() {
        assertFalse(canUseCurrentNetwork(snap(cellular = true, activeCellular = true), useMobileData = false))
    }

    @Test
    fun cellularOnlyAllowedWhenToggleOn() {
        assertTrue(canUseCurrentNetwork(snap(cellular = true, activeCellular = true), useMobileData = true))
    }

    @Test
    fun activeCellularBlockedEvenIfWifiInterfacePresent() {
        // Stale/unvalidated Wi‑Fi interface must not bypass the mobile-data guard.
        assertFalse(
            canUseCurrentNetwork(
                snap(wifi = true, cellular = true, activeWifi = false, activeCellular = true),
                useMobileData = false
            )
        )
    }

    @Test
    fun activeWifiAllowedWhenCellularAlsoPresent() {
        assertTrue(
            canUseCurrentNetwork(
                snap(wifi = true, cellular = true, activeWifi = true, activeCellular = false),
                useMobileData = false
            )
        )
    }
}
