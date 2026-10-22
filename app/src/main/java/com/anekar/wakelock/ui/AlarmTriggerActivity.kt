package com.anekar.wakelock.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
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

        // Math Mission solve karte waqt siren ko mute karna
        if (!isWakeUpCheck) {
            val muteIntent = Intent(this, AlarmForegroundService::class.java).apply {
                action = AlarmForegroundService.ACTION_STOP_ALARM
            }
            startService(muteIntent)
        }

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
            MathGauntletScreen(alarm)
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
                // Wake-up Check Live Counter
                Text(
                    text = "CHECK $currentCheck OF $totalChecks",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
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
        var timeLeft by remember { mutableIntStateOf(alarm.timeoutSeconds) }
        var feedbackMessage by remember { mutableStateOf<String?>(null) }
        var isAnswerCorrect by remember { mutableStateOf(false) }
        var showPinDialog by remember { mutableStateOf(false) }

        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        // Countdown Timer
        DisposableEffect(currentRound, problem) {
            val timer = object : CountDownTimer((timeLeft * 1000).toLong(), 1000) {
                override fun onTick(millisUntilFinished: Long) {
                    timeLeft = (millisUntilFinished / 1000).toInt()
                }

                override fun onFinish() {
                    // Timeout -> Play alarm siren and reset problem
                    val sirenIntent = Intent(this@AlarmTriggerActivity, AlarmForegroundService::class.java).apply {
                        action = AlarmForegroundService.ACTION_START_ALARM
                        putExtra(AlarmForegroundService.EXTRA_ALARM_ID, alarm.id)
                    }
                    startService(sirenIntent)

                    vibrateError(vibrator)
                    problem = MathMissionGenerator.generateProblem(difficulty)
                    userInput = ""
                    timeLeft = alarm.timeoutSeconds
                    feedbackMessage = "TIMEOUT // ALARM TRIGGERED"
                    isAnswerCorrect = false
                    start()
                }
            }.start()

            onDispose { timer.cancel() }
        }

        // Keypad button dabane par timer reset karne ka function
        fun resetTimerOnTyping() {
            timeLeft = alarm.timeoutSeconds
            feedbackMessage = null
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
                        fontSize = 16.sp,
                        color = if (timeLeft <= 10) ThreatCrimson else TextPrimary
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { timeLeft.toFloat() / alarm.timeoutSeconds.toFloat() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = if (isPunishment || timeLeft <= 10) ThreatCrimson else LaserGreen,
                    trackColor = SubtleBorder
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Feedback Banner
                if (feedbackMessage != null) {
                    Text(
                        text = feedbackMessage!!,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = if (isAnswerCorrect) LaserGreen else ThreatCrimson,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                // 6-Chain Addition Numbers
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

                // Keypad with auto-timer reset
                NumericKeypad(
                    onDigit = { digit ->
                        if (userInput.length < 15) {
                            userInput += digit
                            resetTimerOnTyping()
                        }
                    },
                    onBackspace = {
                        if (userInput.isNotEmpty()) {
                            userInput = userInput.dropLast(1)
                            resetTimerOnTyping()
                        }
                    },
                    onSubmit = {
                        val isCorrect = MathMissionGenerator.validateAnswer(userInput, problem.correctSum)
                        if (isCorrect) {
                            feedbackMessage = "CORRECT ANSWER ✓"
                            isAnswerCorrect = true
                            CoroutineScope(Dispatchers.Main).launch {
                                delay(400)
                                if (currentRound < totalRounds) {
                                    currentRound++
                                    problem = MathMissionGenerator.generateProblem(difficulty)
                                    userInput = ""
                                    timeLeft = alarm.timeoutSeconds
                                    feedbackMessage = null
                                } else {
                                    onGauntletCompleted(alarm)
                                }
                            }
                        } else {
                            vibrateError(vibrator)
                            feedbackMessage = "WRONG! TRY AGAIN"
                            isAnswerCorrect = false
                            userInput = ""
                            resetTimerOnTyping()
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
            vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator.vibrate(300)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Suppress back button
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
