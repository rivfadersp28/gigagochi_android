package com.gigagochi.app.core.background

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext

internal const val NotificationPermissionPreferences = "gigagochi-notification-permission"
internal const val NotificationPermissionAsked = "asked"

internal fun notificationPermissionWasAsked(context: Context): Boolean =
    context.getSharedPreferences(NotificationPermissionPreferences, 0)
        .getBoolean(NotificationPermissionAsked, false)

internal fun markNotificationPermissionAsked(context: Context) {
    context.getSharedPreferences(NotificationPermissionPreferences, 0)
        .edit()
        .putBoolean(NotificationPermissionAsked, true)
        .apply()
}

@Composable
fun RequestNotificationPermissionOnce(
    enabled: Boolean,
    onPermissionResult: (Boolean) -> Unit = {},
) {
    if (!enabled || Build.VERSION.SDK_INT < 33) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
        onPermissionResult,
    )
    LaunchedEffect(enabled) {
        if (!notificationPermissionWasAsked(context)) {
            markNotificationPermissionAsked(context)
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
