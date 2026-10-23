package com.example.studycompanion.ui.theme

import androidx.compose.ui.graphics.Color

val DarkBg = Color(0xFF0B0E1A)
val DarkCard = Color(0xFF1C2140)
val DarkText = Color(0xFFEEEEFF)
val DarkMuted = Color(0xFF8C96B8)

val LightBg = Color(0xFFF3F5FB)
val LightCard = Color(0xFFFFFFFF)
val LightText = Color(0xFF121830)
val LightMuted = Color(0xFF6B7596)

val PrimaryBlue = Color(0xFF60A5FA)
val AccentOrange = Color(0xFFF59E0B)
val AccentPink = Color(0xFFF472B6)
val AccentGreen = Color(0xFF10B981)
val AccentPurple = Color(0xFF8B5CF6)

fun parseHexColor(hex: String, default: Color = PrimaryBlue): Color {
    val clean = hex.removePrefix("#").trim()
    if (clean.length == 6) {
        return try {
            val colorInt = clean.toLong(16) or 0xFF000000L
            Color(colorInt)
        } catch (e: Exception) {
            default
        }
    }
    return default
}
