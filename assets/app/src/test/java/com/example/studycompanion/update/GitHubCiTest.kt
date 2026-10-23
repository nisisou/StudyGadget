package com.example.studycompanion.update

import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GitHubCiTest {
    private val config=CiConfig(token="github_pat_test_only_1234567890")
    private class Fake : CiTransport {
        val calls=mutableListOf<Triple<String,String,String?>>()
        val replies=ArrayDeque<CiReply>()
        var bytes=byteArrayOf(1,2,3,4)
        var downloaded=false
        override fun request(method:String,url:URL,token:String,body:String?):CiReply {
            assertEquals("api.github.com",url.host)
            calls.add(Triple(method,url.toString(),body))
            assertTrue(token.startsWith("github_pat_"))
            return replies.removeFirst()
        }
        override fun download(url:URL,destination:File,limit:Long,progress:(Long,Long)->Unit) {
            assertEquals("https",url.protocol)
            downloaded=true;destination.writeBytes(bytes);progress(bytes.size.toLong(),bytes.size.toLong())
        }
    }
    private fun run(id:Long=1,branch:String="main",title:String="Mobile CI / 3ds / main",path:String=CiSafety.WORKFLOW,status:String="completed",conclusion:String="success")=buildJsonObject {
        put("id",id);put("display_title",title);put("head_branch",branch);put("path",path);put("status",status);put("conclusion",conclusion)
    }
    private fun list(vararg runs:JsonObject)=buildJsonObject { put("workflow_runs",JsonArray(runs.toList())) }.toString()
    private fun bad(block:()->Unit) { try { block();fail("Expected rejection") } catch(_:IllegalArgumentException){} }
    @Test fun dispatchCarriesUniqueRequestAndSelectedRef() {
        val fake=Fake().apply { replies.add(CiReply(204)) }
        val id="app-"+UUID.randomUUID()
        GitHubCiApi(config,fake).dispatch("both",id)
        val call=fake.calls.single()
        assertEquals("POST",call.first)
        val body=Json.parseToJsonElement(call.third!!).jsonObject
        assertEquals("main",body["ref"]!!.jsonPrimitive.content)
        assertEquals("both",body["inputs"]!!.jsonObject["target"]!!.jsonPrimitive.content)
        assertEquals(id,body["inputs"]!!.jsonObject["request_id"]!!.jsonPrimitive.content)
    }
    @Test fun findsOnlyTheExactAppRequestNotAnotherSimultaneousRun() {
        val id="app-"+UUID.randomUUID()
        val fake=Fake().apply { replies.add(CiReply(200,list(
            run(2,title="Mobile CI / 3ds / main / app-"+UUID.randomUUID()),
            run(3,branch="other",title="Mobile CI / 3ds / other / "+id),
            run(4,title="Mobile CI / 3ds / main / "+id,path=".github/workflows/other.yml"),
            run(5,title="Mobile CI / 3ds / main / "+id)))) }
        assertEquals(5L,GitHubCiApi(config,fake).findRequest(id)!!.id)
    }
    @Test fun missingRequestDoesNotFallBackToLatestRun() {
        val fake=Fake().apply { replies.add(CiReply(200,list(run(99)))) }
        assertNull(GitHubCiApi(config,fake).findRequest("app-"+UUID.randomUUID()))
    }
    @Test fun latestSuccessRespectsTheSelectedBuildTarget() {
        val fake=Fake().apply { replies.add(CiReply(200,list(
            run(1,title="Mobile CI / android / main"),run(2,title="Mobile CI / 3ds / main"),run(3,branch="other")))) }
        assertEquals(2L,GitHubCiApi(config,fake).latestSuccess("3ds").id)
    }
    @Test fun cannotReceiveArtifactsFromFailedOrUnrelatedRuns() {
        val api=GitHubCiApi(config,Fake())
        bad { api.artifact(CiRun(1,"","main","completed","failure",CiSafety.WORKFLOW)) }
        bad { api.artifact(CiRun(1,"","other","completed","success",CiSafety.WORKFLOW)) }
        bad { api.artifact(CiRun(1,"","main","completed","success","other.yml")) }
    }
    @Test fun rejectsExpiredArtifactsAndMissingDigest() {
        for((expired,digest) in listOf(true to ("sha256:"+"a".repeat(64)),false to "")) {
            val fake=Fake().apply { replies.add(CiReply(200,buildJsonObject {
                putJsonArray("artifacts") { add(buildJsonObject { put("id",1);put("name","StudyGadget-update.sgupdate");put("expired",expired);put("digest",digest);put("size_in_bytes",4) }) }
            }.toString())) }
            bad { GitHubCiApi(config,fake).artifact(CiRun(1,"","main","completed","success",CiSafety.WORKFLOW)) }
        }
    }
    @Test fun verifiesDownloadedArtifactAndRemovesCorruption() {
        val fake=Fake().apply { replies.add(CiReply(302,location="https://test.blob.core.windows.net/file?signature=x")) }
        val sha=MessageDigest.getInstance("SHA-256").digest(fake.bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
        val f=File.createTempFile("ci-test-",".zip")
        try {
            GitHubCiApi(config,fake).download(CiArtifact(1,"sha256:"+sha,4),f)
            assertArrayEquals(fake.bytes,f.readBytes())
            fake.replies.add(CiReply(302,location="https://test.blob.core.windows.net/file"))
            bad { GitHubCiApi(config,fake).download(CiArtifact(1,"sha256:"+"0".repeat(64),4),f) }
            assertFalse(f.exists())
        } finally { f.delete() }
    }
    @Test fun rejectsUnsafeRedirectBeforeDownloading() {
        for(location in listOf("http://test.blob.core.windows.net/file","https://127.0.0.1/file","https://api.github.com/file","https://test.blob.core.windows.net.evil.example/file","https://user:pass@test.blob.core.windows.net/file")) {
            val fake=Fake().apply { replies.add(CiReply(302,location=location)) }
            val f=File.createTempFile("ci-test-",".zip")
            try {
                bad { GitHubCiApi(config,fake).download(CiArtifact(1,"sha256:"+"a".repeat(64),4),f) }
                assertFalse(fake.downloaded)
            } finally { f.delete() }
        }
    }
    @Test fun rejectsUnsafeConfigAndApiHostsWithoutLeakingToken() {
        for(repo in listOf("../evil","https://evil.example/repo","owner/repo?x=y","owner/repo/path"))bad { CiConfig(repo,token=config.token) }
        bad { CiConfig(token="token with whitespace") }
        for(url in listOf("http://api.github.com/repos/x/y","https://api.github.com.evil.example/","https://user:pass@api.github.com/"))bad { CiSafety.apiUrl(URL(url)) }
        assertFalse(config.toString().contains(config.token))
    }
    @Test fun dispatchErrorsAreNotTreatedAsAccepted() {
        val fake=Fake().apply { replies.add(CiReply(403)) }
        try { GitHubCiApi(config,fake).dispatch("3ds","app-"+UUID.randomUUID());fail("Expected permission error") }
        catch(e:CiHttpException) { assertEquals(403,e.status);assertFalse(e.message!!.contains(config.token)) }
    }
}
