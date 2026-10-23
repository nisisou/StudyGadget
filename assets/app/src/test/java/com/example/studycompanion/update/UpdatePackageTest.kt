package com.example.studycompanion.update

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class UpdatePackageTest {
    private fun align512(size:Int)=((size+511)/512)*512
    private fun align64(size:Int)=((size+63)/64)*64
    private fun digest(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun ciaFixture(titleId:Long=0x000400000F764200L):ByteArray {
        val code=byteArrayOf(1,2,3,4)
        val icon=ByteArray(0x36C0).apply{"SMDH".toByteArray().copyInto(this)}
        val banner=ByteArray(0x100).apply{"CBMD".toByteArray().copyInto(this)}
        val packedCode=ByteArray(align512(code.size)).apply{code.copyInto(this)}
        val packedIcon=ByteArray(align512(icon.size)).apply{icon.copyInto(this)}
        val packedBanner=ByteArray(align512(banner.size)).apply{banner.copyInto(this)}
        val exeFs=ByteArray(0x200+packedCode.size+packedIcon.size+packedBanner.size)
        val exeHeader=ByteBuffer.wrap(exeFs).order(ByteOrder.LITTLE_ENDIAN)
        var dataOffset=0
        listOf(".code" to code,"icon" to icon,"banner" to banner).forEachIndexed { index,(name,data) ->
            val entryOffset=index*16
            exeHeader.position(entryOffset)
            exeHeader.put(name.toByteArray().copyOf(8))
            exeHeader.putInt(dataOffset)
            exeHeader.putInt(data.size)
            val hashOffset=0xC0+(9-index)*32
            digest(data).copyInto(exeFs,hashOffset)
            dataOffset+=align512(data.size)
        }
        var payloadOffset=0x200
        listOf(packedCode,packedIcon,packedBanner).forEach { data ->
            data.copyInto(exeFs,payloadOffset)
            payloadOffset+=data.size
        }

        val exHeader=ByteArray(0x400)
        val ncchSize=0x200+exHeader.size+exeFs.size
        val ncch=ByteArray(ncchSize)
        val ncchHeader=ByteBuffer.wrap(ncch).order(ByteOrder.LITTLE_ENDIAN)
        ncchHeader.putInt(0x104,ncchSize/512)
        "NCCH".toByteArray().copyInto(ncch,0x100)
        ncchHeader.putLong(0x118,titleId)
        ncchHeader.put(0x18e,0)
        ncchHeader.put(0x18f,4)
        digest(exHeader).copyInto(ncch,0x160)
        ncchHeader.putInt(0x180,exHeader.size)
        ncchHeader.putInt(0x184,1)
        ncchHeader.putInt(0x188,exHeader.size/512)
        ncchHeader.putInt(0x1a0,(0x200+exHeader.size)/512)
        ncchHeader.putInt(0x1a4,exeFs.size/512)
        ncchHeader.putInt(0x1a8,1)
        digest(exeFs.copyOfRange(0,0x200)).copyInto(ncch,0x1c0)
        exHeader.copyInto(ncch,0x200)
        exeFs.copyInto(ncch,0x200+exHeader.size)

        val ticket=ByteArray(0x350)
        ByteBuffer.wrap(ticket).order(ByteOrder.BIG_ENDIAN).putInt(0,0x00010004)
        ByteBuffer.wrap(ticket).order(ByteOrder.BIG_ENDIAN).putLong(0x1dc,titleId)
        val tmd=ByteArray(0xb34)
        val tmdHeader=ByteBuffer.wrap(tmd).order(ByteOrder.BIG_ENDIAN)
        tmdHeader.putInt(0,0x00010004)
        tmdHeader.putLong(0x18c,titleId)
        tmdHeader.putShort(0x1de,1)
        tmdHeader.putLong(0xb0c,ncch.size.toLong())
        digest(ncch).copyInto(tmd,0xb14)
        val ticketOffset=0x2040
        val tmdOffset=ticketOffset+align64(ticket.size)
        val contentOffset=tmdOffset+align64(tmd.size)
        val cia=ByteArray(contentOffset+ncch.size)
        val ciaHeader=ByteBuffer.wrap(cia).order(ByteOrder.LITTLE_ENDIAN)
        ciaHeader.putInt(0,CIA_HEADER_SIZE)
        ciaHeader.putInt(12,ticket.size)
        ciaHeader.putInt(16,tmd.size)
        ciaHeader.putLong(24,ncch.size.toLong())
        ticket.copyInto(cia,ticketOffset)
        tmd.copyInto(cia,tmdOffset)
        ncch.copyInto(cia,contentOffset)
        return cia
    }
    private companion object { const val CIA_HEADER_SIZE=0x2020 }

    private fun fixture(): ByteArray {
        val bytes=ByteArray(120);val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        b.put("3DSX".toByteArray());b.putShort(32);b.putShort(8);b.putInt(0);b.putInt(0);b.putInt(64);b.putInt(0);b.putInt(0);b.putInt(0)
        UpdatePackage.APP_MARKER.toByteArray().copyInto(bytes,56);return bytes
    }
    private fun work(action:(File)->Unit){
        val root=Files.createTempDirectory("sg-update-test-").toFile()
        try{action(root)}finally{require(root.canonicalFile.parentFile==File(requireNotNull(System.getProperty("java.io.tmpdir"))).canonicalFile);root.deleteRecursively()}
    }
    private fun bad(action:()->Unit){try{action();fail("Expected update rejection")}catch(expected:IllegalArgumentException){}}
    @Test fun matchingApkCanBeReinstalledOrUpgraded(){
        UpdatePackage.validateApkIdentity("app","app",6,6,setOf("key"),setOf("key"))
        UpdatePackage.validateApkIdentity("app","app",6,7,setOf("key"),setOf("key"))
    }
    @Test fun otherPackageRejected(){bad{UpdatePackage.validateApkIdentity("app","other",6,7,setOf("key"),setOf("key"))}}
    @Test fun downgradeRejected(){bad{UpdatePackage.validateApkIdentity("app","app",6,5,setOf("key"),setOf("key"))}}
    @Test fun changedOrMissingSignerRejected(){
        bad{UpdatePackage.validateApkIdentity("app","app",6,7,setOf("key"),setOf("other"))}
        bad{UpdatePackage.validateApkIdentity("app","app",6,7,setOf("key"),emptySet())}
    }
    @Test fun valid3dsExecutable()=work { root ->
        val file=File(root,"app").apply{writeBytes(fixture())};val result=UpdatePackage.unpack(file,File(root,"work"))
        assertEquals("3ds",result.single().target);assertEquals(120L,result.single().size)
    }
    @Test fun validCiaForStudyGadgetIsAccepted()=work { root ->
        val file=File(root,"app.cia").apply{writeBytes(ciaFixture())}
        UpdatePackage.inspectCia(file)
        assertEquals("cia",UpdatePackage.unpack(file,File(root,"work")).single().target)
    }
    @Test fun ciaForDifferentTitleIsRejected()=work { root ->
        val file=File(root,"other.cia").apply{writeBytes(ciaFixture(0x0004000000000000L))}
        bad{UpdatePackage.inspectCia(file)}
    }
    @Test fun corruptedCiaExeFsHashIsRejected()=work { root ->
        val bytes=ciaFixture()
        val changedOffset=0x2f00+0x200+0x400+0x200
        bytes[changedOffset]=(bytes[changedOffset].toInt() xor 1).toByte()
        val file=File(root,"corrupt.cia").apply{writeBytes(bytes)}
        bad{UpdatePackage.inspectCia(file)}
    }
    @Test fun markerRequired()=work { root ->
        val bytes=fixture();bytes.fill(0,56,120);val file=File(root,"bad").apply{writeBytes(bytes)};bad{UpdatePackage.inspect3ds(file)}
    }
    @Test fun oversizedSegmentRejected()=work { root ->
        val bytes=fixture();ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(16,-1)
        val file=File(root,"bad").apply{writeBytes(bytes)};bad{UpdatePackage.inspect3ds(file)}
    }
    @Test fun invalidBssRejected()=work { root ->
        val bytes=fixture();ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(28,1)
        val file=File(root,"bad").apply{writeBytes(bytes)};bad{UpdatePackage.inspect3ds(file)}
    }
    @Test fun copyIsBounded()=work { root ->
        bad{UpdatePackage.copyBounded(ByteArrayInputStream(ByteArray(101)),File(root,"part"),100)}
        assertTrue(File(root,"part").length()<=100)
    }
    private fun bundle(root:File,hash:String,path:String="studygadget.3dsx",targets:String=""):File {
        val source=File(root,"raw").apply{writeBytes(fixture())}
        val entry="""{"target":"3ds","path":"$path","size":120,"sha256":"$hash"}"""
        val entries=if(targets=="duplicate")"$entry,$entry" else entry
        return File(root,"update.sgupdate").apply {
            ZipOutputStream(outputStream()).use { z ->
                z.putNextEntry(ZipEntry("study-update.json"));z.write("""{"format":"studygadget-update","schema":1,"version":"test","files":[$entries]}""".toByteArray());z.closeEntry()
                z.putNextEntry(ZipEntry("studygadget.3dsx"));z.write(source.readBytes());z.closeEntry()
            }
        }
    }
    private fun ciaBundle(root:File):File {
        val cia=File(root,"cia-input").apply{writeBytes(ciaFixture())}
        val hash=UpdatePackage.sha256(cia)
        return File(root,"cia-update.sgupdate").apply {
            ZipOutputStream(outputStream()).use { z ->
                z.putNextEntry(ZipEntry("study-update.json"))
                z.write("""{"format":"studygadget-update","schema":1,"version":"test","files":[{"target":"cia","path":"studygadget.cia","size":${cia.length()},"sha256":"$hash"}]}""".toByteArray())
                z.closeEntry()
                z.putNextEntry(ZipEntry("studygadget.cia"));z.write(cia.readBytes());z.closeEntry()
            }
        }
    }
    @Test fun verifiedCiaBundle()=work { root ->
        val result=UpdatePackage.unpack(ciaBundle(root),File(root,"work"))
        assertEquals("cia",result.single().target)
        // Bundles show their declared version; raw CIA files use the product label.
        assertEquals("test",result.single().label)
    }
    @Test fun verifiedBundle()=work { root ->
        val raw=File(root,"fixture").apply{writeBytes(fixture())};val hash=UpdatePackage.sha256(raw)
        val result=UpdatePackage.unpack(bundle(root,hash),File(root,"work"));assertEquals(hash,result.single().sha256)
    }
    @Test fun githubArtifactWrapperAccepted()=work { root ->
        val raw=File(root,"fixture").apply{writeBytes(fixture())}
        val inner=bundle(root,UpdatePackage.sha256(raw))
        val wrapper=File(root,"artifact.zip").apply { ZipOutputStream(outputStream()).use { z -> z.putNextEntry(ZipEntry("StudyGadget-update.sgupdate"));z.write(inner.readBytes());z.closeEntry() } }
        assertEquals("3ds",UpdatePackage.unpack(wrapper,File(root,"work")).single().target)
    }
    @Test fun corruptedBundleRejected()=work { root ->bad{UpdatePackage.unpack(bundle(root,"0".repeat(64)),File(root,"work"))}}
    @Test fun traversalPathRejected()=work { root ->bad{UpdatePackage.unpack(bundle(root,"0".repeat(64),"../escape"),File(root,"work"))};assertFalse(File(root.parentFile,"escape").exists())}
    @Test fun duplicateTargetRejected()=work { root ->bad{UpdatePackage.unpack(bundle(root,"0".repeat(64),targets="duplicate"),File(root,"work"))}}
}
