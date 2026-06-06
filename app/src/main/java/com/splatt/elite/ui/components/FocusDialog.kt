package com.splatt.elite.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.splatt.elite.ui.theme.AccentColor

@Composable
fun FocusDialog(
    focusValue: Int,
    onStartFocus: () -> Unit,
    onStopFocus: () -> Unit,
    onDismiss: () -> Unit
) {
    var maxFocusSeen by remember { mutableStateOf(10) }

    // Start focus mode when dialog opens, stop when closes
    DisposableEffect(Unit) {
        onStartFocus()
        onDispose {
            onStopFocus()
        }
    }

    // Keep track of the maximum value seen to scale the gauge
    LaunchedEffect(focusValue) {
        if (focusValue > maxFocusSeen) {
            maxFocusSeen = focusValue
        }
    }

    val progress = if (maxFocusSeen > 0) (focusValue.toFloat() / maxFocusSeen.toFloat()).coerceIn(0f, 1f) else 0f
    val animatedProgress by animateFloatAsState(targetValue = progress, label = "focusProgress")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Enfoque de Cámara (Ciego)",
                style = MaterialTheme.typography.titleLarge,
                color = AccentColor
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "Gira la lente lentamente. Busca el valor máximo posible en el medidor para lograr la mayor nitidez.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Background Arc
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawArc(
                            color = Color.DarkGray,
                            startAngle = 135f,
                            sweepAngle = 270f,
                            useCenter = false,
                            style = Stroke(width = 16.dp.toPx(), cap = StrokeCap.Round)
                        )
                        // Foreground Arc
                        drawArc(
                            color = if (animatedProgress > 0.8f) Color(0xFF2ECC71) else AccentColor,
                            startAngle = 135f,
                            sweepAngle = 270f * animatedProgress,
                            useCenter = false,
                            style = Stroke(width = 16.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                    
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "$focusValue",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "MAX: $maxFocusSeen",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Terminar", color = AccentColor)
            }
        }
    )
}
