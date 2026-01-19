"""
Сборка SpherePC Agent в ОДИН .exe файл
PyInstaller с встроенными конфигами и зависимостями
"""

import PyInstaller.__main__
import os
import sys
from pathlib import Path

# Текущая директория
BASE_DIR = Path(__file__).parent

# Конфиг по умолчанию (встроим в .exe)
DEFAULT_CONFIG = """
# SpherePC Agent - Автоматическая конфигурация
# Агент подключится к первому доступному серверу

server:
  url: "https://adb.leetpc.com"
  websocket_path: "/api/v1/pc/ws"
  
  # Fallback серверы (автоматическое переключение!)
  fallback_urls:
    - "https://sphereadb-api-v2.ru.tuna.am"
    - "https://backup1.leetpc.com"
    - "https://backup2.leetpc.com"

# Токен НЕ нужен - автоматическая регистрация!
# token: ""

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

autostart:
  enabled: true
  hidden: true

logging:
  level: "INFO"
  file: "logs/agent.log"
  max_size_mb: 10
  backup_count: 5
  console: true

security:
  allow_shell: true
  shell_whitelist: []
  shell_blacklist: 
    - "rm -rf"
    - "format"
    - "del /f"
    - "shutdown"

advanced:
  device_id: ""
  metrics_enabled: true
  metrics_interval: 60
"""

# Сохраняем конфиг
config_file = BASE_DIR / "config_embedded.yaml"
with open(config_file, 'w', encoding='utf-8') as f:
    f.write(DEFAULT_CONFIG)

print("🔨 Сборка SpherePC-Agent.exe...")
print("=" * 50)

# Параметры PyInstaller
PyInstaller.__main__.run([
    'main.py',
    
    # === ОДИН ФАЙЛ ===
    '--onefile',
    
    # === ИМЯ ===
    '--name=SpherePC-Agent',
    
    # === БЕЗ КОНСОЛИ (опционально) ===
    # '--noconsole',  # Убери комментарий для скрытого запуска
    
    # === ИКОНКА (опционально) ===
    # '--icon=icon.ico',
    
    # === ВСТРАИВАЕМ ФАЙЛЫ ===
    f'--add-data={config_file}:.',
    
    # === СКРЫТЫЕ ИМПОРТЫ ===
    '--hidden-import=websockets',
    '--hidden-import=psutil',
    '--hidden-import=yaml',
    '--hidden-import=aiohttp',
    '--hidden-import=asyncio',
    
    # === ОПТИМИЗАЦИЯ ===
    '--clean',
    '--noconfirm',
    
    # === МЕТАДАННЫЕ ===
    '--version-file=version_info.txt',  # Если есть
    
    # === UPX КОМПРЕССИЯ (если установлен) ===
    '--upx-dir=upx',  # Опционально
    
    # === ИСКЛЮЧЕНИЯ (уменьшаем размер) ===
    '--exclude-module=matplotlib',
    '--exclude-module=numpy',
    '--exclude-module=pandas',
    '--exclude-module=PIL',
    '--exclude-module=tkinter',
])

print("=" * 50)
print("✅ Готово!")
print("")
print("📦 Результат:")
print(f"   dist/SpherePC-Agent.exe")
print("")
print("🚀 Запуск:")
print("   1. Скопируй SpherePC-Agent.exe на любой ПК")
print("   2. Запусти двойным кликом")
print("   3. ПК появится на https://adb.leetpc.com/remote-pcs")
print("")
print("💡 Отказоустойчивость:")
print("   - Автоматический reconnect")
print("   - 4 fallback сервера")
print("   - Exponential backoff (1s → 60s)")
print("")
print("🎯 Всё работает БЕЗ ТОКЕНОВ!")
