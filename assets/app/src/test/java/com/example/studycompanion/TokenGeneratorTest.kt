package com.example.studycompanion

import com.example.studycompanion.core.TokenGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TokenGeneratorTest {

    @Test
    fun testTokenGeneration() {
        val token1 = TokenGenerator.generateToken(32)
        val token2 = TokenGenerator.generateToken(32)

        assertEquals(32, token1.length)
        assertEquals(32, token2.length)
        assertNotEquals(token1, token2)
    }
}
