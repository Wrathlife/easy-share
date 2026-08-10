package com.easyshare.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class EasyShareApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createTransferChannel()
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
