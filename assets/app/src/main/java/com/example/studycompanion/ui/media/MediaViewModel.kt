package com.example.studycompanion.ui.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.studycompanion.domain.ext.MediaBackend
import com.example.studycompanion.domain.models.MediaCommand
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MediaViewModel @Inject constructor(
    private val mediaBackends: Set<@JvmSuppressWildcards MediaBackend>
) : ViewModel() {

    fun sendCommand(command: MediaCommand) {
        viewModelScope.launch {
            val sorted = mediaBackends.sortedByDescending { it.priority }
            for (backend in sorted) {
                if (backend.handle(command)) break
            }
        }
    }
}
