# 🖥️ SpherePC Agent v2.0.0 - Полный Гайд

## 📋 Содержание
1. [Что это?](#что-это)
2. [Быстрый старт](#быстрый-старт)
3. [Скачивание с GitHub](#скачивание-с-github)
4. [Компиляция на Windows](#компиляция-на-windows)
5. [Использование](#использование)
6. [Возможности](#возможности)
7. [Troubleshooting](#troubleshooting)

---

## 🎯 Что это?

**SpherePC Agent** - агент для удалённого управления Windows ПК через веб-интерфейс.

### Основные фичи:
- ✅ **БЕЗ ТОКЕНОВ!** Автоматическая регистрация
- ✅ **35+ команд** управления
- ✅ **4 fallback сервера** с auto-reconnect
- ✅ **LDPlayer** полное управление
- ✅ **Скрипты**: batch, PowerShell, Python
- ✅ **Файлы**: чтение, запись, удаление
- ✅ **Процессы**: список, kill, запуск
- ✅ **Сеть**: ping, download

### Веб-интерфейс:
```
https://adb.leetpc.com/remote-pcs
```

---

## 🚀 Быстрый старт

### Вариант 1: Готовый .exe (когда появится на GitHub)

```cmd
1. Скачай SpherePC-Agent.exe из Releases
2. Запусти двойным кликом
3. Открой https://adb.leetpc.com/remote-pcs
4. ПК появится автоматически!
```

### Вариант 2: Собрать самому (текущий способ)

```cmd
1. git clone https://github.com/RootOne1337/SphereAgent-APK.git
2. cd SphereAgent-APK\windows-pc-agent
3. BUILD_FINAL.bat
4. Запусти dist\SpherePC-Agent.exe
```

---

## 📥 Скачивание с GitHub

### Метод 1: Через Git (рекомендуется)

**Установи Git:**
- Скачай: https://git-scm.com/download/win
- Установи с настройками по умолчанию

**Клонируй репозиторий:**
```cmd
# Открой PowerShell или CMD

# Перейди в удобную папку
cd C:\Users\YourName\Desktop

# Клонируй
git clone https://github.com/RootOne1337/SphereAgent-APK.git

# Переключись на ветку с PC Agent
cd SphereAgent-APK
git checkout feature/windows-pc-agent

# Перейди в папку с агентом
cd windows-pc-agent
```

### Метод 2: Скачать ZIP

1. Открой: https://github.com/RootOne1337/SphereAgent-APK
2. Кнопка **Code** → **Download ZIP**
3. Распакуй архив
4. Открой папку `windows-pc-agent`

### Метод 3: Прямая ссылка на файлы

**Ветка:** `feature/windows-pc-agent`

**Папка:** `windows-pc-agent/`

**Ссылка:**
```
https://github.com/RootOne1337/SphereAgent-APK/tree/feature/windows-pc-agent/windows-pc-agent
```

---

## 🔨 Компиляция на Windows

### Шаг 1: Установка Python

**Скачай Python 3.10 или новее:**
- https://www.python.org/downloads/

**При установке:**
- ✅ Поставь галочку **"Add Python to PATH"**
- Выбери **"Install Now"**

**Проверь установку:**
```cmd
python --version
# Должно показать: Python 3.10.x или выше
```

### Шаг 2: Автоматическая сборка (РЕКОМЕНДУЕТСЯ)

**Запусти:**
```cmd
BUILD_FINAL.bat
```

**Что происходит:**
1. Проверка Python
2. Установка PyInstaller
3. Установка зависимостей (websockets, psutil, yaml, aiohttp)
4. Сборка .exe (~2-5 минут)
5. Результат: `dist\SpherePC-Agent.exe`

**Ожидаемый вывод:**
```
========================================
  SpherePC Agent - Final Build
========================================

[OK] Python found
[INFO] Installing PyInstaller...
[OK] PyInstaller installed
[INFO] Building SpherePC-Agent.exe...

This may take 2-5 minutes...

========================================
  ✅ BUILD SUCCESSFUL!
========================================

📦 Result:
   dist\SpherePC-Agent.exe (~20 MB)
```

### Шаг 3: Ручная сборка (альтернатива)

**Если BUILD_FINAL.bat не работает:**

```cmd
# 1. Установи зависимости
pip install pyinstaller websockets psutil pyyaml aiohttp

# 2. Собери
python build_single_exe.py

# 3. Результат:
# dist\SpherePC-Agent.exe
```

### Шаг 4: Запуск без компиляции (portable)

**Если не хочешь создавать .exe:**

```cmd
# 1. Установи зависимости
pip install -r requirements.txt

# 2. Запусти напрямую
python main.py

# Или используй portable launcher
SpherePC-Agent-PORTABLE.bat
```

---

## 💡 Использование

### Запуск агента

**Вариант 1: Из .exe**
```cmd
# Просто двойной клик на:
dist\SpherePC-Agent.exe

# Или из командной строки:
cd dist
SpherePC-Agent.exe
```

**Вариант 2: Из исходников**
```cmd
cd windows-pc-agent
python main.py
```

### Что увидишь

**Консоль откроется с логами:**
```
SpherePC Agent v2.0.0
======================
Загрузка конфигурации...
✓ Hardware ID: abc123...
✓ Имя ПК: MY-DESKTOP
✓ LDPlayer найден: C:\LDPlayer9

Подключение к серверу...
✓ Подключено к: wss://adb.leetpc.com/api/v1/pc/ws
✓ Зарегистрирован как: MY-DESKTOP
✓ Heartbeat запущен (30 сек)

Агент работает! ПК доступен на:
https://adb.leetpc.com/remote-pcs
```

### Проверка в веб-интерфейсе

1. Открой браузер
2. Перейди: https://adb.leetpc.com/remote-pcs
3. Найди свой ПК в списке (статус: **Online** 🟢)
4. Нажми на ПК → увидишь:
   - CPU/RAM использование
   - Список LDPlayer эмуляторов
   - Кнопки управления

---

## 🎮 Возможности

### 1. LDPlayer Управление (10 команд)

**Через веб-интерфейс:**
- 📋 Список эмуляторов
- ▶️ Запуск эмулятора
- ⏹️ Остановка эмулятора
- ➕ Создание нового
- 📋 Клонирование
- ⚙️ Изменение настроек (CPU, RAM, DPI)
- 🗑️ Удаление
- 📦 Установка APK
- ✅ Проверка статуса

**Пример через API:**
```javascript
POST https://adb.leetpc.com/api/v1/pc/{pc_id}/command
{
  "command": "ldplayer_list",
  "params": {}
}
```

### 2. Выполнение Скриптов (4 команды)

**Batch:**
```javascript
{
  "command": "exec_batch",
  "params": {
    "content": "@echo off\necho Hello World\ndir C:\\"
  }
}
```

**PowerShell:**
```javascript
{
  "command": "exec_powershell",
  "params": {
    "content": "Get-Process | Where-Object {$_.CPU -gt 10}"
  }
}
```

**Python:**
```javascript
{
  "command": "exec_python",
  "params": {
    "content": "import os; print(os.listdir('.'))"
  }
}
```

### 3. Файловые Операции (7 команд)

**Чтение:**
```javascript
{
  "command": "file_read",
  "params": {"path": "C:\\config.txt"}
}
```

**Запись:**
```javascript
{
  "command": "file_write",
  "params": {
    "path": "C:\\test.txt",
    "content": "Hello World!"
  }
}
```

**Список файлов:**
```javascript
{
  "command": "file_list",
  "params": {
    "path": "C:\\Users",
    "recursive": true
  }
}
```

**Другие команды:**
- `file_delete` - удаление
- `file_exists` - проверка существования
- `file_move` - перемещение
- `file_copy` - копирование

### 4. Управление Процессами (3 команды)

**Список процессов:**
```javascript
{
  "command": "process_list",
  "params": {}
}
```

**Убить процесс:**
```javascript
{
  "command": "process_kill",
  "params": {"name": "notepad.exe"}
}
```

**Запустить программу:**
```javascript
{
  "command": "process_start",
  "params": {
    "command": "C:\\Program Files\\App\\app.exe",
    "args": ["--param", "value"]
  }
}
```

### 5. Сетевые Команды (3 команды)

**Ping:**
```javascript
{
  "command": "net_ping",
  "params": {
    "host": "google.com",
    "count": 4
  }
}
```

**Скачать файл:**
```javascript
{
  "command": "net_download",
  "params": {
    "url": "https://example.com/file.zip",
    "path": "C:\\downloads\\file.zip"
  }
}
```

### 6. Shell Команды

**Любая команда CMD:**
```javascript
{
  "command": "shell_exec",
  "params": {
    "command": "ipconfig /all"
  }
}
```

**Безопасность:**
- Опасные команды блокируются: `rm -rf`, `format`, `del /f`, `shutdown`
- Настраивается через `config.yaml`

---

## 🔧 Конфигурация

**Файл:** `config.yaml`

### Основные настройки:

```yaml
server:
  url: "https://adb.leetpc.com"
  websocket_path: "/api/v1/pc/ws"
  
  # Fallback серверы (автопереключение!)
  fallback_urls:
    - "https://sphereadb-api-v2.ru.tuna.am"
    - "https://backup1.leetpc.com"
    - "https://backup2.leetpc.com"

pc:
  name: ""  # Автоматически = имя компьютера
  location: "Auto"

ldplayer:
  enabled: true
  path: ""  # Автопоиск
  auto_detect: true

connection:
  heartbeat_interval: 30
  connect_timeout: 30
  max_reconnect_delay: 60
  initial_reconnect_delay: 1

security:
  allow_shell: true
  shell_blacklist:  # Опасные команды
    - "rm -rf"
    - "format"
    - "del /f"
    - "shutdown"
```

### Изменить настройки:

```cmd
# Отредактируй config.yaml
notepad config.yaml

# Или создай свой
copy config.yaml my_config.yaml
notepad my_config.yaml

# Запусти с кастомным конфигом
python main.py --config my_config.yaml
```

---

## 🌐 Отказоустойчивость

### 4 Fallback Сервера

**Автоматическое переключение:**
```
Попытка 1: adb.leetpc.com
  ↓ (не отвечает)
Попытка 2: sphereadb-api-v2.ru.tuna.am
  ↓ (не отвечает)
Попытка 3: backup1.leetpc.com
  ↓ (подключено!)
✓ Работает через backup1.leetpc.com
```

**Циклическая ротация:**
- После 4-го сервера → возврат к 1-му
- Бесконечные попытки

### Auto-Reconnect

**Exponential Backoff:**
```
1 сек → 2 сек → 4 сек → 8 сек → 16 сек → 32 сек → 60 сек (max)
```

**Heartbeat:**
- Каждые **30 секунд**
- Отправка метрик (CPU, RAM, эмуляторы)

**WebSocket Ping/Pong:**
- Каждые **20 секунд**
- Обнаружение "мёртвых" соединений

---

## ❓ Troubleshooting

### Проблема: "Python not found"

**Решение:**
```cmd
# Установи Python с официального сайта
https://www.python.org/downloads/

# При установке поставь галочку:
☑ Add Python to PATH
```

### Проблема: "PyInstaller installation failed"

**Решение:**
```cmd
# Установи вручную
pip install --upgrade pip
pip install pyinstaller
```

### Проблема: "Module not found: websockets"

**Решение:**
```cmd
# Установи зависимости
pip install -r requirements.txt

# Или по отдельности
pip install websockets psutil pyyaml aiohttp
```

### Проблема: "LDPlayer not found"

**Решение:**
```yaml
# В config.yaml укажи путь вручную
ldplayer:
  enabled: true
  path: "C:\\LDPlayer\\LDPlayer9"
  auto_detect: false
```

### Проблема: "Connection failed"

**Проверь:**
1. Интернет соединение
2. Firewall (разрешить Python/агенту)
3. Антивирус (добавить в исключения)

**Логи:**
```cmd
# Посмотри логи
type logs\agent.log

# Или запусти с debug
python main.py --log-level DEBUG
```

### Проблема: "ПК не появляется на сайте"

**Решение:**
1. Проверь консоль агента - должно быть "Зарегистрирован"
2. Обнови страницу в браузере (Ctrl+F5)
3. Проверь hardware_id в логах
4. Проверь что агент подключён (статус в консоли)

---

## 📊 Системные требования

### Минимальные:
- **OS:** Windows 10 (64-bit)
- **RAM:** 2 GB
- **Disk:** 100 MB свободного места
- **Python:** 3.10+ (для сборки/запуска из исходников)
- **Internet:** Постоянное подключение

### Рекомендуемые:
- **OS:** Windows 10/11 (64-bit)
- **RAM:** 4 GB+
- **Disk:** 500 MB
- **Python:** 3.11+
- **Internet:** Стабильное подключение

---

## 📝 Структура проекта

```
windows-pc-agent/
├── main.py                  # Главный модуль
├── config.yaml              # Конфигурация
├── requirements.txt         # Python зависимости
├── BUILD_FINAL.bat          # Автосборка Windows
├── BUILD_NOW.sh             # Автосборка Linux
├── build_single_exe.py      # PyInstaller скрипт
├── SpherePC-Agent-PORTABLE.bat  # Portable launcher
│
├── agent/                   # Модули агента
│   ├── __init__.py
│   ├── config.py            # Загрузка конфига
│   ├── connection.py        # WebSocket с fallback
│   ├── heartbeat.py         # Heartbeat сервис
│   ├── pc_info.py           # Информация о ПК
│   └── hardware_id.py       # Уникальный ID
│
├── commands/                # Команды управления
│   ├── base.py              # Базовый класс
│   ├── ldplayer.py          # LDPlayer CLI
│   ├── shell.py             # Shell execution
│   ├── system.py            # Системные команды
│   └── advanced.py          # Скрипты, файлы, процессы
│
├── utils/                   # Утилиты
│   ├── hardware_id.py       # Hardware ID генератор
│   └── ldplayer_path.py     # Автопоиск LDPlayer
│
├── docs/                    # Документация
│   ├── READY_TO_USE.md
│   ├── SHOW_CAPABILITIES.txt
│   ├── BUILD_WINDOWS_EXE.md
│   └── README_FULL_GUIDE.md  # Этот файл
│
└── dist/                    # Собранные файлы
    └── SpherePC-Agent.exe   # (после сборки)
```

---

## 🔗 Полезные ссылки

- **GitHub Repo:** https://github.com/RootOne1337/SphereAgent-APK
- **Ветка PC Agent:** `feature/windows-pc-agent`
- **Pull Request:** https://github.com/RootOne1337/SphereAgent-APK/pull/4
- **Веб-интерфейс:** https://adb.leetpc.com/remote-pcs
- **Python:** https://www.python.org/downloads/
- **Git for Windows:** https://git-scm.com/download/win

---

## 🎯 Итого

### Что получаешь:
✅ Готовый агент для Windows
✅ 35+ команд управления
✅ Веб-интерфейс
✅ 4 fallback сервера
✅ Auto-reconnect
✅ БЕЗ токенов!

### Быстрая инструкция:
```cmd
1. git clone https://github.com/RootOne1337/SphereAgent-APK.git
2. cd SphereAgent-APK\windows-pc-agent
3. BUILD_FINAL.bat
4. dist\SpherePC-Agent.exe
5. https://adb.leetpc.com/remote-pcs
```

**Готово! Управляй ПК через браузер!** 🚀
