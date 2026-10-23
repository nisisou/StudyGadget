package com.example.studycompanion.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.studycompanion.domain.ConfigSubjectDto
import com.example.studycompanion.domain.SubjectStat
import com.example.studycompanion.ui.theme.parseHexColor

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DonutChart(
    subjectStats: List<SubjectStat>,
    configSubjects: List<ConfigSubjectDto>,
    modifier: Modifier = Modifier,
    chartSize: Dp = 140.dp,
    strokeWidth: Dp = 24.dp
) {
    val totalSec = subjectStats.sumOf { it.sec }.coerceAtLeast(1)
    val colorMap = configSubjects.associate { it.id to parseHexColor(it.color) }
    val labelMap = configSubjects.associate { it.id to it.label }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(chartSize),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(chartSize)) {
                val strokePx = strokeWidth.toPx()
                if (subjectStats.isEmpty() || subjectStats.all { it.sec == 0 }) {
                    drawCircle(
                        color = Color.Gray.copy(alpha = 0.3f),
                        style = Stroke(width = strokePx)
                    )
                } else {
                    var startAngle = -90f
                    for (stat in subjectStats) {
                        if (stat.sec <= 0) continue
                        val sweepAngle = (stat.sec.toFloat() / totalSec) * 360f
                        val color = colorMap[stat.id] ?: Color.Gray
                        drawArc(
                            color = color,
                            startAngle = startAngle,
                            sweepAngle = sweepAngle,
                            useCenter = false,
                            style = Stroke(width = strokePx)
                        )
                        startAngle += sweepAngle
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            for (stat in subjectStats) {
                val color = colorMap[stat.id] ?: Color.Gray
                val label = labelMap[stat.id] ?: stat.id
                val hours = stat.sec / 3600
                val mins = (stat.sec % 3600) / 60
                val timeStr = "${hours}h${String.format("%02d", mins)}m"

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(color)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "$label $timeStr",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
