package com.sphere.agent.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * CommandExecutor v2.1.0 - Enterprise Input & Control System
 * 
 * НОВЫЕ ФУНКЦИИ v2.1.0:
 * - Clipboard sync (get/set буфер обмена)
 * - Key combo (комбинации клавиш)
 * - Pinch/Zoom/Rotate multi-touch жесты
 * - File operations (list, read, delete)
 * - Logcat management
 * - UI Hierarchy dump
 * - Screenshot to Base64
 * - Battery & Network info
 * 
 * КРИТИЧЕСКИ ВАЖНО: ROOT ВСЕГДА ДОЛЖЕН ОПРЕДЕЛЯТЬСЯ!
 * 
 * Механизмы:
 * - Множественные методы проверки su (5+ способов)
 * - Бесконечные retry пока ROOT не определён
 * - Фоновый checker каждые 5 секунд
 * - Перепроверка при каждой неудачной команде
 * - Никогда не кэшируем false навсегда
 */
class CommandExecutor(private val context: Context) {
    
    companion object {
        private const val TAG = "CommandExecutor"
        
        // v3.5.4 OPTIMIZATION: Уменьшена агрессивность ROOT checker
        private const val ROOT_CHECK_INTERVAL = 30_000L
        private const val ROOT_COMMAND_TIMEOUT = 5_000L
        // v3.6.1: Лимит вывода команд — защита от OOM
        private const val MAX_OUTPUT_SIZE = 256 * 1024 // 256KB
        
        // Key codes
        const val KEYCODE_HOME = 3
        const val KEYCODE_BACK = 4
        const val KEYCODE_MENU = 82
        const val KEYCODE_APP_SWITCH = 187
        const val KEYCODE_POWER = 26
        const val KEYCODE_VOLUME_UP = 24
        const val KEYCODE_VOLUME_DOWN = 25
    }
    
    // ROOT статус
    @Volatile private var hasRoot: Boolean = false
    @Volatile private var rootConfirmed: Boolean = false // ROOT точно есть
    @Volatile private var rootCheckInProgress: Boolean = false
    private var rootCheckAttempts = 0
    
    // Фоновый checker
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var rootCheckerJob: Job? = null
    
    // Callback для уведомления о ROOT статусе
    var onRootStatusChanged: ((Boolean) -> Unit)? = null
    
    init {
        // v3.5.4: Ленивая инициализация - НЕ запускаем checker сразу
        // ROOT будет проверен при первой команде или по запросу
        // Это убирает нагрузку когда ROOT не нужен
        Log.i(TAG, "CommandExecutor initialized - lazy ROOT check")
    }
    
    /**
     * v3.5.4 OPTIMIZED: Ленивый ROOT checker
     * Запускается только при первой команде, проверяет каждые 30 сек
     * Останавливается после 5 неудачных попыток (2.5 минуты)
     */
    private fun startLazyRootChecker() {
        // Если уже подтверждён или checker уже работает - выходим
        if (rootConfirmed || rootCheckerJob?.isActive == true) {
            return
        }
        
        rootCheckerJob?.cancel()
        rootCheckerJob = scope.launch {
            Log.i(TAG, "Lazy ROOT checker started")
            
            // Максимум 5 попыток (2.5 минуты), потом сдаёмся
            val maxAttempts = 5
            
            while (isActive && !rootConfirmed && rootCheckAttempts < maxAttempts) {
                try {
                    val result = performFullRootCheck()
                    
                    if (result) {
                        Log.i(TAG, "✓ ROOT confirmed after ${rootCheckAttempts + 1} attempts")
                        rootConfirmed = true
                        hasRoot = true
                        onRootStatusChanged?.invoke(true)
                        break
                    } else {
                        rootCheckAttempts++
                        if (rootCheckAttempts < maxAttempts) {
                            Log.d(TAG, "ROOT attempt $rootCheckAttempts/$maxAttempts, next in ${ROOT_CHECK_INTERVAL/1000}s")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "ROOT checker error: ${e.message}")
                }
                
                delay(ROOT_CHECK_INTERVAL)
            }
            
            if (!rootConfirmed) {
                Log.w(TAG, "ROOT not available after $maxAttempts attempts, stopping checker")
            }
        }
    }
    
    /**
     * v3.5.4 OPTIMIZED: Проверка ROOT через 2 метода вместо 5
     * Убраны избыточные методы которые создавали лишнюю нагрузку
     * Возвращает true если ЛЮБОЙ метод успешен
     */
    private suspend fun performFullRootCheck(): Boolean = withContext(Dispatchers.IO) {
        if (rootCheckInProgress) {
            Log.d(TAG, "ROOT check already in progress, skipping")
            return@withContext hasRoot
        }
        
        rootCheckInProgress = true
        
        try {
            // v3.5.4: Только 2 самых надёжных метода вместо 5
            // Убраны: checkRootMethod3 (whoami), checkRootMethod4 (9 путей!), checkRootMethod5 (su 0)
            
            // Метод 1: su -c id (стандартный, работает везде)
            if (checkRootMethod1()) {
                Log.i(TAG, "✓ ROOT confirmed via su -c id")
                return@withContext true
            }
            
            // Метод 2: интерактивный su shell (для LDPlayer/Bluestacks)
            if (checkRootMethod2()) {
                Log.i(TAG, "✓ ROOT confirmed via interactive su")
                return@withContext true
            }
            
            // Не логируем WARNING каждые 30 сек - это спам
            Log.d(TAG, "ROOT not available via standard methods")
            return@withContext false
            
        } finally {
            rootCheckInProgress = false
        }
    }
    
    /**
     * Метод 1: su -c id (стандартный)
     * v3.6.1: Закрываем ВСЕ стримы process через use{} + destroyForcibly
     */
    private fun checkRootMethod1(): Boolean {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val result = process.inputStream.bufferedReader().use { it.readLine() ?: "" }
            process.errorStream.bufferedReader().use { it.readText() } // drain
            val finished = waitForProcess(process, ROOT_COMMAND_TIMEOUT)
            if (!finished) {
                Log.w(TAG, "Method1 timeout")
                return false
            }
            val exitCode = process.exitValue()
            Log.d(TAG, "Method1 result: $result, exit=$exitCode")
            result.contains("uid=0")
        } catch (e: Exception) {
            Log.w(TAG, "Method1 failed: ${e.message}")
            false
        } finally {
            process?.destroyForcibly()
        }
    }
    
    /**
     * Метод 2: Интерактивный su shell (для LDPlayer/Bluestacks)
     * v3.6.1: Закрываем ВСЕ стримы + destroyForcibly
     */
    private fun checkRootMethod2(): Boolean {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            
            os.writeBytes("id\n")
            os.writeBytes("exit\n")
            os.flush()
            os.close()
            
            val result = process.inputStream.bufferedReader().use { it.readText().take(MAX_OUTPUT_SIZE) }
            process.errorStream.bufferedReader().use { it.readText() } // drain
            val finished = waitForProcess(process, ROOT_COMMAND_TIMEOUT)
            if (!finished) {
                Log.w(TAG, "Method2 timeout")
                return false
            }
            val exitCode = process.exitValue()
            
            Log.d(TAG, "Method2 result: ${result.take(100)}, exit=$exitCode")
            result.contains("uid=0")
        } catch (e: Exception) {
            Log.w(TAG, "Method2 failed: ${e.message}")
            false
        } finally {
            process?.destroyForcibly()
        }
    }
    
    /**
     * Метод 3: su -c whoami
     */
    private fun checkRootMethod3(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "whoami"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val result = reader.readLine() ?: ""
            val finished = waitForProcess(process, ROOT_COMMAND_TIMEOUT)
            if (!finished) {
                Log.w(TAG, "Method3 timeout")
                process.destroy()
                process.destroyForcibly()
                return false
            }
            val exitCode = process.exitValue()
            
            Log.d(TAG, "Method3 result: $result, exit=$exitCode")
            result.trim() == "root"
        } catch (e: Exception) {
            Log.w(TAG, "Method3 failed: ${e.message}")
            false
        }
    }
    
    /**
     * Метод 4: Проверка наличия su binary + тестовая команда
     */
    private fun checkRootMethod4(): Boolean {
        val suPaths = listOf(
            "/system/bin/su",
            "/system/xbin/su", 
            "/sbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/data/local/su",
            "/su/bin/su",
            "/magisk/.core/bin/su"
        )
        
        for (path in suPaths) {
            if (File(path).exists()) {
                Log.d(TAG, "Found su binary at: $path")
                // Проверяем что su реально работает
                try {
                    val process = Runtime.getRuntime().exec(arrayOf(path, "-c", "echo root"))
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    val result = reader.readLine() ?: ""
                    val finished = waitForProcess(process, ROOT_COMMAND_TIMEOUT)
                    if (!finished) {
                        Log.w(TAG, "Method4 timeout for path: $path")
                        process.destroy()
                        process.destroyForcibly()
                        continue
                    }
                    val exitCode = process.exitValue()
                    
                    if (result.contains("root") && exitCode == 0) {
                        Log.d(TAG, "Method4 success via $path")
                        return true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Method4 path $path failed: ${e.message}")
                }
            }
        }
        return false
    }
    
    /**
     * Метод 5: su 0 id (альтернативный синтаксис)
     */
    private fun checkRootMethod5(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "0", "id"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val result = reader.readLine() ?: ""
            val finished = waitForProcess(process, ROOT_COMMAND_TIMEOUT)
            if (!finished) {
                Log.w(TAG, "Method5 timeout")
                process.destroy()
                process.destroyForcibly()
                return false
            }
            val exitCode = process.exitValue()
            
            Log.d(TAG, "Method5 result: $result, exit=$exitCode")
            result.contains("uid=0")
        } catch (e: Exception) {
            Log.w(TAG, "Method5 failed: ${e.message}")
            false
        }
    }
    
    /**
     * Проверка root доступа - ПУБЛИЧНЫЙ МЕТОД
     * НИКОГДА не возвращает кэшированный false надолго!
     */
    suspend fun checkRoot(): Boolean = withContext(Dispatchers.IO) {
        // Если ROOT уже подтверждён - возвращаем сразу
        if (rootConfirmed) {
            return@withContext true
        }
        
        // Делаем полную проверку
        val result = performFullRootCheck()
        
        if (result) {
            hasRoot = true
            rootConfirmed = true
            onRootStatusChanged?.invoke(true)
        } else {
            hasRoot = false
            // НЕ устанавливаем rootConfirmed = true при неудаче!
            // Фоновый checker продолжит проверять
        }
        
        return@withContext result
    }
    
    /**
     * Принудительная перепроверка ROOT (сброс кэша)
     * v3.5.4: Перезапускает ленивый checker (не агрессивный)
     */
    fun resetRootCache() {
        Log.i(TAG, "ROOT cache reset")
        hasRoot = false
        rootConfirmed = false
        rootCheckAttempts = 0
        
        // Перезапускаем ленивый checker
        startLazyRootChecker()
    }
    
    /**
     * Текущий статус ROOT (для быстрого доступа)
     */
    fun hasRootAccess(): Boolean = hasRoot || rootConfirmed
    
    /**
     * Tap - нажатие в точку (x, y)
     * v3.5.4: Запускает ленивый ROOT checker при первом вызове
     */
    suspend fun tap(x: Int, y: Int): CommandResult = withContext(Dispatchers.IO) {
        // v3.5.4: Ленивая инициализация ROOT checker
        if (!rootConfirmed && rootCheckerJob?.isActive != true) {
            startLazyRootChecker()
        }
        
        // 1. Всегда пробуем ROOT первым
        val rootResult = executeRootCommand("input tap $x $y")
        if (rootResult.success) {
            // ROOT работает! Подтверждаем
            if (!rootConfirmed) {
                Log.i(TAG, "ROOT confirmed via successful tap command!")
                rootConfirmed = true
                hasRoot = true
                onRootStatusChanged?.invoke(true)
            }
            return@withContext rootResult
        }
        
        // 2. ROOT не сработал - перепроверяем
        if (!rootConfirmed) {
            Log.w(TAG, "ROOT command failed, rechecking ROOT status...")
            checkRoot()
        }
        
        // 3. Fallback на Accessibility
        if (SphereAccessibilityService.isServiceEnabled()) {
            val ok = SphereAccessibilityService.tap(x, y)
            if (ok) return@withContext CommandResult(success = true)
        }
        
        // 4. Последняя попытка - обычный shell
        return@withContext executeInputCommand("input tap $x $y")
    }
    
    /**
     * Swipe - свайп
     */
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Int = 300): CommandResult = 
        withContext(Dispatchers.IO) {
            Log.d(TAG, "swipe($x1,$y1 -> $x2,$y2) - hasRoot=$hasRoot")
            
            // 1. ROOT первым
            val rootResult = executeRootCommand("input swipe $x1 $y1 $x2 $y2 $duration")
            if (rootResult.success) {
                if (!rootConfirmed) {
                    Log.i(TAG, "ROOT confirmed via successful swipe command!")
                    rootConfirmed = true
                    hasRoot = true
                    onRootStatusChanged?.invoke(true)
                }
                return@withContext rootResult
            }
            
            // 2. Перепроверка ROOT
            if (!rootConfirmed) {
                checkRoot()
            }
            
            // 3. Accessibility fallback
            if (SphereAccessibilityService.isServiceEnabled()) {
                val ok = SphereAccessibilityService.swipe(x1, y1, x2, y2, duration.toLong())
                if (ok) return@withContext CommandResult(success = true)
            }
            
            executeInputCommand("input swipe $x1 $y1 $x2 $y2 $duration")
        }
    
    /**
     * Long press
     */
    suspend fun longPress(x: Int, y: Int, duration: Int = 1000): CommandResult =
        withContext(Dispatchers.IO) {
            val rootResult = executeRootCommand("input swipe $x $y $x $y $duration")
            if (rootResult.success) {
                if (!rootConfirmed) {
                    rootConfirmed = true
                    hasRoot = true
                    onRootStatusChanged?.invoke(true)
                }
                return@withContext rootResult
            }
            
            if (SphereAccessibilityService.isServiceEnabled()) {
                val ok = SphereAccessibilityService.longPress(x, y, duration.toLong())
                if (ok) return@withContext CommandResult(success = true)
            }
            
            executeInputCommand("input swipe $x $y $x $y $duration")
        }
    
    /**
     * Key event
     */
    suspend fun keyEvent(keyCode: Int): CommandResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "keyEvent($keyCode) - hasRoot=$hasRoot")
        
        val rootResult = executeRootCommand("input keyevent $keyCode")
        if (rootResult.success) {
            if (!rootConfirmed) {
                rootConfirmed = true
                hasRoot = true
                onRootStatusChanged?.invoke(true)
            }
            return@withContext rootResult
        }
        
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
     * Text input
     */
    suspend fun inputText(text: String): CommandResult = withContext(Dispatchers.IO) {
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
        
        executeRootCommand("input text \"$escapedText\"")
    }
    
    // Кнопки
    suspend fun home(): CommandResult = keyEvent(KEYCODE_HOME)
    suspend fun back(): CommandResult = keyEvent(KEYCODE_BACK)
    suspend fun recent(): CommandResult = keyEvent(KEYCODE_APP_SWITCH)
    suspend fun menu(): CommandResult = keyEvent(KEYCODE_MENU)
    suspend fun power(): CommandResult = keyEvent(KEYCODE_POWER)
    suspend fun volumeUp(): CommandResult = keyEvent(KEYCODE_VOLUME_UP)
    suspend fun volumeDown(): CommandResult = keyEvent(KEYCODE_VOLUME_DOWN)
    
    /**
     * Shell команда
     */
    suspend fun shell(command: String): CommandResult = withContext(Dispatchers.IO) {
        executeShellCommand(command)
    }
    
    /**
     * Скриншот
     */
    suspend fun screenshot(path: String = "/sdcard/screenshot.png"): CommandResult = 
        withContext(Dispatchers.IO) {
            executeShellCommand("screencap -p $path")
        }
    
    /**
     * Device info
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
            appendLine("ROOT: $hasRoot (confirmed: $rootConfirmed)")
        }
        CommandResult(success = true, data = info)
    }
    
    suspend fun listPackages(): CommandResult = withContext(Dispatchers.IO) {
        executeShellCommand("pm list packages")
    }
    
    suspend fun launchApp(packageName: String): CommandResult = withContext(Dispatchers.IO) {
        executeShellCommand("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
    }
    
    suspend fun forceStopApp(packageName: String): CommandResult = withContext(Dispatchers.IO) {
        executeShellCommand("am force-stop $packageName")
    }
    
    /**
     * Очистка данных приложения
     */
    suspend fun clearAppData(packageName: String): CommandResult = withContext(Dispatchers.IO) {
        executeShellCommand("pm clear $packageName")
    }
    
    // ===== CLIPBOARD COMMANDS =====
    
    /**
     * Установка текста в буфер обмена устройства
     * Использует ClipboardManager через Main handler
     */
    suspend fun setClipboard(text: String): CommandResult = suspendCoroutine { cont ->
        try {
            Handler(Looper.getMainLooper()).post {
                try {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("SphereAgent", text)
                    clipboard.setPrimaryClip(clip)
                    Log.d(TAG, "Clipboard set: ${text.take(50)}...")
                    cont.resume(CommandResult(success = true, data = "Clipboard set"))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set clipboard", e)
                    cont.resume(CommandResult(success = false, error = e.message))
                }
            }
        } catch (e: Exception) {
            cont.resume(CommandResult(success = false, error = e.message))
        }
    }
    
    /**
     * Получение текста из буфера обмена устройства
     */
    suspend fun getClipboard(): CommandResult = suspendCoroutine { cont ->
        try {
            Handler(Looper.getMainLooper()).post {
                try {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clipData = clipboard.primaryClip
                    
                    val text = if (clipData != null && clipData.itemCount > 0) {
                        clipData.getItemAt(0).text?.toString() ?: ""
                    } else {
                        ""
                    }
                    
                    Log.d(TAG, "Clipboard get: ${text.take(50)}...")
                    cont.resume(CommandResult(success = true, data = text))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get clipboard", e)
                    cont.resume(CommandResult(success = false, error = e.message))
                }
            }
        } catch (e: Exception) {
            cont.resume(CommandResult(success = false, error = e.message))
        }
    }
    
    // ===== MULTI-TOUCH / PINCH / ZOOM =====
    
    /**
     * Pinch/Zoom жест (масштабирование)
     * @param cx центр X
     * @param cy центр Y  
     * @param startDistance начальное расстояние между пальцами
     * @param endDistance конечное расстояние между пальцами
     * @param duration длительность в мс
     */
    suspend fun pinch(
        cx: Int, cy: Int, 
        startDistance: Int, endDistance: Int, 
        duration: Int = 500
    ): CommandResult = withContext(Dispatchers.IO) {
        // sendevent подход для multi-touch
        // Для простоты используем последовательные свайпы, имитирующие pinch
        
        val halfStart = startDistance / 2
        val halfEnd = endDistance / 2
        
        // Точка 1: от (cx - halfStart, cy) до (cx - halfEnd, cy)  
        // Точка 2: от (cx + halfStart, cy) до (cx + halfEnd, cy)
        
        // Используем adb shell sendevent для настоящего multitouch
        // Но это сложно и device-specific. Используем workaround с 2 последовательными swipe
        
        val cmd = buildString {
            // Finger 1 
            append("input swipe ${cx - halfStart} $cy ${cx - halfEnd} $cy $duration & ")
            // Finger 2 (параллельно)
            append("input swipe ${cx + halfStart} $cy ${cx + halfEnd} $cy $duration")
        }
        
        executeRootCommand(cmd)
    }
    
    /**
     * Rotate gesture (вращение двумя пальцами)
     */
    suspend fun rotate(
        cx: Int, cy: Int,
        radius: Int,
        startAngle: Float, endAngle: Float,
        duration: Int = 500
    ): CommandResult = withContext(Dispatchers.IO) {
        // Вращение - сложный жест. Упрощённая реализация через дугообразные свайпы
        val startRad1 = Math.toRadians(startAngle.toDouble())
        val endRad1 = Math.toRadians(endAngle.toDouble())
        val startRad2 = Math.toRadians((startAngle + 180).toDouble())
        val endRad2 = Math.toRadians((endAngle + 180).toDouble())
        
        val x1s = (cx + radius * Math.cos(startRad1)).toInt()
        val y1s = (cy + radius * Math.sin(startRad1)).toInt()
        val x1e = (cx + radius * Math.cos(endRad1)).toInt()
        val y1e = (cy + radius * Math.sin(endRad1)).toInt()
        
        val x2s = (cx + radius * Math.cos(startRad2)).toInt()
        val y2s = (cy + radius * Math.sin(startRad2)).toInt()
        val x2e = (cx + radius * Math.cos(endRad2)).toInt()
        val y2e = (cy + radius * Math.sin(endRad2)).toInt()
        
        val cmd = "input swipe $x1s $y1s $x1e $y1e $duration & input swipe $x2s $y2s $x2e $y2e $duration"
        executeRootCommand(cmd)
    }
    
    /**
     * Key combo (комбинация клавиш с модификаторами)
     * @param keys список keycode для одновременного нажатия
     */
    suspend fun keyCombo(keys: List<Int>): CommandResult = withContext(Dispatchers.IO) {
        // Для комбинаций используем последовательность input keyevent
        val commands = keys.joinToString(" && ") { "input keyevent $it" }
        executeRootCommand(commands)
    }
    
    // ===== FILE OPERATIONS =====
    
    /**
     * Список файлов в директории
     */
    suspend fun listFiles(path: String): CommandResult = withContext(Dispatchers.IO) {
        executeShellCommand("ls -la \"$path\"")
    }
    
    /**
     * Чтение файла (возвращает base64 для бинарных)
     */
    suspend fun readFile(path: String, base64Encode: Boolean = false): CommandResult = withContext(Dispatchers.IO) {
        if (base64Encode) {
            try {
                val file = File(path)
                if (!file.exists()) {
                    return@withContext CommandResult(success = false, error = "File not found: $path")
                }
                val bytes = FileInputStream(file).use { it.readBytes() }
                val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                CommandResult(success = true, data = encoded)
            } catch (e: Exception) {
                CommandResult(success = false, error = e.message)
            }
        } else {
            executeShellCommand("cat \"$path\"")
        }
    }
    
    /**
     * Удаление файла
     */
    suspend fun deleteFile(path: String): CommandResult = withContext(Dispatchers.IO) {
        executeShellCommand("rm -f \"$path\"")
    }
    
    /**
     * Создание директории
     */
    suspend fun createDirectory(path: String): CommandResult = withContext(Dispatchers.IO) {
        executeShellCommand("mkdir -p \"$path\"")
    }
    
    // ===== LOGCAT =====
    
    /**
     * Получение logcat (последние N строк)
     */
    suspend fun getLogcat(lines: Int = 100, filter: String? = null): CommandResult = withContext(Dispatchers.IO) {
        val cmd = buildString {
            append("logcat -d -t $lines")
            if (!filter.isNullOrBlank()) {
                append(" -s $filter")
            }
        }
        executeShellCommand(cmd)
    }
    
    /**
     * Очистка logcat
     */
    suspend fun clearLogcat(): CommandResult = withContext(Dispatchers.IO) {
        executeShellCommand("logcat -c")
    }
    
    // ===== UI HIERARCHY (для автоматизации) =====
    
    /**
     * Получение UI hierarchy (XML дамп текущего экрана)
     */
    suspend fun getUiHierarchy(): CommandResult = withContext(Dispatchers.IO) {
        val dumpPath = "/sdcard/window_dump.xml"
        
        // Сначала делаем дамп
        val dumpResult = executeShellCommand("uiautomator dump $dumpPath")
        if (!dumpResult.success) {
            return@withContext dumpResult
        }
        
        // Читаем файл
        val catResult = executeShellCommand("cat $dumpPath")
        
        // Удаляем временный файл
        executeShellCommand("rm $dumpPath")
        
        catResult
    }
    
    // ===== XPATH POOL (v2.6.0) - Проверка пула XPath элементов =====
    
    /**
     * XPath Pool - Проверяет массив XPath элементов, кликает первый найденный
     * 
     * Логика:
     * 1. Получает UI hierarchy
     * 2. Проверяет каждый xpath из пула
     * 3. Первый найденный элемент → tap по центру
     * 4. Возвращает результат: какой элемент найден и кликнут
     * 
     * @param xpathList Массив XPath селекторов для проверки
     * @param timeout Таймаут проверки пула (мс)
     * @param retryCount Количество повторных проверок
     * @param retryInterval Интервал между проверками (мс)
     * @return CommandResult с данными о найденном элементе
     */
    suspend fun xpathPool(
        xpathList: List<String>,
        timeout: Int = 5000,
        retryCount: Int = 3,
        retryInterval: Int = 1000
    ): CommandResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "xpathPool: checking ${xpathList.size} elements, timeout=$timeout, retries=$retryCount")
        
        if (xpathList.isEmpty()) {
            return@withContext CommandResult(
                success = false,
                error = "XPath pool is empty"
            )
        }
        
        val startTime = System.currentTimeMillis()
        var attempt = 0
        
        while (attempt < retryCount) {
            // Проверяем таймаут
            if (System.currentTimeMillis() - startTime > timeout) {
                Log.w(TAG, "xpathPool: timeout after ${System.currentTimeMillis() - startTime}ms")
                break
            }
            
            attempt++
            Log.d(TAG, "xpathPool: attempt $attempt/$retryCount")
            
            // Получаем UI hierarchy
            val hierarchyResult = getUiHierarchy()
            if (!hierarchyResult.success || hierarchyResult.data == null) {
                Log.w(TAG, "xpathPool: failed to get hierarchy: ${hierarchyResult.error}")
                delay(retryInterval.toLong())
                continue
            }
            
            val hierarchyXml = hierarchyResult.data as String
            
            // Проверяем каждый xpath из пула
            for ((index, xpath) in xpathList.withIndex()) {
                try {
                    val elementResult = findElementByXPath(hierarchyXml, xpath)
                    
                    if (elementResult != null) {
                        val (centerX, centerY, elementInfo) = elementResult
                        Log.i(TAG, "xpathPool: FOUND element #$index at ($centerX, $centerY) - xpath: ${xpath.take(50)}")
                        
                        // Выполняем тап
                        val tapResult = tap(centerX, centerY)
                        
                        val resultData = buildString {
                            append("{")
                            append("\"found\": true,")
                            append("\"index\": $index,")
                            append("\"xpath\": \"${xpath.replace("\"", "\\\"")}\",")
                            append("\"x\": $centerX,")
                            append("\"y\": $centerY,")
                            append("\"element\": \"${elementInfo.replace("\"", "\\\"")}\",")
                            append("\"tap_success\": ${tapResult.success},")
                            append("\"attempts\": $attempt,")
                            append("\"duration_ms\": ${System.currentTimeMillis() - startTime}")
                            append("}")
                        }
                        
                        return@withContext CommandResult(
                            success = true,
                            data = resultData
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "xpathPool: error checking xpath[$index]: ${e.message}")
                }
            }
            
            // Ни один элемент не найден в этой итерации
            Log.d(TAG, "xpathPool: no elements found in attempt $attempt, waiting ${retryInterval}ms...")
            delay(retryInterval.toLong())
        }
        
        // Ни один элемент не найден после всех попыток
        val resultData = buildString {
            append("{")
            append("\"found\": false,")
            append("\"index\": -1,")
            append("\"attempts\": $attempt,")
            append("\"duration_ms\": ${System.currentTimeMillis() - startTime},")
            append("\"pool_size\": ${xpathList.size}")
            append("}")
        }
        
        Log.w(TAG, "xpathPool: NO elements found after $attempt attempts")
        CommandResult(
            success = true,  // Команда выполнена успешно, просто ничего не найдено
            data = resultData
        )
    }
    
    /**
     * Поиск элемента по XPath в XML hierarchy
     * 
     * @param xml UI hierarchy XML
     * @param xpath XPath селектор
     * @return Triple(centerX, centerY, elementInfo) или null если не найден
     */
    private fun findElementByXPath(xml: String, xpath: String): Triple<Int, Int, String>? {
        try {
            // Парсим XPath селектор
            // Поддерживаемые форматы:
            // //*[@resource-id='com.app:id/button']
            // //*[@text='OK']
            // //*[@content-desc='Settings']
            // //android.widget.Button
            // //android.widget.Button[@text='OK']
            
            val attrPattern = """@(\w+[-\w]*)\s*=\s*['"]([^'"]+)['"]""".toRegex()
            val classPattern = """//([a-zA-Z0-9_.]+)(?:\[@|$|\[)""".toRegex()
            
            val attributes = mutableMapOf<String, String>()
            var targetClass: String? = null
            
            // Извлекаем атрибуты
            attrPattern.findAll(xpath).forEach { match ->
                val attrName = match.groupValues[1]
                val attrValue = match.groupValues[2]
                attributes[attrName] = attrValue
            }
            
            // Извлекаем класс
            classPattern.find(xpath)?.let {
                targetClass = it.groupValues[1]
            }
            
            if (attributes.isEmpty() && targetClass == null) {
                Log.w(TAG, "findElementByXPath: couldn't parse xpath: $xpath")
                return null
            }
            
            // Ищем элемент в XML
            // Регулярка для node элементов
            val nodePattern = """<node\s+([^>]+)/>""".toRegex()
            
            for (match in nodePattern.findAll(xml)) {
                val nodeAttrs = match.groupValues[1]
                
                // Проверяем класс если указан
                if (targetClass != null) {
                    val classMatch = """class="([^"]+)"""".toRegex().find(nodeAttrs)
                    if (classMatch == null || !classMatch.groupValues[1].contains(targetClass!!)) {
                        continue
                    }
                }
                
                // Проверяем все атрибуты
                var allMatch = true
                for ((attrName, attrValue) in attributes) {
                    val attrPattern = when (attrName) {
                        "resource-id" -> """resource-id="([^"]*)""""
                        "text" -> """text="([^"]*)""""
                        "content-desc" -> """content-desc="([^"]*)""""
                        else -> """$attrName="([^"]*)""""
                    }.toRegex()
                    
                    val attrMatch = attrPattern.find(nodeAttrs)
                    if (attrMatch == null || attrMatch.groupValues[1] != attrValue) {
                        allMatch = false
                        break
                    }
                }
                
                if (!allMatch) continue
                
                // Элемент найден! Извлекаем bounds
                val boundsPattern = """bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"""".toRegex()
                val boundsMatch = boundsPattern.find(nodeAttrs)
                
                if (boundsMatch != null) {
                    val left = boundsMatch.groupValues[1].toInt()
                    val top = boundsMatch.groupValues[2].toInt()
                    val right = boundsMatch.groupValues[3].toInt()
                    val bottom = boundsMatch.groupValues[4].toInt()
                    
                    val centerX = (left + right) / 2
                    val centerY = (top + bottom) / 2
                    
                    // Краткая информация об элементе
                    val textMatch = """text="([^"]*)"""".toRegex().find(nodeAttrs)
                    val classMatch = """class="([^"]*)"""".toRegex().find(nodeAttrs)
                    val elementInfo = "${classMatch?.groupValues?.get(1)?.split('.')?.lastOrNull() ?: "node"}: ${textMatch?.groupValues?.get(1) ?: ""}"
                    
                    return Triple(centerX, centerY, elementInfo.take(50))
                }
            }
            
            return null
        } catch (e: Exception) {
            Log.e(TAG, "findElementByXPath: error parsing xpath/xml: ${e.message}")
            return null
        }
    }
    
    /**
     * Скриншот в base64 (для быстрой передачи)
     */
    suspend fun screenshotBase64(): CommandResult = withContext(Dispatchers.IO) {
        val path = "/sdcard/sphere_screenshot_${System.currentTimeMillis()}.png"
        
        // Делаем скриншот
        val screenshotResult = executeShellCommand("screencap -p $path")
        if (!screenshotResult.success) {
            return@withContext screenshotResult
        }
        
        // Читаем файл как base64
        try {
            val file = File(path)
            if (!file.exists()) {
                return@withContext CommandResult(success = false, error = "Screenshot file not created")
            }
            
            val bytes = FileInputStream(file).use { it.readBytes() }
            val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
            
            // Удаляем временный файл
            file.delete()
            
            CommandResult(success = true, data = encoded)
        } catch (e: Exception) {
            CommandResult(success = false, error = e.message)
        }
    }
    
    // ===== DEVICE STATE =====
    
    /**
     * Получение батареи
     */
    suspend fun getBatteryLevel(): CommandResult = withContext(Dispatchers.IO) {
        executeShellCommand("cat /sys/class/power_supply/battery/capacity")
    }
    
    /**
     * Получение сетевой информации
     */
    suspend fun getNetworkInfo(): CommandResult = withContext(Dispatchers.IO) {
        val info = buildString {
            append("=== IP Addresses ===\n")
            append(executeShellCommand("ip addr show").data ?: "")
            append("\n=== WiFi ===\n")
            append(executeShellCommand("dumpsys wifi | grep 'mWifiInfo'").data ?: "")
        }
        CommandResult(success = true, data = info)
    }
    
    /**
     * Выполнение input команды
     * v3.6.1: use{} для всех стримов + destroyForcibly
     */
    private fun executeInputCommand(command: String): CommandResult {
        var process: Process? = null
        return try {
            process = if (hasRoot) {
                Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            } else {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            }
            
            val finished = waitForProcess(process, ROOT_COMMAND_TIMEOUT)
            if (!finished) {
                return CommandResult(success = false, error = "Timeout: ${ROOT_COMMAND_TIMEOUT}ms")
            }
            val exitCode = process.exitValue()
            
            if (exitCode == 0) {
                CommandResult(success = true)
            } else {
                val error = process.errorStream.bufferedReader().use { it.readText().take(MAX_OUTPUT_SIZE) }
                CommandResult(success = false, error = "Exit code: $exitCode, Error: $error")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Command failed: $command", e)
            CommandResult(success = false, error = e.message)
        } finally {
            process?.destroyForcibly()
        }
    }
    
    /**
     * Выполнение команды с root правами через su shell
     * v3.6.1: use{} + destroyForcibly + output limit
     */
    private fun executeRootCommand(command: String): CommandResult {
        var process: Process? = null
        return try {
            Log.d(TAG, "ROOT command: $command")
            
            process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            
            os.writeBytes("$command\n")
            os.writeBytes("exit\n")
            os.flush()
            os.close()
            
            // v3.6.1: Читаем streams ДО waitFor чтобы не заблокировать процесс
            process.errorStream.bufferedReader().use { it.readText() } // drain
            
            val finished = waitForProcess(process, ROOT_COMMAND_TIMEOUT)
            if (!finished) {
                return CommandResult(success = false, error = "Root timeout: ${ROOT_COMMAND_TIMEOUT}ms")
            }
            val exitCode = process.exitValue()
            
            if (exitCode == 0) {
                Log.d(TAG, "ROOT command SUCCESS")
                CommandResult(success = true)
            } else {
                Log.w(TAG, "ROOT command failed: exit=$exitCode")
                CommandResult(success = false, error = "Root: $exitCode")
            }
        } catch (e: Exception) {
            Log.e(TAG, "ROOT exception: $command", e)
            CommandResult(success = false, error = "Root error: ${e.message}")
        } finally {
            process?.destroyForcibly()
        }
    }
    
    /**
     * Shell команда с выводом
     * v3.6.1: use{} + destroyForcibly + output limit
     */
    private fun executeShellCommand(command: String): CommandResult {
        var process: Process? = null
        return try {
            process = if (hasRoot) {
                Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            } else {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            }
            
            // v3.6.1: Читаем ВСЕ streams через use{} с лимитом
            val output = process.inputStream.bufferedReader().use { it.readText().take(MAX_OUTPUT_SIZE) }
            val error = process.errorStream.bufferedReader().use { it.readText().take(MAX_OUTPUT_SIZE) }
            
            val finished = waitForProcess(process, ROOT_COMMAND_TIMEOUT)
            if (!finished) {
                return CommandResult(success = false, error = "Shell timeout: ${ROOT_COMMAND_TIMEOUT}ms")
            }
            val exitCode = process.exitValue()
            
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
        } finally {
            process?.destroyForcibly()
        }
    }
    
    /**
     * Выполнение команды с root правами (публичный)
     * v3.6.1: use{} + destroyForcibly + output limit
     */
    suspend fun executeAsRoot(command: String): CommandResult = withContext(Dispatchers.IO) {
        var process: Process? = null
        try {
            process = Runtime.getRuntime().exec("su")
            val outputStream = DataOutputStream(process.outputStream)
            
            outputStream.writeBytes("$command\n")
            outputStream.writeBytes("exit\n")
            outputStream.flush()
            outputStream.close()
            
            val output = process.inputStream.bufferedReader().use { it.readText().take(MAX_OUTPUT_SIZE) }
            val error = process.errorStream.bufferedReader().use { it.readText().take(MAX_OUTPUT_SIZE) }
            val finished = waitForProcess(process, ROOT_COMMAND_TIMEOUT)
            if (!finished) {
                return@withContext CommandResult(success = false, error = "Root timeout: ${ROOT_COMMAND_TIMEOUT}ms")
            }
            val exitCode = process.exitValue()
            
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
        } finally {
            process?.destroyForcibly()
        }
    }
    
    /**
     * Остановка фонового checker при уничтожении
     */
    fun shutdown() {
        rootCheckerJob?.cancel()
        scope.cancel()
    }

    /**
     * Ожидание завершения процесса с таймаутом
     */
    private fun waitForProcess(process: Process, timeoutMs: Long): Boolean {
        return try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            false
        }
    }
}
