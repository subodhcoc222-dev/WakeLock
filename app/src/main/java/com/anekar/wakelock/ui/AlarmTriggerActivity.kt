package com.anekar.wakelock.ui

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.anekar.wakelock.data.database.WakelockDatabase
import com.anekar.wakelock.data.model.AlarmEntity
import com.anekar.wakelock.data.model.MissionDifficulty
import com.anekar.wakelock.data.WakelockSecurity
import com.anekar.wakelock.domain.math.MathMissionGenerator
import com.anekar.wakelock.service.AlarmForegroundService
import com.anekar.wakelock.service.PunishmentGauntletService
import com.anekar.wakelock.service.WakeUpSurveillanceService
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

class AlarmTriggerActivity : ComponentActivity() {

    private var alarmId by mutableLongStateOf(-1L)
    private var isWakeUpCheck by mutableStateOf(false)
    private var isPunishment by mutableStateOf(false)
    private var currentCheckNumber by mutableIntStateOf(1)
    private var totalChecksNumber by mutableIntStateOf(1)
    private var failedCheckIndex by mutableIntStateOf(0)
    private var currentAlarm by mutableStateOf<AlarmEntity?>(null)
    private var isLockdownActive = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            km.requestDismissKeyguard(this, null)
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        handleIncomingIntent(intent)

        setContent {
            WakelockTheme {
                TriggerScreenContent()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(incomingIntent: Intent?) {
        if (incomingIntent == null) return
        val prefs = getSharedPreferences("wakelock_prefs", Context.MODE_PRIVATE)

        alarmId = incomingIntent.getLongExtra("ALARM_ID", -1L)
        isWakeUpCheck = incomingIntent.getBooleanExtra("IS_WAKEUP_CHECK", false)
        isPunishment = incomingIntent.getBooleanExtra("IS_PUNISHMENT", false)

        failedCheckIndex = incomingIntent.getIntExtra(
            "FAILED_CHECK_INDEX",
            prefs.getInt("failed_check_index", 0)
        )

        if (incomingIntent.hasExtra("CURRENT_CHECK")) {
            currentCheckNumber = incomingIntent.getIntExtra("CURRENT_CHECK", 1)
            prefs.edit().putInt("cached_current_check", currentCheckNumber).apply()
        } else {
            currentCheckNumber = prefs.getInt("cached_current_check", 1)
        }

        if (incomingIntent.hasExtra("TOTAL_CHECKS")) {
            totalChecksNumber = incomingIntent.getIntExtra("TOTAL_CHECKS", 1)
            prefs.edit().putInt("cached_total_checks", totalChecksNumber).apply()
        } else {
            totalChecksNumber = prefs.getInt("cached_total_checks", 1)
        }

        isLockdownActive = true
        prefs.edit()
            .putBoolean("is_lockdown_active", true)
            .putLong("active_alarm_id", alarmId)
            .putBoolean("is_punishment_active", isPunishment)
            .putBoolean("is_wakeup_active", isWakeUpCheck)
            .putInt("failed_check_index", failedCheckIndex)
            .apply()

        loadAlarmData(alarmId)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isLockdownActive) {
            bringToFront()
        }
    }

    private fun bringToFront() {
        val reopenIntent = Intent(this, AlarmTriggerActivity::class.java).apply {
            putExtra("ALARM_ID", alarmId)
            putExtra("IS_WAKEUP_CHECK", isWakeUpCheck)
            putExtra("IS_PUNISHMENT", isPunishment)
            putExtra("CURRENT_CHECK", currentCheckNumber)
            putExtra("TOTAL_CHECKS", totalChecksNumber)
            putExtra("FAILED_CHECK_INDEX", failedCheckIndex)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        startActivity(reopenIntent)
    }

    private fun loadAlarmData(targetId: Long) {
        CoroutineScope(Dispatchers.IO).launch {
            val db = WakelockDatabase.getDatabase(applicationContext)
            var alarm = if (targetId > 0) db.alarmDao().getAlarmById(targetId) else null
            if (alarm == null) {
                alarm = db.alarmDao().getAllAlarms().firstOrNull()
            }
            withContext(Dispatchers.Main) {
                currentAlarm = alarm
            }
        }
    }

    @Composable
    private fun TriggerScreenContent() {
        val alarm = currentAlarm

        if (isWakeUpCheck) {
            val requiresQrForCheck = alarm != null && 
                                     alarm.qrPayload.isNotBlank() && 
                                     currentCheckNumber <= alarm.qrChecksCount

            if (requiresQrForCheck) {
                WakeUpQrScannerScreen(
                    currentCheck = currentCheckNumber,
                    totalChecks = totalChecksNumber,
                    expectedPayload = alarm!!.qrPayload,
                    onVerified = {
                        confirmAwakeSuccess()
                    },
                    onTimeout = {
                        triggerCheckFailurePenalty(alarm)
                    }
                )
            } else {
                WakeUpCheckScreen(
                    currentCheck = currentCheckNumber,
                    totalChecks = totalChecksNumber,
                    onTimeout = {
                        triggerCheckFailurePenalty(alarm)
                    }
                )
            }
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

    private fun confirmAwakeSuccess() {
        isLockdownActive = false
        getSharedPreferences("wakelock_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("is_lockdown_active", false)
            .apply()

        val serviceIntent = Intent(this@AlarmTriggerActivity, WakeUpSurveillanceService::class.java).apply {
            action = WakeUpSurveillanceService.ACTION_CONFIRM_AWAKE
        }
        startService(serviceIntent)
        finish()
    }

    private fun triggerCheckFailurePenalty(alarm: AlarmEntity?) {
        isWakeUpCheck = false
        failedCheckIndex = currentCheckNumber.coerceAtLeast(1)
        val prefs = getSharedPreferences("wakelock_prefs", Context.MODE_PRIVATE)
        prefs.edit().putInt("failed_check_index", failedCheckIndex).apply()

        val sirenIntent = Intent(this@AlarmTriggerActivity, AlarmForegroundService::class.java).apply {
            action = AlarmForegroundService.ACTION_START_ALARM
            putExtra(AlarmForegroundService.EXTRA_ALARM_ID, alarm?.id ?: alarmId)
            putExtra("FAILED_CHECK_INDEX", failedCheckIndex)
        }
        startService(sirenIntent)
    }

    @Composable
    private fun AlarmWorkflowScreen(alarm: AlarmEntity) {
        var isDismissed by remember { mutableStateOf(false) }
        var isQrVerified by remember { mutableStateOf(alarm.qrPayload.isBlank() || isPunishment) }

        if (!isDismissed && !isPunishment) {
            RingingAlarmScreen(
                hasQr = alarm.qrPayload.isNotBlank(),
                onDismiss = {
                    isDismissed = true
                    val muteIntent = Intent(this@AlarmTriggerActivity, AlarmForegroundService::class.java).apply {
                        action = AlarmForegroundService.ACTION_MUTE_TEMPORARY
                    }
                    startService(muteIntent)
                }
            )
        } else if (!isQrVerified) {
            InitialAlarmQrScannerScreen(
                expectedPayload = alarm.qrPayload,
                onVerified = {
                    isQrVerified = true
                }
            )
        } else {
            MathGauntletScreen(alarm)
        }
    }

    @Composable
    private fun RingingAlarmScreen(hasQr: Boolean, onDismiss: () -> Unit) {
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
                    fontSize = 18.sp,
                    color = ThreatCrimson,
                    letterSpacing = 2.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = if (failedCheckIndex > 0) {
                        "CHECK #$failedCheckIndex MISSED // ${failedCheckIndex + 2}X PENALTY ARMED"
                    } else if (hasQr) {
                        "DISMISS TO SCAN DESK QR & UNLOCK MISSION"
                    } else {
                        "DISMISS TO START CALM SOLVING"
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = if (failedCheckIndex > 0) ThreatCrimson else LaserGreen,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(60.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(240.dp)
                        .scale(pulseScale),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = ThreatCrimson)
                ) {
                    Text(
                        text = if (hasQr) "DISMISS &\nSCAN DESK QR" else "DISMISS &\nSOLVE MISSION",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = TextPrimary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    @Composable
    private fun InitialAlarmQrScannerScreen(
        expectedPayload: String,
        onVerified: () -> Unit
    ) {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        var mismatchWarning by remember { mutableStateOf<String?>(null) }

        CameraQrScannerView(
            headerTitle = "DESK ANCHOR SCANNER",
            subTitle = "SCAN REGISTERED DESK QR TO UNLOCK MATH MISSION",
            mismatchWarning = mismatchWarning,
            onScanned = { scannedData ->
                if (scannedData.trim() == expectedPayload.trim()) {
                    vibrateSuccess(vibrator)
                    onVerified()
                } else {
                    vibrateError(vibrator)
                    mismatchWarning = "TARGET MISMATCH // SCAN REGISTERED DESK QR"
                }
            }
        )
    }

    @Composable
    private fun WakeUpQrScannerScreen(
        currentCheck: Int,
        totalChecks: Int,
        expectedPayload: String,
        onVerified: () -> Unit,
        onTimeout: () -> Unit
    ) {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        var checkTimeLeft by remember { mutableIntStateOf(45) }
        var mismatchWarning by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(Unit) {
            while (checkTimeLeft > 0) {
                delay(1000L)
                checkTimeLeft--
            }
            onTimeout()
        }

        CameraQrScannerView(
            headerTitle = "CHECK $currentCheck OF $totalChecks // QR REQUIRED",
            subTitle = "REMAINING TIME: ${checkTimeLeft}s (ALIGN WITH DESK QR)",
            timeLeftRatio = checkTimeLeft / 45f,
            mismatchWarning = mismatchWarning,
            onScanned = { scannedData ->
                if (scannedData.trim() == expectedPayload.trim()) {
                    vibrateSuccess(vibrator)
                    onVerified()
                } else {
                    vibrateError(vibrator)
                    mismatchWarning = "TARGET MISMATCH // SCAN REGISTERED DESK QR"
                }
            }
        )
    }

    @Composable
    private fun CameraQrScannerView(
        headerTitle: String,
        subTitle: String,
        timeLeftRatio: Float? = null,
        mismatchWarning: String? = null,
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

        var isTorchOn by remember { mutableStateOf(true) }
        var cameraControl by remember { mutableStateOf<CameraControl?>(null) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(PureBlack)
        ) {
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
                                processImageProxyInternal(scanner, imageProxy, onScanned)
                            }

                            try {
                                cameraProvider.unbindAll()
                                val cam = cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    analysis
                                )
                                cameraControl = cam.cameraControl
                                cam.cameraControl.enableTorch(true)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Reticle Overlay
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(260.dp)
                            .border(
                                width = 2.dp,
                                color = if (mismatchWarning != null) ThreatCrimson else LaserGreen,
                                shape = RoundedCornerShape(16.dp)
                            )
                    )
                }

                // Controls & HUD
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = headerTitle,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = LaserGreen,
                        letterSpacing = 1.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = subTitle,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = TextPrimary,
                        textAlign = TextAlign.Center
                    )

                    if (timeLeftRatio != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { timeLeftRatio },
                            modifier = Modifier
                                .fillMaxWidth(0.7f)
                                .height(4.dp),
                            color = if (timeLeftRatio <= 0.25f) ThreatCrimson else LaserGreen,
                            trackColor = SubtleBorder
                        )
                    }

                    if (mismatchWarning != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = mismatchWarning,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = ThreatCrimson,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    IconButton(
                        onClick = {
                            isTorchOn = !isTorchOn
                            cameraControl?.enableTorch(isTorchOn)
                        },
                        modifier = Modifier
                            .size(56.dp)
                            .background(DarkSurface, CircleShape)
                            .border(1.dp, SubtleBorder, CircleShape)
                    ) {
                        Icon(
                            imageVector = if (isTorchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                            contentDescription = "Torch Toggle",
                            tint = if (isTorchOn) LaserGreen else TextMuted
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (isTorchOn) "TORCH ACTIVE" else "TORCH OFF",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = TextMuted
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "CAMERA PERMISSION REQUIRED",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = ThreatCrimson
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Wakelock requires camera access to scan your registered Desk QR.",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                        colors = ButtonDefaults.buttonColors(containerColor = LaserGreen)
                    ) {
                        Text("GRANT CAMERA ACCESS", color = PureBlack, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    private fun processImageProxyInternal(
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

    @Composable
    private fun WakeUpCheckScreen(currentCheck: Int, totalChecks: Int, onTimeout: () -> Unit) {
        val remainingChecks = (totalChecks - currentCheck).coerceAtLeast(0)
        var checkTimeLeft by remember { mutableIntStateOf(30) }

        LaunchedEffect(Unit) {
            while (checkTimeLeft > 0) {
                delay(1000L)
                checkTimeLeft--
            }
            onTimeout()
        }

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
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "CHECK $currentCheck OF $totalChecks",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "REMAINING CHECKS: $remainingChecks",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = WarningAmber
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "TIME TO CONFIRM: ${checkTimeLeft}s",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (checkTimeLeft <= 10) ThreatCrimson else LaserGreen
                )
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { checkTimeLeft / 30f },
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(4.dp),
                    color = if (checkTimeLeft <= 10) ThreatCrimson else LaserGreen,
                    trackColor = SubtleBorder
                )

                Spacer(modifier = Modifier.height(50.dp))

                Button(
                    onClick = {
                        confirmAwakeSuccess()
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
        
        val baseRounds = if (isPunishment) 1 else alarm.roundsCount
        val multiplier = when {
            isPunishment -> 1
            failedCheckIndex > 0 -> (failedCheckIndex + 2)
            else -> 1
        }
        val totalRounds = (baseRounds * multiplier).coerceAtLeast(1)

        val difficulty = if (isPunishment) MissionDifficulty.SEVEN_DIGIT else alarm.missionDifficulty
        var problem by remember { mutableStateOf(MathMissionGenerator.generateProblem(difficulty)) }
        var userInput by remember { mutableStateOf("") }
        var feedbackMessage by remember { mutableStateOf<String?>(null) }
        var isAnswerCorrect by remember { mutableStateOf(false) }
        var showPinDialog by remember { mutableStateOf(false) }

        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        var activityWatchdogKey by remember { mutableIntStateOf(0) }
        var timeLeft by remember { mutableIntStateOf(alarm.timeoutSeconds) }
        var isAlarmCurrentlyBlasting by remember { mutableStateOf(false) }

        LaunchedEffect(currentRound, problem, activityWatchdogKey) {
            timeLeft = alarm.timeoutSeconds
            while (timeLeft > 0) {
                delay(1000L)
                timeLeft--
            }

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

        fun onUserActivityInput(digit: String? = null, isBackspace: Boolean = false) {
            if (isAlarmCurrentlyBlasting) {
                val muteIntent = Intent(this@AlarmTriggerActivity, AlarmForegroundService::class.java).apply {
                    action = AlarmForegroundService.ACTION_MUTE_TEMPORARY
                }
                startService(muteIntent)
                isAlarmCurrentlyBlasting = false
            }

            feedbackMessage = null

            if (isBackspace) {
                if (userInput.isNotEmpty()) {
                    userInput = userInput.dropLast(1)
                }
            } else if (digit != null) {
                if (userInput.length < 15) {
                    userInput += digit
                }
            }

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
                        color = if (isPunishment || multiplier > 1) ThreatCrimson else LaserGreen
                    )
                    Text(
                        text = "${timeLeft}s",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = if (timeLeft <= 10) ThreatCrimson else TextPrimary
                    )
                }

                if (failedCheckIndex > 0 && !isPunishment) {
                    Text(
                        text = "CHECK #$failedCheckIndex MISSED // ${multiplier}X PENALTY ($totalRounds ROUNDS)",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        color = ThreatCrimson,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { timeLeft.toFloat() / alarm.timeoutSeconds.toFloat() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = if (isPunishment || timeLeft <= 10 || multiplier > 1) ThreatCrimson else LaserGreen,
                    trackColor = SubtleBorder
                )

                Text(
                    text = if (isAlarmCurrentlyBlasting) "PRESS ANY KEY TO MUTE" else "TYPE TO MAINTAIN CALM // 30s INACTIVITY LIMIT",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isAlarmCurrentlyBlasting) ThreatCrimson else LaserGreen,
                    modifier = Modifier.padding(top = 6.dp)
                )

                Spacer(modifier = Modifier.height(14.dp))

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

                NumericKeypad(
                    onDigit = { digit -> onUserActivityInput(digit = digit) },
                    onBackspace = { onUserActivityInput(isBackspace = true) },
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
                        isLockdownActive = false
                        val prefs = getSharedPreferences("wakelock_prefs", Context.MODE_PRIVATE)
                        prefs.edit()
                            .putBoolean("is_lockdown_active", false)
                            .putInt("failed_check_index", 0)
                            .apply()
                        failedCheckIndex = 0

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
        isLockdownActive = false
        val prefs = getSharedPreferences("wakelock_prefs", Context.MODE_PRIVATE)
        val wasPenalty = failedCheckIndex > 0

        prefs.edit()
            .putBoolean("is_lockdown_active", false)
            .putInt("failed_check_index", 0)
            .apply()
        failedCheckIndex = 0

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

            if (wasPenalty) {
                // RESUME ACTION prevents anti-hijack reject and restores check timers
                val resumeIntent = Intent(this, WakeUpSurveillanceService::class.java).apply {
                    action = WakeUpSurveillanceService.ACTION_RESUME_FROM_PENALTY
                    putExtra(WakeUpSurveillanceService.EXTRA_ALARM_ID, alarm.id)
                }
                startService(resumeIntent)
            } else if (alarm.wakeUpTotalDurationSeconds > 0) {
                val surveillanceIntent = Intent(this, WakeUpSurveillanceService::class.java).apply {
                    action = WakeUpSurveillanceService.ACTION_START_SURVEILLANCE
                    putExtra(WakeUpSurveillanceService.EXTRA_ALARM_ID, alarm.id)
                }
                startService(surveillanceIntent)
            }
            finish()
        }
    }

    private fun vibrateSuccess(vibrator: Vibrator) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator.vibrate(120)
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
    override fun onBackPressed() {}
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
