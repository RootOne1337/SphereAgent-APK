package com.sphere.agent.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

/**
 * CommandExecutor - Исполнитель команд управления устройством
 * 
 * Выполняет:
 * - Tap (нажатие)
 * - Swipe (свайп)
 * - Key events (кнопки)
 * - Shell команды
 * 
 * Использует input команды через shell (требует root или ADB)
 * Fallback на Accessibility Service для non-root устройств
 */

class CommandExecutor(private val context: Context) {
    
    companion object {
        private const val TAG = "CommandExecutor"
        
        // Key codes
        const val KEYCODE_HOME = 3
        const val KEYCODE_BACK = 4
        const val KEYCODE_MENU = 82
        const val KEYCODE_APP_SWITCH = 187  // Recent apps
        const val KEYCODE_POWER = 26
        const val KEYCODE_VOLUME_UP = 24
        const val KEYCODE_VOLUME_DOWN = 25
    }
    
    private var hasRoot: Boolean? = null
    
    /**
     * Проверка root доступа
     */
    suspend fun checkRoot(): Boolean = withContext(Dispatchers.IO) {
        if (hasRoot != null) return@withContext hasRoot!!
        
        hasRoot = try {
            val process = Runtime.getRuntime().exec("su -c id")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val result = reader.readLine()
            process.waitFor()
            result?.contains("uid=0") == true
        } catch (e: Exception) {
            Log.d(TAG, "Root not available: ${e.message}")
            false
        }
        
        hasRoot!!
    }
    
    /**
     * Tap - нажатие в точку (x, y)
     * Приоритет: Root → Accessibility → Shell
     */
    suspend fun tap(x: Int, y: Int): CommandResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "tap($x, $y) - hasRoot=${hasRoot}, accessibility=${SphereAccessibilityService.isServiceEnabled()}")
        
        // 1. Если есть root - используем su для гарантированного выполнения
        if (checkRoot()) {
            return@withContext executeRootCommand("input tap $x $y")
        }
        
        // 2. Fallback на Accessibility если включен
        if (SphereAccessibilityService.isServiceEnabled()) {
            val ok = SphereAccessibilityService.tap(x, y)
            if (ok) return@withContext CommandResult(success = true)
        }
        
        // 3. Последняя попытка - обычный shell (может не работать)
        executeInputCommand("input tap $x $y")
    }
    
    /**
     * Swipe - свайп от (x1, y1) до (x2, y2)
     * Приоритет: Root → Accessibility → Shell
     */
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Int = 300): CommandResult = 
        withContext(Dispatchers.IO) {
            Log.d(TAG, "swipe($x1,$y1 -> $x2,$y2) - hasRoot=${hasRoot}")
            
            // 1. Root priority
            if (checkRoot()) {
                return@withContext executeRootCommand("input swipe $x1 $y1 $x2 $y2 $duration")
            }
            
            // 2. Accessibility fallback
            if (SphereAccessibilityService.isServiceEnabled()) {
                val ok = SphereAccessibilityService.swipe(x1, y1, x2, y2, duration.toLong())
                if (ok) return@withContext CommandResult(success = true)
            }
            
            // 3. Shell fallback
            executeInputCommand("input swipe $x1 $y1 $x2 $y2 $duration")
        }
    
    /**
     * Long press - долгое нажатие
     * Приоритет: Root → Accessibility → Shell
     */
    suspend fun longPress(x: Int, y: Int, duration: Int = 1000): CommandResult =
        withContext(Dispatchers.IO) {
            // 1. Root priority
            if (checkRoot()) {
                return@withContext executeRootCommand("input swipe $x $y $x $y $duration")
            }
            
            // 2. Accessibility fallback
            if (SphereAccessibilityService.isServiceEnabled()) {
                val ok = SphereAccessibilityService.longPress(x, y, duration.toLong())
                if (ok) return@withContext CommandResult(success = true)
            }
            
            // 3. Shell fallback
            executeInputCommand("input swipe $x $y $x $y $duration")
        }
    
    /**
     * Key event - нажатие кнопки
     * Приоритет: Root → Accessibility → Shell
     */
    suspend fun keyEvent(keyCode: Int): CommandResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "keyEvent($keyCode) - hasRoot=${hasRoot}")
        
        // 1. Root priority для HOME/BACK/RECENT (самое надёжное)
        if (checkRoot()) {
            return@withContext executeRootCommand("input keyevent $keyCode")
        }
        
        // 2. Accessibility для системных кнопок
        if (SphereAccessibilityService.isServiceEnabled()) {
            val ok = when (keyCode) {
                KEYCODE_BACK -> SphereAccessibilityService.back()
                KEYCODE_HOME -> SphereAccessibilityService.home()
                KEYCODE_APP_SWITCH -> SphereAccessibilityService.recent()
                else -> false
            }
            if (ok) return@withContext CommandResult(success = true)
        }
        executeInputCommand("input keyevent $keyCode")
    }
    
    /**
     * Text input - ввод текста
     */
    suspend fun inputText(text: String): CommandResult = withContext(Dispatchers.IO) {
        // Экранируем специальные символы
        val escapedText = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("'", "\\'")
            .replace(" ", "%s")
            .replace("&", "\\&")
            .replace("<", "\\<")
            .replace(">", "\\>")
            .replace("|", "\\|")
            .replace(";", "\\;")
            .replace("$", "\\$")
            .replace("`", "\\`")
        
        executeInputCommand("input text \"$escapedText\"")
    }
    
    /**
     * Home - кнопка домой
     */
    suspend fun home(): CommandResult = keyEvent(KEYCODE_HOME)
    
    /**
     * Back - кнопка назад
     */
    suspend fun back(): CommandResult = keyEvent(KEYCODE_BACK)
    
    /**
     * Recent - недавние приложения
     */
    suspend fun recent(): CommandResult = keyEvent(KEYCODE_APP_SWITCH)
    
    /**
     * Menu - кнопка меню
     */
    suspend fun menu(): CommandResult = keyEvent(KEYCODE_MENU)
    
    /**
     * Power - кнопка питания (включить/выключить экран)
     */
    suspend fun power(): CommandResult = keyEvent(KEYCODE_POWER)
    
    /**
     * Volume up
     */
    suspend fun volumeUp(): CommandResult = keyEvent(KEYCODE_VOLUME_UP)
    
    /**
     * Volume down
     */
    suspend fun volumeDown(): CommandResult = keyEvent(KEYCODE_VOLUME_DOWN)
    
    /**
     * Shell - выполнение произвольной shell команды
     */
    suspend fun shell(command: String): CommandResult = withContext(Dispatchers.IO) {
        executeShellCommand(command)
    }
    
    /**
     * Скриншот - получение скриншота
     */
    suspend fun screenshot(path: String = "/sdcard/screenshot.png"): CommandResult = 
        withContext(Dispatchers.IO) {
            executeShellCommand("screencap -p $path")
        }
    
    /**
     * Получение информации об устройстве
     */
    suspend fun getDeviceInfo(): CommandResult = withContext(Dispatchers.IO) {
        val info = buildString {
            appendLine("Model: ${android.os.Build.MODEL}")
            appendLine("Manufacturer: ${android.os.Build.MANUFACTURER}")
            appendLine("Android: ${android.os.Build.VERSION.RELEASE}")
            appendLine("SDK: ${android.os.Build.VERSION.SDK_INT}")
            appendLine("Brand: ${android.os.Build.BRAND}")
            appendLine("Device: ${android.os.Build.DEVICE}")
            appendLine("Product: ${android.os.Build.PRODUCT}")
        }
        CommandResult(success = true, data = info)
    }
    
    /**
     * Получение списка пакетов
     */
    suspend fun listPackages(): CommandResult = withContext(Dispatchers.IO) {
        executeShellCommand("pm list packages")
    }
    
    /**
     * Запуск приложения по имени пакета
     */
    suspend fun launchApp(packageName: String): CommandResult = withContext(Dispatchers.IO) {
        executeShellCommand("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
    }
    
    /**
     * Завершение приложения
     */
    suspend fun forceStopApp(packageName: String): CommandResult = withContext(Dispatchers.IO) {
        executeShellCommand("am force-stop $packageName")
    }
    
    /**
     * Выполнение input команды
     */
    private fun executeInputCommand(command: String): CommandResult {
        return try {
            val process = if (hasRoot == true) {
                Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            } else {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            }
            
            val exitCode = process.waitFor()
            
            if (exitCode == 0) {
                CommandResult(success = true)
            } else {
                val error = BufferedReader(InputStreamReader(process.errorStream)).readText()
                CommandResult(success = false, error = "Exit code: $exitCode, Error: $error")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Command failed: $command", e)
            CommandResult(success = false, error = e.message)
        }
    }
    
    /**
     * Выполнение команды с root правами через su shell
     * Используется для надёжного выполнения input команд на эмуляторах
     */
    private fun executeRootCommand(command: String): CommandResult {
        return try {
            Log.d(TAG, "ROOT command: $command")
            
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            
            os.writeBytes("$command\n")
            os.writeBytes("exit\n")
            os.flush()
            os.close()
            
            val exitCode = process.waitFor()
            
            if (exitCode == 0) {
                Log.d(TAG, "ROOT command SUCCESS")
                CommandResult(success = true)
            } else {
                val error = BufferedReader(InputStreamReader(process.errorStream)).readText()
                Log.e(TAG, "ROOT failed: exit=$exitCode, error=$error")
                CommandResult(success = false, error = "Root: $exitCode - $error")
            }
        } catch (e: Exception) {
            Log.e(TAG, "ROOT exception: $command", e)
            CommandResult(success = false, error = "Root error: ${e.message}")
        }
    }
    
    /**
     * Выполнение shell команды с получением вывода
     */
    private fun executeShellCommand(command: String): CommandResult {
        return try {
            val process = if (hasRoot == true) {
                Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            } else {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            }
            
            val output = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val error = BufferedReader(InputStreamReader(process.errorStream)).readText()
            val exitCode = process.waitFor()
            
            if (exitCode == 0) {
                CommandResult(success = true, data = output.ifEmpty { null })
            } else {
                CommandResult(
                    success = false, 
                    data = output.ifEmpty { null },
                    error = "Exit code: $exitCode" + if (error.isNotEmpty()) ", Error: $error" else ""
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Shell command failed: $command", e)
            CommandResult(success = false, error = e.message)
        }
    }
    
    /**
     * Выполнение команды с root правами
     */
    suspend fun executeAsRoot(command: String): CommandResult = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec("su")
            val outputStream = DataOutputStream(process.outputStream)
            
            outputStream.writeBytes("$command\n")
            outputStream.writeBytes("exit\n")
            outputStream.flush()
            
            val output = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val error = BufferedReader(InputStreamReader(process.errorStream)).readText()
            val exitCode = process.waitFor()
            
            if (exitCode == 0) {
                CommandResult(success = true, data = output.ifEmpty { null })
            } else {
                CommandResult(
                    success = false,
                    data = output.ifEmpty { null },
                    error = error.ifEmpty { "Exit code: $exitCode" }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Root command failed", e)
            CommandResult(success = false, error = e.message)
        }
    }
}
