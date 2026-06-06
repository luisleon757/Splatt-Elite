package com.splatt.elite

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
            SplattEliteTheme(darkTheme = !isLightMode) {
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
    var lensMm by remember { mutableFloatStateOf(prefs.getFloat("lens", 25.0f)) }
    var currentSensitivity by remember { mutableIntStateOf(prefs.getInt("sensitivity", 9)) }
    var currentSound by remember { mutableIntStateOf(prefs.getInt("sound", 8)) }
    var currentExposure by remember { mutableIntStateOf(prefs.getInt("exposure", 300)) }
    var currentGain by remember { mutableIntStateOf(prefs.getInt("gain", 0)) }
    
    var localScore by remember { mutableFloatStateOf(0.0f) }
    var isCalibrating by remember { mutableStateOf(false) }
    
    // Zoom and local lists
    var uiZoom by remember { mutableStateOf(1.0f) }
    val shots = remember { mutableStateListOf<ShotPoint>() }
    val trace = remember { mutableStateListOf<TracePoint>() }
    
    // Dialog control
    var showSettings by remember { mutableStateOf(false) }
    var showFocus by remember { mutableStateOf(false) }

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
    LaunchedEffect(status) {
        if (status.state == 2 && lastState != 2) {
            // Calculate score locally
            val cx = status.shotX - 160.0f - calibX
            val cy = status.shotY - 120.0f - calibY
            val distPixels = kotlin.math.sqrt((cx * cx) + (cy * cy))
            
            val pEff = 0.00896f
            val focalLengthPx = lensMm / pEff
            val scaleFactor = (distM * 1000.0f) / focalLengthPx
            
            val calculatedScore = 10.9f - ((distPixels * scaleFactor) / 8.0f)
            localScore = calculatedScore.coerceAtLeast(0.0f)
            
            shots.add(ShotPoint(status.shotX, status.shotY, String.format(Locale.US, "%.1f", localScore)))
        }

        // Clear trace only when starting a new shot (entering state 1)
        if (status.state == 1 && lastState != 1) {
            trace.clear()
        }

        if (status.state == 1 || status.state == 2) { // Apuntando o Post-Disparo
            if (status.v > 0) {
                val traceColor = when {
                    isCalibrating -> Color.Green
                    status.state == 2 -> Color.Green // post-disparo
                    status.state == 1 -> {
                        when {
                            status.time < 4000 -> Color.Green
                            status.time < 8000 -> Color(0xFFE67E22) // Calabaza / Orange
                            status.time < 12000 -> Color(0xFF1B4F72) // Azul oscuro / Dark Blue
                            else -> Color.Red
                        }
                    }
                    else -> Color.Green
                }
                
                trace.add(TracePoint(status.x, status.y, traceColor))
                // Se incrementa el límite a 5000 para no borrar la traza entera
                if (trace.size > 5000) {
                    trace.removeAt(0)
                }
            }
        }

        if (status.state == 0) {
            isCalibrating = false // reset if it went to standby
        }
        
        lastState = status.state
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
            
            writer.append("Numero,X_Raw,Y_Raw,Puntuacion\n")
            shots.forEachIndexed { index, shot ->
                writer.append("${index + 1},${shot.x},${shot.y},${shot.label}\n")
            }
            writer.flush()
            writer.close()
            
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_SUBJECT, "Sesión Splatt Elite")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Exportar Sesión"))
            
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
                        Text(if (isLightMode) "Tema Oscuro" else "Tema Claro", fontSize = 12.sp)
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
                    Text(
                        text = if (localScore > 0) String.format(Locale.US, "%.1f", localScore) else "0.0",
                        fontSize = 64.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = GreenActive,
                        modifier = Modifier.offset(y = (-8).dp) // Move it slightly up as requested
                    )
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
                    currentLaserX = status.x,
                    currentLaserY = status.y,
                    isLaserVisible = (status.v > 0),
                    calibX = calibX,
                    calibY = calibY,
                    distanceM = distM,
                    lensMm = lensMm,
                    shots = shots,
                    trace = trace
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
                            Button(
                                onClick = { bleManager.sendCommand("start_shot") },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentColor),
                                modifier = Modifier.weight(1f).height(48.dp),
                                enabled = isConnected,
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("🎯 TIRO", fontWeight = FontWeight.Bold, fontSize = 14.sp)
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
                                        bleManager.sendCommand("start_calib")
                                    } else {
                                        isCalibrating = false
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
                            enabled = isConnected
                        ) {
                            Text("Ajustes")
                        }

                        Button(
                            onClick = { exportToCsv() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2ECC71), contentColor = Color.White)
                        ) {
                            Text("📥 Exportar CSV")
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
                currentLens = lensMm,
                currentSensitivity = currentSensitivity,
                currentSound = currentSound,
                currentExposure = currentExposure,
                currentGain = currentGain,
                onDismiss = { showSettings = false },
                onSaveSettings = { exposure, gain, distance, lens, sensitivity, sound ->
                    distM = distance
                    lensMm = lens
                    currentSensitivity = sensitivity
                    currentSound = sound
                    currentExposure = exposure
                    currentGain = gain
                    prefs.edit()
                        .putFloat("dist", distance)
                        .putFloat("lens", lens)
                        .putInt("sensitivity", sensitivity)
                        .putInt("sound", sound)
                        .putInt("exposure", exposure)
                        .putInt("gain", gain)
                        .apply()
                        
                    scope.launch {
                        bleManager.sendConfig("exp:$exposure")
                        delay(150)
                        bleManager.sendConfig("gain:$gain")
                        delay(150)
                        bleManager.sendConfig("thr:${(11 - sensitivity) * 5}")
                        delay(150)
                        bleManager.sendConfig("snd:${sound * 250}")
                    }

                    Toast.makeText(context, "Ajustes enviados", Toast.LENGTH_SHORT).show()
                },
                onAdjustFocus = {
                    showSettings = false
                    showFocus = true
                }
            )
        }

        if (showFocus) {
            FocusDialog(
                focusValue = status.f,
                onStartFocus = { bleManager.sendCommand("start_focus") },
                onStopFocus = { bleManager.sendCommand("stop_focus") },
                onDismiss = { showFocus = false }
            )
        }
    }
}
