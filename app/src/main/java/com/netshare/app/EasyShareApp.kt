package com.netshare.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.netshare.app.ads.InterstitialAds
import com.netshare.app.billing.NoAdsBilling
import com.netshare.app.webrtc.WebRtcPeerSession
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import kotlin.concurrent.thread

class EasyShareApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createTransferChannel()
        runCatching { WebRtcPeerSession.ensureFactory(this) }
        NoAdsBilling.start(this)
        InterstitialAds.initialize(this)
        if (BuildConfig.DEBUG) {
            thread(name = "gaid-log", isDaemon = true) {
                runCatching {
                    val info = AdvertisingIdClient.getAdvertisingIdInfo(this)
                    Log.i("UnityGaid", "Advertising ID=${info.id} limitAdTracking=${info.isLimitAdTrackingEnabled}")
                }.onFailure {
                    Log.w("UnityGaid", "Could not read Advertising ID: ${it.message}")
                }
            }
        }
    }

    private fun createTransferChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            TRANSFER_CHANNEL_ID,
            getString(R.string.share_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val TRANSFER_CHANNEL_ID = "easyshare_transfers"
    }
}
