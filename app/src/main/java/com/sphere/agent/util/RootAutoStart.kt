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
     * v3.6.1: use{} для streams + destroyForcibly
     */
    suspend fun hasRootAccess(): Boolean = withContext(Dispatchers.IO) {
        var process: Process? = null
        try {
            process = Runtime.getRuntime().exec("su -c id")
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.errorStream.close() // drain
            val finished = process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                Log.w(TAG, "Root check timed out")
                return@withContext false
            }
            output.contains("uid=0")
        } catch (e: Exception) {
            Log.w(TAG, "No root access: ${e.message}")
            false
        } finally {
            process?.destroyForcibly()
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
     * v3.6.0 CRITICAL FIX: ВСЕ команды в ОДНОМ su сеансе!
     * БЫЛО: 10 отдельных su процессов (каждый — Runtime.exec("su"))
     * СТАЛО: 1 su сеанс с batch командами
     * На 14 эмуляторах: было 140 su процессов → теперь 14
     */
    suspend fun setupEnterpriseAutoStart(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Setting up enterprise auto-start (single batch su)...")
            
            // ОДИН su процесс со ВСЕМИ командами
            val batchScript = """
                # Battery optimization
                dumpsys deviceidle whitelist +$PACKAGE_NAME 2>/dev/null
                cmd appops set $PACKAGE_NAME RUN_IN_BACKGROUND allow 2>/dev/null
                cmd appops set $PACKAGE_NAME RUN_ANY_IN_BACKGROUND allow 2>/dev/null
                cmd appops set $PACKAGE_NAME AUTO_REVOKE_PERMISSIONS_IF_UNUSED deny 2>/dev/null
                cmd appops set $PACKAGE_NAME BOOT_COMPLETED allow 2>/dev/null
                # Persistent
                cmd activity set-inactive $PACKAGE_NAME false 2>/dev/null
                echo -17 > /proc/${'$'}(pidof $PACKAGE_NAME)/oom_adj 2>/dev/null
                # Permissions
                pm grant $PACKAGE_NAME android.permission.RECEIVE_BOOT_COMPLETED 2>/dev/null
                pm enable $PACKAGE_NAME/.receiver.BootReceiver 2>/dev/null
            """.trimIndent()
            
            val result = executeRootCommand(batchScript)
            Log.d(TAG, "Enterprise auto-start configured: ${result.first}")
            result.first
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup enterprise auto-start", e)
            false
        }
    }
    
    /**
     * Выполнение ROOT команды
     * v3.7.0: Исправлен deadlock — streams читаются в отдельных потоках ДО waitFor().
     * Было: readText() блокирует до EOF → waitFor(5s) недостижим при зависшем su.
     * Стало: параллельное чтение stdout+stderr с таймаутом через Future.get().
     */
    private fun executeRootCommand(command: String): Pair<Boolean, String> {
        var process: Process? = null
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        return try {
            process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            
            os.writeBytes("$command\n")
            os.writeBytes("exit\n")
            os.flush()
            os.close()
            
            // v3.7.0: Читаем stdout и stderr параллельно в отдельных потоках
            val stdoutFuture = executor.submit<String> {
                process.inputStream.bufferedReader().use { it.readText().take(65536) }
            }
            val stderrFuture = executor.submit<String> {
                process.errorStream.bufferedReader().use { it.readText().take(65536) }
            }
            
            // Ждём завершения процесса с таймаутом
            val finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            
            if (!finished) {
                process.destroyForcibly()
                stdoutFuture.cancel(true)
                stderrFuture.cancel(true)
                return Pair(false, "Command timed out")
            }
            
            val output = try { stdoutFuture.get(2, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { "" }
            val error = try { stderrFuture.get(2, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { "" }
            
            val exitCode = process.exitValue()
            
            if (exitCode == 0) {
                Pair(true, output)
            } else {
                Pair(false, error.ifEmpty { output })
            }
        } catch (e: Exception) {
            Pair(false, e.message ?: "Unknown error")
        } finally {
            process?.destroyForcibly()
            executor.shutdownNow()
        }
    }
}
