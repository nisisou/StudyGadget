package com.example.studycompanion.server

import com.example.studycompanion.core.Logger
import com.example.studycompanion.core.NetworkUtils
import com.example.studycompanion.data.SessionRepository
import com.example.studycompanion.data.SettingsStore
import com.example.studycompanion.data.SubjectRepository
import com.example.studycompanion.domain.LiveSessionState
import com.example.studycompanion.domain.StatsCalculator
import com.example.studycompanion.domain.ext.MediaBackend
import com.example.studycompanion.domain.models.MediaCommand
import com.example.studycompanion.domain.models.Session
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondFile
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.studycompanion.update.devAutomationRoutes
import com.example.studycompanion.update.UpdateStore
import com.example.studycompanion.update.UpdatePackage
import com.example.studycompanion.update.updateWebHtml
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondBytes
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.example.studycompanion.character.CharacterStore
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KtorServer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore,
    private val sessionRepository: SessionRepository,
    private val subjectRepository: SubjectRepository,
    private val liveSessionState: LiveSessionState,
    private val mediaBackends: Set<@JvmSuppressWildcards MediaBackend>
) {
    @Volatile private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    @Volatile var lastError: String? = null
        internal set

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    suspend fun startServer(port: Int): Boolean {
        lastError = null
        stopServer()
        return try {
            engine = embeddedServer(CIO, host = "0.0.0.0", port = port) {
                configureServer()
            }.start(wait = false)
            Logger.i("KtorServer", "Server started on port $port")
            true
        } catch (e: Exception) {
            lastError = "ポート $port で起動できません: ${e.message ?: e.javaClass.simpleName}"
            Logger.e("KtorServer", "Failed to start server on port $port", e)
            engine = null
            false
        }
    }

    fun stopServer() {
        engine?.stop(500, 1000)
        engine = null
        Logger.i("KtorServer", "Server stopped")
    }

    fun isRunning(): Boolean = engine != null

    private fun Application.configureServer() {
        install(ContentNegotiation) {
            json(json)
        }

        routing {
            devAutomationRoutes(context)
            intercept(io.ktor.server.application.ApplicationCallPipeline.Plugins) {
                val remoteHost = call.request.local.remoteHost
                if (!NetworkUtils.isPrivateIp(remoteHost)) {
                    Logger.w("KtorServer", "Blocked request from non-private IP: $remoteHost")
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                    finish()
                    return@intercept
                }

                liveSessionState.recordApiAccess()

                val expectedToken = settingsStore.tokenFlow.first()
                val authHeader = call.request.headers["Authorization"]
                val tokenQuery = call.request.queryParameters["t"]

                val tokenProvided = when {
                    authHeader?.startsWith("Bearer ", ignoreCase = true) == true ->
                        authHeader.substring(7).trim()
                    !tokenQuery.isNullOrEmpty() -> tokenQuery
                    else -> ""
                }

                if (expectedToken.isBlank() || !constantTimeEquals(tokenProvided, expectedToken)) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized"))
                    finish()
                    return@intercept
                }
            }

            get("/") {
                call.respondText(getWebHtml(), ContentType.Text.Html)
            }

            get("/updates") { call.respondText(updateWebHtml(),ContentType.Text.Html) }
            get("/api/update/manifest") {
                val manifest=withContext(Dispatchers.IO){UpdateStore(context).manifest()}
                if(manifest==null)call.respond(HttpStatusCode.NoContent) else call.respondBytes(manifest,ContentType.Text.Plain)
            }
            get("/api/update/3ds/{sha}") {
                val file=UpdateStore(context).blob("3ds",call.parameters["sha"] ?: "")
                if(file==null)call.respond(HttpStatusCode.NotFound) else call.respondFile(file)
            }
            post("/api/update/upload") {
                val length=call.request.headers["Content-Length"]?.toLongOrNull()
                if(length!=null && length>UpdatePackage.MAX_PACKAGE){call.respond(HttpStatusCode.PayloadTooLarge);return@post}
                try {
                    val input=call.receiveChannel().toInputStream()
                    val result=withContext(Dispatchers.IO){UpdateStore(context).importUpdate(input)}
                    call.respondText("取り込み完了: "+result.joinToString{it.target+" "+it.label},ContentType.Text.Plain)
                }catch(e: Exception){call.respondText(e.message ?: "更新ファイルを取り込めません",ContentType.Text.Plain,HttpStatusCode.BadRequest)}
            }

            get("/api/status") {
                val live = liveSessionState.getValidLive()
                val liveJson = buildLiveJsonObject(live?.subject, live?.start)
                call.respond(StatusResponse(ok = true, live = liveJson))
            }

            get("/api/character/manifest") {
                call.respondBytes(CharacterStore(context).manifest(),ContentType.Text.Plain)
            }
            get("/api/character/model/{crc}") {
                val crc=call.parameters["crc"]?.toLongOrNull()
                val file=crc?.takeIf { it in 0..0xffffffffL }?.let { CharacterStore(context).modelFile(it) }
                if(file==null)call.respond(HttpStatusCode.NotFound) else call.respondBytes(file.readBytes(),ContentType.Application.OctetStream)
            }
            get("/api/character/motions/{revision}") {
                val rev=call.parameters["revision"]?.toIntOrNull()
                val file=rev?.takeIf { it>0 }?.let { CharacterStore(context).motionFile(it) }
                if(file==null)call.respond(HttpStatusCode.NotFound) else call.respondBytes(file.readBytes(),ContentType.Application.OctetStream)
            }

            get("/api/config") {
                val subjects = subjectRepository.getActiveSubjectsList()
                val sb = StringBuilder()
                val activeList = subjects.sortedBy { it.sortOrder }.take(8)
                for (subj in activeList) {
                    val cleanId = subj.id.replace(",", "").replace("\n", "").replace("\r", "")
                    val cleanLabel = subj.label.replace(",", "").replace("\n", "").replace("\r", "")
                    val truncatedLabel = truncateUtf8(cleanLabel, 24)
                    val cleanColor = subj.colorHex.removePrefix("#").uppercase()
                    sb.append("$cleanId,$truncatedLabel,$cleanColor\n")
                }
                call.respondText(sb.toString(), ContentType.Text.Plain)
            }

            post("/api/session/start") {
                try {
                    val bodyText = call.receiveText()
                    val req = if (bodyText.isNotBlank()) json.decodeFromString<StartSessionRequest>(bodyText) else StartSessionRequest()
                    val subj = req.subject ?: "math"
                    val startSec = req.timestamp?.toDouble() ?: (System.currentTimeMillis() / 1000.0)
                    liveSessionState.startLive(subj, startSec)
                    com.example.studycompanion.widget.CharacterWidgetProvider.setLive(context,subj,(startSec*1000).toLong())
                    call.respond(ApiResponse(ok = true))
                } catch (e: Exception) {
                    Logger.e("KtorServer", "Error parsing start request", e)
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad json"))
                }
            }

            post("/api/session/stop") {
                liveSessionState.stopLive()
                com.example.studycompanion.widget.CharacterWidgetProvider.setLive(context,null)
                call.respond(ApiResponse(ok = true))
            }

            post("/api/sync") {
                try {
                    val req = call.receive<SyncSessionRequest>()
                    val session = Session(
                        id = req.id,
                        subject = req.subject,
                        start = req.start,
                        end = req.end,
                        durationSec = req.duration,
                        device = req.device.ifBlank { "3ds" }
                    )
                    sessionRepository.saveSession(session)
                    liveSessionState.stopLive()
                    com.example.studycompanion.widget.CharacterWidgetProvider.setLive(context,null)
                    call.respond(ApiResponse(ok = true))
                } catch (e: Exception) {
                    Logger.e("KtorServer", "Error in sync", e)
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad json"))
                }
            }

            post("/api/media") {
                try {
                    val req = call.receive<MediaRequest>()
                    val cmd = MediaCommand.fromApiString(req.command)
                    if (cmd != null) {
                        val sortedBackends = mediaBackends.sortedByDescending { it.priority }
                        for (backend in sortedBackends) {
                            if (backend.handle(cmd)) break
                        }
                    }
                    call.respond(ApiResponse(ok = true))
                } catch (e: Exception) {
                    Logger.e("KtorServer", "Error in media request", e)
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad json"))
                }
            }

            get("/api/stats") {
                val sessions = sessionRepository.getSessionsFromDateList(
                    java.time.LocalDate.now().minusDays(6).toString()
                )
                val activeSubjects = subjectRepository.getActiveSubjectsList()
                val statsResult = StatsCalculator.calculateStats(sessions, activeSubjects)

                val live = liveSessionState.getValidLive()
                val liveJson = buildLiveJsonObject(live?.subject, live?.start)
                val nowSec = System.currentTimeMillis() / 1000.0

                val response = StatsResponse(
                    today = statsResult.today,
                    week = statsResult.week.map { WeekDayDto(d = it.d, sec = it.sec) },
                    subjects = statsResult.subjects.map { SubjectTotalDto(id = it.id, sec = it.sec) },
                    config = statsResult.config.map { ConfigSubjectResponseDto(id = it.id, label = it.label, color = it.color) },
                    live = liveJson,
                    now = nowSec
                )
                call.respond(response)
            }

            get("/api/export.csv") {
                val sessions = sessionRepository.getAllSessionsList()
                val sb = StringBuilder()
                sb.append("id,subject,start,end,duration,device\n")
                for (s in sessions) {
                    sb.append("${s.id},${s.subject},${s.start},${s.end},${s.durationSec},${s.device}\n")
                }
                call.respondText(sb.toString(), ContentType.Text.CSV)
            }
        }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val aBytes = a.toByteArray(Charsets.UTF_8)
        val bBytes = b.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(aBytes, bBytes)
    }

    private fun buildLiveJsonObject(subject: String?, startSec: Double?): JsonObject {
        return if (subject != null && startSec != null) {
            buildJsonObject {
                put("subject", subject)
                put("start", startSec)
            }
        } else {
            buildJsonObject {}
        }
    }

    private fun truncateUtf8(str: String, maxBytes: Int): String {
        val bytes = str.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return str
        var end = str.length
        while (end > 0) {
            end--
            val sub = str.substring(0, end)
            if (sub.toByteArray(Charsets.UTF_8).size <= maxBytes) {
                return sub
            }
        }
        return ""
    }

    private fun getWebHtml(): String {
        val s = "$"
        return """<!doctype html><meta name=viewport content="width=device-width,initial-scale=1"><title>Study Companion</title>
<style>:root{--bg:#0b0e1a;--p:#1c2140;--t:#eef;--m:#8c96b8}@media(prefers-color-scheme:light){:root{--bg:#f3f5fb;--p:#fff;--t:#121830;--m:#6b7596}}
body{margin:0;font:16px system-ui;background:var(--bg);color:var(--t);padding:max(16px,env(safe-area-inset-top)) 16px 40px;max-width:520px;margin:auto}
.c{background:var(--p);border-radius:20px;padding:18px;margin:12px 0}.m{color:var(--m);font-size:13px}h2{margin:0 0 8px;font-size:15px;color:var(--m)}
.big{font-size:48px;font-weight:700;font-variant-numeric:tabular-nums}.bars{display:flex;gap:8px;align-items:flex-end;height:110px}
.bars div{flex:1;text-align:center;font-size:11px;color:var(--m)}.bars i{display:block;background:#60a5fa;border-radius:6px;margin-bottom:4px}
.row{display:flex;gap:8px;flex-wrap:wrap}button{flex:1;min-width:70px;padding:14px;border:0;border-radius:14px;background:#334;color:#fff;font-size:15px}
.dot{display:inline-block;width:10px;height:10px;border-radius:5px;margin-right:6px}
#don{width:120px;height:120px;border-radius:50%;margin:auto;-webkit-mask:radial-gradient(circle 38px,#0000 98%,#000);mask:radial-gradient(circle 38px,#0000 98%,#000)}</style>
<div class=c id=live><h2>NOW</h2><div class=big id=lt>--:--</div><div class=m id=ls>idle</div></div>
<div class=c><h2>TODAY</h2><div class=big id=td>0h00m</div></div>
<div class=c><h2>7 DAYS</h2><div class=bars id=wk></div></div>
<div class=c><h2>SUBJECTS (7d)</h2><div id=don></div><div id=lg class=m style="margin-top:10px"></div></div>
<div class=c><h2>MEDIA</h2><div class=row><button onclick=md('previous')>⏮</button><button onclick=md('play_pause')>⏯</button><button onclick=md('next')>⏭</button>
<button onclick=md('volume_down')>🔉</button><button onclick=md('volume_up')>🔊</button></div></div>
<a class=m id=ex>CSV export</a>
<script>const T=new URLSearchParams(location.search).get('t');ex.href='/api/export.csv?t='+T;
const A=(p,o={})=>fetch(p,{...o,headers:{Authorization:'Bearer '+T}}).then(r=>r.json());
const md=c=>A('/api/media',{method:'POST',body:JSON.stringify({command:c})});
const hm=sec=>Math.floor(sec/3600)+'h'+String(Math.floor(sec%3600/60)).padStart(2,'0')+'m';
async function r(){const d=await A('/api/stats'),col={};d.config.forEach(x=>col[x.id]=x);
td.textContent=hm(d.today);const mx=Math.max(...d.week.map(x=>x.sec),1);
wk.innerHTML=d.week.map(x=>`<div><i style="height:${s}{x.sec/mx*80}px"></i>${s}{x.d}</div>`).join('');
const tot=d.subjects.reduce((a,x)=>a+x.sec,0)||1;let a=0,g=[];
d.subjects.forEach(x=>{const c='#'+(col[x.id]?.color||'888'),e=a+x.sec/tot*100;g.push(`${s}{c} ${s}{a}% ${s}{e}%`);a=e});
don.style.background=g.length?`conic-gradient(${s}{g.join(',')})`:'#334';
lg.innerHTML=d.subjects.map(x=>`<span class=dot style="background:#${s}{col[x.id]?.color||'888'}"></span>${s}{col[x.id]?.label||x.id} ${s}{hm(x.sec)}&nbsp; `).join('');
const l=d.live;if(l.subject){const sec=Math.floor(d.now-l.start);lt.textContent=String(Math.floor(sec/60)).padStart(2,'0')+':'+String(sec%60).padStart(2,'0');ls.textContent='● '+(col[l.subject]?.label||l.subject)}else{lt.textContent='--:--';ls.textContent='idle'}}
r();setInterval(r,1500)</script>"""
    }
}
