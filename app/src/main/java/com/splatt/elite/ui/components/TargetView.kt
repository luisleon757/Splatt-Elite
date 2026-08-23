package com.splatt.elite.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.splatt.elite.network.BatteryStatus
import com.splatt.elite.ui.theme.AccentColor

data class ShotPoint(val x: Float, val y: Float, val label: String, val timeMs: Long = 0L, val hold10: Float = 0f, val hold9: Float = 0f)
data class TracePoint(val x: Float, val y: Float, val color: Color, val timeMs: Long = 0L)

val TRACE_PUNTERIA_COLOR = Color(0xFF00D9FF)
val TRACE_PRE_COLOR = Color(0xFFFFD600)
val TRACE_POST_COLOR = Color(0xFFFF2DAA)

@Composable
fun TargetView(
    modifier: Modifier = Modifier,
    isLightMode: Boolean = false,
    zoomFactor: Float = 1.0f,
    currentTargetX: Float = 0.0f,
    currentTargetY: Float = 0.0f,
    isTargetVisible: Boolean = false,
    calibX: Float = 0.0f,
    calibY: Float = 0.0f,
    distanceM: Float = 10.0f,
    lensMm: Float = 25.0f,
    shots: List<ShotPoint> = emptyList(),
    trace: List<TracePoint> = emptyList(),
    calibShots: List<Offset> = emptyList()
) {
    var showTracePunteria by remember { androidx.compose.runtime.mutableStateOf(true) }
    var showTracePre by remember { androidx.compose.runtime.mutableStateOf(true) }
    var showTracePost by remember { androidx.compose.runtime.mutableStateOf(true) }
    val batteryPercent by BatteryStatus.percent.collectAsState()
    val bgColor = Color(0xFF1E1E1E)
    val paperColor = if (isLightMode) Color(0xFFF0E5D8) else Color(0xFF111111)
    val targetBlackColor = if (isLightMode) Color(0xFF111111) else Color(0xFFF0E5D8)

    Box(
        modifier = modifier.background(bgColor)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasW = size.width
            val canvasH = size.height
            val centerX = canvasW / 2
            val centerY = canvasH / 2

            val targetSizeMm = 170.0f
            val targetRadiusPx = (minOf(canvasW, canvasH) * 0.9f) / 2.0f
            val mmToPx = targetRadiusPx / (targetSizeMm / 2.0f)

            fun toMm(rx: Float, ry: Float): Offset {
                val pEff = 0.013f
                val focalLengthPx = lensMm / pEff
                val scaleFactor = (distanceM * 1000.0f) / focalLengthPx
                val cx = (rx - 160.0f - calibX) * scaleFactor
                val cy = (ry - 120.0f - calibY) * scaleFactor
                return Offset(-cx, -cy)
            }

            fun toCanvasCoordinates(offsetMm: Offset): Offset {
                val pxX = centerX + offsetMm.x * mmToPx * zoomFactor
                val pxY = centerY + offsetMm.y * mmToPx * zoomFactor
                return Offset(pxX, pxY)
            }

            val paperRadius = targetRadiusPx * zoomFactor
            drawRect(
                color = paperColor,
                topLeft = Offset(centerX - paperRadius, centerY - paperRadius),
                size = androidx.compose.ui.geometry.Size(paperRadius * 2, paperRadius * 2)
            )

            val blackRadiusPx = (59.5f / 2.0f) * mmToPx * zoomFactor
            drawCircle(
                color = targetBlackColor,
                radius = blackRadiusPx,
                center = Offset(centerX, centerY)
            )

            val issfD = floatArrayOf(155.5f, 139.5f, 123.5f, 107.5f, 91.5f, 75.5f, 59.5f, 43.5f, 27.5f, 11.5f)
            for (i in issfD.indices) {
                val d = issfD[i]
                val rPx = (d / 2.0f) * mmToPx * zoomFactor
                val strokeColor = if (isLightMode) {
                    if (d <= 59.5f) Color(0x99FFFFFF) else Color(0x66000000)
                } else {
                    if (d <= 59.5f) Color(0x66000000) else Color(0x99FFFFFF)
                }

                drawCircle(
                    color = strokeColor,
                    radius = rPx,
                    center = Offset(centerX, centerY),
                    style = Stroke(width = 1.0f)
                )

                val numberLabel = (i + 1).toString()
                if (d > 11.5f) {
                    val labelOffsetMm = (d / 2.0f) - 4.0f
                    val paint = Paint().asFrameworkPaint().apply {
                        val textColorArgb = if (isLightMode) {
                            if (d <= 59.5f) Color.White else Color.DarkGray
                        } else {
                            if (d <= 59.5f) Color.DarkGray else Color.White
                        }
                        color = textColorArgb.toArgb()
                        textSize = (4.0f * mmToPx * zoomFactor).coerceIn(12f, 40f)
                        textAlign = android.graphics.Paint.Align.CENTER
                        isAntiAlias = true
                    }

                    val textYCorrection = paint.textSize / 3.0f
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

            drawCircle(
                color = if (isLightMode) Color(0x66FFFFFF) else Color(0x66000000),
                radius = (5.0f / 2.0f) * mmToPx * zoomFactor,
                center = Offset(centerX, centerY),
                style = Stroke(width = 1.0f)
            )

            // MainActivity conserva toda la traza hasta el inicio de una nueva punterÃ­a.
            // El primer punto rojo es la primera muestra POST y marca el lÃ­mite del disparo.
            val firstPostIndex = trace.indexOfFirst { it.color == Color.Red }
            val shotBoundaryTime = if (firstPostIndex >= 0) trace[firstPostIndex].timeMs else null

            val punteriaPoints: List<TracePoint>
            val prePoints: List<TracePoint>
            val postPoints: List<TracePoint>

            if (firstPostIndex >= 0 && shotBoundaryTime != null) {
                val beforeShot = trace.subList(0, firstPostIndex)
                val preStart = (shotBoundaryTime - 200L).coerceAtLeast(0L)
                punteriaPoints = beforeShot.filter { it.timeMs < preStart }
                prePoints = beforeShot.filter { it.timeMs >= preStart }
                postPoints = trace.subList(firstPostIndex, trace.size)
            } else {
                punteriaPoints = trace
                prePoints = emptyList()
                postPoints = emptyList()
            }

            fun drawTraceSegment(p1: TracePoint, p2: TracePoint, color: Color, strokeWidth: Float) {
                val start = toCanvasCoordinates(toMm(p1.x, p1.y))
                val end = toCanvasCoordinates(toMm(p2.x, p2.y))

                drawLine(
                    color = Color.Black.copy(alpha = 0.70f),
                    start = start,
                    end = end,
                    strokeWidth = strokeWidth + 3.0f
                )
                drawLine(
                    color = color,
                    start = start,
                    end = end,
                    strokeWidth = strokeWidth
                )
            }

            fun drawTrace(
                points: List<TracePoint>,
                color: Color,
                strokeWidth: Float,
                connectFrom: TracePoint? = null
            ) {
                if (points.isEmpty()) return
                if (connectFrom != null) {
                    drawTraceSegment(connectFrom, points.first(), color, strokeWidth)
                }
                for (i in 0 until points.size - 1) {
                    drawTraceSegment(points[i], points[i + 1], color, strokeWidth)
                }
            }

            if (showTracePunteria) {
                drawTrace(punteriaPoints, TRACE_PUNTERIA_COLOR, 3.5f)
            }
            if (showTracePost) {
                val origin = prePoints.lastOrNull() ?: punteriaPoints.lastOrNull()
                drawTrace(postPoints, TRACE_POST_COLOR, 4.5f, origin)
            }
            if (showTracePre) {
                val origin = punteriaPoints.lastOrNull()
                drawTrace(prePoints, TRACE_PRE_COLOR, 5.5f, origin)
            }

            shots.forEachIndexed { index, shot ->
                val shotPosMm = toMm(shot.x, shot.y)
                val canvasPos = toCanvasCoordinates(shotPosMm)

                drawCircle(
                    color = Color(0xFFE74C3C),
                    radius = (4.5f / 2.0f) * mmToPx * zoomFactor,
                    center = canvasPos
                )
                drawCircle(
                    color = Color.White,
                    radius = (4.5f / 2.0f) * mmToPx * zoomFactor,
                    center = canvasPos,
                    style = Stroke(width = 1.5f)
                )

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

            calibShots.forEach { shot ->
                val shotPosMm = toMm(shot.x, shot.y)
                val canvasPos = toCanvasCoordinates(shotPosMm)
                drawCircle(
                    color = Color(0xFF2ECC71),
                    radius = (4.5f / 2.0f) * mmToPx * zoomFactor,
                    center = canvasPos
                )
                drawCircle(
                    color = Color.White,
                    radius = (4.5f / 2.0f) * mmToPx * zoomFactor,
                    center = canvasPos,
                    style = Stroke(width = 1.0f)
                )
            }

            if (isTargetVisible) {
                val targetPosMm = toMm(currentTargetX, currentTargetY)
                val canvasPos = toCanvasCoordinates(targetPosMm)

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

       if (batteryPercent >= 0) {
    val batteryColor = when {
        batteryPercent <= 15 -> Color.Red
        batteryPercent <= 30 -> Color.Yellow
        else -> Color.White
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(10.dp)
    ) {
        Canvas(
            modifier = Modifier
                .width(42.dp)
                .height(24.dp)
        ) {
            val terminalWidth = size.width * 0.08f
            val bodyWidth = size.width - terminalWidth - 3.dp.toPx()
            val strokeWidth = 2.dp.toPx()

            drawRoundRect(
                color = batteryColor,
                size = androidx.compose.ui.geometry.Size(
                    bodyWidth,
                    size.height
                ),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                    4.dp.toPx(),
                    4.dp.toPx()
                ),
                style = Stroke(width = strokeWidth)
            )

            drawRoundRect(
                color = batteryColor,
                topLeft = Offset(
                    bodyWidth + 2.dp.toPx(),
                    size.height * 0.30f
                ),
                size = androidx.compose.ui.geometry.Size(
                    terminalWidth,
                    size.height * 0.40f
                ),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                    1.dp.toPx(),
                    1.dp.toPx()
                )
            )

            val fillFraction =
                (batteryPercent.coerceIn(0, 100) / 100f)

            val innerPadding = 4.dp.toPx()
            val usableWidth =
                (bodyWidth - innerPadding * 2).coerceAtLeast(0f)

            drawRoundRect(
                color = batteryColor,
                topLeft = Offset(
                    innerPadding,
                    innerPadding
                ),
                size = androidx.compose.ui.geometry.Size(
                    usableWidth * fillFraction,
                    (size.height - innerPadding * 2)
                        .coerceAtLeast(0f)
                ),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                    2.dp.toPx(),
                    2.dp.toPx()
                )
            )
        }

        Text(
            text = "$batteryPercent%",
            color = batteryColor,
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                onClick = { showTracePunteria = !showTracePunteria },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (showTracePunteria) TRACE_PUNTERIA_COLOR else Color(0xAA4A4A4A),
                    contentColor = if (showTracePunteria) Color.Black else Color.White
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Text("PUNTER\u00CDA", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = { showTracePre = !showTracePre },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (showTracePre) TRACE_PRE_COLOR else Color(0xAA4A4A4A),
                    contentColor = if (showTracePre) Color.Black else Color.White
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Text("PRE 0,2 s", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = { showTracePost = !showTracePost },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (showTracePost) TRACE_POST_COLOR else Color(0xAA4A4A4A),
                    contentColor = Color.White
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Text("POST", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

fun Color.toArgb(): Int {
    return (this.value shr 32).toInt()
}



