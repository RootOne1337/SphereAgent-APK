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
        
        // ROOT проверяется агрессивно - нет лимита попыток!
        private const val ROOT_CHECK_INTERVAL = 5_000L // 5 секунд между проверками
        private const val ROOT_COMMAND_TIMEOUT = 10_000L // 10 секунд таймаут на su команду
        
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
        // Запускаем агрессивную проверку ROOT сразу
        startAggressiveRootChecker()
    }
    
    /**
     * Запуск фонового агрессивного ROOT checker
     * Проверяет каждые 5 секунд пока ROOT не подтверждён
     */
    private fun startAggressiveRootChecker() {
        rootCheckerJob?.cancel()
        rootCheckerJob = scope.launch {
            Log.i(TAG, "=== AGGRESSIVE ROOT CHECKER STARTED ===")
            
            while (isActive && !rootConfirmed) {
                try {
                    val result = performFullRootCheck()
                    
                    if (result) {
                        Log.i(TAG, "=== ROOT CONFIRMED! Stopping checker ===")
                        rootConfirmed = true
                        hasRoot = true
                        onRootStatusChanged?.invoke(true)
                        break
                    } else {
                        rootCheckAttempts++
                        Log.w(TAG, "ROOT check attempt #$rootCheckAttempts failed, retrying in ${ROOT_CHECK_INTERVAL}ms...")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "ROOT checker error: ${e.message}")
                }
                
                delay(ROOT_CHECK_INTERVAL)
            }
        }
    }
    
    /**
     * Полная проверка ROOT через ВСЕ доступные методы
     * Возвращает true если ЛЮБОЙ метод успешен
     */
    private suspend fun performFullRootCheck(): Boolean = withContext(Dispatchers.IO) {
        if (rootCheckInProgress) {
            Log.d(TAG, "ROOT check already in progress, waiting...")
            return@withContext hasRoot
        }
        
        rootCheckInProgress = true
        
        try {
            Log.d(TAG, "=== FULL ROOT CHECK START ===")
            
            // Метод 1: su -c id (стандартный)
            if (checkRootMethod1()) {
                Log.i(TAG, "✓ ROOT via method 1: su -c id")
                return@withContext true
            }
            
            // Метод 2: интерактивный su shell
            if (checkRootMethod2()) {
                Log.i(TAG, "✓ ROOT via method 2: interactive su")
                return@withContext true
            }
            
            // Метод 3: su -c whoami
            if (checkRootMethod3()) {
                Log.i(TAG, "✓ ROOT via method 3: su -c whoami")
                return@withContext true
            }
            
            // Метод 4: проверка su binary
            if (checkRootMethod4()) {
                Log.i(TAG, "✓ ROOT via method 4: su binary exists")
                return@withContext true
            }
            
            // Метод 5: su 0 (альтернативный синтаксис)
            if (checkRootMethod5()) {
                Log.i(TAG, "✓ ROOT via method 5: su 0 id")
                return@withContext true
            }
            
            Log.w(TAG, "=== ALL ROOT METHODS FAILED ===")
            return@withContext false
            
        } finally {
            rootCheckInProgress = false
        }
    }
    
    /**
     * Метод 1: su -c id (стандартный)
     */
    private fun checkRootMethod1(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val result = reader.readLine() ?: ""
            val exitCode = process.waitFor()
            
            Log.d(TAG, "Method1 result: $result, exit=$exitCode")
            result.contains("uid=0")
        } catch (e: Exception) {
            Log.w(TAG, "Method1 failed: ${e.message}")
            false
        }
    }
    
    /**
     * Метод 2: Интерактивный su shell (для LDPlayer/Bluestacks)
     */
    private fun checkRootMethod2(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            
            os.writeBytes("id\n")
            os.writeBytes("exit\n")
            os.flush()
            os.close()
            
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val result = reader.readText()
            val exitCode = process.waitFor()
            
            Log.d(TAG, "Method2 result: ${result.take(100)}, exit=$exitCode")
            result.contains("uid=0")
        } catch (e: Exception) {
            Log.w(TAG, "Method2 failed: ${e.message}")
            false
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
            val exitCode = process.waitFor()
            
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
                    val exitCode = process.waitFor()
                    
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
            val exitCode = process.waitFor()
            
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
     */
    fun resetRootCache() {
        Log.i(TAG, "ROOT cache RESET - will recheck aggressively")
        hasRoot = false
        rootConfirmed = false
        rootCheckAttempts = 0
        
        // Перезапускаем агрессивный checker
        startAggressiveRootChecker()
    }
    
    /**
     * Текущий статус ROOT (для быстрого доступа)
     */
    fun hasRootAccess(): Boolean = hasRoot || rootConfirmed
    
    /**
     * Tap - нажатие в точку (x, y)
     */
    suspend fun tap(x: Int, y: Int): CommandResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "tap($x, $y) - hasRoot=$hasRoot, confirmed=$rootConfirmed")
        
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
     */
    private fun executeInputCommand(command: String): CommandResult {
        return try {
            val process = if (hasRoot) {
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
     * ВСЕГДА пробуем su, даже если hasRoot=false
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
                Log.w(TAG, "ROOT command failed: exit=$exitCode, error=$error")
                CommandResult(success = false, error = "Root: $exitCode - $error")
            }
        } catch (e: Exception) {
            Log.e(TAG, "ROOT exception: $command", e)
            CommandResult(success = false, error = "Root error: ${e.message}")
        }
    }
    
    /**
     * Shell команда с выводом
     */
    private fun executeShellCommand(command: String): CommandResult {
        return try {
            val process = if (hasRoot) {
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
     * Выполнение команды с root правами (публичный)
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
    
    /**
     * Остановка фонового checker при уничтожении
     */
    fun shutdown() {
        rootCheckerJob?.cancel()
        scope.cancel()
    }
}
