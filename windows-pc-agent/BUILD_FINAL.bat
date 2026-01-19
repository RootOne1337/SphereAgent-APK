@echo off
REM ===============================================
REM  SpherePC Agent - ФИНАЛЬНАЯ СБОРКА .exe
REM  Собирает готовый к использованию .exe файл
REM ===============================================

title SpherePC Agent - Build

echo.
echo  ========================================
echo   SpherePC Agent - Final Build
echo  ========================================
echo.

REM Проверка Python
python --version >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Python not found!
    pause
    exit /b 1
)

echo [OK] Python found

REM Установка PyInstaller
echo.
echo [INFO] Installing PyInstaller...
pip install pyinstaller >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Failed to install PyInstaller
    pause
    exit /b 1
)

echo [OK] PyInstaller installed

REM Очистка старых build
echo.
echo [INFO] Cleaning old builds...
if exist "build" rmdir /s /q build
if exist "dist" rmdir /s /q dist
if exist "*.spec" del /q *.spec

REM Сборка .exe
echo.
echo [INFO] Building SpherePC-Agent.exe...
echo.
echo This may take 2-5 minutes...
echo.

python -c "import PyInstaller.__main__; PyInstaller.__main__.run([
    'main.py',
    '--onefile',
    '--name=SpherePC-Agent',
    '--hidden-import=websockets',
    '--hidden-import=psutil',
    '--hidden-import=yaml',
    '--hidden-import=aiohttp',
    '--hidden-import=asyncio',
    '--hidden-import=pywin32',
    '--add-data=config.yaml;.',
    '--clean',
    '--noconfirm',
    '--exclude-module=matplotlib',
    '--exclude-module=numpy',
    '--exclude-module=pandas',
    '--exclude-module=PIL',
    '--exclude-module=tkinter'
])"

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERROR] Build failed!
    pause
    exit /b 1
)

REM Проверка результата
if not exist "dist\SpherePC-Agent.exe" (
    echo.
    echo [ERROR] SpherePC-Agent.exe not found!
    pause
    exit /b 1
)

REM Успех!
echo.
echo  ========================================
echo   ✅ BUILD SUCCESSFUL!
echo  ========================================
echo.
echo  📦 Result:
echo     dist\SpherePC-Agent.exe
echo.

dir dist\SpherePC-Agent.exe

echo.
echo  🚀 Usage:
echo     1. Copy dist\SpherePC-Agent.exe to target PC
echo     2. Run SpherePC-Agent.exe
echo     3. Open https://adb.leetpc.com/remote-pcs
echo     4. See your PC online!
echo.
echo  💡 Features:
echo     • Auto-register (no tokens needed!)
echo     • 4 fallback servers
echo     • Auto-reconnect with backoff
echo     • LDPlayer full control
echo     • Scripts execution (batch, powershell, python)
echo     • File operations
echo     • Process management
echo     • Everything from web!
echo.
echo  ========================================
echo.

pause
