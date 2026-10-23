package com.example.studycompanion.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.AtomicFile
import androidx.core.content.FileProvider
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

data class PublishedUpdate(val target: String,val file: File,val sha256: String,val size: Long,val label: String)

class UpdateStore(private val context: Context) {
    private val root=File(context.filesDir,"updates").apply{mkdirs()}
    private val blobs=File(root,"blobs").apply{mkdirs()}
    private fun pointer()=AtomicFile(File(root,"active.json"))
    fun current(target: String): PublishedUpdate? = synchronized(guard) {
        require(target=="3ds"||target=="android")
        runCatching {
            val p=JSONObject(pointer().readFully().toString(Charsets.UTF_8)).getJSONObject(target);val sha=p.getString("sha256")
            require(sha.matches(Regex("[0-9a-f]{64}")))
            val file=blob(target,sha) ?: return@runCatching null
            PublishedUpdate(target,file,sha,file.length(),p.getString("label"))
        }.getOrNull()
    }
    fun blob(target: String,sha: String): File? {
        if(target !in setOf("3ds","android")||!sha.matches(Regex("[0-9a-f]{64}")))return null
        return File(blobs,"$sha.${if(target=="3ds")"3dsx" else "apk"}").takeIf{it.isFile}
    }
    fun manifest(): ByteArray? = current("3ds")?.let { "SGAPP 1 ${it.size} ${it.sha256}\n".toByteArray(Charsets.US_ASCII) }
    private fun certs(info: PackageInfo): Set<String> {
        @Suppress("DEPRECATION") val signatures=if(Build.VERSION.SDK_INT>=28)info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString(""){b->"%02x".format(b.toInt() and 255)} }.toSet()
    }
    @Suppress("DEPRECATION") private fun inspectApk(file: File): String {
        val flags=if(Build.VERSION.SDK_INT>=28)PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val incoming=context.packageManager.getPackageArchiveInfo(file.absolutePath,flags) ?: error("APKを解析できません")
        val installed=context.packageManager.getPackageInfo(context.packageName,flags)
        val incomingCode=if(Build.VERSION.SDK_INT>=28)incoming.longVersionCode else incoming.versionCode.toLong()
        val installedCode=if(Build.VERSION.SDK_INT>=28)installed.longVersionCode else installed.versionCode.toLong()
        UpdatePackage.validateApkIdentity(context.packageName,incoming.packageName,installedCode,incomingCode,certs(installed),certs(incoming))
        return "${incoming.versionName ?: incomingCode} (code $incomingCode)"
    }
    fun importUpdate(input: InputStream): List<PublishedUpdate> = synchronized(guard) {
        val work=File(context.cacheDir,"update-import-${UUID.randomUUID()}").apply{mkdirs()}
        try {
            val source=File(work,"received")
            input.use { UpdatePackage.copyBounded(it,source,UpdatePackage.MAX_PACKAGE) }
            val candidates=UpdatePackage.unpack(source,work)
            // Validate every target before changing either published pointer.
            val checked=candidates.map { c -> c to if(c.target=="android")inspectApk(c.file) else c.label }
            val result=checked.map { (c,label) ->
                val destination=File(blobs,"${c.sha256}.${if(c.target=="3ds")"3dsx" else "apk"}")
                if(!destination.isFile){
                    val staging=File(blobs,".${UUID.randomUUID()}.tmp")
                    try {
                        c.file.inputStream().use { stream -> staging.outputStream().use { output -> stream.copyTo(output);output.flush();output.fd.sync() } }
                        require(UpdatePackage.sha256(staging)==c.sha256)
                        require(staging.renameTo(destination)){"更新ファイルを保存できません"}
                    } finally { staging.delete() }
                }
                PublishedUpdate(c.target,destination,c.sha256,c.size,label)
            }
            val document=runCatching{JSONObject(pointer().readFully().toString(Charsets.UTF_8))}.getOrElse{JSONObject()}
            result.forEach { update -> document.put(update.target,JSONObject().put("sha256",update.sha256).put("label",update.label)) }
            val atomic=pointer();val stream=atomic.startWrite()
            try{stream.write(document.toString().toByteArray());atomic.finishWrite(stream)}
            catch(e:Exception){atomic.failWrite(stream);throw e}
            result
        } finally { require(work.canonicalFile.parentFile==context.cacheDir.canonicalFile && work.name.startsWith("update-import-"));work.deleteRecursively() }
    }
    fun installIntent(): Intent {
        val update=current("android") ?: error("先にAPKを取り込んでください")
        require(UpdatePackage.sha256(update.file)==update.sha256){"保存したAPKが破損しています"}
        inspectApk(update.file)
        if(!context.packageManager.canRequestPackageInstalls())return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${context.packageName}"))
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",update.file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    companion object { private val guard=Any() }
}
