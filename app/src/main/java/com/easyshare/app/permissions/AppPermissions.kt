package com.easyshare.app.permissions

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.easyshare.app.debug.AgentDebugLog

/**
 * Runtime permissions used by Easy Share.
 * File access uses SAF (system picker) — no broad storage permission required on API 26+.
 */
object AppPermissions {
    fun notificationPermissionOrNull(): String? =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null
}

@Composable
fun RequestNotificationPermissionIfNeeded() {
    val context = LocalContext.current
    val permission = AppPermissions.notificationPermissionOrNull() ?: return
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // #region agent log
        AgentDebugLog.log(
            hypothesisId = "H-PERM",
            location = "RequestNotificationPermissionIfNeeded",
            message = "notification permission result",
            data = mapOf("granted" to granted),
            runId = "pair-asymmetry"
        )
        // #endregion
    }
    LaunchedEffect(permission) {
        val granted = ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
        // #region agent log
        AgentDebugLog.log(
            hypothesisId = "H-PERM",
            location = "RequestNotificationPermissionIfNeeded",
            message = "notification permission check",
            data = mapOf("alreadyGranted" to granted),
            runId = "pair-asymmetry"
        )
        // #endregion
        if (!granted) launcher.launch(permission)
    }
}
