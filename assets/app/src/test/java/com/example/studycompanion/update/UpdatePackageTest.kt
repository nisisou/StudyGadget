package com.example.studycompanion.update

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class UpdatePackageTest {
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
