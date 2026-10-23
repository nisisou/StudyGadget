package com.example.studycompanion.update

import android.content.Context
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import org.json.JSONObject

/** Commands share the existing pairing authorization and pin one validated blob. */
class DevAutomation(context: Context) {
    private val contextForStore=context.applicationContext
    private val prefs=context.getSharedPreferences("dev-automation",Context.MODE_PRIVATE)
    fun schedule(sha: String) = synchronized(lock) {
        require(sha.matches(Regex("[0-9a-f]{64}")))
        require(UpdateStore(contextForStore).current("3ds")?.sha256==sha){"先に同じ3DSXをアップロードしてください"}
        require(prefs.edit().putString("target",sha).putString("installed","").putString("booted","")
            .putString("error","").putLong("scheduled",System.currentTimeMillis()).commit())
    }
    fun control(): String = synchronized(lock) {
        val target=prefs.getString("target","").orEmpty()
        val active=target.isNotBlank()&&System.currentTimeMillis()-prefs.getLong("scheduled",0)<3600000L
        "SGDEV 1 "+(if(active)target else "-")+" "+prefs.getLong("scheduled",0)+"\n"
    }
    fun report(sha: String,command: Long,state: String,message: String) = synchronized(lock) {
        require(sha.matches(Regex("[0-9a-f]{64}")))
        require(state in setOf("installed","booted","error"))
        if(sha!=prefs.getString("target","")||command!=prefs.getLong("scheduled",0))return@synchronized
        val edit=prefs.edit().putLong("seen",System.currentTimeMillis())
        if(state=="installed")edit.putString("installed",sha)
        if(state=="booted")edit.putString("installed",sha).putString("booted",sha)
        if(state=="error")edit.putString("error",message.take(180))
        require(edit.commit())
    }
    fun status(): String = synchronized(lock) {
        JSONObject().put("targetSha",prefs.getString("target","")).put("installedSha",prefs.getString("installed",""))
            .put("restarted",prefs.getString("booted","").orEmpty().isNotBlank()).put("error",prefs.getString("error",""))
            .put("seenAt",prefs.getLong("seen",0)).toString()
    }
    companion object { private val lock=Any() }
}

fun Route.devAutomationRoutes(context: Context) {
    post("/api/dev/auto-update") {
        try {
            val body=call.receiveText();require(body.length<=512)
            val request=JSONObject(body);require(request.optBoolean("enabled"))
            DevAutomation(context).schedule(request.getString("sha256"))
            call.respondText("""{"ok":true}""",ContentType.Application.Json)
        }catch(e: Exception){call.respondText(e.message ?: "予約できません",ContentType.Text.Plain,HttpStatusCode.BadRequest)}
    }
    get("/api/dev/control") { call.respondText(DevAutomation(context).control(),ContentType.Text.Plain) }
    get("/api/dev/status") { call.respondText(DevAutomation(context).status(),ContentType.Application.Json) }
    post("/api/dev/report") {
        try {
            val body=call.receiveText();require(body.length<=512)
            val report=JSONObject(body)
            DevAutomation(context).report(report.getString("sha256"),report.getLong("command"),report.getString("state"),report.optString("message",""))
            call.respondText("""{"ok":true}""",ContentType.Application.Json)
        }catch(e: Exception){call.respondText(e.message ?: "報告が不正です",ContentType.Text.Plain,HttpStatusCode.BadRequest)}
    }
}

