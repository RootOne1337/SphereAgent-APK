# Changelog - SphereAgent APK

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
