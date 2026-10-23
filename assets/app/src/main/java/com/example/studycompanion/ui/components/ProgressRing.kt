package com.example.studycompanion.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ProgressRing(
    elapsedSec: Int,
    subjectLabel: String?,
    color: Color,
    modifier: Modifier = Modifier,
    ringSize: Dp = 200.dp,
    strokeWidth: Dp = 12.dp
) {
    // 60 minutes = 3600 seconds = 1 full turn (1.0)
    val totalSecForFullTurn = 3600f
    val progress = ((elapsedSec % 3600) / totalSecForFullTurn).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = if (elapsedSec > 0) progress else 0f,
        animationSpec = tween(durationMillis = 500),
        label = "ringProgress"
    )

    val minutes = elapsedSec / 60
    val seconds = elapsedSec % 60
    val timeText = String.format("%02d:%02d", minutes, seconds)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(ringSize)
    ) {
        val trackColor = color.copy(alpha = 0.2f)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokePx = strokeWidth.toPx()
            // Track
            drawCircle(
                color = trackColor,
                style = Stroke(width = strokePx)
            )
            // Progress Arc
            val sweepAngle = animatedProgress * 360f
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = if (sweepAngle == 0f && elapsedSec > 0) 1f else sweepAngle,
                useCenter = false,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (elapsedSec > 0 || subjectLabel != null) timeText else "--:--",
                style = MaterialTheme.typography.displayLarge.copy(fontSize = 38.sp),
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subjectLabel != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "● $subjectLabel",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = color
                )
            } else {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "待機中",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
