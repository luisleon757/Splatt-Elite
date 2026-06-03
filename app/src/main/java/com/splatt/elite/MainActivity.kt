package com.splatt.elite

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
    
    // API Client configuration
    var espIp by remember { mutableStateOf("192.168.4.1") }
    val apiClient = remember { SplattApiClient(espIp) }
    
    // Device state
    var status by remember { mutableStateOf(SplattStatus()) }
    var isConnected by remember { mutableStateOf(false) }
    
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
        while (true) {
            apiClient.fetchStatus(
                onSuccess = { newStatus ->
                    isConnected = true
                    
                    // Detect changes in shot count to sync list of shots
                    if (newStatus.shotNum != status.shotNum || newStatus.state == 0 && status.state == 2) {
                        // If shot count changed, or we reset, fetch history or clear
                        if (newStatus.shotNum == 0) {
                            shots.clear()
                            trace.clear()
                        } else if (newStatus.shotNum > shots.size) {
                            // Add the new shot location
                            shots.add(ShotPoint(newStatus.shotX, newStatus.shotY, "${newStatus.score}"))
                        }
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

                    status = newStatus
                },
                onFailure = {
                    isConnected = false
                }
            )
            delay(150) // poll frequency
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
                    calibX = status.cx,
                    calibY = status.cy,
                    distanceM = status.dist,
                    lensMm = status.lens,
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
                        text = if (status.score > 0) String.format("%.1f", status.score) else "0.0",
                        fontSize = 48.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = GreenActive
                    )
                }

                // Shots Count badge
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .background(PanelBg.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Disparos: ${status.shotNum}",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
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
                // Manual Calibration D-Pad
                CalibrationDPad(
                    onMoveUp = { apiClient.executeGet("/set_calib", mapOf("y" to "-0.5")) {} },
                    onMoveDown = { apiClient.executeGet("/set_calib", mapOf("y" to "0.5")) {} },
                    onMoveLeft = { apiClient.executeGet("/set_calib", mapOf("x" to "-0.5")) {} },
                    onMoveRight = { apiClient.executeGet("/set_calib", mapOf("x" to "0.5")) {} }
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
                            Text("❌ Cancelar (${String.format("%.1fs", status.time / 1000f)})", fontWeight = FontWeight.Bold, fontSize = 14.sp)
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
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("${apiClient.getBaseUrl()}/history"))
                                context.startActivity(intent)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2ECC71), contentColor = Color.White)
                        ) {
                            Text("📥 Historial")
                        }
                    }
                }
            }
        }

        // --- DIALOGS ---
        if (showSettings) {
            SettingsDialog(
                status = status,
                onDismiss = { showSettings = false },
                onSaveSettings = { exposure, gain, distance, lens, sensitivity, sound ->
                    // Call /set endpoint to update ESP32 settings
                    apiClient.executeGet(
                        "/set",
                        mapOf(
                            "exposure" to exposure.toString(),
                            "gain" to gain.toString(),
                            "distance" to distance.toString(),
                            "lens" to lens.toString(),
                            "threshold" to ((11 - sensitivity) * 5).toString(), // convert level 1-10 to ESP32 range
                            "sound" to (sound * 250).toString() // convert sound level to ESP32 threshold
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
