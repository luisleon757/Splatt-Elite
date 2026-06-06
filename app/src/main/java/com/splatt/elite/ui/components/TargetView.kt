package com.splatt.elite.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import com.splatt.elite.ui.theme.AccentColor

data class ShotPoint(val x: Float, val y: Float, val label: String)
data class TracePoint(val x: Float, val y: Float, val color: Color)

@Composable
fun TargetView(
    modifier: Modifier = Modifier,
    isLightMode: Boolean = false,
    zoomFactor: Float = 1.0f,
    currentLaserX: Float = 0.0f,
    currentLaserY: Float = 0.0f,
    isLaserVisible: Boolean = false,
    calibX: Float = 0.0f,
    calibY: Float = 0.0f,
    distanceM: Float = 10.0f,
    lensMm: Float = 25.0f,
    shots: List<ShotPoint> = emptyList(),
    trace: List<TracePoint> = emptyList()
) {
    val bgColor = if (isLightMode) Color(0xFFFDF5E6) else Color(0xFF1E1E1E)
    val paperColor = Color(0xFFF0E5D8)
    val targetBlackColor = Color(0xFF111111)
    val lineAccent = if (isLightMode) Color(0xFF333333) else Color(0xFFCCCCCC)

    Box(
        modifier = modifier
            .background(bgColor)
    ) {
        Canvas(
            modifier = Modifier.fillMaxSize()
        ) {
            val canvasW = size.width
            val canvasH = size.height
            val centerX = canvasW / 2
            val centerY = canvasH / 2

            // Reference target width is 170mm
            val targetSizeMm = 170.0f
            // We want the target to occupy 90% of the smallest dimension
            val targetRadiusPx = (minOf(canvasW, canvasH) * 0.9f) / 2.0f
            val mmToPx = targetRadiusPx / (targetSizeMm / 2.0f)

            // Function to map ESP32 coordinates (0-320, 0-240) to millimeter offset from center
            fun toMm(rx: Float, ry: Float): Offset {
                val pEff = 0.00896f
                val focalLengthPx = lensMm / pEff
                val scaleFactor = (distanceM * 1000.0f) / focalLengthPx
                
                // Flip mode (standard is flipped/unflipped, we apply standard conversion)
                val cx = -(rx - 160.0f - calibX) * scaleFactor
                val cy = -(ry - 120.0f - calibY) * scaleFactor
                return Offset(cx, cy)
            }

            // Convert MM offsets to Canvas coordinates including Zoom
            fun toCanvasCoordinates(offsetMm: Offset): Offset {
                val pxX = centerX + offsetMm.x * mmToPx * zoomFactor
                val pxY = centerY + offsetMm.y * mmToPx * zoomFactor
                return Offset(pxX, pxY)
            }

            // --- DRAW TARGET PAPER (SQUARE) ---
            val paperRadius = targetRadiusPx * zoomFactor
            drawRect(
                color = paperColor,
                topLeft = Offset(centerX - paperRadius, centerY - paperRadius),
                size = androidx.compose.ui.geometry.Size(paperRadius * 2, paperRadius * 2)
            )

            // --- DRAW BLACK CENTER ---
            // The black region starts from ring 4 (diameter 59.5mm)
            val blackRadiusPx = (59.5f / 2.0f) * mmToPx * zoomFactor
            drawCircle(
                color = targetBlackColor,
                radius = blackRadiusPx,
                center = Offset(centerX, centerY)
            )

            // --- DRAW CONCENTRIC RINGS (ISSF) ---
            val issfD = floatArrayOf(155.5f, 139.5f, 123.5f, 107.5f, 91.5f, 75.5f, 59.5f, 43.5f, 27.5f, 11.5f)
            for (i in issfD.indices) {
                val d = issfD[i]
                val rPx = (d / 2.0f) * mmToPx * zoomFactor
                val strokeColor = if (d <= 59.5f) Color(0x99FFFFFF) else Color(0x66000000)

                drawCircle(
                    color = strokeColor,
                    radius = rPx,
                    center = Offset(centerX, centerY),
                    style = Stroke(width = 1.0f)
                )

                // Draw numbers for rings (1 to 8)
                // ISSF 10m Pistol targets usually don't print the 9, but we print up to 9
                val numberLabel = (i + 1).toString()
                if (d > 11.5f) { // d > 11.5f excludes the 10 ring. Includes 155.5f (ring 1)
                    val labelOffsetMm = (d / 2.0f) - 4.0f
                    val paint = Paint().asFrameworkPaint().apply {
                        color = (if (d <= 59.5f) Color.White else Color.DarkGray).toArgb()
                        textSize = (4.0f * mmToPx * zoomFactor).coerceIn(12f, 40f)
                        textAlign = android.graphics.Paint.Align.CENTER
                        isAntiAlias = true
                    }
                    
                    val textYCorrection = paint.textSize / 3.0f

                    // Draw in 4 cardinal directions
                    val directions = listOf(
                        Offset(0f, -labelOffsetMm),
                        Offset(0f, labelOffsetMm),
                        Offset(-labelOffsetMm, 0f),
                        Offset(labelOffsetMm, 0f)
                    )

                    for (dir in directions) {
                        val canvasPos = toCanvasCoordinates(dir)
                        drawContext.canvas.nativeCanvas.drawText(
                            numberLabel,
                            canvasPos.x,
                            canvasPos.y + textYCorrection,
                            paint
                        )
                    }
                }
            }

            // Draw Inner Ten Center Dot
            drawCircle(
                color = Color(0x66FFFFFF),
                radius = (5.0f / 2.0f) * mmToPx * zoomFactor,
                center = Offset(centerX, centerY),
                style = Stroke(width = 1.0f)
            )

            // --- DRAW LIVE TRAJECTORY (TRACE) ---
            if (trace.size > 1) {
                for (i in 0 until trace.size - 1) {
                    val p1 = toCanvasCoordinates(toMm(trace[i].x, trace[i].y))
                    val p2 = toCanvasCoordinates(toMm(trace[i + 1].x, trace[i + 1].y))
                    
                    drawLine(
                        color = trace[i].color,
                        start = p1,
                        end = p2,
                        strokeWidth = 4.5f
                    )
                }
            }

            // --- DRAW PREVIOUS SHOTS ---
            shots.forEachIndexed { index, shot ->
                val shotPosMm = toMm(shot.x, shot.y)
                val canvasPos = toCanvasCoordinates(shotPosMm)
                
                // Draw red shot mark
                drawCircle(
                    color = Color(0xFFE74C3C),
                    radius = (4.5f / 2.0f) * mmToPx * zoomFactor, // standard 4.5mm pellet size
                    center = canvasPos
                )
                drawCircle(
                    color = Color.White,
                    radius = (4.5f / 2.0f) * mmToPx * zoomFactor,
                    center = canvasPos,
                    style = Stroke(width = 1.5f)
                )

                // Label/Number of the shot
                val paint = Paint().asFrameworkPaint().apply {
                    color = Color.White.toArgb()
                    textSize = (3.5f * mmToPx * zoomFactor).coerceIn(14f, 32f)
                    textAlign = android.graphics.Paint.Align.CENTER
                    isAntiAlias = true
                }
                drawContext.canvas.nativeCanvas.drawText(
                    (index + 1).toString(),
                    canvasPos.x,
                    canvasPos.y + (paint.textSize / 3.0f),
                    paint
                )
            }

            // --- DRAW LIVE LASER (AIMING) POINT ---
            if (isLaserVisible) {
                val laserPosMm = toMm(currentLaserX, currentLaserY)
                val canvasPos = toCanvasCoordinates(laserPosMm)
                
                // Draw yellow/orange crosshair or glowing dot
                drawCircle(
                    color = AccentColor,
                    radius = 8f,
                    center = canvasPos
                )
                drawCircle(
                    color = Color.White,
                    radius = 14f,
                    center = canvasPos,
                    style = Stroke(width = 2.0f)
                )
            }
        }
    }
}

// Extension to convert Compose Color to Android Color Int
fun Color.toArgb(): Int {
    return (this.value shr 32).toInt()
}
