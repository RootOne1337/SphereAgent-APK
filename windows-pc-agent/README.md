# SpherePC Agent

**Windows/Linux PC Agent для SphereADB** - автоматическое управление Remote PC и LDPlayer эмуляторами.

## 🎯 Возможности

- ✅ **Авто-регистрация** - ПК автоматически появляется на сайте при первом запуске
- ✅ **Persistent WebSocket** - стабильное соединение с auto-reconnect
- ✅ **Heartbeat** - статус ПК обновляется каждые 30 секунд
- ✅ **LDPlayer CLI** - полное управление эмуляторами через ldconsole.exe
- ✅ **Автозагрузка** - агент запускается при старте Windows
- ✅ **Системная информация** - CPU, RAM, диск, сеть
- ✅ **Shell команды** - выполнение любых команд на ПК

## 📦 Установка

### Быстрый способ (Windows)

1. Скачайте `SpherePC-Agent-Setup.exe` с сервера
2. Запустите установщик
3. Введите токен (получить на сайте в разделе Remote PCs → Generate Token)
4. Готово! ПК появится на сайте автоматически

### Ручная установка (Python)

```bash
# Клонировать репозиторий
git clone https://github.com/RootOne1337/SpherePC-Agent.git
cd SpherePC-Agent

# Установить зависимости
pip install -r requirements.txt

# Настроить конфигурацию
cp config.example.yaml config.yaml
# Отредактировать config.yaml (указать server_url и token)

# Запустить
python main.py
```

### Установка как Windows Service

```bash
# После настройки config.yaml
python install_service.py install

# Управление сервисом
python install_service.py start
python install_service.py stop
python install_service.py restart
python install_service.py uninstall
```

## 🔧 Конфигурация

`config.yaml`:

```yaml
# Сервер SphereADB
server:
  url: "https://adb.leetpc.com"
  websocket_path: "/api/v1/pc/ws"
  
# Токен авторизации (получить на сайте)
token: "your-token-here"

# Информация о ПК (опционально, автоопределение)
pc:
  name: "My Gaming PC"
  location: "Office"

# LDPlayer настройки
ldplayer:
  enabled: true
  path: "C:\\LDPlayer\\LDPlayer9"
  
# Heartbeat интервал (секунды)
heartbeat_interval: 30

# Автозагрузка
autostart: true

# Логирование
logging:
  level: INFO
  file: "logs/agent.log"
```

## 🌐 API Протокол

### Hello Message (при подключении)

```json
{
  "type": "hello",
  "token": "pc-token-xxx",
  "pc_id": "hardware-uuid",
  "pc_name": "DESKTOP-ABC123",
  "os_type": "windows",
  "os_version": "Windows 10 Pro 22H2",
  "agent_version": "1.0.0",
  "hostname": "192.168.1.100",
  "capabilities": ["ldplayer", "shell", "adb", "file_transfer"],
  "hardware": {
    "cpu": "AMD Ryzen 9 5900X",
    "ram_total_gb": 64,
    "ram_free_gb": 48,
    "disk_total_gb": 1000,
    "disk_free_gb": 500
  },
  "ldplayer": {
    "path": "C:\\LDPlayer\\LDPlayer9",
    "version": "9.0.75",
    "emulators": [...]
  }
}
```

### Heartbeat (каждые 30 сек)

```json
{
  "type": "heartbeat",
  "timestamp": 1704326400000,
  "cpu_usage": 25.5,
  "ram_usage": 45.2,
  "emulators": [
    {"index": 0, "name": "LDPlayer", "status": "running"}
  ]
}
```

### Команды от сервера

| Команда | Параметры | Описание |
|---------|-----------|----------|
| `ld_list` | - | Список эмуляторов |
| `ld_launch` | index | Запустить эмулятор |
| `ld_quit` | index | Остановить эмулятор |
| `ld_reboot` | index | Перезагрузить эмулятор |
| `ld_create` | name, config | Создать эмулятор |
| `ld_clone` | index, name | Клонировать эмулятор |
| `ld_remove` | index | Удалить эмулятор |
| `ld_rename` | index, name | Переименовать |
| `ld_modify` | index, settings | Изменить настройки |
| `ld_install_apk` | index, path | Установить APK |
| `ld_run_app` | index, package | Запустить приложение |
| `shell` | command | Shell команда |
| `get_info` | - | Системная информация |
| `restart_agent` | - | Перезапуск агента |

## 📁 Структура проекта

```
SpherePC-Agent/
├── main.py                     # Точка входа
├── config.yaml                 # Конфигурация
├── requirements.txt            # Python зависимости
├── install_service.py          # Установка Windows Service
├── build.py                    # Сборка .exe (PyInstaller)
│
├── agent/
│   ├── __init__.py
│   ├── config.py               # Загрузка конфигурации
│   ├── connection.py           # WebSocket + reconnect
│   ├── heartbeat.py            # Heartbeat service
│   ├── system_info.py          # Информация о системе
│   └── hardware_id.py          # Уникальный Hardware ID
│
├── commands/
│   ├── __init__.py
│   ├── base.py                 # Базовый класс команд
│   ├── ldplayer.py             # LDPlayer команды
│   ├── shell.py                # Shell команды
│   └── system.py               # Системные команды
│
├── service/
│   ├── __init__.py
│   ├── windows_service.py      # Windows Service (pywin32)
│   └── linux_service.py        # Linux systemd
│
└── logs/
    └── agent.log               # Лог файлы
```

## 🔨 Сборка .exe

```bash
# Установить PyInstaller
pip install pyinstaller

# Собрать
python build.py

# Результат в dist/SpherePC-Agent.exe
```

## 📄 Лицензия

MIT License - см. LICENSE файл

## 🤝 Contributing

Pull requests welcome! См. CONTRIBUTING.md
