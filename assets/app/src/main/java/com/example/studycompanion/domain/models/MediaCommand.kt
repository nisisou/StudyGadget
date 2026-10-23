package com.example.studycompanion.domain.models

enum class MediaCommand {
    PLAY_PAUSE,
    NEXT,
    PREVIOUS,
    VOLUME_UP,
    VOLUME_DOWN;

    companion object {
        fun fromApiString(raw: String): MediaCommand? {
            return when (raw.lowercase()) {
                "play_pause", "playpause" -> PLAY_PAUSE
                "next" -> NEXT
                "previous" -> PREVIOUS
                "volume_up", "volumeup" -> VOLUME_UP
                "volume_down", "volumedown" -> VOLUME_DOWN
                else -> null
            }
        }
    }
}
