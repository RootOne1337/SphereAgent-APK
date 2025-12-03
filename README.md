# SphereAgent - Android Remote Control Agent

Enterprise-grade Android agent для удалённого управления устройствами

![Android](https://img.shields.io/badge/Android-26+-green)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-purple)
![Compose](https://img.shields.io/badge/Jetpack_Compose-2024.04-blue)
![Version](https://img.shields.io/badge/Version-1.1.0-orange)

## 🆕 Что нового в v1.1.0 (Critical Reliability) 🔴

**Stage 1: Критическая надёжность** - Агент теперь значительно надёжнее!

### ❤️ Heartbeat с телеметрией
- CPU usage (%)
- RAM usage (%, доступно MB)
- Battery level и статус зарядки
- Network type и сила сигнала
- Foreground app (активное приложение)
- Screen on/off
- Agent uptime
- Battery temperature

### 💀 Reaper (Жнец) на сервере
- 3 пропущенных heartbeat → статус OFFLINE
- 5 пропущенных heartbeat → disconnect
- Автоматическая очистка неактивных агентов

### 🔄 AlarmManager Watchdog
- Проверка каждые 5 минут
- Автоматический перезапуск сервиса при kill системой
- Работает в Doze mode

### 📦 Локальная очередь команд
- Команды не теряются при потере связи
- Персистентное сохранение на диск
- Автоматическая синхронизация при восстановлении
- Retry для failed команд (до 3 попыток)

---

## 🆕 Что нового в v1.0.6 (Enhanced Discovery)

- **🌐 Dual Tunnel Support** - Поддержка обоих туннелей (`sphere-api` и `sphere-web`) в fallback списке
- **🛡️ Discovery Logic** - Улучшенный алгоритм выбора сервера
- **🐛 Bug Fixes** - Исправлено отображение внутренних Docker IP адресов

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
│   ├── AgentConfig.kt         # Remote config management
│   └── DeviceMetrics.kt       # 🆕 Телеметрия устройства
├── data/
│   ├── SettingsRepository.kt  # DataStore repository
│   └── CommandQueue.kt        # 🆕 Очередь команд
├── di/
│   └── AppModule.kt           # Hilt modules
├── network/
│   └── ConnectionManager.kt   # WebSocket + Queue sync
├── receiver/
│   ├── BootReceiver.kt        # Auto-start
│   ├── NetworkReceiver.kt     # Network changes
│   └── WatchdogReceiver.kt    # 🆕 AlarmManager watchdog
├── service/
│   ├── ScreenCaptureService.kt    # Foreground service
│   ├── CommandExecutor.kt         # Shell commands
│   └── SphereAccessibilityService.kt  # Non-root input
└── ui/
    ├── screens/
    │   └── MainScreen.kt      # Main UI
    └── viewmodel/
        └── MainViewModel.kt   # State management
```

## 📥 Установка

1. Скачайте последнюю версию APK
2. Разрешите установку из неизвестных источников
3. Установите APK
4. Дайте разрешения (Accessibility, Notification, Screen Capture)
5. Агент автоматически подключится к серверу

## 🔧 Настройка

Агент автоматически находит сервер в следующем порядке:
1. Remote Config с GitHub
2. Публичный туннель `sphere-api.ru.tuna.am`
3. mDNS/NSD в локальной сети
4. Сканирование подсети
5. Hardcoded fallback URLs

## 📋 Changelog

| Версия | Дата | Изменения |
|--------|------|-----------|
| 1.1.0 | 2025-12-03 | 🔴 Stage 1: Heartbeat телеметрия, Reaper, Watchdog, CommandQueue |
| 1.0.6 | 2025-12-03 | Enhanced Discovery: dual tunnels |
| 1.0.5 | 2025-12-03 | Global Access: tunnel priority |
| 1.0.4 | 2025-12-02 | Zero-Config Auto-Discovery |
| 1.0.3 | 2025-12-02 | Release build (minified) |

## 📄 License

MIT License - см. основной репозиторий [SphereADB](https://github.com/RootOne1337/SphereADB)
