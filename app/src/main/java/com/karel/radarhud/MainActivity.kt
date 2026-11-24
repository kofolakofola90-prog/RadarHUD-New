package com.karel.radarhud

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.*

// ==========================================
// DATA MODELS
// ==========================================

data class RadarPoint(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val maxspeed: Int? = null,
    val direction: Int? = null
)

enum class AlertMode(val label: String) {
    VOICE("Hlas (TTS) + Vibrace"),
    VIBRATION("Pouze vibrace"),
    SCREEN_ONLY("Jen obrazovka (tichý)")
}

enum class AlarmType {
    POLICE,
    SECRET_POLICE,
    RADAR
}

data class ActiveAlarm(
    val type: AlarmType,
    val title: String,
    val subtitle: String,
    val detail: String,
    val timestamp: Long = System.currentTimeMillis()
)

// ==========================================
// MAIN ACTIVITY
// ==========================================

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("radar_hud_prefs", Context.MODE_PRIVATE)
        tts = TextToSpeech(this, this)

        setContent {
            RadarHUDApp(
                prefs = prefs,
                onSpeak = { text, mode -> triggerVoiceAlert(text, mode) },
                onVibrate = { mode -> triggerVibration(mode) },
                setKeepScreenOn = { on ->
                    if (on) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            )
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("cs", "CZ"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.getDefault())
            }
            ttsReady = true
        }
    }

    private fun triggerVoiceAlert(text: String, mode: AlertMode) {
        if (mode == AlertMode.VOICE && ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ALERT_ID")
        }
    }

    private fun triggerVibration(mode: AlertMode) {
        if (mode == AlertMode.SCREEN_ONLY) return
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

        vibrator?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                it.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200, 100, 300), -1))
            } else {
                @Suppress("DEPRECATION")
                it.vibrate(longArrayOf(0, 200, 100, 200, 100, 300), -1)
            }
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

// ==========================================
// UTILITY FUNCTIONS: Levenshtein & Haversine
// ==========================================

fun levenshteinDistance(s1: String, s2: String): Int {
    val a = s1.trim().uppercase()
    val b = s2.trim().uppercase()
    val dp = Array(a.length + 1) { IntArray(b.length + 1) }
    for (i in 0..a.length) dp[i][0] = i
    for (j in 0..b.length) dp[0][j] = j
    for (i in 1..a.length) {
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            dp[i][j] = minOf(
                dp[i - 1][j] + 1,
                dp[i][j - 1] + 1,
                dp[i - 1][j - 1] + cost
            )
        }
    }
    return dp[a.length][b.length]
}

fun calculateHaversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val earthRadius = 6371000.0 // meters
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2).pow(2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return earthRadius * c
}

// ==========================================
// MAIN COMPOSABLE APP
// ==========================================

@Composable
fun RadarHUDApp(
    prefs: SharedPreferences,
    onSpeak: (String, AlertMode) -> Unit,
    onVibrate: (AlertMode) -> Unit,
    setKeepScreenOn: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var isDriving by remember { mutableStateOf(false) }

    // Alert settings
    var alertMode by remember {
        val saved = prefs.getString("alert_mode", AlertMode.VOICE.name) ?: AlertMode.VOICE.name
        mutableStateOf(AlertMode.valueOf(saved))
    }

    // SPZ lists
    val defaultPolice = setOf("POLICIE", "POLICE")
    val defaultSecret = setOf("1P23456", "5M89012", "4M12345")

    var policePlates by remember {
        val saved = prefs.getStringSet("spz_police", defaultPolice) ?: defaultPolice
        mutableStateOf(saved.toSet())
    }
    var secretPlates by remember {
        val saved = prefs.getStringSet("spz_secret", defaultSecret) ?: defaultSecret
        mutableStateOf(saved.toSet())
    }

    // Radars database
    var radars by remember { mutableStateOf<List<RadarPoint>>(emptyList()) }
    var loadedRadarCount by remember { mutableIntStateOf(0) }

    // Load initial radars
    LaunchedEffect(Unit) {
        val loaded = loadRadarsFromAssets(context, "radary_cz.json")
        radars = loaded
        loadedRadarCount = loaded.size
    }

    // JSON file picker
    val jsonPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val customRadars = loadRadarsFromUri(context, it)
            if (customRadars.isNotEmpty()) {
                radars = customRadars
                loadedRadarCount = customRadars.size
                onSpeak("Načteno ${customRadars.size} vlastních radarů", alertMode)
            }
        }
    }

    // Dialogs
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showSpzManagerDialog by remember { mutableStateOf(false) }

    // GPS location & speed
    var currentSpeedKmH by remember { mutableFloatStateOf(0f) }
    var userLocation by remember { mutableStateOf<Location?>(null) }
    var nearestRadar by remember { mutableStateOf<RadarPoint?>(null) }
    var distanceToRadar by remember { mutableStateOf<Double?>(null) }

    // Active alarm
    var activeAlarm by remember { mutableStateOf<ActiveAlarm?>(null) }
    val coroutineScope = rememberCoroutineScope()

    // Trigger Alarm Helper
    fun triggerAlarm(alarm: ActiveAlarm) {
        activeAlarm = alarm
        onVibrate(alertMode)
        val speakText = when (alarm.type) {
            AlarmType.POLICE -> "Pozor, Policie České republiky! Registrační značka ${alarm.detail}"
            AlarmType.SECRET_POLICE -> "Varování! Tajná policejní hlídka, značka ${alarm.detail}"
            AlarmType.RADAR -> "Pozor! Rychlostní radar. Vzdálenost ${alarm.detail}. Zpomalte."
        }
        onSpeak(speakText, alertMode)

        coroutineScope.launch {
            delay(4000)
            if (activeAlarm == alarm) {
                activeAlarm = null
            }
        }
    }

    // Location Updates when driving
    var lastRadarAlertTimestamp by remember { mutableLongStateOf(0L) }

    DisposableEffect(isDriving) {
        setKeepScreenOn(isDriving)
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                userLocation = loc
                currentSpeedKmH = if (loc.hasSpeed()) loc.speed * 3.6f else 0f

                // Find nearest radar
                if (radars.isNotEmpty()) {
                    var minDistance = Double.MAX_VALUE
                    var closest: RadarPoint? = null
                    for (r in radars) {
                        val dist = calculateHaversineDistance(loc.latitude, loc.longitude, r.lat, r.lon)
                        if (dist < minDistance) {
                            minDistance = dist
                            closest = r
                        }
                    }
                    nearestRadar = closest
                    distanceToRadar = minDistance

                    // Radar proximity warning (< 500m)
                    if (minDistance < 500.0) {
                        val now = System.currentTimeMillis()
                        if (now - lastRadarAlertTimestamp > 15000) { // Cooldown 15s
                            lastRadarAlertTimestamp = now
                            val limitStr = closest?.maxspeed?.let { "Limit $it km/h" } ?: "Bez limitu"
                            triggerAlarm(
                                ActiveAlarm(
                                    type = AlarmType.RADAR,
                                    title = "POZOR RADAR!",
                                    subtitle = limitStr,
                                    detail = "${minDistance.toInt()} metrů"
                                )
                            )
                        }
                    }
                }
            }
        }

        if (isDriving) {
            val permissionCheck = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            )
            if (permissionCheck == PackageManager.PERMISSION_GRANTED) {
                val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
                    .setMinUpdateIntervalMillis(500)
                    .build()
                fusedLocationClient.requestLocationUpdates(request, locationCallback, context.mainLooper)
            }
        }

        onDispose {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            setKeepScreenOn(false)
        }
    }

    // UI Structure
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color(0xFF0A0E17),
            surface = Color(0xFF111827),
            primary = Color(0xFF00E676),
            secondary = Color(0xFF00E5FF),
            error = Color(0xFFFF1744)
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            if (!isDriving) {
                HomeScreen(
                    radarCount = loadedRadarCount,
                    policeCount = policePlates.size,
                    secretCount = secretPlates.size,
                    alertMode = alertMode,
                    onStartDrive = { isDriving = true },
                    onOpenSettings = { showSettingsDialog = true },
                    onOpenSpzManager = { showSpzManagerDialog = true },
                    onPickJson = { jsonPickerLauncher.launch("application/json") }
                )
            } else {
                DrivingHUDScreen(
                    currentSpeedKmH = currentSpeedKmH,
                    nearestRadar = nearestRadar,
                    distanceToRadarMeters = distanceToRadar,
                    activeAlarm = activeAlarm,
                    policePlates = policePlates,
                    secretPlates = secretPlates,
                    onPlateDetected = { plate, type ->
                        val now = System.currentTimeMillis()
                        val isRecent = activeAlarm?.let {
                            it.detail == plate && (now - it.timestamp < 10000)
                        } ?: false
                        if (!isRecent) {
                            val title = if (type == AlarmType.POLICE) "POLICIE ČR" else "SECRET POLICIE"
                            val sub = if (type == AlarmType.POLICE) "Oficiální vozidlo" else "Skrytý radarový speciál"
                            triggerAlarm(ActiveAlarm(type, title, sub, plate, now))
                        }
                    },
                    onTestAlarm = { type ->
                        when (type) {
                            AlarmType.POLICE -> triggerAlarm(
                                ActiveAlarm(AlarmType.POLICE, "TEST: POLICIE ČR", "Bílé označené auto", "POLICIE")
                            )
                            AlarmType.SECRET_POLICE -> triggerAlarm(
                                ActiveAlarm(AlarmType.SECRET_POLICE, "TEST: TAJNÁ POLICIE", "Černé civilní auto s majáky", "1P23456")
                            )
                            AlarmType.RADAR -> triggerAlarm(
                                ActiveAlarm(AlarmType.RADAR, "TEST: STACIONÁRNÍ RADAR", "Limit 50 km/h", "350 m")
                            )
                        }
                    },
                    onExitDrive = { isDriving = false }
                )
            }

            // Settings Dialog
            if (showSettingsDialog) {
                AlertDialog(
                    onDismissRequest = { showSettingsDialog = false },
                    title = { Text("Režim upozornění", fontWeight = FontWeight.Bold) },
                    text = {
                        Column {
                            AlertMode.values().forEach { mode ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            alertMode = mode
                                            prefs.edit().putString("alert_mode", mode.name).apply()
                                            showSettingsDialog = false
                                        }
                                        .padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = (alertMode == mode),
                                        onClick = {
                                            alertMode = mode
                                            prefs.edit().putString("alert_mode", mode.name).apply()
                                            showSettingsDialog = false
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(mode.label, color = Color.White)
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showSettingsDialog = false }) {
                            Text("Zavřít")
                        }
                    }
                )
            }

            // SPZ Manager Dialog
            if (showSpzManagerDialog) {
                SpzManagerDialog(
                    policePlates = policePlates,
                    secretPlates = secretPlates,
                    onSave = { newPolice, newSecret ->
                        policePlates = newPolice
                        secretPlates = newSecret
                        prefs.edit()
                            .putStringSet("spz_police", newPolice)
                            .putStringSet("spz_secret", newSecret)
                            .apply()
                        showSpzManagerDialog = false
                    },
                    onDismiss = { showSpzManagerDialog = false }
                )
            }
        }
    }
}

// ==========================================
// HOME SCREEN
// ==========================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    radarCount: Int,
    policeCount: Int,
    secretCount: Int,
    alertMode: AlertMode,
    onStartDrive: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSpzManager: () -> Unit,
    onPickJson: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        val locGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        if (cameraGranted && locGranted) {
            onStartDrive()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            tint = Color(0xFF00E676),
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Radar & SPZ HUD",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 20.sp
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Menu",
                            tint = Color.White
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Režim upozornění") },
                            leadingIcon = { Icon(Icons.Default.Notifications, null) },
                            onClick = {
                                showMenu = false
                                onOpenSettings()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Nahrát JSON radarů") },
                            leadingIcon = { Icon(Icons.Default.UploadFile, null) },
                            onClick = {
                                showMenu = false
                                onPickJson()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Správa sledovaných SPZ") },
                            leadingIcon = { Icon(Icons.Default.DirectionsCar, null) },
                            onClick = {
                                showMenu = false
                                onOpenSpzManager()
                            }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF111827)
                )
            )
        },
        containerColor = Color(0xFF0A0E17)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Stats Section
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "SYSTÉMOVÝ PŘEHLED",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF9CA3AF),
                    letterSpacing = 1.5.sp
                )

                // Radars card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2937)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .background(Color(0xFFE65100).copy(alpha = 0.2f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CameraAlt,
                                contentDescription = null,
                                tint = Color(0xFFFF9800),
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = "Stacionární radary v ČR",
                                color = Color(0xFF9CA3AF),
                                fontSize = 13.sp
                            )
                            Text(
                                text = "$radarCount aktivních bodů",
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // SPZ card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2937)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .background(Color(0xFF0D47A1).copy(alpha = 0.2f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocalPolice,
                                contentDescription = null,
                                tint = Color(0xFF2196F3),
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = "Sledované SPZ hlídek",
                                color = Color(0xFF9CA3AF),
                                fontSize = 13.sp
                            )
                            Text(
                                text = "Policie ($policeCount) • Secret ($secretCount)",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Mode card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2937)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .background(Color(0xFF00E676).copy(alpha = 0.2f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.VolumeUp,
                                contentDescription = null,
                                tint = Color(0xFF00E676),
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = "Upozornění",
                                color = Color(0xFF9CA3AF),
                                fontSize = 13.sp
                            )
                            Text(
                                text = alertMode.label,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // START DRIVE BUTTON
            Button(
                onClick = {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.CAMERA,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00E676)
                ),
                shape = RoundedCornerShape(20.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Navigation,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(30.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "SPUSTIT JÍZDU",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.Black,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}

// ==========================================
// DRIVING HUD SCREEN (CameraX, OCR, GPS HUD)
// ==========================================

@Composable
fun DrivingHUDScreen(
    currentSpeedKmH: Float,
    nearestRadar: RadarPoint?,
    distanceToRadarMeters: Double?,
    activeAlarm: ActiveAlarm?,
    policePlates: Set<String>,
    secretPlates: Set<String>,
    onPlateDetected: (String, AlarmType) -> Unit,
    onTestAlarm: (AlarmType) -> Unit,
    onExitDrive: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var testMenuExpanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. CameraX PreviewView + Offline ML Kit OCR Analyzer
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                val executor = Executors.newSingleThreadExecutor()
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

                var lastAnalysisTimestamp = 0L

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    imageAnalysis.setAnalyzer(executor) { imageProxy ->
                        val currentTime = System.currentTimeMillis()
                        // 250 ms throttle
                        if (currentTime - lastAnalysisTimestamp < 250) {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        lastAnalysisTimestamp = currentTime

                        processImageProxy(imageProxy, recognizer) { detectedText ->
                            val cleaned = detectedText.uppercase().replace(Regex("[^A-Z0-9]"), "")
                            if (cleaned.length in 5..9) {
                                // Check Police
                                for (p in policePlates) {
                                    if (levenshteinDistance(cleaned, p) <= 1) {
                                        onPlateDetected(cleaned, AlarmType.POLICE)
                                        return@processImageProxy
                                    }
                                }
                                // Check Secret Police
                                for (s in secretPlates) {
                                    if (levenshteinDistance(cleaned, s) <= 1) {
                                        onPlateDetected(cleaned, AlarmType.SECRET_POLICE)
                                        return@processImageProxy
                                    }
                                }
                            }
                        }
                    }

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalysis
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. HUD Crosshair / Target Overlay in Center
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val boxW = 280.dp.toPx()
            val boxH = 90.dp.toPx()
            val left = cx - boxW / 2f
            val top = cy - boxH / 2f
            val cornerLen = 24.dp.toPx()
            val color = Color(0xFF00E5FF).copy(alpha = 0.8f)
            val stroke = Stroke(width = 3.dp.toPx())

            // Corner brackets
            // Top-Left
            drawLine(color, Offset(left, top), Offset(left + cornerLen, top), stroke.width)
            drawLine(color, Offset(left, top), Offset(left, top + cornerLen), stroke.width)
            // Top-Right
            drawLine(color, Offset(left + boxW, top), Offset(left + boxW - cornerLen, top), stroke.width)
            drawLine(color, Offset(left + boxW, top), Offset(left + boxW, top + cornerLen), stroke.width)
            // Bottom-Left
            drawLine(color, Offset(left, top + boxH), Offset(left + cornerLen, top + boxH), stroke.width)
            drawLine(color, Offset(left, top + boxH), Offset(left, top + boxH - cornerLen), stroke.width)
            // Bottom-Right
            drawLine(color, Offset(left + boxW, top + boxH), Offset(left + boxW - cornerLen, top + boxH), stroke.width)
            drawLine(color, Offset(left + boxW, top + boxH), Offset(left + boxW, top + boxH - cornerLen), stroke.width)

            // Center target dot
            drawCircle(Color(0xFF00E676).copy(alpha = 0.7f), radius = 3.dp.toPx(), center = Offset(cx, cy))
        }

        // 3. Top Controls (TEST button & EXIT button)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 40.dp, start = 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // TEST Button
            Box {
                Button(
                    onClick = { testMenuExpanded = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC1F2937)),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E5FF)),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.BugReport,
                        contentDescription = "Test",
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("TEST", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                DropdownMenu(
                    expanded = testMenuExpanded,
                    onDismissRequest = { testMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Alarm: Policie ČR") },
                        leadingIcon = { Icon(Icons.Default.LocalPolice, null, tint = Color(0xFF2196F3)) },
                        onClick = {
                            testMenuExpanded = false
                            onTestAlarm(AlarmType.POLICE)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Alarm: Tajná policie") },
                        leadingIcon = { Icon(Icons.Default.DirectionsCar, null, tint = Color(0xFFFF1744)) },
                        onClick = {
                            testMenuExpanded = false
                            onTestAlarm(AlarmType.SECRET_POLICE)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Alarm: Radar") },
                        leadingIcon = { Icon(Icons.Default.CameraAlt, null, tint = Color(0xFFFF9800)) },
                        onClick = {
                            testMenuExpanded = false
                            onTestAlarm(AlarmType.RADAR)
                        }
                    )
                }
            }

            // Exit Driving HUD
            IconButton(
                onClick = onExitDrive,
                modifier = Modifier
                    .size(44.dp)
                    .background(Color(0xCC1F2937), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Ukončit",
                    tint = Color.White
                )
            }
        }

        // 4. TOP VISUAL WARNING CARD WITH CANVAS GRAPHICS
        AnimatedVisibility(
            visible = activeAlarm != null,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 95.dp, start = 16.dp, end = 16.dp)
        ) {
            activeAlarm?.let { alarm ->
                WarningCard(alarm = alarm)
            }
        }

        // 5. LEFT RADAR DISTANCE PANEL
        val isProximity = (distanceToRadarMeters != null && distanceToRadarMeters < 500.0)
        val radarPanelColor = if (isProximity) Color(0xEEFF6F00) else Color(0xCC111827)
        val radarBorderColor = if (isProximity) Color(0xFFFFD54F) else Color(0xFF374151)

        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 16.dp)
                .background(radarPanelColor, RoundedCornerShape(16.dp))
                .border(2.dp, radarBorderColor, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = "Radar",
                    tint = if (isProximity) Color.White else Color(0xFFFFB300),
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                if (distanceToRadarMeters != null) {
                    val distInt = distanceToRadarMeters.toInt()
                    Text(
                        text = if (distInt < 1000) "$distInt m" else String.format("%.1f km", distInt / 1000.0),
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace
                    )
                } else {
                    Text(
                        text = "-- m",
                        color = Color(0xFF9CA3AF),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "RADAR",
                    color = if (isProximity) Color.White else Color(0xFF9CA3AF),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        }

        // 6. BOTTOM HUD SPEED & SPEED LIMIT ROAD SIGN
        val maxspeed = nearestRadar?.maxspeed
        val isSpeeding = maxspeed != null && currentSpeedKmH > maxspeed
        val speedTextColor = if (isSpeeding) Color(0xFFFF1744) else Color(0xFF00E676)

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 28.dp)
                .background(Color(0xD90A0E17), RoundedCornerShape(24.dp))
                .border(1.dp, Color(0xFF1F2937), RoundedCornerShape(24.dp))
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Speedometer
            Column {
                Text(
                    text = "RYCHLOST",
                    color = Color(0xFF9CA3AF),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "${currentSpeedKmH.roundToInt()}",
                        color = speedTextColor,
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "km/h",
                        color = Color(0xFF9CA3AF),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }

            // Right: Speed Limit Traffic Sign (European Circular White Disc with Red Border)
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(Color.White, CircleShape)
                    .border(7.dp, Color(0xFFD50000), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = maxspeed?.toString() ?: "--",
                    color = Color.Black,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.SansSerif
                )
            }
        }
    }
}

// ==========================================
// WARNING CARDS (Canvas Drawing for Police & Radars)
// ==========================================

@Composable
fun WarningCard(alarm: ActiveAlarm) {
    val (gradientColors, borderColor) = when (alarm.type) {
        AlarmType.POLICE -> Pair(
            listOf(Color(0xFF0D47A1), Color(0xFF1565C0)),
            Color(0xFF64B5F6)
        )
        AlarmType.SECRET_POLICE -> Pair(
            listOf(Color(0xFFB71C1C), Color(0xFFC62828)),
            Color(0xFFFF8A80)
        )
        AlarmType.RADAR -> Pair(
            listOf(Color(0xFFE65100), Color(0xFFEF6C00)),
            Color(0xFFFFD54F)
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, borderColor, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .background(Brush.horizontalGradient(gradientColors))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Dynamic Canvas Icon based on Alarm Type
            Box(
                modifier = Modifier
                    .size(70.dp)
                    .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(54.dp)) {
                    when (alarm.type) {
                        AlarmType.POLICE -> drawWhitePoliceCar(this)
                        AlarmType.SECRET_POLICE -> drawSecretPoliceCar(this)
                        AlarmType.RADAR -> drawSpeedRadarPole(this)
                    }
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = alarm.title,
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 17.sp,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = alarm.subtitle,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = alarm.detail,
                    color = Color(0xFFFFF9C4),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

// Canvas Drawings:
private fun drawWhitePoliceCar(scope: androidx.compose.ui.graphics.drawscope.DrawScope) {
    scope.apply {
        val w = size.width
        val h = size.height

        // Wheels
        drawCircle(Color.DarkGray, radius = w * 0.12f, center = Offset(w * 0.28f, h * 0.78f))
        drawCircle(Color.DarkGray, radius = w * 0.12f, center = Offset(w * 0.72f, h * 0.78f))

        // White Car Body
        val bodyPath = Path().apply {
            moveTo(w * 0.10f, h * 0.68f)
            lineTo(w * 0.90f, h * 0.68f)
            lineTo(w * 0.88f, h * 0.50f)
            lineTo(w * 0.70f, h * 0.48f)
            lineTo(w * 0.58f, h * 0.28f)
            lineTo(w * 0.36f, h * 0.28f)
            lineTo(w * 0.25f, h * 0.48f)
            lineTo(w * 0.10f, h * 0.52f)
            close()
        }
        drawPath(bodyPath, Color.White, style = Fill)
        drawPath(bodyPath, Color.Black, style = Stroke(width = 2f))

        // Blue police stripe
        drawRect(Color(0xFF1565C0), topLeft = Offset(w * 0.10f, h * 0.54f), size = Size(w * 0.78f, h * 0.08f))

        // Roof Flasher (Red / Blue)
        drawRoundRect(Color(0xFF1976D2), Offset(w * 0.40f, h * 0.18f), Size(w * 0.09f, h * 0.09f), CornerRadius(3f, 3f))
        drawRoundRect(Color(0xFFD50000), Offset(w * 0.51f, h * 0.18f), Size(w * 0.09f, h * 0.09f), CornerRadius(3f, 3f))
    }
}

private fun drawSecretPoliceCar(scope: androidx.compose.ui.graphics.drawscope.DrawScope) {
    scope.apply {
        val w = size.width
        val h = size.height

        // Wheels
        drawCircle(Color.Black, radius = w * 0.12f, center = Offset(w * 0.28f, h * 0.78f))
        drawCircle(Color.Black, radius = w * 0.12f, center = Offset(w * 0.72f, h * 0.78f))

        // Black / Dark Metallic Car Body
        val bodyPath = Path().apply {
            moveTo(w * 0.10f, h * 0.68f)
            lineTo(w * 0.90f, h * 0.68f)
            lineTo(w * 0.88f, h * 0.50f)
            lineTo(w * 0.70f, h * 0.46f)
            lineTo(w * 0.58f, h * 0.28f)
            lineTo(w * 0.36f, h * 0.28f)
            lineTo(w * 0.25f, h * 0.48f)
            lineTo(w * 0.10f, h * 0.52f)
            close()
        }
        drawPath(bodyPath, Color(0xFF212121), style = Fill)
        drawPath(bodyPath, Color(0xFF424242), style = Stroke(width = 2.5f))

        // Tinted windows
        val windowPath = Path().apply {
            moveTo(w * 0.28f, h * 0.48f)
            lineTo(w * 0.38f, h * 0.32f)
            lineTo(w * 0.56f, h * 0.32f)
            lineTo(w * 0.67f, h * 0.48f)
            close()
        }
        drawPath(windowPath, Color(0xFF111111), style = Fill)

        // Hidden Grille Flashers (Flashing effect)
        drawCircle(Color(0xFF00E5FF), radius = 4f, center = Offset(w * 0.13f, h * 0.58f))
        drawCircle(Color(0xFFFF1744), radius = 4f, center = Offset(w * 0.19f, h * 0.58f))
    }
}

private fun drawSpeedRadarPole(scope: androidx.compose.ui.graphics.drawscope.DrawScope) {
    scope.apply {
        val w = size.width
        val h = size.height

        // Mounting Pole
        drawRect(Color(0xFFB0BEC5), topLeft = Offset(w * 0.44f, h * 0.40f), size = Size(w * 0.12f, h * 0.55f))

        // Radar Box on Top
        drawRoundRect(
            Color(0xFF37474F),
            topLeft = Offset(w * 0.30f, h * 0.15f),
            size = Size(w * 0.40f, h * 0.35f),
            cornerRadius = CornerRadius(6f, 6f)
        )

        // Camera lens
        drawCircle(Color.Black, radius = w * 0.08f, center = Offset(w * 0.50f, h * 0.26f))
        drawCircle(Color(0xFF00E5FF), radius = w * 0.04f, center = Offset(w * 0.50f, h * 0.26f))

        // Radar Flash / Sensor
        drawRect(Color(0xFFFFD54F), topLeft = Offset(w * 0.42f, h * 0.38f), size = Size(w * 0.16f, h * 0.06f))

        // Signal waves
        drawArc(
            color = Color(0xFFFF9800),
            startAngle = -45f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(w * 0.72f, h * 0.16f),
            size = Size(w * 0.22f, h * 0.22f),
            style = Stroke(width = 3f)
        )
    }
}

// ==========================================
// CAMERA OCR PROCESSING HELPER
// ==========================================

@OptIn(ExperimentalGetImage::class)
private fun processImageProxy(
    imageProxy: ImageProxy,
    recognizer: com.google.mlkit.vision.text.TextRecognizer,
    onResult: (String) -> Unit
) {
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                for (block in visionText.textBlocks) {
                    for (line in block.lines) {
                        onResult(line.text)
                    }
                }
            }
            .addOnFailureListener {
                // ignore OCR frame errors
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    } else {
        imageProxy.close()
    }
}

// ==========================================
// RADAR JSON PARSING (Overpass & GeoJSON)
// ==========================================

fun loadRadarsFromAssets(context: Context, filename: String): List<RadarPoint> {
    return try {
        context.assets.open(filename).use { stream ->
            parseRadarsJson(stream.bufferedReader().use { it.readText() })
        }
    } catch (e: Exception) {
        e.printStackTrace()
        emptyList()
    }
}

fun loadRadarsFromUri(context: Context, uri: Uri): List<RadarPoint> {
    return try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            parseRadarsJson(stream.bufferedReader().use { it.readText() })
        } ?: emptyList()
    } catch (e: Exception) {
        e.printStackTrace()
        emptyList()
    }
}

fun parseRadarsJson(jsonStr: String): List<RadarPoint> {
    val list = mutableListOf<RadarPoint>()
    try {
        val root = JSONObject(jsonStr)

        // 1. Overpass format: { "elements": [ { "id": 123, "lat": 50.1, "lon": 14.4, "tags": { "maxspeed": "50" } } ] }
        if (root.has("elements")) {
            val elements = root.getJSONArray("elements")
            for (i in 0 until elements.length()) {
                val el = elements.getJSONObject(i)
                val id = el.optLong("id", i.toLong())
                val lat = el.optDouble("lat", Double.NaN)
                val lon = el.optDouble("lon", Double.NaN)
                if (!lat.isNaN() && !lon.isNaN()) {
                    var maxspeed: Int? = null
                    var direction: Int? = null
                    if (el.has("tags")) {
                        val tags = el.getJSONObject("tags")
                        maxspeed = tags.optString("maxspeed", "").filter { it.isDigit() }.toIntOrNull()
                        direction = tags.optString("direction", "").filter { it.isDigit() }.toIntOrNull()
                    }
                    list.add(RadarPoint(id, lat, lon, maxspeed, direction))
                }
            }
        }
        // 2. GeoJSON format: { "features": [ { "geometry": { "coordinates": [lon, lat] }, "properties": { "maxspeed": "50" } } ] }
        else if (root.has("features")) {
            val features = root.getJSONArray("features")
            for (i in 0 until features.length()) {
                val f = features.getJSONObject(i)
                val geom = f.optJSONObject("geometry")
                val coords = geom?.optJSONArray("coordinates")
                if (coords != null && coords.length() >= 2) {
                    val lon = coords.getDouble(0)
                    val lat = coords.getDouble(1)
                    val props = f.optJSONObject("properties")
                    val maxspeed = props?.optString("maxspeed", "")?.filter { it.isDigit() }?.toIntOrNull()
                    val direction = props?.optString("direction", "")?.filter { it.isDigit() }?.toIntOrNull()
                    list.add(RadarPoint(i.toLong(), lat, lon, maxspeed, direction))
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return list
}

// ==========================================
// SPZ MANAGER DIALOG
// ==========================================

@Composable
fun SpzManagerDialog(
    policePlates: Set<String>,
    secretPlates: Set<String>,
    onSave: (Set<String>, Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var policeList by remember { mutableStateOf(policePlates.toMutableList()) }
    var secretList by remember { mutableStateOf(secretPlates.toMutableList()) }

    var newPoliceText by remember { mutableStateOf("") }
    var newSecretText by remember { mutableStateOf("") }
    var selectedTab by remember { mutableIntStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Správa sledovaných SPZ", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Policie (${policeList.size})") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Secret (${secretList.size})") }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (selectedTab == 0) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newPoliceText,
                            onValueChange = { newPoliceText = it.uppercase() },
                            label = { Text("Nová SPZ Policie") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                if (newPoliceText.isNotBlank() && !policeList.contains(newPoliceText.trim())) {
                                    policeList = (policeList + newPoliceText.trim()).toMutableList()
                                    newPoliceText = ""
                                }
                            }
                        ) {
                            Icon(Icons.Default.Add, "Přidat", tint = Color(0xFF00E676))
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                        items(policeList) { plate ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(plate, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                IconButton(
                                    onClick = { policeList = (policeList - plate).toMutableList() }
                                ) {
                                    Icon(Icons.Default.Delete, "Smazat", tint = Color(0xFFFF5252))
                                }
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newSecretText,
                            onValueChange = { newSecretText = it.uppercase() },
                            label = { Text("Nová SPZ Secret") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                if (newSecretText.isNotBlank() && !secretList.contains(newSecretText.trim())) {
                                    secretList = (secretList + newSecretText.trim()).toMutableList()
                                    newSecretText = ""
                                }
                            }
                        ) {
                            Icon(Icons.Default.Add, "Přidat", tint = Color(0xFF00E676))
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                        items(secretList) { plate ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(plate, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                IconButton(
                                    onClick = { secretList = (secretList - plate).toMutableList() }
                                ) {
                                    Icon(Icons.Default.Delete, "Smazat", tint = Color(0xFFFF5252))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(policeList.toSet(), secretList.toSet()) }
            ) {
                Text("Uložit změny")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Zrušit")
            }
        }
    )
}
