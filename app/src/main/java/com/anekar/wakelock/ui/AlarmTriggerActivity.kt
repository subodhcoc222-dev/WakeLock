package com.anekar.wakelock.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anekar.wakelock.data.database.WakelockDatabase
import com.anekar.wakelock.data.model.AlarmEntity
import com.anekar.wakelock.data.model.MissionDifficulty
import com.anekar.wakelock.data.WakelockSecurity
import com.anekar.wakelock.domain.math.MathMissionGenerator
import com.anekar.wakelock.domain.math.MathProblem
import com.anekar.wakelock.service.AlarmForegroundService
import com.anekar.wakelock.service.PunishmentGauntletService
import com.anekar.wakelock.service.WakeUpSurveillanceService
import com.anekar.wakelock.ui.theme.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AlarmTriggerActivity : ComponentActivity() {

    private var alarmId: Long = -1L
    private var isWakeUpCheck: Boolean = false
    private var isPunishment: Boolean = false
    private var currentCheckNumber: Int = 1
    private var totalChecksNumber: Int = 1
    private var currentAlarm: AlarmEntity? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        alarmId = intent.getLongExtra("ALARM_ID", -1L)
        isWakeUpCheck = intent.getBooleanExtra("IS_WAKEUP_CHECK", false)
        isPunishment = intent.getBooleanExtra("IS_PUNISHMENT", false)
        currentCheckNumber = intent.getIntExtra("CURRENT_CHECK", 1)
        totalChecksNumber = intent.getIntExtra("TOTAL_CHECKS", 1)

        setContent {
            WakelockTheme {
                TriggerScreenContent()
            }
        }

        loadAlarmData()
    }

    private fun loadAlarmData() {
        CoroutineScope(Dispatchers.IO).launch {
            val db = WakelockDatabase.getDatabase(applicationContext)
            val alarm = db.alarmDao().getAlarmById(alarmId)
            withContext(Dispatchers.Main) {
                currentAlarm = alarm
            }
        }
    }

    @Composable
    private fun TriggerScreenContent() {
        val alarm = currentAlarm

        if (isWakeUpCheck) {
            WakeUpCheckScreen(currentCheckNumber, totalChecksNumber)
        } else if (alarm != null) {
            AlarmWorkflowScreen(alarm)
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(PureBlack),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = LaserGreen)
            }
        }
    }

    @Composable
    private fun AlarmWorkflowScreen(alarm: AlarmEntity) {
        var isDismissedToMission by remember { mutableStateOf(false) }

        if (!isDismissedToMission && !isPunishment) {
            RingingAlarmScreen(
                onDismiss = {
                    isDismissedToMission = true
                    // पहली बार म्यूट: शांति से हल करने की शुरुआत
                    val muteIntent = Intent(this@AlarmTriggerActivity, AlarmForegroundService::class.java).apply {
                        action = AlarmForegroundService.ACTION_MUTE_TEMPORARY
                    }
                    startService(muteIntent)
                }
            )
        } else {
            MathGauntletScreen(alarm)
        }
    }

    @Composable
    private fun RingingAlarmScreen(onDismiss: () -> Unit) {
        val infiniteTransition = rememberInfiniteTransition(label = "alarmPulse")
        val pulseScale by infiniteTransition.animateFloat(
            initialValue = 0.92f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(
                animation = tween(500, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseScale"
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(PureBlack)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "CRITICAL ALARM ACTIVE",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = ThreatCrimson,
                    letterSpacing = 2.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "DISMISS TO START CALM SOLVING",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = LaserGreen
                )
                Spacer(modifier = Modifier.height(60.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(230.dp)
                        .scale(pulseScale),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = ThreatCrimson)
                ) {
                    Text(
                        text = "DISMISS &\nSOLVE MISSION",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = TextPrimary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    @Composable
    private fun WakeUpCheckScreen(currentCheck: Int, totalChecks: Int) {
        val remainingChecks = (totalChecks - currentCheck).coerceAtLeast(0)
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val scale by infiniteTransition.animateFloat(
            initialValue = 0.95f,
            targetValue = 1.05f,
            animationSpec = infiniteRepeatable(
                animation = tween(600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseScale"
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(PureBlack)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "WAKELOCK // SURVEILLANCE ACTIVE",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = LaserGreen,
                    letterSpacing = 1.5.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "CHECK $currentCheck OF $totalChecks",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "REMAINING CHECKS: $remainingChecks",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = WarningAmber
                )
                Spacer(modifier = Modifier.height(50.dp))

                Button(
                    onClick = {
                        val serviceIntent = Intent(this@AlarmTriggerActivity, WakeUpSurveillanceService::class.java).apply {
                            action = WakeUpSurveillanceService.ACTION_CONFIRM_AWAKE
                        }
                        startService(serviceIntent)
                        finish()
                    },
                    modifier = Modifier
                        .size(220.dp)
                        .scale(scale),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = LaserGreen)
                ) {
                    Text(
                        text = "I AM AWAKE",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = PureBlack
                    )
                }
            }
        }
    }

    @Composable
    private fun MathGauntletScreen(alarm: AlarmEntity) {
        var currentRound by remember { mutableIntStateOf(1) }
        val totalRounds = if (isPunishment) 1 else alarm.roundsCount
        val difficulty = if (isPunishment) MissionDifficulty.SEVEN_DIGIT else alarm.missionDifficulty
        var problem by remember { mutableStateOf(MathMissionGenerator.generateProblem(difficulty)) }
        var userInput by remember { mutableStateOf("") }
        var feedbackMessage by remember { mutableStateOf<String?>(null) }
        var isAnswerCorrect by remember { mutableStateOf(false) }
        var showPinDialog by remember { mutableStateOf(false) }

        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        // Activity Watchdog State
        var activityWatchdogKey by remember { mutableIntStateOf(0) }
        var timeLeft by remember { mutableIntStateOf(alarm.timeoutSeconds) }
        var isAlarmCurrentlyBlasting by remember { mutableStateOf(false) }

        // Inactivity Watchdog Timer
        LaunchedEffect(currentRound, problem, activityWatchdogKey) {
            timeLeft = alarm.timeoutSeconds
            while (timeLeft > 0) {
                delay(1000L)
                timeLeft--
            }

            // 30s इनएक्टिविटी पर अलार्म शुरू
            isAlarmCurrentlyBlasting = true
            vibrateError(vibrator)
            val sirenIntent = Intent(this@AlarmTriggerActivity, AlarmForegroundService::class.java).apply {
                action = AlarmForegroundService.ACTION_RESUME_TONE
                putExtra(AlarmForegroundService.EXTRA_ALARM_ID, alarm.id)
            }
            startService(sirenIntent)

            feedbackMessage = "INACTIVITY DETECTED // ALARM ACTIVE"
            isAnswerCorrect = false
        }

        // Heartbeat Watchdog Trigger: कीपैड का कोई भी बटन (नंबर या बैकस्पेस) दबाने पर
        fun onUserActivityInput(digit: String? = null, isBackspace: Boolean = false) {
            // 1. यदि इनएक्टिविटी की वजह से अलार्म बज रहा था -> तुरंत म्यूट करें!
            if (isAlarmCurrentlyBlasting) {
                val muteIntent = Intent(this@AlarmTriggerActivity, AlarmForegroundService::class.java).apply {
                    action = AlarmForegroundService.ACTION_MUTE_TEMPORARY
                }
                startService(muteIntent)
                isAlarmCurrentlyBlasting = false
            }

            feedbackMessage = null

            // 2. इनपुट हैंडल करें
            if (isBackspace) {
                if (userInput.isNotEmpty()) {
                    userInput = userInput.dropLast(1)
                }
            } else if (digit != null) {
                if (userInput.length < 15) {
                    userInput += digit
                }
            }

            // 3. टाइमर को तुरंत पूरे 30s पर रीसेट करें!
            activityWatchdogKey++
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(PureBlack)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // टॉप बार: राउंड्स और वॉचडॉग टाइमर
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isPunishment) "PUNISHMENT GAUNTLET" else "ROUND $currentRound OF $totalRounds",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = if (isPunishment) ThreatCrimson else LaserGreen
                    )
                    Text(
                        text = "${timeLeft}s",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = if (timeLeft <= 10) ThreatCrimson else TextPrimary
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { timeLeft.toFloat() / alarm.timeoutSeconds.toFloat() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = if (isPunishment || timeLeft <= 10) ThreatCrimson else LaserGreen,
                    trackColor = SubtleBorder
                )

                // एक्टिविटी वॉचडॉग स्टेटस
                Text(
                    text = if (isAlarmCurrentlyBlasting) "PRESS ANY KEY TO MUTE" else "TYPE TO MAINTAIN CALM // 30s INACTIVITY LIMIT",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isAlarmCurrentlyBlasting) ThreatCrimson else LaserGreen,
                    modifier = Modifier.padding(top = 6.dp)
                )

                Spacer(modifier = Modifier.height(14.dp))

                // फीडबैक बैनर
                if (feedbackMessage != null) {
                    Text(
                        text = feedbackMessage!!,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = if (isAnswerCorrect) LaserGreen else ThreatCrimson,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }

                // 6-चेन वर्टिकल नंबर्स
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            if (feedbackMessage != null && !isAnswerCorrect) ThreatCrimson else SubtleBorder,
                            RoundedCornerShape(8.dp)
                        ),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        problem.terms.forEachIndexed { index, num ->
                            Text(
                                text = if (index == 0) "  $num" else "+ $num",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                textAlign = TextAlign.End
                            )
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = SubtleBorder,
                            thickness = 1.dp
                        )
                        Text(
                            text = if (userInput.isEmpty()) "ENTER SUM" else userInput,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (userInput.isEmpty()) TextMuted else LaserGreen,
                            textAlign = TextAlign.End
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // कीपैड: किसी भी बटन (नंबर या बैकस्पेस) पर तुरंत रीसेट और री-म्यूट
                NumericKeypad(
                    onDigit = { digit ->
                        onUserActivityInput(digit = digit)
                    },
                    onBackspace = {
                        onUserActivityInput(isBackspace = true)
                    },
                    onSubmit = {
                        val isCorrect = MathMissionGenerator.validateAnswer(userInput, problem.correctSum)
                        if (isCorrect) {
                            feedbackMessage = "CORRECT ANSWER ✓"
                            isAnswerCorrect = true
                            if (isAlarmCurrentlyBlasting) {
                                val muteIntent = Intent(this@AlarmTriggerActivity, AlarmForegroundService::class.java).apply {
                                    action = AlarmForegroundService.ACTION_MUTE_TEMPORARY
                                }
                                startService(muteIntent)
                                isAlarmCurrentlyBlasting = false
                            }
                            CoroutineScope(Dispatchers.Main).launch {
                                delay(350)
                                if (currentRound < totalRounds) {
                                    currentRound++
                                    problem = MathMissionGenerator.generateProblem(difficulty)
                                    userInput = ""
                                    feedbackMessage = null
                                    activityWatchdogKey++
                                } else {
                                    onGauntletCompleted(alarm)
                                }
                            }
                        } else {
                            // गलत उत्तर: अलार्म नहीं बजेगा, बस वाइब्रेशन और एरर, टाइमर रीसेट
                            vibrateError(vibrator)
                            feedbackMessage = "WRONG! TRY AGAIN"
                            isAnswerCorrect = false
                            userInput = ""
                            activityWatchdogKey++
                        }
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "[ EMERGENCY MASTER PIN ]",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = TextMuted,
                    modifier = Modifier.clickable { showPinDialog = true }
                )
            }

            if (showPinDialog) {
                MasterPinVerificationDialog(
                    correctHash = alarm.masterPinHash,
                    onDismiss = { showPinDialog = false },
                    onSuccess = {
                        if (isPunishment) {
                            val serviceIntent = Intent(this@AlarmTriggerActivity, PunishmentGauntletService::class.java).apply {
                                action = PunishmentGauntletService.ACTION_TERMINATE_WITH_PIN
                            }
                            startService(serviceIntent)
                        } else {
                            stopService(Intent(this@AlarmTriggerActivity, AlarmForegroundService::class.java))
                        }
                        finish()
                    }
                )
            }
        }
    }

    private fun onGauntletCompleted(alarm: AlarmEntity) {
        if (isPunishment) {
            val serviceIntent = Intent(this, PunishmentGauntletService::class.java).apply {
                action = PunishmentGauntletService.ACTION_SOLVED_ROUND
            }
            startService(serviceIntent)
            finish()
        } else {
            val stopAlarmIntent = Intent(this, AlarmForegroundService::class.java).apply {
                action = AlarmForegroundService.ACTION_STOP_ALARM
            }
            startService(stopAlarmIntent)

            if (alarm.wakeUpTotalDurationSeconds > 0) {
                val surveillanceIntent = Intent(this, WakeUpSurveillanceService::class.java).apply {
                    action = WakeUpSurveillanceService.ACTION_START_SURVEILLANCE
                    putExtra(WakeUpSurveillanceService.EXTRA_ALARM_ID, alarm.id)
                }
                startService(surveillanceIntent)
            }
            finish()
        }
    }

    private fun vibrateError(vibrator: Vibrator) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator.vibrate(250)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Back press block
    }
}

@Composable
fun NumericKeypad(
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit
) {
    val keys = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("⌫", "0", "GO")
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        keys.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { key ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(58.dp)
                            .background(
                                if (key == "GO") LaserGreen else ElevatedSurface,
                                RoundedCornerShape(6.dp)
                            )
                            .border(1.dp, SubtleBorder, RoundedCornerShape(6.dp))
                            .clickable {
                                when (key) {
                                    "⌫" -> onBackspace()
                                    "GO" -> onSubmit()
                                    else -> onDigit(key)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = key,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = if (key == "GO") PureBlack else TextPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MasterPinVerificationDialog(
    correctHash: String,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    var enteredPin by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        title = {
            Text(
                text = "ENTER MASTER PIN",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = ThreatCrimson
            )
        },
        text = {
            Column {
                Text(
                    text = "Authorization required to override protocol.",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = enteredPin,
                    onValueChange = { if (it.length <= 8) enteredPin = it },
                    singleLine = true,
                    isError = isError,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = LaserGreen,
                        unfocusedBorderColor = SubtleBorder,
                        focusedTextColor = TextPrimary
                    )
                )
                if (isError) {
                    Text(
                        text = "INVALID PIN // ACCESS DENIED",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = ThreatCrimson,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (WakelockSecurity.verifyPin(enteredPin, correctHash)) {
                        onSuccess()
                    } else {
                        isError = true
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = LaserGreen)
            ) {
                Text("VERIFY", color = PureBlack, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = TextSecondary)
            }
        }
    )
}
