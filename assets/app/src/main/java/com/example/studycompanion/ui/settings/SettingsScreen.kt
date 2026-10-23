package com.example.studycompanion.ui.settings

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Toast
import com.example.studycompanion.core.NetworkUtils
import com.example.studycompanion.domain.models.Subject
import com.example.studycompanion.ui.components.ColorPickerRow
import com.example.studycompanion.ui.components.QrCodeImage
import com.example.studycompanion.ui.theme.parseHexColor

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val token by viewModel.token.collectAsState(initial = "")
    val port by viewModel.port.collectAsState(initial = 8765)
    val autoStart by viewModel.autoStart.collectAsState(initial = false)
    val studyplusEnabled by viewModel.studyplusEnabled.collectAsState(initial = false)
    val darkThemeOption by viewModel.darkThemeOption.collectAsState(initial = "SYSTEM")
    val subjects by viewModel.allSubjects.collectAsState()
    val endpoints by viewModel.localEndpoints.collectAsState()
    val scope=rememberCoroutineScope()

    var showTokenMasked by remember { mutableStateOf(true) }
    var showQrDialog by remember { mutableStateOf(false) }
    var showAddSubjectDialog by remember { mutableStateOf(false) }
    var editingSubject by remember { mutableStateOf<Subject?>(null) }
    var showClearDataDialog by remember { mutableStateOf(false) }

    var tethering by rememberSaveable { mutableStateOf(true) }
    var manualIp by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(endpoints,tethering) {
        if (!tethering && manualIp.isBlank()) manualIp=endpoints.firstOrNull()?.ip ?: ""
    }
    val activeIp = if(tethering) "auto" else manualIp.trim()
    val pairUrl = "studygadget://pair?ip=$activeIp&port=$port&token=$token"
    val configTxtLine = NetworkUtils.pairingLine(activeIp,port,token)
    val saveConfig=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val content=configTxtLine
        if(uri!=null && content!=null) scope.launch {
            val saved=runCatching {
                withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openOutputStream(uri,"wt")).use { it.write(content.toByteArray(Charsets.US_ASCII)) }
                }
            }.isSuccess
            Toast.makeText(context,if(saved)"config.txtを保存しました。3DSのSD内 /3ds/studygadget/ に配置してください" else "設定ファイルを保存できませんでした",Toast.LENGTH_LONG).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Button(onClick={context.startActivity(android.content.Intent(context,com.example.studycompanion.update.UpdateActivity::class.java))},modifier=Modifier.fillMaxWidth()) { Text("アプリの更新・アップロード") }
        Spacer(Modifier.height(12.dp))
        com.example.studycompanion.character.CharacterSettings()
        // Section 1: Pairing & Token
        Text(
            text = "ペアリング設定",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text("接続方法",style=MaterialTheme.typography.titleSmall)
                Row(verticalAlignment=Alignment.CenterVertically) {
                    RadioButton(tethering,{tethering=true})
                    Text("親機スマホのテザリング（IP自動）")
                }
                Row(verticalAlignment=Alignment.CenterVertically) {
                    RadioButton(!tethering,{tethering=false})
                    Text("同一Wi-Fi・IPを指定")
                }
                if(tethering) {
                    Text("このスマホのテザリングを2.4GHz・WPA2でONにし、3DSを接続してください。新しい3DS版がゲートウェイから親機IPを取得します。通常のWi-Fiルーター経由ではIP指定を選びます。",style=MaterialTheme.typography.bodySmall)
                } else {
                    endpoints.forEach { endpoint ->
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            RadioButton(manualIp==endpoint.ip,{manualIp=endpoint.ip})
                            Text("${endpoint.ip} (${if(endpoint.hotspot) "テザリング候補" else "Wi-Fi / LAN"})")
                        }
                    }
                    OutlinedTextField(manualIp,{manualIp=it},label={Text("親機のIPv4アドレス")},singleLine=true,modifier=Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "認証トークン",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        val displayToken = if (showTokenMasked) "••••••••••••••••••••••••••••••••" else token
                        Text(
                            text = displayToken,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row {
                        IconButton(onClick = { showTokenMasked = !showTokenMasked }) {
                            Icon(
                                if (showTokenMasked) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = "マスク切替"
                            )
                        }
                        IconButton(onClick = { viewModel.copyToClipboard(context, token, "トークン") }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "トークンコピー")
                        }
                        IconButton(onClick = { viewModel.regenerateToken() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "トークン再生成")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { showQrDialog = true },
                        enabled = configTxtLine != null,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.QrCode, contentDescription = "QRコード")
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("QRコード表示")
                    }

                    OutlinedButton(
                        onClick = { configTxtLine?.let { viewModel.copyToClipboard(context, it.trim(), "config.txtの1行") } },
                        enabled = configTxtLine != null,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "1行コピー")
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("config.txt1行コピー")
                    }
                }
                OutlinedButton(onClick={saveConfig.launch("config.txt")},enabled=configTxtLine!=null,modifier=Modifier.fillMaxWidth()) { Text("3DS用config.txtを保存") }
                Text("テザリングを切り替えた後もIP自動なら設定し直す必要はありません。アプリのサーバーを起動し、3DSでSYNCを押してください。",style=MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Section 2: Subject Management
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "科目マスタ管理 (最大8科目)",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            IconButton(onClick = { showAddSubjectDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "科目追加", tint = MaterialTheme.colorScheme.primary)
            }
        }
        Text(
            text = "※変更内容は3DSの次回同期(SYNC/config)で自動反映されます",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                subjects.sortedBy { it.sortOrder }.forEachIndexed { index, subj ->
                    if (index > 0) {
                        Divider(modifier = Modifier.padding(vertical = 8.dp))
                    }

                    val color = parseHexColor(subj.colorHex)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(color)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = subj.label,
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                                        color = if (subj.archived) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                                    )
                                    if (subj.archived) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "(アーカイブ)",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Text(
                                    text = "ID: ${subj.id} | 順序: ${subj.sortOrder}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Row {
                            IconButton(onClick = { editingSubject = subj }) {
                                Icon(Icons.Default.Edit, contentDescription = "編集")
                            }
                            IconButton(onClick = { viewModel.toggleArchiveSubject(subj) }) {
                                Icon(
                                    if (subj.archived) Icons.Default.Unarchive else Icons.Default.Archive,
                                    contentDescription = "アーカイブ切替"
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Section 3: Server & System Settings
        Text(
            text = "サーバー設定",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                var portInput by remember(port) { mutableStateOf(port.toString()) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "待受ポート番号",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "標準: 8765",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    OutlinedTextField(
                        value = portInput,
                        onValueChange = { input ->
                            portInput = input.filter { it.isDigit() }
                            val newP = portInput.toIntOrNull()
                            if (newP != null && newP in 1024..65535) {
                                viewModel.setPort(newP)
                            }
                        },
                        modifier = Modifier.width(100.dp)
                    )
                }

                Divider(modifier = Modifier.padding(vertical = 12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "端末起動時に自動開始",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Android起動時にサーバーをバックグラウンド開始",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Switch(
                        checked = autoStart,
                        onCheckedChange = { viewModel.setAutoStart(it) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Section 4: External Integrations & Theme
        Text(
            text = "データ・テーマ・拡張設定",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Studyplus 連携 (スタブ)",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "将来的な公式API連携の有効化フラグ",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Switch(
                        checked = studyplusEnabled,
                        onCheckedChange = { viewModel.setStudyplusEnabled(it) }
                    )
                }

                Divider(modifier = Modifier.padding(vertical = 12.dp))

                Text(
                    text = "テーマ切替",
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("SYSTEM" to "システム", "DARK" to "ダーク", "LIGHT" to "ライト").forEach { (opt, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { viewModel.setDarkThemeOption(opt) }
                        ) {
                            RadioButton(
                                selected = darkThemeOption == opt,
                                onClick = { viewModel.setDarkThemeOption(opt) }
                            )
                            Text(label)
                        }
                    }
                }

                Divider(modifier = Modifier.padding(vertical = 12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { viewModel.exportCsvAndShare(context) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("CSV書き出し")
                    }

                    OutlinedButton(
                        onClick = { showClearDataDialog = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("全データ削除")
                    }
                }
            }
        }
    }

    // QR Code Dialog
    if (showQrDialog) {
        AlertDialog(
            onDismissRequest = { showQrDialog = false },
            title = { Text("ペアリング用QRコード") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    QrCodeImage(content = pairUrl, size = 220.dp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = pairUrl,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showQrDialog = false }) {
                    Text("閉じる")
                }
            }
        )
    }

    // Add Subject Dialog
    if (showAddSubjectDialog) {
        var idInput by remember { mutableStateOf("") }
        var labelInput by remember { mutableStateOf("") }
        var selectedColorHex by remember { mutableStateOf("60A5FA") }
        var sortOrderInput by remember { mutableStateOf("${subjects.size}") }

        AlertDialog(
            onDismissRequest = { showAddSubjectDialog = false },
            title = { Text("科目の追加") },
            text = {
                Column {
                    OutlinedTextField(
                        value = idInput,
                        onValueChange = { idInput = it },
                        label = { Text("科目ID (英数)") }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = labelInput,
                        onValueChange = { labelInput = it },
                        label = { Text("表示ラベル (例: MATH)") }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = sortOrderInput,
                        onValueChange = { sortOrderInput = it.filter { c -> c.isDigit() } },
                        label = { Text("並び順番号") }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("カラーの選択:")
                    Spacer(modifier = Modifier.height(6.dp))
                    ColorPickerRow(
                        selectedColorHex = selectedColorHex,
                        onColorSelected = { selectedColorHex = it }
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (idInput.isNotBlank() && labelInput.isNotBlank()) {
                        val order = sortOrderInput.toIntOrNull() ?: subjects.size
                        viewModel.saveSubject(idInput, labelInput, selectedColorHex, order)
                        showAddSubjectDialog = false
                    }
                }) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddSubjectDialog = false }) {
                    Text("キャンセル")
                }
            }
        )
    }

    // Edit Subject Dialog
    if (editingSubject != null) {
        val target = editingSubject!!
        var labelInput by remember(target) { mutableStateOf(target.label) }
        var selectedColorHex by remember(target) { mutableStateOf(target.colorHex) }
        var sortOrderInput by remember(target) { mutableStateOf(target.sortOrder.toString()) }

        AlertDialog(
            onDismissRequest = { editingSubject = null },
            title = { Text("科目の編集 (${target.id})") },
            text = {
                Column {
                    OutlinedTextField(
                        value = labelInput,
                        onValueChange = { labelInput = it },
                        label = { Text("表示ラベル") }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = sortOrderInput,
                        onValueChange = { sortOrderInput = it.filter { c -> c.isDigit() } },
                        label = { Text("並び順番号") }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("カラーの選択:")
                    Spacer(modifier = Modifier.height(6.dp))
                    ColorPickerRow(
                        selectedColorHex = selectedColorHex,
                        onColorSelected = { selectedColorHex = it }
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val order = sortOrderInput.toIntOrNull() ?: target.sortOrder
                    viewModel.updateSubject(target.copy(label = labelInput, colorHex = selectedColorHex, sortOrder = order))
                    editingSubject = null
                }) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingSubject = null }) {
                    Text("キャンセル")
                }
            }
        )
    }

    // Clear Data Confirmation Dialog
    if (showClearDataDialog) {
        AlertDialog(
            onDismissRequest = { showClearDataDialog = false },
            title = { Text("全データの削除") },
            text = { Text("すべての学習記録および登録科目を削除して初期状態に戻します。この操作は取り消せません。本当に実行しますか？") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearAllData()
                        showClearDataDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("削除する")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDataDialog = false }) {
                    Text("キャンセル")
                }
            }
        )
    }
}
