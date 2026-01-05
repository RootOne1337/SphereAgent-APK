# 🚀 SpherePC Agent - Быстрый Старт

## ⚡ За 5 минут до запуска!

### Шаг 1: Скачай код

```cmd
git clone https://github.com/RootOne1337/SphereAgent-APK.git
cd SphereAgent-APK
git checkout feature/windows-pc-agent
cd windows-pc-agent
```

**Или скачай ZIP:**
https://github.com/RootOne1337/SphereAgent-APK/archive/refs/heads/feature/windows-pc-agent.zip

---

### Шаг 2: Установи Python

**Скачай:** https://www.python.org/downloads/

✅ **Важно:** Поставь галочку "Add Python to PATH"!

---

### Шаг 3: Собери .exe

```cmd
BUILD_FINAL.bat
```

**Ждём 2-5 минут...**

Результат: `dist\SpherePC-Agent.exe` (~20 MB)

---

### Шаг 4: Запусти!

```cmd
dist\SpherePC-Agent.exe
```

Или просто **двойной клик** на exe файл!

---

### Шаг 5: Проверь в браузере

Открой: **https://adb.leetpc.com/remote-pcs**

Твой ПК появится автоматически! 🎉

---

## 📚 Подробная документация

**Полный гайд:** [README_FULL_GUIDE.md](README_FULL_GUIDE.md)

**Содержит:**
- 3 способа скачивания
- Ручная сборка
- Portable версия
- Все 35+ команд с примерами
- Troubleshooting
- Конфигурация

---

## ❓ Проблемы?

### Python не найден
```cmd
# Переустанови Python с галочкой "Add to PATH"
https://www.python.org/downloads/
```

### BUILD_FINAL.bat не работает
```cmd
# Установи вручную
pip install pyinstaller websockets psutil pyyaml aiohttp
python build_single_exe.py
```

### ПК не появляется на сайте
1. Проверь что агент запущен (консоль открыта)
2. Обнови страницу (Ctrl+F5)
3. Проверь firewall/антивирус

---

## 🎯 Возможности

- ✅ LDPlayer управление (10 команд)
- ✅ Скрипты: batch, PS, Python (4 команды)
- ✅ Файлы: чтение, запись, список (7 команд)
- ✅ Процессы: список, kill, start (3 команды)
- ✅ Сеть: ping, download (3 команды)
- ✅ Shell команды
- ✅ 4 fallback сервера
- ✅ Auto-reconnect
- ✅ БЕЗ токенов!

**Итого: 35+ команд управления!**

---

## 🔗 Ссылки

- **GitHub:** https://github.com/RootOne1337/SphereAgent-APK/tree/feature/windows-pc-agent/windows-pc-agent
- **Pull Request:** https://github.com/RootOne1337/SphereAgent-APK/pull/4
- **Веб:** https://adb.leetpc.com/remote-pcs

---

**Готово! Управляй ПК через браузер!** 🚀
