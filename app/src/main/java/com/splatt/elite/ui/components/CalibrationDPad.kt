package com.splatt.elite.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.splatt.elite.ui.theme.AccentColor
import com.splatt.elite.ui.theme.GlassBg

@Composable
fun CalibrationDPad(
    modifier: Modifier = Modifier,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit
) {
    Box(
        modifier = modifier
            .size(180.dp)
            .clip(CircleShape)
            .background(GlassBg),
        contentAlignment = Alignment.Center
    ) {
        // Inner circle
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(AccentColor.copy(alpha = 0.2f))
        )

        // Arrow Up
        IconButton(
            onClick = onMoveUp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 4.dp)
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowUp,
                contentDescription = "Mover centro arriba",
                tint = Color.White,
                modifier = Modifier.size(44.dp)
            )
        }

        // Arrow Down
        IconButton(
            onClick = onMoveDown,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 4.dp)
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Mover centro abajo",
                tint = Color.White,
                modifier = Modifier.size(44.dp)
            )
        }

        // Arrow Left
        IconButton(
            onClick = onMoveLeft,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 4.dp)
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowLeft,
                contentDescription = "Mover centro a la izquierda",
                tint = Color.White,
                modifier = Modifier.size(44.dp)
            )
        }

        // Arrow Right
        IconButton(
            onClick = onMoveRight,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 4.dp)
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = "Mover centro a la derecha",
                tint = Color.White,
                modifier = Modifier.size(44.dp)
            )
        }
    }
}
