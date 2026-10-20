package com.catlaser.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import com.catlaser.app.ui.theme.AccentOrange
import com.catlaser.app.ui.theme.AccentRed

/**
 * Live preview as in the web UI: grid, hinted hotzone,
 * trail of the last ~90 positions and the laser dot.
 */
@Composable
fun LivePreview(
    trail: List<Pair<Float, Float>>,
    laserOn: Boolean,
    hotzoneEnabled: Boolean,
    hotzoneSize: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier.fillMaxWidth().aspectRatio(1.6f)) {
        val w = size.width
        val h = size.height
        drawRect(Color(0xFF0A0A10))

        if (hotzoneEnabled) {
            drawRect(
                color = AccentOrange.copy(alpha = 0.25f),
                topLeft = Offset(w * (0.5f - hotzoneSize / 2), h * (0.5f - hotzoneSize / 2)),
                size = Size(w * hotzoneSize, h * hotzoneSize),
                style = Stroke(width = 1.5f)
            )
        }

        val grid = Color.White.copy(alpha = 0.07f)
        for (i in 1..3) {
            drawLine(grid, Offset(w * i / 4, 0f), Offset(w * i / 4, h), strokeWidth = 1f)
            drawLine(grid, Offset(0f, h * i / 4), Offset(w, h * i / 4), strokeWidth = 1f)
        }

        for (i in 1 until trail.size) {
            val a = i.toFloat() / trail.size
            val p0 = trail[i - 1]
            val p1 = trail[i]
            drawLine(
                AccentRed.copy(alpha = a * 0.6f),
                Offset(p0.first * w, p0.second * h),
                Offset(p1.first * w, p1.second * h),
                strokeWidth = 3f
            )
        }

        trail.lastOrNull()?.let { p ->
            val c = Offset(p.first * w, p.second * h)
            if (laserOn) drawCircle(AccentRed.copy(alpha = 0.25f), radius = 20f, center = c)
            drawCircle(if (laserOn) AccentRed else Color(0xFF555555), radius = 9f, center = c)
        }
    }
}
