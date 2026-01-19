#!/bin/bash
# SpherePC Agent - Quick Setup Package Creator
# Создаёт готовый пакет для установки на Windows PC

set -e

AGENT_DIR="/home/rootone/SpherePC-Agent"
RELEASE_DIR="/home/rootone/SpherePC-Agent-Release"
VERSION="1.0.0"

echo "═══════════════════════════════════════════════════════════"
echo "  SpherePC Agent v${VERSION} - Release Package Creator"
echo "═══════════════════════════════════════════════════════════"
echo ""

# Очистка старого релиза
rm -rf "$RELEASE_DIR"
mkdir -p "$RELEASE_DIR"

echo "[1/5] Копирование файлов..."

# Копируем все нужные файлы
cp -r "$AGENT_DIR"/* "$RELEASE_DIR/"

# Удаляем ненужное
rm -rf "$RELEASE_DIR/__pycache__"
rm -rf "$RELEASE_DIR"/*/__pycache__
rm -rf "$RELEASE_DIR"/*/**/__pycache__
rm -rf "$RELEASE_DIR/dist"
rm -rf "$RELEASE_DIR/build"
rm -rf "$RELEASE_DIR/.git"

echo "✓ Файлы скопированы"

echo ""
echo "[2/5] Создание config.yaml с примерами..."

# Создаём готовый config.yaml с placeholder токеном
cat > "$RELEASE_DIR/config.yaml" << 'EOF'
# SpherePC Agent Configuration
# ВАЖНО: Замените YOUR_TOKEN_HERE на настоящий токен!

# ===== Сервер SphereADB =====
server:
  url: "https://adb.leetpc.com"
  websocket_path: "/api/v1/pc/ws"
  fallback_urls:
    - "https://sphereadb-api-v2.ru.tuna.am"

# ===== ТОКЕН (ОБЯЗАТЕЛЬНО ЗАПОЛНИТЬ!) =====
# Получить токен: https://adb.leetpc.com/remote-pcs → "Add PC" → "Generate Token"
token: "YOUR_TOKEN_HERE"

# ===== Информация о ПК =====
pc:
  # Имя ПК (если пусто - берётся hostname автоматически)
  name: ""
  # Локация (например: "Office", "Home", "DataCenter-1")
  location: ""

# ===== LDPlayer настройки =====
ldplayer:
  enabled: true
  # Путь к LDPlayer (оставьте пустым для автоопределения)
  path: ""
  auto_detect: true

# ===== Соединение =====
connection:
  heartbeat_interval: 30
  connect_timeout: 30
  max_reconnect_delay: 60
  initial_reconnect_delay: 1

# ===== Автозагрузка Windows =====
autostart:
  enabled: true
  hidden: true

# ===== Логирование =====
logging:
  level: "INFO"
  file: "logs/agent.log"
  max_size_mb: 10
  backup_count: 5
  console: true

# ===== Безопасность =====
security:
  allow_shell: true
  shell_whitelist:
    - "^dir.*"
    - "^ipconfig.*"
    - "^tasklist.*"
    - "^systeminfo.*"
  shell_blacklist:
    - "^format.*"
    - "^del /s.*"
    - "^rmdir /s.*"
    - ".*shutdown.*"
    - ".*restart.*"

# ===== Дополнительно =====
advanced:
  device_id: ""
  metrics_enabled: true
  metrics_interval: 60
EOF

echo "✓ config.yaml создан"

echo ""
echo "[3/5] Создание инструкций по установке..."

cat > "$RELEASE_DIR/INSTALL.txt" << 'EOF'
╔═══════════════════════════════════════════════════════════════╗
║           SpherePC Agent v1.0.0 - Installation Guide          ║
╚═══════════════════════════════════════════════════════════════╝

БЫСТРАЯ УСТАНОВКА (5 минут):

1. Установите Python 3.10+ (если ещё нет):
   - Скачайте: https://www.python.org/downloads/
   - ☑ Галочка "Add Python to PATH" при установке!

2. Получите токен подключения:
   - Откройте: https://adb.leetpc.com/remote-pcs
   - Нажмите: "Add PC" → "Generate Token"
   - Скопируйте токен (начинается с "sphere_...")

3. Настройте config.yaml:
   - Откройте файл config.yaml в блокноте
   - Найдите строку: token: "YOUR_TOKEN_HERE"
   - Замените YOUR_TOKEN_HERE на ваш токен
   - Сохраните файл

4. Установите зависимости:
   - Откройте командную строку (cmd) в этой папке
   - Выполните: pip install -r requirements.txt

5. Запустите агент:
   - Выполните: python main.py
   - ПК должен появиться на сайте через 5-10 секунд!

═══════════════════════════════════════════════════════════════

УСТАНОВКА КАК СЛУЖБЫ WINDOWS (автозапуск):

После выполнения шагов 1-4 выше:

1. Откройте cmd от имени Администратора
2. Выполните:
   python install_service.py install
   python install_service.py start

3. Проверка:
   python install_service.py status

Агент будет запускаться автоматически при загрузке Windows!

═══════════════════════════════════════════════════════════════

ПРОВЕРКА РАБОТЫ:

1. На ПК:
   - Смотрим логи: logs/agent.log
   - Должны увидеть: "✓ Зарегистрирован как ..."

2. На сайте:
   - Откройте: https://adb.leetpc.com/remote-pcs
   - Ваш ПК должен быть в списке со статусом "Online" (зелёный)

═══════════════════════════════════════════════════════════════

УПРАВЛЕНИЕ СЛУЖБОЙ:

python install_service.py start      # Запустить
python install_service.py stop       # Остановить
python install_service.py restart    # Перезапустить
python install_service.py status     # Статус
python install_service.py remove     # Удалить службу

═══════════════════════════════════════════════════════════════

РЕШЕНИЕ ПРОБЛЕМ:

❌ "Module not found":
   → pip install -r requirements.txt

❌ "Connection refused":
   → Проверьте интернет и доступ к adb.leetpc.com
   → Проверьте токен в config.yaml

❌ "Invalid token":
   → Сгенерируйте новый токен на сайте
   → Убедитесь что скопировали весь токен целиком

❌ "LDPlayer not found":
   → Убедитесь что LDPlayer установлен
   → Укажите путь в config.yaml: ldplayer.path

❌ ПК не появляется на сайте:
   → Проверьте логи: logs/agent.log
   → Проверьте что служба запущена
   → Перезапустите агент

═══════════════════════════════════════════════════════════════

СТРУКТУРА ФАЙЛОВ:

SpherePC-Agent/
├── main.py                 # Главный файл (запускать этот)
├── config.yaml             # НАСТРОЙКИ (заполнить токен!)
├── requirements.txt        # Зависимости Python
├── install_service.py      # Установка службы Windows
├── INSTALL.txt            # Эта инструкция
├── agent/                  # Модули агента
├── commands/              # Обработчики команд
├── service/               # Windows Service
└── logs/                  # Логи (создаётся автоматически)

═══════════════════════════════════════════════════════════════

ПОДДЕРЖКА:

GitHub: https://github.com/RootOne1337/SphereADB
Docs:   https://adb.leetpc.com/docs

═══════════════════════════════════════════════════════════════
EOF

echo "✓ INSTALL.txt создан"

echo ""
echo "[4/5] Создание Windows batch файлов для удобства..."

# Быстрый запуск
cat > "$RELEASE_DIR/START.bat" << 'EOF'
@echo off
title SpherePC Agent
echo Starting SpherePC Agent...
echo.
python main.py
pause
EOF

# Установка зависимостей
cat > "$RELEASE_DIR/INSTALL_DEPS.bat" << 'EOF'
@echo off
title Install Dependencies
echo Installing Python dependencies...
echo.
pip install -r requirements.txt
echo.
echo Done!
pause
EOF

# Установка службы
cat > "$RELEASE_DIR/INSTALL_SERVICE.bat" << 'EOF'
@echo off
title Install Windows Service
echo Installing SpherePC Agent as Windows Service...
echo.
python install_service.py install
python install_service.py start
echo.
echo Service installed and started!
echo.
python install_service.py status
pause
EOF

chmod +x "$RELEASE_DIR"/*.bat

echo "✓ Batch файлы созданы"

echo ""
echo "[5/5] Создание архива для скачивания..."

cd "$(dirname "$RELEASE_DIR")"
tar -czf "SpherePC-Agent-v${VERSION}.tar.gz" "$(basename "$RELEASE_DIR")"
zip -r "SpherePC-Agent-v${VERSION}.zip" "$(basename "$RELEASE_DIR")" > /dev/null 2>&1

ARCHIVE_SIZE=$(du -h "SpherePC-Agent-v${VERSION}.tar.gz" | cut -f1)
ZIP_SIZE=$(du -h "SpherePC-Agent-v${VERSION}.zip" | cut -f1)

echo "✓ Архивы созданы"

echo ""
echo "═══════════════════════════════════════════════════════════"
echo "  ✓ Release Package готов!"
echo "═══════════════════════════════════════════════════════════"
echo ""
echo "Директория:  $RELEASE_DIR"
echo "Архив Linux: SpherePC-Agent-v${VERSION}.tar.gz (${ARCHIVE_SIZE})"
echo "Архив Win:   SpherePC-Agent-v${VERSION}.zip (${ZIP_SIZE})"
echo ""
echo "Файлы для пользователей:"
echo "  ✓ INSTALL.txt        - инструкция по установке"
echo "  ✓ START.bat          - быстрый запуск"
echo "  ✓ INSTALL_DEPS.bat   - установка зависимостей"
echo "  ✓ INSTALL_SERVICE.bat - установка службы Windows"
echo "  ✓ config.yaml        - конфигурация (нужен токен!)"
echo ""
echo "Что делать дальше:"
echo "  1. Скачать SpherePC-Agent-v${VERSION}.zip на Windows ПК"
echo "  2. Распаковать архив"
echo "  3. Следовать инструкциям в INSTALL.txt"
echo ""
echo "Готово! 🚀"
echo "═══════════════════════════════════════════════════════════"
