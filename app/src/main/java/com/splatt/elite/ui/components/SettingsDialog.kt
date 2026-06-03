package com.splatt.elite.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.splatt.elite.network.SplattStatus
import com.splatt.elite.ui.theme.AccentColor

@Composable
fun SettingsDialog(
    status: SplattStatus,
    onDismiss: () -> Unit,
    onSaveSettings: (exposure: Int, gain: Int, distance: Float, lens: Float, sensitivity: Int, sound: Int) -> Unit,
    onAdjustFocus: () -> Unit
) {
    var exposure by remember { mutableStateOf(status.v.coerceIn(10, 1200)) } // Temporary assignment for init or custom local fields
    var gain by remember { mutableStateOf(status.sd.coerceIn(0, 30)) } // Placeholders for sliders
    var distance by remember { mutableFloatStateOf(status.dist) }
    var lens by remember { mutableFloatStateOf(status.lens) }
    var sensitivity by remember { mutableIntStateOf(9) }
    var soundSensitivity by remember { mutableIntStateOf(8) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Ajustes de Láser IR",
                style = MaterialTheme.typography.titleLarge,
                color = AccentColor
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Laser sensitivity level
                Column {
                    Text("Sensibilidad Láser (Nivel: $sensitivity)", style = MaterialTheme.typography.bodyMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        (1..5).forEach { num ->
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(
                                        if (sensitivity == num) AccentColor else Color.DarkGray,
                                        shape = RoundedCornerShape(4.dp)
                                    )
                                    .clickable { sensitivity = num },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    num.toString(),
                                    color = if (sensitivity == num) Color.Black else Color.White
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        (6..10).forEach { num ->
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(
                                        if (sensitivity == num) AccentColor else Color.DarkGray,
                                        shape = RoundedCornerShape(4.dp)
                                    )
                                    .clickable { sensitivity = num },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    num.toString(),
                                    color = if (sensitivity == num) Color.Black else Color.White
                                )
                            }
                        }
                    }
                }

                // Exposure Slider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Exposición (Oscurecer fondo)", style = MaterialTheme.typography.bodyMedium)
                        Text(exposure.toString())
                    }
                    Slider(
                        value = exposure.toFloat(),
                        onValueChange = { exposure = it.toInt() },
                        valueRange = 10f..1200f,
                        colors = SliderDefaults.colors(
                            thumbColor = AccentColor,
                            activeTrackColor = AccentColor
                        )
                    )
                }

                // Gain Slider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Ganancia Sensibilidad", style = MaterialTheme.typography.bodyMedium)
                        Text(gain.toString())
                    }
                    Slider(
                        value = gain.toFloat(),
                        onValueChange = { gain = it.toInt() },
                        valueRange = 0f..30f,
                        colors = SliderDefaults.colors(
                            thumbColor = AccentColor,
                            activeTrackColor = AccentColor
                        )
                    )
                }

                // Distance Slider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Distancia a la diana (Metros)", style = MaterialTheme.typography.bodyMedium)
                        Text(String.format("%.1f m", distance))
                    }
                    Slider(
                        value = distance,
                        onValueChange = { distance = it },
                        valueRange = 1f..25f,
                        steps = 48,
                        colors = SliderDefaults.colors(
                            thumbColor = AccentColor,
                            activeTrackColor = AccentColor
                        )
                    )
                }

                // Lens Slider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Lente de Cámara (mm)", style = MaterialTheme.typography.bodyMedium)
                        Text(String.format("%.1f mm", lens))
                    }
                    Slider(
                        value = lens,
                        onValueChange = { lens = it },
                        valueRange = 1f..50f,
                        steps = 98,
                        colors = SliderDefaults.colors(
                            thumbColor = AccentColor,
                            activeTrackColor = AccentColor
                        )
                    )
                }

                // Sound Sensitivity
                Column {
                    Text("Sensibilidad de Sonido (Nivel: $soundSensitivity)", style = MaterialTheme.typography.bodyMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        (1..5).forEach { num ->
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(
                                        if (soundSensitivity == num) AccentColor else Color.DarkGray,
                                        shape = RoundedCornerShape(4.dp)
                                    )
                                    .clickable { soundSensitivity = num },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    num.toString(),
                                    color = if (soundSensitivity == num) Color.Black else Color.White
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        (6..10).forEach { num ->
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(
                                        if (soundSensitivity == num) AccentColor else Color.DarkGray,
                                        shape = RoundedCornerShape(4.dp)
                                    )
                                    .clickable { soundSensitivity = num },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    num.toString(),
                                    color = if (soundSensitivity == num) Color.Black else Color.White
                                )
                            }
                        }
                    }
                }

                Button(
                    onClick = onAdjustFocus,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2ECC71)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🔍 Ajustar Enfoque (Lente)", color = Color.White)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSaveSettings(exposure, gain, distance, lens, sensitivity, soundSensitivity)
                    onDismiss()
                }
            ) {
                Text("Guardar", color = AccentColor)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cerrar", color = Color.LightGray)
            }
        }
    )
}
