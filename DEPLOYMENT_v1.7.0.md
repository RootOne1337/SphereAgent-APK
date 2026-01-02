# SphereAgent v1.7.0 - Fleet Management Update

## 🎯 Новые возможности

### 1. ✅ Веб-контроль Accessibility Service
**Проблема:** Пользователь должен вручную включать Accessibility на каждом устройстве  
**Решение:**
- ⚠️ Индикатор статуса Accessibility на странице стрима (красный/зелёный)
- 🔘 Кнопка "Open Settings" прямо в веб-интерфейсе
- 🌐 Backend endpoint: `POST /api/v1/agent/agents/{agent_id}/open-accessibility`
- 📱 Команда автоматически открывает Android settings на устройстве

**Где посмотреть:**
- Откройте https://adb.leetpc.com/remote-fleet
- Выберите любое устройство (стрим откроется)
- Справа в сайдбаре "Settings" → Device Info → Accessibility
- Если "✗ Disabled" - появится жёлтая кнопка "Open Settings"
- Клик → на устройстве откроются настройки

---

### 2. 🔄 Автоматическое обновление APK
**Проблема:** Обновлять вручную 500 устройств невозможно  
**Решение:**
- ⏱️ Каждые 6 часов APK проверяет `/api/v1/agent/updates/version`
- 📥 Автоматически скачивает новую версию в background
- 🔔 Показывает notification "Update Available v1.X.X"
- 📲 Клик на notification → запускается установщик Android

**Технические детали:**
- UpdateManager запускается в ScreenCaptureService
- Первая проверка через 1 минуту после старта
- Затем каждые 6 часов автоматически
- Сохраняет APK в `externalCacheDir/sphere_agent_{version}.apk`
- Использует FileProvider для безопасной установки

**Backend endpoint:**
```bash
curl https://adb.leetpc.com/api/v1/agent/updates/version
```
Ответ:
```json
{
  "version": "1.7.0",
  "url": "/api/v1/agent/updates/latest.apk",
  "changelog": "Auto-update system, web controls, boot auto-start",
  "force_update": false,
  "min_version": "1.0.0"
}
```

---

### 3. 🚀 Автозапуск при загрузке эмулятора
**Проблема:** После перезагрузки нужно вручную запускать приложение  
**Решение:**
- 📱 BootReceiver слушает `ACTION_BOOT_COMPLETED`
- ✅ Автоматически запускает AgentService
- 🔗 Устройство само подключается к серверу
- 💪 Работает на всех устройствах без root

**Технические детали:**
- Permission: `RECEIVE_BOOT_COMPLETED` (уже в манифесте)
- Receiver: `com.sphere.agent.receiver.BootReceiver` (уже реализован)
- Проверяет наличие server_url перед стартом
- Запускает foreground service через `startForegroundService()`

**⚠️ Важно:** MediaProjection permission может сброситься после перезагрузки Android.  
В этом случае пользователю нужно один раз вручную открыть приложение и разрешить.

---

## 📦 Установка обновления

### Вариант 1: Автоматическое обновление (рекомендуется)
1. Загрузите новый APK на сервер:
   ```bash
   scp app-debug.apk root@adb.leetpc.com:/var/www/sphere/backend/updates/sphere-agent-latest.apk
   ```

2. Обновите `update_info.json`:
   ```bash
   cat > /var/www/sphere/backend/updates/update_info.json <<EOF
   {
     "version": "1.7.0",
     "latest_version": "1.7.0",
     "min_version": "1.0.0",
     "filename": "sphere-agent-latest.apk",
     "size_bytes": 24117248,
     "changelog": "Auto-update, web controls, boot auto-start",
     "force_update": false
   }
   EOF
   ```

3. Ждите до 6 часов. Все устройства с v1.6.0 получат notification об обновлении.

4. Пользователи кликнут → установят → готово!

---

### Вариант 2: Ручная установка (для тестирования)
```bash
# На одном устройстве для проверки
adb install -r /home/rootone/SphereAgent-APK-Repo/app/build/outputs/apk/debug/app-debug.apk
```

---

## 🧪 Тестирование

### 1. Проверка веб-контролов Accessibility
```bash
# 1. Откройте стрим устройства
https://adb.leetpc.com/remote-fleet

# 2. Выберите устройство → Settings (шестерёнка)
# 3. Найдите "Accessibility: ✗ Disabled"
# 4. Кликните "Open Settings"
# 5. Проверьте на устройстве - должны открыться настройки
```

### 2. Проверка автообновления (ускоренная)
```kotlin
// В UpdateManager.kt измените:
private const val CHECK_INTERVAL_MS = 60_000L // 1 минута вместо 6 часов

// Пересоберите APK
// Установите на тестовое устройство
// Ждите 1-2 минуты
// Notification появится если версия на сервере новее
```

### 3. Проверка автозапуска
```bash
# Перезагрузите устройство
adb reboot

# Ждите 30-60 секунд после загрузки
# Проверьте подключение
curl https://adb.leetpc.com/api/v1/agent/agents | jq '.[] | select(.device_model=="G576D")'

# Должен показать: "status": "online"
```

---

## 📊 Мониторинг флота

### Проверка версий на всех устройствах
```bash
curl -s https://adb.leetpc.com/api/v1/agent/agents | \
  jq '.[] | {device: .device_model, version: .agent_version, status: .status}'
```

Пример вывода:
```json
{
  "device": "G576D",
  "version": "1.7.0",
  "status": "online"
}
{
  "device": "SM-G920F",
  "version": "1.6.0",
  "status": "online"
}
```

### Форсированное обновление всех устройств
```bash
# В update_info.json установите:
"force_update": true

# UpdateManager покажет HIGH priority notification
# Устройства будут требовать обновление при каждой проверке
```

---

## 🔧 Troubleshooting

### Обновление не приходит
1. **Проверьте server_url в APK:**
   ```kotlin
   // BuildConfig.DEFAULT_SERVER_URL должен быть "https://adb.leetpc.com"
   ```

2. **Проверьте доступность endpoint:**
   ```bash
   curl https://adb.leetpc.com/api/v1/agent/updates/version
   ```

3. **Проверьте логи устройства:**
   ```bash
   adb logcat | grep UpdateManager
   # Должны видеть: "Checking updates from: https://..."
   # Если версия новее: "Update available: 1.7.0 (current: 1.6.0)"
   ```

### Кнопка Accessibility не работает
1. **Проверьте backend endpoint:**
   ```bash
   curl -X POST https://adb.leetpc.com/api/v1/agent/agents/{agent_id}/open-accessibility
   ```

2. **Проверьте shell команда выполняется:**
   ```bash
   adb shell am start -a android.settings.ACCESSIBILITY_SETTINGS
   # Должны открыться настройки
   ```

### Автозапуск не работает после перезагрузки
1. **Проверьте permission в манифесте:**
   ```xml
   <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
   ```

2. **Проверьте BootReceiver зарегистрирован:**
   ```xml
   <receiver android:name=".receiver.BootReceiver" android:exported="true">
       <intent-filter>
           <action android:name="android.intent.action.BOOT_COMPLETED" />
       </intent-filter>
   </receiver>
   ```

3. **Проверьте логи после загрузки:**
   ```bash
   adb logcat | grep BootReceiver
   # Должны видеть: "Device boot completed - starting SphereAgent service"
   ```

---

## 📈 Roadmap (Future)

### v1.8.0 (планируется)
- [ ] **Silent Install**: Установка без участия пользователя (требует root или system app)
- [ ] **WebRTC Stream**: Замена WebSocket на WebRTC для меньшей задержки
- [ ] **Multi-touch**: Поддержка жестов двумя пальцами (pinch-zoom, rotate)
- [ ] **File Transfer**: Отправка/получение файлов через веб-интерфейс

### v2.0.0 (долгосрок)
- [ ] **Screen Recording**: Запись видео с устройства и сохранение на сервере
- [ ] **Automation Scripts**: Запуск скриптов (тапы, свайпы, delays) из веб-UI
- [ ] **Analytics Dashboard**: Статистика использования, heat maps, session replays
- [ ] **Multi-language**: Интерфейс на русском/английском/китайском

---

## 📝 Changelog

### v1.7.0 (2025-01-02)
- ✅ **Auto-update**: Каждые 6 часов проверка + notification + установка
- ✅ **Web Accessibility Controls**: Кнопка "Open Settings" на странице стрима
- ✅ **Boot Auto-start**: Автозапуск при загрузке устройства
- ✅ **Backend endpoint**: `POST /agents/{id}/open-accessibility`
- ✅ **Backend endpoint**: `GET /updates/version` для UpdateManager
- 🐛 Fixed: Viewer WebSocket close error (finally block exception)

### v1.6.0 (2024-12-24)
- Enterprise stream/control
- Accessibility Service для non-root tap/swipe
- FPS control, Quality settings
- Device info diagnostics

---

## 👨‍💻 Deploy команды (копипаста)

```bash
# 1. Соберите APK
cd /home/rootone/SphereAgent-APK-Repo
./gradlew assembleDebug --no-daemon

# 2. Скопируйте на сервер
cp app/build/outputs/apk/debug/app-debug.apk \
   /home/rootone/SphereADB/backend/updates/sphere-agent-latest.apk

# 3. Обновите версию в update_info.json
cat > /home/rootone/SphereADB/backend/updates/update_info.json <<'EOF'
{
  "version": "1.7.0",
  "latest_version": "1.7.0",
  "min_version": "1.0.0",
  "filename": "sphere-agent-latest.apk",
  "size_bytes": 24117248,
  "changelog": "Auto-update, web controls, boot auto-start",
  "force_update": false
}
EOF

# 4. Перезапустите backend (если нужно)
cd /home/rootone/SphereADB
docker-compose restart backend

# 5. Проверьте
curl https://adb.leetpc.com/api/v1/agent/updates/version

# ✅ Готово! Все 500 устройств получат обновление в течение 6 часов
```

---

**Вопросы?** Пишите в Telegram: @RootOne1337
