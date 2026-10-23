package com.example.studycompanion.character

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** CPU preview of SGCH, independent of Android graphics so the actual mesh can be tested. */
class CharacterRasterizer(data: ByteArray) {
    private data class Bone(val name: String, val parent: Int, val local: FloatArray,
                            val inverse: FloatArray, val basis: FloatArray)
    private data class Batch(val start: Int, val count: Int, val cull: Int)
    private val bones: List<Bone>
    private val batches: List<Batch>
    private val vertices: FloatArray
    private val indices: IntArray
    private val weights: FloatArray
    private val texture: IntArray
    private val texSize: Int
    private val vertexCount: Int

    init {
        CharacterStore.inspect(data)
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val boneCount = b.getInt(12)
        vertexCount = b.getInt(16)
        texSize = b.getInt(20)
        val batchCount = b.getInt(24)
        b.position(36)
        val rest = mutableListOf<FloatArray>()
        bones = List(boneCount) {
            val name = ByteArray(32).also { b.get(it) }.takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8)
            val parent = b.int
            val local = FloatArray(16) { b.float }
            val inverse = FloatArray(16) { b.float }
            val world = if (parent < 0) local else multiply(rest[parent], local)
            rest.add(world)
            val basis = identity()
            for (col in 0..2) {
                val length = sqrt((0..2).sumOf { row -> (world[row*4+col]*world[row*4+col]).toDouble() }).toFloat().coerceAtLeast(0.000001f)
                for (row in 0..2) basis[row*4+col] = world[row*4+col]/length
            }
            Bone(name, parent, local, inverse, basis)
        }
        batches = List(batchCount) { Batch(b.int, b.int, b.int) }
        vertices = FloatArray(vertexCount*9)
        indices = IntArray(vertexCount*4)
        weights = FloatArray(vertexCount*4)
        repeat(vertexCount) { v ->
            repeat(9) { vertices[v*9+it] = b.float }
            repeat(4) { indices[v*4+it] = b.get().toInt() and 255 }
            repeat(4) { weights[v*4+it] = b.float }
        }
        // 3DS RGBA8 is ABGR in Morton order within 8x8 tiles.
        val offset = b.position()
        texture = IntArray(texSize*texSize) { p ->
            val x = p%texSize; val y = p/texSize
            var morton = 0
            repeat(3) { bit -> morton = morton or (((x shr bit) and 1) shl (bit*2)) or (((y shr bit) and 1) shl (bit*2+1)) }
            val at = offset+((y/8)*(texSize/8)*64+(x/8)*64+morton)*4
            ((data[at].toInt() and 255) shl 24) or ((data[at+3].toInt() and 255) shl 16) or
                ((data[at+2].toInt() and 255) shl 8) or (data[at+1].toInt() and 255)
        }
    }

    /** Phase is normalized to one seamless loop; speed is applied to the host flip interval. */
    fun render(recipe: MotionRecipe, phase: Float, width: Int = 128, height: Int = 160,
               continueRendering: () -> Boolean = { true }): IntArray {
        require(recipe.valid() && phase.isFinite() && width in 16..512 && height in 16..512)
        val world = Array(bones.size) { identity() }
        val skin = Array(bones.size) { identity() }
        val s = sin(phase*2f*PI.toFloat()+recipe.phase)
        val q = sin(phase*2f*PI.toFloat()+recipe.phase+0.7f)
        for ((i, bone) in bones.withIndex()) {
            val name = bone.name
            var x = 0f; var y = 0f; var z = 0f
            var amp = recipe.intensity
            if (name == "upper_arm.L") z = -1.18f
            if (name == "upper_arm.R") z = 1.18f
            if (name == "chest") { z = q*recipe.sway; amp *= 0.4f; x = s*0.025f*amp }
            if (name == "head") {
                y = q*recipe.head*0.4f; x = s*recipe.head*0.3f
                if (recipe.state == 1) x += 0.12f
                if (recipe.state == 5) z = 0.18f
            }
            if (name.contains("hair.")) { x = s*0.04f*amp; z = q*recipe.sway*0.4f }
            if (recipe.state == 3 || recipe.state == 4) {
                if (name == "upper_arm.L") z += recipe.arm*(0.6f+0.25f*s)*amp
                if (name == "forearm.L") z = 0.5f*amp
                if (name == "hand.L") y = s*0.4f*amp
                if (recipe.state == 3 && name == "upper_arm.R") z -= recipe.arm*0.6f*amp
            }
            if (recipe.state == 2 && name == "chest") z += s*0.08f*amp
            if (recipe.state == 5 && name == "chest") x = 0.06f+s*0.01f
            val inverseBasis = identity()
            for (row in 0..2) for (col in 0..2) inverseBasis[row*4+col] = bone.basis[col*4+row]
            val local = multiply(bone.local, multiply(multiply(inverseBasis, rotation(x,y,z)), bone.basis))
            world[i] = if (bone.parent < 0) local else multiply(world[bone.parent], local)
            skin[i] = multiply(world[i], bone.inverse)
        }
        val points = FloatArray(vertexCount*3)
        val scale = min(width/0.96f, height/0.96f)
        repeat(vertexCount) { v ->
            repeat(3) { row ->
                var value = 0f
                repeat(4) { k ->
                    val matrix = skin[indices[v*4+k]]
                    value += weights[v*4+k]*(matrix[row*4]*vertices[v*9]+matrix[row*4+1]*vertices[v*9+1]+matrix[row*4+2]*vertices[v*9+2]+matrix[row*4+3])
                }
                points[v*3+row] = when(row) { 0 -> width*0.5f+value*scale; 1 -> height*0.48f-value*scale; else -> value }
            }
        }
        val pixels = IntArray(width*height)
        val depth = FloatArray(pixels.size) { Float.NEGATIVE_INFINITY }
        for (batch in batches) for (i in batch.start until batch.start+batch.count step 3) {
            if (i%96==0 && !continueRendering()) throw java.util.concurrent.CancellationException("Widget render superseded or time limit reached")
            val x0=points[i*3]; val y0=points[i*3+1]
            val x1=points[(i+1)*3]; val y1=points[(i+1)*3+1]
            val x2=points[(i+2)*3]; val y2=points[(i+2)*3+1]
            val area=(x1-x0)*(y2-y0)-(y1-y0)*(x2-x0)
            if (abs(area)<0.000001f || (batch.cull==1 && area>=0) || (batch.cull==2 && area<=0)) continue
            val left=floor(minOf(x0,x1,x2)).toInt().coerceAtLeast(0)
            val right=ceil(maxOf(x0,x1,x2)).toInt().coerceAtMost(width-1)
            val top=floor(minOf(y0,y1,y2)).toInt().coerceAtLeast(0)
            val bottom=ceil(maxOf(y0,y1,y2)).toInt().coerceAtMost(height-1)
            for (yy in top..bottom) for (xx in left..right) {
                val px=xx+0.5f; val py=yy+0.5f
                val a=((x1-px)*(y2-py)-(y1-py)*(x2-px))/area
                val b=((x2-px)*(y0-py)-(y2-py)*(x0-px))/area
                val c=1-a-b
                if (a<0 || b<0 || c<0) continue
                val at=yy*width+xx
                val z=a*points[i*3+2]+b*points[(i+1)*3+2]+c*points[(i+2)*3+2]
                if (z<=depth[at]) continue
                val u=a*vertices[i*9+3]+b*vertices[(i+1)*9+3]+c*vertices[(i+2)*9+3]
                val v=a*vertices[i*9+4]+b*vertices[(i+1)*9+4]+c*vertices[(i+2)*9+4]
                val color=texture[(v*texSize).toInt().coerceIn(0,texSize-1)*texSize+(u*texSize).toInt().coerceIn(0,texSize-1)]
                fun tint(channel: Int): Float = (a*vertices[i*9+5+channel]+b*vertices[(i+1)*9+5+channel]+c*vertices[(i+2)*9+5+channel]).coerceIn(0f,1f)
                if ((color ushr 24)*tint(3)<=8f) continue
                pixels[at] = (255 shl 24) or ((((color ushr 16) and 255)*tint(0)).toInt() shl 16) or
                    ((((color ushr 8) and 255)*tint(1)).toInt() shl 8) or ((color and 255)*tint(2)).toInt()
                depth[at]=z
            }
        }
        return pixels
    }

    private fun identity() = FloatArray(16) { if(it%5==0) 1f else 0f }
    private fun multiply(a: FloatArray, b: FloatArray) = FloatArray(16) { index ->
        val row=index/4; val col=index%4
        var sum=0f; repeat(4) { sum+=a[row*4+it]*b[it*4+col] }; sum
    }
    private fun rotation(x: Float,y: Float,z: Float): FloatArray {
        val cx=cos(x); val sx=sin(x); val cy=cos(y); val sy=sin(y); val cz=cos(z); val sz=sin(z)
        return floatArrayOf(cz*cy,cz*sy*sx-sz*cx,cz*sy*cx+sz*sx,0f,
            sz*cy,sz*sy*sx+cz*cx,sz*sy*cx-cz*sx,0f,-sy,cy*sx,cy*cx,0f,0f,0f,0f,1f)
    }
}
