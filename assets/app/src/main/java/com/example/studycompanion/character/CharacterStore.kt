package com.example.studycompanion.character

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import org.json.JSONObject
import java.io.ByteArrayOutputStream

internal fun InputStream.readBounded(limit: Int): ByteArray {
    val out=ByteArrayOutputStream();val buf=ByteArray(8192)
    while(out.size()<limit) { val n=read(buf,0,minOf(buf.size,limit-out.size()));if(n<0)break;out.write(buf,0,n) }
    return out.toByteArray()
}

data class CharacterModel(val crc: Long, val skeleton: Long, val size: Int, val bones: Int, val triangles: Int)
data class CharacterSnapshot(val model: CharacterModel, val revision: Int, val bytes: ByteArray, val recipes: List<MotionRecipe>)

/** Immutable version files + AtomicFile pointer: download URLs keep returning the same bytes during generation. */
class CharacterStore(context: Context) {
    private val root = File(context.filesDir, "character").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("character", Context.MODE_PRIVATE)
    private val app = context.applicationContext
    var aiUrl: String
        get() = prefs.getString("aiUrl", "") ?: ""
        set(value) { prefs.edit().putString("aiUrl", value.trim()).apply() }
    var aiToken: String
        get() = prefs.getString("aiToken", "") ?: ""
        set(value) { prefs.edit().putString("aiToken", value.trim()).apply() }
    var nightEnabled: Boolean
        get() = prefs.getBoolean("nightEnabled", true)
        set(value) { prefs.edit().putBoolean("nightEnabled", value).apply() }
    var lastResult: String
        get() = prefs.getString("result", "未生成") ?: "未生成"
        set(value) { prefs.edit().putString("result", value).apply() }
    var generatedDay: String
        get() = prefs.getString("generatedDay", "") ?: ""
        set(value) { prefs.edit().putString("generatedDay", value).apply() }

    private fun pointer() = AtomicFile(File(root, "active.json"))
    private fun readPointer(): JSONObject? = runCatching { JSONObject(pointer().readFully().toString(Charsets.UTF_8)) }.getOrNull()
    private fun atomic(file: File, bytes: ByteArray) {
        val target=AtomicFile(file);val stream=target.startWrite()
        try { stream.write(bytes); target.finishWrite(stream) } catch (e: Exception) { target.failWrite(stream); throw e }
    }
    private fun writePointer(model: CharacterModel, revision: Int) = atomic(File(root,"active.json"), JSONObject()
        .put("crc",model.crc).put("skeleton",model.skeleton).put("size",model.size)
        .put("bones",model.bones).put("triangles",model.triangles).put("revision",revision).toString().toByteArray())

    fun initialize() = synchronized(guard) {
        if (readPointer() == null) app.assets.open("character/model.sgch").use { importModel(it) }
    }
    fun info(): CharacterModel = synchronized(guard) {
        initialize(); val p=requireNotNull(readPointer())
        CharacterModel(p.getLong("crc"),p.getLong("skeleton"),p.getInt("size"),p.getInt("bones"),p.getInt("triangles"))
    }
    fun revision(): Int = synchronized(guard) { initialize();requireNotNull(readPointer()).getInt("revision") }
    fun manifest(): ByteArray = synchronized(guard) { val m=info();"SGUP 1 ${m.crc} ${m.skeleton} ${m.size} ${revision()}\n".toByteArray() }
    fun modelFile(crc: Long): File? = File(root,"model-$crc.sgch").takeIf { it.isFile }
    fun motionFile(revision: Int): File? = File(root,"motions-$revision.sgmo").takeIf { it.isFile }

    fun snapshot(): CharacterSnapshot = synchronized(guard) {
        val model = info()
        val revision = revision()
        CharacterSnapshot(model,revision,requireNotNull(modelFile(model.crc)).readBytes(),
            MotionPlanner.decodeBank(requireNotNull(motionFile(revision)).readBytes(),model.skeleton,revision))
    }

    fun importModel(input: InputStream): CharacterModel = synchronized(guard) {
        val bytes = input.readBounded(MAX_BYTES+1)
        require(bytes.size <= MAX_BYTES) { "モデルは8MiB以下にしてください" }
        val model=inspect(bytes)
        atomic(File(root,"model-${model.crc}.sgch"),bytes)
        val revision=nextRevision()
        atomic(File(root,"motions-$revision.sgmo"), MotionPlanner.encodeBank(model.skeleton,revision,MotionPlanner.generate(revision,0)))
        writePointer(model,revision)
        lastResult="モデル更新・48パターン生成済み"
        com.example.studycompanion.widget.CharacterWidgetProvider.refresh(app)
        model
    }
    fun publish(recipes: List<MotionRecipe>, expectedSkeleton: Long, source: String): Int = synchronized(guard) {
        val model=info();require(model.skeleton==expectedSkeleton) { "モデルが変わりました。再生成してください" }
        val revision=nextRevision()
        atomic(File(root,"motions-$revision.sgmo"),MotionPlanner.encodeBank(model.skeleton,revision,recipes))
        writePointer(model,revision)
        lastResult="$source / ${recipes.size}パターン / ${java.time.LocalDateTime.now().withNano(0)}"
        generatedDay=java.time.LocalDate.now().toString()
        prune()
        com.example.studycompanion.widget.CharacterWidgetProvider.refresh(app)
        revision
    }
    private fun nextRevision(): Int = maxOf((System.currentTimeMillis()/1000).toInt(),(readPointer()?.optInt("revision") ?: 0)+1)
    private fun prune() {
        // Retain current plus three prior snapshots for clients already downloading an old manifest.
        for(prefix in listOf("motions-","model-")) root.listFiles()?.filter { it.name.startsWith(prefix) }
            ?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { it.delete() }
    }
    companion object {
        private val guard=Any()
        const val MAX_BYTES=8*1024*1024
        fun inspect(data: ByteArray): CharacterModel {
            require(data.size>=36 && data.copyOfRange(0,4).contentEquals("SGCH".toByteArray())) { "SGCHモデルではありません" }
            val b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);b.position(4)
            val version=b.int;val skeleton=b.int.toLong() and 0xffffffffL;val bones=b.int;val vertices=b.int
            val tex=b.int;val batches=b.int;val crc=b.int.toLong() and 0xffffffffL;val payload=b.int
            require(version==1 && bones in 1..96 && vertices in 3..24000 && vertices%3==0 && tex in 8..1024 && (tex and (tex-1))==0 && batches in 1..64)
            require(payload==bones*164+batches*12+vertices*56+tex*tex*4 && data.size==payload+36)
            require(CRC32().apply { update(data,36,payload) }.value==crc) { "モデルのCRCが一致しません" }
            repeat(bones) { index ->
                val name=ByteArray(32);b.get(name);require(name.contains(0))
                require(b.int in -1 until index)
                repeat(32) { require(b.float.let { it.isFinite() && kotlin.math.abs(it)<=10000 }) }
            }
            var total=0
            repeat(batches) { val start=b.int;val count=b.int;val cull=b.int;require(start==total && count>=0 && count%3==0 && count<=vertices-total && cull in 0..2);total+=count }
            require(total==vertices)
            repeat(vertices) {
                repeat(9) { require(b.float.let { it.isFinite() && kotlin.math.abs(it)<=10000 }) }
                repeat(4) { require((b.get().toInt() and 255)<bones) }
                var sum=0f;repeat(4) { val w=b.float;require(w.isFinite() && w in 0f..1f);sum+=w };require(kotlin.math.abs(sum-1f)<0.01f)
            }
            return CharacterModel(crc,skeleton,data.size,bones,vertices/3)
        }
    }
}
