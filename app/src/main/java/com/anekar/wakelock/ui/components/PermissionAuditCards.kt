package com.anekar.wakelock.ui.components

import android.Manifest
import android.app.AlarmManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.anekar.wakelock.receiver.WakelockAdminReceiver
import com.anekar.wakelock.service.AntiForceStopService
import com.anekar.wakelock.ui.theme.*

data class PermissionState(
    val hasOverlay: Boolean,
    val hasExactAlarm: Boolean,
    val hasBatteryOpt: Boolean,
    val hasDeviceAdmin: Boolean,
    val hasNotification: Boolean,
    val hasAccessibility: Boolean,
    val isAutoStartAcknowledged: Boolean
) {
    val isAllGranted: Boolean
        get() = hasOverlay && hasExactAlarm && hasBatteryOpt && hasDeviceAdmin && hasNotification && hasAccessibility && isAutoStartAcknowledged
}

fun checkPermissions(context: Context): PermissionState {
    val prefs = context.getSharedPreferences("wakelock_prefs", Context.MODE_PRIVATE)
    val isAutoStartAck = prefs.getBoolean("autostart_ack", false)

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

    val hasNotification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else true

    val expectedServiceName = "${context.packageName}/${AntiForceStopService::class.java.canonicalName}"
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: ""
    val hasAccessibility = enabledServices.contains(expectedServiceName)

    return PermissionState(
        hasOverlay,
        hasExactAlarm,
        hasBatteryOpt,
        hasDeviceAdmin,
        hasNotification,
        hasAccessibility,
        isAutoStartAck
    )
}

@Composable
fun PermissionAuditCards(state: PermissionState, onRefresh: () -> Unit) {
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxWidth(),
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

        // 1. Overlay Card
        AnimatedVisibility(visible = !state.hasOverlay, exit = fadeOut() + shrinkVertically()) {
            AuditCard(
                title = "OVERLAY PERMISSION",
                description = "Math Mission ko lockscreen ke upar forcefully show karne ke liye required hai.",
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

        // 2. Battery Whitelist Card
        AnimatedVisibility(visible = !state.hasBatteryOpt, exit = fadeOut() + shrinkVertically()) {
            AuditCard(
                title = "BATTERY RESTRICTION REMOVAL",
                description = "Background wake-up check timers ko OS sleep mode me na daal sake.",
                actionText = "SET TO UNRESTRICTED"
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                }
            }
        }

        // 3. Device Admin Card
        AnimatedVisibility(visible = !state.hasDeviceAdmin, exit = fadeOut() + shrinkVertically()) {
            AuditCard(
                title = "ANTI-UNINSTALL GUARD",
                description = "Surveillance ke dauran app uninstall hone se rokta hai.",
                actionText = "ACTIVATE ADMIN"
            ) {
                val adminComponent = ComponentName(context, WakelockAdminReceiver::class.java)
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "WAKELOCK tamper protection restricts uninstallation during active monitoring."
                    )
                }
                context.startActivity(intent)
            }
        }

        // 4. Accessibility Service Card
        AnimatedVisibility(visible = !state.hasAccessibility, exit = fadeOut() + shrinkVertically()) {
            AuditCard(
                title = "ACCESSIBILITY (ANTI-FORCE STOP)",
                description = "Settings me jakar app ko forcefully band (Force Stop) karne se rokta hai.",
                actionText = "ENABLE IN ACCESSIBILITY"
            ) {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                context.startActivity(intent)
            }
        }

        // 5. Notifications Card
        AnimatedVisibility(visible = !state.hasNotification, exit = fadeOut() + shrinkVertically()) {
            AuditCard(
                title = "NOTIFICATION PERMISSION",
                description = "Alarm head-up display aur sirens ke liye notification on karna zaroori hai.",
                actionText = "ALLOW NOTIFICATIONS"
            ) {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
                context.startActivity(intent)
            }
        }

        // 6. OEM Auto-Start & Background Activity Card (2-Step Explicit Confirmation)
        AnimatedVisibility(visible = !state.isAutoStartAcknowledged, exit = fadeOut() + shrinkVertically()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SubtleBorder, RoundedCornerShape(8.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "AUTO-LAUNCH / AUTO-START [CRITICAL]",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = ThreatCrimson
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Step 1: Settings me jakar Auto-start / Background activity ON karein.\nStep 2: ON karne ke baad neeche confirm karein.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ElevatedSurface),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .border(1.dp, SubtleBorder, RoundedCornerShape(6.dp))
                        ) {
                            Text(
                                text = "1. OPEN SETTINGS",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }

                        Button(
                            onClick = {
                                val prefs = context.getSharedPreferences("wakelock_prefs", Context.MODE_PRIVATE)
                                prefs.edit().putBoolean("autostart_ack", true).apply()
                                onRefresh()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = LaserGreen),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "2. I HAVE ENABLED",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = PureBlack
                            )
                        }
                    }
                }
            }
        }

        // All Clear Banner
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
                        fontSize = 11.sp,
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
