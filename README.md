# SphereAgent - Android Remote Control Agent

Enterprise-grade Android agent для удалённого управления устройствами

![Android](https://img.shields.io/badge/Android-26+-green)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-purple)
![Compose](https://img.shields.io/badge/Jetpack_Compose-2024.04-blue)
![Version](https://img.shields.io/badge/Version-1.0.6-orange)

## 🆕 Что нового в v1.0.6 (Enhanced Discovery)

- **🌐 Dual Tunnel Support** - Поддержка обоих туннелей (`sphere-api` и `sphere-web`) в fallback списке
- **🛡️ Discovery Logic** - Улучшенный алгоритм выбора сервера (приоритет публичных туннелей при отсутствии локальной сети)
- **🐛 Bug Fixes** - Исправлено отображение внутренних Docker IP адресов (172.16.x.x) как основных

## 🆕 Что нового в v1.0.5 (Global Access)

- **🌍 Global Tunnel Access** - Гарантированный доступ из любой точки мира через `sphere-api.ru.tuna.am`
- **🔄 Smart Fallback** - Автоматическое переключение между локальным IP и публичным туннелем
- **🛡️ Connection Reliability** - Улучшенная логика переподключения при смене сети
- **🔍 Zero-Config** - Полностью автоматическая настройка при первом запуске

## 🆕 Что нового в v1.0.4 (Zero-Config)

- **🔍 Zero-Config Auto-Discovery** - Автоматический поиск сервера в локальной сети (mDNS/NSD)
- **🌐 Network Scanning** - Сканирование подсети для поиска SphereADB сервера
- **🔄 Smart Fallback** - Remote Config → mDNS → Network Scan → Hardcoded URLs
- **🚇 Tuna Tunnel Support** - Поддержка работы через публичные туннели (sphere-web.ru.tuna.am)
- **🛠 Dependency Injection** - Новый NetworkModule для чистого кода

## 📱 Возможности

- **📺 Real-time Screen Streaming** - Трансляция экрана через WebSocket (JPEG binary frames)
- **🎮 Remote Control** - Tap, Swipe, Key events, Shell commands
- **🔄 Auto-Reconnect** - Автоматическое переподключение с exponential backoff
- **🌐 Fallback Servers** - Поддержка нескольких серверов для отказоустойчивости
- **⚙️ Remote Config** - Динамическая конфигурация с сервера
- **🔒 Secure Connection** - WSS/HTTPS с аутентификацией

## 🛠 Технологии

| Компонент | Технология |
|-----------|------------|
| Language | Kotlin 2.0.21 |
| UI | Jetpack Compose (Material 3) |
| DI | Hilt 2.52 |
| Network | OkHttp 4.12 + WebSocket |
| Persistence | DataStore 1.1.1 |
| Serialization | Kotlinx Serialization |
| Screen Capture | MediaProjection API |
| Input | Accessibility Service / Shell |

## 📦 Структура проекта

```
app/src/main/java/com/sphere/agent/
├── SphereAgentApp.kt          # Application class
├── MainActivity.kt            # Main Activity
├── core/
│   └── AgentConfig.kt         # Remote config management
├── data/
│   └── SettingsRepository.kt  # DataStore repository
├── di/
│   └── AppModule.kt           # Hilt modules
├── network/
│   └── ConnectionManager.kt   # WebSocket connection
├── service/
│   ├── ScreenCaptureService.kt    # Foreground service
│   ├── CommandExecutor.kt         # Shell commands
│   └── SphereAccessibilityService.kt  # Non-root input
└── ui/
    ├── screens/
    │   └── MainScreen.kt      # Main UI
    ├── theme/
    │   ├── Theme.kt           # Material 3 theme
    │   └── Typography.kt      # Typography
    └── viewmodel/
        └── MainViewModel.kt   # State management
```

## 🚀 Сборка

### Требования

- Android Studio Ladybug (2024.2+)
- JDK 17+
- Android SDK 35

### Команды

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# APK location
app/build/outputs/apk/release/sphere-agent-release.apk
```

## 📡 WebSocket Protocol

### Подключение

```
wss://server.com/api/v1/agent/ws/{device_token}
```

### Сообщения от агента

```json
{
  "type": "hello",
  "device_id": "uuid",
  "device_name": "Samsung Galaxy S24",
  "device_model": "SM-S921B",
  "android_version": "14",
  "agent_version": "1.0.0",
  "capabilities": ["screen_capture", "touch", "swipe", "key_event", "shell"]
}
```

### Команды от сервера

```json
{ "type": "tap", "command_id": "cmd-1", "x": 500, "y": 800 }
{ "type": "swipe", "command_id": "cmd-2", "x": 500, "y": 1200, "x2": 500, "y2": 400, "duration": 300 }
{ "type": "key", "command_id": "cmd-3", "keyCode": 4 }
{ "type": "shell", "command_id": "cmd-4", "command": "pm list packages" }
{ "type": "home" }
{ "type": "back" }
```

### Binary Frames

Экран передаётся как binary WebSocket frames (JPEG data) - без base64!

## 🔐 Permissions

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
```

## 📊 Performance

| Метрика | Значение |
|---------|----------|
| FPS | 15-30 |
| Latency | 50-150ms |
| Bandwidth | 0.5-2 Mbps |
| CPU Usage | 5-15% |
| Memory | ~50 MB |

## 📋 Поддерживаемые команды

| Команда | Параметры | Описание |
|---------|-----------|----------|
| `tap` | `x`, `y` | Тап по координатам |
| `swipe` | `x1`, `y1`, `x2`, `y2`, `duration` | Свайп |
| `long_press` | `x`, `y`, `duration` | Долгое нажатие |
| `key` | `keycode` | Нажатие клавиши |
| `text` | `text` | Ввод текста |
| `shell` | `command` | Shell команда |
| `home` | - | Кнопка Home |
| `back` | - | Кнопка Back |
| `recent` | - | Recent Apps |

---

**SphereAgent** - часть экосистемы **SphereADB** для управления Android устройствами.
