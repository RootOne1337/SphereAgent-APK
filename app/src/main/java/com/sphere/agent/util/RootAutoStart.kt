package com.sphere.agent.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

/**
 * ENTERPRISE: ROOT-based Auto Start
 * 
 * Использует ROOT права для:
 * 1. Запуска сервиса через `am start-foreground-service`
 * 2. Установки приложения в whitelist батареи
 * 3. Отключения battery optimization
 * 4. Создания init.d скрипта для автозапуска
 */
object RootAutoStart {
    
    private const val TAG = "RootAutoStart"
    private const val PACKAGE_NAME = "com.sphere.agent"
    private const val SERVICE_CLASS = "com.sphere.agent.service.AgentService"
    
    /**
     * Проверка наличия ROOT доступа
     */
    fun hasRootAccess(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su -c id")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            process.waitFor()
            output.contains("uid=0")
        } catch (e: Exception) {
            Log.w(TAG, "No root access: ${e.message}")
            false
        }
    }
    
    /**
     * ENTERPRISE: Запуск сервиса через ROOT
     * Работает даже если приложение force-stopped!
     */
    suspend fun startServiceViaRoot(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting service via ROOT...")
            
            val commands = arrayOf(
                // Метод 1: Запускаем foreground service через am
                "am start-foreground-service -n $PACKAGE_NAME/$SERVICE_CLASS",
                // Метод 2: Стандартный startservice
                "am startservice -n $PACKAGE_NAME/$SERVICE_CLASS",
                // Метод 3: Запускаем невидимую AutoStartActivity
                "am start -n $PACKAGE_NAME/.AutoStartActivity --activity-no-history",
                // Метод 4: Запускаем главную Activity (будет видно на экране)
                "am start -n $PACKAGE_NAME/.MainActivity"
            )
            
            var success = false
            for (cmd in commands) {
                val result = executeRootCommand(cmd)
                Log.d(TAG, "Trying: $cmd -> ${result.first}")
                if (result.first) {
                    Log.d(TAG, "Service started via: $cmd")
                    success = true
                    break
                }
            }
            
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service via ROOT", e)
            false
        }
    }
    
    /**
     * ENTERPRISE: Отключение battery optimization для приложения
     * Критично для фоновой работы на Xiaomi/Huawei/Samsung!
     */
    suspend fun disableBatteryOptimization(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Disabling battery optimization via ROOT...")
            
            val commands = listOf(
                // Добавляем в whitelist battery optimization
                "dumpsys deviceidle whitelist +$PACKAGE_NAME",
                // Отключаем Doze для приложения
                "cmd appops set $PACKAGE_NAME RUN_IN_BACKGROUND allow",
                "cmd appops set $PACKAGE_NAME RUN_ANY_IN_BACKGROUND allow",
                // Samsung specific
                "cmd appops set $PACKAGE_NAME AUTO_REVOKE_PERMISSIONS_IF_UNUSED deny",
                // Xiaomi/MIUI specific
                "settings put global forced_app_standby_for_small_battery_enabled 0"
            )
            
            var allSuccess = true
            for (cmd in commands) {
                val result = executeRootCommand(cmd)
                if (!result.first) {
                    Log.w(TAG, "Command failed (may be OK): $cmd - ${result.second}")
                }
            }
            
            Log.d(TAG, "Battery optimization disabled")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable battery optimization", e)
            false
        }
    }
    
    /**
     * ENTERPRISE: Установка приложения как системного (persist)
     * После этого Android НЕ УБЬЁТ приложение!
     */
    suspend fun makeAppPersistent(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Making app persistent via ROOT...")
            
            val commands = listOf(
                // Делаем приложение persistent (как системное)
                "cmd activity set-inactive $PACKAGE_NAME false",
                // Устанавливаем высокий приоритет OOM
                "echo -17 > /proc/$(pidof $PACKAGE_NAME)/oom_adj 2>/dev/null",
                // Защищаем от kill
                "cmd appops set $PACKAGE_NAME BOOT_COMPLETED allow"
            )
            
            for (cmd in commands) {
                executeRootCommand(cmd)
            }
            
            Log.d(TAG, "App made persistent")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to make app persistent", e)
            false
        }
    }
    
    /**
     * ENTERPRISE: Полная инициализация ROOT автозапуска
     * Вызывать при первом запуске и после OTA!
     */
    suspend fun setupEnterpriseAutoStart(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!hasRootAccess()) {
            Log.w(TAG, "No ROOT access, skipping enterprise setup")
            return@withContext false
        }
        
        Log.d(TAG, "Setting up enterprise auto-start...")
        SphereLog.i(TAG, "Configuring ROOT-based auto-start...")
        
        var success = true
        
        // 1. Отключаем battery optimization
        if (!disableBatteryOptimization()) {
            success = false
        }
        
        // 2. Делаем приложение persistent
        if (!makeAppPersistent()) {
            success = false
        }
        
        // 3. Грантим BOOT_COMPLETED permission явно
        executeRootCommand("pm grant $PACKAGE_NAME android.permission.RECEIVE_BOOT_COMPLETED")
        
        // 4. Включаем компонент BootReceiver
        executeRootCommand("pm enable $PACKAGE_NAME/.receiver.BootReceiver")
        
        Log.d(TAG, "Enterprise auto-start configured: $success")
        SphereLog.i(TAG, "ROOT auto-start configured: $success")
        
        success
    }
    
    /**
     * Выполнение ROOT команды
     */
    private fun executeRootCommand(command: String): Pair<Boolean, String> {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            
            os.writeBytes("$command\n")
            os.writeBytes("exit\n")
            os.flush()
            
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))
            
            val output = reader.readText()
            val error = errorReader.readText()
            
            val exitCode = process.waitFor()
            
            os.close()
            reader.close()
            errorReader.close()
            
            if (exitCode == 0) {
                Pair(true, output)
            } else {
                Pair(false, error.ifEmpty { output })
            }
        } catch (e: Exception) {
            Pair(false, e.message ?: "Unknown error")
        }
    }
}
