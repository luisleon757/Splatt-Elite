package com.splatt.elite

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.splatt.elite.network.SplattApiClient
import com.splatt.elite.network.SplattStatus
import com.splatt.elite.ui.components.CalibrationDPad
import com.splatt.elite.ui.components.FocusDialog
import com.splatt.elite.ui.components.SettingsDialog
import com.splatt.elite.ui.components.ShotPoint
import com.splatt.elite.ui.components.TargetView
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
    
    // Preferences for local state
    val prefs = context.getSharedPreferences("splatt_prefs", Context.MODE_PRIVATE)
    
    // API Client configuration
    var espIp by remember { mutableStateOf(prefs.getString("esp_ip", "192.168.4.1") ?: "192.168.4.1") }
    val apiClient = remember { SplattApiClient(espIp) }
    
    // Device state
    var status by remember { mutableStateOf(SplattStatus()) }
    var isConnected by remember { mutableStateOf(false) }
    
    // Local App State (migrated from ESP32)
    var calibX by remember { mutableFloatStateOf(prefs.getFloat("calib_x", 0.0f)) }
    var calibY by remember { mutableFloatStateOf(prefs.getFloat("calib_y", 0.0f)) }
    var distM by remember { mutableFloatStateOf(prefs.getFloat("dist", 10.0f)) }
    var lensMm by remember { mutableFloatStateOf(prefs.getFloat("lens", 25.0f)) }
    
    var localScore by remember { mutableFloatStateOf(0.0f) }
    
    // Zoom and local lists
    var uiZoom by remember { mutableStateOf(1.0f) }
    val shots = remember { mutableStateListOf<ShotPoint>() }
    val trace = remember { mutableStateListOf<Offset>() }
    
    // Dialog control
    var showSettings by remember { mutableStateOf(false) }
    var showFocus by remember { mutableStateOf(false) }
    var showIpConfig by remember { mutableStateOf(false) }

    // State labels mapping
    val stateText = when (status.state) {
        0 -> "STANDBY"
        1 -> "APUNTANDO"
        2 -> "POST-DISPARO"
        else -> "DESCONOCIDO"
    }

    // Connect & Polling Loop
    LaunchedEffect(espIp) {
        apiClient.updateBaseUrl(espIp)
        var lastState = 0
        
        while (true) {
            apiClient.fetchStatus(
                onSuccess = { newStatus ->
                    isConnected = true
                    
                    // Shot detection logic: Transition from 1 (Aiming) or 0 (Standby if missed) to 2 (Post-Shot)
                    if (newStatus.state == 2 && lastState != 2) {
                        // Calculate score locally
                        val cx = newStatus.shotX - 160.0f - calibX
                        val cy = newStatus.shotY - 120.0f - calibY
                        val distPixels = kotlin.math.sqrt((cx * cx) + (cy * cy))
                        
                        val pEff = 0.00896f
                        val focalLengthPx = lensMm / pEff
                        val scaleFactor = (distM * 1000.0f) / focalLengthPx
                        
                        val calculatedScore = 10.9f - ((distPixels * scaleFactor) / 8.0f)
                        localScore = calculatedScore.coerceAtLeast(0.0f)
                        
                        // Add to shots list
                        shots.add(ShotPoint(newStatus.shotX, newStatus.shotY, String.format(Locale.US, "%.1f", localScore)))
                    }

                    // Handle reset
                    if (newStatus.state == 1 && lastState == 0 && shots.isNotEmpty()) {
                        // Just a clean state transition to Aiming, we might want to clear shots manually later
                    }

                    // Update trace
                    if (newStatus.state == 1) { // Apuntando
                        if (newStatus.v > 0) {
                            trace.add(Offset(newStatus.x, newStatus.y))
                        }
                    } else {
                        // Clear trace outside aiming state
                        if (trace.isNotEmpty() && newStatus.state == 0) {
                            trace.clear()
                        }
                    }

                    lastState = newStatus.state
                    status = newStatus
                },
                onFailure = {
                    isConnected = false
                }
            )
            delay(150) // poll frequency
        }
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
                writer.append("${index + 1},${shot.x},${shot.y},${shot.score}\n")
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

    Box(modifier = Modifier.fillMaxSize()) {
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
                // Connection and IP indicator
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(RoundedCornerShape(50))
                                .background(if (isConnected) GreenActive else RedActive)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isConnected) "Conectado ($espIp)" else "Desconectado",
                            color = if (isConnected) Color.White else RedActive,
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
                    // IP Config Button
                    IconButton(
                        onClick = { showIpConfig = true },
                        modifier = Modifier
                            .size(36.dp)
                            .background(GlassBg, RoundedCornerShape(8.dp))
                    ) {
                        Text("⚙", color = Color.White, fontSize = 16.sp)
                    }

                    // Theme Button
                    Button(
                        onClick = onToggleTheme,
                        colors = ButtonDefaults.buttonColors(containerColor = GlassBg, contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text(if (isLightMode) "Tema Oscuro" else "Tema Claro", fontSize = 12.sp)
                    }

                    // Deep sleep button (Modo Carga)
                    Button(
                        onClick = {
                            scope.launch {
                                apiClient.executeGet("/sleep") { success ->
                                    if (success) {
                                        Toast.makeText(context, "ESP32 apagado (Modo Carga activo)", Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(context, "Error al mandar comando de apagado", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = RedActive, contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text("🔋 Apagar / Cargar", fontSize = 12.sp)
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

                // Score Overlay
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(12.dp)
                ) {
                    Text(
                        text = if (localScore > 0) String.format(Locale.US, "%.1f", localScore) else "0.0",
                        fontSize = 48.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = GreenActive
                    )
                }

                // Shots Count badge & Reset Button
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (shots.isNotEmpty()) {
                        Button(
                            onClick = { shots.clear(); localScore = 0.0f },
                            colors = ButtonDefaults.buttonColors(containerColor = RedActive),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("Borrar", fontSize = 12.sp)
                        }
                    }
                    
                    Box(
                        modifier = Modifier
                            .background(PanelBg.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Disparos: ${shots.size}",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
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
                // Manual Calibration D-Pad (local app state update)
                CalibrationDPad(
                    onMoveUp = { calibY -= 0.5f; prefs.edit().putFloat("calib_y", calibY).apply() },
                    onMoveDown = { calibY += 0.5f; prefs.edit().putFloat("calib_y", calibY).apply() },
                    onMoveLeft = { calibX -= 0.5f; prefs.edit().putFloat("calib_x", calibX).apply() },
                    onMoveRight = { calibX += 0.5f; prefs.edit().putFloat("calib_x", calibX).apply() }
                )

                // Action Buttons
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Start / Cancel Shot Button
                    if (status.state == 0) { // Standby
                        Button(
                            onClick = { apiClient.executeGet("/start_shot") {} },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentColor),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text("🎯 INICIAR TIRO", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    } else { // Aiming or Post-Shot
                        Button(
                            onClick = { apiClient.executeGet("/cancel_shot") {} },
                            colors = ButtonDefaults.buttonColors(containerColor = RedActive),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text("❌ Cancelar", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }

                    // Calibration Mode Trigger
                    Button(
                        onClick = {
                            if (status.state == 0) {
                                apiClient.executeGet("/start_calib") {}
                            } else {
                                apiClient.executeGet("/stop_calib") {}
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = GlassBg, contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (status.state == 0) "CALIBRAR" else "DETENER CALIB.")
                    }

                    // Zoom Buttons
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

                    // Bottom settings & history triggers
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { showSettings = true },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = GlassBg, contentColor = Color.White)
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
                onDismiss = { showSettings = false },
                onSaveSettings = { exposure, gain, distance, lens, sensitivity, sound ->
                    // Save local variables
                    distM = distance
                    lensMm = lens
                    prefs.edit()
                        .putFloat("dist", distance)
                        .putFloat("lens", lens)
                        .apply()
                        
                    // Send ESP32 variables
                    apiClient.executeGet(
                        "/set",
                        mapOf(
                            "exp" to exposure.toString(),
                            "gain" to gain.toString(),
                            "thr" to ((11 - sensitivity) * 5).toString(),
                            "snd" to (sound * 250).toString()
                        )
                    ) {
                        Toast.makeText(context, "Ajustes enviados", Toast.LENGTH_SHORT).show()
                    }
                },
                onAdjustFocus = {
                    showSettings = false
                    showFocus = true
                }
            )
        }

        if (showFocus) {
            FocusDialog(
                baseUrl = apiClient.getBaseUrl(),
                onDismiss = { showFocus = false }
            )
        }

        if (showIpConfig) {
            var tempIp by remember { mutableStateOf(espIp) }
            AlertDialog(
                onDismissRequest = { showIpConfig = false },
                title = { Text("Configurar IP de la Placa") },
                text = {
                    Column {
                        Text("Introduce la dirección IP de tu ESP32 (Punto de acceso por defecto: 192.168.4.1):", fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        TextField(
                            value = tempIp,
                            onValueChange = { tempIp = it },
                            placeholder = { Text("192.168.4.1") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            espIp = tempIp
                            prefs.edit().putString("esp_ip", tempIp).apply()
                            showIpConfig = false
                        }
                    ) {
                        Text("Aceptar", color = AccentColor)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showIpConfig = false }) {
                        Text("Cancelar")
                    }
                }
            )
        }
    }
}
