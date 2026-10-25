package com.anekar.wakelock.ui

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.anekar.wakelock.data.WakelockSecurity
import com.anekar.wakelock.data.database.WakelockDatabase
import com.anekar.wakelock.data.model.AlarmEntity
import com.anekar.wakelock.data.model.MissionDifficulty
import com.anekar.wakelock.receiver.AlarmReceiver
import com.anekar.wakelock.ui.theme.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkEssentialPermissions()

        setContent {
            WakelockTheme {
                MainScreenContent()
            }
        }
    }

    private fun checkEssentialPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MainScreenContent() {
        val context = LocalContext.current
        val db = remember { WakelockDatabase.getDatabase(context) }
        var alarms by remember { mutableStateOf<List<AlarmEntity>>(emptyList()) }
        var editingAlarm by remember { mutableStateOf<AlarmEntity?>(null) }
        var showEditDialog by remember { mutableStateOf(false) }

        fun reloadAlarms() {
            CoroutineScope(Dispatchers.IO).launch {
                val list = db.alarmDao().getAllAlarms()
                withContext(Dispatchers.Main) {
                    alarms = list
                }
            }
        }

        LaunchedEffect(Unit) {
            reloadAlarms()
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "WAKELOCK",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp,
                                color = LaserGreen,
                                letterSpacing = 2.sp
                            )
                            Text(
                                text = "TAMPER-PROOF DISCIPLINE PROTOCOL",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                color = TextMuted
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = PureBlack)
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = {
                        editingAlarm = AlarmEntity(
                            hour = 5,
                            minute = 0,
                            roundsCount = 5,
                            missionDifficulty = MissionDifficulty.TWO_DIGIT,
                            wakeUpIntervalSeconds = -1, // Default to ⚡ Taper
                            wakeUpTotalDurationSeconds = 7200, // 2 Hours
                            timeoutSeconds = 30,
                            masterPinHash = WakelockSecurity.hashPin("1234"),
                            isEnabled = true
                        )
                        showEditDialog = true
                    },
                    containerColor = LaserGreen,
                    contentColor = PureBlack
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Protocol")
                }
            },
            containerColor = PureBlack
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
            ) {
                if (alarms.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "NO PROTOCOLS ARMED\nTAP + TO INITIALIZE",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            color = TextMuted,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(alarms) { alarm ->
                            AlarmCard(
                                alarm = alarm,
                                onEdit = {
                                    editingAlarm = alarm
                                    showEditDialog = true
                                },
                                onToggle = { enabled ->
                                    CoroutineScope(Dispatchers.IO).launch {
                                        val updatedAlarm = alarm.copy(isEnabled = enabled)
                                        db.alarmDao().updateAlarm(updatedAlarm)
                                        if (enabled) {
                                            scheduleSystemAlarm(context, updatedAlarm)
                                        } else {
                                            cancelSystemAlarm(context, alarm.id)
                                        }
                                        reloadAlarms()
                                    }
                                },
                                onDelete = {
                                    CoroutineScope(Dispatchers.IO).launch {
                                        cancelSystemAlarm(context, alarm.id)
                                        db.alarmDao().deleteAlarm(alarm)
                                        reloadAlarms()
                                    }
                                }
                            )
                        }
                    }
                }

                if (showEditDialog && editingAlarm != null) {
                    EditProtocolDialog(
                        alarm = editingAlarm!!,
                        onDismiss = {
                            showEditDialog = false
                            editingAlarm = null
                        },
                        onSave = { updatedAlarm ->
                            CoroutineScope(Dispatchers.IO).launch {
                                val savedId = if (updatedAlarm.id == 0L) {
                                    db.alarmDao().insertAlarm(updatedAlarm)
                                } else {
                                    db.alarmDao().updateAlarm(updatedAlarm)
                                    updatedAlarm.id
                                }

                                val finalAlarm = updatedAlarm.copy(id = savedId)
                                if (finalAlarm.isEnabled) {
                                    scheduleSystemAlarm(context, finalAlarm)
                                } else {
                                    cancelSystemAlarm(context, finalAlarm.id)
                                }

                                withContext(Dispatchers.Main) {
                                    showEditDialog = false
                                    editingAlarm = null
                                    reloadAlarms()
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    @Composable
    private fun AlarmCard(
        alarm: AlarmEntity,
        onEdit: () -> Unit,
        onToggle: (Boolean) -> Unit,
        onDelete: () -> Unit
    ) {
        val amPm = if (alarm.hour < 12) "AM" else "PM"
        val hour12 = when (val h = alarm.hour % 12) {
            0 -> 12
            else -> h
        }
        val displayTime = String.format("%02d:%02d", hour12, alarm.minute)

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, if (alarm.isEnabled) LaserGreen else SubtleBorder, RoundedCornerShape(8.dp)),
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
                            text = displayTime,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 32.sp,
                            color = if (alarm.isEnabled) TextPrimary else TextMuted
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = amPm,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = if (alarm.isEnabled) LaserGreen else TextMuted,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    Switch(
                        checked = alarm.isEnabled,
                        onCheckedChange = onToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = PureBlack,
                            checkedTrackColor = LaserGreen,
                            uncheckedThumbColor = TextMuted,
                            uncheckedTrackColor = ElevatedSurface
                        )
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "${alarm.roundsCount} ROUNDS // ${alarm.missionDifficulty.name.replace("_", "-")}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = LaserGreen
                )

                val intervalText = if (alarm.wakeUpIntervalSeconds == -1) {
                    "⚡ TAPER (30m @ 5m ➔ 10m)"
                } else {
                    "${alarm.wakeUpIntervalSeconds / 60}m INTERVAL"
                }
                val durationText = "${alarm.wakeUpTotalDurationSeconds / 3600}h DURATION"

                Text(
                    text = "$intervalText // $durationText",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = WarningAmber
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit", tint = TextSecondary)
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = ThreatCrimson)
                    }
                }
            }
        }
    }

    @Composable
    private fun EditProtocolDialog(
        alarm: AlarmEntity,
        onDismiss: () -> Unit,
        onSave: (AlarmEntity) -> Unit
    ) {
        val context = LocalContext.current
        var targetHour by remember { mutableIntStateOf(alarm.hour) }
        var targetMinute by remember { mutableIntStateOf(alarm.minute) }
        var roundsCount by remember { mutableIntStateOf(alarm.roundsCount) }
        var missionDifficulty by remember { mutableStateOf(alarm.missionDifficulty) }
        var wakeUpIntervalSeconds by remember { mutableIntStateOf(alarm.wakeUpIntervalSeconds) }
        var wakeUpTotalDurationSeconds by remember { mutableIntStateOf(alarm.wakeUpTotalDurationSeconds) }

        var masterPinInput by remember { mutableStateOf("") }
        var confirmPinInput by remember { mutableStateOf("") }
        var pinError by remember { mutableStateOf<String?>(null) }

        val scrollState = rememberScrollState()

        Dialog(onDismissRequest = onDismiss) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.92f),
                shape = RoundedCornerShape(16.dp),
                color = DarkSurface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp)
                        .verticalScroll(scrollState)
                ) {
                    Text(
                        text = "EDIT PROTOCOL",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = TextPrimary
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // TARGET TIME (TAP TO CHANGE)
                    Text(
                        text = "TARGET TIME (TAP TO CHANGE)",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    val hour12 = when (val h = targetHour % 12) {
                        0 -> 12
                        else -> h
                    }
                    val amPm = if (targetHour < 12) "AM" else "PM"
                    val formattedTime = String.format("%02d : %02d", hour12, targetMinute)

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(PureBlack, RoundedCornerShape(8.dp))
                            .border(1.dp, SubtleBorder, RoundedCornerShape(8.dp))
                            .clickable {
                                TimePickerDialog(
                                    context,
                                    { _, h, m ->
                                        targetHour = h
                                        targetMinute = m
                                    },
                                    targetHour,
                                    targetMinute,
                                    false
                                ).show()
                            }
                            .padding(vertical = 14.dp, horizontal = 20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = formattedTime,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 32.sp,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Box(
                                modifier = Modifier
                                    .border(1.dp, LaserGreen, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = amPm,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = LaserGreen
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // MATH ROUNDS (PROBLEMS TO SOLVE)
                    Text(
                        text = "MATH ROUNDS (PROBLEMS TO SOLVE)",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { if (roundsCount > 1) roundsCount-- },
                            colors = ButtonDefaults.buttonColors(containerColor = ElevatedSurface),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.size(48.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("-", fontSize = 22.sp, color = TextPrimary)
                        }
                        Text(
                            text = String.format("%02d", roundsCount),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 24.sp,
                            color = LaserGreen,
                            modifier = Modifier.padding(horizontal = 28.dp)
                        )
                        Button(
                            onClick = { if (roundsCount < 20) roundsCount++ },
                            colors = ButtonDefaults.buttonColors(containerColor = ElevatedSurface),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.size(48.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("+", fontSize = 22.sp, color = TextPrimary)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // MISSION DIFFICULTY (6-CHAIN)
                    Text(
                        text = "MISSION DIFFICULTY (6-CHAIN)",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val difficulties = listOf(
                        MissionDifficulty.TWO_DIGIT to "2-Digit (6-Chain)",
                        MissionDifficulty.FOUR_DIGIT to "4-Digit (6-Chain)",
                        MissionDifficulty.FIVE_DIGIT to "5-Digit (6-Chain)",
                        MissionDifficulty.SEVEN_DIGIT to "7-Digit (6-Chain)",
                        MissionDifficulty.EIGHT_DIGIT to "8-Digit (6-Chain)"
                    )
                    difficulties.forEach { (diff, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { missionDifficulty = diff }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (missionDifficulty == diff),
                                onClick = { missionDifficulty = diff },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = LaserGreen,
                                    unselectedColor = TextMuted
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = label,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                color = if (missionDifficulty == diff) TextPrimary else TextMuted
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // WAKE-UP CHECK INTERVAL (WITH ⚡ TAPER)
                    Text(
                        text = "WAKE-UP CHECK INTERVAL",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    val intervalOptions = listOf(
                        -1 to "⚡ Taper",
                        300 to "5m",
                        600 to "10m",
                        30 to "30s (Test)"
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        intervalOptions.forEach { (seconds, label) ->
                            val isSelected = (wakeUpIntervalSeconds == seconds)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .background(
                                        if (isSelected) DarkSurface else PureBlack,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .border(
                                        1.dp,
                                        if (isSelected) LaserGreen else SubtleBorder,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .clickable { wakeUpIntervalSeconds = seconds },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) LaserGreen else TextMuted
                                )
                            }
                        }
                    }

                    if (wakeUpIntervalSeconds == -1) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "⚡ TAPER: First 30m checks every 5m (6 checks) ➔ Then every 10m until duration ends.",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = LaserGreen
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // SURVEILLANCE DURATION (WITH 2 HOURS)
                    Text(
                        text = "SURVEILLANCE DURATION",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    val durationOptions = listOf(
                        7200 to "2 Hours",
                        3600 to "1 Hour",
                        120 to "2m (Test)"
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        durationOptions.forEach { (seconds, label) ->
                            val isSelected = (wakeUpTotalDurationSeconds == seconds)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .background(
                                        if (isSelected) DarkSurface else PureBlack,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .border(
                                        1.dp,
                                        if (isSelected) LaserGreen else SubtleBorder,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .clickable { wakeUpTotalDurationSeconds = seconds },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) LaserGreen else TextMuted
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // MASTER PIN
                    Text(
                        text = "UPDATE MASTER PIN (OR LEAVE BLANK TO KEEP)",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = masterPinInput,
                        onValueChange = { if (it.length <= 8) masterPinInput = it },
                        label = { Text("Master PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = LaserGreen,
                            unfocusedBorderColor = SubtleBorder
                        )
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = confirmPinInput,
                        onValueChange = { if (it.length <= 8) confirmPinInput = it },
                        label = { Text("Confirm PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = LaserGreen,
                            unfocusedBorderColor = SubtleBorder
                        )
                    )

                    if (pinError != null) {
                        Text(
                            text = pinError!!,
                            color = ThreatCrimson,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // ACTION BUTTONS
                    Button(
                        onClick = {
                            var finalHash = alarm.masterPinHash
                            if (masterPinInput.isNotEmpty()) {
                                if (masterPinInput != confirmPinInput) {
                                    pinError = "PINS DO NOT MATCH"
                                    return@Button
                                }
                                finalHash = WakelockSecurity.hashPin(masterPinInput)
                            }

                            val updated = alarm.copy(
                                hour = targetHour,
                                minute = targetMinute,
                                roundsCount = roundsCount,
                                missionDifficulty = missionDifficulty,
                                wakeUpIntervalSeconds = wakeUpIntervalSeconds,
                                wakeUpTotalDurationSeconds = wakeUpTotalDurationSeconds,
                                masterPinHash = finalHash
                            )
                            onSave(updated)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(25.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = LaserGreen)
                    ) {
                        Text(
                            text = "UPDATE PROTOCOL",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = PureBlack
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "CANCEL",
                            fontFamily = FontFamily.Monospace,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }

    private fun scheduleSystemAlarm(context: Context, alarm: AlarmEntity) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE, alarm.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("ALARM_ID", alarm.id)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarm.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val clockInfo = AlarmManager.AlarmClockInfo(calendar.timeInMillis, pendingIntent)
        alarmManager.setAlarmClock(clockInfo, pendingIntent)
    }

    private fun cancelSystemAlarm(context: Context, alarmId: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarmId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }
}
