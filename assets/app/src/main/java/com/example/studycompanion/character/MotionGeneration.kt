package com.example.studycompanion.character

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import android.os.Build
import com.example.studycompanion.data.SessionRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URI
import java.time.LocalDate
import java.time.LocalTime

object MotionGeneration {
    suspend fun generate(store: CharacterStore, studySeconds: Long, canPublish: () -> Boolean = { true }): Int = withContext(Dispatchers.IO) {
        store.initialize();val model=store.info()
        val url=store.aiUrl
        val recipes=if(url.isBlank())MotionPlanner.generate((System.currentTimeMillis()/1000).toInt(),studySeconds) else {
            val uri=URI(url)
            require(uri.scheme=="https" || (uri.scheme=="http" && (uri.host=="127.0.0.1" || uri.host=="localhost"))) { "AI接続先はHTTPS、または端末内localhostを指定してください" }
            require(uri.userInfo==null && uri.fragment==null)
            val connection=uri.toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout=15000;connection.readTimeout=60000;connection.requestMethod="POST";connection.doOutput=true
                connection.instanceFollowRedirects=false
                connection.setRequestProperty("Content-Type","application/json")
                if(store.aiToken.isNotBlank())connection.setRequestProperty("Authorization","Bearer ${store.aiToken}")
                val request=buildJsonObject { put("schema",1);put("skeleton",model.skeleton);put("studySeconds",studySeconds);put("hour",LocalTime.now().hour);put("variantsPerState",8) }
                connection.outputStream.use { it.write(request.toString().toByteArray()) }
                require(connection.responseCode==200) { "AI HTTP ${connection.responseCode}" }
                val bytes=connection.inputStream.use { it.readBounded(32769) };require(bytes.size<=32768)
                MotionPlanner.decode(bytes.toString(Charsets.UTF_8))
            } finally { connection.disconnect() }
        }
        // Exceptions retain the old version. Invalid AI output is never silently published.
        currentCoroutineContext().ensureActive()
        require(canPublish()) { "充電・夜間条件が変わったため、更新を保留しました" }
        store.publish(recipes,model.skeleton,if(url.isBlank())"端末内プロシージャル生成" else "AI生成")
    }
    fun schedule(context: Context) {
        val jobs=context.getSystemService(JobScheduler::class.java)
        if(!CharacterStore(context).nightEnabled){ jobs.cancel(JOB_ID);return }
        if(jobs.getPendingJob(JOB_ID)!=null)return
        val job=JobInfo.Builder(JOB_ID,ComponentName(context,MotionNightJob::class.java))
            .setRequiresCharging(true).setRequiresBatteryNotLow(true)
            .setPeriodic(3*60*60*1000L,60*60*1000L).setPersisted(true).build()
        jobs.schedule(job)
    }
    const val JOB_ID=2107
}

@AndroidEntryPoint
class MotionNightJob: JobService() {
    @Inject lateinit var sessions: SessionRepository
    private var job: Job?=null
    @Volatile private var stopped=false
    override fun onStartJob(params: JobParameters): Boolean {
        val store=CharacterStore(this)
        if(!store.nightEnabled || LocalTime.now().hour !in 0..6 || store.generatedDay==LocalDate.now().toString())return false
        stopped=false
        job=CoroutineScope(Dispatchers.IO).launch {
            var retry=false
            try {
                // OS may remove constraints while a request is active; check before publication too.
                val pm=getSystemService(PowerManager::class.java)
                if(Build.VERSION.SDK_INT>=29 && pm.currentThermalStatus>=PowerManager.THERMAL_STATUS_SEVERE)return@launch
                val total=sessions.getSessionsFromDateList(LocalDate.now().minusDays(6).toString()).sumOf { it.durationSec.toLong() }
                if(getSystemService(BatteryManager::class.java).isCharging)MotionGeneration.generate(store,total) {
                    getSystemService(BatteryManager::class.java).isCharging && LocalTime.now().hour in 0..6 && store.nightEnabled &&
                        (Build.VERSION.SDK_INT<29 || pm.currentThermalStatus<PowerManager.THERMAL_STATUS_SEVERE)
                }
            } catch(e: CancellationException) { throw e }
              catch(e: Exception) { store.lastResult="生成失敗・前の動作を保持: ${e.message}";retry=true }
            finally { if(!stopped)jobFinished(params,retry) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {stopped=true;job?.cancel();return true}
}
