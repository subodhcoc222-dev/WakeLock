package com.anekar.wakelock.ui.components

import android.app.AlarmManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anekar.wakelock.receiver.WakelockAdminReceiver
import com.anekar.wakelock.ui.theme.*

data class PermissionState(
    val hasOverlay: Boolean,
    val hasExactAlarm: Boolean,
    val hasBatteryOpt: Boolean,
    val hasDeviceAdmin: Boolean
) {
    val isAllGranted: Boolean
        get() = hasOverlay && hasExactAlarm && hasBatteryOpt && hasDeviceAdmin
}

fun checkPermissions(context: Context): PermissionState {
    val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Settings.canDrawOverlays(context)
    } else true

    val hasExactAlarm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.canScheduleExactAlarms()
    } else true

    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val hasBatteryOpt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        powerManager.isIgnoringBatteryOptimizations(context.packageName)
    } else true

    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val adminComponent = ComponentName(context, WakelockAdminReceiver::class.java)
    val hasDeviceAdmin = dpm.isAdminActive(adminComponent)

    return PermissionState(hasOverlay, hasExactAlarm, hasBatteryOpt, hasDeviceAdmin)
}

@Composable
fun PermissionAuditCards(state: PermissionState, onRefresh: () -> Unit) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "SYSTEM CLEARANCE STATUS",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = TextMuted,
            letterSpacing = 1.2.sp
        )

        // Card 1: Display Over Other Apps
        AnimatedVisibility(visible = !state.hasOverlay, exit = fadeOut() + shrinkVertically()) {
            AuditCard(
                title = "OVERLAY PERMISSION",
                description = "Required to display Math Mission over lockscreen and running apps.",
                actionText = "GRANT OVERLAY"
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    context.startActivity(intent)
                }
            }
        }

        // Card 2: Schedule Exact Alarm
        AnimatedVisibility(visible = !state.hasExactAlarm, exit = fadeOut() + shrinkVertically()) {
            AuditCard(
                title = "EXACT ALARM ACCESS",
                description = "Required to fire 30s testing and morning targets with millisecond precision.",
                actionText = "GRANT EXACT ALARM"
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    context.startActivity(intent)
                }
            }
        }

        // Card 3: Battery Optimization Whitelist
        AnimatedVisibility(visible = !state.hasBatteryOpt, exit = fadeOut() + shrinkVertically()) {
            AuditCard(
                title = "BATTERY WHITELIST",
                description = "Prevents OS background daemon killers from terminating wake-up checks.",
                actionText = "DISABLE RESTRICTIONS"
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                }
            }
        }

        // Card 4: Device Administrator
        AnimatedVisibility(visible = !state.hasDeviceAdmin, exit = fadeOut() + shrinkVertically()) {
            AuditCard(
                title = "ANTI-UNINSTALL GUARD",
                description = "Enforces tamper resistance and prevents uninstallation during surveillance.",
                actionText = "ACTIVATE ADMIN"
            ) {
                val adminComponent = ComponentName(context, WakelockAdminReceiver::class.java)
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "WAKELOCK tamper protection prevents uninstallation during active monitoring."
                    )
                }
                context.startActivity(intent)
            }
        }

        // Final Clearance State Badge
        AnimatedVisibility(visible = state.isAllGranted) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ElevatedSurface, RoundedCornerShape(8.dp))
                    .border(1.dp, LaserGreen, RoundedCornerShape(8.dp))
                    .padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "✓ ALL PERMISSIONS GRANTED // SYSTEM FULLY ARMED",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = LaserGreen
                    )
                }
            }
        }
    }
}

@Composable
private fun AuditCard(
    title: String,
    description: String,
    actionText: String,
    onAction: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, SubtleBorder, RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = title,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = ThreatCrimson
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                fontSize = 12.sp,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = ElevatedSurface),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SubtleBorder, RoundedCornerShape(6.dp))
            ) {
                Text(
                    text = actionText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }
        }
    }
}
