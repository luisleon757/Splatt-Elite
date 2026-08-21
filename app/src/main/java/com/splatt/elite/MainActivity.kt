package com.splatt.elite

import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.splatt.elite.ui.components.SettingsDialog
import com.splatt.elite.ui.components.SessionsDialog
import com.splatt.elite.ui.components.StatsDialog
import com.splatt.elite.ui.components.ShotPoint
import com.splatt.elite.ui.components.TargetView
import com.splatt.elite.ui.components.TracePoint
import com.splatt.elite.ui.components.WifiCalibrationDialog
import com.splatt.elite.ui.theme.AccentColor
import com.splatt.elite.ui.theme.DarkBg
import com.splatt.elite.ui.theme.GlassBg
import com.splatt.elite.ui.theme.GreenActive
import com.splatt.elite.ui.theme.PanelBg
import com.splatt.elite.ui.theme.RedActive
import com.splatt.elite.ui.theme.SplattEliteTheme
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
    // Valores fÃ­sicos fijos (ya no son configurables por UI)
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
    var showSessions by remember { mutableStateOf(false) }
    var showWifiCalibration by remember { mutableStateOf(false) }
    var raspberryHost by remember {
        mutableStateOf(
            prefs.getString("raspberry_host", "192.168.31.195")
                ?: "192.168.31.195"
        )
    }
    var currentSessionFile by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(status.host) {
        val detectedHost = status.host.trim()
        if (detectedHost.isNotEmpty() && detectedHost != raspberryHost) {
            raspberryHost = detectedHost
            prefs.edit().putString("raspberry_host", detectedHost).apply()
        }
    }

    // Permissions logic
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            bleManager.startScan()
        } else {
            Toast.makeText(context, "Se requieren permisos para buscar la cÃ¡mara", Toast.LENGTH_LONG).show()
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
    
    LaunchedEffect(status) {
        // El protocolo BLE compacto marca cada impacto al entrar en estado 2.
        // Usar la transición evita perderlo o contarlo más de una vez.
        if (isCalibrating && status.state == 2 && lastState != 2) {
            calibShots.add(Offset(status.shotX, status.shotY))
            Toast.makeText(context, "Disparo de calibraciÃ³n registrado (${calibShots.size})", Toast.LENGTH_SHORT).show()
        }
        
        if (status.state == 2 && lastState != 2) {
            // Usar la posición histórica asociada al disparo por la Raspberry.
            val finalShotX = status.shotX
            val finalShotY = status.shotY

            // Calculate score locally
            val cx = finalShotX - 160.0f - calibX
            val cy = finalShotY - 120.0f - calibY
            val distPixels = kotlin.math.sqrt((cx * cx) + (cy * cy))
            
            val pEff = 0.013f // Modificado de 0.011 a 0.013 para que los disparos se abran un poco mÃ¡s
            val focalLengthPx = lensMm / pEff
            val scaleFactor = (distM * 1000.0f) / focalLengthPx
            
            val calculatedScore = 11.0f - ((distPixels * scaleFactor) / 8.0f)
            localScore = calculatedScore.coerceIn(0.0f, 10.9f)
            
            val shotTime = status.time
            val shotTraceTime = android.os.SystemClock.elapsedRealtime()
            val holdTimeWindowMs = 1000L
            val lastSecTrace = trace.filter {
                val age = shotTraceTime - it.timeMs
                age in 0..holdTimeWindowMs
            }
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
            
            val newShot = ShotPoint(finalShotX, finalShotY, String.format(Locale.US, "%.1f", localScore), shotTime, hold10Pct, hold9Pct)
            shots.add(newShot)
            
            try {
                val sessionsDir = java.io.File(context.filesDir, "sessions")
                if (!sessionsDir.exists()) sessionsDir.mkdirs()
                
                if (currentSessionFile == null) {
                    val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                    currentSessionFile = java.io.File(sessionsDir, "Splatt_Session_$timestamp.csv")
                    val writer = java.io.FileWriter(currentSessionFile!!, true)
                    writer.append("Numero,X_Raw,Y_Raw,Puntuacion,Tiempo_Apuntado_ms,Parada_10_Pct,Parada_9_Pct\n")
                    writer.close()
                }
                
                val writer = java.io.FileWriter(currentSessionFile!!, true)
                writer.append("${shots.size},${newShot.x},${newShot.y},${newShot.label},${newShot.timeMs},${String.format(java.util.Locale.US, "%.1f", newShot.hold10)},${String.format(java.util.Locale.US, "%.1f", newShot.hold9)}\n")
                writer.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
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
                    val timeBeforeShot = shotTraceTime - pt.timeMs
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
                
                trace.add(TracePoint(status.x, status.y, traceColor, android.os.SystemClock.elapsedRealtime()))
                // Se incrementa el lÃ­mite a 5000 para no borrar la traza entera
                if (trace.size > 5000) {
                    trace.removeAt(0)
                }
            }
        }

        // Eliminado el reseteo automÃ¡tico de isCalibrating al entrar en standby
        // para permitir que se inicie la calibraciÃ³n con el arma apoyada.
        
        lastState = status.state
    }

    val stateText = when (status.state) {
        0 -> "Listo"
        1 -> "Apuntando"
        2 -> "Resultado"
        3 -> "Ajustando visión"
        else -> "Sin información"
    }





    BoxWithConstraints(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        val maxH = maxHeight
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp)
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
                            fontSize = 16.sp
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Estado: $stateText",
                            color = AccentColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (status.v > 0) "Diana detectada" else "Buscando diana...",
                            color = if (status.v > 0) GreenActive else Color.Gray,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Header buttons
                Button(
                    onClick = onToggleTheme,
                    colors = ButtonDefaults.buttonColors(containerColor = GlassBg, contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.height(40.dp)
                ) {
                    Text(if (isLightMode) "DIANA INVERTIDA" else "DIANA NORMAL", fontSize = 13.sp)
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
                                text = String.format(Locale.US, "%d.%d s", seconds, deciseconds),
                                color = AccentColor,
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Center: Score
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (shots.isNotEmpty()) String.format(Locale.US, "%.1f", localScore) else "—",
                            fontSize = 56.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = GreenActive,
                            modifier = Modifier.offset(y = (-4).dp)
                        )
                        if (shots.isNotEmpty()) {
                            Text(
                                text = String.format(Locale.US, "Estabilidad en el 10: %.0f%%", shots.last().hold10),
                                color = Color.White,
                                fontSize = 16.sp,
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
                            onClick = { shots.clear(); trace.clear(); localScore = 0.0f; currentSessionFile = null },
                            colors = ButtonDefaults.buttonColors(containerColor = RedActive),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.height(36.dp).padding(end = 8.dp)
                        ) {
                            Text("NUEVA SESIÓN", fontSize = 13.sp)
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
                            fontSize = 20.sp
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(max = maxH * 0.55f)
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
                    .heightIn(max = maxH * 0.40f)
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
                        .padding(start = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Vision and impact-centering controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                bleManager.sendCommand("start_calib")
                                Toast.makeText(context, "Ajustando la visión... Mantén el arma firme", Toast.LENGTH_LONG).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = GlassBg, contentColor = Color.White),
                            modifier = Modifier.weight(1f).height(34.dp),
                            enabled = isConnected && !isCalibrating && status.state != 3,
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(if (status.state == 3) "AJUSTANDO..." else "AJUSTAR VISIÓN", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                if (!isCalibrating) {
                                    isCalibrating = true
                                    calibShots.clear()
                                    Toast.makeText(context, "Centrado iniciado. Realiza varios disparos", Toast.LENGTH_LONG).show()
                                } else {
                                    isCalibrating = false
                                    if (calibShots.isNotEmpty()) {
                                        val avgX = calibShots.map { it.x }.average().toFloat()
                                        val avgY = calibShots.map { it.y }.average().toFloat()
                                        calibX = avgX - 160.0f
                                        calibY = avgY - 120.0f
                                        prefs.edit().putFloat("calib_x", calibX).putFloat("calib_y", calibY).apply()
                                        Toast.makeText(context, "Centrado aplicado con ${calibShots.size} disparos", Toast.LENGTH_LONG).show()
                                        calibShots.clear()
                                    } else {
                                        Toast.makeText(context, "Centrado cancelado: no se registraron disparos", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isCalibrating) RedActive else GlassBg,
                                contentColor = Color.White
                            ),
                            modifier = Modifier.weight(1f).height(34.dp),
                            enabled = isConnected && status.state != 3,
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(
                                if (isCalibrating) "APLICAR (${calibShots.size})" else "CENTRAR IMPACTOS",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { uiZoom = (uiZoom + 0.15f).coerceAtMost(5.0f) },
                            modifier = Modifier.weight(1f).height(32.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = GlassBg, contentColor = Color.White)
                        ) {
                            Text("AMPLIAR", fontSize = 13.sp)
                        }
                        Button(
                            onClick = { uiZoom = (uiZoom - 0.15f).coerceAtLeast(0.5f) },
                            modifier = Modifier.weight(1f).height(32.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = GlassBg, contentColor = Color.White)
                        ) {
                            Text("REDUCIR", fontSize = 13.sp)
                        }
                    }

                    Button(
                        onClick = { showWifiCalibration = true },
                        modifier = Modifier.fillMaxWidth().height(32.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF6C5CE7),
                            contentColor = Color.White
                        )
                    ) {
                        Text("PANEL DE CÁMARA", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { showSettings = true },
                            modifier = Modifier.weight(1f).height(32.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = GlassBg, contentColor = Color.White),
                            enabled = isConnected,
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("DISTANCIA", fontSize = 13.sp)
                        }

                        Button(
                            onClick = { showSessions = true },
                            modifier = Modifier.weight(1f).height(32.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2ECC71), contentColor = Color.White),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("SESIONES", fontSize = 13.sp)
                        }
                        
                        Button(
                            onClick = { showStats = true },
                            modifier = Modifier.weight(1f).height(32.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3498DB), contentColor = Color.White),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("ESTADÍSTICAS", fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        // --- DIALOGS ---
        if (showWifiCalibration) {
            WifiCalibrationDialog(
                initialHost = raspberryHost,
                onHostSaved = { host ->
                    raspberryHost = host
                    prefs.edit().putString("raspberry_host", host).apply()
                },
                onDismiss = { showWifiCalibration = false },
            )
        }

        if (showSettings) {
            SettingsDialog(
                currentDistance = distM,
                onDismiss = { showSettings = false },
                onSaveDistance = { distance ->
                    distM = distance
                    prefs.edit()
                        .putFloat("dist", distance)
                        .apply()
                    Toast.makeText(context, "Distancia guardada", Toast.LENGTH_SHORT).show()
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
        if (showSessions) {
            SessionsDialog(
                onDismiss = { showSessions = false },
                onOpen = { file ->
                    try {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "text/csv")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(viewIntent)
                    } catch (e: Exception) {
                        Toast.makeText(context, "No hay una aplicación para abrir el archivo", Toast.LENGTH_SHORT).show()
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/csv"
                            putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(context, "${context.packageName}.provider", file))
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Abrir archivo con:"))
                    }
                }
            )
        }
    }
}


