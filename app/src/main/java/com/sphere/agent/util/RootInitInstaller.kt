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
     */
    suspend fun installInitScript(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!RootAutoStart.hasRootAccess()) {
            Log.w(TAG, "No ROOT access, cannot install init script")
            return@withContext false
        }
        
        Log.d(TAG, "Installing init script for auto-start...")
        SphereLog.i(TAG, "Installing ROOT init script for guaranteed auto-start")
        
        // Находим подходящую директорию
        var installed = false
        
        for (path in INIT_PATHS) {
            try {
                // Проверяем существует ли директория
                val checkResult = executeRootCommand("[ -d $path ] && echo 'exists'")
                if (checkResult.first && checkResult.second.contains("exists")) {
                    Log.d(TAG, "Found init directory: $path")
                    
                    // Устанавливаем скрипт
                    if (installScriptToPath(path)) {
                        Log.d(TAG, "Script installed to: $path")
                        SphereLog.i(TAG, "Init script installed to: $path")
                        installed = true
                        break
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to check/install to $path: ${e.message}")
            }
        }
        
        // Если не нашли стандартную директорию - создаём в /data/local
        if (!installed) {
            Log.d(TAG, "No init directory found, creating custom solution...")
            installed = installCustomAutoStart()
        }

        // ENTERPRISE: Дополнительно устанавливаем init.rc (самый жёсткий уровень)
        val initRcInstalled = installInitRc()
        if (initRcInstalled) {
            Log.d(TAG, "Init.rc auto-start installed successfully")
            SphereLog.i(TAG, "Init.rc auto-start installed")
        } else {
            Log.w(TAG, "Init.rc auto-start not installed")
        }
        
        // Дополнительно: устанавливаем через Magisk module если доступен
        if (checkMagiskAvailable()) {
            installMagiskModule()
        }
        
        installed || initRcInstalled
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
    
    /**
     * Устанавливает скрипт в указанную директорию
     */
    private fun installScriptToPath(dirPath: String): Boolean {
        return try {
            val scriptPath = "$dirPath/$SCRIPT_NAME.sh"
            val scriptContent = getScriptContent()
            
            // Записываем скрипт через ROOT
            val commands = listOf(
                "mkdir -p $dirPath",
                "cat > $scriptPath << 'SCRIPT_EOF'\n$scriptContent\nSCRIPT_EOF",
                "chmod 755 $scriptPath",
                "chown root:root $scriptPath"
            )
            
            for (cmd in commands) {
                val result = executeRootCommand(cmd)
                if (!result.first) {
                    Log.w(TAG, "Command failed: $cmd")
                }
            }
            
            // Проверяем что файл создан
            val checkResult = executeRootCommand("[ -f $scriptPath ] && echo 'ok'")
            checkResult.first && checkResult.second.contains("ok")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install script to $dirPath", e)
            false
        }
    }
    
    /**
     * Устанавливает кастомный автозапуск через property trigger
     */
    private fun installCustomAutoStart(): Boolean {
        return try {
            Log.d(TAG, "Installing custom auto-start via property trigger...")
            
            // Создаём скрипт в /data/local
            val scriptPath = "/data/local/sphere-agent-start.sh"
            val scriptContent = getScriptContent()
            
            executeRootCommand("cat > $scriptPath << 'EOF'\n$scriptContent\nEOF")
            executeRootCommand("chmod 755 $scriptPath")
            
            // Создаём cron-like задачу через init.rc override (если возможно)
            // Или используем setprop trigger
            
            // Добавляем в /data/local/userinit.sh если существует
            val userinit = "/data/local/userinit.sh"
            executeRootCommand("""
                if [ -f $userinit ]; then
                    grep -q 'sphere-agent' $userinit || echo '$scriptPath &' >> $userinit
                else
                    echo '#!/system/bin/sh' > $userinit
                    echo '$scriptPath &' >> $userinit
                    chmod 755 $userinit
                fi
            """.trimIndent())
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install custom auto-start", e)
            false
        }
    }

    /**
     * ENTERPRISE: Устанавливает init.rc триггеры (самый надёжный автозапуск)
     * Работает даже если BootReceiver НЕ вызывается.
     */
    private fun installInitRc(): Boolean {
        return try {
            Log.d(TAG, "Installing init.rc auto-start...")

            // Гарантируем стартовый скрипт в /data/local/tmp
            executeRootCommand("cat > $START_SCRIPT_PATH << 'EOF'\n${getScriptContent()}\nEOF")
            executeRootCommand("chmod 755 $START_SCRIPT_PATH")

            // Пытаемся перемонтировать системные разделы в RW
            val remountCommands = listOf(
                "mount -o rw,remount /",
                "mount -o rw,remount /system",
                "mount -o rw,remount /system_root",
                "mount -o rw,remount /vendor",
                "mount -o rw,remount /product",
                "mount -o rw,remount /odm",
                "mount -o rw,remount /system_ext"
            )
            remountCommands.forEach { executeRootCommand(it) }

            val rcContent = getInitRcContent()
            var installed = false

            for (path in INIT_RC_PATHS) {
                val checkResult = executeRootCommand("[ -d $path ] && echo 'exists'")
                if (checkResult.first && checkResult.second.contains("exists")) {
                    val rcPath = "$path/$INIT_RC_NAME"
                    executeRootCommand("cat > $rcPath << 'EOF'\n$rcContent\nEOF")
                    executeRootCommand("chmod 644 $rcPath")
                    Log.d(TAG, "Init.rc installed to: $rcPath")
                    installed = true
                }
            }

            installed
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install init.rc auto-start", e)
            false
        }
    }

    /**
     * Содержимое init.rc
     */
    private fun getInitRcContent(): String {
        return """
on boot
    exec -- /system/bin/sh $START_SCRIPT_PATH

on property:sys.boot_completed=1
    exec -- /system/bin/sh $START_SCRIPT_PATH

on property:dev.bootcomplete=1
    exec -- /system/bin/sh $START_SCRIPT_PATH

on property:service.bootanim.exit=1
    exec -- /system/bin/sh $START_SCRIPT_PATH
""".trimIndent()
    }
    
    /**
     * Проверяет доступность Magisk
     */
    private fun checkMagiskAvailable(): Boolean {
        val result = executeRootCommand("[ -d /data/adb/modules ] && echo 'magisk'")
        return result.first && result.second.contains("magisk")
    }
    
    /**
     * Устанавливает Magisk module для автозапуска
     */
    private fun installMagiskModule(): Boolean {
        return try {
            Log.d(TAG, "Installing Magisk module for auto-start...")
            
            val modulePath = "/data/adb/modules/sphere-agent-autostart"
            
            val commands = listOf(
                "mkdir -p $modulePath/service.d",
                "mkdir -p $modulePath/post-fs-data.d",
                
                // module.prop
                """echo 'id=sphere-agent-autostart
name=SphereAgent AutoStart
version=1.0
versionCode=1
author=SphereADB
description=Автозапуск SphereAgent при загрузке' > $modulePath/module.prop""",
                
                // service.d script
                "cat > $modulePath/service.d/start.sh << 'EOF'\n${getScriptContent()}\nEOF",
                "chmod 755 $modulePath/service.d/start.sh"
            )
            
            for (cmd in commands) {
                executeRootCommand(cmd)
            }
            
            Log.d(TAG, "Magisk module installed")
            true
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
     */
    fun isInitScriptInstalled(): Boolean {
        for (path in INIT_PATHS) {
            val scriptPath = "$path/$SCRIPT_NAME.sh"
            val result = executeRootCommand("[ -f $scriptPath ] && echo 'installed'")
            if (result.first && result.second.contains("installed")) {
                return true
            }
        }
        return false
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
                Pair(true, output.trim())
            } else {
                Pair(false, error.ifEmpty { output }.trim())
            }
        } catch (e: Exception) {
            Pair(false, e.message ?: "Unknown error")
        }
    }
}
