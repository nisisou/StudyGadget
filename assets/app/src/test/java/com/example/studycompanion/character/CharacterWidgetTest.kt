package com.example.studycompanion.character

import com.example.studycompanion.widget.WidgetMotion
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CharacterWidgetTest {
    @Test fun motionBankRoundTripsAndRejectsMismatchedVersions() {
        val recipes=MotionPlanner.generate(123,3600)
        val bytes=MotionPlanner.encodeBank(42,7,recipes)
        assertEquals(recipes,MotionPlanner.decodeBank(bytes,42,7))
        assertTrue(runCatching { MotionPlanner.decodeBank(bytes,43,7) }.isFailure)
        assertTrue(runCatching { MotionPlanner.decodeBank(bytes,42,8) }.isFailure)
        assertTrue(runCatching { MotionPlanner.decodeBank(bytes.copyOf(bytes.size/2),42,7) }.isFailure)
    }

    @Test fun automaticMotionPrioritizesLearningAndExpiresOldSessions() {
        val now=100_000_000L
        assertEquals(1,WidgetMotion.state(-1,now-1000,0,now,23))
        assertEquals(3,WidgetMotion.state(-1,0,now-1000,now,12))
        assertEquals(0,WidgetMotion.state(-1,0,now-31*60*1000,now,12))
        assertEquals(5,WidgetMotion.state(-1,now-13*60*60*1000,0,now,23))
        assertEquals(5,WidgetMotion.state(-1,now+1000,0,now,23))
        assertEquals(4,WidgetMotion.state(4,now-1000,0,now,23))
        assertEquals(0,WidgetMotion.nextMode(-1))
        assertEquals(-1,WidgetMotion.nextMode(5))
    }

    @Test fun actualBundledMeshRendersTransparentAnimatedFramesWithOutline() {
        val androidRoot=if(File("app/src/main/assets/character/model.sgch").isFile) File(".") else File("..")
        val data=File(androidRoot,"app/src/main/assets/character/model.sgch").readBytes()
        val renderer=CharacterRasterizer(data)
        val recipe=MotionRecipe(4,intensity=0.8f,arm=1.1f)
        val start=System.nanoTime()
        val a=renderer.render(recipe,0f)
        val b=renderer.render(recipe,0.5f)
        assertEquals(128*160,a.size)
        assertEquals(0,a[0])
        assertTrue(a.count { it!=0 }>1500)
        assertTrue(a.indices.count { a[it]!=b[it] }>50)
        assertTrue(a.any { (it ushr 24)==255 && (it and 0xffffff)<0x202020 })
        val end=renderer.render(recipe,1f)
        assertTrue(a.indices.count { a[it]!=end[it] }<20)
        // Review output from this same runtime, rather than a different rendering implementation.
        assertTrue(runCatching { renderer.render(recipe,0f,continueRendering={false}) }.isFailure)
        val file=File(androidRoot,"../diagnostics/widget-render.argb").also { it.parentFile?.mkdirs() }
        java.io.DataOutputStream(file.outputStream()).use { output ->
            for(state in 0..5) renderer.render(recipe.copy(state=state),0.2f).forEach { output.writeInt(it) }
        }
        println("SGCH widget render verification: ${(System.nanoTime()-start)/1_000_000} ms")
    }
}
