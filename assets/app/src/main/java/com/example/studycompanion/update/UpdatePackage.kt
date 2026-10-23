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
    const val MAX_CIA = 32L * 1024 * 1024
    const val MAX_APK = 80L * 1024 * 1024
    const val APP_MARKER = "STUDYGADGET_APP_V1"
    private const val CIA_HEADER_SIZE = 0x2020
    private const val CIA_TITLE_ID = 0x000400000F764200L
    private const val SMDH_SIZE = 0x36C0
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
    fun inspectCia(file: File) {
        require(file.length() in (CIA_HEADER_SIZE + 512L)..MAX_CIA){"CIAのサイズが不正です"}
        val bytes=file.readBytes()
        fun u32(offset: Int): Long {
            require(offset >= 0 && offset <= bytes.size - 4){"CIAヘッダーが破損しています"}
            return ByteBuffer.wrap(bytes,offset,4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        }
        fun range(offset: Long,size: Long): Boolean =
            offset >= 0 && size >= 0 && offset <= bytes.size && size <= bytes.size - offset
        fun align64(size: Long): Long {
            require(size >= 0 && size <= Long.MAX_VALUE - 63){"CIAセクションサイズが不正です"}
            return (size + 63) and -64L
        }
        fun sha256Range(offset: Int,size: Int): ByteArray {
            require(offset>=0 && size>=0 && offset<=bytes.size && size<=bytes.size-offset){"CIAハッシュ範囲が不正です"}
            val digest=MessageDigest.getInstance("SHA-256")
            digest.update(bytes,offset,size)
            return digest.digest()
        }
        require(u32(0)==CIA_HEADER_SIZE.toLong()){"CIAヘッダーではありません"}
        val certificateSize=u32(8)
        val ticketSize=u32(12)
        val tmdSize=u32(16)
        val contentSize=ByteBuffer.wrap(bytes,24,8).order(ByteOrder.LITTLE_ENDIAN).long
        require(contentSize in 0x200..MAX_CIA){"CIAコンテンツサイズが不正です"}
        val ticketOffset=align64(CIA_HEADER_SIZE.toLong())+align64(certificateSize)
        val tmdOffset=ticketOffset+align64(ticketSize)
        val contentOffset=align64(CIA_HEADER_SIZE.toLong())+
            align64(certificateSize)+align64(ticketSize)+align64(tmdSize)
        require(ticketSize>=0x1e4 && tmdSize>=0xb34 &&
            range(ticketOffset,ticketSize) && range(tmdOffset,tmdSize) &&
            range(contentOffset,contentSize)){"CIAセクションがファイル範囲外です"}
        val ticketTitleId=ByteBuffer.wrap(bytes,ticketOffset.toInt()+0x1dc,8).order(ByteOrder.BIG_ENDIAN).long
        val tmdTitleId=ByteBuffer.wrap(bytes,tmdOffset.toInt()+0x18c,8).order(ByteOrder.BIG_ENDIAN).long
        val contentCount=ByteBuffer.wrap(bytes,tmdOffset.toInt()+0x1de,2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff
        val tmdContentSize=ByteBuffer.wrap(bytes,tmdOffset.toInt()+0xb0c,8).order(ByteOrder.BIG_ENDIAN).long
        val ncchDigest=sha256Range(contentOffset.toInt(),contentSize.toInt())
        require(ticketTitleId==CIA_TITLE_ID && tmdTitleId==CIA_TITLE_ID && contentCount==1 &&
            tmdContentSize==contentSize &&
            ncchDigest.contentEquals(bytes.copyOfRange(tmdOffset.toInt()+0xb14,tmdOffset.toInt()+0xb34))){"CIAのTicket／TMDがタイトルまたはコンテンツと一致しません"}
        val ncchOffset=contentOffset.toInt()
        require(bytes.copyOfRange(ncchOffset+0x100,ncchOffset+0x104).contentEquals("NCCH".toByteArray(Charsets.US_ASCII))){"CIAにNCCHコンテンツがありません"}
        val ncchSize=u32(ncchOffset+0x104)*512
        require(ncchSize==contentSize){"CIAのNCCHサイズが一致しません"}
        val titleId=ByteBuffer.wrap(bytes,ncchOffset+0x118,8).order(ByteOrder.LITTLE_ENDIAN).long
        require(titleId==CIA_TITLE_ID){"Study GadgetのCIAではありません"}
        require(bytes[ncchOffset+0x18e].toInt()==0 && (bytes[ncchOffset+0x18f].toInt() and 0x04) != 0){"暗号化形式に対応していないCIAです"}

        val exHeaderSize=u32(ncchOffset+0x180)
        require(exHeaderSize>0 && exHeaderSize<=ncchSize-0x200 &&
            range(ncchOffset+0x200L,exHeaderSize)){"NCCH ExHeaderが不正です"}
        val exHeaderHash=sha256Range(ncchOffset+0x200,exHeaderSize.toInt())
        require(exHeaderHash.contentEquals(bytes.copyOfRange(ncchOffset+0x160,ncchOffset+0x180))){"NCCH ExHeaderのSHA-256が一致しません"}

        val mediaUnit=512L
        val exeFsOffset=u32(ncchOffset+0x1a0)*mediaUnit
        val exeFsSize=u32(ncchOffset+0x1a4)*mediaUnit
        val exeFsHashSize=u32(ncchOffset+0x1a8)*mediaUnit
        require(exeFsSize>=0x200 && exeFsHashSize in 0x200..exeFsSize &&
            exeFsOffset<=ncchSize && exeFsSize<=ncchSize-exeFsOffset &&
            range(ncchOffset+exeFsOffset,exeFsSize)){"NCCH ExeFSが不正です"}
        val exeFsStart=ncchOffset+exeFsOffset.toInt()
        val exeFsHash=sha256Range(exeFsStart,exeFsHashSize.toInt())
        require(exeFsHash.contentEquals(bytes.copyOfRange(ncchOffset+0x1c0,ncchOffset+0x1e0))){"NCCH ExeFSのSHA-256が一致しません"}

        val entries=mutableMapOf<String,ByteArray>()
        for(index in 0 until 10){
            val entryOffset=exeFsStart+index*16
            val nameBytes=bytes.copyOfRange(entryOffset,entryOffset+8)
            val name=nameBytes.takeWhile{it.toInt()!=0}.toByteArray().toString(Charsets.US_ASCII)
            if(name.isEmpty())continue
            require(name.all{it in ' '..'~'} && name !in entries){"ExeFSの項目名が不正です"}
            val dataOffset=u32(entryOffset+8)
            val dataSize=u32(entryOffset+12)
            require(dataSize>0 && dataOffset<=exeFsSize-0x200 && dataSize<=exeFsSize-0x200-dataOffset){"ExeFSの項目が範囲外です"}
            val start=exeFsStart+0x200+dataOffset.toInt()
            val data=bytes.copyOfRange(start,start+dataSize.toInt())
            val hashOffset=exeFsStart+0xc0+(9-index)*32
            val expectedHash=bytes.copyOfRange(hashOffset,hashOffset+32)
            require(sha256Range(start,dataSize.toInt()).contentEquals(expectedHash)){"ExeFS ${name} のSHA-256が一致しません"}
            entries[name]=data
        }
        require(".code" in entries && entries["icon"]?.size==SMDH_SIZE &&
            entries["icon"]?.copyOfRange(0,4)?.contentEquals("SMDH".toByteArray(Charsets.US_ASCII))==true &&
            (entries["banner"]?.size ?: 0)>=4 &&
            entries["banner"]?.copyOfRange(0,4)?.contentEquals("CBMD".toByteArray(Charsets.US_ASCII))==true){"Study Gadgetのアイコンまたはバナーを含むCIAではありません"}
    }
    private fun candidate(target: String, file: File, label: String): UpdateCandidate {
        require(target in setOf("3ds","cia","android")){"未対応の更新先です"}
        if(target=="3ds")inspect3ds(file) else if(target=="cia")inspectCia(file) else {
            require(file.length() in 1..MAX_APK)
            ZipFile(file).use { require(it.getEntry("AndroidManifest.xml")!=null && it.getEntry("classes.dex")!=null){"APKではありません"} }
        }
        return UpdateCandidate(target,file,file.length(),sha256(file),label.take(100))
    }
    fun unpack(input: File, work: File, depth: Int = 0): List<UpdateCandidate> {
        work.mkdirs();require(input.length() in 1..MAX_PACKAGE)
        val magic=ByteArray(4);input.inputStream().use { require(it.read(magic)==4) }
        if(magic.contentEquals("3DSX".toByteArray()))return listOf(candidate("3ds",input,"3DS HBL版"))
        if(ByteBuffer.wrap(magic).order(ByteOrder.LITTLE_ENDIAN).int==CIA_HEADER_SIZE)
            return listOf(candidate("cia",input,"Study Gadget CIA（FBI）"))
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
            require(bundle.format=="studygadget-update" && bundle.schema==1 && bundle.files.size in 1..3)
            require(bundle.files.map { it.target }.distinct().size==bundle.files.size){"更新先が重複しています"}
            val names=zip.entries().asSequence().map{it.name}.toList()
            require(names.distinct().size==names.size){"ZIPに重複名があります"}
            bundle.files.mapIndexed { index, entry ->
                require(entry.path in setOf("studygadget.3dsx","studygadget.cia","StudyCompanion.apk"))
                require(entry.sha256.matches(Regex("[0-9a-f]{64}")))
                val limit=when(entry.target){"3ds"->MAX_3DS;"cia"->MAX_CIA;else->MAX_APK}
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
