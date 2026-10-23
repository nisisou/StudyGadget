package com.example.studycompanion.domain

import com.example.studycompanion.domain.models.LiveSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LiveSessionState @Inject constructor() {
    private val _live = MutableStateFlow<LiveSession?>(null)
    val live: StateFlow<LiveSession?> = _live.asStateFlow()

    private val _lastApiAccessTime = MutableStateFlow<Long>(0)
    val lastApiAccessTime: StateFlow<Long> = _lastApiAccessTime.asStateFlow()

    fun recordApiAccess() {
        _lastApiAccessTime.value = System.currentTimeMillis()
    }

    fun startLive(subject: String, startSec: Double = System.currentTimeMillis() / 1000.0) {
        _live.value = LiveSession(subject = subject, start = startSec)
    }

    fun stopLive() {
        _live.value = null
    }

    fun getValidLive(): LiveSession? {
        val current = _live.value ?: return null
        val nowSec = System.currentTimeMillis() / 1000.0
        // Auto clear if > 12 hours (43200 seconds)
        if (nowSec - current.start > 43200) {
            _live.value = null
            return null
        }
        return current
    }
}
