package com.example.studycompanion.character

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CharacterSettings() {
    val context=LocalContext.current
    val store=remember { CharacterStore(context) }
    val scope=rememberCoroutineScope()
    var url by remember { mutableStateOf(store.aiUrl) }
    var token by remember { mutableStateOf(store.aiToken) }
    var enabled by remember { mutableStateOf(store.nightEnabled) }
    var status by remember { mutableStateOf(store.lastResult) }
    var busy by remember { mutableStateOf(false) }
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null)scope.launch {
            busy=true
            status=try { withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { store.importModel(it) } };"モデル更新済み。3DSのSYNCで受信できます" }
            catch(e:Exception){"取り込み失敗: ${e.message}"}
            finally{busy=false}
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("キャラクターとモーション",style=MaterialTheme.typography.titleMedium)
            Text("待機・集中・休憩・達成・応援・睡眠の動作を生成します。AI未設定でも48パターンを端末内で生成できます。")
            OutlinedButton(onClick={
                if(!com.example.studycompanion.widget.CharacterWidgetProvider.pin(context))
                    status="ホーム画面を長押し → ウィジェット → Study Companion → 学習キャラクターを追加してください"
            }) { Text("ホーム画面にキャラクターを追加") }
            Text("ウィジェットのキャラクターをタップで動作変更、状態名で自動／6状態を切り替え、静止で省電力表示。自動では3DSの学習開始・終了に反応します。",style=MaterialTheme.typography.bodySmall)
            Row { Text("夜間（0〜7時）・充電中に自動生成",Modifier.weight(1f));Switch(enabled,{enabled=it;store.nightEnabled=it;MotionGeneration.schedule(context)}) }
            Text("実行時刻はAndroidが調整します。3DS起動中は10分ごと、またはSYNCで更新を受信します。",style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(url,{url=it},label={Text("AIリレーURL（空欄で端末内生成）")},singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(token,{token=it},label={Text("リレー認証トークン")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
            Button(onClick={store.aiUrl=url;store.aiToken=token;status="接続設定を保存しました"},enabled=!busy){Text("接続設定を保存")}
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick={scope.launch {
                    busy=true
                    try{store.aiUrl=url;store.aiToken=token;MotionGeneration.generate(store,0);status=store.lastResult}
                    catch(e:Exception){status="生成失敗・前の動作を保持: ${e.message}"}
                    finally{busy=false}
                }},enabled=!busy){Text(if(busy)"処理中…" else "今すぐ生成")}
                OutlinedButton(onClick={importer.launch(arrayOf("application/octet-stream","*/*"))},enabled=!busy){Text("モデル更新")}
            }
            Text(status,style=MaterialTheme.typography.bodySmall)
            Text("AIは安全な動作パラメータを提案し、3DSで骨格アニメーションを合成します。学習済みの動画・モーション生成モデルではありません。",style=MaterialTheme.typography.bodySmall)
        }
    }
    Spacer(Modifier.height(16.dp))
}
