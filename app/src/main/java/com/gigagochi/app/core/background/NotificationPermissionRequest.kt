package com.gigagochi.app.core.background

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext

private const val PermissionPreferences = "gigagochi-notification-permission"
private const val PermissionAsked = "asked"

@Composable
fun RequestNotificationPermissionOnce(enabled: Boolean) {
    if (!enabled || Build.VERSION.SDK_INT < 33) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(enabled) {
        val preferences = context.getSharedPreferences(PermissionPreferences, 0)
        if (!preferences.getBoolean(PermissionAsked, false)) {
            preferences.edit().putBoolean(PermissionAsked, true).apply()
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
