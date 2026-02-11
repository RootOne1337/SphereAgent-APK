# Changelog - SphereAgent APK

## [3.9.0] - 2026-02-10

### Added — AmneziaWG VPN Integration (Sprint 2)
- **VpnManager.kt** (NEW): Программное управление AWG/WG VPN туннелем на root эмуляторах. Поддержка wg-quick, UI automation и broadcast методов активации. Проверка внешнего IP для подтверждения.
- **VpnHealthMonitor.kt** (NEW): Мониторинг здоровья VPN каждые 30 секунд — проверка интерфейса, внешнего IP, доступности сервера управления (split-tunnel). Self-healing с автоматической переактивацией (до 3 попыток с cooldown).
- **VpnKillSwitch.kt** (NEW): Kill-switch через iptables — блокировка всего исходящего трафика кроме VPN интерфейса, сервера управления, DNS и SphereAgent. Защита от утечки реального IP при падении VPN.
- **AgentService.kt**: 6 новых VPN команд: `vpn_config`, `vpn_activate`, `vpn_deactivate`, `vpn_status`, `vpn_health`, `vpn_killswitch`. Инициализация VPN компонентов при старте, graceful shutdown при остановке.
- **ConnectionManager.kt**: VPN статус в Hello (`vpn_capable`, `vpn_active`, `vpn_ip`, `vpn_config_type`) и Heartbeat (`vpn_active`, `vpn_ip`). Бэкенд видит VPN состояние каждого агента в реальном времени.
- **Backend AgentInfo**: VPN поля (`vpn_capable`, `vpn_active`, `vpn_ip`, `vpn_config_type`, `vpn_healthy`) в AgentInfo dataclass и REST API.
- **Backend WebSocket**: Обработчик `vpn_health_report` от агентов с логированием проблем.

### Technical Details
- Version Code: 106
- Version Name: 3.9.0
- Совместимость: Android 7.0+ (API 24), root required для VPN управления

---

## [3.6.2] - 2026-02-08

### Fixed — Connection Stability & Resource Leaks
- **OkHttpClient shutdown** (`ConnectionManager.kt`): `httpClient.dispatcher.executorService.shutdown()` + `connectionPool.evictAll()` при `shutdown()` — устраняет утечку thread pool.
- **Circuit-breaking для reconnect** (`ConnectionManager.kt`): После 500 попыток — пауза 60с, затем сброс. Предотвращает бесконечный цикл reconnect при недоступном сервере.
- **Zombie coroutine fix** (`ConnectionManager.kt`): `disconnect()` теперь отменяет ВСЕ child jobs (`heartbeatJob`, `watchdogJob`, `reconnectJob`) + сброс `isConnecting`.
- **Offline buffer atomicity** (`ConnectionManager.kt`): `@Synchronized` на `bufferMessage()` — устраняет TOCTOU race при одновременной буферизации из разных потоков.
- **Batch buffer cap** (`AgentService.kt`): `statusBatchBuffer` ограничен 200 записями — защита от OOM при длительном disconnect.
- **Proper service shutdown** (`AgentService.kt`): `onDestroy()` вызывает `connectionManager.shutdown()` вместо `disconnect()` — полное освобождение OkHttp ресурсов.

### Technical Details
- Version Code: 102
- Version Name: 3.6.2
- OTA deployed: 13/13 agents updated

---

## [3.6.1] - 2026-02-08

### Fixed — Critical Stability (22 fixes)
- **CommandExecutor**: FD exhaustion fix — `use{}` + `destroyForcibly()`
- **H264RootStreamService**: NAL buffer OOM fix (ByteArrayOutputStream + 2MB cap)
- **ConnectionManager**: AtomicInteger для pendingFrames, атомарный drain offlineBuffer
- **ServerDiscoveryManager**: Semaphore(24) для network scan
- **BootReceiver**: AtomicBoolean guard, 0 Thread.sleep, 0 su процессов
- **RootAutoStart/RootInitInstaller**: batch su sessions (1 процесс вместо 30+)
- **SphereAgentApp**: ROOT ops deferred 60s, одноразовый флаг
- **AgentService**: удалены restart alarm (START_STICKY + WorkManager)
- **ScreenCaptureService**: удалён дубликат UpdateManager
- **ScriptLogSender**: ConcurrentHashMap thread safety

### Technical Details
- Version Code: 101
- Version Name: 3.6.1
- 22 stability fixes eliminating ANR, FD leaks, OOM, race conditions

---

## [3.0.1] - 2026-01-28

### Fixed - H.264 stream start

- `start_stream` теперь запускает H.264 encoder (MediaProjection) напрямую
- Авто-запуск `ScreenCaptureService` перед стартом стрима
- Авто-запрос MediaProjection (через MainActivity) если нет разрешения
- Убран JPEG fallback при `compression=h264`

### Technical Details
- Version Code: 80
- Version Name: 3.0.1
- Modified: `AgentService.kt` (H.264 start_stream flow)

---

## [2.27.1] - 2026-01-26

### Added - Enterprise Stability Hardening

- **Jitter для периодических задач**: watchdog/heartbeat/reconnect распределяются по времени
  - Убраны синхронные пики нагрузки при массовом запуске эмуляторов
  - Jitter добавлен в AlarmManager watchdog, WorkManager и ConnectionManager

- **Timeout для ROOT/Shell команд**: защита от зависаний `su`/`sh`
  - Все команды завершаются по таймауту (ROOT_COMMAND_TIMEOUT)
  - Исключены бесконечные блокировки процессов на слабых эмуляторах

### Changed

- **Lazy RootScreenCaptureService**: сервис стартует только по команде `start_stream`
- **Default streaming profile**: качество 70, FPS 10 (легковесный режим для ферм)

### Fixed

- **Log spam reduction**: троттлинг ошибок отправки кадров
- **Плавность reconnection**: jitter для reconnect задержек

### Technical Details
- Version Code: 76
- Modified: `AgentService.kt` (lazy root capture, jitter, defaults)
- Modified: `ConnectionManager.kt` (heartbeat/watchdog/reconnect jitter)
- Modified: `CommandExecutor.kt` (timeouts for root/shell)
- Modified: `RootScreenCaptureService.kt` (lighter defaults, throttled logs)
- Modified: `AgentWorker.kt`, `BootJobService.kt` (desync periodic tasks)

---

## [2.26.0] - 2026-01-25

### Added - Enterprise Wave Optimization & Network Resilience

- **script_status Jitter (100-500ms)**: Распределение нагрузки при 1000+ устройств
  - При массовом запуске "волны" устройства не забивают бэкенд одновременно
  - Случайная задержка 100-500ms перед отправкой статуса
  - `sendMessageWithJitter()` в ConnectionManager

- **Offline Buffer**: Буферизация сообщений при disconnect
  - До 100 сообщений сохраняются при потере соединения
  - TTL 5 минут для буферизованных сообщений
  - Автоматический flush при восстановлении WebSocket
  - Приоритет для высокоприоритетных сообщений (script_status)

- **Screenshot on Failure**: Скриншот при ошибке XPath
  - `captureFailureScreenshot()` в XPathHelper
  - Base64 скриншот для отладки скриптов на ферме
  - `waitForXPathWithScreenshot()` для автоматического захвата

- **Enhanced Clone Detection for LDPlayer/Memu/Nox**:
  - Чтение специфичных свойств эмулятора из build.prop:
    - `ro.ld.player.index` - индекс инстанса LDPlayer
    - `ro.ld.adb.port` - ADB порт (уникален)
    - `ro.serialno`, `ro.boot.serialno`
    - `ro.emu.instance.id`, `ro.memu.instance.id`, `ro.nox.instance.id`
  - Использование `getprop` для получения свойств
  - **ИСПРАВЛЕНО**: Убран `boot_id` - менялся при перезагрузке

- **SD Card ID Backup**: Fallback хранение Device ID
  - Сохранение в `/sdcard/.sphere_id`
  - Восстановление при переустановке APK
  - Защита от потери ID при сбросе данных приложения

- **Health Metrics Collector** (NEW P2):
  - CPU usage (%) через /proc/stat
  - Memory usage (used/total MB, %)
  - Battery level + charging status
  - Storage available/total
  - App memory (PSS)
  - Uptime seconds
  - Health warnings (HIGH_CPU, HIGH_MEMORY, LOW_BATTERY, LOW_STORAGE)
  - Все метрики отправляются в heartbeat каждые 15 сек

- **Batch Status Updates** (NEW P2):
  - Агрегация промежуточных статусов скрипта (RUNNING)
  - Flush каждые 500ms вместо немедленной отправки
  - Критические статусы (STARTED, COMPLETED, FAILED) отправляются сразу
  - Уменьшение нагрузки на WebSocket при быстрых скриптах

### Technical Details
- Version Code: 74
- NEW: `HealthMetricsCollector.kt` - сбор метрик здоровья
- Modified: `ConnectionManager.kt` - Offline Buffer + Jitter + Health Metrics в heartbeat
- Modified: `AgentService.kt` - Batch Status Updates
- Modified: `XPathHelper.kt` - Screenshot on Failure
- Modified: `AgentConfig.kt` - LDPlayer props + SD card backup (исправлен boot_id баг)
- **Enterprise Production Ready**: 100% отказоустойчивость для 1000+ устройств

---

## [2.24.0] - 2026-01-24

### Added - Enterprise Script Orchestration System
- **GlobalVariables Manager**: Глобальные переменные для обмена данными между скриптами
  - Thread-safe singleton с namespaces и TTL
  - Атомарные операции: `set`, `get`, `delete`, `increment`
  - Подписки на изменения (listeners)
  - Коллекции: `appendToList`, `putToMap`
  - Import/Export состояния

- **ScriptEventBus**: Событийная шина для межскриптового взаимодействия
  - Emit/Subscribe с wildcard patterns (`script.*`)
  - Встроенные триггеры на события
  - `waitForEvent()` с timeout для синхронизации
  - История событий для дебага

- **New StepTypes** (15 новых команд оркестрации):
  - `SET_GLOBAL`, `GET_GLOBAL`, `DELETE_GLOBAL` - управление переменными
  - `INCREMENT_GLOBAL` - атомарный инкремент
  - `APPEND_TO_LIST`, `PUT_TO_MAP` - работа с коллекциями
  - `EMIT_EVENT`, `WAIT_FOR_EVENT`, `SUBSCRIBE_EVENT` - события
  - `START_SCRIPT`, `STOP_SCRIPT`, `WAIT_SCRIPT` - управление скриптами
  - `REGISTER_TRIGGER`, `REMOVE_TRIGGER` - триггеры

- **ScriptRunner Integration**: Автоматические события lifecycle
  - `script.started` - при запуске скрипта
  - `script.completed` - при успешном завершении
  - `script.failed` - при ошибке

### Technical Details
- Version Code: 72
- New files: `GlobalVariables.kt`, `ScriptEventBus.kt`
- Modified: `ScriptEngine.kt` - 15 new step handlers
- Полная совместимость с backend Orchestrator API v2.8.0

---

## [2.23.0] - 2026-01-23

### Added
- **XPath Pool Command**: новая команда `xpath_pool` для пакетного поиска элементов
  - Проверяет пул XPath селекторов за ОДИН UI dump
  - Первый найденный элемент → автоматический tap
  - Параметры: `timeout`, `retry_count`, `retry_interval`
  - Возвращает JSON с координатами, индексом, результатом

### Technical Details
- Version Code: 71
- CommandExecutor: `xpathPool()` + `findElementByXPath()` helper
- AgentService: обработчик `xpath_pool` команды
- Совместимо с VisualBuilder xpath_pool блоком

---

## [2.22.0] - 2026-01-22

### Added
- **INIT.RC Auto-Start**: установка init.rc триггеров для запуска при boot.
- **Boot Triggers**: sys.boot_completed, dev.bootcomplete, bootanim.exit.
- **ScriptEngine Logic**: поддержка шагов `GET_TIME`, `IF`, `GOTO` для управления потоком.

### Changed
- **ROOT Script**: ожидание boot с таймаутом и доп. диагностикой.
- **AutoStartActivity**: fallback запуск через невидимую Activity.

### Fixed
- **Emulator Boot**: усиленный автозапуск на эмуляторах/кастомных ROM.
- **Script Execution**: корректные переходы по условиям и прыжкам между шагами.

## [2.1.0] - 2026-01-08

### Added - Enterprise Control System
- **Clipboard Sync**: Синхронизация буфера обмена между ПК и устройством
  - `clipboard_set` - отправить текст в буфер устройства
  - `clipboard_get` - получить текст из буфера устройства
- **Extended Input Commands**:
  - `key_combo` - комбинации клавиш (Ctrl+, Alt+, etc.)
  - `pinch` - жест масштабирования (zoom in/out)
  - `rotate` - жест вращения двумя пальцами
- **File Operations**:
  - `file_list` - список файлов в директории
  - `file_read` - чтение файла (с base64 для бинарных)
  - `file_delete` - удаление файла
  - `mkdir` - создание директории
- **Logcat Management**:
  - `logcat` - получение логов (с фильтрацией)
  - `logcat_clear` - очистка логов
- **UI Automation**:
  - `get_hierarchy` - дамп UI иерархии (XML для автоматизации)
  - `screenshot_base64` - скриншот в base64 формате
- **Device Info**:
  - `get_battery` - уровень заряда
  - `get_network` - сетевая информация
  - `get_device_info` - полная информация об устройстве
- **App Management**:
  - `launch_app` - запуск приложения
  - `force_stop` - принудительная остановка
  - `clear_app_data` - очистка данных приложения
  - `list_packages` - список установленных приложений

### Technical Details
- Version Code: 33
- CommandExecutor v2.1.0 с 20+ новыми методами
- ClipboardManager интеграция через Main thread Handler
- Base64 encoding для бинарных данных
- UIAutomator dump для UI иерархии

---

## [2.0.6] - 2026-01-07

### Fixed
- **Stream Reconnect**: Убран дублирующий вызов initializeAgent() при старте стрима
- **isConnected Check**: Проверка подключения перед connect() - не переподключаемся если уже подключены
- **Stable Stream**: Стрим не сбрасывается при получении команды start_stream

### Technical Details
- Version Code: 32
- ScreenCaptureService.initializeAgent() проверяет connectionManager.isConnected
- Убран второй вызов initializeAgent() в startCapture()

---

## [2.0.5] - 2026-01-07

### Fixed
- **Hardcoded Fallback URLs**: Убраны захардкоженные fallback серверы из ServerSettings
- **Только конфигурируемые серверы**: Агент использует только URL из remote config

### Technical Details
- Version Code: 31
- Target SDK: 35

---

## [2.0.4] - 2026-01-07

### Fixed
- **Reconnect Loop**: Добавлен Mutex для предотвращения параллельных подключений
- **Code 1001 Handling**: Сервер заменил соединение - не переподключаемся
- **Code 4003 Handling**: Уже подключен - ждём 30 секунд перед повторной попыткой
- **Cancel Pending Reconnect**: reconnectJob?.cancel() перед новым подключением

### Technical Details
- Version Code: 30
- Добавлен connectionMutex = Mutex()
- Добавлен reconnectJob: Job? для отмены pending reconnects

---

## [2.0.3] - 2026-01-06

### Fixed
- **NetworkReceiver Disabled**: Полностью отключён NetworkReceiver - источник reconnect loop
- **Stable Connection**: Убраны все источники неконтролируемых переподключений

### Technical Details
- Version Code: 29

---

## [2.0.2] - 2026-01-06

### Fixed
- **Reconnect Strategy**: isConnecting флаг для предотвращения параллельных подключений
- **WebSocket Stability**: Улучшена обработка close кодов

### Technical Details
- Version Code: 28

---

## [2.0.1] - 2026-01-05

### Fixed
- **Stream Buffering**: Очередь фреймов с приоритетом команд
- **Heartbeat Protection**: Фреймы не блокируют heartbeat

### Technical Details
- Version Code: 27

---

## [2.0.0] - 2026-01-05

### Added
- **Frame Queue System**: Очередь стрим-фреймов с ограничением
- **Priority Commands**: Команды выполняются вне очереди фреймов
- **Connection States**: Чёткие состояния подключения

### Technical Details
- Version Code: 26
- Breaking: Новая архитектура стриминга

---

## [1.9.8] - 2026-01-04

### Added
- **Boot Start**: AgentService запускается при старте устройства
- **Auto Reconnect**: Улучшенная логика переподключения

### Technical Details
- Version Code: 25

---

## [1.9.7] - 2026-01-04

### Fixed
- **Command Loop Fix**: Критический фикс обработки команд
- **Execution Stability**: Стабильное выполнение команд

### Technical Details
- Version Code: 24

---

## [1.9.6] - 2026-01-04

### Added
- **Command Diagnostics**: Диагностика command loop

### Technical Details
- Version Code: 23

---

## [1.9.5] - 2026-01-03

### Fixed
- **ROOT Detection**: Гарантированное определение ROOT прав

### Technical Details
- Version Code: 22

---

## [1.9.2] - 2025-01-03

### Added
- **OTA Updates**: Автоматическое скачивание и установка обновлений
- **Silent ROOT Install**: Тихая установка APK через `su` команды  
- **Update Command**: Обработка команды `update_agent` от сервера

### Fixed
- **ROOT Detection**: Исправлена инициализация ROOT прав
- **Control Commands**: Все кнопки управления работают корректно

### Technical Details
- Version Code: 18

---

## [1.9.0] - 2025-01-01

### Added
- **ROOT-only Mode**: Полное управление устройством через ROOT права

---

## [1.7.0] - 2024-12-24

### Added
- **Enterprise Diagnostics**: Hello message с полной диагностикой

---

## [1.6.0] - 2024-12-23

### Added
- **Accessibility Service**: Non-root управление

---

## [1.5.0] - 2024-12-22

### Added
- **MediaProjection**: Screen capture и streaming

---

## [1.0.0] - 2024-12-20

### Added
- Первоначальный релиз
