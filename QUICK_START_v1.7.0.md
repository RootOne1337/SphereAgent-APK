# 🚀 SphereAgent v1.7.0 - Quick Start

## Что нового в v1.7.0

### 1. 🔄 Автообновление APK (каждые 6 часов)
- ✅ Загружаете новый APK на сервер → все 500 устройств получают обновление
- ✅ Notification с кнопкой "Install" появляется автоматически
- ✅ Нет ручной работы!

### 2. 🌐 Веб-контроль Accessibility Service
- ✅ Кнопка "Open Settings" прямо в веб-интерфейсе на странице стрима
- ✅ Клик → на устройстве открываются настройки Android
- ✅ Пользователь включает → tap/swipe/home кнопки работают

### 3. 🚀 Автозапуск при загрузке
- ✅ Эмулятор перезагрузился → агент сам стартует и подключается
- ✅ Работает без root
- ✅ Нет ручного запуска!

---

## Установка обновления (2 команды)

```bash
# 1. Скопируйте APK
cp /home/rootone/SphereAgent-APK-Repo/app/build/outputs/apk/debug/app-debug.apk \
   /home/rootone/SphereADB/backend/updates/sphere-agent-latest.apk

# 2. Обновите версию
echo '{"version":"1.7.0","latest_version":"1.7.0","min_version":"1.0.0","filename":"sphere-agent-latest.apk","size_bytes":24117248,"changelog":"Auto-update, web controls, boot auto-start","force_update":false}' \
  > /home/rootone/SphereADB/backend/updates/update_info.json

# ✅ Готово! Ждите до 6 часов - все устройства получат notification
```

---

## Тест веб-контролов Accessibility

1. Откройте: https://adb.leetpc.com/remote-fleet
2. Выберите устройство → стрим откроется
3. Справа Settings (⚙️) → Device Info → Accessibility
4. Если "✗ Disabled" → кнопка "Open Settings" → клик
5. На устройстве откроются настройки Android → включите SphereAgent
6. Через 2 секунды статус станет "✓ Enabled"
7. Tap/Swipe/Home кнопки заработают! ✅

---

## Проверка автообновления

```bash
# Проверьте endpoint работает
curl https://adb.leetpc.com/api/v1/agent/updates/version

# Проверьте версии всех устройств
curl -s https://adb.leetpc.com/api/v1/agent/agents | \
  jq '.[] | {device: .device_model, version: .agent_version}'
```

---

## Файлы

- **APK (23 MB):** `/home/rootone/SphereAgent-APK-Repo/app/build/outputs/apk/debug/app-debug.apk`
- **Backend APK:** `/home/rootone/SphereADB/backend/updates/sphere-agent-latest.apk`
- **Версия:** `/home/rootone/SphereADB/backend/updates/update_info.json`
- **Полная документация:** `/home/rootone/SphereAgent-APK-Repo/DEPLOYMENT_v1.7.0.md`

---

**Проблемы?** Смотри [DEPLOYMENT_v1.7.0.md](./DEPLOYMENT_v1.7.0.md) (раздел Troubleshooting)
