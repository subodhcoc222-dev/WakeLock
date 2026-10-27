package com.anekar.wakelock.ui

import android.Manifest
import android.app.TimePickerDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.anekar.wakelock.data.database.WakelockDatabase
import com.anekar.wakelock.data.model.AlarmEntity
import com.anekar.wakelock.data.model.MissionDifficulty
import com.anekar.wakelock.data.WakelockSecurity
import com.anekar.wakelock.receiver.AlarmScheduler
import com.anekar.wakelock.ui.components.PermissionAuditCards
import com.anekar.wakelock.ui.components.checkPermissions
import com.anekar.wakelock.ui.theme.*
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.OptIn

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            WakelockTheme {
                var showSplash by remember { mutableStateOf(true) }

                LaunchedEffect(Unit) {
                    delay(1200)
                    showSplash = false
                }

                if (showSplash) {
                    SplashScreen()
                } else {
                    MainDashboardScreen()
                }
            }
        }
    }
}

@Composable
fun SplashScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PureBlack),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "W A K E L O C K",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                color = TextPrimary,
                letterSpacing = 4.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(
                modifier = Modifier.width(180.dp),
                color = SubtleBorder,
                thickness = 1.dp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "ENGINEERED BY SUBODH ANEKAR",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = LaserGreen,
                letterSpacing = 2.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "v1.0 // TAMPER-PROOF PROTOCOL",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = TextMuted,
                letterSpacing = 1.sp
            )
        }
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainDashboardScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val db = remember { WakelockDatabase.getDatabase(context) }
    val prefs = remember { context.getSharedPreferences("wakelock_prefs", Context.MODE_PRIVATE) }

    var alarms by remember { mutableStateOf<List<AlarmEntity>>(emptyList()) }
    var permissionState by remember { mutableStateOf(checkPermissions(context)) }
    var isSurveillanceActive by remember { mutableStateOf(false) }
    var activeCurrentCheck by remember { mutableIntStateOf(1) }
    var activeTotalChecks by remember { mutableIntStateOf(1) }

    var showConfigDialog by remember { mutableStateOf(false) }
    var editingAlarm by remember { mutableStateOf<AlarmEntity?>(null) }
    var selectedAlarmForPin by remember { mutableStateOf<AlarmEntity?>(null) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun refreshAlarms() {
        CoroutineScope(Dispatchers.IO).launch {
            val list = db.alarmDao().getAllAlarms()
            val activeInPrefs = prefs.getBoolean("is_surveillance_active", false)
            val activeInDb = list.any { it.isSurveillanceActive }
            val isRunning = activeInPrefs || activeInDb
            val curCheck = prefs.getInt("cached_current_check", 1)
            val totChecks = prefs.getInt("cached_total_checks", 1)

            withContext(Dispatchers.Main) {
                alarms = list
                permissionState = checkPermissions(context)
                isSurveillanceActive = isRunning
                activeCurrentCheck = curCheck
                activeTotalChecks = totChecks
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshAlarms()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        containerColor = PureBlack,
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (isSurveillanceActive) {
                        Toast.makeText(
                            context,
                            "SURVEILLANCE ACTIVE: SYSTEM LOCKED // CANNOT ADD PROTOCOLS",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        editingAlarm = null
                        showConfigDialog = true
                    }
                },
                containerColor = if (isSurveillanceActive) DarkSurface else LaserGreen,
                contentColor = if (isSurveillanceActive) TextMuted else PureBlack,
                shape = CircleShape
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Add Alarm Protocol",
                    tint = if (isSurveillanceActive) TextMuted else PureBlack
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "WAKELOCK",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            letterSpacing = 2.sp,
                            color = TextPrimary
                        )
                        Text(
                            text = "TAMPER-PROOF DISCIPLINE",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = TextMuted
                        )
                    }
                    Box(
                        modifier = Modifier
                            .background(DarkSurface, RoundedCornerShape(4.dp))
                            .border(1.dp, if (permissionState.isAllGranted) LaserGreen else ThreatCrimson, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (isSurveillanceActive) "SURVEILLANCE LOCKED" else if (permissionState.isAllGranted) "ARMED" else "UNPROTECTED",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            color = if (isSurveillanceActive) ThreatCrimson else if (permissionState.isAllGranted) LaserGreen else ThreatCrimson
                        )
                    }
                }
            }

            // ACTIVE SURVEILLANCE LOCKDOWN BANNER
            if (isSurveillanceActive) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, ThreatCrimson, RoundedCornerShape(8.dp)),
                        colors = CardDefaults.cardColors(containerColor = ElevatedSurface),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(ThreatCrimson, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "ACTIVE SURVEILLANCE LOCKDOWN",
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = ThreatCrimson,
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    text = "Cycle in progress [Check $activeCurrentCheck of $activeTotalChecks]. Adding, modifying, or deleting protocols is disabled until complete.",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = TextMuted
                                )
                            }
                        }
                    }
                }
            }

            item {
                PermissionAuditCards(state = permissionState, onRefresh = { refreshAlarms() })
            }

            item {
                Text(
                    text = "CONFIGURED PROTOCOLS",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted,
                    letterSpacing = 1.2.sp
                )
            }

            if (alarms.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "NO ACTIVE TARGETS CONFIGURED\nTAP + TO INITIALIZE PROTOCOL",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = TextMuted,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                items(alarms, key = { it.id }) { alarm ->
                    AlarmProtocolCard(
                        alarm = alarm,
                        onCardClick = {
                            if (isSurveillanceActive) {
                                Toast.makeText(
                                    context,
                                    "SURVEILLANCE ACTIVE // CANNOT MODIFY PROTOCOLS",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                selectedAlarmForPin = alarm
                                pendingAction = {
                                    editingAlarm = alarm
                                    showConfigDialog = true
                                }
                            }
                        },
                        onToggle = {
                            if (isSurveillanceActive) {
                                Toast.makeText(
                                    context,
                                    "SURVEILLANCE ACTIVE // CANNOT TOGGLE PROTOCOL",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                selectedAlarmForPin = alarm
                                pendingAction = {
                                    val updatedState = !alarm.isEnabled
                                    CoroutineScope(Dispatchers.IO).launch {
                                        db.alarmDao().updateAlarmState(alarm.id, updatedState)
                                        if (updatedState) {
                                            AlarmScheduler.scheduleAlarm(context, alarm.copy(isEnabled = true))
                                        } else {
                                            AlarmScheduler.cancelAlarm(context, alarm.id)
                                        }
                                        refreshAlarms()
                                    }
                                }
                            }
                        },
                        onDelete = {
                            if (isSurveillanceActive) {
                                Toast.makeText(
                                    context,
                                    "SURVEILLANCE ACTIVE // CANNOT DELETE PROTOCOL",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                selectedAlarmForPin = alarm
                                pendingAction = {
                                    CoroutineScope(Dispatchers.IO).launch {
                                        AlarmScheduler.cancelAlarm(context, alarm.id)
                                        db.alarmDao().deleteAlarm(alarm)
                                        refreshAlarms()
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }

        selectedAlarmForPin?.let { alarm ->
            MasterPinVerificationDialog(
                correctHash = alarm.masterPinHash,
                onDismiss = {
                    selectedAlarmForPin = null
                    pendingAction = null
                },
                onSuccess = {
                    pendingAction?.invoke()
                    selectedAlarmForPin = null
                    pendingAction = null
                }
            )
        }

        if (showConfigDialog) {
            AddOrEditProtocolDialog(
                alarmToEdit = editingAlarm,
                onDismiss = {
                    showConfigDialog = false
                    editingAlarm = null
                },
                onSave = { updatedAlarm ->
                    CoroutineScope(Dispatchers.IO).launch {
                        if (editingAlarm != null) {
                            db.alarmDao().updateAlarm(updatedAlarm)
                            AlarmScheduler.cancelAlarm(context, updatedAlarm.id)
                            AlarmScheduler.scheduleAlarm(context, updatedAlarm)
                        } else {
                            val id = db.alarmDao().insertAlarm(updatedAlarm)
                            val inserted = updatedAlarm.copy(id = id)
                            AlarmScheduler.scheduleAlarm(context, inserted)
                        }
                        refreshAlarms()
                        withContext(Dispatchers.Main) {
                            showConfigDialog = false
                            editingAlarm = null
                        }
                    }
                }
            )
        }
    }
}

@Composable
fun AlarmProtocolCard(
    alarm: AlarmEntity,
    onCardClick: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    val amPm = if (alarm.hour >= 12) "PM" else "AM"
    val displayHour = when {
        alarm.hour == 0 -> 12
        alarm.hour > 12 -> alarm.hour - 12
        else -> alarm.hour
    }
    val timeFormatted = String.format("%02d:%02d", displayHour, alarm.minute)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, SubtleBorder, RoundedCornerShape(8.dp))
            .clickable { onCardClick() },
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = timeFormatted,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 36.sp,
                        color = if (alarm.isEnabled) TextPrimary else TextMuted
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = amPm,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (alarm.isEnabled) LaserGreen else TextMuted,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete Alarm",
                            tint = TextMuted
                        )
                    }
                    Switch(
                        checked = alarm.isEnabled,
                        onCheckedChange = { onToggle() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = LaserGreen,
                            checkedTrackColor = ElevatedSurface,
                            uncheckedThumbColor = TextMuted,
                            uncheckedTrackColor = DarkSurface
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            val taperText = if (alarm.taperIntervalSeconds > 0) {
                val tInt = if (alarm.taperIntervalSeconds < 60) "${alarm.taperIntervalSeconds}s" else "${alarm.taperIntervalSeconds / 60}m"
                val tDur = if (alarm.taperDurationSeconds < 60) "${alarm.taperDurationSeconds}s" else "${alarm.taperDurationSeconds / 60}m"
                "⚡ TAPER ($tDur @ $tInt) ➔ "
            } else {
                ""
            }
            val intervalDisplay = when {
                alarm.wakeUpIntervalSeconds < 60 -> "${alarm.wakeUpIntervalSeconds}s"
                else -> "${alarm.wakeUpIntervalSeconds / 60}m"
            }
            val durationDisplay = when {
                alarm.wakeUpTotalDurationSeconds < 3600 -> "${alarm.wakeUpTotalDurationSeconds / 60}m"
                else -> "${alarm.wakeUpTotalDurationSeconds / 3600}h"
            }

            val qrBadge = if (alarm.qrPayload.isNotBlank()) {
                val countText = if (alarm.qrChecksCount > 0) "QR: 1st ${alarm.qrChecksCount} checks" else "QR: Alarm only"
                " • [$countText]"
            } else {
                ""
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${alarm.missionDifficulty.displayName} [x${alarm.roundsCount}]",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = TextSecondary
                )
                Text(text = "•", color = TextMuted)
                Text(
                    text = "CHECK: $taperText$intervalDisplay / $durationDisplay$qrBadge",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = LaserGreen
                )
            }
        }
    }
}

@Composable
fun AddOrEditProtocolDialog(
    alarmToEdit: AlarmEntity?,
    onDismiss: () -> Unit,
    onSave: (AlarmEntity) -> Unit
) {
    val context = LocalContext.current
    var hour24 by remember { mutableIntStateOf(alarmToEdit?.hour ?: 5) }
    var minute by remember { mutableIntStateOf(alarmToEdit?.minute ?: 0) }
    val selectedDays = remember {
        mutableStateListOf<Int>().apply {
            addAll(alarmToEdit?.daysOfWeek ?: listOf(1, 2, 3, 4, 5, 6, 7))
        }
    }
    var difficulty by remember { mutableStateOf(alarmToEdit?.missionDifficulty ?: MissionDifficulty.TWO_DIGIT) }
    var rounds by remember { mutableIntStateOf(alarmToEdit?.roundsCount ?: 5) }
    
    // Taper Phase Settings
    var taperIntervalSec by remember { mutableIntStateOf(alarmToEdit?.taperIntervalSeconds ?: 300) }
    var taperDurationSec by remember { mutableIntStateOf(alarmToEdit?.taperDurationSeconds ?: 1800) }
    
    // Regular Phase Settings
    var wakeIntervalSec by remember { mutableIntStateOf(alarmToEdit?.wakeUpIntervalSeconds ?: 600) }
    var wakeDurationSec by remember { mutableIntStateOf(alarmToEdit?.wakeUpTotalDurationSeconds ?: 7200) }
    
    // QR Physical Anchor Settings
    var qrPayload by remember { mutableStateOf(alarmToEdit?.qrPayload ?: "") }
    var qrChecksCount by remember { mutableIntStateOf(alarmToEdit?.qrChecksCount ?: 3) }
    var showQrRegistrationScanner by remember { mutableStateOf(false) }

    var masterPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }

    val displayHour = when {
        hour24 == 0 -> 12
        hour24 > 12 -> hour24 - 12
        else -> hour24
    }
    val amPm = if (hour24 >= 12) "PM" else "AM"

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        title = {
            Text(
                text = if (alarmToEdit != null) "EDIT PROTOCOL" else "CONFIGURE PROTOCOL",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = TextPrimary
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. Time Selector
                item {
                    Text("TARGET TIME (TAP TO CHANGE)", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextMuted)
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(ElevatedSurface, RoundedCornerShape(8.dp))
                            .border(1.dp, SubtleBorder, RoundedCornerShape(8.dp))
                            .clickable {
                                TimePickerDialog(
                                    context,
                                    { _, selectedH, selectedM ->
                                        hour24 = selectedH
                                        minute = selectedM
                                    },
                                    hour24,
                                    minute,
                                    false
                                ).show()
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = String.format("%02d:%02d", displayHour, minute),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Box(
                                modifier = Modifier
                                    .background(PureBlack, RoundedCornerShape(4.dp))
                                    .border(1.dp, LaserGreen, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = amPm,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = LaserGreen
                                )
                            }
                        }
                    }
                }

                // 2. Day Selector
                item {
                    Text("REPEAT PROTOCOL DAYS", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextMuted)
                    Spacer(modifier = Modifier.height(6.dp))
                    val dayNames = listOf("M", "T", "W", "T", "F", "S", "S")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        dayNames.forEachIndexed { idx, name ->
                            val dayNum = idx + 1
                            val isSelected = selectedDays.contains(dayNum)
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(
                                        if (isSelected) LaserGreen else ElevatedSurface,
                                        CircleShape
                                    )
                                    .border(1.dp, if (isSelected) LaserGreen else SubtleBorder, CircleShape)
                                    .clickable {
                                        if (isSelected) selectedDays.remove(dayNum) else selectedDays.add(dayNum)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = name,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = if (isSelected) PureBlack else TextSecondary
                                )
                            }
                        }
                    }
                }

                // 3. PHYSICAL DESK QR ANCHOR REGISTRATION
                item {
                    Text(
                        text = "PHYSICAL DESK QR ANCHOR",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (qrPayload.isNotBlank()) LaserGreen else TextMuted
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    if (qrPayload.isBlank()) {
                        Button(
                            onClick = { showQrRegistrationScanner = true },
                            colors = ButtonDefaults.buttonColors(containerColor = ElevatedSurface),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, SubtleBorder, RoundedCornerShape(8.dp))
                        ) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = LaserGreen)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("SCAN & REGISTER DESK QR", color = LaserGreen, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(ElevatedSurface, RoundedCornerShape(8.dp))
                                .border(1.dp, LaserGreen, RoundedCornerShape(8.dp))
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "TARGET ANCHOR LINKED ✓",
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        color = LaserGreen
                                    )
                                    Text(
                                        text = if (qrPayload.length > 25) qrPayload.take(22) + "..." else qrPayload,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        color = TextSecondary
                                    )
                                }
                                Row {
                                    TextButton(onClick = { showQrRegistrationScanner = true }) {
                                        Text("RE-SCAN", color = LaserGreen, fontSize = 11.sp)
                                    }
                                    IconButton(onClick = { qrPayload = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear QR", tint = ThreatCrimson)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "MANDATORY QR SCAN CHECKS",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = TextMuted
                            )
                            Spacer(modifier = Modifier.height(6.dp))

                            val qrOptions = listOf(
                                0 to "0 (Alarm Only)",
                                1 to "1 Check",
                                2 to "2 Checks",
                                3 to "3 Checks (Rec)",
                                4 to "4 Checks",
                                5 to "5 Checks",
                                6 to "6 Checks"
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                qrOptions.forEach { (count, label) ->
                                    FilterChip(
                                        selected = qrChecksCount == count,
                                        onClick = { qrChecksCount = count },
                                        label = { Text(label, fontSize = 10.sp, fontFamily = FontFamily.Monospace) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = LaserGreen,
                                            selectedLabelColor = PureBlack
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                // 4. Math Rounds UI
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("MATH ROUNDS (PROBLEMS TO SOLVE)", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextMuted)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { if (rounds > 1) rounds-- },
                                colors = ButtonDefaults.buttonColors(containerColor = ElevatedSurface),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier
                                    .size(48.dp)
                                    .border(1.dp, SubtleBorder, RoundedCornerShape(6.dp)),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("-", fontSize = 22.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
                            }

                            Text(
                                text = String.format("%02d", rounds),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = LaserGreen,
                                modifier = Modifier.padding(horizontal = 28.dp)
                            )

                            Button(
                                onClick = { if (rounds < 50) rounds++ },
                                colors = ButtonDefaults.buttonColors(containerColor = ElevatedSurface),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier
                                    .size(48.dp)
                                    .border(1.dp, SubtleBorder, RoundedCornerShape(6.dp)),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("+", fontSize = 22.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // 5. Mission Difficulty
                item {
                    Text("MISSION DIFFICULTY (6-CHAIN)", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextMuted)
                    Spacer(modifier = Modifier.height(4.dp))
                    MissionDifficulty.entries.forEach { diff ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { difficulty = diff }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = difficulty == diff,
                                onClick = { difficulty = diff },
                                colors = RadioButtonDefaults.colors(selectedColor = LaserGreen)
                            )
                            Text(diff.displayName, fontSize = 13.sp, color = TextPrimary)
                        }
                    }
                }

                // 6. TAPER CHECK INTERVAL (PHASE 1)
                item {
                    Text(
                        text = "PHASE 1: TAPER CHECK INTERVAL",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (taperIntervalSec > 0) LaserGreen else TextMuted
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    val taperIntervals = listOf(
                        0 to "OFF",
                        30 to "30s (Test)",
                        60 to "1m (Test)",
                        120 to "2m (Test)",
                        300 to "5m (Prod)",
                        600 to "10m"
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        taperIntervals.forEach { (sec, label) ->
                            FilterChip(
                                selected = taperIntervalSec == sec,
                                onClick = { taperIntervalSec = sec },
                                label = { Text(label, fontSize = 11.sp, fontFamily = FontFamily.Monospace) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = LaserGreen,
                                    selectedLabelColor = PureBlack
                                )
                            )
                        }
                    }
                }

                // 7. TAPER PHASE DURATION
                if (taperIntervalSec > 0) {
                    item {
                        Text(
                            text = "PHASE 1: TAPER DURATION",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = LaserGreen
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        val taperDurations = listOf(
                            60 to "1m (Test)",
                            120 to "2m (Test)",
                            300 to "5m (Test)",
                            900 to "15m",
                            1800 to "30m (Prod)"
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                            taperDurations.forEach { (sec, label) ->
                                FilterChip(
                                    selected = taperDurationSec == sec,
                                    onClick = { taperDurationSec = sec },
                                    label = { Text(label, fontSize = 11.sp, fontFamily = FontFamily.Monospace) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = LaserGreen,
                                        selectedLabelColor = PureBlack
                                    )
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        val checksInPhase1 = if (taperIntervalSec > 0) taperDurationSec / taperIntervalSec else 0
                        Text(
                            text = "⚡ Phase 1 executes $checksInPhase1 checks (every ${if (taperIntervalSec < 60) "${taperIntervalSec}s" else "${taperIntervalSec/60}m"}) before shifting.",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = LaserGreen
                        )
                    }
                }

                // 8. REGULAR CHECK INTERVAL (PHASE 2)
                item {
                    Text(
                        text = if (taperIntervalSec > 0) "PHASE 2: REGULAR INTERVAL (AFTER TAPER)" else "REGULAR CHECK INTERVAL",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    val intervals = listOf(
                        30 to "30s (Test)",
                        60 to "1m (Test)",
                        120 to "2m (Test)",
                        300 to "5m",
                        600 to "10m",
                        900 to "15m",
                        1200 to "20m"
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        intervals.forEach { (sec, label) ->
                            FilterChip(
                                selected = wakeIntervalSec == sec,
                                onClick = { wakeIntervalSec = sec },
                                label = { Text(label, fontSize = 11.sp, fontFamily = FontFamily.Monospace) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = LaserGreen,
                                    selectedLabelColor = PureBlack
                                )
                            )
                        }
                    }
                }

                // 9. SURVEILLANCE DURATION (TOTAL)
                item {
                    Text("TOTAL SURVEILLANCE DURATION", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextMuted)
                    Spacer(modifier = Modifier.height(6.dp))
                    val durations = listOf(
                        60 to "1m (Test)",
                        120 to "2m (Test)",
                        300 to "5m (Test)",
                        3600 to "1 Hour",
                        7200 to "2 Hours",
                        10800 to "3 Hours",
                        14400 to "4 Hours",
                        18000 to "5 Hours"
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        durations.forEach { (sec, label) ->
                            FilterChip(
                                selected = wakeDurationSec == sec,
                                onClick = { wakeDurationSec = sec },
                                label = { Text(label, fontSize = 11.sp, fontFamily = FontFamily.Monospace) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = LaserGreen,
                                    selectedLabelColor = PureBlack
                                )
                            )
                        }
                    }
                }

                // 10. Master PIN Setup
                item {
                    Text(
                        if (alarmToEdit != null) "UPDATE MASTER PIN (OR LEAVE BLANK TO KEEP)" else "SET MASTER PIN (4-8 DIGITS)",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = masterPin,
                        onValueChange = { if (it.length <= 8) masterPin = it },
                        label = { Text("Master PIN") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = LaserGreen,
                            unfocusedBorderColor = SubtleBorder
                        )
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = confirmPin,
                        onValueChange = { if (it.length <= 8) confirmPin = it },
                        label = { Text("Confirm PIN") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = LaserGreen,
                            unfocusedBorderColor = SubtleBorder
                        )
                    )
                    if (errorMessage.isNotEmpty()) {
                        Text(
                            text = errorMessage,
                            color = ThreatCrimson,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (selectedDays.isEmpty()) {
                        errorMessage = "Please select at least 1 repeat day"
                    } else if (alarmToEdit == null && masterPin.length < 4) {
                        errorMessage = "PIN must be at least 4 digits"
                    } else if (masterPin.isNotEmpty() && masterPin != confirmPin) {
                        errorMessage = "PIN confirmation does not match"
                    } else {
                        val pinHash = if (masterPin.isNotEmpty()) {
                            WakelockSecurity.hashPin(masterPin)
                        } else {
                            alarmToEdit?.masterPinHash ?: ""
                        }

                        val updatedAlarm = AlarmEntity(
                            id = alarmToEdit?.id ?: 0L,
                            hour = hour24,
                            minute = minute,
                            daysOfWeek = selectedDays.toList(),
                            isEnabled = alarmToEdit?.isEnabled ?: true,
                            missionDifficulty = difficulty,
                            roundsCount = rounds,
                            timeoutSeconds = difficulty.defaultTimeoutSec,
                            taperIntervalSeconds = taperIntervalSec,
                            taperDurationSeconds = taperDurationSec,
                            wakeUpIntervalSeconds = wakeIntervalSec,
                            wakeUpTotalDurationSeconds = wakeDurationSec,
                            masterPinHash = pinHash,
                            qrPayload = qrPayload,
                            qrChecksCount = qrChecksCount
                        )
                        onSave(updatedAlarm)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = LaserGreen)
            ) {
                Text(
                    if (alarmToEdit != null) "UPDATE PROTOCOL" else "ARM PROTOCOL",
                    color = PureBlack,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = TextSecondary)
            }
        }
    )

    if (showQrRegistrationScanner) {
        DeskQrRegistrationDialog(
            onDismiss = { showQrRegistrationScanner = false },
            onScanned = { payload ->
                qrPayload = payload
                showQrRegistrationScanner = false
            }
        )
    }
}

@Composable
fun DeskQrRegistrationDialog(
    onDismiss: () -> Unit,
    onScanned: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp),
            colors = CardDefaults.cardColors(containerColor = PureBlack),
            shape = RoundedCornerShape(12.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (hasCameraPermission) {
                    AndroidView(
                        factory = { ctx ->
                            val previewView = PreviewView(ctx)
                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                            cameraProviderFuture.addListener({
                                val cameraProvider = cameraProviderFuture.get()
                                val preview = Preview.Builder().build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }

                                val options = BarcodeScannerOptions.Builder()
                                    .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                                    .build()
                                val scanner = BarcodeScanning.getClient(options)

                                val analysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()

                                analysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                                    processRegistrationImageProxy(scanner, imageProxy, onScanned)
                                }

                                try {
                                    cameraProvider.unbindAll()
                                    cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        CameraSelector.DEFAULT_BACK_CAMERA,
                                        preview,
                                        analysis
                                    )
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }, ContextCompat.getMainExecutor(ctx))
                            previewView
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Reticle Box
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(200.dp)
                                .border(2.dp, LaserGreen, RoundedCornerShape(12.dp))
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "POINT CAMERA AT DESK QR",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = LaserGreen
                        )
                        Button(
                            onClick = onDismiss,
                            colors = ButtonDefaults.buttonColors(containerColor = ElevatedSurface)
                        ) {
                            Text("CANCEL", color = TextPrimary, fontSize = 11.sp)
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(20.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("CAMERA ACCESS REQUIRED", color = ThreatCrimson, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                            colors = ButtonDefaults.buttonColors(containerColor = LaserGreen)
                        ) {
                            Text("GRANT PERMISSION", color = PureBlack)
                        }
                    }
                }
            }
        }
    }
}

private fun processRegistrationImageProxy(
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    imageProxy: ImageProxy,
    onScanned: (String) -> Unit
) {
    @androidx.camera.core.ExperimentalGetImage
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    val raw = barcode.rawValue
                    if (!raw.isNullOrBlank()) {
                        onScanned(raw)
                        break
                    }
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    } else {
        imageProxy.close()
    }
}
