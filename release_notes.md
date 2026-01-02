# SphereAgent v1.7.0 - Fleet Management Update 🚀

## 🎯 Ключевые возможности для управления флотом из 500+ устройств

### 🔄 Автоматическое обновление APK
- ⏱️ Проверка каждые 6 часов без вмешательства пользователя
- 📥 Фоновая загрузка новых версий
- 🔔 Notification с кнопкой "Install"
- 📲 Один клик → установка обновления
- **Больше НЕ НУЖНО обновлять 500 устройств вручную!**

### 🌐 Веб-контроль Accessibility Service
- ⚠️ Индикатор статуса (✓ Enabled / ✗ Disabled) в веб-UI
- 🔘 Кнопка "Open Settings" прямо в стрим-интерфейсе
- 📱 Удалённо открывает Android настройки на устройстве
- ⚡ Shell команда: `am start -a android.settings.ACCESSIBILITY_SETTINGS`

### 🚀 Автозапуск при загрузке
- 📱 Эмулятор перезагрузился → агент стартует автоматически
- 🔗 Автоматическое подключение к серверу
- 💪 Работает без root на всех устройствах
- ⚙️ BootReceiver слушает `BOOT_COMPLETED`

---

## 📦 Что в релизе

- **APK размер:** 23 MB (debug build)
- **Min Android:** 7.0 (API 24)
- **Target Android:** 14+ (API 35)
- **Version Code:** 12

---

## 🔧 Технические детали

### UpdateManager
- Первая проверка через 1 минуту после старта
- Периодическая проверка каждые 6 часов
- Endpoint: `GET /api/v1/agent/updates/version`
- Сохранение APK: `externalCacheDir/sphere_agent_{version}.apk`
- FileProvider для безопасной установки

### Web Controls
- Backend endpoint: `POST /api/v1/agent/agents/{id}/open-accessibility`
- UI предупреждение в APK при выключенном Accessibility
- Polling статуса каждые 2 секунды в MainViewModel
- AccessibilityWarningCard в MainScreen

### Boot Auto-Start
- Permission: `RECEIVE_BOOT_COMPLETED`
- BootReceiver: автозапуск AgentService
- Поддержка `QUICKBOOT_POWERON`
- Foreground service для надёжности

---

## 📊 Deployment для 500+ устройств

### Быстрая установка (2 команды)
```bash
# 1. Скопируйте APK на сервер
cp app-debug.apk /path/to/backend/updates/sphere-agent-latest.apk

# 2. Обновите update_info.json
echo '{"version":"1.7.0",...}' > /path/to/backend/updates/update_info.json

# ✅ Готово! Все устройства получат обновление в течение 6 часов
```

### Мониторинг флота
```bash
# Проверка версий всех устройств
curl https://adb.leetpc.com/api/v1/agent/agents | jq '.[] | {device: .device_model, version: .agent_version}'

# Проверка endpoint работает
curl https://adb.leetpc.com/api/v1/agent/updates/version
```

---

## 📝 Документация

- **[DEPLOYMENT_v1.7.0.md](https://github.com/RootOne1337/SphereAgent-APK/blob/main/DEPLOYMENT_v1.7.0.md)** - полная инструкция развёртывания
- **[QUICK_START_v1.7.0.md](https://github.com/RootOne1337/SphereAgent-APK/blob/main/QUICK_START_v1.7.0.md)** - краткая инструкция (2 команды)
- **README.md** - обновлён с Fleet Management секцией

---

## 🐛 Bug Fixes

- Исправлен viewer WebSocket close error (finally block exception)
- Добавлена защита от краша enableEdgeToEdge на Android 9
- Улучшена обработка ошибок в observeEffects

---

## 🔮 Roadmap

### v1.8.0
- Silent Install (требует root или system app)
- WebRTC Stream (меньше задержки чем WebSocket)
- Multi-touch support (pinch-zoom, rotate)
- File Transfer через веб-UI

### v2.0.0
- Screen Recording + сохранение на сервере
- Automation Scripts (тапы, свайпы, delays)
- Analytics Dashboard с heat maps
- Multi-language UI

---

## 💬 Поддержка

- **Issues:** https://github.com/RootOne1337/SphereAgent-APK/issues
- **Telegram:** @RootOne1337
- **Docs:** https://github.com/RootOne1337/SphereADB/tree/main/docs

---

**Совместимость:** Backend v0.17.7+ | Frontend v2.0.0+ | APK v1.6.0+ (backward compatible)
