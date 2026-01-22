package com.sphere.agent

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.sphere.agent.service.AgentService

/**
 * ENTERPRISE: Invisible Auto-Start Activity
 * 
 * Невидимая Activity которая запускает AgentService и сразу закрывается.
 * Используется как fallback для запуска сервиса через am start.
 * 
 * Theme.NoDisplay - не показывает UI
 * excludeFromRecents - не показывается в recent apps
 * noHistory - не сохраняется в back stack
 */
class AutoStartActivity : Activity() {
    
    companion object {
        private const val TAG = "AutoStartActivity"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.d(TAG, "╔════════════════════════════════════════════╗")
        Log.d(TAG, "║     AUTO-START ACTIVITY TRIGGERED!         ║")
        Log.d(TAG, "╚════════════════════════════════════════════╝")
        
        try {
            // Запускаем AgentService
            AgentService.start(this)
            Log.d(TAG, "AgentService started from AutoStartActivity")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AgentService", e)
        }
        
        // Сразу закрываемся - мы невидимы
        finish()
    }
}
