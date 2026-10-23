package com.example.studycompanion.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.studycompanion.domain.DayStat

@Composable
fun BarChart(
    days: List<DayStat>,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
    highlightColor: Color = MaterialTheme.colorScheme.tertiary
) {
    val maxSec = days.maxOfOrNull { it.sec }?.coerceAtLeast(1) ?: 1
    val todayDateStr = java.time.LocalDate.now().toString()

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            days.forEach { dayStat ->
                val fraction = (dayStat.sec.toFloat() / maxSec).coerceIn(0.05f, 1f)
                val isToday = dayStat.date == todayDateStr
                val color = if (isToday) highlightColor else barColor

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .height(100.dp)
                    ) {
                        val barHeight = size.height * fraction
                        val top = size.height - barHeight
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(0f, top),
                            size = Size(size.width, barHeight),
                            cornerRadius = CornerRadius(8f, 8f)
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            days.forEach { dayStat ->
                val isToday = dayStat.date == todayDateStr
                Text(
                    text = dayStat.d,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        color = if (isToday) highlightColor else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}
