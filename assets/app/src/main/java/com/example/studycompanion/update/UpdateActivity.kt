package com.example.studycompanion.update

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.studycompanion.BuildConfig
import com.example.studycompanion.data.SettingsStore
import com.example.studycompanion.ui.theme.StudyCompanionTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class UpdateActivity : ComponentActivity() {
    @Inject lateinit var settings: SettingsStore
    private var incoming by mutableStateOf<Uri?>(null)
    private var incomingSerial by mutableIntStateOf(0)
    @Suppress("DEPRECATION") private fun accept(intent: Intent?) {
        incoming=when(intent?.action){
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        incomingSerial++
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent);setIntent(intent);accept(intent) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);accept(intent)
        setContent {
            StudyCompanionTheme(darkThemeOption="SYSTEM") {
                val store=remember{UpdateStore(applicationContext)}
                val scope=rememberCoroutineScope()
                var message by remember{mutableStateOf("ChatGPTやコーディングアプリから保存したAPK・3DSX・sgupdateを取り込めます。")}
                var busy by remember{mutableStateOf(false)}
                var updates by remember{mutableStateOf<List<PublishedUpdate>>(emptyList())}
                suspend fun refresh(){updates=withContext(Dispatchers.IO){listOfNotNull(store.current("android"),store.current("3ds"))}}
                fun importUpdate(uri: Uri){
                    if(busy)return
                    busy=true;message="取り込み・検証中…"
                    scope.launch {
                        try {
                            require(uri.scheme=="content"){"ファイルアプリの共有またはファイル選択を使ってください"}
                            val imported=withContext(Dispatchers.IO){
                                val stream=contentResolver.openInputStream(uri) ?: error("ファイルを開けません")
                                store.importUpdate(stream)
                            }
                            message=imported.joinToString("\n"){if(it.target=="3ds")"3DS更新の配信準備ができました。3DSのUPDATE→INSTALLを押してください。" else "APKを確認しました。「スマホ版を更新」で進めます。"}
                            refresh()
                        } catch(e: Exception){message=e.message ?: "取り込みに失敗しました"} finally {busy=false}
                    }
                }
                val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)importUpdate(uri)}
                LaunchedEffect(Unit){refresh()}
                LaunchedEffect(incomingSerial){incoming?.let{importUpdate(it)};incoming=null}
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                        Text("アプリの更新・アップロード",style=MaterialTheme.typography.headlineSmall)
                        Text("スマホ版 ${BuildConfig.VERSION_NAME} / code ${BuildConfig.VERSION_CODE}")
                        Text(message)
                        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                        Button(onClick={picker.launch(arrayOf("*/*"))},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("更新ファイルを選ぶ")}
                        updates.forEach { update ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                    Text(if(update.target=="3ds")"3DS HBL版・配信中" else "スマホ版・インストール待ち",style=MaterialTheme.typography.titleMedium)
                                    Text(update.label)
                                    Text("${update.size/1024} KB / SHA-256 ${update.sha256.take(16)}…",style=MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        Button(onClick={
                            busy=true;scope.launch {
                                try{val next=withContext(Dispatchers.IO){store.installIntent()};startActivity(next)
                                    message=if(next.action==android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)"このアプリからのインストールを許可して戻り、もう一度「スマホ版を更新」を押してください。" else "Androidの確認画面で更新を完了してください。"
                                }catch(e: Exception){message=e.message ?: "更新画面を開けません"}finally{busy=false}
                            }
                        },enabled=!busy&&updates.any{it.target=="android"},modifier=Modifier.fillMaxWidth()){Text("スマホ版を更新")}
                        OutlinedButton(onClick={lifecycleScope.launch {
                            val token=settings.getOrCreateToken();val port=settings.portFlow.first()
                            startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("http://127.0.0.1:$port/updates?t=${Uri.encode(token)}")))
                        }},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("ブラウザーのアップロード画面")}
                        OutlinedButton(onClick={scope.launch{refresh();message="配信状態を読み直しました"}},enabled=!busy){Text("配信状態を更新")}
                        Text("3DSと同じWi-Fi、またはこのスマホのテザリングに接続し、ホーム画面で親機サーバーをONにしてください。3DSはUPDATE→INSTALL→EXIT後、HBLから起動し直します。",style=MaterialTheme.typography.bodyMedium)
                        Text("初回だけ、この更新対応APKと3DSXを通常の方法で導入してください。今後のAPKは同じ署名キーでビルドしたものを使用します。HOMEメニューのCIA版の入れ替えはFBI等で行ってください。",style=MaterialTheme.typography.bodySmall)
                        TextButton(onClick={finish()}){Text("戻る")}
                    }
                }
            }
        }
    }
}
