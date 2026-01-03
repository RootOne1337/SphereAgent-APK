# Changelog - SphereAgent APK

## [1.9.2] - 2025-01-03

### Added
- **OTA Updates**: Автоматическое скачивание и установка обновлений
- **Silent ROOT Install**: Тихая установка APK через `su` команды  
- **Update Command**: Обработка команды `update_agent` от сервера
- **Enhanced UpdateManager**: Проверка ROOT доступа и fallback на стандартную установку

### Fixed
- **ROOT Detection**: Исправлена инициализация ROOT прав при подключении к серверу
- **Command Execution**: ROOT проверяется ДО подключения и результат сохраняется в connectionManager
- **Control Commands**: Все кнопки управления (Home, Back, Recent, tap, swipe) теперь работают корректно

### Technical Details
- Version Code: 18 
- Target SDK: 35
- APK Size: ~11MB
- **Breaking**: Требуется обязательное обновление с версий 1.9.0 и ниже

---

## [1.9.1] - 2025-01-02

### Fixed
- **ROOT Initialization**: Исправлена проблема с определением ROOT доступа при запуске
- **Connection Flow**: ROOT права проверяются перед подключением к серверу

---

## [1.9.0] - 2025-01-01

### Added
- **ROOT-only Mode**: Полное управление устройством через ROOT права
- **Enhanced Control**: Улучшенное управление эмуляторами

### Known Issues
- ROOT определяется неправильно (исправлено в v1.9.1)

---

## [1.7.0] - 2024-12-24

### Added
- **Enterprise Diagnostics**: Hello message теперь включает `has_accessibility`, `has_root`, `screen_width`, `screen_height`, `is_streaming`
- **Real-time Status Updates**: Heartbeat отправляет актуальные статусы accessibility и streaming
- **Command ACK**: Все команды теперь возвращают результат выполнения viewer'у

### Changed
- Улучшена диагностика input: viewer видит ошибки если Accessibility Service не включён
- Обновлена структура сообщений для enterprise управления

### Fixed
- Корректная передача статуса стрима при reconnect

---

## [1.6.0] - 2024-12-23

### Added
- Accessibility Service для non-root управления
- Поддержка tap/swipe/longpress через Accessibility API
- Global actions: Home, Back, Recent через Accessibility
- Start/Stop stream по команде от viewer

### Changed
- CommandExecutor теперь предпочитает Accessibility над shell input
- Улучшена совместимость с non-root устройствами

---

## [1.5.0] - 2024-12-22

### Added
- MediaProjection screen capture
- JPEG frame streaming через WebSocket
- Quality и FPS настройки стрима
- Remote Config загрузка с GitHub

### Changed
- Переход на binary frames (без base64)
- Оптимизация памяти при захвате экрана

---

## [1.0.0] - 2024-12-20

### Added
- Первоначальный релиз
- WebSocket подключение к серверу
- Базовые команды управления
- Foreground Service для работы в фоне
