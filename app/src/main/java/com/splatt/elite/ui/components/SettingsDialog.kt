package com.splatt.elite.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.splatt.elite.ui.theme.AccentColor

@Composable
fun SettingsDialog(
    currentDistance: Float,
    onDismiss: () -> Unit,
    onSaveDistance: (Float) -> Unit
) {
    var distance by remember { mutableFloatStateOf(currentDistance) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "DISTANCIA A LA DIANA",
                style = MaterialTheme.typography.titleLarge,
                color = AccentColor
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Distancia para calcular puntuaciones")
                    Text(String.format("%.1f m", distance), color = AccentColor)
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
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSaveDistance(distance)
                    onDismiss()
                }
            ) {
                Text("GUARDAR", color = AccentColor)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCELAR", color = Color.LightGray)
            }
        }
    )
}
