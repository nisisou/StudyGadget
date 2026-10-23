package com.example.studycompanion.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.studycompanion.data.SettingsStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var settingsStore: SettingsStore

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val autoStart = settingsStore.autoStartFlow.first()
                    if (autoStart) {
                        SyncService.start(context)
                    }
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
