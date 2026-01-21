package com.sphere.agent.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sphere.agent.service.AgentService
import com.sphere.agent.util.SphereLog

/**
 * BootReceiver - Автозапуск агента при загрузке устройства и после OTA обновления
 * 
 * КРИТИЧНО ДЛЯ ОТКАЗОУСТОЙЧИВОСТИ:
 * - BOOT_COMPLETED: запуск после перезагрузки устройства
 * - QUICKBOOT_POWERON: запуск после быстрой перезагрузки (некоторые производители)
 * - MY_PACKAGE_REPLACED: запуск после OTA обновления APK
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
        SphereLog.i(TAG, "BootReceiver triggered: ${intent.action}")
        
        when (intent.action) {
            // Стандартный boot
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                Log.d(TAG, "Boot completed, starting AgentService")
                SphereLog.i(TAG, "Boot completed - starting AgentService")
                startAgentService(context)
            }
            
            // КРИТИЧНО: После OTA обновления APK
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.d(TAG, "Package replaced (OTA update), starting AgentService")
                SphereLog.i(TAG, "OTA update completed - restarting AgentService")
                startAgentService(context)
            }
            
            // Legacy для совместимости
            Intent.ACTION_PACKAGE_REPLACED -> {
                // Проверяем что это наш пакет
                val packageName = intent.data?.schemeSpecificPart
                if (packageName == context.packageName) {
                    Log.d(TAG, "Our package replaced, starting AgentService")
                    SphereLog.i(TAG, "Package replaced - restarting AgentService")
                    startAgentService(context)
                }
            }
        }
    }
    
    private fun startAgentService(context: Context) {
        try {
            // Небольшая задержка для стабильности системы после boot/update
            Thread.sleep(1000)
            AgentService.start(context)
            Log.d(TAG, "AgentService started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AgentService", e)
            SphereLog.e(TAG, "Failed to start AgentService: ${e.message}")
            
            // Retry через 5 секунд если первый раз не получилось
            try {
                Thread.sleep(5000)
                AgentService.start(context)
                Log.d(TAG, "AgentService started on retry")
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to start AgentService on retry", e2)
            }
        }
    }
}
