@echo off
REM ===============================================
REM  SpherePC Agent - ПОРТАБЕЛЬНАЯ ВЕРСИЯ
REM  Один клик - и ПК в системе!
REM ===============================================

title SpherePC Agent v1.0.0

echo.
echo  =====================================
echo   SpherePC Agent - Starting...
echo  =====================================
echo.

REM Проверка Python
python --version >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Python не найден!
    echo.
    echo Скачай Python 3.10+ с https://python.org
    echo Обязательно отметь "Add Python to PATH"!
    echo.
    pause
    exit /b 1
)

echo [OK] Python найден

REM Проверка зависимостей
echo [INFO] Проверка зависимостей...

pip show websockets >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [INSTALL] Установка зависимостей...
    pip install -q websockets psutil pyyaml aiohttp pywin32
    if %ERRORLEVEL% NEQ 0 (
        echo [ERROR] Не удалось установить зависимости
        pause
        exit /b 1
    )
)

echo [OK] Зависимости готовы
echo.

REM Создаём минимальный конфиг если нет
if not exist "config.yaml" (
    echo [INFO] Создаём конфиг...
    (
        echo server:
        echo   url: "https://adb.leetpc.com"
        echo   websocket_path: "/api/v1/pc/ws"
        echo   fallback_urls:
        echo     - "https://sphereadb-api-v2.ru.tuna.am"
        echo.
        echo pc:
        echo   name: ""
        echo   location: "Auto"
        echo.
        echo ldplayer:
        echo   enabled: true
        echo   path: ""
        echo   auto_detect: true
        echo.
        echo logging:
        echo   level: "INFO"
        echo   console: true
    ) > config.yaml
    echo [OK] Конфиг создан
)

echo.
echo =====================================
echo  Подключаемся к серверу...
echo =====================================
echo.
echo  URL: https://adb.leetpc.com
echo  Fallback: sphereadb-api-v2.ru.tuna.am
echo.
echo  Токен НЕ нужен - автоматическая регистрация!
echo =====================================
echo.

REM Запуск агента
python main.py

REM Если вышли с ошибкой
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERROR] Агент завершился с ошибкой!
    echo.
    echo Проверь логи: logs/agent.log
    echo.
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo Агент остановлен.
pause
