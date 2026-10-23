package com.example.studycompanion.character

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import org.junit.Test
import org.junit.Assert.*

class CharacterStoreTest {
    private fun fixture(): ByteArray {
        val payload=ByteBuffer.allocate(164+12+3*56+8*8*4).order(ByteOrder.LITTLE_ENDIAN)
        payload.put("hips".toByteArray());payload.position(32);payload.putInt(-1)
        repeat(2) { repeat(16) { i -> payload.putFloat(if(i%5==0)1f else 0f) } }
        payload.putInt(0);payload.putInt(3);payload.putInt(1)
        repeat(3) { v ->
            repeat(9) { i -> payload.putFloat(if(i>=5 || i==v)1f else 0f) }
            repeat(4) {payload.put(0.toByte())}
            repeat(4) {payload.putFloat(if(it==0)1f else 0f)}
        }
        while(payload.hasRemaining())payload.put(255.toByte())
        val bytes=payload.array();val all=ByteBuffer.allocate(bytes.size+36).order(ByteOrder.LITTLE_ENDIAN)
        all.put("SGCH".toByteArray());for(v in intArrayOf(1,42,1,3,8,1,CRC32().apply {update(bytes)}.value.toInt(),bytes.size))all.putInt(v)
        all.put(bytes);return all.array()
    }
    private fun fixCrc(data: ByteArray) { ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putInt(28,CRC32().apply {update(data,36,data.size-36)}.value.toInt()) }
    @Test fun acceptsValidFourWeightModel() {
        val m=CharacterStore.inspect(fixture());assertEquals(42L,m.skeleton);assertEquals(1,m.bones);assertEquals(1,m.triangles)
    }
    @Test fun rejectsCorruptionAndTruncation() {
        val data=fixture();data[data.lastIndex]=0
        assertTrue(runCatching {CharacterStore.inspect(data)}.isFailure)
        assertTrue(runCatching {CharacterStore.inspect(fixture().dropLast(1).toByteArray())}.isFailure)
    }
    @Test fun rejectsCyclicBonesAndInvalidSkinWeightsEvenWithValidCrc() {
        val cycle=fixture();ByteBuffer.wrap(cycle).order(ByteOrder.LITTLE_ENDIAN).putInt(36+32,0);fixCrc(cycle)
        assertTrue(runCatching {CharacterStore.inspect(cycle)}.isFailure)
        val bad=fixture();ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putFloat(36+164+12+40,Float.NaN);fixCrc(bad)
        assertTrue(runCatching {CharacterStore.inspect(bad)}.isFailure)
    }
}
