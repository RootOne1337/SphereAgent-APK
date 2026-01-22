package com.sphere.agent.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sphere.agent.service.AgentService
import com.sphere.agent.service.BootJobService
import com.sphere.agent.util.RootAutoStart
import com.sphere.agent.util.SphereLog
import com.sphere.agent.worker.AgentWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BootReceiver - Автозапуск агента при загрузке устройства и после OTA обновления
 * 
 * ENTERPRISE FAULT TOLERANCE v2.21.0:
 * - BOOT_COMPLETED: стандартный boot
 * - LOCKED_BOOT_COMPLETED: Direct Boot (Android 7+) - срабатывает РАНЬШЕ!
 * - QUICKBOOT_POWERON: эмуляторы (LDPlayer, Nox, Bluestacks)
 * - HTC QUICKBOOT: HTC устройства
 * - USER_PRESENT: когда экран разблокирован
 * - USER_UNLOCKED: когда пользователь разблокировал устройство
 * - MY_PACKAGE_REPLACED: после OTA обновления APK
 * - directBootAware=true: запуск ДО разблокировки экрана!
 * 
 * Совместимость: Android 7.0+ (API 24)
 */
class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootReceiver"
        
        // Все возможные boot actions
        private val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            "android.intent.action.ACTION_BOOT_COMPLETED",
            "android.intent.action.REBOOT",
            Intent.ACTION_USER_PRESENT,
            Intent.ACTION_USER_UNLOCKED
        )
    }
    
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        
        val action = intent.action ?: return
        
        Log.d(TAG, "╔════════════════════════════════════════════╗")
        Log.d(TAG, "║     BOOT RECEIVER TRIGGERED!               ║")
        Log.d(TAG, "╠════════════════════════════════════════════╣")
        Log.d(TAG, "║ Action: $action")
        Log.d(TAG, "╚════════════════════════════════════════════╝")
        
        SphereLog.i(TAG, "BootReceiver triggered: $action")
        
        when {
            // Boot события
            action in BOOT_ACTIONS -> {
                Log.d(TAG, "Boot event detected: $action")
                SphereLog.i(TAG, "Boot completed - starting AgentService")
                startAgentService(context, "BOOT: $action")
            }
            
            // После OTA обновления APK
            action == Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.d(TAG, "Package replaced (OTA update)")
                SphereLog.i(TAG, "OTA update completed - restarting AgentService")
                startAgentService(context, "OTA_UPDATE")
            }
            
            // Legacy PACKAGE_REPLACED
            action == Intent.ACTION_PACKAGE_REPLACED -> {
                val packageName = intent.data?.schemeSpecificPart
                if (packageName == context.packageName) {
                    Log.d(TAG, "Our package replaced")
                    SphereLog.i(TAG, "Package replaced - restarting AgentService")
                    startAgentService(context, "PACKAGE_REPLACED")
                }
            }
            
            else -> {
                // Неизвестный action - всё равно пробуем запустить!
                Log.d(TAG, "Unknown action, but trying to start anyway: $action")
                startAgentService(context, "UNKNOWN: $action")
            }
        }
    }
    
    private fun startAgentService(context: Context, reason: String) {
        // Запускаем в отдельном потоке чтобы не блокировать broadcast
        Thread {
            try {
                Log.d(TAG, "Starting AgentService (reason: $reason)...")
                
                // НЕМЕДЛЕННО планируем JobScheduler - он ГАРАНТИРОВАННО выполнится!
                try {
                    BootJobService.scheduleImmediateJob(context)
                    BootJobService.schedulePeriodicJob(context)
                    Log.d(TAG, "BootJobService scheduled (immediate + periodic)")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to schedule BootJobService", e)
                }
                
                // Планируем WorkManager
                try {
                    AgentWorker.schedule(context)
                    Log.d(TAG, "AgentWorker scheduled")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to schedule AgentWorker", e)
                }
                
                // Небольшая задержка для стабильности
                Thread.sleep(2000)
                
                // Попытка 1: Стандартный запуск
                try {
                    AgentService.start(context)
                    Log.d(TAG, "AgentService started (standard method)")
                    SphereLog.i(TAG, "AgentService started (reason: $reason)")
                    
                    // Успех! Но всё равно попробуем ROOT для надёжности
                    Thread.sleep(3000)
                    if (!AgentService.isRunning) {
                        throw Exception("Service not running after start")
                    }
                    return@Thread
                } catch (e: Exception) {
                    Log.e(TAG, "Standard start failed: ${e.message}")
                }
                
                // Попытка 2: Retry через 5 секунд
                Thread.sleep(5000)
                try {
                    AgentService.start(context)
                    Log.d(TAG, "AgentService started on retry")
                    
                    Thread.sleep(3000)
                    if (AgentService.isRunning) {
                        return@Thread
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Retry start failed: ${e.message}")
                }
                
                // Попытка 3: ROOT запуск
                Log.d(TAG, "Trying ROOT start...")
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        if (RootAutoStart.hasRootAccess()) {
                            val success = RootAutoStart.startServiceViaRoot(context)
                            if (success) {
                                Log.d(TAG, "AgentService started via ROOT!")
                                SphereLog.i(TAG, "AgentService started via ROOT (reason: $reason)")
                            } else {
                                Log.e(TAG, "ROOT start returned false")
                                AgentWorker.checkNow(context)
                            }
                        } else {
                            Log.w(TAG, "No ROOT access")
                            AgentWorker.checkNow(context)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "ROOT start failed", e)
                        AgentWorker.checkNow(context)
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start AgentService completely", e)
                SphereLog.e(TAG, "Complete failure: ${e.message}")
            }
        }.start()
    }
}
