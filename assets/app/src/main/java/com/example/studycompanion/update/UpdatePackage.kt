package com.example.studycompanion.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipFile

@Serializable data class UpdateEntry(val target: String, val path: String, val size: Long, val sha256: String)
@Serializable data class UpdateBundle(val format: String, val schema: Int, val version: String, val files: List<UpdateEntry>)
data class UpdateCandidate(val target: String, val file: File, val size: Long, val sha256: String, val label: String)

/** Fixed output names, bounded decompression and digest verification before publication. No ZIP path is extracted. */
object UpdatePackage {
    const val MAX_PACKAGE = 96L * 1024 * 1024
    const val MAX_3DS = 16L * 1024 * 1024
    const val MAX_APK = 80L * 1024 * 1024
    const val APP_MARKER = "STUDYGADGET_APP_V1"
    fun copyBounded(input: InputStream, file: File, limit: Long): Long {
        var total=0L
        file.outputStream().use { output ->
            val buffer=ByteArray(32768)
            while(true){val n=input.read(buffer);if(n<0)break;if(n==0)continue;total+=n
                require(total<=limit){"更新ファイルが大きすぎます"};output.write(buffer,0,n)}
            output.flush()
        }
        return total
    }
    fun sha256(file: File): String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer=ByteArray(32768);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)} }
        return digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
    }
    fun validateApkIdentity(expectedPackage:String,incomingPackage:String,currentVersion:Long,incomingVersion:Long,currentCerts:Set<String>,incomingCerts:Set<String>){
        require(incomingPackage==expectedPackage){"別のアプリのAPKです"}
        require(incomingVersion>=currentVersion){"古いバージョンには戻せません"}
        require(incomingCerts.isNotEmpty() && incomingCerts==currentCerts){"APKの署名が一致しません。同じ署名キーでビルドしてください"}
    }
    fun inspect3ds(file: File) {
        require(file.length() in 32..MAX_3DS){"3DSXのサイズが不正です"}
        val header=ByteArray(32);file.inputStream().use { require(it.read(header)==32) }
        require(header.copyOfRange(0,4).contentEquals("3DSX".toByteArray())){"HBL用3DSXではありません"}
        val b=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val headerSize=b.getShort(4).toInt() and 65535
        val relocationSize=b.getShort(6).toInt() and 65535
        val code=b.getInt(16).toLong() and 0xffffffffL
        val ro=b.getInt(20).toLong() and 0xffffffffL
        val data=b.getInt(24).toLong() and 0xffffffffL
        val bss=b.getInt(28).toLong() and 0xffffffffL
        require(headerSize in 32..512 && relocationSize in 8..512 && code>0 && bss<=data &&
            headerSize.toLong()+3L*relocationSize+code+ro+data-bss<=file.length()){"3DSXヘッダーが破損しています"}
        // The marker distinguishes this application's executable from other homebrew.
        val bytes=file.readBytes()
        require(bytes.toString(Charsets.ISO_8859_1).contains(APP_MARKER)){"Study Gadgetの更新用3DSXではありません"}
    }
    private fun candidate(target: String, file: File, label: String): UpdateCandidate {
        require(target in setOf("3ds","android")){"未対応の更新先です"}
        if(target=="3ds")inspect3ds(file) else {
            require(file.length() in 1..MAX_APK)
            ZipFile(file).use { require(it.getEntry("AndroidManifest.xml")!=null && it.getEntry("classes.dex")!=null){"APKではありません"} }
        }
        return UpdateCandidate(target,file,file.length(),sha256(file),label.take(100))
    }
    fun unpack(input: File, work: File, depth: Int = 0): List<UpdateCandidate> {
        work.mkdirs();require(input.length() in 1..MAX_PACKAGE)
        val magic=ByteArray(4);input.inputStream().use { require(it.read(magic)==4) }
        if(magic.contentEquals("3DSX".toByteArray()))return listOf(candidate("3ds",input,"3DS HBL版"))
        return ZipFile(input).use { zip ->
            if(zip.getEntry("AndroidManifest.xml")!=null)return@use listOf(candidate("android",input,"Android APK"))
            require(zip.size()<=128){"更新ZIPの項目が多すぎます"}
            val manifest=zip.getEntry("study-update.json")
            if(manifest==null){
                val nested=zip.entries().asSequence().filter{!it.isDirectory}.toList()
                require(depth==0 && nested.size==1 && nested.single().name.endsWith(".sgupdate")){"更新用ZIPではありません。APK・3DSX・sgupdateファイルを選んでください"}
                val packed=File(work,"artifact.sgupdate")
                zip.getInputStream(nested.single()).use{copyBounded(it,packed,MAX_PACKAGE)}
                return@use unpack(packed,File(work,"artifact"),depth+1)
            }
            require(manifest.size in 1..16384){"更新マニフェストが不正です"}
            val text=zip.getInputStream(manifest).use { input ->
                val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
                while(true){val n=input.read(buffer);if(n<0)break;require(output.size()+n<=16384);output.write(buffer,0,n)};output.toByteArray()
            }
            val bundle=Json.decodeFromString<UpdateBundle>(text.toString(Charsets.UTF_8))
            require(bundle.format=="studygadget-update" && bundle.schema==1 && bundle.files.size in 1..2)
            require(bundle.files.map { it.target }.distinct().size==bundle.files.size){"更新先が重複しています"}
            val names=zip.entries().asSequence().map{it.name}.toList()
            require(names.distinct().size==names.size){"ZIPに重複名があります"}
            bundle.files.mapIndexed { index, entry ->
                require(entry.path in setOf("studygadget.3dsx","StudyCompanion.apk"))
                require(entry.sha256.matches(Regex("[0-9a-f]{64}")))
                val limit=if(entry.target=="3ds")MAX_3DS else MAX_APK
                require(entry.size in 1..limit)
                val part=zip.getEntry(entry.path) ?: error("更新ファイルが不足しています")
                require(!part.isDirectory && part.size==entry.size)
                val dest=File(work,"part-$index")
                val count=zip.getInputStream(part).use { copyBounded(it,dest,limit) }
                require(count==entry.size && sha256(dest)==entry.sha256){"更新ファイルのSHA-256が一致しません"}
                candidate(entry.target,dest,bundle.version)
            }
        }
    }
}
