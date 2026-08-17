package com.splatt.elite.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.util.Locale
import kotlin.math.sqrt

@Composable
fun StatsDialog(
    shots: List<ShotPoint>,
    calibX: Float,
    calibY: Float,
    distM: Float,
    lensMm: Float,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF1E1E1E),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "📊 Estadísticas de la Sesión",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                if (shots.isEmpty()) {
                    Text(
                        text = "No hay datos de disparos.",
                        color = Color.LightGray,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                } else {
                    val pEff = 0.013f
                    val focalLengthPx = lensMm / pEff
                    val scaleFactor = (distM * 1000.0f) / focalLengthPx

                    var totalScore = 0f
                    var bestScore = 0f
                    var num10s = 0
                    var totalHold10 = 0f

                    val shotMms = mutableListOf<Pair<Float, Float>>()

                    for (shot in shots) {
                        val score = shot.label.toFloatOrNull() ?: 0f
                        totalScore += score
                        if (score > bestScore) bestScore = score
                        if (score >= 10.0f) num10s++
                        totalHold10 += shot.hold10

                        val cx = shot.x - 160.0f - calibX
                        val cy = shot.y - 120.0f - calibY
                        shotMms.add(Pair(cx * scaleFactor, cy * scaleFactor))
                    }

                    val avgScore = totalScore / shots.size
                    val pct10s = (num10s.toFloat() / shots.size) * 100f
                    val avgHold10 = totalHold10 / shots.size

                    // Centroid
                    val sumX = shotMms.sumOf { it.first.toDouble() }.toFloat()
                    val sumY = shotMms.sumOf { it.second.toDouble() }.toFloat()
                    val centroidX = sumX / shots.size
                    val centroidY = sumY / shots.size
                    
                    // Grouping diameter (max distance between any two shots)
                    var maxDist = 0f
                    for (i in 0 until shotMms.size) {
                        for (j in i + 1 until shotMms.size) {
                            val dx = shotMms[i].first - shotMms[j].first
                            val dy = shotMms[i].second - shotMms[j].second
                            val dist = sqrt((dx * dx) + (dy * dy))
                            if (dist > maxDist) maxDist = dist
                        }
                    }

                    // Render Stats
                    StatRow("Disparos Totales", "${shots.size}")
                    StatRow("Puntuación Media", String.format(Locale.US, "%.1f", avgScore))
                    StatRow("Mejor Disparo", String.format(Locale.US, "%.1f", bestScore))
                    StatRow("Porcentaje de 10s", String.format(Locale.US, "%.1f %%", pct10s))
                    StatRow("Estabilidad media en el 10", String.format(Locale.US, "%.1f %%", avgHold10))
                    StatRow("Diámetro Agrupación", String.format(Locale.US, "%.1f mm", maxDist))
                    
                    // Formato del centroide
                    val dirX = if (centroidX > 0) "Derecha" else "Izquierda"
                    val dirY = if (centroidY < 0) "Arriba" else "Abajo" // cy is positive downwards
                    val absX = kotlin.math.abs(centroidX)
                    val absY = kotlin.math.abs(centroidY)
                    val centroidText = String.format(Locale.US, "X: %.1f mm (%s)\nY: %.1f mm (%s)", absX, dirX, absY, dirY)
                    
                    Column {
                        Text("Centroide (Tendencia):", color = Color.LightGray, fontSize = 15.sp)
                        Text(centroidText, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3498DB))
                ) {
                    Text("Cerrar")
                }
            }
        }
    }
}

@Composable
fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = Color.LightGray, fontSize = 15.sp)
        Text(text = value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}
