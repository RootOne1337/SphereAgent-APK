"""
LDPlayer команды
Управление LDPlayer эмуляторами через ldconsole.exe
"""

import asyncio
import os
import sys
import re
import logging
from pathlib import Path
from typing import Dict, Any, List, Optional
import time

from commands.base import CommandHandler, CommandResult

logger = logging.getLogger(__name__)


class LDPlayerCommands(CommandHandler):
    """
    Обработчик команд LDPlayer
    
    Поддерживает все команды ldconsole.exe:
    - list, list2 - список эмуляторов
    - launch, quit, reboot - lifecycle
    - add, remove, rename, copy - управление инстансами
    - modify - изменение настроек
    - installapp, uninstallapp, runapp, killapp - APK
    - и многое другое
    """
    
    SUPPORTED_COMMANDS = [
        # Список
        "ld_list",
        # Lifecycle
        "ld_launch", "ld_quit", "ld_quitall", "ld_reboot",
        # Инстансы
        "ld_create", "ld_clone", "ld_remove", "ld_rename",
        # Настройки
        "ld_modify",
        # APK
        "ld_install_apk", "ld_uninstall_apk", "ld_run_app", "ld_kill_app",
        # Информация
        "ld_get_prop", "ld_adb_port",
        # Действия
        "ld_action", "ld_scan",
        # Экспорт/Импорт
        "ld_backup", "ld_restore",
        # Низкоуровневые
        "ld_command"
    ]
    
    # Дефолтные пути к LDPlayer
    DEFAULT_PATHS = [
        r"C:\LDPlayer\LDPlayer9",
        r"C:\LDPlayer9",
        r"C:\Program Files\LDPlayer\LDPlayer9",
        r"C:\Program Files\ldplayer9box",
        r"D:\LDPlayer\LDPlayer9",
    ]
    
    def __init__(self, ldplayer_path: str = "", auto_detect: bool = True):
        """
        Args:
            ldplayer_path: Путь к папке LDPlayer
            auto_detect: Автоматически определить путь
        """
        self.ldplayer_path = ldplayer_path
        
        if auto_detect and not self.ldplayer_path:
            self.ldplayer_path = self._auto_detect_path()
        
        self._ldconsole_path: Optional[str] = None
        if self.ldplayer_path:
            self._ldconsole_path = self._get_ldconsole_path()
    
    def _auto_detect_path(self) -> str:
        """Автоматически найти путь к LDPlayer"""
        for path in self.DEFAULT_PATHS:
            ldconsole = os.path.join(path, "ldconsole.exe")
            if os.path.exists(ldconsole):
                logger.info(f"LDPlayer найден: {path}")
                return path
        
        # Пробуем найти через реестр Windows
        if sys.platform == "win32":
            try:
                import winreg
                key = winreg.OpenKey(
                    winreg.HKEY_LOCAL_MACHINE,
                    r"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\LDPlayer9"
                )
                install_path = winreg.QueryValueEx(key, "InstallLocation")[0]
                winreg.CloseKey(key)
                if install_path and os.path.exists(install_path):
                    logger.info(f"LDPlayer найден через реестр: {install_path}")
                    return install_path
            except:
                pass
        
        logger.warning("LDPlayer не найден")
        return ""
    
    def _get_ldconsole_path(self) -> Optional[str]:
        """Получить путь к ldconsole.exe"""
        if not self.ldplayer_path:
            return None
        
        ldconsole = os.path.join(self.ldplayer_path, "ldconsole.exe")
        if os.path.exists(ldconsole):
            return ldconsole
        
        # Попробовать dnconsole.exe
        dnconsole = os.path.join(self.ldplayer_path, "dnconsole.exe")
        if os.path.exists(dnconsole):
            return dnconsole
        
        return None
    
    def is_available(self) -> bool:
        """Проверить доступен ли LDPlayer"""
        return self._ldconsole_path is not None and os.path.exists(self._ldconsole_path)
    
    async def _run_ldconsole(self, *args, timeout: int = 30) -> Dict[str, Any]:
        """
        Запустить ldconsole.exe с аргументами
        
        Returns:
            {"stdout": str, "stderr": str, "returncode": int}
        """
        if not self._ldconsole_path:
            return {
                "stdout": "",
                "stderr": "LDPlayer not found",
                "returncode": -1
            }
        
        cmd = [self._ldconsole_path] + list(args)
        
        try:
            process = await asyncio.create_subprocess_exec(
                *cmd,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE
            )
            
            stdout, stderr = await asyncio.wait_for(
                process.communicate(),
                timeout=timeout
            )
            
            return {
                "stdout": stdout.decode("utf-8", errors="replace").strip(),
                "stderr": stderr.decode("utf-8", errors="replace").strip(),
                "returncode": process.returncode
            }
            
        except asyncio.TimeoutError:
            return {
                "stdout": "",
                "stderr": f"Command timeout after {timeout}s",
                "returncode": -1
            }
        except Exception as e:
            return {
                "stdout": "",
                "stderr": str(e),
                "returncode": -1
            }
    
    async def execute(self, command: str, params: Dict[str, Any]) -> CommandResult:
        """Выполнить команду LDPlayer"""
        start_time = time.time()
        
        if not self.is_available():
            return self.error("LDPlayer not available")
        
        try:
            # Маршрутизация команд
            if command == "ld_list":
                result = await self._list_emulators()
            elif command == "ld_launch":
                result = await self._launch(params.get("index", 0))
            elif command == "ld_quit":
                result = await self._quit(params.get("index", 0))
            elif command == "ld_quitall":
                result = await self._quitall()
            elif command == "ld_reboot":
                result = await self._reboot(params.get("index", 0))
            elif command == "ld_create":
                result = await self._create(params.get("name", "NewEmulator"))
            elif command == "ld_clone":
                result = await self._clone(params.get("index", 0), params.get("name", "Clone"))
            elif command == "ld_remove":
                result = await self._remove(params.get("index", 0))
            elif command == "ld_rename":
                result = await self._rename(params.get("index", 0), params.get("name", ""))
            elif command == "ld_modify":
                result = await self._modify(params.get("index", 0), params.get("settings", {}))
            elif command == "ld_install_apk":
                result = await self._install_apk(params.get("index", 0), params.get("path", ""))
            elif command == "ld_uninstall_apk":
                result = await self._uninstall_apk(params.get("index", 0), params.get("package", ""))
            elif command == "ld_run_app":
                result = await self._run_app(params.get("index", 0), params.get("package", ""))
            elif command == "ld_kill_app":
                result = await self._kill_app(params.get("index", 0), params.get("package", ""))
            elif command == "ld_adb_port":
                result = await self._get_adb_port(params.get("index", 0))
            elif command == "ld_backup":
                result = await self._backup(params.get("index", 0), params.get("path", ""))
            elif command == "ld_restore":
                result = await self._restore(params.get("path", ""), params.get("name", ""))
            elif command == "ld_command":
                result = await self._raw_command(params.get("args", []))
            else:
                result = self.error(f"Unknown LDPlayer command: {command}")
            
            result.duration_ms = int((time.time() - start_time) * 1000)
            return result
            
        except Exception as e:
            logger.exception(f"Ошибка выполнения {command}")
            return self.error(str(e), int((time.time() - start_time) * 1000))
    
    # === Реализации команд ===
    
    async def _list_emulators(self) -> CommandResult:
        """Получить список эмуляторов (list2 для детальной информации)"""
        result = await self._run_ldconsole("list2")
        
        if result["returncode"] != 0:
            return self.error(result["stderr"] or "Failed to list emulators")
        
        emulators = self._parse_list2(result["stdout"])
        return self.success(emulators)
    
    def _parse_list2(self, output: str) -> List[Dict[str, Any]]:
        """Парсинг вывода list2"""
        emulators = []
        
        for line in output.strip().split('\n'):
            if not line.strip():
                continue
            
            # Формат: index,name,top_window_handle,bind_window_handle,is_running,pid,vbox_pid
            parts = line.split(',')
            if len(parts) >= 5:
                try:
                    emu = {
                        "index": int(parts[0]),
                        "name": parts[1],
                        "status": "running" if parts[4] == "1" else "stopped",
                        "pid": int(parts[5]) if len(parts) > 5 and parts[5] else 0
                    }
                    emulators.append(emu)
                except (ValueError, IndexError):
                    continue
        
        return emulators
    
    async def _launch(self, index: int) -> CommandResult:
        """Запустить эмулятор"""
        result = await self._run_ldconsole("launch", "--index", str(index))
        
        if result["returncode"] != 0:
            return self.error(result["stderr"] or "Failed to launch")
        
        return self.success({"index": index, "action": "launched"})
    
    async def _quit(self, index: int) -> CommandResult:
        """Остановить эмулятор"""
        result = await self._run_ldconsole("quit", "--index", str(index))
        
        if result["returncode"] != 0:
            return self.error(result["stderr"] or "Failed to quit")
        
        return self.success({"index": index, "action": "stopped"})
    
    async def _quitall(self) -> CommandResult:
        """Остановить все эмуляторы"""
        result = await self._run_ldconsole("quitall")
        return self.success({"action": "all_stopped"})
    
    async def _reboot(self, index: int) -> CommandResult:
        """Перезагрузить эмулятор"""
        result = await self._run_ldconsole("reboot", "--index", str(index))
        
        if result["returncode"] != 0:
            return self.error(result["stderr"] or "Failed to reboot")
        
        return self.success({"index": index, "action": "rebooted"})
    
    async def _create(self, name: str) -> CommandResult:
        """Создать новый эмулятор"""
        result = await self._run_ldconsole("add", "--name", name)
        
        if result["returncode"] != 0:
            return self.error(result["stderr"] or "Failed to create")
        
        return self.success({"name": name, "action": "created"})
    
    async def _clone(self, index: int, name: str) -> CommandResult:
        """Клонировать эмулятор"""
        result = await self._run_ldconsole("copy", "--index", str(index), "--name", name)
        
        if result["returncode"] != 0:
            return self.error(result["stderr"] or "Failed to clone")
        
        return self.success({"index": index, "name": name, "action": "cloned"})
    
    async def _remove(self, index: int) -> CommandResult:
        """Удалить эмулятор"""
        result = await self._run_ldconsole("remove", "--index", str(index))
        
        if result["returncode"] != 0:
            return self.error(result["stderr"] or "Failed to remove")
        
        return self.success({"index": index, "action": "removed"})
    
    async def _rename(self, index: int, name: str) -> CommandResult:
        """Переименовать эмулятор"""
        if not name:
            return self.error("Name is required")
        
        result = await self._run_ldconsole("rename", "--index", str(index), "--title", name)
        
        if result["returncode"] != 0:
            return self.error(result["stderr"] or "Failed to rename")
        
        return self.success({"index": index, "name": name, "action": "renamed"})
    
    async def _modify(self, index: int, settings: Dict[str, Any]) -> CommandResult:
        """
        Изменить настройки эмулятора
        
        settings может содержать:
        - cpu: int (1-8)
        - ram: int (256-8192 MB, кратно 256)
        - resolution: str ("720x1280" или WxH)
        - dpi: int (120, 160, 240, 320, ...)
        - fps: int (20-120)
        - manufacturer: str
        - model: str
        - imei: str
        - phone: str
        """
        args = ["modify", "--index", str(index)]
        
        if "cpu" in settings:
            args.extend(["--cpu", str(settings["cpu"])])
        
        if "ram" in settings:
            args.extend(["--memory", str(settings["ram"])])
        
        if "resolution" in settings:
            res = settings["resolution"]
            if "x" in str(res):
                w, h = res.split("x")
                args.extend(["--resolution", f"{w},{h},dpi"])
            else:
                args.extend(["--resolution", res])
        
        if "dpi" in settings:
            args.extend(["--dpi", str(settings["dpi"])])
        
        if "fps" in settings:
            args.extend(["--fps", str(settings["fps"])])
        
        if "manufacturer" in settings:
            args.extend(["--manufacturer", settings["manufacturer"]])
        
        if "model" in settings:
            args.extend(["--model", settings["model"]])
        
        if "imei" in settings:
            args.extend(["--imei", settings["imei"]])
        
        if "phone" in settings:
            args.extend(["--pnumber", settings["phone"]])
        
        result = await self._run_ldconsole(*args)
        
        if result["returncode"] != 0:
            return self.error(result["stderr"] or "Failed to modify")
        
        return self.success({"index": index, "settings": settings, "action": "modified"})
    
    async def _install_apk(self, index: int, path: str) -> CommandResult:
        """Установить APK"""
        if not path:
            return self.error("APK path is required")
        
        result = await self._run_ldconsole("installapp", "--index", str(index), "--filename", path, timeout=120)
        
        if result["returncode"] != 0:
            return self.error(result["stderr"] or "Failed to install APK")
        
        return self.success({"index": index, "path": path, "action": "installed"})
    
    async def _uninstall_apk(self, index: int, package: str) -> CommandResult:
        """Удалить APK"""
        if not package:
            return self.error("Package name is required")
        
        result = await self._run_ldconsole("uninstallapp", "--index", str(index), "--packagename", package)
        
        if result["returncode"] != 0:
            return self.error(result["stderr"] or "Failed to uninstall")
        
        return self.success({"index": index, "package": package, "action": "uninstalled"})
    
    async def _run_app(self, index: int, package: str) -> CommandResult:
        """Запустить приложение"""
        if not package:
            return self.error("Package name is required")
        
        result = await self._run_ldconsole("runapp", "--index", str(index), "--packagename", package)
        
        if result["returncode"] != 0:
            return self.error(result["stderr"] or "Failed to run app")
        
        return self.success({"index": index, "package": package, "action": "started"})
    
    async def _kill_app(self, index: int, package: str) -> CommandResult:
        """Остановить приложение"""
        if not package:
            return self.error("Package name is required")
        
        result = await self._run_ldconsole("killapp", "--index", str(index), "--packagename", package)
        
        if result["returncode"] != 0:
            return self.error(result["stderr"] or "Failed to kill app")
        
        return self.success({"index": index, "package": package, "action": "killed"})
    
    async def _get_adb_port(self, index: int) -> CommandResult:
        """Получить ADB порт эмулятора"""
        result = await self._run_ldconsole("adb", "--index", str(index))
        
        # Парсим вывод типа "5555"
        try:
            port = int(result["stdout"].strip())
            return self.success({"index": index, "adb_port": port})
        except ValueError:
            return self.error("Failed to get ADB port")
    
    async def _backup(self, index: int, path: str) -> CommandResult:
        """Создать backup эмулятора"""
        if not path:
            path = f"backup_{index}_{int(time.time())}.ldbk"
        
        result = await self._run_ldconsole("backup", "--index", str(index), "--file", path, timeout=300)
        
        if result["returncode"] != 0:
            return self.error(result["stderr"] or "Failed to backup")
        
        return self.success({"index": index, "path": path, "action": "backed_up"})
    
    async def _restore(self, path: str, name: str) -> CommandResult:
        """Восстановить эмулятор из backup"""
        if not path:
            return self.error("Backup path is required")
        
        args = ["restore", "--file", path]
        if name:
            args.extend(["--name", name])
        
        result = await self._run_ldconsole(*args, timeout=300)
        
        if result["returncode"] != 0:
            return self.error(result["stderr"] or "Failed to restore")
        
        return self.success({"path": path, "name": name, "action": "restored"})
    
    async def _raw_command(self, args: List[str]) -> CommandResult:
        """Выполнить произвольную команду ldconsole"""
        if not args:
            return self.error("Command arguments required")
        
        result = await self._run_ldconsole(*args)
        
        return self.success({
            "stdout": result["stdout"],
            "stderr": result["stderr"],
            "returncode": result["returncode"]
        })
    
    # === Вспомогательные методы ===
    
    async def get_emulators_for_heartbeat(self) -> List[Dict[str, Any]]:
        """Получить список эмуляторов для heartbeat (упрощённый)"""
        if not self.is_available():
            return []
        
        try:
            result = await self._list_emulators()
            if result.success and result.data:
                return result.data
        except:
            pass
        
        return []
