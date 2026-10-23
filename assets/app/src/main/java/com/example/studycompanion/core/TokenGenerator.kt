package com.example.studycompanion.core

import java.security.SecureRandom

object TokenGenerator {
    private const val CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    fun generateToken(length: Int = 32): String {
        val random = SecureRandom()
        val sb = StringBuilder(length)
        for (i in 0 until length) {
            val index = random.nextInt(CHARS.length)
            sb.append(CHARS[index])
        }
        return sb.toString()
    }
}
