package com.example.studycompanion.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.studycompanion.ui.components.ProgressRing
import com.example.studycompanion.ui.theme.parseHexColor
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val isRunning by viewModel.isServerRunning.collectAsState()
    val serverError by viewModel.serverError.collectAsState()
    val ipList by viewModel.ipList.collectAsState()
    val port by viewModel.port.collectAsState(initial = 8765)
    val live by viewModel.liveSession.collectAsState()
    val todaySec by viewModel.todayTotalSec.collectAsState()
    val recentSessions by viewModel.recentSessions.collectAsState()
    val hasRecentAccess by viewModel.hasRecentAccess.collectAsState()
    val subjectsMap by viewModel.subjectsMap.collectAsState()

    // Calculate live elapsed seconds
    var elapsedSec by remember { mutableStateOf(0) }
    LaunchedEffect(live) {
        while (live != null) {
            val nowSec = System.currentTimeMillis() / 1000.0
            elapsedSec = (nowSec - live!!.start).toInt().coerceAtLeast(0)
            delay(1000)
        }
        elapsedSec = 0
    }

    val displayIp = if (ipList.isNotEmpty()) "${ipList.first()}:$port" else "テザリングは設定のIP自動を使用"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Top Server Control Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (hasRecentAccess) Color(0xFF10B981) else if (isRunning) Color(0xFFF59E0B) else Color.Gray)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isRunning) "サーバー稼働中" else "サーバー停止中",
                            style = MaterialTheme.typography.headlineMedium.copy(fontSize = 16.sp),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "接続先: $displayIp (タップでコピー)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable {
                            if(ipList.isNotEmpty())viewModel.copyToClipboard(context, displayIp, "接続先アドレス")
                        }
                    )
                }

                Switch(
                    checked = isRunning,
                    onCheckedChange = { viewModel.toggleServer(context) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        serverError?.let { Text(it, color=MaterialTheme.colorScheme.error, modifier=Modifier.padding(bottom=12.dp)) }
        // Live Session Ring Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "LIVE SESSION",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                val liveSubjectObj = live?.let { subjectsMap[it.subject] }
                val ringColor = liveSubjectObj?.let { parseHexColor(it.colorHex) } ?: MaterialTheme.colorScheme.primary
                val subjectLabel = liveSubjectObj?.label ?: live?.subject

                ProgressRing(
                    elapsedSec = elapsedSec,
                    subjectLabel = subjectLabel,
                    color = ringColor,
                    ringSize = 210.dp,
                    strokeWidth = 14.dp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Today Total Time Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "TODAY",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                val hours = todaySec / 3600
                val mins = (todaySec % 3600) / 60
                val timeDisplay = "${hours}h${String.format("%02d", mins)}m"
                Text(
                    text = timeDisplay,
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Recent Sessions Card
        if (recentSessions.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "直近のセッション",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    recentSessions.forEach { session ->
                        val subjectObj = subjectsMap[session.subject]
                        val badgeColor = subjectObj?.let { parseHexColor(it.colorHex) } ?: MaterialTheme.colorScheme.primary
                        val label = subjectObj?.label ?: session.subject
                        val mins = session.durationSec / 60
                        val secs = session.durationSec % 60

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(badgeColor)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Text(
                                text = "${session.start.substring(11, 16)}  (${mins}分${secs}秒)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
