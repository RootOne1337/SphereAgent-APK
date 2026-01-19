#!/usr/bin/env python3
"""
Скрипт сборки SpherePC Agent в standalone .exe
Использует PyInstaller
"""

import os
import sys
import subprocess
from pathlib import Path


def build():
    """Собрать .exe"""
    print("═══════════════════════════════════════════")
    print("  SpherePC Agent - Build Script")
    print("═══════════════════════════════════════════")
    
    # Проверяем PyInstaller
    try:
        import PyInstaller
        print(f"✓ PyInstaller v{PyInstaller.__version__}")
    except ImportError:
        print("✗ PyInstaller не установлен")
        print("  Выполните: pip install pyinstaller")
        sys.exit(1)
    
    # Пути
    base_dir = Path(__file__).parent
    main_py = base_dir / "main.py"
    dist_dir = base_dir / "dist"
    build_dir = base_dir / "build"
    
    # Опции PyInstaller
    args = [
        str(main_py),
        "--name=SpherePC-Agent",
        "--onefile",  # Один файл
        "--console",  # Консольное приложение (можно заменить на --noconsole для скрытого)
        f"--distpath={dist_dir}",
        f"--workpath={build_dir}",
        "--clean",
        # Добавляем модули
        "--hidden-import=websockets",
        "--hidden-import=psutil",
        "--hidden-import=yaml",
        "--hidden-import=asyncio",
    ]
    
    # Для Windows добавляем win32 если доступен
    if sys.platform == "win32":
        args.extend([
            "--hidden-import=win32serviceutil",
            "--hidden-import=win32service",
            "--hidden-import=win32event",
            "--hidden-import=servicemanager",
        ])
    
    # Добавляем config.example.yaml как data file
    config_example = base_dir / "config.example.yaml"
    if config_example.exists():
        args.append(f"--add-data={config_example};.")
    
    print(f"\nЗапуск PyInstaller...")
    print(f"Аргументы: {' '.join(args)}")
    print("")
    
    # Запускаем PyInstaller
    result = subprocess.run(
        [sys.executable, "-m", "PyInstaller"] + args,
        cwd=str(base_dir)
    )
    
    if result.returncode == 0:
        exe_path = dist_dir / "SpherePC-Agent.exe" if sys.platform == "win32" else dist_dir / "SpherePC-Agent"
        print("")
        print("═══════════════════════════════════════════")
        print("  ✓ Сборка успешна!")
        print("═══════════════════════════════════════════")
        print(f"  Результат: {exe_path}")
        print(f"  Размер: {exe_path.stat().st_size / 1024 / 1024:.2f} MB")
    else:
        print("")
        print("✗ Сборка не удалась")
        sys.exit(1)


def build_noconsole():
    """Собрать .exe без консоли (для фоновой работы)"""
    print("Сборка без консоли...")
    
    # Меняем --console на --noconsole
    original_argv = sys.argv.copy()
    
    # Модифицируем build для noconsole
    import PyInstaller.__main__
    
    base_dir = Path(__file__).parent
    main_py = base_dir / "main.py"
    
    PyInstaller.__main__.run([
        str(main_py),
        "--name=SpherePC-Agent",
        "--onefile",
        "--noconsole",  # Без консоли
        "--hidden-import=websockets",
        "--hidden-import=psutil", 
        "--hidden-import=yaml",
    ])


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--noconsole":
        build_noconsole()
    else:
        build()
