# 📊 PROJECT STATE - SphereAgent APK

**Last Updated:** 2026-01-28
**Version:** v3.0.1
**Status:** 🟢 Enterprise Production Ready

---

## 🚀 v3.0.1 - H.264 Stream Start Fix (2026-01-28)

### ✅ Исправления
- `start_stream` теперь запускает H.264 encoder напрямую
- Авто-запуск `ScreenCaptureService` перед стартом стрима
- Авто-запрос MediaProjection при отсутствии разрешения
- Убран JPEG fallback при `compression=h264`

---

## 🚀 v2.27.1 - ENTERPRISE STABILITY HARDENING (2026-01-26)

### ✅ Новые возможности
- **Jitter для watchdog/heartbeat/reconnect**: защита от синхронных пиков
- **Timeout для ROOT/Shell команд**: исключены зависания su/sh процессов
- **Lazy RootScreenCaptureService**: запуск только по команде `start_stream`
- **Default streaming profile**: 70% quality, 10 FPS (легковесный режим)

### 📋 Текущее состояние
- **Android Agent**: Enterprise-ready для ферм 1000+ устройств
- **Clone Detection**: Работает для LDPlayer, Memu, Nox
- **PC Agent**: Работает через TacticalRMM интеграцию
- **OTA**: Jitter для обновлений (MAX_JITTER_MS = 30 мин)
