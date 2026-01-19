#!/bin/bash
# ================================================
# SpherePC Agent - Build Script для Linux/WSL
# Создаёт Windows .exe через PyInstaller
# ================================================

echo ""
echo "========================================="
echo "  SpherePC Agent - Build Script"
echo "========================================="
echo ""

# Проверка Python
if ! command -v python3 &> /dev/null; then
    echo "[ERROR] Python3 not found!"
    exit 1
fi

echo "[OK] Python found: $(python3 --version)"

# Установка PyInstaller
echo ""
echo "[INFO] Installing PyInstaller..."
pip3 install pyinstaller

# Установка зависимостей
echo ""
echo "[INFO] Installing dependencies..."
pip3 install -r requirements.txt

# Сборка
echo ""
echo "[INFO] Building SpherePC-Agent.exe..."
echo ""
echo "This may take 2-5 minutes..."
echo ""

python3 build_single_exe.py

# Проверка результата
if [ -f "dist/SpherePC-Agent.exe" ]; then
    echo ""
    echo "========================================="
    echo "  ✅ BUILD SUCCESSFUL!"
    echo "========================================="
    echo ""
    echo "📦 Result:"
    ls -lh dist/SpherePC-Agent.exe
    echo ""
    echo "🚀 Usage:"
    echo "  1. Copy dist/SpherePC-Agent.exe to Windows PC"
    echo "  2. Run SpherePC-Agent.exe"
    echo "  3. Open https://adb.leetpc.com/remote-pcs"
    echo "  4. See your PC online!"
    echo ""
    echo "💡 Features:"
    echo "  • Auto-register (no tokens!)"
    echo "  • 4 fallback servers"
    echo "  • Auto-reconnect"
    echo "  • LDPlayer control"
    echo "  • Scripts (batch, PS, python)"
    echo "  • File operations"
    echo "  • Process management"
    echo "  • Everything from web!"
    echo ""
else
    echo ""
    echo "[ERROR] Build failed! Check output above."
    exit 1
fi
