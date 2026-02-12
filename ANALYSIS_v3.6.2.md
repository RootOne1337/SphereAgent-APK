# ПОЛНЫЙ АНАЛИЗ SphereAgent v3.6.2

**Дата:** 2026-02-06
**Кодовая база:** `/home/rootone/SphereAgent-APK/`
**Версия:** `versionName = "3.6.2"`, `versionCode = 102`
**Файлов Kotlin:** 45 | **Прочитано:** ~9500 строк ключевых файлов
**Стек:** Kotlin + Coroutines + Hilt DI + Jetpack Compose + OkHttp + kotlinx.serialization

---

## АРХИТЕКТУРА

### Ключевые компоненты:

| Компонент | Файл | Строк | Роль |
|---|---|---|---|
| SphereAgentApp | `SphereAgentApp.kt` | 265 | Application entry, Hilt DI, deferred ROOT setup |
| AgentService | `AgentService.kt` | 1140 | Foreground service, command dispatch, ScriptEngine |
| ConnectionManager | `ConnectionManager.kt` | 1084 | WebSocket, reconnect, heartbeat, offline buffer |
| CommandExecutor | `CommandExecutor.kt` | 1233 | Shell/ROOT/Accessibility command execution |
| ScriptEngine | `ScriptEngine.kt` | 1823 | Автоматизация, 70+ типов шагов |
| H264RootStreamService | `H264RootStreamService.kt` | 700 | H.264 через ROOT screenrecord |
| H264ScreenEncoder | `H264ScreenEncoder.kt` | 580 | MediaCodec H.264 (MediaProjection) |
| ScreenCaptureService | `ScreenCaptureService.kt` | 579 | MediaProjection foreground service |
| AgentConfig | `AgentConfig.kt` | 666 | Remote config + clone detection |
| EmergencyCommandExecutor | `EmergencyCommandExecutor.kt` | 395 | Аварийные команды через CDN |
| HttpPollingFallback | `HttpPollingFallback.kt` | 313 | HTTP fallback при недоступности WS |
| HealthMetricsCollector | `HealthMetricsCollector.kt` | 371 | Телеметрия устройства |
| AgentWorker | `AgentWorker.kt` | 106 | WorkManager watchdog (15 мин) |
| RootAutoStart | `RootAutoStart.kt` | 165 | ROOT auto-start setup |

### 3 уровня отказоустойчивости связи:
1. **WebSocket** (primary) → `ConnectionManager`
2. **HTTP Polling** (fallback через 2 мин) → `HttpPollingFallback`
3. **CDN Emergency** (GitHub/jsDelivr/Statically/GitHack) → `EmergencyCommandExecutor`

### Параметры подключения:
- Heartbeat: 30 сек (с jitter)
- Fast reconnect: первые 10 попыток — 100-1000ms
- Max reconnect delay: 10 сек
- Circuit break: после 500 попыток — 60 сек пауза
- Keyframe restart: каждые 120 сек
- OkHttp ping: ОТКЛЮЧЁН (heartbeat достаточно)

---

## 🔴 КРИТИЧЕСКИЕ ПРОБЛЕМЫ

### 🔴 C1. Удалённое выполнение через Emergency Commands без подписи

**Файл:** `EmergencyCommandExecutor.kt:66-74, 288-353`

Конфиг загружается из 4 CDN (GitHub Raw, jsDelivr, Statically, GitHack) **без криптографической верификации**. Поддерживаемые команды включают:
- `force_server_url` — перенаправление ВСЕХ агентов на другой сервер
- `execute_script` — выполнение произвольного скрипта
- `force_update` — установка произвольного APK
- `set_config` — изменение конфигурации

**Вектор атаки:** Компрометация GitHub аккаунта / MITM на CDN → полный контроль над всем флотом.

**Рекомендация:** HMAC-SHA256 подпись JSON с ключом в APK; верификация перед выполнением.

---

### 🔴 C2. Shell Command Injection через сервер

**Файл:** `CommandExecutor.kt:1138-1145`

Команда `shell` из WebSocket передаётся напрямую в `Runtime.exec()` без санитизации. С ROOT правами — полный контроль над устройством.

```kotlin
process = if (hasRoot) {
    Runtime.getRuntime().exec(arrayOf("su", "-c", command))  // command = user input!
} else {
    Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
}
```

**Рекомендация:** Whitelist команд, санитизация метасимволов, логирование.

---

### 🔴 C3. Hardcoded IP fallbacks без TLS

**Файл:** `AgentConfig.kt:296-307`

Два из трёх hardcoded fallback URL используют `ws://` (без шифрования):
```
ws://212.220.204.72:8001   ← PLAINTEXT
ws://212.220.204.72:8000   ← PLAINTEXT
wss://212.220.204.72       ← OK
```

Все данные (heartbeat, команды, credentials) идут открытым текстом по этим каналам.

**Рекомендация:** Убрать `ws://`, использовать только `wss://`.

---

### 🔴 C4. Потенциальный deadlock в RootAutoStart

**Файл:** `RootAutoStart.kt:135-142`

```kotlin
val output = reader.readText()   // БЛОКИРУЕТ до EOF
val error = errorReader.readText() // БЛОКИРУЕТ до EOF
val finished = process.waitFor(5, ...) // НЕДОСТИЖИМО если readText() повис
```

`readText()` блокируется до EOF. Если `su` процесс повиснет — поток заблокируется навечно, таймаут 5 сек **никогда не будет достигнут**.

**Рекомендация:** Читать streams в отдельных потоках или использовать неблокирующий ввод с таймаутом.

---

### 🔴 C5. stdout не дренируется в CommandExecutor.executeRootCommand()

**Файл:** `CommandExecutor.kt:1097-1131`

Читается только `errorStream` ("drain"), но `inputStream` (stdout) **не читается**. Если ROOT команда выводит >64KB — pipe buffer заполняется, процесс блокируется на write, `waitFor()` не завершается → таймаут.

```kotlin
process.errorStream.bufferedReader().use { it.readText() } // drain stderr
// ← stdout НЕ дренируется! 
val finished = waitForProcess(process, ROOT_COMMAND_TIMEOUT)
```

**Рекомендация:** Дренировать оба потока параллельно в отдельных потоках.

---

## 🟠 СЕРЬЁЗНЫЕ ПРОБЛЕМЫ

### 🟠 S1. ScriptEngine: 6 StepType'ов не работают

**Файл:** `ScriptEngine.kt:1460-1497`

BREAK, CONTINUE, RESTART_SCRIPT, WHILE, LOOP_FOREVER, TRY_CATCH — устанавливают переменные `_break`, `_continue`, `_restart`, но `executeScript()` (строки 494-627) **никогда не проверяет** эти переменные. Эти шаги полностью нефункциональны.

**Затронутые функции Visual Editor:** Все циклы и блоки try-catch в скриптах.

---

### 🟠 S2. Thread Safety в ScriptRunner

**Файл:** `ScriptEngine.kt:421-422`

```kotlin
private var isPaused = false   // НЕ volatile, НЕ AtomicBoolean
private var isStopped = false  // НЕ volatile, НЕ AtomicBoolean
```

Модифицируются из разных корутин (`pause()`, `stop()` извне, чтение — в script coroutine). Возможна невидимость изменений из-за CPU cache на многоядерных устройствах.

**Рекомендация:** Заменить на `AtomicBoolean` (как в `H264RootStreamService`).

---

### 🟠 S3. Двойной запуск screenrecord в H264RootStreamService

**Файл:** `H264RootStreamService.kt:348-534, 576-586`

`restartScreenrecord()` вызывает `startScreenrecordStream()` напрямую. Но `startScreenrecordStream()` в `finally` блоке (строка 528-533) также запускает restart через `scheduleStreamRestart()`. При keyframe restart могут запуститься **два конкурирующих** screenrecord процесса.

**Рекомендация:** Добавить guard (AtomicBoolean `isRestarting`) для предотвращения concurrent restarts.

---

### 🟠 S4. Четыре отдельных OkHttpClient

4 отдельных OkHttpClient в:
- `ConnectionManager.kt` — WebSocket
- `AgentConfig.kt` — Remote Config
- `EmergencyCommandExecutor.kt` — CDN
- `HttpPollingFallback.kt` — HTTP polling

Каждый: свой thread pool (~2 потока) + connection pool (5 idle). Итого ~8 лишних потоков + 20 idle connections.

**Рекомендация:** Один shared OkHttpClient через Hilt DI (`NetworkModule.kt` уже существует!).

---

### 🟠 S5. Device ID на SD-карте без защиты

**Файл:** `AgentConfig.kt:622-631`

Device ID сохраняется в `/sdcard/.sphere_id` — world-readable. Любое приложение может прочитать/подменить.

**Рекомендация:** Шифровать ID или использовать внутреннее хранилище с MODE_PRIVATE.

---

### 🟠 S6. Нет minification в Release

**Файл:** `build.gradle.kts`

`isMinifyEnabled = false`, `isShrinkResources = false` → APK не обфусцирован, легко декомпилируется. Виден протокол, URL серверов, логика reconnect.

---

### 🟠 S7. Circuit Breaker сбрасывается в 0

**Файл:** `ConnectionManager.kt:990-994`

```kotlin
if (attempt > MAX_RECONNECT_ATTEMPTS) {
    delay(CIRCUIT_BREAK_PAUSE_MS)
    reconnectAttempt.set(0)  // Reset after pause
}
```

После паузы 60 сек счётчик сбрасывается в 0, и агент снова делает 500 быстрых попыток. Паттерн: 500 попыток → 60с → 500 попыток → 60с — **бесконечный**. Нет экспоненциального увеличения паузы.

**Рекомендация:** Увеличивать паузу экспоненциально: 60с → 120с → 240с → max 600с.

---

### 🟠 S8. ScriptEngine: 10+ нереализованных StepType'ов (TODO stubs)

Заглушки без реальной функциональности:
- `SCREENSHOT` — "TODO: Implement screenshot capture"
- `WAIT_SCREEN_STABLE` — фиктивное ожидание 3 раза по 500мс
- `PIXEL_CHECK`, `PIXEL_WAIT`, `PIXEL_GROUP` — возвращают "true" без проверки
- `TEMPLATE_WAIT`, `TEMPLATE_TAP`, `TEMPLATE_EXISTS` — "template_not_implemented"
- `NOTIFY` — "TODO: Integrate with Android notification system"

**Риск:** Скрипты, использующие эти шаги, будут всегда "успешны" но ничего не делать.

---

### 🟠 S9. evaluateCondition() — ограничения и баги

**Файл:** `ScriptEngine.kt:1761-1780`

Парсер разделяет на пробелы: `condition.split(" ")`. Условие `text == hello world` будет некорректно: `parts = ["text", "==", "hello", "world"]`, `value = "hello world"` — OK благодаря `drop(2).joinToString(" ")`. Но `variable_name == value` требует ровно 1 пробел перед/после оператора.

Не поддерживает: AND/OR, вложенные условия, числовые сравнения (>=, <=), регулярки.

---

### 🟠 S10. XPath поиск через Regex (хрупкий)

**Файл:** `CommandExecutor.kt:911-1005`

`findElementByXPath()` парсит XML через regex вместо настоящего XPath парсера. Ломается на:
- Escaped кавычки в атрибутах
- Многострочные XML nodes (regex ищет `<node ... />` на одной строке)
- Вложенные элементы с одинаковыми атрибутами
- Сложные XPath выражения (axes, predicates)

---

## 🟡 ЗАМЕЧАНИЯ И РЕКОМЕНДАЦИИ

### 🟡 R1. Watchdog дублирование

`AgentWorker` (WorkManager, каждые 15 мин) + `ConnectionManager.startConnectionWatchdog()` (каждые 30 сек) — оба мониторят соединение. Возможны race conditions при одновременном reconnect.

### 🟡 R2. Два streaming сервиса

`H264RootStreamService` (ROOT screenrecord) и `ScreenCaptureService` (MediaProjection) существуют параллельно. `start_stream` использует только ROOT, но `request_keyframe` проверяет оба. `ScreenCaptureService` фактически не используется в текущем flow.

### 🟡 R3. ScriptLogSender в ScriptEngine

Каждый шаг скрипта отправляет лог на сервер (`ScriptLogSender.logStep()`). При скриптах с 100+ шагами и 10 concurrent скриптах — 1000+ сообщений в минуту. Batching реализован в `AgentService` для `script_status`, но не для `ScriptLogSender`.

### 🟡 R4. Offline Buffer без лимита размера

`ConnectionManager` буферизует сообщения при disconnect, но максимальный размер буфера не ограничен. При длительном disconnect + активном скрипте буфер может расти неограниченно.

### 🟡 R5. getprop в цикле при генерации fingerprint

`AgentConfig.addEmulatorSpecificFingerprint()` запускает до 5 отдельных `getprop` процессов. На медленных эмуляторах с 2с таймаутом на каждый — до 10 секунд. Лучше один `getprop` вызов и парсинг вывода.

---

## ✅ ПОЗИТИВНЫЕ ИЗМЕНЕНИЯ v3.6.2 vs v2.2.0

| Версия | Улучшение | Влияние |
|---|---|---|
| v3.6.2 | `shutdown()` с освобождением OkHttpClient (#33) | Нет zombie потоков |
| v3.6.2 | Circuit-breaking после 500 попыток (#34) | Защита от reconnect-шторма |
| v3.6.1 | `use{}` + `destroyForcibly()` для всех Process | Нет утечки файловых дескрипторов |
| v3.6.1 | NAL buffer с лимитом 2MB | Защита от OOM при стриминге |
| v3.6.1 | `MAX_OUTPUT_SIZE = 256KB` для shell output | Защита от OOM |
| v3.6.0 | Deferred ROOT setup (60 сек) | Нет ANR при загрузке |
| v3.6.0 | Удалён restart alarm | Нет crash→restart loop |
| v3.6.0 | Batch su (1 вместо 10 процессов) | 10x меньше su на флоте |
| v3.6.0 | `Dispatchers.IO` вместо Default | Нет блокировки CPU pool |
| v3.5.8 | Переключение серверов при DNS/Connection ошибках | Автоматический failover |
| v3.5.4 | Keyframe interval 120s (было 15s) | 8x меньше fork на флоте |
| v3.5.4 | Кеш метрик 60 сек | Нет двойного сбора |
| v3.5.1 | Таймауты на все Process | Защита от зависаний |
| v3.5.0 | ScriptLogSender | Логирование шагов скрипта |
| v2.27.0 | Connection Watchdog | Автовосстановление |
| v2.26.0 | Clone detection | Уникальные ID для клонов LDPlayer |
| v2.26.0 | Slot assignment | Привязка аккаунтов/прокси |
| v2.8.0 | ScriptEventBus + GlobalVariables | Межскриптовое взаимодействие |
| v2.7.0 | XPATH_POOL оптимизация | 1 UI dump вместо N |

---

## ИТОГОВАЯ ОЦЕНКА

### Стабильность соединения: 8/10 ⬆️ (было ~6/10 в v2.2.0)
- ✅ Circuit-breaking, fast reconnect, jitter
- ✅ 3 уровня fallback (WS → HTTP → CDN)
- ✅ Watchdog + WorkManager
- ⚠️ Circuit breaker не экспоненциальный
- ⚠️ Двойной watchdog может конфликтовать

### Стабильность приложения: 7/10 ⬆️ (было ~5/10)
- ✅ `use{}` + `destroyForcibly()` для процессов
- ✅ Deferred ROOT setup, no alarm loops
- ✅ Output size limits, NAL buffer limits
- ⚠️ Deadlock в RootAutoStart
- ⚠️ stdout не дренируется в executeRootCommand
- ⚠️ Thread safety в ScriptRunner

### Безопасность: 4/10 ⚠️
- 🔴 Emergency commands без подписи
- 🔴 Shell injection через сервер
- 🔴 Plaintext ws:// fallbacks
- 🔴 Нет minification/obfuscation
- ⚠️ Device ID world-readable

### Скриптовый движок: 6/10
- ✅ 70+ типов шагов, XPath, событийная модель
- ✅ Параллельное выполнение, loop/pause/stop
- ⚠️ 6 control flow шагов не работают
- ⚠️ 10+ шагов — заглушки
- ⚠️ XPath через regex (хрупкий)

### H.264 стриминг: 8/10
- ✅ Hardware H.264 через ROOT screenrecord
- ✅ MediaCodec fallback без ROOT
- ✅ Keyframe management, SPS/PPS caching
- ✅ Pause/Resume без пересоздания сервиса
- ⚠️ Двойной restart возможен

### Масштабирование (1000+ устройств): 7/10
- ✅ Jitter на всех периодических операциях
- ✅ Batch su, deferred ROOT, cached metrics
- ✅ Clone detection для LDPlayer
- ⚠️ 4 OkHttpClient × 1000 = overhead
- ⚠️ ScriptLogSender без batching

---

## ПРИОРИТЕТНЫЙ ПЛАН ИСПРАВЛЕНИЙ

### Приоритет 1 (Критичные — исправить немедленно):
1. **Подписать Emergency Config** — HMAC-SHA256
2. **Убрать ws:// fallbacks** — только wss://
3. **Исправить deadlock в RootAutoStart** — async stream reading
4. **Дренировать stdout в executeRootCommand** — оба потока параллельно

### Приоритет 2 (Серьёзные — следующий спринт):
5. **AtomicBoolean для isPaused/isStopped** в ScriptRunner
6. **Guard для concurrent screenrecord restart**
7. **Один shared OkHttpClient** через Hilt
8. **Экспоненциальный circuit breaker**
9. **Включить minification** для release builds

### Приоритет 3 (Улучшения):
10. **Реализовать BREAK/CONTINUE/WHILE** в ScriptEngine
11. **Лимит offline buffer** в ConnectionManager
12. **Batch getprop** вместо цикла
13. **Убрать неиспользуемый ScreenCaptureService** или интегрировать
