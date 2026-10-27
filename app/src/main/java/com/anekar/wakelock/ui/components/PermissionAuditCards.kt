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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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

enum class OemBrand {
    XIAOMI,
    OPPO_REALME_ONEPLUS,
    VIVO_IQOO,
    HUAWEI_HONOR,
    SAMSUNG,
    GENERIC
}

data class PermissionState(
    val hasOverlay: Boolean,
    val hasExactAlarm: Boolean,
    val hasBatteryOpt: Boolean,
    val hasDeviceAdmin: Boolean,
    val hasNotification: Boolean,
    val hasAccessibility: Boolean,
    val hasCamera: Boolean,
    val isAutoStartAcknowledged: Boolean,
    val isOemClearanceAcknowledged: Boolean,
    val detectedOem: OemBrand
) {
    val isAllGranted: Boolean
        get() = hasOverlay && 
                hasExactAlarm && 
                hasBatteryOpt && 
                hasDeviceAdmin && 
                hasNotification && 
                hasAccessibility && 
                hasCamera && 
                isAutoStartAcknowledged &&
                (detectedOem == OemBrand.GENERIC || detectedOem == OemBrand.SAMSUNG || isOemClearanceAcknowledged)
}

fun detectOem(): OemBrand {
    val manufacturer = Build.MANUFACTURER.lowercase()
    val brand = Build.BRAND.lowercase()

    return when {
        manufacturer.contains("xiaomi") || brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") -> OemBrand.XIAOMI
        manufacturer.contains("oppo") || brand.contains("oppo") || manufacturer.contains("realme") || brand.contains("realme") || manufacturer.contains("oneplus") || brand.contains("oneplus") -> OemBrand.OPPO_REALME_ONEPLUS
        manufacturer.contains("vivo") || brand.contains("vivo") || brand.contains("iqoo") -> OemBrand.VIVO_IQOO
        manufacturer.contains("huawei") || brand.contains("huawei") || brand.contains("honor") -> OemBrand.HUAWEI_HONOR
        manufacturer.contains("samsung") || brand.contains("samsung") -> OemBrand.SAMSUNG
        else -> OemBrand.GENERIC
    }
}

fun checkPermissions(context: Context): PermissionState {
    val prefs = context.getSharedPreferences("wakelock_prefs", Context.MODE_PRIVATE)
    val isAutoStartAck = prefs.getBoolean("autostart_ack", false)
    val isOemAck = prefs.getBoolean("oem_clearance_ack", false)
    val oem = detectOem()

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

    val hasCamera = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    val expectedServiceName = "${context.packageName}/${AntiForceStopService::class.java.canonicalName}"
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: ""
    val hasAccessibility = enabledServices.contains(expectedServiceName)

    return PermissionState(
        hasOverlay = hasOverlay,
        hasExactAlarm = hasExactAlarm,
        hasBatteryOpt = hasBatteryOpt,
        hasDeviceAdmin = hasDeviceAdmin,
        hasNotification = hasNotification,
        hasAccessibility = hasAccessibility,
        hasCamera = hasCamera,
        isAutoStartAcknowledged = isAutoStartAck,
        isOemClearanceAcknowledged = isOemAck,
        detectedOem = oem
    )
}

@Composable
fun PermissionAuditCards(state: PermissionState, onRefresh: () -> Unit) {
    val context = LocalContext.current

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        onRefresh()
    }

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

        // 2. OEM Special Clearance Card
        if (state.detectedOem != OemBrand.GENERIC && state.detectedOem != OemBrand.SAMSUNG) {
            AnimatedVisibility(visible = !state.isOemClearanceAcknowledged, exit = fadeOut() + shrinkVertically()) {
                val (oemTitle, oemDesc) = when (state.detectedOem) {
                    OemBrand.XIAOMI -> Pair(
                        "XIAOMI / HYPEROS LOCK SCREEN CLEARANCE",
                        "MIUI/HyperOS requires: 1. Show on Lock screen, 2. Display pop-up windows while in background."
                    )
                    OemBrand.OPPO_REALME_ONEPLUS -> Pair(
                        "COLOROS / OXYGENOS BACKGROUND LAUNCH CLEARANCE",
                        "Oppo/Realme/OnePlus requires: 1. Allow background activity, 2. Allow auto-launch & floating windows."
                    )
                    OemBrand.VIVO_IQOO -> Pair(
                        "FUNTOUCH / ORIGIN OS AUTOLAUNCH CLEARANCE",
                        "Vivo/iQOO requires: 1. Autostart in iManager, 2. High background power consumption allow."
                    )
                    OemBrand.HUAWEI_HONOR -> Pair(
                        "EMUI / MAGICOS AUTO-LAUNCH CLEARANCE",
                        "Huawei/Honor requires: Set App Launch to 'Manage Manually' (Auto-launch & Secondary launch ON)."
                    )
                    else -> Pair("", "")
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, ThreatCrimson, RoundedCornerShape(8.dp)),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = oemTitle,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = ThreatCrimson
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = oemDesc,
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    launchOemSettings(context, state.detectedOem)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = ElevatedSurface),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.weight(1f).border(1.dp, SubtleBorder, RoundedCornerShape(6.dp))
                            ) {
                                Text("1. OPEN OEM PERMS", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = TextPrimary)
                            }

                            Button(
                                onClick = {
                                    val prefs = context.getSharedPreferences("wakelock_prefs", Context.MODE_PRIVATE)
                                    prefs.edit().putBoolean("oem_clearance_ack", true).apply()
                                    onRefresh()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = LaserGreen),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("2. I HAVE ALLOWED", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = PureBlack, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // 3. Battery Whitelist Card
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

        // 4. Device Admin Card
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

        // 5. Accessibility Service Card
        AnimatedVisibility(visible = !state.hasAccessibility, exit = fadeOut() + shrinkVertically()) {
            AuditCard(
                title = "ACCESSIBILITY (ANTI-FORCE STOP)",
                description = "Settings me jakar app ko forcefully band karne se rokta hai.",
                actionText = "ENABLE IN ACCESSIBILITY"
            ) {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                context.startActivity(intent)
            }
        }

        // 6. Camera Hardware Card
        AnimatedVisibility(visible = !state.hasCamera, exit = fadeOut() + shrinkVertically()) {
            AuditCard(
                title = "CAMERA (DESK QR SCANNER)",
                description = "Desk QR anchor scan karne ke liye camera access anivarya hai.",
                actionText = "GRANT CAMERA ACCESS"
            ) {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        // 7. Notification Card
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

        // 8. OEM Auto-Start Card
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
                        text = "Step 1: Settings me jakar Auto-start / Background activity ON karein.\nStep 2: ON karne ke baad confirm karein.",
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
                            modifier = Modifier.weight(1f).border(1.dp, SubtleBorder, RoundedCornerShape(6.dp))
                        ) {
                            Text("1. OPEN SETTINGS", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextPrimary)
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
                            Text("2. I HAVE ENABLED", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = PureBlack)
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

private fun launchOemSettings(context: Context, oem: OemBrand) {
    try {
        when (oem) {
            OemBrand.XIAOMI -> {
                val intent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                    setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                    putExtra("extra_pkgname", context.packageName)
                }
                context.startActivity(intent)
            }
            OemBrand.OPPO_REALME_ONEPLUS -> {
                val intent = Intent().apply {
                    component = ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
                }
                context.startActivity(intent)
            }
            OemBrand.VIVO_IQOO -> {
                val intent = Intent().apply {
                    component = ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
                }
                context.startActivity(intent)
            }
            OemBrand.HUAWEI_HONOR -> {
                val intent = Intent().apply {
                    component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
                }
                context.startActivity(intent)
            }
            else -> {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            }
        }
    } catch (e: Exception) {
        val fallbackIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        context.startActivity(fallbackIntent)
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
            Text(text = description, fontSize = 12.sp, color = TextSecondary)
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = ElevatedSurface),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth().border(1.dp, SubtleBorder, RoundedCornerShape(6.dp))
            ) {
                Text(text = actionText, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            }
        }
    }
}
