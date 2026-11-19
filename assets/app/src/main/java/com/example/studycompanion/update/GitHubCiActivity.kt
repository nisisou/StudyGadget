package com.example.studycompanion.update

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.studycompanion.server.SyncService
import com.example.studycompanion.ui.theme.StudyCompanionTheme
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

class GitHubCiActivity : ComponentActivity() {
    private fun open(url: String) = startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))
    private fun errorText(e: Exception) = when(e) {
        is IOException -> "通信できません。インターネット接続を確認して「状態を確認」を押してください。"
        is SerializationException -> "GitHubの応答を読み取れません。CIの詳細を確認してください。"
        else -> e.message?.take(240) ?: "処理に失敗しました"
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StudyCompanionTheme(darkThemeOption="SYSTEM") {
                val local=remember { CiLocalStore(applicationContext) }
                val scope=rememberCoroutineScope()
                val handler=remember { Handler(Looper.getMainLooper()) }
                var config by remember { mutableStateOf<CiConfig?>(null) }
                var repository by remember { mutableStateOf("nisisou/StudyGadget") }
                var branch by remember { mutableStateOf("main") }
                var token by remember { mutableStateOf("") }
                var showSettings by remember { mutableStateOf(false) }
                var loaded by remember { mutableStateOf(false) }
                var pending by remember { mutableStateOf<CiPending?>(null) }
                var run by remember { mutableStateOf<CiRun?>(null) }
                var target by remember { mutableStateOf("3ds") }
                var autoApply by remember { mutableStateOf(true) }
                var busy by remember { mutableStateOf(false) }
                var message by remember { mutableStateOf("GitHubでビルドし、完成した更新をこのアプリへ取り込みます。") }
                var refreshKey by remember { mutableIntStateOf(0) }
                var importedRun by remember { mutableLongStateOf(0) }
                val active=pending!=null && (run==null || !run!!.complete)
                suspend fun receive(selected: CiRun, connection: CiConfig, apply3ds: Boolean) {
                    busy=true
                    message="更新ファイルを確認しています…"
                    try {
                        val imported=withContext(Dispatchers.IO) {
                            val api=GitHubCiApi(connection)
                            val artifact=api.artifact(selected)
                            val temp=File.createTempFile("github-ci-",".sgupdate",cacheDir)
                            try {
                                var last=0L
                                api.download(artifact,temp) { count,total ->
                                    val now=System.currentTimeMillis()
                                    if(now-last>=500 || count==total) {
                                        last=now
                                        handler.post { message=if(total>0)"受信中 ${count*100/total}%（${count/1024} KB）" else "受信中 ${count/1024} KB" }
                                    }
                                }
                                val result=temp.inputStream().use { UpdateStore(applicationContext).importUpdate(it) }
                                local.imported(selected.id)
                                result
                            } finally { temp.delete() }
                        }
                        importedRun=selected.id
                        message="検証と取り込みが完了しました。「更新・配信画面」で確認できます。"
                        val three=imported.firstOrNull { it.target=="3ds" }
                        val cia=imported.firstOrNull { it.target=="cia" }
                        if(three!=null && apply3ds) {
                            withContext(Dispatchers.IO) { DevAutomation(applicationContext).schedule(three.sha256) }
                            SyncService.start(this@GitHubCiActivity)
                            message=if(cia!=null)
                                "3DSへの自動更新を予約し、FBI用CIAの準備も完了しました。「更新・配信画面を開く」からFBI QRコードを表示できます。"
                            else
                                "3DSへの自動更新を予約しました。3DSをこのスマホに接続してアプリを起動してください。"
                        } else if(cia!=null) {
                            SyncService.start(this@GitHubCiActivity)
                            message="FBI用CIAを取り込み、親機サーバーを起動しました。「更新・配信画面を開く」からQRコードを表示し、3DSのFBIで読み取ってください。"
                        }
                    } catch(e: CancellationException) { throw e }
                    catch(e: Exception) { message=errorText(e) }
                    finally { busy=false }
                }
                LaunchedEffect(Unit) {
                    try {
                        val saved=withContext(Dispatchers.IO) { local.config() to local.state() }
                        config=saved.first;pending=saved.second.pending;importedRun=saved.second.importedRun
                        config?.let { repository=it.repository;branch=it.branch }
                        pending?.let { target=it.target;autoApply=it.autoApply }
                        showSettings=config==null
                    } catch(e: Exception) {
                        showSettings=true;message="保存した接続情報を開けません。トークンを登録し直してください。"
                    } finally { loaded=true }
                }
                LaunchedEffect(loaded,config,pending?.requestId,refreshKey) {
                    val connection=config ?: return@LaunchedEffect
                    var record=pending ?: return@LaunchedEffect
                    if(record.repository!=connection.repository || record.branch!=connection.branch)return@LaunchedEffect
                    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        while(isActive) {
                            if(busy) { delay(1000);continue }
                            try {
                                val found=withContext(Dispatchers.IO) {
                                    val api=GitHubCiApi(connection)
                                    if(record.runId>0)api.run(record.runId) else api.findRequest(record.requestId)
                                }
                                if(found==null) {
                                    message=if(System.currentTimeMillis()-record.createdAt>600000)
                                        "ビルドの受付を確認できません。CIの詳細を確認してください。新しいビルドは自動で重複送信しません。"
                                    else "GitHubのビルド受付を待っています…"
                                } else {
                                    run=found
                                    if(record.runId==0L) {
                                        record=record.copy(runId=found.id)
                                        withContext(Dispatchers.IO) { local.pending(record) }
                                        pending=record
                                    }
                                    message=when {
                                        found.succeeded -> if(importedRun==found.id)"このビルドは取り込み済みです。" else "ビルド成功。更新を受信します…"
                                        found.complete -> "ビルドは ${found.conclusion} で終了しました。「CIの詳細」で原因を確認できます。"
                                        found.status=="queued" || found.status=="waiting" || found.status=="pending" -> "GitHubでビルドの開始を待っています…"
                                        else -> "GitHubでビルド中です。画面を離れてもビルドは続き、戻ると確認を再開します。"
                                    }
                                    if(found.complete) {
                                        if(found.succeeded) {
                                            importedRun=withContext(Dispatchers.IO){local.state().importedRun}
                                            if(importedRun!=found.id)receive(found,connection,record.autoApply)
                                        }
                                        break
                                    }
                                }
                            } catch(e: CancellationException) { throw e }
                            catch(e: Exception) {
                                message=errorText(e)
                                if(e is CiHttpException && e.status in setOf(401,403,404,422))break
                            }
                            delay(10000)
                        }
                    }
                }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text("GitHubでビルド・更新",style=MaterialTheme.typography.headlineSmall)
                        Text("ソースはGitHubに保存されたものを使います。ビルド中にPCを起動しておく必要はありません。")
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                Text(message)
                                if(busy || active)LinearProgressIndicator(Modifier.fillMaxWidth())
                                run?.let { Text("実行 #${it.id}",style=MaterialTheme.typography.bodySmall) }
                            }
                        }
                        if(config!=null) {
                            Text("${config!!.repository} / ${config!!.branch}",style=MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                listOf("3ds" to "3DS版","android" to "スマホ版","both" to "両方").forEach { (value,label) ->
                                    FilterChip(selected=target==value,onClick={target=value},enabled=!busy&&!active,label={Text(label)})
                                }
                            }
                            if(target!="android") {
                                Row {
                                    Checkbox(checked=autoApply,onCheckedChange={autoApply=it},enabled=!busy&&!active)
                                    Column { Text("3DSへ自動更新を予約");Text("親機サーバーを起動します。計測中は終了後に適用します。",style=MaterialTheme.typography.bodySmall) }
                                }
                            }
                            if(target!="3ds")Text("スマホ版はGitHub側の署名キー設定が必要です。Androidのインストール確認は手動で行います。",style=MaterialTheme.typography.bodySmall)
                            Button(onClick={
                                val connection=config ?: return@Button
                                busy=true
                                scope.launch {
                                    val record=CiPending(connection.repository,connection.branch,target,"app-${UUID.randomUUID()}",System.currentTimeMillis(),autoApply=autoApply)
                                    try {
                                        withContext(Dispatchers.IO) { local.pending(record) }
                                        pending=record;run=null
                                        message="ビルドを開始しています…"
                                        withContext(Dispatchers.IO) { GitHubCiApi(connection).dispatch(record.target,record.requestId) }
                                        message="ビルドを送信しました。完了すると更新ファイルを取り込みます。"
                                    } catch(e: CancellationException) { throw e }
                                    catch(e: Exception) {
                                        if(e is CiHttpException && e.status in setOf(400,401,403,404,422,429)) { withContext(Dispatchers.IO){local.pending(null)};pending=null }
                                        message=errorText(e)
                                    } finally { busy=false }
                                }
                            },enabled=loaded&&!busy&&!active,modifier=Modifier.fillMaxWidth()) { Text("ビルドして更新を取り込む") }
                            OutlinedButton(onClick={
                                val connection=config ?: return@OutlinedButton
                                busy=true
                                scope.launch {
                                    try {
                                        val selected=withContext(Dispatchers.IO) { GitHubCiApi(connection).latestSuccess(target) }
                                        withContext(Dispatchers.IO) { local.pending(null) };pending=null;run=selected
                                        receive(selected,connection,autoApply)
                                    } catch(e: CancellationException) { throw e }
                                    catch(e: Exception) { message=errorText(e) }
                                    finally { busy=false }
                                }
                            },enabled=!busy&&!active,modifier=Modifier.fillMaxWidth()) { Text("最新の成功ビルドを取り込む") }
                            if(pending!=null)OutlinedButton(onClick={refreshKey++},enabled=!busy) { Text("状態を確認・受信を再開") }
                            if(pending!=null && run==null)TextButton(onClick={
                                scope.launch { withContext(Dispatchers.IO){local.pending(null)};pending=null;message="この端末の追跡を終了しました。GitHub上のビルドはキャンセルされません。" }
                            },enabled=!busy) { Text("この端末の追跡を終了") }
                            TextButton(onClick={open(run?.webUrl(config!!.repository) ?: "https://github.com/${config!!.repository}/actions/workflows/mobile-update.yml")}) { Text("CIの詳細を開く") }
                        }
                        TextButton(onClick={showSettings=!showSettings},enabled=loaded&&!busy) { Text(if(showSettings)"接続設定を閉じる" else "GitHubの接続設定") }
                        if(showSettings) {
                            Text("初回だけトークンを登録",style=MaterialTheme.typography.titleMedium)
                            Text("GitHubでFine-grained tokenを作り、Repository accessをStudyGadget、Repository permissionsのActionsをRead and writeにします。Metadataの読み取りは自動で付きます。")
                            OutlinedButton(onClick={open("https://github.com/settings/personal-access-tokens/new")}) { Text("GitHubでトークンを作成") }
                            OutlinedTextField(repository,{repository=it},label={Text("保存先（owner/repository）")},singleLine=true,enabled=!busy&&!active,modifier=Modifier.fillMaxWidth())
                            OutlinedTextField(branch,{branch=it},label={Text("ブランチ")},singleLine=true,enabled=!busy&&!active,modifier=Modifier.fillMaxWidth())
                            OutlinedTextField(token,{token=it},label={Text(if(config==null)"GitHubトークン" else "新しいトークン（空欄なら現在のもの）")},singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),enabled=!busy,modifier=Modifier.fillMaxWidth())
                            Button(onClick={
                                busy=true
                                scope.launch {
                                    try {
                                        val next=CiConfig(repository.trim(),branch.trim(),token.trim().ifEmpty { config?.token.orEmpty() })
                                        withContext(Dispatchers.IO) { GitHubCiApi(next).checkConnection();local.saveConfig(next) }
                                        config=next;token="";showSettings=false;message="GitHubへの接続を確認しました。ビルドを開始できます。";refreshKey++
                                    } catch(e: CancellationException) { throw e }
                                    catch(e: Exception) { message=errorText(e) }
                                    finally { busy=false }
                                }
                            },enabled=!busy) { Text("接続を確認して保存") }
                            if(config!=null)TextButton(onClick={
                                scope.launch { withContext(Dispatchers.IO){local.disconnect()};config=null;pending=null;run=null;token="";importedRun=0;message="この端末の接続情報を削除しました。" }
                            },enabled=!busy&&!active) { Text("接続情報を削除") }
                            Text("トークンはこの端末内で暗号化して保存します。チャットやリポジトリへ貼り付ける必要はありません。",style=MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(onClick={startActivity(Intent(this@GitHubCiActivity,UpdateActivity::class.java))},enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text("更新・配信画面を開く（FBI QRコード・APK更新）") }
                        TextButton(onClick={finish()},enabled=!busy) { Text("戻る") }
                    }
                }
            }
        }
    }
}
