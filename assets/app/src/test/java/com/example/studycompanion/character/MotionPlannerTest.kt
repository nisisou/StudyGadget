package com.example.studycompanion.character

import org.junit.Test
import org.junit.Assert.*

class MotionPlannerTest {
    @Test fun generatedBanksCoverEveryStateAndStayBounded() {
        repeat(100) { seed ->
            val bank=MotionPlanner.validate(MotionPlanner.generate(seed,7200))
            assertEquals(48,bank.size)
            assertTrue(bank.all { it.valid() })
            (0..5).forEach { assertEquals(8,bank.count { m -> m.state==it }) }
        }
    }
    @Test fun rejectsMissingStatesAndOutOfRangeAiOutput() {
        val valid=MotionPlanner.generate(1,0)
        for(invalid in listOf(valid.filter { it.state!=5 },valid.map { it.copy(arm=100f) },valid.map { it.copy(speed=Float.NaN) })) {
            assertTrue(runCatching { MotionPlanner.validate(invalid) }.isFailure)
        }
    }
    @Test fun wireBankCarriesSkeletonAndRevision() {
        val bytes=MotionPlanner.encodeBank(4294967295,123,MotionPlanner.generate(1,0))
        val lines=bytes.toString(Charsets.US_ASCII).trim().lines()
        assertEquals("SGMO 1 4294967295 123 48",lines.first())
        assertEquals(49,lines.size)
        assertTrue(lines.drop(1).all { it.split(' ').size==8 })
    }
}
