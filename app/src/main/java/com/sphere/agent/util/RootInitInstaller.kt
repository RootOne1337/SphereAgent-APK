package com.sphere.agent.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader

/**
 * ENTERPRISE: ROOT Init Script Installer
 * 
 * Устанавливает init.d скрипт который ГАРАНТИРОВАННО запускает приложение
 * при КАЖДОЙ загрузке устройства - ДО того как Android проверяет permissions!
 * 
 * Работает на:
 * - Magisk (через service.d)
 * - SuperSU (через su.d)
 * - Стандартный init.d
 * - LDPlayer/BlueStacks/Nox (через post-fs-data.d)
 */
object RootInitInstaller {
    
    private const val TAG = "RootInitInstaller"
    private const val PACKAGE_NAME = "com.sphere.agent"
    private const val SERVICE_CLASS = "com.sphere.agent.service.AgentService"
    private const val SCRIPT_NAME = "99-sphere-agent"
    private const val INIT_RC_NAME = "99-sphere-agent.rc"
    private const val START_SCRIPT_PATH = "/data/local/tmp/sphere-agent-start.sh"
    
    // Возможные пути для init скриптов
    private val INIT_PATHS = listOf(
        "/data/adb/service.d",          // Magisk service.d (после boot_completed)
        "/data/adb/post-fs-data.d",     // Magisk post-fs-data.d (раньше)
        "/su/su.d",                      // SuperSU
        "/system/etc/init.d",            // Стандартный init.d
        "/data/local/userinit.d",        // Некоторые ROM
        "/data/local"                    // Fallback
    )

    // Пути для init rc (самый жёсткий и надёжный автозапуск)
    private val INIT_RC_PATHS = listOf(
        "/system/etc/init",
        "/system_root/etc/init",
        "/vendor/etc/init",
        "/product/etc/init",
        "/odm/etc/init",
        "/system_ext/etc/init"
    )
    
    /**
     * Установка init скрипта для автозапуска
     * v3.6.0: Batch su — одна сессия для ВСЕХ операций
     */
    suspend fun installInitScript(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!RootAutoStart.hasRootAccess()) {
            Log.w(TAG, "No ROOT access, cannot install init script")
            return@withContext false
        }
        
        Log.d(TAG, "Installing init script (batch mode)...")
        
        // ОДНА batch команда: найти директорию + установить скрипт
        val scriptContent = getScriptContent()
        val batchScript = buildString {
            appendLine("INSTALLED=0")
            for (path in INIT_PATHS) {
                appendLine("if [ -d $path ] && [ \$INSTALLED -eq 0 ]; then")
                appendLine("  mkdir -p $path 2>/dev/null")
                appendLine("  cat > $path/$SCRIPT_NAME.sh << 'SPHERE_SCRIPT_EOF'")
                appendLine(scriptContent)
                appendLine("SPHERE_SCRIPT_EOF")
                appendLine("  chmod 755 $path/$SCRIPT_NAME.sh")
                appendLine("  chown root:root $path/$SCRIPT_NAME.sh 2>/dev/null")
                appendLine("  INSTALLED=1")
                appendLine("  echo \"installed_to=$path\"")
                appendLine("fi")
            }
            appendLine("if [ \$INSTALLED -eq 0 ]; then")
            appendLine("  echo '#!/system/bin/sh' > /data/local/userinit.sh")
            appendLine("  echo '/data/local/sphere-agent-start.sh &' >> /data/local/userinit.sh")
            appendLine("  cat > /data/local/sphere-agent-start.sh << 'SPHERE_SCRIPT_EOF'")
            appendLine(scriptContent)
            appendLine("SPHERE_SCRIPT_EOF")
            appendLine("  chmod 755 /data/local/userinit.sh /data/local/sphere-agent-start.sh")
            appendLine("  INSTALLED=1")
            appendLine("  echo 'installed_to=/data/local'")
            appendLine("fi")
            appendLine("echo \"script_installed=\$INSTALLED\"")
        }
        
        val scriptResult = executeRootCommand(batchScript)
        val scriptInstalled = scriptResult.second.contains("script_installed=1")
        Log.d(TAG, "Script install: $scriptInstalled (${scriptResult.second.lines().lastOrNull { it.startsWith("installed_to=") } ?: "unknown"})")

        // Init.rc — отдельная batch команда (тоже 1 su процесс)
        val initRcInstalled = installInitRc()
        
        // Magisk module — только если есть
        val magiskResult = executeRootCommand("[ -d /data/adb/modules ] && echo 'magisk'")
        if (magiskResult.first && magiskResult.second.contains("magisk")) {
            installMagiskModule()
        }
        
        scriptInstalled || initRcInstalled
    }
    
    /**
     * Содержимое init скрипта
     */
    private fun getScriptContent(): String {
        return """#!/system/bin/sh
# SphereAgent Auto-Start Script
# Гарантированный запуск при каждой загрузке устройства

PACKAGE="$PACKAGE_NAME"
SERVICE="$SERVICE_CLASS"
LOG="/data/local/tmp/sphere-agent-boot.log"

echo "[$(date)] SphereAgent init script started" >> ${'$'}LOG

MAX_WAIT=120
WAITED=0

# Ждём пока система полностью загрузится (с таймаутом)
sleep 10

# Проверяем что система готова
while [ "$(getprop sys.boot_completed)" != "1" ] && \
      [ "$(getprop dev.bootcomplete)" != "1" ] && \
      [ "$(getprop service.bootanim.exit)" != "1" ] && \
      [ ${'$'}WAITED -lt ${'$'}MAX_WAIT ]; do
    echo "[$(date)] Waiting for boot_completed... waited=${'$'}WAITED" >> ${'$'}LOG
    sleep 5
    WAITED=$((WAITED + 5))
done

echo "[$(date)] Boot status: sys.boot_completed=$(getprop sys.boot_completed), dev.bootcomplete=$(getprop dev.bootcomplete), bootanim.exit=$(getprop service.bootanim.exit)" >> ${'$'}LOG

echo "[$(date)] Boot completed, starting SphereAgent..." >> ${'$'}LOG

# Запускаем сервис через am
am start-foreground-service -n ${'$'}PACKAGE/${'$'}SERVICE >> ${'$'}LOG 2>&1

# Альтернативный способ - запуск невидимой activity
am start -n ${'$'}PACKAGE/.AutoStartActivity --activity-no-history >> ${'$'}LOG 2>&1

# Fallback - через broadcast
am broadcast -a android.intent.action.BOOT_COMPLETED -n ${'$'}PACKAGE/.receiver.BootReceiver >> ${'$'}LOG 2>&1

echo "[$(date)] SphereAgent start commands sent" >> ${'$'}LOG

# Даём время на запуск и проверяем
sleep 10

# Проверяем запустился ли процесс
if pidof ${'$'}PACKAGE > /dev/null 2>&1; then
    echo "[$(date)] SphereAgent is running!" >> ${'$'}LOG
else
    echo "[$(date)] SphereAgent not running, retrying..." >> ${'$'}LOG
    
    # Повторная попытка
    am start-foreground-service -n ${'$'}PACKAGE/${'$'}SERVICE >> ${'$'}LOG 2>&1
    sleep 5
    
    if pidof ${'$'}PACKAGE > /dev/null 2>&1; then
        echo "[$(date)] SphereAgent started on retry!" >> ${'$'}LOG
    else
        echo "[$(date)] FAILED to start SphereAgent!" >> ${'$'}LOG
    fi
fi

exit 0
"""
    }
    
    // v3.6.0: installScriptToPath, installCustomAutoStart, checkMagiskAvailable
    // удалены — заменены batch-операциями в installInitScript()

    /**
     * ENTERPRISE: Устанавливает init.rc триггеры
     * v3.6.0 CRITICAL FIX: ВСЕ команды в ОДНОМ su сеансе!
     * БЫЛО: 7 remount + 6 directory checks + 6 file writes = 19 su процессов
     * СТАЛО: 1 su процесс с batch скриптом
     */
    private fun installInitRc(): Boolean {
        return try {
            Log.d(TAG, "Installing init.rc auto-start (single batch su)...")

            val rcContent = getInitRcContent()
            
            // ОДНА batch команда для ВСЕГО
            val batchScript = buildString {
                // Стартовый скрипт
                appendLine("cat > $START_SCRIPT_PATH << 'SPHERE_EOF'")
                appendLine(getScriptContent())
                appendLine("SPHERE_EOF")
                appendLine("chmod 755 $START_SCRIPT_PATH")
                
                // Remount — тихо, без ошибок
                appendLine("mount -o rw,remount / 2>/dev/null")
                appendLine("mount -o rw,remount /system 2>/dev/null")
                appendLine("mount -o rw,remount /vendor 2>/dev/null")
                
                // Ищем ПЕРВУЮ доступную init директорию и ставим ОДИН rc файл
                appendLine("INSTALLED=0")
                for (path in INIT_RC_PATHS) {
                    appendLine("if [ -d $path ] && [ \$INSTALLED -eq 0 ]; then")
                    appendLine("  cat > $path/$INIT_RC_NAME << 'RC_EOF'")
                    appendLine(rcContent)
                    appendLine("RC_EOF")
                    appendLine("  chmod 644 $path/$INIT_RC_NAME")
                    appendLine("  INSTALLED=1")
                    appendLine("fi")
                }
                appendLine("echo \"rc_installed=\$INSTALLED\"")
            }
            
            val result = executeRootCommand(batchScript)
            val installed = result.second.contains("rc_installed=1")
            Log.d(TAG, "Init.rc install result: $installed")
            installed
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install init.rc auto-start", e)
            false
        }
    }

    /**
     * Содержимое init.rc
     * v3.6.0 CRITICAL FIX: ОДИН триггер вместо 4!
     * БЫЛО: 4 triggers → скрипт запускался 4 раза при каждом boot = 4x нагрузка
     * СТАЛО: 1 trigger (sys.boot_completed=1) — самый надёжный и поздний
     */
    private fun getInitRcContent(): String {
        return """
on property:sys.boot_completed=1
    exec_background -- /system/bin/sh $START_SCRIPT_PATH
""".trimIndent()
    }
    
    // v3.6.0: checkMagiskAvailable removed — check inlined in installInitScript

    /**
     * Устанавливает Magisk module для автозапуска
     * v3.6.0: Одна batch команда вместо 5 отдельных su
     */
    private fun installMagiskModule(): Boolean {
        return try {
            Log.d(TAG, "Installing Magisk module (batch)...")
            
            val modulePath = "/data/adb/modules/sphere-agent-autostart"
            val scriptContent = getScriptContent()
            
            val batchScript = """
                mkdir -p $modulePath/service.d
                echo 'id=sphere-agent-autostart
name=SphereAgent AutoStart
version=1.0
versionCode=1
author=SphereADB
description=AutoStart SphereAgent at boot' > $modulePath/module.prop
                cat > $modulePath/service.d/start.sh << 'MAGISK_EOF'
$scriptContent
MAGISK_EOF
                chmod 755 $modulePath/service.d/start.sh
                echo 'magisk_ok'
            """.trimIndent()
            
            val result = executeRootCommand(batchScript)
            Log.d(TAG, "Magisk module: ${result.second.contains("magisk_ok")}")
            result.second.contains("magisk_ok")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install Magisk module", e)
            false
        }
    }
    
    /**
     * Немедленный запуск через ROOT (для проверки)
     */
    suspend fun forceStartNow(): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "Force starting SphereAgent via ROOT...")
        
        val commands = listOf(
            "am start-foreground-service -n $PACKAGE_NAME/$SERVICE_CLASS",
            "am startservice -n $PACKAGE_NAME/$SERVICE_CLASS",
            "am start -n $PACKAGE_NAME/.MainActivity"
        )
        
        var success = false
        for (cmd in commands) {
            val result = executeRootCommand(cmd)
            if (result.first) {
                Log.d(TAG, "Started via: $cmd")
                success = true
                break
            }
        }
        
        success
    }
    
    /**
     * Проверяет установлен ли init скрипт
     * v3.6.1: Одна batch su вместо 6 отдельных
     */
    fun isInitScriptInstalled(): Boolean {
        val checkScript = INIT_PATHS.joinToString("\n") { path ->
            "[ -f $path/$SCRIPT_NAME.sh ] && echo 'installed'"
        }
        val result = executeRootCommand(checkScript)
        return result.first && result.second.contains("installed")
    }
    
    /**
     * Удаляет init скрипт
     */
    fun uninstallInitScript(): Boolean {
        var removed = false
        for (path in INIT_PATHS) {
            val scriptPath = "$path/$SCRIPT_NAME.sh"
            val result = executeRootCommand("rm -f $scriptPath 2>/dev/null && echo 'ok'")
            if (result.first && result.second.contains("ok")) {
                removed = true
            }
        }
        
        // Удаляем Magisk module
        executeRootCommand("rm -rf /data/adb/modules/sphere-agent-autostart")
        
        return removed
    }
    
    /**
     * Выполнение ROOT команды
     * v3.5.1: Добавлен таймаут для предотвращения зависаний
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
            
            // v3.5.1: Таймаут 5 секунд
            val finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            
            os.close()
            reader.close()
            errorReader.close()
            
            if (!finished) {
                process.destroyForcibly()
                return Pair(false, "Command timed out")
            }
            
            val exitCode = process.exitValue()
            
            if (exitCode == 0) {
                Pair(true, output.trim())
            } else {
                Pair(false, error.ifEmpty { output }.trim())
            }
        } catch (e: Exception) {
            Pair(false, e.message ?: "Unknown error")
        }
    }
}
