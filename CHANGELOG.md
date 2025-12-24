# Changelog - SphereAgent APK

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
