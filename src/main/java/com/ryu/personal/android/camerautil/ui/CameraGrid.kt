package com.ryu.personal.android.camerautil.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

@Composable
fun CameraGrid(
    width: Dp,
    height: Dp,
    color: Color = Color.White,
    strokeWidth: Float = 2f
) {
    Canvas(
        modifier = Modifier.size(width, height)
    ) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        for (i in 1..2) {
            val y = canvasHeight / 3 * i
            drawLine(
                color = color,
                alpha = 0.5f,
                start = Offset(0f, y),
                end = Offset(canvasWidth, y),
                strokeWidth = strokeWidth
            )
        }

        // 세로선 그리기 (가로 방향으로 3분할)
        for (i in 1..2) {
            val x = canvasWidth / 3 * i
            drawLine(
                color = color,
                alpha = 0.5f,
                start = Offset(x, 0f),
                end = Offset(x, canvasHeight),
                strokeWidth = strokeWidth
            )
        }
    }
}