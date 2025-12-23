package com.sphere.agent.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sphere.agent.service.AgentService

/**
 * BootReceiver - Автозапуск агента при загрузке устройства
 * 
 * Совместимость: Android 7.0+ (API 24)
 */
class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootReceiver"
    }
    
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        
        Log.d(TAG, "Received: ${intent.action}")
        
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                Log.d(TAG, "Boot completed, starting AgentService")
                try {
                    AgentService.start(context)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start AgentService", e)
                }
            }
        }
    }
}
