package com.netshare.app.permissions

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Runtime permissions used by Easy Share.
 * File access uses SAF (system picker) — no broad storage permission required on API 26+.
 */
object AppPermissions {
    fun notificationPermissionOrNull(): String? =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null
}

/**
 * Optional notification permission for transfer status.
 * Do **not** auto-prompt on screen entry — that overlays Share/Receive and makes
 * Add files / Add folder look broken.
 */
@Composable
fun rememberNotificationPermissionRequester(): () -> Unit {
    val context = LocalContext.current
    val permission = AppPermissions.notificationPermissionOrNull()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* optional; transfer works without it */ }
    return remember(permission, launcher) {
        {
            if (permission != null) {
                val granted = ContextCompat.checkSelfPermission(context, permission) ==
                    PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    runCatching { launcher.launch(permission) }
                }
            }
        }
    }
}

/** @deprecated Use [rememberNotificationPermissionRequester] at transfer start instead. */
@Composable
fun RequestNotificationPermissionIfNeeded() {
    // Intentionally no-op: kept so call sites compile until migrated.
}
