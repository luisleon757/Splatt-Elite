package com.splatt.elite

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.splatt.elite.network.BleManager
import com.splatt.elite.network.SplattStatus
import com.splatt.elite.ui.components.CalibrationDPad
import com.splatt.elite.ui.components.FocusDialog
import com.splatt.elite.ui.components.SettingsDialog
import com.splatt.elite.ui.components.StatsDialog
import com.splatt.elite.ui.components.ShotPoint
import com.splatt.elite.ui.components.TargetView
import com.splatt.elite.ui.components.TracePoint
import com.splatt.elite.ui.theme.AccentColor
import com.splatt.elite.ui.theme.DarkBg
import com.splatt.elite.ui.theme.GlassBg
import com.splatt.elite.ui.theme.GreenActive
import com.splatt.elite.ui.theme.PanelBg
import com.splatt.elite.ui.theme.RedActive
import com.splatt.elite.ui.theme.SplattEliteTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var isLightMode by remember { mutableStateOf(false) }
            SplattEliteTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SplattMainScreen(
                        isLightMode = isLightMode,
                        onToggleTheme = { isLightMode = !isLightMode }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplattMainScreen(isLightMode: Boolean, onToggleTheme: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val prefs = context.getSharedPreferences("splatt_prefs", Context.MODE_PRIVATE)
    
    // TTS Manager
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var isTtsReady by remember { mutableStateOf(false) }

    DisposableEffect(context) {
        var ttsInstance: TextToSpeech? = null
        ttsInstance = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = ttsInstance?.setLanguage(Locale("es", "ES"))
                if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                    isTtsReady = true
                } else {
                    ttsInstance?.language = Locale.getDefault()
                    isTtsReady = true
                }
            }
        }
        tts = ttsInstance
        onDispose {
            ttsInstance?.stop()
            ttsInstance?.shutdown()
        }
    }
    
    // BLE Manager
    val bleManager = remember { BleManager(context) }
    
    // Observers from BLE
    val status by bleManager.statusFlow.collectAsState()
    val isConnected by bleManager.connectionState.collectAsState()
    val isScanning by bleManager.isScanningState.collectAsState()
    
    // Local App State
    var calibX by remember { mutableFloatStateOf(prefs.getFloat("calib_x", 0.0f)) }
    var calibY by remember { mutableFloatStateOf(prefs.getFloat("calib_y", 0.0f)) }
    var distM by remember { mutableFloatStateOf(prefs.getFloat("dist", 10.0f)) }
    var currentSensitivity by remember { mutableIntStateOf(prefs.getInt("sensitivity", 9)) }
    var currentSound by remember { mutableIntStateOf(prefs.getInt("sound", 8)) }
    var currentExposure by remember { mutableIntStateOf(prefs.getInt("exposure", 300)) }

    // Valores físicos fijos (ya no son configurables por UI)
    val lensMm = 25.0f
    
    var localScore by remember { mutableFloatStateOf(0.0f) }
    var isCalibrating by remember { mutableStateOf(false) }
    
    // Zoom and local lists
    var uiZoom by remember { mutableStateOf(1.0f) }
    val shots = remember { mutableStateListOf<ShotPoint>() }
    val trace = remember { mutableStateListOf<TracePoint>() }
    val calibShots = remember { mutableStateListOf<Offset>() }
    
    // Dialog control
    var showSettings by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }

    // Permissions logic
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            bleManager.startScan()
        } else {
            Toast.makeText(context, "Se requieren permisos para buscar la cámara", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        val permissionsToRequest = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            arrayOf(
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        permissionLauncher.launch(permissionsToRequest)
    }

    // Shot logic (when status changes)
    var lastState by remember { mutableIntStateOf(0) }
    var lastS by remember { mutableIntStateOf(0) }
    
    LaunchedEffect(status) {
        if (isCalibrating && status.s == 1 && lastS == 0) {
            calibShots.add(Offset(status.shotX, status.shotY))
            Toast.makeText(context, "Disparo de calibración registrado (${calibShots.size})", Toast.LENGTH_SHORT).show()
        }
        
        if (status.state == 2 && lastState != 2) {
            // Use the last valid trace point to ensure the impact perfectly matches the visual trace
            val lastValidTrace = trace.lastOrNull { it.x != 0.0f && it.y != 0.0f }
            val finalShotX = lastValidTrace?.x ?: status.shotX
            val finalShotY = lastValidTrace?.y ?: status.shotY

            // Calculate score locally
            val cx = finalShotX - 160.0f - calibX
            val cy = finalShotY - 120.0f - calibY
            val distPixels = kotlin.math.sqrt((cx * cx) + (cy * cy))
            
            val pEff = 0.013f // Modificado de 0.011 a 0.013 para que los disparos se abran un poco más
            val focalLengthPx = lensMm / pEff
            val scaleFactor = (distM * 1000.0f) / focalLengthPx
            
            val calculatedScore = 11.0f - ((distPixels * scaleFactor) / 8.0f)
            localScore = calculatedScore.coerceIn(0.0f, 10.9f)
            
            val shotTime = status.time
            val holdTimeWindowMs = 1000L
            val lastSecTrace = trace.filter { shotTime - it.timeMs <= holdTimeWindowMs }
            var inside10 = 0
            var inside9 = 0
            lastSecTrace.forEach { pt ->
                val cxPt = pt.x - 160.0f - calibX
                val cyPt = pt.y - 120.0f - calibY
                val distPtMm = kotlin.math.sqrt((cxPt * cxPt) + (cyPt * cyPt)) * scaleFactor
                if (distPtMm <= 8.0f) inside10++
                if (distPtMm <= 16.0f) inside9++
            }
            val hold10Pct = if (lastSecTrace.isNotEmpty()) (inside10.toFloat() / lastSecTrace.size) * 100f else 0f
            val hold9Pct = if (lastSecTrace.isNotEmpty()) (inside9.toFloat() / lastSecTrace.size) * 100f else 0f
            
            shots.add(ShotPoint(finalShotX, finalShotY, String.format(Locale.US, "%.1f", localScore), shotTime, hold10Pct, hold9Pct))
            
            // TTS Speak
            if (isTtsReady) {
                val scoreInt = localScore.toInt()
                val scoreDec = ((localScore * 10).toInt()) % 10
                val holdInt = hold10Pct.toInt()
                
                val speechText = "$scoreInt con $scoreDec... parada $holdInt por ciento"
                tts?.speak(speechText, TextToSpeech.QUEUE_FLUSH, null, null)
            }
            
            if (!isCalibrating) {
                val repaintedTrace = trace.map { pt ->
                    val timeBeforeShot = shotTime - pt.timeMs
                    val newColor = when {
                        timeBeforeShot <= 200 -> Color(0xFF3498DB) // Azul hasta el disparo
                        timeBeforeShot <= 1000 -> Color(0xFFF1C40F) // Amarillo hasta 0.2s
                        else -> Color.Green // Verde resto del tiempo
                    }
                    pt.copy(color = newColor)
                }
                trace.clear()
                trace.addAll(repaintedTrace)
            }
        }

        // Clear trace only when starting a new shot (entering state 1)
        if (status.state == 1 && lastState != 1) {
            trace.clear()
        }

        if (status.state == 1 || status.state == 2) { // Apuntando o Post-Disparo
            if (status.v > 0) {
                val traceColor = when {
                    isCalibrating -> Color.Green
                    status.state == 2 -> Color.Red // post-disparo
                    else -> Color.Green // Verde durante la fase de apuntado
                }
                
                trace.add(TracePoint(status.x, status.y, traceColor, status.time))
                // Se incrementa el límite a 5000 para no borrar la traza entera
                if (trace.size > 5000) {
                    trace.removeAt(0)
                }
            }
        }

        // Eliminado el reseteo automático de isCalibrating al entrar en standby
        // para permitir que se inicie la calibración con el arma apoyada.
        
        lastState = status.state
        lastS = status.s
    }

    val stateText = when (status.state) {
        0 -> "STANDBY"
        1 -> "APUNTANDO"
        2 -> "POST-DISPARO"
        3 -> "ENFOQUE"
        else -> "DESCONOCIDO"
    }

    fun exportToCsv() {
        if (shots.isEmpty()) {
            Toast.makeText(context, "No hay disparos para exportar", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename = "Splatt_Session_$timestamp.csv"
            val file = File(context.cacheDir, filename)
            val writer = FileWriter(file)
            
            writer.append("Numero,X_Raw,Y_Raw,Puntuacion,Tiempo_Apuntado_ms,Parada_10_Pct,Parada_9_Pct\n")
            shots.forEachIndexed { index, shot ->
                writer.append("${index + 1},${shot.x},${shot.y},${shot.label},${shot.timeMs},${String.format(Locale.US, "%.1f", shot.hold10)},${String.format(Locale.US, "%.1f", shot.hold9)}\n")
            }
            writer.flush()
            writer.close()
            
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "text/csv")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                context.startActivity(viewIntent)
            } catch (e: android.content.ActivityNotFoundException) {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_SUBJECT, "Sesión Splatt Elite")
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Exportar Sesión"))
            }
            
        } catch (e: Exception) {
            Toast.makeText(context, "Error exportando CSV", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }

    Box(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
        ) {
            // --- TOP HEADER ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Connection indicator
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { if (!isConnected && !isScanning) bleManager.startScan() }
                            .padding(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(RoundedCornerShape(50))
                                .background(if (isConnected) GreenActive else if (isScanning) Color(0xFFF39C12) else RedActive)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isConnected) "Conectado (BLE)" else if (isScanning) "Buscando dispositivo..." else "Desconectado (Tocar)",
                            color = if (isConnected) Color.White else if (isScanning) Color(0xFFF39C12) else RedActive,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                    Text(
                        text = "Modo: $stateText",
                        color = AccentColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Header buttons
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onToggleTheme,
                        colors = ButtonDefaults.buttonColors(containerColor = GlassBg, contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text(if (isLightMode) "Diana Invertida" else "Diana Normal", fontSize = 12.sp)
                    }

                    Button(
                        onClick = {
                            bleManager.sendCommand("sleep")
                            Toast.makeText(context, "Modo Carga activado. Reinicia la placa para conectar.", Toast.LENGTH_LONG).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = RedActive, contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text("🔋 Apagar", fontSize = 12.sp)
                    }
                }
            }

            // --- INFO OVERLAYS (Timer, Score, Shots) ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Timer
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.TopStart) {
                    if (status.state == 1 || status.state == 2 || (status.state == 0 && status.time > 0)) {
                        Box(
                            modifier = Modifier
                                .background(PanelBg.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            val seconds = status.time / 1000
                            val deciseconds = (status.time % 1000) / 100
                            Text(
                                text = String.format(Locale.US, "⏱ %d.%d s", seconds, deciseconds),
                                color = AccentColor,
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Center: Score
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (localScore > 0) String.format(Locale.US, "%.1f", localScore) else "0.0",
                            fontSize = 64.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = GreenActive,
                            modifier = Modifier.offset(y = (-8).dp) // Move it slightly up as requested
                        )
                        if (shots.isNotEmpty()) {
                            Text(
                                text = String.format(Locale.US, "Parada: %.0f%%", shots.last().hold10),
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.offset(y = (-16).dp)
                            )
                        }
                    }
                }

                // Right: Shots Count & Reset
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (shots.isNotEmpty()) {
                        Button(
                            onClick = { shots.clear(); trace.clear(); localScore = 0.0f },
                            colors = ButtonDefaults.buttonColors(containerColor = RedActive),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.height(36.dp).padding(end = 8.dp)
                        ) {
                            Text("Borrar", fontSize = 14.sp)
                        }
                    }
                    
                    Box(
                        modifier = Modifier
                            .background(PanelBg.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "Disparos: ${shots.size}",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    }
                }
            }

            // --- VIEWPORT / SHOOTING CANVAS ---
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, GlassBg, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                TargetView(
                    modifier = Modifier.fillMaxSize(),
                    isLightMode = isLightMode,
                    zoomFactor = uiZoom,
                    currentTargetX = status.x,
                    currentTargetY = status.y,
                    isTargetVisible = (status.v > 0),
                    calibX = calibX,
                    calibY = calibY,
                    distanceM = distM,
                    lensMm = lensMm,
                    shots = shots,
                    trace = trace,
                    calibShots = calibShots
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // --- LOWER PANEL CONTROLS ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PanelBg, RoundedCornerShape(12.dp))
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CalibrationDPad(
                    onMoveUp = { calibY -= 0.5f; prefs.edit().putFloat("calib_y", calibY).apply() },
                    onMoveDown = { calibY += 0.5f; prefs.edit().putFloat("calib_y", calibY).apply() },
                    onMoveLeft = { calibX -= 0.5f; prefs.edit().putFloat("calib_x", calibX).apply() },
                    onMoveRight = { calibX += 0.5f; prefs.edit().putFloat("calib_x", calibX).apply() }
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Main Action Buttons Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (status.state == 0) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .background(GlassBg, RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "Levanta el arma para apuntar",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else if (!isCalibrating) {
                            Button(
                                onClick = { bleManager.sendCommand("cancel_shot") },
                                colors = ButtonDefaults.buttonColors(containerColor = RedActive),
                                modifier = Modifier.weight(1f).height(48.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("❌ Cancelar", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }

                        if (status.state == 0 || isCalibrating) {
                            Button(
                                onClick = {
                                    if (!isCalibrating) {
                                        isCalibrating = true
                                        calibShots.clear()
                                        bleManager.sendCommand("start_calib")
                                    } else {
                                        isCalibrating = false
                                        if (calibShots.isNotEmpty()) {
                                            val avgX = calibShots.map { it.x }.average().toFloat()
                                            val avgY = calibShots.map { it.y }.average().toFloat()
                                            calibX = avgX - 160.0f
                                            calibY = avgY - 120.0f
                                            prefs.edit().putFloat("calib_x", calibX).putFloat("calib_y", calibY).apply()
                                            Toast.makeText(context, "Calibración aplicada (Promedio de ${calibShots.size} disparos)", Toast.LENGTH_LONG).show()
                                            calibShots.clear()
                                        }
                                        bleManager.sendCommand("stop_calib")
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = GlassBg, contentColor = Color.White),
                                modifier = Modifier.weight(1f).height(48.dp),
                                enabled = isConnected,
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(if (!isCalibrating) "CALIBRAR" else "DETENER", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { uiZoom = (uiZoom + 0.15f).coerceAtMost(5.0f) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = GlassBg, contentColor = Color.White)
                        ) {
                            Text("Zoom +")
                        }
                        Button(
                            onClick = { uiZoom = (uiZoom - 0.15f).coerceAtLeast(0.5f) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = GlassBg, contentColor = Color.White)
                        ) {
                            Text("Zoom -")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { showSettings = true },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = GlassBg, contentColor = Color.White),
                            enabled = isConnected,
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("⚙️ Ajustes", fontSize = 12.sp)
                        }

                        Button(
                            onClick = { exportToCsv() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2ECC71), contentColor = Color.White),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("📥 CSV", fontSize = 12.sp)
                        }
                        
                        Button(
                            onClick = { showStats = true },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3498DB), contentColor = Color.White),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("📊 Stats", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // --- DIALOGS ---
        if (showSettings) {
            SettingsDialog(
                status = status,
                currentDistance = distM,
                currentSensitivity = currentSensitivity,
                currentSound = currentSound,
                currentExposure = currentExposure,
                onDismiss = { showSettings = false },
                onSaveSettings = { exposure, distance, sensitivity, sound ->
                    distM = distance
                    currentSensitivity = sensitivity
                    currentSound = sound
                    currentExposure = exposure
                    prefs.edit()
                        .putFloat("dist", distance)
                        .putInt("sensitivity", sensitivity)
                        .putInt("sound", sound)
                        .putInt("exposure", exposure)
                        .apply()
                        
                    scope.launch {
                        bleManager.sendConfig("exp:$exposure")
                        delay(150)
                        bleManager.sendConfig("gain:0") // Fixed gain
                        delay(150)
                        bleManager.sendConfig("thr:${(11 - sensitivity) * 5}")
                        delay(150)
                        bleManager.sendConfig("snd:${sound * 250}")
                        delay(150)
                        bleManager.sendConfig("max_snd:60000") // Fixed max sound (no limit)
                    }

                    Toast.makeText(context, "Ajustes enviados", Toast.LENGTH_SHORT).show()
                }
            )
        }
        if (showStats) {
            StatsDialog(
                shots = shots,
                calibX = calibX,
                calibY = calibY,
                distM = distM,
                lensMm = lensMm,
                onDismiss = { showStats = false }
            )
        }
    }
}
