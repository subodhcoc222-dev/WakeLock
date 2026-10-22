package com.anekar.wakelock.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anekar.wakelock.data.database.WakelockDatabase
import com.anekar.wakelock.data.model.AlarmEntity
import com.anekar.wakelock.data.model.MissionDifficulty
import com.anekar.wakelock.data.WakelockSecurity
import com.anekar.wakelock.receiver.AlarmScheduler
import com.anekar.wakelock.ui.components.PermissionAuditCards
import com.anekar.wakelock.ui.components.checkPermissions
import com.anekar.wakelock.ui.theme.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainDashboardScreen() {
    val context = LocalContext.current
    val db = remember { WakelockDatabase.getDatabase(context) }
    var alarms by remember { mutableStateOf<List<AlarmEntity>>(emptyList()) }
    var permissionState by remember { mutableStateOf(checkPermissions(context)) }
    var showAddDialog by remember { mutableStateOf(false) }
    var selectedAlarmForPin by remember { mutableStateOf<AlarmEntity?>(null) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun refreshAlarms() {
        CoroutineScope(Dispatchers.IO).launch {
            val list = db.alarmDao().getAllAlarms()
            withContext(Dispatchers.Main) {
                alarms = list
                permissionState = checkPermissions(context)
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshAlarms()
    }

    Scaffold(
        containerColor = PureBlack,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = LaserGreen,
                contentColor = PureBlack,
                shape = CircleShape
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Alarm Protocol")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
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
                        text = if (permissionState.isAllGranted) "ARMED" else "UNPROTECTED",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        color = if (permissionState.isAllGranted) LaserGreen else ThreatCrimson
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            PermissionAuditCards(state = permissionState, onRefresh = { refreshAlarms() })

            Spacer(modifier = Modifier.height(10.dp))

            if (alarms.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
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
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(alarms, key = { it.id }) { alarm ->
                        AlarmProtocolCard(
                            alarm = alarm,
                            onToggle = {
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
                            },
                            onDelete = {
                                selectedAlarmForPin = alarm
                                pendingAction = {
                                    CoroutineScope(Dispatchers.IO).launch {
                                        AlarmScheduler.cancelAlarm(context, alarm.id)
                                        db.alarmDao().deleteAlarm(alarm)
                                        refreshAlarms()
                                    }
                                }
                            }
                        )
                    }
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

        if (showAddDialog) {
            AddProtocolDialog(
                onDismiss = { showAddDialog = false },
                onSave = { newAlarm ->
                    CoroutineScope(Dispatchers.IO).launch {
                        val id = db.alarmDao().insertAlarm(newAlarm)
                        val inserted = newAlarm.copy(id = id)
                        AlarmScheduler.scheduleAlarm(context, inserted)
                        refreshAlarms()
                        withContext(Dispatchers.Main) {
                            showAddDialog = false
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
            .border(1.dp, SubtleBorder, RoundedCornerShape(8.dp)),
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

            Spacer(modifier = Modifier.height(8.dp))

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
                    text = "CHECK: ${alarm.wakeUpIntervalSeconds}s / ${alarm.wakeUpTotalDurationSeconds / 60}m",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = LaserGreen
                )
            }
        }
    }
}

@Composable
fun AddProtocolDialog(
    onDismiss: () -> Unit,
    onSave: (AlarmEntity) -> Unit
) {
    var hour by remember { mutableIntStateOf(5) }
    var minute by remember { mutableIntStateOf(0) }
    var isAm by remember { mutableStateOf(true) }
    var difficulty by remember { mutableStateOf(MissionDifficulty.SEVEN_DIGIT) }
    var rounds by remember { mutableIntStateOf(2) }
    var wakeIntervalSec by remember { mutableIntStateOf(600) }
    var wakeDurationSec by remember { mutableIntStateOf(7200) }
    var masterPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        title = {
            Text(
                text = "CONFIGURE PROTOCOL",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = TextPrimary
            )
        },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("TARGET TIME", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextMuted)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = String.format("%02d:%02d", hour, minute),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = { isAm = !isAm },
                                colors = ButtonDefaults.buttonColors(containerColor = ElevatedSurface),
                                shape = RoundedCornerShape(4.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(if (isAm) "AM" else "PM", fontFamily = FontFamily.Monospace, color = LaserGreen)
                            }
                        }
                    }
                }

                item {
                    Text("MATH MISSION DIFFICULTY", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextMuted)
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

                item {
                    Text("WAKE-UP CHECK INTERVAL", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextMuted)
                    Spacer(modifier = Modifier.height(4.dp))
                    val intervals = listOf(30 to "30s (Test)", 60 to "1m (Test)", 300 to "5m", 600 to "10m")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        intervals.forEach { (sec, label) ->
                            FilterChip(
                                selected = wakeIntervalSec == sec,
                                onClick = { wakeIntervalSec = sec },
                                label = { Text(label, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = LaserGreen,
                                    selectedLabelColor = PureBlack
                                )
                            )
                        }
                    }
                }

                item {
                    Text("TOTAL SURVEILLANCE DURATION", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextMuted)
                    Spacer(modifier = Modifier.height(4.dp))
                    val durations = listOf(60 to "1m (Test)", 120 to "2m (Test)", 3600 to "1h", 7200 to "2h")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        durations.forEach { (sec, label) ->
                            FilterChip(
                                selected = wakeDurationSec == sec,
                                onClick = { wakeDurationSec = sec },
                                label = { Text(label, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = LaserGreen,
                                    selectedLabelColor = PureBlack
                                )
                            )
                        }
                    }
                }

                item {
                    Text("SET MASTER PIN (4-8 DIGITS)", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextMuted)
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
                    if (masterPin.length < 4) {
                        errorMessage = "PIN must be at least 4 digits"
                    } else if (masterPin != confirmPin) {
                        errorMessage = "PIN confirmation does not match"
                    } else {
                        val computedHour = if (!isAm && hour < 12) hour + 12 else if (isAm && hour == 12) 0 else hour
                        val newAlarm = AlarmEntity(
                            hour = computedHour,
                            minute = minute,
                            missionDifficulty = difficulty,
                            roundsCount = rounds,
                            timeoutSeconds = difficulty.defaultTimeoutSec,
                            wakeUpIntervalSeconds = wakeIntervalSec,
                            wakeUpTotalDurationSeconds = wakeDurationSec,
                            masterPinHash = WakelockSecurity.hashPin(masterPin)
                        )
                        onSave(newAlarm)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = LaserGreen)
            ) {
                Text("ARM PROTOCOL", color = PureBlack, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = TextSecondary)
            }
        }
    )
}
