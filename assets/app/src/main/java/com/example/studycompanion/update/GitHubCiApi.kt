package com.example.studycompanion.update

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.serialization.json.*

data class CiConfig(val repository: String = "nisisou/StudyGadget", val branch: String = "main", val token: String) {
    init {
        require(repository.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}")) && !repository.endsWith("/.") && !repository.endsWith("/..")) { "保存先は owner/repository の形式で入力してください" }
        require(branch.isNotBlank() && branch.length <= 200 && branch.none { it <= ' ' || it == '\u007f' }) { "ブランチ名を確認してください" }
        require(token.length in 20..500 && token.all { it in '!'..'~' }) { "GitHubトークンを入力してください" }
    }
    override fun toString() = "CiConfig(repository=$repository, branch=$branch, token=<hidden>)"
}
data class CiRun(val id: Long, val title: String, val branch: String, val status: String, val conclusion: String, val path: String) {
    val complete get() = status == "completed"
    val succeeded get() = complete && conclusion == "success"
    fun webUrl(repository: String) = "https://github.com/$repository/actions/runs/$id"
}
data class CiArtifact(val id: Long, val digest: String, val size: Long)
data class CiReply(val code: Int, val text: String = "", val location: String? = null)
class CiHttpException(val status: Int) : IllegalStateException(when(status) {
    401 -> "GitHubトークンが無効か、有効期限が切れています。接続設定で登録し直してください。"
    403 -> "GitHubへのアクセスが拒否されました。対象リポジトリとActionsの読み書き権限、利用制限を確認してください。"
    404 -> "保存先またはCIが見つかりません。トークンの対象にStudyGadgetを含めてください。"
    410 -> "更新ファイルの保存期限が切れています。新しくビルドしてください。"
    422 -> "ビルドを開始できません。ブランチ名とアプリ対応のCI設定を確認してください。"
    429 -> "GitHubの利用制限に達しました。しばらくしてから状態を確認してください。"
    else -> "GitHubとの通信に失敗しました（HTTP $status）。"
})
interface CiTransport {
    fun request(method: String, url: URL, token: String, body: String? = null): CiReply
    fun download(url: URL, destination: File, limit: Long, progress: (Long, Long) -> Unit)
}
object CiSafety {
    const val WORKFLOW = ".github/workflows/mobile-update.yml"
    fun apiUrl(url: URL) {
        require(url.protocol == "https" && url.host == "api.github.com" && url.port in listOf(-1,443) && url.userInfo == null && url.ref == null) { "GitHub APIの接続先が不正です" }
    }
    fun downloadUrl(url: URL) {
        val host = url.host.lowercase()
        require(url.protocol == "https" && url.port in listOf(-1,443) && url.userInfo == null && url.ref == null && host != "api.github.com" &&
            (host.endsWith(".blob.core.windows.net") || host.endsWith(".actions.githubusercontent.com") ||
             host.endsWith(".githubusercontent.com") || host == "github.com" || host.endsWith(".github.com"))) { "GitHubの配信先を確認できません" }
    }
    fun matchRequest(run: CiRun, branch: String, requestId: String) =
        run.branch == branch && run.path == WORKFLOW && run.title.endsWith(" / $requestId") &&
        requestId.matches(Regex("app-[0-9a-f-]{36}"))
    fun digest(value: String): String {
        require(value.matches(Regex("sha256:[0-9a-f]{64}"))) { "更新ファイルの検証情報がありません。新しくビルドしてください。" }
        return value.removePrefix("sha256:")
    }
}
/** The bearer token is sent only to api.github.com. Storage redirects never receive it. */
class HttpsCiTransport : CiTransport {
    private fun connection(url: URL) = (url.openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = 15000
        readTimeout = 45000
        setRequestProperty("User-Agent","StudyCompanion")
    }
    override fun request(method: String, url: URL, token: String, body: String?): CiReply {
        CiSafety.apiUrl(url)
        val c = connection(url)
        try {
            c.requestMethod = method
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Accept","application/vnd.github+json")
            c.setRequestProperty("X-GitHub-Api-Version","2022-11-28")
            if(body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type","application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            if(code in 300..399) return CiReply(code, location=c.getHeaderField("Location"))
            if(code !in 200..299) throw CiHttpException(code)
            val text = if(code == 204) "" else c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while(true) { val n=input.read(buffer); if(n<0)break; require(out.size()+n<=4*1024*1024){"GitHubの応答が大きすぎます"};out.write(buffer,0,n) }
                out.toString("UTF-8")
            }
            return CiReply(code,text)
        } finally { c.disconnect() }
    }
    override fun download(url: URL, destination: File, limit: Long, progress: (Long, Long) -> Unit) {
        var next = url
        try {
            repeat(6) {
                CiSafety.downloadUrl(next)
                val c = connection(next) // No Authorization header on any storage request.
                try {
                    val status=c.responseCode
                    if(status in listOf(301,302,303,307,308)) {
                        next=URL(next,c.getHeaderField("Location") ?: error("配信先が取得できません"))
                    } else {
                        if(status != 200) throw CiHttpException(status)
                        val expected=c.contentLengthLong
                        require(expected<=limit){"更新ファイルが大きすぎます"}
                        var count=0L
                        c.inputStream.use { input -> destination.outputStream().use { output ->
                            val buffer=ByteArray(32768)
                            while(true) {
                                val n=input.read(buffer);if(n<0)break;if(n==0)continue
                                count+=n;require(count<=limit){"更新ファイルが大きすぎます"}
                                output.write(buffer,0,n);progress(count,expected)
                            }
                        } }
                        require(expected<0 || count==expected){"ダウンロードが途中で終了しました"}
                        return
                    }
                } finally { c.disconnect() }
            }
            error("配信先の転送回数が多すぎます")
        } catch(e: Exception) { destination.delete();throw e }
    }
}
class GitHubCiApi(private val config: CiConfig, private val transport: CiTransport = HttpsCiTransport()) {
    private val json=Json { ignoreUnknownKeys=true }
    private val base="https://api.github.com/repos/${config.repository}"
    private fun query(value: String)=URLEncoder.encode(value,"UTF-8")
    private fun get(path: String): JsonObject {
        val r=transport.request("GET",URL(base+path),config.token)
        if(r.code !in 200..299)throw CiHttpException(r.code)
        return json.parseToJsonElement(r.text).jsonObject
    }
    fun checkConnection() {
        val result=get("/actions/workflows/mobile-update.yml")
        require(result["state"]?.jsonPrimitive?.content == "active"){"Mobile CIが無効です。GitHub側で有効にしてください。"}
        require(result["path"]?.jsonPrimitive?.content == CiSafety.WORKFLOW){"CIの設定が一致しません"}
    }
    fun dispatch(target: String, requestId: String) {
        require(target in setOf("3ds","android","both"))
        require(requestId.matches(Regex("app-[0-9a-f-]{36}")))
        val body=buildJsonObject {
            put("ref",config.branch)
            putJsonObject("inputs") { put("target",target);put("request_id",requestId) }
        }.toString()
        val r=transport.request("POST",URL("$base/actions/workflows/mobile-update.yml/dispatches"),config.token,body)
        if(r.code !in 200..299)throw CiHttpException(r.code)
    }
    private fun parseRun(o: JsonObject) = CiRun(o.getValue("id").jsonPrimitive.long,
        o["display_title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        o["head_branch"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        o.getValue("status").jsonPrimitive.content,
        o["conclusion"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        o.getValue("path").jsonPrimitive.content)
    fun findRequest(requestId: String): CiRun? {
        for(page in 1..3) {
            val list=get("/actions/workflows/mobile-update.yml/runs?event=workflow_dispatch&branch=${query(config.branch)}&per_page=100&page=$page")["workflow_runs"]!!.jsonArray
            list.forEach { val run=parseRun(it.jsonObject); if(CiSafety.matchRequest(run,config.branch,requestId))return run }
            if(list.size<100)break
        }
        return null
    }
    fun run(id: Long): CiRun {
        require(id>0)
        val run=parseRun(get("/actions/runs/$id"))
        require(run.path==CiSafety.WORKFLOW && run.branch==config.branch){"別のCI実行を検出しました"}
        return run
    }
    fun latestSuccess(target: String): CiRun {
        require(target in setOf("3ds","android","both"))
        val list=get("/actions/workflows/mobile-update.yml/runs?status=success&branch=${query(config.branch)}&per_page=10")["workflow_runs"]!!.jsonArray
        return list.map { parseRun(it.jsonObject) }.firstOrNull { it.succeeded && it.path==CiSafety.WORKFLOW && it.branch==config.branch && it.title.startsWith("Mobile CI / $target / ") }
            ?: error("成功したビルドがまだありません")
    }
    fun artifact(run: CiRun): CiArtifact {
        require(run.succeeded && run.path==CiSafety.WORKFLOW && run.branch==config.branch){"成功したビルドだけ受信できます"}
        val list=get("/actions/runs/${run.id}/artifacts?per_page=100")["artifacts"]!!.jsonArray
        val o=list.map { it.jsonObject }.singleOrNull { it["name"]?.jsonPrimitive?.content=="StudyGadget-update.sgupdate" }
            ?: error("更新ファイルが見つかりません。CIの詳細を確認してください。")
        require(o["expired"]?.jsonPrimitive?.boolean==false){"更新ファイルの保存期限が切れています。新しくビルドしてください。"}
        val digest=o["digest"]?.jsonPrimitive?.contentOrNull.orEmpty()
        CiSafety.digest(digest)
        val size=o.getValue("size_in_bytes").jsonPrimitive.long
        require(size in 1..UpdatePackage.MAX_PACKAGE){"更新ファイルのサイズが不正です"}
        return CiArtifact(o.getValue("id").jsonPrimitive.long,digest,size)
    }
    fun download(artifact: CiArtifact, destination: File, progress: (Long, Long) -> Unit = {_,_->}) {
        require(artifact.id>0)
        val r=transport.request("GET",URL("$base/actions/artifacts/${artifact.id}/zip"),config.token)
        require(r.code==302 && !r.location.isNullOrBlank()){"GitHubからダウンロード先を取得できません"}
        val url=URL(r.location)
        CiSafety.downloadUrl(url)
        try {
            transport.download(url,destination,UpdatePackage.MAX_PACKAGE,progress)
            require(destination.length()==artifact.size){"更新ファイルのサイズが一致しません"}
            require(UpdatePackage.sha256(destination)==CiSafety.digest(artifact.digest)){"更新ファイルのSHA-256が一致しません"}
        } catch(e: Exception) { destination.delete();throw e }
    }
}
