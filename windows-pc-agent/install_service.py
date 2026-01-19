#!/usr/bin/env python3
"""
Установка/управление Windows Service
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from service.windows_service import (
    install_service, 
    uninstall_service, 
    start_service, 
    stop_service,
    WIN32_AVAILABLE
)


def print_usage():
    print("SpherePC Agent - Windows Service Manager")
    print("")
    print("Usage: python install_service.py <command>")
    print("")
    print("Commands:")
    print("  install   - Установить службу")
    print("  uninstall - Удалить службу")
    print("  start     - Запустить службу")
    print("  stop      - Остановить службу")
    print("  restart   - Перезапустить службу")
    print("")


def main():
    if not WIN32_AVAILABLE:
        print("Error: pywin32 не установлен")
        print("Выполните: pip install pywin32")
        sys.exit(1)
    
    if len(sys.argv) < 2:
        print_usage()
        sys.exit(0)
    
    command = sys.argv[1].lower()
    
    if command == "install":
        install_service()
    elif command == "uninstall":
        uninstall_service()
    elif command == "start":
        start_service()
    elif command == "stop":
        stop_service()
    elif command == "restart":
        stop_service()
        start_service()
    else:
        print(f"Unknown command: {command}")
        print_usage()
        sys.exit(1)


if __name__ == "__main__":
    main()
