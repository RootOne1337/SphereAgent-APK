#!/usr/bin/env python3
"""
Скрипт установки SpherePC Agent
Поддерживает Windows и Linux
"""

import os
import sys
import shutil
import platform
from pathlib import Path


def is_admin():
    """Проверить права администратора"""
    if sys.platform == "win32":
        try:
            import ctypes
            return ctypes.windll.shell32.IsUserAnAdmin() != 0
        except:
            return False
    else:
        return os.geteuid() == 0


def install_windows():
    """Установка для Windows"""
    print("═══════════════════════════════════════════")
    print("  SpherePC Agent - Windows Installer")
    print("═══════════════════════════════════════════")
    
    # Проверка прав
    if not is_admin():
        print("⚠️  Для установки службы требуются права администратора")
        print("   Запустите скрипт от имени администратора")
    
    # Создаём директорию в ProgramData
    data_dir = Path(os.environ.get("PROGRAMDATA", "C:\\ProgramData")) / "SpherePC-Agent"
    data_dir.mkdir(parents=True, exist_ok=True)
    
    # Директория для логов
    (data_dir / "logs").mkdir(exist_ok=True)
    
    # Копируем config.example.yaml если нет config.yaml
    config_file = data_dir / "config.yaml"
    if not config_file.exists():
        example_config = Path(__file__).parent / "config.example.yaml"
        if example_config.exists():
            shutil.copy(example_config, config_file)
            print(f"✓ Конфигурация создана: {config_file}")
        else:
            # Создаём минимальный конфиг
            config_file.write_text("""# SpherePC Agent Configuration
server:
  url: "https://adb.leetpc.com"
  websocket_path: "/api/v1/pc/ws"

# ВАЖНО: Укажите токен!
token: ""

pc:
  name: ""
  location: ""

ldplayer:
  enabled: true
  path: ""
  auto_detect: true

connection:
  heartbeat_interval: 30

logging:
  level: "INFO"
  file: "logs/agent.log"
""")
            print(f"✓ Конфигурация создана: {config_file}")
    
    print(f"\n📁 Директория данных: {data_dir}")
    print(f"📄 Конфигурация: {config_file}")
    print(f"\n⚠️  Не забудьте указать token в {config_file}")
    
    # Добавляем в автозагрузку
    add_to_autostart_windows()
    
    print("\n═══════════════════════════════════════════")
    print("  Установка завершена!")
    print("═══════════════════════════════════════════")
    print("\nДля запуска:")
    print("  python main.py")
    print("\nДля установки как службы (требует права админа):")
    print("  python install_service.py install")
    print("  python install_service.py start")


def add_to_autostart_windows():
    """Добавить в автозагрузку Windows"""
    try:
        import winreg
        
        key_path = r"SOFTWARE\Microsoft\Windows\CurrentVersion\Run"
        key = winreg.OpenKey(winreg.HKEY_CURRENT_USER, key_path, 0, winreg.KEY_SET_VALUE)
        
        # Путь к main.py
        agent_path = str(Path(__file__).parent / "main.py")
        python_path = sys.executable
        
        # Команда запуска (скрытый режим через pythonw)
        pythonw = python_path.replace("python.exe", "pythonw.exe")
        if os.path.exists(pythonw):
            command = f'"{pythonw}" "{agent_path}"'
        else:
            command = f'"{python_path}" "{agent_path}"'
        
        winreg.SetValueEx(key, "SpherePCAgent", 0, winreg.REG_SZ, command)
        winreg.CloseKey(key)
        
        print("✓ Добавлено в автозагрузку")
        
    except Exception as e:
        print(f"⚠️  Не удалось добавить в автозагрузку: {e}")


def remove_from_autostart_windows():
    """Удалить из автозагрузки Windows"""
    try:
        import winreg
        
        key_path = r"SOFTWARE\Microsoft\Windows\CurrentVersion\Run"
        key = winreg.OpenKey(winreg.HKEY_CURRENT_USER, key_path, 0, winreg.KEY_SET_VALUE)
        
        try:
            winreg.DeleteValue(key, "SpherePCAgent")
            print("✓ Удалено из автозагрузки")
        except FileNotFoundError:
            print("ℹ️  Не было в автозагрузке")
        
        winreg.CloseKey(key)
        
    except Exception as e:
        print(f"⚠️  Ошибка: {e}")


def install_linux():
    """Установка для Linux (systemd)"""
    print("═══════════════════════════════════════════")
    print("  SpherePC Agent - Linux Installer")
    print("═══════════════════════════════════════════")
    
    if not is_admin():
        print("⚠️  Для установки службы требуются права root")
        print("   Запустите с sudo")
    
    # Создаём директорию
    data_dir = Path.home() / ".sphere-pc-agent"
    data_dir.mkdir(parents=True, exist_ok=True)
    (data_dir / "logs").mkdir(exist_ok=True)
    
    # Копируем конфиг
    config_file = data_dir / "config.yaml"
    if not config_file.exists():
        example_config = Path(__file__).parent / "config.example.yaml"
        if example_config.exists():
            shutil.copy(example_config, config_file)
            print(f"✓ Конфигурация создана: {config_file}")
    
    # Создаём systemd unit file
    agent_path = Path(__file__).parent / "main.py"
    python_path = sys.executable
    
    service_content = f"""[Unit]
Description=SpherePC Agent
After=network.target

[Service]
Type=simple
User={os.getenv('USER', 'root')}
WorkingDirectory={Path(__file__).parent}
ExecStart={python_path} {agent_path}
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
"""
    
    service_path = Path("/etc/systemd/system/sphere-pc-agent.service")
    
    if is_admin():
        try:
            service_path.write_text(service_content)
            print(f"✓ Systemd unit создан: {service_path}")
            
            # Reload systemd
            os.system("systemctl daemon-reload")
            print("✓ Systemd перезагружен")
            
            print("\nДля запуска службы:")
            print("  sudo systemctl start sphere-pc-agent")
            print("  sudo systemctl enable sphere-pc-agent")
            
        except Exception as e:
            print(f"⚠️  Ошибка создания service: {e}")
    else:
        print(f"\n📝 Для установки systemd service выполните с sudo:")
        print(f"   echo '{service_content}' | sudo tee /etc/systemd/system/sphere-pc-agent.service")
        print("   sudo systemctl daemon-reload")
        print("   sudo systemctl enable sphere-pc-agent")
        print("   sudo systemctl start sphere-pc-agent")
    
    print(f"\n📁 Директория: {data_dir}")
    print(f"📄 Конфигурация: {config_file}")
    print(f"\n⚠️  Не забудьте указать token в {config_file}")


def uninstall():
    """Удаление агента"""
    print("Удаление SpherePC Agent...")
    
    if sys.platform == "win32":
        remove_from_autostart_windows()
        
        # Удаляем службу
        try:
            from service.windows_service import uninstall_service, stop_service
            stop_service()
            uninstall_service()
        except:
            pass
    else:
        if is_admin():
            os.system("systemctl stop sphere-pc-agent")
            os.system("systemctl disable sphere-pc-agent")
            os.remove("/etc/systemd/system/sphere-pc-agent.service")
            os.system("systemctl daemon-reload")
            print("✓ Systemd service удалён")
    
    print("✓ Удаление завершено")


def main():
    """Главная функция"""
    if len(sys.argv) > 1:
        command = sys.argv[1].lower()
        
        if command == "uninstall":
            uninstall()
            return
        elif command == "autostart":
            if sys.platform == "win32":
                add_to_autostart_windows()
            return
        elif command == "no-autostart":
            if sys.platform == "win32":
                remove_from_autostart_windows()
            return
    
    # Установка
    if sys.platform == "win32":
        install_windows()
    else:
        install_linux()


if __name__ == "__main__":
    main()
