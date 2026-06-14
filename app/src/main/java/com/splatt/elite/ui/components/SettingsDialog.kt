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
import androidx.compose.ui.unit.sp
import com.splatt.elite.network.SplattStatus
import com.splatt.elite.ui.theme.AccentColor

@Composable
fun SettingsDialog(
    status: SplattStatus,
    currentDistance: Float,
    currentSensitivity: Int,
    currentSound: Int,
    currentExposure: Int,
    onDismiss: () -> Unit,
    onSaveSettings: (exposure: Int, distance: Float, sensitivity: Int, sound: Int) -> Unit
) {
    var exposure by remember { mutableStateOf(currentExposure.coerceIn(10, 1200)) }
    var distance by remember { mutableFloatStateOf(currentDistance) }
    var sensitivity by remember { mutableIntStateOf(currentSensitivity) }
    var soundSensitivity by remember { mutableIntStateOf(currentSound) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Ajustes de Visión",
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
                // Target sensitivity level
                Column {
                    Text("Detección de Diana (Contraste: $sensitivity)", style = MaterialTheme.typography.bodyMedium)
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

                // Eliminado Gain Slider

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

                // Eliminado Lens Slider

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

                // Eliminado botón de Ajustar Enfoque
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSaveSettings(exposure, distance, sensitivity, soundSensitivity)
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
