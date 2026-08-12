package com.easyshare.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.easyshare.app.EasyShareApp
import com.easyshare.app.MainActivity
import com.easyshare.app.R

class ShareForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val sending = intent?.getBooleanExtra(EXTRA_SENDING, true) ?: true
                val progress = intent?.getIntExtra(EXTRA_PROGRESS, 0) ?: 0
                val fileName = intent?.getStringExtra(EXTRA_FILE_NAME)
                startAsForeground(sending, progress, fileName)
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground(sending: Boolean, progressPermille: Int, fileName: String?) {
        val notification = buildNotification(sending, progressPermille, fileName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(sending: Boolean, progressPermille: Int, fileName: String?): Notification {
        val launch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ShareForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = getString(
            if (sending) R.string.share_notification_title_sending
            else R.string.share_notification_title_receiving
        )
        return NotificationCompat.Builder(this, EasyShareApp.TRANSFER_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(fileName ?: "Easy Share transfer")
            .setSmallIcon(R.drawable.ic_stat_share)
            .setContentIntent(launch)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(1000, progressPermille.coerceIn(0, 1000), false)
            .addAction(0, "Cancel", stop)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "com.easyshare.app.STOP_SHARE"
        const val EXTRA_SENDING = "sending"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_FILE_NAME = "file_name"
    }
}
