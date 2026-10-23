package com.example.studycompanion.media

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import com.example.studycompanion.core.Logger
import com.example.studycompanion.domain.ext.MediaBackend
import com.example.studycompanion.domain.models.MediaCommand
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioManagerMediaBackend @Inject constructor(
    @ApplicationContext private val context: Context
) : MediaBackend {

    override val priority: Int = 100

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    override suspend fun handle(command: MediaCommand): Boolean {
        Logger.d("AudioManagerMediaBackend", "Handling command: $command")
        return try {
            when (command) {
                MediaCommand.PLAY_PAUSE -> {
                    dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                }
                MediaCommand.NEXT -> {
                    dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                }
                MediaCommand.PREVIOUS -> {
                    dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                }
                MediaCommand.VOLUME_UP -> {
                    audioManager.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_RAISE,
                        AudioManager.FLAG_SHOW_UI
                    )
                }
                MediaCommand.VOLUME_DOWN -> {
                    audioManager.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_LOWER,
                        AudioManager.FLAG_SHOW_UI
                    )
                }
            }
            true
        } catch (e: Exception) {
            Logger.e("AudioManagerMediaBackend", "Error handling media command $command", e)
            true
        }
    }

    private fun dispatchMediaKey(keyCode: Int) {
        val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val upEvent = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        audioManager.dispatchMediaKeyEvent(downEvent)
        audioManager.dispatchMediaKeyEvent(upEvent)
    }
}
